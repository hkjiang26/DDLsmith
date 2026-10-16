package org.ddlsmith.oracle.impl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ddlsmith.DDLsmithDBConnection;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.introspection.AbstractIntrospector;
import org.ddlsmith.introspection.SchemaIntrospector;

/**
 * TEST SCAFFOLDING - not part of DDLsmith.
 *
 * <p>A minimal SQLite introspector built on the {@code pragma_*} table-valued functions
 * ({@code pragma_table_info}, {@code pragma_foreign_key_list}, {@code pragma_index_list},
 * {@code pragma_index_info}). It deliberately does not report CHECK constraints (SQLite's catalog
 * does not expose them) or indexes, and says so through {@link #getCapabilities()} so that
 * {@link org.ddlsmith.introspection.SchemaNormalizer#erase} removes those aspects before a
 * comparison.</p>
 */
public class TestSqliteIntrospector extends AbstractIntrospector {

    static final Set<IntrospectionCapability> CAPABILITIES = EnumSet.of(
            IntrospectionCapability.COLUMNS,
            IntrospectionCapability.COLUMN_TYPES,
            IntrospectionCapability.NULLABILITY,
            IntrospectionCapability.DEFAULTS,
            IntrospectionCapability.PRIMARY_KEYS,
            IntrospectionCapability.FOREIGN_KEYS,
            IntrospectionCapability.FOREIGN_KEY_ACTIONS,
            IntrospectionCapability.UNIQUE_CONSTRAINTS,
            IntrospectionCapability.TABLE_EXISTENCE);

    private static final Pattern TYPE = Pattern.compile("^([A-Za-z_ ]+?)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?$");

    @Override
    public Set<IntrospectionCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public RelationalSchema introspect(DDLsmithDBConnection connection, String schemaName) throws SQLException {
        RelationalSchema schema = new RelationalSchema("sqlite");
        for (Map<String, Object> row : query(connection,
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            String name = getString(row, "name");
            if (name == null) {
                continue;
            }
            schema.addTable(introspectTable(connection, name));
        }
        resolveImplicitTargets(schema);
        return schema;
    }

    private Table introspectTable(DDLsmithDBConnection connection, String name) throws SQLException {
        Table table = new Table(name);
        Map<Integer, String> primaryKey = new TreeMap<>();
        for (Map<String, Object> row : query(connection, "SELECT * FROM pragma_table_info(?)", name)) {
            String columnName = getString(row, "name");
            Column column = new Column(columnName, parseType(getString(row, "type")),
                    getInt(row, "notnull", 0) == 0);
            String defaultValue = getString(row, "dflt_value");
            if (defaultValue != null) {
                column.setDefaultExpression(cleanDefault(defaultValue));
            }
            table.addColumn(column);
            int position = getInt(row, "pk", 0);
            if (position > 0) {
                primaryKey.put(position, columnName);
            }
        }
        if (!primaryKey.isEmpty()) {
            table.setPrimaryKey(new PrimaryKey(new ArrayList<>(primaryKey.values())));
        }
        for (Map<String, Object> row : query(connection, "SELECT * FROM pragma_index_list(?)", name)) {
            if (getInt(row, "unique", 0) != 1 || !"u".equals(getString(row, "origin"))) {
                continue;
            }
            String indexName = getString(row, "name");
            List<String> columns = indexColumns(connection, indexName);
            if (!columns.isEmpty()) {
                table.addUniqueConstraint(new UniqueConstraint(indexName, columns));
            }
        }
        Map<Integer, List<Map<String, Object>>> grouped = new TreeMap<>();
        for (Map<String, Object> row : query(connection, "SELECT * FROM pragma_foreign_key_list(?)", name)) {
            grouped.computeIfAbsent(getInt(row, "id", 0), key -> new ArrayList<>()).add(row);
        }
        for (List<Map<String, Object>> rows : grouped.values()) {
            rows.sort(java.util.Comparator.comparingInt(row -> getInt(row, "seq", 0)));
            Map<String, Object> first = rows.get(0);
            List<String> columns = new ArrayList<>();
            List<String> referenced = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                columns.add(getString(row, "from"));
                referenced.add(getString(row, "to"));
            }
            ForeignKey foreignKey = new ForeignKey(columns, getString(first, "table"), referenced)
                    .setOnDelete(ReferentialAction.fromSql(getString(first, "on_delete")))
                    .setOnUpdate(ReferentialAction.fromSql(getString(first, "on_update")));
            table.addForeignKey(foreignKey);
        }
        return table;
    }

    private List<String> indexColumns(DDLsmithDBConnection connection, String indexName) throws SQLException {
        List<Map<String, Object>> rows = query(connection, "SELECT * FROM pragma_index_info(?)", indexName);
        rows.sort(java.util.Comparator.comparingInt(row -> getInt(row, "seqno", 0)));
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String columnName = getString(row, "name");
            if (columnName != null) {
                columns.add(columnName);
            }
        }
        return columns;
    }

    /** SQLite reports a NULL target column when the FK omits the referenced column list. */
    private void resolveImplicitTargets(RelationalSchema schema) {
        for (Table table : schema.getTables()) {
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                Table parent = schema.getTable(foreignKey.getReferencedTable());
                if (parent == null || parent.getPrimaryKey() == null) {
                    continue;
                }
                List<String> referenced = foreignKey.getReferencedColumns();
                for (int i = 0; i < referenced.size(); i++) {
                    if (referenced.get(i) == null && i < parent.getPrimaryKey().getColumns().size()) {
                        referenced.set(i, parent.getPrimaryKey().getColumns().get(i));
                    }
                }
            }
        }
    }

    static DataType parseType(String declared) {
        if (declared == null || declared.isBlank()) {
            return DataType.text();
        }
        Matcher matcher = TYPE.matcher(declared.trim());
        if (!matcher.matches()) {
            return DataType.text();
        }
        String base = matcher.group(1).trim().toUpperCase(Locale.ROOT);
        Integer first = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
        Integer second = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
        switch (base) {
        case "INT":
        case "INTEGER":
            return DataType.integer();
        case "BIGINT":
            return DataType.bigint();
        case "SMALLINT":
            return DataType.smallint();
        case "NUMERIC":
        case "DECIMAL":
            return DataType.decimal(first == null ? 10 : first, second == null ? 0 : second);
        case "REAL":
            return DataType.real();
        case "DOUBLE":
        case "DOUBLE PRECISION":
        case "FLOAT":
            return DataType.doublePrecision();
        case "CHAR":
        case "CHARACTER":
            return DataType.charType(first == null ? 1 : first);
        case "VARCHAR":
        case "NVARCHAR":
        case "CHARACTER VARYING":
            return first == null ? DataType.text() : DataType.varchar(first);
        case "TEXT":
        case "CLOB":
            return DataType.text();
        case "BOOLEAN":
        case "BOOL":
            return DataType.bool();
        case "DATE":
            return DataType.date();
        case "TIME":
            return DataType.time();
        case "TIMESTAMP":
        case "DATETIME":
            return DataType.timestamp();
        case "BLOB":
            return DataType.blob();
        default:
            return DataType.text();
        }
    }
}
