package org.ddlsmith;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.algorithm.Pipeline;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.gen.DDLFeatureAnalyzer;
import org.ddlsmith.ddl.gen.SchemaCreationScript;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.dbms.Dialects;

/**
 * The {@code --print-ddl-only} mode: it runs the whole generation pipeline and prints the resulting
 * DDL script without connecting to any database.
 *
 * <p>This makes DDLSmith usable as a DDL generator on its own — for building corpora, for reviewing
 * what the tool would send to an engine, and for inspecting the graph → ER → relational mapping
 * chain end to end.</p>
 */
public final class DDLOnly {

    private DDLOnly() {
    }

    public static int run(MainOptions options, String dialectName) {
        Dialect dialect = Dialects.forName(dialectName);
        // the printed DDL must be the DDL a real run would produce, so the command line feature
        // overrides apply here too
        try {
            dialect = org.ddlsmith.ddl.FeatureOverriddenDialect.of(dialect, options.getExtraDdlFeatures(),
                    options.getDisabledDdlFeatures());
        } catch (IllegalArgumentException e) {
            System.err.println("DDLSmith: " + e.getMessage());
            return 1;
        }
        // With --databases-per-graph K every slot of the group is printed: this is the mode that turns
        // one generated graph into a corpus of scripts, so printing only one of them would hide exactly
        // what the option is for. The graph seed is the one a --num-tries 1 run would use, so a script
        // can be regenerated from the seed printed next to it.
        int databasesPerGraph = Math.max(1, options.getDatabasesPerGraph());
        long graphSeed = options.getRandomSeed() == -1 ? System.currentTimeMillis() : options.getRandomSeed();
        // --input-slot picks one database of the group instead of the whole group, exactly as it does
        // for a real run: it is how a single printed script is regenerated from its seed.
        int firstSlot = options.getInputSlot() != 0 ? options.getInputSlot() : 0;
        int lastSlot = options.getInputSlot() != 0 ? options.getInputSlot() + 1 : databasesPerGraph;
        for (int slot = firstSlot; slot < lastSlot; slot++) {
            int exitCode = printOne(options, dialect, graphSeed, slot, databasesPerGraph);
            if (exitCode != 0) {
                return exitCode;
            }
        }
        return 0;
    }

    /** Prints the DDL of one database: the graph of {@code graphSeed}, read as slot {@code slot}. */
    private static int printOne(MainOptions options, Dialect dialect, long graphSeed, int slot,
            int databasesPerGraph) {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted;
        Pipeline.Generated generated;
        try {
            Randomly.setSeed(graphSeed);
            generated = generateFor(dialect, options, slot);
            adapted = generated.adaptedSchema;
        } catch (RuntimeException e) {
            System.err.println("DDLSmith could not generate a schema: " + e);
            e.printStackTrace();
            return options.getErrorExitCode();
        }

        System.out.println("-- DDLSmith generated DDL");
        System.out.println("-- dialect  : " + dialect.getName());
        System.out.println("-- generator: " + options.getGraphGenerator() + " (available: " + GraphGenerators.names()
                + ")");
        System.out.println("-- seed     : " + (options.getRandomSeed() == -1 ? "time-based" : options.getRandomSeed()));
        if (databasesPerGraph > 1) {
            System.out.println("-- graph seed: " + graphSeed);
            System.out.println("-- slot     : " + slot + " of " + databasesPerGraph);
        }
        System.out.println("-- graph    : " + generated.graph);
        System.out.println("-- ER       : " + generated.erDiagram.getStatistics());
        System.out.println("-- mapping  : " + generated.mappingOptions.describe());
        System.out.println("-- schema   : " + adapted.getStatistics());
        System.out.println("-- features : " + DDLFeatureAnalyzer.featuresOf(adapted));
        if (!generated.notes.isEmpty()) {
            System.out.println("-- adaptations:");
            for (String note : generated.notes) {
                System.out.println("--   " + note);
            }
        }
        System.out.println();

        for (String setup : dialect.getSessionSetupStatements()) {
            System.out.println(setup + ";");
        }
        for (DDLStatement statement : SchemaCreationScript.create(adapted, dialect, options)) {
            for (String sql : dialect.renderAll(statement)) {
                System.out.println(sql + ";");
            }
        }

        if (options.getEvolveSteps() > 0 || options.getNumDdlStatements() > 0) {
            System.out.println();
            System.out.println("-- randomized DDL");
            org.ddlsmith.ddl.gen.RandomDDLGenerator generator = new org.ddlsmith.ddl.gen.RandomDDLGenerator(adapted,
                    dialect, options);
            int count = options.getEvolveSteps() > 0 ? options.getEvolveSteps() : options.getNumDdlStatements();
            for (DDLStatement statement : generator.generate(count)) {
                for (String sql : dialect.renderAll(statement)) {
                    System.out.println(sql + ";");
                }
            }
        }
        return 0;
    }

    /** Runs the generation pipeline without a database, using a state-less dialect. */
    private static Pipeline.Generated generateFor(Dialect dialect, MainOptions options, int slot) {
        HeadlessState state = new HeadlessState(options);
        state.getState().inputSlot = slot;
        state.getState().seedValue = Randomly.getSeed();
        return Pipeline.generate(state, dialect);
    }

    /** A global state with no connection, used by the DDL-only mode. */
    private static final class HeadlessState extends GlobalState<org.ddlsmith.dbms.ConnectionOptions, DDLSmithDBConnection> {
        HeadlessState(MainOptions options) {
            setMainOptions(options);
            setState(new StateToReproduce("ddl-only"));
            setDatabaseName("ddl-only");
        }
    }
}
