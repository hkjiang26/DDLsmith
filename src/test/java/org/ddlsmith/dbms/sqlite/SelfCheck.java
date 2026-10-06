package org.ddlsmith.dbms.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

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
import org.ddlsmith.ddl.gen.SchemaAdapter;
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
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaIntrospector;
import org.ddlsmith.introspection.SchemaNormalizer;

/**
 * Executable self check for the SQLite module. It lives in the test source root (it is not part of
 * the production build) and is deliberately a {@code main} rather than a JUnit test: it connects to
 * a real in-memory SQLite database, runs the DDL the dialect renders, reads the schema back with the
 * introspector and reports both the round trip and a handful of documented engine behaviours that
 * DDLSmith is meant to surface.
 *
 * <p>Run it with:</p>
 * <pre>
 * .\compile-subset.ps1 -Path 'src\main\java\org\ddlsmith\dbms\sqlite' -OutDir 'target\sqlite-classes'
 * .\compile-subset.ps1 -Path 'src\test\java\org\ddlsmith\dbms\sqlite' -OutDir 'target\sqlite-test-classes'
 * java -cp "target\sqlite-test-classes;target\sqlite-classes;target\contract-classes;$(cat target/cp.txt)" `
 *      org.ddlsmith.dbms.sqlite.SelfCheck
 * </pre>
 *
 * <p>Exit code 0 means every assertion passed; the "engine behaviour" section is informational and
 * never fails the check.</p>
 */
public final class SelfCheck {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int assertions;

    private SelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== DDLSmith SQLite module self check ===");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            System.out.println("sqlite version: " + connection.getMetaData().getDatabaseProductVersion());
        }
        System.out.println();

        SqliteDialect dialect = new SqliteDialect();
        checkTypeRoundTrip(dialect);
        checkTypeParsing(dialect);
        checkFeatureGating(dialect);
        checkAlterActions(dialect);
        checkLiterals(dialect);
        checkSchemaRoundTrip(dialect);
        checkEffectiveType(dialect);
        checkGeneratedColumns(dialect);
        checkReservedWordRenaming(dialect);
        checkTemporarySchema(dialect);
        checkAlterRoundTrip(dialect);
        checkForeignKeyEnforcement(dialect);
        printEngineBehaviours(dialect);

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("=== ALL " + assertions + " ASSERTIONS PASSED ===");
        } else {
            System.out.println("=== " + FAILURES.size() + " OF " + assertions + " ASSERTIONS FAILED ===");
            for (String failure : FAILURES) {
                System.out.println("  FAILED: " + failure);
            }
            System.exit(1);
        }
    }

    /* ------------------------------------------------------------------ assertions */

    private static void check(String what, boolean condition) {
        assertions++;
        if (condition) {
            System.out.println("  ok   " + what);
        } else {
            FAILURES.add(what);
            System.out.println("  FAIL " + what);
        }
    }

    private static void assertEquals(String what, Object expected, Object actual) {
        assertions++;
        boolean equal = expected == null ? actual == null : expected.equals(actual);
        if (equal) {
            System.out.println("  ok   " + what + " -> " + actual);
        } else {
            FAILURES.add(what + ": expected <" + expected + "> but was <" + actual + ">");
            System.out.println("  FAIL " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    /* ------------------------------------------------------------------ 1. type round trip */

    private static void checkTypeRoundTrip(SqliteDialect dialect) {
        System.out.println("--- type round trip (render -> parse) for every type the dialect declares");
        SqliteTypeMapper mapper = dialect.getTypeMapper();
        Object[][] supported = {
                { DataType.smallint(), null },
                { DataType.integer(), null },
                { DataType.bigint(), null },
                { DataType.decimal(10, 2), DDLFeature.TYPE_NUMERIC_PRECISION },
                { DataType.decimal(6, 0), DDLFeature.TYPE_NUMERIC_PRECISION },
                { DataType.real(), null },
                { DataType.doublePrecision(), null },
                { DataType.charType(5), DDLFeature.TYPE_CHAR_LENGTH },
                { DataType.charType(1), DDLFeature.TYPE_CHAR_LENGTH },
                { DataType.varchar(20), DDLFeature.TYPE_CHAR_LENGTH },
                { DataType.varchar(255), DDLFeature.TYPE_CHAR_LENGTH },
                { DataType.text(), DDLFeature.TYPE_TEXT },
                { DataType.bool(), DDLFeature.TYPE_BOOLEAN },
                { DataType.date(), DDLFeature.TYPE_DATE },
                { DataType.time(), DDLFeature.TYPE_TIME },
                { DataType.timestamp(), DDLFeature.TYPE_TIMESTAMP },
                { DataType.blob(), DDLFeature.TYPE_BLOB } };
        for (Object[] entry : supported) {
            DataType type = (DataType) entry[0];
            DDLFeature required = (DDLFeature) entry[1];
            if (required != null) {
                check("dialect declares " + required + " (needed by " + type.getName() + ")",
                        dialect.getFeatures().supports(required));
            }
            String rendered = mapper.render(type);
            DataType parsed = mapper.parse(rendered);
            assertEquals("round trip " + type.getName(), type, parsed);
        }

        System.out.println();
        System.out.println("--- lossy types: SQLite has no such type, so the feature must be absent");
        Object[][] lossy = {
                { DataType.timestampWithTimeZone(), DDLFeature.TYPE_TIMESTAMPTZ },
                { DataType.json(), DDLFeature.TYPE_JSON },
                { DataType.uuid(), DDLFeature.TYPE_UUID },
                { DataType.interval(), null },
                { DataType.arrayOf(DataType.integer()), DDLFeature.TYPE_ARRAY },
                { DataType.enumOf(Arrays.asList("a", "b")), DDLFeature.TYPE_ENUM } };
        for (Object[] entry : lossy) {
            DataType type = (DataType) entry[0];
            DDLFeature feature = (DDLFeature) entry[1];
            if (feature != null) {
                check("dialect does not declare " + feature + " (" + type.getName() + ")",
                        !dialect.getFeatures().supports(feature));
            }
            String rendered = mapper.render(type);
            DataType parsed = mapper.parse(rendered);
            check("lossy mapping " + type.getName() + " -> " + rendered + " -> " + parsed,
                    parsed != null && !parsed.equals(type));
            System.out.println("       " + type.getName() + " is stored as " + rendered + " (lossy on purpose)");
        }

        System.out.println();
        System.out.println("--- render is total over every DataType category");
        for (DataType.Category category : DataType.Category.values()) {
            DataType type = representative(category);
            String rendered;
            try {
                rendered = mapper.render(type);
            } catch (RuntimeException e) {
                rendered = null;
            }
            check("render(" + category + ") does not throw", rendered != null);
            try {
                mapper.parse(rendered);
                check("parse(render(" + category + ")) does not throw", true);
            } catch (RuntimeException e) {
                check("parse(render(" + category + ")) does not throw", false);
            }
        }
        System.out.println();
    }

    private static DataType representative(DataType.Category category) {
        switch (category) {
        case CHAR:
            return DataType.charType(3);
        case VARCHAR:
            return DataType.varchar(3);
        case DECIMAL:
            return DataType.decimal(5, 2);
        case ARRAY:
            return DataType.arrayOf(DataType.integer());
        case ENUM:
            return DataType.enumOf(Arrays.asList("x", "y"));
        default:
            return DataType.of(category);
        }
    }

    private static void checkTypeParsing(SqliteDialect dialect) {
        System.out.println("--- declared type parsing tolerance");
        SqliteTypeMapper mapper = dialect.getTypeMapper();
        assertEquals("int", DataType.integer(), mapper.parse("int"));
        assertEquals("INTEGER", DataType.integer(), mapper.parse("INTEGER"));
        assertEquals("Integer ", DataType.integer(), mapper.parse(" Integer "));
        assertEquals("int2", DataType.smallint(), mapper.parse("int2"));
        assertEquals("int8", DataType.bigint(), mapper.parse("int8"));
        assertEquals("varchar (no length)", DataType.varchar(255), mapper.parse("varchar"));
        assertEquals("VARCHAR(20)", DataType.varchar(20), mapper.parse("VARCHAR(20)"));
        assertEquals("character varying(30)", DataType.varchar(30), mapper.parse("character varying(30)"));
        assertEquals("CHARACTER VARYING (7)", DataType.varchar(7), mapper.parse("CHARACTER VARYING (7)"));
        assertEquals("char", DataType.charType(1), mapper.parse("char"));
        assertEquals("numeric(8,3)", DataType.decimal(8, 3), mapper.parse("numeric(8,3)"));
        assertEquals("NUMERIC", DataType.decimal(10, 0), mapper.parse("NUMERIC"));
        assertEquals("double precision", DataType.doublePrecision(), mapper.parse("double precision"));
        assertEquals("float", DataType.real(), mapper.parse("float"));
        assertEquals("bool", DataType.bool(), mapper.parse("bool"));
        assertEquals("clob", DataType.text(), mapper.parse("clob"));
        assertEquals("json", DataType.json(), mapper.parse("json"));
        assertEquals("unknown declared type", null, mapper.parse("SOMETYPE"));
        assertEquals("empty declared type", null, mapper.parse(""));
        assertEquals("null declared type", null, mapper.parse(null));
        assertEquals("affinity of SOMETYPE", DataType.decimal(10, 0), mapper.affinityOf("SOMETYPE"));
        assertEquals("affinity of empty", DataType.blob(), mapper.affinityOf(""));
        assertEquals("affinity of MONEY", DataType.decimal(10, 0), mapper.affinityOf("MONEY"));
        // SQLite's affinity rule looks for "INT" anywhere in the declared type
        assertEquals("affinity of POINT", DataType.integer(), mapper.affinityOf("POINT"));
        System.out.println();
    }

    /* ------------------------------------------------------------------ 2. feature gating */

    private static void checkFeatureGating(SqliteDialect dialect) {
        System.out.println("--- feature gating");
        List<DDLFeature> required = Arrays.asList(DDLFeature.IF_NOT_EXISTS, DDLFeature.DROP_IF_EXISTS,
                DDLFeature.CASCADE_DROP, DDLFeature.TEMPORARY_TABLE, DDLFeature.COMPOSITE_PRIMARY_KEY,
                DDLFeature.NAMED_PRIMARY_KEY, DDLFeature.COMPOSITE_FOREIGN_KEY, DDLFeature.COMPOSITE_UNIQUE,
                DDLFeature.NAMED_UNIQUE, DDLFeature.UNIQUE_CONSTRAINT, DDLFeature.NAMED_CHECK,
                DDLFeature.SELF_REFERENTIAL_FK, DDLFeature.NAMED_FOREIGN_KEY,
                DDLFeature.TABLE_LEVEL_FOREIGN_KEY, DDLFeature.INLINE_UNIQUE,
                DDLFeature.INLINE_CHECK, DDLFeature.COLUMN_LEVEL_PRIMARY_KEY, DDLFeature.FK_ON_DELETE,
                DDLFeature.FK_ON_UPDATE, DDLFeature.FK_CASCADE, DDLFeature.FK_SET_NULL,
                DDLFeature.FK_SET_DEFAULT, DDLFeature.FK_RESTRICT, DDLFeature.FK_ENFORCED_BY_PRAGMA,
                DDLFeature.DEFAULT_LITERAL, DDLFeature.DEFAULT_EXPRESSION,
                DDLFeature.DEFAULT_CURRENT_TIMESTAMP, DDLFeature.NOT_NULL_COLUMN, DDLFeature.AUTO_INCREMENT,
                DDLFeature.GENERATED_COLUMN, DDLFeature.GENERATED_VIRTUAL, DDLFeature.GENERATED_STORED,
                DDLFeature.COLLATION, DDLFeature.NULLS_DISTINCT, DDLFeature.ALTER_ADD_COLUMN,
                DDLFeature.ALTER_DROP_COLUMN, DDLFeature.ALTER_RENAME_COLUMN, DDLFeature.ALTER_RENAME_TABLE,
                DDLFeature.CREATE_INDEX, DDLFeature.CREATE_UNIQUE_INDEX,
                DDLFeature.CREATE_INDEX_IF_NOT_EXISTS, DDLFeature.DROP_INDEX, DDLFeature.DROP_INDEX_IF_EXISTS,
                DDLFeature.PARTIAL_INDEX, DDLFeature.EXPRESSION_INDEX, DDLFeature.CREATE_VIEW,
                DDLFeature.VIEW_COLUMN_LIST, DDLFeature.TYPE_TEXT, DDLFeature.TYPE_BLOB,
                DDLFeature.TYPE_NUMERIC_PRECISION, DDLFeature.TYPE_CHAR_LENGTH, DDLFeature.TYPE_TIMESTAMP,
                DDLFeature.TYPE_DATE, DDLFeature.TYPE_TIME, DDLFeature.TYPE_BOOLEAN);
        for (DDLFeature feature : required) {
            check("supported: " + feature, dialect.getFeatures().supports(feature));
        }
        List<DDLFeature> forbidden = Arrays.asList(DDLFeature.CREATE_SCHEMA,
                DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS, DDLFeature.DROP_SCHEMA, DDLFeature.DROP_SCHEMA_CASCADE,
                DDLFeature.QUALIFIED_NAMES, DDLFeature.INLINE_FOREIGN_KEY, DDLFeature.DEFERRABLE_FK,
                DDLFeature.INITIALLY_DEFERRED, DDLFeature.MATCH_FULL, DDLFeature.FK_ENFORCED_BY_DEFAULT,
                DDLFeature.FK_TARGET_MUST_EXIST,
                DDLFeature.IDENTITY_COLUMN, DDLFeature.SERIAL, DDLFeature.COLUMN_TYPE_UNSIGNED,
                DDLFeature.COLUMN_COMMENT, DDLFeature.TABLE_COMMENT,
                DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS, DDLFeature.ALTER_DROP_COLUMN_CASCADE,
                DDLFeature.ALTER_COLUMN_TYPE, DDLFeature.ALTER_COLUMN_TYPE_USING,
                DDLFeature.ALTER_SET_NOT_NULL, DDLFeature.ALTER_DROP_NOT_NULL, DDLFeature.ALTER_SET_DEFAULT,
                DDLFeature.ALTER_DROP_DEFAULT, DDLFeature.ALTER_ADD_CONSTRAINT,
                DDLFeature.ALTER_DROP_CONSTRAINT, DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS,
                DDLFeature.ALTER_ADD_PRIMARY_KEY,
                DDLFeature.ALTER_DROP_PRIMARY_KEY, DDLFeature.ALTER_ADD_FOREIGN_KEY,
                DDLFeature.ALTER_DROP_FOREIGN_KEY, DDLFeature.ALTER_ADD_UNIQUE, DDLFeature.ALTER_ADD_CHECK,
                DDLFeature.ALTER_MULTIPLE_ACTIONS, DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE,
                DDLFeature.INDEX_METHOD, DDLFeature.INCLUDE_COLUMNS, DDLFeature.INDEX_DESCENDING,
                DDLFeature.CREATE_OR_REPLACE_VIEW, DDLFeature.MATERIALIZED_VIEW, DDLFeature.VIEW_CHECK_OPTION,
                DDLFeature.CREATE_SEQUENCE, DDLFeature.DROP_SEQUENCE, DDLFeature.SEQUENCE_OPTIONS,
                DDLFeature.TRUNCATE_TABLE, DDLFeature.TRUNCATE_CASCADE,
                DDLFeature.TYPE_ARRAY, DDLFeature.TYPE_JSON, DDLFeature.TYPE_UUID, DDLFeature.TYPE_ENUM,
                DDLFeature.TYPE_TIMESTAMPTZ, DDLFeature.TYPE_INTERVAL, DDLFeature.TYPE_DOMAIN,
                DDLFeature.ENUM_AS_CHECK);
        for (DDLFeature feature : forbidden) {
            check("not supported: " + feature, !dialect.getFeatures().supports(feature));
        }
        // the two lists above have to decide every constant: a new DDLFeature must be classified here
        // (and in SqliteDialect, which refuses to build a feature set with an undecided constant)
        EnumSet<DDLFeature> decided = EnumSet.noneOf(DDLFeature.class);
        decided.addAll(required);
        decided.addAll(forbidden);
        EnumSet<DDLFeature> undecided = EnumSet.allOf(DDLFeature.class);
        undecided.removeAll(decided);
        check("every DDLFeature constant is classified by this check (undecided: " + undecided + ")",
                undecided.isEmpty());
        check("no DDLFeature constant is classified twice",
                required.stream().noneMatch(forbidden::contains));
        assertEquals("dialect name", "sqlite", dialect.getName());
        assertEquals("default schema", "main", dialect.getDefaultSchemaName());
        assertEquals("every requested namespace maps to main", "main",
                dialect.getEffectiveSchemaName("ddlsmith"));
        assertEquals("identifiers are normalized to lower case", "customer",
                dialect.normalizeIdentifier("CUSTOMER"));

        System.out.println();
        System.out.println("--- constructs SQLite does not have render as documented no-ops");
        assertEquals("CREATE SCHEMA", SqliteDialect.NO_SCHEMA_SUPPORT, dialect.render(new CreateSchema("s", false)));
        assertEquals("DROP SCHEMA", SqliteDialect.NO_SCHEMA_SUPPORT,
                dialect.render(new DropSchema("s", false, false)));
        assertEquals("CREATE SEQUENCE", SqliteDialect.NO_SEQUENCE_SUPPORT,
                dialect.render(new CreateSequence(new Sequence("s"))));
        assertEquals("DROP SEQUENCE", SqliteDialect.NO_SEQUENCE_SUPPORT, dialect.render(new DropSequence("s", false, false)));
        assertEquals("TRUNCATE TABLE", "DELETE FROM t", dialect.render(new TruncateTable("t", true, false)));
        assertEquals("DROP INDEX (no table qualification)", "DROP INDEX IF EXISTS idx_a",
                dialect.render(new DropIndex("idx_a", "t", true, false)));
        assertEquals("DROP TABLE (no CASCADE keyword)", "DROP TABLE IF EXISTS t",
                dialect.render(new DropTable("t", true, true)));
        assertEquals("DROP VIEW (no CASCADE keyword)", "DROP VIEW IF EXISTS v",
                dialect.render(new DropView("v", true, true)));

        View view = new View("v_customer", "SELECT id, name FROM customer");
        List<String> replacing = dialect.renderAll(new CreateView(view, true, false));
        assertEquals("CREATE OR REPLACE VIEW expands to 2 statements", 2, replacing.size());
        assertEquals("  first statement drops the view", "DROP VIEW IF EXISTS v_customer", replacing.get(0));
        assertEquals("  second statement creates the view",
                "CREATE VIEW v_customer AS SELECT id, name FROM customer", replacing.get(1));

        AlterTable alter = new AlterTable("t");
        alter.addAction(new DropColumn("a", null));
        alter.addAction(new RenameColumn("b", "c"));
        List<String> split = dialect.renderAll(alter);
        assertEquals("multi action ALTER TABLE is split", 2, split.size());
        assertEquals("  first action", "ALTER TABLE t DROP COLUMN a", split.get(0));
        assertEquals("  second action", "ALTER TABLE t RENAME COLUMN b TO c", split.get(1));

        System.out.println();
        System.out.println("--- unsupported ALTER TABLE actions fail loudly");
        List<AlterTableAction> unsupported = Arrays.asList(
                new AlterColumnType("a", DataType.text(), DataType.integer(), null),
                new SetNotNull("a"), new DropNotNull("a"), new SetDefault("a", "0"), new DropDefault("a"),
                new AddConstraint(ConstraintDefinition.unique(new UniqueConstraint(Arrays.asList("a")))),
                new DropConstraint("ck", null, false, false));
        for (AlterTableAction action : unsupported) {
            try {
                String sql = dialect.renderAlterAction(action);
                check(action.getClass().getSimpleName() + " throws UnsupportedOperationException",
                        false);
                System.out.println("       rendered instead: " + sql);
            } catch (UnsupportedOperationException e) {
                check(action.getClass().getSimpleName() + " throws UnsupportedOperationException", true);
            }
        }
        System.out.println();
    }

    private static void checkAlterActions(SqliteDialect dialect) {
        System.out.println("--- ALTER TABLE actions SQLite has");
        assertEquals("ADD COLUMN", "ADD COLUMN c TEXT",
                dialect.renderAlterAction(new AddColumn(new Column("c", DataType.text()))));
        assertEquals("ADD COLUMN NOT NULL gets a typed zero default", "ADD COLUMN c INTEGER NOT NULL DEFAULT 0",
                dialect.renderAlterAction(new AddColumn(new Column("c", DataType.integer(), false))));
        assertEquals("ADD COLUMN NOT NULL text gets an empty default",
                "ADD COLUMN c VARCHAR(10) NOT NULL DEFAULT ''",
                dialect.renderAlterAction(new AddColumn(new Column("c", DataType.varchar(10), false))));
        assertEquals("ADD COLUMN UNIQUE drops the UNIQUE keyword", "ADD COLUMN c TEXT",
                dialect.renderAlterAction(new AddColumn(new Column("c", DataType.text()).setUnique(true))));
        assertEquals("ADD COLUMN NOT NULL UNIQUE keeps the default and drops UNIQUE",
                "ADD COLUMN c TEXT NOT NULL DEFAULT ''",
                dialect.renderAlterAction(new AddColumn(new Column("c", DataType.text(), false).setUnique(true))));
        assertEquals("ADD COLUMN identity degrades to plain INTEGER", "ADD COLUMN c INTEGER",
                dialect.renderAlterAction(new AddColumn(new Column("c", DataType.integer()).setIdentity(true))));
        assertEquals("DROP COLUMN", "DROP COLUMN c",
                dialect.renderAlterAction(new DropColumn("c", null, true, true)));
        assertEquals("RENAME COLUMN", "RENAME COLUMN a TO b",
                dialect.renderAlterAction(new RenameColumn("a", "b")));
        assertEquals("RENAME TO", "RENAME TO t2", dialect.renderAlterAction(new RenameTable("t", "t2")));
        System.out.println();
    }

    private static void checkLiterals(SqliteDialect dialect) {
        System.out.println("--- literals");
        assertEquals("null", "NULL", dialect.renderLiteral(null, DataType.text()));
        assertEquals("true", "1", dialect.renderLiteral(Boolean.TRUE, DataType.bool()));
        assertEquals("false", "0", dialect.renderLiteral(Boolean.FALSE, DataType.bool()));
        assertEquals("int", "42", dialect.renderLiteral(42, DataType.integer()));
        assertEquals("double", "1.5", dialect.renderLiteral(1.5, DataType.doublePrecision()));
        assertEquals("string escaping", "'it''s'", dialect.renderLiteral("it's", DataType.text()));
        assertEquals("date", "'2024-03-01'",
                dialect.renderLiteral(java.sql.Date.valueOf("2024-03-01"), DataType.date()));
        assertEquals("time", "'10:11:12'",
                dialect.renderLiteral(java.sql.Time.valueOf("10:11:12"), DataType.time()));
        assertEquals("timestamp", "'2024-03-01 10:11:12.0'",
                dialect.renderLiteral(java.sql.Timestamp.valueOf("2024-03-01 10:11:12"), DataType.timestamp()));
        assertEquals("blob", "X'0AFF'", dialect.renderLiteral(new byte[] { 10, (byte) 0xFF }, DataType.blob()));
        System.out.println();
    }

    /* ------------------------------------------------------------------ 3. schema round trip */

    /** The result of executing the sample schema and reading it back. */
    public static final class RoundTripResult {
        public final RelationalSchema intended;
        public final RelationalSchema actual;
        public final List<SchemaDifference> differences;
        public final List<String> statements;

        RoundTripResult(RelationalSchema intended, RelationalSchema actual, List<SchemaDifference> differences,
                List<String> statements) {
            this.intended = intended;
            this.actual = actual;
            this.differences = differences;
            this.statements = statements;
        }
    }

    private static void checkSchemaRoundTrip(SqliteDialect dialect) throws Exception {
        System.out.println("--- schema round trip on a real in-memory database");
        RoundTripResult result;
        try (DDLSmithDBConnection connection = connect()) {
            result = executeAndIntrospect(connection, dialect, true);
        }
        System.out.println("    executed " + result.statements.size() + " statements, introspected "
                + result.actual.getTables().size() + " tables and " + result.actual.getViews().size() + " views");
        if (!result.differences.isEmpty()) {
            System.out.println("    differences:");
            System.out.println(indent(SchemaNormalizer.render(result.differences)));
        }
        check("SchemaNormalizer.diff(erase(intended), actual) is empty", result.differences.isEmpty());

        // extra checks on facts the shared comparison does not look at
        Table warehouse = result.actual.getTable("warehouse");
        Index partial = warehouse == null ? null : findIndex(warehouse, "idx_warehouse_capacity");
        assertEquals("partial index predicate survives", "capacity > 0", partial == null ? null : partial.getPredicate());
        Table product = result.actual.getTable("product");
        Index expression = product == null ? null : findIndex(product, "idx_product_description_expr");
        assertEquals("expression index key survives", "[lower(description)]",
                expression == null ? null : expression.getExpressions().toString());
        Column name = result.actual.getTable("customer").getColumn("name");
        assertEquals("column collation is read from the DDL text", "NOCASE", name == null ? null : name.getCollation());
        Column id = result.actual.getTable("customer").getColumn("id");
        assertEquals("identity column is recognised", Boolean.TRUE, id == null ? null : id.isIdentity());
        Table employee = result.actual.getTable("employee");
        assertEquals("self referential foreign key is recognised", Boolean.TRUE,
                employee.getForeignKeys().get(0).isSelfReferential());
        assertEquals("ON DELETE CASCADE survives", ReferentialAction.CASCADE,
                employee.getForeignKeys().get(0).getOnDelete());
        Table orderLine = result.actual.getTable("order_line");
        assertEquals("composite foreign key keeps its column order", "[product_sku, product_warehouse_code]",
                orderLine.getForeignKeys().get(1).getColumns().toString());
        assertEquals("named and unnamed CHECK constraints are parsed back",
                "[ck_customer_age, ck_line_quantity, ck_product_price, ck_warehouse_capacity, unnamed, unnamed]",
                checkNames(result.actual));
        assertEquals("all three checks of warehouse survive", 3,
                result.actual.getTable("warehouse").getCheckConstraints().size());
        assertEquals("both unique constraints of warehouse survive", 2,
                result.actual.getTable("warehouse").getUniqueConstraints().size());

        System.out.println();
        System.out.println("--- capabilities match what the introspector actually reads");
        SqliteIntrospector introspector = new SqliteIntrospector();
        EnumSet<SchemaIntrospector.IntrospectionCapability> expected = EnumSet.of(
                SchemaIntrospector.IntrospectionCapability.COLUMNS,
                SchemaIntrospector.IntrospectionCapability.COLUMN_TYPES,
                SchemaIntrospector.IntrospectionCapability.NULLABILITY,
                SchemaIntrospector.IntrospectionCapability.DEFAULTS,
                SchemaIntrospector.IntrospectionCapability.PRIMARY_KEYS,
                SchemaIntrospector.IntrospectionCapability.FOREIGN_KEYS,
                SchemaIntrospector.IntrospectionCapability.FOREIGN_KEY_ACTIONS,
                SchemaIntrospector.IntrospectionCapability.UNIQUE_CONSTRAINTS,
                SchemaIntrospector.IntrospectionCapability.CHECK_CONSTRAINTS,
                SchemaIntrospector.IntrospectionCapability.INDEXES,
                SchemaIntrospector.IntrospectionCapability.INDEX_UNIQUENESS,
                SchemaIntrospector.IntrospectionCapability.INDEX_COLUMNS,
                SchemaIntrospector.IntrospectionCapability.PARTIAL_INDEXES,
                SchemaIntrospector.IntrospectionCapability.EXPRESSION_INDEXES,
                SchemaIntrospector.IntrospectionCapability.VIEWS,
                SchemaIntrospector.IntrospectionCapability.VIEW_DEFINITIONS,
                SchemaIntrospector.IntrospectionCapability.TABLE_EXISTENCE,
                SchemaIntrospector.IntrospectionCapability.IDENTITY,
                SchemaIntrospector.IntrospectionCapability.GENERATED_COLUMNS);
        assertEquals("capability set", expected, introspector.getCapabilities());
        check("SEQUENCES is not claimed", !introspector.canIntrospect(
                SchemaIntrospector.IntrospectionCapability.SEQUENCES));
        check("COMMENTS is not claimed", !introspector.canIntrospect(
                SchemaIntrospector.IntrospectionCapability.COMMENTS));
        check("NAMED_FOREIGN_KEY is not observable", !introspector.supports(DDLFeature.NAMED_FOREIGN_KEY));
        check("NAMED_UNIQUE is not observable", !introspector.supports(DDLFeature.NAMED_UNIQUE));
        check("AUTO_INCREMENT is observable", introspector.supports(DDLFeature.AUTO_INCREMENT));
        check("CHECK constraints are observable", introspector.supports(DDLFeature.NAMED_CHECK));
        System.out.println();
    }

    private static String checkNames(RelationalSchema schema) {
        List<String> names = new ArrayList<>();
        for (Table table : schema.getTables()) {
            for (CheckConstraint check : table.getCheckConstraints()) {
                names.add(check.getName() == null ? "unnamed" : check.getName());
            }
        }
        names.sort(String::compareTo);
        return names.toString();
    }

    private static Index findIndex(Table table, String name) {
        for (Index index : table.getIndexes()) {
            if (index.getName().equalsIgnoreCase(name)) {
                return index;
            }
        }
        return null;
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append("      ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static DDLSmithDBConnection connect() throws SQLException {
        return new DDLSmithDBConnection(DriverManager.getConnection("jdbc:sqlite::memory:"), "self-check");
    }

    /**
     * Renders the sample schema with the dialect, executes every statement and reads the schema back.
     * Shared with {@code SqliteDialectTest} so that the JUnit test checks exactly the same schema.
     *
     * @param print when true, every rendered statement is printed
     */
    public static RoundTripResult executeAndIntrospect(DDLSmithDBConnection connection, SqliteDialect dialect,
            boolean print) throws SQLException {
        RelationalSchema intended = sampleSchema();
        List<String> statements = new ArrayList<>();
        for (String setup : dialect.getSessionSetupStatements()) {
            statements.add(setup);
        }
        for (Table table : intended.getTables()) {
            statements.addAll(dialect.renderAll(new CreateTable(table)));
            for (Index index : table.getIndexes()) {
                statements.addAll(dialect.renderAll(new CreateIndex(table.getName(), index)));
            }
        }
        for (View view : intended.getViews()) {
            statements.addAll(dialect.renderAll(new CreateView(view)));
        }
        if (print) {
            System.out.println("    rendered DDL:");
            for (String sql : statements) {
                System.out.println("      " + sql);
            }
        }
        for (String sql : statements) {
            connection.executeStatement(sql);
        }
        SqliteIntrospector introspector = new SqliteIntrospector();
        RelationalSchema actual = introspector.introspect(connection, dialect.getDefaultSchemaName());
        RelationalSchema erased = SchemaNormalizer.erase(intended, introspector.getCapabilities());
        return new RoundTripResult(intended, actual, SchemaNormalizer.diff(erased, actual), statements);
    }

    /**
     * The schema the round trip is checked against: six tables plus a view, covering a composite
     * primary key, a self-referential foreign key with {@code ON DELETE CASCADE}, composite foreign
     * keys, unique constraints (table level and column level), named CHECK constraints, a nullable
     * column with a default, {@code INTEGER PRIMARY KEY AUTOINCREMENT} identity columns, a partial
     * index, an expression index, plain indexes, a view and a column with {@code COLLATE NOCASE}.
     */
    public static RelationalSchema sampleSchema() {
        RelationalSchema schema = new RelationalSchema("self_check");

        Table warehouse = new Table("warehouse");
        warehouse.addColumn(new Column("code", DataType.varchar(12), false));
        warehouse.addColumn(new Column("region", DataType.charType(4), false));
        warehouse.addColumn(new Column("capacity", DataType.integer(), false).setDefaultExpression("0"));
        warehouse.addColumn(new Column("notes", DataType.text(), true));
        warehouse.setPrimaryKey(new PrimaryKey(Arrays.asList("code", "region")));
        warehouse.addUniqueConstraint(new UniqueConstraint("uq_warehouse_code", Arrays.asList("code")));
        // unnamed table level constraints are the common case for a generator, and SQLite accepts
        // several of them on one table; the introspector has to read them all back from the DDL text
        warehouse.addUniqueConstraint(new UniqueConstraint(Arrays.asList("notes", "capacity")));
        warehouse.addCheckConstraint(new CheckConstraint("ck_warehouse_capacity", "capacity >= 0"));
        warehouse.addCheckConstraint(new CheckConstraint("capacity < 1000"));
        warehouse.addCheckConstraint(new CheckConstraint("region <> 'ZZZZ'"));
        warehouse.addIndex(new Index("idx_warehouse_capacity", Arrays.asList("capacity"))
                .setPredicate("capacity > 0"));
        Index warehouseExpressionIndex = new Index("idx_warehouse_code_expr");
        warehouseExpressionIndex.getExpressions().add("lower(code)");
        warehouse.addIndex(warehouseExpressionIndex);
        warehouse.addIndex(new Index("idx_warehouse_notes", Arrays.asList("notes")));
        schema.addTable(warehouse);

        Table customer = new Table("customer");
        customer.addColumn(new Column("id", DataType.integer(), false).setIdentity(true));
        customer.addColumn(new Column("name", DataType.varchar(50), false).setCollation("NOCASE"));
        customer.addColumn(new Column("email", DataType.varchar(100), true).setUnique(true)
                .setDefaultExpression("'unknown@example.com'"));
        customer.addColumn(new Column("age", DataType.integer(), false).setDefaultExpression("0"));
        customer.addColumn(new Column("created", DataType.timestamp(), false)
                .setDefaultExpression("CURRENT_TIMESTAMP"));
        customer.setPrimaryKey(new PrimaryKey(Arrays.asList("id")));
        customer.addUniqueConstraint(new UniqueConstraint("uq_customer_name", Arrays.asList("name")));
        customer.addCheckConstraint(new CheckConstraint("ck_customer_age", "age >= 0 AND age < 200"));
        customer.addIndex(new Index("idx_customer_created", Arrays.asList("created")));
        schema.addTable(customer);

        Table employee = new Table("employee");
        employee.addColumn(new Column("id", DataType.bigint(), false));
        employee.addColumn(new Column("name", DataType.varchar(50), false));
        employee.addColumn(new Column("manager_id", DataType.bigint(), true));
        employee.setPrimaryKey(new PrimaryKey(Arrays.asList("id")));
        employee.addForeignKey(new ForeignKey(Arrays.asList("manager_id"), "employee", Arrays.asList("id"))
                .setName("fk_employee_manager").setOnDelete(ReferentialAction.CASCADE).setSelfReferential(true));
        schema.addTable(employee);

        Table product = new Table("product");
        product.addColumn(new Column("sku", DataType.varchar(16), false));
        product.addColumn(new Column("warehouse_code", DataType.varchar(12), false));
        product.addColumn(new Column("warehouse_region", DataType.charType(4), false));
        product.addColumn(new Column("price", DataType.decimal(10, 2), false).setDefaultExpression("0"));
        product.addColumn(new Column("description", DataType.text(), true));
        product.setPrimaryKey(new PrimaryKey(Arrays.asList("sku", "warehouse_code")));
        product.addForeignKey(new ForeignKey(Arrays.asList("warehouse_code", "warehouse_region"), "warehouse",
                Arrays.asList("code", "region")).setName("fk_product_warehouse")
                        .setOnUpdate(ReferentialAction.CASCADE));
        product.addCheckConstraint(new CheckConstraint("ck_product_price", "price >= 0"));
        Index productExpressionIndex = new Index("idx_product_description_expr");
        productExpressionIndex.getExpressions().add("lower(description)");
        product.addIndex(productExpressionIndex);
        schema.addTable(product);

        Table salesOrder = new Table("sales_order");
        salesOrder.addColumn(new Column("id", DataType.integer(), false).setIdentity(true));
        salesOrder.addColumn(new Column("customer_id", DataType.integer(), true));
        salesOrder.addColumn(new Column("placed_at", DataType.timestamp(), false)
                .setDefaultExpression("CURRENT_TIMESTAMP"));
        salesOrder.setPrimaryKey(new PrimaryKey(Arrays.asList("id")));
        salesOrder.addForeignKey(new ForeignKey(Arrays.asList("customer_id"), "customer", Arrays.asList("id"))
                .setName("fk_order_customer").setOnDelete(ReferentialAction.SET_NULL)
                .setOnUpdate(ReferentialAction.CASCADE));
        salesOrder.addIndex(new Index("idx_order_customer", Arrays.asList("customer_id")));
        schema.addTable(salesOrder);

        Table orderLine = new Table("order_line");
        orderLine.addColumn(new Column("order_id", DataType.integer(), false));
        orderLine.addColumn(new Column("line_no", DataType.integer(), false));
        orderLine.addColumn(new Column("product_sku", DataType.varchar(16), false));
        orderLine.addColumn(new Column("product_warehouse_code", DataType.varchar(12), false));
        orderLine.addColumn(new Column("quantity", DataType.integer(), false).setDefaultExpression("1"));
        orderLine.setPrimaryKey(new PrimaryKey(Arrays.asList("order_id", "line_no")));
        orderLine.addForeignKey(new ForeignKey(Arrays.asList("order_id"), "sales_order", Arrays.asList("id"))
                .setName("fk_line_order").setOnDelete(ReferentialAction.CASCADE));
        orderLine.addForeignKey(new ForeignKey(Arrays.asList("product_sku", "product_warehouse_code"), "product",
                Arrays.asList("sku", "warehouse_code")).setName("fk_line_product")
                        .setOnDelete(ReferentialAction.CASCADE));
        orderLine.addCheckConstraint(new CheckConstraint("ck_line_quantity", "quantity > 0"));
        schema.addTable(orderLine);

        schema.addView(new View("v_customer", "SELECT id, name FROM customer"));
        return schema;
    }

    /* ------------------------------------------------------------------ 4. more round trips */

    /**
     * Checks the hook that keeps the round-trip oracle honest: {@code effectiveType} must report the
     * type SQLite necessarily stores, and {@link SchemaAdapter} puts that into the intended schema.
     * Without it a mapper surrogate key of {@code BIGINT IDENTITY} shows up as a false
     * {@code COLUMN_TYPE} difference, because SQLite only auto-increments an {@code INTEGER PRIMARY
     * KEY}.
     */
    private static void checkEffectiveType(SqliteDialect dialect) throws Exception {
        System.out.println("--- effectiveType: the type SQLite necessarily stores");
        Table surrogate = new Table("t_7_r");
        Column id = new Column("id", DataType.bigint(), false).setIdentity(true);
        surrogate.addColumn(id);
        surrogate.addColumn(new Column("label", DataType.varchar(20), true));
        surrogate.setPrimaryKey(new PrimaryKey(Arrays.asList("id")));
        assertEquals("BIGINT identity primary key is reported as INTEGER", DataType.integer(),
                dialect.effectiveType(id.getType(), id, surrogate));

        Table plain = new Table("t_plain");
        Column plainId = new Column("id", DataType.bigint(), false);
        plain.addColumn(plainId);
        plain.setPrimaryKey(new PrimaryKey(Arrays.asList("id")));
        assertEquals("a non-identity BIGINT primary key keeps its type", DataType.bigint(),
                dialect.effectiveType(plainId.getType(), plainId, plain));

        Table composite = new Table("t_composite");
        Column compositeId = new Column("id", DataType.bigint(), false).setIdentity(true);
        composite.addColumn(compositeId);
        composite.addColumn(new Column("part", DataType.integer(), false));
        composite.setPrimaryKey(new PrimaryKey(Arrays.asList("id", "part")));
        assertEquals("an identity column outside a single-column primary key keeps its type",
                DataType.bigint(), dialect.effectiveType(compositeId.getType(), compositeId, composite));

        Column lengthless = new Column("v", DataType.of(DataType.Category.VARCHAR), true);
        assertEquals("a VARCHAR without a length is stored as VARCHAR(255)", DataType.varchar(255),
                dialect.effectiveType(lengthless.getType(), lengthless, new Table("t_v")));
        Column lengthlessChar = new Column("c", DataType.of(DataType.Category.CHAR), true);
        assertEquals("a CHAR without a length is stored as CHAR(1)", DataType.charType(1),
                dialect.effectiveType(lengthlessChar.getType(), lengthlessChar, new Table("t_c")));
        Column parameterlessDecimal = new Column("d", DataType.of(DataType.Category.DECIMAL), true);
        assertEquals("a DECIMAL without precision is stored as DECIMAL(10,0)", DataType.decimal(10, 0),
                dialect.effectiveType(parameterlessDecimal.getType(), parameterlessDecimal, new Table("t_d")));

        // and the whole adaptation path, exactly as the pipeline uses it
        RelationalSchema mapperSchema = new RelationalSchema("adapted");
        mapperSchema.addTable(surrogate);
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(mapperSchema, dialect, notes);
        assertEquals("SchemaAdapter applies the effective type", DataType.integer(),
                adapted.getTable("t_7_r").getColumn("id").getType());
        check("the adaptation is recorded as a note (" + notes + ")",
                notes.stream().anyMatch(note -> note.contains("t_7_r.id")));
        try (DDLSmithDBConnection connection = connect()) {
            for (String sql : dialect.getSessionSetupStatements()) {
                connection.executeStatement(sql);
            }
            for (String sql : dialect.renderAll(new CreateTable(adapted.getTable("t_7_r")))) {
                System.out.println("      " + sql);
                connection.executeStatement(sql);
            }
            SqliteIntrospector introspector = new SqliteIntrospector();
            RelationalSchema actual = introspector.introspect(connection, dialect.getDefaultSchemaName());
            List<SchemaDifference> differences = SchemaNormalizer.diff(
                    SchemaNormalizer.erase(adapted, introspector.getCapabilities()), actual);
            check("the adapted schema round trips without a false COLUMN_TYPE", differences.isEmpty());
            if (!differences.isEmpty()) {
                System.out.println(indent(SchemaNormalizer.render(differences)));
            }
        }
        System.out.println();
    }

    private static void checkGeneratedColumns(SqliteDialect dialect) throws Exception {
        System.out.println("--- generated columns (only visible through PRAGMA table_xinfo)");
        try (DDLSmithDBConnection connection = connect()) {
            Table table = new Table("computed");
            table.addColumn(new Column("base", DataType.integer(), false));
            table.addColumn(new Column("doubled", DataType.integer(), true)
                    .setGeneratedExpression("base * 2").setGeneratedStored(false));
            table.addColumn(new Column("plus_one", DataType.integer(), true)
                    .setGeneratedExpression("base + 1").setGeneratedStored(true));
            RelationalSchema intended = new RelationalSchema("generated");
            intended.addTable(table);
            for (String sql : dialect.renderAll(new CreateTable(table))) {
                System.out.println("      " + sql);
                connection.executeStatement(sql);
            }
            SqliteIntrospector introspector = new SqliteIntrospector();
            RelationalSchema actual = introspector.introspect(connection, dialect.getDefaultSchemaName());
            List<SchemaDifference> differences = SchemaNormalizer.diff(
                    SchemaNormalizer.erase(intended, introspector.getCapabilities()), actual);
            check("generated column schema round trips", differences.isEmpty());
            if (!differences.isEmpty()) {
                System.out.println(indent(SchemaNormalizer.render(differences)));
            }
            Column doubled = actual.getTable("computed").getColumn("doubled");
            assertEquals("virtual generated expression", "base * 2", doubled.getGeneratedExpression());
            assertEquals("virtual generated flag", Boolean.FALSE, doubled.isGeneratedStored());
            Column plusOne = actual.getTable("computed").getColumn("plus_one");
            assertEquals("stored generated expression", "base + 1", plusOne.getGeneratedExpression());
            assertEquals("stored generated flag", Boolean.TRUE, plusOne.isGeneratedStored());
            connection.executeStatement("INSERT INTO computed (base) VALUES (5)");
            assertEquals("the stored generated column computes", 6,
                    connection.executeAndGet("SELECT plus_one FROM computed").getRows().get(0).get(0));
        }
        System.out.println();
    }

    /**
     * The contract renders identifiers unquoted and has {@link SchemaAdapter} rename the reserved
     * words instead; the dialect only has to publish its reserved word list. This checks that
     * division of labour end to end, because a name the adapter misses would make SQLite reject an
     * otherwise valid statement.
     */
    private static void checkReservedWordRenaming(SqliteDialect dialect) throws Exception {
        System.out.println("--- reserved identifiers are renamed by the adapter, not quoted by the dialect");
        assertEquals("the dialect renders identifiers unquoted", "select", dialect.quote("select"));
        check("SQLite's reserved word list is published",
                dialect.getReservedWords().contains("select") && dialect.getReservedWords().contains("value"));
        Table table = new Table("order");
        table.addColumn(new Column("select", DataType.integer(), false));
        table.addColumn(new Column("value", DataType.text(), true));
        table.setPrimaryKey(new PrimaryKey(Arrays.asList("select")));
        RelationalSchema mapperSchema = new RelationalSchema("reserved");
        mapperSchema.addTable(table);
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(mapperSchema, dialect, notes);
        Table adaptedTable = adapted.getTables().get(0);
        check("the reserved table name was renamed to " + adaptedTable.getName(),
                !adaptedTable.getName().equalsIgnoreCase("order"));
        check("the reserved column names were renamed to " + adaptedTable.getColumnNames(),
                !adaptedTable.hasColumn("select") && !adaptedTable.hasColumn("value"));
        check("the renames are recorded as notes (" + notes.size() + ")", notes.size() >= 2);
        try (DDLSmithDBConnection connection = connect()) {
            for (String sql : dialect.getSessionSetupStatements()) {
                connection.executeStatement(sql);
            }
            for (String sql : dialect.renderAll(new CreateTable(adaptedTable))) {
                System.out.println("      " + sql);
                connection.executeStatement(sql);
            }
            SqliteIntrospector introspector = new SqliteIntrospector();
            RelationalSchema actual = introspector.introspect(connection, dialect.getDefaultSchemaName());
            List<SchemaDifference> differences = SchemaNormalizer.diff(
                    SchemaNormalizer.erase(adapted, introspector.getCapabilities()), actual);
            check("the renamed schema round trips", differences.isEmpty());
            if (!differences.isEmpty()) {
                System.out.println(indent(SchemaNormalizer.render(differences)));
            }
        }
        System.out.println();
    }

    private static void checkTemporarySchema(SqliteDialect dialect) throws Exception {
        System.out.println("--- the temp schema is introspected from sqlite_temp_master");
        try (DDLSmithDBConnection connection = connect()) {
            Table table = new Table("temp_notes");
            table.setTemporary(true);
            table.addColumn(new Column("id", DataType.integer(), false).setIdentity(true));
            table.addColumn(new Column("body", DataType.text(), true));
            table.setPrimaryKey(new PrimaryKey(Arrays.asList("id")));
            RelationalSchema intended = new RelationalSchema("temp");
            intended.addTable(table);
            for (String sql : dialect.renderAll(new CreateTable(table))) {
                System.out.println("      " + sql);
                connection.executeStatement(sql);
            }
            SqliteIntrospector introspector = new SqliteIntrospector();
            RelationalSchema main = introspector.introspect(connection, "main");
            check("a temporary table is not part of main", !main.hasTable("temp_notes"));
            RelationalSchema temp = introspector.introspect(connection, "temp");
            check("the temp schema is introspected", temp.hasTable("temp_notes"));
            check("temp tables are marked temporary",
                    temp.hasTable("temp_notes") && temp.getTable("temp_notes").isTemporary());
            List<SchemaDifference> differences = SchemaNormalizer.diff(
                    SchemaNormalizer.erase(intended, introspector.getCapabilities()), temp);
            check("temp schema round trips", differences.isEmpty());
            if (!differences.isEmpty()) {
                System.out.println(indent(SchemaNormalizer.render(differences)));
            }
        }
        System.out.println();
    }

    private static void checkAlterRoundTrip(SqliteDialect dialect) throws Exception {
        System.out.println("--- ALTER TABLE round trip (on a table that already has a row)");
        try (DDLSmithDBConnection connection = connect()) {
            for (String sql : dialect.getSessionSetupStatements()) {
                connection.executeStatement(sql);
            }
            connection.executeStatement("CREATE TABLE alter_target (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + " a INTEGER NOT NULL DEFAULT 0)");
            connection.executeStatement("INSERT INTO alter_target (a) VALUES (1)");
            executeAll(connection, dialect, new AlterTable("alter_target",
                    new AddColumn(new Column("b", DataType.text(), false))));
            executeAll(connection, dialect, new AlterTable("alter_target",
                    new AddColumn(new Column("d", DataType.integer()))));
            executeAll(connection, dialect, new AlterTable("alter_target", new DropColumn("d", null)));
            executeAll(connection, dialect, new AlterTable("alter_target", new RenameColumn("b", "c")));
            executeAll(connection, dialect, new AlterTable("alter_target", new RenameTable("alter_target",
                    "alter_target2")));
            RelationalSchema actual = new SqliteIntrospector().introspect(connection, "main");
            Table table = actual.getTable("alter_target2");
            check("RENAME TO is visible in the catalog", table != null);
            check("DROP COLUMN removed the column", table != null && !table.hasColumn("d"));
            check("the identity column survives ALTER TABLE", table != null && table.getColumn("id").isIdentity());
            assertEquals("the added NOT NULL column carries the relaxed default", "''",
                    table == null ? null : table.getColumn("c").getDefaultExpression());
            System.out.println("      note: the relaxed DEFAULT '' is visible in the catalog, which is the"
                    + " documented price of expressing ADD COLUMN ... NOT NULL in SQLite");
        }
        System.out.println();
    }

    private static void executeAll(DDLSmithDBConnection connection, SqliteDialect dialect, DDLStatement statement)
            throws SQLException {
        for (String sql : dialect.renderAll(statement)) {
            System.out.println("      " + sql);
            connection.executeStatement(sql);
        }
    }

    /* ------------------------------------------------------------------ 5. foreign key enforcement */

    private static void checkForeignKeyEnforcement(SqliteDialect dialect) throws Exception {
        System.out.println("--- foreign key enforcement depends on the session setup");
        check("setup contains PRAGMA foreign_keys = ON",
                dialect.getSessionSetupStatements().contains("PRAGMA foreign_keys = ON"));
        try (DDLSmithDBConnection connection = connect()) {
            for (String sql : dialect.getSessionSetupStatements()) {
                connection.executeStatement(sql);
            }
            connection.executeStatement("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            connection.executeStatement("CREATE TABLE child (id INTEGER PRIMARY KEY, pid INTEGER,"
                    + " FOREIGN KEY (pid) REFERENCES parent (id))");
            connection.executeStatement("INSERT INTO parent (id) VALUES (1)");
            connection.executeStatement("INSERT INTO child (id, pid) VALUES (1, 1)");
            check("a valid row is accepted", true);
            try {
                connection.executeStatement("INSERT INTO child (id, pid) VALUES (2, 99)");
                check("a row violating the foreign key is rejected", false);
            } catch (SQLException e) {
                check("a row violating the foreign key is rejected", true);
                System.out.println("       rejected with: " + e.getMessage());
            }
        }
        try (DDLSmithDBConnection connection = connect()) {
            connection.executeStatement("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            connection.executeStatement("CREATE TABLE child (id INTEGER PRIMARY KEY, pid INTEGER,"
                    + " FOREIGN KEY (pid) REFERENCES parent (id))");
            connection.executeStatement("INSERT INTO child (id, pid) VALUES (2, 99)");
            System.out.println("       (without the pragma the same insert is accepted: foreign keys are"
                    + " off by default)");
        }
        // FK_TARGET_MUST_EXIST is deliberately absent from the feature set: SQLite resolves foreign
        // key targets lazily, so a table may reference one that does not exist yet
        try (DDLSmithDBConnection connection = connect()) {
            connection.executeStatement("PRAGMA foreign_keys = ON");
            connection.executeStatement("CREATE TABLE forward_reference (pid INTEGER REFERENCES created_later (id))");
            connection.executeStatement("CREATE TABLE created_later (id INTEGER PRIMARY KEY)");
            check("a forward foreign key reference is accepted (FK_TARGET_MUST_EXIST is absent)", true);
            connection.executeStatement("INSERT INTO created_later (id) VALUES (1)");
            connection.executeStatement("INSERT INTO forward_reference (pid) VALUES (1)");
            check("the forward reference is enforced once the target exists", true);
            try {
                connection.executeStatement("INSERT INTO forward_reference (pid) VALUES (99)");
                check("the forward reference rejects an unknown target", false);
            } catch (SQLException e) {
                check("the forward reference rejects an unknown target", true);
            }
        }
        System.out.println();
    }

    /* ------------------------------------------------------------------ 5. engine behaviours */

    /**
     * Documents the SQLite behaviours DDLSmith is meant to surface. Nothing here fails the check:
     * these are findings about the engine, and the dialect works around them where it can.
     */
    private static void printEngineBehaviours(SqliteDialect dialect) throws Exception {
        System.out.println("--- observed SQLite engine behaviours (informational, never fails)");
        try (DDLSmithDBConnection connection = connect()) {
            connection.executeStatement("PRAGMA foreign_keys = ON");

            // (a) DROP COLUMN of an indexed column
            connection.executeStatement("CREATE TABLE drop_indexed (a INTEGER, b INTEGER)");
            connection.executeStatement("CREATE INDEX drop_indexed_b ON drop_indexed (b)");
            System.out.println("  (a) ALTER TABLE ... DROP COLUMN on an indexed column:");
            try {
                connection.executeStatement("ALTER TABLE drop_indexed DROP COLUMN b");
                System.out.println("      accepted (unexpected)");
            } catch (SQLException e) {
                System.out.println("      rejected: " + e.getMessage());
            }

            // (b) ADD COLUMN NOT NULL without a default
            connection.executeStatement("CREATE TABLE add_notnull (a INTEGER)");
            connection.executeStatement("INSERT INTO add_notnull (a) VALUES (1)");
            System.out.println("  (b) ALTER TABLE ... ADD COLUMN ... NOT NULL without a default"
                    + " (table is not empty):");
            try {
                connection.executeStatement("ALTER TABLE add_notnull ADD COLUMN b TEXT NOT NULL");
                System.out.println("      accepted (unexpected)");
            } catch (SQLException e) {
                System.out.println("      rejected: " + e.getMessage());
            }
            System.out.println("      the dialect renders the same action as: "
                    + dialect.renderAlterAction(new AddColumn(new Column("b", DataType.text(), false))));

            // (b2) ADD COLUMN UNIQUE / PRIMARY KEY
            System.out.println("  (b2) ALTER TABLE ... ADD COLUMN ... UNIQUE:");
            try {
                connection.executeStatement("ALTER TABLE add_notnull ADD COLUMN c TEXT UNIQUE");
                System.out.println("      accepted (unexpected)");
            } catch (SQLException e) {
                System.out.println("      rejected: " + e.getMessage());
            }
            System.out.println("      the dialect renders the same action as: "
                    + dialect.renderAlterAction(new AddColumn(new Column("c", DataType.text()).setUnique(true))));

            // (c) CHECK constraints are enforced
            connection.executeStatement("CREATE TABLE checked (a INTEGER CONSTRAINT ck_a CHECK (a > 0))");
            connection.executeStatement("INSERT INTO checked (a) VALUES (5)");
            System.out.println("  (c) declared CHECK constraint:");
            try {
                connection.executeStatement("INSERT INTO checked (a) VALUES (-5)");
                System.out.println("      NOT enforced (unexpected)");
            } catch (SQLException e) {
                System.out.println("      enforced: " + e.getMessage());
            }

            // (d) VARCHAR length is ignored
            connection.executeStatement("CREATE TABLE short_text (a VARCHAR(3))");
            connection.executeStatement("INSERT INTO short_text (a) VALUES ('abcdefgh')");
            System.out.println("  (d) a VARCHAR(3) column accepting a longer string:");
            System.out.println("      accepted, stored length is "
                    + connection.executeAndGet("SELECT length(a) FROM short_text").getRows().get(0).get(0));

            // (e) no CASCADE keyword on DROP
            connection.executeStatement("CREATE TABLE cascade_target (a INTEGER)");
            System.out.println("  (e) DROP TABLE ... CASCADE:");
            try {
                connection.executeStatement("DROP TABLE cascade_target CASCADE");
                System.out.println("      accepted (unexpected)");
            } catch (SQLException e) {
                System.out.println("      rejected: " + e.getMessage());
                System.out.println("      the dialect therefore renders: "
                        + dialect.render(new DropTable("cascade_target", false, true)));
            }

            // (f) constraint names are not preserved
            connection.executeStatement("CREATE TABLE named (a INTEGER, CONSTRAINT pk_named PRIMARY KEY (a),"
                    + " CONSTRAINT uq_named UNIQUE (a))");
            System.out.println("  (f) constraint names in the catalog:");
            System.out.println("      PRAGMA index_list('named') reports: "
                    + connection.executeAndGet("SELECT name, origin FROM pragma_index_list('named')").getRows());

            // (g) identity columns must be INTEGER
            System.out.println("  (g) AUTOINCREMENT on a non-INTEGER primary key:");
            try {
                connection.executeStatement("CREATE TABLE big_identity (id BIGINT PRIMARY KEY AUTOINCREMENT)");
                System.out.println("      accepted (unexpected)");
            } catch (SQLException e) {
                System.out.println("      rejected: " + e.getMessage());
                System.out.println("      so an identity column is always rendered as INTEGER, whatever its"
                        + " canonical type says");
            }

            // (h) generated columns are invisible to PRAGMA table_info
            connection.executeStatement("CREATE TABLE generated (a INTEGER,"
                    + " b INTEGER GENERATED ALWAYS AS (a * 2) VIRTUAL)");
            System.out.println("  (h) generated columns:");
            System.out.println("      PRAGMA table_info sees "
                    + connection.executeAndGet("SELECT count(*) FROM pragma_table_info('generated')").getRows().get(0).get(0)
                    + " column(s), PRAGMA table_xinfo sees "
                    + connection.executeAndGet("SELECT count(*) FROM pragma_table_xinfo('generated')").getRows().get(0).get(0)
                    + " (the introspector uses table_xinfo)");

            // (i) NOT NULL is not implied by PRIMARY KEY
            connection.executeStatement("CREATE TABLE null_pk (a INTEGER, PRIMARY KEY (a))");
            System.out.println("  (i) a PRIMARY KEY column declared without NOT NULL reports notnull="
                    + connection.executeAndGet("SELECT [notnull] FROM pragma_table_info('null_pk')").getRows().get(0).get(0)
                    + ", so the dialect always renders NOT NULL explicitly");

            // (j) only one auto-increment column is possible, and only as the primary key
            System.out.println("  (j) a second identity column cannot be expressed: SQLite has exactly one"
                    + " auto-increment form, INTEGER PRIMARY KEY AUTOINCREMENT, so a mapper that marks a"
                    + " non-key column (e.g. a surrogate key merged in by a 1:1 MERGE_TABLES mapping) as an"
                    + " identity column is reported as a COLUMN_IDENTITY difference");
        }
        System.out.println();
    }
}
