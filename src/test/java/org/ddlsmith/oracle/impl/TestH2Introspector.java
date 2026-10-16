package org.ddlsmith.oracle.impl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.DDLsmithDBConnection;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.introspection.AbstractIntrospector;

/**
 * TEST SCAFFOLDING - not part of DDLsmith.
 *
 * <p>A minimal H2 introspector reading {@code INFORMATION_SCHEMA}: tables, columns, table
 * constraints, key column usage, check constraints and referential constraints (for the referential
 * actions). Indexes are deliberately not reported, so the INDEXES capability is absent.</p>
 */
public class TestH2Introspector extends AbstractIntrospector {

    static final Set<IntrospectionCapability> CAPABILITIES = EnumSet.of(
            IntrospectionCapability.COLUMNS,
            IntrospectionCapability.COLUMN_TYPES,
            IntrospectionCapability.NULLABILITY,
            IntrospectionCapability.DEFAULTS,
            IntrospectionCapability.PRIMARY_KEYS,
            IntrospectionCapability.FOREIGN_KEYS,
            IntrospectionCapability.FOREIGN_KEY_ACTIONS,
            IntrospectionCapability.UNIQUE_CONSTRAINTS,
            IntrospectionCapability.CHECK_CONSTRAINTS,
            IntrospectionCapability.TABLE_EXISTENCE);

    @Override
    public Set<IntrospectionCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public RelationalSchema introspect(DDLsmithDBConnection connection, String schemaName) throws SQLException {
        String schema = schemaName == null ? "PUBLIC" : schemaName;
        RelationalSchema result = new RelationalSchema(schema);
        for (Map<String, Object> row : query(connection,
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = "
                        + "'BASE TABLE' ORDER BY TABLE_NAME",
                schema)) {
            String name = getString(row, "table_name");
            if (name != null) {
                result.addTable(introspectTable(connection, schema, name));
            }
        }
        return result;
    }

    private Table introspectTable(DDLsmithDBConnection connection, String schema, String name) throws SQLException {
        Table table = new Table(name);
        for (Map<String, Object> row : query(connection,
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, "
                        + "IS_NULLABLE, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? "
                        + "AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                schema, name)) {
            Column column = new Column(getString(row, "column_name"),
                    parseType(getString(row, "data_type"), row), getBoolean(row, "is_nullable", true));
            String defaultValue = getString(row, "column_default");
            if (defaultValue != null) {
                column.setDefaultExpression(cleanDefault(defaultValue));
            }
            table.addColumn(column);
        }
        Map<String, List<String>> keyColumns = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection,
                "SELECT CONSTRAINT_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = ? "
                        + "AND TABLE_NAME = ? ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION",
                schema, name)) {
            keyColumns.computeIfAbsent(getString(row, "constraint_name"), key -> new ArrayList<>())
                    .add(getString(row, "column_name"));
        }
        Map<String, String> constraintTypes = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection,
                "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE "
                        + "TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                schema, name)) {
            constraintTypes.put(getString(row, "constraint_name"), getString(row, "constraint_type"));
        }
        for (Map.Entry<String, String> entry : constraintTypes.entrySet()) {
            String constraintName = entry.getKey();
            String type = entry.getValue() == null ? "" : entry.getValue().toUpperCase(Locale.ROOT);
            switch (type) {
            case "PRIMARY KEY":
                table.setPrimaryKey(new PrimaryKey(constraintName,
                        keyColumns.getOrDefault(constraintName, List.of())));
                break;
            case "UNIQUE":
                table.addUniqueConstraint(new UniqueConstraint(constraintName,
                        keyColumns.getOrDefault(constraintName, List.of())));
                break;
            case "CHECK":
                table.addCheckConstraint(new CheckConstraint(constraintName, checkClause(connection, schema,
                        constraintName)));
                break;
            case "FOREIGN KEY":
                addForeignKey(connection, schema, table, constraintName,
                        keyColumns.getOrDefault(constraintName, List.of()));
                break;
            default:
                break;
            }
        }
        return table;
    }

    private String checkClause(DDLsmithDBConnection connection, String schema, String constraintName)
            throws SQLException {
        List<Map<String, Object>> rows = query(connection,
                "SELECT CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = ? AND "
                        + "CONSTRAINT_NAME = ?",
                schema, constraintName);
        return rows.isEmpty() ? null : cleanDefault(getString(rows.get(0), "check_clause"));
    }

    private void addForeignKey(DDLsmithDBConnection connection, String schema, Table table, String constraintName,
            List<String> childColumns) throws SQLException {
        List<Map<String, Object>> rows = query(connection,
                "SELECT UPDATE_RULE, DELETE_RULE, UNIQUE_CONSTRAINT_NAME FROM "
                        + "INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = ? AND "
                        + "CONSTRAINT_NAME = ?",
                schema, constraintName);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> row = rows.get(0);
        String uniqueName = getString(row, "unique_constraint_name");
        List<Map<String, Object>> parents = query(connection,
                "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA = ? "
                        + "AND CONSTRAINT_NAME = ? ORDER BY ORDINAL_POSITION",
                schema, uniqueName);
        List<String> referencedColumns = new ArrayList<>();
        String referencedTable = null;
        for (Map<String, Object> parent : parents) {
            referencedTable = getString(parent, "table_name");
            referencedColumns.add(getString(parent, "column_name"));
        }
        if (referencedTable == null) {
            return;
        }
        table.addForeignKey(new ForeignKey(childColumns, referencedTable, referencedColumns)
                .setName(constraintName)
                .setOnDelete(ReferentialAction.fromSql(getString(row, "delete_rule")))
                .setOnUpdate(ReferentialAction.fromSql(getString(row, "update_rule"))));
    }

    static DataType parseType(String dataType, Map<String, Object> row) {
        if (dataType == null) {
            return DataType.text();
        }
        Integer length = row.get("character_maximum_length") == null ? null
                : ((Number) row.get("character_maximum_length")).intValue();
        Integer precision = row.get("numeric_precision") == null ? null
                : ((Number) row.get("numeric_precision")).intValue();
        Integer scale = row.get("numeric_scale") == null ? null : ((Number) row.get("numeric_scale")).intValue();
        switch (dataType.toUpperCase(Locale.ROOT)) {
        case "TINYINT":
        case "SMALLINT":
            return DataType.smallint();
        case "INTEGER":
        case "INT":
            return DataType.integer();
        case "BIGINT":
            return DataType.bigint();
        case "NUMERIC":
        case "DECIMAL":
            return DataType.decimal(precision == null ? 10 : precision, scale == null ? 0 : scale);
        case "REAL":
            return DataType.real();
        case "DOUBLE PRECISION":
        case "DOUBLE":
        case "FLOAT":
            return DataType.doublePrecision();
        case "CHARACTER":
        case "CHAR":
            return DataType.charType(length == null ? 1 : length);
        case "CHARACTER VARYING":
        case "VARCHAR":
        case "VARCHAR2":
            return length == null ? DataType.text() : DataType.varchar(length);
        case "CHARACTER LARGE OBJECT":
        case "CLOB":
        case "TEXT":
            return DataType.text();
        case "BOOLEAN":
            return DataType.bool();
        case "DATE":
            return DataType.date();
        case "TIME":
            return DataType.time();
        case "TIMESTAMP":
        case "TIMESTAMP WITH TIME ZONE":
            return DataType.timestamp();
        case "BINARY VARYING":
        case "VARBINARY":
        case "BLOB":
            return DataType.blob();
        default:
            return DataType.text();
        }
    }
}
