package org.ddlsmith.dbms.h2;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateSequence;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLStatement;
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
import org.ddlsmith.introspection.SchemaNormalizer;

/**
 * End-to-end self-check of the H2 module: connects to an in-memory H2 database, exercises the type
 * mapper, the dialect and the introspector, and reports every divergence.
 *
 * <p>Run with (from the repository root; {@code <sep>} is {@code ;} on Windows and {@code :}
 * elsewhere):</p>
 * <pre>
 * # compile the production tree to target/core-api-classes and write target/cp.txt
 * mvn -Pcore-api process-classes
 * # compile the H2 test tree against it
 * javac -encoding UTF-8 -cp "target/core-api-classes<sep>$(cat target/cp.txt)" `
 *       -d target/h2-test-classes src/test/java/org/ddlsmith/dbms/h2/*.java
 * # run the self-check
 * java -cp "target/h2-test-classes<sep>target/core-api-classes<sep>$(cat target/cp.txt)" `
 *      org.ddlsmith.dbms.h2.SelfCheck
 * </pre>
 *
 * <p>This class lives in the test source tree so that it cannot break the production build; it is
 * the driver for {@code H2DialectTest}, which asserts on the very same evidence.</p>
 *
 * <p>The individual checks are public static methods returning a list of failure descriptions, so
 * {@code H2DialectTest} can assert on exactly the same evidence without duplicating it.</p>
 *
 * <p>The feature section does three things: it prints every probe result, it asserts that
 * {@link H2Dialect#getFeatures()} agrees with what H2 actually did, and it asserts that every
 * {@link DDLFeature} constant is decided explicitly (declared supported or listed in
 * {@link H2Dialect#unsupportedFeaturesByReason()} with a reason).</p>
 */
public final class SelfCheck {

    /** The schema every check introspects; H2's default namespace. */
    public static final String SCHEMA = "public";

    private static final H2Dialect DIALECT = new H2Dialect();
    private static final H2Introspector INTROSPECTOR = new H2Introspector();

    private SelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("DDLsmith H2 module self-check");
        System.out.println("  H2 version  : " + h2Version());
        System.out.println("  dialect     : " + DIALECT.getName() + ", default schema "
                + DIALECT.getDefaultSchemaName() + ", session setup " + DIALECT.getSessionSetupStatements());
        System.out.println("  capabilities: " + INTROSPECTOR.getCapabilities());

        List<String> failures = new ArrayList<>();
        section("1. feature set covers every DDLFeature constant");
        failures.addAll(checkFeatureCoverage());
        section("2. type mapper: render/parse round trip and alias tolerance");
        failures.addAll(checkTypeMapper());
        section("3. every supported type through a real H2 table");
        failures.addAll(checkTypeRoundTrip());
        section("4. schema round trip (dialect -> H2 -> introspector -> diff)");
        failures.addAll(checkSchemaRoundTrip());
        section("5. check-constraint expression normalization against real H2 output");
        failures.addAll(checkExpressionNormalization());
        section("6. literal rendering");
        failures.addAll(checkLiterals());
        section("7. feature probes (observed vs declared)");
        failures.addAll(checkFeatureProbes());
        section("8. ALTER TABLE round trip and inverse (metamorphic premise)");
        failures.addAll(checkAlterRoundTrip());

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("SELF-CHECK PASSED - no divergences");
            return;
        }
        System.out.println("SELF-CHECK FAILED - " + failures.size() + " problem(s)");
        for (String failure : failures) {
            System.out.println();
            System.out.println("  ! " + failure);
        }
        System.exit(1);
    }

    /* ================================================================== 1. feature coverage */

    /**
     * Every {@link DDLFeature} constant must be either declared in {@link H2Dialect#getFeatures()}
     * or listed in {@link H2Dialect#unsupportedFeaturesByReason()}. A constant in neither (or in
     * both) would silently change behaviour, so it is reported as a failure.
     */
    public static List<String> checkFeatureCoverage() {
        List<String> failures = new ArrayList<>();
        List<DDLFeature> unclassified = H2Dialect.unclassifiedFeatures();
        if (!unclassified.isEmpty()) {
            failures.add("DDLFeature constants that H2Dialect neither declares nor rejects explicitly: "
                    + unclassified);
        }
        int total = DDLFeature.values().length;
        int supported = DIALECT.getFeatures().getFeatures().size();
        int unsupported = H2Dialect.unsupportedFeaturesByReason().size();
        System.out.println("    " + total + " constants = " + supported + " supported + " + unsupported
                + " explicitly unsupported");
        System.out.println();
        System.out.println("    supported: " + DIALECT.getFeatures().getFeatures());
        System.out.println();
        System.out.println("    explicitly unsupported:");
        for (Map.Entry<DDLFeature, String> entry : new java.util.TreeMap<>(
                H2Dialect.unsupportedFeaturesByReason()).entrySet()) {
            System.out.println("      " + pad(entry.getKey().name()) + entry.getValue());
        }
        return failures;
    }

    /* ================================================================== 2. type mapper */

    /**
     * {@code parse(render(t))} must return {@code t} for every supported canonical type, and
     * {@code parse} must tolerate every spelling H2 reports or accepts.
     */
    public static List<String> checkTypeMapper() {
        List<String> failures = new ArrayList<>();
        H2TypeMapper mapper = new H2TypeMapper();
        for (DataType type : supportedTypes()) {
            String rendered = mapper.render(type);
            DataType parsed = mapper.parse(rendered);
            if (!type.equals(parsed)) {
                failures.add("type round trip: " + type.getName() + " -> '" + rendered + "' -> "
                        + (parsed == null ? "null" : parsed.getName()));
            }
            DataType loose = mapper.parse(rendered.toLowerCase(Locale.ROOT).replace(",", ", "));
            if (!type.equals(loose)) {
                failures.add("type round trip (lower case): " + type.getName() + " -> '" + rendered + "' -> "
                        + (loose == null ? "null" : loose.getName()));
            }
        }
        for (Alias alias : typeAliases()) {
            DataType parsed = mapper.parse(alias.spelling);
            if (!alias.expected.equals(parsed)) {
                failures.add("alias '" + alias.spelling + "' parsed as "
                        + (parsed == null ? "null" : parsed.getName()) + ", expected "
                        + alias.expected.getName());
            }
        }
        for (String uninterpretable : new String[] {null, "", "   ", "ARRAY", "GEOMETRY", "POINT",
                "ROW(1 INT)", "MYSTERY_TYPE"}) {
            if (mapper.parse(uninterpretable) != null) {
                failures.add("parse('" + uninterpretable + "') should be null");
            }
        }
        System.out.println("    " + supportedTypes().size() + " canonical types round-tripped, "
                + typeAliases().size() + " aliases and " + 8 + " uninterpretable names checked");
        return failures;
    }

    /** Every canonical type the mapper claims to support. */
    public static List<DataType> supportedTypes() {
        List<DataType> types = new ArrayList<>();
        types.add(DataType.smallint());
        types.add(DataType.integer());
        types.add(DataType.bigint());
        types.add(DataType.decimal(1, 0));
        types.add(DataType.decimal(10, 2));
        types.add(DataType.decimal(38, 10));
        types.add(DataType.real());
        types.add(DataType.doublePrecision());
        types.add(DataType.charType(1));
        types.add(DataType.charType(5));
        types.add(DataType.varchar(1));
        types.add(DataType.varchar(20));
        types.add(DataType.varchar(255));
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
        types.add(DataType.arrayOf(DataType.varchar(5)));
        types.add(DataType.arrayOf(DataType.decimal(6, 3)));
        types.add(DataType.arrayOf(DataType.arrayOf(DataType.bigint())));
        types.add(DataType.enumOf(Arrays.asList("new", "doing", "done")));
        types.add(DataType.enumOf(Arrays.asList("it's", "plain")));
        return types;
    }

    /** One engine spelling and the canonical type it must parse to. */
    private static final class Alias {
        final String spelling;
        final DataType expected;

        Alias(String spelling, DataType expected) {
            this.spelling = spelling;
            this.expected = expected;
        }
    }

    private static List<Alias> typeAliases() {
        List<Alias> aliases = new ArrayList<>();
        aliases.add(new Alias("CHARACTER VARYING", DataType.of(DataType.Category.VARCHAR)));
        aliases.add(new Alias("CHARACTER VARYING(20)", DataType.varchar(20)));
        aliases.add(new Alias("VARCHAR2(20)", DataType.varchar(20)));
        aliases.add(new Alias("CHARACTER LARGE OBJECT", DataType.text()));
        aliases.add(new Alias("CLOB", DataType.text()));
        aliases.add(new Alias("TEXT", DataType.text()));
        aliases.add(new Alias("DOUBLE PRECISION", DataType.doublePrecision()));
        aliases.add(new Alias("DOUBLE", DataType.doublePrecision()));
        aliases.add(new Alias("FLOAT", DataType.doublePrecision()));
        aliases.add(new Alias("FLOAT8", DataType.doublePrecision()));
        aliases.add(new Alias("BINARY LARGE OBJECT", DataType.blob()));
        aliases.add(new Alias("BLOB", DataType.blob()));
        aliases.add(new Alias("BINARY VARYING(10)", DataType.blob()));
        aliases.add(new Alias("VARBINARY", DataType.blob()));
        aliases.add(new Alias("INT", DataType.integer()));
        aliases.add(new Alias("INT4", DataType.integer()));
        aliases.add(new Alias("INTEGER UNSIGNED", DataType.integer()));
        aliases.add(new Alias("NUMERIC", DataType.of(DataType.Category.DECIMAL)));
        aliases.add(new Alias("NUMERIC(10,2)", DataType.decimal(10, 2)));
        aliases.add(new Alias("DECIMAL(4, 1)", DataType.decimal(4, 1)));
        aliases.add(new Alias("TIMESTAMP WITH TIMEZONE", DataType.timestampWithTimeZone()));
        aliases.add(new Alias("TIMESTAMPTZ", DataType.timestampWithTimeZone()));
        aliases.add(new Alias("DATETIME", DataType.timestamp()));
        aliases.add(new Alias("INTEGER ARRAY", DataType.arrayOf(DataType.integer())));
        aliases.add(new Alias("INTEGER ARRAY[10]", DataType.arrayOf(DataType.integer())));
        aliases.add(new Alias("CHARACTER VARYING(5) ARRAY", DataType.arrayOf(DataType.varchar(5))));
        aliases.add(new Alias("ENUM('a','b')", DataType.enumOf(Arrays.asList("a", "b"))));
        aliases.add(new Alias("ENUM('a''b')", DataType.enumOf(Arrays.asList("a'b"))));
        aliases.add(new Alias("INTERVAL DAY TO SECOND", DataType.interval()));
        aliases.add(new Alias("INTERVAL YEAR", DataType.interval()));
        aliases.add(new Alias("INTERVAL", DataType.interval()));
        aliases.add(new Alias("TINYINT", DataType.smallint()));
        aliases.add(new Alias("BOOL", DataType.bool()));
        aliases.add(new Alias("JSONB", DataType.json()));
        return aliases;
    }

    /* ================================================================== 3. types through H2 */

    /**
     * Creates a table with one column per canonical type, executes it, introspects it and compares
     * the introspected types with the intended ones. This proves the <em>catalog</em> side of the
     * mapper, including the {@code ELEMENT_TYPES} and {@code ENUM_VALUES} lookups for arrays and
     * enums and the {@code INTERVAL_TYPE} qualifier.
     */
    public static List<String> checkTypeRoundTrip() {
        List<String> failures = new ArrayList<>();
        try (DDLsmithDBConnection connection = connect("ddlsmith_types")) {
            RelationalSchema intended = new RelationalSchema(SCHEMA);
            Table table = new Table(SCHEMA, "all_types");
            List<DataType> types = new ArrayList<>();
            for (DataType type : supportedTypes()) {
                if (type.getCategory() == DataType.Category.ARRAY
                        && type.getElementType().getCategory() == DataType.Category.ARRAY) {
                    // nested arrays parse but are not accepted in an H2 column definition
                    continue;
                }
                types.add(type);
            }
            for (int i = 0; i < types.size(); i++) {
                table.addColumn(new Column("c" + i, types.get(i), true));
            }
            intended.addTable(table);
            execute(connection, new CreateTable(table));
            failures.addAll(compare("all supported types", intended, introspect(connection)));
            System.out.println("    " + types.size() + " types created and introspected");
        } catch (SQLException e) {
            failures.add("type round trip failed: " + e);
        }
        return failures;
    }

    /* ================================================================== 4. schema round trip */

    /**
     * The schema used by both the round-trip check and the JUnit test: six tables covering a
     * composite primary key, a self-referential foreign key with {@code ON DELETE CASCADE} and
     * {@code ON UPDATE SET NULL}, a composite foreign key, named unique and check constraints, a
     * nullable column with a default, an identity column, a generated column, a plain index, a
     * unique index, two views, a sequence and table/column comments.
     */
    public static RelationalSchema selfCheckSchema() {
        RelationalSchema schema = new RelationalSchema(SCHEMA);

        Table dept = new Table(SCHEMA, "dept");
        dept.addColumn(new Column("dept_id", DataType.integer(), false));
        dept.addColumn(new Column("code", DataType.varchar(10), false));
        dept.addColumn(new Column("budget", DataType.decimal(12, 2), true).setDefaultExpression("0.00"));
        dept.addColumn(new Column("opened", DataType.date(), true));
        dept.addColumn(new Column("label", DataType.text(), true));
        dept.addColumn(new Column("region", DataType.varchar(20), true));
        dept.setPrimaryKey(new PrimaryKey("pk_dept", List.of("dept_id")));
        dept.addUniqueConstraint(new UniqueConstraint("uq_dept_code", List.of("code")));
        dept.addCheckConstraint(new CheckConstraint("ck_dept_budget", "budget >= 0"));
        dept.setComment("departments");
        dept.getColumn("code").setComment("business code");
        schema.addTable(dept);

        Table emp = new Table(SCHEMA, "emp");
        emp.addColumn(new Column("emp_id", DataType.integer(), false).setIdentity(true));
        emp.addColumn(new Column("name", DataType.varchar(40), false));
        emp.addColumn(new Column("dept_id", DataType.integer(), true));
        emp.addColumn(new Column("mentor_id", DataType.integer(), true));
        emp.addColumn(new Column("salary", DataType.decimal(10, 2), true));
        emp.addColumn(new Column("bonus", DataType.decimal(12, 2), true).setGeneratedExpression("salary * 2"));
        emp.addColumn(new Column("hired", DataType.timestamp(), true));
        emp.addColumn(new Column("note", DataType.varchar(50), true).setDefaultExpression("'n/a'"));
        emp.setPrimaryKey(new PrimaryKey("pk_emp", List.of("emp_id")));
        emp.addForeignKey(new ForeignKey(List.of("dept_id"), "dept", List.of("dept_id"))
                .setName("fk_emp_dept").setOnDelete(ReferentialAction.SET_NULL)
                .setOnUpdate(ReferentialAction.CASCADE));
        // self-referential foreign key: ON DELETE CASCADE and ON UPDATE SET NULL
        emp.addForeignKey(new ForeignKey(List.of("mentor_id"), "emp", List.of("emp_id"))
                .setName("fk_emp_mentor").setOnDelete(ReferentialAction.CASCADE)
                .setOnUpdate(ReferentialAction.SET_NULL).setSelfReferential(true));
        emp.addIndex(new Index("idx_emp_name", List.of("name")));
        emp.addIndex(new Index("uidx_emp_name_hired", List.of("name", "hired")).setUnique(true));
        emp.getColumn("note").setComment("free text note");
        schema.addTable(emp);

        Table proj = new Table(SCHEMA, "proj");
        proj.addColumn(new Column("proj_id", DataType.integer(), false));
        proj.addColumn(new Column("part_no", DataType.smallint(), false));
        proj.addColumn(new Column("title", DataType.varchar(40), false));
        proj.addColumn(new Column("lead_id", DataType.integer(), true));
        proj.addColumn(new Column("weight", DataType.doublePrecision(), true));
        proj.setPrimaryKey(new PrimaryKey("pk_proj", List.of("proj_id", "part_no")));
        proj.addForeignKey(new ForeignKey(List.of("lead_id"), "emp", List.of("emp_id"))
                .setName("fk_proj_lead").setOnDelete(ReferentialAction.SET_NULL));
        schema.addTable(proj);

        Table assignment = new Table(SCHEMA, "assignment");
        assignment.addColumn(new Column("emp_id", DataType.integer(), false));
        assignment.addColumn(new Column("proj_id", DataType.integer(), false));
        assignment.addColumn(new Column("part_no", DataType.smallint(), false));
        assignment.addColumn(new Column("role", DataType.varchar(20), true).setDefaultExpression("'dev'"));
        assignment.addColumn(new Column("hours", DataType.real(), true));
        assignment.setPrimaryKey(new PrimaryKey("pk_assignment", List.of("emp_id", "proj_id", "part_no")));
        // composite foreign key
        assignment.addForeignKey(new ForeignKey(List.of("proj_id", "part_no"), "proj", List.of("proj_id", "part_no"))
                .setName("fk_assignment_proj").setOnDelete(ReferentialAction.CASCADE)
                .setOnUpdate(ReferentialAction.CASCADE));
        assignment.addForeignKey(new ForeignKey(List.of("emp_id"), "emp", List.of("emp_id"))
                .setName("fk_assignment_emp").setOnDelete(ReferentialAction.CASCADE));
        assignment.addUniqueConstraint(new UniqueConstraint("uq_assignment_role", List.of("emp_id", "role")));
        assignment.addCheckConstraint(new CheckConstraint("ck_assignment_hours", "hours >= 0"));
        schema.addTable(assignment);

        Table auditLog = new Table(SCHEMA, "audit_log");
        auditLog.addColumn(new Column("log_id", DataType.bigint(), false));
        auditLog.addColumn(new Column("uid", DataType.uuid(), true));
        auditLog.addColumn(new Column("payload", DataType.json(), true));
        auditLog.addColumn(new Column("at", DataType.timestampWithTimeZone(), true));
        auditLog.addColumn(new Column("ok", DataType.bool(), true).setDefaultExpression("FALSE"));
        auditLog.addColumn(new Column("tag", DataType.charType(3), true));
        auditLog.addColumn(new Column("body", DataType.text(), true));
        auditLog.addColumn(new Column("raw", DataType.blob(), true));
        auditLog.addColumn(new Column("t", DataType.time(), true));
        auditLog.addColumn(new Column("d", DataType.date(), true));
        auditLog.addColumn(new Column("n", DataType.smallint(), true));
        auditLog.addColumn(new Column("x", DataType.doublePrecision(), true));
        auditLog.addColumn(new Column("y", DataType.real(), true));
        auditLog.setPrimaryKey(new PrimaryKey("pk_audit_log", List.of("log_id")));
        schema.addTable(auditLog);

        Table task = new Table(SCHEMA, "task");
        task.addColumn(new Column("proj_id", DataType.integer(), false));
        task.addColumn(new Column("part_no", DataType.smallint(), false));
        task.addColumn(new Column("seq", DataType.integer(), false));
        task.addColumn(new Column("state", DataType.enumOf(Arrays.asList("new", "doing", "done")), true)
                .setDefaultExpression("'new'"));
        task.addColumn(new Column("priority", DataType.smallint(), true).setDefaultExpression("3"));
        task.addColumn(new Column("tags", DataType.arrayOf(DataType.integer()), true));
        task.addColumn(new Column("due", DataType.timestamp(), true));
        task.setPrimaryKey(new PrimaryKey("pk_task", List.of("proj_id", "part_no", "seq")));
        task.addForeignKey(new ForeignKey(List.of("proj_id", "part_no"), "proj", List.of("proj_id", "part_no"))
                .setName("fk_task_proj"));
        task.addUniqueConstraint(new UniqueConstraint("uq_task_due", List.of("proj_id", "due")));
        task.addCheckConstraint(new CheckConstraint("ck_task_priority", "priority > 0"));
        schema.addTable(task);

        schema.addView(new View("v_emp_names", "SELECT name, salary FROM emp WHERE salary > 0"));
        View deptCodes = new View("v_dept_codes", "SELECT code FROM dept");
        deptCodes.getColumnNames().add("dept_code");
        schema.addView(deptCodes);
        // a compound WHERE clause: H2 parenthesizes every operand and casts the literal
        schema.addView(new View("v_emp_active",
                "SELECT name FROM emp WHERE salary > 0 AND dept_id IS NOT NULL"));

        schema.addSequence(new Sequence("seq_task").setStart(100).setIncrement(5)
                .setMinValue(1L).setMaxValue(10000L).setCycle(true));
        return schema;
    }

    /** Every statement the dialect produces for a schema, in dependency order. */
    public static List<String> statementsFor(RelationalSchema schema) {
        List<String> statements = new ArrayList<>();
        for (Table table : schema.getTables()) {
            statements.addAll(DIALECT.renderAll(new CreateTable(table)));
        }
        for (Table table : schema.getTables()) {
            for (Index index : table.getIndexes()) {
                statements.add(DIALECT.render(new CreateIndex(table.getName(), index)));
            }
        }
        for (View view : schema.getViews()) {
            statements.add(DIALECT.render(new CreateView(view)));
        }
        for (Sequence sequence : schema.getSequences()) {
            statements.add(DIALECT.render(new CreateSequence(sequence)));
        }
        return statements;
    }

    public static List<String> checkSchemaRoundTrip() {
        List<String> failures = new ArrayList<>();
        try (DDLsmithDBConnection connection = connect("ddlsmith_schema")) {
            RelationalSchema intended = selfCheckSchema();
            for (String sql : statementsFor(intended)) {
                try {
                    connection.executeStatement(sql);
                } catch (SQLException e) {
                    failures.add("statement failed: " + sql + "\n      " + e.getMessage().split("\n")[0]);
                }
            }
            if (!failures.isEmpty()) {
                return failures;
            }
            RelationalSchema actual = introspect(connection);
            failures.addAll(compare("schema round trip", intended, actual));
            failures.addAll(checkComments(intended, actual));
            System.out.println("    " + intended.getTables().size() + " tables, "
                    + intended.getViews().size() + " views, " + intended.getSequences().size()
                    + " sequence, " + statementsFor(intended).size() + " statements");
            System.out.println("    introspected statistics: " + actual.getStatistics());
        } catch (SQLException e) {
            failures.add("schema round trip failed: " + e);
        }
        return failures;
    }

    /**
     * {@code SchemaNormalizer.erase} does not carry column comments over, so they are asserted
     * separately; table comments are part of the diff itself.
     */
    private static List<String> checkComments(RelationalSchema intended, RelationalSchema actual) {
        List<String> failures = new ArrayList<>();
        for (Table table : intended.getTables()) {
            Table actualTable = actual.getTable(table.getName());
            if (actualTable == null) {
                continue;
            }
            for (Column column : table.getColumns()) {
                Column actualColumn = actualTable.getColumn(column.getName());
                if (actualColumn == null) {
                    continue;
                }
                if (!java.util.Objects.equals(column.getComment(), actualColumn.getComment())) {
                    failures.add("comment round trip: " + table.getName() + "." + column.getName()
                            + " intended " + column.getComment() + ", actual " + actualColumn.getComment());
                }
            }
        }
        return failures;
    }

    /* ================================================================== 5. expression normalization */

    /**
     * H2 re-prints check clauses with parentheses around every boolean and comparison operand.
     * {@link H2ExpressionNormalizer} removes exactly those; this check drives real H2 clauses through
     * it and asserts the canonical forms match. The cases H2 rewrites semantically (negation
     * elimination, operand reordering, function renaming, value-list spacing) are printed as
     * documented limitations rather than asserted.
     */
    public static List<String> checkExpressionNormalization() {
        List<String> failures = new ArrayList<>();
        List<String[]> fixable = new ArrayList<>();
        fixable.add(new String[] {"a > 0", "true"});
        fixable.add(new String[] {"a > 0 AND a < 100", "true"});
        fixable.add(new String[] {"a + b > 0", "true"});
        fixable.add(new String[] {"(a + b) * c > 0", "true"});
        fixable.add(new String[] {"a IS NOT NULL", "true"});
        fixable.add(new String[] {"a IS NULL OR b IS NULL", "true"});
        fixable.add(new String[] {"a >= 0 AND (b > 0 OR c > 0)", "true"});
        fixable.add(new String[] {"a * 2 + 1 < 10", "true"});
        fixable.add(new String[] {"a BETWEEN 1 AND 10", "true"});
        fixable.add(new String[] {"d <> 'x'", "true"});
        // H2 adds an explicit cast around a literal whose type differs from the column's
        fixable.add(new String[] {"n >= 0", "true"});
        fixable.add(new String[] {"n >= 1.5", "true"});
        fixable.add(new String[] {"n >= -2", "true"});
        fixable.add(new String[] {"r >= 0", "true"});
        fixable.add(new String[] {"r >= 1.5", "true"});
        fixable.add(new String[] {"r * 2 > 1", "true"});
        fixable.add(new String[] {"n >= 0 AND r >= 0", "true"});
        // a function call must keep its argument list, and a cast around a column must survive
        fixable.add(new String[] {"CHAR_LENGTH(d) > 2", "true"});
        fixable.add(new String[] {"UPPER(d) <> 'X'", "true"});
        fixable.add(new String[] {"CAST(a AS BIGINT) > 0", "true"});
        List<String[]> limitations = new ArrayList<>();
        limitations.add(new String[] {"NOT (a > 0)", "negation elimination"});
        limitations.add(new String[] {"a > 0 AND b < 5 OR c = 1", "operand reordering"});
        limitations.add(new String[] {"a IN (1, 2, 3)", "whitespace before the value list"});
        limitations.add(new String[] {"LENGTH(d) > 2", "function name canonicalization"});
        limitations.add(new String[] {"r >= 0.0", "a literal written with a zero fraction"});

        String columns = " (a INT, b INT, c INT, d VARCHAR(10), n DECIMAL(12,2), r REAL,";
        try (DDLsmithDBConnection connection = connect("ddlsmith_expr")) {
            int index = 0;
            for (String[] entry : fixable) {
                boolean mustSurvive = Boolean.parseBoolean(entry[1]);
                String constraintName = "ck_expr_" + index;
                connection.executeStatement("CREATE TABLE expr_" + index + columns
                        + " CONSTRAINT " + constraintName + " CHECK (" + entry[0] + "))");
                String clause = checkClause(connection, constraintName);
                String normalized = SchemaNormalizer.canonicalExpression(
                        H2ExpressionNormalizer.normalize(clause));
                String wanted = SchemaNormalizer.canonicalExpression(entry[0]);
                boolean equal = wanted.equals(normalized);
                System.out.println("    " + (equal ? "ok  " : "DIFF") + "  " + pad(entry[0])
                        + " h2=" + pad(clause.replace("\n", " ")) + " -> " + normalized);
                if (mustSurvive && !equal) {
                    failures.add("check expression normalization: '" + entry[0] + "' came back as '" + normalized
                            + "' (H2 stored '" + clause.replace("\n", " ") + "')");
                }
                index++;
            }
            for (String[] entry : limitations) {
                String constraintName = "ck_expr_" + index;
                connection.executeStatement("CREATE TABLE expr_" + index + columns
                        + " CONSTRAINT " + constraintName + " CHECK (" + entry[0] + "))");
                String clause = checkClause(connection, constraintName);
                String normalized = SchemaNormalizer.canonicalExpression(
                        H2ExpressionNormalizer.normalize(clause));
                String wanted = SchemaNormalizer.canonicalExpression(entry[0]);
                System.out.println("    known limitation (" + entry[1] + "): " + pad(entry[0])
                        + " h2=" + pad(clause.replace("\n", " ")) + " -> " + normalized
                        + (wanted.equals(normalized) ? "  (matches)" : "  (does not match)"));
                index++;
            }
        } catch (SQLException e) {
            failures.add("expression normalization check failed: " + e);
        }
        return failures;
    }

    private static String checkClause(DDLsmithDBConnection connection, String constraintName) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.getConnection().prepareStatement(
                "SELECT CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS WHERE CONSTRAINT_NAME = ?")) {
            statement.setString(1, constraintName.toUpperCase(Locale.ROOT));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    /* ================================================================== 6. literals */

    public static List<String> checkLiterals() {
        List<String> failures = new ArrayList<>();
        try (DDLsmithDBConnection connection = connect("ddlsmith_literals")) {
            connection.executeStatement("CREATE TABLE lit (d DATE, t TIME, ts TIMESTAMP, tz TIMESTAMP WITH TIME ZONE,"
                    + " b BINARY LARGE OBJECT, u UUID, j JSON, n DECIMAL(10,2), f BOOLEAN, s VARCHAR(20),"
                    + " i INTEGER, e ENUM('x','y'))");
            DataType[] types = {DataType.date(), DataType.time(), DataType.timestamp(),
                    DataType.timestampWithTimeZone(), DataType.blob(), DataType.uuid(), DataType.json(),
                    DataType.decimal(10, 2), DataType.bool(), DataType.varchar(20), DataType.integer(),
                    DataType.enumOf(Arrays.asList("x", "y"))};
            Object[] values = {LocalDate.of(2020, 1, 2), LocalTime.of(3, 4, 5),
                    LocalDateTime.of(2020, 1, 2, 3, 4, 5),
                    OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2)),
                    new byte[] {0x0A, 0x1B}, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                    "{\"a\":1}", new BigDecimal("-12.34"), Boolean.TRUE, "it's", 42, "x"};
            StringBuilder sql = new StringBuilder("INSERT INTO lit VALUES (");
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(DIALECT.renderLiteral(values[i], types[i]));
            }
            sql.append(')');
            System.out.println("    " + sql);
            connection.executeStatement(sql.toString());
            try (Statement statement = connection.getConnection().createStatement();
                    ResultSet rs = statement.executeQuery("SELECT * FROM lit")) {
                if (!rs.next()) {
                    failures.add("literal insert produced no row");
                    return failures;
                }
                check(failures, "DATE", LocalDate.of(2020, 1, 2), rs.getObject(1, LocalDate.class));
                check(failures, "TIME", LocalTime.of(3, 4, 5), rs.getObject(2, LocalTime.class));
                check(failures, "TIMESTAMP", LocalDateTime.of(2020, 1, 2, 3, 4, 5),
                        rs.getObject(3, LocalDateTime.class));
                OffsetDateTime tz = rs.getObject(4, OffsetDateTime.class);
                if (tz == null || !tz.toInstant().equals(OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0,
                        ZoneOffset.ofHours(2)).toInstant())) {
                    failures.add("TIMESTAMPTZ literal: got " + tz);
                }
                if (!Arrays.equals(new byte[] {0x0A, 0x1B}, rs.getBytes(5))) {
                    failures.add("BLOB literal: got " + Arrays.toString(rs.getBytes(5)));
                }
                check(failures, "UUID", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), rs.getObject(6));
                if (rs.getString(7) == null || !rs.getString(7).contains("a")) {
                    failures.add("JSON literal: got " + rs.getString(7));
                }
                if (rs.getBigDecimal(8) == null || rs.getBigDecimal(8).compareTo(new BigDecimal("-12.34")) != 0) {
                    failures.add("DECIMAL literal: got " + rs.getBigDecimal(8));
                }
                check(failures, "BOOLEAN", Boolean.TRUE, rs.getBoolean(9));
                check(failures, "VARCHAR", "it's", rs.getString(10));
                check(failures, "INTEGER", 42, rs.getInt(11));
                check(failures, "ENUM", "x", rs.getString(12));
            }
            if (!"NULL".equals(DIALECT.renderLiteral(null, DataType.integer()))) {
                failures.add("renderLiteral(null) must be NULL");
            }
            for (String[] entry : new String[][] {{"TRUE", DIALECT.renderLiteral(Boolean.TRUE, DataType.bool())},
                    {"FALSE", DIALECT.renderLiteral(Boolean.FALSE, DataType.bool())},
                    {"FALSE", DIALECT.renderLiteral("no", DataType.bool())}}) {
                if (!entry[0].equals(entry[1])) {
                    failures.add("boolean literal: expected " + entry[0] + ", got " + entry[1]);
                }
            }
            System.out.println("    all literals accepted and read back");
        } catch (SQLException e) {
            failures.add("literal check failed: " + e);
        }
        return failures;
    }

    private static void check(List<String> failures, String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures.add(label + " literal: expected " + expected + " (" + expected.getClass().getSimpleName()
                    + "), got " + actual + (actual == null ? "" : " (" + actual.getClass().getSimpleName() + ")"));
        }
    }

    /* ================================================================== 7. feature probes */

    /**
     * Runs each uncertain construct against H2, prints the observation and asserts that
     * {@link H2Dialect#getFeatures()} agrees with it. Constructs H2 accepts but ignores, breaks or
     * cannot report back are probed semantically rather than syntactically (see
     * {@code semanticProbes}).
     */
    public static List<String> checkFeatureProbes() {
        List<String> failures = new ArrayList<>();
        try (DDLsmithDBConnection connection = connect("ddlsmith_features")) {
            connection.executeStatement("CREATE TABLE probe (a INT NOT NULL, b INT, c VARCHAR(10))");
            connection.executeStatement("CREATE TABLE probe_u (a INT NOT NULL, b INT, CONSTRAINT uq_probe UNIQUE (a))");

            System.out.println("    syntax probes (accepted / rejected):");
            syntaxProbe(failures, connection, DDLFeature.CREATE_INDEX_IF_NOT_EXISTS, true,
                    "CREATE INDEX IF NOT EXISTS", "CREATE INDEX IF NOT EXISTS idx_ine ON probe (a)");
            syntaxProbe(failures, connection, DDLFeature.CREATE_UNIQUE_INDEX, true,
                    "CREATE UNIQUE INDEX", "CREATE UNIQUE INDEX idx_uniq ON probe (b)");
            syntaxProbe(failures, connection, DDLFeature.CREATE_INDEX, true,
                    "CREATE INDEX", "CREATE INDEX idx_plain ON probe (c)");
            syntaxProbe(failures, connection, DDLFeature.DROP_INDEX_IF_EXISTS, true,
                    "DROP INDEX IF EXISTS", "DROP INDEX IF EXISTS idx_ine");
            syntaxProbe(failures, connection, DDLFeature.INDEX_METHOD, false,
                    "CREATE INDEX USING GIN", "CREATE INDEX idx_gin ON probe USING GIN (a)");
            syntaxProbe(failures, connection, DDLFeature.EXPRESSION_INDEX, false,
                    "expression index", "CREATE INDEX idx_expr ON probe ((a + 1))");
            syntaxProbe(failures, connection, DDLFeature.PARTIAL_INDEX, false,
                    "partial index", "CREATE INDEX idx_partial ON probe (a) WHERE a > 0");
            syntaxProbe(failures, connection, DDLFeature.INCLUDE_COLUMNS, false,
                    "INCLUDE columns", "CREATE INDEX idx_include ON probe (a) INCLUDE (b)");
            syntaxProbe(failures, connection, DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE, false,
                    "DROP INDEX table.index", "DROP INDEX probe.idx_plain");
            syntaxProbe(failures, connection, DDLFeature.ALTER_ADD_COLUMN, true,
                    "ALTER TABLE ADD COLUMN", "ALTER TABLE probe ADD COLUMN added INT");
            syntaxProbe(failures, connection, DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS, true,
                    "ALTER TABLE ADD COLUMN IF NOT EXISTS", "ALTER TABLE probe ADD COLUMN IF NOT EXISTS added INT");
            syntaxProbe(failures, connection, DDLFeature.ALTER_DROP_COLUMN, true,
                    "ALTER TABLE DROP COLUMN", "ALTER TABLE probe DROP COLUMN added");
            syntaxProbe(failures, connection, DDLFeature.ALTER_DROP_COLUMN_CASCADE, false,
                    "ALTER TABLE DROP COLUMN CASCADE", "ALTER TABLE probe DROP COLUMN added CASCADE");
            syntaxProbe(failures, connection, DDLFeature.ALTER_RENAME_COLUMN, true,
                    "ALTER TABLE RENAME COLUMN", "ALTER TABLE probe RENAME COLUMN c TO c2");
            syntaxProbe(failures, connection, DDLFeature.ALTER_COLUMN_TYPE, true,
                    "ALTER COLUMN SET DATA TYPE", "ALTER TABLE probe ALTER COLUMN b SET DATA TYPE BIGINT");
            syntaxProbe(failures, connection, DDLFeature.ALTER_COLUMN_TYPE_USING, true,
                    "ALTER COLUMN SET DATA TYPE USING",
                    "ALTER TABLE probe ALTER COLUMN b SET DATA TYPE INTEGER USING CAST(b AS INTEGER)");
            syntaxProbe(failures, connection, DDLFeature.ALTER_SET_NOT_NULL, true,
                    "ALTER COLUMN SET NOT NULL", "ALTER TABLE probe ALTER COLUMN b SET NOT NULL");
            syntaxProbe(failures, connection, DDLFeature.ALTER_DROP_NOT_NULL, true,
                    "ALTER COLUMN DROP NOT NULL", "ALTER TABLE probe ALTER COLUMN b DROP NOT NULL");
            syntaxProbe(failures, connection, DDLFeature.ALTER_SET_DEFAULT, true,
                    "ALTER COLUMN SET DEFAULT", "ALTER TABLE probe ALTER COLUMN b SET DEFAULT 7");
            syntaxProbe(failures, connection, DDLFeature.ALTER_DROP_DEFAULT, true,
                    "ALTER COLUMN DROP DEFAULT", "ALTER TABLE probe ALTER COLUMN b DROP DEFAULT");
            syntaxProbe(failures, connection, DDLFeature.ALTER_ADD_CONSTRAINT, true,
                    "ALTER TABLE ADD CONSTRAINT", "ALTER TABLE probe ADD CONSTRAINT ck_probe CHECK (a > 0)");
            syntaxProbe(failures, connection, DDLFeature.ALTER_DROP_CONSTRAINT, true,
                    "ALTER TABLE DROP CONSTRAINT", "ALTER TABLE probe DROP CONSTRAINT ck_probe");
            syntaxProbe(failures, connection, DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS, true,
                    "DROP CONSTRAINT IF EXISTS", "ALTER TABLE probe DROP CONSTRAINT IF EXISTS ck_probe");
            syntaxProbe(failures, connection, DDLFeature.ALTER_ADD_PRIMARY_KEY, true,
                    "ALTER TABLE ADD PRIMARY KEY", "ALTER TABLE probe ADD CONSTRAINT pk_probe PRIMARY KEY (a)");
            syntaxProbe(failures, connection, DDLFeature.ALTER_DROP_PRIMARY_KEY, true,
                    "ALTER TABLE DROP PRIMARY KEY", "ALTER TABLE probe DROP PRIMARY KEY");
            syntaxProbe(failures, connection, DDLFeature.ALTER_ADD_FOREIGN_KEY, true,
                    "ALTER TABLE ADD FOREIGN KEY",
                    "ALTER TABLE probe ADD CONSTRAINT fk_probe FOREIGN KEY (b) REFERENCES probe_u (a)");
            syntaxProbe(failures, connection, DDLFeature.ALTER_DROP_FOREIGN_KEY, true,
                    "ALTER TABLE DROP FOREIGN KEY", "ALTER TABLE probe DROP CONSTRAINT fk_probe");
            syntaxProbe(failures, connection, DDLFeature.ALTER_ADD_UNIQUE, true,
                    "ALTER TABLE ADD UNIQUE", "ALTER TABLE probe ADD CONSTRAINT uq_probe2 UNIQUE (b)");
            syntaxProbe(failures, connection, DDLFeature.ALTER_ADD_CHECK, true,
                    "ALTER TABLE ADD CHECK", "ALTER TABLE probe ADD CONSTRAINT ck_probe2 CHECK (b >= 0)");
            syntaxProbe(failures, connection, DDLFeature.ALTER_MULTIPLE_ACTIONS, false,
                    "ALTER TABLE with two actions",
                    "ALTER TABLE probe ADD COLUMN m1 INT, ADD COLUMN m2 INT");
            syntaxProbe(failures, connection, DDLFeature.ALTER_RENAME_TABLE, true,
                    "ALTER TABLE RENAME TO", "ALTER TABLE probe RENAME TO probe_renamed");
            syntaxProbe(failures, connection, DDLFeature.CREATE_OR_REPLACE_VIEW, true,
                    "CREATE OR REPLACE VIEW", "CREATE OR REPLACE VIEW v_probe AS SELECT a FROM probe_renamed");
            syntaxProbe(failures, connection, DDLFeature.VIEW_COLUMN_LIST, true,
                    "CREATE VIEW with column list",
                    "CREATE VIEW v_probe_cols (x) AS SELECT a FROM probe_renamed");
            syntaxProbe(failures, connection, DDLFeature.VIEW_CHECK_OPTION, false,
                    "CREATE VIEW WITH CHECK OPTION",
                    "CREATE VIEW v_probe_ck AS SELECT a FROM probe_renamed WITH LOCAL CHECK OPTION");
            syntaxProbe(failures, connection, DDLFeature.TEMPORARY_TABLE, true,
                    "CREATE TEMPORARY TABLE", "CREATE TEMPORARY TABLE tmp_probe (a INT)");
            syntaxProbe(failures, connection, DDLFeature.TRUNCATE_TABLE, true,
                    "TRUNCATE TABLE", "TRUNCATE TABLE probe_renamed");
            syntaxProbe(failures, connection, DDLFeature.TRUNCATE_CASCADE, false,
                    "TRUNCATE TABLE CASCADE", "TRUNCATE TABLE probe_renamed CASCADE");
            syntaxProbe(failures, connection, DDLFeature.CREATE_SEQUENCE, true,
                    "CREATE SEQUENCE", "CREATE SEQUENCE seq_probe START WITH 1");
            syntaxProbe(failures, connection, DDLFeature.SEQUENCE_OPTIONS, true,
                    "CREATE SEQUENCE with options",
                    "CREATE SEQUENCE seq_probe2 START WITH 5 INCREMENT BY 2 MINVALUE 1 MAXVALUE 99 CYCLE");
            syntaxProbe(failures, connection, DDLFeature.DROP_SEQUENCE, true,
                    "DROP SEQUENCE", "DROP SEQUENCE seq_probe2");
            connection.executeStatement("CREATE TABLE dropme (a INT)");
            syntaxProbe(failures, connection, DDLFeature.CASCADE_DROP, true,
                    "DROP TABLE IF EXISTS CASCADE", "DROP TABLE IF EXISTS dropme CASCADE");
            connection.executeStatement("CREATE TABLE cascade_view (a INT)");
            connection.executeStatement("CREATE VIEW cascade_view_v AS SELECT a FROM cascade_view");
            syntaxProbe(failures, connection, DDLFeature.CASCADE_DROP, true,
                    "DROP VIEW IF EXISTS CASCADE", "DROP VIEW IF EXISTS cascade_view_v CASCADE");
            syntaxProbe(failures, connection, DDLFeature.COLLATION, false,
                    "column COLLATE", "CREATE TABLE coll (a VARCHAR(10) COLLATE ENGLISH)");
            syntaxProbe(failures, connection, DDLFeature.GENERATED_STORED, false,
                    "GENERATED ... STORED", "CREATE TABLE gs (a INT, b INT GENERATED ALWAYS AS (a + 1) STORED)");
            syntaxProbe(failures, connection, DDLFeature.GENERATED_VIRTUAL, false,
                    "GENERATED ... VIRTUAL", "CREATE TABLE gv (a INT, b INT GENERATED ALWAYS AS (a + 1) VIRTUAL)");
            syntaxProbe(failures, connection, DDLFeature.GENERATED_COLUMN, true,
                    "GENERATED ALWAYS AS (expr)", "CREATE TABLE gc (a INT, b INT GENERATED ALWAYS AS (a + 1))");
            syntaxProbe(failures, connection, DDLFeature.IDENTITY_COLUMN, true,
                    "GENERATED BY DEFAULT AS IDENTITY",
                    "CREATE TABLE idc (a INT GENERATED BY DEFAULT AS IDENTITY)");
            syntaxProbe(failures, connection, DDLFeature.AUTO_INCREMENT, true,
                    "AUTO_INCREMENT", "CREATE TABLE aic (a INT AUTO_INCREMENT)");
            syntaxProbe(failures, connection, DDLFeature.SERIAL, true,
                    "SERIAL", "CREATE TABLE serc (a SERIAL)");
            syntaxProbe(failures, connection, DDLFeature.DEFAULT_CURRENT_TIMESTAMP, true,
                    "DEFAULT CURRENT_TIMESTAMP", "CREATE TABLE dts (a TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            syntaxProbe(failures, connection, DDLFeature.NULLS_DISTINCT, true,
                    "UNIQUE NULLS NOT DISTINCT",
                    "CREATE TABLE nd (a INT, b INT, CONSTRAINT uq_nd UNIQUE NULLS NOT DISTINCT (a, b))");
            syntaxProbe(failures, connection, DDLFeature.INLINE_UNIQUE, true,
                    "inline UNIQUE", "CREATE TABLE iu (a INT UNIQUE)");
            syntaxProbe(failures, connection, DDLFeature.INLINE_CHECK, true,
                    "inline CHECK", "CREATE TABLE ic (a INT CHECK (a > 0))");
            syntaxProbe(failures, connection, DDLFeature.COLUMN_LEVEL_PRIMARY_KEY, true,
                    "column level PRIMARY KEY", "CREATE TABLE cpk (a INT PRIMARY KEY)");
            syntaxProbe(failures, connection, DDLFeature.TABLE_COMMENT, true,
                    "COMMENT ON TABLE", "CREATE TABLE cmt (a INT)");
            syntaxProbe(failures, connection, DDLFeature.TABLE_COMMENT, true,
                    "COMMENT ON TABLE (statement)", "COMMENT ON TABLE cmt IS 'a table'");
            syntaxProbe(failures, connection, DDLFeature.COLUMN_COMMENT, true,
                    "COMMENT ON COLUMN", "COMMENT ON COLUMN cmt.a IS 'a column'");
            syntaxProbe(failures, connection, DDLFeature.QUALIFIED_NAMES, true,
                    "CREATE SCHEMA IF NOT EXISTS", "CREATE SCHEMA IF NOT EXISTS probe_schema");
            syntaxProbe(failures, connection, DDLFeature.QUALIFIED_NAMES, true,
                    "schema qualified table", "CREATE TABLE probe_schema.qualified (a INT)");
            syntaxProbe(failures, connection, DDLFeature.DROP_SCHEMA, true,
                    "DROP SCHEMA", "DROP SCHEMA IF EXISTS probe_schema CASCADE");
            syntaxProbe(failures, connection, DDLFeature.DROP_SCHEMA_CASCADE, true,
                    "DROP SCHEMA ... CASCADE", "DROP SCHEMA IF EXISTS probe_schema CASCADE");
            syntaxProbe(failures, connection, DDLFeature.FK_SET_DEFAULT, true,
                    "FK ON DELETE SET DEFAULT",
                    "ALTER TABLE probe_renamed ADD CONSTRAINT fk_setdef FOREIGN KEY (b) REFERENCES probe_u (a)"
                            + " ON DELETE SET DEFAULT");
            syntaxProbe(failures, connection, DDLFeature.FK_CASCADE, true,
                    "FK ON UPDATE CASCADE",
                    "ALTER TABLE probe_renamed ADD CONSTRAINT fk_casc FOREIGN KEY (b) REFERENCES probe_u (a)"
                            + " ON UPDATE CASCADE");
            syntaxProbe(failures, connection, DDLFeature.FK_SET_NULL, true,
                    "FK ON DELETE SET NULL",
                    "ALTER TABLE probe_renamed ADD CONSTRAINT fk_setnull FOREIGN KEY (b) REFERENCES probe_u (a)"
                            + " ON DELETE SET NULL");
            syntaxProbe(failures, connection, DDLFeature.INITIALLY_DEFERRED, false,
                    "FK INITIALLY DEFERRED",
                    "ALTER TABLE probe_renamed ADD CONSTRAINT fk_defer FOREIGN KEY (b) REFERENCES probe_u (a)"
                            + " DEFERRABLE INITIALLY DEFERRED");
            syntaxProbe(failures, connection, DDLFeature.MATCH_FULL, false,
                    "FK MATCH FULL",
                    "ALTER TABLE probe_renamed ADD CONSTRAINT fk_match FOREIGN KEY (b) REFERENCES probe_u (a)"
                            + " MATCH FULL");
            syntaxProbe(failures, connection, DDLFeature.COLUMN_TYPE_UNSIGNED, false,
                    "INT UNSIGNED", "CREATE TABLE uns (a INT UNSIGNED)");

            System.out.println();
            System.out.println("    semantic probes (accepted but ignored, broken, or not reportable):");
            semanticProbes(failures, connection);

            System.out.println();
            System.out.println("    enforcement probes:");
            connection.executeStatement("CREATE TABLE enf_ck (a INT CHECK (a > 0))");
            enforcementProbe(failures, connection, "CHECK constraint is enforced",
                    "INSERT INTO enf_ck VALUES (-1)");
            connection.executeStatement("CREATE TABLE enf_nn (a INT NOT NULL)");
            enforcementProbe(failures, connection, "NOT NULL is enforced",
                    "INSERT INTO enf_nn VALUES (NULL)");
            connection.executeStatement("CREATE TABLE enf_p (id INT PRIMARY KEY)");
            connection.executeStatement("CREATE TABLE enf_c (id INT PRIMARY KEY, p INT REFERENCES enf_p (id))");
            boolean foreignKeysEnforced = enforcementProbe(failures, connection, "foreign keys are enforced",
                    "INSERT INTO enf_c (id, p) VALUES (1, 99)");
            record(failures, DDLFeature.FK_ENFORCED_BY_DEFAULT, foreignKeysEnforced,
                    "a foreign key violation is rejected without any session setup");
            record(failures, DDLFeature.FK_ENFORCED_BY_PRAGMA, false,
                    "H2 has no PRAGMA; enforcement is the default");
        } catch (SQLException e) {
            failures.add("feature probes failed: " + e);
        }
        return failures;
    }

    /**
     * Constructs H2 parses but does not honour, accepts with a broken result, or cannot report back.
     * These are the probes that decide the non-obvious entries of the feature set.
     */
    private static void semanticProbes(List<String> failures, DDLsmithDBConnection connection) throws SQLException {
        /* --- UNIQUE_CONSTRAINT: created and introspected back --- */
        connection.executeStatement("CREATE TABLE uq_master (a INT, b INT, CONSTRAINT uq_master_ab UNIQUE (a, b))");
        Table uq = introspect(connection).getTable("uq_master");
        boolean uniqueReported = uq != null && uq.getUniqueConstraints().stream()
                .anyMatch(unique -> unique.getColumns().equals(List.of("a", "b")));
        System.out.println("        UNIQUE constraint created and reported: " + uniqueReported);
        record(failures, DDLFeature.UNIQUE_CONSTRAINT, uniqueReported,
                "a UNIQUE constraint is created and introspected with its columns");

        /* --- TYPE_INTERVAL: mapper round trip plus catalog read back --- */
        H2TypeMapper mapper = new H2TypeMapper();
        boolean mapperRoundTrip = DataType.interval().equals(mapper.parse(mapper.render(DataType.interval())));
        connection.executeStatement("CREATE TABLE iv_master (a INTERVAL DAY TO SECOND, b INTERVAL YEAR)");
        Table iv = introspect(connection).getTable("iv_master");
        boolean intervalReported = iv != null && DataType.interval().equals(iv.getColumn("a").getType())
                && DataType.interval().equals(iv.getColumn("b").getType());
        System.out.println("        INTERVAL mapper round trip=" + mapperRoundTrip
                + " catalog round trip=" + intervalReported
                + " (rendered as '" + mapper.render(DataType.interval()) + "')");
        record(failures, DDLFeature.TYPE_INTERVAL, mapperRoundTrip && intervalReported,
                "INTERVAL columns survive the mapper and the catalog (qualifier is not part of the canonical type)");

        /* --- FK_RESTRICT: H2 collapses RESTRICT and NO ACTION --- */
        connection.executeStatement("CREATE TABLE rp (id INT PRIMARY KEY)");
        connection.executeStatement("CREATE TABLE r_plain (id INT PRIMARY KEY, p INT,"
                + " CONSTRAINT fk_plain FOREIGN KEY (p) REFERENCES rp (id))");
        connection.executeStatement("CREATE TABLE r_restrict (id INT PRIMARY KEY, p INT,"
                + " CONSTRAINT fk_restrict FOREIGN KEY (p) REFERENCES rp (id) ON DELETE RESTRICT)");
        String plainRule = scalar(connection,
                "SELECT DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_NAME = 'FK_PLAIN'");
        String restrictRule = scalar(connection,
                "SELECT DELETE_RULE FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_NAME = 'FK_RESTRICT'");
        boolean restrictDistinguishable = plainRule != null && !plainRule.equalsIgnoreCase(restrictRule);
        System.out.println("        FK ON DELETE RESTRICT is accepted; DELETE_RULE without a clause=" + plainRule
                + ", with RESTRICT=" + restrictRule + " -> distinguishable=" + restrictDistinguishable);
        record(failures, DDLFeature.FK_RESTRICT, restrictDistinguishable,
                "ON DELETE RESTRICT is distinguishable from NO ACTION in the catalog");

        /* --- DEFERRABLE_FK: parsed but ignored --- */
        connection.executeStatement("CREATE TABLE dg (id INT PRIMARY KEY, x INT UNIQUE)");
        connection.executeStatement("CREATE TABLE dc (id INT PRIMARY KEY, d INT)");
        boolean deferredAccepted = executeQuietly(connection,
                "ALTER TABLE dc ADD CONSTRAINT fk_deferred FOREIGN KEY (d) REFERENCES dg (x) DEFERRABLE");
        String isDeferrable = scalar(connection,
                "SELECT IS_DEFERRABLE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_NAME = 'FK_DEFERRED'");
        boolean deferredHonoured = "YES".equalsIgnoreCase(isDeferrable);
        System.out.println("        FK DEFERRABLE accepted=" + deferredAccepted + " honoured(IS_DEFERRABLE)="
                + isDeferrable);
        record(failures, DDLFeature.DEFERRABLE_FK, deferredHonoured,
                "DEFERRABLE is honoured (IS_DEFERRABLE = YES)");

        /* --- INLINE_FOREIGN_KEY: accepted, but the AST cannot render it --- */
        connection.executeStatement("CREATE TABLE ifp (id INT PRIMARY KEY)");
        boolean inlineAccepted = executeQuietly(connection,
                "CREATE TABLE ifk (id INT PRIMARY KEY, p INT REFERENCES ifp (id))");
        Table ifk = introspect(connection).getTable("ifk");
        boolean inlineReported = ifk != null && ifk.getForeignKeys().size() == 1
                && "ifp".equals(ifk.getForeignKeys().get(0).getReferencedTable());
        System.out.println("        inline REFERENCES accepted=" + inlineAccepted
                + " reported as a table-level foreign key=" + inlineReported
                + " -> H2 supports the syntax, but the DDL AST has no inline FK rendering,"
                + " so the flag stays off (TABLE_LEVEL_FOREIGN_KEY covers foreign keys)");
        record(failures, DDLFeature.INLINE_FOREIGN_KEY, false,
                "inline foreign keys are renderable by the frozen DDL AST");

        /* --- INDEX_DESCENDING: accepted, but not expressible or reportable --- */
        boolean descendingAccepted = executeQuietly(connection,
                "CREATE INDEX idx_desc ON probe_renamed (a DESC)");
        String ordering = scalar(connection,
                "SELECT ORDERING_SPECIFICATION FROM INFORMATION_SCHEMA.INDEX_COLUMNS"
                        + " WHERE INDEX_NAME = 'IDX_DESC' AND COLUMN_NAME = 'A'");
        System.out.println("        INDEX (a DESC) accepted=" + descendingAccepted
                + " ORDERING_SPECIFICATION=" + ordering
                + " -> Index has no ordering field, so the direction cannot be expressed or compared");
        record(failures, DDLFeature.INDEX_DESCENDING, false,
                "a descending index is expressible and reportable through the canonical Index model");

        /* --- INDEX_METHOD: only BTREE/HASH/RTREE, and not reported back --- */
        boolean btree = executeQuietly(connection, "CREATE INDEX idx_btree ON probe_renamed USING BTREE (a)");
        boolean hash = executeQuietly(connection, "CREATE INDEX idx_hash ON probe_renamed USING HASH (b)");
        boolean arbitrary = executeQuietly(connection, "CREATE INDEX idx_arbitrary ON probe_renamed USING GIN (a)");
        String reported = scalar(connection,
                "SELECT INDEX_TYPE_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = 'IDX_BTREE'");
        System.out.println("        INDEX METHOD btree=" + btree + " hash=" + hash + " gin=" + arbitrary
                + " INDEX_TYPE_NAME(btree)=" + reported);
        record(failures, DDLFeature.INDEX_METHOD, arbitrary && "BTREE".equalsIgnoreCase(reported),
                "an arbitrary index method is accepted and the method is reported back");

        /* --- TYPE_DOMAIN: expressible, but a domain column reports its base type --- */
        boolean domainCreated = executeQuietly(connection, "CREATE DOMAIN dom_semantic AS INT CHECK (VALUE > 0)");
        boolean domainColumn = executeQuietly(connection, "CREATE TABLE dom_use (a dom_semantic)");
        String reportedType = scalar(connection,
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'DOM_USE'");
        String domainName = scalar(connection,
                "SELECT DOMAIN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'DOM_USE'");
        System.out.println("        DOMAIN created=" + domainCreated + " column=" + domainColumn
                + " DATA_TYPE=" + reportedType + " DOMAIN_NAME=" + domainName);
        record(failures, DDLFeature.TYPE_DOMAIN, domainCreated && domainColumn
                && !"INTEGER".equalsIgnoreCase(reportedType), "the domain identity survives introspection");

        /* --- ENUM_AS_CHECK: not needed, H2 has a native ENUM --- */
        connection.executeStatement("CREATE TABLE en_master (e ENUM('a','b'))");
        Table en = introspect(connection).getTable("en_master");
        boolean nativeEnum = en != null && DataType.enumOf(List.of("a", "b")).equals(en.getColumn("e").getType());
        System.out.println("        native ENUM round trip=" + nativeEnum);
        record(failures, DDLFeature.ENUM_AS_CHECK, !nativeEnum,
                "H2 lacks a native ENUM type and needs the CHECK emulation");

        /* --- FK_TARGET_MUST_EXIST: the target is resolved when the CREATE TABLE runs --- */
        connection.executeStatement("CREATE TABLE cycle_a (id INT PRIMARY KEY)");
        boolean missingTargetRejected = !executeQuietly(connection,
                "CREATE TABLE fk_missing (id INT PRIMARY KEY, a INT REFERENCES no_such_table (id))");
        boolean cycleClosedByAlter = executeQuietly(connection,
                "CREATE TABLE cycle_b (id INT PRIMARY KEY, a INT REFERENCES cycle_a (id))")
                && executeQuietly(connection,
                        "ALTER TABLE cycle_a ADD CONSTRAINT fk_cycle FOREIGN KEY (id) REFERENCES cycle_b (id)");
        System.out.println("        FK to a missing table rejected=" + missingTargetRejected
                + " reference cycle closed with ALTER TABLE=" + cycleClosedByAlter);
        record(failures, DDLFeature.FK_TARGET_MUST_EXIST, missingTargetRejected,
                "a CREATE TABLE whose foreign key target does not exist yet is rejected");

        /* --- MATERIALIZED_VIEW: accepted but produces an unusable object ---
           This probe runs last on purpose: the object it creates makes H2's
           INFORMATION_SCHEMA.COLUMNS query throw an internal NullPointerException, which would
           poison every later introspection. */
        boolean mvAccepted = executeQuietly(connection,
                "CREATE MATERIALIZED VIEW mv_semantic AS SELECT a FROM probe_renamed");
        String tableType = scalar(connection,
                "SELECT TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'MV_SEMANTIC'");
        String definition = scalar(connection,
                "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_NAME = 'MV_SEMANTIC'");
        boolean columnsReadable = executeQuietly(connection,
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'MV_SEMANTIC'");
        System.out.println("        MATERIALIZED VIEW accepted=" + mvAccepted + " TABLE_TYPE=" + tableType
                + " VIEW_DEFINITION=" + definition + " COLUMNS readable=" + columnsReadable
                + " (this breaks INFORMATION_SCHEMA.COLUMNS for the whole database)");
        record(failures, DDLFeature.MATERIALIZED_VIEW, mvAccepted && definition != null && columnsReadable,
                "the materialized view has a definition and readable columns");
    }

    /**
     * Runs a construct, prints the observation and asserts that the dialect declares exactly that.
     *
     * @return whether H2 accepted the statement
     */
    private static boolean syntaxProbe(List<String> failures, DDLsmithDBConnection connection, DDLFeature feature,
            boolean expected, String description, String sql) {
        boolean accepted = executeQuietly(connection, sql);
        record(failures, feature, accepted, description);
        System.out.println("        " + sql);
        if (accepted != expected) {
            failures.add("probe '" + description + "' was expected to be " + (expected ? "accepted" : "rejected")
                    + " but H2 " + (accepted ? "accepted" : "rejected") + " it");
        }
        return accepted;
    }

    private static void record(List<String> failures, DDLFeature feature, boolean observed, String description) {
        boolean declared = DIALECT.getFeatures().supports(feature);
        System.out.printf("        %-30s observed=%-5s declared=%-5s  %s%n", feature, observed, declared, description);
        if (declared != observed) {
            failures.add("getFeatures() declares " + feature + "=" + declared + " but H2 shows " + observed
                    + " (" + description + ")");
        }
    }

    /** @return true when the probe statement was rejected (i.e. the constraint was enforced) */
    private static boolean enforcementProbe(List<String> failures, DDLsmithDBConnection connection,
            String description, String probe) {
        boolean accepted = executeQuietly(connection, probe);
        System.out.println("        " + pad(description) + " rejected=" + !accepted + "   " + probe);
        if (accepted) {
            failures.add("enforcement probe: " + description + " did not reject '" + probe + "'");
        }
        return !accepted;
    }

    /* ================================================================== 8. ALTER round trip */

    /**
     * Applies a sequence of supported {@code ALTER TABLE} actions, checks the introspected schema
     * against the expected model after each one, then applies the action's
     * {@link AlterTableAction#inverse(String)} and checks that the previous schema is restored.
     * This is exactly the premise the metamorphic ALTER oracle relies on.
     */
    public static List<String> checkAlterRoundTrip() {
        List<String> failures = new ArrayList<>();
        try (DDLsmithDBConnection connection = connect("ddlsmith_alter")) {
            RelationalSchema initial = alterSchema();
            for (String sql : statementsFor(initial)) {
                connection.executeStatement(sql);
            }
            AlterHarness harness = new AlterHarness(connection, failures, initial);
            harness.verifyInitialState();

            harness.on("alter_demo");
            harness.step(new AddColumn(new Column("extra", DataType.varchar(15), false)
                    .setDefaultExpression("'x'")));
            harness.step(new AddColumn(new Column("counter", DataType.integer(), false).setIdentity(true)));
            harness.step(new RenameColumn("label", "title"));
            harness.step(new SetNotNull("flag", true));
            harness.step(new DropNotNull("label", true));
            harness.step(new SetDefault("score", "42", "0.00"));
            harness.step(new DropDefault("score", "0.00"));
            harness.step(new AlterColumnType("amount", DataType.bigint(), DataType.integer(), null));
            harness.step(new AddConstraint(ConstraintDefinition.unique(
                    new UniqueConstraint("uq_alter_demo_label", List.of("label")))));
            harness.step(new AddConstraint(ConstraintDefinition.check(
                    new CheckConstraint("ck_alter_demo_score", "score >= 0"))));
            harness.step(new AddConstraint(ConstraintDefinition.foreignKey(
                    new ForeignKey(List.of("key_ref"), "alter_keys", List.of("code"))
                            .setName("fk_alter_demo_keys").setOnDelete(ReferentialAction.SET_NULL))));
            harness.step(new DropColumn("amount", new Column("amount", DataType.integer(), true)));
            harness.step(new RenameTable("alter_demo", "alter_demo_renamed"));

            harness.on("alter_keys");
            harness.step(new DropConstraint("pk_alter_keys",
                    ConstraintDefinition.primaryKey(new PrimaryKey("pk_alter_keys", List.of("code"))), false, false));
            harness.step(new AddConstraint(ConstraintDefinition.unique(
                    new UniqueConstraint("uq_alter_keys_note", List.of("note")))));

            harness.on("alter_nopk");
            harness.step(new AddConstraint(ConstraintDefinition.primaryKey(
                    new PrimaryKey("pk_alter_nopk", List.of("a")))));

            System.out.println("    " + harness.steps + " ALTER steps, each verified and inverted");
        } catch (SQLException e) {
            failures.add("ALTER round trip failed: " + e);
        }
        return failures;
    }

    /** The three tables the ALTER check works on. */
    private static RelationalSchema alterSchema() {
        RelationalSchema schema = new RelationalSchema(SCHEMA);

        Table demo = new Table(SCHEMA, "alter_demo");
        demo.addColumn(new Column("id", DataType.integer(), false));
        demo.addColumn(new Column("label", DataType.varchar(10), false));
        demo.addColumn(new Column("score", DataType.decimal(10, 2), true).setDefaultExpression("0.00"));
        demo.addColumn(new Column("flag", DataType.bool(), true));
        demo.addColumn(new Column("key_ref", DataType.varchar(10), true));
        demo.addColumn(new Column("amount", DataType.integer(), true));
        demo.setPrimaryKey(new PrimaryKey("pk_alter_demo", List.of("id")));
        schema.addTable(demo);

        Table keys = new Table(SCHEMA, "alter_keys");
        keys.addColumn(new Column("code", DataType.varchar(10), false));
        keys.addColumn(new Column("note", DataType.varchar(20), true));
        keys.setPrimaryKey(new PrimaryKey("pk_alter_keys", List.of("code")));
        schema.addTable(keys);

        Table noPk = new Table(SCHEMA, "alter_nopk");
        noPk.addColumn(new Column("a", DataType.integer(), false));
        noPk.addColumn(new Column("b", DataType.varchar(20), true));
        schema.addTable(noPk);

        return schema;
    }

    /** Applies actions, verifies the expected schema, then verifies that the inverse restores it. */
    private static final class AlterHarness {

        private final DDLsmithDBConnection connection;
        private final List<String> failures;
        private final RelationalSchema current;
        private String tableName;
        private int steps;

        AlterHarness(DDLsmithDBConnection connection, List<String> failures, RelationalSchema current) {
            this.connection = connection;
            this.failures = failures;
            this.current = current;
        }

        void on(String table) {
            this.tableName = table;
        }

        void verifyInitialState() {
            compare("initial ALTER state", current);
        }

        void step(AlterTableAction action) {
            String originalTable = tableName;
            String sql = "ALTER TABLE " + tableName + " " + DIALECT.renderAlterAction(action);
            String error = executeOrError(connection, sql);
            if (error != null) {
                failures.add("ALTER step rejected: " + sql + "\n      " + error);
                return;
            }
            RelationalSchema expected = current.copy();
            String expectedTable = applyAction(expected, tableName, action);
            compare("after " + sql, expected);

            AlterTableAction inverse = action.inverse(expectedTable);
            if (inverse == null) {
                failures.add("action is not invertible: " + sql);
                return;
            }
            String inverseSql = "ALTER TABLE " + expectedTable + " " + DIALECT.renderAlterAction(inverse);
            String inverseError = executeOrError(connection, inverseSql);
            if (inverseError != null) {
                failures.add("inverse step rejected: " + inverseSql + "\n      " + inverseError);
                return;
            }
            compare("after inverse " + inverseSql, current);
            tableName = originalTable;
            steps++;
        }

        private void compare(String label, RelationalSchema expected) {
            try {
                RelationalSchema actual = introspect(connection);
                List<SchemaDifference> differences = SchemaNormalizer.diff(
                        SchemaNormalizer.erase(expected, INTROSPECTOR.getCapabilities()), actual);
                if (!differences.isEmpty()) {
                    failures.add(label + ":\n" + indent(SchemaNormalizer.render(differences)));
                }
            } catch (SQLException e) {
                failures.add(label + ": introspection failed: " + e);
            }
        }
    }

    /**
     * Mirrors an {@link AlterTableAction} on the model, so that the expected schema can be compared
     * with what H2 reports.
     *
     * @return the table name after the action (only a {@code RENAME TO} changes it)
     */
    private static String applyAction(RelationalSchema schema, String tableName, AlterTableAction action) {
        Table table = schema.getTable(tableName);
        if (table == null) {
            throw new IllegalStateException("no such table: " + tableName);
        }
        if (action instanceof AddColumn) {
            table.addColumn(((AddColumn) action).getColumn().copy());
        } else if (action instanceof DropColumn) {
            table.removeColumn(((DropColumn) action).getColumnName());
        } else if (action instanceof RenameColumn) {
            RenameColumn rename = (RenameColumn) action;
            table.getColumn(rename.getFrom()).setName(rename.getTo());
        } else if (action instanceof RenameTable) {
            String to = ((RenameTable) action).getTo();
            table.setName(to);
            return to;
        } else if (action instanceof AlterColumnType) {
            AlterColumnType alter = (AlterColumnType) action;
            table.getColumn(alter.getColumnName()).setType(alter.getNewType());
        } else if (action instanceof SetNotNull) {
            table.getColumn(((SetNotNull) action).getColumnName()).setNullable(false);
        } else if (action instanceof DropNotNull) {
            table.getColumn(((DropNotNull) action).getColumnName()).setNullable(true);
        } else if (action instanceof SetDefault) {
            SetDefault setDefault = (SetDefault) action;
            table.getColumn(setDefault.getColumnName()).setDefaultExpression(setDefault.getExpression());
        } else if (action instanceof DropDefault) {
            table.getColumn(((DropDefault) action).getColumnName()).setDefaultExpression(null);
        } else if (action instanceof AddConstraint) {
            addConstraint(table, ((AddConstraint) action).getDefinition());
        } else if (action instanceof DropConstraint) {
            dropConstraint(table, ((DropConstraint) action).getConstraintName());
        } else {
            throw new IllegalStateException("unsupported action " + action);
        }
        return tableName;
    }

    private static void addConstraint(Table table, ConstraintDefinition definition) {
        switch (definition.getKind()) {
        case PRIMARY_KEY:
            table.setPrimaryKey(new PrimaryKey(definition.getPrimaryKey().getName(),
                    new ArrayList<>(definition.getPrimaryKey().getColumns())));
            break;
        case UNIQUE:
            table.addUniqueConstraint(new UniqueConstraint(definition.getUniqueConstraint().getName(),
                    new ArrayList<>(definition.getUniqueConstraint().getColumns())));
            break;
        case CHECK:
            table.addCheckConstraint(new CheckConstraint(definition.getCheckConstraint().getName(),
                    definition.getCheckConstraint().getExpression()));
            break;
        case FOREIGN_KEY:
            ForeignKey source = definition.getForeignKey();
            ForeignKey copy = new ForeignKey(new ArrayList<>(source.getColumns()), source.getReferencedTable(),
                    new ArrayList<>(source.getReferencedColumns()));
            copy.setName(source.getName());
            copy.setOnDelete(source.getOnDelete());
            copy.setOnUpdate(source.getOnUpdate());
            table.addForeignKey(copy);
            break;
        default:
            throw new IllegalStateException("unsupported constraint " + definition.getKind());
        }
    }

    private static void dropConstraint(Table table, String name) {
        if (table.getPrimaryKey() != null && name.equalsIgnoreCase(table.getPrimaryKey().getName())) {
            table.setPrimaryKey(null);
            return;
        }
        if (table.getUniqueConstraints().removeIf(unique -> name.equalsIgnoreCase(unique.getName()))) {
            return;
        }
        if (table.getCheckConstraints().removeIf(check -> name.equalsIgnoreCase(check.getName()))) {
            return;
        }
        if (table.getForeignKeys().removeIf(foreignKey -> name.equalsIgnoreCase(foreignKey.getName()))) {
            return;
        }
        throw new IllegalStateException("no such constraint in the model: " + name);
    }

    /* ================================================================== plumbing */

    private static String h2Version() {
        try (DDLsmithDBConnection connection = connect("ddlsmith_version")) {
            return connection.getDatabaseVersion();
        } catch (SQLException e) {
            return "<unavailable: " + e.getMessage() + ">";
        }
    }

    static DDLsmithDBConnection connect(String database) throws SQLException {
        return new DDLsmithDBConnection(DriverManager.getConnection(
                "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1", "sa", ""), "h2");
    }

    private static void execute(DDLsmithDBConnection connection, DDLStatement statement) throws SQLException {
        for (String sql : DIALECT.renderAll(statement)) {
            connection.executeStatement(sql);
        }
    }

    static boolean executeQuietly(DDLsmithDBConnection connection, String sql) {
        try {
            connection.executeStatement(sql);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** @return null when the statement succeeded, otherwise the first line of the engine's message */
    static String executeOrError(DDLsmithDBConnection connection, String sql) {
        try {
            connection.executeStatement(sql);
            return null;
        } catch (SQLException e) {
            String message = e.getMessage();
            return message == null ? e.toString() : message.split("\n")[0];
        }
    }

    private static String scalar(DDLsmithDBConnection connection, String sql) {
        try (Statement statement = connection.getConnection().createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private static RelationalSchema introspect(DDLsmithDBConnection connection) throws SQLException {
        return INTROSPECTOR.introspect(connection, SCHEMA);
    }

    private static List<String> compare(String label, RelationalSchema intended, RelationalSchema actual) {
        List<SchemaDifference> differences = SchemaNormalizer.diff(
                SchemaNormalizer.erase(intended, INTROSPECTOR.getCapabilities()), actual);
        if (differences.isEmpty()) {
            return List.of();
        }
        return List.of(label + ":\n" + indent(SchemaNormalizer.render(differences)));
    }

    private static String indent(String text) {
        return "      " + text.replace("\n", "\n      ");
    }

    private static String pad(String text) {
        String result = text.length() > 46 ? text.substring(0, 43) + "..." : text;
        return String.format("%-48s", result);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    /** Exposed for the JUnit test and for reports. */
    public static Map<String, String> dialectSummary() {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("name", DIALECT.getName());
        summary.put("defaultSchema", DIALECT.getDefaultSchemaName());
        summary.put("sessionSetup", DIALECT.getSessionSetupStatements().toString());
        summary.put("features", DIALECT.getFeatures().getFeatures().toString());
        return summary;
    }
}
