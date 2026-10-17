package org.ddlsmith.algorithm;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.GlobalState;
import org.ddlsmith.Main;
import org.ddlsmith.MainOptions;
import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.graph.GraphFilling;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphGenerator;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.ERDiagramPrinter;
import org.ddlsmith.graph.gen.GraphPropertyFiller;
import org.ddlsmith.graph.gen.RandomGraphFiller;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;
import org.ddlsmith.mapping.ERToRelationalMapper;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;
import org.ddlsmith.oracle.OracleContext;

/**
 * The first half of every DDLsmith round: generate a random base graph, fill it with attributes for
 * this database, interpret the result as an ER diagram, map that diagram to a relational schema, and
 * adapt the schema to the dialect of the database under test.
 *
 * <pre>
 *   base graph schema  ->  base graph  ->  filling  ->  ER diagram  ->  relational  ->  adapted
 *   (labels, rel.         (nodes =        (attributes  (entities,       schema          schema
 *    types, card.)         entities,       per label    relationships,  (tables,
 *                          edges =         and rel.     cardinalities)   keys,
 *                          relationships)  type)                        constraints)
 * </pre>
 *
 * <p>The base graph is only nodes and edges. What makes the ER diagram complex is the filling: it is
 * drawn per database, so the databases of a {@code --databases-per-graph} group share a graph and
 * differ in the attributes they give it - how many, of which type, which are NOT NULL, which form
 * the keys, and which labels are subtypes of which.</p>
 */
public final class Pipeline {

    private Pipeline() {
    }

    /** The outcome of the generation pipeline, before anything is executed. */
    public static final class Generated {
        public GraphSchema graphSchema;
        public GraphModel graph;
        public GraphFilling filling;
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
            sb.append("-- base graph schema : ").append(graphSchema).append('\n');
            sb.append("-- base graph        : ").append(graph).append('\n');
            sb.append("-- filling           : ").append(filling == null ? "-" : filling.describe()).append('\n');
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

        // The worker thread is seeded with the seed of the base graph this database belongs to. With
        // --databases-per-graph K > 1 every database of a group starts from that same seed, so all K of
        // them build the same base graph schema and - unless --reroll-instance is on - the same base
        // graph instance. The stream is then re-seeded per slot, so the filling, the ER interpretation
        // and the mapping decisions differ across the group: one base graph becomes K databases
        // instead of one. With K == 1 the default of 8 is not in play and the stream is the single one
        // it has always been.
        long graphSeed = Randomly.getSeed();
        int databasesPerGraph = Math.max(1, options.getDatabasesPerGraph());
        int slot = state.getState() == null ? 0 : state.getState().getInputSlot();
        boolean sharedGraph = databasesPerGraph > 1;

        generated.graphSchema = new RandomGraphSchemaGenerator().generate();

        GraphGenerationOptions graphOptions = new GraphGenerationOptions()
                .setMinNodes(options.getMinNodeNum())
                .setMaxNodes(options.getMaxNodeNum());
        GraphGenerator generator = resolveGenerator(options.getGraphGenerator());
        if (sharedGraph && options.isRerollInstance()) {
            // the schema stays, the instance is drawn again for this slot: this is what reaches the ER
            // features the instance locks in (weak entities, identifying relationships)
            Randomly.setSeed(slotSeed(graphSeed, slot));
        }
        generated.graph = generator.generate(generated.graphSchema, graphOptions);
        if (slot == 0) {
            // one generated base graph per group; the other databases of the group only fill it and
            // re-interpret it
            Main.nrGeneratedGraphs.incrementAndGet();
        }
        if (sharedGraph && !options.isRerollInstance()) {
            Randomly.setSeed(slotSeed(graphSeed, slot));
        }

        // the filling is drawn per database: same base graph, different attributes, therefore a
        // different ER diagram, a different set of keys and a different relational schema
        generated.filling = new RandomGraphFiller().plan(generated.graphSchema);
        GraphPropertyFiller.fill(generated.graph, generated.filling);

        generated.erDiagram = new DefaultGraphToERMapper().map(generated.graph, generated.graphSchema,
                generated.filling);

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

        // Coverage accounting for the summary: how much of the input space was new. Sharing one base
        // graph between several databases makes the graph shape repeat by construction, so the number
        // of generated databases alone would hide how much variety the run actually reached - and the
        // filling is the axis on which the databases of one group differ most.
        Main.distinctFillings.add(generated.filling.describe());
        Main.distinctErProfiles.add(generated.erDiagram.getStatistics().toString());
        Main.distinctSchemaProfiles.add(generated.adaptedSchema.getStatistics().toString());

        state.setCurrentGraph(generated.graph);
        state.setCurrentFilling(generated.filling);
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
     * @return the seed of one database of a graph group. Every slot draws a different stream, and the
     *         multiplication keeps the slots of one graph apart from the graphs themselves; the group
     *         size is bounded by 999999 exactly so that this stays injective over the seed range the
     *         tool uses (see {@code MainOptions#getDatabasesPerGraph}).
     */
    public static long slotSeed(long graphSeed, int slot) {
        return graphSeed * 1_000_000L + slot;
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
        if (state.getCurrentFilling() != null) {
            sb.append("-- filling   : ").append(state.getCurrentFilling().describe()).append('\n');
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
                .setSeed(seed)
                .setInputSlot(state.getState() == null ? 0 : state.getState().getInputSlot());
        return context;
    }
}
