package org.ddlsmith.conformance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.ddlsmith.IgnoreMeException;
import org.ddlsmith.Randomly;
import org.ddlsmith.common.query.QueryResult;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.conformance.ConformancePopulator.Rejection;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.StatementRunner;
import org.ddlsmith.oracle.TestOracle;
import org.ddlsmith.oracle.impl.ConstraintEnforcementOracle.Support;
import org.ddlsmith.oracle.impl.OracleSupport;

/**
 * The graph-conformance oracle: it materializes the generated graph instance into the intended
 * schema and checks that the engine stores that instance and answers about it the way the ER
 * diagram says it should.
 *
 * <p>Every other oracle in DDLSmith asks a question about one statement, one constraint or one
 * table. This one asks the question the whole pipeline was built to make answerable: <em>given the
 * graph instance the schema was derived from, does the database behave like the schema the ER
 * diagram describes?</em> The graph is the ground truth, and it is not a second engine, not a
 * rewritten query and not an expected value a human wrote down: it is the same object that produced
 * the schema.</p>
 *
 * <h2>What it does, per database</h2>
 * <ol>
 *   <li>{@link ConformancePlanner} turns the instance into rows: nodes to rows, edges to foreign key
 *       values or join rows, structural links to the references the mapper created for them.</li>
 *   <li>{@link ConformancePopulator} empties the tables and inserts those rows, keeping only the ones
 *       the engine accepted. A row the graph requires and the engine rejects is itself a finding
 *       ({@link FailureKind#CONSTRAINT_TOO_STRICT}).</li>
 *   <li>{@link SemanticQueryBuilder} derives the queries: every populated table's rows, every foreign
 *       key (its join, its per-parent cardinality, its dangling references) and every generated view
 *       against its own body.</li>
 *   <li>The engine's answers are compared with the instance. A difference is
 *       {@link FailureKind#DATA_MISMATCH}.</li>
 * </ol>
 *
 * <h2>Why a difference is a defect</h2>
 * <p>The chain that makes the expectation trustworthy is the one the project already verifies: the
 * generated instance is a legal instance of the ER diagram ({@code CardinalityEnforcer}), the mapper
 * is validated over tens of thousands of mappings (its invariants, its self-check and its drop
 * ledger), and the materialization is deterministic and derived from the graph rather than from the
 * engine. So a disagreement is either the engine not implementing what it accepted, or DDLSmith
 * being wrong about what it asked for — and the second case is not hidden: it is what the census of
 * a run measures.</p>
 *
 * <h2>Guards</h2>
 * <ul>
 *   <li>Only tables the plan populated are queried, and only rows the engine accepted are expected.</li>
 *   <li>A table that could not be emptied, or in which any row was rejected, is left out of the
 *       foreign key checks: its contents are not the instance the graph describes.</li>
 *   <li>A partially NULL foreign key is not a reference (MATCH SIMPLE), so it is neither expected in a
 *       join nor counted as a child nor reported as dangling.</li>
 *   <li>Columns whose values have no portable rendering (JSON, ARRAY, ENUM, ...) are stored but not
 *       compared, so an engine's normalisation of them cannot become a finding.</li>
 *   <li>A construct the materializer cannot derive from the graph (an aggregation, an untraceable
 *       link) is skipped <em>and</em> announced in the notes of the report.</li>
 *   <li>On {@code --evolve-steps} runs the schema under test is no longer the mapped schema, so the
 *       oracle declines the input instead of reporting the evolution as a mismatch.</li>
 * </ul>
 */
public class GraphConformanceOracle implements TestOracle {

    public static final String NAME = "graph-conformance";
    /** Upper bound on the number of semantic queries executed per database. */
    public static final int MAX_QUERIES = 40;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Materializes the generated graph instance into the intended schema and checks that the engine"
                + " stores it and answers about it as the ER diagram implies (rows, joins, cardinalities, views).";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        RelationalSchema intended = context.getIntendedSchema();
        if (intended == null || intended.getTables().isEmpty()) {
            return Optional.empty();
        }
        if (context.getGraph() == null || context.getErDiagram() == null || context.getTrace() == null) {
            // nothing to materialize: the oracle is only meaningful for a generated input
            return Optional.empty();
        }
        if (context.getOptions() != null && context.getOptions().getEvolveSteps() > 0) {
            throw new IgnoreMeException("graph-conformance is skipped: --evolve-steps moved the schema under test"
                    + " away from the mapped schema the graph instance belongs to");
        }
        List<Support.Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (DatabaseUnderTest database : context.getDatabases()) {
            RelationalSchema schema = OracleSupport.intendedSchemaFor(context, database);
            if (schema == null || schema.getTables().isEmpty()) {
                continue;
            }
            verifyDatabase(context, database, schema, findings, notes);
        }
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toFailure(context, findings, notes));
    }

    private void verifyDatabase(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
            List<Support.Finding> findings, List<String> notes) {
        String prefix = "[" + database.getName() + "] ";
        ConformancePlan plan = plan(context, database, schema);
        try {
            verifyPlannedDatabase(context, database, schema, plan, findings, notes, prefix);
        } finally {
            // the rows are removed again whatever happened: the oracle must not change what the next
            // oracle (or the algorithm's own post-conditions) sees, and the differential algorithm
            // checks after the oracles that the tables it created are still empty
            ConformancePopulator.cleanup(context, database, schema, plan, notes);
        }
    }

    private void verifyPlannedDatabase(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
            ConformancePlan plan, List<Support.Finding> findings, List<String> notes, String prefix) {
        for (String note : plan.getNotes()) {
            notes.add(prefix + note);
        }
        if (plan.isEmpty()) {
            notes.add(prefix + "no row could be materialized from the graph instance, so nothing is checked");
            return;
        }
        ConformancePopulator.Result population = ConformancePopulator.populate(context, database, plan, schema);
        for (String note : population.notes) {
            notes.add(prefix + note);
        }
        for (Rejection rejection : population.rejections) {
            findings.add(rejection(database, rejection));
        }
        List<SemanticQuery> queries = new ArrayList<>(
                queries(context, database, schema, plan, population, notes));
        if (queries.isEmpty()) {
            notes.add(prefix + "no semantic query could be derived from the materialized instance");
            return;
        }
        Randomly.shuffle(queries);
        int limit = Math.min(queries.size(), MAX_QUERIES);
        if (queries.size() > limit) {
            notes.add(prefix + "only " + limit + " of " + queries.size() + " semantic queries were executed (the cap"
                    + " is " + MAX_QUERIES + " per database)");
        }
        for (int i = 0; i < limit; i++) {
            Support.Finding finding = run(context, database, queries.get(i));
            if (finding != null) {
                findings.add(finding);
            }
        }
    }

    /* ================================================================== test seams */

    /**
     * Test seam: the materialization the oracle checks the engine against.
     *
     * <p>A test can override it to hand the oracle a plan with a row the engine cannot store, and
     * assert that the rejection is reported — the same both-directions discipline the other data
     * oracles use.</p>
     */
    protected ConformancePlan plan(OracleContext context, DatabaseUnderTest database, RelationalSchema schema) {
        return new ConformancePlanner(context.getGraph(), context.getErDiagram(), schema, context.getTrace(),
                database.getDialect()).plan();
    }

    /**
     * Test seam: the query bank.
     *
     * <p>A test can override it to add a query whose expectation is deliberately wrong and assert that
     * the mismatch is reported.</p>
     */
    protected List<SemanticQuery> queries(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema, ConformancePlan plan, ConformancePopulator.Result population,
            List<String> notes) {
        return SemanticQueryBuilder.build(context, database, schema, plan, population, notes);
    }

    /* ================================================================== execution */

    private Support.Finding run(OracleContext context, DatabaseUnderTest database, SemanticQuery query) {
        QueryResult result;
        try {
            result = StatementRunner.query(context, database, query.getSql(), "probe-conformance-query");
        } catch (Exception e) {
            // a query the engine cannot execute at all is the crash oracle's business, not a mismatch
            return null;
        }
        if (query.getKind() == SemanticQuery.Kind.UNFOLD) {
            QueryResult reference;
            try {
                reference = StatementRunner.query(context, database, query.getReferenceSql(),
                        "probe-conformance-unfold");
            } catch (Exception e) {
                return null;
            }
            List<String> left = plainRows(result);
            List<String> right = plainRows(reference);
            Collections.sort(left);
            Collections.sort(right);
            if (left.equals(right)) {
                return null;
            }
            return mismatch(database, query, left, right,
                    "the view and its own definition return different rows");
        }
        List<String> actual = canonicalRows(result, query.getTypes());
        List<String> expected = new ArrayList<>(query.getExpected());
        Collections.sort(actual);
        Collections.sort(expected);
        if (actual.equals(expected)) {
            return null;
        }
        String explanation;
        if (actual.size() != expected.size()) {
            explanation = "the engine returned " + actual.size() + " row(s) where the materialized instance has "
                    + expected.size();
        } else {
            explanation = "the engine returned the same number of rows but different values";
        }
        return mismatch(database, query, expected, actual, explanation);
    }

    /** Canonicalizes a result with the declared types of the selected columns. */
    private static List<String> canonicalRows(QueryResult result, List<DataType> types) {
        List<String> rows = new ArrayList<>();
        for (List<Object> row : result.getRows()) {
            rows.add(SemanticQueryBuilder.tuple(row, types));
        }
        return rows;
    }

    /**
     * Canonicalizes a result without declared types, for a comparison between two engine answers (a
     * view and its body): both sides come from the same engine, so the values only have to be
     * described the same way on both sides.
     */
    private static List<String> plainRows(QueryResult result) {
        List<String> rows = new ArrayList<>();
        for (List<Object> row : result.getRows()) {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Object normalized = DataModel.normalizeForComparison(row.get(i));
                sb.append(normalized == null ? "NULL" : normalized);
            }
            rows.add(sb.append(')').toString());
        }
        return rows;
    }

    /* ================================================================== findings */

    private Support.Finding rejection(DatabaseUnderTest database, Rejection rejection) {
        StringBuilder details = new StringBuilder();
        details.append("The materialized graph instance requires a row the engine refused to store.\n");
        details.append("Table      : ").append(rejection.table.getName()).append('\n');
        details.append("Comes from : ").append(rejection.row.getProvenance()).append('\n');
        details.append("Row        : ").append(rejection.row.getValues()).append('\n');
        details.append("SQL        : ").append(rejection.sql).append('\n');
        details.append("Observed   : ").append(StatementRunner.describe(rejection.event)).append('\n');
        details.append("The row was generated from the graph, validated against every declared NOT NULL and CHECK"
                + " constraint of the table, and it is not a duplicate of another planned row. A rejection here means"
                + " the schema cannot hold the instance its own ER diagram describes.\n");
        String reproducer = rejection.sql + ";\n";
        return Support.Finding.of(FailureKind.CONSTRAINT_TOO_STRICT, database.getName(),
                "materializing " + rejection.table.getName(),
                "a row the generated graph instance requires was rejected by the engine",
                OracleSupport.truncate(details.toString()), reproducer);
    }

    private Support.Finding mismatch(DatabaseUnderTest database, SemanticQuery query, List<String> expected,
            List<String> observed, String explanation) {
        StringBuilder details = new StringBuilder();
        details.append("The engine's answer disagrees with the graph instance the schema was materialized from.\n");
        details.append("Check      : ").append(query.getGroup()).append('\n');
        details.append("Meaning    : ").append(query.getDescription()).append('\n');
        details.append("Provenance : ").append(query.getProvenance()).append('\n');
        details.append("Query      : ").append(query.getSql()).append('\n');
        if (query.getReferenceSql() != null) {
            details.append("Reference  : ").append(query.getReferenceSql()).append('\n');
        }
        details.append("Explanation: ").append(explanation).append('\n');
        details.append("Expected   : ").append(expected.size()).append(" row(s)\n");
        details.append("Observed   : ").append(observed.size()).append(" row(s)\n");
        details.append("Differences (bounded):\n").append(describeDifference(expected, observed));
        return Support.Finding.of(FailureKind.DATA_MISMATCH, database.getName(), query.getGroup(),
                query.getGroup() + " does not match the materialized graph instance",
                OracleSupport.truncate(details.toString()),
                query.getSql() + ";\n"
                        + (query.getReferenceSql() == null ? "" : query.getReferenceSql() + ";\n"));
    }

    /** @return the first few tuples that differ, so that a report shows what happened, not just that it did */
    private static String describeDifference(List<String> expected, List<String> observed) {
        Set<String> expectedSet = new LinkedHashSet<>(expected);
        Set<String> observedSet = new LinkedHashSet<>(observed);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String tuple : expectedSet) {
            if (!observedSet.contains(tuple)) {
                sb.append("  expected but not returned: ").append(tuple).append('\n');
                if (++shown >= 10) {
                    break;
                }
            }
        }
        for (String tuple : observedSet) {
            if (!expectedSet.contains(tuple)) {
                sb.append("  returned but not expected: ").append(tuple).append('\n');
                if (++shown >= 20) {
                    break;
                }
            }
        }
        if (shown == 0 && expected.size() != observed.size()) {
            sb.append("  the multisets hold the same values but a different number of rows\n");
        }
        return sb.toString();
    }

    /**
     * Aggregates the findings into one failure: one representative per (database, check), with every
     * occurrence counted and the notes appended, so that a report always says what was <em>not</em>
     * checked.
     */
    private OracleFailure toFailure(OracleContext context, List<Support.Finding> findings, List<String> notes) {
        Map<String, List<Support.Finding>> groups = new LinkedHashMap<>();
        Set<String> databases = new LinkedHashSet<>();
        Map<FailureKind, Integer> kindCounts = new LinkedHashMap<>();
        for (Support.Finding finding : findings) {
            groups.computeIfAbsent(finding.database + "|" + finding.group, key -> new ArrayList<>()).add(finding);
            databases.add(finding.database);
            kindCounts.merge(finding.kind, 1, Integer::sum);
        }
        StringBuilder details = new StringBuilder();
        details.append(findings.size()).append(" disagreement(s) between the engine and the materialized graph")
                .append(" instance, in ").append(groups.size()).append(" (database, check) group(s).\n")
                .append("At most one representative is described per group; the count covers every occurrence.\n");
        StringBuilder reproducer = new StringBuilder();
        for (Map.Entry<String, List<Support.Finding>> entry : groups.entrySet()) {
            Support.Finding representative = entry.getValue().get(0);
            details.append("\n================================================================\n");
            details.append("database : ").append(representative.database).append('\n');
            details.append("check    : ").append(representative.group).append('\n');
            details.append("kind     : ").append(representative.kind).append('\n');
            details.append("occurrences in this group: ").append(entry.getValue().size()).append('\n');
            details.append("----------------------------------------------------------------\n");
            details.append(representative.details).append('\n');
            if (representative.reproducer != null && !representative.reproducer.isBlank()) {
                reproducer.append("-- ").append(representative.database).append(": ")
                        .append(representative.title).append('\n').append(representative.reproducer);
            }
        }
        if (!notes.isEmpty()) {
            details.append("\n================================================================\n");
            details.append("What was not materialized or not checked (").append(notes.size()).append(" note(s)):\n");
            for (String note : notes) {
                details.append("  - ").append(note).append('\n');
            }
        }
        StringBuilder title = new StringBuilder();
        title.append(findings.size()).append(" semantic disagreement(s) with the materialized graph instance (");
        boolean first = true;
        for (Map.Entry<FailureKind, Integer> entry : kindCounts.entrySet()) {
            if (!first) {
                title.append(", ");
            }
            first = false;
            title.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        title.append(')');
        return new OracleFailure(findings.get(0).kind, NAME, title.toString(),
                OracleSupport.truncate(details.toString()), reproducer.toString(), context.getSeed(),
                String.join(", ", databases), null);
    }
}
