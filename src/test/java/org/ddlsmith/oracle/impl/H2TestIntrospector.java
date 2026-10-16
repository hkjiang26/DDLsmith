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
 * <p>A minimal {@link org.ddlsmith.introspection.SchemaIntrospector} for H2, written for
 * {@link OracleSelfCheck} because the real engine modules are developed in parallel. It reads the
 * standard {@code INFORMATION_SCHEMA} views (COLUMNS, TABLES, TABLE_CONSTRAINTS, KEY_COLUMN_USAGE,
 * REFERENTIAL_CONSTRAINTS, CHECK_CONSTRAINTS, INDEXES, INDEX_COLUMNS, VIEWS).</p>
 *
 * <p>It doubles as a check that the frozen {@code SchemaIntrospector} interface is implementable.</p>
 */
public class H2TestIntrospector extends AbstractIntrospector {

    /** What this scaffolding can actually read back from an H2 catalog. */
    private static final Set<IntrospectionCapability> CAPABILITIES = EnumSet.of(
            IntrospectionCapability.COLUMNS,
            IntrospectionCapability.COLUMN_TYPES,
            IntrospectionCapability.NULLABILITY,
            IntrospectionCapability.DEFAULTS,
            IntrospectionCapability.IDENTITY,
            IntrospectionCapability.GENERATED_COLUMNS,
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
    public RelationalSchema introspect(DDLsmithDBConnection connection, String schemaName) throws SQLException {
        String namespace = schemaName == null || schemaName.isBlank() ? "PUBLIC" : schemaName;
        RelationalSchema schema = new RelationalSchema(namespace);
        Map<String, Table> byName = new LinkedHashMap<>();

        for (Map<String, Object> row : query(connection,
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'"
                        + " ORDER BY TABLE_NAME",
                namespace)) {
            String name = getString(row, "table_name");
            Table table = new Table(namespace, name);
            readColumns(connection, namespace, table);
            readConstraints(connection, namespace, table, byName);
            readIndexes(connection, namespace, table);
            schema.addTable(table);
            byName.put(normalize(name), table);
        }

        for (Map<String, Object> row : query(connection,
                "SELECT TABLE_NAME, VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ?"
                        + " ORDER BY TABLE_NAME",
                namespace)) {
            schema.addView(new View(getString(row, "table_name"), getString(row, "view_definition")));
        }
        return schema;
    }

    /* ------------------------------------------------------------------ pieces */

    private void readColumns(DDLsmithDBConnection connection, String namespace, Table table) throws SQLException {
        for (Map<String, Object> row : query(connection,
                "SELECT COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION,"
                        + " NUMERIC_SCALE, IS_NULLABLE, COLUMN_DEFAULT, IS_IDENTITY, IS_GENERATED,"
                        + " GENERATION_EXPRESSION FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                namespace, table.getName())) {
            DataType type = parseType(getString(row, "data_type"), getInt(row, "character_maximum_length", -1),
                    getInt(row, "numeric_precision", -1), getInt(row, "numeric_scale", -1));
            Column column = new Column(getString(row, "column_name"), type,
                    !"NO".equalsIgnoreCase(getString(row, "is_nullable", "YES")));
            String defaultValue = getString(row, "column_default");
            if (defaultValue != null) {
                column.setDefaultExpression(cleanDefault(defaultValue));
            }
            if ("YES".equalsIgnoreCase(getString(row, "is_identity", "NO"))) {
                column.setIdentity(true);
            }
            String generation = getString(row, "generation_expression");
            if (generation != null && !"NEVER".equalsIgnoreCase(getString(row, "is_generated", "NEVER"))) {
                column.setGeneratedExpression(generation);
            }
            table.addColumn(column);
        }
    }

    /**
     * Reads primary keys, unique constraints, check constraints and foreign keys. The foreign key's
     * target is resolved through the unique constraint it references, which is how the standard
     * catalog spells it.
     */
    private void readConstraints(DDLsmithDBConnection connection, String namespace, Table table,
            Map<String, Table> alreadyRead) throws SQLException {
        Map<String, String> typesByName = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection,
                "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS"
                        + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                namespace, table.getName())) {
            typesByName.put(getString(row, "constraint_name"), getString(row, "constraint_type"));
        }

        Map<String, List<String>> columnsByConstraint = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection,
                "SELECT CONSTRAINT_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE"
                        + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION",
                namespace, table.getName())) {
            columnsByConstraint.computeIfAbsent(getString(row, "constraint_name"), key -> new ArrayList<>())
                    .add(getString(row, "column_name"));
        }

        Map<String, Map<String, Object>> checkClauses = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection,
                "SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS"
                        + " WHERE CONSTRAINT_SCHEMA = ?",
                namespace)) {
            checkClauses.put(getString(row, "constraint_name"), row);
        }

        for (Map.Entry<String, String> entry : typesByName.entrySet()) {
            String name = entry.getKey();
            String type = entry.getValue() == null ? "" : entry.getValue().toUpperCase(Locale.ROOT);
            List<String> columns = columnsByConstraint.getOrDefault(name, new ArrayList<>());
            switch (type) {
            case "PRIMARY KEY":
                table.setPrimaryKey(new PrimaryKey(name, columns));
                break;
            case "UNIQUE":
                table.addUniqueConstraint(new UniqueConstraint(name, columns));
                break;
            case "CHECK":
                Map<String, Object> check = checkClauses.get(name);
                if (check != null) {
                    table.addCheckConstraint(new org.ddlsmith.common.schema.CheckConstraint(name,
                            getString(check, "check_clause")));
                }
                break;
            case "FOREIGN KEY":
                readForeignKey(connection, namespace, table, name, columns, alreadyRead);
                break;
            default:
                break;
            }
        }
    }

    private void readForeignKey(DDLsmithDBConnection connection, String namespace, Table table, String name,
            List<String> columns, Map<String, Table> alreadyRead) throws SQLException {
        List<Map<String, Object>> references = query(connection,
                "SELECT UNIQUE_CONSTRAINT_NAME, UPDATE_RULE, DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS"
                        + " WHERE CONSTRAINT_SCHEMA = ? AND CONSTRAINT_NAME = ?",
                namespace, name);
        if (references.isEmpty()) {
            return;
        }
        Map<String, Object> reference = references.get(0);
        String uniqueConstraint = getString(reference, "unique_constraint_name");
        String referencedTable = null;
        List<String> referencedColumns = new ArrayList<>();
        for (Map<String, Object> row : query(connection,
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS"
                        + " WHERE TABLE_SCHEMA = ? AND CONSTRAINT_NAME = ?",
                namespace, uniqueConstraint)) {
            referencedTable = getString(row, "table_name");
        }
        for (Map<String, Object> row : query(connection,
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE"
                        + " WHERE TABLE_SCHEMA = ? AND CONSTRAINT_NAME = ? ORDER BY ORDINAL_POSITION",
                namespace, uniqueConstraint)) {
            referencedColumns.add(getString(row, "column_name"));
        }
        if (referencedTable == null) {
            return;
        }
        ForeignKey foreignKey = new ForeignKey(columns, referencedTable, referencedColumns)
                .setName(name)
                .setOnDelete(ReferentialAction.fromSql(getString(reference, "delete_rule")))
                .setOnUpdate(ReferentialAction.fromSql(getString(reference, "update_rule")));
        foreignKey.setSelfReferential(normalize(referencedTable).equals(normalize(table.getName())));
        table.addForeignKey(foreignKey);
    }

    private void readIndexes(DDLsmithDBConnection connection, String namespace, Table table) throws SQLException {
        for (Map<String, Object> row : query(connection,
                "SELECT INDEX_NAME, INDEX_TYPE_NAME FROM INFORMATION_SCHEMA.INDEXES"
                        + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND IS_GENERATED = FALSE ORDER BY INDEX_NAME",
                namespace, table.getName())) {
            String indexName = getString(row, "index_name");
            List<String> columns = new ArrayList<>();
            for (Map<String, Object> columnRow : query(connection,
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS"
                            + " WHERE TABLE_SCHEMA = ? AND INDEX_NAME = ? ORDER BY ORDINAL_POSITION",
                    namespace, indexName)) {
                columns.add(getString(columnRow, "column_name"));
            }
            if (columns.contains(null)) {
                // an expression index; this scaffolding does not declare EXPRESSION_INDEXES
                continue;
            }
            boolean unique = "UNIQUE INDEX".equalsIgnoreCase(getString(row, "index_type_name", ""));
            table.addIndex(new Index(indexName, columns).setUnique(unique));
        }
    }

    /* ------------------------------------------------------------------ helpers */

    /** Parses the type H2 reports in {@code INFORMATION_SCHEMA.COLUMNS}. */
    static DataType parseType(String dataType, int characterLength, int numericPrecision, int numericScale) {
        if (dataType == null) {
            return DataType.text();
        }
        String type = dataType.trim().toUpperCase(Locale.ROOT);
        switch (type) {
        case "SMALLINT":
        case "TINYINT":
            return DataType.smallint();
        case "INTEGER":
        case "INT":
        case "MEDIUMINT":
            return DataType.integer();
        case "BIGINT":
            return DataType.bigint();
        case "NUMERIC":
        case "DECIMAL":
        case "DECFLOAT":
            int precision = numericPrecision > 0 ? numericPrecision : 10;
            int scale = numericScale > 0 ? numericScale : 0;
            return DataType.decimal(precision, scale);
        case "REAL":
            return DataType.real();
        case "DOUBLE PRECISION":
        case "FLOAT":
            return DataType.doublePrecision();
        case "CHARACTER":
        case "CHAR":
            return characterLength > 0 ? DataType.charType(characterLength) : DataType.text();
        case "CHARACTER VARYING":
        case "VARCHAR":
        case "VARCHAR2":
            return characterLength > 0 ? DataType.varchar(characterLength) : DataType.text();
        case "CHARACTER LARGE OBJECT":
        case "CLOB":
        case "TEXT":
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
        case "TIMESTAMP WITH TIME ZONE":
            return DataType.timestampWithTimeZone();
        case "BINARY LARGE OBJECT":
        case "BLOB":
            return DataType.blob();
        case "JSON":
            return DataType.json();
        case "UUID":
            return DataType.uuid();
        case "INTERVAL":
            return DataType.interval();
        default:
            return DataType.text();
        }
    }
}
