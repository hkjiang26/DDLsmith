package org.ddlsmith.conformance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.conformance.ConformancePlan.PlannedRow;
import org.ddlsmith.data.RowGenerator;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.StatementRunner;

/**
 * Inserts a {@link ConformancePlan} into the database under test and reports what the engine
 * accepted.
 *
 * <p>Two things happen here, in this order:</p>
 * <ol>
 *   <li><b>The tables are emptied</b>, children before parents. The oracle compares what the engine
 *       answers against the rows it inserted itself, so any row another oracle (or the algorithm's
 *       own seed data) left behind would be a difference that says nothing about the graph. A table
 *       that cannot be emptied is not populated at all, and its rows are not queried.</li>
 *   <li><b>The planned rows are inserted</b> in the plan's order, which is a topological order of the
 *       foreign keys. Every statement carries a {@code probe-} purpose, so the crash oracle does not
 *       report a rejected row a second time: a rejection is this oracle's finding, and it is reported
 *       with the graph provenance of the row.</li>
 * </ol>
 *
 * <p>Only the rows the engine really stored are returned as accepted. An expectation is never
 * computed from a row that was rejected, so a rejection can never turn into a second, phantom
 * finding about the same row.</p>
 */
public final class ConformancePopulator {

    /** What the engine did with the plan. */
    public static final class Result {
        /** Lower-cased table name -> the rows that were stored. */
        public final Map<String, List<PlannedRow>> accepted = new LinkedHashMap<>();
        /** Tables with at least one rejected row: their contents are not the planned instance. */
        public final Set<String> incomplete = new LinkedHashSet<>();
        /** Tables that could not be emptied before populating. */
        public final Set<String> notReset = new LinkedHashSet<>();
        /** The rows the engine rejected, in insertion order. */
        public final List<Rejection> rejections = new ArrayList<>();
        /** What was skipped, and why; appended to the failure report when there is one. */
        public final List<String> notes = new ArrayList<>();

        public boolean isUsable(String tableName) {
            String name = normalize(tableName);
            return !incomplete.contains(name) && !notReset.contains(name) && accepted.containsKey(name);
        }

        public List<PlannedRow> acceptedRows(String tableName) {
            return accepted.getOrDefault(normalize(tableName), List.of());
        }
    }

    /** One row the engine refused, with the graph element it came from. */
    public static final class Rejection {
        public final Table table;
        public final PlannedRow row;
        public final String sql;
        public final ExecutionEvent event;

        Rejection(Table table, PlannedRow row, String sql, ExecutionEvent event) {
            this.table = table;
            this.row = row;
            this.sql = sql;
            this.event = event;
        }
    }

    private ConformancePopulator() {
    }

    public static Result populate(OracleContext context, DatabaseUnderTest database, ConformancePlan plan,
            RelationalSchema schema) {
        Result result = new Result();
        RowGenerator renderer = new RowGenerator(schema, database.getDialect());
        reset(context, database, schema, result);
        for (PlannedRow row : plan.getRows()) {
            String tableName = normalize(row.getTable().getName());
            if (result.notReset.contains(tableName)) {
                continue;
            }
            String sql = renderer.toInsertSql(row.getTable(), row.getValues(), Set.of());
            ExecutionEvent event = StatementRunner.execute(context, database, sql, false, "probe-conformance-insert");
            if (!event.isSucceeded()) {
                result.incomplete.add(tableName);
                if (isMissingReference(row, event, result)) {
                    // The reference the engine complains about points at a row this plan has not stored
                    // (yet): either the row was rejected earlier, or the graph instance contains a cycle
                    // no insertion order can satisfy. The engine is right to reject the row, so this is
                    // a limitation of the materialization, not a defect — and it is announced as one.
                    result.notes.add("the row " + row.getProvenance() + " in " + row.getTable().getName()
                            + " references a row that is not stored, so the engine correctly rejected it ("
                            + event.getErrorMessage() + ")");
                    continue;
                }
                if (isUniquenessOverAGeneratedColumn(row.getTable(), event)) {
                    // The value of a generated column is computed by the engine, so the planner cannot
                    // know which key a row will end up with: whether two rows collide is decided by the
                    // expression and by the operands the graph supplied. The engine is right to reject
                    // the row, and the collision is a limitation of the materialization.
                    result.notes.add("the row " + row.getProvenance() + " in " + row.getTable().getName()
                            + " collides on a key the engine computes (a unique constraint covers a generated"
                            + " column), so the engine correctly rejected it (" + event.getErrorMessage() + ")");
                    continue;
                }
                result.rejections.add(new Rejection(row.getTable(), row, sql, event));
                continue;
            }
            result.accepted.computeIfAbsent(tableName, key -> new ArrayList<>()).add(row);
        }
        return result;
    }

    /**
     * @return true when the engine rejected the row because a reference of it points at a row that is
     *         not in the database. Two things have to hold: the engine's own words have to describe a
     *         referential violation, and one of the row's foreign keys has to be genuinely unresolvable
     *         at this point. Requiring both keeps a rejection that is really about something else — a
     *         NOT NULL column, a CHECK, a type — a finding.
     */
    private static boolean isMissingReference(PlannedRow row, ExecutionEvent event, Result result) {
        String message = event.getErrorMessage();
        String upper = message == null ? "" : message.toUpperCase(Locale.ROOT);
        if (!upper.contains("FOREIGN KEY") && !upper.contains("REFERENTIAL")) {
            return false;
        }
        for (ForeignKey foreignKey : row.getTable().getForeignKeys()) {
            List<Object> values = row.valuesOf(foreignKey.getColumns());
            boolean present = true;
            for (Object value : values) {
                if (value == null) {
                    present = false;
                    break;
                }
            }
            if (!present) {
                continue;
            }
            if (!referencesStoredRow(values, foreignKey, result)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when the engine rejected the row as a duplicate and the key it complains about is one
     *         the engine computes: a unique constraint (or unique index) over a generated column, whose
     *         value DDLSmith never inserts and therefore cannot predict when it plans the instance
     */
    private static boolean isUniquenessOverAGeneratedColumn(Table table, ExecutionEvent event) {
        String message = event.getErrorMessage();
        String upper = message == null ? "" : message.toUpperCase(Locale.ROOT);
        if (!upper.contains("UNIQUE") && !upper.contains("DUPLICATE")) {
            return false;
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (containsGenerated(table, unique.getColumns())) {
                return true;
            }
        }
        for (Index index : table.getIndexes()) {
            if (index.isUnique() && containsGenerated(table, index.getColumns())) {
                return true;
            }
        }
        return table.getPrimaryKey() != null && containsGenerated(table, table.getPrimaryKey().getColumns());
    }

    private static boolean containsGenerated(Table table, List<String> columns) {
        for (String name : columns) {
            Column column = table.getColumn(name);
            if (column != null && column.isGenerated()) {
                return true;
            }
        }
        return false;
    }

    /** @return true when some row already stored in the referenced table carries these values */
    private static boolean referencesStoredRow(List<Object> values, ForeignKey foreignKey, Result result) {        for (PlannedRow candidate : result.acceptedRows(foreignKey.getReferencedTable())) {
            boolean same = true;
            for (int i = 0; i < foreignKey.getReferencedColumns().size() && i < values.size(); i++) {
                Object other = org.ddlsmith.data.DataModel.getValue(candidate.getValues(),
                        foreignKey.getReferencedColumns().get(i));
                if (!java.util.Objects.equals(org.ddlsmith.data.DataModel.canonical(values.get(i), null),
                        org.ddlsmith.data.DataModel.canonical(other, null))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the rows this oracle inserted, children before parents.
     *
     * <p>The database is left as it was found, which matters beyond tidiness: the differential
     * algorithm checks <em>after</em> the oracles that every table it created is still empty, so an
     * oracle that keeps its rows behind turns that post-condition into a finding about itself. A table
     * that cannot be emptied is recorded as a note — housekeeping is never a defect report.</p>
     */
    public static void cleanup(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
            ConformancePlan plan, List<String> notes) {
        RowGenerator order = new RowGenerator(schema, database.getDialect());
        List<Table> tables = new ArrayList<>(order.insertionOrder());
        java.util.Collections.reverse(tables);
        for (Table table : tables) {
            if (!plan.populates(table.getName())) {
                continue;
            }
            String sql = "DELETE FROM " + org.ddlsmith.oracle.impl.OracleSupport
                    .tableReference(context, database, table);
            ExecutionEvent event = StatementRunner.execute(context, database, sql, false, "probe-conformance-cleanup");
            if (!event.isSucceeded()) {
                notes.add("the rows materialized in " + table.getName() + " could not be removed afterwards ("
                        + event.getErrorMessage() + ")");
            }
        }
    }

    /**
     * Empties every table of the schema, children before parents, and records which tables are now
     * known to be empty. A failed delete is a note, not a finding: the statement is this oracle's own
     * housekeeping, and a table that could not be emptied is simply not compared.
     */
    private static void reset(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
            Result result) {
        RowGenerator order = new RowGenerator(schema, database.getDialect());
        List<Table> tables = new ArrayList<>(order.insertionOrder());
        java.util.Collections.reverse(tables);
        for (Table table : tables) {
            // the reference has to be qualified exactly like the one the table was created with: an
            // unqualified name resolves to the engine's default namespace, and on an engine whose
            // generated objects live in a namespace of their own (H2, PostgreSQL, MySQL) the delete
            // then fails with "table not found" and nothing of that table is ever materialized
            String sql = "DELETE FROM " + org.ddlsmith.oracle.impl.OracleSupport
                    .tableReference(context, database, table);
            ExecutionEvent event = StatementRunner.execute(context, database, sql, false, "probe-conformance-reset");
            if (!event.isSucceeded()) {
                result.notReset.add(normalize(table.getName()));
                result.notes.add("could not empty " + table.getName() + " before materializing the graph instance ("
                        + event.getErrorMessage() + "); nothing is checked against the rows of that table");
            }
        }
    }

    private static String normalize(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }
}
