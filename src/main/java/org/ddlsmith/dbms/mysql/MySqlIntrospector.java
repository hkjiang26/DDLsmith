package org.ddlsmith.dbms.mysql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.common.schema.CheckConstraint;
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
import org.ddlsmith.introspection.SchemaIntrospector;
import org.ddlsmith.introspection.SchemaNormalizer;

/**
 * Reads a MySQL 8.0 schema back from {@code information_schema}.
 *
 * <h2>Namespace</h2>
 * <p>MySQL's schema is the connection's database. The {@code schemaName} argument is honoured when
 * given; otherwise the connection's database is used (falling back to {@code SELECT DATABASE()}).
 * {@link MySqlDialect#getDefaultSchemaName()} returns {@code null} for the same reason.</p>
 *
 * <h2>Queries</h2>
 * <ul>
 *   <li>tables: {@code information_schema.tables} with {@code table_type = 'BASE TABLE'}, plus the
 *       table collation (needed to tell an explicit column collation from the inherited one) and
 *       the table comment.</li>
 *   <li>columns: {@code information_schema.columns}. {@code column_type} is preferred over
 *       {@code data_type} because it preserves exact fidelity ({@code int unsigned},
 *       {@code varchar(20)}, {@code enum('a','b')}). {@code extra} carries
 *       {@code auto_increment} (identity) and {@code STORED GENERATED}/{@code VIRTUAL GENERATED}.</li>
 *   <li>constraints: {@code table_constraints} joined with {@code key_column_usage} (columns and
 *       the referenced table/columns) and {@code referential_constraints} (update/delete rule).
 *       CHECK expressions come from {@code information_schema.check_constraints}, which MySQL
 *       8.0.16+ populates; on older servers the query fails and the check constraints are reported
 *       as absent (they were not enforced either).</li>
 *   <li>indexes: {@code information_schema.statistics}. The {@code PRIMARY} index is the primary
 *       key and is excluded; a unique index that backs a UNIQUE constraint is filtered by
 *       {@link SchemaNormalizer#isConstraintBackedIndex} because MySQL reports one for every
 *       UNIQUE constraint.</li>
 *   <li>views: {@code information_schema.views} ({@code view_definition} and
 *       {@code check_option}).</li>
 *   <li>sequences: MySQL has none.</li>
 * </ul>
 *
 * <h2>Documented limitations</h2>
 * <ul>
 *   <li>{@code DEFAULT} values are reported by MySQL as <em>values</em>, not as SQL expressions: a
 *       string default {@code 'abc'} comes back as {@code abc}. For a textual column whose
 *       {@code extra} does not contain {@code DEFAULT_GENERATED} the value is therefore quoted again
 *       so that the canonical default is a SQL expression as the contract requires. Values that
 *       already look like a literal or an expression (quoted, parenthesised, hex, or one of the
 *       {@code CURRENT_*} keywords) are passed through unchanged.</li>
 *   <li>{@code ON UPDATE CURRENT_TIMESTAMP} has no representation in the canonical model and is not
 *       reported.</li>
 *   <li>Index prefix lengths ({@code sub_part}), index type ({@code FULLTEXT}/{@code SPATIAL}) and
 *       the per-key-part direction ({@code statistics.collation}) are not reported: the canonical
 *       {@link Index} has no field for them.</li>
 *   <li>A column whose {@code column_type} cannot be interpreted ({@code year}, {@code bit},
 *       {@code set}, {@code geometry}, ...) is omitted, and so is any constraint that references it;
 *       the round-trip oracle then reports it as a missing column rather than hiding the gap.</li>
 *   <li>MySQL reports the effective collation of every character column, including the one inherited
 *       from the table. Only a collation that differs from the table's default is reported, so an
 *       implicit collation is not mistaken for an explicit one.</li>
 *   <li>RESTRICT and NO ACTION are the same thing to InnoDB and the catalog may report either for a
 *       given foreign key, so the round-trip oracle can report a spurious ON DELETE/ON UPDATE
 *       difference for that pair.</li>
 * </ul>
 */
public class MySqlIntrospector extends AbstractIntrospector {

    static final String TABLES_SQL = "SELECT table_name, table_collation, table_comment FROM information_schema.tables "
            + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name";

    static final String COLUMNS_SQL = "SELECT column_name, ordinal_position, column_default, is_nullable, data_type, "
            + "column_type, character_maximum_length, numeric_precision, numeric_scale, extra, generation_expression, "
            + "collation_name, column_comment FROM information_schema.columns "
            + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";

    static final String CONSTRAINTS_SQL = "SELECT constraint_name, constraint_type "
            + "FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = ? "
            + "ORDER BY constraint_name";

    static final String KEY_COLUMN_USAGE_SQL = "SELECT constraint_name, column_name, ordinal_position, "
            + "referenced_table_name, referenced_column_name FROM information_schema.key_column_usage "
            + "WHERE table_schema = ? AND table_name = ? ORDER BY constraint_name, ordinal_position";

    static final String REFERENTIAL_CONSTRAINTS_SQL = "SELECT constraint_name, update_rule, delete_rule "
            + "FROM information_schema.referential_constraints WHERE constraint_schema = ? AND table_name = ?";

    static final String CHECK_CONSTRAINTS_SQL = "SELECT constraint_name, check_clause "
            + "FROM information_schema.check_constraints WHERE constraint_schema = ?";

    static final String INDEXES_SQL = "SELECT index_name, non_unique, seq_in_index, column_name, expression, "
            + "index_type, sub_part FROM information_schema.statistics "
            + "WHERE table_schema = ? AND table_name = ? ORDER BY index_name, seq_in_index";

    /** MySQL before 8.0.13 (and MariaDB) has no {@code expression} column for functional indexes. */
    static final String INDEXES_WITHOUT_EXPRESSION_SQL = "SELECT index_name, non_unique, seq_in_index, column_name, "
            + "index_type, sub_part FROM information_schema.statistics "
            + "WHERE table_schema = ? AND table_name = ? ORDER BY index_name, seq_in_index";

    static final String VIEWS_SQL = "SELECT table_name, view_definition, check_option FROM information_schema.views "
            + "WHERE table_schema = ? ORDER BY table_name";

    static final String CURRENT_DATABASE_SQL = "SELECT DATABASE() AS database_name";

    /** Default expressions MySQL accepts without parentheses. */
    private static final Set<String> BARE_DEFAULT_KEYWORDS = Set.of("current_timestamp", "current_date",
            "current_time", "current_user", "localtime", "localtimestamp", "now()", "uuid()", "null");

    private final MySqlTypeMapper typeMapper = new MySqlTypeMapper();

    @Override
    public RelationalSchema introspect(DDLSmithDBConnection connection, String schemaName) throws SQLException {
        String schema = resolveSchema(connection, schemaName);
        RelationalSchema result = new RelationalSchema(schema);

        // MySQL 8.0.16+ only; on older servers the query fails and CHECK constraints are not
        // reported (which is right: those servers parsed and ignored them)
        Map<String, String> checkClauses = new HashMap<>();
        for (Map<String, Object> row : queryQuietly(connection, CHECK_CONSTRAINTS_SQL, schema)) {
            checkClauses.put(getString(row, "constraint_name"), getString(row, "check_clause"));
        }

        for (Map<String, Object> row : query(connection, TABLES_SQL, schema)) {
            String tableName = getString(row, "table_name");
            Table table = new Table(schema, tableName);
            table.setComment(emptyToNull(getString(row, "table_comment")));
            String tableCollation = getString(row, "table_collation");

            for (Map<String, Object> columnRow : query(connection, COLUMNS_SQL, schema, tableName)) {
                Column column = toColumn(columnRow, tableCollation);
                if (column != null) {
                    table.addColumn(column);
                }
            }

            Map<String, List<Map<String, Object>>> keyUsage = new LinkedHashMap<>();
            for (Map<String, Object> keyRow : query(connection, KEY_COLUMN_USAGE_SQL, schema, tableName)) {
                keyUsage.computeIfAbsent(getString(keyRow, "constraint_name"), key -> new ArrayList<>()).add(keyRow);
            }
            Map<String, Map<String, Object>> referentialRules = new HashMap<>();
            for (Map<String, Object> ruleRow : query(connection, REFERENTIAL_CONSTRAINTS_SQL, schema, tableName)) {
                referentialRules.put(getString(ruleRow, "constraint_name"), ruleRow);
            }
            for (Map<String, Object> constraintRow : query(connection, CONSTRAINTS_SQL, schema, tableName)) {
                String name = getString(constraintRow, "constraint_name");
                applyConstraint(table, constraintRow, keyUsage.getOrDefault(name, List.of()),
                        referentialRules.get(name), checkClauses.get(name));
            }

            Map<String, List<Map<String, Object>>> indexParts = new LinkedHashMap<>();
            for (Map<String, Object> indexRow : readIndexRows(connection, schema, tableName)) {
                indexParts.computeIfAbsent(getString(indexRow, "index_name"), key -> new ArrayList<>()).add(indexRow);
            }
            for (Map.Entry<String, List<Map<String, Object>>> entry : indexParts.entrySet()) {
                if ("PRIMARY".equalsIgnoreCase(entry.getKey())) {
                    // the primary key index; the primary key itself comes from table_constraints
                    continue;
                }
                Index index = toIndex(entry.getKey(), entry.getValue());
                if (index == null || SchemaNormalizer.isConstraintBackedIndex(index, table)
                        || isForeignKeyIndex(index, table)) {
                    // a UNIQUE constraint is reported as a unique index as well; DDLSmith never
                    // created it as an index
                    continue;
                }
                table.addIndex(index);
            }
            result.addTable(table);
        }

        for (Map<String, Object> row : query(connection, VIEWS_SQL, schema)) {
            View view = new View(getString(row, "table_name"), getString(row, "view_definition"));
            view.setSchemaName(schema);
            String checkOption = getString(row, "check_option");
            if (checkOption != null && !"NONE".equalsIgnoreCase(checkOption)) {
                view.setCheckOption(checkOption.toUpperCase(Locale.ROOT));
            }
            result.addView(view);
        }
        // MySQL has no sequences
        return result;
    }

    /**
     * Resolves the {@code information_schema} schema (MySQL's name for a database) to read.
     *
     * <p>An explicit name wins. Otherwise the connection's <em>current database</em> is asked for with
     * {@code SELECT DATABASE()}, and only if that is empty does the logical database name DDLSmith
     * generated serve as a fallback.</p>
     *
     * <p>The order matters and used to be the other way round. {@code connection.getDatabaseName()}
     * is DDLSmith's own name for the generated database — {@code database0}, {@code database1} — and
     * for an embedded engine that is the whole database. For MySQL it is not a schema at all: the
     * tables live in the database named by the configuration ({@code ddlsmith}), because that is what
     * the JDBC URL connects to. Reading {@code information_schema} with
     * {@code table_schema = 'database0'} therefore found nothing, and the round-trip oracle reported
     * every table as missing even though every {@code CREATE TABLE} had succeeded.</p>
     */
    private String resolveSchema(DDLSmithDBConnection connection, String schemaName) throws SQLException {
        if (schemaName != null && !schemaName.isEmpty()) {
            return schemaName;
        }
        List<Map<String, Object>> rows = query(connection, CURRENT_DATABASE_SQL);
        if (!rows.isEmpty() && getString(rows.get(0), "database_name") != null) {
            return getString(rows.get(0), "database_name");
        }
        String databaseName = connection.getDatabaseName();
        if (databaseName != null && !databaseName.isEmpty()) {
            return databaseName;
        }
        throw new SQLException("cannot determine the MySQL database to introspect: pass a schema name "
                + "or connect with a default database");
    }

    private List<Map<String, Object>> readIndexRows(DDLSmithDBConnection connection, String schema, String tableName)
            throws SQLException {
        try {
            return query(connection, INDEXES_SQL, schema, tableName);
        } catch (SQLException e) {
            return query(connection, INDEXES_WITHOUT_EXPRESSION_SQL, schema, tableName);
        }
    }

    private List<Map<String, Object>> queryQuietly(DDLSmithDBConnection connection, String sql, Object... parameters) {
        try {
            return query(connection, sql, parameters);
        } catch (SQLException e) {
            return List.of();
        }
    }

    /* ------------------------------------------------------------------ row translation */

    /**
     * Translates one {@code information_schema.columns} row into a canonical column.
     *
     * @param tableCollation the table's default collation, used to detect an explicit column
     *        collation
     * @return the column, or {@code null} when the reported type cannot be interpreted
     */
    Column toColumn(Map<String, Object> row, String tableCollation) {
        DataType type = typeMapper.parse(getString(row, "column_type"));
        if (type == null) {
            type = typeMapper.parse(getString(row, "data_type"));
        }
        if (type == null) {
            return null;
        }
        Column column = new Column(getString(row, "column_name"), type);
        column.setNullable(!"NO".equalsIgnoreCase(getString(row, "is_nullable", "YES")));

        String extra = getString(row, "extra", "");
        String extraUpper = extra.toUpperCase(Locale.ROOT);
        String generationExpression = getString(row, "generation_expression");
        if (generationExpression != null && !generationExpression.isEmpty() && extraUpper.contains("GENERATED")) {
            column.setGeneratedExpression(generationExpression);
            column.setGeneratedStored(extraUpper.contains("STORED"));
        } else {
            String defaultValue = getString(row, "column_default");
            if (defaultValue != null && !defaultValue.isEmpty()) {
                column.setDefaultExpression(toDefaultExpression(defaultValue, type,
                        extraUpper.contains("DEFAULT_GENERATED")));
            }
            if (extraUpper.contains("AUTO_INCREMENT")) {
                column.setIdentity(true);
            }
        }

        String collation = getString(row, "collation_name");
        if (collation != null && !collation.equalsIgnoreCase(tableCollation)) {
            column.setCollation(collation);
        }
        column.setComment(emptyToNull(getString(row, "column_comment")));
        return column;
    }

    /**
     * Rebuilds a SQL default expression from the value MySQL reports.
     *
     * <p>{@code information_schema.columns.column_default} holds the default <em>value</em>, so a
     * string default is reported without quotes; the canonical model stores a SQL expression, so a
     * textual column's value is quoted again unless {@code extra} says the default is generated by
     * an expression or the value already looks like a literal or a keyword.</p>
     *
     * <p>A temporal column is quoted again for the same reason. {@code DEFAULT '2000-01-01'} on a
     * {@code DATE} column comes back as {@code 2000-01-01}, measured on 26.7.0, and the model holds the
     * quoted literal — so without this every evolved database with a date default reported
     * {@code COLUMN_DEFAULT at t.c: expected '2000-01-01', actual 2000-01-01}. A keyword default such
     * as {@code CURRENT_TIMESTAMP} is still left alone, because {@link #looksLikeSqlExpression} catches
     * it before the quoting happens.</p>
     */
    static String toDefaultExpression(String defaultValue, DataType type, boolean generatedByExpression) {
        if (generatedByExpression || !(type.isTextual() || type.isTemporal()) || looksLikeSqlExpression(defaultValue)) {
            return defaultValue;
        }
        return "'" + defaultValue.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private static boolean looksLikeSqlExpression(String value) {
        String text = value.trim();
        if (text.isEmpty()) {
            return true;
        }
        char first = text.charAt(0);
        if (first == '\'' || first == '(' || first == '"' || first == '`') {
            return true;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.startsWith("X'") || upper.startsWith("B'") || upper.startsWith("_")) {
            // hex/bit literals and introducer-prefixed literals
            return true;
        }
        return BARE_DEFAULT_KEYWORDS.contains(text.toLowerCase(Locale.ROOT));
    }

    /**
     * Applies one {@code information_schema.table_constraints} row to the table being built.
     *
     * @param keyUsageRows the {@code key_column_usage} rows of that constraint, in ordinal order
     * @param referentialRule the matching {@code referential_constraints} row, or {@code null}
     * @param checkClause the matching {@code check_constraints.check_clause}, or {@code null} when
     *        the server does not expose it
     */
    void applyConstraint(Table table, Map<String, Object> constraintRow,
            List<Map<String, Object>> keyUsageRows, Map<String, Object> referentialRule, String checkClause) {
        String name = getString(constraintRow, "constraint_name");
        String type = getString(constraintRow, "constraint_type");
        if (type == null) {
            return;
        }
        switch (type.toUpperCase(Locale.ROOT)) {
        case "PRIMARY KEY": {
            List<String> columns = columnsOf(keyUsageRows);
            if (!columns.isEmpty()) {
                table.setPrimaryKey(new PrimaryKey(name, columns));
            }
            break;
        }
        case "UNIQUE": {
            List<String> columns = columnsOf(keyUsageRows);
            if (!columns.isEmpty()) {
                table.addUniqueConstraint(new UniqueConstraint(name, columns));
            }
            break;
        }
        case "FOREIGN KEY": {
            List<String> columns = columnsOf(keyUsageRows);
            List<String> referencedColumns = new ArrayList<>();
            String referencedTable = null;
            for (Map<String, Object> row : keyUsageRows) {
                if (referencedTable == null) {
                    referencedTable = getString(row, "referenced_table_name");
                }
                String referencedColumn = getString(row, "referenced_column_name");
                if (referencedColumn != null) {
                    referencedColumns.add(referencedColumn);
                }
            }
            if (columns.isEmpty() || referencedTable == null || referencedColumns.size() != columns.size()) {
                break;
            }
            ForeignKey foreignKey = new ForeignKey(columns, referencedTable, referencedColumns);
            foreignKey.setName(name);
            if (referentialRule != null) {
                foreignKey.setOnDelete(ReferentialAction.fromSql(getString(referentialRule, "delete_rule")));
                foreignKey.setOnUpdate(ReferentialAction.fromSql(getString(referentialRule, "update_rule")));
            }
            foreignKey.setSelfReferential(referencedTable.equalsIgnoreCase(table.getName()));
            table.addForeignKey(foreignKey);
            break;
        }
        case "CHECK": {
            if (checkClause != null) {
                table.addCheckConstraint(new CheckConstraint(name, checkClause));
            }
            break;
        }
        default:
            break;
        }
    }

    /**
     * @return true when the index is the one InnoDB creates for a foreign key rather than one DDLSmith
     *         asked for
     *
     * <p>InnoDB requires an index on a foreign key's referencing columns and creates one when the schema
     * does not provide it. PostgreSQL does not, so one declared schema produces an index on MySQL and
     * none on PostgreSQL — and the differential oracle reported that as
     * {@code MISSING_INDEX at t_3.idx([e3_dst_id])} on database after database. It is an implementation
     * detail of one engine rather than a disagreement about what the DDL means, so it is not part of
     * what the schema declared.</p>
     *
     * <p>What identifies it is the name InnoDB gives it, which depends on whether the foreign key was
     * named: after the constraint when it was, and after the <em>first referencing column</em> when it
     * was not. DDLSmith declares a foreign key unnamed inside {@code CREATE TABLE} (it names one only in
     * the {@code ALTER TABLE ... ADD CONSTRAINT} form), so on a live server the constraint arrives as
     * {@code t_3_ibfk_2} while its index arrives as {@code t_0_id_2} — which is why matching the
     * constraint name alone left the indexes in place. DDLSmith's own indexes are always named
     * {@code idx_}, {@code uidx_} or {@code ev_idx_}, so neither shape can be one of them, and an index
     * the engine really did create for DDLSmith is still reported.</p>
     *
     * <p>The first referencing column's name is not free either: when another index already carries it,
     * InnoDB appends a counter, so a foreign key on {@code (e0_dst_a0, e0_dst_a2)} whose first column
     * already has a {@code UNIQUE} index arrives as {@code e0_dst_a0_2}. Measured on the live server
     * with exactly that table — the catalog then holds {@code e0_dst_a0} (the unique constraint) and
     * {@code e0_dst_a0_2} (the foreign key's). Matching the bare column name alone therefore left this
     * one in place, and the differential oracle reported {@code MISSING_INDEX at t_0.idx([e0_dst_a0,
     * e0_dst_a2])} — PostgreSQL, which creates no such index, was right and MySQL's extra index was an
     * implementation detail.</p>
     */
    private static boolean isForeignKeyIndex(Index index, Table table) {
        List<String> columns = new ArrayList<>();
        for (String column : index.getColumns()) {
            columns.add(column.toLowerCase(Locale.ROOT));
        }
        if (columns.isEmpty()) {
            return false;
        }
        String name = index.getName();
        if (name == null) {
            return false;
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            List<String> referencing = new ArrayList<>();
            for (String column : foreignKey.getColumns()) {
                referencing.add(column.toLowerCase(Locale.ROOT));
            }
            if (!referencing.equals(columns)) {
                continue;
            }
            String firstColumn = foreignKey.getColumns().get(0);
            if (name.equalsIgnoreCase(foreignKey.getName())
                    || name.equalsIgnoreCase(firstColumn)
                    || name.matches("(?i)" + java.util.regex.Pattern.quote(firstColumn) + "_\\d+")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> columnsOf(List<Map<String, Object>> keyUsageRows) {
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : keyUsageRows) {
            String column = getString(row, "column_name");
            if (column != null) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * Builds a canonical index from the {@code information_schema.statistics} rows of one index.
     *
     * <p>If any key part is a functional key part (MySQL reports an {@code expression} and no
     * {@code column_name}), <em>all</em> key parts are reported as expressions, because
     * {@link Index} cannot hold columns and expressions at the same time; a plain column part is
     * reported by its name and renders identically.</p>
     *
     * @return the index, or {@code null} when a key part cannot be described
     */
    Index toIndex(String indexName, List<Map<String, Object>> partRows) {
        if (partRows.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        boolean hasExpression = false;
        for (Map<String, Object> row : partRows) {
            String column = getString(row, "column_name");
            String expression = getString(row, "expression");
            if (column != null) {
                parts.add(column);
            } else if (expression != null && !expression.isEmpty()) {
                parts.add(stripDirection(expression.trim()));
                hasExpression = true;
            } else {
                return null;
            }
        }
        Index index = new Index(indexName);
        index.setUnique(getInt(partRows.get(0), "non_unique", 1) == 0);
        if (hasExpression) {
            index.getExpressions().addAll(parts);
        } else {
            index.getColumns().addAll(parts);
        }
        return index;
    }

    /** Removes a trailing {@code ASC}/{@code DESC} from a functional key part. */
    static String stripDirection(String expression) {
        String upper = expression.toUpperCase(Locale.ROOT);
        if (upper.endsWith(" DESC") || upper.endsWith(" ASC")) {
            return expression.substring(0, expression.length() - 5).trim();
        }
        return expression;
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : value;
    }

    @Override
    public Set<IntrospectionCapability> getCapabilities() {
        // PARTIAL_INDEXES is absent: MySQL has no partial indexes.
        // SEQUENCES is absent: MySQL has no sequence objects.
        // CHECK_CONSTRAINTS only works on MySQL 8.0.16+ (information_schema.check_constraints);
        // older servers parsed and ignored CHECK, so reporting them as absent is correct there.
        return EnumSet.of(
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
                IntrospectionCapability.EXPRESSION_INDEXES,
                IntrospectionCapability.VIEWS,
                IntrospectionCapability.VIEW_DEFINITIONS,
                IntrospectionCapability.COMMENTS,
                IntrospectionCapability.TABLE_EXISTENCE);
    }
}
