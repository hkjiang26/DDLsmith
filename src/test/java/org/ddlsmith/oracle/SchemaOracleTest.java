package org.ddlsmith.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.oracle.impl.CrashOracle;
import org.ddlsmith.oracle.impl.DifferentialSchemaOracle;
import org.ddlsmith.oracle.impl.FeaturePurpose;
import org.ddlsmith.oracle.impl.IdempotenceOracle;
import org.ddlsmith.oracle.impl.OracleSelfCheck;
import org.ddlsmith.oracle.impl.SchemaRoundTripOracle;
import org.junit.jupiter.api.Test;

/**
 * The assertions of {@link OracleSelfCheck}, expressed as JUnit tests.
 *
 * <p>Every test delegates to the corresponding public {@code check...} method of the self check, so
 * the standalone {@code main} and this test can never drift apart. The databases are real in-memory
 * SQLite and H2 instances built by {@code org.ddlsmith.oracle.impl.OracleTestFixture}; the engine
 * modules are written in parallel and are deliberately not used here.</p>
 */
class SchemaOracleTest {

    /* ------------------------------------------------------------------ oracle identity */

    @Test
    void oraclesExposeTheirNamesAndDescriptions() {
        assertEquals("crash", new CrashOracle().getName());
        assertEquals("schema-round-trip", new SchemaRoundTripOracle().getName());
        assertEquals("differential-schema", new DifferentialSchemaOracle().getName());
        assertEquals("idempotence", new IdempotenceOracle().getName());
        for (TestOracle oracle : List.of(new CrashOracle(), new SchemaRoundTripOracle(),
                new DifferentialSchemaOracle(), new IdempotenceOracle())) {
            assertNotNull(oracle.getDescription(), "every oracle needs a description");
            assertTrue(oracle.getDescription().indexOf('\n') < 0, "the description must be a single line");
        }
    }

    @Test
    void purposeConventionIsParsed() {
        assertEquals("create-table", FeaturePurpose.base("create-table[COMPOSITE_PRIMARY_KEY]"));
        assertEquals(List.of(DDLFeature.COMPOSITE_PRIMARY_KEY),
                FeaturePurpose.features("create-table[COMPOSITE_PRIMARY_KEY]"));
        assertEquals("create-schema", FeaturePurpose.base("create-schema[COMPOSITE_FOREIGN_KEY,DEFERRABLE_FK]"));
        assertEquals(List.of(DDLFeature.COMPOSITE_FOREIGN_KEY, DDLFeature.DEFERRABLE_FK),
                FeaturePurpose.features("create-schema[COMPOSITE_FOREIGN_KEY,DEFERRABLE_FK]"));
        assertEquals("probe-not-null", FeaturePurpose.base("probe-not-null"));
        assertTrue(FeaturePurpose.features("probe-not-null").isEmpty());
        assertEquals("create-table[NAMED_CHECK]",
                FeaturePurpose.encode("create-table", DDLFeature.NAMED_CHECK));
        assertTrue(FeaturePurpose.isProbe("probe-check-constraint"));
    }

    /* ------------------------------------------------------------------ crash oracle */

    @Test
    void crashOracleIsSilentForASuccessfullyCreatedSchema() throws Exception {
        OracleSelfCheck.checkCrashOracleIsSilentForASuccessfullyCreatedSchema();
    }

    @Test
    void crashOracleReportsAnUnexpectedFailure() throws Exception {
        OracleSelfCheck.checkCrashOracleReportsAnUnexpectedFailure();
    }

    @Test
    void crashOracleReportsADeclaredFeatureFailure() throws Exception {
        OracleSelfCheck.checkCrashOracleReportsADeclaredFeatureFailure();
    }

    @Test
    void crashOracleIgnoresAnUndeclaredFeatureFailure() throws Exception {
        OracleSelfCheck.checkCrashOracleIgnoresAnUndeclaredFeatureFailure();
    }

    @Test
    void crashOracleIgnoresAnExpectedProbeFailure() throws Exception {
        OracleSelfCheck.checkCrashOracleIgnoresAnExpectedProbeFailure();
    }

    @Test
    void crashOracleReportsAnAcceptedProbeStatement() throws Exception {
        OracleSelfCheck.checkCrashOracleReportsAnAcceptedProbeStatement();
    }

    @Test
    void crashOracleIgnoresAGeneratedRowFailure() throws Exception {
        OracleSelfCheck.checkCrashOracleIgnoresAGeneratedRowFailure();
    }

    @Test
    void crashOraclePrefersSchemaFailuresOverProbeFailures() throws Exception {
        OracleSelfCheck.checkCrashOraclePrefersSchemaFailuresOverProbeFailures();
    }

    /* ------------------------------------------------------------------ schema round trip */

    @Test
    void roundTripIsSilentForAMatchingSchema() throws Exception {
        OracleSelfCheck.checkRoundTripIsSilentForAMatchingSchema();
    }

    @Test
    void roundTripReportsAnExtraTable() throws Exception {
        OracleSelfCheck.checkRoundTripReportsAnExtraTable();
    }

    @Test
    void roundTripReportsAWrongColumnType() throws Exception {
        OracleSelfCheck.checkRoundTripReportsAWrongColumnType();
    }

    @Test
    void roundTripReportsATableTheEngineAddedOnItsOwn() throws Exception {
        OracleSelfCheck.checkRoundTripReportsATableTheEngineAddedOnItsOwn();
    }

    @Test
    void roundTripUsesThePerDatabaseIntendedSchema() throws Exception {
        OracleSelfCheck.checkRoundTripUsesThePerDatabaseIntendedSchema();
    }

    /* ------------------------------------------------------------------ differential schema */

    @Test
    void differentialIsSilentForEquivalentSchemas() throws Exception {
        OracleSelfCheck.checkDifferentialIsSilentForEquivalentSchemas();
    }

    @Test
    void differentialReportsAMissingTable() throws Exception {
        OracleSelfCheck.checkDifferentialReportsAMissingTable();
    }

    @Test
    void differentialReportsRowsTheEngineInsertedByItself() throws Exception {
        OracleSelfCheck.checkDifferentialReportsRowsTheEngineInsertedByItself();
    }

    @Test
    void differentialIsNotApplicableToASingleDatabase() throws Exception {
        OracleSelfCheck.checkDifferentialIsNotApplicableToASingleDatabase();
    }

    /* ------------------------------------------------------------------ idempotence */

    @Test
    void idempotenceIsSilentOnSqliteAndH2() throws Exception {
        OracleSelfCheck.checkIdempotenceIsSilentOnSqliteAndH2();
    }

    @Test
    void idempotenceReportsAnOverstatedFeature() throws Exception {
        OracleSelfCheck.checkIdempotenceReportsAnOverstatedFeature();
    }
}
