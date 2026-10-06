package org.ddlsmith.algorithm;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.GlobalState;
import org.ddlsmith.MainOptions;
import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.dbms.DatabaseConfig;
import org.ddlsmith.dbms.Dialects;
import org.ddlsmith.dbms.Introspectors;
import org.ddlsmith.dbms.Targets;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.gen.DDLFeatureAnalyzer;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.ddl.gen.SchemaCreationScript;
import org.ddlsmith.introspection.SchemaIntrospector;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.Oracles;
import org.ddlsmith.oracle.StatementRunner;
import org.ddlsmith.oracle.TestOracle;

/**
 * Shared machinery of the DDLSmith algorithms: it wires the generation pipeline to a database,
 * executes the generated script while recording every outcome, opens the additional databases
 * needed for differential testing, and runs the oracles.
 */
public abstract class AbstractTestingAlgorithm implements TestingAlgorithm {

    protected final MainOptions options;

    protected AbstractTestingAlgorithm(MainOptions options) {
        this.options = options;
    }

    /** @return the oracle context for the primary database, with the generated input attached */
    protected OracleContext newContext(GlobalState<?, ?> state, Pipeline.Generated generated) {
        OracleContext context = Pipeline.context(state, generated, state.getState().getSeedValue());
        DatabaseUnderTest database = new DatabaseUnderTest(state.getDatabaseName(), state.getConnection(),
                state.getDialect(), Introspectors.forName(state.getDialect().getName()));
        database.setIntendedSchema(generated.adaptedSchema);
        // the live log of this database, so that --log-each-select has somewhere to write to
        database.setStateLogger(state.getLogger());
        context.addDatabase(database);
        try {
            database.setDatabaseVersion(state.getConnection().getDatabaseVersion());
        } catch (Exception e) {
            database.setDatabaseVersion("unknown");
        }
        return context;
    }

    /**
     * Opens every database requested with {@code --compare-with} and creates the given schema there.
     *
     * <p>The schema passed in must already be the <em>common</em> schema — adapted to the
     * intersection of every participating engine's capabilities — so that all engines are asked for
     * exactly the same thing and a difference in their catalogs is a real difference in behaviour.
     * Adapting per engine here would make the comparison report the adaptations instead.</p>
     */
    protected void addComparisonDatabases(OracleContext context, GlobalState<?, ?> state,
            RelationalSchema commonSchema) {
        List<DatabaseConfig> targets = Targets.comparisons(options);
        for (DatabaseConfig target : targets) {
            if (target.getDBMSName().equalsIgnoreCase(state.getDialect().getName())) {
                continue;
            }
            Dialect dialect;
            SchemaIntrospector introspector;
            try {
                dialect = Dialects.forName(target.getDBMSName());
                introspector = Introspectors.forName(target.getDBMSName());
            } catch (RuntimeException e) {
                context.addExecutionEvent(new ExecutionEvent(target.getFullName(), "-- " + e.getMessage(), false,
                        e.getMessage(), null, 0, 0, true, "comparison-unavailable"));
                continue;
            }
            String name = state.getDatabaseName() + "_" + target.getDBMSName();
            try {
                org.ddlsmith.DDLSmithDBConnection connection = Targets.open(target, name);
                // The common schema is deliberately unqualified (CommonDialect.getEffectiveSchemaName
                // returns null), so every participant creates its objects in whatever namespace its
                // connection is pointed at. For an engine that keeps all of a run's generated databases
                // in one place - PostgreSQL's `public` - that namespace is shared, so the second
                // generated database meets the first one's tables (`relation "t_1" already exists`) and
                // every comparison after it reads a schema accumulated from all the databases before it.
                // An embedded engine cannot have this problem: its JDBC URL already gives each generated
                // database a database of its own, and both namespace hook lists are empty for it, which
                // is exactly how this test tells the two kinds apart.
                String namespace = dialect.schemaNameForGeneratedDatabase(options.getSchemaName(),
                        state.getDatabaseName());
                boolean needsNamespace = !dialect.dropNamespaceStatements(namespace).isEmpty()
                        || !dialect.createNamespaceStatements(namespace).isEmpty();
                // The participant is handed a copy of the common schema named after that namespace, so
                // that the introspection side reads the same place the creation side wrote to. The
                // objects themselves are shared: every engine is still asked for exactly the same thing.
                //
                // An engine with no namespace hook of its own creates the unqualified objects in its
                // own default namespace, and the name it is handed must say so: the common schema is
                // named after the *primary* engine's namespace (SQLite's `main`), and an embedded
                // comparison engine whose default namespace differs (H2's `public`) would otherwise be
                // introspected where its tables are not. Every table was reported missing on H2 in a
                // `sqlite --algorithm differential --compare-with h2` run before this.
                String participantNamespace = needsNamespace ? namespace : defaultNamespaceOf(dialect, commonSchema);
                RelationalSchema participantSchema = commonSchema.withName(participantNamespace);
                runSessionSetup(context, connection, dialect, target.getFullName(), participantNamespace);
                DatabaseUnderTest database = new DatabaseUnderTest(target.getDBMSName(), connection, dialect,
                        introspector);
                database.setIntendedSchema(participantSchema);
                try {
                    database.setDatabaseVersion(connection.getDatabaseVersion());
                } catch (Exception e) {
                    database.setDatabaseVersion("unknown");
                }
                context.addDatabase(database);
                List<DDLStatement> script = SchemaCreationScript.create(participantSchema, dialect, options);
                for (DDLStatement statement : script) {
                    execute(context, database, statement, "compare-create");
                }
            } catch (Exception e) {
                context.addExecutionEvent(new ExecutionEvent(target.getFullName(), "-- could not open " + name, false,
                        e.getMessage(), null, 0, 0, true, "comparison-unavailable"));
            }
        }
    }

    /**
     * Builds the schema that every participating engine will be asked to create: the mapped schema
     * adapted to the intersection of the primary dialect and every {@code --compare-with} target.
     *
     * @return the common schema, or null when there is nothing to compare with
     */
    protected RelationalSchema buildCommonSchema(GlobalState<?, ?> state, Pipeline.Generated generated,
            List<String> notes) {
        List<Dialect> participants = new ArrayList<>();
        participants.add(state.getDialect());
        for (DatabaseConfig target : Targets.comparisons(options)) {
            if (target.getDBMSName().equalsIgnoreCase(state.getDialect().getName())) {
                continue;
            }
            if (Dialects.exists(target.getDBMSName())) {
                participants.add(Dialects.forName(target.getDBMSName()));
            }
        }
        if (participants.size() < 2) {
            return null;
        }
        Dialect common = org.ddlsmith.ddl.CommonDialect.of(participants);
        notes.add("differential run: the schema was adapted to what "
                + participants.stream().map(Dialect::getName).collect(java.util.stream.Collectors.joining(" and "))
                + " can all express, so that every engine is asked for exactly the same thing");
        // The common schema is deliberately unqualified, so its objects are created in whatever
        // namespace each connection is pointed at. It still has to *say* which namespace that is, or
        // nothing can read them back: OracleSupport.resolveSchemaName falls back to the engine's
        // default namespace for an anonymous schema, and for a client/server engine that default is
        // shared by every generated database of the run. Naming it after the primary's own namespace
        // keeps creation and introspection pointed at the same place. An embedded engine derives the
        // configured name unchanged, so this is the name its objects already carried.
        RelationalSchema named = SchemaAdapter.adapt(generated.mappedSchema, common, notes).withName(
                state.getDialect().schemaNameForGeneratedDatabase(options.getSchemaName(),
                        state.getDatabaseName()));
        return named;
    }

    /**
     * @return the namespace an engine that has no namespace hook of its own creates its unqualified
     *         objects in: its declared default namespace, or the common schema's own name when the
     *         engine does not declare one (in which case the name is only used for introspection)
     */
    private static String defaultNamespaceOf(Dialect dialect, RelationalSchema commonSchema) {
        String defaultNamespace = dialect.getDefaultSchemaName();
        if (defaultNamespace == null || defaultNamespace.isBlank()) {
            return commonSchema.getName();
        }
        return defaultNamespace;
    }

    /**
     * Prepares a fresh comparison connection for the namespace every engine is asked to create.
     *
     * <p>The namespace is the one the common schema already carries, so all participants really are
     * asked for the same thing. For an embedded engine both the drop and the create are empty — its
     * URL gave it a database of its own — so nothing changes there.</p>
     */
    protected void runSessionSetup(OracleContext context, org.ddlsmith.DDLSmithDBConnection connection,
            Dialect dialect, String databaseName, String namespace) {
        List<String> statements = new ArrayList<>();
        statements.addAll(dialect.dropNamespaceStatements(namespace));
        statements.addAll(dialect.createNamespaceStatements(namespace));
        statements.addAll(dialect.getSessionSetupStatements(namespace));
        for (String sql : statements) {
            long start = System.nanoTime();
            boolean succeeded;
            String message = null;
            try {
                connection.executeStatement(sql);
                succeeded = true;
            } catch (Exception e) {
                succeeded = false;
                message = e.getMessage();
            }
            context.addExecutionEvent(new ExecutionEvent(databaseName, sql, succeeded, message, null, 0,
                    (System.nanoTime() - start) / 1_000_000, false, "session-setup"));
        }
    }

    /** Executes one generated statement, recording the outcome and the features it relies on. */
    protected ExecutionEvent execute(OracleContext context, DatabaseUnderTest database, DDLStatement statement,
            String purpose) {
        List<String> sql = database.getDialect().renderAll(statement);
        ExecutionEvent last = null;
        for (String single : sql) {
            String fullPurpose = purpose + DDLFeatureAnalyzer.purposeSuffix(statement);
            last = StatementRunner.execute(context, database, single, false, fullPurpose);
            if (!last.isSucceeded()) {
                break;
            }
        }
        return last;
    }

    /** Executes the create script of the intended schema. */
    protected void executeCreateScript(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema) {
        List<DDLStatement> script = SchemaCreationScript.create(schema, database.getDialect(), options);
        dumpDdl(context, database, script);
        for (DDLStatement statement : script) {
            execute(context, database, statement, "create");
        }
    }

    /**
     * Writes the whole create script of one database to {@code --ddl-dump-dir}, when it is set.
     *
     * <p>This is the exact script the engine is about to receive, rendered by that engine's dialect,
     * which makes it the right artefact for a bug report: it can be pasted into the engine's own
     * client without DDLSmith in the loop.</p>
     */
    private void dumpDdl(OracleContext context, DatabaseUnderTest database, List<DDLStatement> script) {
        String directory = options.getDdlDumpDir();
        if (directory == null || directory.isBlank()) {
            return;
        }
        // the database name is used as a file name, so anything a file system might reject is folded
        String fileName = database.getName().replaceAll("[^A-Za-z0-9_.-]", "_") + ".sql";
        java.nio.file.Path target = java.nio.file.Paths.get(directory).resolve(fileName);
        List<String> lines = new ArrayList<>();
        lines.add("-- DDLSmith generated DDL");
        lines.add("-- database : " + database.getName());
        lines.add("-- dialect  : " + database.getDialect().getName());
        lines.add("-- version  : " + database.getDatabaseVersion());
        lines.add("-- seed     : " + options.getRandomSeed());
        lines.add("-- objects  : tables=" + database.getIntendedSchema().getTables().size()
                + " views=" + database.getIntendedSchema().getViews().size()
                + " sequences=" + database.getIntendedSchema().getSequences().size());
        lines.add("");
        for (DDLStatement statement : script) {
            lines.addAll(database.getDialect().renderAll(statement));
        }
        try {
            if (target.getParent() != null) {
                java.nio.file.Files.createDirectories(target.getParent());
            }
            java.nio.file.Files.write(target, lines);
        } catch (java.io.IOException e) {
            // a dump is a convenience, never a reason to fail a test run
            context.getTrace().note("could not write the DDL dump to " + target + ": " + e.getMessage());
        }
    }

    /** Runs every applicable oracle and collects the failures. */
    protected List<OracleFailure> runOracles(OracleContext context, GlobalState<?, ?> state) {
        List<TestOracle> oracles = Oracles.resolve(options, context);
        List<OracleFailure> failures = new ArrayList<>();
        for (TestOracle oracle : oracles) {
            try {
                oracle.verify(context).ifPresent(failures::add);
            } catch (org.ddlsmith.IgnoreMeException e) {
                // the oracle decided the input is not applicable
            } catch (Exception e) {
                failures.add(new OracleFailure(OracleFailure.FailureKind.UNEXPECTED_ERROR, oracle.getName(),
                        "oracle " + oracle.getName() + " threw an exception",
                        "The oracle itself failed, which usually indicates a defect in DDLSmith rather than in the "
                                + "database under test.",
                        null, context.getSeed(), describeDatabases(context), e));
            }
        }
        return failures;
    }

    protected static String describeDatabases(OracleContext context) {
        List<String> names = new ArrayList<>();
        for (DatabaseUnderTest database : context.getDatabases()) {
            names.add(database.getFullName());
        }
        return String.join(", ", names);
    }

    /** @return a random boolean with the given probability, used to vary the generated workload */
    protected static boolean chance(double probability) {
        return Randomly.getBoolean(probability);
    }
}
