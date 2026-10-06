package org.ddlsmith.dbms.h2;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.introspection.AbstractIntrospector;

/**
 * Reads an H2 2.3.232 schema back from {@code INFORMATION_SCHEMA}.
 *
 * <p>The catalog tables used, and why:</p>
 * <ul>
 *   <li>{@code TABLES} — table names, {@code TABLE_TYPE} (base table / global or local temporary
 *       table; views are read from {@code VIEWS} instead) and the table comment in
 *       {@code REMARKS}.</li>
 *   <li>{@code COLUMNS} — everything about a column. {@code DECLARED_DATA_TYPE} is preferred over
 *       {@code DATA_TYPE} when it is present; empirically H2 2.3.232 only fills it in for the
 *       numeric types, where it agrees with {@code DATA_TYPE} except that a {@code DECIMAL(p,s)}
 *       column reports {@code DATA_TYPE = NUMERIC} and {@code DECLARED_DATA_TYPE = DECIMAL} — both
 *       of which parse to the same canonical type. {@code CHARACTER_MAXIMUM_LENGTH} supplies the
 *       length of {@code CHARACTER}/{@code CHARACTER VARYING} columns (H2 reports a bare type name
 *       there), {@code NUMERIC_PRECISION}/{@code NUMERIC_SCALE} the parameters of
 *       {@code NUMERIC}, {@code INTERVAL_TYPE} the qualifier of an {@code INTERVAL}, and
 *       {@code DTD_IDENTIFIER} the key into {@code ELEMENT_TYPES}/{@code ENUM_VALUES}.</li>
 *   <li>{@code ELEMENT_TYPES} — the element type of an {@code ARRAY} column. {@code COLUMNS}
 *       reports only {@code DATA_TYPE = ARRAY} plus a numeric {@code DTD_IDENTIFIER}, which is
 *       matched against {@code ELEMENT_TYPES.COLLECTION_TYPE_IDENTIFIER}. Without this the element
 *       type of an array would be unrecoverable and the type round trip would fail.</li>
 *   <li>{@code ENUM_VALUES} — the value list of an {@code ENUM} column, matched through
 *       {@code ENUM_IDENTIFIER} = {@code COLUMNS.DTD_IDENTIFIER} and ordered by
 *       {@code VALUE_ORDINAL}. Note that H2 reports {@code CONSTRAINT_TYPE = 'FOREIGN KEY'} (not the
 *       standard {@code 'REFERENTIAL'}) and that {@code CONSTRAINT_TYPE = 'CHECK'} also covers
 *       column-level {@code CHECK} clauses, which H2 stores as table constraints.</li>
 *   <li>{@code TABLE_CONSTRAINTS} + {@code KEY_COLUMN_USAGE} — primary key, unique and foreign key
 *       columns; {@code POSITION_IN_UNIQUE_CONSTRAINT} links each foreign key column to its ordinal
 *       position inside the referenced unique/primary key constraint.</li>
 *   <li>{@code REFERENTIAL_CONSTRAINTS} — {@code UNIQUE_CONSTRAINT_NAME} (which yields the
 *       referenced table through {@code TABLE_CONSTRAINTS}) and the {@code UPDATE_RULE} /
 *       {@code DELETE_RULE} referential actions.</li>
 *   <li>{@code CHECK_CONSTRAINTS} — the check expressions.</li>
 *   <li>{@code INDEXES} + {@code INDEX_COLUMNS} — user indexes only. H2 marks the backing index of
 *       every primary key, unique constraint and foreign key with {@code IS_GENERATED = TRUE}, so
 *       those are skipped; uniqueness comes from {@code INDEX_COLUMNS.IS_UNIQUE}, which is more
 *       reliable than {@code INDEX_TYPE_NAME} (a hash index reports {@code HASH INDEX}).</li>
 *   <li>{@code VIEWS} / {@code SEQUENCES} — view definitions and sequence options.</li>
 * </ul>
 *
 * <p>Three normalizations are applied because the catalog does not preserve the declared text:</p>
 * <ol>
 *   <li><b>Referential actions.</b> H2 collapses {@code NO ACTION} and {@code RESTRICT} into one
 *       behaviour and reports {@code RESTRICT} for both (and for a foreign key with no action
 *       clause at all). Since the two are indistinguishable in this engine, the reported
 *       {@code RESTRICT} is mapped back to {@link ReferentialAction#NO_ACTION}, which is
 *       {@link ForeignKey}'s default. {@link #supports(DDLFeature)} reports {@code FK_RESTRICT} as
 *       not introspectable for the same reason.</li>
 *   <li><b>Expressions.</b> H2 re-prints parsed expressions instead of storing their text: it wraps
 *       every boolean and comparison operand in parentheses and adds an explicit cast around a
 *       literal whose type differs from the column's. {@link H2ExpressionNormalizer} removes
 *       exactly those two, for check clauses, column defaults and view definitions alike. Rewrites
 *       that change the expression itself (negation elimination, operand reordering, function-name
 *       canonicalization, constant folding) are not undone and are reported by {@code SelfCheck}.</li>
 *   <li><b>View definitions.</b> H2 qualifies every referenced object with its schema
 *       ({@code FROM "PUBLIC"."EMP"}); the schema prefix of the introspected schema is stripped so
 *       that a view created from an unqualified query compares equal.</li>
 * </ol>
 *
 * <p>Two further catalog limitations are handled explicitly: sequence bounds are reported as the
 * <em>effective</em> bounds, so H2's defaults ({@code 1}/{@code Long.MAX_VALUE} for an ascending
 * sequence) are mapped back to {@code null}; and a column's {@code COLLATION_NAME} is only reported
 * when it is not H2's default {@code OFF}.</p>
 */
public class H2Introspector extends AbstractIntrospector {

    private static final String DEFAULT_SCHEMA = "public";

    /**
     * Everything H2's catalog exposes, except {@code PARTIAL_INDEXES} and {@code EXPRESSION_INDEXES}:
     * H2 rejects {@code WHERE} predicates on indexes and index expressions
     * ({@code CREATE INDEX i ON t((a + 1))} is a syntax error).
     */
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
            IntrospectionCapability.VIEWS,
            IntrospectionCapability.VIEW_DEFINITIONS,
            IntrospectionCapability.SEQUENCES,
            IntrospectionCapability.COMMENTS,
            IntrospectionCapability.TABLE_EXISTENCE);

    private final H2TypeMapper typeMapper = new H2TypeMapper();

    @Override
    public Set<IntrospectionCapability> getCapabilities() {
        return CAPABILITIES;
    }

    /**
     * DDL features whose effect this catalog cannot report back. The oracle can consult this to
     * avoid blaming H2 for information it does not keep.
     */
    @Override
    public boolean supports(DDLFeature feature) {
        switch (feature) {
        case DEFERRABLE_FK:
        case INITIALLY_DEFERRED:
            // H2 parses DEFERRABLE but ignores it: IS_DEFERRABLE stays NO for every constraint
        case MATCH_FULL:
            // not supported by H2 at all
        case FK_RESTRICT:
            // indistinguishable from NO ACTION in the catalog
        case MATERIALIZED_VIEW:
        case VIEW_CHECK_OPTION:
        case COLLATION:
        case GENERATED_STORED:
        case GENERATED_VIRTUAL:
        case INDEX_METHOD:
        case EXPRESSION_INDEX:
        case PARTIAL_INDEX:
        case INCLUDE_COLUMNS:
        case INDEX_DESCENDING:
            // Index has no ordering field and the access method is not reported back
        case TYPE_DOMAIN:
            // a domain column is reported with its base type, so the domain itself is invisible
            return false;
        default:
            return true;
        }
    }

    /* ------------------------------------------------------------------ entry point */

    @Override
    public RelationalSchema introspect(DDLSmithDBConnection connection, String schemaName) throws SQLException {
        String schema = schemaName == null || schemaName.isBlank() ? DEFAULT_SCHEMA : schemaName;
        RelationalSchema result = new RelationalSchema(schema);
        Map<String, Table> tables = readTables(connection, schema, result);
        Map<String, Map<String, Object>> elementTypes = readElementTypes(connection, schema);
        Map<String, List<String>> enumValues = readEnumValues(connection, schema);
        readColumns(connection, schema, tables, elementTypes, enumValues);
        readConstraints(connection, schema, tables);
        readIndexes(connection, schema, tables);
        readViews(connection, schema, result);
        readSequences(connection, schema, result);
        return result;
    }

    /* ------------------------------------------------------------------ tables */

    private Map<String, Table> readTables(DDLSmithDBConnection connection, String schema, RelationalSchema result)
            throws SQLException {
        Map<String, Table> tables = new LinkedHashMap<>();
        List<Map<String, Object>> rows = query(connection,
                "SELECT TABLE_NAME, TABLE_TYPE, REMARKS FROM INFORMATION_SCHEMA.TABLES"
                        + " WHERE UPPER(TABLE_SCHEMA) = UPPER(?) ORDER BY TABLE_NAME",
                schema);
        for (Map<String, Object> row : rows) {
            String type = getString(row, "table_type", "");
            if (!type.toUpperCase(Locale.ROOT).contains("TABLE")) {
                // views are read from INFORMATION_SCHEMA.VIEWS, synonyms are not tables
                continue;
            }
            String name = lower(getString(row, "table_name"));
            Table table = new Table(normalize(getString(row, "table_schema")), name);
            table.setTemporary(type.toUpperCase(Locale.ROOT).contains("TEMPORARY"));
            table.setComment(getString(row, "remarks"));
            tables.put(name, table);
            result.addTable(table);
        }
        return tables;
    }

    /* ------------------------------------------------------------------ columns */

    private void readColumns(DDLSmithDBConnection connection, String schema, Map<String, Table> tables,
            Map<String, Map<String, Object>> elementTypes, Map<String, List<String>> enumValues) throws SQLException {
        List<Map<String, Object>> rows = query(connection,
                "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_DEFAULT, IS_NULLABLE, DATA_TYPE,"
                        + " DECLARED_DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE,"
                        + " INTERVAL_TYPE, DTD_IDENTIFIER, IS_IDENTITY, IS_GENERATED, GENERATION_EXPRESSION,"
                        + " COLLATION_NAME, REMARKS"
                        + " FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_SCHEMA) = UPPER(?)"
                        + " ORDER BY TABLE_NAME, ORDINAL_POSITION",
                schema);
        for (Map<String, Object> row : rows) {
            Table table = tables.get(lower(getString(row, "table_name")));
            if (table == null) {
                continue;
            }
            DataType type = resolveColumnType(lower(getString(row, "table_name")), row, elementTypes, enumValues);
            boolean nullable = !"NO".equalsIgnoreCase(getString(row, "is_nullable", "YES"));
            Column column = new Column(lower(getString(row, "column_name")), type, nullable);
            column.setOrdinalPosition(getInt(row, "ordinal_position", table.getColumns().size() + 1));
            if (getBoolean(row, "is_identity", false)) {
                column.setIdentity(true);
            }
            if ("ALWAYS".equalsIgnoreCase(getString(row, "is_generated", "NEVER"))) {
                column.setGeneratedExpression(getString(row, "generation_expression"));
                // H2 generated columns are always virtual: STORED and VIRTUAL are syntax errors
                column.setGeneratedStored(false);
            }
            column.setDefaultExpression(H2ExpressionNormalizer.normalize(cleanDefault(
                    getString(row, "column_default"))));
            String collation = getString(row, "collation_name");
            if (collation != null && !collation.isBlank() && !collation.equalsIgnoreCase("OFF")) {
                column.setCollation(collation);
            }
            column.setComment(getString(row, "remarks"));
            table.addColumn(column);
        }
    }

    private DataType resolveColumnType(String tableName, Map<String, Object> row,
            Map<String, Map<String, Object>> elementTypes, Map<String, List<String>> enumValues) {
        String dataType = getString(row, "data_type", "");
        if ("ARRAY".equalsIgnoreCase(dataType)) {
            Map<String, Object> element = elementTypes.get(key(tableName, stringValue(row.get("dtd_identifier"))));
            if (element == null) {
                return null;
            }
            DataType elementType = resolveElementType(tableName, element, elementTypes, enumValues);
            return elementType == null ? null : DataType.arrayOf(elementType);
        }
        if ("ENUM".equalsIgnoreCase(dataType)) {
            return enumType(tableName, stringValue(row.get("dtd_identifier")), enumValues);
        }
        return resolveScalarType(row);
    }

    private DataType resolveElementType(String tableName, Map<String, Object> row,
            Map<String, Map<String, Object>> elementTypes, Map<String, List<String>> enumValues) {
        String dataType = getString(row, "data_type", "");
        if ("ENUM".equalsIgnoreCase(dataType)) {
            return enumType(tableName, stringValue(row.get("dtd_identifier")), enumValues);
        }
        if ("ARRAY".equalsIgnoreCase(dataType)) {
            Map<String, Object> nested = elementTypes.get(key(tableName, stringValue(row.get("dtd_identifier"))));
            if (nested == null) {
                return null;
            }
            DataType nestedType = resolveElementType(tableName, nested, elementTypes, enumValues);
            return nestedType == null ? null : DataType.arrayOf(nestedType);
        }
        return resolveScalarType(row);
    }

    /** Resolves an {@code ENUM} value list through {@code ENUM_VALUES}, ordered by value ordinal. */
    private static DataType enumType(String tableName, String identifier, Map<String, List<String>> enumValues) {
        List<String> values = enumValues.get(key(tableName, identifier));
        return values == null || values.isEmpty() ? null : DataType.enumOf(values);
    }

    /** Builds the engine type name of a scalar column and parses it with the shared mapper. */
    private DataType resolveScalarType(Map<String, Object> row) {
        String base = getString(row, "declared_data_type");
        if (base == null || base.isBlank()) {
            base = getString(row, "data_type");
        }
        if (base == null || base.isBlank()) {
            return null;
        }
        String keyword = base.trim().toUpperCase(Locale.ROOT);
        switch (keyword) {
        case "CHARACTER VARYING":
        case "CHAR VARYING":
        case "VARCHAR":
        case "NVARCHAR":
        case "LONGVARCHAR": {
            Long length = longValue(row.get("character_maximum_length"));
            return typeMapper.parse(usableLength(length) ? "VARCHAR(" + length + ")" : "VARCHAR");
        }
        case "CHARACTER":
        case "CHAR":
        case "NCHAR":
        case "BPCHAR": {
            Long length = longValue(row.get("character_maximum_length"));
            return typeMapper.parse(usableLength(length) ? "CHARACTER(" + length + ")" : "CHARACTER");
        }
        case "BINARY VARYING":
        case "VARBINARY":
        case "RAW":
        case "LONGVARBINARY": {
            Long length = longValue(row.get("character_maximum_length"));
            return typeMapper.parse(usableLength(length) ? "BINARY VARYING(" + length + ")" : "BINARY VARYING");
        }
        case "NUMERIC":
        case "DECIMAL":
        case "DEC": {
            Integer precision = intValue(row.get("numeric_precision"));
            if (precision == null) {
                return typeMapper.parse(base);
            }
            Integer scale = intValue(row.get("numeric_scale"));
            return typeMapper.parse("DECIMAL(" + precision + "," + (scale == null ? 0 : scale) + ")");
        }
        case "INTERVAL": {
            String intervalType = getString(row, "interval_type");
            return typeMapper.parse(intervalType == null || intervalType.isBlank()
                    ? H2TypeMapper.DEFAULT_INTERVAL_TYPE
                    : "INTERVAL " + intervalType);
        }
        default:
            return typeMapper.parse(base);
        }
    }

    private static boolean usableLength(Long length) {
        return length != null && length > 0 && length <= Integer.MAX_VALUE;
    }

    /* ------------------------------------------------------------------ constraints */

    private void readConstraints(DDLSmithDBConnection connection, String schema, Map<String, Table> tables)
            throws SQLException {
        Map<String, String> constraintTable = new LinkedHashMap<>();
        Map<String, String> constraintType = new LinkedHashMap<>();
        List<Map<String, Object>> constraints = query(connection,
                "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE, TABLE_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS"
                        + " WHERE UPPER(TABLE_SCHEMA) = UPPER(?) ORDER BY TABLE_NAME, CONSTRAINT_NAME",
                schema);
        for (Map<String, Object> row : constraints) {
            String name = lower(getString(row, "constraint_name"));
            constraintTable.put(name, lower(getString(row, "table_name")));
            constraintType.put(name, getString(row, "constraint_type", "").toUpperCase(Locale.ROOT));
        }

        Map<String, List<Map<String, Object>>> keyColumns = new TreeMap<>();
        List<Map<String, Object>> usages = query(connection,
                "SELECT CONSTRAINT_NAME, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, POSITION_IN_UNIQUE_CONSTRAINT"
                        + " FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE UPPER(TABLE_SCHEMA) = UPPER(?)"
                        + " ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION",
                schema);
        for (Map<String, Object> row : usages) {
            keyColumns.computeIfAbsent(lower(getString(row, "constraint_name")), k -> new ArrayList<>()).add(row);
        }

        Map<String, Map<String, Object>> referential = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection,
                "SELECT CONSTRAINT_NAME, UNIQUE_CONSTRAINT_NAME, UPDATE_RULE, DELETE_RULE"
                        + " FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE UPPER(CONSTRAINT_SCHEMA) = UPPER(?)",
                schema)) {
            referential.put(lower(getString(row, "constraint_name")), row);
        }

        Map<String, String> checkClauses = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection,
                "SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS"
                        + " WHERE UPPER(CONSTRAINT_SCHEMA) = UPPER(?)",
                schema)) {
            checkClauses.put(lower(getString(row, "constraint_name")), getString(row, "check_clause"));
        }

        for (Map.Entry<String, String> entry : constraintType.entrySet()) {
            String name = entry.getKey();
            String tableName = constraintTable.get(name);
            Table table = tables.get(tableName);
            if (table == null) {
                continue;
            }
            List<String> columns = columnsOf(keyColumns.get(name));
            switch (entry.getValue()) {
            case "PRIMARY KEY":
                if (!columns.isEmpty()) {
                    table.setPrimaryKey(new PrimaryKey(name, columns));
                }
                break;
            case "UNIQUE":
                if (!columns.isEmpty()) {
                    table.addUniqueConstraint(new UniqueConstraint(name, columns));
                }
                break;
            case "CHECK": {
                String clause = checkClauses.get(name);
                if (clause != null) {
                    table.addCheckConstraint(new CheckConstraint(name, H2ExpressionNormalizer.normalize(clause)));
                }
                break;
            }
            case "FOREIGN KEY":
            case "REFERENTIAL":
                ForeignKey foreignKey = buildForeignKey(name, table, keyColumns, constraintTable, referential);
                if (foreignKey != null) {
                    table.addForeignKey(foreignKey);
                }
                break;
            default:
                // NOT NULL / DOMAIN constraints are represented on the column itself
                break;
            }
        }
    }

    private ForeignKey buildForeignKey(String name, Table table,
            Map<String, List<Map<String, Object>>> keyColumns, Map<String, String> constraintTable,
            Map<String, Map<String, Object>> referential) {
        List<Map<String, Object>> rows = keyColumns.get(name);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, Object> reference = referential.get(name);
        if (reference == null) {
            return null;
        }
        String uniqueConstraint = lower(getString(reference, "unique_constraint_name"));
        String referencedTable = constraintTable.get(uniqueConstraint);
        List<Map<String, Object>> referencedRows = keyColumns.get(uniqueConstraint);
        if (referencedTable == null || referencedRows == null) {
            return null;
        }
        List<String> columns = new ArrayList<>();
        List<String> referencedColumns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int position = getInt(row, "position_in_unique_constraint", 0);
            Map<String, Object> target = rowAt(referencedRows, position);
            if (target == null) {
                return null;
            }
            columns.add(lower(getString(row, "column_name")));
            referencedColumns.add(lower(getString(target, "column_name")));
        }
        ForeignKey foreignKey = new ForeignKey(columns, referencedTable, referencedColumns);
        foreignKey.setName(name);
        foreignKey.setOnDelete(referentialAction(getString(reference, "delete_rule")));
        foreignKey.setOnUpdate(referentialAction(getString(reference, "update_rule")));
        foreignKey.setSelfReferential(table.getName().equalsIgnoreCase(referencedTable));
        return foreignKey;
    }

    private static Map<String, Object> rowAt(List<Map<String, Object>> rows, int ordinalPosition) {
        for (Map<String, Object> row : rows) {
            if (getInt(row, "ordinal_position", -1) == ordinalPosition) {
                return row;
            }
        }
        return null;
    }

    private static List<String> columnsOf(List<Map<String, Object>> rows) {
        List<String> columns = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                columns.add(lower(getString(row, "column_name")));
            }
        }
        return columns;
    }

    /**
     * H2 reports {@code RESTRICT} both for an explicit {@code ON DELETE RESTRICT} and for
     * {@code NO ACTION} (including a foreign key without any action clause), so the two cannot be
     * told apart and the reported value is mapped back to {@link ReferentialAction#NO_ACTION}.
     */
    private static ReferentialAction referentialAction(String rule) {
        ReferentialAction action = ReferentialAction.fromSql(rule);
        return action == ReferentialAction.RESTRICT ? ReferentialAction.NO_ACTION : action;
    }

    /* ------------------------------------------------------------------ indexes */

    private void readIndexes(DDLSmithDBConnection connection, String schema, Map<String, Table> tables)
            throws SQLException {
        Map<String, List<Map<String, Object>>> indexColumns = new TreeMap<>();
        for (Map<String, Object> row : query(connection,
                "SELECT INDEX_NAME, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, IS_UNIQUE"
                        + " FROM INFORMATION_SCHEMA.INDEX_COLUMNS WHERE UPPER(TABLE_SCHEMA) = UPPER(?)"
                        + " ORDER BY INDEX_NAME, ORDINAL_POSITION",
                schema)) {
            indexColumns.computeIfAbsent(lower(getString(row, "index_name")), k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> rows = query(connection,
                "SELECT INDEX_NAME, TABLE_NAME, INDEX_TYPE_NAME, IS_GENERATED FROM INFORMATION_SCHEMA.INDEXES"
                        + " WHERE UPPER(TABLE_SCHEMA) = UPPER(?) ORDER BY TABLE_NAME, INDEX_NAME",
                schema);
        for (Map<String, Object> row : rows) {
            if (getBoolean(row, "is_generated", true)) {
                // the backing index of a primary key, unique constraint or foreign key
                continue;
            }
            Table table = tables.get(lower(getString(row, "table_name")));
            if (table == null) {
                continue;
            }
            String name = lower(getString(row, "index_name"));
            List<Map<String, Object>> columns = indexColumns.get(name);
            if (columns == null || columns.isEmpty()) {
                continue;
            }
            Index index = new Index(name, columnsOf(columns));
            index.setUnique(isUnique(row, columns));
            table.addIndex(index);
        }
    }

    private static boolean isUnique(Map<String, Object> indexRow, List<Map<String, Object>> columns) {
        for (Map<String, Object> column : columns) {
            if (column.containsKey("is_unique")) {
                return getBoolean(column, "is_unique", false);
            }
        }
        return getString(indexRow, "index_type_name", "").toUpperCase(Locale.ROOT).contains("UNIQUE");
    }

    /* ------------------------------------------------------------------ views and sequences */

    private void readViews(DDLSmithDBConnection connection, String schema, RelationalSchema result)
            throws SQLException {
        for (Map<String, Object> row : query(connection,
                "SELECT TABLE_NAME, VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS"
                        + " WHERE UPPER(TABLE_SCHEMA) = UPPER(?) ORDER BY TABLE_NAME",
                schema)) {
            String definition = getString(row, "view_definition");
            View view = new View(lower(getString(row, "table_name")),
                    definition == null ? null
                            : H2ExpressionNormalizer.normalize(stripSchemaQualifier(definition, schema)));
            view.setSchemaName(schema);
            result.addView(view);
        }
    }

    /**
     * H2 fully qualifies every object a view references. Removing the introspected schema's prefix
     * makes a view created from an unqualified query compare equal to its stored definition.
     */
    static String stripSchemaQualifier(String definition, String schema) {
        String result = definition;
        if (schema != null && !schema.isBlank()) {
            String quoted = Pattern.quote(schema);
            result = result.replaceAll("(?i)\"" + quoted + "\"\\s*\\.", "");
            result = result.replaceAll("(?i)\\b" + quoted + "\\s*\\.", "");
        }
        return result;
    }

    private void readSequences(DDLSmithDBConnection connection, String schema, RelationalSchema result)
            throws SQLException {
        for (Map<String, Object> row : query(connection,
                "SELECT SEQUENCE_NAME, START_VALUE, MINIMUM_VALUE, MAXIMUM_VALUE, INCREMENT, CYCLE_OPTION"
                        + " FROM INFORMATION_SCHEMA.SEQUENCES WHERE UPPER(SEQUENCE_SCHEMA) = UPPER(?)"
                        + " ORDER BY SEQUENCE_NAME",
                schema)) {
            Sequence sequence = new Sequence(lower(getString(row, "sequence_name")));
            sequence.setSchemaName(schema);
            sequence.setStart(getLong(row, "start_value", 1L));
            long increment = getLong(row, "increment", 1L);
            sequence.setIncrement(increment);
            sequence.setMinValue(defaultBound(getLong(row, "minimum_value", 0L), increment > 0 ? 1L : Long.MIN_VALUE));
            sequence.setMaxValue(defaultBound(getLong(row, "maximum_value", 0L), increment > 0 ? Long.MAX_VALUE : -1L));
            sequence.setCycle("YES".equalsIgnoreCase(getString(row, "cycle_option", "NO")));
            result.addSequence(sequence);
        }
    }

    /** @return null when the catalog value is merely H2's effective default for that direction */
    private static Long defaultBound(long value, long defaultValue) {
        return value == defaultValue ? null : value;
    }

    /* ------------------------------------------------------------------ helpers */

    /**
     * The element type and enum value catalogs. Both are guarded: they exist in H2 2.x, but an
     * introspection must not fail wholesale if a catalog table is missing.
     */
    private Map<String, Map<String, Object>> readElementTypes(DDLSmithDBConnection connection, String schema) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : query(connection,
                    "SELECT OBJECT_NAME, COLLECTION_TYPE_IDENTIFIER, DATA_TYPE, DECLARED_DATA_TYPE,"
                            + " CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, INTERVAL_TYPE,"
                            + " DTD_IDENTIFIER FROM INFORMATION_SCHEMA.ELEMENT_TYPES"
                            + " WHERE UPPER(OBJECT_SCHEMA) = UPPER(?) AND OBJECT_TYPE = 'TABLE'",
                    schema)) {
                result.put(key(lower(getString(row, "object_name")), stringValue(row.get("collection_type_identifier"))),
                        row);
            }
        } catch (SQLException e) {
            // no array support in this catalog: array columns will report an unknown type
        }
        return result;
    }

    private Map<String, List<String>> readEnumValues(DDLSmithDBConnection connection, String schema) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : query(connection,
                    "SELECT OBJECT_NAME, ENUM_IDENTIFIER, VALUE_NAME, VALUE_ORDINAL"
                            + " FROM INFORMATION_SCHEMA.ENUM_VALUES"
                            + " WHERE UPPER(OBJECT_SCHEMA) = UPPER(?) AND OBJECT_TYPE = 'TABLE'"
                            + " ORDER BY OBJECT_NAME, ENUM_IDENTIFIER, VALUE_ORDINAL",
                    schema)) {
                result.computeIfAbsent(key(lower(getString(row, "object_name")),
                        stringValue(row.get("enum_identifier"))), k -> new ArrayList<>())
                        .add(getString(row, "value_name", ""));
            }
        } catch (SQLException e) {
            // no enum support in this catalog: enum columns will report an unknown type
        }
        return result;
    }

    private static String key(String tableName, String identifier) {
        return tableName + "#" + identifier;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intValue(Object value) {
        Long result = longValue(value);
        return result == null ? null : result.intValue();
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
