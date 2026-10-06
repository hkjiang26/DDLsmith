package org.ddlsmith.algorithm.impl;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.GlobalState;
import org.ddlsmith.MainOptions;
import org.ddlsmith.algorithm.AbstractTestingAlgorithm;
import org.ddlsmith.algorithm.Pipeline;
import org.ddlsmith.data.RowGenerator;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.StatementRunner;

/**
 * Focuses on constraints: it creates the schema, fills it with rows that respect every declared
 * constraint, and then probes the constraints by trying to violate them.
 *
 * <p>The interesting outcome is an engine that accepts a row violating a constraint it declared —
 * a constraint that exists in the catalog but is not enforced.</p>
 */
public class ConstraintFocusedAlgorithm extends AbstractTestingAlgorithm {

    public ConstraintFocusedAlgorithm(MainOptions options) {
        super(options);
    }

    @Override
    public String getName() {
        return "constraints";
    }

    @Override
    public List<OracleFailure> test(GlobalState<?, ?> state) throws Exception {
        Pipeline.Generated generated = Pipeline.generate(state, state.getDialect());
        OracleContext context = newContext(state, generated);
        DatabaseUnderTest primary = context.getPrimary();

        executeCreateScript(context, primary, generated.adaptedSchema);

        // populate the schema so that the constraint and referential-action oracles have data to work with
        RowGenerator rows = new RowGenerator(generated.adaptedSchema, state.getDialect());
        for (org.ddlsmith.common.schema.Table table : rows.insertionOrder()) {
            for (int i = 0; i < options.getRowsPerTable(); i++) {
                java.util.Map<String, Object> row = rows.generateRow(table);
                if (row.isEmpty()) {
                    break;
                }
                java.util.Set<String> omitted = new java.util.LinkedHashSet<>();
                if (org.ddlsmith.Randomly.getBoolean(0.25)) {
                    omitted.addAll(rows.defaultableColumns(table));
                }
                String sql = rows.toInsertSql(table, row, omitted);
                StatementRunner.execute(context, primary, sql, false, "seed-insert");
            }
        }

        List<OracleFailure> failures = new ArrayList<>(runOracles(context, state));
        return failures;
    }
}
