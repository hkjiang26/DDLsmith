package org.ddlsmith.oracle.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;
import org.ddlsmith.introspection.SchemaNormalizer;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.TestOracle;

/**
 * Compares the schema DDLSmith intended to create with the schema the engine reports afterwards.
 *
 * <p>For every database the oracle introspects the catalog, erases from the intended schema
 * everything that cannot be observed on that database, and computes the remaining differences with
 * {@link SchemaNormalizer#diff(RelationalSchema, RelationalSchema, boolean, boolean)}.</p>
 *
 * <h2>What "cannot be observed" means</h2>
 *
 * <p>The erased aspects are the intersection of two sets:</p>
 * <ul>
 *   <li>the {@link IntrospectionCapability capabilities} the engine's introspector declares &mdash;
 *       an engine whose catalog does not expose referential actions must not be blamed for
 *       "losing" them;</li>
 *   <li>the {@link DDLFeature features} the dialect declares &mdash; a dialect that cannot render a
 *       construct never emitted it, so a missing construct is not an engine defect either.</li>
 * </ul>
 *
 * <p>Referential actions are only compared when the resulting capability set contains
 * {@link IntrospectionCapability#FOREIGN_KEY_ACTIONS}, and types are compared strictly (both sides
 * went through the same engine's type mapper, so an exact match is required).</p>
 *
 * <h2>Why there is a filter</h2>
 *
 * <p>A few differences are artifacts of the comparison itself rather than observations about the
 * engine; {@link #filteredRule} lists them one by one, each with the {@link DDLFeatureSet} check
 * that justifies it. Two families exist:</p>
 * <ul>
 *   <li><b>the dialect cannot express the construct</b> &mdash; it was never rendered, so its absence
 *       says nothing about the engine (rules {@code *-not-creatable/-expressible});</li>
 *   <li><b>the introspector cannot report the aspect</b> &mdash; {@link SchemaNormalizer#erase} does
 *       not erase every aspect (column types, nullability and index expressions are always copied),
 *       so the oracle removes those differences itself (rules {@code *-not-introspectable}).</li>
 * </ul>
 *
 * <p>Table level differences are never filtered: a table that is absent, or a table the engine
 * created although DDLSmith did not ask for it ({@code UNEXPECTED_TABLE}), is always a finding.</p>
 *
 * <p>At most one failure is reported per database (the first database with a mismatch wins), and the
 * report is bounded by {@link OracleSupport#MAX_DETAIL_CHARS}.</p>
 */
public class SchemaRoundTripOracle implements TestOracle {

    /** The name used to select this oracle on the command line. */
    public static final String NAME = "schema-round-trip";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "compares the introspected schema of every database with the schema DDLSmith intended";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        for (DatabaseUnderTest database : context.getDatabases()) {
            RelationalSchema intended = OracleSupport.intendedSchemaFor(context, database);
            if (intended == null) {
                continue;
            }
            Set<IntrospectionCapability> capabilities = OracleSupport.effectiveCapabilities(database);
            String schemaName = OracleSupport.resolveSchemaName(context, database);
            RelationalSchema erasedIntended = SchemaNormalizer.erase(intended, capabilities);
            RelationalSchema actual = database.getIntrospector().introspect(database.getConnection(), schemaName);
            List<SchemaDifference> differences = SchemaNormalizer.diff(erasedIntended, actual,
                    SchemaNormalizer.preservesExactTypes(database.getDialect().getFeatures()),
                    capabilities.contains(IntrospectionCapability.FOREIGN_KEY_ACTIONS));
            FilterOutcome outcome = filter(differences, database.getDialect().getFeatures(), capabilities);
            if (!outcome.kept.isEmpty()) {
                return Optional.of(mismatch(context, database, erasedIntended, actual, outcome, capabilities,
                        schemaName));
            }
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------ filtering */

    /** The differences that survive the filter, plus the bookkeeping of what was removed and why. */
    private static final class FilterOutcome {
        private final List<SchemaDifference> kept = new ArrayList<>();
        private final Map<String, Integer> ignoredByRule = new LinkedHashMap<>();

        private int ignoredCount() {
            int total = 0;
            for (Integer count : ignoredByRule.values()) {
                total += count;
            }
            return total;
        }
    }

    private static FilterOutcome filter(List<SchemaDifference> differences, DDLFeatureSet features,
            Set<IntrospectionCapability> capabilities) {
        FilterOutcome outcome = new FilterOutcome();
        for (SchemaDifference difference : differences) {
            String rule = filteredRule(difference, features, capabilities);
            if (rule == null) {
                outcome.kept.add(difference);
            } else {
                outcome.ignoredByRule.merge(rule, 1, Integer::sum);
            }
        }
        return outcome;
    }

    /**
     * The explicit list of differences that are artifacts of the comparison rather than observations
     * about the engine.
     *
     * @return the name of the rule that discards the difference, or {@code null} to keep it
     */
    private static String filteredRule(SchemaDifference difference, DDLFeatureSet features,
            Set<IntrospectionCapability> capabilities) {
        SchemaDifference.Kind kind = difference.getKind();

        // (1) The dialect cannot create secondary indexes at all, so an index of the intended schema
        //     was never rendered and cannot be missing in the database.
        if (kind == SchemaDifference.Kind.MISSING_INDEX && !features.supports(DDLFeature.CREATE_INDEX)) {
            return "missing-index: dialect does not support CREATE_INDEX";
        }

        // (2) The dialect cannot express a CHECK constraint in any of the three ways DDLSmith knows
        //     (named, inline, ALTER TABLE ADD), and the catalog cannot report checks either. Both
        //     halves are required: if the engine can report checks but the dialect cannot create them,
        //     the absence is explained by the dialect, and if the dialect can create them the absence
        //     is a real finding.
        if (kind == SchemaDifference.Kind.MISSING_CHECK_CONSTRAINT
                && !supportsAny(features, DDLFeature.NAMED_CHECK, DDLFeature.INLINE_CHECK,
                        DDLFeature.ALTER_ADD_CHECK)
                && !capabilities.contains(IntrospectionCapability.CHECK_CONSTRAINTS)) {
            return "missing-check: neither the dialect nor the introspector supports checks";
        }

        // (3) The dialect cannot create views, so no view was ever rendered.
        if (kind == SchemaDifference.Kind.MISSING_VIEW && !features.supports(DDLFeature.CREATE_VIEW)) {
            return "missing-view: dialect does not support CREATE_VIEW";
        }

        // (4) The dialect has no way to express an identity/auto-increment column, so a column that
        //     the intended schema marks as identity was rendered as a plain column.
        if (kind == SchemaDifference.Kind.COLUMN_IDENTITY && !supportsAny(features, DDLFeature.IDENTITY_COLUMN,
                DDLFeature.AUTO_INCREMENT, DDLFeature.SERIAL)) {
            return "column-identity: dialect cannot express identity columns";
        }

        // (5) The dialect cannot express a foreign key at all (neither table level nor inline), so the
        //     foreign keys of the intended schema were never rendered.
        if (kind == SchemaDifference.Kind.MISSING_FOREIGN_KEY
                && !supportsAny(features, DDLFeature.TABLE_LEVEL_FOREIGN_KEY, DDLFeature.INLINE_FOREIGN_KEY)) {
            return "missing-foreign-key: dialect cannot express foreign keys";
        }

        // (6) The dialect cannot express a column default (no literal and no expression form), so the
        //     default was dropped when the schema was rendered for this engine.
        if (kind == SchemaDifference.Kind.COLUMN_DEFAULT
                && !supportsAny(features, DDLFeature.DEFAULT_LITERAL, DDLFeature.DEFAULT_EXPRESSION)) {
            return "column-default: dialect cannot express column defaults";
        }

        // (7) The introspector does not report column types, and SchemaNormalizer.erase always copies
        //     the intended type, so a "different" type is the introspector's placeholder, not the
        //     engine's answer.
        if (kind == SchemaDifference.Kind.COLUMN_TYPE
                && !capabilities.contains(IntrospectionCapability.COLUMN_TYPES)) {
            return "column-type: introspector cannot report column types";
        }

        // (8) Same for nullability: erase() copies it unconditionally, so an introspector that cannot
        //     tell NULL from NOT NULL would otherwise report every column as wrong.
        if (kind == SchemaDifference.Kind.COLUMN_NULLABILITY
                && !capabilities.contains(IntrospectionCapability.NULLABILITY)) {
            return "column-nullability: introspector cannot report nullability";
        }

        // (9) erase() drops the default when the introspector cannot report defaults, which turns
        //     "intended has a default, actual is unknown" into a difference. The introspector's silence
        //     is not evidence about the engine.
        if (kind == SchemaDifference.Kind.COLUMN_DEFAULT
                && !capabilities.contains(IntrospectionCapability.DEFAULTS)) {
            return "column-default: introspector cannot report defaults";
        }

        // (10) erase() resets the identity flag when the introspector cannot report identity.
        if (kind == SchemaDifference.Kind.COLUMN_IDENTITY
                && !capabilities.contains(IntrospectionCapability.IDENTITY)) {
            return "column-identity: introspector cannot report identity";
        }

        // (11) erase() resets generated columns when the introspector cannot report them.
        if (kind == SchemaDifference.Kind.COLUMN_GENERATED
                && !capabilities.contains(IntrospectionCapability.GENERATED_COLUMNS)) {
            return "column-generated: introspector cannot report generated columns";
        }

        // (12) An introspector that cannot read the check constraints cannot contradict the intended
        //      ones; the intended checks were erased, so this only guards against a future change of
        //      erase().
        if (kind == SchemaDifference.Kind.MISSING_CHECK_CONSTRAINT
                && !capabilities.contains(IntrospectionCapability.CHECK_CONSTRAINTS)) {
            return "missing-check: introspector cannot report checks";
        }

        // (13) Referential actions that the introspector cannot read were erased from the intended
        //      side (diff() is called with compareReferentialActions=false in that case); this rule
        //      makes the guarantee independent of the caller.
        if ((kind == SchemaDifference.Kind.FOREIGN_KEY_ON_DELETE
                || kind == SchemaDifference.Kind.FOREIGN_KEY_ON_UPDATE)
                && !capabilities.contains(IntrospectionCapability.FOREIGN_KEY_ACTIONS)) {
            return "foreign-key-actions: introspector cannot report referential actions";
        }

        // (14) NO_ACTION is not an action DDLSmith ever renders: a dialect emits ON DELETE/ON UPDATE
        //      only when the action differs from NO_ACTION (see the dialect's renderReferentialActions),
        //      so a NO_ACTION on the intended side means "no clause was written at all" and what the
        //      engine reports back is its own default spelling of "leave the parent row alone" - H2
        //      answers RESTRICT for a foreign key declared with no clause, with ON DELETE NO ACTION and
        //      with ON DELETE RESTRICT alike, while SQLite and PostgreSQL answer NO ACTION. Comparing
        //      those two spellings would report every foreign key without an explicit action as a
        //      defect, so the difference is ignored while the dialect is able to render referential
        //      actions at all (when it is not, the erase step has already dropped the comparison).
        if (OracleSupport.isDefaultReferentialActionSpelling(difference)
                && supportsAny(features, DDLFeature.FK_ON_DELETE, DDLFeature.FK_ON_UPDATE)) {
            return "foreign-key-action: the intended action is NO_ACTION, so no clause was rendered and the"
                    + " engine reported its default";
        }

        // (14) Foreign keys that the introspector cannot read were erased from the intended side, so
        //      every foreign key difference is an artifact of the introspector.
        if ((kind == SchemaDifference.Kind.MISSING_FOREIGN_KEY
                || kind == SchemaDifference.Kind.FOREIGN_KEY_COLUMNS
                || kind == SchemaDifference.Kind.FOREIGN_KEY_TARGET
                || kind == SchemaDifference.Kind.UNEXPECTED_FOREIGN_KEY)
                && !capabilities.contains(IntrospectionCapability.FOREIGN_KEYS)) {
            return "foreign-keys: introspector cannot report foreign keys";
        }

        // (15) Primary keys the introspector cannot read were erased from the intended side.
        if ((kind == SchemaDifference.Kind.MISSING_PRIMARY_KEY
                || kind == SchemaDifference.Kind.PRIMARY_KEY_COLUMNS
                || kind == SchemaDifference.Kind.UNEXPECTED_PRIMARY_KEY)
                && !capabilities.contains(IntrospectionCapability.PRIMARY_KEYS)) {
            return "primary-keys: introspector cannot report primary keys";
        }

        // (16) Unique constraints the introspector cannot read were erased from the intended side.
        if (kind == SchemaDifference.Kind.MISSING_UNIQUE_CONSTRAINT
                && !capabilities.contains(IntrospectionCapability.UNIQUE_CONSTRAINTS)) {
            return "unique-constraints: introspector cannot report unique constraints";
        }

        // (17) Indexes the introspector cannot read were erased from the intended side.
        if ((kind == SchemaDifference.Kind.MISSING_INDEX
                || kind == SchemaDifference.Kind.INDEX_UNIQUENESS
                || kind == SchemaDifference.Kind.UNEXPECTED_INDEX)
                && !capabilities.contains(IntrospectionCapability.INDEXES)) {
            return "indexes: introspector cannot report indexes";
        }

        // (18) Index uniqueness is a separate capability: an introspector may list indexes without
        //      knowing which of them are unique.
        if (kind == SchemaDifference.Kind.INDEX_UNIQUENESS
                && !capabilities.contains(IntrospectionCapability.INDEX_UNIQUENESS)) {
            return "index-uniqueness: introspector cannot report index uniqueness";
        }

        // (19) erase() always copies the expressions of an index, so an expression index would be
        //      compared by expression text even though the introspector never claimed to report
        //      expressions.
        if ((kind == SchemaDifference.Kind.MISSING_INDEX || kind == SchemaDifference.Kind.INDEX_UNIQUENESS)
                && difference.getPath() != null && difference.getPath().contains("idx(expr:")
                && !capabilities.contains(IntrospectionCapability.EXPRESSION_INDEXES)) {
            return "expression-indexes: introspector cannot report index expressions";
        }

        // (20) Views: an introspector that cannot see views at all cannot contradict them, and one
        //      that cannot read their definitions cannot be compared on the definition text.
        if (kind == SchemaDifference.Kind.MISSING_VIEW
                && !capabilities.contains(IntrospectionCapability.VIEWS)) {
            return "missing-view: introspector cannot report views";
        }
        if (kind == SchemaDifference.Kind.VIEW_DEFINITION
                && !capabilities.contains(IntrospectionCapability.VIEW_DEFINITIONS)) {
            return "view-definition: introspector cannot report view definitions";
        }

        // (21) Columns that the introspector cannot read: the whole comparison is meaningless then,
        //      so the column level differences are removed as artifacts.
        if ((kind == SchemaDifference.Kind.MISSING_COLUMN
                || kind == SchemaDifference.Kind.UNEXPECTED_COLUMN
                || kind == SchemaDifference.Kind.COLUMN_ORDER)
                && !capabilities.contains(IntrospectionCapability.COLUMNS)) {
            return "columns: introspector cannot report columns";
        }

        // No rule applies: MISSING_TABLE and UNEXPECTED_TABLE in particular are always findings,
        // because a table the engine did not create (or created unasked) is observable behaviour that
        // no capability check can excuse.
        return null;
    }

    private static boolean supportsAny(DDLFeatureSet features, DDLFeature... candidates) {
        for (DDLFeature candidate : candidates) {
            if (features.supports(candidate)) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ reporting */

    private OracleFailure mismatch(OracleContext context, DatabaseUnderTest database, RelationalSchema intended,
            RelationalSchema actual, FilterOutcome outcome, Set<IntrospectionCapability> capabilities,
            String schemaName) {
        SchemaDifference first = outcome.kept.get(0);
        StringBuilder details = new StringBuilder();
        details.append("The schema the engine reports differs from the schema DDLSmith intended to create.\n\n");
        details.append("Database   : ").append(database.getFullName()).append('\n');
        details.append("Dialect    : ").append(database.getDialect().getName()).append('\n');
        details.append("Schema     : ").append(schemaName == null ? "<engine default>" : schemaName).append('\n');
        details.append("Compared   : ").append(OracleSupport.describe(capabilities)).append('\n');
        details.append("\nDifferences (").append(outcome.kept.size()).append("):\n");
        details.append(SchemaNormalizer.render(outcome.kept));
        if (!outcome.ignoredByRule.isEmpty()) {
            details.append("\nFiltered as known comparison artifacts (").append(outcome.ignoredCount()).append("):\n");
            for (Map.Entry<String, Integer> entry : outcome.ignoredByRule.entrySet()) {
                details.append("  ").append(entry.getValue()).append(" x ").append(entry.getKey()).append('\n');
            }
        }
        details.append("\nIntended schema (after erasing what this database cannot expose):\n");
        details.append(SchemaPrinter.describe(intended));
        details.append("\nIntrospected schema:\n");
        details.append(SchemaPrinter.describe(actual));

        String title = "schema mismatch on " + database.getName() + ": " + first.getKind() + " at " + first.getPath();
        return new OracleFailure(FailureKind.SCHEMA_MISMATCH, getName(), title,
                OracleSupport.truncate(details.toString()),
                OracleSupport.reproducer(context, database.getName(), null), context.getSeed(),
                database.getFullName(), null);
    }
}
