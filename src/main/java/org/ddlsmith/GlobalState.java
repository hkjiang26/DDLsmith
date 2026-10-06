package org.ddlsmith;

import java.sql.SQLException;

import org.ddlsmith.common.query.QueryResult;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.graph.GraphModel;

/**
 * Per-thread state of a DDLSmith run: the connection to the database under test, the generated
 * graph, the ER diagram derived from it, the intended relational schema, and the execution log.
 *
 * <p>Compared to GDsmith this drops the schema type parameter: DDLSmith always describes the
 * intended schema with the single {@link RelationalSchema} intermediate representation.</p>
 */
public abstract class GlobalState<O extends DBMSSpecificOptions<?>, C extends DDLSmithDBConnection> {

    private Randomly randomly;
    private MainOptions options;
    private O dbmsSpecificOptions;
    private C connection;
    private StateToReproduce state;
    private Main.StateLogger logger;
    private String databaseName;

    /** The graph that was randomly generated and is interpreted as an ER diagram. */
    private GraphModel currentGraph;

    /** The ER diagram extracted from {@link #currentGraph}. */
    private ERDiagram currentERDiagram;

    /** The relational schema (tables/columns/constraints) that the DDL is supposed to create. */
    private RelationalSchema currentSchema;

    /** The DDL dialect of the database under test. */
    private Dialect dialect;

    /** The mapping decisions that produced {@link #currentSchema}. */
    private org.ddlsmith.mapping.MappingOptions mappingOptions;

    /** How every ER construct was mapped, for failure reports. */
    private org.ddlsmith.mapping.MappingTrace mappingTrace;

    /** What had to be adapted because the dialect cannot express it. */
    private final java.util.List<String> adaptationNotes = new java.util.ArrayList<>();

    /** Statistics for the current run. */
    private int executedStatements;

    public org.ddlsmith.mapping.MappingOptions getMappingOptions() {
        return mappingOptions;
    }

    public void setMappingOptions(org.ddlsmith.mapping.MappingOptions mappingOptions) {
        this.mappingOptions = mappingOptions;
    }

    public org.ddlsmith.mapping.MappingTrace getMappingTrace() {
        return mappingTrace;
    }

    public void setMappingTrace(org.ddlsmith.mapping.MappingTrace mappingTrace) {
        this.mappingTrace = mappingTrace;
    }

    public java.util.List<String> getAdaptationNotes() {
        return adaptationNotes;
    }

    public Randomly getRandomly() {
        return randomly;
    }

    public void setRandomly(Randomly randomly) {
        this.randomly = randomly;
    }

    public MainOptions getOptions() {
        return options;
    }

    public void setMainOptions(MainOptions options) {
        this.options = options;
    }

    public O getDbmsSpecificOptions() {
        return dbmsSpecificOptions;
    }

    public void setDbmsSpecificOptions(O dbmsSpecificOptions) {
        this.dbmsSpecificOptions = dbmsSpecificOptions;
    }

    public C getConnection() {
        return connection;
    }

    public void setConnection(C connection) {
        this.connection = connection;
    }

    public StateToReproduce getState() {
        return state;
    }

    public void setState(StateToReproduce state) {
        this.state = state;
    }

    public Main.StateLogger getLogger() {
        return logger;
    }

    public void setStateLogger(Main.StateLogger logger) {
        this.logger = logger;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public GraphModel getCurrentGraph() {
        return currentGraph;
    }

    public void setCurrentGraph(GraphModel currentGraph) {
        this.currentGraph = currentGraph;
    }

    public ERDiagram getCurrentERDiagram() {
        return currentERDiagram;
    }

    public void setCurrentERDiagram(ERDiagram currentERDiagram) {
        this.currentERDiagram = currentERDiagram;
    }

    public RelationalSchema getCurrentSchema() {
        return currentSchema;
    }

    public void setCurrentSchema(RelationalSchema currentSchema) {
        this.currentSchema = currentSchema;
    }

    public Dialect getDialect() {
        return dialect;
    }

    public void setDialect(Dialect dialect) {
        this.dialect = dialect;
    }

    public int getExecutedStatements() {
        return executedStatements;
    }

    /* ------------------------------------------------------------------ statement execution */

    /** Executes a statement, records it in the reproduction log and updates the counters. */
    public void executeStatement(String sql) throws SQLException {
        state.logStatement(sql);
        if (logger != null && options != null && options.logEachSelect()) {
            logger.writeCurrent(sql);
        }
        connection.executeStatement(sql);
        executedStatements++;
        Main.nrSuccessfulActions.incrementAndGet();
    }

    /** Executes a generated DDL statement against the database under test. */
    public void executeStatement(DDLStatement statement) throws SQLException {
        executeStatement(render(statement));
    }

    /** Executes a query and materializes the result. */
    public QueryResult executeQuery(String sql) throws SQLException {
        state.logStatement(sql);
        if (logger != null && options != null && options.logEachSelect()) {
            logger.writeCurrent(sql);
        }
        executedStatements++;
        Main.nrQueries.incrementAndGet();
        return connection.executeAndGet(sql);
    }

    public String render(DDLStatement statement) {
        return statement.toSql(dialect);
    }
}
