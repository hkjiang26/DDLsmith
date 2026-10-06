package org.ddlsmith.dbms.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.AlterTable;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateSchema;
import org.ddlsmith.ddl.CreateSequence;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.DropSchema;
import org.ddlsmith.ddl.DropSequence;
import org.ddlsmith.ddl.DropTable;
import org.ddlsmith.ddl.TruncateTable;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.ddl.alter.DropColumn;
import org.ddlsmith.ddl.alter.DropConstraint;
import org.ddlsmith.ddl.alter.DropDefault;
import org.ddlsmith.ddl.alter.DropNotNull;
import org.ddlsmith.ddl.alter.RenameColumn;
import org.ddlsmith.ddl.alter.RenameTable;
import org.ddlsmith.ddl.alter.SetDefault;
import org.ddlsmith.ddl.alter.SetNotNull;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;
import org.junit.jupiter.api.Test;

/**
 * MySQL dialect and introspector tests.
 *
 * <p>Runnable without a database server: the dialect side asserts exact SQL text, the introspector
 * side feeds synthetic {@code information_schema} rows through the row translation methods.</p>
 */
class MySqlDialectTest {

    private final MySqlDialect dialect = new MySqlDialect();
    private final MySqlTypeMapper typeMapper = new MySqlTypeMapper();
    private final MySqlIntrospector introspector = new MySqlIntrospector();

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    /* ------------------------------------------------------------------ dialect identity */

    @Test
    void dialectIdentity() {
        assertEquals("mysql", dialect.getName());
        assertNull(dialect.getDefaultSchemaName(), "the connection's database is the namespace");
        assertEquals("mytable", dialect.normalizeIdentifier("MyTable"));
        assertEquals("MyTable", dialect.quote("MyTable"));
    }

    @Test
    void sessionSetupEnablesStrictModeAndDisablesImplicitTimestampDefaults() {
        List<String> setup = dialect.getSessionSetupStatements();
        assertEquals(2, setup.size());
        assertEquals("SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION'", setup.get(0),
                "non-strict mode silently truncates values and would mask constraint violations");
        assertEquals("SET SESSION explicit_defaults_for_timestamp = ON", setup.get(1),
                "otherwise the first TIMESTAMP column silently gains DEFAULT CURRENT_TIMESTAMP and NOT NULL");
    }

    @Test
    void featureSetMatchesMySqlCapabilities() {
        assertEquals(74, dialect.getFeatures().getFeatures().size());
        for (DDLFeature feature : new DDLFeature[] { DDLFeature.IF_NOT_EXISTS, DDLFeature.DROP_IF_EXISTS,
                DDLFeature.TEMPORARY_TABLE, DDLFeature.COMPOSITE_PRIMARY_KEY, DDLFeature.NAMED_PRIMARY_KEY,
                DDLFeature.COMPOSITE_FOREIGN_KEY, DDLFeature.COMPOSITE_UNIQUE, DDLFeature.NAMED_UNIQUE,
                DDLFeature.UNIQUE_CONSTRAINT, DDLFeature.NAMED_CHECK, DDLFeature.SELF_REFERENTIAL_FK,
                DDLFeature.TABLE_LEVEL_FOREIGN_KEY, DDLFeature.INLINE_UNIQUE, DDLFeature.INLINE_CHECK,
                DDLFeature.COLUMN_LEVEL_PRIMARY_KEY, DDLFeature.FK_SET_DEFAULT, DDLFeature.AUTO_INCREMENT,
                // MySQL resolves a foreign key's target when the referencing table is created and
                // answers "Failed to open the referenced table 't_1'" otherwise, so it belongs in the
                // feature set rather than only in EngineTraits' fallback table
                DDLFeature.FK_TARGET_MUST_EXIST,
                DDLFeature.GENERATED_STORED, DDLFeature.GENERATED_VIRTUAL, DDLFeature.COLLATION,
                DDLFeature.COLUMN_COMMENT, DDLFeature.TABLE_COMMENT, DDLFeature.COLUMN_TYPE_UNSIGNED,
                DDLFeature.NULLS_DISTINCT, DDLFeature.ALTER_MULTIPLE_ACTIONS, DDLFeature.ALTER_DROP_PRIMARY_KEY,
                DDLFeature.CREATE_UNIQUE_INDEX, DDLFeature.EXPRESSION_INDEX, DDLFeature.INDEX_DESCENDING,
                DDLFeature.CREATE_OR_REPLACE_VIEW, DDLFeature.VIEW_CHECK_OPTION, DDLFeature.VIEW_COLUMN_LIST,
                DDLFeature.TRUNCATE_TABLE, DDLFeature.TYPE_JSON, DDLFeature.TYPE_ENUM }) {
            assertTrue(dialect.getFeatures().supports(feature), "expected support for " + feature);
        }
        for (DDLFeature feature : new DDLFeature[] { DDLFeature.CREATE_SCHEMA, DDLFeature.DROP_SCHEMA,
                DDLFeature.QUALIFIED_NAMES, DDLFeature.CASCADE_DROP, DDLFeature.DEFERRABLE_FK,
                DDLFeature.INITIALLY_DEFERRED, DDLFeature.MATCH_FULL, DDLFeature.CREATE_SEQUENCE,
                DDLFeature.DROP_SEQUENCE, DDLFeature.SEQUENCE_OPTIONS, DDLFeature.MATERIALIZED_VIEW,
                DDLFeature.PARTIAL_INDEX, DDLFeature.INCLUDE_COLUMNS, DDLFeature.INDEX_METHOD, DDLFeature.TYPE_ARRAY,
                DDLFeature.TYPE_UUID, DDLFeature.TYPE_TIMESTAMPTZ, DDLFeature.TYPE_INTERVAL, DDLFeature.TYPE_DOMAIN,
                DDLFeature.SERIAL, DDLFeature.IDENTITY_COLUMN, DDLFeature.ALTER_SET_NOT_NULL,
                DDLFeature.ALTER_DROP_NOT_NULL, DDLFeature.ALTER_COLUMN_TYPE_USING,
                DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS, DDLFeature.ALTER_DROP_COLUMN_CASCADE,
                DDLFeature.TRUNCATE_CASCADE, DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE,
                DDLFeature.INLINE_FOREIGN_KEY,
                // MySQL has no IF NOT EXISTS on CREATE INDEX and no IF EXISTS on DROP INDEX (MariaDB
                // has both): it answers "ERROR 1064 ... near 'IF NOT EXISTS i ON t(a)'". Declaring
                // them while the renderer silently ignored the flag made the idempotence oracle test a
                // promise the engine never made, and every MySQL database of the first live run was
                // reported as "CREATE INDEX IF NOT EXISTS is not idempotent".
                DDLFeature.CREATE_INDEX_IF_NOT_EXISTS, DDLFeature.DROP_INDEX_IF_EXISTS }) {
            assertFalse(dialect.getFeatures().supports(feature), "expected no support for " + feature);
        }
    }

    /* ------------------------------------------------------------------ types */

    @Test
    void rendersTypesInMySqlSyntax() {
        assertEquals("SMALLINT", dialect.renderType(DataType.smallint()));
        assertEquals("INT", dialect.renderType(DataType.integer()));
        assertEquals("BIGINT", dialect.renderType(DataType.bigint()));
        assertEquals("DECIMAL(10,2)", dialect.renderType(DataType.decimal(10, 2)));
        assertEquals("FLOAT", dialect.renderType(DataType.real()));
        assertEquals("DOUBLE", dialect.renderType(DataType.doublePrecision()));
        assertEquals("CHAR(5)", dialect.renderType(DataType.charType(5)));
        assertEquals("VARCHAR(20)", dialect.renderType(DataType.varchar(20)));
        assertEquals("TEXT", dialect.renderType(DataType.text()));
        assertEquals("BOOLEAN", dialect.renderType(DataType.bool()));
        assertEquals("DATE", dialect.renderType(DataType.date()));
        assertEquals("TIME", dialect.renderType(DataType.time()));
        assertEquals("DATETIME", dialect.renderType(DataType.timestamp()));
        assertEquals("TIMESTAMP", dialect.renderType(DataType.timestampWithTimeZone()));
        assertEquals("BLOB", dialect.renderType(DataType.blob()));
        assertEquals("JSON", dialect.renderType(DataType.json()));
        assertEquals("CHAR(36)", dialect.renderType(DataType.uuid()));
        assertEquals("JSON", dialect.renderType(DataType.arrayOf(DataType.integer())));
        assertEquals("TIME", dialect.renderType(DataType.interval()));
        assertEquals("ENUM('a','b')", dialect.renderType(DataType.enumOf(List.of("a", "b"))));
        assertEquals("INT UNSIGNED", dialect.renderType(DataType.integer().withUnsigned()));
    }

    @Test
    void everyNonLossyTypeSurvivesTheRoundTrip() {
        List<DataType> types = List.of(DataType.smallint(), DataType.integer(), DataType.bigint(),
                DataType.decimal(10, 2), DataType.real(), DataType.doublePrecision(), DataType.charType(5),
                DataType.varchar(20), DataType.text(), DataType.bool(), DataType.date(), DataType.time(),
                DataType.timestamp(), DataType.timestampWithTimeZone(), DataType.blob(), DataType.json(),
                DataType.enumOf(List.of("a", "b")), DataType.integer().withUnsigned());
        for (DataType type : types) {
            String rendered = typeMapper.render(type);
            assertEquals(type, typeMapper.parse(rendered), "round trip of " + type.getName() + " via " + rendered);
        }
    }

    @Test
    void lossyMappingsAreDocumented() {
        assertEquals(DataType.charType(36), typeMapper.parse(typeMapper.render(DataType.uuid())),
                "UUID becomes CHAR(36); MySQL has no UUID type");
        assertEquals(DataType.json(), typeMapper.parse(typeMapper.render(DataType.arrayOf(DataType.integer()))),
                "ARRAY becomes JSON");
        assertEquals(DataType.time(), typeMapper.parse(typeMapper.render(DataType.interval())),
                "INTERVAL becomes TIME");
    }

    @Test
    void parsesColumnTypeSpellings() {
        assertEquals(DataType.integer(), typeMapper.parse("int"));
        assertEquals(DataType.integer(), typeMapper.parse("int(11)"));
        assertEquals(DataType.integer().withUnsigned(), typeMapper.parse("int unsigned"));
        assertTrue(typeMapper.parse("int(10) unsigned").isUnsigned());
        assertTrue(typeMapper.parse("int zerofill").isUnsigned());
        assertEquals(DataType.bool(), typeMapper.parse("tinyint(1)"));
        assertEquals(DataType.smallint(), typeMapper.parse("tinyint(4)"));
        assertEquals(DataType.smallint(), typeMapper.parse("smallint(6)"));
        assertEquals(DataType.integer(), typeMapper.parse("mediumint"));
        assertEquals(DataType.bigint(), typeMapper.parse("bigint(20)"));
        assertEquals(DataType.decimal(10, 2), typeMapper.parse("decimal(10,2)"));
        assertEquals(DataType.decimal(10, 0), typeMapper.parse("decimal(10)"));
        assertEquals(DataType.decimal(10, 0), typeMapper.parse("decimal"));
        assertEquals(DataType.real(), typeMapper.parse("float"));
        assertEquals(DataType.doublePrecision(), typeMapper.parse("double"));
        assertEquals(DataType.charType(1), typeMapper.parse("char"));
        assertEquals(DataType.charType(5), typeMapper.parse("char(5)"));
        assertEquals(DataType.varchar(20), typeMapper.parse("varchar(20)"));
        assertEquals(DataType.text(), typeMapper.parse("tinytext"));
        assertEquals(DataType.text(), typeMapper.parse("longtext"));
        assertEquals(DataType.timestamp(), typeMapper.parse("datetime"));
        assertEquals(DataType.timestamp(), typeMapper.parse("datetime(6)"));
        assertEquals(DataType.timestampWithTimeZone(), typeMapper.parse("timestamp"));
        assertEquals(DataType.timestampWithTimeZone(), typeMapper.parse("timestamp(6)"));
        assertEquals(DataType.time(), typeMapper.parse("time(6)"));
        assertEquals(DataType.blob(), typeMapper.parse("varbinary(16)"));
        assertEquals(DataType.json(), typeMapper.parse("json"));
        assertEquals(DataType.enumOf(List.of("a", "b")), typeMapper.parse("enum('a','b')"));
        assertEquals(DataType.enumOf(List.of("a'b")), typeMapper.parse("enum('a''b')"));
    }

    @Test
    void returnsNullForTypesTheCanonicalModelCannotExpress() {
        for (String spelling : List.of("year", "bit(1)", "set('a','b')", "geometry", "point", "inet6", "")) {
            assertNull(typeMapper.parse(spelling), "expected " + spelling + " to be uninterpretable");
        }
        assertNull(typeMapper.parse(null));
    }

    /* ------------------------------------------------------------------ CREATE TABLE */

    @Test
    void rendersColumnDefinitionsInMySqlOrder() {
        Table table = new Table("t");
        table.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        table.addColumn(new Column("note", DataType.text()).setCollation("utf8mb4_bin").setComment("a note"));
        table.addColumn(new Column("total", DataType.decimal(12, 4)).setGeneratedExpression("id * 2")
                .setGeneratedStored(true));
        table.addColumn(new Column("counter", DataType.integer().withUnsigned()));
        table.setPrimaryKey(new PrimaryKey("pk_t", List.of("id")));
        table.setComment("a table");
        assertEquals("CREATE TABLE t (id INT NOT NULL AUTO_INCREMENT, "
                + "note TEXT COMMENT 'a note' COLLATE utf8mb4_bin, "
                + "total DECIMAL(12,4) GENERATED ALWAYS AS (id * 2) STORED, "
                + "counter INT UNSIGNED, CONSTRAINT pk_t PRIMARY KEY (id)) COMMENT = 'a table'",
                dialect.render(new CreateTable(table)));
    }

    @Test
    void autoIncrementIsOnlyEmittedForAKeyColumn() {
        Table keyed = new Table("keyed");
        keyed.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        keyed.setPrimaryKey(new PrimaryKey(List.of("id")));
        assertEquals("CREATE TABLE keyed (id INT NOT NULL AUTO_INCREMENT, PRIMARY KEY (id))",
                dialect.render(new CreateTable(keyed)));

        Table notKeyed = new Table("not_keyed");
        notKeyed.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        notKeyed.addColumn(new Column("k", DataType.integer()).setNullable(false));
        notKeyed.setPrimaryKey(new PrimaryKey(List.of("k")));
        assertEquals("CREATE TABLE not_keyed (id INT NOT NULL, k INT NOT NULL, PRIMARY KEY (k))",
                dialect.render(new CreateTable(notKeyed)),
                "MySQL rejects an auto column that is not a key, so the identity is relaxed");
    }

    @Test
    void tableCommentIsATableOptionNotASeparateStatement() {
        Table table = new Table("t");
        table.addColumn(new Column("x", DataType.integer()));
        table.setComment("it's a table");
        List<String> statements = dialect.renderAll(new CreateTable(table));
        assertEquals(1, statements.size());
        assertEquals("CREATE TABLE t (x INT) COMMENT = 'it''s a table'", statements.get(0));
    }

    @Test
    void rendersEveryConstraintForm() {
        Table table = new Table("child");
        table.addColumn(new Column("pid", DataType.integer()).setNullable(false));
        table.addColumn(new Column("seq", DataType.smallint()).setNullable(false));
        table.addColumn(new Column("label", DataType.text()).setUnique(true).setCheckExpression("label <> ''"));
        table.setPrimaryKey(new PrimaryKey(List.of("pid", "seq")));
        table.addUniqueConstraint(new UniqueConstraint("uq_child", List.of("label")));
        table.addCheckConstraint(new CheckConstraint("ck_child", "seq > 0"));
        table.addForeignKey(new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_child")
                .setOnDelete(ReferentialAction.CASCADE).setOnUpdate(ReferentialAction.SET_NULL));
        assertEquals("CREATE TABLE child (pid INT NOT NULL, seq SMALLINT NOT NULL, "
                + "label TEXT UNIQUE CHECK (label <> ''), "
                + "PRIMARY KEY (pid, seq), "
                + "CONSTRAINT uq_child UNIQUE (label), "
                + "CONSTRAINT ck_child CHECK (seq > 0), "
                + "CONSTRAINT fk_child FOREIGN KEY (pid) REFERENCES parent (id) "
                + "ON DELETE CASCADE ON UPDATE SET NULL)",
                dialect.render(new CreateTable(table)));
    }

    @Test
    void rendersRemainingStatements() {
        assertEquals("DROP TABLE IF EXISTS t", dialect.render(new DropTable("t", true, true)));
        assertEquals("TRUNCATE TABLE t", dialect.render(new TruncateTable("t", true, false)));
    }

    @Test
    void objectsMySqlDoesNotHaveRenderAsSafetyNetComments() {
        assertEquals(MySqlDialect.NO_SCHEMA_SUPPORT, dialect.render(new CreateSchema("s", true)),
                "MySQL's CREATE SCHEMA creates a database, which is not what DDLSmith models");
        assertEquals(MySqlDialect.NO_SCHEMA_SUPPORT, dialect.render(new DropSchema("s", true, true)),
                "DROP SCHEMA would drop a database");
        assertEquals(MySqlDialect.NO_SEQUENCE_SUPPORT, dialect.render(new CreateSequence(new Sequence("s"), true)));
        assertEquals(MySqlDialect.NO_SEQUENCE_SUPPORT, dialect.render(new DropSequence("s", true, true)));
    }

    /* ------------------------------------------------------------------ indexes */

    @Test
    void rendersIndexesWithParenthesisedFunctionalKeyParts() {
        assertEquals("CREATE INDEX idx_a ON t (a, b)",
                dialect.render(new CreateIndex("t", new Index("idx_a", List.of("a", "b")))));
        assertEquals("CREATE UNIQUE INDEX idx_u ON t (a)",
                dialect.render(new CreateIndex("t", new Index("idx_u", List.of("a")).setUnique(true), true)),
                "MySQL 8.0 has no CREATE INDEX IF NOT EXISTS, so the clause is never rendered");
        Index expression = new Index("idx_e");
        expression.getExpressions().add("lower(b)");
        assertEquals("CREATE INDEX idx_e ON t ((lower(b)))", dialect.render(new CreateIndex("t", expression)));
        Index descending = new Index("idx_d");
        descending.getExpressions().add("lower(b) DESC");
        assertEquals("CREATE INDEX idx_d ON t ((lower(b)) DESC)", dialect.render(new CreateIndex("t", descending)));
        Index mixed = new Index("idx_m");
        mixed.getExpressions().addAll(List.of("a", "lower(b)"));
        assertEquals("CREATE INDEX idx_m ON t (a, (lower(b)))", dialect.render(new CreateIndex("t", mixed)));
    }

    @Test
    void dropIndexQualifiesWithTheTableAndHasNoIfExists() {
        assertEquals("DROP INDEX idx_a ON t", dialect.render(new DropIndex("idx_a", "t", false, false)));
        assertEquals("DROP INDEX idx_a ON t", dialect.render(new DropIndex("idx_a", "t", true, false)),
                "MySQL 8.0 has no DROP INDEX IF EXISTS, so the clause is never rendered");
    }

    /* ------------------------------------------------------------------ ALTER TABLE */

    @Test
    void rendersEveryAlterAction() {
        assertEquals("ALTER TABLE t ADD COLUMN c INT",
                dialect.render(new AlterTable("t", new AddColumn(new Column("c", DataType.integer()), true))));
        assertEquals("ALTER TABLE t DROP COLUMN c",
                dialect.render(new AlterTable("t", new DropColumn("c", null, true, true))));
        assertEquals("ALTER TABLE t RENAME COLUMN a TO b",
                dialect.render(new AlterTable("t", new RenameColumn("a", "b"))));
        assertEquals("ALTER TABLE t RENAME TO t2", dialect.render(new AlterTable("t", new RenameTable("t", "t2"))));
        assertEquals("ALTER TABLE t MODIFY COLUMN c BIGINT",
                dialect.render(new AlterTable("t",
                        new AlterColumnType("c", DataType.bigint(), DataType.integer(), "c"))));
        assertEquals("ALTER TABLE t ALTER COLUMN c SET DEFAULT 5",
                dialect.render(new AlterTable("t", new SetDefault("c", "5"))));
        assertEquals("ALTER TABLE t ALTER COLUMN c DROP DEFAULT",
                dialect.render(new AlterTable("t", new DropDefault("c"))));
        assertEquals("ALTER TABLE t ADD COLUMN c INT, DROP COLUMN d",
                dialect.render(new AlterTable("t", List.of(new AddColumn(new Column("c", DataType.integer())),
                        new DropColumn("d", null, false, false)))));
    }

    @Test
    void addColumnEmitsAutoIncrementOnlyForAProvableKey() {
        assertEquals("ALTER TABLE t ADD COLUMN c INT AUTO_INCREMENT UNIQUE",
                dialect.render(new AlterTable("t", new AddColumn(
                        new Column("c", DataType.integer()).setIdentity(true).setUnique(true)))));
        assertEquals("ALTER TABLE t ADD COLUMN c INT",
                dialect.render(new AlterTable("t", new AddColumn(new Column("c", DataType.integer())
                        .setIdentity(true)))),
                "the ADD COLUMN action does not carry the table, so an unprovable key drops AUTO_INCREMENT");
    }

    @Test
    void setAndDropNotNullAreNotMySqlSyntax() {
        assertThrows(UnsupportedOperationException.class,
                () -> dialect.render(new AlterTable("t", new SetNotNull("c"))));
        assertThrows(UnsupportedOperationException.class,
                () -> dialect.render(new AlterTable("t", new DropNotNull("c"))));
    }

    @Test
    void dropConstraintDispatchesOnTheConstraintKind() {
        ConstraintDefinition foreignKey = ConstraintDefinition.foreignKey(
                new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_x"));
        ConstraintDefinition primaryKey = ConstraintDefinition.primaryKey(new PrimaryKey("pk_x", List.of("a")));
        ConstraintDefinition unique = ConstraintDefinition.unique(new UniqueConstraint("uq_x", List.of("a")));
        ConstraintDefinition check = ConstraintDefinition.check(new CheckConstraint("ck_x", "a > 0"));
        assertEquals("ALTER TABLE t DROP FOREIGN KEY fk_x",
                dialect.render(new AlterTable("t", new DropConstraint("fk_x", foreignKey, false, false))));
        assertEquals("ALTER TABLE t DROP PRIMARY KEY",
                dialect.render(new AlterTable("t", new DropConstraint("pk_x", primaryKey, false, false))));
        assertEquals("ALTER TABLE t DROP INDEX uq_x",
                dialect.render(new AlterTable("t", new DropConstraint("uq_x", unique, false, false))));
        assertEquals("ALTER TABLE t DROP CHECK ck_x",
                dialect.render(new AlterTable("t", new DropConstraint("ck_x", check, false, false))));
    }

    @Test
    void dropConstraintWithoutADefinitionUsesTheNameHeuristic() {
        assertEquals("ALTER TABLE t DROP FOREIGN KEY fk_child_parent",
                dialect.render(new AlterTable("t", new DropConstraint("fk_child_parent"))));
        assertEquals("ALTER TABLE t DROP INDEX uq_code",
                dialect.render(new AlterTable("t", new DropConstraint("uq_code"))));
        assertEquals("ALTER TABLE t DROP PRIMARY KEY",
                dialect.render(new AlterTable("t", new DropConstraint("pk_parent"))));
        assertEquals("ALTER TABLE t DROP CHECK ck_amount",
                dialect.render(new AlterTable("t", new DropConstraint("ck_amount"))));
    }

    @Test
    void addConstraintEmitsTheNameOnce() {
        ConstraintDefinition definition = ConstraintDefinition.foreignKey(
                new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_x"));
        assertEquals("ALTER TABLE t ADD CONSTRAINT fk_x FOREIGN KEY (pid) REFERENCES parent (id)",
                dialect.render(new AlterTable("t", new AddConstraint(definition))),
                "the contract's renderConstraintDefinition emits the name twice for a named foreign key");
        assertEquals("ALTER TABLE t ADD CONSTRAINT ck_x CHECK (a > 0)",
                dialect.render(new AlterTable("t", new AddConstraint(
                        ConstraintDefinition.check(new CheckConstraint("ck_x", "a > 0"))))));
    }

    /* ------------------------------------------------------------------ views and literals */

    @Test
    void rendersViews() {
        View view = new View("v", "SELECT id FROM parent");
        view.getColumnNames().add("id");
        view.setCheckOption("CASCADED");
        assertEquals("CREATE OR REPLACE VIEW v (id) AS SELECT id FROM parent WITH CASCADED CHECK OPTION",
                dialect.render(new CreateView(view, true, false)));
    }

    @Test
    void rendersLiterals() {
        assertEquals("TRUE", dialect.renderLiteral(Boolean.TRUE, DataType.bool()));
        assertEquals("42", dialect.renderLiteral(42, DataType.integer()));
        assertEquals("'O''Brien'", dialect.renderLiteral("O'Brien", DataType.text()));
        assertEquals("'a\\\\b'", dialect.renderLiteral("a\\b", DataType.text()),
                "backslash is an escape character in MySQL string literals");
        assertEquals("NULL", dialect.renderLiteral(null, DataType.text()));
        assertEquals("X'01AB'", dialect.renderLiteral(new byte[] { 0x01, (byte) 0xAB }, DataType.blob()));
    }

    /* ------------------------------------------------------------------ introspection */

    @Test
    void translatesColumns() {
        Column identity = introspector.toColumn(row("column_name", "id", "column_default", null, "is_nullable", "NO",
                "data_type", "int", "column_type", "int", "extra", "auto_increment", "generation_expression", "",
                "collation_name", null, "column_comment", ""), "utf8mb4_0900_ai_ci");
        assertEquals(DataType.integer(), identity.getType());
        assertTrue(identity.isIdentity());
        assertFalse(identity.isNullable());
        assertNull(identity.getComment());

        Column generated = introspector.toColumn(row("column_name", "total", "column_default", null, "is_nullable",
                "YES", "data_type", "decimal", "column_type", "decimal(12,4)", "extra", "STORED GENERATED",
                "generation_expression", "(`amount` * 2)", "collation_name", null, "column_comment", ""), null);
        assertTrue(generated.isGenerated());
        assertTrue(generated.isGeneratedStored());
        assertEquals("(`amount` * 2)", generated.getGeneratedExpression());
        assertNull(generated.getDefaultExpression());

        Column virtual = introspector.toColumn(row("column_name", "v", "column_default", null, "is_nullable", "YES",
                "data_type", "int", "column_type", "int", "extra", "VIRTUAL GENERATED", "generation_expression",
                "(`a` + 1)", "collation_name", null, "column_comment", ""), null);
        assertTrue(virtual.isGenerated());
        assertFalse(virtual.isGeneratedStored());

        assertNull(introspector.toColumn(row("column_name", "y", "is_nullable", "YES", "data_type", "year",
                "column_type", "year", "extra", "", "generation_expression", "", "column_comment", ""), null));
    }

    @Test
    void rebuildsDefaultExpressionsFromValues() {
        assertEquals("'abc'", MySqlIntrospector.toDefaultExpression("abc", DataType.varchar(10), false),
                "MySQL reports a string default without quotes");
        assertEquals("'abc'", MySqlIntrospector.toDefaultExpression("'abc'", DataType.varchar(10), false));
        assertEquals("(uuid())", MySqlIntrospector.toDefaultExpression("(uuid())", DataType.charType(36), false));
        assertEquals("CURRENT_TIMESTAMP",
                MySqlIntrospector.toDefaultExpression("CURRENT_TIMESTAMP", DataType.timestamp(), true));
        assertEquals("5", MySqlIntrospector.toDefaultExpression("5", DataType.integer(), false));

        Column column = introspector.toColumn(row("column_name", "code", "column_default", "abc", "is_nullable",
                "YES", "data_type", "varchar", "column_type", "varchar(20)", "extra", "", "generation_expression", "",
                "collation_name", "utf8mb4_0900_ai_ci", "column_comment", "the code"), "utf8mb4_0900_ai_ci");
        assertEquals("'abc'", column.getDefaultExpression());
        assertEquals("the code", column.getComment());
        assertNull(column.getCollation(), "an inherited collation is not reported");
    }

    @Test
    void reportsOnlyExplicitCollations() {
        Column explicit = introspector.toColumn(row("column_name", "note", "column_default", null, "is_nullable",
                "YES", "data_type", "text", "column_type", "text", "extra", "", "generation_expression", "",
                "collation_name", "utf8mb4_bin", "column_comment", ""), "utf8mb4_0900_ai_ci");
        assertEquals("utf8mb4_bin", explicit.getCollation());
    }

    @Test
    void translatesConstraints() {
        Table table = new Table("child");
        table.addColumn(new Column("pid", DataType.integer()));
        table.addColumn(new Column("seq", DataType.smallint()));

        introspector.applyConstraint(table, row("constraint_name", "PRIMARY", "constraint_type", "PRIMARY KEY"),
                List.of(row("column_name", "pid", "ordinal_position", 1),
                        row("column_name", "seq", "ordinal_position", 2)),
                null, null);
        assertEquals(List.of("pid", "seq"), table.getPrimaryKey().getColumns());

        introspector.applyConstraint(table, row("constraint_name", "uq_child", "constraint_type", "UNIQUE"),
                List.of(row("column_name", "seq", "ordinal_position", 1)), null, null);
        assertEquals(List.of("seq"), table.getUniqueConstraints().get(0).getColumns());

        introspector.applyConstraint(table, row("constraint_name", "fk_child", "constraint_type", "FOREIGN KEY"),
                List.of(row("column_name", "pid", "ordinal_position", 1, "referenced_table_name", "parent",
                        "referenced_column_name", "id")),
                row("update_rule", "SET NULL", "delete_rule", "CASCADE"), null);
        ForeignKey foreignKey = table.getForeignKeys().get(0);
        assertEquals(List.of("pid"), foreignKey.getColumns());
        assertEquals("parent", foreignKey.getReferencedTable());
        assertEquals(List.of("id"), foreignKey.getReferencedColumns());
        assertEquals(ReferentialAction.CASCADE, foreignKey.getOnDelete());
        assertEquals(ReferentialAction.SET_NULL, foreignKey.getOnUpdate());

        introspector.applyConstraint(table, row("constraint_name", "ck_child", "constraint_type", "CHECK"), List.of(),
                null, "(cast(`seq` as signed) > 0)");
        assertEquals("(cast(`seq` as signed) > 0)", table.getCheckConstraints().get(0).getExpression());

        introspector.applyConstraint(table, row("constraint_name", "ck_old", "constraint_type", "CHECK"), List.of(),
                null, null);
        assertEquals(1, table.getCheckConstraints().size(),
                "without information_schema.check_constraints (pre-8.0.16) the check is not reported");
    }

    @Test
    void detectsSelfReferentialForeignKeys() {
        Table table = new Table("node");
        table.addColumn(new Column("id", DataType.integer()));
        table.addColumn(new Column("parent_id", DataType.integer()));
        introspector.applyConstraint(table, row("constraint_name", "fk_node_parent", "constraint_type", "FOREIGN KEY"),
                List.of(row("column_name", "parent_id", "ordinal_position", 1, "referenced_table_name", "node",
                        "referenced_column_name", "id")),
                row("update_rule", "RESTRICT", "delete_rule", "SET DEFAULT"), null);
        assertTrue(table.getForeignKeys().get(0).isSelfReferential());
        assertEquals(ReferentialAction.SET_DEFAULT, table.getForeignKeys().get(0).getOnDelete());
        assertEquals(ReferentialAction.RESTRICT, table.getForeignKeys().get(0).getOnUpdate());
    }

    @Test
    void translatesIndexesIncludingFunctionalKeyParts() {
        Index plain = introspector.toIndex("idx_label",
                List.of(row("column_name", "label", "non_unique", 0, "seq_in_index", 1)));
        assertEquals(List.of("label"), plain.getColumns());
        assertTrue(plain.isUnique());

        Index composite = introspector.toIndex("idx_two",
                List.of(row("column_name", "a", "non_unique", 1, "seq_in_index", 1),
                        row("column_name", "b", "non_unique", 1, "seq_in_index", 2)));
        assertEquals(List.of("a", "b"), composite.getColumns());
        assertFalse(composite.isUnique());

        Index functional = introspector.toIndex("idx_lower",
                List.of(row("column_name", null, "expression", "lower(`label`)", "non_unique", 1)));
        assertEquals(List.of("lower(`label`)"), functional.getExpressions());
        assertTrue(functional.isExpressionIndex());

        Index mixed = introspector.toIndex("idx_mixed",
                List.of(row("column_name", "a", "non_unique", 1, "seq_in_index", 1),
                        row("column_name", null, "expression", "lower(`b`)", "non_unique", 1, "seq_in_index", 2)));
        assertEquals(List.of("a", "lower(`b`)"), mixed.getExpressions());
        assertEquals("lower(`b`)", MySqlIntrospector.stripDirection("lower(`b`) DESC"));
    }

    @Test
    void catalogQueriesTargetInformationSchema() {
        assertTrue(MySqlIntrospector.TABLES_SQL.contains("information_schema.tables"));
        assertTrue(MySqlIntrospector.COLUMNS_SQL.contains("column_type"));
        assertTrue(MySqlIntrospector.CONSTRAINTS_SQL.contains("information_schema.table_constraints"));
        assertTrue(MySqlIntrospector.KEY_COLUMN_USAGE_SQL.contains("referenced_column_name"));
        assertTrue(MySqlIntrospector.REFERENTIAL_CONSTRAINTS_SQL.contains("delete_rule"));
        assertTrue(MySqlIntrospector.CHECK_CONSTRAINTS_SQL.contains("check_clause"));
        assertTrue(MySqlIntrospector.INDEXES_SQL.contains("information_schema.statistics"));
        assertFalse(MySqlIntrospector.INDEXES_WITHOUT_EXPRESSION_SQL.contains("expression"),
                "the fallback query exists for servers older than 8.0.13");
        assertTrue(MySqlIntrospector.VIEWS_SQL.contains("view_definition"));
    }

    @Test
    void capabilitiesOmitPartialIndexesAndSequences() {
        assertEquals(19, introspector.getCapabilities().size());
        assertFalse(introspector.canIntrospect(IntrospectionCapability.PARTIAL_INDEXES));
        assertFalse(introspector.canIntrospect(IntrospectionCapability.SEQUENCES));
        for (IntrospectionCapability capability : new IntrospectionCapability[] { IntrospectionCapability.COLUMNS,
                IntrospectionCapability.COLUMN_TYPES, IntrospectionCapability.NULLABILITY,
                IntrospectionCapability.DEFAULTS, IntrospectionCapability.IDENTITY,
                IntrospectionCapability.GENERATED_COLUMNS, IntrospectionCapability.PRIMARY_KEYS,
                IntrospectionCapability.FOREIGN_KEYS, IntrospectionCapability.FOREIGN_KEY_ACTIONS,
                IntrospectionCapability.UNIQUE_CONSTRAINTS, IntrospectionCapability.CHECK_CONSTRAINTS,
                IntrospectionCapability.INDEXES, IntrospectionCapability.INDEX_UNIQUENESS,
                IntrospectionCapability.INDEX_COLUMNS, IntrospectionCapability.EXPRESSION_INDEXES,
                IntrospectionCapability.VIEWS, IntrospectionCapability.VIEW_DEFINITIONS,
                IntrospectionCapability.COMMENTS, IntrospectionCapability.TABLE_EXISTENCE }) {
            assertTrue(introspector.canIntrospect(capability), "expected capability " + capability);
        }
    }
}
