package org.ddlsmith.dbms.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ddl.AlterTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
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
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.introspection.SchemaNormalizer;
import org.junit.jupiter.api.Test;

/**
 * Tests for the SQLite dialect, type mapper and introspector.
 *
 * <p>These mirror {@link SelfCheck}: the same sample schema is created in an in-memory SQLite
 * database and read back, so a JUnit run and a {@code SelfCheck} run fail for the same reasons.</p>
 */
class SqliteDialectTest {

    private final SqliteDialect dialect = new SqliteDialect();
    private final SqliteTypeMapper typeMapper = dialect.getTypeMapper();

    /* ------------------------------------------------------------------ types */

    @Test
    void everySupportedTypeRoundTrips() {
        DataType[] supported = { DataType.smallint(), DataType.integer(), DataType.bigint(),
                DataType.decimal(10, 2), DataType.decimal(6, 0), DataType.real(), DataType.doublePrecision(),
                DataType.charType(1), DataType.charType(5), DataType.varchar(20), DataType.varchar(255),
                DataType.text(), DataType.bool(), DataType.date(), DataType.time(), DataType.timestamp(),
                DataType.blob() };
        for (DataType type : supported) {
            assertEquals(type, typeMapper.parse(typeMapper.render(type)),
                    "round trip of " + type.getName());
        }
    }

    @Test
    void typesSqliteDoesNotHaveAreMappedLossilyAndNotDeclared() {
        assertEquals("TIMESTAMP", typeMapper.render(DataType.timestampWithTimeZone()));
        assertEquals("TEXT", typeMapper.render(DataType.json()));
        assertEquals("TEXT", typeMapper.render(DataType.uuid()));
        assertEquals("TEXT", typeMapper.render(DataType.interval()));
        assertEquals("TEXT", typeMapper.render(DataType.arrayOf(DataType.integer())));
        assertEquals("TEXT", typeMapper.render(DataType.enumOf(Arrays.asList("a", "b"))));
        assertFalse(dialect.getFeatures().supports(DDLFeature.TYPE_JSON));
        assertFalse(dialect.getFeatures().supports(DDLFeature.TYPE_UUID));
        assertFalse(dialect.getFeatures().supports(DDLFeature.TYPE_ENUM));
        assertFalse(dialect.getFeatures().supports(DDLFeature.TYPE_ARRAY));
        assertFalse(dialect.getFeatures().supports(DDLFeature.TYPE_TIMESTAMPTZ));
    }

    @Test
    void declaredTypesAreParsedTolerantly() {
        assertEquals(DataType.integer(), typeMapper.parse("int"));
        assertEquals(DataType.integer(), typeMapper.parse(" Integer "));
        assertEquals(DataType.smallint(), typeMapper.parse("int2"));
        assertEquals(DataType.bigint(), typeMapper.parse("int8"));
        assertEquals(DataType.varchar(255), typeMapper.parse("varchar"));
        assertEquals(DataType.varchar(30), typeMapper.parse("character varying(30)"));
        assertEquals(DataType.charType(1), typeMapper.parse("char"));
        assertEquals(DataType.decimal(8, 3), typeMapper.parse("numeric(8,3)"));
        assertEquals(DataType.decimal(10, 0), typeMapper.parse("NUMERIC"));
        assertEquals(DataType.doublePrecision(), typeMapper.parse("double precision"));
        assertEquals(DataType.bool(), typeMapper.parse("bool"));
        assertEquals(DataType.timestamp(), typeMapper.parse("datetime"));
        assertEquals(DataType.json(), typeMapper.parse("json"));
        assertEquals(null, typeMapper.parse("SOMETYPE"));
        assertEquals(null, typeMapper.parse(""));
        assertEquals(null, typeMapper.parse(null));
        assertEquals(DataType.integer(), typeMapper.affinityOf("POINT"));
        assertEquals(DataType.blob(), typeMapper.affinityOf(""));
    }

    /* ------------------------------------------------------------------ features */

    @Test
    void featureSetMatchesSqlite() {
        for (DDLFeature feature : Arrays.asList(DDLFeature.IF_NOT_EXISTS, DDLFeature.DROP_IF_EXISTS,
                DDLFeature.CASCADE_DROP, DDLFeature.TEMPORARY_TABLE, DDLFeature.COMPOSITE_PRIMARY_KEY,
                DDLFeature.NAMED_PRIMARY_KEY, DDLFeature.COMPOSITE_FOREIGN_KEY, DDLFeature.COMPOSITE_UNIQUE,
                DDLFeature.NAMED_UNIQUE, DDLFeature.NAMED_CHECK, DDLFeature.SELF_REFERENTIAL_FK,
                DDLFeature.NAMED_FOREIGN_KEY, DDLFeature.TABLE_LEVEL_FOREIGN_KEY, DDLFeature.INLINE_UNIQUE,
                DDLFeature.INLINE_CHECK, DDLFeature.COLUMN_LEVEL_PRIMARY_KEY, DDLFeature.FK_ON_DELETE,
                DDLFeature.FK_ON_UPDATE, DDLFeature.FK_CASCADE, DDLFeature.FK_SET_NULL,
                DDLFeature.FK_SET_DEFAULT, DDLFeature.FK_RESTRICT, DDLFeature.FK_ENFORCED_BY_PRAGMA,
                DDLFeature.DEFAULT_LITERAL, DDLFeature.DEFAULT_EXPRESSION,
                DDLFeature.DEFAULT_CURRENT_TIMESTAMP, DDLFeature.NOT_NULL_COLUMN, DDLFeature.AUTO_INCREMENT,
                DDLFeature.GENERATED_VIRTUAL, DDLFeature.GENERATED_STORED, DDLFeature.COLLATION,
                DDLFeature.NULLS_DISTINCT, DDLFeature.ALTER_ADD_COLUMN, DDLFeature.ALTER_DROP_COLUMN,
                DDLFeature.ALTER_RENAME_COLUMN, DDLFeature.ALTER_RENAME_TABLE, DDLFeature.CREATE_INDEX,
                DDLFeature.CREATE_UNIQUE_INDEX, DDLFeature.CREATE_INDEX_IF_NOT_EXISTS, DDLFeature.DROP_INDEX,
                DDLFeature.DROP_INDEX_IF_EXISTS, DDLFeature.PARTIAL_INDEX, DDLFeature.EXPRESSION_INDEX,
                DDLFeature.CREATE_VIEW, DDLFeature.VIEW_COLUMN_LIST, DDLFeature.TYPE_TEXT,
                DDLFeature.TYPE_BLOB, DDLFeature.TYPE_NUMERIC_PRECISION, DDLFeature.TYPE_CHAR_LENGTH,
                DDLFeature.TYPE_TIMESTAMP, DDLFeature.TYPE_DATE, DDLFeature.TYPE_TIME,
                DDLFeature.TYPE_BOOLEAN)) {
            assertTrue(dialect.getFeatures().supports(feature), "should be supported: " + feature);
        }
        for (DDLFeature feature : Arrays.asList(DDLFeature.CREATE_SCHEMA, DDLFeature.DROP_SCHEMA,
                DDLFeature.QUALIFIED_NAMES, DDLFeature.ALTER_COLUMN_TYPE, DDLFeature.ALTER_SET_NOT_NULL,
                DDLFeature.ALTER_DROP_NOT_NULL, DDLFeature.ALTER_SET_DEFAULT, DDLFeature.ALTER_DROP_DEFAULT,
                DDLFeature.ALTER_ADD_CONSTRAINT, DDLFeature.ALTER_DROP_CONSTRAINT,
                DDLFeature.ALTER_ADD_PRIMARY_KEY, DDLFeature.ALTER_DROP_PRIMARY_KEY,
                DDLFeature.ALTER_ADD_FOREIGN_KEY, DDLFeature.ALTER_DROP_FOREIGN_KEY,
                DDLFeature.ALTER_ADD_UNIQUE, DDLFeature.ALTER_ADD_CHECK,
                DDLFeature.ALTER_DROP_COLUMN_CASCADE, DDLFeature.ALTER_MULTIPLE_ACTIONS,
                DDLFeature.DEFERRABLE_FK, DDLFeature.INITIALLY_DEFERRED, DDLFeature.MATCH_FULL,
                DDLFeature.INDEX_METHOD, DDLFeature.INCLUDE_COLUMNS,
                DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE, DDLFeature.CREATE_OR_REPLACE_VIEW,
                DDLFeature.MATERIALIZED_VIEW, DDLFeature.CREATE_SEQUENCE, DDLFeature.DROP_SEQUENCE,
                DDLFeature.SEQUENCE_OPTIONS, DDLFeature.TRUNCATE_TABLE, DDLFeature.TRUNCATE_CASCADE,
                DDLFeature.TABLE_COMMENT, DDLFeature.COLUMN_COMMENT, DDLFeature.TYPE_ARRAY,
                DDLFeature.TYPE_JSON, DDLFeature.TYPE_UUID, DDLFeature.TYPE_ENUM,
                DDLFeature.TYPE_TIMESTAMPTZ, DDLFeature.TYPE_DOMAIN,
                DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS)) {
            assertFalse(dialect.getFeatures().supports(feature), "should not be supported: " + feature);
        }
        assertEquals("sqlite", dialect.getName());
        assertEquals("main", dialect.getDefaultSchemaName());
        assertEquals("customer", dialect.normalizeIdentifier("CUSTOMER"));
        assertTrue(dialect.getSessionSetupStatements().contains("PRAGMA foreign_keys = ON"));
    }

    /* ------------------------------------------------------------------ rendering */

    @Test
    void objectsSqliteDoesNotHaveAreRenderedAsComments() {
        assertEquals(SqliteDialect.NO_SCHEMA_SUPPORT,
                dialect.render(new org.ddlsmith.ddl.CreateSchema("s", false)));
        assertEquals(SqliteDialect.NO_SEQUENCE_SUPPORT,
                dialect.render(new org.ddlsmith.ddl.DropSequence("s", false, false)));
        assertEquals("DELETE FROM t", dialect.render(new TruncateTable("t", true, false)));
        // SQLite rejects DROP TABLE ... CASCADE, so the flag is accepted and not rendered
        assertEquals("DROP TABLE t", dialect.render(new DropTable("t", false, true)));
    }

    @Test
    void createOrReplaceViewExpandsToTwoStatements() {
        List<String> statements = dialect.renderAll(
                new CreateView(new View("v", "SELECT 1"), true, false));
        assertEquals(2, statements.size());
        assertEquals("DROP VIEW IF EXISTS v", statements.get(0));
        assertEquals("CREATE VIEW v AS SELECT 1", statements.get(1));
    }

    @Test
    void multiActionAlterTableIsSplit() {
        AlterTable alter = new AlterTable("t");
        alter.addAction(new DropColumn("a", null));
        alter.addAction(new RenameColumn("b", "c"));
        assertEquals(Arrays.asList("ALTER TABLE t DROP COLUMN a", "ALTER TABLE t RENAME COLUMN b TO c"),
                dialect.renderAll(alter));
    }

    @Test
    void alterActionsSqliteDoesNotHaveThrow() {
        assertThrows(UnsupportedOperationException.class,
                () -> dialect.renderAlterAction(new AlterColumnType("a", DataType.text(), DataType.integer(), null)));
        assertThrows(UnsupportedOperationException.class, () -> dialect.renderAlterAction(new SetNotNull("a")));
        assertThrows(UnsupportedOperationException.class, () -> dialect.renderAlterAction(new DropNotNull("a")));
        assertThrows(UnsupportedOperationException.class, () -> dialect.renderAlterAction(new SetDefault("a", "0")));
        assertThrows(UnsupportedOperationException.class, () -> dialect.renderAlterAction(new DropDefault("a")));
        assertThrows(UnsupportedOperationException.class, () -> dialect.renderAlterAction(
                new AddConstraint(ConstraintDefinition.unique(new UniqueConstraint(Arrays.asList("a"))))));
        assertThrows(UnsupportedOperationException.class,
                () -> dialect.renderAlterAction(new DropConstraint("ck", null, false, false)));
    }

    @Test
    void addColumnWorksAroundSqliteRestrictions() {
        // NOT NULL without a default is rejected by SQLite on a non-empty table
        assertEquals("ADD COLUMN c INTEGER NOT NULL DEFAULT 0",
                dialect.renderAlterAction(new AddColumn(new Column("c", DataType.integer(), false))));
        assertEquals("ADD COLUMN c TEXT NOT NULL DEFAULT ''",
                dialect.renderAlterAction(new AddColumn(new Column("c", DataType.text(), false))));
        // a UNIQUE column cannot be added; the caller has to create a unique index instead
        assertEquals("ADD COLUMN c TEXT",
                dialect.renderAlterAction(new AddColumn(new Column("c", DataType.text()).setUnique(true))));
        // AUTOINCREMENT requires INTEGER PRIMARY KEY, so a non-key identity column degrades
        assertEquals("ADD COLUMN c INTEGER", dialect.renderAlterAction(
                new AddColumn(new Column("c", DataType.integer()).setIdentity(true))));
    }

    @Test
    void literals() {
        assertEquals("NULL", dialect.renderLiteral(null, DataType.text()));
        assertEquals("1", dialect.renderLiteral(Boolean.TRUE, DataType.bool()));
        assertEquals("0", dialect.renderLiteral(Boolean.FALSE, DataType.bool()));
        assertEquals("'it''s'", dialect.renderLiteral("it's", DataType.text()));
        assertEquals("'2024-03-01'", dialect.renderLiteral(java.sql.Date.valueOf("2024-03-01"), DataType.date()));
        assertEquals("X'0AFF'", dialect.renderLiteral(new byte[] { 10, (byte) 0xFF }, DataType.blob()));
    }

    /* ------------------------------------------------------------------ round trips */

    @Test
    void schemaRoundTripIsClean() throws Exception {
        try (DDLSmithDBConnection connection = connect()) {
            SelfCheck.RoundTripResult result = SelfCheck.executeAndIntrospect(connection, dialect, false);
            assertTrue(result.differences.isEmpty(),
                    () -> "schema round trip differences:\n" + SchemaNormalizer.render(result.differences));
            assertEquals(6, result.actual.getTables().size());
            assertEquals(1, result.actual.getViews().size());
        }
    }

    @Test
    void identityPartialExpressionIndexAndCollationSurvive() throws Exception {
        try (DDLSmithDBConnection connection = connect()) {
            SelfCheck.RoundTripResult result = SelfCheck.executeAndIntrospect(connection, dialect, false);
            Table customer = result.actual.getTable("customer");
            assertNotNull(customer);
            assertTrue(customer.getColumn("id").isIdentity());
            assertEquals("NOCASE", customer.getColumn("name").getCollation());
            Table warehouse = result.actual.getTable("warehouse");
            assertEquals("capacity > 0", indexOf(warehouse, "idx_warehouse_capacity").getPredicate());
            Table product = result.actual.getTable("product");
            assertEquals(Arrays.asList("lower(description)"),
                    indexOf(product, "idx_product_description_expr").getExpressions());
            Table employee = result.actual.getTable("employee");
            assertTrue(employee.getForeignKeys().get(0).isSelfReferential());
            assertEquals(org.ddlsmith.common.schema.ReferentialAction.CASCADE,
                    employee.getForeignKeys().get(0).getOnDelete());
        }
    }

    @Test
    void introspectorCapabilitiesMatchWhatIsRead() {
        SqliteIntrospector introspector = new SqliteIntrospector();
        assertTrue(introspector.canIntrospect(
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.COLUMNS));
        assertTrue(introspector.canIntrospect(
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.GENERATED_COLUMNS));
        assertFalse(introspector.canIntrospect(
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.SEQUENCES));
        assertFalse(introspector.canIntrospect(
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.COMMENTS));
        assertFalse(introspector.supports(DDLFeature.NAMED_FOREIGN_KEY));
        assertTrue(introspector.supports(DDLFeature.AUTO_INCREMENT));
        assertTrue(introspector.supports(DDLFeature.PARTIAL_INDEX));
    }

    @Test
    void foreignKeysAreEnforcedAfterTheSetupStatements() throws Exception {
        try (DDLSmithDBConnection connection = connect()) {
            for (String sql : dialect.getSessionSetupStatements()) {
                connection.executeStatement(sql);
            }
            connection.executeStatement("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            connection.executeStatement("CREATE TABLE child (id INTEGER PRIMARY KEY, pid INTEGER,"
                    + " FOREIGN KEY (pid) REFERENCES parent (id))");
            connection.executeStatement("INSERT INTO parent (id) VALUES (1)");
            connection.executeStatement("INSERT INTO child (id, pid) VALUES (1, 1)");
            assertThrows(java.sql.SQLException.class,
                    () -> connection.executeStatement("INSERT INTO child (id, pid) VALUES (2, 99)"));
        }
    }

    @Test
    void generatedColumnsAreVisibleThroughTableXinfo() throws Exception {
        try (DDLSmithDBConnection connection = connect()) {
            Table table = new Table("computed");
            table.addColumn(new Column("base", DataType.integer(), false));
            table.addColumn(new Column("doubled", DataType.integer(), true)
                    .setGeneratedExpression("base * 2").setGeneratedStored(false));
            RelationalSchema intended = new RelationalSchema("generated");
            intended.addTable(table);
            connection.executeStatement(dialect.render(new org.ddlsmith.ddl.CreateTable(table)));
            SqliteIntrospector introspector = new SqliteIntrospector();
            RelationalSchema actual = introspector.introspect(connection, "main");
            assertEquals("base * 2", actual.getTable("computed").getColumn("doubled").getGeneratedExpression());
            assertTrue(SchemaNormalizer.diff(SchemaNormalizer.erase(intended, introspector.getCapabilities()),
                    actual).isEmpty());
        }
    }

    private static Index indexOf(Table table, String name) {
        for (Index index : table.getIndexes()) {
            if (index.getName().equalsIgnoreCase(name)) {
                return index;
            }
        }
        throw new AssertionError("no index " + name + " in " + table.getName());
    }

    private static DDLSmithDBConnection connect() throws java.sql.SQLException {
        return new DDLSmithDBConnection(DriverManager.getConnection("jdbc:sqlite::memory:"), "test");
    }
}
