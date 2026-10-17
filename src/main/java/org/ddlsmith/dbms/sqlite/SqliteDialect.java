package org.ddlsmith.dbms.sqlite;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.AbstractDialect;
import org.ddlsmith.ddl.CreateSchema;
import org.ddlsmith.ddl.CreateSequence;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.DropSchema;
import org.ddlsmith.ddl.DropSequence;
import org.ddlsmith.ddl.DropTable;
import org.ddlsmith.ddl.DropView;
import org.ddlsmith.ddl.TruncateTable;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.ddlsmith.ddl.alter.DropColumn;
import org.ddlsmith.ddl.alter.DropConstraint;
import org.ddlsmith.ddl.alter.DropDefault;
import org.ddlsmith.ddl.alter.DropNotNull;
import org.ddlsmith.ddl.alter.RenameColumn;
import org.ddlsmith.ddl.alter.RenameTable;
import org.ddlsmith.ddl.alter.SetDefault;
import org.ddlsmith.ddl.alter.SetNotNull;

/**
 * The SQLite dialect (SQLite 3.49, as bundled with {@code org.xerial:sqlite-jdbc}).
 *
 * <p>SQLite is the odd one out among the engines DDLsmith targets: it has no schemas, no sequences,
 * no {@code ALTER TABLE} beyond adding, dropping and renaming, no boolean literals, and it does not
 * enforce foreign keys unless {@code PRAGMA foreign_keys = ON} is set on every connection. The
 * differences that matter for rendering are made explicit here; everything else is inherited from
 * {@link AbstractDialect}.</p>
 */
public class SqliteDialect extends AbstractDialect {

    /** Rendered when the generator asks for a schema although SQLite has none. */
    static final String NO_SCHEMA_SUPPORT = "-- sqlite has no schema support";
    /** Rendered when the generator asks for a sequence although SQLite has none. */
    static final String NO_SEQUENCE_SUPPORT = "-- sqlite has no sequences";

    private final SqliteTypeMapper typeMapper = new SqliteTypeMapper();
    private final DDLFeatureSet features = buildFeatures();
    private final AlterActionRenderer sqliteAlterActionRenderer = new AlterActionRenderer();

    /* ------------------------------------------------------------------ identity */

    @Override
    public String getName() {
        return "sqlite";
    }

    @Override
    public DDLFeatureSet getFeatures() {
        return features;
    }

    @Override
    public String renderType(DataType type) {
        return typeMapper.render(type);
    }

    /** The type mapper shared with {@link SqliteIntrospector}, so that both sides agree on types. */
    public SqliteTypeMapper getTypeMapper() {
        return typeMapper;
    }

    @Override
    public String getDefaultSchemaName() {
        return "main";
    }

    /**
     * SQLite has no namespace to put objects in: every table lives in {@code main} (or {@code temp}
     * for temporary tables), and a schema name is neither rendered ({@code QUALIFIED_NAMES} is not
     * declared) nor reported by the catalog. Generated objects are therefore always described as
     * living in {@code main}, which is also what {@link SqliteIntrospector} reports, so the intended
     * and the actual schema agree instead of merely ignoring the difference.
     */
    @Override
    public String getEffectiveSchemaName(String requested) {
        return getDefaultSchemaName();
    }

    @Override
    public String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }

    /**
     * SQLite does not enforce foreign keys unless the pragma is enabled, and the pragma is per
     * connection. Without this statement every generated foreign key would be decorative and every
     * referential-action oracle would silently test nothing.
     */
    @Override
    public List<String> getSessionSetupStatements() {
        return List.of("PRAGMA foreign_keys = ON");
    }

    /* ------------------------------------------------------------------ feature set */

    /**
     * The features SQLite 3.49 really supports.
     *
     * <p>Both this list and {@link #UNSUPPORTED} are exhaustive: {@link #buildFeatures()} fails
     * loudly when a {@link DDLFeature} constant is classified by neither or by both. That matters
     * because the shared enum grows as other engines need new flags, and a feature that is silently
     * missing drops the construct from every generated schema, while one that is wrongly present
     * produces a false engine finding.</p>
     */
    private static final EnumSet<DDLFeature> SUPPORTED = EnumSet.of(
            /* identifiers and namespaces */
            DDLFeature.IF_NOT_EXISTS, DDLFeature.DROP_IF_EXISTS,
            // SQLite rejects the CASCADE keyword on DROP, but dropping a table already removes its
            // indexes and triggers, so the dialect accepts the flag and renders a plain DROP
            DDLFeature.CASCADE_DROP, DDLFeature.TEMPORARY_TABLE,
            /* table level constraints */
            DDLFeature.COMPOSITE_PRIMARY_KEY, DDLFeature.NAMED_PRIMARY_KEY,
            DDLFeature.COMPOSITE_FOREIGN_KEY, DDLFeature.COMPOSITE_UNIQUE, DDLFeature.NAMED_UNIQUE,
            // inline UNIQUE and table level UNIQUE (...) are both accepted; SQLite implements them
            // with an automatic index, which the introspector reads back (origin 'u')
            DDLFeature.UNIQUE_CONSTRAINT,
            DDLFeature.NAMED_CHECK, DDLFeature.SELF_REFERENTIAL_FK, DDLFeature.NAMED_FOREIGN_KEY,
            DDLFeature.TABLE_LEVEL_FOREIGN_KEY, DDLFeature.INLINE_UNIQUE, DDLFeature.INLINE_CHECK,
            DDLFeature.COLUMN_LEVEL_PRIMARY_KEY,
            /* referential actions */
            DDLFeature.FK_ON_DELETE, DDLFeature.FK_ON_UPDATE, DDLFeature.FK_CASCADE,
            DDLFeature.FK_SET_NULL, DDLFeature.FK_SET_DEFAULT, DDLFeature.FK_RESTRICT,
            // off by default, turned on per connection by PRAGMA foreign_keys = ON
            DDLFeature.FK_ENFORCED_BY_PRAGMA,
            /* column definitions */
            DDLFeature.DEFAULT_LITERAL, DDLFeature.DEFAULT_EXPRESSION,
            DDLFeature.DEFAULT_CURRENT_TIMESTAMP, DDLFeature.NOT_NULL_COLUMN,
            // INTEGER PRIMARY KEY AUTOINCREMENT, which requires the column to be the primary key
            DDLFeature.AUTO_INCREMENT,
            DDLFeature.GENERATED_COLUMN, DDLFeature.GENERATED_STORED, DDLFeature.GENERATED_VIRTUAL,
            DDLFeature.COLLATION,
            // NULLs are distinct in a UNIQUE index, which is the SQL default
            DDLFeature.NULLS_DISTINCT,
            /* ALTER TABLE: exactly one action per statement, ADD/DROP/RENAME COLUMN and RENAME TO */
            DDLFeature.ALTER_ADD_COLUMN, DDLFeature.ALTER_DROP_COLUMN, DDLFeature.ALTER_RENAME_COLUMN,
            DDLFeature.ALTER_RENAME_TABLE,
            /* indexes */
            DDLFeature.CREATE_INDEX, DDLFeature.CREATE_UNIQUE_INDEX,
            DDLFeature.CREATE_INDEX_IF_NOT_EXISTS, DDLFeature.DROP_INDEX, DDLFeature.DROP_INDEX_IF_EXISTS,
            DDLFeature.EXPRESSION_INDEX, DDLFeature.PARTIAL_INDEX,
            /* views */
            DDLFeature.CREATE_VIEW, DDLFeature.VIEW_COLUMN_LIST,
            /* types */
            DDLFeature.TYPE_TEXT, DDLFeature.TYPE_BOOLEAN, DDLFeature.TYPE_BLOB,
            DDLFeature.TYPE_NUMERIC_PRECISION, DDLFeature.TYPE_CHAR_LENGTH, DDLFeature.TYPE_TIMESTAMP,
            DDLFeature.TYPE_DATE, DDLFeature.TYPE_TIME);

    /**
     * Every feature SQLite 3.49 does not really support, each for a reason worth recording. The
     * classifier in {@link #buildFeatures()} makes sure nothing falls between the two lists.
     */
    private static final EnumSet<DDLFeature> UNSUPPORTED = EnumSet.of(
            // no namespaces: only main and temp exist, and they cannot be created
            DDLFeature.CREATE_SCHEMA, DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS,
            DDLFeature.DROP_SCHEMA, DDLFeature.DROP_SCHEMA_CASCADE, DDLFeature.QUALIFIED_NAMES,
            // foreign keys: SQLite parses DEFERRABLE/MATCH but ignores them, enforcement is opt-in,
            // and a foreign key may reference a table that does not exist yet (SQLite resolves the
            // target lazily, at DML time)
            DDLFeature.INLINE_FOREIGN_KEY, DDLFeature.DEFERRABLE_FK, DDLFeature.INITIALLY_DEFERRED,
            DDLFeature.MATCH_FULL, DDLFeature.FK_ENFORCED_BY_DEFAULT, DDLFeature.FK_TARGET_MUST_EXIST,
            // columns: only INTEGER PRIMARY KEY AUTOINCREMENT is an identity, there is no SERIAL,
            // no unsigned types and nowhere to store a comment
            DDLFeature.IDENTITY_COLUMN, DDLFeature.SERIAL, DDLFeature.COLUMN_TYPE_UNSIGNED,
            DDLFeature.COLUMN_COMMENT, DDLFeature.TABLE_COMMENT,
            // ALTER TABLE: one action per statement, and only ADD/DROP/RENAME COLUMN and RENAME TO
            DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS, DDLFeature.ALTER_DROP_COLUMN_CASCADE,
            DDLFeature.ALTER_COLUMN_TYPE, DDLFeature.ALTER_COLUMN_TYPE_USING,
            DDLFeature.ALTER_SET_NOT_NULL, DDLFeature.ALTER_DROP_NOT_NULL,
            DDLFeature.ALTER_SET_DEFAULT, DDLFeature.ALTER_DROP_DEFAULT,
            DDLFeature.ALTER_ADD_CONSTRAINT, DDLFeature.ALTER_DROP_CONSTRAINT,
            DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS, DDLFeature.ALTER_ADD_PRIMARY_KEY,
            DDLFeature.ALTER_DROP_PRIMARY_KEY, DDLFeature.ALTER_ADD_FOREIGN_KEY,
            DDLFeature.ALTER_DROP_FOREIGN_KEY, DDLFeature.ALTER_ADD_UNIQUE, DDLFeature.ALTER_ADD_CHECK,
            DDLFeature.ALTER_MULTIPLE_ACTIONS,
            // indexes: no access method, no INCLUDE, no DESC in the DDL AST, and an index name is
            // global (never qualified by its table)
            DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE, DDLFeature.INDEX_METHOD,
            DDLFeature.INCLUDE_COLUMNS, DDLFeature.INDEX_DESCENDING,
            // views: no OR REPLACE, no materialization, no CHECK OPTION
            DDLFeature.CREATE_OR_REPLACE_VIEW, DDLFeature.MATERIALIZED_VIEW, DDLFeature.VIEW_CHECK_OPTION,
            // no sequences and no TRUNCATE (the dialect renders DELETE FROM as a safety net)
            DDLFeature.CREATE_SEQUENCE, DDLFeature.DROP_SEQUENCE, DDLFeature.SEQUENCE_OPTIONS,
            DDLFeature.TRUNCATE_TABLE, DDLFeature.TRUNCATE_CASCADE,
            // types SQLite does not have: the mapper folds them onto TEXT/TIMESTAMP on purpose. The
            // schema adapter maps INTERVAL to VARCHAR(32) when TYPE_INTERVAL is absent.
            DDLFeature.TYPE_ARRAY, DDLFeature.TYPE_JSON, DDLFeature.TYPE_UUID, DDLFeature.TYPE_ENUM,
            DDLFeature.TYPE_TIMESTAMPTZ, DDLFeature.TYPE_INTERVAL, DDLFeature.TYPE_DOMAIN,
            // an ENUM could be emulated with a CHECK constraint, but this dialect maps ENUM to TEXT
            // instead, so declaring the emulation would be a lie
            DDLFeature.ENUM_AS_CHECK);

    private static DDLFeatureSet buildFeatures() {
        EnumSet<DDLFeature> undecided = EnumSet.allOf(DDLFeature.class);
        undecided.removeAll(SUPPORTED);
        undecided.removeAll(UNSUPPORTED);
        EnumSet<DDLFeature> classifiedTwice = EnumSet.copyOf(SUPPORTED);
        classifiedTwice.retainAll(UNSUPPORTED);
        if (!undecided.isEmpty() || !classifiedTwice.isEmpty()) {
            throw new IllegalStateException("SqliteDialect does not classify every DDLFeature: undecided="
                    + undecided + ", classified twice=" + classifiedTwice);
        }
        return new DDLFeatureSet(SUPPORTED);
    }

    /* ------------------------------------------------------------------ identifiers */

    /**
     * Identifiers are rendered unquoted, as {@link Dialect#quote(String)} does by default.
     *
     * <p>The shared contract keeps names unquoted so that they stay case-insensitive and comparable
     * across engines, and it deals with the one hazard that creates — a generated name that is a
     * keyword — in {@link org.ddlsmith.ddl.gen.SchemaAdapter}, which renames reserved words using
     * {@link #getReservedWords()}. Quoting here as well would hide that mechanism instead of
     * testing it, and would make the same schema render differently from the other dialects.</p>
     */

    /* ------------------------------------------------------------------ objects SQLite does not have */

    @Override
    public String visit(CreateSchema statement) {
        // gated by CREATE_SCHEMA, which is absent from the feature set; this is only a safety net
        return NO_SCHEMA_SUPPORT;
    }

    @Override
    public String visit(DropSchema statement) {
        return NO_SCHEMA_SUPPORT;
    }

    @Override
    public String visit(CreateSequence statement) {
        // gated by CREATE_SEQUENCE, which is absent from the feature set
        return NO_SEQUENCE_SUPPORT;
    }

    @Override
    public String visit(DropSequence statement) {
        return NO_SEQUENCE_SUPPORT;
    }

    /** SQLite has no {@code TRUNCATE}; {@code DELETE FROM} is the closest equivalent. */
    @Override
    public String visit(TruncateTable statement) {
        // TRUNCATE_TABLE is absent from the feature set, so the generator never asks for this
        return "DELETE FROM " + renderTableReference(statement.getSchemaName(), statement.getTableName());
    }

    /* ------------------------------------------------------------------ drops */

    /**
     * SQLite has no {@code CASCADE} keyword on {@code DROP TABLE}: the statement is rejected with a
     * syntax error. Dropping a table already removes its indexes and triggers, so the flag is
     * accepted and simply not rendered.
     */
    @Override
    public String visit(DropTable statement) {
        StringBuilder sb = new StringBuilder("DROP TABLE ");
        if (statement.isIfExists() && getFeatures().supports(DDLFeature.DROP_IF_EXISTS)) {
            sb.append("IF EXISTS ");
        }
        sb.append(renderTableReference(statement.getSchemaName(), statement.getTableName()));
        return sb.toString();
    }

    /** As for {@code DROP TABLE}: SQLite rejects {@code DROP VIEW ... CASCADE}. */
    @Override
    public String visit(DropView statement) {
        StringBuilder sb = new StringBuilder("DROP VIEW ");
        if (statement.isIfExists() && getFeatures().supports(DDLFeature.DROP_IF_EXISTS)) {
            sb.append("IF EXISTS ");
        }
        sb.append(renderTableReference(statement.getSchemaName(), statement.getViewName()));
        return sb.toString();
    }

    /** Index names are global in SQLite, so the table name is never part of the statement. */
    @Override
    public String visit(DropIndex statement) {
        StringBuilder sb = new StringBuilder("DROP INDEX ");
        if (statement.isIfExists() && getFeatures().supports(DDLFeature.DROP_INDEX_IF_EXISTS)) {
            sb.append("IF EXISTS ");
        }
        sb.append(quote(statement.getIndexName()));
        return sb.toString();
    }

    /* ------------------------------------------------------------------ CREATE TABLE */

    @Override
    public String visit(CreateTable statement) {
        Table table = statement.getTable();
        StringBuilder sb = new StringBuilder("CREATE ");
        if (table.isTemporary() && getFeatures().supports(DDLFeature.TEMPORARY_TABLE)) {
            sb.append("TEMPORARY ");
        }
        sb.append("TABLE ");
        if (statement.isIfNotExists() && getFeatures().supports(DDLFeature.IF_NOT_EXISTS)) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(quote(table.getName())).append(" (");

        Column identityPrimaryKey = autoincrementColumn(table);
        List<String> parts = new ArrayList<>();
        for (Column column : table.getColumns()) {
            parts.add(renderColumnDefinition(column, column == identityPrimaryKey));
        }
        if (identityPrimaryKey == null) {
            // with an INTEGER PRIMARY KEY AUTOINCREMENT column the key is declared on the column,
            // and SQLite rejects a second PRIMARY KEY clause ("more than one primary key")
            String primaryKey = renderPrimaryKeyClause(table);
            if (primaryKey != null) {
                parts.add(primaryKey);
            }
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            parts.add(renderUniqueClause(unique));
        }
        for (CheckConstraint check : table.getCheckConstraints()) {
            parts.add(renderCheckClause(check));
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            String rendered = renderForeignKeyClause(foreignKey);
            if (rendered != null) {
                parts.add(rendered);
            }
        }
        sb.append(String.join(", ", parts));
        sb.append(')');
        return sb.toString();
    }

    /**
     * The one condition under which SQLite's only auto-increment form applies: the column is an
     * identity column and it is the table's single-column primary key.
     *
     * <p>{@link #visit(CreateTable)} emits {@code INTEGER PRIMARY KEY AUTOINCREMENT} exactly when
     * this holds, and {@link #effectiveType} reports the column as {@code INTEGER} exactly when this
     * holds. Both go through this predicate so that the rendered DDL and the intended schema can
     * never drift apart: SQLite refuses {@code AUTOINCREMENT} on anything but an
     * {@code INTEGER PRIMARY KEY}, so the stored type is necessarily {@code INTEGER} whatever the
     * mapper asked for.</p>
     */
    private static boolean isAutoincrementPrimaryKey(Table table, String columnName, boolean identity) {
        if (!identity || table.getPrimaryKey() == null || table.getPrimaryKey().isComposite()) {
            return false;
        }
        String keyColumn = table.getPrimaryKey().getColumns().get(0);
        return keyColumn != null && keyColumn.equals(columnName);
    }

    /**
     * @return the column that carries {@code INTEGER PRIMARY KEY AUTOINCREMENT}, or null when the
     *         table has no identity column SQLite can express
     */
    private static Column autoincrementColumn(Table table) {
        if (table.getPrimaryKey() == null || table.getPrimaryKey().isComposite()) {
            return null;
        }
        Column column = table.getColumn(table.getPrimaryKey().getColumns().get(0));
        return column != null && isAutoincrementPrimaryKey(table, column.getName(), column.isIdentity()) ? column : null;
    }

    /**
     * The type SQLite will report for a column, which is not always the type that was requested.
     *
     * <p>Two rewrites happen while rendering and both are reflected here, because
     * {@link org.ddlsmith.ddl.gen.SchemaAdapter} puts this method's result into the intended schema
     * and the round-trip oracle would otherwise report a difference that SQLite's syntax makes
     * unavoidable:</p>
     *
     * <ul>
     * <li>an integral identity column that is the single-column primary key is rendered as
     * {@code INTEGER PRIMARY KEY AUTOINCREMENT}, so it is stored as {@code INTEGER} even when the
     * mapper asked for {@code BIGINT} (SQLite rejects {@code AUTOINCREMENT} on a non-INTEGER key);</li>
     * <li>a type without its parameters is rendered as its bare name ({@code VARCHAR} rather than
     * {@code VARCHAR(n)}), which {@link SqliteTypeMapper#parse(String)} reads back with the
     * documented defaults ({@code VARCHAR(255)}, {@code CHAR(1)}, {@code DECIMAL(10,0)}).</li>
     * </ul>
     */
    @Override
    public DataType effectiveType(DataType requested, Column column, Table table) {
        if (isAutoincrementPrimaryKey(table, column.getName(), column.isIdentity())) {
            return DataType.integer();
        }
        switch (requested.getCategory()) {
        case DECIMAL:
            if (requested.getPrecision() == null) {
                return DataType.decimal(10, 0);
            }
            if (requested.getScale() == null) {
                return DataType.decimal(requested.getPrecision(), 0);
            }
            return requested;
        case CHAR:
            return requested.getLength() == null ? DataType.charType(1) : requested;
        case VARCHAR:
            return requested.getLength() == null ? DataType.varchar(255) : requested;
        default:
            return requested;
        }
    }

    @Override
    protected String renderColumnDefinition(Column column) {
        // context free form: an identity column outside a single-column primary key degrades to a
        // plain INTEGER, because SQLite has no other auto-increment
        return renderColumnDefinition(column, false);
    }

    private String renderColumnDefinition(Column column, boolean identityPrimaryKey) {
        StringBuilder sb = new StringBuilder();
        sb.append(quote(column.getName())).append(' ');
        sb.append(identityPrimaryKey ? "INTEGER" : renderType(column.getType()));
        if (identityPrimaryKey) {
            sb.append(" PRIMARY KEY AUTOINCREMENT");
        }
        if (column.isGenerated() && getFeatures().supports(DDLFeature.GENERATED_COLUMN)) {
            sb.append(" GENERATED ALWAYS AS (").append(column.getGeneratedExpression()).append(')');
            if (column.isGeneratedStored()) {
                if (getFeatures().supports(DDLFeature.GENERATED_STORED)) {
                    sb.append(" STORED");
                }
            } else if (getFeatures().supports(DDLFeature.GENERATED_VIRTUAL)) {
                sb.append(" VIRTUAL");
            }
        }
        if (column.getCollation() != null && getFeatures().supports(DDLFeature.COLLATION)) {
            sb.append(" COLLATE ").append(column.getCollation());
        }
        if (!column.isNullable()) {
            sb.append(" NOT NULL");
        }
        if (column.hasDefault()) {
            sb.append(" DEFAULT ").append(column.getDefaultExpression());
        }
        if (column.isUnique() && !identityPrimaryKey && getFeatures().supports(DDLFeature.INLINE_UNIQUE)) {
            // the primary key is already unique; SQLite accepts the redundancy but it adds a second
            // index for nothing
            sb.append(" UNIQUE");
        }
        if (column.getCheckExpression() != null && getFeatures().supports(DDLFeature.INLINE_CHECK)) {
            sb.append(" CHECK (").append(column.getCheckExpression()).append(')');
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ views */

    /**
     * SQLite has no {@code CREATE OR REPLACE VIEW}, so a replacing statement becomes two
     * statements. A single {@link #visit(CreateView)} can only return one string, which is why the
     * expansion happens here.
     */
    @Override
    public List<String> renderAll(DDLStatement statement) {
        if (statement instanceof CreateView && ((CreateView) statement).isOrReplace()) {
            View view = ((CreateView) statement).getView();
            return List.of("DROP VIEW IF EXISTS " + quote(view.getName()), render(statement));
        }
        // AbstractDialect splits a multi-action ALTER TABLE because ALTER_MULTIPLE_ACTIONS is absent
        return super.renderAll(statement);
    }

    /* ------------------------------------------------------------------ ALTER TABLE */

    @Override
    public String renderAlterAction(AlterTableAction action) {
        return action.accept(sqliteAlterActionRenderer);
    }

    /**
     * Renders the {@code ALTER TABLE} actions SQLite has. Everything else fails loudly: the
     * generator gates on {@link DDLFeature}, so reaching one of those methods means a gating bug,
     * and silently rendering something else would turn it into a bogus engine finding.
     */
    protected class AlterActionRenderer extends AbstractDialect.AlterActionRenderer {

        @Override
        public String visit(AddColumn action) {
            return "ADD COLUMN " + renderAddedColumnDefinition(action.getColumn());
        }

        @Override
        public String visit(DropColumn action) {
            // SQLite has neither IF EXISTS nor CASCADE on DROP COLUMN
            return "DROP COLUMN " + quote(action.getColumnName());
        }

        @Override
        public String visit(RenameColumn action) {
            return "RENAME COLUMN " + quote(action.getFrom()) + " TO " + quote(action.getTo());
        }

        @Override
        public String visit(RenameTable action) {
            return "RENAME TO " + quote(action.getTo());
        }

        @Override
        public String visit(AlterColumnType action) {
            throw unsupported("ALTER COLUMN ... TYPE");
        }

        @Override
        public String visit(SetNotNull action) {
            throw unsupported("ALTER COLUMN ... SET NOT NULL");
        }

        @Override
        public String visit(DropNotNull action) {
            throw unsupported("ALTER COLUMN ... DROP NOT NULL");
        }

        @Override
        public String visit(SetDefault action) {
            throw unsupported("ALTER COLUMN ... SET DEFAULT");
        }

        @Override
        public String visit(DropDefault action) {
            throw unsupported("ALTER COLUMN ... DROP DEFAULT");
        }

        @Override
        public String visit(AddConstraint action) {
            throw unsupported("ADD CONSTRAINT");
        }

        @Override
        public String visit(DropConstraint action) {
            throw unsupported("DROP CONSTRAINT");
        }
    }

    private static UnsupportedOperationException unsupported(String action) {
        return new UnsupportedOperationException("sqlite does not support ALTER TABLE " + action
                + "; the generator must gate on DDLFeature, so reaching this point is a bug");
    }

    /**
     * Renders a column for {@code ALTER TABLE ... ADD COLUMN}, working around the three restrictions
     * SQLite imposes on a new column:
     *
     * <ul>
     * <li>it may not be a PRIMARY KEY ({@code Cannot add a PRIMARY KEY column}) and may not be
     * UNIQUE ({@code Cannot add a UNIQUE column}), so both constraints are dropped here; a caller
     * that needs uniqueness must issue {@code CREATE UNIQUE INDEX} instead;</li>
     * <li>if it is NOT NULL it must have a non-NULL default, otherwise SQLite refuses to add it to
     * a non-empty table ({@code Cannot add a NOT NULL column with default value NULL}). The DDL AST
     * cannot express "add the column with a temporary default and drop the default afterwards", so
     * the column is added with a typed zero default instead. That default is visible in the
     * introspected schema, which is the price of expressing the statement at all.</li>
     * </ul>
     */
    private String renderAddedColumnDefinition(Column column) {
        Column relaxed = column.copy();
        relaxed.setIdentity(false);
        relaxed.setUnique(false);
        if (!relaxed.isNullable() && !relaxed.hasDefault()) {
            relaxed.setDefaultExpression(typedZeroLiteral(relaxed.getType()));
        }
        return renderColumnDefinition(relaxed, false);
    }

    /** The value a NOT NULL column has to default to so that SQLite accepts {@code ADD COLUMN}. */
    private static String typedZeroLiteral(DataType type) {
        switch (type.getCategory()) {
        case CHAR:
        case VARCHAR:
        case TEXT:
        case JSON:
        case UUID:
        case INTERVAL:
        case ARRAY:
        case ENUM:
            return "''";
        default:
            // numerics, booleans, dates, times, timestamps and blobs all accept 0
            return "0";
        }
    }

    /* ------------------------------------------------------------------ literals */

    @Override
    public String renderLiteral(Object value, DataType type) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Object[]) {
            // SQLite has no array type, so a column of that type was adapted to text by the adapter
            return renderArrayAsText((Object[]) value);
        }
        if (value instanceof Boolean) {
            // SQLite has no boolean literals: TRUE/FALSE are only aliases for 1/0 since 3.23
            return ((Boolean) value) ? "1" : "0";
        }
        if (value instanceof byte[]) {
            StringBuilder hex = new StringBuilder("X'");
            for (byte b : (byte[]) value) {
                hex.append(String.format("%02X", b));
            }
            return hex.append('\'').toString();
        }
        if (value instanceof java.sql.Timestamp || value instanceof java.sql.Date
                || value instanceof java.sql.Time) {
            // SQLite stores dates and times as ISO-8601 text
            return quoteLiteral(value.toString());
        }
        if (value instanceof java.util.Date) {
            return quoteLiteral(new java.sql.Timestamp(((java.util.Date) value).getTime()).toString());
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return quoteLiteral(value.toString());
    }

    private static String quoteLiteral(String text) {
        return "'" + text.replace("'", "''") + "'";
    }
}
