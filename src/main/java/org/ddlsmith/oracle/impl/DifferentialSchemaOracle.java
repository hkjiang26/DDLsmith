package org.ddlsmith.oracle.impl;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.ddlsmith.common.query.QueryResult;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
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
 * Compares the databases of a differential run with each other.
 *
 * <p>This oracle is only applicable when the run has at least two databases; with fewer it returns
 * {@link Optional#empty()} instead of inventing a failure. It performs two independent checks:</p>
 *
 * <ol>
 *   <li><b>Structural</b>: every pair of databases is introspected and compared against the other
 *       (never against the intended schema &mdash; that is the round trip oracle's job). Both sides
 *       are erased with the intersection of the two introspectors' capabilities and of the two
 *       dialects' features, and the comparison runs with {@code strictTypes = false}, because two
 *       engines necessarily spell the same type differently ({@code INTEGER} vs {@code INT},
 *       {@code VARCHAR(20)} vs {@code CHARACTER VARYING(20)}).</li>
 *   <li><b>Semantic</b>: an engine independent check of observable behaviour. For every table of the
 *       intended schema, {@code SELECT COUNT(*) FROM <table>} must return 0 on every database (a
 *       schema that was just created contains no rows, and an engine that inserts rows on its own is
 *       broken), and for every pair of databases {@code SELECT <pk columns> FROM <table> ORDER BY
 *       <pk columns>} must return the same row set. A table that one engine failed to create shows up
 *       here as a query error, which is exactly the "engine did not create the table" case. The table
 *       list is taken from the context wide intended schema, which is safe because adapting a schema
 *       to a dialect drops unsupported constructs but never a whole table.</li>
 * </ol>
 *
 * <p>Identifiers are always quoted with {@code database.getDialect().quote(name)}, so the generated
 * SQL is valid for engines that need quoting and unchanged for engines that do not.</p>
 */
public class DifferentialSchemaOracle implements TestOracle {

    /** The name used to select this oracle on the command line. */
    public static final String NAME = "differential-schema";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "compares the schemas and the contents of two or more databases against each other";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        List<DatabaseUnderTest> databases = context.getDatabases();
        if (databases.size() < 2) {
            // A single database run has nothing to compare against; the oracle is not applicable.
            return Optional.empty();
        }
        Map<String, RelationalSchema> introspected = new LinkedHashMap<>();
        Map<String, String> schemaNames = new LinkedHashMap<>();
        for (DatabaseUnderTest database : databases) {
            String schemaName = OracleSupport.resolveSchemaName(context, database);
            schemaNames.put(database.getName(), schemaName);
            introspected.put(database.getName(),
                    database.getIntrospector().introspect(database.getConnection(), schemaName));
        }
        for (int i = 0; i < databases.size(); i++) {
            for (int j = i + 1; j < databases.size(); j++) {
                DatabaseUnderTest left = databases.get(i);
                DatabaseUnderTest right = databases.get(j);
                Set<IntrospectionCapability> capabilities = OracleSupport.sharedCapabilities(left, right);
                RelationalSchema erasedLeft = SchemaNormalizer.erase(introspected.get(left.getName()), capabilities);
                RelationalSchema erasedRight = SchemaNormalizer.erase(introspected.get(right.getName()), capabilities);
                List<SchemaDifference> differences = SchemaNormalizer.diff(erasedLeft, erasedRight, false, true);
                FilterOutcome outcome = filter(differences, left, right);
                if (!outcome.kept.isEmpty()) {
                    return Optional.of(structuralFailure(context, left, right, erasedLeft, erasedRight, outcome,
                            capabilities, schemaNames));
                }
            }
        }
        return semanticCheck(context, databases, introspected);
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

    /**
     * Removes the differences that are known artifacts of comparing two engines rather than
     * disagreements about behaviour. There is exactly one such rule today, and it is shared with the
     * schema round trip oracle ({@link OracleSupport#isDefaultReferentialActionSpelling}):
     *
     * <ul>
     *   <li><b>the spelling of "no referential action"</b>: DDLSmith renders an {@code ON DELETE} /
     *       {@code ON UPDATE} clause only for an action other than {@code NO_ACTION}, so the engines
     *       report their own default back &mdash; H2 says {@code RESTRICT}, SQLite and PostgreSQL say
     *       {@code NO ACTION}. The two spellings describe the same rule, so reporting them would flag
     *       every foreign key without an explicit action. The rule is only applied while at least one
     *       of the two dialects declares {@code FK_ON_DELETE}/{@code FK_ON_UPDATE}, because without
     *       those features no clause can be rendered in the first place.</li>
     * </ul>
     */
    private static FilterOutcome filter(List<SchemaDifference> differences, DatabaseUnderTest left,
            DatabaseUnderTest right) {
        FilterOutcome outcome = new FilterOutcome();
        boolean rendersActions = supportsAny(left.getDialect().getFeatures())
                || supportsAny(right.getDialect().getFeatures());
        for (SchemaDifference difference : differences) {
            if (rendersActions && OracleSupport.isDefaultReferentialActionSpelling(difference)) {
                outcome.ignoredByRule.merge(
                        "referential-action-spelling: NO_ACTION means no clause was rendered, so each engine"
                                + " reported its own default",
                        1, Integer::sum);
            } else {
                outcome.kept.add(difference);
            }
        }
        return outcome;
    }

    private static boolean supportsAny(DDLFeatureSet features) {
        return features.supports(DDLFeature.FK_ON_DELETE) || features.supports(DDLFeature.FK_ON_UPDATE);
    }

    /* ------------------------------------------------------------------ structural check */

    private OracleFailure structuralFailure(OracleContext context, DatabaseUnderTest left, DatabaseUnderTest right,
            RelationalSchema erasedLeft, RelationalSchema erasedRight, FilterOutcome outcome,
            Set<IntrospectionCapability> capabilities, Map<String, String> schemaNames) {
        List<SchemaDifference> differences = outcome.kept;
        SchemaDifference first = differences.get(0);
        StringBuilder details = new StringBuilder();
        details.append("Two databases disagree about the same schema.\n\n");
        details.append("Left       : ").append(left.getFullName()).append(" (dialect ")
                .append(left.getDialect().getName()).append(", schema ")
                .append(schemaNames.get(left.getName()) == null ? "<default>" : schemaNames.get(left.getName()))
                .append(")\n");
        details.append("Right      : ").append(right.getFullName()).append(" (dialect ")
                .append(right.getDialect().getName()).append(", schema ")
                .append(schemaNames.get(right.getName()) == null ? "<default>" : schemaNames.get(right.getName()))
                .append(")\n");
        details.append("Compared   : ").append(OracleSupport.describe(capabilities)).append('\n');
        details.append("Types      : compared by category, not by engine specific type name\n");
        details.append("\nDifferences (").append(differences.size()).append(", left vs right):\n");
        details.append(SchemaNormalizer.render(differences));
        if (!outcome.ignoredByRule.isEmpty()) {
            details.append("\nFiltered as known comparison artifacts (").append(outcome.ignoredCount()).append("):\n");
            for (Map.Entry<String, Integer> entry : outcome.ignoredByRule.entrySet()) {
                details.append("  ").append(entry.getValue()).append(" x ").append(entry.getKey()).append('\n');
            }
        }
        details.append("\nIntended schema of the left database:\n");
        details.append(SchemaPrinter.describe(OracleSupport.intendedSchemaFor(context, left)));
        details.append("\nIntended schema of the right database:\n");
        details.append(SchemaPrinter.describe(OracleSupport.intendedSchemaFor(context, right)));
        details.append("\nLeft introspection:\n");
        details.append(SchemaPrinter.describe(erasedLeft));
        details.append("\nRight introspection:\n");
        details.append(SchemaPrinter.describe(erasedRight));

        String title = "differential mismatch between " + left.getName() + " and " + right.getName() + ": "
                + first.getKind() + " at " + first.getPath();
        return new OracleFailure(FailureKind.DIFFERENTIAL, getName(), title,
                OracleSupport.truncate(details.toString()),
                reproducerFor(context, left, right), context.getSeed(),
                left.getFullName() + " vs " + right.getFullName(), null);
    }

    /* ------------------------------------------------------------------ semantic check */

    private Optional<OracleFailure> semanticCheck(OracleContext context, List<DatabaseUnderTest> databases,
            Map<String, RelationalSchema> introspected) {
        RelationalSchema intended = context.getIntendedSchema();
        if (intended == null) {
            return Optional.empty();
        }
        for (Table table : intended.getTables()) {
            Map<String, String> counts = new LinkedHashMap<>();
            Map<String, String> countSql = new LinkedHashMap<>();
            for (DatabaseUnderTest database : databases) {
                String sql = "SELECT COUNT(*) FROM " + OracleSupport.tableReference(context, database, table);
                countSql.put(database.getName(), sql);
                counts.put(database.getName(), count(database, sql));
            }
            if (disagrees(counts)) {
                return Optional.of(semanticFailure(context, table, "row count", counts, countSql,
                        "the databases disagree about the number of rows of a table that was just created",
                        databases, introspected));
            }
            for (Map.Entry<String, String> entry : counts.entrySet()) {
                if (!entry.getValue().equals("0")) {
                    return Optional.of(semanticFailure(context, table, "row count", counts, countSql,
                            "a table that was just created is not empty on " + entry.getKey() + " ("
                                    + entry.getValue() + " rows); an engine must not insert rows by itself",
                            databases, introspected));
                }
            }

            List<String> primaryKey = primaryKeyColumns(table);
            if (primaryKey.isEmpty()) {
                continue;
            }
            Map<String, List<String>> rowSets = new LinkedHashMap<>();
            Map<String, String> rowSql = new LinkedHashMap<>();
            for (DatabaseUnderTest database : databases) {
                String columns = quotedList(database, primaryKey);
                String sql = "SELECT " + columns + " FROM " + OracleSupport.tableReference(context, database, table)
                        + " ORDER BY " + columns;
                rowSql.put(database.getName(), sql);
                rowSets.put(database.getName(), rows(database, sql));
            }
            if (disagrees(rowSets)) {
                return Optional.of(semanticFailure(context, table, "primary key row set", rowSets, rowSql,
                        "the databases return different rows for the same freshly created table",
                        databases, introspected));
            }
        }
        return Optional.empty();
    }

    private String count(DatabaseUnderTest database, String sql) {
        try {
            QueryResult result = database.getConnection().executeAndGet(sql);
            if (result.getRowCount() != 1 || result.getValue(0, 0) == null) {
                return "<no result>";
            }
            return normalize(result.getValue(0, 0));
        } catch (SQLException e) {
            return "ERROR: " + OracleSupport.oneLine(e.getMessage());
        }
    }

    private List<String> rows(DatabaseUnderTest database, String sql) {
        List<String> rows = new ArrayList<>();
        try {
            QueryResult result = database.getConnection().executeAndGet(sql);
            for (List<Object> row : result.getRows()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(normalize(row.get(i)));
                }
                rows.add(sb.toString());
            }
        } catch (SQLException e) {
            rows.add("ERROR: " + OracleSupport.oneLine(e.getMessage()));
        }
        rows.sort(String::compareTo);
        return rows;
    }

    /** @return true when not all values of the map are equal (an empty map does not disagree) */
    private static boolean disagrees(Map<String, ?> values) {
        if (values.size() < 2) {
            return false;
        }
        Object reference = null;
        boolean first = true;
        for (Object value : values.values()) {
            if (first) {
                reference = value;
                first = false;
            } else if (!java.util.Objects.equals(reference, value)) {
                return true;
            }
        }
        return false;
    }

    private OracleFailure semanticFailure(OracleContext context, Table table, String check, Map<String, ?> observed,
            Map<String, String> sql, String explanation, List<DatabaseUnderTest> databases,
            Map<String, RelationalSchema> introspected) {
        StringBuilder details = new StringBuilder();
        details.append("A semantic differential check failed.\n\n");
        details.append("Explanation: ").append(explanation).append('\n');
        details.append("Check      : ").append(check).append('\n');
        details.append("Table      : ").append(table.getName()).append('\n');
        details.append("Databases  : ").append(String.join(" vs ", names(databases))).append('\n');
        details.append("\nObserved per database:\n");
        for (Map.Entry<String, ?> entry : observed.entrySet()) {
            details.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            String statement = sql.get(entry.getKey());
            if (statement != null) {
                details.append("      ").append(statement).append('\n');
            }
        }
        details.append("\nIntended schema:\n");
        details.append(SchemaPrinter.describe(context.getIntendedSchema()));
        details.append("\nIntrospected schemas:\n");
        for (DatabaseUnderTest database : databases) {
            details.append("  ").append(database.getFullName()).append(":\n");
            details.append(indent(SchemaPrinter.describe(introspected.get(database.getName())), "    "));
        }
        String title = "differential " + check + " mismatch on " + table.getName();
        return new OracleFailure(FailureKind.DIFFERENTIAL, getName(), title,
                OracleSupport.truncate(details.toString()), reproducerFor(context, databases), context.getSeed(),
                String.join(" vs ", names(databases)), null);
    }

    /* ------------------------------------------------------------------ helpers */

    private static List<String> names(List<DatabaseUnderTest> databases) {
        List<String> names = new ArrayList<>();
        for (DatabaseUnderTest database : databases) {
            names.add(database.getFullName());
        }
        return names;
    }

    private static List<String> primaryKeyColumns(Table table) {
        List<String> columns = new ArrayList<>();
        if (table.getPrimaryKey() == null) {
            return columns;
        }
        for (String name : table.getPrimaryKey().getColumns()) {
            if (table.getColumn(name) != null) {
                columns.add(name);
            }
        }
        return columns;
    }

    private static String quotedList(DatabaseUnderTest database, List<String> identifiers) {
        List<String> quoted = new ArrayList<>();
        for (String identifier : identifiers) {
            quoted.add(database.getDialect().quote(identifier));
        }
        return String.join(", ", quoted);
    }

    /** Normalizes a value so that two engines' representations of the same value compare equal. */
    private static String normalize(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "true" : "false";
        }
        if (value instanceof byte[]) {
            return java.util.Arrays.toString((byte[]) value);
        }
        return value.toString();
    }

    private static String indent(String text, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append(prefix).append(line).append('\n');
        }
        return sb.toString();
    }

    private static String reproducerFor(OracleContext context, DatabaseUnderTest... databases) {
        StringBuilder sb = new StringBuilder();
        for (DatabaseUnderTest database : databases) {
            sb.append("-- ").append(database.getFullName()).append('\n');
            sb.append(OracleSupport.reproducer(context, database.getName(), null));
        }
        return sb.toString();
    }

    private static String reproducerFor(OracleContext context, List<DatabaseUnderTest> databases) {
        return reproducerFor(context, databases.toArray(new DatabaseUnderTest[0]));
    }
}
