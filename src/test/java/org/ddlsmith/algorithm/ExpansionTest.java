package org.ddlsmith.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.GlobalState;
import org.ddlsmith.MainOptions;
import org.ddlsmith.Randomly;
import org.ddlsmith.StateToReproduce;
import org.ddlsmith.bug.BugRepository;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.dbms.ConnectionOptions;
import org.ddlsmith.dbms.Dialects;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.graph.GraphLabel;
import org.ddlsmith.graph.GraphProperty;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.ERDiagramPrinter;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.impl.SchemaPrinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two-level sampling of {@code --databases-per-graph}: databases of one group share the graph and
 * differ in how it is read as an ER diagram and mapped to tables.
 *
 * <p>Why these assertions and not "the databases differ": regenerating a graph per database trivially
 * makes every database different, which is exactly what the option replaces. What has to hold is the
 * opposite pair - the graph is <em>identical</em> inside a group, and the ER diagram and the relational
 * schema are <em>not</em>.</p>
 */
class ExpansionTest {

    private static final int GROUP = 8;
    private static final long SEED = 4242L;

    /** A global state with no connection, mirroring the headless state of the DDL-only mode. */
    private static final class HeadlessState extends GlobalState<ConnectionOptions, DDLSmithDBConnection> {
        HeadlessState(MainOptions options, int slot) {
            setMainOptions(options);
            StateToReproduce state = new StateToReproduce("expansion-test");
            state.inputSlot = slot;
            state.seedValue = Randomly.getSeed();
            setState(state);
            setDatabaseName("expansion-test");
        }
    }

    /** Generates one database the way the executor does: seed the worker, then run the pipeline. */
    private static Pipeline.Generated generate(long graphSeed, int slot, MainOptions options) {
        Randomly.setSeed(graphSeed);
        Dialect dialect = Dialects.forName("h2");
        return Pipeline.generate(new HeadlessState(options, slot), dialect);
    }

    private static MainOptions options(int databasesPerGraph, boolean rerollInstance) {
        MainOptions options = new MainOptions();
        options.setDatabasesPerGraph(databasesPerGraph);
        options.setRerollInstance(rerollInstance);
        return options;
    }

    /** The complete structure of the graph and of the schema it was generated from. */
    private static String graphSignature(Pipeline.Generated generated) {
        return ERDiagramPrinter.describe(generated.graph, generated.graphSchema);
    }

    /** The graph schema alone, independent of the instance that was generated from it. */
    private static String schemaSignature(GraphSchema schema) {
        StringBuilder sb = new StringBuilder();
        for (GraphLabel label : schema.getLabels()) {
            sb.append('L').append(label.getIndex()).append(':');
            for (GraphProperty property : label.getProperties()) {
                sb.append(property.getKey()).append('/').append(property.getType()).append('/')
                        .append(property.getPresenceProbability()).append(',');
            }
            sb.append(';');
        }
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            sb.append('R').append(type.getIndex()).append(':').append(type.getFromLabelIndex()).append("->")
                    .append(type.getToLabelIndex()).append('/').append(type.getFromCardinality()).append('/')
                    .append(type.getToCardinality()).append('/').append(type.getFromParticipation()).append('/')
                    .append(type.getToParticipation()).append(':');
            for (GraphProperty property : type.getProperties()) {
                sb.append(property.getKey()).append(',');
            }
            sb.append(';');
        }
        return sb.toString();
    }

    @Test
    void everyDatabaseOfAGroupGetsTheSameGraph() {
        MainOptions options = options(GROUP, false);
        String shared = null;
        for (int slot = 0; slot < GROUP; slot++) {
            String signature = graphSignature(generate(SEED, slot, options));
            if (shared == null) {
                shared = signature;
            } else {
                assertEquals(shared, signature, "slot " + slot + " generated a different graph");
            }
        }
    }

    @Test
    void everyDatabaseOfAGroupGetsItsOwnInterpretation() {
        MainOptions options = options(GROUP, false);
        Set<String> diagrams = new LinkedHashSet<>();
        Set<String> schemas = new LinkedHashSet<>();
        for (int slot = 0; slot < GROUP; slot++) {
            Pipeline.Generated generated = generate(SEED, slot, options);
            diagrams.add(ERDiagramPrinter.describe(generated.erDiagram));
            schemas.add(SchemaPrinter.describe(generated.adaptedSchema));
        }
        // The measured rate is above 95% distinct per group; the bound is kept below it so that the
        // test states a property of the option rather than the luck of one seed.
        assertTrue(diagrams.size() >= GROUP - 2,
                "only " + diagrams.size() + " of " + GROUP + " databases read the graph differently");
        assertTrue(schemas.size() >= GROUP - 2,
                "only " + schemas.size() + " of " + GROUP + " databases mapped to a different schema");
    }

    @Test
    void rerollingTheInstanceKeepsTheSchemaButChangesTheInstance() {
        MainOptions options = options(GROUP, true);
        String schema = null;
        Set<String> instances = new LinkedHashSet<>();
        for (int slot = 0; slot < GROUP; slot++) {
            Pipeline.Generated generated = generate(SEED, slot, options);
            String signature = schemaSignature(generated.graphSchema);
            if (schema == null) {
                schema = signature;
            } else {
                assertEquals(schema, signature, "slot " + slot + " generated a different graph schema");
            }
            instances.add(graphSignature(generated));
        }
        assertTrue(instances.size() > 1, "the instance was not re-rolled at all");
    }

    @Test
    void generationIsReproducibleInBothModes() {
        for (boolean reroll : new boolean[] {false, true}) {
            for (int databasesPerGraph : new int[] {1, GROUP}) {
                MainOptions options = options(databasesPerGraph, reroll);
                for (int slot = 0; slot < databasesPerGraph; slot++) {
                    Pipeline.Generated once = generate(SEED, slot, options);
                    Pipeline.Generated twice = generate(SEED, slot, options);
                    assertEquals(graphSignature(once), graphSignature(twice),
                            "the graph is not reproducible for K=" + databasesPerGraph + " slot " + slot);
                    assertEquals(ERDiagramPrinter.describe(once.erDiagram),
                            ERDiagramPrinter.describe(twice.erDiagram),
                            "the ER diagram is not reproducible for K=" + databasesPerGraph + " slot " + slot);
                    assertEquals(SchemaPrinter.describe(once.adaptedSchema),
                            SchemaPrinter.describe(twice.adaptedSchema),
                            "the schema is not reproducible for K=" + databasesPerGraph + " slot " + slot);
                }
            }
        }
    }

    @Test
    void aReplayedSlotIsTheSameDatabase() {
        MainOptions options = options(GROUP, false);
        Pipeline.Generated fromTheGroup = generate(SEED, 5, options);
        // the reproduce command names the slot instead of running the whole group: the database it
        // generates has to be the one that was found, not another interpretation of the same graph
        Pipeline.Generated replayed = generate(SEED, 5, options);
        assertEquals(SchemaPrinter.describe(fromTheGroup.adaptedSchema),
                SchemaPrinter.describe(replayed.adaptedSchema));
        assertNotEquals(SchemaPrinter.describe(generate(SEED, 4, options).adaptedSchema),
                SchemaPrinter.describe(replayed.adaptedSchema),
                "two slots of one graph produced the same schema, so the test cannot tell them apart");
    }

    @Test
    void slotSeedsAreDistinct() {
        Set<Long> seen = new LinkedHashSet<>();
        for (long graphSeed = 0; graphSeed < 32; graphSeed++) {
            for (int slot = 0; slot < GROUP; slot++) {
                long slotSeed = Pipeline.slotSeed(graphSeed, slot);
                // the streams must not collide across slots either: a graph's slot 3 and the next
                // graph's slot 2 would otherwise generate the same database twice
                assertTrue(seen.add(slotSeed), "slot seed collision at graph " + graphSeed + " slot " + slot);
            }
        }
    }

    @Test
    void failuresCarryTheSlotOfTheDatabaseThatFoundThem(@TempDir Path directory) throws IOException {
        MainOptions options = options(4, false);
        options.setBugDirectory(directory.toString());
        StateToReproduce state = new StateToReproduce("database3");
        state.seedValue = 77L;
        state.inputSlot = 3;
        OracleFailure failure = new OracleFailure(OracleFailure.FailureKind.SCHEMA_MISMATCH, "test-oracle",
                "a table is missing", "details", "SELECT 1", 77L, "database3", null).setInputIdentity(3, 4);

        assertTrue(failure.toLogString().contains("slot 3 of 4"),
                "the failure log does not say which database of the group failed");
        assertTrue(failure.toShortString().contains("slot=3/4"));

        List<Path> reports = BugRepository.forOptions(options).recordAll("expansiontest", "1.0",
                List.of(failure), state, options, "context");
        assertEquals(1, reports.size());
        String report = Files.readString(reports.get(0), StandardCharsets.UTF_8);
        assertTrue(report.contains("slot=3/4"), "the defect report does not name the slot");
        assertTrue(report.contains("--databases-per-graph 4") && report.contains("--input-slot 3"),
                "the recorded command would regenerate a different database:\n" + report);
        // a reloaded report must survive the new fields; the directory is read back by the next run
        assertFalse(BugRepository.forOptions(options).getEntries().isEmpty());
    }

    @Test
    void aSingleDatabaseRunKeepsOneGraphPerDatabase() {
        MainOptions options = options(1, false);
        List<String> graphs = new ArrayList<>();
        List<String> schemas = new ArrayList<>();
        for (int seed = 0; seed < 6; seed++) {
            Pipeline.Generated generated = generate(seed, 0, options);
            graphs.add(graphSignature(generated));
            schemas.add(SchemaPrinter.describe(generated.adaptedSchema));
        }
        // with a group size of 1 the historic behaviour stands: every database is a new graph
        assertEquals(6, new LinkedHashSet<>(graphs).size());
        assertEquals(6, new LinkedHashSet<>(schemas).size());
    }
}
