package org.ddlsmith.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.ddlsmith.common.schema.RelationalSchema;

/**
 * An in-memory model of the rows that DDLSmith has inserted into a database.
 *
 * <p>The oracles use it to know what the database is supposed to contain, so that a query result
 * can be checked against a value DDLSmith computes itself instead of against another query.</p>
 */
public class DataModel {

    private final RelationalSchema schema;
    private final Map<String, List<Map<String, Object>>> rows = new TreeMap<>();

    public DataModel(RelationalSchema schema) {
        this.schema = schema;
    }

    public RelationalSchema getSchema() {
        return schema;
    }

    public void addRow(String tableName, Map<String, Object> row) {
        rows.computeIfAbsent(tableName.toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>()).add(row);
    }

    public List<Map<String, Object>> getRows(String tableName) {
        return rows.getOrDefault(tableName.toLowerCase(java.util.Locale.ROOT), List.of());
    }

    public int getRowCount(String tableName) {
        return getRows(tableName).size();
    }

    public boolean isEmpty() {
        return rows.values().stream().allMatch(List::isEmpty);
    }

    /** @return the number of rows where the given column is not null */
    public long countNonNull(String tableName, String columnName) {
        long count = 0;
        for (Map<String, Object> row : getRows(tableName)) {
            Object value = getValue(row, columnName);
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    /** @return the number of distinct non-null values of the given column */
    public long countDistinct(String tableName, String columnName) {
        java.util.Set<Object> distinct = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : getRows(tableName)) {
            Object value = getValue(row, columnName);
            if (value != null) {
                distinct.add(normalizeForComparison(value));
            }
        }
        return distinct.size();
    }

    /** @return the sum of a numeric column, or null when there are no non-null values */
    public java.math.BigDecimal sum(String tableName, String columnName) {
        java.math.BigDecimal total = null;
        for (Map<String, Object> row : getRows(tableName)) {
            Object value = getValue(row, columnName);
            if (value instanceof Number) {
                java.math.BigDecimal decimal = new java.math.BigDecimal(value.toString());
                total = total == null ? decimal : total.add(decimal);
            }
        }
        return total;
    }

    public Object min(String tableName, String columnName) {
        return extreme(tableName, columnName, true);
    }

    public Object max(String tableName, String columnName) {
        return extreme(tableName, columnName, false);
    }

    private Object extreme(String tableName, String columnName, boolean minimum) {
        Object best = null;
        for (Map<String, Object> row : getRows(tableName)) {
            Object value = getValue(row, columnName);
            if (value == null) {
                continue;
            }
            if (best == null) {
                best = value;
                continue;
            }
            int comparison = compare(value, best);
            if ((minimum && comparison < 0) || (!minimum && comparison > 0)) {
                best = value;
            }
        }
        return best;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compare(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()));
        }
        if (a instanceof Comparable && a.getClass().isInstance(b)) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }

    /** Column lookup that tolerates identifier case differences between engines. */
    public static Object getValue(Map<String, Object> row, String columnName) {
        if (row.containsKey(columnName)) {
            return row.get(columnName);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Normalizes a value for equality comparison across engines. */
    public static Object normalizeForComparison(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return new java.math.BigDecimal(value.toString()).stripTrailingZeros();
        }
        if (value instanceof Boolean) {
            // the same Java type as the numeric branch above, deliberately: a caller compares the
            // normalized values with equals(), and Integer(1) is not equal to BigDecimal(1). SQLite
            // stores BOOLEAN with NUMERIC affinity and hands back 0/1, so MIN/MAX over a BOOLEAN
            // column returned Integer(1) where the model held Boolean.TRUE, and every such query was
            // reported as a DATA_MISMATCH even though the two values are the same number.
            return ((Boolean) value) ? java.math.BigDecimal.ONE : java.math.BigDecimal.ZERO;
        }
        if (value instanceof byte[]) {
            return java.util.Arrays.toString((byte[]) value);
        }
        return value.toString();
    }

    /** @return a compact dump used in failure reports */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Map<String, Object>>> entry : rows.entrySet()) {
            sb.append("-- ").append(entry.getKey()).append(": ").append(entry.getValue().size()).append(" rows\n");
            int shown = 0;
            for (Map<String, Object> row : entry.getValue()) {
                if (shown++ >= 5) {
                    sb.append("--   ...\n");
                    break;
                }
                sb.append("--   ").append(new LinkedHashMap<>(row)).append('\n');
            }
        }
        return sb.toString();
    }
}
