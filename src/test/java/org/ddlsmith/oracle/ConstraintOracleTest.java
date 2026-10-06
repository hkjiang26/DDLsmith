package org.ddlsmith.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.oracle.impl.ConstraintEnforcementOracle;
import org.ddlsmith.oracle.impl.ConstraintOracleSelfCheck;
import org.ddlsmith.oracle.impl.DefaultValueOracle;
import org.ddlsmith.oracle.impl.MetamorphicAlterOracle;
import org.ddlsmith.oracle.impl.PivotRowOracle;
import org.ddlsmith.oracle.impl.ReferentialActionOracle;
import org.ddlsmith.oracle.impl.TestH2Dialect;
import org.ddlsmith.oracle.impl.TestH2Introspector;
import org.ddlsmith.oracle.impl.TestSqliteDialect;
import org.ddlsmith.oracle.impl.TestSqliteIntrospector;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.junit.jupiter.api.Test;

/**
 * Drives the five constraint/action/metamorphic/data oracles against real in-memory SQLite and H2
 * databases.
 *
 * <p>The scenario fixtures live in {@link ConstraintOracleSelfCheck}, which is also runnable from
 * the command line; this test adds the JUnit level assertions on the failure kinds. Both run under
 * {@code mvn test} like every other suite.</p>
 */
class ConstraintOracleTest {

    private static final long SEED = 20241004L;

    @Test
    void constraintEnforcementFindsNothingOnACorrectSchema() throws Exception {
        for (boolean sqlite : new boolean[] { true, false }) {
            Randomly.setSeed(SEED);
            RelationalSchema schema = ConstraintOracleSelfCheck.schema();
            OracleContext.DatabaseUnderTest database = sqlite ? ConstraintOracleSelfCheck.sqlite("t-ce-ok")
                    : ConstraintOracleSelfCheck.h2("t-ce-ok");
            ConstraintOracleSelfCheck.materialize(database, schema);
            Optional<OracleFailure> failure = new ConstraintEnforcementOracle()
                    .verify(ConstraintOracleSelfCheck.context(schema, database));
            assertTrue(failure.isEmpty(), () -> engine(sqlite) + ": " + describe(failure));
        }
    }

    @Test
    void constraintEnforcementReportsADroppedUniqueConstraint() throws Exception {
        for (boolean sqlite : new boolean[] { true, false }) {
            Randomly.setSeed(SEED);
            RelationalSchema intended = ConstraintOracleSelfCheck.schema();
            RelationalSchema actual = ConstraintOracleSelfCheck.schema();
            actual.getTable("t_author").getUniqueConstraints().clear();
            actual.getTable("t_book").getCheckConstraints().clear();
            OracleContext.DatabaseUnderTest database = sqlite ? ConstraintOracleSelfCheck.sqlite("t-ce-bad")
                    : ConstraintOracleSelfCheck.h2("t-ce-bad");
            ConstraintOracleSelfCheck.materialize(database, actual);
            Optional<OracleFailure> failure = new ConstraintEnforcementOracle()
                    .verify(ConstraintOracleSelfCheck.context(intended, database));
            assertTrue(failure.isPresent(), () -> engine(sqlite) + ": a dropped constraint must be reported");
            assertEquals(OracleFailure.FailureKind.CONSTRAINT_NOT_ENFORCED, failure.get().getKind());
        }
    }

    @Test
    void defaultValueReportsAMissingDefault() throws Exception {
        for (boolean sqlite : new boolean[] { true, false }) {
            Randomly.setSeed(SEED);
            RelationalSchema intended = ConstraintOracleSelfCheck.schema();
            RelationalSchema actual = ConstraintOracleSelfCheck.schema();
            actual.getTable("t_author").getColumn("score").setDefaultExpression(null);
            actual.getTable("t_book").getColumn("price").setDefaultExpression(null);
            OracleContext.DatabaseUnderTest database = sqlite ? ConstraintOracleSelfCheck.sqlite("t-dv-bad")
                    : ConstraintOracleSelfCheck.h2("t-dv-bad");
            ConstraintOracleSelfCheck.materialize(database, actual);
            Optional<OracleFailure> failure = new DefaultValueOracle()
                    .verify(ConstraintOracleSelfCheck.context(intended, database));
            assertTrue(failure.isPresent(), () -> engine(sqlite) + ": a missing DEFAULT must be reported");
            assertEquals(OracleFailure.FailureKind.SCHEMA_MISMATCH, failure.get().getKind());
        }
    }

    @Test
    void referentialActionVerifiesACascadeAndReportsNoAction() throws Exception {
        for (boolean sqlite : new boolean[] { true, false }) {
            Randomly.setSeed(SEED);
            RelationalSchema schema = ConstraintOracleSelfCheck.schema();
            OracleContext.DatabaseUnderTest database = sqlite ? ConstraintOracleSelfCheck.sqlite("t-ra-ok")
                    : ConstraintOracleSelfCheck.h2("t-ra-ok");
            ConstraintOracleSelfCheck.materialize(database, schema);
            OracleContext context = ConstraintOracleSelfCheck.context(schema, database);
            Optional<OracleFailure> failure = new ReferentialActionOracle().verify(context);
            assertTrue(failure.isEmpty(), () -> engine(sqlite) + ": " + describe(failure));
            long inserted = context.getExecutionEvents().stream()
                    .filter(event -> "probe-insert".equals(event.getPurpose()) && event.isSucceeded()
                            && event.getSql().startsWith("INSERT INTO t_book "))
                    .count();
            assertTrue(inserted > 0, "the oracle must have populated t_book");
            assertTrue(ConstraintOracleSelfCheck.count(database, "SELECT COUNT(*) FROM t_book") < inserted,
                    "ON DELETE CASCADE must really have deleted the referencing child rows");
        }
    }

    @Test
    void referentialActionReportsWhenTheTableWasCreatedWithoutTheCascade() throws Exception {
        for (boolean sqlite : new boolean[] { true, false }) {
            Randomly.setSeed(SEED);
            RelationalSchema intended = ConstraintOracleSelfCheck.schema();
            RelationalSchema actual = ConstraintOracleSelfCheck.schema();
            Table book = actual.getTable("t_book");
            book.getForeignKeys().clear();
            book.addForeignKey(new ForeignKey(List.of("author_id"), "t_author", List.of("id"))
                    .setName("fk_book_author").setOnDelete(ReferentialAction.NO_ACTION));
            OracleContext.DatabaseUnderTest database = sqlite ? ConstraintOracleSelfCheck.sqlite("t-ra-bad")
                    : ConstraintOracleSelfCheck.h2("t-ra-bad");
            ConstraintOracleSelfCheck.materialize(database, actual);
            Optional<OracleFailure> failure = new ReferentialActionOracle()
                    .verify(ConstraintOracleSelfCheck.context(intended, database));
            assertTrue(failure.isPresent(), () -> engine(sqlite) + ": a missing CASCADE must be reported");
            assertTrue(failure.get().getKind() == OracleFailure.FailureKind.CONSTRAINT_NOT_ENFORCED
                            || failure.get().getKind() == OracleFailure.FailureKind.STANDARD_DEVIATION,
                    () -> "unexpected kind " + failure.get().getKind());
        }
    }

    /**
     * A parent key column can itself be a foreign key column: a 1:1 extension table, or any table whose
     * natural key is shared with the table it extends. The update probe then asks the engine to move the
     * parent key to a value the grandparent does not contain, which every engine rejects correctly
     * (MySQL 1452 "Cannot add or update a child row", SQLite "FOREIGN KEY constraint failed"). It used to
     * be reported as "ON UPDATE CASCADE was declared but the update was rejected" - seed 3200001 of the
     * 2026-10-05 MySQL round, where {@code mv_e2_0[t_2_id] -> t_2[id]} was blamed for {@code t_2}'s own
     * foreign key to {@code t_0}.
     */
    @Test
    void referentialActionSkipsAParentKeyThatIsItselfAForeignKeyColumn() throws Exception {
        long controlProbes = 0;
        for (boolean sqlite : new boolean[] { true, false }) {
            for (long seed = 0; seed < 8; seed++) {
                Randomly.setSeed(seed);
                RelationalSchema guarded = ConstraintOracleSelfCheck.parentKeyIsForeignKeySchema(true);
                OracleContext.DatabaseUnderTest guardedDatabase = sqlite
                        ? ConstraintOracleSelfCheck.sqlite("t-ra-pkfk")
                        : ConstraintOracleSelfCheck.h2("t-ra-pkfk");
                ConstraintOracleSelfCheck.materialize(guardedDatabase, guarded);
                OracleContext guardedContext = ConstraintOracleSelfCheck.context(guarded, guardedDatabase);
                Optional<OracleFailure> failure = new ReferentialActionOracle().verify(guardedContext);
                assertTrue(failure.isEmpty(), () -> engine(sqlite) + ": " + describe(failure));
                assertEquals(0, ConstraintOracleSelfCheck.updateProbesOf(guardedContext, "t_2"),
                        "the update probe must be skipped when the parent key is itself a foreign key column");

                RelationalSchema control = ConstraintOracleSelfCheck.parentKeyIsForeignKeySchema(false);
                OracleContext.DatabaseUnderTest controlDatabase = sqlite
                        ? ConstraintOracleSelfCheck.sqlite("t-ra-pkfk-ctl")
                        : ConstraintOracleSelfCheck.h2("t-ra-pkfk-ctl");
                ConstraintOracleSelfCheck.materialize(controlDatabase, control);
                OracleContext controlContext = ConstraintOracleSelfCheck.context(control, controlDatabase);
                Optional<OracleFailure> controlFailure = new ReferentialActionOracle().verify(controlContext);
                assertTrue(controlFailure.isEmpty(), () -> engine(sqlite) + ": " + describe(controlFailure));
                controlProbes += ConstraintOracleSelfCheck.updateProbesOf(controlContext, "t_2");
            }
        }
        assertTrue(controlProbes > 0, "without the parent's own foreign key the same probe must run for at "
                + "least one seed, otherwise the guard is not what skipped it");
    }

    @Test
    void metamorphicAlterAcceptsBothEnginesAndRejectsAMissingInverse() throws Exception {
        for (boolean sqlite : new boolean[] { true, false }) {
            Randomly.setSeed(SEED);
            RelationalSchema schema = ConstraintOracleSelfCheck.schema();
            OracleContext.DatabaseUnderTest database = sqlite ? ConstraintOracleSelfCheck.sqlite("t-mm-ok")
                    : ConstraintOracleSelfCheck.h2("t-mm-ok");
            ConstraintOracleSelfCheck.materialize(database, schema);
            OracleContext context = ConstraintOracleSelfCheck.context(schema, database);
            Optional<OracleFailure> failure = new MetamorphicAlterOracle().verify(context);
            assertTrue(failure.isEmpty(), () -> engine(sqlite) + ": " + describe(failure));
            assertFalse(context.getExecutionEvents().isEmpty(), "the oracle must have executed statements");
        }
        Randomly.setSeed(SEED);
        RelationalSchema schema = ConstraintOracleSelfCheck.schema();
        OracleContext.DatabaseUnderTest database = ConstraintOracleSelfCheck.h2("t-mm-bad");
        ConstraintOracleSelfCheck.materialize(database, schema);
        Optional<OracleFailure> failure = new MetamorphicAlterOracle() {
            @Override
            protected List<AlterTableAction> inversesFor(List<AlterTableAction> applied, String tableName) {
                List<AlterTableAction> inverses = super.inversesFor(applied, tableName);
                if (inverses == null || inverses.isEmpty()) {
                    return inverses;
                }
                return new java.util.ArrayList<>(inverses.subList(0, inverses.size() - 1));
            }
        }.verify(ConstraintOracleSelfCheck.context(schema, database));
        assertTrue(failure.isPresent(), "an action applied without its inverse must be reported");
        assertEquals(OracleFailure.FailureKind.METAMORPHIC, failure.get().getKind());
    }

    @Test
    void pivotRowAcceptsBothEnginesAndRejectsACorruptedModel() throws Exception {
        for (boolean sqlite : new boolean[] { true, false }) {
            Randomly.setSeed(SEED);
            RelationalSchema schema = ConstraintOracleSelfCheck.schema();
            OracleContext.DatabaseUnderTest database = sqlite ? ConstraintOracleSelfCheck.sqlite("t-pv-ok")
                    : ConstraintOracleSelfCheck.h2("t-pv-ok");
            ConstraintOracleSelfCheck.materialize(database, schema);
            Optional<OracleFailure> failure = new PivotRowOracle()
                    .verify(ConstraintOracleSelfCheck.context(schema, database));
            assertTrue(failure.isEmpty(), () -> engine(sqlite) + ": " + describe(failure));
        }
        Randomly.setSeed(SEED);
        RelationalSchema schema = ConstraintOracleSelfCheck.schema();
        OracleContext.DatabaseUnderTest database = ConstraintOracleSelfCheck.h2("t-pv-bad");
        ConstraintOracleSelfCheck.materialize(database, schema);
        Optional<OracleFailure> failure = new PivotRowOracle() {
            @Override
            protected org.ddlsmith.data.DataModel expectationModel(org.ddlsmith.data.DataModel stored,
                    RelationalSchema schema) {
                org.ddlsmith.data.DataModel corrupted = new org.ddlsmith.data.DataModel(schema);
                for (Table table : schema.getTables()) {
                    for (java.util.Map<String, Object> row : stored.getRows(table.getName())) {
                        corrupted.addRow(table.getName(), row);
                    }
                }
                for (Table table : schema.getTables()) {
                    List<java.util.Map<String, Object>> rows = corrupted.getRows(table.getName());
                    if (!rows.isEmpty()) {
                        corrupted.addRow(table.getName(), rows.get(0));
                        break;
                    }
                }
                return corrupted;
            }
        }.verify(ConstraintOracleSelfCheck.context(schema, database));
        assertTrue(failure.isPresent(), "a corrupted expectation model must be reported");
        assertEquals(OracleFailure.FailureKind.DATA_MISMATCH, failure.get().getKind());
    }

    /** The full self-check, over several seeds, because every oracle makes random choices. */
    @Test
    void selfCheckPassesForSeveralSeeds() {
        for (long seed : new long[] { SEED, 1L, 42L }) {
            List<String> failures = ConstraintOracleSelfCheck.runAll(seed);
            assertTrue(failures.isEmpty(), () -> "seed " + seed + " failed:\n" + String.join("\n", failures));
        }
    }

    @Test
    void oraclesExposeTheirNames() {
        assertEquals("constraint-enforcement", new ConstraintEnforcementOracle().getName());
        assertEquals("referential-action", new ReferentialActionOracle().getName());
        assertEquals("default-value", new DefaultValueOracle().getName());
        assertEquals("metamorphic-alter", new MetamorphicAlterOracle().getName());
        assertEquals("pivot-row", new PivotRowOracle().getName());
        assertNotNull(new TestSqliteDialect().getName());
        assertNotNull(new TestH2Dialect().getName());
        assertNotNull(new TestSqliteIntrospector().getCapabilities());
        assertNotNull(new TestH2Introspector().getCapabilities());
    }

    private static String engine(boolean sqlite) {
        return sqlite ? "SQLite" : "H2";
    }

    private static String describe(Optional<OracleFailure> failure) {
        return failure.map(value -> value.getKind() + ": " + value.getTitle() + "\n" + value.getDetails())
                .orElse("<no failure>");
    }
}
