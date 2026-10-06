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
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted;
        Pipeline.Generated generated;
        try {
            generated = generateFor(dialect, options);
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
    private static Pipeline.Generated generateFor(Dialect dialect, MainOptions options) {
        return Pipeline.generate(new HeadlessState(options), dialect);
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
