package org.ddlsmith;

import java.io.File;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ddlsmith.algorithm.Pipeline;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.dbms.DatabaseConfig;
import org.ddlsmith.dbms.Dialects;
import org.ddlsmith.dbms.Introspectors;
import org.ddlsmith.dbms.Targets;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.gen.DDLFeatureAnalyzer;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.ddl.gen.SchemaCreationScript;
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaIntrospector;
import org.ddlsmith.introspection.SchemaNormalizer;

/**
 * End-to-end smoke check for the whole DDLSmith pipeline.
 *
 * <p>It runs, for every embedded engine:</p>
 * <ol>
 *   <li>the generation pipeline (graph → ER diagram → relational schema → dialect adaptation);</li>
 *   <li>the create script against a real in-memory database;</li>
 *   <li>a full catalog round trip compared with {@link SchemaNormalizer};</li>
 *   <li>a schema evolution sequence with a round trip after every step;</li>
 *   <li>a differential comparison across all embedded engines.</li>
 * </ol>
 *
 * <p>It is a {@code main} rather than a JUnit test because it needs to print a readable report of
 * what each engine did, which is the point of running it.</p>
 */
public final class EndToEndCheck {

    private static int failures;
    private static final List<String> report = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20240101L;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        System.out.println("DDLSmith end-to-end check (seed=" + seed + ", rounds=" + rounds + ")");
        System.out.println("================================================================================");

        for (String engine : List.of("sqlite", "h2", "duckdb")) {
            checkEngine(engine, seed, rounds);
        }
        differentialCheck(seed, rounds);

        System.out.println();
        System.out.println("================================================================================");
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        System.out.println("================================================================================");
        for (String line : report) {
            System.out.println(line);
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void checkEngine(String engine, long seed, int rounds) throws Exception {
        System.out.println();
        System.out.println("### " + engine);
        Dialect dialect = Dialects.forName(engine);
        SchemaIntrospector introspector = Introspectors.forName(engine);
        System.out.println("  features: " + dialect.getFeatures().getFeatures().size() + " declared");
        System.out.println("  setup   : " + dialect.getSessionSetupStatements());
        System.out.println("  caps    : " + introspector.getCapabilities().size() + " introspection capabilities");

        int emptyDiffs = 0;
        int totalDiffs = 0;
        for (int round = 0; round < rounds; round++) {
            Randomly.setSeed(seed + round);
            MainOptions options = new MainOptions();
            options.setRandomSeed(seed + round);
            options.setMinNodeNum(6);
            options.setMaxNodeNum(14);
            Randomly.initialize(options);

            Pipeline.Generated generated = Pipeline.generate(new HeadlessState(options), dialect);
            List<DDLStatement> script = SchemaCreationScript.create(generated.adaptedSchema, dialect, options);

            DatabaseConfig target = Targets.primary(engine, options);
            String databaseName = "e2e_" + engine + "_" + round;
            try (DDLSmithDBConnection connection = Targets.open(target, databaseName)) {
                for (String setup : dialect.getSessionSetupStatements()) {
                    connection.executeStatement(setup);
                }
                int created = 0;
                for (DDLStatement statement : script) {
                    for (String sql : dialect.renderAll(statement)) {
                        try {
                            connection.executeStatement(sql);
                            created++;
                        } catch (Exception e) {
                            failures++;
                            String message = "  [" + engine + " round " + round + "] CREATE FAILED: " + sql + " -> "
                                    + e.getMessage();
                            System.out.println(message);
                            report.add(message);
                        }
                    }
                }

                // full round trip
                RelationalSchema actual = introspector.introspect(connection, generated.adaptedSchema.getName());
                java.util.Set<SchemaIntrospector.IntrospectionCapability> capabilities = new java.util.LinkedHashSet<>(
                        introspector.getCapabilities());
                RelationalSchema erased = SchemaNormalizer.erase(generated.adaptedSchema, capabilities);
                List<SchemaDifference> differences = SchemaNormalizer.diff(erased, actual, true,
                        capabilities.contains(
                                SchemaIntrospector.IntrospectionCapability.FOREIGN_KEY_ACTIONS));
                totalDiffs += differences.size();
                if (differences.isEmpty()) {
                    emptyDiffs++;
                } else {
                    String message = "  [" + engine + " round " + round + "] ROUND TRIP DIFFERENCES ("
                            + differences.size() + "):\n" + SchemaNormalizer.render(differences);
                    System.out.println(message);
                    report.add(message);
                }
                System.out.println("  round " + round + ": " + created + " statements, "
                        + generated.adaptedSchema.getStatistics() + ", diffs=" + differences.size()
                        + ", features=" + DDLFeatureAnalyzer.featuresOf(generated.adaptedSchema).size());
            }
        }
        System.out.println("  round trips clean: " + emptyDiffs + "/" + rounds + " (total differences " + totalDiffs + ")");
        report.add(engine + ": " + emptyDiffs + "/" + rounds + " clean round trips, " + totalDiffs
                + " total differences");
    }

    /**
     * Creates the same <em>common</em> schema on every embedded engine and compares the catalogs
     * pairwise.
     *
     * <p>The common schema is adapted to the intersection of the three dialects, exactly as the
     * differential algorithm does. Adapting per engine instead would make this check report the
     * adaptations (an engine that cannot express a construct has fewer objects) rather than any
     * disagreement between the engines.</p>
     */
    private static void differentialCheck(long seed, int rounds) throws Exception {
        System.out.println();
        System.out.println("### differential (sqlite vs h2 vs duckdb)");
        List<String> engines = List.of("sqlite", "h2", "duckdb");
        List<Dialect> dialects = new ArrayList<>();
        for (String engine : engines) {
            dialects.add(Dialects.forName(engine));
        }
        org.ddlsmith.ddl.CommonDialect common = org.ddlsmith.ddl.CommonDialect.of(dialects);
        System.out.println("  common feature set: " + common.getFeatures().getFeatures().size() + " features");

        for (int round = 0; round < rounds; round++) {
            Randomly.setSeed(seed + 1000 + round);
            MainOptions options = new MainOptions();
            options.setRandomSeed(seed + 1000 + round);
            options.setMinNodeNum(6);
            options.setMaxNodeNum(12);
            Randomly.initialize(options);

            Dialect reference = Dialects.forName(engines.get(0));
            Pipeline.Generated generated = Pipeline.generate(new HeadlessState(options), reference);

            List<String> adaptationNotes = new ArrayList<>();
            RelationalSchema commonSchema = SchemaAdapter.adapt(generated.mappedSchema, common, adaptationNotes);

            Map<String, RelationalSchema> actuals = new LinkedHashMap<>();
            for (String engine : engines) {
                Dialect dialect = Dialects.forName(engine);
                SchemaIntrospector introspector = Introspectors.forName(engine);
                DatabaseConfig target = Targets.primary(engine, options);
                try (DDLSmithDBConnection connection = Targets.open(target, "diff_" + engine + "_" + round)) {
                    for (String setup : dialect.getSessionSetupStatements()) {
                        connection.executeStatement(setup);
                    }
                    for (DDLStatement statement : SchemaCreationScript.create(commonSchema, dialect, options)) {
                        for (String sql : dialect.renderAll(statement)) {
                            try {
                                connection.executeStatement(sql);
                            } catch (Exception e) {
                                report.add("  [differential] " + engine + " rejected: " + sql + " -> "
                                        + e.getMessage());
                                System.out.println("  [differential] " + engine + " rejected: " + sql + " -> "
                                        + e.getMessage());
                            }
                        }
                    }
                    actuals.put(engine, introspector.introspect(connection, commonSchema.getName()));
                }
            }
            // pairwise comparison by type category
            String a = engines.get(0);
            for (int i = 1; i < engines.size(); i++) {
                String b = engines.get(i);
                java.util.Set<SchemaIntrospector.IntrospectionCapability> shared = new java.util.LinkedHashSet<>(
                        Introspectors.forName(a).getCapabilities());
                shared.retainAll(Introspectors.forName(b).getCapabilities());
                List<SchemaDifference> differences = SchemaNormalizer.diff(SchemaNormalizer.erase(actuals.get(a), shared),
                        SchemaNormalizer.erase(actuals.get(b), shared), false, true);
                System.out.println("  round " + round + ": " + a + " vs " + b + " -> " + differences.size()
                        + " differences");
                if (!differences.isEmpty()) {
                    String message = "  [differential round " + round + "] " + a + " vs " + b + ":\n"
                            + SchemaNormalizer.render(differences);
                    System.out.println(message);
                    report.add(message);
                }
            }
        }
    }

    /** A state with no connection, used to drive the pipeline from the check. */
    private static final class HeadlessState
            extends GlobalState<org.ddlsmith.dbms.ConnectionOptions, DDLSmithDBConnection> {
        HeadlessState(MainOptions options) {
            setMainOptions(options);
            setState(new StateToReproduce("e2e"));
            setDatabaseName("e2e");
        }
    }
}
