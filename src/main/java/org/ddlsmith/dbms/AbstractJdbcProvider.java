package org.ddlsmith.dbms;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.DDLsmithDBConnection;
import org.ddlsmith.DatabaseProvider;
import org.ddlsmith.Main;
import org.ddlsmith.StateToReproduce;
import org.ddlsmith.algorithm.Algorithms;
import org.ddlsmith.algorithm.Pipeline;
import org.ddlsmith.algorithm.TestingAlgorithm;
import org.ddlsmith.common.log.DDLsmithLoggableFactory;
import org.ddlsmith.common.log.LoggableFactory;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.oracle.OracleFailure;

/**
 * Base class of the JDBC providers: it opens the connection, runs the session setup statements the
 * dialect requires (SQLite's foreign key pragma is the important one), runs the selected algorithm,
 * and writes one log file per failure with everything needed to reproduce it.
 */
public abstract class AbstractJdbcProvider
        implements DatabaseProvider<SimpleGlobalState<ConnectionOptions>, ConnectionOptions, DDLsmithDBConnection> {

    private static final LoggableFactory LOGGABLE_FACTORY = new DDLsmithLoggableFactory();

    /** The name of the engine, which is also the dialect and introspector name. */
    protected abstract String engineName();

    @Override
    public String getDBMSName() {
        return engineName();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public Class<SimpleGlobalState<ConnectionOptions>> getGlobalStateClass() {
        return (Class) SimpleGlobalState.class;
    }

    @Override
    public Class<ConnectionOptions> getOptionClass() {
        return ConnectionOptions.class;
    }

    @Override
    public DDLsmithDBConnection createDatabase(SimpleGlobalState<ConnectionOptions> state) throws Exception {
        DatabaseConfig target = resolveTarget(state);
        DDLsmithDBConnection connection;
        try {
            connection = Targets.open(target, state.getDatabaseName(), state.getOptions().isKeepDatabases());
        } catch (java.sql.SQLException e) {
            throw new org.ddlsmith.DatabaseUnavailableException(
                    "cannot connect to " + engineName() + " at " + target.jdbcUrl(state.getDatabaseName()) + ": "
                            + e.getMessage(),
                    e);
        }
        Dialect dialect = Dialects.forName(engineName());
        // --ddl-features / --disable-ddl-features adjust what the dialect claims to support; assuming a
        // capability the engine does not really have is how the crash oracle's question gets asked
        try {
            dialect = org.ddlsmith.ddl.FeatureOverriddenDialect.of(dialect,
                    state.getOptions().getExtraDdlFeatures(), state.getOptions().getDisabledDdlFeatures());
        } catch (IllegalArgumentException e) {
            System.err.println("warning: " + e.getMessage());
        }
        state.setDialect(dialect);
        // The namespace is decided here and in Pipeline by the same dialect method and from the same
        // database name, so the connection is set up for exactly the schema the statements will
        // address. For an embedded engine both are no-ops: its URL already gives each generated
        // database a database of its own.
        String namespace = dialect.schemaNameForGeneratedDatabase(state.getOptions().getSchemaName(),
                state.getDatabaseName());
        if (!state.getOptions().isKeepDatabases()) {
            // the client/server counterpart of deleting an embedded database's file: a second run with
            // the same --database-prefix must not find the first run's objects. Only the namespace
            // derived above is named, so nothing DDLsmith did not create can be removed.
            for (String sql : dialect.dropNamespaceStatements(namespace)) {
                runSetupQuietly(connection, sql, "drop-namespace");
            }
        }
        for (String sql : dialect.createNamespaceStatements(namespace)) {
            runSetupQuietly(connection, sql, "create-namespace");
        }
        for (String sql : dialect.getSessionSetupStatements(namespace)) {
            runSetupQuietly(connection, sql, "session-setup");
        }
        return connection;
    }

    /**
     * Runs a statement that prepares the connection. A failure is a warning rather than an error: an
     * engine that rejects one of them still gives the oracles a schema to compare, and the failure
     * surfaces there with the statement attached.
     */
    private void runSetupQuietly(DDLsmithDBConnection connection, String sql, String purpose) {
        try {
            connection.executeStatement(sql);
        } catch (Exception e) {
            System.err.println("warning: " + purpose + " statement failed on " + engineName() + ": " + sql + " ("
                    + e.getMessage() + ")");
        }
    }

    /** Resolves the connection settings, letting an explicit {@code --url} win. */
    protected DatabaseConfig resolveTarget(SimpleGlobalState<ConnectionOptions> state) {
        DatabaseConfig target = Targets.primary(engineName(), state.getOptions());
        String url = state.getOptions().getUrl();
        if (url == null) {
            ConnectionOptions command = state.getDbmsSpecificOptions();
            url = command == null ? null : command.getUrl();
        }
        if (url != null && !url.isBlank()) {
            target.setUrl(url);
        }
        return target;
    }

    @Override
    public void generateAndTestDatabase(SimpleGlobalState<ConnectionOptions> state) throws Exception {
        TestingAlgorithm algorithm = Algorithms.forName(state.getOptions().getAlgorithm(), state.getOptions());
        List<OracleFailure> failures;
        try {
            failures = algorithm.test(state);
        } catch (org.ddlsmith.IgnoreMeException e) {
            return;
        }
        reportFailures(state, failures);
    }

    private void reportFailures(SimpleGlobalState<ConnectionOptions> state, List<OracleFailure> failures)
            throws Exception {
        if (failures.isEmpty()) {
            return;
        }
        // Every report has to identify the input that produced it. The seed names the graph; with
        // --databases-per-graph the database is one interpretation of that graph, so the slot belongs
        // next to the seed or the report cannot be turned back into the same database.
        for (OracleFailure failure : failures) {
            failure.setInputIdentity(state.getState().getInputSlot(), state.getOptions().getDatabasesPerGraph());
        }
        String context = Pipeline.describe(state);
        org.ddlsmith.bug.BugRepository bugs = org.ddlsmith.bug.BugRepository.forOptions(state.getOptions());
        String version = state.getState().getDatabaseVersion();
        // the same defect is filed once, no matter how many generated databases expose it
        java.util.List<java.nio.file.Path> reports = bugs.recordAll(engineName(), version, failures,
                state.getState(), state.getOptions(), context);
        for (int i = 0; i < failures.size(); i++) {
            OracleFailure failure = failures.get(i);
            java.io.File file = state.getLogger().logFailure(failure, state.getState(), context);
            System.out.println("FAILURE [" + failure.getKind() + "] " + failure.getTitle() + " -> "
                    + file.getAbsolutePath());
            if (i < reports.size() && reports.get(i) != null) {
                System.out.println("        filed as " + reports.get(i));
            }
            Main.nrUnsuccessfulActions.incrementAndGet();
        }
    }

    @Override
    public StateToReproduce getStateToReproduce(String databaseName) {
        return new StateToReproduce(databaseName);
    }

    @Override
    public LoggableFactory getLoggableFactory() {
        return LOGGABLE_FACTORY;
    }

    /** @return the list of engines this build can drive */
    public static List<String> supportedEngines() {
        return new ArrayList<>(Dialects.names());
    }
}
