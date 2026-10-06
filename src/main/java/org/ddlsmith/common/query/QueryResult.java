package org.ddlsmith.common.query;

import java.util.ArrayList;
import java.util.List;

/**
 * A materialized result set: column labels plus rows of values.
 */
public class QueryResult {

    private final List<String> columnNames;
    private final List<List<Object>> rows;

    public QueryResult(List<String> columnNames, List<List<Object>> rows) {
        this.columnNames = columnNames;
        this.rows = rows;
    }

    public static QueryResult empty() {
        return new QueryResult(new ArrayList<>(), new ArrayList<>());
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    public int getRowCount() {
        return rows.size();
    }

    public Object getValue(int row, int column) {
        return rows.get(row).get(column);
    }

    public Object getValue(int row, String columnName) {
        int idx = columnNames.indexOf(columnName);
        if (idx < 0) {
            throw new IllegalArgumentException("unknown column " + columnName + " in " + columnNames);
        }
        return rows.get(row).get(idx);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** A stable, engine-independent textual form used when comparing results across DBMS. */
    public String toNormalizedString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(');
            List<Object> row = rows.get(i);
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(normalize(row.get(j)));
            }
            sb.append(')');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String normalize(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            // compare numerics by value, not by Java type (engines return Integer/Long/BigDecimal interchangeably)
            return new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "true" : "false";
        }
        if (value instanceof byte[]) {
            return java.util.Arrays.toString((byte[]) value);
        }
        return value.toString();
    }

    @Override
    public String toString() {
        return toNormalizedString();
    }
}
