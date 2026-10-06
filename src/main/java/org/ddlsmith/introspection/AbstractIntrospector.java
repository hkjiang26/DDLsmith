package org.ddlsmith.introspection;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.DDLSmithDBConnection;

/**
 * Shared plumbing for catalog introspection: runs queries against the engine's metadata tables and
 * hands back rows as case-insensitive maps, so that engine introspectors only contain the catalog
 * queries and the translation into the canonical schema model.
 */
public abstract class AbstractIntrospector implements SchemaIntrospector {

    /** Runs a catalog query and materializes the rows as maps keyed by lowercased column label. */
    protected List<Map<String, Object>> query(DDLSmithDBConnection connection, String sql, Object... parameters)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.getConnection().prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                String[] labels = new String[columnCount + 1];
                for (int i = 1; i <= columnCount; i++) {
                    labels[i] = metaData.getColumnLabel(i).toLowerCase(Locale.ROOT);
                }
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(labels[i], rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    protected static String getString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    protected static String getString(Map<String, Object> row, String key, String defaultValue) {
        String value = getString(row, key);
        return value == null ? defaultValue : value;
    }

    protected static int getInt(Map<String, Object> row, String key, int defaultValue) {
        Object value = row.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected static long getLong(Map<String, Object> row, String key, long defaultValue) {
        Object value = row.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected static boolean getBoolean(Map<String, Object> row, String key, boolean defaultValue) {
        Object value = row.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.equals("yes") || text.equals("true") || text.equals("t") || text.equals("y")) {
            return true;
        }
        if (text.equals("no") || text.equals("false") || text.equals("f") || text.equals("n")) {
            return false;
        }
        return defaultValue;
    }

    /** Canonical form of an identifier as reported by the engine. */
    protected static String normalize(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }

    /** Strips surrounding quotes and parentheses that engines add around default expressions. */
    protected static String cleanDefault(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        while (result.startsWith("(") && result.endsWith(")") && result.length() > 1) {
            String inner = result.substring(1, result.length() - 1);
            if (isBalanced(inner)) {
                result = inner.trim();
            } else {
                break;
            }
        }
        return result;
    }

    /**
     * @return true when the parentheses of {@code text} are properly nested and balanced.
     *
     *         <p>A text whose parentheses merely cancel out — {@code a) or (b} — is <em>not</em>
     *         balanced: stripping its first and last character would leave a broken expression.
     *         DuckDB reports the CHECK constraint of a disjunction exactly that way, as
     *         {@code (a IS NOT NULL) OR (b IS NOT NULL)}, and treating it as wrapped made the
     *         round-trip oracle report the constraint as lost.</p>
     */
    private static boolean isBalanced(String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth < 0) {
                        return false;
                    }
                }
            }
        }
        return depth == 0;
    }

    @Override
    public Set<IntrospectionCapability> getCapabilities() {
        return EnumSet.allOf(IntrospectionCapability.class);
    }
}
