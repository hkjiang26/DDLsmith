package org.ddlsmith.oracle.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.ddlsmith.DDLsmithDBConnection;
import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.TestOracle;

/**
 * TEST SCAFFOLDING - runs the constraint, referential-action, default-value, metamorphic and
 * pivot-row oracles against real in-memory SQLite and H2 databases created with plain JDBC.
 *
 * <p>It exists because the rest of DDLsmith is written in parallel: instead of a full DDLsmith
 * round it materializes a hand-made {@link RelationalSchema} with the local test dialects
 * ({@link TestSqliteDialect}, {@link TestH2Dialect}) and introspects it with the local introspectors
 * ({@link TestSqliteIntrospector}, {@link TestH2Introspector}).</p>
 *
 * <p>Every scenario asserts both directions: a correctly created schema must produce <em>no</em>
 * failure, and a deliberately broken one must produce the expected failure kind.</p>
 */
public final class ConstraintOracleSelfCheck {

    private static int h2Counter;

    private ConstraintOracleSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "debug-metamorphic".equals(args[0])) {
            debugMetamorphic();
            return;
        }
        if (args.length > 0 && "debug-report".equals(args[0])) {
            debugReport();
            return;
        }
        List<String> failures = new ArrayList<>();
        long[] seeds = { 20241004L, 1L, 42L, 7L, 987654321L };
        for (long seed : seeds) {
            List<String> seedFailures = runAll(seed);
            if (!seedFailures.isEmpty()) {
                failures.add("seed " + seed + ":");
                failures.addAll(seedFailures);
            }
        }
        if (failures.isEmpty()) {
            System.out.println("ALL CHECKS PASSED (" + seeds.length + " seeds)");
            return;
        }
        System.out.println(failures.size() + " CHECK(S) FAILED:");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        System.exit(1);
    }

    /** @return the list of failed checks; empty when everything passed */
    public static List<String> runAll() {
        return runAll(20241004L);
    }

    /** @return the list of failed checks for one seed; empty when everything passed */
    public static List<String> runAll(long seed) {
        List<String> failures = new ArrayList<>();
        Randomly.setSeed(seed);
        run(failures, "constraint-enforcement (SQLite, correct schema)", () -> constraintEnforcementPositive(true));
        run(failures, "constraint-enforcement (H2, correct schema)", () -> constraintEnforcementPositive(false));
        run(failures, "constraint-enforcement (SQLite, dropped UNIQUE/CHECK)", () -> constraintEnforcementNegative(true));
        run(failures, "constraint-enforcement (H2, dropped UNIQUE/CHECK)", () -> constraintEnforcementNegative(false));
        run(failures, "constraint-enforcement (SQLite, CHECK evaluating to UNKNOWN)", () -> checkUnknownGuard(true));
        run(failures, "constraint-enforcement (H2, CHECK evaluating to UNKNOWN)", () -> checkUnknownGuard(false));
        run(failures, "constraint-enforcement (SQLite, unsatisfiable CHECK)", () -> unsatisfiableCheckGuard(true));
        run(failures, "constraint-enforcement (H2, unsatisfiable CHECK)", () -> unsatisfiableCheckGuard(false));
        run(failures, "default-value (SQLite, correct defaults)", () -> defaultValuePositive(true));
        run(failures, "default-value (H2, correct defaults)", () -> defaultValuePositive(false));
        run(failures, "default-value (SQLite, missing default)", () -> defaultValueNegative(true));
        run(failures, "default-value (H2, missing default)", () -> defaultValueNegative(false));
        run(failures, "default-value (SQLite, NOT NULL with a DEFAULT)", () -> notNullWithDefault(true));
        run(failures, "default-value (H2, NOT NULL with a DEFAULT)", () -> notNullWithDefault(false));
        run(failures, "referential-action (SQLite, ON DELETE CASCADE)", () -> referentialPositive(true));
        run(failures, "referential-action (H2, ON DELETE CASCADE)", () -> referentialPositive(false));
        run(failures, "referential-action (SQLite, actual NO ACTION)", () -> referentialNegative(true));
        run(failures, "referential-action (H2, actual NO ACTION)", () -> referentialNegative(false));
        run(failures, "referential-action (SQLite, pragma OFF must not report)", ConstraintOracleSelfCheck::referentialPragmaOff);
        run(failures, "metamorphic-alter (SQLite)", () -> metamorphicPositive(true));
        run(failures, "metamorphic-alter (H2)", () -> metamorphicPositive(false));
        run(failures, "metamorphic-alter (missing inverse)", ConstraintOracleSelfCheck::metamorphicNegative);
        run(failures, "pivot-row (SQLite)", () -> pivotPositive(true));
        run(failures, "pivot-row (H2)", () -> pivotPositive(false));
        run(failures, "pivot-row (corrupted expectation model)", ConstraintOracleSelfCheck::pivotNegative);
        run(failures, "complex schema (SQLite)", () -> complexSchema(true));
        run(failures, "complex schema (H2)", () -> complexSchema(false));
        run(failures, "referential-action (SQLite, self-referencing row)", () -> selfReferencingRowGuard(true));
        run(failures, "referential-action (H2, self-referencing row)", () -> selfReferencingRowGuard(false));
        run(failures, "referential-action (SQLite, ON UPDATE CASCADE counting)", () -> updateCascadeIsObserved(true));
        run(failures, "referential-action (H2, ON UPDATE CASCADE counting)", () -> updateCascadeIsObserved(false));
        run(failures, "referential-action (SQLite, restrictive sibling key)", () -> restrictiveSiblingGuard(true));
        run(failures, "referential-action (H2, restrictive sibling key)", () -> restrictiveSiblingGuard(false));
        run(failures, "referential-action (SQLite, NO ACTION after a sibling cascade)",
                () -> noActionAfterASiblingCascade(true));
        run(failures, "referential-action (H2, NO ACTION after a sibling cascade)",
                () -> noActionAfterASiblingCascade(false));
        run(failures, "referential-action (SQLite, two cascading keys)",
                () -> twoCascadingKeysCountTheRowOnce(true));
        run(failures, "referential-action (H2, two cascading keys)",
                () -> twoCascadingKeysCountTheRowOnce(false));
        run(failures, "referential-action (SQLite, parent key is itself a foreign key)",
                () -> parentKeyIsAlsoAForeignKeyColumn(true));
        run(failures, "referential-action (H2, parent key is itself a foreign key)",
                () -> parentKeyIsAlsoAForeignKeyColumn(false));
        run(failures, "pivot-row (SQLite, rows left by an earlier oracle)", () -> pivotAfterAnotherOracle(true));
        run(failures, "pivot-row (H2, rows left by an earlier oracle)", () -> pivotAfterAnotherOracle(false));
        run(failures, "constraint-enforcement (SQLite, NOT NULL generated column)", () -> generatedNotNullColumn(true));
        run(failures, "constraint-enforcement (H2, NOT NULL generated column)", () -> generatedNotNullColumn(false));
        return failures;
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void run(List<String> failures, String name, Check check) {
        try {
            check.run();
        } catch (Throwable t) {
            java.io.StringWriter writer = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(writer));
            failures.add(name + ": threw " + t + "\n" + writer);
        }
    }

    /* ================================================================== scenarios */

    private static void constraintEnforcementPositive(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = schema();
        DatabaseUnderTest database = sqlite ? sqlite("ce-ok") : h2("ce-ok");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new ConstraintEnforcementOracle().verify(context);
        check(failure.isEmpty(), "constraint-enforcement must not report on a correct " + label + " schema: "
                + describe(failure));
        long probes = countEvents(context, "probe-");
        check(probes >= 5, "constraint-enforcement must actually probe constraints on " + label
                + " (only " + probes + " probes were executed)");
        long inserts = countEvents(context, "probe-insert");
        check(inserts >= 4, "constraint-enforcement must populate the schema on " + label + " (only " + inserts
                + " INSERTs were executed)");
        for (ExecutionEvent event : context.getExecutionEvents()) {
            String purpose = event.getPurpose() == null ? "" : event.getPurpose();
            if (purpose.equals("probe-unique") || purpose.equals("probe-primary_key")) {
                check(!event.getSql().contains("NULL"),
                        "a UNIQUE/PRIMARY KEY probe must never insert NULL on " + label + ": " + event.getSql());
            }
        }
        assertExpectedToFailFlagging(context, label);
    }

    /**
     * Validates the rule the crash oracle depends on: a statement that is expected to be rejected is
     * recorded with {@code expectedToFail = true}, and a statement that must be accepted is not.
     */
    private static void assertExpectedToFailFlagging(OracleContext context, String label) {
        for (ExecutionEvent event : context.getExecutionEvents()) {
            String purpose = event.getPurpose() == null ? "" : event.getPurpose();
            boolean rejectionProbe = purpose.startsWith("probe-fk-delete") || purpose.startsWith("probe-fk-update")
                    || purpose.equals("probe-not-null") || purpose.equals("probe-primary_key")
                    || purpose.equals("probe-unique") || purpose.equals("probe-check")
                    || purpose.equals("probe-foreign_key");
            boolean acceptanceProbe = purpose.equals("probe-insert") || purpose.equals("probe-default")
                    || purpose.equals("probe-pivot-query");
            if (rejectionProbe) {
                check(event.isExpectedToFail(), "a probe expected to be rejected must set expectedToFail on "
                        + label + ": " + event);
            }
            if (acceptanceProbe) {
                check(!event.isExpectedToFail(), "a probe expected to be accepted must not set expectedToFail on "
                        + label + ": " + event);
            }
        }
    }

    /**
     * A CHECK whose expression contains a NULL literal can only evaluate to UNKNOWN for the
     * "violating" value, and a CHECK that evaluates to UNKNOWN is satisfied. The oracle must exclude
     * such a probe instead of reporting a false positive.
     */
    private static void checkUnknownGuard(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = new RelationalSchema("unknown-guard");
        Table table = new Table("t_unknown");
        table.addColumn(new Column("id", DataType.integer(), false));
        table.addColumn(new Column("c", DataType.integer(), true));
        table.setPrimaryKey(new PrimaryKey(List.of("id")));
        table.addCheckConstraint(new CheckConstraint("ck_in", "c IN (1, NULL)"));
        intended.addTable(table);
        DatabaseUnderTest database = sqlite ? sqlite("guard-unknown") : h2("guard-unknown");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new ConstraintEnforcementOracle().verify(context);
        check(failure.isEmpty(), "a CHECK comparing against a NULL literal must not be reported as unenforced on "
                + label + ": " + describe(failure));
        check(countEvents(context, "probe-check") == 0,
                "the CHECK probe against a NULL literal must be excluded on " + label);
    }

    /**
     * The row generator cannot satisfy {@code CHECK (c IS NULL)} on a column it always fills, so the
     * INSERT is rejected for a reason that has nothing to do with the engine. The oracle must notice
     * that the row is not satisfying and stay silent.
     */
    private static void unsatisfiableCheckGuard(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = new RelationalSchema("unsatisfiable-guard");
        Table table = new Table("t_unsat");
        table.addColumn(new Column("id", DataType.integer(), false));
        table.addColumn(new Column("c", DataType.integer(), true));
        table.setPrimaryKey(new PrimaryKey(List.of("id")));
        table.addCheckConstraint(new CheckConstraint("ck_null", "c IS NULL"));
        intended.addTable(table);
        DatabaseUnderTest database = sqlite ? sqlite("guard-unsat") : h2("guard-unsat");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new ConstraintEnforcementOracle().verify(context);
        check(failure.isEmpty(), "an unsatisfiable CHECK must not be reported as CONSTRAINT_TOO_STRICT on " + label
                + ": " + describe(failure));
    }

    /** An explicit NULL in a NOT NULL column must be rejected even when the column has a DEFAULT. */
    private static void notNullWithDefault(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = new RelationalSchema("not-null-default");
        Table table = new Table("t_nn");
        table.addColumn(new Column("id", DataType.integer(), false));
        table.addColumn(new Column("n", DataType.integer(), false).setDefaultExpression("5"));
        table.setPrimaryKey(new PrimaryKey(List.of("id")));
        intended.addTable(table);
        DatabaseUnderTest database = sqlite ? sqlite("nn-default") : h2("nn-default");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new DefaultValueOracle().verify(context);
        check(failure.isEmpty(), "default-value must not report for a NOT NULL column with a DEFAULT on " + label
                + ": " + describe(failure));
        boolean sawExplicitNull = false;
        for (ExecutionEvent event : context.getExecutionEvents()) {
            if (!"probe-not-null".equals(event.getPurpose())) {
                continue;
            }
            sawExplicitNull = true;
            check(event.isExpectedToFail(), "the NOT NULL probe must be flagged as expected to fail on " + label);
            check(event.getSql().contains("NULL"),
                    "the NOT NULL probe must insert an explicit NULL on " + label + ": " + event.getSql());
            check(!event.getSql().endsWith("DEFAULT VALUES"),
                    "the NOT NULL probe must not omit the column on " + label + ": " + event.getSql());
        }
        check(sawExplicitNull, "default-value must probe the NOT NULL column on " + label);
        check(countEvents(context, "probe-default") >= 1,
                "default-value must exercise the DEFAULT of the NOT NULL column on " + label);
    }

    private static void constraintEnforcementNegative(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = schema();
        RelationalSchema actual = schema();
        actual.getTable("t_author").getUniqueConstraints().clear();
        actual.getTable("t_book").getCheckConstraints().clear();
        DatabaseUnderTest database = sqlite ? sqlite("ce-bad") : h2("ce-bad");
        materialize(database, actual);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new ConstraintEnforcementOracle().verify(context);
        check(failure.isPresent(), "constraint-enforcement must report on a " + label + " schema whose UNIQUE and "
                + "CHECK constraints were dropped behind its back");
        if (failure.isPresent()) {
            check(failure.get().getKind() == FailureKind.CONSTRAINT_NOT_ENFORCED,
                    "constraint-enforcement must report CONSTRAINT_NOT_ENFORCED on " + label + ", got "
                            + failure.get().getKind());
            check(failure.get().getDetails().contains("UNIQUE") || failure.get().getDetails().contains("CHECK"),
                    "the report must name the constraint that was not enforced");
        }
    }

    private static void defaultValuePositive(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = schema();
        DatabaseUnderTest database = sqlite ? sqlite("dv-ok") : h2("dv-ok");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new DefaultValueOracle().verify(context);
        check(failure.isEmpty(), "default-value must not report on a correct " + label + " schema: "
                + describe(failure));
        check(countEvents(context, "probe-default") >= 2,
                "default-value must exercise the declared defaults on " + label);
        check(countEvents(context, "probe-not-null") >= 2,
                "default-value must probe the NOT NULL columns on " + label);
    }

    private static void defaultValueNegative(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = schema();
        RelationalSchema actual = schema();
        actual.getTable("t_author").getColumn("score").setDefaultExpression(null);
        actual.getTable("t_book").getColumn("price").setDefaultExpression(null);
        DatabaseUnderTest database = sqlite ? sqlite("dv-bad") : h2("dv-bad");
        materialize(database, actual);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new DefaultValueOracle().verify(context);
        check(failure.isPresent(), "default-value must report when a declared default is absent from the actual "
                + "table on " + label);
        if (failure.isPresent()) {
            check(failure.get().getKind() == FailureKind.SCHEMA_MISMATCH,
                    "default-value must report SCHEMA_MISMATCH on " + label + ", got " + failure.get().getKind());
            check(failure.get().getDetails().contains("DEFAULT"),
                    "the report must mention the DEFAULT that was not applied");
        }
    }

    private static void referentialPositive(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = schema();
        DatabaseUnderTest database = sqlite ? sqlite("ra-ok") : h2("ra-ok");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new ReferentialActionOracle().verify(context);
        check(failure.isEmpty(), "referential-action must not report for a correct ON DELETE CASCADE on " + label
                + ": " + describe(failure));
        check(countEvents(context, "probe-fk-delete-cascade") >= 1,
                "referential-action must actually execute the ON DELETE probe on " + label);
        long insertedBooks = countInserts(context, "t_book");
        check(insertedBooks > 0, "the referential-action oracle must have populated t_book on " + label);
        long booksAfter = count(database, "SELECT COUNT(*) FROM t_book");
        check(booksAfter < insertedBooks, "ON DELETE CASCADE must really have deleted the referencing child rows on "
                + label + " (" + insertedBooks + " inserted, " + booksAfter + " remain)");
    }

    private static void referentialNegative(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = schema();
        RelationalSchema actual = schema();
        Table book = actual.getTable("t_book");
        book.getForeignKeys().clear();
        book.addForeignKey(new ForeignKey(List.of("author_id"), "t_author", List.of("id"))
                .setName("fk_book_author").setOnDelete(ReferentialAction.NO_ACTION));
        DatabaseUnderTest database = sqlite ? sqlite("ra-bad") : h2("ra-bad");
        materialize(database, actual);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new ReferentialActionOracle().verify(context);
        check(failure.isPresent(), "referential-action must report when the schema declares CASCADE but the table "
                + "was created with NO ACTION on " + label);
        if (failure.isPresent()) {
            check(failure.get().getKind() == FailureKind.CONSTRAINT_NOT_ENFORCED
                            || failure.get().getKind() == FailureKind.STANDARD_DEVIATION,
                    "referential-action must report CONSTRAINT_NOT_ENFORCED or STANDARD_DEVIATION on " + label
                            + ", got " + failure.get().getKind());
            check(failure.get().getDetails().contains("CASCADE"),
                    "the report must mention the declared CASCADE action");
        }
    }

    /** Without the pragma the engine was never asked to enforce foreign keys: nothing may be reported. */
    private static void referentialPragmaOff() throws Exception {
        RelationalSchema intended = schema();
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        DDLsmithDBConnection wrapper = new DDLsmithDBConnection(connection, "sqlite-pragma-off");
        DatabaseUnderTest database = new DatabaseUnderTest("sqlite-pragma-off", wrapper, new TestSqliteDialect(),
                new TestSqliteIntrospector());
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new ReferentialActionOracle().verify(context);
        check(failure.isEmpty(), "referential-action must not report anything when PRAGMA foreign_keys is OFF: "
                + describe(failure));
        check(countEvents(context, "probe-fk-delete") == 0,
                "referential-action must skip the delete probes when the pragma is OFF");
        check(context.getExecutionEvents().stream()
                        .anyMatch(event -> "probe-fk-pragma-state".equals(event.getPurpose())),
                "referential-action must query the pragma state to detect the situation");
    }

    private static void metamorphicPositive(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = schema();
        DatabaseUnderTest database = sqlite ? sqlite("mm-ok") : h2("mm-ok");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new MetamorphicAlterOracle().verify(context);
        check(failure.isEmpty(), "metamorphic-alter must not report on " + label + ": " + describe(failure));
        check(countEvents(context, "metamorphic-alter") >= 1,
                "metamorphic-alter must actually apply ALTER TABLE actions on " + label);
        check(countEvents(context, "metamorphic-inverse") >= 1,
                "metamorphic-alter must actually apply the inverse actions on " + label);
        for (ExecutionEvent event : context.getExecutionEvents()) {
            String purpose = event.getPurpose() == null ? "" : event.getPurpose();
            if (purpose.startsWith("metamorphic-") && !purpose.equals("metamorphic-drop-scratch")) {
                check(event.isSucceeded(), "the test dialect declares this action as supported but " + label
                        + " rejected it, so the scaffolding (not the oracle) needs fixing: " + event);
            }
        }
    }

    private static void metamorphicNegative() throws Exception {
        RelationalSchema intended = schema();
        DatabaseUnderTest database = h2("mm-bad");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new MetamorphicAlterOracle() {
            @Override
            protected List<AlterTableAction> inversesFor(List<AlterTableAction> applied, String tableName) {
                List<AlterTableAction> inverses = super.inversesFor(applied, tableName);
                if (inverses == null || inverses.isEmpty()) {
                    return inverses;
                }
                // deliberately forget the last inverse, so the schema cannot be restored
                return new ArrayList<>(inverses.subList(0, inverses.size() - 1));
            }
        }.verify(context);
        check(failure.isPresent(), "metamorphic-alter must report when an action is applied without its inverse");
        if (failure.isPresent()) {
            check(failure.get().getKind() == FailureKind.METAMORPHIC,
                    "metamorphic-alter must report METAMORPHIC, got " + failure.get().getKind());
        }
    }

    private static void pivotPositive(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema intended = schema();
        DatabaseUnderTest database = sqlite ? sqlite("pv-ok") : h2("pv-ok");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new PivotRowOracle().verify(context);
        check(failure.isEmpty(), "pivot-row must not report on " + label + ": " + describe(failure));
        check(countEvents(context, "probe-pivot-query") >= 8,
                "pivot-row must actually execute deterministic queries on " + label);
    }

    private static void pivotNegative() throws Exception {
        RelationalSchema intended = schema();
        DatabaseUnderTest database = h2("pv-bad");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new PivotRowOracle() {
            @Override
            protected DataModel expectationModel(DataModel stored, RelationalSchema schema) {
                DataModel corrupted = new DataModel(schema);
                for (Table table : schema.getTables()) {
                    for (java.util.Map<String, Object> row : stored.getRows(table.getName())) {
                        corrupted.addRow(table.getName(), row);
                    }
                }
                for (Table table : schema.getTables()) {
                    List<java.util.Map<String, Object>> rows = corrupted.getRows(table.getName());
                    if (!rows.isEmpty()) {
                        // one row too many: every COUNT(*) must now disagree with the engine
                        corrupted.addRow(table.getName(), rows.get(0));
                        break;
                    }
                }
                return corrupted;
            }
        }.verify(context);
        check(failure.isPresent(), "pivot-row must report when the expectation model has an extra row");
        if (failure.isPresent()) {
            check(failure.get().getKind() == FailureKind.DATA_MISMATCH,
                    "pivot-row must report DATA_MISMATCH, got " + failure.get().getKind());
            check(failure.get().getDetails().contains("Query      :")
                            && failure.get().getDetails().contains("Expected")
                            && failure.get().getDetails().contains("Rows DDLsmith inserted"),
                    "the report must show the query, the expected and the observed result and the inserted rows: "
                            + failure.get().getDetails());
        }
    }

    /* ================================================================== fixtures */

    /**
     * A schema shaped like the ones DDLsmith generates for recursive relationships: a composite
     * primary key, a composite foreign key with CASCADE and a nullable self-referencing foreign key.
     */
    public static RelationalSchema complexSchema() {
        RelationalSchema schema = new RelationalSchema("complex");

        Table parent = new Table("t_parent");
        parent.addColumn(new Column("a", DataType.integer(), false));
        parent.addColumn(new Column("b", DataType.integer(), false));
        parent.addColumn(new Column("note", DataType.varchar(10), true));
        parent.setPrimaryKey(new PrimaryKey(List.of("a", "b")));
        schema.addTable(parent);

        Table child = new Table("t_child");
        child.addColumn(new Column("id", DataType.integer(), false));
        child.addColumn(new Column("pa", DataType.integer(), false));
        child.addColumn(new Column("pb", DataType.integer(), false));
        child.addColumn(new Column("parent_ref", DataType.integer(), true));
        child.addColumn(new Column("label", DataType.varchar(12), true));
        child.setPrimaryKey(new PrimaryKey(List.of("id")));
        child.addForeignKey(new ForeignKey(List.of("pa", "pb"), "t_parent", List.of("a", "b"))
                .setName("fk_child_parent").setOnDelete(ReferentialAction.CASCADE));
        child.addForeignKey(new ForeignKey(List.of("parent_ref"), "t_child", List.of("id"))
                .setName("fk_child_self").setSelfReferential(true));
        schema.addTable(child);
        return schema;
    }

    /**
     * Composite keys, a composite CASCADE foreign key and a recursive (self-referencing) foreign key:
     * none of the oracles may report a failure, and the self-referencing table must really have been
     * populated.
     */
    private static void complexSchema(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema schema = complexSchema();
        DatabaseUnderTest database = sqlite ? sqlite("cx-ok") : h2("cx-ok");
        materialize(database, schema);
        OracleContext context = context(schema, database);
        Optional<OracleFailure> failure = new ConstraintEnforcementOracle().verify(context);
        check(failure.isEmpty(), "constraint-enforcement must not report on the complex " + label + " schema: "
                + describe(failure));
        check(count(database, "SELECT COUNT(*) FROM t_child") >= 2,
                "the self-referencing table must have been populated on " + label);
        check(countEvents(context, "probe-foreign_key") >= 2,
                "both foreign keys must be probed on " + label);

        OracleContext pivotContext = context(schema, database);
        Optional<OracleFailure> pivotFailure = new PivotRowOracle().verify(pivotContext);
        check(pivotFailure.isEmpty(), "pivot-row must not report on the complex " + label + " schema: "
                + describe(pivotFailure));

        OracleContext referentialContext = context(schema, database);
        Optional<OracleFailure> referentialFailure = new ReferentialActionOracle().verify(referentialContext);
        check(referentialFailure.isEmpty(), "referential-action must not report on the complex " + label
                + " schema: " + describe(referentialFailure));
    }

    /* ================================================================== regression scenarios */

    /**
     * A recursive foreign key whose root row references itself.
     *
     * <p>{@code DELETE FROM t WHERE id = 15} where row 15 carries {@code parent_id = 15} removes the
     * only row that references the parent, so every engine accepts it. The oracle counted that row as
     * "a child row still references the parent row" and reported SQLite as not enforcing
     * {@code ON DELETE NO ACTION} — 5 findings of one acceptance session were exactly this. The
     * fixture makes it deterministic: the self-referencing column is NOT NULL, so the generator's
     * first row points at itself, and it is UNIQUE, so no second row can be generated at all — the
     * only parent row the probe can pick is the self-referencing one.</p>
     *
     * <p>The fixture also exposes a real engine difference on the <em>update</em> side, which is why
     * the assertion is split. {@code UPDATE t SET id = 1000004 WHERE id = 1} under
     * {@code ON UPDATE NO ACTION} leaves the row as {@code (1000004, parent_id = 1)} — a reference to
     * a key that no longer exists. SQLite rejects the statement, which is correct; H2 2.3.232 accepts
     * it and leaves the foreign key violated. That is a finding about H2, so it must not be turned
     * into a test failure — only a <em>delete</em> finding would mean the guard is broken.</p>
     */
    private static void selfReferencingRowGuard(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema schema = new RelationalSchema("self-reference");
        Table node = new Table("t_node");
        node.addColumn(new Column("id", DataType.integer(), false));
        node.addColumn(new Column("parent_id", DataType.integer(), false));
        node.setPrimaryKey(new PrimaryKey("pk_t_node", List.of("id")));
        node.addUniqueConstraint(new UniqueConstraint("uq_t_node_parent", List.of("parent_id")));
        node.addForeignKey(new ForeignKey(List.of("parent_id"), "t_node", List.of("id"))
                .setName("fk_t_node_parent").setSelfReferential(true)
                .setOnDelete(ReferentialAction.NO_ACTION));
        schema.addTable(node);
        DatabaseUnderTest database = sqlite ? sqlite("ra-self") : h2("ra-self");
        materialize(database, schema);
        OracleContext context = context(schema, database);
        Optional<OracleFailure> failure = new ReferentialActionOracle().verify(context);
        if (failure.isPresent()) {
            check(!failure.get().getDetails().contains("fk-on-delete"),
                    "a row that is its own parent may be deleted, so no delete finding may be reported on "
                            + label + ": " + describe(failure));
        }
        if (sqlite) {
            check(failure.isEmpty(), "SQLite rejects the self-referencing update, so nothing may be "
                    + "reported at all: " + describe(failure));
        }
        check(count(database, "SELECT COUNT(*) FROM t_node") == 1L,
                "the fixture must hold exactly one, self-referencing row on " + label);
    }

    /**
     * {@code ON UPDATE CASCADE} where the child column is not called like the parent column.
     *
     * <p>The count after the update looked the parent column name up in a map keyed by the child
     * column name, found nothing, and compared {@code child_col = NULL} — which matches no row. Every
     * such probe therefore reported "expected 1 child row(s) to carry the new key, found 0" although
     * the engine had cascaded correctly (6 findings in one acceptance session, 20 in another).</p>
     */
    private static void updateCascadeIsObserved(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema schema = new RelationalSchema("update-cascade");
        Table parent = new Table("t_parent");
        parent.addColumn(new Column("id", DataType.integer(), false));
        parent.addColumn(new Column("label", DataType.varchar(10), true));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        schema.addTable(parent);

        Table child = new Table("t_child");
        child.addColumn(new Column("id", DataType.integer(), false));
        child.addColumn(new Column("parent_ref", DataType.integer(), false));
        child.setPrimaryKey(new PrimaryKey("pk_child", List.of("id")));
        child.addForeignKey(new ForeignKey(List.of("parent_ref"), "t_parent", List.of("id"))
                .setName("fk_child_parent").setOnDelete(ReferentialAction.CASCADE)
                .setOnUpdate(ReferentialAction.CASCADE));
        schema.addTable(child);

        DatabaseUnderTest database = sqlite ? sqlite("ra-upd") : h2("ra-upd");
        materialize(database, schema);
        OracleContext context = context(schema, database);
        Optional<OracleFailure> failure = new ReferentialActionOracle().verify(context);
        check(failure.isEmpty(), "ON UPDATE CASCADE must be observed on " + label + ": " + describe(failure));
        check(countEvents(context, "probe-fk-referencing-after-update") >= 1,
                "the update probe must really have counted the child rows after the update on " + label);
    }

    /**
     * A parent key that a second foreign key protects with a restrictive action.
     *
     * <p>{@code DELETE FROM t_parent WHERE id = …} is legitimately rejected when another table still
     * references that row with {@code NO ACTION}. Only the child side was checked, so 13 findings of
     * one acceptance session reported "ON DELETE CASCADE was declared but the delete was rejected"
     * for a rejection the generated schema itself had made correct.</p>
     *
     * <p>The probe picks one of the parent rows at random, so the scenario runs several seeds: with
     * the guard missing, any seed that picks a parent row the second foreign key also references
     * produces a finding.</p>
     */
    private static void restrictiveSiblingGuard(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        for (long seed = 0; seed < 12; seed++) {
            Randomly.setSeed(seed);
            RelationalSchema schema = new RelationalSchema("restrictive-sibling");
            Table parent = new Table("t_parent");
            parent.addColumn(new Column("id", DataType.integer(), false));
            parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
            schema.addTable(parent);

            Table cascading = new Table("t_a");
            cascading.addColumn(new Column("id", DataType.integer(), false));
            cascading.addColumn(new Column("pid", DataType.integer(), false));
            cascading.setPrimaryKey(new PrimaryKey("pk_a", List.of("id")));
            cascading.addForeignKey(new ForeignKey(List.of("pid"), "t_parent", List.of("id"))
                    .setName("fk_a_parent").setOnDelete(ReferentialAction.CASCADE));
            schema.addTable(cascading);

            Table restrictive = new Table("t_b");
            restrictive.addColumn(new Column("id", DataType.integer(), false));
            restrictive.addColumn(new Column("pid", DataType.integer(), false));
            restrictive.setPrimaryKey(new PrimaryKey("pk_b", List.of("id")));
            restrictive.addForeignKey(new ForeignKey(List.of("pid"), "t_parent", List.of("id"))
                    .setName("fk_b_parent").setOnDelete(ReferentialAction.NO_ACTION));
            schema.addTable(restrictive);

            DatabaseUnderTest database = sqlite ? sqlite("ra-sib") : h2("ra-sib");
            materialize(database, schema);
            OracleContext context = context(schema, database);
            Optional<OracleFailure> failure = new ReferentialActionOracle().verify(context);
            check(failure.isEmpty(), "t_b references the parent row with NO ACTION, so a rejection of the "
                    + "delete is legitimate on " + label + " (seed " + seed + "): " + describe(failure));
        }
    }

    /**
     * The pivot-row oracle must not compare its own rows against a table an earlier oracle filled.
     *
     * <p>The data oracles share one database and pivot-row runs last, so the algorithm's seed rows and
     * the rows of the constraint, referential-action and default-value oracles are still there. The
     * expectation model only knows the rows pivot-row inserted, so every aggregate query looked wrong:
     * 135 of 1 272 databases in one acceptance session reported {@code DATA_MISMATCH} while the engine
     * had returned exactly what was stored.</p>
     */
    private static void pivotAfterAnotherOracle(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        RelationalSchema schema = schema();
        DatabaseUnderTest database = sqlite ? sqlite("pv-left") : h2("pv-left");
        materialize(database, schema);
        // what an earlier oracle leaves behind
        database.getConnection().executeStatement("INSERT INTO t_author (id, name) VALUES (901, 'leftover')");
        database.getConnection().executeStatement("INSERT INTO t_book (id, author_id, title) VALUES (902, 901, 'x')");
        OracleContext context = context(schema, database);
        Optional<OracleFailure> failure = new PivotRowOracle().verify(context);
        check(failure.isEmpty(), "the pivot-row oracle must own the tables it queries on " + label + ": "
                + describe(failure));
        check(countEvents(context, "probe-reset") >= 1,
                "the pivot-row oracle must empty the tables before populating them on " + label);
        check(count(database, "SELECT COUNT(*) FROM t_author") >= 1L,
                "the tables must still hold the rows the oracle inserted on " + label);
    }

    /**
     * A {@code NOT NULL} generated column whose expression is arithmetic over a nullable column.
     *
     * <p>{@code GENERATED ALWAYS AS (a + 1) NOT NULL} is NULL whenever {@code a} is NULL, and the
     * engine then rejects the INSERT. The row generator skips generated columns — it cannot evaluate
     * the expression — and the constraint oracle's own check skips them too, so the rejection was
     * reported as the engine being too strict about a row DDLsmith had declared valid. This was the
     * one class of {@code CONSTRAINT_TOO_STRICT} that still survived the existing guard.</p>
     */
    private static void generatedNotNullColumn(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        for (long seed = 0; seed < 10; seed++) {
            Randomly.setSeed(seed);
            RelationalSchema schema = new RelationalSchema("generated-not-null");
            Table table = new Table("t_gen");
            table.addColumn(new Column("id", DataType.integer(), false));
            table.addColumn(new Column("a", DataType.integer(), true));
            table.addColumn(new Column("b", DataType.integer(), false).setGeneratedExpression("a + 1"));
            table.setPrimaryKey(new PrimaryKey("pk_t_gen", List.of("id")));
            // a unique constraint on the nullable source column is what makes the generator leave it
            // NULL sometimes, which is the case that used to be misreported
            table.addUniqueConstraint(new UniqueConstraint("uq_t_gen_a", List.of("a")));
            schema.addTable(table);
            DatabaseUnderTest database = sqlite ? sqlite("gen-nn") : h2("gen-nn");
            materialize(database, schema);
            OracleContext context = context(schema, database);
            Optional<OracleFailure> failure = new ConstraintEnforcementOracle().verify(context);
            check(failure.isEmpty(), "a row whose generated NOT NULL column would be NULL must not be "
                    + "reported as the engine being too strict on " + label + " (seed " + seed + "): "
                    + describe(failure));
        }
    }

    /**
     * {@code NO ACTION} is checked once the statement has finished, so a second foreign key of the
     * same child row may legitimately have removed the referencing row in between.
     *
     * <p>One generated row carried the parent key in two columns — one foreign key {@code CASCADE},
     * one {@code NO ACTION} — and the cascade deleted it, which leaves nothing for {@code NO ACTION}
     * to object to. Reporting that as "the delete was accepted although a child row still references
     * the parent row" was a finding about the generated schema rather than about the engine
     * ({@code runs/round2/constraints/logs/sqlite/database22-failure-001-constraint_not_enforced.log}).</p>
     *
     * <p>{@code RESTRICT} is deliberately not covered: it is checked as soon as the parent row is
     * touched, and SQLite really does evaluate a sibling cascade first — a minimal reproduction is in
     * the acceptance report. A finding there is a finding about SQLite.</p>
     */
    private static void noActionAfterASiblingCascade(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        for (long seed = 0; seed < 20; seed++) {
            Randomly.setSeed(seed);
            RelationalSchema schema = new RelationalSchema("no-action-after-cascade");
            Table parent = new Table("t_parent");
            parent.addColumn(new Column("id", DataType.integer(), false));
            parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
            schema.addTable(parent);

            Table both = new Table("t_both");
            both.addColumn(new Column("id", DataType.integer(), false));
            both.addColumn(new Column("cascade_ref", DataType.integer(), false));
            both.addColumn(new Column("noaction_ref", DataType.integer(), false));
            both.setPrimaryKey(new PrimaryKey("pk_both", List.of("id")));
            both.addForeignKey(new ForeignKey(List.of("cascade_ref"), "t_parent", List.of("id"))
                    .setName("fk_both_cascade").setOnDelete(ReferentialAction.CASCADE));
            both.addForeignKey(new ForeignKey(List.of("noaction_ref"), "t_parent", List.of("id"))
                    .setName("fk_both_noaction").setOnDelete(ReferentialAction.NO_ACTION));
            schema.addTable(both);

            DatabaseUnderTest database = sqlite ? sqlite("ra-noact") : h2("ra-noact");
            materialize(database, schema);
            // one row that carries the parent key in both columns, which is what makes the cascade
            // remove the row the NO ACTION check would otherwise still see
            database.getConnection().executeStatement("INSERT INTO t_parent (id) VALUES (1)");
            database.getConnection().executeStatement(
                    "INSERT INTO t_both (id, cascade_ref, noaction_ref) VALUES (10, 1, 1)");
            OracleContext context = context(schema, database);
            Optional<OracleFailure> failure = new ReferentialActionOracle().verify(context);
            if (failure.isPresent()) {
                check(!failure.get().getDetails().contains("fk-on-delete-NO_ACTION"),
                        "the referencing row is removed by the sibling cascade, so NO ACTION has nothing "
                                + "to reject on " + label + " (seed " + seed + "): " + describe(failure));
            }
        }
    }

    /**
     * Two cascading foreign keys from one child table to one parent, which is what a join table for a
     * relationship with two roles looks like.
     *
     * <p>The check counted only the rows that reach the parent through the key under test, so a row
     * that reached it through the other cascading key was an unexpected disappearance:
     * "expected 9 child rows to remain, found 8"
     * ({@code runs/final-bugs/logs/sqlite/database13-failure-001-standard_deviation.log}). The
     * expectation is now taken over a single disjunction of every cascading key of the table, so a
     * row is counted once however many keys point at the parent.</p>
     */
    private static void twoCascadingKeysCountTheRowOnce(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        for (long seed = 0; seed < 20; seed++) {
            Randomly.setSeed(seed);
            RelationalSchema schema = new RelationalSchema("two-cascading-keys");
            Table parent = new Table("t_parent");
            parent.addColumn(new Column("id", DataType.integer(), false));
            parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
            schema.addTable(parent);

            Table child = new Table("t_child");
            child.addColumn(new Column("id", DataType.integer(), false));
            child.addColumn(new Column("src_ref", DataType.integer(), false));
            child.addColumn(new Column("dst_ref", DataType.integer(), false));
            child.setPrimaryKey(new PrimaryKey("pk_child", List.of("id")));
            child.addForeignKey(new ForeignKey(List.of("src_ref"), "t_parent", List.of("id"))
                    .setName("fk_child_src").setOnDelete(ReferentialAction.CASCADE));
            child.addForeignKey(new ForeignKey(List.of("dst_ref"), "t_parent", List.of("id"))
                    .setName("fk_child_dst").setOnDelete(ReferentialAction.CASCADE));
            schema.addTable(child);

            DatabaseUnderTest database = sqlite ? sqlite("ra-2ck") : h2("ra-2ck");
            materialize(database, schema);
            // one row reaching the parent through both keys, one only through the second: the second
            // is the row the key under test does not see, and the cascade removes it anyway
            database.getConnection().executeStatement("INSERT INTO t_parent (id) VALUES (1)");
            database.getConnection().executeStatement("INSERT INTO t_parent (id) VALUES (2)");
            database.getConnection().executeStatement(
                    "INSERT INTO t_child (id, src_ref, dst_ref) VALUES (10, 1, 1)");
            database.getConnection().executeStatement(
                    "INSERT INTO t_child (id, src_ref, dst_ref) VALUES (11, 2, 1)");
            OracleContext context = context(schema, database);
            Optional<OracleFailure> failure = new ReferentialActionOracle().verify(context);
            if (failure.isPresent()) {
                check(!failure.get().getDetails().contains("STANDARD_DEVIATION"),
                        "a row removed through the other cascading key is not a deviation on " + label
                                + " (seed " + seed + "): " + describe(failure));
            }
        }
    }

    /**
     * A parent key column that is itself a foreign key column of the parent table.
     *
     * <p>A 1:1 extension table - or any table whose natural key is shared with the table it extends -
     * has a primary key that is also a foreign key. The update probe derives a key value that is absent
     * from the <em>parent</em> table, so the grandparent cannot contain it either, and rewriting the
     * column leaves the parent's own foreign key unsatisfied. Every engine rejects that, correctly:
     * MySQL with 1452 "Cannot add or update a child row" (the MySQL 8.4 Reference Manual, "Using
     * Foreign Keys", shows the same statement rejected the same way), SQLite with "FOREIGN KEY
     * constraint failed". The oracle used to report it as "ON UPDATE CASCADE was declared but the
     * update was rejected" - seed 3200001 of the 2026-10-05 MySQL round, {@code
     * mv_e2_0[t_2_id] -> t_2[id]} with {@code t_2.id} itself referencing {@code t_0.id}.</p>
     *
     * <p>Both directions are checked: with the parent's own foreign key in place no probe may rewrite
     * the parent key at all, and without it the very same probe has to run and be accepted - otherwise
     * a guard that skipped every update probe would pass this check.</p>
     */
    private static void parentKeyIsAlsoAForeignKeyColumn(boolean sqlite) throws Exception {
        String label = sqlite ? "SQLite" : "H2";
        long controlProbes = 0;
        for (long seed = 0; seed < 20; seed++) {
            Randomly.setSeed(seed);
            RelationalSchema guarded = parentKeyIsForeignKeySchema(true);
            DatabaseUnderTest guardedDatabase = sqlite ? sqlite("ra-pkfk") : h2("ra-pkfk");
            materialize(guardedDatabase, guarded);
            OracleContext guardedContext = context(guarded, guardedDatabase);
            Optional<OracleFailure> failure = new ReferentialActionOracle().verify(guardedContext);
            check(failure.isEmpty(), "referential-action must not report when the parent key column is itself "
                    + "a foreign key column on " + label + " (seed " + seed + "): " + describe(failure));
            check(updateProbesOf(guardedContext, "t_2") == 0, "the update probe must be skipped when the "
                    + "parent key column is itself a foreign key column on " + label + " (seed " + seed + ")");

            RelationalSchema control = parentKeyIsForeignKeySchema(false);
            DatabaseUnderTest controlDatabase = sqlite ? sqlite("ra-pkfk-ctl") : h2("ra-pkfk-ctl");
            materialize(controlDatabase, control);
            OracleContext controlContext = context(control, controlDatabase);
            Optional<OracleFailure> controlFailure = new ReferentialActionOracle().verify(controlContext);
            check(controlFailure.isEmpty(), "the control schema must not report on " + label + " (seed " + seed
                    + "): " + describe(controlFailure));
            controlProbes += updateProbesOf(controlContext, "t_2");
        }
        // The control is not asserted per seed: whether the update probe has a candidate left depends on
        // how the row generator happened to distribute the child rows, and at some seeds the delete probe
        // removes the only referenced parent row. What has to hold is that the probe does run for this
        // shape - otherwise a guard that skipped every update probe would pass this check.
        check(controlProbes > 0, "without the parent's own foreign key the update probe must run for at least "
                + "one seed on " + label + ", otherwise the guard is not what skipped it");
    }

    /**
     * The schema of the false positive - a child table referencing a parent whose key column is itself a
     * foreign key to a grandparent - and, with {@code parentKeyIsAForeignKey} false, the same schema
     * without that one constraint, which is the control the guard is measured against.
     */
    public static RelationalSchema parentKeyIsForeignKeySchema(boolean parentKeyIsAForeignKey) {
        RelationalSchema schema = new RelationalSchema("parent-key-is-fk");
        Table grandparent = new Table("t_0");
        grandparent.addColumn(new Column("id", DataType.integer(), false));
        grandparent.setPrimaryKey(new PrimaryKey("pk_t_0", List.of("id")));
        schema.addTable(grandparent);

        Table parent = new Table("t_2");
        parent.addColumn(new Column("id", DataType.integer(), false));
        parent.setPrimaryKey(new PrimaryKey("pk_t_2", List.of("id")));
        if (parentKeyIsAForeignKey) {
            parent.addForeignKey(new ForeignKey(List.of("id"), "t_0", List.of("id"))
                    .setName("t_2_ibfk_1").setOnDelete(ReferentialAction.RESTRICT)
                    .setOnUpdate(ReferentialAction.CASCADE));
        }
        schema.addTable(parent);

        Table child = new Table("mv_e2_0");
        child.addColumn(new Column("t_2_id", DataType.integer(), false));
        child.addColumn(new Column("value_col", DataType.integer(), false));
        child.setPrimaryKey(new PrimaryKey("pk_mv_e2_0", List.of("t_2_id", "value_col")));
        child.addForeignKey(new ForeignKey(List.of("t_2_id"), "t_2", List.of("id"))
                .setName("fk_mv").setOnDelete(ReferentialAction.CASCADE)
                .setOnUpdate(ReferentialAction.CASCADE));
        schema.addTable(child);
        return schema;
    }

    /** The intended schema: primary keys, a UNIQUE, two CHECKs, two NOT NULLs, a CASCADE FK and defaults. */
    public static RelationalSchema schema() {        RelationalSchema schema = new RelationalSchema("self-check");

        Table author = new Table("t_author");
        author.addColumn(new Column("id", DataType.integer(), false));
        author.addColumn(new Column("name", DataType.varchar(20), false));
        author.addColumn(new Column("code", DataType.varchar(10), true));
        author.addColumn(new Column("score", DataType.integer(), true).setDefaultExpression("7"));
        author.setPrimaryKey(new PrimaryKey(List.of("id")));
        author.addUniqueConstraint(new UniqueConstraint("uq_author_code", List.of("code")));
        author.addCheckConstraint(new CheckConstraint("ck_author_score", "score >= 0"));
        schema.addTable(author);

        Table book = new Table("t_book");
        book.addColumn(new Column("id", DataType.integer(), false));
        book.addColumn(new Column("author_id", DataType.integer(), false));
        book.addColumn(new Column("title", DataType.varchar(20), true));
        book.addColumn(new Column("price", DataType.integer(), true).setDefaultExpression("0"));
        book.setPrimaryKey(new PrimaryKey(List.of("id")));
        book.addForeignKey(new ForeignKey(List.of("author_id"), "t_author", List.of("id"))
                .setName("fk_book_author").setOnDelete(ReferentialAction.CASCADE));
        book.addCheckConstraint(new CheckConstraint("ck_book_price", "price >= 0"));
        schema.addTable(book);
        return schema;
    }

    public static DatabaseUnderTest sqlite(String name) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        DDLsmithDBConnection wrapper = new DDLsmithDBConnection(connection, name);
        // the dialect's session setup statement, executed exactly as the runner would
        for (String setup : new TestSqliteDialect().getSessionSetupStatements()) {
            wrapper.executeStatement(setup);
        }
        return new DatabaseUnderTest(name, wrapper, new TestSqliteDialect(), new TestSqliteIntrospector());
    }

    public static DatabaseUnderTest h2(String name) throws SQLException {
        h2Counter++;
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:cst_" + h2Counter + ";DB_CLOSE_DELAY=-1");
        return new DatabaseUnderTest(name, new DDLsmithDBConnection(connection, name), new TestH2Dialect(),
                new TestH2Introspector());
    }

    public static OracleContext context(RelationalSchema schema, DatabaseUnderTest database) {
        return new OracleContext().setIntendedSchema(schema).setSeed(Randomly.getSeed()).addDatabase(database);
    }

    public static void materialize(DatabaseUnderTest database, RelationalSchema schema) throws SQLException {
        Dialect dialect = database.getDialect();
        for (Table table : schema.getTables()) {
            database.getConnection().executeStatement(dialect.render(new CreateTable(table)));
        }
    }

    public static long count(DatabaseUnderTest database, String sql) throws SQLException {
        Object value = database.getConnection().executeAndGet(sql).getValue(0, 0);
        return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
    }

    static long countEvents(OracleContext context, String purposePrefix) {
        long count = 0;
        for (ExecutionEvent event : context.getExecutionEvents()) {
            if (event.getPurpose() != null && event.getPurpose().startsWith(purposePrefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts the update probes that rewrote the key of {@code tableName}, whatever the dialect quotes
     * the identifier with. Used to tell "the guard skipped the probe" apart from "the probe ran and the
     * engine happened to accept it".
     */
    public static long updateProbesOf(OracleContext context, String tableName) {
        long count = 0;
        for (ExecutionEvent event : context.getExecutionEvents()) {
            if (event.getSql() == null) {
                continue;
            }
            String sql = event.getSql().replace("\"", "").replace("`", "");
            if (sql.startsWith("UPDATE " + tableName + " ")) {
                count++;
            }
        }
        return count;
    }

    /** Counts the rows the oracle really stored in a table (its own INSERTs, not the DDL). */
    static long countInserts(OracleContext context, String tableName) {
        long count = 0;
        for (ExecutionEvent event : context.getExecutionEvents()) {
            if ("probe-insert".equals(event.getPurpose()) && event.isSucceeded()
                    && event.getSql().startsWith("INSERT INTO " + tableName + " ")) {
                count++;
            }
        }
        return count;
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static String describe(Optional<OracleFailure> failure) {
        if (failure.isEmpty()) {
            return "<no failure>";
        }
        OracleFailure value = failure.get();
        return value.getKind() + " / " + value.getTitle() + "\n" + value.getDetails();
    }

    /** Runs one oracle and returns its failure, for ad-hoc use from the JUnit test. */
    static Optional<OracleFailure> run(TestOracle oracle, RelationalSchema schema, DatabaseUnderTest database)
            throws Exception {
        return oracle.verify(context(schema, database));
    }

    /** Diagnostic entry point: prints every statement a metamorphic run executed. */
    private static void debugMetamorphic() throws Exception {
        Randomly.setSeed(20241004L);
        RelationalSchema intended = schema();
        DatabaseUnderTest database = h2("debug-mm");
        materialize(database, intended);
        OracleContext context = context(intended, database);
        Optional<OracleFailure> failure = new MetamorphicAlterOracle().verify(context);
        for (ExecutionEvent event : context.getExecutionEvents()) {
            System.out.println(event);
        }
        System.out.println("failure: " + describe(failure));
    }

    /** Diagnostic entry point: prints the full report of the deliberately broken schema. */
    private static void debugReport() throws Exception {
        Randomly.setSeed(20241004L);
        RelationalSchema intended = schema();
        RelationalSchema actual = schema();
        actual.getTable("t_author").getUniqueConstraints().clear();
        actual.getTable("t_book").getCheckConstraints().clear();
        DatabaseUnderTest database = h2("debug-report");
        materialize(database, actual);
        Optional<OracleFailure> failure = new ConstraintEnforcementOracle()
                .verify(context(intended, database));
        System.out.println(failure.map(OracleFailure::toLogString).orElse("<no failure>"));
    }
}
