package org.ddlsmith.algorithm;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.GlobalState;
import org.ddlsmith.Main;
import org.ddlsmith.MainOptions;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphGenerator;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.ERDiagramPrinter;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;
import org.ddlsmith.mapping.ERToRelationalMapper;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;
import org.ddlsmith.oracle.OracleContext;

/**
 * The first half of every DDLSmith round: generate a random graph, interpret it as an ER diagram,
 * map that diagram to a relational schema, and adapt the schema to the dialect of the database
 * under test.
 *
 * <pre>
 *   graph schema  ->  random graph  ->  ER diagram  ->  relational schema  ->  adapted schema
 *   (labels,          (nodes =         (entities,       (tables, columns,      (only what the
 *    relationship      entities,        relationships,   keys, constraints)     engine can
 *    types)            edges =          cardinalities)                          express)
 *                      relationships)
 * </pre>
 */
public final class Pipeline {

    private Pipeline() {
    }

    /** The outcome of the generation pipeline, before anything is executed. */
    public static final class Generated {
        public GraphSchema graphSchema;
        public GraphModel graph;
        public ERDiagram erDiagram;
        public RelationalSchema mappedSchema;
        public RelationalSchema adaptedSchema;
        public MappingOptions mappingOptions;
        public MappingTrace trace;
        public final List<String> notes = new ArrayList<>();

        /** @return a description of the generated input, written into failure reports */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("-- ============================ generated input ============================\n");
            sb.append("-- graph schema : ").append(graphSchema).append('\n');
            sb.append("-- graph        : ").append(graph).append('\n');
            sb.append("-- ER diagram   : ").append(erDiagram.getStatistics()).append('\n');
            sb.append("-- mapping      : ").append(mappingOptions.describe()).append('\n');
            sb.append("-- schema       : ").append(adaptedSchema.getStatistics()).append('\n');
            sb.append("-- ER diagram detail:\n");
            sb.append(ERDiagramPrinter.describe(erDiagram)).append('\n');
            sb.append("-- mapping decisions:\n");
            for (String note : trace.getNotes()) {
                sb.append("--   ").append(note).append('\n');
            }
            if (!notes.isEmpty()) {
                sb.append("-- dialect adaptations:\n");
                for (String note : notes) {
                    sb.append("--   ").append(note).append('\n');
                }
            }
            sb.append("-- =======================================================================\n");
            return sb.toString();
        }
    }

    /** Runs the generation pipeline for the dialect of the database under test. */
    public static Generated generate(GlobalState<?, ?> state, Dialect dialect) {
        MainOptions options = state.getOptions();
        Generated generated = new Generated();

        generated.graphSchema = new RandomGraphSchemaGenerator().generate();

        GraphGenerationOptions graphOptions = new GraphGenerationOptions()
                .setMinNodes(options.getMinNodeNum())
                .setMaxNodes(options.getMaxNodeNum());
        GraphGenerator generator = resolveGenerator(options.getGraphGenerator());
        generated.graph = generator.generate(generated.graphSchema, graphOptions);
        Main.nrGeneratedGraphs.incrementAndGet();

        generated.erDiagram = new DefaultGraphToERMapper().map(generated.graph, generated.graphSchema);

        generated.mappingOptions = MappingOptions.random();
        generated.trace = new MappingTrace();
        ERToRelationalMapper mapper = new StandardERToRelationalMapper();
        String schemaName = dialect.schemaNameForGeneratedDatabase(options.getSchemaName(),
                state.getDatabaseName());
        if (!java.util.Objects.equals(schemaName, options.getSchemaName())) {
            generated.notes.add("generated objects placed in schema '" + schemaName + "' instead of '"
                    + options.getSchemaName() + "': " + dialect.getName()
                    + " keeps every generated database in a namespace of its own, because they all connect to "
                    + "the same server database and a shared namespace would make the second one collide with "
                    + "the first");
        }
        generated.mappedSchema = mapper.map(generated.erDiagram, generated.mappingOptions, schemaName,
                generated.trace);

        generated.adaptedSchema = SchemaAdapter.adapt(generated.mappedSchema, dialect, generated.notes);

        state.setCurrentGraph(generated.graph);
        state.setCurrentERDiagram(generated.erDiagram);
        state.setCurrentSchema(generated.adaptedSchema);
        state.setMappingOptions(generated.mappingOptions);
        state.setMappingTrace(generated.trace);
        state.getAdaptationNotes().clear();
        state.getAdaptationNotes().addAll(generated.notes);
        state.setDialect(dialect);
        return generated;
    }

    /**
     * @return a description of the input currently held by the state, for failure reports. Works
     *         even after the algorithm has moved on, because everything it needs is in the state.
     */
    public static String describe(GlobalState<?, ?> state) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ============================ generated input ============================\n");
        if (state.getCurrentGraph() != null) {
            sb.append("-- graph     : ").append(state.getCurrentGraph()).append('\n');
        }
        if (state.getCurrentERDiagram() != null) {
            sb.append("-- ER diagram: ").append(state.getCurrentERDiagram().getStatistics()).append('\n');
            sb.append(ERDiagramPrinter.describe(state.getCurrentERDiagram())).append('\n');
        }
        if (state.getMappingOptions() != null) {
            sb.append("-- mapping   : ").append(state.getMappingOptions().describe()).append('\n');
        }
        if (state.getMappingTrace() != null) {
            for (String note : state.getMappingTrace().getNotes()) {
                sb.append("--   ").append(note).append('\n');
            }
        }
        if (state.getCurrentSchema() != null) {
            sb.append("-- schema    : ").append(state.getCurrentSchema().getStatistics()).append('\n');
        }
        if (!state.getAdaptationNotes().isEmpty()) {
            sb.append("-- dialect adaptations:\n");
            for (String note : state.getAdaptationNotes()) {
                sb.append("--   ").append(note).append('\n');
            }
        }
        sb.append("-- =======================================================================\n");
        return sb.toString();
    }

    private static GraphGenerator resolveGenerator(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("mix")) {
            return GraphGenerators.random();
        }
        return GraphGenerators.forName(name);
    }

    /** Builds an oracle context holding everything the oracles need. */
    public static OracleContext context(GlobalState<?, ?> state, Generated generated, long seed) {
        OracleContext context = new OracleContext()
                .setGraph(generated.graph)
                .setErDiagram(generated.erDiagram)
                .setIntendedSchema(generated.adaptedSchema)
                .setTrace(generated.trace)
                .setMappingOptions(generated.mappingOptions)
                .setOptions(state.getOptions())
                .setStateToReproduce(state.getState())
                .setSeed(seed);
        return context;
    }
}
