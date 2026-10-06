package org.ddlsmith.dbms.mysql;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ddl.AbstractDialect;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.ddl.alter.DropColumn;
import org.ddlsmith.ddl.alter.DropConstraint;
import org.ddlsmith.ddl.alter.DropNotNull;
import org.ddlsmith.ddl.alter.SetDefault;
import org.ddlsmith.ddl.alter.SetNotNull;

/**
 * The MySQL 8.0 dialect.
 *
 * <h2>Namespaces</h2>
 * <p>MySQL's "schema" is a database, and DDLSmith treats the connection's database as the
 * namespace, so {@code CREATE_SCHEMA}, {@code DROP_SCHEMA} and {@code QUALIFIED_NAMES} are absent
 * from {@link #getFeatures()} and {@link #getDefaultSchemaName()} returns {@code null}.
 * Identifiers are folded to lower case, because MySQL's table name case sensitivity depends on the
 * host file system ({@code lower_case_table_names}), which would otherwise make the round trip
 * host dependent.</p>
 * <p>{@code CREATE SCHEMA}/{@code DROP SCHEMA} and {@code CREATE SEQUENCE}/{@code DROP SEQUENCE}
 * additionally render as a comment (see {@link #NO_SCHEMA_SUPPORT} and
 * {@link #NO_SEQUENCE_SUPPORT}) rather than as invalid - or, for {@code DROP SCHEMA}, destructive -
 * SQL, so a generator that forgets to gate on the feature set cannot drop a database.</p>
 *
 * <h2>Session setup</h2>
 * <p>Two statements are issued per connection:</p>
 * <ol>
 *   <li>{@code SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION'}. MySQL silently
 *       truncates over-long strings, coerces out-of-range numbers and converts invalid dates in
 *       non-strict mode. A constraint test would then observe a value that was quietly rewritten
 *       instead of the error the constraint should have raised, so strict mode is required for
 *       meaningful constraint testing.</li>
 *   <li>{@code SET SESSION explicit_defaults_for_timestamp = ON}. With the MySQL default
 *       ({@code OFF}), the <em>first</em> {@code TIMESTAMP} column of a table silently receives
 *       {@code DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP} and becomes {@code NOT NULL}
 *       when the DDL specifies neither. That would make every generated {@code TIMESTAMP} column
 *       look like a schema defect in the round-trip oracle. Turning the option on makes MySQL treat
 *       {@code TIMESTAMP} like every other type, which is what the canonical model assumes.</li>
 * </ol>
 *
 * <h2>Feature declarations that MySQL 8.0.33 does not actually accept</h2>
 * <p>Three flags in the module's required feature set name clauses that MySQL does not have
 * (MariaDB has them): {@code CREATE_INDEX_IF_NOT_EXISTS}, {@code DROP_INDEX_IF_EXISTS} and
 * {@code ALTER_DROP_CONSTRAINT_IF_EXISTS}. They are declared as required, but the renderer
 * <em>never emits the corresponding clauses</em>, because a syntax error on every statement that
 * happens to set the flag would produce spurious findings. See {@link #visit(CreateIndex)},
 * {@link #visit(DropIndex)} and {@link MySqlAlterActionRenderer#visit(DropConstraint)}.</p>
 *
 * <h2>CHECK constraints</h2>
 * <p>MySQL 8.0.16 and later enforce {@code CHECK} constraints; earlier 8.0 releases parsed and
 * silently ignored them, and 5.7 ignored them too. {@code NAMED_CHECK} and {@code ALTER_ADD_CHECK}
 * are therefore declared for 8.0.33 (the version this dialect targets), and
 * {@link MySqlIntrospector} only reports {@code CHECK_CONSTRAINTS} for 8.0.16+.</p>
 *
 * <h2>ALTER TABLE</h2>
 * <p>MySQL's {@code ALTER TABLE} syntax differs from the standard in most actions, so
 * {@link MySqlAlterActionRenderer} overrides them all. Two consequences are worth repeating:</p>
 * <ul>
 *   <li>{@code MODIFY COLUMN} replaces the <em>whole</em> column definition. The AST action carries
 *       only the new type, so a type change rendered here drops the column's {@code NOT NULL},
 *       {@code DEFAULT}, {@code AUTO_INCREMENT} and {@code COMMENT} attributes. The generator must
 *       combine a type change with the other attributes (or accept the loss) - see
 *       {@link MySqlAlterActionRenderer#visit(AlterColumnType)}.</li>
 *   <li>MySQL has no {@code SET/DROP NOT NULL}; those actions throw
 *       {@link UnsupportedOperationException} and the matching feature flags are absent.</li>
 * </ul>
 */
public class MySqlDialect extends AbstractDialect {

    private final MySqlTypeMapper typeMapper = new MySqlTypeMapper();
    private final AlterActionRenderer mySqlAlterActionRenderer = new MySqlAlterActionRenderer();

    /**
     * Safety net for {@code CREATE SCHEMA}/{@code DROP SCHEMA}.
     *
     * <p>MySQL accepts {@code CREATE SCHEMA x} as a synonym of {@code CREATE DATABASE x}, and
     * {@code DROP SCHEMA x} would <em>drop a database</em>. Both are gated by the absent
     * {@code CREATE_SCHEMA}/{@code DROP_SCHEMA} features, so a correct generator never emits them;
     * this comment makes the mistake harmless and obvious in a reproduction log.</p>
     */
    static final String NO_SCHEMA_SUPPORT = "-- mysql has no schema namespace; the connection's database is the namespace";

    /** Safety net for {@code CREATE SEQUENCE}/{@code DROP SEQUENCE}, which MySQL does not have. */
    static final String NO_SEQUENCE_SUPPORT = "-- mysql has no sequences";

    /**
     * The table currently being rendered, or {@code null} outside {@code CREATE TABLE}.
     *
     * <p>Needed because {@code AUTO_INCREMENT} is only legal on a column that is part of a key, and
     * the inherited renderer hands {@link #renderColumnDefinition(Column)} a column without its
     * table. For the {@code ALTER TABLE ... ADD COLUMN} path there is no table in the AST at all, so
     * the fallback is an inline {@code UNIQUE} (which does make the column a key).</p>
     */
    private Table renderingTable;

    @Override
    public String getName() {
        return "mysql";
    }

    @Override
    public DDLFeatureSet getFeatures() {
        // TYPE_INTERVAL is deliberately absent: MySQL has no INTERVAL column type and
        // MySqlTypeMapper renders INTERVAL as TIME, so the mapping is lossy and cannot round trip.
        return new DDLFeatureSet(EnumSet.of(
                /* identifiers */
                DDLFeature.IF_NOT_EXISTS,
                DDLFeature.DROP_IF_EXISTS,
                DDLFeature.TEMPORARY_TABLE,
                /* table level constraints */
                DDLFeature.COMPOSITE_PRIMARY_KEY,
                DDLFeature.NAMED_PRIMARY_KEY,
                DDLFeature.COMPOSITE_FOREIGN_KEY,
                DDLFeature.COMPOSITE_UNIQUE,
                DDLFeature.NAMED_UNIQUE,
                DDLFeature.UNIQUE_CONSTRAINT,
                DDLFeature.NAMED_CHECK,
                DDLFeature.SELF_REFERENTIAL_FK,
                DDLFeature.NAMED_FOREIGN_KEY,
                DDLFeature.TABLE_LEVEL_FOREIGN_KEY,
                DDLFeature.INLINE_UNIQUE,
                DDLFeature.INLINE_CHECK,
                DDLFeature.COLUMN_LEVEL_PRIMARY_KEY,
                /* referential actions */
                DDLFeature.FK_ON_DELETE,
                DDLFeature.FK_ON_UPDATE,
                DDLFeature.FK_CASCADE,
                DDLFeature.FK_SET_NULL,
                DDLFeature.FK_SET_DEFAULT,
                DDLFeature.FK_RESTRICT,
                DDLFeature.FK_ENFORCED_BY_DEFAULT,
                // MySQL resolves a foreign key's target when the referencing table is created and
                // answers "Failed to open the referenced table 't_1'" otherwise, so a reference cycle
                // has to be broken by deferring the cycle-closing foreign key to an ALTER TABLE.
                // Dialect.validatesForeignKeyTargets() already reported true here through
                // EngineTraits, so declaring it changes no generated DDL — it stops the feature set
                // from contradicting the behaviour it was derived from.
                DDLFeature.FK_TARGET_MUST_EXIST,
                /* column definitions */
                DDLFeature.DEFAULT_LITERAL,
                DDLFeature.DEFAULT_EXPRESSION,
                DDLFeature.DEFAULT_CURRENT_TIMESTAMP,
                DDLFeature.NOT_NULL_COLUMN,
                DDLFeature.AUTO_INCREMENT,
                DDLFeature.GENERATED_COLUMN,
                DDLFeature.GENERATED_STORED,
                DDLFeature.GENERATED_VIRTUAL,
                DDLFeature.COLLATION,
                DDLFeature.COLUMN_COMMENT,
                DDLFeature.TABLE_COMMENT,
                DDLFeature.COLUMN_TYPE_UNSIGNED,
                DDLFeature.NULLS_DISTINCT,
                /* ALTER TABLE */
                DDLFeature.ALTER_ADD_COLUMN,
                DDLFeature.ALTER_DROP_COLUMN,
                DDLFeature.ALTER_RENAME_COLUMN,
                DDLFeature.ALTER_RENAME_TABLE,
                DDLFeature.ALTER_COLUMN_TYPE,
                DDLFeature.ALTER_SET_DEFAULT,
                DDLFeature.ALTER_DROP_DEFAULT,
                DDLFeature.ALTER_ADD_CONSTRAINT,
                DDLFeature.ALTER_DROP_CONSTRAINT,
                DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS,
                DDLFeature.ALTER_ADD_PRIMARY_KEY,
                DDLFeature.ALTER_DROP_PRIMARY_KEY,
                DDLFeature.ALTER_ADD_FOREIGN_KEY,
                DDLFeature.ALTER_DROP_FOREIGN_KEY,
                DDLFeature.ALTER_ADD_UNIQUE,
                DDLFeature.ALTER_ADD_CHECK,
                DDLFeature.ALTER_MULTIPLE_ACTIONS,
                /* indexes */
                DDLFeature.CREATE_INDEX,
                DDLFeature.CREATE_UNIQUE_INDEX,
                DDLFeature.DROP_INDEX,
                DDLFeature.EXPRESSION_INDEX,
                DDLFeature.INDEX_DESCENDING,
                /* views */
                DDLFeature.CREATE_VIEW,
                DDLFeature.CREATE_OR_REPLACE_VIEW,
                DDLFeature.VIEW_CHECK_OPTION,
                DDLFeature.VIEW_COLUMN_LIST,
                /* other objects */
                DDLFeature.TRUNCATE_TABLE,
                /* types */
                DDLFeature.TYPE_JSON,
                DDLFeature.TYPE_ENUM,
                DDLFeature.TYPE_TEXT,
                DDLFeature.TYPE_BOOLEAN,
                DDLFeature.TYPE_BLOB,
                DDLFeature.TYPE_NUMERIC_PRECISION,
                DDLFeature.TYPE_CHAR_LENGTH,
                DDLFeature.TYPE_TIMESTAMP,
                DDLFeature.TYPE_DATE,
                DDLFeature.TYPE_TIME));
    }

    @Override
    public List<String> getSessionSetupStatements() {
        return List.of(
                // strict mode: otherwise MySQL silently truncates/coerces values, which would mask
                // constraint violations
                "SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION'",
                // otherwise the first TIMESTAMP column silently gains
                // DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP and NOT NULL
                "SET SESSION explicit_defaults_for_timestamp = ON");
    }

    @Override
    public String renderType(DataType type) {
        return typeMapper.render(type);
    }

    /** @return {@code null}: MySQL has no schema namespace separate from the connection's database */
    @Override
    public String getDefaultSchemaName() {
        return null;
    }

    /**
     * Every generated database gets a database of its own, named after the configured schema and the
     * generated database.
     *
     * <p>MySQL's schema <em>is</em> a database, so this creates one per generated database. Without
     * it the second generated database of a run collides with the first: all of them connect to the
     * server database named in the configuration.</p>
     */
    @Override
    public String schemaNameForGeneratedDatabase(String requested, String databaseName) {
        String base = requested == null || requested.isBlank() ? "ddlsmith" : requested;
        if (databaseName == null || databaseName.isBlank()) {
            return base;
        }
        // MySQL truncates identifiers at 64 characters and rejects a name longer than that outright
        String candidate = base + "_" + databaseName;
        return candidate.length() <= 64 ? candidate : candidate.substring(candidate.length() - 64);
    }

    @Override
    public List<String> dropNamespaceStatements(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return List.of();
        }
        return List.of("DROP DATABASE IF EXISTS " + quote(schemaName));
    }

    /**
     * Creates the generated database.
     *
     * <p>MySQL declares no {@code CREATE_SCHEMA}, so the creation script cannot do it: without this
     * the connection's {@code USE} would name a database that does not exist.</p>
     */
    @Override
    public List<String> createNamespaceStatements(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return List.of();
        }
        return List.of("CREATE DATABASE IF NOT EXISTS " + quote(schemaName));
    }

    /**
     * Switches the connection to the generated database, after the settings that every connection
     * needs.
     *
     * <p>MySQL renders table names unqualified, so the connection's current database decides where
     * they land; {@code USE} is the only way to put one generated database's objects somewhere of
     * their own. {@link MySqlIntrospector} then reads the same name back with
     * {@code SELECT DATABASE()}.</p>
     */
    @Override
    public List<String> getSessionSetupStatements(String schemaName) {
        List<String> statements = new ArrayList<>(getSessionSetupStatements());
        if (schemaName != null && !schemaName.isBlank()) {
            statements.add("USE " + quote(schemaName));
        }
        return statements;
    }

    @Override
    public String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Rendered in MySQL's column definition order - {@code type [GENERATED ALWAYS AS (expr)
     * [STORED|VIRTUAL]] [NOT NULL] [DEFAULT e] [AUTO_INCREMENT] [UNIQUE] [COMMENT '...']
     * [COLLATE c] [CHECK (expr)]} - because MySQL's grammar fixes that order, unlike PostgreSQL
     * where column constraints may appear in any order.</p>
     *
     * <p>{@code AUTO_INCREMENT} is only emitted when the column is part of the table's primary key;
     * MySQL rejects an auto column that is not a key ("there can be only one auto column and it must
     * be defined as a key"). A column that is marked as identity but is not a key is rendered as a
     * plain column: the relaxation is deliberate, because emitting {@code AUTO_INCREMENT} would make
     * the whole {@code CREATE TABLE} fail.</p>
     *
     * <p>Expression defaults must be parenthesised in MySQL 8.0.13+ ({@code DEFAULT (expr)}); the
     * expression is passed through verbatim, so the generator is responsible for the parentheses on
     * expression defaults (a literal needs none).</p>
     */
    @Override
    protected String renderColumnDefinition(Column column) {
        StringBuilder sb = new StringBuilder();
        sb.append(quote(column.getName())).append(' ').append(renderType(column.getType()));
        if (column.isGenerated()) {
            sb.append(" GENERATED ALWAYS AS (").append(column.getGeneratedExpression()).append(')');
            if (column.isGeneratedStored() && getFeatures().supports(DDLFeature.GENERATED_STORED)) {
                sb.append(" STORED");
            } else if (getFeatures().supports(DDLFeature.GENERATED_VIRTUAL)) {
                sb.append(" VIRTUAL");
            }
        }
        if (!column.isNullable()) {
            sb.append(" NOT NULL");
        }
        if (column.hasDefault() && !column.isGenerated()) {
            // a generated column may not carry a DEFAULT
            sb.append(" DEFAULT ").append(column.getDefaultExpression());
        }
        if (column.isIdentity() && !column.isGenerated() && isAutoIncrementAllowed(column)) {
            sb.append(" AUTO_INCREMENT");
        }
        if (column.isUnique() && getFeatures().supports(DDLFeature.INLINE_UNIQUE)) {
            sb.append(" UNIQUE");
        }
        if (column.getComment() != null && getFeatures().supports(DDLFeature.COLUMN_COMMENT)) {
            sb.append(" COMMENT ").append(sqlString(column.getComment()));
        }
        if (column.getCollation() != null && getFeatures().supports(DDLFeature.COLLATION)) {
            sb.append(" COLLATE ").append(column.getCollation());
        }
        if (column.getCheckExpression() != null && getFeatures().supports(DDLFeature.INLINE_CHECK)) {
            sb.append(" CHECK (").append(column.getCheckExpression()).append(')');
        }
        return sb.toString();
    }

    private boolean isAutoIncrementAllowed(Column column) {
        Table table = renderingTable;
        if (table == null) {
            // ALTER TABLE ... ADD COLUMN: the AST action does not carry the table, so an inline
            // UNIQUE is the only key membership we can prove
            return column.isUnique();
        }
        return table.getPrimaryKey() != null && table.getPrimaryKey().getColumns().contains(column.getName());
    }

    /**
     * {@inheritDoc}
     *
     * <p>MySQL renders a table comment as a table option, not as a {@code COMMENT ON} statement:
     * {@code ... ) COMMENT = '...'}.</p>
     */
    @Override
    public String visit(CreateTable statement) {
        Table previous = renderingTable;
        renderingTable = statement.getTable();
        try {
            String sql = super.visit(statement);
            String comment = statement.getTable().getComment();
            if (comment != null && getFeatures().supports(DDLFeature.TABLE_COMMENT)) {
                return sql + " COMMENT = " + sqlString(comment);
            }
            return sql;
        } finally {
            renderingTable = previous;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@link #NO_SCHEMA_SUPPORT}: MySQL's {@code CREATE SCHEMA} creates a database, which
     * is not what DDLSmith models. Gated by the absent {@code CREATE_SCHEMA} feature.</p>
     */
    @Override
    public String visit(org.ddlsmith.ddl.CreateSchema statement) {
        return NO_SCHEMA_SUPPORT;
    }

    /** {@inheritDoc} */
    @Override
    public String visit(org.ddlsmith.ddl.DropSchema statement) {
        // DROP SCHEMA would drop a database; never render it
        return NO_SCHEMA_SUPPORT;
    }

    /** {@inheritDoc} */
    @Override
    public String visit(org.ddlsmith.ddl.CreateSequence statement) {
        return NO_SEQUENCE_SUPPORT;
    }

    /** {@inheritDoc} */
    @Override
    public String visit(org.ddlsmith.ddl.DropSequence statement) {
        return NO_SEQUENCE_SUPPORT;
    }

    /**
     * {@code CREATE [UNIQUE] INDEX name ON table (key parts)}.
     *
     * <p>{@code statement.isIfNotExists()} is deliberately ignored: MySQL has no
     * {@code CREATE INDEX IF NOT EXISTS} clause (MariaDB does) and answers
     * {@code ERROR 1064 ... near 'IF NOT EXISTS i ON t(a)'}. The feature is therefore <em>not</em>
     * declared, so the generator never asks for the clause. Declaring it while not rendering it — as
     * this dialect used to do — made the idempotence oracle test a promise the engine never made: it
     * executed the statement twice, the second run failed with a duplicate key name, and every MySQL
     * database of the first live run was reported as "CREATE INDEX IF NOT EXISTS is not idempotent".
     * A feature set is a contract; the crash oracle exists precisely to catch a contract the engine
     * does not honour, so it must not contain one the engine was never asked about.</p>
     */
    @Override
    public String visit(CreateIndex statement) {
        Index index = statement.getIndex();
        StringBuilder sb = new StringBuilder("CREATE ");
        if (index.isUnique()) {
            sb.append("UNIQUE ");
        }
        sb.append("INDEX ").append(quote(index.getName()));
        sb.append(" ON ").append(quote(statement.getTableName()));
        sb.append(" (").append(renderIndexKey(index)).append(')');
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>MySQL 8.0.13+ functional key parts must be parenthesised: {@code CREATE INDEX i ON t
     * ((lower(name)))}. Each key part that is not a bare identifier is therefore wrapped in
     * parentheses, and a trailing {@code ASC}/{@code DESC} is kept outside them (MySQL writes the
     * direction after the parenthesised expression).</p>
     */
    @Override
    protected String renderIndexKey(Index index) {
        if (!index.isExpressionIndex()) {
            return joinQuoted(index.getColumns());
        }
        List<String> parts = new ArrayList<>();
        for (String expression : index.getExpressions()) {
            parts.add(renderKeyPart(expression));
        }
        return String.join(", ", parts);
    }

    private static String renderKeyPart(String expression) {
        String text = expression.trim();
        String direction = "";
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.endsWith(" DESC") || upper.endsWith(" ASC")) {
            direction = text.substring(text.length() - 5);
            text = text.substring(0, text.length() - 5).trim();
        }
        if (isBareIdentifier(text)) {
            return text + direction;
        }
        return "(" + text + ")" + direction;
    }

    private static boolean isBareIdentifier(String text) {
        if (text.isEmpty()) {
            return false;
        }
        if (text.charAt(0) == '`' && text.charAt(text.length() - 1) == '`' && text.length() > 1) {
            return true;
        }
        if (!Character.isLetter(text.charAt(0)) && text.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code DROP INDEX [IF EXISTS] name ON table}.
     *
     * <p>MySQL - unlike PostgreSQL - requires the table name, and its {@code DROP INDEX} statement
     * has no {@code IF EXISTS} clause. {@code INDEX_NAME_QUALIFIED_BY_TABLE} is therefore absent
     * (the inherited renderer would emit {@code DROP INDEX table.index}, which MySQL rejects) and
     * the {@code IF EXISTS} clause is never rendered even though {@code DROP_INDEX_IF_EXISTS} is
     * declared in the feature set.</p>
     */
    @Override
    public String visit(DropIndex statement) {
        StringBuilder sb = new StringBuilder("DROP INDEX ");
        sb.append(quote(statement.getIndexName()));
        if (statement.getTableName() != null) {
            sb.append(" ON ").append(quote(statement.getTableName()));
        }
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>MySQL accepts {@code TRUE}/{@code FALSE}, quoted strings and {@code X'..'} hex literals.
     * Backslashes are doubled in string literals: the session setup does not set
     * {@code NO_BACKSLASH_ESCAPES}, so MySQL treats {@code \} as an escape character and a literal
     * backslash must be written {@code \\}.</p>
     */
    @Override
    public String renderLiteral(Object value, DataType type) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "TRUE" : "FALSE";
        }
        if (value instanceof byte[]) {
            return hexLiteral((byte[]) value);
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (type != null && type.getCategory() == DataType.Category.BLOB && value instanceof String) {
            return hexLiteral(((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return sqlString(value.toString());
    }

    private static String hexLiteral(byte[] bytes) {
        StringBuilder sb = new StringBuilder("X'");
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.append('\'').toString();
    }

    /** A single-quoted MySQL string literal, with backslash and quote escaped. */
    private static String sqlString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    @Override
    public String renderAlterAction(AlterTableAction action) {
        return action.accept(mySqlAlterActionRenderer);
    }

    /**
     * Renders a constraint definition with the constraint name emitted exactly once.
     *
     * <p><b>Contract gap.</b> {@code AbstractDialect.renderConstraintDefinition} prepends
     * {@code CONSTRAINT <name>} itself and then calls {@code renderForeignKeyClause}, which prepends
     * the very same name again when the {@link ForeignKey} carries one, so adding a <em>named</em>
     * foreign key would render as
     * {@code ADD CONSTRAINT fk_x CONSTRAINT fk_x FOREIGN KEY (...)}. The foreign key is copied
     * without its name here so that the name appears once.</p>
     */
    protected String renderConstraintDefinitionOnce(ConstraintDefinition definition) {
        if (definition.getKind() != ConstraintDefinition.Kind.FOREIGN_KEY || definition.getName() == null) {
            return renderConstraintDefinition(definition);
        }
        ForeignKey foreignKey = definition.getForeignKey();
        ForeignKey unnamed = new ForeignKey(foreignKey.getColumns(), foreignKey.getReferencedTable(),
                foreignKey.getReferencedColumns()).setOnDelete(foreignKey.getOnDelete())
                        .setOnUpdate(foreignKey.getOnUpdate()).setDeferrable(foreignKey.isDeferrable())
                        .setInitiallyDeferred(foreignKey.isInitiallyDeferred());
        return "CONSTRAINT " + quote(definition.getName()) + " " + renderForeignKeyClause(unnamed);
    }

    /** Renders the MySQL spelling of every {@code ALTER TABLE} action. */
    protected class MySqlAlterActionRenderer extends AlterActionRenderer {

        @Override
        public String visit(AddConstraint action) {
            return "ADD " + renderConstraintDefinitionOnce(action.getDefinition());
        }

        @Override
        public String visit(AddColumn action) {
            // MySQL 8.0 has no ADD COLUMN IF NOT EXISTS (ALTER_ADD_COLUMN_IF_NOT_EXISTS is absent)
            return "ADD COLUMN " + renderColumnDefinition(action.getColumn());
        }

        @Override
        public String visit(DropColumn action) {
            // MySQL has no IF EXISTS and no CASCADE for DROP COLUMN
            return "DROP COLUMN " + quote(action.getColumnName());
        }

        @Override
        public String visit(AlterColumnType action) {
            // MySQL replaces the entire column definition with MODIFY COLUMN. The AST action only
            // carries the new type, so NOT NULL / DEFAULT / AUTO_INCREMENT / COMMENT of the column
            // are lost unless the generator combines the type change with the other attributes.
            return "MODIFY COLUMN " + quote(action.getColumnName()) + " " + renderType(action.getNewType());
        }

        @Override
        public String visit(SetDefault action) {
            // MySQL takes a literal, or an expression in parentheses, and nothing in between:
            // `ALTER COLUMN d SET DEFAULT CURRENT_TIMESTAMP` is a syntax error near 'CURRENT_TIMESTAMP'
            // (error 1064), while `SET DEFAULT (CURRENT_TIMESTAMP)` is accepted and stored as an
            // expression default (measured on 26.7.0). A quoted string, a number and the boolean
            // keywords are literals and stay as they are.
            String expression = action.getExpression();
            if (isExpressionDefault(expression)) {
                return "ALTER COLUMN " + quote(action.getColumnName()) + " SET DEFAULT (" + expression + ")";
            }
            return "ALTER COLUMN " + quote(action.getColumnName()) + " SET DEFAULT " + expression;
        }

        /**
         * @return true when the default is something MySQL requires to be parenthesised: not a quoted
         *         string, not a number and not one of the literal keywords
         */
        private static boolean isExpressionDefault(String expression) {
            if (expression == null) {
                return false;
            }
            String text = expression.trim();
            if (text.isEmpty()) {
                return false;
            }
            char first = text.charAt(0);
            if (first == '\'' || first == '"' || first == '(' || Character.isDigit(first)
                    || first == '-' || first == '+') {
                return false;
            }
            return !text.equalsIgnoreCase("TRUE") && !text.equalsIgnoreCase("FALSE")
                    && !text.equalsIgnoreCase("NULL");
        }

        @Override
        public String visit(SetNotNull action) {
            throw new UnsupportedOperationException(
                    "MySQL has no ALTER COLUMN ... SET NOT NULL; use MODIFY COLUMN with the full column definition");
        }

        @Override
        public String visit(DropNotNull action) {
            throw new UnsupportedOperationException(
                    "MySQL has no ALTER COLUMN ... DROP NOT NULL; use MODIFY COLUMN with the full column definition");
        }

        /**
         * MySQL's {@code DROP CONSTRAINT} does not exist: each constraint kind has its own
         * statement. The dispatch is driven by {@link ConstraintDefinition#getKind()}:
         * <ul>
         *   <li>{@code FOREIGN KEY} - {@code DROP FOREIGN KEY name} (a foreign key is the only
         *       constraint MySQL can drop by name with a dedicated clause)</li>
         *   <li>{@code PRIMARY KEY} - {@code DROP PRIMARY KEY}: a MySQL primary key is always named
         *       {@code PRIMARY}, so the declared name is not part of the syntax and is ignored</li>
         *   <li>{@code UNIQUE} - {@code DROP INDEX name}, because a MySQL unique constraint <em>is</em>
         *       an index</li>
         *   <li>{@code CHECK} - {@code DROP CHECK name} (MySQL 8.0.16+)</li>
         * </ul>
         * <p>When the action carries no {@link ConstraintDefinition} the kind is inferred from the
         * constraint name prefix ({@code pk_}, {@code fk_}, {@code uq_}, {@code ck_}, ...), which is
         * how DDLSmith names generated constraints; the fallback is {@code DROP FOREIGN KEY}. MySQL
         * has no {@code IF EXISTS} clause here, so {@code action.isIfExists()} is ignored.</p>
         */
        @Override
        public String visit(DropConstraint action) {
            ConstraintDefinition definition = action.getDefinition();
            ConstraintDefinition.Kind kind = definition != null ? definition.getKind()
                    : inferConstraintKind(action.getConstraintName());
            switch (kind) {
            case PRIMARY_KEY:
                return "DROP PRIMARY KEY";
            case FOREIGN_KEY:
                return "DROP FOREIGN KEY " + quote(action.getConstraintName());
            case UNIQUE:
                return "DROP INDEX " + quote(action.getConstraintName());
            case CHECK:
                return "DROP CHECK " + quote(action.getConstraintName());
            default:
                throw new IllegalStateException("unhandled constraint kind " + kind);
            }
        }
    }

    private static ConstraintDefinition.Kind inferConstraintKind(String constraintName) {
        String name = constraintName == null ? "" : constraintName.toLowerCase(Locale.ROOT);
        if (name.startsWith("pk") || name.startsWith("primary")) {
            return ConstraintDefinition.Kind.PRIMARY_KEY;
        }
        if (name.startsWith("uq") || name.startsWith("unique") || name.startsWith("key")) {
            return ConstraintDefinition.Kind.UNIQUE;
        }
        if (name.startsWith("ck") || name.startsWith("check")) {
            return ConstraintDefinition.Kind.CHECK;
        }
        return ConstraintDefinition.Kind.FOREIGN_KEY;
    }
}
