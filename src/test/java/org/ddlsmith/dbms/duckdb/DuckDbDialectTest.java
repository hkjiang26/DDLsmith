package org.ddlsmith.dbms.duckdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.ddlsmith.DDLsmithDBConnection;
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
import org.ddlsmith.ddl.AlterTable;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateSequence;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.TruncateTable;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.ddl.alter.DropColumn;
import org.ddlsmith.ddl.alter.DropConstraint;
import org.ddlsmith.ddl.alter.DropDefault;
import org.ddlsmith.ddl.alter.DropNotNull;
import org.ddlsmith.ddl.alter.RenameColumn;
import org.ddlsmith.ddl.alter.RenameTable;
import org.ddlsmith.ddl.alter.SetDefault;
import org.ddlsmith.ddl.alter.SetNotNull;
import org.ddlsmith.introspection.SchemaNormalizer;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the DuckDB dialect, type mapper and catalog introspector.
 *
 * <p>These tests are deliberately independent of Maven: they are compiled and executed with the
 * same {@code compile-subset.ps1} + {@code java} commands as {@link SelfCheck}, because the rest of
 * the project is being written in parallel. The type, dialect and introspection expectations are
 * the ones recorded in {@link SelfCheck}, which probes a live DuckDB 1.3 instance.</p>
 */
class DuckDbDialectTest {

    private final DuckDbDialect dialect = new DuckDbDialect();
    private final DuckDbTypeMapper mapper = new DuckDbTypeMapper();
    private final DuckDbIntrospector introspector = new DuckDbIntrospector();

    /* ------------------------------------------------------------------ types */

    @Test
    void typeMapperRoundTripsEverySupportedType() {
        for (DataType type : supportedTypes()) {
            String rendered = mapper.render(type);
            DataType parsed = mapper.parse(rendered);
            assertEquals(type, parsed, "round trip of " + type.getName() + " through " + rendered);
        }
    }

    @Test
    void typeMapperRendersTheCanonicalSpellings() {
        assertEquals("SMALLINT", mapper.render(DataType.smallint()));
        assertEquals("INTEGER", mapper.render(DataType.integer()));
        assertEquals("BIGINT", mapper.render(DataType.bigint()));
        assertEquals("DECIMAL(10,2)", mapper.render(DataType.decimal(10, 2)));
        assertEquals("REAL", mapper.render(DataType.real()));
        assertEquals("DOUBLE", mapper.render(DataType.doublePrecision()));
        assertEquals("CHAR(5)", mapper.render(DataType.charType(5)));
        assertEquals("VARCHAR(20)", mapper.render(DataType.varchar(20)));
        assertEquals("TEXT", mapper.render(DataType.text()));
        assertEquals("BOOLEAN", mapper.render(DataType.bool()));
        assertEquals("DATE", mapper.render(DataType.date()));
        assertEquals("TIME", mapper.render(DataType.time()));
        assertEquals("TIMESTAMP", mapper.render(DataType.timestamp()));
        assertEquals("TIMESTAMP WITH TIME ZONE", mapper.render(DataType.timestampWithTimeZone()));
        assertEquals("BLOB", mapper.render(DataType.blob()));
        assertEquals("JSON", mapper.render(DataType.json()));
        assertEquals("UUID", mapper.render(DataType.uuid()));
        assertEquals("INTERVAL", mapper.render(DataType.interval()));
        assertEquals("INTEGER[]", mapper.render(DataType.arrayOf(DataType.integer())));
        assertEquals("INTEGER[][]", mapper.render(DataType.arrayOf(DataType.arrayOf(DataType.integer()))));
    }

    @Test
    void typeMapperParsesWhatDuckDbReports() {
        assertEquals(DataType.smallint(), mapper.parse("SMALLINT"));
        assertEquals(DataType.integer(), mapper.parse("INTEGER"));
        assertEquals(DataType.bigint(), mapper.parse("BIGINT"));
        assertEquals(DataType.decimal(10, 2), mapper.parse("DECIMAL(10,2)"));
        assertEquals(DataType.decimal(8, 3), mapper.parse("NUMERIC(8,3)"));
        // DuckDB reports REAL as FLOAT
        assertEquals(DataType.real(), mapper.parse("FLOAT"));
        assertEquals(DataType.doublePrecision(), mapper.parse("DOUBLE"));
        assertEquals(DataType.doublePrecision(), mapper.parse("DOUBLE PRECISION"));
        // DuckDB collapses CHAR(n), VARCHAR(n) and TEXT into an unconstrained VARCHAR
        assertEquals(DataType.text(), mapper.parse("VARCHAR"));
        assertEquals(DataType.text(), mapper.parse("TEXT"));
        assertEquals(DataType.bool(), mapper.parse("BOOLEAN"));
        assertEquals(DataType.date(), mapper.parse("DATE"));
        assertEquals(DataType.time(), mapper.parse("TIME"));
        assertEquals(DataType.timestamp(), mapper.parse("TIMESTAMP"));
        assertEquals(DataType.timestampWithTimeZone(), mapper.parse("TIMESTAMP WITH TIME ZONE"));
        assertEquals(DataType.timestampWithTimeZone(), mapper.parse("TIMESTAMPTZ"));
        assertEquals(DataType.blob(), mapper.parse("BLOB"));
        assertEquals(DataType.json(), mapper.parse("JSON"));
        assertEquals(DataType.uuid(), mapper.parse("UUID"));
        assertEquals(DataType.interval(), mapper.parse("INTERVAL"));
        assertEquals(DataType.arrayOf(DataType.integer()), mapper.parse("INTEGER[]"));
        assertEquals(DataType.arrayOf(DataType.text()), mapper.parse("VARCHAR[]"));
    }

    @Test
    void typeMapperReturnsNullForTypesWithoutACanonicalEquivalent() {
        for (String engineType : List.of("STRUCT(a INTEGER)", "MAP(VARCHAR, INTEGER)", "UNION(a INTEGER)",
                "ENUM('x', 'y')", "HUGEINT", "UHUGEINT", "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "TIMETZ",
                "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS", "BIT", "VARINT")) {
            assertNull(mapper.parse(engineType), engineType + " has no canonical equivalent");
        }
        assertNull(mapper.parse(null));
        assertNull(mapper.parse("  "));
    }

    @Test
    void enumDegradesToVarcharBecauseDuckDbEnumsNeedANamedType() {
        assertEquals("VARCHAR", mapper.render(DataType.enumOf(List.of("a", "b"))));
        assertEquals("VARCHAR[]", mapper.render(DataType.arrayOf(DataType.enumOf(List.of("a")))));
    }

    @Test
    void unsignedTypesAreRefusedLoudly() {
        assertThrows(UnsupportedOperationException.class, () -> mapper.render(DataType.integer().withUnsigned()));
    }

    private static List<DataType> supportedTypes() {
        List<DataType> types = new ArrayList<>();
        types.add(DataType.smallint());
        types.add(DataType.integer());
        types.add(DataType.bigint());
        types.add(DataType.decimal(10, 2));
        types.add(DataType.real());
        types.add(DataType.doublePrecision());
        types.add(DataType.charType(5));
        types.add(DataType.varchar(20));
        types.add(DataType.text());
        types.add(DataType.bool());
        types.add(DataType.date());
        types.add(DataType.time());
        types.add(DataType.timestamp());
        types.add(DataType.timestampWithTimeZone());
        types.add(DataType.blob());
        types.add(DataType.json());
        types.add(DataType.uuid());
        types.add(DataType.interval());
        types.add(DataType.arrayOf(DataType.integer()));
        types.add(DataType.arrayOf(DataType.text()));
        types.add(DataType.arrayOf(DataType.varchar(10)));
        types.add(DataType.arrayOf(DataType.arrayOf(DataType.integer())));
        return types;
    }

    /* ------------------------------------------------------------------ features */

    @Test
    void everyFeatureConstantIsDecided() {
        Map<DDLFeature, DuckDbDialect.FeatureDecision> decisions = DuckDbDialect.featureDecisions();
        assertEquals(DDLFeature.values().length, decisions.size(),
                "every DDLFeature constant must have an explicit decision");
        for (DDLFeature feature : DDLFeature.values()) {
            DuckDbDialect.FeatureDecision decision = decisions.get(feature);
            assertNotNull(decision, "no decision for " + feature);
            assertEquals(decision.supported(), dialect.getFeatures().supports(feature),
                    feature + " is declared inconsistently with its decision");
            assertFalse(decision.reason().isBlank(), "no reason recorded for " + feature);
            // the switch in DuckDbDialect has a default branch so that the enum can grow without
            // breaking the build; a constant that lands there has not been probed yet
            assertNotEquals(DuckDbDialect.UNPROBED_REASON, decision.reason(),
                    feature + " still carries the unprobed default decision");
        }
    }

    @Test
    void foreignKeyTargetsMustExist() {
        DDLFeature feature = featureByName("FK_TARGET_MUST_EXIST");
        if (feature == null) {
            // the compiled contract in target/contract-classes predates the constant; the SelfCheck
            // probe still exercises the behaviour and prints the result
            return;
        }
        assertTrue(dialect.getFeatures().supports(feature),
                "FK_TARGET_MUST_EXIST must be declared: DuckDB rejects CREATE TABLE when the referenced "
                        + "table does not exist yet");
    }

    /**
     * Looks a {@link DDLFeature} up by name, so this test compiles and runs both before and after the
     * compiled contract in {@code target/contract-classes} is refreshed with newly added constants.
     */
    private static DDLFeature featureByName(String name) {
        try {
            return DDLFeature.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Test
    void featuresVerifiedAsSupportedAreDeclared() {
        for (DDLFeature feature : List.of(DDLFeature.CREATE_SCHEMA, DDLFeature.QUALIFIED_NAMES,
                DDLFeature.COMPOSITE_PRIMARY_KEY, DDLFeature.NAMED_PRIMARY_KEY, DDLFeature.COMPOSITE_UNIQUE,
                DDLFeature.NAMED_UNIQUE, DDLFeature.UNIQUE_CONSTRAINT, DDLFeature.NAMED_CHECK,
                DDLFeature.SELF_REFERENTIAL_FK, DDLFeature.COMPOSITE_FOREIGN_KEY, DDLFeature.INLINE_UNIQUE,
                DDLFeature.INLINE_CHECK, DDLFeature.COLUMN_LEVEL_PRIMARY_KEY, DDLFeature.FK_ON_DELETE,
                DDLFeature.FK_ON_UPDATE, DDLFeature.FK_RESTRICT, DDLFeature.FK_ENFORCED_BY_DEFAULT,
                DDLFeature.DEFAULT_LITERAL, DDLFeature.DEFAULT_EXPRESSION, DDLFeature.DEFAULT_CURRENT_TIMESTAMP,
                DDLFeature.NOT_NULL_COLUMN, DDLFeature.TABLE_COMMENT, DDLFeature.COLUMN_COMMENT,
                DDLFeature.ALTER_ADD_COLUMN, DDLFeature.ALTER_DROP_COLUMN, DDLFeature.ALTER_RENAME_COLUMN,
                DDLFeature.ALTER_RENAME_TABLE, DDLFeature.ALTER_COLUMN_TYPE, DDLFeature.ALTER_SET_NOT_NULL,
                DDLFeature.ALTER_DROP_NOT_NULL, DDLFeature.ALTER_SET_DEFAULT, DDLFeature.ALTER_DROP_DEFAULT,
                DDLFeature.ALTER_ADD_PRIMARY_KEY, DDLFeature.CREATE_INDEX, DDLFeature.CREATE_UNIQUE_INDEX,
                DDLFeature.EXPRESSION_INDEX, DDLFeature.CREATE_VIEW, DDLFeature.CREATE_OR_REPLACE_VIEW,
                DDLFeature.VIEW_COLUMN_LIST, DDLFeature.CREATE_SEQUENCE, DDLFeature.SEQUENCE_OPTIONS,
                DDLFeature.TRUNCATE_TABLE, DDLFeature.TYPE_ARRAY, DDLFeature.TYPE_JSON, DDLFeature.TYPE_UUID,
                DDLFeature.TYPE_TEXT, DDLFeature.TYPE_BOOLEAN, DDLFeature.TYPE_BLOB,
                DDLFeature.TYPE_NUMERIC_PRECISION, DDLFeature.TYPE_TIMESTAMP, DDLFeature.TYPE_TIMESTAMPTZ,
                DDLFeature.TYPE_DATE, DDLFeature.TYPE_TIME, DDLFeature.TYPE_INTERVAL)) {
            assertTrue(dialect.getFeatures().supports(feature), feature + " should be declared as supported");
        }
    }

    @Test
    void featuresVerifiedAsUnsupportedAreNotDeclared() {
        for (DDLFeature feature : List.of(DDLFeature.IDENTITY_COLUMN, DDLFeature.SERIAL, DDLFeature.AUTO_INCREMENT,
                DDLFeature.GENERATED_COLUMN, DDLFeature.GENERATED_STORED, DDLFeature.GENERATED_VIRTUAL,
                DDLFeature.COLLATION, DDLFeature.COLUMN_TYPE_UNSIGNED, DDLFeature.NULLS_DISTINCT,
                DDLFeature.FK_CASCADE, DDLFeature.FK_SET_NULL, DDLFeature.FK_SET_DEFAULT, DDLFeature.DEFERRABLE_FK,
                DDLFeature.INITIALLY_DEFERRED, DDLFeature.MATCH_FULL, DDLFeature.FK_ENFORCED_BY_PRAGMA,
                DDLFeature.ALTER_ADD_CONSTRAINT, DDLFeature.ALTER_DROP_CONSTRAINT, DDLFeature.ALTER_DROP_PRIMARY_KEY,
                DDLFeature.ALTER_ADD_FOREIGN_KEY, DDLFeature.ALTER_DROP_FOREIGN_KEY, DDLFeature.ALTER_ADD_UNIQUE,
                DDLFeature.ALTER_ADD_CHECK, DDLFeature.ALTER_MULTIPLE_ACTIONS,
                DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE, DDLFeature.INDEX_METHOD, DDLFeature.PARTIAL_INDEX,
                DDLFeature.INCLUDE_COLUMNS, DDLFeature.INDEX_DESCENDING, DDLFeature.MATERIALIZED_VIEW,
                DDLFeature.VIEW_CHECK_OPTION, DDLFeature.TRUNCATE_CASCADE, DDLFeature.TYPE_CHAR_LENGTH,
                DDLFeature.TYPE_ENUM, DDLFeature.ENUM_AS_CHECK, DDLFeature.TYPE_DOMAIN)) {
            assertFalse(dialect.getFeatures().supports(feature), feature + " must not be declared as supported");
        }
    }

    @Test
    void dialectIdentityMatchesTheProviderWiring() {
        assertEquals("duckdb", dialect.getName());
        assertEquals("main", dialect.getDefaultSchemaName());
        assertEquals("main", dialect.normalizeIdentifier("MAIN"));
        assertEquals("t_1", dialect.normalizeIdentifier("T_1"));
        assertEquals("abc", dialect.quote("abc"));
        assertTrue(dialect.getSessionSetupStatements().isEmpty());
        assertEquals("INTEGER", dialect.renderType(DataType.integer()));
    }

    /* ------------------------------------------------------------------ rendering */

    @Test
    void createTableWithCommentsAlsoRendersCommentStatements() {
        Table table = new Table("main", "t_1");
        table.setComment("a table");
        table.addColumn(new Column("a", DataType.integer(), false).setComment("a column"));
        table.addColumn(new Column("b", DataType.text(), true));
        List<String> statements = dialect.renderAll(new CreateTable(table));
        assertEquals(3, statements.size());
        assertEquals("CREATE TABLE main.t_1 (a INTEGER NOT NULL, b TEXT)", statements.get(0));
        assertEquals("COMMENT ON TABLE main.t_1 IS 'a table'", statements.get(1));
        assertEquals("COMMENT ON COLUMN main.t_1.a IS 'a column'", statements.get(2));
    }

    @Test
    void commentsAreEscaped() {
        Table table = new Table("main", "t_1");
        table.setComment("it's here");
        table.addColumn(new Column("a", DataType.integer(), true));
        assertEquals("COMMENT ON TABLE main.t_1 IS 'it''s here'", dialect.renderAll(new CreateTable(table)).get(1));
    }

    @Test
    void identityColumnsRenderAsPlainColumns() {
        Table table = new Table("main", "t_1");
        table.addColumn(new Column("a", DataType.integer(), false).setIdentity(true));
        assertEquals("CREATE TABLE main.t_1 (a INTEGER NOT NULL)", dialect.render(new CreateTable(table)));
    }

    @Test
    void generatedColumnsRenderWithoutStoredOrVirtual() {
        Table table = new Table("main", "t_1");
        table.addColumn(new Column("a", DataType.integer(), true));
        table.addColumn(new Column("b", DataType.integer(), true).setGeneratedExpression("a + 1"));
        assertEquals("CREATE TABLE main.t_1 (a INTEGER, b INTEGER GENERATED ALWAYS AS (a + 1))",
                dialect.render(new CreateTable(table)));
    }

    @Test
    void dropIndexIsNeverTableQualified() {
        assertEquals("DROP INDEX idx_1", dialect.render(new DropIndex("idx_1", "t_1")));
        assertEquals("DROP INDEX IF EXISTS idx_1", dialect.render(new DropIndex("idx_1", "t_1", true, false)));
    }

    @Test
    void truncateNeverRendersCascade() {
        assertEquals("TRUNCATE TABLE t_1", dialect.render(new TruncateTable("t_1", false, false)));
        assertEquals("TRUNCATE TABLE t_1", dialect.render(new TruncateTable("t_1", true, true)));
    }

    @Test
    void createIndexUsesTheExpressionFormForExpressionIndexes() {
        Index plain = new Index("idx_plain", List.of("a", "b"));
        assertEquals("CREATE INDEX idx_plain ON t_1 (a, b)", dialect.render(new CreateIndex("t_1", plain)));
        Index expression = new Index("idx_expr");
        expression.getExpressions().add("(a + b)");
        assertEquals("CREATE INDEX idx_expr ON t_1 ((a + b))", dialect.render(new CreateIndex("t_1", expression)));
        Index unique = new Index("idx_u", List.of("a")).setUnique(true);
        assertEquals("CREATE UNIQUE INDEX idx_u ON t_1 (a)", dialect.render(new CreateIndex("t_1", unique)));
    }

    @Test
    void renderLiteralUsesDuckDbSpellings() {
        assertEquals("NULL", dialect.renderLiteral(null, DataType.integer()));
        assertEquals("TRUE", dialect.renderLiteral(Boolean.TRUE, DataType.bool()));
        assertEquals("FALSE", dialect.renderLiteral(Boolean.FALSE, DataType.bool()));
        assertEquals("42", dialect.renderLiteral(42, DataType.integer()));
        assertEquals("'it''s'", dialect.renderLiteral("it's", DataType.text()));
        // X'41' is the three character string "x41" in DuckDB, so the blob escape form is required
        assertEquals("'\\x4142'::BLOB", dialect.renderLiteral(new byte[] { 0x41, 0x42 }, DataType.blob()));
    }

    /* ------------------------------------------------------------------ ALTER TABLE */

    @Test
    void alterActionsUseTheSyntaxDuckDbAccepts() {
        assertEquals("ADD COLUMN b INTEGER",
                dialect.renderAlterAction(new AddColumn(new Column("b", DataType.integer(), true))));
        assertEquals("ADD COLUMN IF NOT EXISTS b INTEGER",
                dialect.renderAlterAction(new AddColumn(new Column("b", DataType.integer(), true), true)));
        assertEquals("DROP COLUMN b",
                dialect.renderAlterAction(new DropColumn("b", new Column("b", DataType.integer(), true))));
        assertEquals("DROP COLUMN IF EXISTS b CASCADE",
                dialect.renderAlterAction(new DropColumn("b", null, true, true)));
        assertEquals("RENAME COLUMN a TO b", dialect.renderAlterAction(new RenameColumn("a", "b")));
        assertEquals("RENAME TO t_2", dialect.renderAlterAction(new RenameTable("t_1", "t_2")));
        assertEquals("ALTER COLUMN a SET DATA TYPE BIGINT",
                dialect.renderAlterAction(new AlterColumnType("a", DataType.bigint(), DataType.integer(), null)));
        assertEquals("ALTER COLUMN a SET DATA TYPE BIGINT USING a::BIGINT", dialect
                .renderAlterAction(new AlterColumnType("a", DataType.bigint(), DataType.integer(), "a::BIGINT")));
        assertEquals("ALTER COLUMN a SET NOT NULL", dialect.renderAlterAction(new SetNotNull("a", true)));
        assertEquals("ALTER COLUMN a DROP NOT NULL", dialect.renderAlterAction(new DropNotNull("a", true)));
        assertEquals("ALTER COLUMN a SET DEFAULT 3", dialect.renderAlterAction(new SetDefault("a", "3", null)));
        assertEquals("ALTER COLUMN a DROP DEFAULT", dialect.renderAlterAction(new DropDefault("a", "3")));
    }

    @Test
    void addPrimaryKeyIsSupportedButDropConstraintIsNot() {
        AlterTableAction addKey = new AddConstraint(
                ConstraintDefinition.primaryKey(new PrimaryKey("pk", List.of("a", "b"))));
        assertEquals("ADD PRIMARY KEY (a, b)", dialect.renderAlterAction(addKey));
        assertThrows(UnsupportedOperationException.class,
                () -> dialect.renderAlterAction(new AddConstraint(
                        ConstraintDefinition.unique(new UniqueConstraint(List.of("a"))))));
        assertThrows(UnsupportedOperationException.class,
                () -> dialect.renderAlterAction(new AddConstraint(
                        ConstraintDefinition.check(new CheckConstraint("a > 0")))));
        assertThrows(UnsupportedOperationException.class,
                () -> dialect.renderAlterAction(new AddConstraint(ConstraintDefinition
                        .foreignKey(new ForeignKey(List.of("a"), "t_1", List.of("a"))))));
        assertThrows(UnsupportedOperationException.class, () -> dialect.renderAlterAction(new DropConstraint("pk")));
        assertThrows(UnsupportedOperationException.class,
                () -> dialect.renderAlterAction(new DropConstraint("pk", ConstraintDefinition.primaryKey(
                        new PrimaryKey("pk", List.of("a"))), true, true)));
    }

    @Test
    void multipleAlterActionsAreExpandedIntoOneStatementEach() {
        AlterTable alter = new AlterTable("t_1");
        alter.addAction(new AddColumn(new Column("b", DataType.integer(), true)));
        alter.addAction(new RenameColumn("a", "c"));
        List<String> statements = dialect.renderAll(alter);
        assertEquals(List.of("ALTER TABLE t_1 ADD COLUMN b INTEGER", "ALTER TABLE t_1 RENAME COLUMN a TO c"),
                statements);
    }

    /**
     * DuckDB's parser rejects {@code ON DELETE CASCADE}, and the dialect must still be able to render it.
     *
     * <p>Refusing to render would hide the defect: the crash oracle exists to record a capability an
     * engine claims but does not implement, and it can only do that if the statement reaches the engine.
     * What may be asked for is decided in one place — the capability gate in {@code SchemaAdapter},
     * which consults the declared feature set. So the contract this test pins has two halves: the
     * feature is <em>not</em> declared (nothing emits it by default), and the renderer produces the SQL
     * anyway (so a forced {@code --ddl-features FK_CASCADE} can put the question to the engine).</p>
     */
    @Test
    void referentialActionsDuckDbRejectsAreStillRendered() {
        assertFalse(dialect.getFeatures().supports(DDLFeature.FK_CASCADE),
                "DuckDB cannot honour CASCADE, so the dialect must not declare it");
        assertFalse(dialect.getFeatures().supports(DDLFeature.FK_SET_NULL));
        assertFalse(dialect.getFeatures().supports(DDLFeature.FK_SET_DEFAULT));

        for (ReferentialAction action : List.of(ReferentialAction.CASCADE, ReferentialAction.SET_NULL,
                ReferentialAction.SET_DEFAULT)) {
            Table table = new Table("main", "t_1");
            table.addColumn(new Column("a", DataType.integer(), true));
            table.addForeignKey(new ForeignKey(List.of("a"), "t_1", List.of("a")).setOnDelete(action));
            String sql = dialect.render(new CreateTable(table));
            assertTrue(sql.contains("ON DELETE " + action.toSql()),
                    "the renderer must produce the statement so the engine can answer: " + sql);
        }
        Table restricted = new Table("main", "t_1");
        restricted.addColumn(new Column("a", DataType.integer(), true));
        restricted.addForeignKey(new ForeignKey(List.of("a"), "t_1", List.of("a"))
                .setOnDelete(ReferentialAction.RESTRICT).setOnUpdate(ReferentialAction.RESTRICT));
        assertEquals("CREATE TABLE main.t_1 (a INTEGER, FOREIGN KEY (a) REFERENCES t_1 (a) "
                + "ON DELETE RESTRICT ON UPDATE RESTRICT)", dialect.render(new CreateTable(restricted)));
    }

    @Test
    void deferrableForeignKeysAreRenderedWithoutTheDeferrableKeyword() {
        Table table = new Table("main", "t_1");
        table.addColumn(new Column("a", DataType.integer(), true));
        table.addForeignKey(new ForeignKey(List.of("a"), "t_1", List.of("a")).setDeferrable(true)
                .setInitiallyDeferred(true));
        assertEquals("CREATE TABLE main.t_1 (a INTEGER, FOREIGN KEY (a) REFERENCES t_1 (a))",
                dialect.render(new CreateTable(table)));
    }

    /* ------------------------------------------------------------------ introspection */

    @Test
    void viewQueryIsCutOutOfTheStoredCreateViewText() {
        assertEquals("SELECT a FROM t", DuckDbIntrospector.extractViewQuery("CREATE VIEW main.v AS SELECT a FROM t;"));
        assertEquals("SELECT a FROM t",
                DuckDbIntrospector.extractViewQuery("CREATE OR REPLACE VIEW \"main\".\"v\" AS SELECT a FROM t;"));
        assertEquals("SELECT 'as' FROM t",
                DuckDbIntrospector.extractViewQuery("CREATE VIEW v AS SELECT 'as' FROM t"));
        assertNull(DuckDbIntrospector.extractViewQuery(null));
    }

    @Test
    void listColumnsAreSplitOnTheControlCharacterSeparator() {
        assertEquals(List.of("a", "b"), DuckDbIntrospector.splitList("a\u0001b"));
        assertEquals(List.of(), DuckDbIntrospector.splitList(null));
        assertEquals(List.of(), DuckDbIntrospector.splitList(""));
        assertEquals(List.of("a"), DuckDbIntrospector.splitList("a"));
        // the fallback shapes the driver may produce instead of text
        assertEquals(List.of("a", "b"), DuckDbIntrospector.toStringList(new String[] { "a", "b" }));
        assertEquals(List.of("a", "b"), DuckDbIntrospector.toStringList("[a, b]"));
        assertEquals(List.of("((a + b))"), DuckDbIntrospector.toStringList("['((a + b))']"));
    }

    @Test
    void introspectorDeclaresOnlyTheCapabilitiesDuckDbExposes() {
        assertTrue(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.COLUMNS));
        assertTrue(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.PRIMARY_KEYS));
        assertTrue(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.UNIQUE_CONSTRAINTS));
        assertTrue(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.CHECK_CONSTRAINTS));
        assertTrue(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.EXPRESSION_INDEXES));
        assertTrue(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.COMMENTS));
        assertTrue(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.VIEW_DEFINITIONS));
        assertTrue(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.SEQUENCES));
        // identity and generated columns are not exposed by the DuckDB catalog
        assertFalse(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.IDENTITY));
        assertFalse(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.GENERATED_COLUMNS));
        // only NO ACTION and RESTRICT are accepted, and both are reported as NO ACTION
        assertFalse(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.FOREIGN_KEY_ACTIONS));
        assertFalse(introspector.canIntrospect(DuckDbIntrospector.IntrospectionCapability.PARTIAL_INDEXES));
    }

    /* ------------------------------------------------------------------ end to end */

    @Test
    void schemaRoundTripsThroughAnInMemoryDuckDb() throws Exception {
        RelationalSchema intended = new RelationalSchema("main");

        Table dept = new Table("main", "dept");
        dept.addColumn(new Column("d_id", DataType.integer(), false));
        dept.addColumn(new Column("d_code", DataType.integer(), false));
        dept.addColumn(new Column("d_label", DataType.text(), false));
        dept.addColumn(new Column("d_budget", DataType.decimal(10, 2), true));
        dept.setPrimaryKey(new PrimaryKey(List.of("d_id", "d_code")));
        dept.addUniqueConstraint(new UniqueConstraint(List.of("d_label")));
        dept.addCheckConstraint(new CheckConstraint("d_budget > 0"));
        intended.addTable(dept);

        Table emp = new Table("main", "emp");
        emp.addColumn(new Column("e_id", DataType.integer(), false));
        emp.addColumn(new Column("d_id", DataType.integer(), false));
        emp.addColumn(new Column("d_code", DataType.integer(), false));
        emp.addColumn(new Column("mgr_id", DataType.integer(), true));
        emp.addColumn(new Column("e_note", DataType.text(), true).setDefaultExpression("'none'"));
        emp.setPrimaryKey(new PrimaryKey(List.of("e_id")));
        emp.addForeignKey(new ForeignKey(List.of("d_id", "d_code"), "dept", List.of("d_id", "d_code")));
        emp.addForeignKey(new ForeignKey(List.of("mgr_id"), "emp", List.of("e_id")).setSelfReferential(true));
        emp.addIndex(new Index("idx_emp_note", List.of("e_note")));
        Index expressionIndex = new Index("idx_emp_cost");
        expressionIndex.getExpressions().add("(e_id + 1)");
        emp.addIndex(expressionIndex);
        intended.addTable(emp);

        intended.addView(new View("v_emp", "SELECT e_id, e_note FROM emp").setSchemaName("main"));
        intended.addSequence(new Sequence("seq_emp").setSchemaName("main"));

        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            DDLsmithDBConnection db = new DDLsmithDBConnection(connection, "junit");
            for (DDLStatement statement : statementsOf(intended)) {
                for (String sql : dialect.renderAll(statement)) {
                    db.executeStatement(sql);
                }
            }
            RelationalSchema actual = introspector.introspect(db, "main");
            assertEquals(intended.getTables().size(), actual.getTables().size());
            List<org.ddlsmith.introspection.SchemaDifference> differences = SchemaNormalizer
                    .diff(SchemaNormalizer.erase(intended, introspector.getCapabilities()), actual);
            assertEquals(List.of(), differences, () -> SchemaNormalizer.render(differences));
            assertEquals(actual.getTables().size(), introspector.introspect(db, null).getTables().size(),
                    "introspect(null) must default to the main schema");
        }
    }

    @Test
    void alterRoundTripRestoresTheIntrospectedSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            DDLsmithDBConnection db = new DDLsmithDBConnection(connection, "junit");
            db.executeStatement("CREATE SCHEMA alter_rt");
            Table table = new Table("alter_rt", "t_alter");
            table.addColumn(new Column("id", DataType.integer(), false));
            table.addColumn(new Column("num", DataType.integer(), true));
            table.addColumn(new Column("txt", DataType.text(), false));
            for (String sql : dialect.renderAll(new CreateTable(table))) {
                db.executeStatement(sql);
            }
            RelationalSchema before = introspector.introspect(db, "alter_rt");

            List<AlterTableAction> actions = List.of(
                    new AddColumn(new Column("extra", DataType.integer(), true)),
                    new RenameColumn("txt", "txt2"),
                    new AlterColumnType("num", DataType.bigint(), DataType.integer(), null),
                    new SetNotNull("num", true),
                    new DropNotNull("txt", true),
                    new SetDefault("num", "3", null));
            for (AlterTableAction action : actions) {
                db.executeStatement(dialect.render(new AlterTable("alter_rt.t_alter", action)));
                AlterTableAction inverse = action.inverse("alter_rt.t_alter");
                assertNotNull(inverse, action + " must be invertible");
                db.executeStatement(dialect.render(new AlterTable("alter_rt.t_alter", inverse)));
                RelationalSchema after = introspector.introspect(db, "alter_rt");
                List<org.ddlsmith.introspection.SchemaDifference> differences = SchemaNormalizer.diff(before, after);
                assertEquals(List.of(), differences,
                        () -> action + " -> " + inverse + " did not restore the schema: "
                                + SchemaNormalizer.render(differences));
            }
        }
    }

    private static List<DDLStatement> statementsOf(RelationalSchema schema) {
        List<DDLStatement> statements = new ArrayList<>();
        for (Table table : schema.getTables()) {
            statements.add(new CreateTable(table));
        }
        for (Table table : schema.getTables()) {
            for (Index index : table.getIndexes()) {
                statements.add(new CreateIndex(table.getName(), index));
            }
        }
        for (Sequence sequence : schema.getSequences()) {
            statements.add(new CreateSequence(sequence));
        }
        for (View view : schema.getViews()) {
            statements.add(new CreateView(view));
        }
        return statements;
    }
}
