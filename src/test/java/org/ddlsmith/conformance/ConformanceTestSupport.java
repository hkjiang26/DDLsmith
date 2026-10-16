package org.ddlsmith.conformance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.DDLsmithDBConnection;
import org.ddlsmith.MainOptions;
import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.ddl.gen.SchemaCreationScript;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;
import org.ddlsmith.introspection.SchemaIntrospector;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.StatementRunner;

/**
 * TEST SCAFFOLDING, not part of the deliverable.
 *
 * <p>Runs the generation half of the pipeline (graph schema, graph instance, ER diagram, mapping,
 * dialect adaptation) without a database, and opens an in-memory database holding the adapted
 * schema, so that the planner and the oracle can be exercised on the same input a real run would
 * produce. The random stream is consumed in the order {@code Pipeline.generate} consumes it, so a
 * seed here means the same input a seed there means.</p>
 */
final class ConformanceTestSupport {

    /** The namespace generated objects are asked for; embedded dialects ignore it. */
    static final String SCHEMA_NAME = "ddlsmith";

    private ConformanceTestSupport() {
    }

    /** One generated input: the graph, its ER reading and the adapted schema. */
    static final class Input {
        GraphModel graph;
        ERDiagram erDiagram;
        RelationalSchema mapped;
        RelationalSchema adapted;
        MappingOptions mappingOptions;
        MappingTrace trace;
        final List<String> adaptationNotes = new ArrayList<>();

        List<String> tableNames() {
            return adapted.getTableNames();
        }
    }

    /** One database under test, its connection and the oracle context around it. */
    static final class Session implements AutoCloseable {
        DDLsmithDBConnection connection;
        DatabaseUnderTest database;
        OracleContext context;
        Input input;

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException e) {
                // closing an in-memory database cannot fail in a way that matters to a test
            }
        }
    }

    static Input generate(long graphSeed, Dialect dialect) {
        return generate(graphSeed, 0, dialect);
    }

    static Input generate(long graphSeed, int slot, Dialect dialect) {
        Input input = new Input();
        Randomly.setSeed(graphSeed);
        GraphSchema graphSchema = new RandomGraphSchemaGenerator().generate();
        GraphModel graph = GraphGenerators.random().generate(graphSchema, new GraphGenerationOptions()
                .setMinNodes(new MainOptions().getMinNodeNum())
                .setMaxNodes(new MainOptions().getMaxNodeNum()));
        Randomly.setSeed(graphSeed * 1_000_000L + slot);
        input.graph = graph;
        input.erDiagram = new DefaultGraphToERMapper().map(graph, graphSchema);
        input.mappingOptions = MappingOptions.random();
        input.trace = new MappingTrace();
        input.mapped = new StandardERToRelationalMapper().map(input.erDiagram, input.mappingOptions, SCHEMA_NAME,
                input.trace);
        input.adapted = SchemaAdapter.adapt(input.mapped, dialect, input.adaptationNotes);
        return input;
    }

    /** Opens an in-memory database, creates the adapted schema in it and builds the oracle context. */
    static Session open(String jdbcUrl, String name, Dialect dialect, SchemaIntrospector introspector,
            Input input, long seed) throws SQLException {
        Session session = new Session();
        session.input = input;
        session.connection = new DDLsmithDBConnection(DriverManager.getConnection(jdbcUrl), name);
        session.database = new DatabaseUnderTest(name, session.connection, dialect, introspector);
        session.context = new OracleContext()
                .setGraph(input.graph)
                .setErDiagram(input.erDiagram)
                .setIntendedSchema(input.adapted)
                .setTrace(input.trace)
                .setMappingOptions(input.mappingOptions)
                .setOptions(new MainOptions())
                .setSeed(seed)
                .addDatabase(session.database);
        session.database.setDatabaseVersion(session.connection.getDatabaseVersion());
        for (String setup : dialect.getSessionSetupStatements()) {
            session.connection.executeStatement(setup);
        }
        for (DDLStatement statement : SchemaCreationScript.create(input.adapted, dialect, new MainOptions())) {
            String sql = statement.toSql(dialect);
            if (StatementRunner.isCommentOnly(sql)) {
                continue;
            }
            if (!StatementRunner.execute(session.context, session.database, sql, false, "create").isSucceeded()) {
                throw new SQLException("the test fixture could not create the schema: " + sql);
            }
        }
        return session;
    }
}
