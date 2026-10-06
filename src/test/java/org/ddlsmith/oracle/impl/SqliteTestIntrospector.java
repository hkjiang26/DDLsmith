package org.ddlsmith.oracle.impl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.introspection.AbstractIntrospector;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;

/**
 * TEST SCAFFOLDING, not part of the deliverable.
 *
 * <p>A minimal {@link org.ddlsmith.introspection.SchemaIntrospector} for SQLite, written for
 * {@link OracleSelfCheck} because the real engine modules are developed in parallel. It reads
 * {@code sqlite_master}, {@code PRAGMA table_info}, {@code PRAGMA foreign_key_list} and
 * {@code PRAGMA index_list}, and extracts the {@code CHECK} constraints from the stored
 * {@code CREATE TABLE} text (SQLite has no catalog table for them).</p>
 *
 * <p>It doubles as a check that the frozen {@code SchemaIntrospector} interface is implementable:
 * everything it declares in {@link #getCapabilities()} is really read from the engine, and nothing
 * else.</p>
 */
public class SqliteTestIntrospector extends AbstractIntrospector {

    /** What this scaffolding can actually read back from a SQLite catalog. */
    private static final Set<IntrospectionCapability> CAPABILITIES = EnumSet.of(
            IntrospectionCapability.COLUMNS,
            IntrospectionCapability.COLUMN_TYPES,
            IntrospectionCapability.NULLABILITY,
            IntrospectionCapability.DEFAULTS,
            IntrospectionCapability.PRIMARY_KEYS,
            IntrospectionCapability.FOREIGN_KEYS,
            IntrospectionCapability.FOREIGN_KEY_ACTIONS,
            IntrospectionCapability.UNIQUE_CONSTRAINTS,
            IntrospectionCapability.CHECK_CONSTRAINTS,
            IntrospectionCapability.INDEXES,
            IntrospectionCapability.INDEX_UNIQUENESS,
            IntrospectionCapability.INDEX_COLUMNS,
            IntrospectionCapability.TABLE_EXISTENCE,
            IntrospectionCapability.VIEWS,
            IntrospectionCapability.VIEW_DEFINITIONS);

    @Override
    public Set<IntrospectionCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public RelationalSchema introspect(DDLSmithDBConnection connection, String schemaName) throws SQLException {
        String namespace = schemaName == null || schemaName.isBlank() ? "main" : schemaName;
        RelationalSchema schema = new RelationalSchema(namespace);
        Map<String, Table> byName = new LinkedHashMap<>();
        List<ForeignKey> unresolvedTargets = new ArrayList<>();

        for (Map<String, Object> row : query(connection,
                "SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            String name = getString(row, "name");
            String createSql = getString(row, "sql");
            Table table = new Table(namespace, name);

            List<Map<String, Object>> columnRows = query(connection, "PRAGMA table_info(" + literal(name) + ")");
            Map<Integer, String> primaryKeyByPosition = new java.util.TreeMap<>();
            for (Map<String, Object> columnRow : columnRows) {
                Column column = new Column(getString(columnRow, "name"), parseType(getString(columnRow, "type")),
                        getInt(columnRow, "notnull", 0) == 0);
                String defaultValue = getString(columnRow, "dflt_value");
                if (defaultValue != null) {
                    column.setDefaultExpression(cleanDefault(defaultValue));
                }
                table.addColumn(column);
                int pk = getInt(columnRow, "pk", 0);
                if (pk > 0) {
                    primaryKeyByPosition.put(pk, column.getName());
                }
            }
            if (!primaryKeyByPosition.isEmpty()) {
                table.setPrimaryKey(new PrimaryKey(new ArrayList<>(primaryKeyByPosition.values())));
            }

            readForeignKeys(connection, table, unresolvedTargets);
            readIndexes(connection, table);
            for (String expression : extractChecks(createSql)) {
                table.addCheckConstraint(new org.ddlsmith.common.schema.CheckConstraint(expression));
            }
            schema.addTable(table);
            byName.put(normalize(name), table);
        }

        // SQLite reports NULL as the referenced column when the FK omits it, which means "the primary
        // key of the referenced table"; resolve those now that every table has been read.
        for (ForeignKey foreignKey : unresolvedTargets) {
            Table referenced = byName.get(normalize(foreignKey.getReferencedTable()));
            if (referenced != null && referenced.getPrimaryKey() != null) {
                foreignKey.getReferencedColumns().addAll(referenced.getPrimaryKey().getColumns());
            }
        }

        for (Map<String, Object> row : query(connection,
                "SELECT name, sql FROM sqlite_master WHERE type = 'view' ORDER BY name")) {
            String name = getString(row, "name");
            String sql = getString(row, "sql");
            schema.addView(new View(name, selectPart(sql)));
        }
        return schema;
    }

    /* ------------------------------------------------------------------ pieces */

    private void readForeignKeys(DDLSmithDBConnection connection, Table table, List<ForeignKey> unresolvedTargets)
            throws SQLException {
        Map<Integer, List<Map<String, Object>>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection, "PRAGMA foreign_key_list(" + literal(table.getName()) + ")")) {
            byId.computeIfAbsent(getInt(row, "id", 0), key -> new ArrayList<>()).add(row);
        }
        for (List<Map<String, Object>> rows : byId.values()) {
            rows.sort((a, b) -> Integer.compare(getInt(a, "seq", 0), getInt(b, "seq", 0)));
            List<String> columns = new ArrayList<>();
            List<String> referencedColumns = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                columns.add(getString(row, "from"));
                referencedColumns.add(getString(row, "to"));
            }
            Map<String, Object> first = rows.get(0);
            ForeignKey foreignKey = new ForeignKey(columns, getString(first, "table"), referencedColumns)
                    .setOnDelete(ReferentialAction.fromSql(getString(first, "on_delete")))
                    .setOnUpdate(ReferentialAction.fromSql(getString(first, "on_update")));
            if (referencedColumns.contains(null)) {
                foreignKey.getReferencedColumns().clear();
                unresolvedTargets.add(foreignKey);
            }
            foreignKey.setSelfReferential(normalize(foreignKey.getReferencedTable()).equals(normalize(table.getName())));
            table.addForeignKey(foreignKey);
        }
    }

    private void readIndexes(DDLSmithDBConnection connection, Table table) throws SQLException {
        for (Map<String, Object> row : query(connection, "PRAGMA index_list(" + literal(table.getName()) + ")")) {
            String origin = getString(row, "origin", "c");
            if ("pk".equals(origin)) {
                // the index that implements the primary key; the primary key is reported separately
                continue;
            }
            String indexName = getString(row, "name");
            List<String> columns = new ArrayList<>();
            boolean expressionIndex = false;
            for (Map<String, Object> columnRow : query(connection, "PRAGMA index_info(" + literal(indexName) + ")")) {
                String columnName = getString(columnRow, "name");
                if (columnName == null) {
                    expressionIndex = true;
                } else {
                    columns.add(columnName);
                }
            }
            if (expressionIndex) {
                // this scaffolding cannot represent expression indexes (EXPRESSION_INDEXES is not
                // declared as a capability), so they are left out rather than reported wrongly
                continue;
            }
            if ("u".equals(origin)) {
                table.addUniqueConstraint(new UniqueConstraint(columns));
            } else {
                table.addIndex(new Index(indexName, columns).setUnique(getBoolean(row, "unique", false)));
            }
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String selectPart(String createViewSql) {
        if (createViewSql == null) {
            return null;
        }
        int as = createViewSql.toUpperCase(Locale.ROOT).indexOf(" AS ");
        return as < 0 ? createViewSql : createViewSql.substring(as + 4).trim();
    }

    /** Extracts the expressions of all {@code CHECK (...)} clauses of a stored CREATE TABLE text. */
    static List<String> extractChecks(String createSql) {
        List<String> result = new ArrayList<>();
        if (createSql == null) {
            return result;
        }
        int i = 0;
        while (i < createSql.length()) {
            char c = createSql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(createSql, i, c);
                continue;
            }
            if (c == '[') {
                int close = createSql.indexOf(']', i);
                i = close < 0 ? createSql.length() : close + 1;
                continue;
            }
            if (matchesKeyword(createSql, i, "CHECK")) {
                int open = i + "CHECK".length();
                while (open < createSql.length() && Character.isWhitespace(createSql.charAt(open))) {
                    open++;
                }
                if (open < createSql.length() && createSql.charAt(open) == '(') {
                    int close = matchingParenthesis(createSql, open);
                    if (close > 0) {
                        result.add(createSql.substring(open + 1, close).trim());
                        i = close + 1;
                        continue;
                    }
                }
            }
            i++;
        }
        return result;
    }

    private static boolean matchesKeyword(String text, int index, String keyword) {
        if (!text.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        int before = index - 1;
        if (before >= 0 && (Character.isLetterOrDigit(text.charAt(before)) || text.charAt(before) == '_')) {
            return false;
        }
        int after = index + keyword.length();
        return after >= text.length() || !(Character.isLetterOrDigit(text.charAt(after)) || text.charAt(after) == '_');
    }

    private static int skipQuoted(String text, int index, char quote) {
        int i = index + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    private static int matchingParenthesis(String text, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /** Parses the type text SQLite stores verbatim in the schema. */
    static DataType parseType(String declared) {
        if (declared == null || declared.isBlank()) {
            return DataType.text();
        }
        String type = declared.trim().toUpperCase(Locale.ROOT);
        String base = type;
        List<Integer> arguments = new ArrayList<>();
        int open = type.indexOf('(');
        if (open > 0 && type.endsWith(")")) {
            base = type.substring(0, open).trim();
            for (String part : type.substring(open + 1, type.length() - 1).split(",")) {
                try {
                    arguments.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException e) {
                    arguments.add(null);
                }
            }
        }
        if (base.contains("INT")) {
            if (base.contains("BIG")) {
                return DataType.bigint();
            }
            if (base.contains("SMALL") || base.contains("TINY")) {
                return DataType.smallint();
            }
            return DataType.integer();
        }
        if (base.contains("CHAR") || base.contains("CLOB") || base.contains("TEXT")) {
            if (!arguments.isEmpty() && arguments.get(0) != null && arguments.get(0) > 0) {
                boolean fixedLength = base.equals("CHAR") || base.equals("CHARACTER") || base.equals("NCHAR");
                return fixedLength ? DataType.charType(arguments.get(0)) : DataType.varchar(arguments.get(0));
            }
            return DataType.text();
        }
        if (base.contains("DEC") || base.contains("NUM")) {
            int precision = arguments.size() > 0 && arguments.get(0) != null ? arguments.get(0) : 10;
            int scale = arguments.size() > 1 && arguments.get(1) != null ? arguments.get(1) : 0;
            return DataType.decimal(precision, scale);
        }
        if (base.contains("BOOL")) {
            return DataType.bool();
        }
        if (base.contains("DATE")) {
            return DataType.date();
        }
        if (base.contains("TIME")) {
            return DataType.time();
        }
        if (base.contains("REAL") || base.contains("FLOA")) {
            return DataType.doublePrecision();
        }
        if (base.contains("BLOB") || base.equals("BINARY")) {
            return DataType.blob();
        }
        if (base.contains("JSON")) {
            return DataType.json();
        }
        if (base.contains("UUID")) {
            return DataType.uuid();
        }
        return DataType.text();
    }
}
