package org.ddlsmith.oracle;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.MainOptions;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.introspection.SchemaIntrospector;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingTrace;

/**
 * Everything an oracle needs: the generated input, the intended schema, and one or more databases
 * on which that schema was materialized.
 *
 * <p>Single-DBMS runs have exactly one {@link DatabaseUnderTest}; differential runs have several,
 * which is how the same ER diagram is compared across engines or versions.</p>
 */
public class OracleContext {

    private GraphModel graph;
    private ERDiagram erDiagram;
    private RelationalSchema intendedSchema;
    private MappingTrace trace;
    /** The record of everything executed, written into failure logs for replay. */
    private org.ddlsmith.StateToReproduce stateToReproduce;
    private MappingOptions mappingOptions;
    private MainOptions options;
    private final List<DatabaseUnderTest> databases = new ArrayList<>();
    /** The DDL that was executed to create the intended schema, per database name. */
    private final List<String> generatedDdl = new ArrayList<>();
    /** Every statement that was executed, with its outcome. */
    private final List<ExecutionEvent> executionEvents = new ArrayList<>();
    private long seed;

    public List<ExecutionEvent> getExecutionEvents() {
        return executionEvents;
    }

    public OracleContext addExecutionEvent(ExecutionEvent event) {
        executionEvents.add(event);
        return this;
    }

    /** @return the execution events of one database, in order */
    public List<ExecutionEvent> getExecutionEvents(String databaseName) {
        List<ExecutionEvent> result = new ArrayList<>();
        for (ExecutionEvent event : executionEvents) {
            if (event.getDatabaseName().equals(databaseName)) {
                result.add(event);
            }
        }
        return result;
    }

    /**
     * The outcome of one executed statement. Oracles use this to distinguish a statement that the
     * engine should have accepted (an unexpected error is a finding) from one that was expected to
     * fail (a constraint probe, where failing is the correct behaviour).
     */
    public static class ExecutionEvent {
        private final String databaseName;
        private final String sql;
        private final boolean succeeded;
        private final String errorMessage;
        private final String sqlState;
        private final int errorCode;
        private final long millis;
        private final boolean expectedToFail;
        /** A label describing the purpose of the statement, e.g. "create-schema" or "probe-not-null". */
        private final String purpose;

        public ExecutionEvent(String databaseName, String sql, boolean succeeded, String errorMessage, String sqlState,
                int errorCode, long millis, boolean expectedToFail, String purpose) {
            this.databaseName = databaseName;
            this.sql = sql;
            this.succeeded = succeeded;
            this.errorMessage = errorMessage;
            this.sqlState = sqlState;
            this.errorCode = errorCode;
            this.millis = millis;
            this.expectedToFail = expectedToFail;
            this.purpose = purpose;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public String getSql() {
            return sql;
        }

        public boolean isSucceeded() {
            return succeeded;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getSqlState() {
            return sqlState;
        }

        public int getErrorCode() {
            return errorCode;
        }

        public long getMillis() {
            return millis;
        }

        public boolean isExpectedToFail() {
            return expectedToFail;
        }

        public String getPurpose() {
            return purpose;
        }

        /** @return true when a statement the engine should have accepted failed */
        public boolean isUnexpectedFailure() {
            return !succeeded && !expectedToFail;
        }

        /** @return true when a statement that was expected to be rejected was accepted */
        public boolean isUnexpectedSuccess() {
            return succeeded && expectedToFail;
        }

        @Override
        public String toString() {
            return (succeeded ? "OK   " : "FAIL ") + purpose + ": " + sql
                    + (errorMessage == null ? "" : "  [" + errorMessage + "]");
        }
    }

    public GraphModel getGraph() {
        return graph;
    }

    public OracleContext setGraph(GraphModel graph) {
        this.graph = graph;
        return this;
    }

    public ERDiagram getErDiagram() {
        return erDiagram;
    }

    public OracleContext setErDiagram(ERDiagram erDiagram) {
        this.erDiagram = erDiagram;
        return this;
    }

    public RelationalSchema getIntendedSchema() {
        return intendedSchema;
    }

    public OracleContext setIntendedSchema(RelationalSchema intendedSchema) {
        this.intendedSchema = intendedSchema;
        return this;
    }

    public MappingTrace getTrace() {
        return trace;
    }

    /**
     * @return the record of everything executed, which the failure log writes out so that a finding
     *         can be replayed with {@code --reproduce} without DDLSmith in the loop
     */
    public org.ddlsmith.StateToReproduce getStateToReproduce() {
        return stateToReproduce;
    }

    public OracleContext setStateToReproduce(org.ddlsmith.StateToReproduce stateToReproduce) {
        this.stateToReproduce = stateToReproduce;
        return this;
    }

    public OracleContext setTrace(MappingTrace trace) {
        this.trace = trace;
        return this;
    }

    public MappingOptions getMappingOptions() {
        return mappingOptions;
    }

    public OracleContext setMappingOptions(MappingOptions mappingOptions) {
        this.mappingOptions = mappingOptions;
        return this;
    }

    public MainOptions getOptions() {
        return options;
    }

    public OracleContext setOptions(MainOptions options) {
        this.options = options;
        return this;
    }

    public List<DatabaseUnderTest> getDatabases() {
        return databases;
    }

    public OracleContext addDatabase(DatabaseUnderTest database) {
        databases.add(database);
        return this;
    }

    /** @return the database a single-DBMS oracle should act on */
    public DatabaseUnderTest getPrimary() {
        if (databases.isEmpty()) {
            throw new IllegalStateException("no database under test");
        }
        return databases.get(0);
    }

    public List<String> getGeneratedDdl() {
        return generatedDdl;
    }

    public long getSeed() {
        return seed;
    }

    public OracleContext setSeed(long seed) {
        this.seed = seed;
        return this;
    }

    /** A database on which the generated schema was materialized. */
    public static class DatabaseUnderTest {
        private final String name;
        private final DDLSmithDBConnection connection;
        private final Dialect dialect;
        private final SchemaIntrospector introspector;
        private final List<String> executedStatements = new ArrayList<>();
        private String databaseVersion;
        /**
         * The schema this particular database was asked to create. It differs from
         * {@link OracleContext#getIntendedSchema()} in differential runs, where every engine gets a
         * schema adapted to its own dialect. When null, the context-wide intended schema applies.
         */
        private RelationalSchema intendedSchema;
        /**
         * The live log of this database, written when {@code --log-each-select} is on. Only the
         * database under test has one; the databases opened for a differential comparison are
         * reported through the primary's log, because their statements are part of its record.
         */
        private org.ddlsmith.Main.StateLogger stateLogger;

        public DatabaseUnderTest(String name, DDLSmithDBConnection connection, Dialect dialect,
                SchemaIntrospector introspector) {
            this.name = name;
            this.connection = connection;
            this.dialect = dialect;
            this.introspector = introspector;
        }

        public org.ddlsmith.Main.StateLogger getStateLogger() {
            return stateLogger;
        }

        public DatabaseUnderTest setStateLogger(org.ddlsmith.Main.StateLogger stateLogger) {
            this.stateLogger = stateLogger;
            return this;
        }

        public RelationalSchema getIntendedSchema() {
            return intendedSchema;
        }

        public DatabaseUnderTest setIntendedSchema(RelationalSchema intendedSchema) {
            this.intendedSchema = intendedSchema;
            return this;
        }

        public String getName() {
            return name;
        }

        public DDLSmithDBConnection getConnection() {
            return connection;
        }

        public Dialect getDialect() {
            return dialect;
        }

        public SchemaIntrospector getIntrospector() {
            return introspector;
        }

        public List<String> getExecutedStatements() {
            return executedStatements;
        }

        public String getDatabaseVersion() {
            return databaseVersion;
        }

        public DatabaseUnderTest setDatabaseVersion(String databaseVersion) {
            this.databaseVersion = databaseVersion;
            return this;
        }

        /** A stable identity for reports: {@code sqlite@3.49.1}. */
        public String getFullName() {
            return databaseVersion == null ? name : name + "@" + databaseVersion;
        }
    }
}
