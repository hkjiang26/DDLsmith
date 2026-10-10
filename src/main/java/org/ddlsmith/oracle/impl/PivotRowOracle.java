package org.ddlsmith.oracle.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.query.QueryResult;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.StatementRunner;
import org.ddlsmith.oracle.TestOracle;

/**
 * A lightweight version of SQLancer's Pivoted Query Synthesis, restricted to what DDL semantics
 * imply: the rows DDLSmith inserted are known, so a small set of deterministic queries has a result
 * DDLSmith can compute from its own {@link DataModel} instead of from another query.
 *
 * <p>The queries are:</p>
 * <ul>
 *   <li>{@code SELECT COUNT(*) FROM t} against {@link DataModel#getRowCount};</li>
 *   <li>{@code SELECT COUNT(c) FROM t} against {@link DataModel#countNonNull};</li>
 *   <li>{@code SELECT COUNT(DISTINCT c) FROM t} against {@link DataModel#countDistinct};</li>
 *   <li>{@code SELECT MIN(c) / MAX(c) FROM t} against {@link DataModel#min} / {@link DataModel#max};</li>
 *   <li>{@code SELECT <pk columns> FROM t ORDER BY <pk columns>} against the key values of the
 *       inserted rows - this is the check that catches silent coercion or truncation of stored
 *       values, because it compares what came back with what was inserted;</li>
 *   <li>{@code SELECT COUNT(*) FROM t WHERE c IS NULL} against
 *       {@code rowCount - countNonNull}.</li>
 * </ul>
 *
 * <p>A mismatch is a {@link FailureKind#DATA_MISMATCH} whose details show the query, the value
 * DDLSmith computed, the value the engine returned and the rows DDLSmith inserted.</p>
 *
 * <h2>Guards</h2>
 * <ul>
 *   <li>Only the rows the engine actually stored are modelled: the expectation model is built from
 *       the successful INSERTs, so an INSERT that was rejected never makes the oracle expect a row
 *       the database does not have.</li>
 *   <li>The tables are emptied before they are populated, because the model only knows the rows this
 *       oracle inserted. The data oracles share one database, so the algorithm's seed rows and the
 *       rows of the constraint, referential-action and default-value oracles — all of which run
 *       before this one — are still in the tables; comparing against the model alone made every
 *       aggregate query look wrong (135 of 1 272 databases in one acceptance session) although the
 *       engine had returned exactly what was stored. A table that could not be emptied is not
 *       queried at all.</li>
 *   <li>Values are compared through {@link DataModel#normalizeForComparison}, so
 *       {@code Integer}/{@code Long}/{@code BigDecimal} from different engines compare equal, and
 *       numerics are additionally compared with {@link BigDecimal#compareTo} so that a scale
 *       difference is not a mismatch.</li>
 *   <li>{@code CHAR(n)} values are compared with trailing spaces stripped, because the SQL standard
 *       requires padding to the declared length.</li>
 *   <li>Temporal key values are compared in a canonical form derived from the column's declared type
 *       rather than from the Java type the driver happened to return, because
 *       {@code Timestamp.toString()} ("2000-01-01 00:00:06.0") and
 *       {@code LocalDateTime.toString()} ("2000-01-01T00:00:06") describe the same stored value.</li>
 *   <li>{@code MIN}/{@code MAX} are only generated for numeric, textual and boolean columns. For
 *       temporal, binary and structured types the engine's textual representation and collation
 *       rules are not portable enough to compare reliably, and a mismatch there would be a false
 *       positive rather than a defect.</li>
 *   <li>{@code MIN}/{@code MAX} are skipped when the model has no non-NULL value for the column,
 *       because the SQL result is then NULL for a reason that carries no information.</li>
 *   <li>The {@code ORDER BY} query is compared as a multiset of key tuples (ordered by the canonical
 *       form of each tuple), because engines differ in the collation of textual keys; the ordering
 *       itself is additionally verified when every key column is numeric, where no collation is
 *       involved.</li>
 *   <li>Tables the row generator could not populate are skipped, and the reason is listed in the
 *       failure details whenever a mismatch is reported.</li>
 * </ul>
 */
public class PivotRowOracle implements TestOracle {

    public static final String NAME = "pivot-row";
    /** Upper bound on the number of queries executed per database. */
    public static final int MAX_QUERIES = 30;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Compares deterministic query results with the rows DDLSmith inserted (pivoted query "
                + "synthesis restricted to DDL semantics).";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        RelationalSchema schema = context.getIntendedSchema();
        if (schema == null || schema.getTables().isEmpty()) {
            return Optional.empty();
        }
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (DatabaseUnderTest database : context.getDatabases()) {
            findings.addAll(verifyDatabase(context, database, schema, notes));
        }
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ConstraintEnforcementOracle.Support.toFailure(NAME, context, findings, notes));
    }

    private List<ConstraintEnforcementOracle.Support.Finding> verifyDatabase(OracleContext context,
            DatabaseUnderTest database, RelationalSchema schema, List<String> notes) {
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        String prefix = "[" + database.getName() + "] ";
        ConstraintEnforcementOracle.Support.Population population = ConstraintEnforcementOracle.Support.populate(
                context, database, schema, 2, 6, true);
        notes.addAll(population.notes);
        if (population.populated.isEmpty()) {
            notes.add(prefix + "no table could be populated, so no query can be checked");
            return findings;
        }
        DataModel model = expectationModel(population.model, schema);
        List<PivotQuery> candidates = new ArrayList<>();
        for (Table table : population.populated) {
            candidates.addAll(queriesFor(table, model, database.getDialect()));
        }
        if (candidates.isEmpty()) {
            notes.add(prefix + "no query could be derived from the populated tables");
            return findings;
        }
        Randomly.shuffle(candidates);
        int limit = Math.min(candidates.size(), MAX_QUERIES);
        for (int i = 0; i < limit; i++) {
            ConstraintEnforcementOracle.Support.Finding finding = run(context, database, model, candidates.get(i));
            if (finding != null) {
                findings.add(finding);
            }
        }
        return findings;
    }

    /**
     * Test seam: the model the expected values are computed from.
     *
     * <p>The default implementation returns the rows the engine actually stored. A test can override
     * this to return a model with an extra row and check that the oracle reports a
     * {@link FailureKind#DATA_MISMATCH}.</p>
     */
    protected DataModel expectationModel(DataModel stored, RelationalSchema schema) {
        return stored;
    }

    /* ------------------------------------------------------------------ query generation */

    /** One deterministic query together with the result DDLSmith computes for it. */
    private static final class PivotQuery {
        final Table table;
        final String sql;
        final Object expected;
        final List<Column> valueColumns;
        final boolean orderedTuples;
        final boolean numericOrder;
        final String description;

        PivotQuery(Table table, String sql, Object expected, List<Column> valueColumns, boolean orderedTuples,
                boolean numericOrder, String description) {
            this.table = table;
            this.sql = sql;
            this.expected = expected;
            this.valueColumns = valueColumns;
            this.orderedTuples = orderedTuples;
            this.numericOrder = numericOrder;
            this.description = description;
        }
    }

    private List<PivotQuery> queriesFor(Table table, DataModel model, Dialect dialect) {
        List<PivotQuery> queries = new ArrayList<>();
        String quotedTable = dialect.quote(table.getName());
        long rowCount = model.getRowCount(table.getName());
        queries.add(new PivotQuery(table, "SELECT COUNT(*) FROM " + quotedTable, rowCount, List.of(), false, false,
                "number of rows"));
        for (Column column : table.getColumns()) {
            if (column.isGenerated()) {
                continue;
            }
            String quoted = dialect.quote(column.getName());
            long nonNull = model.countNonNull(table.getName(), column.getName());
            queries.add(new PivotQuery(table, "SELECT COUNT(" + quoted + ") FROM " + quotedTable, nonNull,
                    List.of(column), false, false, "non-NULL values of " + column.getName()));
            queries.add(new PivotQuery(table, "SELECT COUNT(DISTINCT " + quoted + ") FROM " + quotedTable,
                    model.countDistinct(table.getName(), column.getName()), List.of(column), false, false,
                    "distinct non-NULL values of " + column.getName()));
            queries.add(new PivotQuery(table,
                    "SELECT COUNT(*) FROM " + quotedTable + " WHERE " + quoted + " IS NULL", rowCount - nonNull,
                    List.of(column), false, false, "NULL values of " + column.getName()));
            if (isExtremeComparable(column.getType())) {
                Object minimum = model.min(table.getName(), column.getName());
                if (minimum != null) {
                    queries.add(new PivotQuery(table, "SELECT MIN(" + quoted + ") FROM " + quotedTable, minimum,
                            List.of(column), false, false, "minimum of " + column.getName()));
                }
                Object maximum = model.max(table.getName(), column.getName());
                if (maximum != null) {
                    queries.add(new PivotQuery(table, "SELECT MAX(" + quoted + ") FROM " + quotedTable, maximum,
                            List.of(column), false, false, "maximum of " + column.getName()));
                }
            }
        }
        if (table.getPrimaryKey() != null && !table.getPrimaryKey().getColumns().isEmpty()
                && rowCount > 0) {
            List<Column> keyColumns = new ArrayList<>();
            boolean usable = true;
            for (String columnName : table.getPrimaryKey().getColumns()) {
                Column column = table.getColumn(columnName);
                if (column == null) {
                    usable = false;
                    break;
                }
                keyColumns.add(column);
            }
            List<List<Object>> expected = new ArrayList<>();
            boolean numeric = usable;
            for (Map<String, Object> row : model.getRows(table.getName())) {
                List<Object> tuple = new ArrayList<>();
                for (Column column : keyColumns) {
                    Object value = DataModel.getValue(row, column.getName());
                    if (value == null) {
                        usable = false;
                        break;
                    }
                    if (!(value instanceof Number)) {
                        numeric = false;
                    }
                    tuple.add(value);
                }
                if (!usable) {
                    break;
                }
                expected.add(tuple);
            }
            if (usable && !expected.isEmpty()) {
                List<String> selected = new ArrayList<>();
                for (Column column : keyColumns) {
                    selected.add(dialect.quote(column.getName()));
                }
                String list = String.join(", ", selected);
                queries.add(new PivotQuery(table,
                        "SELECT " + list + " FROM " + quotedTable + " ORDER BY " + list, expected, keyColumns,
                        true, numeric, "stored primary key values of " + table.getName()));
            }
        }
        return queries;
    }

    private static boolean isExtremeComparable(DataType type) {
        if (type == null) {
            return false;
        }
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
        case DECIMAL:
        case REAL:
        case DOUBLE:
        case CHAR:
        case VARCHAR:
        case TEXT:
        case BOOLEAN:
            return true;
        default:
            // temporal, binary and structured types are not compared: their textual form and
            // collation differ between engines, so a mismatch would not be attributable
            return false;
        }
    }

    /* ------------------------------------------------------------------ execution and comparison */

    private ConstraintEnforcementOracle.Support.Finding run(OracleContext context, DatabaseUnderTest database,
            DataModel model, PivotQuery query) {
        QueryResult result;
        try {
            result = StatementRunner.query(context, database, query.sql, "probe-pivot-query");
        } catch (Exception e) {
            // an unexpected query failure is reported by the crash oracle, not here
            return null;
        }
        if (query.orderedTuples) {
            return compareTuples(context, database, model, query, result);
        }
        if (result.getRowCount() == 0 || result.getColumnNames().isEmpty()) {
            return null;
        }
        Object observed = result.getValue(0, 0);
        Object expected = query.expected;
        DataType type = query.valueColumns.isEmpty() ? null : query.valueColumns.get(0).getType();
        if (sameValue(expected, observed, type)) {
            return null;
        }
        return mismatch(database, model, query, describe(expected), describe(observed),
                "expected " + describe(expected) + ", observed " + describe(observed));
    }

    private ConstraintEnforcementOracle.Support.Finding compareTuples(OracleContext context,
            DatabaseUnderTest database, DataModel model, PivotQuery query, QueryResult result) {
        List<List<Object>> expected = new ArrayList<>();
        for (Object tuple : (List<?>) query.expected) {
            expected.add(new ArrayList<>((List<?>) tuple));
        }
        List<List<Object>> observed = new ArrayList<>(result.getRows());
        List<String> expectedKeys = canonicalTuples(expected, query.valueColumns);
        List<String> observedKeys = canonicalTuples(observed, query.valueColumns);
        List<String> sortedExpected = new ArrayList<>(expectedKeys);
        List<String> sortedObserved = new ArrayList<>(observedKeys);
        java.util.Collections.sort(sortedExpected);
        java.util.Collections.sort(sortedObserved);
        if (!sortedExpected.equals(sortedObserved)) {
            return mismatch(database, model, query, expectedKeys.toString(), observedKeys.toString(),
                    "the set of stored key values differs from the set of inserted key values"
                            + (sortedExpected.size() == sortedObserved.size()
                                    ? " (same number of rows, different values)"
                                    : " (different number of rows: expected " + sortedExpected.size() + ", observed "
                                            + sortedObserved.size() + ")"));
        }
        if (query.numericOrder) {
            List<BigDecimal> numeric = new ArrayList<>();
            for (List<Object> tuple : observed) {
                numeric.add(new BigDecimal(String.valueOf(tuple.get(0))));
            }
            for (int i = 1; i < numeric.size(); i++) {
                if (numeric.get(i - 1).compareTo(numeric.get(i)) > 0) {
                    return mismatch(database, model, query, "an ascending sequence", observedKeys.toString(),
                            "ORDER BY did not return the rows in ascending key order");
                }
            }
        }
        return null;
    }

    private ConstraintEnforcementOracle.Support.Finding mismatch(DatabaseUnderTest database, DataModel model,
            PivotQuery query, String expected, String observed, String explanation) {
        StringBuilder details = new StringBuilder();
        details.append("Query      : ").append(query.sql).append('\n');
        details.append("Meaning    : ").append(query.description).append('\n');
        details.append("Table      : ").append(query.table.getName()).append('\n');
        details.append("Expected   : ").append(expected).append("  (computed by DDLSmith from the rows it "
                + "inserted)\n");
        details.append("Observed   : ").append(observed).append("  (returned by the engine)\n");
        details.append("Explanation: ").append(explanation).append('\n');
        details.append("Rows DDLSmith inserted:\n").append(model.describe());
        return ConstraintEnforcementOracle.Support.Finding.of(FailureKind.DATA_MISMATCH, database.getName(),
                "query", "a query result is inconsistent with the inserted rows", details.toString(), query.sql);
    }

    private static List<String> canonicalTuples(List<List<Object>> tuples, List<Column> columns) {
        List<String> result = new ArrayList<>();
        for (List<Object> tuple : tuples) {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < tuple.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                DataType type = i < columns.size() ? columns.get(i).getType() : null;
                sb.append(canonical(tuple.get(i), type));
            }
            sb.append(')');
            result.add(sb.toString());
        }
        return result;
    }

    /**
     * The canonical, engine independent form of a value used for tuple comparison.
     *
     * <p>The implementation is shared with every other consumer of the data model
     * ({@link DataModel#canonical(Object, DataType)}) so that an engine is never reported for a
     * difference that is only the Java type or the spelling a driver chose.</p>
     */
    static String canonical(Object value, DataType type) {
        return DataModel.canonical(value, type);
    }

    private static boolean sameValue(Object expected, Object observed, DataType type) {
        Object left = DataModel.normalizeForComparison(expected);
        Object right = DataModel.normalizeForComparison(observed);
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof BigDecimal && right instanceof BigDecimal) {
            return ((BigDecimal) left).compareTo((BigDecimal) right) == 0;
        }
        if (type != null && type.getCategory() == DataType.Category.CHAR) {
            return left.toString().stripTrailing().equals(right.toString().stripTrailing());
        }
        return left.equals(right);
    }

    private static String describe(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[]) {
            return "X'" + java.util.Arrays.toString((byte[]) value) + "'";
        }
        return value + " (" + value.getClass().getSimpleName() + ")";
    }
}
