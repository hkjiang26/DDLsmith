package org.ddlsmith.dbms.mysql;

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
import org.ddlsmith.ddl.gen.EngineTraits;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaNormalizer;

/**
 * Standalone verification of the MySQL module.
 *
 * <p>Mirrors {@code org.ddlsmith.dbms.postgres.SelfCheck}: a type round trip, a rendering matrix
 * with exact SQL assertions, and introspection driven by synthetic {@code information_schema} rows.
 * Finally it attempts a live connection; with no server it prints
 * {@code SKIPPED (no server at <url>)} and exits 0.</p>
 */
public final class SelfCheck {

    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/ddlsmith";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASSWORD = "root";
    private static final String LIVE_DATABASE = "ddlsmith_selfcheck";
    private static final String RULE_PROBE_DATABASE = "ddlsmith_selfcheck_rule";

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private SelfCheck() {
    }

    public static void main(String[] args) {
        System.out.println("=== DDLSmith MySQL self-check ===");
        MySqlDialect dialect = new MySqlDialect();
        MySqlTypeMapper typeMapper = new MySqlTypeMapper();
        MySqlIntrospector introspector = new MySqlIntrospector();

        checkTypeRoundTrip(typeMapper);
        checkTypeParsing(typeMapper);
        checkRenderingMatrix(dialect);
        checkAlterActions(dialect);
        checkLiteralRendering(dialect);
        checkDialectProperties(dialect);
        checkIndexKeyPartLimit(dialect);
        checkIntrospection(introspector);
        checkCapabilities(dialect, introspector);
        checkLiveRoundTrip(dialect, introspector);

        System.out.println();
        System.out.println(checks + " assertions, " + FAILURES.size() + " failure(s)");
        if (FAILURES.isEmpty()) {
            System.out.println("MYSQL SELF-CHECK OK");
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

    private static void checkTypeRoundTrip(MySqlTypeMapper mapper) {
        System.out.println();
        System.out.println("-- type round trip (render -> parse) --");
        List<DataType> types = new ArrayList<>(List.of(
                DataType.smallint(), DataType.integer(), DataType.bigint(), DataType.decimal(10, 2),
                DataType.real(), DataType.doublePrecision(), DataType.charType(5), DataType.varchar(20),
                DataType.text(), DataType.bool(), DataType.date(), DataType.time(), DataType.timestamp(),
                DataType.timestampWithTimeZone(), DataType.blob(), DataType.json(),
                DataType.enumOf(List.of("a", "b")), DataType.integer().withUnsigned(),
                DataType.decimal(12, 4).withUnsigned()));
        for (DataType type : types) {
            String rendered = mapper.render(type);
            DataType parsed = mapper.parse(rendered);
            System.out.println("  " + type.getName() + (type.isUnsigned() ? " UNSIGNED" : "") + " -> " + rendered
                    + " -> " + (parsed == null ? "null" : parsed.getName() + (parsed.isUnsigned() ? " UNSIGNED" : "")));
            assertEquals("round trip of " + type.getName() + " (rendered as " + rendered + ")", type, parsed);
            assertEquals("unsigned flag of " + type.getName(), type.isUnsigned(),
                    parsed != null && parsed.isUnsigned());
        }
        // The documented lossy mappings: MySQL has no UUID, ARRAY or INTERVAL column type.
        assertEquals("UUID renders as CHAR(36) (documented loss)", "CHAR(36)", mapper.render(DataType.uuid()));
        assertEquals("UUID parses back as CHAR(36), never as UUID", DataType.charType(36),
                mapper.parse(mapper.render(DataType.uuid())));
        assertEquals("ARRAY renders as JSON (documented loss)", "JSON",
                mapper.render(DataType.arrayOf(DataType.integer())));
        assertEquals("ARRAY parses back as JSON", DataType.json(),
                mapper.parse(mapper.render(DataType.arrayOf(DataType.integer()))));
        assertEquals("INTERVAL renders as TIME (documented loss)", "TIME", mapper.render(DataType.interval()));
        assertEquals("INTERVAL parses back as TIME", DataType.time(),
                mapper.parse(mapper.render(DataType.interval())));
        assertEquals("enum with a quote renders escaped", "ENUM('a''b','c')",
                mapper.render(DataType.enumOf(List.of("a'b", "c"))));
        assertEquals("enum with a quote parses back", DataType.enumOf(List.of("a'b", "c")),
                mapper.parse("enum('a''b','c')"));
    }

    private static void checkTypeParsing(MySqlTypeMapper mapper) {
        System.out.println();
        System.out.println("-- information_schema COLUMN_TYPE spellings --");
        Object[][] cases = {
                { "int", DataType.integer() },
                { "int(11)", DataType.integer() },
                { "integer", DataType.integer() },
                { "int unsigned", DataType.integer().withUnsigned() },
                { "int(10) unsigned", DataType.integer().withUnsigned() },
                { "int zerofill", DataType.integer().withUnsigned() },
                { "tinyint(1)", DataType.bool() },
                { "tinyint", DataType.smallint() },
                { "tinyint(4)", DataType.smallint() },
                { "smallint(6)", DataType.smallint() },
                { "mediumint", DataType.integer() },
                { "bigint(20)", DataType.bigint() },
                { "bigint unsigned", DataType.bigint().withUnsigned() },
                { "decimal(10,2)", DataType.decimal(10, 2) },
                { "decimal(10)", DataType.decimal(10, 0) },
                { "decimal", DataType.decimal(10, 0) },
                { "numeric(8,3)", DataType.decimal(8, 3) },
                { "float", DataType.real() },
                { "float(12,3)", DataType.real() },
                { "double", DataType.doublePrecision() },
                { "double(12,3)", DataType.doublePrecision() },
                { "char(5)", DataType.charType(5) },
                { "char", DataType.charType(1) },
                { "varchar(20)", DataType.varchar(20) },
                { "tinytext", DataType.text() },
                { "text", DataType.text() },
                { "mediumtext", DataType.text() },
                { "longtext", DataType.text() },
                { "boolean", DataType.bool() },
                { "date", DataType.date() },
                { "time", DataType.time() },
                { "time(6)", DataType.time() },
                { "datetime", DataType.timestamp() },
                { "datetime(6)", DataType.timestamp() },
                { "timestamp", DataType.timestampWithTimeZone() },
                { "timestamp(6)", DataType.timestampWithTimeZone() },
                { "blob", DataType.blob() },
                { "tinyblob", DataType.blob() },
                { "mediumblob", DataType.blob() },
                { "longblob", DataType.blob() },
                { "varbinary(16)", DataType.blob() },
                { "json", DataType.json() },
                { "enum('a','b')", DataType.enumOf(List.of("a", "b")) },
                { "year", null },
                { "bit(1)", null },
                { "set('a','b')", null },
                { "geometry", null },
                { "point", null },
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
        RelationalSchema schema = new RelationalSchema("shop");

        Table parent = new Table("shop", "parent");
        parent.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        parent.addColumn(new Column("code", DataType.varchar(10)).setNullable(false).setDefaultExpression("'x'"));
        parent.addColumn(new Column("amount", DataType.decimal(10, 2)).setCheckExpression("amount >= 0"));
        parent.addColumn(new Column("active", DataType.bool()).setDefaultExpression("TRUE"));
        parent.addColumn(new Column("payload", DataType.json()));
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
        parent.addColumn(new Column("note", DataType.text()).setCollation("utf8mb4_bin"));
        parent.addColumn(new Column("summary", DataType.text()).setUnique(true));
        parent.addColumn(new Column("doc", DataType.uuid()));
        parent.addColumn(new Column("tags", DataType.arrayOf(DataType.text())));
        parent.addColumn(new Column("span", DataType.interval()));
        parent.addColumn(new Column("status", DataType.enumOf(List.of("a", "b"))));
        parent.addColumn(new Column("counter", DataType.integer().withUnsigned()));
        parent.addColumn(new Column("total", DataType.decimal(12, 4)).setGeneratedExpression("amount * 2")
                .setGeneratedStored(true));
        parent.addColumn(new Column("label", DataType.varchar(20)).setComment("a label"));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        parent.addUniqueConstraint(new UniqueConstraint("uq_parent_code", List.of("code")));
        parent.addCheckConstraint(new CheckConstraint("ck_parent_amount", "amount >= 0"));
        parent.setComment("parent table");
        parent.addIndex(new Index("idx_parent_code", List.of("code")));
        schema.addTable(parent);

        Table child = new Table("shop", "child");
        child.addColumn(new Column("pid", DataType.integer()).setNullable(false));
        child.addColumn(new Column("seq", DataType.smallint()).setNullable(false));
        child.addColumn(new Column("label", DataType.text()));
        child.setPrimaryKey(new PrimaryKey(List.of("pid", "seq")));
        child.addForeignKey(new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_child_parent")
                .setOnDelete(ReferentialAction.CASCADE).setOnUpdate(ReferentialAction.SET_NULL));
        child.addForeignKey(new ForeignKey(List.of("pid", "seq"), "parent", List.of("id", "code"))
                .setName("fk_child_composite"));
        schema.addTable(child);

        Table node = new Table("shop", "node");
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

        View plainView = new View("v_plain", "SELECT 1 AS one");
        schema.addView(plainView);
        return schema;
    }

    private static void checkRenderingMatrix(MySqlDialect dialect) {
        System.out.println();
        System.out.println("-- rendering matrix --");
        RelationalSchema schema = buildMatrixSchema();

        List<String> parentSql = dialect.renderAll(new CreateTable(schema.getTable("parent")));
        show("CREATE TABLE parent", parentSql.get(0));
        assertEquals("MySQL keeps the table comment inline, so renderAll yields one statement", 1, parentSql.size());
        assertSql("CREATE TABLE with AUTO_INCREMENT, table comment and every type",
                "CREATE TABLE parent ("
                        + "id INT NOT NULL AUTO_INCREMENT, code VARCHAR(10) NOT NULL DEFAULT 'x', "
                        + "amount DECIMAL(10,2) CHECK (amount >= 0), active BOOLEAN DEFAULT TRUE, payload JSON, "
                        + "created DATETIME DEFAULT CURRENT_TIMESTAMP, updated TIMESTAMP, raw BLOB, ratio DOUBLE, "
                        + "small SMALLINT, big BIGINT, single FLOAT, fixed CHAR(3), moment TIME, day DATE, "
                        + "note TEXT COLLATE utf8mb4_bin, summary TEXT UNIQUE, doc CHAR(36), tags JSON, span TIME, "
                        + "status ENUM('a','b'), counter INT UNSIGNED, "
                        + "total DECIMAL(12,4) GENERATED ALWAYS AS (amount * 2) STORED, "
                        + "label VARCHAR(20) COMMENT 'a label', "
                        + "CONSTRAINT pk_parent PRIMARY KEY (id), "
                        + "CONSTRAINT uq_parent_code UNIQUE (code), "
                        + "CONSTRAINT ck_parent_amount CHECK (amount >= 0)) COMMENT = 'parent table'",
                parentSql.get(0));

        show("CREATE TABLE child", dialect.render(new CreateTable(schema.getTable("child"))));
        assertSql("composite key and foreign keys with referential actions",
                "CREATE TABLE child (pid INT NOT NULL, seq SMALLINT NOT NULL, label TEXT, "
                        + "PRIMARY KEY (pid, seq), "
                        + "CONSTRAINT fk_child_parent FOREIGN KEY (pid) REFERENCES parent (id) "
                        + "ON DELETE CASCADE ON UPDATE SET NULL, "
                        + "CONSTRAINT fk_child_composite FOREIGN KEY (pid, seq) REFERENCES parent (id, code))",
                dialect.render(new CreateTable(schema.getTable("child"))));

        assertSql("self-referential foreign key",
                "CREATE TABLE node (id INT NOT NULL, parent_id INT, PRIMARY KEY (id), "
                        + "CONSTRAINT fk_node_parent FOREIGN KEY (parent_id) REFERENCES node (id) "
                        + "ON DELETE SET DEFAULT ON UPDATE RESTRICT)",
                dialect.render(new CreateTable(schema.getTable("node"))));
        assertSql("TEMPORARY TABLE", "CREATE TEMPORARY TABLE tmp_stage (x INT)",
                dialect.render(new CreateTable(schema.getTable("tmp_stage"))));

        /* the documented AUTO_INCREMENT relaxation */
        Table keyed = new Table("keyed");
        keyed.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        keyed.setPrimaryKey(new PrimaryKey(List.of("id")));
        assertSql("AUTO_INCREMENT is emitted for a primary key column",
                "CREATE TABLE keyed (id INT NOT NULL AUTO_INCREMENT, PRIMARY KEY (id))",
                dialect.render(new CreateTable(keyed)));

        Table notKeyed = new Table("not_keyed");
        notKeyed.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        notKeyed.addColumn(new Column("k", DataType.integer()).setNullable(false));
        notKeyed.setPrimaryKey(new PrimaryKey(List.of("k")));
        assertSql("AUTO_INCREMENT is dropped for a non-key identity column (documented relaxation)",
                "CREATE TABLE not_keyed (id INT NOT NULL, k INT NOT NULL, PRIMARY KEY (k))",
                dialect.render(new CreateTable(notKeyed)));

        assertSql("DROP TABLE IF EXISTS", "DROP TABLE IF EXISTS t",
                dialect.render(new DropTable("t", true, true)));
        assertSql("TRUNCATE TABLE ignores CASCADE (feature absent)", "TRUNCATE TABLE t",
                dialect.render(new TruncateTable("t", true, false)));

        /* indexes */
        assertSql("CREATE INDEX", "CREATE INDEX idx_a ON t (a, b)",
                dialect.render(new CreateIndex("t", new Index("idx_a", List.of("a", "b")))));
        assertSql("CREATE UNIQUE INDEX (IF NOT EXISTS is never rendered)",
                "CREATE UNIQUE INDEX idx_u ON t (a)",
                dialect.render(new CreateIndex("t", new Index("idx_u", List.of("a")).setUnique(true), true)));
        Index expressionIndex = new Index("idx_e");
        expressionIndex.getExpressions().add("lower(b)");
        assertSql("functional index parts are parenthesised", "CREATE INDEX idx_e ON t ((lower(b)))",
                dialect.render(new CreateIndex("t", expressionIndex)));
        Index descendingIndex = new Index("idx_d");
        descendingIndex.getExpressions().add("lower(b) DESC");
        assertSql("functional index direction stays outside the parentheses",
                "CREATE INDEX idx_d ON t ((lower(b)) DESC)", dialect.render(new CreateIndex("t", descendingIndex)));
        Index mixedIndex = new Index("idx_m");
        mixedIndex.getExpressions().addAll(List.of("a", "lower(b)"));
        assertSql("mixed key parts", "CREATE INDEX idx_m ON t (a, (lower(b)))",
                dialect.render(new CreateIndex("t", mixedIndex)));
        assertSql("DROP INDEX is table qualified and has no IF EXISTS", "DROP INDEX idx_a ON t",
                dialect.render(new DropIndex("idx_a", "t", false, false)));
        assertSql("DROP INDEX IF EXISTS is not rendered", "DROP INDEX idx_a ON t",
                dialect.render(new DropIndex("idx_a", "t", true, true)));

        /* views */
        assertSql("CREATE OR REPLACE VIEW with column list and check option",
                "CREATE OR REPLACE VIEW v_parent (id, code) AS SELECT id, code FROM parent WITH CASCADED CHECK OPTION",
                dialect.render(new CreateView(schema.getView("v_parent"), true, false)));
        assertSql("CREATE VIEW", "CREATE VIEW v_plain AS SELECT 1 AS one",
                dialect.render(new CreateView(schema.getView("v_plain"), false, false)));
        assertSql("DROP VIEW IF EXISTS", "DROP VIEW IF EXISTS v1",
                dialect.render(new DropView("v1", true, true)));

        /* statements whose features are absent: rendering them is a documented no-op */
        assertEquals("CREATE SCHEMA is a safety-net comment, never a database creation",
                MySqlDialect.NO_SCHEMA_SUPPORT, dialect.render(new CreateSchema("s", true)));
        assertEquals("DROP SCHEMA must never render (it would drop a database)",
                MySqlDialect.NO_SCHEMA_SUPPORT, dialect.render(new DropSchema("s", true, true)));
        assertEquals("CREATE SEQUENCE is a safety-net comment",
                MySqlDialect.NO_SEQUENCE_SUPPORT,
                dialect.render(new CreateSequence(new Sequence("s"), true)));
        assertEquals("DROP SEQUENCE is a safety-net comment",
                MySqlDialect.NO_SEQUENCE_SUPPORT, dialect.render(new DropSequence("s", true, true)));
    }

    private static void checkAlterActions(MySqlDialect dialect) {
        System.out.println();
        System.out.println("-- ALTER TABLE --");
        assertSql("ADD COLUMN never renders IF NOT EXISTS",
                "ALTER TABLE t ADD COLUMN c INT",
                dialect.render(new AlterTable("t", new AddColumn(new Column("c", DataType.integer()), true))));
        assertSql("ADD COLUMN with an inline UNIQUE allows AUTO_INCREMENT",
                "ALTER TABLE t ADD COLUMN c INT AUTO_INCREMENT UNIQUE",
                dialect.render(new AlterTable("t", new AddColumn(
                        new Column("c", DataType.integer()).setIdentity(true).setUnique(true)))));
        assertSql("ADD COLUMN without a provable key drops AUTO_INCREMENT (documented relaxation)",
                "ALTER TABLE t ADD COLUMN c INT",
                dialect.render(new AlterTable("t",
                        new AddColumn(new Column("c", DataType.integer()).setIdentity(true)))));
        assertSql("DROP COLUMN", "ALTER TABLE t DROP COLUMN c",
                dialect.render(new AlterTable("t", new DropColumn("c", null, true, true))));
        assertSql("RENAME COLUMN", "ALTER TABLE t RENAME COLUMN a TO b",
                dialect.render(new AlterTable("t", new RenameColumn("a", "b"))));
        assertSql("RENAME TO", "ALTER TABLE t RENAME TO t2",
                dialect.render(new AlterTable("t", new RenameTable("t", "t2"))));
        assertSql("ALTER COLUMN TYPE uses MODIFY COLUMN",
                "ALTER TABLE t MODIFY COLUMN c BIGINT",
                dialect.render(new AlterTable("t",
                        new AlterColumnType("c", DataType.bigint(), DataType.integer(), "c"))));
        assertSql("SET DEFAULT", "ALTER TABLE t ALTER COLUMN c SET DEFAULT 5",
                dialect.render(new AlterTable("t", new SetDefault("c", "5"))));
        assertSql("DROP DEFAULT", "ALTER TABLE t ALTER COLUMN c DROP DEFAULT",
                dialect.render(new AlterTable("t", new DropDefault("c"))));
        assertSql("ADD CONSTRAINT with a named foreign key emits the name once",
                "ALTER TABLE t ADD CONSTRAINT fk_x FOREIGN KEY (pid) REFERENCES parent (id)",
                dialect.render(new AlterTable("t", new AddConstraint(ConstraintDefinition.foreignKey(
                        new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_x"))))));
        assertSql("ADD CONSTRAINT PRIMARY KEY",
                "ALTER TABLE t ADD CONSTRAINT pk_x PRIMARY KEY (a, b)",
                dialect.render(new AlterTable("t", new AddConstraint(
                        ConstraintDefinition.primaryKey(new PrimaryKey("pk_x", List.of("a", "b")))))));
        assertSql("ADD CONSTRAINT UNIQUE",
                "ALTER TABLE t ADD CONSTRAINT uq_x UNIQUE (a)",
                dialect.render(new AlterTable("t", new AddConstraint(
                        ConstraintDefinition.unique(new UniqueConstraint("uq_x", List.of("a")))))));
        assertSql("ADD CONSTRAINT CHECK",
                "ALTER TABLE t ADD CONSTRAINT ck_x CHECK (a > 0)",
                dialect.render(new AlterTable("t", new AddConstraint(
                        ConstraintDefinition.check(new CheckConstraint("ck_x", "a > 0"))))));
        assertSql("several actions in one statement",
                "ALTER TABLE t ADD COLUMN c INT, DROP COLUMN d, RENAME COLUMN e TO f",
                dialect.render(new AlterTable("t", List.of(new AddColumn(new Column("c", DataType.integer())),
                        new DropColumn("d", null, false, false), new RenameColumn("e", "f")))));

        /* the DROP CONSTRAINT dispatch */
        System.out.println("  -- DROP CONSTRAINT dispatch --");
        ConstraintDefinition foreignKey = ConstraintDefinition.foreignKey(
                new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_x"));
        ConstraintDefinition primaryKey = ConstraintDefinition.primaryKey(new PrimaryKey("pk_x", List.of("a")));
        ConstraintDefinition unique = ConstraintDefinition.unique(new UniqueConstraint("uq_x", List.of("a")));
        ConstraintDefinition check = ConstraintDefinition.check(new CheckConstraint("ck_x", "a > 0"));
        assertSql("DROP CONSTRAINT of a FOREIGN KEY", "ALTER TABLE t DROP FOREIGN KEY fk_x",
                dialect.render(new AlterTable("t", new DropConstraint("fk_x", foreignKey, true, true))));
        assertSql("DROP CONSTRAINT of a PRIMARY KEY drops the name", "ALTER TABLE t DROP PRIMARY KEY",
                dialect.render(new AlterTable("t", new DropConstraint("pk_x", primaryKey, true, true))));
        assertSql("DROP CONSTRAINT of a UNIQUE constraint", "ALTER TABLE t DROP INDEX uq_x",
                dialect.render(new AlterTable("t", new DropConstraint("uq_x", unique, true, true))));
        assertSql("DROP CONSTRAINT of a CHECK constraint", "ALTER TABLE t DROP CHECK ck_x",
                dialect.render(new AlterTable("t", new DropConstraint("ck_x", check, true, true))));
        assertSql("DROP CONSTRAINT without a definition uses the name heuristic (fk)",
                "ALTER TABLE t DROP FOREIGN KEY fk_child_parent",
                dialect.render(new AlterTable("t", new DropConstraint("fk_child_parent"))));
        assertSql("DROP CONSTRAINT without a definition uses the name heuristic (uq)",
                "ALTER TABLE t DROP INDEX uq_code",
                dialect.render(new AlterTable("t", new DropConstraint("uq_code"))));
        assertSql("DROP CONSTRAINT without a definition uses the name heuristic (pk)",
                "ALTER TABLE t DROP PRIMARY KEY",
                dialect.render(new AlterTable("t", new DropConstraint("pk_parent"))));
        assertSql("DROP CONSTRAINT without a definition uses the name heuristic (ck)",
                "ALTER TABLE t DROP CHECK ck_amount",
                dialect.render(new AlterTable("t", new DropConstraint("ck_amount"))));

        /* SET/DROP NOT NULL are not MySQL syntax */
        assertThrows("SetNotNull throws UnsupportedOperationException",
                () -> dialect.render(new AlterTable("t", new SetNotNull("c"))));
        assertThrows("DropNotNull throws UnsupportedOperationException",
                () -> dialect.render(new AlterTable("t", new DropNotNull("c"))));
    }

    private static void assertThrows(String label, Runnable runnable) {
        checks++;
        try {
            runnable.run();
            FAILURES.add(label + "\n      expected UnsupportedOperationException, nothing was thrown");
        } catch (UnsupportedOperationException e) {
            // expected
        } catch (RuntimeException e) {
            FAILURES.add(label + "\n      expected UnsupportedOperationException, got "
                    + e.getClass().getSimpleName());
        }
    }

    private static void checkLiteralRendering(MySqlDialect dialect) {
        System.out.println();
        System.out.println("-- literals --");
        assertSql("TRUE", "TRUE", dialect.renderLiteral(Boolean.TRUE, DataType.bool()));
        assertSql("FALSE", "FALSE", dialect.renderLiteral(Boolean.FALSE, DataType.bool()));
        assertSql("number", "42", dialect.renderLiteral(42, DataType.integer()));
        assertSql("string", "'O''Brien'", dialect.renderLiteral("O'Brien", DataType.text()));
        assertSql("backslash is escaped", "'a\\\\b'", dialect.renderLiteral("a\\b", DataType.text()));
        assertSql("NULL", "NULL", dialect.renderLiteral(null, DataType.text()));
        assertSql("blob uses X'..'", "X'01AB'", dialect.renderLiteral(new byte[] { 0x01, (byte) 0xAB },
                DataType.blob()));
    }

    private static void checkDialectProperties(MySqlDialect dialect) {
        System.out.println();
        System.out.println("-- dialect properties --");
        assertEquals("getName", "mysql", dialect.getName());
        assertEquals("getDefaultSchemaName is null (the connection's database is the namespace)", null,
                dialect.getDefaultSchemaName());
        assertEquals("normalizeIdentifier folds to lower case", "mytable", dialect.normalizeIdentifier("MyTable"));
        assertEquals("quote is the identity", "MyTable", dialect.quote("MyTable"));
        List<String> setup = dialect.getSessionSetupStatements();
        System.out.println("  session setup: " + setup);
        assertEquals("session setup statements", 2, setup.size());
        assertSql("strict mode is required for meaningful constraint testing",
                "SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION'", setup.get(0));
        assertSql("implicit TIMESTAMP defaults must be disabled", "SET SESSION explicit_defaults_for_timestamp = ON",
                setup.get(1));
        for (DDLFeature feature : new DDLFeature[] { DDLFeature.IF_NOT_EXISTS, DDLFeature.DROP_IF_EXISTS,
                DDLFeature.TEMPORARY_TABLE, DDLFeature.COMPOSITE_PRIMARY_KEY, DDLFeature.NAMED_CHECK,
                DDLFeature.AUTO_INCREMENT, DDLFeature.GENERATED_STORED, DDLFeature.GENERATED_VIRTUAL,
                DDLFeature.COLLATION, DDLFeature.COLUMN_COMMENT, DDLFeature.TABLE_COMMENT,
                DDLFeature.COLUMN_TYPE_UNSIGNED, DDLFeature.EXPRESSION_INDEX, DDLFeature.INDEX_DESCENDING,
                DDLFeature.ALTER_MULTIPLE_ACTIONS, DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS,
                DDLFeature.TYPE_ENUM,
                DDLFeature.TYPE_JSON, DDLFeature.VIEW_CHECK_OPTION, DDLFeature.NULLS_DISTINCT,
                DDLFeature.FK_SET_DEFAULT, DDLFeature.SELF_REFERENTIAL_FK, DDLFeature.UNIQUE_CONSTRAINT,
                DDLFeature.FK_TARGET_MUST_EXIST }) {
            assertTrue("feature declared: " + feature, dialect.getFeatures().supports(feature));
        }
        assertEquals("feature count", 74, dialect.getFeatures().getFeatures().size());
        checkFeatureCoverage(dialect);
        assertEquals("InnoDB accepts at most 16 key parts per index (error 1070)", 16,
                EngineTraits.maxIndexColumns("mysql"));
        assertEquals("MySQL's DECIMAL precision ceiling", 65, EngineTraits.maxDecimalPrecision("mysql"));
    }

    /**
     * The adapter has to cap an index at the engine's key-part limit before anything is rendered,
     * because MySQL rejects the whole statement rather than the excess part.
     *
     * <p>This runs without a server, so the cap is checked on every build instead of only when one
     * happens to be reachable: the census that found the missing cap ran against a live server, and a
     * self-check that needs one is the reason the gap survived.</p>
     */
    private static void checkIndexKeyPartLimit(MySqlDialect dialect) {
        System.out.println();
        System.out.println("-- index key-part limit --");
        RelationalSchema schema = new RelationalSchema("shop");
        Table wide = new Table("shop", "wide");
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            wide.addColumn(new Column("w" + i, DataType.integer()));
            columns.add("w" + i);
        }
        wide.setPrimaryKey(new PrimaryKey(List.of("w0")));
        wide.addIndex(new Index("idx_wide_all", columns));
        schema.addTable(wide);

        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(schema, dialect, notes);
        Index adaptedIndex = adapted.getTable("wide").getIndexes().get(0);
        System.out.println("  18-column index -> " + adaptedIndex.getColumns().size() + " column(s)");
        for (String note : notes) {
            System.out.println("  note: " + note);
        }
        assertEquals("the adapter caps the index at MySQL's 16 key parts", 16, adaptedIndex.getColumns().size());
        assertTrue("the cap is recorded as an adaptation note",
                notes.stream().anyMatch(note -> note.contains("key parts")));
        String sql = dialect.render(new CreateIndex("wide", adaptedIndex));
        System.out.println("  " + sql);
        assertTrue("the rendered CREATE INDEX names the 16th key part and no more",
                sql.contains("w15") && !sql.contains("w16"));
    }

    /**
     * The {@link DDLFeature} constants MySQL deliberately does not declare.
     *
     * <p>Every other constant must be present. Checking the whole enum (rather than a hand written
     * list of interesting flags) means a constant added to {@code DDLFeature} later cannot silently
     * be left out: a missing flag removes a construct from every generated schema, while a wrongly
     * present flag produces a false engine finding.</p>
     */
    private static final Set<DDLFeature> NOT_DECLARED = EnumSet.of(
            // MySQL's "schema" is a database; DDLSmith uses the connection's database as the namespace
            DDLFeature.CREATE_SCHEMA,
            DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS,
            DDLFeature.DROP_SCHEMA,
            DDLFeature.DROP_SCHEMA_CASCADE,
            DDLFeature.QUALIFIED_NAMES,
            // MySQL has no DROP ... CASCADE
            DDLFeature.CASCADE_DROP,
            // the DDL AST has no inline (column level) foreign key representation
            DDLFeature.INLINE_FOREIGN_KEY,
            // MySQL parses but does not enforce DEFERRABLE / INITIALLY DEFERRED / MATCH FULL
            DDLFeature.DEFERRABLE_FK,
            DDLFeature.INITIALLY_DEFERRED,
            DDLFeature.MATCH_FULL,
            // SQLite specific
            DDLFeature.FK_ENFORCED_BY_PRAGMA,
            // MySQL's spelling is AUTO_INCREMENT
            DDLFeature.IDENTITY_COLUMN,
            DDLFeature.SERIAL,
            // MySQL 8.0 has no ADD COLUMN IF NOT EXISTS and no DROP COLUMN CASCADE
            DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS,
            DDLFeature.ALTER_DROP_COLUMN_CASCADE,
            // MySQL uses MODIFY COLUMN and has no SET/DROP NOT NULL
            DDLFeature.ALTER_COLUMN_TYPE_USING,
            DDLFeature.ALTER_SET_NOT_NULL,
            DDLFeature.ALTER_DROP_NOT_NULL,
            // MySQL's syntax is DROP INDEX name ON table, not table.name
            DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE,
            DDLFeature.INDEX_METHOD,
            // MySQL rejects both with ERROR 1064 ("... near 'IF NOT EXISTS i1 ON t (a)'"), measured on
            // 26.7.0. They were declared once and the generator emitted them; the crash oracle reported
            // the rejection on every one of 20 generated databases. The feature set is the contract with
            // the engine, so a construct the engine does not parse must not be in it — and the two
            // assertions above used to still demand them, which is why this self-check was red.
            DDLFeature.CREATE_INDEX_IF_NOT_EXISTS,
            DDLFeature.DROP_INDEX_IF_EXISTS,
            // no partial, covering or functional-index-direction support in the canonical model
            DDLFeature.PARTIAL_INDEX,
            DDLFeature.INCLUDE_COLUMNS,
            // no materialized views, no sequences
            DDLFeature.MATERIALIZED_VIEW,
            DDLFeature.CREATE_SEQUENCE,
            DDLFeature.DROP_SEQUENCE,
            DDLFeature.SEQUENCE_OPTIONS,
            DDLFeature.TRUNCATE_CASCADE,
            // no array, UUID, timestamptz, interval or domain types
            DDLFeature.TYPE_ARRAY,
            DDLFeature.TYPE_UUID,
            DDLFeature.TYPE_TIMESTAMPTZ,
            DDLFeature.TYPE_INTERVAL,
            DDLFeature.TYPE_DOMAIN,
            // enums are native, so no CHECK emulation is needed
            DDLFeature.ENUM_AS_CHECK);

    private static void checkFeatureCoverage(MySqlDialect dialect) {
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

    private static void checkIntrospection(MySqlIntrospector introspector) {
        System.out.println();
        System.out.println("-- introspection from synthetic information_schema rows --");

        Column identity = introspector.toColumn(row("column_name", "id", "ordinal_position", 1, "column_default",
                null, "is_nullable", "NO", "data_type", "int", "column_type", "int", "extra", "auto_increment",
                "generation_expression", "", "collation_name", null, "column_comment", ""), "utf8mb4_0900_ai_ci");
        assertEquals("identity type", DataType.integer(), identity.getType());
        assertTrue("auto_increment is identity", identity.isIdentity());
        assertTrue("NOT NULL", !identity.isNullable());
        assertEquals("no comment", null, identity.getComment());

        Column text = introspector.toColumn(row("column_name", "code", "column_default", "abc", "is_nullable", "YES",
                "data_type", "varchar", "column_type", "varchar(20)", "extra", "", "generation_expression", "",
                "collation_name", "utf8mb4_0900_ai_ci", "column_comment", "the code"), "utf8mb4_0900_ai_ci");
        assertEquals("varchar type comes from column_type", DataType.varchar(20), text.getType());
        assertEquals("a string default is quoted again", "'abc'", text.getDefaultExpression());
        assertEquals("column comment", "the code", text.getComment());
        assertEquals("an inherited collation is not reported", null, text.getCollation());

        Column explicitCollation = introspector.toColumn(row("column_name", "note", "column_default", null,
                "is_nullable", "YES", "data_type", "text", "column_type", "text", "extra", "",
                "generation_expression", "", "collation_name", "utf8mb4_bin", "column_comment", ""),
                "utf8mb4_0900_ai_ci");
        assertEquals("an explicit collation is reported", "utf8mb4_bin", explicitCollation.getCollation());

        Column timestamp = introspector.toColumn(row("column_name", "created", "column_default", "CURRENT_TIMESTAMP",
                "is_nullable", "NO", "data_type", "datetime", "column_type", "datetime", "extra", "DEFAULT_GENERATED",
                "generation_expression", "", "collation_name", null, "column_comment", ""), null);
        assertEquals("expression defaults are not quoted", "CURRENT_TIMESTAMP", timestamp.getDefaultExpression());
        assertEquals("datetime maps to TIMESTAMP", DataType.timestamp(), timestamp.getType());

        Column numeric = introspector.toColumn(row("column_name", "amount", "column_default", "5", "is_nullable",
                "YES", "data_type", "int", "column_type", "int", "extra", "", "generation_expression", "",
                "collation_name", null, "column_comment", ""), null);
        assertEquals("a numeric default is not quoted", "5", numeric.getDefaultExpression());

        Column generated = introspector.toColumn(row("column_name", "total", "column_default", null, "is_nullable",
                "YES", "data_type", "decimal", "column_type", "decimal(12,4)", "extra", "STORED GENERATED",
                "generation_expression", "(`amount` * 2)", "collation_name", null, "column_comment", ""), null);
        assertTrue("stored generated column", generated.isGenerated() && generated.isGeneratedStored());
        assertEquals("generation expression", "(`amount` * 2)", generated.getGeneratedExpression());
        assertEquals("generated column has no default", null, generated.getDefaultExpression());

        Column virtual = introspector.toColumn(row("column_name", "v", "column_default", null, "is_nullable", "YES",
                "data_type", "int", "column_type", "int", "extra", "VIRTUAL GENERATED", "generation_expression",
                "(`a` + 1)", "collation_name", null, "column_comment", ""), null);
        assertTrue("virtual generated column", virtual.isGenerated() && !virtual.isGeneratedStored());

        assertEquals("uninterpretable type yields no column", null,
                introspector.toColumn(row("column_name", "y", "is_nullable", "YES", "data_type", "year",
                        "column_type", "year", "extra", "", "generation_expression", "", "column_comment", ""), null));
        assertEquals("a YEAR column is not representable", null, new MySqlTypeMapper().parse("year"));

        /* constraints */
        Table parent = new Table("shop", "parent");
        parent.addColumn(new Column("id", DataType.integer()));
        parent.addColumn(new Column("code", DataType.varchar(10)));
        introspector.applyConstraint(parent, row("constraint_name", "PRIMARY", "constraint_type", "PRIMARY KEY"),
                List.of(row("column_name", "id", "ordinal_position", 1)), null, null);
        assertEquals("primary key columns", List.of("id"), parent.getPrimaryKey().getColumns());
        introspector.applyConstraint(parent, row("constraint_name", "uq_parent_code", "constraint_type", "UNIQUE"),
                List.of(row("column_name", "code", "ordinal_position", 1)), null, null);
        assertEquals("unique constraint columns", List.of("code"),
                parent.getUniqueConstraints().get(0).getColumns());
        introspector.applyConstraint(parent, row("constraint_name", "ck_parent_amount", "constraint_type", "CHECK"),
                List.of(), null, "(cast(`amount` as signed) >= 0)");
        assertEquals("check clause", "(cast(`amount` as signed) >= 0)",
                parent.getCheckConstraints().get(0).getExpression());
        introspector.applyConstraint(parent, row("constraint_name", "ck_ignored", "constraint_type", "CHECK"),
                List.of(), null, null);
        assertEquals("a CHECK without a clause (pre-8.0.16) is skipped", 1, parent.getCheckConstraints().size());

        Table child = new Table("shop", "child");
        child.addColumn(new Column("pid", DataType.integer()));
        child.addColumn(new Column("seq", DataType.smallint()));
        List<Map<String, Object>> keyUsage = List.of(
                row("constraint_name", "fk_child_parent", "column_name", "pid", "ordinal_position", 1,
                        "referenced_table_name", "parent", "referenced_column_name", "id"));
        introspector.applyConstraint(child, row("constraint_name", "fk_child_parent", "constraint_type",
                "FOREIGN KEY"), keyUsage, row("constraint_name", "fk_child_parent", "update_rule", "SET NULL",
                        "delete_rule", "CASCADE"), null);
        ForeignKey foreignKey = child.getForeignKeys().get(0);
        assertEquals("foreign key columns", List.of("pid"), foreignKey.getColumns());
        assertEquals("foreign key target", "parent", foreignKey.getReferencedTable());
        assertEquals("foreign key target columns", List.of("id"), foreignKey.getReferencedColumns());
        assertEquals("ON DELETE", ReferentialAction.CASCADE, foreignKey.getOnDelete());
        assertEquals("ON UPDATE", ReferentialAction.SET_NULL, foreignKey.getOnUpdate());
        assertTrue("not self-referential", !foreignKey.isSelfReferential());

        Table node = new Table("shop", "node");
        node.addColumn(new Column("id", DataType.integer()));
        node.addColumn(new Column("parent_id", DataType.integer()));
        introspector.applyConstraint(node, row("constraint_name", "fk_node_parent", "constraint_type", "FOREIGN KEY"),
                List.of(row("column_name", "parent_id", "ordinal_position", 1, "referenced_table_name", "node",
                        "referenced_column_name", "id")),
                row("update_rule", "RESTRICT", "delete_rule", "SET DEFAULT"), null);
        assertTrue("self-referential foreign key", node.getForeignKeys().get(0).isSelfReferential());
        assertEquals("ON DELETE SET DEFAULT", ReferentialAction.SET_DEFAULT,
                node.getForeignKeys().get(0).getOnDelete());
        assertEquals("ON UPDATE RESTRICT", ReferentialAction.RESTRICT,
                node.getForeignKeys().get(0).getOnUpdate());

        /* indexes */
        Index plain = introspector.toIndex("idx_label", List.of(
                row("index_name", "idx_label", "non_unique", 0, "seq_in_index", 1, "column_name", "label",
                        "expression", null, "index_type", "BTREE", "sub_part", null)));
        assertEquals("plain index columns", List.of("label"), plain.getColumns());
        assertTrue("plain index uniqueness", plain.isUnique());
        Index composite = introspector.toIndex("idx_two", List.of(
                row("seq_in_index", 1, "column_name", "a", "non_unique", 1),
                row("seq_in_index", 2, "column_name", "b", "non_unique", 1)));
        assertEquals("composite index keeps key order", List.of("a", "b"), composite.getColumns());
        assertTrue("non-unique index", !composite.isUnique());
        Index functional = introspector.toIndex("idx_lower", List.of(
                row("seq_in_index", 1, "column_name", null, "expression", "lower(`label`)", "non_unique", 1)));
        assertEquals("functional key part", List.of("lower(`label`)"), functional.getExpressions());
        assertTrue("functional index is an expression index", functional.isExpressionIndex());
        Index mixed = introspector.toIndex("idx_mixed", List.of(
                row("seq_in_index", 1, "column_name", "a", "non_unique", 1),
                row("seq_in_index", 2, "column_name", null, "expression", "lower(`b`)", "non_unique", 1)));
        assertEquals("mixed index is reported as expressions", List.of("a", "lower(`b`)"), mixed.getExpressions());
        assertEquals("direction is stripped from a functional key part", "lower(`b`)",
                MySqlIntrospector.stripDirection("lower(`b`) DESC"));
        assertEquals("a plain expression is unchanged", "lower(`b`)",
                MySqlIntrospector.stripDirection("lower(`b`)"));
        assertEquals("default of a textual column is quoted", "'abc'",
                MySqlIntrospector.toDefaultExpression("abc", DataType.varchar(10), false));
        assertEquals("an already quoted default is left alone", "'abc'",
                MySqlIntrospector.toDefaultExpression("'abc'", DataType.varchar(10), false));
        assertEquals("a parenthesised expression default is left alone", "(uuid())",
                MySqlIntrospector.toDefaultExpression("(uuid())", DataType.charType(36), false));
        assertEquals("a numeric default is left alone", "5",
                MySqlIntrospector.toDefaultExpression("5", DataType.integer(), false));
        assertEquals("an expression default is left alone", "CURRENT_TIMESTAMP",
                MySqlIntrospector.toDefaultExpression("CURRENT_TIMESTAMP", DataType.timestamp(), true));

        /* catalog query sanity checks */
        assertTrue("tables query uses information_schema.tables and BASE TABLE",
                MySqlIntrospector.TABLES_SQL.contains("information_schema.tables")
                        && MySqlIntrospector.TABLES_SQL.contains("table_type = 'BASE TABLE'"));
        assertTrue("columns query reads column_type, extra and generation_expression",
                MySqlIntrospector.COLUMNS_SQL.contains("column_type")
                        && MySqlIntrospector.COLUMNS_SQL.contains("extra")
                        && MySqlIntrospector.COLUMNS_SQL.contains("generation_expression"));
        assertTrue("constraints query uses table_constraints",
                MySqlIntrospector.CONSTRAINTS_SQL.contains("information_schema.table_constraints"));
        assertTrue("key column usage query reads the referenced table and column",
                MySqlIntrospector.KEY_COLUMN_USAGE_SQL.contains("referenced_table_name")
                        && MySqlIntrospector.KEY_COLUMN_USAGE_SQL.contains("referenced_column_name"));
        assertTrue("referential constraints query reads both rules",
                MySqlIntrospector.REFERENTIAL_CONSTRAINTS_SQL.contains("update_rule")
                        && MySqlIntrospector.REFERENTIAL_CONSTRAINTS_SQL.contains("delete_rule"));
        assertTrue("check constraints query uses check_clause",
                MySqlIntrospector.CHECK_CONSTRAINTS_SQL.contains("check_clause"));
        assertTrue("indexes query uses information_schema.statistics and the functional expression column",
                MySqlIntrospector.INDEXES_SQL.contains("information_schema.statistics")
                        && MySqlIntrospector.INDEXES_SQL.contains("expression"));
        assertTrue("indexes fallback query omits the expression column",
                !MySqlIntrospector.INDEXES_WITHOUT_EXPRESSION_SQL.contains("expression"));
        assertTrue("views query reads view_definition and check_option",
                MySqlIntrospector.VIEWS_SQL.contains("view_definition")
                        && MySqlIntrospector.VIEWS_SQL.contains("check_option"));
    }

    private static void checkCapabilities(MySqlDialect dialect, MySqlIntrospector introspector) {
        System.out.println();
        System.out.println("-- capabilities --");
        System.out.println("  dialect features (" + dialect.getFeatures().getFeatures().size() + "):");
        for (DDLFeature feature : dialect.getFeatures().getFeatures()) {
            System.out.println("    " + feature);
        }
        System.out.println("  introspection capabilities: " + introspector.getCapabilities());
        assertEquals("introspection capability count", 19, introspector.getCapabilities().size());
        assertTrue("PARTIAL_INDEXES is not claimed (MySQL has no partial indexes)",
                !introspector.canIntrospect(
                        org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.PARTIAL_INDEXES));
        assertTrue("SEQUENCES is not claimed (MySQL has no sequences)",
                !introspector.canIntrospect(
                        org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.SEQUENCES));
        for (org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability capability : new org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability[] {
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.COLUMNS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.COLUMN_TYPES,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.NULLABILITY,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.DEFAULTS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.IDENTITY,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.GENERATED_COLUMNS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.PRIMARY_KEYS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.FOREIGN_KEYS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.FOREIGN_KEY_ACTIONS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.UNIQUE_CONSTRAINTS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.CHECK_CONSTRAINTS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.INDEXES,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.INDEX_UNIQUENESS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.INDEX_COLUMNS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.EXPRESSION_INDEXES,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.VIEWS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.VIEW_DEFINITIONS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.COMMENTS,
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.TABLE_EXISTENCE }) {
            assertTrue("capability reported: " + capability, introspector.canIntrospect(capability));
        }
    }

    /* ------------------------------------------------------------------ 4. live round trip */

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    private static void checkLiveRoundTrip(MySqlDialect dialect, MySqlIntrospector introspector) {
        String url = environment("DDLSMITH_MYSQL_URL", DEFAULT_URL);
        String user = environment("DDLSMITH_MYSQL_USER", DEFAULT_USER);
        String password = environment("DDLSMITH_MYSQL_PASSWORD", DEFAULT_PASSWORD);
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
        try (DDLSmithDBConnection dbConnection = new DDLSmithDBConnection(connection, LIVE_DATABASE)) {
            System.out.println("  server: " + dbConnection.getDatabaseProductName() + " "
                    + dbConnection.getDatabaseVersion());
            for (String setup : dialect.getSessionSetupStatements()) {
                dbConnection.executeStatement(setup);
            }
            dbConnection.executeStatement("DROP DATABASE IF EXISTS " + LIVE_DATABASE);
            dbConnection.executeStatement("CREATE DATABASE " + LIVE_DATABASE);
            dbConnection.executeStatement("USE " + LIVE_DATABASE);

            // The schema is put through the same adaptation the pipeline applies before it is rendered.
            // An engine limit that changes the schema on the way — InnoDB caps an index at 16 key parts
            // — is therefore exercised as the server will actually receive it, and a check the engine
            // refuses is dropped here rather than failing the CREATE TABLE below.
            List<String> adaptationNotes = new ArrayList<>();

            RelationalSchema intended = SchemaAdapter.adapt(buildLiveSchema(), dialect, adaptationNotes);
            for (String note : adaptationNotes) {
                System.out.println("  adapted: " + note);
            }
            for (Table table : intended.getTables()) {
                for (String sql : dialect.renderAll(new CreateTable(table))) {
                    System.out.println("  executing: " + sql);
                    dbConnection.executeStatement(sql);
                }
            }
            for (Table table : intended.getTables()) {
                for (Index index : table.getIndexes()) {
                    String sql = dialect.render(new CreateIndex(table.getName(), index));
                    System.out.println("  executing: " + sql);
                    dbConnection.executeStatement(sql);
                }
            }
            for (View view : intended.getViews()) {
                String sql = dialect.render(new CreateView(view, false, false));
                System.out.println("  executing: " + sql);
                dbConnection.executeStatement(sql);
            }

            RelationalSchema actual = introspector.introspect(dbConnection, LIVE_DATABASE);
            // MySQL cannot store UUID, ARRAY or INTERVAL columns; the documented lossy projection
            // is applied to the intended schema before comparing
            RelationalSchema projected = projectLossyTypes(intended);
            RelationalSchema erased = SchemaNormalizer.erase(projected, introspector.getCapabilities());
            List<SchemaDifference> differences = SchemaNormalizer.diff(erased, actual);
            System.out.println("  intended: " + erased.getStatistics());
            System.out.println("  actual:   " + actual.getStatistics());
            assertTrue("live schema round trip is empty:\n" + SchemaNormalizer.render(differences),
                    differences.isEmpty());
            checkCheckOnReferentialActionColumnRule(dbConnection);
            checkStoredGeneratedColumnRule(dbConnection);
            checkSelfReferentialCascadeRule(dbConnection);
            checkForeignKeyIndexNamingRule(introspector, dbConnection);
            checkForeignKeyIndexRebindingRule(dbConnection);
        } catch (SQLException e) {
            checks++;
            FAILURES.add("live round trip failed: " + e.getClass().getSimpleName() + ": " + oneLine(e.getMessage()));
        }
    }

    /**
     * Pins the name InnoDB gives the index it creates for a foreign key, and that the introspector
     * therefore leaves it out of the schema.
     *
     * <p>InnoDB needs an index over a foreign key's referencing columns and creates one when the schema
     * does not provide it, while PostgreSQL creates none — so one declared schema produced an index on
     * MySQL and none on PostgreSQL, and the differential oracle reported it as a missing index. What
     * identifies the index is its name: after the constraint when the foreign key was named, and after
     * the first referencing column when it was not. The second shape has a second case that the
     * recognition rule missed: when another index already carries that column's name, InnoDB appends a
     * counter. Measured on the live server with a foreign key on {@code (x, y)} whose first column
     * already has a {@code UNIQUE} index: the catalog holds {@code x} (the unique constraint) and
     * {@code x_2} (the foreign key's), and the differential oracle reported
     * {@code MISSING_INDEX at t_0.idx([e0_dst_a0, e0_dst_a2])} in a 60-minute round because only the
     * bare column name was matched.</p>
     *
     * <p>The control matters as much as the rule: an index DDLSmith really asked for must still be
     * reported, or the fix would be "drop every index that sits on a foreign key's columns".</p>
     */
    private static void checkForeignKeyIndexNamingRule(MySqlIntrospector introspector,
            DDLSmithDBConnection connection) throws SQLException {
        System.out.println();
        System.out.println("-- the index InnoDB creates for a foreign key --");
        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("CREATE DATABASE " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + RULE_PROBE_DATABASE);
        connection.executeStatement("CREATE TABLE t (a DATE NOT NULL, b CHAR(5) NOT NULL, x DATE, y CHAR(5), "
                + "PRIMARY KEY (a, b), UNIQUE (x), FOREIGN KEY (x, y) REFERENCES t (a, b))");
        connection.executeStatement("CREATE INDEX idx_t_0 ON t (b)");
        Object raw = connection.executeAndGet("SELECT GROUP_CONCAT(index_name ORDER BY index_name) "
                + "FROM information_schema.statistics WHERE table_schema = '" + RULE_PROBE_DATABASE
                + "' AND table_name = 't'").getValue(0, 0);
        System.out.println("  index names in the catalog: " + raw);
        assertTrue("InnoDB suffixes the foreign key's index name with a counter when the first "
                + "referencing column's name is already taken by another index (catalog: " + raw + ")",
                String.valueOf(raw).contains("x_2"));

        RelationalSchema actual = introspector.introspect(connection, RULE_PROBE_DATABASE);
        Table table = actual.getTable("t");
        List<String> reported = new ArrayList<>();
        for (Index index : table.getIndexes()) {
            reported.add(index.getName());
        }
        System.out.println("  indexes the introspector reports: " + reported);
        for (String name : reported) {
            assertTrue("the index InnoDB created for the foreign key (" + name + ") is not part of what "
                    + "the schema declared", !name.equalsIgnoreCase("x") && !name.equalsIgnoreCase("x_2"));
        }
        assertTrue("an index DDLSmith really created is still reported (reported: " + reported + ")",
                reported.contains("idx_t_0"));

        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + LIVE_DATABASE);
    }

    /**
     * Pins the foreign key MySQL stops enforcing after an index is created over two foreign key
     * columns — the one engine defect this project found rather than caused.
     *
     * <p>Measured on 26.7.0. With two foreign keys on the same child table and an index whose leading
     * columns are those two key columns, an insert that leaves the <em>second</em> indexed column NULL
     * is no longer checked against the <em>first</em> foreign key: the row is stored with a dangling
     * reference. Every catalog still reports the constraint — {@code TABLE_CONSTRAINTS},
     * {@code REFERENTIAL_CONSTRAINTS}, {@code KEY_COLUMN_USAGE} and InnoDB's own
     * {@code INNODB_FOREIGN} — and InnoDB even refuses to drop the index that "is needed in a foreign
     * key constraint", so the catalog cannot be used to detect it. That is the failure mode DDLSmith
     * exists to find: a constraint that is parsed, stored and never enforced.</p>
     *
     * <p>The probe asserts the defect rather than the standard, so that a MySQL version which starts
     * enforcing the constraint fails this self-check and says so, instead of the
     * {@code constraint-enforcement} oracle silently going quiet. The controls keep the assertion from
     * passing for the wrong reason: the same dangling value with a non-NULL second column is rejected
     * both before and after the index, and a dangling <em>second</em> column is rejected after it.</p>
     */
    private static void checkForeignKeyIndexRebindingRule(DDLSmithDBConnection connection) throws SQLException {
        System.out.println();
        System.out.println("-- foreign key enforcement after an index over two key columns --");
        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("CREATE DATABASE " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + RULE_PROBE_DATABASE);
        connection.executeStatement("CREATE TABLE p1 (k INT NOT NULL, PRIMARY KEY (k))");
        connection.executeStatement("CREATE TABLE p2 (k INT NOT NULL, PRIMARY KEY (k))");
        connection.executeStatement("CREATE TABLE c (id INT NOT NULL, f1 INT, f2 INT, PRIMARY KEY (id), "
                + "CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES p1 (k), "
                + "CONSTRAINT c2 FOREIGN KEY (f2) REFERENCES p2 (k))");
        connection.executeStatement("INSERT INTO p1 VALUES (1)");
        connection.executeStatement("INSERT INTO p2 VALUES (1)");
        assertEquals("before the index, a dangling f1 with a NULL f2 is rejected (1452)", 1452,
                errorCodeOf(connection, "INSERT INTO c VALUES (10, 999, NULL)"));
        connection.executeStatement("CREATE INDEX i ON c (f1, f2)");
        Integer danglingWithNull = errorCodeOf(connection, "INSERT INTO c VALUES (11, 999, NULL)");
        System.out.println("  after CREATE INDEX i ON c (f1, f2), a dangling f1 with f2 NULL -> "
                + (danglingWithNull == null ? "accepted (the foreign key is not enforced)" : "refused " + danglingWithNull));
        assertEquals("MySQL 26.7.0 does not enforce the first foreign key for a row whose second key "
                + "column is NULL once an index covers both key columns; a version that starts enforcing "
                + "it makes this assertion fail, which is the point", null, danglingWithNull);
        assertEquals("the control: the same dangling value with a non-NULL f2 is still rejected (1452)", 1452,
                errorCodeOf(connection, "INSERT INTO c VALUES (12, 999, 1)"));
        assertEquals("the control: a dangling f2 is still rejected (1452)", 1452,
                errorCodeOf(connection, "INSERT INTO c VALUES (13, NULL, 999)"));
        Object stored = connection.executeAndGet("SELECT COUNT(*) FROM c WHERE f1 = 999").getValue(0, 0);
        System.out.println("  rows stored with a dangling f1: " + stored);
        assertEquals("the row with the dangling reference really is in the table", 1L,
                stored instanceof Number ? ((Number) stored).longValue() : stored);

        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + LIVE_DATABASE);
    }

    /** @return the error code the server answered with, or {@code null} when it accepted the statement */
    private static Integer errorCodeOf(DDLSmithDBConnection connection, String sql) {
        try {
            connection.executeStatement(sql);
            return null;
        } catch (SQLException e) {
            return e.getErrorCode();
        }
    }

    /**
     * Asks the server which referential actions make it refuse a {@code CHECK} on the affected column,
     * and asserts that {@code SchemaAdapter.writesThroughReferentialAction} answers the same way.
     *
     * <p>Error 3823 fires for exactly the actions that <em>write</em> the child column, and "exactly"
     * is the point: a rule one action too wide silently drops a check the server would have accepted,
     * and one action too narrow makes the whole {@code CREATE TABLE} fail. The omission of
     * {@code ON UPDATE CASCADE} from that predicate cost 327 defect units of a 1000-database census —
     * the crashes themselves and every {@code MISSING_TABLE}, {@code MISSING_VIEW} and
     * {@code MISSING_FOREIGN_KEY} cascading from the tables it prevented from being created. The rule
     * is therefore derived here from the engine rather than from the error message, one action at a
     * time, and the asserted error code keeps a refusal for some other reason from passing as
     * agreement.</p>
     */
    private static void checkCheckOnReferentialActionColumnRule(DDLSmithDBConnection connection)
            throws SQLException {
        System.out.println();
        System.out.println("-- which referential actions make a CHECK illegal --");
        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("CREATE DATABASE " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + RULE_PROBE_DATABASE);
        connection.executeStatement("CREATE TABLE p (k INT PRIMARY KEY)");
        // every action MySQL understands on a column that also carries a CHECK, and whether the server
        // is expected to write that column. RESTRICT and NO ACTION do not, and neither does a cascading
        // DELETE, which removes the row instead of changing it.
        String[][] cases = {
                { "ON UPDATE CASCADE", "yes" },
                { "ON UPDATE SET NULL", "yes" },
                { "ON UPDATE SET DEFAULT", "yes" },
                { "ON DELETE SET NULL", "yes" },
                { "ON DELETE SET DEFAULT", "yes" },
                { "ON DELETE CASCADE", "no" },
                { "ON DELETE RESTRICT", "no" },
                { "ON UPDATE RESTRICT", "no" },
                { "ON UPDATE NO ACTION", "no" } };
        int index = 0;
        for (String[] testCase : cases) {
            String action = testCase[0];
            boolean expectRefusal = "yes".equals(testCase[1]);
            String probe = "probe_" + index++;
            // a DEFAULT is required for SET DEFAULT to be legal syntax at all, so every case carries one
            String sql = "CREATE TABLE " + probe + " (c INT DEFAULT 0, CONSTRAINT ck_" + probe + " CHECK (c <> 1), "
                    + "CONSTRAINT fk_" + probe + " FOREIGN KEY (c) REFERENCES p (k) " + action + ")";
            Integer errorCode = null;
            try {
                connection.executeStatement(sql);
            } catch (SQLException e) {
                errorCode = e.getErrorCode();
            }
            System.out.println("  " + action + " -> " + (errorCode == null ? "accepted" : "refused " + errorCode)
                    + (expectRefusal == (errorCode != null) ? "" : "   <-- unexpected"));
            if (expectRefusal) {
                assertEquals("MySQL refuses a CHECK on a column written by " + action + " with error 3823",
                        3823, errorCode);
            } else {
                assertEquals("MySQL accepts a CHECK on a column that " + action + " does not write", null,
                        errorCode);
            }
        }
        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + LIVE_DATABASE);
    }

    /**
     * Records the second restriction that kept the live round trip red: a {@code STORED} generated
     * column may not read a column that one of the table's own foreign keys maintains, for a
     * <em>measured</em> set of referential actions.
     *
     * <p>MySQL answers error 1215 ("Cannot add foreign key constraint") and the message names neither
     * the generated column nor the action, so the cause is invisible from the outside: the identical
     * statement is accepted with {@code VIRTUAL} instead of {@code STORED}, with an accepted action
     * instead of a refused one, or with the expression based on any other column. The hand-built live
     * schema used {@code pid * 2} with a cascading foreign key on {@code pid}, which is why this
     * self-check never reached its round-trip assertion. No generated database of the 923-database
     * MySQL census produced 1215, so the note here used to say the generator does not build this
     * combination — the twelve-hour campaign of 2026-10-05 disproved that (1 database in 1000, seed
     * 9100308), which is why the matrix below is now complete rather than two cases wide.</p>
     *
     * <p>The refused set is {@code {CASCADE, SET NULL} × {DELETE, UPDATE}} — symmetric, and narrower
     * than the set that governs {@code CHECK} constraints (error 3823), which also refuses both
     * {@code SET DEFAULT} actions. It therefore cannot be derived from "does this action write the
     * column", and the generator's guard has to name the set measured here. A {@code SET NULL} probe
     * needs a <em>nullable</em> foreign key column, or MySQL refuses the statement for an unrelated
     * reason and the probe would report the wrong cause; a {@code SET DEFAULT} probe needs a default for
     * the same reason. The foreign key column is therefore {@code pid} and the primary key is a separate
     * {@code id} column — a {@code PRIMARY KEY (pid)} would make {@code pid} implicitly {@code NOT NULL}
     * and MySQL would answer error 1171 ("foreign key constraint ... set null") instead of the 1215 this
     * check is about. That mistake was made here once, and the probe then reported four actions as
     * "unexpected" while measuring something else entirely.</p>
     */
    private static void checkStoredGeneratedColumnRule(DDLSmithDBConnection connection) throws SQLException {
        System.out.println();
        System.out.println("-- STORED generated column over a column a foreign key maintains --");
        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("CREATE DATABASE " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + RULE_PROBE_DATABASE);
        connection.executeStatement("CREATE TABLE p (id INT NOT NULL, PRIMARY KEY (id))");
        // expression, generated kind, referential action, nullable FK column?, whether MySQL refuses
        String[][] cases = {
                { "qty * 2", "STORED", "ON DELETE CASCADE", "no", "no" },
                { "pid * 2", "STORED", "ON DELETE CASCADE", "no", "yes" },
                { "pid * 2", "VIRTUAL", "ON DELETE CASCADE", "no", "no" },
                { "pid * 2", "STORED", "ON UPDATE CASCADE", "no", "yes" },
                { "pid * 2", "STORED", "ON DELETE SET NULL", "yes", "yes" },
                { "pid * 2", "STORED", "ON UPDATE SET NULL", "yes", "yes" },
                { "pid * 2", "STORED", "ON DELETE SET DEFAULT", "yes", "no" },
                { "pid * 2", "STORED", "ON UPDATE SET DEFAULT", "yes", "no" },
                { "pid * 2", "STORED", "ON DELETE RESTRICT", "no", "no" },
                { "pid * 2", "STORED", "ON UPDATE RESTRICT", "no", "no" },
                { "pid * 2", "STORED", "ON DELETE NO ACTION", "no", "no" },
                { "pid * 2", "STORED", "ON UPDATE NO ACTION", "no", "no" },
                { "pid * 2", "STORED", "", "no", "no" } };
        int index = 0;
        for (String[] testCase : cases) {
            String expression = testCase[0];
            String kind = testCase[1];
            String action = testCase[2];
            boolean nullableKey = "yes".equals(testCase[3]);
            boolean expectRefusal = "yes".equals(testCase[4]);
            String probe = "probe_" + index++;
            // `id` is the primary key and `pid` the foreign key column, so that `pid` may be nullable
            // for the SET NULL probes without colliding with the implicit NOT NULL of a key column
            String sql = "CREATE TABLE " + probe + " (id INT NOT NULL, pid INT "
                    + (nullableKey ? "NULL" : "NOT NULL")
                    + (action.contains("SET DEFAULT") ? " DEFAULT 7" : "")
                    + ", qty INT, g DECIMAL(12,4) GENERATED ALWAYS AS (" + expression + ") " + kind
                    + ", PRIMARY KEY (id), CONSTRAINT fk_" + probe
                    + " FOREIGN KEY (pid) REFERENCES p (id) " + action + ")";
            Integer errorCode = null;
            try {
                connection.executeStatement(sql);
            } catch (SQLException e) {
                errorCode = e.getErrorCode();
            }
            String label = kind + " over " + (expression.startsWith("pid") ? "the foreign key column" : "a column")
                    + (action.isEmpty() ? ", no action clause" : ", " + action);
            System.out.println("  " + label + " -> "
                    + (errorCode == null ? "accepted" : "refused " + errorCode)
                    + (expectRefusal == (errorCode != null) ? "" : "   <-- unexpected"));
            if (expectRefusal) {
                assertEquals("MySQL refuses a foreign key with " + label + " with error 1215", 1215, errorCode);
            } else {
                assertEquals("MySQL accepts a foreign key with " + label, null, errorCode);
            }
        }
        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + LIVE_DATABASE);
    }

    /**
     * Asks the server what happens when the key of a row is updated while a <em>self-referencing</em>
     * foreign key points at it — the rule behind the two cascading-action findings of the MySQL
     * campaign.
     *
     * <p>MySQL accepts {@code ON UPDATE CASCADE} and {@code ON UPDATE SET NULL} on a foreign key that
     * references its own table, and then behaves like {@code RESTRICT}. The manual documents it under
     * "FOREIGN KEY Constraint Differences" as a deviation from SQL standards: <i>"If
     * {@code ON UPDATE CASCADE} or {@code ON UPDATE SET NULL} recurses to update the same table it has
     * previously updated during the same cascade, it acts like {@code RESTRICT}. This means that you
     * cannot use self-referential {@code ON UPDATE CASCADE} or {@code ON UPDATE SET NULL}
     * operations."</i> A generated database of the campaign therefore reported "ON UPDATE CASCADE was
     * declared but the update was rejected" with error 1451 and no hint of the cause, and the first
     * reading of that log blamed the row generator instead.</p>
     *
     * <p>The probe asks the question one action at a time, so the rule is derived from the engine
     * rather than from the error message, and it pins the deviation: if a later MySQL version starts
     * cascading, this self-check says so instead of the referential-action oracle reporting it as a
     * fresh engine defect. The two controls show that the rule is about the recursion and not about
     * the action — the same declaration is honoured when no child row references the parent row, and a
     * foreign key to another table cascades normally.</p>
     */
    private static void checkSelfReferentialCascadeRule(DDLSmithDBConnection connection) throws SQLException {
        System.out.println();
        System.out.println("-- updating the key of a row a self-referencing foreign key points at --");
        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("CREATE DATABASE " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + RULE_PROBE_DATABASE);
        // every case has one child row pointing at row 1, and every action is declared on a
        // self-referencing foreign key
        String[] actions = { "", "ON UPDATE RESTRICT", "ON UPDATE CASCADE", "ON UPDATE SET NULL",
                "ON UPDATE NO ACTION" };
        int index = 0;
        for (String action : actions) {
            String label = action.isEmpty() ? "no action clause" : action;
            String probe = "probe_" + index++;
            connection.executeStatement("CREATE TABLE " + probe + " (id INT NOT NULL, r INT, PRIMARY KEY (id), "
                    + "CONSTRAINT fk_" + probe + " FOREIGN KEY (r) REFERENCES " + probe + " (id)"
                    + (action.isEmpty() ? "" : " " + action) + ")");
            connection.executeStatement("INSERT INTO " + probe + " VALUES (1, NULL), (2, 1)");
            Integer errorCode = null;
            try {
                connection.executeStatement("UPDATE " + probe + " SET id = 1000 WHERE id = 1");
            } catch (SQLException e) {
                errorCode = e.getErrorCode();
            }
            System.out.println("  self-referencing, " + label + " -> "
                    + (errorCode == null ? "accepted" : "refused " + errorCode));
            assertEquals("MySQL refuses to move a key a self-referencing foreign key points at (" + label
                    + ") with error 1451", 1451, errorCode);
        }
        connection.executeStatement("CREATE TABLE alone (id INT NOT NULL, r INT, PRIMARY KEY (id), "
                + "CONSTRAINT fk_alone FOREIGN KEY (r) REFERENCES alone (id) ON UPDATE CASCADE)");
        connection.executeStatement("INSERT INTO alone VALUES (1, NULL)");
        Integer aloneError = null;
        try {
            connection.executeStatement("UPDATE alone SET id = 2000 WHERE id = 1");
        } catch (SQLException e) {
            aloneError = e.getErrorCode();
        }
        System.out.println("  self-referencing, ON UPDATE CASCADE, no child row -> "
                + (aloneError == null ? "accepted" : "refused " + aloneError));
        assertEquals("MySQL accepts the update when no child row references the parent row", null, aloneError);

        connection.executeStatement("CREATE TABLE par (id INT NOT NULL, PRIMARY KEY (id))");
        connection.executeStatement("CREATE TABLE other (id INT NOT NULL, r INT, PRIMARY KEY (id), "
                + "CONSTRAINT fk_other FOREIGN KEY (r) REFERENCES par (id) ON UPDATE CASCADE)");
        connection.executeStatement("INSERT INTO par VALUES (1)");
        connection.executeStatement("INSERT INTO other VALUES (2, 1)");
        connection.executeStatement("UPDATE par SET id = 3000 WHERE id = 1");
        Object cascaded = connection.executeAndGet("SELECT COUNT(*) FROM other WHERE r = 3000").getValue(0, 0);
        assertEquals("a foreign key to another table still cascades the update", 1L,
                cascaded instanceof Number ? ((Number) cascaded).longValue() : cascaded);

        connection.executeStatement("DROP DATABASE IF EXISTS " + RULE_PROBE_DATABASE);
        connection.executeStatement("USE " + LIVE_DATABASE);
    }

    /**
     * Rewrites the documented lossy type aliases to what MySQL can actually store, so that the
     * round-trip oracle compares like with like.
     */
    static RelationalSchema projectLossyTypes(RelationalSchema schema) {
        RelationalSchema projected = schema.copy();
        for (Table table : projected.getTables()) {
            for (Column column : table.getColumns()) {
                switch (column.getType().getCategory()) {
                case UUID:
                    column.setType(DataType.charType(36));
                    break;
                case ARRAY:
                    column.setType(DataType.json());
                    break;
                case INTERVAL:
                    column.setType(DataType.time());
                    break;
                default:
                    break;
                }
            }
        }
        return projected;
    }

    /** A small, self-consistent schema that MySQL must accept and report back unchanged. */
    private static RelationalSchema buildLiveSchema() {
        RelationalSchema schema = new RelationalSchema(LIVE_DATABASE);

        Table parent = new Table(LIVE_DATABASE, "parent");
        parent.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        parent.addColumn(new Column("code", DataType.varchar(10)).setNullable(false));
        parent.addColumn(new Column("amount", DataType.decimal(10, 2)).setDefaultExpression("0"));
        parent.addColumn(new Column("doc", DataType.uuid()));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        parent.addUniqueConstraint(new UniqueConstraint("uq_parent_code", List.of("code")));
        parent.addCheckConstraint(new CheckConstraint("ck_parent_amount", "amount >= 0"));
        // MySQL parenthesises a negative numeric literal in the clause it reports back, so this is what
        // pins the `-(1)` normalisation; without it a wrongly reported MISSING_CHECK_CONSTRAINT cannot
        // be seen from inside the module.
        parent.addCheckConstraint(new CheckConstraint("ck_parent_floor", "amount >= -1"));
        parent.setComment("parent table");
        parent.getColumn("id").setComment("identifier");
        // A unique index that is not a unique constraint. MySQL reports it in table_constraints with the
        // index's own name and leaves no index behind, so the round trip used to lose it entirely: the
        // schema below had no unique index at all, which is why every generated database reported
        // MISSING_INDEX and no self-check ever contradicted them.
        parent.addIndex(new Index("uidx_parent_amount", List.of("amount")).setUnique(true));
        schema.addTable(parent);

        Table child = new Table(LIVE_DATABASE, "child");
        child.addColumn(new Column("pid", DataType.integer()).setNullable(false));
        child.addColumn(new Column("seq", DataType.smallint()).setNullable(false));
        child.addColumn(new Column("label", DataType.text()));
        child.addColumn(new Column("status", DataType.varchar(10)));
        child.addColumn(new Column("qty", DataType.integer()));
        // The generated column must not read a column of a foreign key. MySQL refuses the foreign key
        // with error 1215 ("Cannot add foreign key constraint") when a STORED generated column depends
        // on a column that a CASCADE referential action maintains; a VIRTUAL one is accepted, and so is
        // a STORED one based on any other column. `pid * 2` here is what kept this live check red before
        // anything else could be seen — see checkStoredGeneratedColumnRule, which pins the rule.
        child.addColumn(new Column("total", DataType.decimal(12, 4)).setGeneratedExpression("qty * 2")
                .setGeneratedStored(true));
        child.addColumn(new Column("created", DataType.timestamp()).setDefaultExpression("CURRENT_TIMESTAMP"));
        child.setPrimaryKey(new PrimaryKey(List.of("pid", "seq")));
        child.addForeignKey(new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_child_parent")
                .setOnDelete(ReferentialAction.CASCADE));
        child.addIndex(new Index("idx_child_label", List.of("label")));
        // MySQL prefixes a string literal with its character set in the clause it reports back
        // (`_utf8mb4'none'`), which is the other half of the MISSING_CHECK_CONSTRAINT mass.
        child.addCheckConstraint(new CheckConstraint("ck_child_status", "status <> 'none'"));
        schema.addTable(child);

        Table node = new Table(LIVE_DATABASE, "node");
        node.addColumn(new Column("id", DataType.integer()).setNullable(false));
        node.addColumn(new Column("parent_id", DataType.integer()));
        node.setPrimaryKey(new PrimaryKey(List.of("id")));
        node.addForeignKey(new ForeignKey(List.of("parent_id"), "node", List.of("id")).setName("fk_node_parent")
                .setSelfReferential(true).setOnDelete(ReferentialAction.SET_NULL));
        schema.addTable(node);

        // InnoDB answers "Too many key parts specified; max 16 parts allowed" (error 1070) for this
        // index, so it can only be created because SchemaAdapter caps it at EngineTraits.maxIndexColumns.
        // Eighteen plain integers keep the key under InnoDB's byte limit, so the part count is the only
        // reason the server can have for refusing it.
        Table wide = new Table(LIVE_DATABASE, "wide");
        List<String> wideColumns = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            // w0 is the primary key, and MySQL stores a primary key column as NOT NULL whatever the
            // model says, so the model has to say it too
            wide.addColumn(new Column("w" + i, DataType.integer()).setNullable(i != 0));
            wideColumns.add("w" + i);
        }
        wide.setPrimaryKey(new PrimaryKey("pk_wide", List.of("w0")));
        wide.addIndex(new Index("idx_wide_all", wideColumns));
        schema.addTable(wide);

        View view = new View("v_parent", "SELECT id, code FROM parent");
        view.setSchemaName(LIVE_DATABASE);
        schema.addView(view);
        return schema;
    }

    private static String oneLine(String message) {
        return message == null ? "<none>" : message.replace('\n', ' ').replace('\r', ' ');
    }
}
