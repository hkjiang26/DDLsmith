package org.ddlsmith.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.ddlsmith.common.schema.DataType;
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
        // A driver may hand a large object back as a handle rather than as its content: H2 returns a
        // java.sql.Clob for a CLOB column whose toString() is "clob0: 'the text'", which would make
        // every comparison against the inserted text a difference that says nothing about the engine.
        // The content is read here so that a value compares as the value it holds.
        if (value instanceof java.sql.Clob) {
            return clobText((java.sql.Clob) value);
        }
        if (value instanceof java.sql.Blob) {
            return blobText((java.sql.Blob) value);
        }
        if (value instanceof java.sql.SQLXML) {
            try {
                return ((java.sql.SQLXML) value).getString();
            } catch (java.sql.SQLException e) {
                return value.toString();
            }
        }
        return value.toString();
    }

    private static String clobText(java.sql.Clob clob) {
        try {
            long length = clob.length();
            return length > Integer.MAX_VALUE ? clob.getSubString(1, Integer.MAX_VALUE)
                    : clob.getSubString(1, (int) length);
        } catch (java.sql.SQLException e) {
            return clob.toString();
        }
    }

    private static String blobText(java.sql.Blob blob) {
        try {
            long length = blob.length();
            if (length > Integer.MAX_VALUE) {
                return blob.toString();
            }
            return java.util.Arrays.toString(blob.getBytes(1, (int) length));
        } catch (java.sql.SQLException e) {
            return blob.toString();
        }
    }

    /**
     * The canonical, engine independent textual form of one stored value.
     *
     * <p>It is the comparison form the data oracles share: a value the engine hands back must be
     * described the same way as the value DDLSmith inserted, whatever Java type the driver chose and
     * whatever spelling the engine uses. The rules are deliberately narrow — numeric values compare
     * by value ({@link BigDecimal#compareTo} semantics, so a scale difference is not a difference),
     * {@code CHAR(n)} values are compared with the SQL standard's trailing spaces stripped, booleans
     * compare by number (SQLite stores them with NUMERIC affinity), and temporal values are rendered
     * from the value itself rather than from the Java type the driver happened to return.</p>
     *
     * <p>It lives here rather than in the oracle that first needed it so that every consumer of the
     * data model — including extension oracles outside this package — can compare stored values the
     * same way, instead of each one inventing its own notion of equality.</p>
     *
     * @param value the value, as inserted by DDLSmith or as returned by the engine
     * @param type the declared type of the column, or null when it is unknown
     * @return a stable textual form; {@code "NULL"} for a null value
     */
    public static String canonical(Object value, DataType type) {
        String temporal = canonicalTemporal(value, type);
        if (temporal != null) {
            return temporal;
        }
        Object normalized = normalizeForComparison(value);
        if (normalized == null) {
            return "NULL";
        }
        if (normalized instanceof BigDecimal) {
            return ((BigDecimal) normalized).toPlainString();
        }
        if (type != null && type.getCategory() == DataType.Category.CHAR) {
            return normalized.toString().stripTrailing();
        }
        return normalized.toString();
    }

    /**
     * The canonical form of a temporal value, which the Java type alone cannot supply: the model
     * holds a {@link java.sql.Timestamp} for every DATE/TIME/TIMESTAMP column the row generator
     * fills, while the driver hands back whatever type its engine maps the column to — measured on
     * MySQL 26.7.0, Connector/J returns {@code java.sql.Date} for DATE, {@code java.sql.Time} for
     * TIME and {@link java.time.LocalDateTime} for DATETIME — and their {@code toString()} forms
     * differ in the date/time separator, in whether the seconds are shown, and in the fractional
     * seconds. A primary key that contains a temporal column therefore made every key tuple of that
     * table look different from the value that was inserted: one generated database of the MySQL
     * campaign reported {@code [(1, 2000-01-01 00:00:06.0), …]} against
     * {@code [(1, 2000-01-01T00:00:06), …]} although both sides held the same four rows. Rendering
     * both sides from the same {@code LocalDateTime} — the date only for a DATE column, the time only
     * for a TIME column, and fractional seconds with their trailing zeros removed — removes the whole
     * class of difference without weakening the check, because a value the engine actually coerced or
     * truncated still differs.
     *
     * @return the canonical form, or null when the column is not temporal or the value cannot be
     *         read as a temporal value (in which case the ordinary comparison applies)
     */
    private static String canonicalTemporal(Object value, DataType type) {
        if (type == null || value == null) {
            return null;
        }
        DataType.Category category = type.getCategory();
        if (category != DataType.Category.DATE && category != DataType.Category.TIME
                && category != DataType.Category.TIMESTAMP && category != DataType.Category.TIMESTAMPTZ) {
            return null;
        }
        java.time.LocalDateTime moment = toLocalDateTime(value);
        if (moment == null) {
            return null;
        }
        switch (category) {
        case DATE:
            return moment.toLocalDate().toString();
        case TIME:
            return formatTime(moment.toLocalTime());
        default:
            return moment.toLocalDate() + " " + formatTime(moment.toLocalTime());
        }
    }

    /** @return the temporal value a Java object or a textual rendering denotes, or null */
    private static java.time.LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof java.time.LocalDateTime) {
            return (java.time.LocalDateTime) value;
        }
        if (value instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) value).atStartOfDay();
        }
        if (value instanceof java.time.LocalTime) {
            return java.time.LocalDate.of(1970, 1, 1).atTime((java.time.LocalTime) value);
        }
        if (value instanceof java.time.OffsetDateTime) {
            return ((java.time.OffsetDateTime) value).toLocalDateTime();
        }
        if (value instanceof java.time.ZonedDateTime) {
            return ((java.time.ZonedDateTime) value).toLocalDateTime();
        }
        if (value instanceof java.sql.Timestamp) {
            // checked before java.util.Date, which Timestamp extends
            return ((java.sql.Timestamp) value).toLocalDateTime();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().atStartOfDay();
        }
        if (value instanceof java.sql.Time) {
            return java.time.LocalDate.of(1970, 1, 1).atTime(((java.sql.Time) value).toLocalTime());
        }
        if (value instanceof java.util.Date) {
            return java.time.LocalDateTime.ofInstant(((java.util.Date) value).toInstant(),
                    java.time.ZoneId.systemDefault());
        }
        if (value instanceof CharSequence) {
            return parseTemporal(value.toString());
        }
        return null;
    }

    /** @return the temporal value a literal denotes, or null when the text is not one */
    private static java.time.LocalDateTime parseTemporal(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(trimmed.replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException notADateTime) {
            // try the shorter forms below
        }
        try {
            return java.time.LocalDate.parse(trimmed).atStartOfDay();
        } catch (java.time.format.DateTimeParseException notADate) {
            // try a time of day
        }
        try {
            return java.time.LocalDate.of(1970, 1, 1).atTime(java.time.LocalTime.parse(trimmed));
        } catch (java.time.format.DateTimeParseException notATime) {
            return null;
        }
    }

    private static String formatTime(java.time.LocalTime time) {
        StringBuilder sb = new StringBuilder(String.format("%02d:%02d:%02d", time.getHour(), time.getMinute(),
                time.getSecond()));
        int nanos = time.getNano();
        if (nanos > 0) {
            String fraction = String.format("%09d", nanos);
            int end = fraction.length();
            while (end > 0 && fraction.charAt(end - 1) == '0') {
                end--;
            }
            sb.append('.').append(fraction, 0, end);
        }
        return sb.toString();
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
