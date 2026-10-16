package org.ddlsmith.algorithm.impl;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.GlobalState;
import org.ddlsmith.MainOptions;
import org.ddlsmith.algorithm.AbstractTestingAlgorithm;
import org.ddlsmith.algorithm.Pipeline;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.gen.RandomDDLGenerator;
import org.ddlsmith.introspection.SchemaNormalizer;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleFailure;

/**
 * Focuses on schema evolution: it creates the schema, evolves it with random {@code ALTER TABLE}
 * statements, and checks after every step that the database still matches the schema DDLsmith
 * believes it has.
 *
 * <p>This turns the round-trip comparison into an incremental one, which pinpoints the exact
 * {@code ALTER TABLE} statement that made the catalog diverge — much easier to act on than a single
 * comparison at the end.</p>
 */
public class SchemaEvolutionAlgorithm extends AbstractTestingAlgorithm {

    public SchemaEvolutionAlgorithm(MainOptions options) {
        super(options);
    }

    @Override
    public String getName() {
        return "evolution";
    }

    @Override
    public List<OracleFailure> test(GlobalState<?, ?> state) throws Exception {
        Pipeline.Generated generated = Pipeline.generate(state, state.getDialect());
        OracleContext context = newContext(state, generated);
        DatabaseUnderTest primary = context.getPrimary();

        executeCreateScript(context, primary, generated.adaptedSchema);

        // The create script is the one place a table can go missing with no later statement to blame.
        // Checking it before the first evolution step is what keeps the divergence report honest: the
        // loop below reports the statement it has just run, so without this check a table the create
        // script never managed to build is pinned on the first DDL step that happens to succeed, and
        // the report then claims to "pinpoint the statement that introduced the divergence" about a
        // statement that had nothing to do with it. Measured on the MySQL campaign: ten databases
        // blamed an unrelated ALTER TABLE or CREATE VIEW for a missing table.
        List<OracleFailure> failures = new ArrayList<>();
        primary.setIntendedSchema(generated.adaptedSchema);
        context.setIntendedSchema(generated.adaptedSchema);
        List<org.ddlsmith.introspection.SchemaDifference> atCreation = compare(context, primary,
                generated.adaptedSchema);
        if (!atCreation.isEmpty()) {
            failures.add(new OracleFailure(OracleFailure.FailureKind.SCHEMA_MISMATCH, getName(),
                    "the create script did not produce the intended schema",
                    "The schema the engine reports does not match the schema the create script below was "
                            + "meant to build, and no evolution statement has run yet, so the difference "
                            + "was left behind by the create script itself.\n\n"
                            + rejectedStatements(context)
                            + SchemaNormalizer.render(atCreation),
                    executedScript(context), context.getSeed(), describeDatabases(context), null));
            return failures;
        }

        int steps = options.getEvolveSteps() > 0 ? options.getEvolveSteps()
                : Math.max(1, options.getNumDdlStatements() / 4);
        RandomDDLGenerator evolution = new RandomDDLGenerator(generated.adaptedSchema, state.getDialect(), options);

        List<DDLStatement> history = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            DDLStatement statement = evolution.nextStatement().orElse(null);
            if (statement == null) {
                break;
            }
            history.add(statement);
            org.ddlsmith.oracle.OracleContext.ExecutionEvent event = execute(context, primary, statement, "evolve");
            if (!event.isSucceeded()) {
                // The engine refused the statement, so its effect must not stay in the model: comparing
                // the catalog with a schema that was never created reports the missing column or index
                // as a divergence, which is a finding about DDLsmith rather than about the engine — and
                // it hides the rejection itself, which is what the crash oracle is for. Rolling the
                // model back keeps the two in step and lets the evolution continue from a state the
                // engine really has.
                evolution.discardLastStatement();
                continue;
            }
            RelationalSchema expected = evolution.getCurrentSchema();
            primary.setIntendedSchema(expected);
            context.setIntendedSchema(expected);
            List<org.ddlsmith.introspection.SchemaDifference> differences = compare(context, primary, expected);
            if (!differences.isEmpty()) {
                failures.add(new OracleFailure(OracleFailure.FailureKind.SCHEMA_MISMATCH, getName(),
                        "schema diverged after " + statement.describe(),
                        "After executing the ALTER TABLE statement below, the schema reported by the database no "
                                + "longer matches the schema DDLsmith intended. This pinpoints the statement that "
                                + "introduced the divergence.\n\n"
                                + "Statement: " + statement.toSql(primary.getDialect()) + "\n\n"
                                + "Preceding statements:\n" + renderHistory(history, primary) + "\n"
                                + SchemaNormalizer.render(differences),
                        renderHistory(history, primary), context.getSeed(), describeDatabases(context), null));
                // stop this round: everything after the first divergence is noise
                return failures;
            }
        }

        failures.addAll(runOracles(context, state));
        return failures;
    }

    private List<org.ddlsmith.introspection.SchemaDifference> compare(OracleContext context,
            DatabaseUnderTest database, RelationalSchema expected) throws Exception {
        RelationalSchema actual = database.getIntrospector().introspect(database.getConnection(),
                expected.getName());
        java.util.Set<org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability> capabilities = new java.util.LinkedHashSet<>(
                database.getIntrospector().getCapabilities());
        capabilities.retainAll(java.util.EnumSet.allOf(
                org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.class));
        RelationalSchema erased = SchemaNormalizer.erase(expected, capabilities);
        return SchemaNormalizer.diff(erased, actual,
                SchemaNormalizer.preservesExactTypes(database.getDialect().getFeatures()),
                capabilities.contains(
                        org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability.FOREIGN_KEY_ACTIONS));
    }

    private static String renderHistory(List<DDLStatement> history, DatabaseUnderTest database) {
        StringBuilder sb = new StringBuilder();
        for (DDLStatement statement : history) {
            for (String sql : database.getDialect().renderAll(statement)) {
                sb.append(sql).append(";\n");
            }
        }
        return sb.toString();
    }

    /**
     * @return the statements of the create script that the engine refused, one per line, or a note
     *         that it accepted all of them.
     *
     * <p>A create script that fails silently is the case this check exists for, and the rejection is
     * the whole diagnosis: without it the report would only say that a table is missing.</p>
     */
    private static String rejectedStatements(OracleContext context) {
        StringBuilder sb = new StringBuilder();
        for (OracleContext.ExecutionEvent event : context.getExecutionEvents()) {
            if (!event.isSucceeded()) {
                sb.append("Rejected by the engine:\n  ")
                        .append(org.ddlsmith.oracle.StatementRunner.describe(event)).append("\n\n");
            }
        }
        if (sb.length() == 0) {
            sb.append("The engine accepted every statement of the create script, so the difference is "
                    + "between what was asked of it and what it recorded.\n\n");
        }
        return sb.toString();
    }

    /** @return exactly the statements the create script sent, in order */
    private static String executedScript(OracleContext context) {
        StringBuilder sb = new StringBuilder();
        for (OracleContext.ExecutionEvent event : context.getExecutionEvents()) {
            sb.append(event.getSql()).append(";\n");
        }
        return sb.toString();
    }
}
