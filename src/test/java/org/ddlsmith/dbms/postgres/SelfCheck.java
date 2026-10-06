package org.ddlsmith.dbms.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.ddl.alter.DropColumn;
import org.ddlsmith.ddl.alter.DropConstraint;
import org.ddlsmith.ddl.alter.DropDefault;
import org.ddlsmith.ddl.alter.DropNotNull;
import org.ddlsmith.ddl.alter.RenameColumn;
import org.ddlsmith.ddl.alter.RenameTable;
import org.ddlsmith.ddl.alter.SetDefault;
import org.ddlsmith.ddl.alter.SetNotNull;
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaNormalizer;

/**
 * Standalone verification of the PostgreSQL module.
 *
 * <p>It runs three things that need no database server:</p>
 * <ol>
 *   <li>the type round trip over every type {@link PostgresTypeMapper} supports, plus the catalog
 *       spellings it has to tolerate;</li>
 *   <li>a rendering matrix that builds a schema covering every construct the dialect claims to
 *       support, renders every statement and asserts the exact SQL text of the tricky cases;</li>
 *   <li>the introspection logic, driven by synthetic {@code pg_catalog} rows (the same shape the
 *       real queries return), because no PostgreSQL server is available here.</li>
 * </ol>
 * <p>Finally it attempts a live connection. When no server answers it prints
 * {@code SKIPPED (no server at <url>)} and exits 0; when a server answers it executes the DDL,
 * introspects the result and requires {@link SchemaNormalizer#diff} to be empty.</p>
 *
 * <p>Run with (see the module report for the exact classpath):</p>
 * <pre>
 * java -cp "target\pg-classes;target\contract-classes;&lt;deps&gt;" org.ddlsmith.dbms.postgres.SelfCheck
 * </pre>
 */
public final class SelfCheck {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/ddlsmith";
    private static final String DEFAULT_USER = "postgres";
    private static final String DEFAULT_PASSWORD = "postgres";
    private static final String LIVE_SCHEMA = "ddlsmith_selfcheck";

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private SelfCheck() {
    }

    public static void main(String[] args) {
        System.out.println("=== DDLSmith PostgreSQL self-check ===");
        PostgresDialect dialect = new PostgresDialect();
        PostgresTypeMapper typeMapper = new PostgresTypeMapper();
        PostgresIntrospector introspector = new PostgresIntrospector();

        checkTypeRoundTrip(typeMapper);
        checkTypeParsing(typeMapper);
        checkRenderingMatrix(dialect);
        checkLiteralRendering(dialect);
        checkDialectProperties(dialect);
        checkIntrospection(typeMapper, introspector);
        checkCapabilities(dialect, introspector);
        checkLiveRoundTrip(dialect, introspector);

        System.out.println();
        System.out.println(checks + " assertions, " + FAILURES.size() + " failure(s)");
        if (FAILURES.isEmpty()) {
            System.out.println("POSTGRES SELF-CHECK OK");
            return;
        }
        for (String failure : FAILURES) {
            System.out.println("FAIL: " + failure);
        }
        System.exit(1);
    }

    /* ------------------------------------------------------------------ assertions */

    private static void assertSql(String label, String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) {
            FAILURES.add(label + "\n      expected: " + expected + "\n      actual:   " + actual);
        }
    }

    private static void assertTrue(String label, boolean condition) {
        checks++;
        if (!condition) {
            FAILURES.add(label);
        }
    }

    private static void assertEquals(String label, Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            FAILURES.add(label + "\n      expected: " + expected + "\n      actual:   " + actual);
        }
    }

    private static void show(String label, String sql) {
        System.out.println("  " + label + ": " + sql);
    }

    /* ------------------------------------------------------------------ 1. type round trip */

    private static void checkTypeRoundTrip(PostgresTypeMapper mapper) {
        System.out.println();
        System.out.println("-- type round trip (render -> parse) --");
        List<DataType> types = new ArrayList<>(List.of(
                DataType.smallint(), DataType.integer(), DataType.bigint(), DataType.decimal(10, 2),
                DataType.real(), DataType.doublePrecision(), DataType.charType(5), DataType.varchar(20),
                DataType.text(), DataType.bool(), DataType.date(), DataType.time(), DataType.timestamp(),
                DataType.timestampWithTimeZone(), DataType.blob(), DataType.json(), DataType.uuid(),
                DataType.interval(), DataType.arrayOf(DataType.integer()),
                DataType.arrayOf(DataType.varchar(20)), DataType.arrayOf(DataType.decimal(8, 3)),
                DataType.of(DataType.Category.DECIMAL), DataType.of(DataType.Category.VARCHAR)));
        for (DataType type : types) {
            String rendered = mapper.render(type);
            DataType parsed = mapper.parse(rendered);
            System.out.println("  " + type.getName() + " -> " + rendered + " -> "
                    + (parsed == null ? "null" : parsed.getName()));
            assertEquals("round trip of " + type.getName() + " (rendered as " + rendered + ")", type, parsed);
        }
        // ENUM is the one documented lossy mapping: PostgreSQL enums need a CREATE TYPE
        assertEquals("ENUM renders as TEXT (documented loss)", "TEXT",
                mapper.render(DataType.enumOf(List.of("a", "b"))));
        assertEquals("ENUM parses back as TEXT (documented loss)", DataType.text(),
                mapper.parse(mapper.render(DataType.enumOf(List.of("a", "b")))));
    }

    private static void checkTypeParsing(PostgresTypeMapper mapper) {
        System.out.println();
        System.out.println("-- pg_catalog spellings --");
        Object[][] cases = {
                { "smallint", DataType.smallint() },
                { "int2", DataType.smallint() },
                { "integer", DataType.integer() },
                { "int4", DataType.integer() },
                { "bigint", DataType.bigint() },
                { "int8", DataType.bigint() },
                { "numeric(10,2)", DataType.decimal(10, 2) },
                { "numeric(10)", DataType.decimal(10, 0) },
                { "numeric", DataType.of(DataType.Category.DECIMAL) },
                { "real", DataType.real() },
                { "float4", DataType.real() },
                { "double precision", DataType.doublePrecision() },
                { "float8", DataType.doublePrecision() },
                { "float(24)", DataType.real() },
                { "float(53)", DataType.doublePrecision() },
                { "character(5)", DataType.charType(5) },
                { "bpchar", DataType.charType(1) },
                { "character varying(20)", DataType.varchar(20) },
                { "varchar(20)", DataType.varchar(20) },
                { "character varying", DataType.of(DataType.Category.VARCHAR) },
                { "text", DataType.text() },
                { "boolean", DataType.bool() },
                { "bool", DataType.bool() },
                { "date", DataType.date() },
                { "time without time zone", DataType.time() },
                { "time(3) without time zone", DataType.time() },
                { "timestamp without time zone", DataType.timestamp() },
                { "timestamp(6) without time zone", DataType.timestamp() },
                { "timestamp with time zone", DataType.timestampWithTimeZone() },
                { "timestamp(3) with time zone", DataType.timestampWithTimeZone() },
                { "timestamptz", DataType.timestampWithTimeZone() },
                { "bytea", DataType.blob() },
                { "jsonb", DataType.json() },
                { "uuid", DataType.uuid() },
                { "interval", DataType.interval() },
                { "integer[]", DataType.arrayOf(DataType.integer()) },
                { "character varying(20)[]", DataType.arrayOf(DataType.varchar(20)) },
                { "numeric(10,2)[]", DataType.arrayOf(DataType.decimal(10, 2)) },
                { "ARRAY", null },
                { "USER-DEFINED", null },
                { "name", null },
                { "oid", null },
                { "regclass", null },
                { "timetz", null },
                { "time with time zone", null },
                { "bit(3)", null },
                { "tsvector", null },
                { "inet", null },
                { "money", null },
                { "xml", null },
                { "mood", null },
                { null, null },
                { "", null } };
        for (Object[] testCase : cases) {
            String spelling = (String) testCase[0];
            DataType expected = (DataType) testCase[1];
            DataType actual = mapper.parse(spelling);
            System.out.println("  parse(\"" + spelling + "\") -> " + (actual == null ? "null" : actual.getName()));
            assertEquals("parse(\"" + spelling + "\")", expected, actual);
        }
    }

    /* ------------------------------------------------------------------ 2. rendering matrix */

    private static RelationalSchema buildMatrixSchema() {
        RelationalSchema schema = new RelationalSchema("matrix");

        Table parent = new Table("public", "parent");
        parent.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        parent.addColumn(new Column("code", DataType.varchar(10)).setNullable(false).setDefaultExpression("'x'"));
        parent.addColumn(new Column("amount", DataType.decimal(10, 2)).setCheckExpression("amount > 0"));
        parent.addColumn(new Column("active", DataType.bool()).setDefaultExpression("TRUE"));
        parent.addColumn(new Column("tags", DataType.arrayOf(DataType.text())));
        parent.addColumn(new Column("payload", DataType.json()));
        parent.addColumn(new Column("doc", DataType.uuid()));
        parent.addColumn(new Column("span", DataType.interval()));
        parent.addColumn(new Column("created", DataType.timestamp()).setDefaultExpression("CURRENT_TIMESTAMP"));
        parent.addColumn(new Column("updated", DataType.timestampWithTimeZone()));
        parent.addColumn(new Column("raw", DataType.blob()));
        parent.addColumn(new Column("ratio", DataType.doublePrecision()));
        parent.addColumn(new Column("small", DataType.smallint()));
        parent.addColumn(new Column("big", DataType.bigint()));
        parent.addColumn(new Column("single", DataType.real()));
        parent.addColumn(new Column("fixed", DataType.charType(3)));
        parent.addColumn(new Column("moment", DataType.time()));
        parent.addColumn(new Column("day", DataType.date()));
        parent.addColumn(new Column("note", DataType.text()).setCollation("C"));
        parent.addColumn(new Column("summary", DataType.text()).setUnique(true));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        parent.addUniqueConstraint(new UniqueConstraint("uq_parent_code", List.of("code")));
        parent.addCheckConstraint(new CheckConstraint("ck_parent_amount", "amount > 0"));
        parent.setComment("parent table");
        parent.getColumn("id").setComment("the identifier");
        parent.addIndex(new Index("idx_parent_code", List.of("code")));
        schema.addTable(parent);

        Table child = new Table("public", "child");
        child.addColumn(new Column("pid", DataType.integer()).setNullable(false));
        child.addColumn(new Column("seq", DataType.smallint()).setNullable(false));
        child.addColumn(new Column("label", DataType.text()));
        child.addColumn(new Column("total", DataType.decimal(12, 4)).setGeneratedExpression("pid * 2")
                .setGeneratedStored(true));
        child.setPrimaryKey(new PrimaryKey(List.of("pid", "seq")));
        child.addForeignKey(new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_child_parent")
                .setOnDelete(ReferentialAction.CASCADE).setOnUpdate(ReferentialAction.SET_NULL));
        child.addForeignKey(new ForeignKey(List.of("pid", "seq"), "parent", List.of("id", "code"))
                .setName("fk_child_composite").setDeferrable(true).setInitiallyDeferred(true));
        child.addIndex(new Index("idx_child_label", List.of("label")));
        schema.addTable(child);

        Table node = new Table("public", "node");
        node.addColumn(new Column("id", DataType.integer()).setNullable(false));
        node.addColumn(new Column("parent_id", DataType.integer()));
        node.setPrimaryKey(new PrimaryKey(List.of("id")));
        node.addForeignKey(new ForeignKey(List.of("parent_id"), "node", List.of("id")).setName("fk_node_parent")
                .setSelfReferential(true).setOnDelete(ReferentialAction.SET_DEFAULT)
                .setOnUpdate(ReferentialAction.RESTRICT));
        schema.addTable(node);

        Table temporary = new Table("tmp_stage");
        temporary.addColumn(new Column("x", DataType.integer()));
        temporary.setTemporary(true);
        schema.addTable(temporary);

        View view = new View("v_parent", "SELECT id, code FROM parent");
        view.getColumnNames().addAll(List.of("id", "code"));
        view.setCheckOption("CASCADED");
        schema.addView(view);

        View materialized = new View("mv_parent", "SELECT count(*) AS n FROM parent");
        materialized.setMaterialized(true);
        schema.addView(materialized);

        schema.addSequence(new Sequence("seq_parent").setStart(5).setIncrement(2).setMinValue(1L).setMaxValue(100L)
                .setCycle(true));
        return schema;
    }

    private static void checkRenderingMatrix(PostgresDialect dialect) {
        System.out.println();
        System.out.println("-- rendering matrix --");
        RelationalSchema schema = buildMatrixSchema();

        List<String> parentSql = dialect.renderAll(new CreateTable(schema.getTable("parent")));
        show("CREATE TABLE parent", parentSql.get(0));
        assertSql("CREATE TABLE parent",
                "CREATE TABLE public.parent ("
                        + "id INTEGER GENERATED BY DEFAULT AS IDENTITY NOT NULL, "
                        + "code CHARACTER VARYING(10) NOT NULL DEFAULT 'x', "
                        + "amount NUMERIC(10,2) CHECK (amount > 0), "
                        + "active BOOLEAN DEFAULT TRUE, "
                        + "tags TEXT[], payload JSONB, doc UUID, span INTERVAL, "
                        + "created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated TIMESTAMP WITH TIME ZONE, "
                        + "raw BYTEA, ratio DOUBLE PRECISION, small SMALLINT, big BIGINT, single REAL, "
                        + "fixed CHARACTER(3), moment TIME, day DATE, note TEXT COLLATE C, summary TEXT UNIQUE, "
                        + "CONSTRAINT pk_parent PRIMARY KEY (id), "
                        + "CONSTRAINT uq_parent_code UNIQUE (code), "
                        + "CONSTRAINT ck_parent_amount CHECK (amount > 0))",
                parentSql.get(0));
        assertEquals("renderAll(CreateTable) yields CREATE TABLE + 2 comments", 3, parentSql.size());
        assertSql("COMMENT ON TABLE", "COMMENT ON TABLE public.parent IS 'parent table'", parentSql.get(1));
        assertSql("COMMENT ON COLUMN", "COMMENT ON COLUMN public.parent.id IS 'the identifier'", parentSql.get(2));

        assertSql("CREATE TABLE IF NOT EXISTS",
                "CREATE TABLE IF NOT EXISTS public.parent (id INTEGER GENERATED BY DEFAULT AS IDENTITY NOT NULL)",
                dialect.render(new CreateTable(singleColumnTable("public", "parent"), true)));

        show("CREATE TABLE child", dialect.render(new CreateTable(schema.getTable("child"))));
        assertSql("composite primary key and foreign keys",
                "CREATE TABLE public.child (pid INTEGER NOT NULL, seq SMALLINT NOT NULL, label TEXT, "
                        + "total NUMERIC(12,4) GENERATED ALWAYS AS (pid * 2) STORED, "
                        + "PRIMARY KEY (pid, seq), "
                        + "CONSTRAINT fk_child_parent FOREIGN KEY (pid) REFERENCES parent (id) "
                        + "ON DELETE CASCADE ON UPDATE SET NULL, "
                        + "CONSTRAINT fk_child_composite FOREIGN KEY (pid, seq) REFERENCES parent (id, code) "
                        + "DEFERRABLE INITIALLY DEFERRED)",
                dialect.render(new CreateTable(schema.getTable("child"))));

        show("CREATE TABLE node", dialect.render(new CreateTable(schema.getTable("node"))));
        assertSql("self-referential foreign key",
                "CREATE TABLE public.node (id INTEGER NOT NULL, parent_id INTEGER, PRIMARY KEY (id), "
                        + "CONSTRAINT fk_node_parent FOREIGN KEY (parent_id) REFERENCES node (id) "
                        + "ON DELETE SET DEFAULT ON UPDATE RESTRICT)",
                dialect.render(new CreateTable(schema.getTable("node"))));

        assertSql("TEMPORARY TABLE", "CREATE TEMPORARY TABLE tmp_stage (x INTEGER)",
                dialect.render(new CreateTable(schema.getTable("tmp_stage"))));
        assertSql("DROP TABLE IF EXISTS ... CASCADE", "DROP TABLE IF EXISTS t CASCADE",
                dialect.render(new DropTable("t", true, true)));
        assertSql("TRUNCATE TABLE ... CASCADE", "TRUNCATE TABLE t CASCADE",
                dialect.render(new TruncateTable("t", true, false)));
        assertSql("CREATE SCHEMA IF NOT EXISTS", "CREATE SCHEMA IF NOT EXISTS s",
                dialect.render(new CreateSchema("s", true)));
        assertSql("DROP SCHEMA IF EXISTS ... CASCADE", "DROP SCHEMA IF EXISTS s CASCADE",
                dialect.render(new DropSchema("s", true, true)));

        /* ALTER TABLE */
        assertSql("ALTER ADD COLUMN IF NOT EXISTS",
                "ALTER TABLE t ADD COLUMN IF NOT EXISTS c INTEGER",
                dialect.render(new AlterTable("t", new AddColumn(new Column("c", DataType.integer()), true))));
        assertSql("ALTER ADD COLUMN with identity",
                "ALTER TABLE t ADD COLUMN c INTEGER GENERATED BY DEFAULT AS IDENTITY NOT NULL",
                dialect.render(new AlterTable("t",
                        new AddColumn(new Column("c", DataType.integer()).setIdentity(true).setNullable(false)))));
        assertSql("ALTER DROP COLUMN IF EXISTS ... CASCADE",
                "ALTER TABLE t DROP COLUMN IF EXISTS c CASCADE",
                dialect.render(new AlterTable("t", new DropColumn("c", null, true, true))));
        assertSql("ALTER RENAME COLUMN", "ALTER TABLE t RENAME COLUMN a TO b",
                dialect.render(new AlterTable("t", new RenameColumn("a", "b"))));
        assertSql("ALTER RENAME TO", "ALTER TABLE t RENAME TO t2",
                dialect.render(new AlterTable("t", new RenameTable("t", "t2"))));
        assertSql("ALTER COLUMN TYPE ... USING",
                "ALTER TABLE t ALTER COLUMN c TYPE BIGINT USING c::bigint",
                dialect.render(new AlterTable("t",
                        new AlterColumnType("c", DataType.bigint(), DataType.integer(), "c::bigint"))));
        assertSql("ALTER COLUMN TYPE without USING",
                "ALTER TABLE t ALTER COLUMN c TYPE CHARACTER VARYING(20)",
                dialect.render(new AlterTable("t", new AlterColumnType("c", DataType.varchar(20), null, null))));
        assertSql("ALTER SET NOT NULL", "ALTER TABLE t ALTER COLUMN c SET NOT NULL",
                dialect.render(new AlterTable("t", new SetNotNull("c"))));
        assertSql("ALTER DROP NOT NULL", "ALTER TABLE t ALTER COLUMN c DROP NOT NULL",
                dialect.render(new AlterTable("t", new DropNotNull("c"))));
        assertSql("ALTER SET DEFAULT", "ALTER TABLE t ALTER COLUMN c SET DEFAULT 5",
                dialect.render(new AlterTable("t", new SetDefault("c", "5"))));
        assertSql("ALTER DROP DEFAULT", "ALTER TABLE t ALTER COLUMN c DROP DEFAULT",
                dialect.render(new AlterTable("t", new DropDefault("c"))));
        assertSql("ALTER ADD CONSTRAINT",
                "ALTER TABLE t ADD CONSTRAINT fk_x FOREIGN KEY (pid) REFERENCES parent (id)",
                dialect.render(new AlterTable("t", new AddConstraint(ConstraintDefinition.foreignKey(
                        new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_x"))))));
        assertSql("ALTER ADD PRIMARY KEY",
                "ALTER TABLE t ADD CONSTRAINT pk_x PRIMARY KEY (a, b)",
                dialect.render(new AlterTable("t", new AddConstraint(
                        ConstraintDefinition.primaryKey(new PrimaryKey("pk_x", List.of("a", "b")))))));
        assertSql("ALTER DROP CONSTRAINT IF EXISTS ... CASCADE",
                "ALTER TABLE t DROP CONSTRAINT IF EXISTS ck CASCADE",
                dialect.render(new AlterTable("t", new DropConstraint("ck", null, true, true))));
        assertSql("ALTER with several actions",
                "ALTER TABLE t ADD COLUMN c INTEGER, DROP COLUMN d, RENAME COLUMN e TO f",
                dialect.render(new AlterTable("t", List.of(new AddColumn(new Column("c", DataType.integer())),
                        new DropColumn("d", null, false, false), new RenameColumn("e", "f")))));
        assertEquals("renderAll(AlterTable) keeps one statement when ALTER_MULTIPLE_ACTIONS is supported", 1,
                dialect.renderAll(new AlterTable("t", List.of(new AddColumn(new Column("c", DataType.integer())),
                        new DropColumn("d", null, false, false)))).size());

        /* indexes */
        assertSql("CREATE INDEX", "CREATE INDEX idx_a ON t (a, b)",
                dialect.render(new CreateIndex("t", new Index("idx_a", List.of("a", "b")))));
        assertSql("CREATE UNIQUE INDEX IF NOT EXISTS", "CREATE UNIQUE INDEX IF NOT EXISTS idx_u ON t (a)",
                dialect.render(new CreateIndex("t", new Index("idx_u", List.of("a")).setUnique(true), true)));
        assertSql("partial index", "CREATE INDEX idx_p ON t (a) WHERE a > 0",
                dialect.render(new CreateIndex("t", new Index("idx_p", List.of("a")).setPredicate("a > 0"))));
        Index expressionIndex = new Index("idx_e");
        expressionIndex.getExpressions().add("lower(b)");
        assertSql("expression index", "CREATE INDEX idx_e ON t (lower(b))",
                dialect.render(new CreateIndex("t", expressionIndex)));
        Index includeIndex = new Index("idx_i", List.of("a"));
        includeIndex.getIncludeColumns().add("b");
        assertSql("index INCLUDE columns", "CREATE INDEX idx_i ON t (a) INCLUDE (b)",
                dialect.render(new CreateIndex("t", includeIndex)));
        assertSql("index method", "CREATE INDEX idx_m ON t USING gin (a)",
                dialect.render(new CreateIndex("t", new Index("idx_m", List.of("a")).setMethod("gin"))));
        assertSql("DROP INDEX is not table qualified", "DROP INDEX idx_a",
                dialect.render(new DropIndex("idx_a", "t", false, false)));
        assertSql("DROP INDEX IF EXISTS", "DROP INDEX IF EXISTS idx_a",
                dialect.render(new DropIndex("idx_a", "t", true, false)));
        assertSql("DROP INDEX CASCADE", "DROP INDEX idx_a CASCADE",
                dialect.render(new DropIndex("idx_a", "t", false, true)));

        /* views */
        assertSql("CREATE VIEW with column list and check option",
                "CREATE OR REPLACE VIEW v_parent (id, code) AS SELECT id, code FROM parent WITH CASCADED CHECK OPTION",
                dialect.render(new CreateView(schema.getView("v_parent"), true, false)));
        assertSql("CREATE MATERIALIZED VIEW drops OR REPLACE",
                "CREATE MATERIALIZED VIEW mv_parent AS SELECT count(*) AS n FROM parent",
                dialect.render(new CreateView(schema.getView("mv_parent"), true, false)));
        assertSql("DROP VIEW IF EXISTS ... CASCADE", "DROP VIEW IF EXISTS v1 CASCADE",
                dialect.render(new DropView("v1", true, true)));

        /* sequences */
        assertSql("CREATE SEQUENCE with options",
                "CREATE SEQUENCE IF NOT EXISTS seq_parent START WITH 5 INCREMENT BY 2 MINVALUE 1 MAXVALUE 100 CYCLE",
                dialect.render(new CreateSequence(schema.getSequence("seq_parent"), true)));
        assertSql("DROP SEQUENCE IF EXISTS ... CASCADE", "DROP SEQUENCE IF EXISTS seq_parent CASCADE",
                dialect.render(new DropSequence("seq_parent", true, true)));
    }

    private static Table singleColumnTable(String schemaName, String name) {
        Table table = new Table(schemaName, name);
        table.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        return table;
    }

    private static void checkLiteralRendering(PostgresDialect dialect) {
        System.out.println();
        System.out.println("-- literals --");
        assertSql("TRUE", "TRUE", dialect.renderLiteral(Boolean.TRUE, DataType.bool()));
        assertSql("FALSE", "FALSE", dialect.renderLiteral(Boolean.FALSE, DataType.bool()));
        assertSql("number", "42", dialect.renderLiteral(42, DataType.integer()));
        assertSql("string", "'O''Brien'", dialect.renderLiteral("O'Brien", DataType.text()));
        assertSql("NULL", "NULL", dialect.renderLiteral(null, DataType.text()));
        assertSql("bytea uses the hex escape format, not X'..'", "'\\x01ab'::bytea",
                dialect.renderLiteral(new byte[] { 0x01, (byte) 0xAB }, DataType.blob()));
        assertSql("array literal", "ARRAY[1, 2]",
                dialect.renderLiteral(List.of(1, 2), DataType.arrayOf(DataType.integer())));
    }

    private static void checkDialectProperties(PostgresDialect dialect) {
        System.out.println();
        System.out.println("-- dialect properties --");
        assertEquals("getName", "postgres", dialect.getName());
        assertEquals("getDefaultSchemaName", "public", dialect.getDefaultSchemaName());
        assertEquals("normalizeIdentifier folds to lower case", "mytable", dialect.normalizeIdentifier("MyTable"));
        assertEquals("quote is the identity (PostgreSQL folds unquoted identifiers)", "MyTable",
                dialect.quote("MyTable"));
        assertEquals("no session setup statements", 0, dialect.getSessionSetupStatements().size());
        assertEquals("feature count", 101, dialect.getFeatures().getFeatures().size());
        assertTrue("FK_TARGET_MUST_EXIST is declared (a live run failed on 'relation \"t_1\" does not exist')",
                dialect.getFeatures().supports(DDLFeature.FK_TARGET_MUST_EXIST));
        checkFeatureCoverage(dialect);
    }

    /**
     * The {@link DDLFeature} constants PostgreSQL deliberately does not declare.
     *
     * <p>Every other constant must be present. Checking the whole enum (rather than a hand written
     * list of interesting flags) means a constant added to {@code DDLFeature} later cannot silently
     * be left out: a missing flag removes a construct from every generated schema, while a wrongly
     * present flag produces a false engine finding.</p>
     */
    private static final Set<DDLFeature> NOT_DECLARED = EnumSet.of(
            // the DDL AST has no inline (column level) foreign key representation
            DDLFeature.INLINE_FOREIGN_KEY,
            // SQLite specific
            DDLFeature.FK_ENFORCED_BY_PRAGMA,
            // MySQL's spelling; PostgreSQL uses GENERATED BY DEFAULT AS IDENTITY
            DDLFeature.AUTO_INCREMENT,
            // PostgreSQL only has STORED generated columns
            DDLFeature.GENERATED_VIRTUAL,
            // no unsigned integer types
            DDLFeature.COLUMN_TYPE_UNSIGNED,
            // index names are unique per schema, so DROP INDEX is not table qualified
            DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE,
            // a PostgreSQL enum needs CREATE TYPE ..., which the DDL AST cannot express
            DDLFeature.TYPE_ENUM,
            // enums are modelled as a type (rendered as TEXT), not as a CHECK constraint
            DDLFeature.ENUM_AS_CHECK);

    private static void checkFeatureCoverage(PostgresDialect dialect) {
        for (DDLFeature feature : DDLFeature.values()) {
            boolean expected = !NOT_DECLARED.contains(feature);
            assertEquals("feature " + feature + (expected ? " must be declared" : " must not be declared"), expected,
                    dialect.getFeatures().supports(feature));
        }
    }

    /* ------------------------------------------------------------------ 3. introspection */

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static void checkIntrospection(PostgresTypeMapper typeMapper, PostgresIntrospector introspector) {
        System.out.println();
        System.out.println("-- introspection from synthetic pg_catalog rows --");

        Column identity = introspector.toColumn(row("attname", "id", "attnum", 1, "type", "integer",
                "attnotnull", Boolean.TRUE, "default_expr", null, "attidentity", "d", "attgenerated", "",
                "generation_expr", null));
        assertEquals("identity column type", DataType.integer(), identity.getType());
        assertTrue("identity column is an identity", identity.isIdentity());
        assertTrue("identity column is NOT NULL", !identity.isNullable());
        assertEquals("identity column has no default", null, identity.getDefaultExpression());

        Column serial = introspector.toColumn(row("attname", "id", "attnum", 1, "type", "integer",
                "attnotnull", Boolean.TRUE, "default_expr", "nextval('parent_id_seq'::regclass)", "attidentity", "",
                "attgenerated", "", "generation_expr", null));
        assertTrue("serial column is not reported as identity", !serial.isIdentity());
        assertEquals("serial column default is kept", "nextval('parent_id_seq'::regclass)",
                serial.getDefaultExpression());

        Column generated = introspector.toColumn(row("attname", "total", "attnum", 2, "type", "numeric(12,4)",
                "attnotnull", Boolean.FALSE, "default_expr", "(pid * 2)", "attidentity", "", "attgenerated", "s",
                "generation_expr", "(pid * 2)"));
        assertTrue("generated column is generated", generated.isGenerated());
        assertTrue("generated column is stored", generated.isGeneratedStored());
        assertEquals("generated expression", "(pid * 2)", generated.getGeneratedExpression());
        assertEquals("generated column has no default", null, generated.getDefaultExpression());

        Column varchar = introspector.toColumn(row("attname", "code", "attnum", 3, "type",
                "character varying(10)", "attnotnull", Boolean.FALSE, "default_expr", "'x'::character varying",
                "attidentity", "", "attgenerated", "", "generation_expr", null));
        assertEquals("varchar type", DataType.varchar(10), varchar.getType());
        assertEquals("default keeps its cast (canonicalized later)", "'x'::character varying",
                varchar.getDefaultExpression());

        assertEquals("uninterpretable type yields no column", null,
                introspector.toColumn(row("attname", "m", "attnum", 4, "type", "USER-DEFINED", "attnotnull",
                        Boolean.FALSE, "attidentity", "", "attgenerated", "")));
        assertEquals("a domain is not interpretable", null, typeMapper.parse("mood"));

        /* constraints */
        Table parent = new Table("public", "parent");
        parent.addColumn(new Column("id", DataType.integer()));
        parent.addColumn(new Column("code", DataType.varchar(10)));
        Map<Integer, String> parentAttributes = new LinkedHashMap<>();
        parentAttributes.put(1, "id");
        parentAttributes.put(2, "code");
        introspector.applyConstraint(parent, row("conname", "parent_pkey", "contype", "p", "condeferrable",
                Boolean.FALSE, "condeferred", Boolean.FALSE, "conkey", "{1}", "confkey", null, "ref_table", null,
                "confdeltype", null, "confupdtype", null, "check_expr", null), parentAttributes, null);
        assertEquals("primary key columns", List.of("id"), parent.getPrimaryKey().getColumns());

        introspector.applyConstraint(parent, row("conname", "parent_code_key", "contype", "u", "condeferrable",
                Boolean.TRUE, "condeferred", Boolean.TRUE, "conkey", "{2}", "check_expr", null), parentAttributes,
                null);
        assertEquals("unique constraint columns", List.of("code"),
                parent.getUniqueConstraints().get(0).getColumns());
        assertTrue("unique constraint deferrability is reported",
                parent.getUniqueConstraints().get(0).isDeferrable());

        Table child = new Table("public", "child");
        child.addColumn(new Column("pid", DataType.integer()));
        child.addColumn(new Column("seq", DataType.smallint()));
        Map<Integer, String> childAttributes = new LinkedHashMap<>();
        childAttributes.put(1, "pid");
        childAttributes.put(2, "seq");
        childAttributes.put(3, "label");
        introspector.applyConstraint(child, row("conname", "fk_child_parent", "contype", "f", "condeferrable",
                Boolean.TRUE, "condeferred", Boolean.TRUE, "conkey", "{1,2}", "confkey", "{1,2}", "ref_table",
                "public.parent", "confdeltype", "c", "confupdtype", "n", "check_expr", null), childAttributes,
                parentAttributes);
        ForeignKey foreignKey = child.getForeignKeys().get(0);
        assertEquals("foreign key columns", List.of("pid", "seq"), foreignKey.getColumns());
        assertEquals("foreign key target table has no schema qualification", "parent",
                foreignKey.getReferencedTable());
        assertEquals("foreign key target columns", List.of("id", "code"), foreignKey.getReferencedColumns());
        assertEquals("ON DELETE CASCADE", ReferentialAction.CASCADE, foreignKey.getOnDelete());
        assertEquals("ON UPDATE SET NULL", ReferentialAction.SET_NULL, foreignKey.getOnUpdate());
        assertTrue("deferrable", foreignKey.isDeferrable());
        assertTrue("initially deferred", foreignKey.isInitiallyDeferred());
        assertTrue("not self-referential", !foreignKey.isSelfReferential());

        Table node = new Table("public", "node");
        node.addColumn(new Column("id", DataType.integer()));
        node.addColumn(new Column("parent_id", DataType.integer()));
        Map<Integer, String> nodeAttributes = new LinkedHashMap<>();
        nodeAttributes.put(1, "id");
        nodeAttributes.put(2, "parent_id");
        introspector.applyConstraint(node, row("conname", "fk_node_parent", "contype", "f", "conkey", "{2}",
                "confkey", "{1}", "ref_table", "node", "confdeltype", "d", "confupdtype", "r", "check_expr", null),
                nodeAttributes, nodeAttributes);
        assertTrue("self-referential foreign key is detected", node.getForeignKeys().get(0).isSelfReferential());
        assertEquals("ON DELETE SET DEFAULT", ReferentialAction.SET_DEFAULT,
                node.getForeignKeys().get(0).getOnDelete());
        assertEquals("ON UPDATE RESTRICT", ReferentialAction.RESTRICT, node.getForeignKeys().get(0).getOnUpdate());

        introspector.applyConstraint(parent, row("conname", "parent_amount_check", "contype", "c", "conkey", null,
                "check_expr", "(amount > 0)"), parentAttributes, null);
        assertEquals("check expression has redundant parentheses removed", "amount > 0",
                parent.getCheckConstraints().get(0).getExpression());
        introspector.applyConstraint(parent, row("conname", "ignored", "contype", "x", "conkey", null), parentAttributes,
                null);
        assertEquals("exclusion constraints are ignored", 1, parent.getCheckConstraints().size());

        /* indexes */
        Index plain = introspector.toIndex(row("relname", "idx_child_label", "indisunique", Boolean.FALSE,
                "is_partial", Boolean.FALSE, "predicate", null, "indkey", "{3}", "indnkeyatts", 1,
                "definition", "CREATE INDEX idx_child_label ON public.child USING btree (label)"), childAttributes);
        assertEquals("plain index columns", List.of("label"), plain.getColumns());
        assertTrue("plain index is not an expression index", !plain.isExpressionIndex());

        Index expression = introspector.toIndex(row("relname", "idx_child_expr", "indisunique", Boolean.FALSE,
                "is_partial", Boolean.TRUE, "predicate", "(lower(label) IS NOT NULL)", "indkey", "{0}", "indnkeyatts",
                1, "definition",
                "CREATE INDEX idx_child_expr ON public.child USING btree (lower(label)) WHERE (lower(label) IS NOT NULL)"),
                childAttributes);
        assertEquals("expression index expressions", List.of("lower(label)"), expression.getExpressions());
        assertEquals("partial index predicate", "(lower(label) IS NOT NULL)", expression.getPredicate());
        assertTrue("partial index is partial", expression.isPartial());

        Index mixed = introspector.toIndex(row("relname", "idx_child_mixed", "indisunique", Boolean.TRUE,
                "is_partial", Boolean.FALSE, "predicate", null, "indkey", "{1,0,2}", "indnkeyatts", 3, "definition",
                "CREATE UNIQUE INDEX idx_child_mixed ON public.child USING btree (pid, lower(label), seq)"),
                childAttributes);
        assertEquals("mixed index is reported as expressions", List.of("pid", "lower(label)", "seq"),
                mixed.getExpressions());
        assertTrue("mixed index keeps uniqueness", mixed.isUnique());

        Index withInclude = introspector.toIndex(row("relname", "idx_child_include", "indisunique", Boolean.FALSE,
                "is_partial", Boolean.FALSE, "predicate", null, "indkey", "{1,3}", "indnkeyatts", 1, "definition",
                "CREATE INDEX idx_child_include ON public.child USING btree (pid) INCLUDE (label)"), childAttributes);
        assertEquals("INCLUDE columns are split off the key parts", List.of("pid"), withInclude.getColumns());
        assertEquals("INCLUDE columns", List.of("label"), withInclude.getIncludeColumns());

        assertEquals("key parts parsed from pg_get_indexdef", List.of("a", "lower(b)", "c"),
                PostgresIntrospector.keyPartsFromIndexDef(
                        "CREATE INDEX i ON public.t USING btree (a, lower(b), c) INCLUDE (d) WHERE (a > 0)"));
        assertEquals("INCLUDE columns parsed from pg_get_indexdef", List.of("d"),
                PostgresIntrospector.includeColumnsFromIndexDef(
                        "CREATE INDEX i ON public.t USING btree (a, lower(b), c) INCLUDE (d) WHERE (a > 0)"));
        assertEquals("nested expression key part", List.of("(a + b)"),
                PostgresIntrospector.keyPartsFromIndexDef(
                        "CREATE INDEX i ON public.t USING btree ((a + b))"));
        assertEquals("regclass with schema", "parent", PostgresIntrospector.bareRelationName("public.parent"));
        assertEquals("regclass without schema", "parent", PostgresIntrospector.bareRelationName("parent"));
        assertEquals("regclass with quoted mixed case name", "My Table",
                PostgresIntrospector.bareRelationName("public.\"My Table\""));
        assertEquals("regclass parameter is quoted and qualified", "\"public\".\"child\"",
                PostgresIntrospector.qualifiedRelationName("public", "child"));
        assertEquals("int2vector text form", List.of(1, 2), PostgresIntrospector.toIntList("{1,2}"));
        assertEquals("int2vector with an expression entry", List.of(1, 0, 2),
                PostgresIntrospector.toIntList("{1,0,2}"));
        assertEquals("java array form", List.of(3), PostgresIntrospector.toIntList(new int[] { 3 }));
        assertEquals("null array", List.of(), PostgresIntrospector.toIntList(null));
        assertEquals("confdeltype a", ReferentialAction.NO_ACTION, PostgresIntrospector.toReferentialAction("a"));
        assertEquals("confdeltype c", ReferentialAction.CASCADE, PostgresIntrospector.toReferentialAction("c"));
        assertEquals("confdeltype n", ReferentialAction.SET_NULL, PostgresIntrospector.toReferentialAction("n"));
        assertEquals("confdeltype d", ReferentialAction.SET_DEFAULT, PostgresIntrospector.toReferentialAction("d"));
        assertEquals("confdeltype r", ReferentialAction.RESTRICT, PostgresIntrospector.toReferentialAction("r"));

        /* catalog query sanity checks: the SQL must target the documented catalogs */
        assertTrue("tables query uses pg_class and relkind",
                PostgresIntrospector.TABLES_SQL.contains("pg_class")
                        && PostgresIntrospector.TABLES_SQL.contains("relkind = 'r'"));
        assertTrue("columns query uses format_type",
                PostgresIntrospector.COLUMNS_SQL.contains("format_type(a.atttypid, a.atttypmod)"));
        assertTrue("constraints query uses pg_constraint and pg_get_constraintdef",
                PostgresIntrospector.CONSTRAINTS_SQL.contains("pg_constraint")
                        && PostgresIntrospector.CONSTRAINTS_SQL.contains("pg_get_constraintdef"));
        assertTrue("indexes query uses pg_index and excludes the primary key index",
                PostgresIntrospector.INDEXES_SQL.contains("pg_index")
                        && PostgresIntrospector.INDEXES_SQL.contains("NOT ix.indisprimary"));
        assertTrue("views query includes materialized views",
                PostgresIntrospector.VIEWS_SQL.contains("relkind IN ('v','m')"));
        assertTrue("sequences query uses information_schema.sequences",
                PostgresIntrospector.SEQUENCES_SQL.contains("information_schema.sequences"));
        assertTrue("table comments query uses pg_description with objsubid = 0",
                PostgresIntrospector.TABLE_COMMENTS_SQL.contains("pg_description")
                        && PostgresIntrospector.TABLE_COMMENTS_SQL.contains("objsubid = 0"));
        assertTrue("column comments query uses pg_description with objsubid = attnum",
                PostgresIntrospector.COLUMN_COMMENTS_SQL.contains("d.objsubid = a.attnum"));
    }

    private static void checkCapabilities(PostgresDialect dialect, PostgresIntrospector introspector) {
        System.out.println();
        System.out.println("-- capabilities --");
        System.out.println("  dialect features (" + dialect.getFeatures().getFeatures().size() + "):");
        for (DDLFeature feature : dialect.getFeatures().getFeatures()) {
            System.out.println("    " + feature);
        }
        System.out.println("  introspection capabilities: " + introspector.getCapabilities());
        assertEquals("introspection capability count", 21, introspector.getCapabilities().size());
        for (org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability capability : org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability
                .values()) {
            assertTrue("capability reported: " + capability, introspector.canIntrospect(capability));
        }
    }

    /* ------------------------------------------------------------------ 4. live round trip */

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    private static void checkLiveRoundTrip(PostgresDialect dialect, PostgresIntrospector introspector) {
        String url = environment("DDLSMITH_POSTGRES_URL", DEFAULT_URL);
        String user = environment("DDLSMITH_POSTGRES_USER", DEFAULT_USER);
        String password = environment("DDLSMITH_POSTGRES_PASSWORD", DEFAULT_PASSWORD);
        System.out.println();
        System.out.println("-- live round trip against " + url + " --");
        DriverManager.setLoginTimeout(5);
        Connection connection;
        try {
            connection = DriverManager.getConnection(url, user, password);
        } catch (SQLException | RuntimeException e) {
            System.out.println("SKIPPED (no server at " + url + ")");
            System.out.println("  reason: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
            return;
        }
        checks++;
        try (DDLSmithDBConnection dbConnection = new DDLSmithDBConnection(connection, LIVE_SCHEMA)) {
            System.out.println("  server: " + dbConnection.getDatabaseProductName() + " "
                    + dbConnection.getDatabaseVersion());
            // The namespace has to go on the search path. AbstractDialect renders a foreign key target
            // unqualified, so `REFERENCES parent (id)` is resolved through search_path, and without this
            // the fourth statement of this live check failed with
            // `ERROR: relation "parent" does not exist` — the same defect the first live run of the tool
            // hit on PostgreSQL, fixed in AbstractJdbcProvider but never here, which is why this
            // self-check never reached its round-trip assertion. A real run sets it up per generated
            // database; this is the same call with the schema this check creates.
            for (String setup : dialect.getSessionSetupStatements(LIVE_SCHEMA)) {
                System.out.println("  executing: " + setup);
                dbConnection.executeStatement(setup);
            }
            RelationalSchema intended = buildLiveSchema();
            List<DDLStatement> statements = new ArrayList<>();
            statements.add(new DropSchema(LIVE_SCHEMA, true, true));
            statements.add(new CreateSchema(LIVE_SCHEMA, false));
            for (Table table : intended.getTables()) {
                statements.add(new CreateTable(table));
            }
            for (Table table : intended.getTables()) {
                for (Index index : table.getIndexes()) {
                    statements.add(new CreateIndex(table.getName(), index));
                }
            }
            statements.add(new CreateSequence(intended.getSequences().get(0)));
            for (DDLStatement statement : statements) {
                for (String sql : dialect.renderAll(statement)) {
                    System.out.println("  executing: " + sql);
                    dbConnection.executeStatement(sql);
                }
            }
            RelationalSchema actual = introspector.introspect(dbConnection, LIVE_SCHEMA);
            RelationalSchema erased = SchemaNormalizer.erase(intended, introspector.getCapabilities());
            List<SchemaDifference> differences = SchemaNormalizer.diff(erased, actual);
            System.out.println("  intended: " + erased.getStatistics());
            System.out.println("  actual:   " + actual.getStatistics());
            assertTrue("live schema round trip is empty:\n" + SchemaNormalizer.render(differences),
                    differences.isEmpty());
        } catch (SQLException e) {
            checks++;
            FAILURES.add("live round trip failed: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
        }
    }

    /** A small, self-consistent schema that PostgreSQL must accept and report back unchanged. */
    private static RelationalSchema buildLiveSchema() {
        RelationalSchema schema = new RelationalSchema(LIVE_SCHEMA);

        Table parent = new Table(LIVE_SCHEMA, "parent");
        parent.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        parent.addColumn(new Column("code", DataType.varchar(10)).setNullable(false));
        parent.addColumn(new Column("amount", DataType.decimal(10, 2)).setDefaultExpression("0"));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        parent.addUniqueConstraint(new UniqueConstraint("uq_parent_code", List.of("code")));
        parent.addCheckConstraint(new CheckConstraint("ck_parent_amount", "amount >= 0"));
        parent.setComment("parent table");
        parent.getColumn("id").setComment("identifier");
        schema.addTable(parent);

        Table child = new Table(LIVE_SCHEMA, "child");
        child.addColumn(new Column("pid", DataType.integer()).setNullable(false));
        child.addColumn(new Column("seq", DataType.smallint()).setNullable(false));
        child.addColumn(new Column("label", DataType.text()));
        child.addColumn(new Column("total", DataType.decimal(12, 4)).setGeneratedExpression("pid * 2")
                .setGeneratedStored(true));
        child.addColumn(new Column("created", DataType.timestamp()).setDefaultExpression("CURRENT_TIMESTAMP"));
        child.setPrimaryKey(new PrimaryKey(List.of("pid", "seq")));
        child.addForeignKey(new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_child_parent")
                .setOnDelete(ReferentialAction.CASCADE));
        child.addIndex(new Index("idx_child_label", List.of("label")).setPredicate("label IS NOT NULL"));
        schema.addTable(child);

        Table node = new Table(LIVE_SCHEMA, "node");
        node.addColumn(new Column("id", DataType.integer()).setNullable(false));
        node.addColumn(new Column("parent_id", DataType.integer()));
        node.setPrimaryKey(new PrimaryKey(List.of("id")));
        node.addForeignKey(new ForeignKey(List.of("parent_id"), "node", List.of("id")).setName("fk_node_parent")
                .setSelfReferential(true).setOnDelete(ReferentialAction.SET_NULL));
        schema.addTable(node);

        schema.addSequence(new Sequence("seq_child").setStart(10).setIncrement(5).setMaxValue(1000L));
        return schema;
    }

    private static String oneLine(String message) {
        return message == null ? "<none>" : message.replace('\n', ' ').replace('\r', ' ');
    }
}
