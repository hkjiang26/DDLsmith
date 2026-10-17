package org.ddlsmith.oracle.impl;

import java.util.Optional;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.TestOracle;

/**
 * TEST SCAFFOLDING, not part of the deliverable.
 *
 * <p>Runs the schema level oracles against real in-memory SQLite and H2 databases and asserts what
 * they must report. The databases are built directly with JDBC by {@link OracleTestFixture}; the
 * {@code SchemaIntrospector}s are the local test scaffolding in this package, so the check does not
 * depend on the engine modules that are written in parallel.</p>
 *
 * <p>Run with</p>
 *
 * <pre>
 * java -cp "target\oracle-classes;target\core-api-classes;&lt;deps&gt;" org.ddlsmith.oracle.impl.OracleSelfCheck
 * </pre>
 *
 * <p>Every {@code check...} method is public and throws {@link AssertionError} on failure, so the
 * JUnit test {@code org.ddlsmith.oracle.SchemaOracleTest} can reuse exactly the same assertions.</p>
 */
public final class OracleSelfCheck {

    private static int checks;

    private OracleSelfCheck() {
    }

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        runAll();
        System.out.println("OracleSelfCheck: all " + checks + " checks passed in "
                + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    /** Runs every check; the first failure aborts with an {@link AssertionError}. */
    public static void runAll() throws Exception {
        checks = 0;
        checkCrashOracleIsSilentForASuccessfullyCreatedSchema();
        checkCrashOracleReportsAnUnexpectedFailure();
        checkCrashOracleReportsADeclaredFeatureFailure();
        checkCrashOracleIgnoresAnUndeclaredFeatureFailure();
        checkCrashOracleIgnoresAnExpectedProbeFailure();
        checkCrashOracleIgnoresAGeneratedRowFailure();
        checkCrashOracleReportsAnAcceptedProbeStatement();
        checkCrashOraclePrefersSchemaFailuresOverProbeFailures();
        checkRoundTripIsSilentForAMatchingSchema();
        checkRoundTripReportsAnExtraTable();
        checkRoundTripReportsAWrongColumnType();
        checkRoundTripReportsATableTheEngineAddedOnItsOwn();
        checkRoundTripUsesThePerDatabaseIntendedSchema();
        checkDifferentialIsSilentForEquivalentSchemas();
        checkDifferentialReportsAMissingTable();
        checkDifferentialReportsRowsTheEngineInsertedByItself();
        checkDifferentialIsNotApplicableToASingleDatabase();
        checkIdempotenceIsSilentOnSqliteAndH2();
        checkIdempotenceReportsAnOverstatedFeature();
    }

    /* ------------------------------------------------------------------ crash oracle */

    public static void checkCrashOracleIsSilentForASuccessfullyCreatedSchema() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            assertNoFailure(new CrashOracle(), fixture.context,
                    "a schema that was created successfully must not be reported by the crash oracle");
        }
    }

    public static void checkCrashOracleReportsAnUnexpectedFailure() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            fixture.context.addExecutionEvent(new ExecutionEvent("sqlite",
                    "CREATE TABLE t_broken (a INTEGER, PRIMARY KEY (a))", false,
                    "near \"PRIMARY\": syntax error", "42000", 1, 3, false, "create-table[COMPOSITE_PRIMARY_KEY]"));
            OracleFailure failure = assertFailure(new CrashOracle(), fixture.context, FailureKind.UNEXPECTED_ERROR,
                    "a statement that failed unexpectedly must be reported");
            assertContains(failure.getDetails(), "CREATE TABLE t_broken", "details must quote the failing SQL");
            assertContains(failure.getDetails(), "near \"PRIMARY\"", "details must quote the engine message");
            assertContains(failure.getDetails(), "42000", "details must quote the SQLState");
            assertContains(failure.getDetails(), "COMPOSITE_PRIMARY_KEY",
                    "details must name the feature the statement relies on");
            assertContains(failure.getReproducerSql(), "FAILS HERE",
                    "the reproducer must mark the failing statement");
            assertContains(failure.getReproducerSql(), "PRAGMA foreign_keys = ON",
                    "the reproducer must contain the statements executed before the failure");
        }
    }

    public static void checkCrashOracleReportsADeclaredFeatureFailure() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            // SQLState 0A000 is "feature not supported"; the purpose names a feature the SQLite test
            // dialect declares (CREATE_VIEW), so the engine contradicted its own feature set.
            fixture.context.addExecutionEvent(new ExecutionEvent("sqlite", "CREATE VIEW v AS SELECT 1", false,
                    "not supported", "0A000", 1, 1, false, "create-view[CREATE_VIEW]"));
            assertFailure(new CrashOracle(), fixture.context, FailureKind.UNEXPECTED_ERROR,
                    "a feature-not-supported failure of a declared feature must be reported");
        }
    }

    public static void checkCrashOracleIgnoresAnUndeclaredFeatureFailure() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            // The purpose names MATERIALIZED_VIEW, which the SQLite test dialect does not declare: the
            // generator emitted a construct the dialect never claimed to support, so it is a generator
            // bug rather than an engine defect.
            fixture.context.addExecutionEvent(new ExecutionEvent("sqlite", "CREATE MATERIALIZED VIEW v AS SELECT 1",
                    false, "not supported", "0A000", 1, 1, false, "create-view[MATERIALIZED_VIEW]"));
            assertNoFailure(new CrashOracle(), fixture.context,
                    "a feature-not-supported failure of an undeclared feature must not be reported");
        }
    }

    public static void checkCrashOracleIgnoresAnExpectedProbeFailure() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            fixture.context.addExecutionEvent(new ExecutionEvent("sqlite",
                    "INSERT INTO t_parent (id, code, qty) VALUES (1, 'a', -1)", false,
                    "CHECK constraint failed: ck_parent_qty", "23000", 19, 1, true, "probe-check-constraint"));
            assertNoFailure(new CrashOracle(), fixture.context,
                    "a probe that was expected to fail must not be reported");
        }
    }

    /**
     * A row DDLsmith generated is not part of the DDL contract this oracle asks about. An INSERT
     * rejected for a unique or foreign key violation means the row generator did not respect a
     * constraint of its own schema — DDLsmith's mistake, reported by the data oracles as "the table
     * could not be populated". Reporting it here filed "engine rejected seed-insert" at high
     * confidence, which blames the engine for DDLsmith's own defect.
     */
    public static void checkCrashOracleIgnoresAGeneratedRowFailure() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            fixture.context.addExecutionEvent(new ExecutionEvent("sqlite",
                    "INSERT INTO t_child (id, parent_id) VALUES (1, 99)", false,
                    "[SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed", "23000", 19, 1, false,
                    "seed-insert"));
            assertNoFailure(new CrashOracle(), fixture.context,
                    "a row the generator got wrong must not be reported as an engine defect");
        }
    }

    public static void checkCrashOracleReportsAnAcceptedProbeStatement() throws Exception {        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            fixture.context.addExecutionEvent(new ExecutionEvent("sqlite",
                    "INSERT INTO t_parent (id, code, qty) VALUES (1, 'a', -1)", true, null, null, 0, 1, true,
                    "probe-check-constraint"));
            OracleFailure failure = assertFailure(new CrashOracle(), fixture.context, FailureKind.UNEXPECTED_ERROR,
                    "a probe statement that was accepted must be reported as a possible engine defect");
            assertContains(failure.getDetails(), "expected to be rejected",
                    "the report must explain that the statement should have been rejected");
        }
    }

    /** A table building failure is more interesting than a probe failure and must win. */
    public static void checkCrashOraclePrefersSchemaFailuresOverProbeFailures() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            fixture.context.addExecutionEvent(new ExecutionEvent("sqlite",
                    "INSERT INTO t_parent (id, code, qty) VALUES (1, 'a', 5)", false,
                    "syntax error", "42000", 1, 1, false, "probe-insert[INLINE_CHECK]"));
            fixture.context.addExecutionEvent(new ExecutionEvent("sqlite",
                    "CREATE TABLE t_late (a INTEGER, b INTEGER, PRIMARY KEY (a, b))", false,
                    "syntax error", "42000", 1, 1, false, "create[COMPOSITE_PRIMARY_KEY]"));
            OracleFailure failure = assertFailure(new CrashOracle(), fixture.context, FailureKind.UNEXPECTED_ERROR,
                    "the failing statement must be reported");
            assertContains(failure.getDetails(), "CREATE TABLE t_late",
                    "the report must be about the CREATE TABLE failure, not the probe failure");
        }
    }

    /* ------------------------------------------------------------------ round trip oracle */

    public static void checkRoundTripIsSilentForAMatchingSchema() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            assertNoFailure(new SchemaRoundTripOracle(), fixture.context,
                    "the intended schema must round trip through the SQLite and H2 catalogs unchanged");
        }
    }

    public static void checkRoundTripReportsAnExtraTable() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            RelationalSchema intended = fixture.schema.copy();
            Table ghost = new Table(OracleTestFixture.SCHEMA_NAME, "t_ghost");
            ghost.addColumn(new org.ddlsmith.common.schema.Column("id", DataType.integer(), false));
            ghost.setPrimaryKey(new org.ddlsmith.common.schema.PrimaryKey(java.util.List.of("id")));
            intended.addTable(ghost);
            fixture.context.setIntendedSchema(intended);
            OracleFailure failure = assertFailure(new SchemaRoundTripOracle(), fixture.context,
                    FailureKind.SCHEMA_MISMATCH, "a table that the engine did not create must be reported");
            assertContains(failure.getDetails(), "MISSING_TABLE",
                    "the report must name the kind of difference");
            assertContains(failure.getDetails(), "t_ghost", "the report must name the missing table");
        }
    }

    public static void checkRoundTripReportsAWrongColumnType() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            RelationalSchema intended = fixture.schema.copy();
            intended.getTable("t_parent").getColumn("code").setType(DataType.varchar(50));
            fixture.context.setIntendedSchema(intended);
            OracleFailure failure = assertFailure(new SchemaRoundTripOracle(), fixture.context,
                    FailureKind.SCHEMA_MISMATCH, "a column type the engine did not preserve must be reported");
            assertContains(failure.getDetails(), "COLUMN_TYPE", "the report must name the kind of difference");
            assertContains(failure.getDetails(), "VARCHAR(50)", "the report must show the intended type");
            assertContains(failure.getDetails(), "VARCHAR(20)", "the report must show the introspected type");
        }
    }

    /** A table the engine created although DDLsmith never asked for it must never be filtered away. */
    public static void checkRoundTripReportsATableTheEngineAddedOnItsOwn() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            fixture.sqliteConnection.executeStatement("CREATE TABLE t_stray (x INTEGER)");
            OracleFailure failure = assertFailure(new SchemaRoundTripOracle(), fixture.context,
                    FailureKind.SCHEMA_MISMATCH, "a table the engine created on its own must be reported");
            assertContains(failure.getDetails(), "UNEXPECTED_TABLE",
                    "the report must name the kind of difference");
            assertContains(failure.getDetails(), "t_stray", "the report must name the stray table");
        }
    }

    /** The per-database intended schema must win over the context wide one. */    public static void checkRoundTripUsesThePerDatabaseIntendedSchema() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            // The context wide schema does not contain t_child, but every database does: the round trip
            // oracle must use the per-database schema and stay silent (an UNEXPECTED_TABLE would be the
            // artifact of comparing against the wrong schema).
            fixture.context.setIntendedSchema(OracleTestFixture.schemaWithout(OracleTestFixture.LEAF_TABLE));
            fixture.sqlite.setIntendedSchema(fixture.schema);
            fixture.h2.setIntendedSchema(fixture.schema);
            assertNoFailure(new SchemaRoundTripOracle(), fixture.context,
                    "the per-database intended schema must be preferred over the context wide one");
        }
    }

    /* ------------------------------------------------------------------ differential oracle */

    public static void checkDifferentialIsSilentForEquivalentSchemas() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            assertNoFailure(new DifferentialSchemaOracle(), fixture.context,
                    "SQLite and H2 holding the same schema must not be reported as a disagreement");
        }
    }

    public static void checkDifferentialReportsAMissingTable() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create(OracleTestFixture.LEAF_TABLE)) {
            OracleFailure failure = assertFailure(new DifferentialSchemaOracle(), fixture.context,
                    FailureKind.DIFFERENTIAL, "a table that only one engine created must be reported");
            assertContains(failure.getDetails(), OracleTestFixture.LEAF_TABLE,
                    "the report must name the table the databases disagree about");
            assertContains(failure.getDetails(), "sqlite", "the report must name the left database");
            assertContains(failure.getDetails(), "h2", "the report must name the right database");
        }
    }

    /** A schema that was just created is empty; rows that appear out of nowhere are a defect. */
    public static void checkDifferentialReportsRowsTheEngineInsertedByItself() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            fixture.sqliteConnection.executeStatement(
                    "INSERT INTO t_parent (id, code, qty) VALUES (1, 'silent', 5)");
            OracleFailure failure = assertFailure(new DifferentialSchemaOracle(), fixture.context,
                    FailureKind.DIFFERENTIAL, "a table that is not empty must be reported");
            assertContains(failure.getDetails(), "row count", "the report must name the semantic check");
            assertContains(failure.getDetails(), "t_parent", "the report must name the table");
        }
    }

    public static void checkDifferentialIsNotApplicableToASingleDatabase() throws Exception {        try (OracleTestFixture fixture = OracleTestFixture.createSingle()) {
            assertNoFailure(new DifferentialSchemaOracle(), fixture.context,
                    "the differential oracle is not applicable to a single database run");
        }
        assertNoFailure(new DifferentialSchemaOracle(), OracleTestFixture.emptyContext(),
                "the differential oracle is not applicable without databases");
    }

    /* ------------------------------------------------------------------ idempotence oracle */

    public static void checkIdempotenceIsSilentOnSqliteAndH2() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            assertNoFailure(new IdempotenceOracle(), fixture.context,
                    "the IF [NOT] EXISTS and OR REPLACE guarantees SQLite and H2 declare must hold");
            // the probes must not leave anything behind
            assertFalse(tableExists(fixture, fixture.sqlite, "idem_probe_1"),
                    "the SQLite scratch table must be dropped again");
            assertFalse(tableExists(fixture, fixture.h2, "idem_probe_2"),
                    "the H2 scratch table must be dropped again");
        }
    }

    /**
     * A dialect that declares a feature the engine does not implement must be reported: this is the
     * failure mode the idempotence oracle exists for.
     */
    public static void checkIdempotenceReportsAnOverstatedFeature() throws Exception {
        try (OracleTestFixture fixture = OracleTestFixture.create()) {
            OracleContext.DatabaseUnderTest overstated = new OracleContext.DatabaseUnderTest("sqlite",
                    fixture.sqliteConnection, TestDialects.overstatingSqliteDialect(), new SqliteTestIntrospector());
            OracleContext context = new OracleContext().setIntendedSchema(fixture.schema).setSeed(42L)
                    .addDatabase(overstated);
            OracleFailure failure = assertFailure(new IdempotenceOracle(), context, FailureKind.IDEMPOTENCE,
                    "a dialect that declares CREATE SCHEMA IF NOT EXISTS on SQLite must be contradicted");
            assertContains(failure.getDetails(), "CREATE SCHEMA IF NOT EXISTS",
                    "the report must name the guarantee that was broken");
            assertContains(failure.getReproducerSql(), "FAILS HERE",
                    "the reproducer must mark the failing statement");
        }
    }

    private static boolean tableExists(OracleTestFixture fixture, OracleContext.DatabaseUnderTest database,
            String name) {
        try {
            RelationalSchema introspected = database.getIntrospector().introspect(database.getConnection(),
                    OracleSupport.resolveSchemaName(fixture.context, database));
            for (Table table : introspected.getTables()) {
                if (table.getName().equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new AssertionError("could not introspect after the idempotence probes: " + e, e);
        }
    }

    /* ------------------------------------------------------------------ assertions */

    private static void assertNoFailure(TestOracle oracle, OracleContext context, String message) throws Exception {
        checks++;
        Optional<OracleFailure> failure = oracle.verify(context);
        if (failure.isPresent()) {
            throw new AssertionError(message + "\nbut " + oracle.getName() + " reported:\n"
                    + failure.get().toLogString());
        }
        System.out.println("  ok: " + oracle.getName() + " - " + message);
    }

    private static OracleFailure assertFailure(TestOracle oracle, OracleContext context, FailureKind kind,
            String message) throws Exception {
        checks++;
        Optional<OracleFailure> failure = oracle.verify(context);
        if (!failure.isPresent()) {
            throw new AssertionError(message + "\nbut " + oracle.getName() + " reported nothing");
        }
        if (failure.get().getKind() != kind) {
            throw new AssertionError(message + "\nbut " + oracle.getName() + " reported kind "
                    + failure.get().getKind() + " instead of " + kind + ":\n" + failure.get().toLogString());
        }
        System.out.println("  ok: " + oracle.getName() + " - " + message + " [" + failure.get().getTitle() + "]");
        return failure.get();
    }

    private static void assertContains(String text, String expected, String message) {
        if (text == null || !text.contains(expected)) {
            throw new AssertionError(message + ": expected to find \"" + expected + "\" in:\n" + text);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }
}
