package org.ddlsmith.oracle.impl;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;

/**
 * Small shared helpers for the schema level oracles: which namespace to introspect, how to build a
 * qualified table reference, which aspects of a schema may be compared at all, and how to turn the
 * recorded execution events into a self-contained reproducer.
 *
 * <p>Everything in here is deliberately free of engine specific knowledge: the only inputs are the
 * frozen contract ({@link DatabaseUnderTest}, {@link DDLFeatureSet},
 * {@link IntrospectionCapability}) and the intended schema.</p>
 *
 * <p>The class is public so that an oracle living outside this package (an extension oracle loaded
 * through {@link java.util.ServiceLoader}, in {@code org.ddlsmith.conformance} or in a plugin of
 * one's own) can render the same qualified table references and write the same self-contained
 * reproducers, instead of growing a second, subtly different copy of these rules. Only the members
 * an oracle needs that way are public; the rest stays package private.</p>
 */
public final class OracleSupport {

    /** Upper bound for the {@code details} of a failure; longer reports are truncated. */
    static final int MAX_DETAIL_CHARS = 12000;

    private OracleSupport() {
    }

    /* ------------------------------------------------------------------ context lookup */

    /** @return the database under test with the given name, or {@code null} */
    static DatabaseUnderTest database(OracleContext context, String databaseName) {
        for (DatabaseUnderTest database : context.getDatabases()) {
            if (database.getName().equals(databaseName)) {
                return database;
            }
        }
        return null;
    }

    /** @return the declared features of the database an event belongs to, falling back to the primary */
    static DDLFeatureSet featuresOf(OracleContext context, DatabaseUnderTest database) {
        if (database != null) {
            return database.getDialect().getFeatures();
        }
        if (!context.getDatabases().isEmpty()) {
            return context.getPrimary().getDialect().getFeatures();
        }
        return DDLFeatureSet.none();
    }

    /**
     * The schema each database was actually asked to create.
     *
     * <p>In differential runs every engine receives a schema adapted to its own dialect, so the
     * per-database schema wins over the context-wide one (which only describes the primary
     * database). Comparing a database against a schema that was never rendered for its dialect would
     * report dialect adaptation as an engine defect.</p>
     */
    public static RelationalSchema intendedSchemaFor(OracleContext context, DatabaseUnderTest database) {
        if (database != null && database.getIntendedSchema() != null) {
            return database.getIntendedSchema();
        }
        return context.getIntendedSchema();
    }

    /**
     * The namespace to introspect for one database: the schema the DDL was rendered for, or
     * {@code null} when the dialect does not qualify identifiers (in which case the DDL created the
     * objects in the engine's default namespace and asking for a named schema would be wrong).
     */
    public static String resolveSchemaName(OracleContext context, DatabaseUnderTest database) {
        if (!database.getDialect().getFeatures().supports(DDLFeature.QUALIFIED_NAMES)) {
            return null;
        }
        RelationalSchema intended = intendedSchemaFor(context, database);
        if (intended != null) {
            for (Table table : intended.getTables()) {
                if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
                    return table.getSchemaName();
                }
            }
            // The intended schema exists but none of its tables is qualified: its objects were created
            // in whatever namespace the session was pointed at. A named schema says which namespace that
            // was - a differential run names it after the namespace it prepared for this database, so
            // that creation and introspection cannot disagree. Only an anonymous schema is left to the
            // engine's default namespace, which is what an embedded engine's objects always carry.
            if (intended.getName() != null && !intended.getName().isBlank()) {
                return intended.getName();
            }
            return database.getDialect().getDefaultSchemaName();
        }
        if (context.getOptions() != null && context.getOptions().getSchemaName() != null) {
            return context.getOptions().getSchemaName();
        }
        return database.getDialect().getDefaultSchemaName();
    }

    /** Quotes an identifier with the database's dialect. */
    static String quote(DatabaseUnderTest database, String identifier) {
        return database.getDialect().quote(identifier);
    }

    /** @return {@code schema.name} when the dialect qualifies identifiers, else just {@code name} */
    public static String qualifiedName(DatabaseUnderTest database, String schemaName, String name) {
        String quoted = quote(database, name);
        if (schemaName == null || schemaName.isBlank()
                || !database.getDialect().getFeatures().supports(DDLFeature.QUALIFIED_NAMES)) {
            return quoted;
        }
        return quote(database, schemaName) + "." + quoted;
    }

    /** @return a qualified reference to a table of the intended schema, quoted for this database */
    public static String tableReference(OracleContext context, DatabaseUnderTest database, Table table) {
        String schemaName = table.getSchemaName() != null ? table.getSchemaName()
                : resolveSchemaName(context, database);
        return qualifiedName(database, schemaName, table.getName());
    }

    /* ------------------------------------------------------------------ capabilities */

    /**
     * The aspects of a schema that may be compared for one database: what the introspector can read
     * <em>and</em> what the dialect claims to be able to render.
     *
     * <p>Intersecting with the dialect's features keeps a construct that the dialect never rendered
     * (because it does not support it) from being reported as an introspection gap, and it keeps a
     * construct that the dialect rendered but the engine cannot expose from being blamed on the
     * introspector.</p>
     */
    static Set<IntrospectionCapability> effectiveCapabilities(DatabaseUnderTest database) {
        Set<IntrospectionCapability> capabilities = copyOf(database.getIntrospector().getCapabilities());
        capabilities.retainAll(dialectCapabilities(database.getDialect().getFeatures()));
        return capabilities;
    }

    /**
     * The aspects of a schema that are comparable for two databases: the intersection of the two
     * introspectors' capabilities, further reduced by the features both dialects declare.
     *
     * <p>Reducing by the dialects is what makes the differential comparison fair: when one dialect
     * cannot render (say) a column default, the generator drops that default for that engine, and
     * the resulting asymmetry is an artifact of feature adaptation rather than an engine
     * disagreement.</p>
     */
    static Set<IntrospectionCapability> sharedCapabilities(DatabaseUnderTest left, DatabaseUnderTest right) {
        Set<IntrospectionCapability> capabilities = copyOf(left.getIntrospector().getCapabilities());
        capabilities.retainAll(right.getIntrospector().getCapabilities());
        capabilities.retainAll(dialectCapabilities(left.getDialect().getFeatures()));
        capabilities.retainAll(dialectCapabilities(right.getDialect().getFeatures()));
        return capabilities;
    }

    /**
     * Maps the features a dialect declares to the aspects of a schema that can then be compared.
     * A feature that is absent here simply means "do not compare this aspect": erasing more can only
     * remove differences, never invent them.
     */
    static Set<IntrospectionCapability> dialectCapabilities(DDLFeatureSet features) {
        Set<IntrospectionCapability> capabilities = EnumSet.noneOf(IntrospectionCapability.class);
        // every dialect renders columns and their types, and every engine can say whether a table exists
        capabilities.add(IntrospectionCapability.COLUMNS);
        capabilities.add(IntrospectionCapability.COLUMN_TYPES);
        capabilities.add(IntrospectionCapability.NULLABILITY);
        capabilities.add(IntrospectionCapability.TABLE_EXISTENCE);
        // a primary key can be rendered inline, as a table level clause, or through ALTER TABLE
        capabilities.add(IntrospectionCapability.PRIMARY_KEYS);
        if (supportsAny(features, DDLFeature.DEFAULT_LITERAL, DDLFeature.DEFAULT_EXPRESSION,
                DDLFeature.DEFAULT_CURRENT_TIMESTAMP)) {
            capabilities.add(IntrospectionCapability.DEFAULTS);
        }
        if (supportsAny(features, DDLFeature.IDENTITY_COLUMN, DDLFeature.AUTO_INCREMENT, DDLFeature.SERIAL)) {
            capabilities.add(IntrospectionCapability.IDENTITY);
        }
        if (supportsAny(features, DDLFeature.GENERATED_COLUMN, DDLFeature.GENERATED_STORED,
                DDLFeature.GENERATED_VIRTUAL)) {
            capabilities.add(IntrospectionCapability.GENERATED_COLUMNS);
        }
        if (supportsAny(features, DDLFeature.TABLE_LEVEL_FOREIGN_KEY, DDLFeature.INLINE_FOREIGN_KEY,
                DDLFeature.COMPOSITE_FOREIGN_KEY, DDLFeature.SELF_REFERENTIAL_FK, DDLFeature.ALTER_ADD_FOREIGN_KEY)) {
            capabilities.add(IntrospectionCapability.FOREIGN_KEYS);
        }
        if (supportsAny(features, DDLFeature.FK_ON_DELETE, DDLFeature.FK_ON_UPDATE, DDLFeature.FK_CASCADE,
                DDLFeature.FK_SET_NULL, DDLFeature.FK_SET_DEFAULT, DDLFeature.FK_RESTRICT)) {
            capabilities.add(IntrospectionCapability.FOREIGN_KEY_ACTIONS);
        }
        if (supportsAny(features, DDLFeature.COMPOSITE_UNIQUE, DDLFeature.NAMED_UNIQUE, DDLFeature.INLINE_UNIQUE,
                DDLFeature.ALTER_ADD_UNIQUE)) {
            capabilities.add(IntrospectionCapability.UNIQUE_CONSTRAINTS);
        }
        if (supportsAny(features, DDLFeature.NAMED_CHECK, DDLFeature.INLINE_CHECK, DDLFeature.ALTER_ADD_CHECK,
                DDLFeature.ENUM_AS_CHECK)) {
            capabilities.add(IntrospectionCapability.CHECK_CONSTRAINTS);
        }
        if (supportsAny(features, DDLFeature.CREATE_INDEX, DDLFeature.CREATE_UNIQUE_INDEX)) {
            capabilities.add(IntrospectionCapability.INDEXES);
            capabilities.add(IntrospectionCapability.INDEX_COLUMNS);
        }
        if (features.supports(DDLFeature.CREATE_UNIQUE_INDEX)) {
            capabilities.add(IntrospectionCapability.INDEX_UNIQUENESS);
        }
        if (supportsAny(features, DDLFeature.CREATE_VIEW, DDLFeature.CREATE_OR_REPLACE_VIEW,
                DDLFeature.MATERIALIZED_VIEW)) {
            capabilities.add(IntrospectionCapability.VIEWS);
        }
        if (supportsAny(features, DDLFeature.CREATE_VIEW, DDLFeature.CREATE_OR_REPLACE_VIEW)) {
            capabilities.add(IntrospectionCapability.VIEW_DEFINITIONS);
        }
        if (features.supports(DDLFeature.CREATE_SEQUENCE)) {
            capabilities.add(IntrospectionCapability.SEQUENCES);
        }
        if (supportsAny(features, DDLFeature.TABLE_COMMENT, DDLFeature.COLUMN_COMMENT)) {
            capabilities.add(IntrospectionCapability.COMMENTS);
        }
        return capabilities;
    }

    private static boolean supportsAny(DDLFeatureSet features, DDLFeature... candidates) {
        for (DDLFeature candidate : candidates) {
            if (features.supports(candidate)) {
                return true;
            }
        }
        return false;
    }

    static Set<IntrospectionCapability> copyOf(Set<IntrospectionCapability> capabilities) {
        Set<IntrospectionCapability> copy = EnumSet.noneOf(IntrospectionCapability.class);
        if (capabilities != null) {
            copy.addAll(capabilities);
        }
        return copy;
    }

    /** @return the capabilities in a stable, readable order */
    static String describe(Set<IntrospectionCapability> capabilities) {
        return new TreeSet<>(capabilities).toString();
    }

    /* ------------------------------------------------------------------ comparison artifacts */

    /**
     * True when a referential action difference is only the spelling of "leave the parent row
     * alone".
     *
     * <p>DDLSmith never renders an {@code ON DELETE}/{@code ON UPDATE} clause for
     * {@link org.ddlsmith.common.schema.ReferentialAction#NO_ACTION} (see the dialect's
     * {@code renderReferentialActions}), so NO_ACTION means "no clause was written" and the engine
     * answers with its own default: H2 reports {@code RESTRICT} for a foreign key declared with no
     * clause, with {@code ON DELETE NO ACTION} and with {@code ON DELETE RESTRICT} alike, while
     * SQLite and PostgreSQL report {@code NO ACTION}. Comparing the two spellings would report every
     * foreign key without an explicit action as a defect, so the schema round trip oracle and the
     * differential oracle both ignore this difference.</p>
     */
    static boolean isDefaultReferentialActionSpelling(org.ddlsmith.introspection.SchemaDifference difference) {
        org.ddlsmith.introspection.SchemaDifference.Kind kind = difference.getKind();
        if (kind != org.ddlsmith.introspection.SchemaDifference.Kind.FOREIGN_KEY_ON_DELETE
                && kind != org.ddlsmith.introspection.SchemaDifference.Kind.FOREIGN_KEY_ON_UPDATE) {
            return false;
        }
        String expected = difference.getExpected();
        String actual = difference.getActual();
        return isNoActionSpelling(expected) && isNoActionSpelling(actual) && !expected.equals(actual);
    }

    private static boolean isNoActionSpelling(String action) {
        return "NO_ACTION".equals(action) || "RESTRICT".equals(action);
    }

    /* ------------------------------------------------------------------ reporting */

    /** The statements executed immediately before an event, oldest first. */
    static List<ExecutionEvent> preceding(OracleContext context, ExecutionEvent event, int count) {
        List<ExecutionEvent> events = context.getExecutionEvents(event.getDatabaseName());
        List<ExecutionEvent> preceding = new ArrayList<>();
        for (ExecutionEvent candidate : events) {
            if (candidate == event) {
                break;
            }
            preceding.add(candidate);
        }
        if (preceding.size() > count) {
            return new ArrayList<>(preceding.subList(preceding.size() - count, preceding.size()));
        }
        return preceding;
    }

    /**
     * The statements executed on one database, one per line, in execution order.
     *
     * @param marked the event the report is about (marked with a trailing comment), or {@code null};
     *        when it is set the listing stops at that statement, so the reproducer is exactly the
     *        prefix that leads to the failure
     */
    public static String reproducer(OracleContext context, String databaseName, ExecutionEvent marked) {
        StringBuilder sb = new StringBuilder();
        for (ExecutionEvent event : context.getExecutionEvents(databaseName)) {
            sb.append(oneLine(event.getSql()));
            if (event == marked) {
                sb.append("  -- <-- FAILS HERE (").append(FeaturePurpose.base(event.getPurpose())).append(')');
            } else if (!event.isSucceeded()) {
                sb.append("  -- (failed earlier: ").append(oneLine(event.getErrorMessage())).append(')');
            }
            sb.append('\n');
            if (event == marked) {
                break;
            }
        }
        return sb.toString();
    }

    /** Collapses a statement (or an error message) onto a single line. */
    static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Keeps a failure report readable by bounding its length. */
    public static String truncate(String text) {
        if (text == null || text.length() <= MAX_DETAIL_CHARS) {
            return text;
        }
        return text.substring(0, MAX_DETAIL_CHARS) + "\n... details truncated after " + MAX_DETAIL_CHARS
                + " characters\n";
    }

    /** @return {@code [a, b]} for a list of features, used in failure details */
    static String describeFeatures(List<DDLFeature> features) {
        if (features.isEmpty()) {
            return "<none>";
        }
        Set<String> names = new LinkedHashSet<>();
        for (DDLFeature feature : features) {
            names.add(feature.name());
        }
        return names.toString();
    }
}
