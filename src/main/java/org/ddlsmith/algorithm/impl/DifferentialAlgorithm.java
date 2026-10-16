package org.ddlsmith.algorithm.impl;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.GlobalState;
import org.ddlsmith.MainOptions;
import org.ddlsmith.algorithm.AbstractTestingAlgorithm;
import org.ddlsmith.algorithm.Pipeline;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.StatementRunner;

/**
 * Differential DDL testing: the same ER diagram is mapped once, then adapted and created on several
 * engines, and the engines' catalogs are compared against each other.
 *
 * <p>This is the DDLsmith analogue of GDsmith's differential oracle. It is what makes the tool
 * usable with no infrastructure: {@code --compare-with h2,duckdb} compares SQLite, H2 and DuckDB
 * in-process, and any disagreement about what a schema means is a finding worth investigating.</p>
 */
public class DifferentialAlgorithm extends AbstractTestingAlgorithm {

    public DifferentialAlgorithm(MainOptions options) {
        super(options);
    }

    @Override
    public String getName() {
        return "differential";
    }

    @Override
    public List<OracleFailure> test(GlobalState<?, ?> state) throws Exception {
        Pipeline.Generated generated = Pipeline.generate(state, state.getDialect());

        // Every engine is asked for the same schema: the mapped schema adapted to what all the
        // participating dialects can express together. Comparing per-dialect adaptations would report
        // the adaptations instead of any disagreement between the engines.
        List<String> notes = new ArrayList<>();
        org.ddlsmith.common.schema.RelationalSchema common = buildCommonSchema(state, generated, notes);
        if (common != null) {
            generated.adaptedSchema = common;
            generated.notes.addAll(notes);
        }

        OracleContext context = newContext(state, generated);
        DatabaseUnderTest primary = context.getPrimary();
        executeCreateScript(context, primary, generated.adaptedSchema);
        addComparisonDatabases(context, state, generated.adaptedSchema);

        List<OracleFailure> failures = new ArrayList<>(runOracles(context, state));

        if (context.getDatabases().size() < 2) {
            failures.add(new OracleFailure(FailureKind.DIFFERENTIAL, getName(),
                    "differential testing requested but only one database is available",
                    "The differential algorithm needs at least two databases. Pass --compare-with with one or more "
                            + "engines (for example --compare-with h2,duckdb) or add entries to config.json.",
                    null, context.getSeed(), describeDatabases(context), null));
            return failures;
        }

        // A first, engine independent sanity check that does not depend on catalog fidelity at all:
        // every table that was asked for must exist and be empty.
        for (DatabaseUnderTest database : context.getDatabases()) {
            for (org.ddlsmith.common.schema.Table table : generated.adaptedSchema.getTables()) {
                String sql = "SELECT COUNT(*) FROM " + database.getDialect().quote(table.getName());
                try {
                    org.ddlsmith.common.query.QueryResult result = StatementRunner.query(context, database, sql,
                            "differential-count");
                    long count = result.getRowCount() == 0 ? -1
                            : ((Number) result.getValue(0, 0)).longValue();
                    if (count != 0) {
                        failures.add(new OracleFailure(FailureKind.DIFFERENTIAL, getName(),
                                "table " + table.getName() + " is not empty right after creation",
                                "Database " + database.getFullName() + " reports " + count + " row(s) in "
                                        + table.getName() + " immediately after the schema was created.",
                                sql, context.getSeed(), describeDatabases(context), null));
                    }
                } catch (Exception e) {
                    failures.add(new OracleFailure(FailureKind.DIFFERENTIAL, getName(),
                            "table " + table.getName() + " cannot be queried on " + database.getFullName(),
                            "Querying " + table.getName() + " failed: " + e.getMessage()
                                    + "\nThis means the table was not created as requested.",
                            sql, context.getSeed(), describeDatabases(context), e));
                }
            }
        }
        return failures;
    }
}
