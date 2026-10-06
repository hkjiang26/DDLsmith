package org.ddlsmith.algorithm.impl;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.GlobalState;
import org.ddlsmith.MainOptions;
import org.ddlsmith.algorithm.AbstractTestingAlgorithm;
import org.ddlsmith.algorithm.Pipeline;
import org.ddlsmith.ddl.gen.RandomDDLGenerator;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleFailure;

/**
 * The default algorithm: generate an ER diagram, map it to a relational schema, create it, and run
 * the oracles on the result. Optionally evolves the schema with random DDL afterwards.
 */
public class RandomDDLAlgorithm extends AbstractTestingAlgorithm {

    public RandomDDLAlgorithm(MainOptions options) {
        super(options);
    }

    @Override
    public String getName() {
        return "random";
    }

    @Override
    public List<OracleFailure> test(GlobalState<?, ?> state) throws Exception {
        Pipeline.Generated generated = Pipeline.generate(state, state.getDialect());

        // When comparing, every engine is asked for the same schema: the mapped schema adapted to
        // what all the participating dialects can express together. This has to happen before
        // anything is created, because the intended schema must be exactly what the primary received.
        if (!options.getCompareWith().isEmpty()) {
            List<String> notes = new ArrayList<>();
            org.ddlsmith.common.schema.RelationalSchema common = buildCommonSchema(state, generated, notes);
            if (common != null) {
                generated.adaptedSchema = common;
                generated.notes.addAll(notes);
            }
        }

        OracleContext context = newContext(state, generated);
        DatabaseUnderTest primary = context.getPrimary();

        executeCreateScript(context, primary, generated.adaptedSchema);

        if (!options.getCompareWith().isEmpty()) {
            addComparisonDatabases(context, state, generated.adaptedSchema);
        }

        if (options.getEvolveSteps() > 0) {
            RandomDDLGenerator evolution = new RandomDDLGenerator(generated.adaptedSchema, state.getDialect(),
                    options);
            for (org.ddlsmith.ddl.DDLStatement statement : evolution.generate(options.getEvolveSteps())) {
                execute(context, primary, statement, "evolve");
            }
            primary.setIntendedSchema(evolution.getCurrentSchema());
            context.setIntendedSchema(evolution.getCurrentSchema());
            for (String note : evolution.getNotes()) {
                context.getTrace().note("dialect adaptation during evolution: " + note);
            }
        }

        List<OracleFailure> failures = new ArrayList<>(runOracles(context, state));
        return failures;
    }
}
