package org.ddlsmith.dbms.duckdb;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.DDLsmithDBConnection;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.introspection.AbstractIntrospector;
import org.ddlsmith.introspection.SchemaIntrospector;

/**
 * Reads the DuckDB 1.3 catalog back into the canonical schema model.
 *
 * <p>DuckDB exposes the standard {@code information_schema} views next to its own table functions,
 * but the standard views are not always the better source. What was measured on 1.3.0:</p>
 *
 * <ul>
 *   <li>{@code information_schema.columns} has no {@code comment} column at all (selecting it is a
 *       binder error) and reports {@code is_identity}, {@code is_generated} and
 *       {@code generation_expression} as NULL. {@code duckdb_columns()} has the comment and the
 *       real nullability, so columns are read from there.</li>
 *   <li>{@code information_schema.table_constraints} classifies every NOT NULL constraint as a
 *       {@code CHECK} constraint, which would invent check constraints that do not exist.
 *       {@code duckdb_constraints()} distinguishes {@code PRIMARY KEY}, {@code UNIQUE},
 *       {@code CHECK}, {@code NOT NULL} and {@code FOREIGN KEY} and carries the referencing and
 *       referenced column lists directly, so it is used for all constraints.</li>
 *   <li>{@code information_schema.views.view_definition} and {@code duckdb_views().sql} both return
 *       the whole {@code CREATE VIEW ... AS ...} text rather than the query, so the query is
 *       extracted from it.</li>
 *   <li>{@code information_schema.sequences} does not exist; {@code duckdb_sequences()} does.</li>
 *   <li>{@code duckdb_indexes()} lists only user-created indexes: the indexes DuckDB creates for
 *       primary key and unique constraints do not appear (although {@code duckdb_tables().index_count}
 *       counts them). Its {@code expressions} column holds the indexed column names for an ordinary
 *       index and the parenthesised expression for an expression index.</li>
 * </ul>
 */
public class DuckDbIntrospector extends AbstractIntrospector {

    /** The schema DuckDB uses when none is given. */
    public static final String DEFAULT_SCHEMA = "main";

    private final DuckDbTypeMapper typeMapper = new DuckDbTypeMapper();

    /**
     * The aspects this introspector reports reliably, and the reason for every omission:
     *
     * <ul>
     *   <li>{@code IDENTITY} is absent: DuckDB 1.3 has no identity columns at all (see
     *       {@link DuckDbDialect}), and {@code information_schema.columns.is_identity} is NULL even
     *       for a column created with {@code DEFAULT nextval('s')}.</li>
     *   <li>{@code GENERATED_COLUMNS} is absent: {@code information_schema.columns.is_generated} is
     *       NULL and {@code duckdb_columns()} has no such column. A generated column comes back as
     *       an ordinary column whose default is {@code CAST(<expression> AS <type>)}, so reporting
     *       generated-ness would mean parsing DDL text.</li>
     *   <li>{@code FOREIGN_KEY_ACTIONS} is absent: DuckDB accepts only {@code NO ACTION} and
     *       {@code RESTRICT}, and {@code information_schema.referential_constraints} reports
     *       {@code delete_rule = update_rule = 'NO ACTION'} for both, so the catalog cannot
     *       distinguish the actions the engine accepts. The actions are therefore left at their
     *       default instead of inventing a difference the engine cannot express.</li>
     *   <li>{@code PARTIAL_INDEXES} is absent: {@code CREATE INDEX ... WHERE ...} fails with
     *       {@code Not implemented Error: Creating partial indexes is not supported currently}.</li>
     * </ul>
     */
    private static final Set<IntrospectionCapability> CAPABILITIES = EnumSet.of(
            IntrospectionCapability.COLUMNS,
            IntrospectionCapability.COLUMN_TYPES,
            IntrospectionCapability.NULLABILITY,
            IntrospectionCapability.DEFAULTS,
            IntrospectionCapability.PRIMARY_KEYS,
            IntrospectionCapability.FOREIGN_KEYS,
            IntrospectionCapability.UNIQUE_CONSTRAINTS,
            IntrospectionCapability.CHECK_CONSTRAINTS,
            IntrospectionCapability.INDEXES,
            IntrospectionCapability.INDEX_UNIQUENESS,
            IntrospectionCapability.INDEX_COLUMNS,
            IntrospectionCapability.EXPRESSION_INDEXES,
            IntrospectionCapability.VIEWS,
            IntrospectionCapability.VIEW_DEFINITIONS,
            IntrospectionCapability.SEQUENCES,
            IntrospectionCapability.COMMENTS,
            IntrospectionCapability.TABLE_EXISTENCE);

    @Override
    public Set<IntrospectionCapability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public RelationalSchema introspect(DDLsmithDBConnection connection, String schemaName) throws SQLException {
        String schema = schemaName == null ? DEFAULT_SCHEMA : normalize(schemaName);
        RelationalSchema result = new RelationalSchema(schema);

        List<Map<String, Object>> tableRows = query(connection,
                "SELECT table_name, comment FROM duckdb_tables() WHERE schema_name = ? AND NOT internal", schema);
        for (Map<String, Object> row : tableRows) {
            String tableName = getString(row, "table_name");
            Table table = new Table(schema, tableName);
            table.setComment(getString(row, "comment"));
            readColumns(connection, schema, table);
            readConstraints(connection, schema, table);
            readIndexes(connection, schema, table);
            result.addTable(table);
        }
        readViews(connection, schema, result);
        readSequences(connection, schema, result);
        return result;
    }

    /* ------------------------------------------------------------------ columns */

    private void readColumns(DDLsmithDBConnection connection, String schema, Table table) throws SQLException {
        // duckdb_columns() is preferred over information_schema.columns: it carries the comment and
        // reports nullability for real (a primary key column is is_nullable = false).
        List<Map<String, Object>> rows = query(connection,
                "SELECT column_name, column_index, column_default, is_nullable, data_type, comment "
                        + "FROM duckdb_columns() WHERE schema_name = ? AND table_name = ? ORDER BY column_index",
                schema, table.getName());
        for (Map<String, Object> row : rows) {
            String name = getString(row, "column_name");
            String engineType = getString(row, "data_type");
            DataType type = typeMapper.parse(engineType);
            if (type == null) {
                // Types without a canonical equivalent (STRUCT, MAP, UNION, HUGEINT, ENUM, ...) are
                // recorded as TEXT so that the round-trip oracle reports a type difference rather
                // than failing on a null type.
                type = DataType.text();
            }
            Column column = new Column(name, type, getBoolean(row, "is_nullable", true));
            String defaultExpression = cleanDefault(getString(row, "column_default"));
            if (defaultExpression != null) {
                column.setDefaultExpression(defaultExpression);
            }
            column.setComment(getString(row, "comment"));
            table.addColumn(column);
        }
    }

    /* ------------------------------------------------------------------ constraints */

    private void readConstraints(DDLsmithDBConnection connection, String schema, Table table) throws SQLException {
        List<Map<String, Object>> rows = query(connection,
                "SELECT constraint_type, constraint_name, "
                        + "array_to_string(constraint_column_names, chr(1)) AS constraint_columns, "
                        + "referenced_table, "
                        + "array_to_string(referenced_column_names, chr(1)) AS referenced_columns, "
                        + "expression FROM duckdb_constraints() WHERE schema_name = ? AND table_name = ?",
                schema, table.getName());
        for (Map<String, Object> row : rows) {
            String type = getString(row, "constraint_type", "").toUpperCase(Locale.ROOT);
            List<String> columns = splitList(getString(row, "constraint_columns"));
            switch (type) {
            case "PRIMARY KEY":
                table.setPrimaryKey(new PrimaryKey(normalizeAll(columns)));
                break;
            case "UNIQUE":
                table.addUniqueConstraint(new UniqueConstraint(normalizeAll(columns)));
                break;
            case "CHECK":
                String expression = getString(row, "expression");
                if (expression != null) {
                    table.addCheckConstraint(new CheckConstraint(cleanDefault(expression)));
                }
                break;
            case "FOREIGN KEY":
                List<String> referencedColumns = splitList(getString(row, "referenced_columns"));
                ForeignKey foreignKey = new ForeignKey(normalizeAll(columns),
                        normalize(getString(row, "referenced_table")), normalizeAll(referencedColumns));
                foreignKey.setSelfReferential(normalize(table.getName()).equals(foreignKey.getReferencedTable()));
                // onDelete/onUpdate are deliberately left at NO_ACTION: see CAPABILITIES.
                table.addForeignKey(foreignKey);
                break;
            case "NOT NULL":
                // already reported through duckdb_columns().is_nullable
                break;
            default:
                // DuckDB reports nothing else today; unknown kinds are ignored rather than guessed.
                break;
            }
        }
    }

    /* ------------------------------------------------------------------ indexes */

    private void readIndexes(DDLsmithDBConnection connection, String schema, Table table) throws SQLException {
        // array_to_string is used because the driver hands LIST columns back in three different
        // shapes depending on the table function: a DuckDBArray here, a String[] there, and the
        // rendered "[a, b]" text elsewhere. chr(1) cannot occur in an identifier or expression.
        List<Map<String, Object>> rows = query(connection,
                "SELECT index_name, is_unique, array_to_string(expressions, chr(1)) AS index_keys "
                        + "FROM duckdb_indexes() WHERE schema_name = ? AND table_name = ?",
                schema, table.getName());
        for (Map<String, Object> row : rows) {
            String name = getString(row, "index_name");
            List<String> keys = splitList(getString(row, "index_keys"));
            Index index = new Index(name);
            index.setUnique(getBoolean(row, "is_unique", false));
            boolean plainColumns = true;
            for (String key : keys) {
                if (!isColumnReference(key)) {
                    plainColumns = false;
                    break;
                }
            }
            if (plainColumns) {
                List<String> columns = new ArrayList<>();
                for (String key : keys) {
                    columns.add(unquoteIdentifier(key));
                }
                index.getColumns().addAll(normalizeAll(columns));
            } else {
                // an expression index is reported as "(lower(c))" or "((a + b))"
                index.getExpressions().addAll(keys);
            }
            table.addIndex(index);
        }
    }

    /**
     * @return true if the reported index key is a bare column reference rather than an expression.
     *         DuckDB reports both in the same {@code expressions} column: {@code a} for
     *         {@code CREATE INDEX i ON t (a)} and {@code (lower(c))} for
     *         {@code CREATE INDEX i ON t (lower(c))}. A column whose name needs quoting is reported
     *         quoted, e.g. {@code "Mixed Case"}.
     */
    private static boolean isColumnReference(String key) {
        String identifier = unquoteIdentifier(key);
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        if (!Character.isLetter(identifier.charAt(0)) && identifier.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                return false;
            }
        }
        return true;
    }

    /** Strips the double quotes DuckDB adds around an identifier that needs them. */
    private static String unquoteIdentifier(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }

    /* ------------------------------------------------------------------ views */

    private void readViews(DDLsmithDBConnection connection, String schema, RelationalSchema result)
            throws SQLException {
        List<Map<String, Object>> rows = query(connection,
                "SELECT view_name, sql FROM duckdb_views() WHERE schema_name = ? AND NOT internal", schema);
        for (Map<String, Object> row : rows) {
            String name = getString(row, "view_name");
            View view = new View(name, extractViewQuery(getString(row, "sql")));
            view.setSchemaName(schema);
            result.addView(view);
        }
    }

    /**
     * DuckDB stores the whole {@code CREATE VIEW} text (schema-qualified and terminated by a
     * semicolon) in both {@code duckdb_views().sql} and
     * {@code information_schema.views.view_definition}, so the query has to be cut out of it.
     */
    static String extractViewQuery(String sql) {
        if (sql == null) {
            return null;
        }
        String text = sql.trim();
        int as = indexOfViewAs(text);
        if (as >= 0) {
            text = text.substring(as);
        }
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text.trim();
    }

    /** @return the offset just past the {@code AS} that separates the view name from its query */
    private static int indexOfViewAs(String sql) {
        boolean inString = false;
        for (int i = 0; i < sql.length() - 1; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && (c == 'a' || c == 'A') && (sql.charAt(i + 1) == 's' || sql.charAt(i + 1) == 'S')
                    && (i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1)))
                    && (i + 2 >= sql.length() || !Character.isLetterOrDigit(sql.charAt(i + 2)))) {
                return i + 2;
            }
        }
        return -1;
    }

    /* ------------------------------------------------------------------ sequences */

    private void readSequences(DDLsmithDBConnection connection, String schema, RelationalSchema result)
            throws SQLException {
        List<Map<String, Object>> rows = query(connection,
                "SELECT sequence_name, start_value, increment_by, min_value, max_value, cycle "
                        + "FROM duckdb_sequences() WHERE schema_name = ?",
                schema);
        for (Map<String, Object> row : rows) {
            Sequence sequence = new Sequence(getString(row, "sequence_name"));
            sequence.setSchemaName(schema);
            sequence.setStart(getLong(row, "start_value", 1L));
            sequence.setIncrement(getLong(row, "increment_by", 1L));
            // DuckDB always reports the effective bounds; the canonical model only records bounds
            // that were asked for, and sequences are not compared by SchemaNormalizer.diff.
            sequence.setMinValue(getLong(row, "min_value", 1L));
            sequence.setMaxValue(getLong(row, "max_value", Long.MAX_VALUE));
            sequence.setCycle(getBoolean(row, "cycle", false));
            result.addSequence(sequence);
        }
    }

    /* ------------------------------------------------------------------ helpers */

    /** The separator used to read DuckDB LIST columns as text; it cannot occur in an identifier. */
    private static final String LIST_SEPARATOR = "\u0001";

    /**
     * Splits what {@code array_to_string(<list>, chr(1))} produced. A NULL result means the list was
     * empty, which is how DuckDB renders {@code []} (e.g. {@code referenced_column_names} of a
     * primary key constraint).
     */
    static List<String> splitList(String joined) {
        List<String> result = new ArrayList<>();
        if (joined == null || joined.isEmpty()) {
            return result;
        }
        for (String element : joined.split(LIST_SEPARATOR, -1)) {
            result.add(element);
        }
        return result;
    }

    /**
     * Fallback used when a catalog column is read as a raw LIST value instead of text. The DuckDB
     * JDBC driver hands the same column back as a {@link java.sql.Array}, a {@code String[]} or the
     * rendered {@code [a, b]} text depending on the table function, so all three shapes are
     * accepted.
     */
    static List<String> toStringList(Object value) {
        if (value instanceof String) {
            String text = (String) value;
            if (text.startsWith("[") && text.endsWith("]")) {
                return splitRenderedList(text.substring(1, text.length() - 1));
            }
            return splitList(text);
        }
        List<String> result = new ArrayList<>();
        collectInto(value, result);
        return result;
    }

    private static void collectInto(Object value, List<String> result) {
        if (value == null) {
            return;
        }
        if (value instanceof Array) {
            try {
                collectInto(((Array) value).getArray(), result);
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read a DuckDB list column", e);
            }
            return;
        }
        if (value instanceof Object[]) {
            for (Object element : (Object[]) value) {
                collectInto(element, result);
            }
            return;
        }
        if (value instanceof java.util.Collection) {
            for (Object element : (java.util.Collection<?>) value) {
                collectInto(element, result);
            }
            return;
        }
        result.add(value.toString());
    }

    /** Splits DuckDB's rendered {@code a, 'b, c'} list text, honouring quoted elements. */
    private static List<String> splitRenderedList(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inString = !inString;
                current.append(c);
            } else if (!inString && c == ',' && i + 1 < text.length() && text.charAt(i + 1) == ' ') {
                result.add(unquoteElement(current.toString()));
                current.setLength(0);
                i++;
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(unquoteElement(current.toString()));
        }
        return result;
    }

    private static String unquoteElement(String element) {
        String trimmed = element.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '\'' && trimmed.charAt(trimmed.length() - 1) == '\'') {
            return trimmed.substring(1, trimmed.length() - 1).replace("''", "'");
        }
        return trimmed;
    }

    private static List<String> normalizeAll(List<String> identifiers) {
        List<String> result = new ArrayList<>();
        for (String identifier : identifiers) {
            result.add(normalize(identifier));
        }
        return result;
    }
}
