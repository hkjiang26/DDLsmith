package org.ddlsmith.conformance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.common.schema.Table;

/**
 * The materialization of one generated graph instance into a relational instance of the intended
 * schema: the rows the graph requires, in the order they must be inserted, plus the notes that say
 * what could not be materialized and why.
 *
 * <p>The plan is the oracle's ground truth. It is produced by {@link ConformancePlanner} from the
 * graph, the ER diagram, the mapping trace and the adapted schema, and it is consumed by
 * {@link ConformancePopulator} (which inserts the rows) and {@link SemanticQueryBuilder} (which
 * turns the accepted rows into queries whose answers the engine must reproduce).</p>
 *
 * <p>Which rows survive into the expectations is decided <em>after</em> population, from the rows
 * the engine actually accepted: a rejected row never makes the oracle expect data the database does
 * not have, which is the same discipline the other data oracles follow.</p>
 */
public final class ConformancePlan {

    /** Every row the graph requires, in insertion order (parents before children). */
    private final List<PlannedRow> rows = new ArrayList<>();
    /** Tables the plan inserts into; a table absent here is never queried. */
    private final Set<String> populatedTables = new LinkedHashSet<>();
    /** The views that were materialized by the mapping, by name, for the unfolding check. */
    private final List<String> views = new ArrayList<>();
    /** Why something was not materialized; every skip must be announced here. */
    private final List<String> notes = new ArrayList<>();

    public List<PlannedRow> getRows() {
        return rows;
    }

    /**
     * Replaces the insertion order of the planned rows.
     *
     * <p>The planner fills the rows construct by construct; the order they are inserted in has to
     * respect the foreign keys instead, including the references a table makes to itself (a
     * recursive relationship), which is why the planner re-orders them once the whole instance is
     * known.</p>
     */
    public void setRows(List<PlannedRow> ordered) {
        rows.clear();
        rows.addAll(ordered);
    }

    public ConformancePlan addRow(PlannedRow row) {
        rows.add(row);
        populatedTables.add(normalize(row.getTable().getName()));
        return this;
    }

    public Set<String> getPopulatedTables() {
        return populatedTables;
    }

    public boolean populates(String tableName) {
        return populatedTables.contains(normalize(tableName));
    }

    /** @return the planned rows of one table, in insertion order */
    public List<PlannedRow> rowsOf(String tableName) {
        List<PlannedRow> result = new ArrayList<>();
        for (PlannedRow row : rows) {
            if (normalize(row.getTable().getName()).equals(normalize(tableName))) {
                result.add(row);
            }
        }
        return result;
    }

    /** @return the planned rows of one table grouped by their insertion index, for quick lookup */
    public Map<String, List<PlannedRow>> rowsByTable() {
        Map<String, List<PlannedRow>> result = new LinkedHashMap<>();
        for (PlannedRow row : rows) {
            result.computeIfAbsent(normalize(row.getTable().getName()), key -> new ArrayList<>()).add(row);
        }
        return result;
    }

    public List<String> getViews() {
        return views;
    }

    public ConformancePlan addView(String viewName) {
        views.add(viewName);
        return this;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void note(String note) {
        notes.add(note);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    static String normalize(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * One row the graph requires: the table, the values every non-generated column will be given,
     * and where the row came from (which node or edge of the graph, and which ER construct).
     */
    public static final class PlannedRow {

        private final Table table;
        private final Map<String, Object> values;
        private final String provenance;
        /** The graph node this row stands for, or -1 when the row is not a node row. */
        private final int nodeId;
        /** The graph edge this row stands for, or -1 when the row is not an edge row. */
        private final int edgeId;
        /**
         * The foreign key columns a materialization step filled on this row. The columns the row
         * generator resolved on its own are deliberately absent: they point at parents the graph does
         * not connect the row to, so the planner clears them before the row is inserted.
         */
        private final Set<String> filledForeignKeys = new LinkedHashSet<>();

        public PlannedRow(Table table, Map<String, Object> values, String provenance, int nodeId, int edgeId) {
            this.table = table;
            this.values = values;
            this.provenance = provenance;
            this.nodeId = nodeId;
            this.edgeId = edgeId;
        }

        public void markForeignKeyFilled(String column) {
            filledForeignKeys.add(normalize(column));
        }

        public boolean isForeignKeyFilled(String column) {
            return filledForeignKeys.contains(normalize(column));
        }

        public Table getTable() {
            return table;
        }

        public Map<String, Object> getValues() {
            return values;
        }

        public String getProvenance() {
            return provenance;
        }

        public int getNodeId() {
            return nodeId;
        }

        public int getEdgeId() {
            return edgeId;
        }

        /** @return the values of the given columns, in order; a missing column contributes null */
        public List<Object> valuesOf(List<String> columns) {
            List<Object> result = new ArrayList<>();
            for (String column : columns) {
                result.add(org.ddlsmith.data.DataModel.getValue(values, column));
            }
            return result;
        }

        @Override
        public String toString() {
            return table.getName() + values + " (" + provenance + ")";
        }
    }
}
