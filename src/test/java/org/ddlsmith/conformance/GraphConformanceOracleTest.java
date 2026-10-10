package org.ddlsmith.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.ddlsmith.IgnoreMeException;
import org.ddlsmith.MainOptions;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.conformance.ConformancePlan.PlannedRow;
import org.ddlsmith.dbms.h2.H2Dialect;
import org.ddlsmith.dbms.h2.H2Introspector;
import org.ddlsmith.dbms.sqlite.SqliteDialect;
import org.ddlsmith.dbms.sqlite.SqliteIntrospector;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the oracle in both directions on real engines: it stays silent about a database that stores
 * the materialized instance, and it reports a difference when there is one.
 *
 * <p>The negative direction is what makes the positive direction mean something. Each of the three
 * defect shapes the oracle can report is produced here by corrupting exactly one thing — the expected
 * value, the row that is inserted, or the ordering of the population — and the test asserts that the
 * oracle notices, with the right {@link FailureKind}, instead of passing quietly.</p>
 */
class GraphConformanceOracleTest {

    private static final AtomicInteger H2_DATABASES = new AtomicInteger();

    @Test
    @DisplayName("a database that stores the materialized instance is not reported")
    void staysSilentOnAConformingDatabase() throws Exception {
        for (long seed : List.of(1L, 2L, 3L, 4L, 5L)) {
            try (ConformanceTestSupport.Session session = sqlite(seed)) {
                Optional<OracleFailure> failure = new GraphConformanceOracle().verify(session.context);
                assertTrue(failure.isEmpty(), "seed " + seed + ": " + failure.map(OracleFailure::getTitle).orElse(""));
            }
        }
        try (ConformanceTestSupport.Session session = h2(1L)) {
            assertTrue(new GraphConformanceOracle().verify(session.context).isEmpty());
        }
    }

    @Test
    @DisplayName("an answer that disagrees with the materialized instance is reported as a data mismatch")
    void reportsAWrongAnswer() throws Exception {
        try (ConformanceTestSupport.Session session = sqlite(2L)) {
            GraphConformanceOracle oracle = new GraphConformanceOracle() {
                @Override
                protected List<SemanticQuery> queries(OracleContext context, DatabaseUnderTest database,
                        RelationalSchema schema, ConformancePlan plan, ConformancePopulator.Result population,
                        List<String> notes) {
                    List<SemanticQuery> queries = super.queries(context, database, schema, plan, population, notes);
                    String table = population.accepted.keySet().iterator().next();
                    queries.add(0, new SemanticQuery(SemanticQuery.Kind.MULTISET, "corrupted check for the test",
                            "a query whose expected value the test deliberately corrupted",
                            "SELECT COUNT(*) FROM " + database.getDialect().quote(table), List.of("(0)"),
                            List.of(DataType.integer()), null, "test"));
                    return queries;
                }
            };
            Optional<OracleFailure> failure = oracle.verify(session.context);
            assertTrue(failure.isPresent(), "the corrupted expectation was not reported");
            assertEquals(FailureKind.DATA_MISMATCH, failure.get().getKind());
            assertEquals(GraphConformanceOracle.NAME, failure.get().getOracleName());
            assertTrue(failure.get().getDetails().contains("corrupted check for the test"),
                    failure.get().getDetails());
        }
    }

    @Test
    @DisplayName("a row the engine refuses to store is reported as a constraint that is too strict")
    void reportsARejectedRow() throws Exception {
        try (ConformanceTestSupport.Session session = sqlite(3L)) {
            GraphConformanceOracle oracle = new GraphConformanceOracle() {
                @Override
                protected ConformancePlan plan(OracleContext context, DatabaseUnderTest database,
                        RelationalSchema schema) {
                    ConformancePlan plan = super.plan(context, database, schema);
                    for (PlannedRow row : plan.getRows()) {
                        for (Column column : row.getTable().getColumns()) {
                            // a key column is a bad choice for this test: SQLite assigns a value to a NULL
                            // INTEGER PRIMARY KEY, so the row would be stored (with a different value) and
                            // the oracle would report a data mismatch instead of the rejection under test
                            boolean isKey = row.getTable().getPrimaryKey() != null
                                    && row.getTable().getPrimaryKey().getColumns().contains(column.getName());
                            if (!column.isNullable() && !column.isGenerated() && !column.isIdentity() && !isKey
                                    && row.getValues().containsKey(column.getName())) {
                                row.getValues().put(column.getName(), null);
                                return plan;
                            }
                        }
                    }
                    return plan;
                }
            };
            Optional<OracleFailure> failure = oracle.verify(session.context);
            assertTrue(failure.isPresent(), "the rejected row was not reported");
            assertEquals(FailureKind.CONSTRAINT_TOO_STRICT, failure.get().getKind());
            assertTrue(failure.get().getDetails().contains("materialized graph instance requires a row"),
                    failure.get().getDetails());
        }
    }

    @Test
    @DisplayName("a schema that --evolve-steps moved away from the mapping is declined, not reported")
    void declinesAnEvolutionRun() throws Exception {
        try (ConformanceTestSupport.Session session = sqlite(4L)) {
            MainOptions options = new MainOptions();
            options.setEvolveSteps(5);
            session.context.setOptions(options);
            assertThrows(IgnoreMeException.class, () -> new GraphConformanceOracle().verify(session.context));
        }
    }

    @Test
    @DisplayName("a context without a generated graph is declined, not reported")
    void declinesAContextWithoutAGraph() throws Exception {
        try (ConformanceTestSupport.Session session = sqlite(5L)) {
            session.context.setGraph(null);
            assertTrue(new GraphConformanceOracle().verify(session.context).isEmpty());
            session.context.setGraph(session.input.graph).setTrace(null);
            assertTrue(new GraphConformanceOracle().verify(session.context).isEmpty());
        }
    }

    @Test
    @DisplayName("the oracle is selectable by name and contributed by the service loader")
    void isRegisteredAsAnExtension() {
        List<String> names = org.ddlsmith.oracle.Oracles.names();
        assertTrue(names.contains(GraphConformanceOracle.NAME),
                "the extension oracle is not registered: " + names);
        assertTrue(org.ddlsmith.oracle.Oracles.isDataOracle(GraphConformanceOracle.NAME));
        assertFalse(new GraphConformanceOracle().getDescription().isBlank());
    }

    /* ------------------------------------------------------------------ engines */

    private ConformanceTestSupport.Session sqlite(long seed) throws Exception {
        SqliteDialect dialect = new SqliteDialect();
        ConformanceTestSupport.Input input = ConformanceTestSupport.generate(seed, dialect);
        return ConformanceTestSupport.open("jdbc:sqlite::memory:", "sqlite", dialect, new SqliteIntrospector(),
                input, seed);
    }

    private ConformanceTestSupport.Session h2(long seed) throws Exception {
        H2Dialect dialect = new H2Dialect();
        ConformanceTestSupport.Input input = ConformanceTestSupport.generate(seed, dialect);
        return ConformanceTestSupport.open("jdbc:h2:mem:conformance_" + H2_DATABASES.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1", "h2", dialect, new H2Introspector(), input, seed);
    }
}
