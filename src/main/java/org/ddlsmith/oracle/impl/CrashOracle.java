package org.ddlsmith.oracle.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.TestOracle;

/**
 * Reports statements that failed even though the engine should have accepted them.
 *
 * <p>DDLSmith only generates DDL that the target dialect declares as supported, so a rejected
 * {@code CREATE TABLE}, {@code ALTER TABLE} or {@code CREATE INDEX} is a defect of the engine (or of
 * its declared feature set). Statements that are <em>supposed</em> to fail &mdash; the constraint
 * probes of the other oracles &mdash; are recognized by their purpose and are not reported.</p>
 *
 * <h2>The purpose convention</h2>
 *
 * <p>{@link ExecutionEvent#getPurpose()} is formatted as</p>
 *
 * <pre>
 *     &lt;purpose&gt;[&lt;FEATURE&gt;,&lt;FEATURE&gt;]
 * </pre>
 *
 * <p>for example {@code create-table[COMPOSITE_PRIMARY_KEY]} or
 * {@code create-schema[COMPOSITE_FOREIGN_KEY,DEFERRABLE_FK]}. The bracketed list names the
 * {@link DDLFeature}s the statement needs in order to be accepted; {@link FeaturePurpose} parses it.
 * The crash oracle uses it to attribute a failure to a feature:</p>
 *
 * <ul>
 *   <li>a failure whose SQLState means "feature not supported" is <em>not</em> reported when the
 *       purpose names features and the dialect declares none of them &mdash; the generator emitted a
 *       construct for an engine that never claimed to support it, which is a generator bug;</li>
 *   <li>the same failure <em>is</em> reported when the dialect declares at least one of the named
 *       features, and the report states which ones &mdash; the engine contradicted its own feature
 *       set;</li>
 *   <li>a failure that is not a "feature not supported" error is always reported, because nothing in
 *       the dialect's feature set excuses it.</li>
 * </ul>
 *
 * <p>Probes mark their statements with a purpose starting with {@code probe-}; when such an event is
 * additionally flagged {@code expectedToFail}, failing is the correct behaviour and the event is
 * ignored. The mirror image is reported as well: a statement that was expected to be rejected but
 * was accepted ({@code isUnexpectedSuccess()}) is reported as a possible engine defect, but only for
 * purposes starting with {@code probe-}.</p>
 */
public class CrashOracle implements TestOracle {

    /** The name used to select this oracle on the command line. */
    public static final String NAME = "crash";

    /** How many statements before the failing one are quoted in the report. */
    private static final int PRECEDING_STATEMENTS = 5;

    /** The purpose prefix that marks a statement as a probe. */
    private static final String PROBE_PREFIX = "probe-";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "reports statements the engine rejected although it declares the features they rely on";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        // An unexpected failure is the stronger signal, so it wins over an unexpected success.
        ExecutionEvent mostInteresting = null;
        for (ExecutionEvent event : context.getExecutionEvents()) {
            if (!isReportableFailure(context, event)) {
                continue;
            }
            // "first, or the most interesting": a strict comparison keeps the earliest event on ties.
            if (mostInteresting == null || interestingness(event) > interestingness(mostInteresting)) {
                mostInteresting = event;
            }
        }
        if (mostInteresting != null) {
            return Optional.of(unexpectedFailure(context, mostInteresting));
        }
        for (ExecutionEvent event : context.getExecutionEvents()) {
            if (event.isUnexpectedSuccess() && FeaturePurpose.isProbe(event.getPurpose())) {
                return Optional.of(unexpectedSuccess(context, event));
            }
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------ classification */

    /** @return true when the event is an engine defect rather than correct or excusable behaviour */
    private static boolean isReportableFailure(OracleContext context, ExecutionEvent event) {
        if (!event.isUnexpectedFailure()) {
            // either it succeeded, or it was expected to fail (a constraint probe doing its job)
            return false;
        }
        if (isDataStatement(event)) {
            // A row DDLSmith generated is not part of the DDL contract this oracle asks about: an
            // INSERT rejected for a unique or foreign key violation means the row generator did not
            // respect a constraint of its own schema, which is DDLSmith's business and is reported by
            // the data oracles as "the table could not be populated". Blaming the engine for it
            // produced reports like "engine rejected seed-insert" at high confidence — the most
            // damaging kind of false finding this tool can file.
            return false;
        }
        if (event.isExpectedToFail() && FeaturePurpose.isProbe(event.getPurpose())) {
            // Belt and braces: isUnexpectedFailure() already excludes expected failures, but the rule
            // "a probe that was expected to fail is not a finding" is the contract of this oracle and
            // is stated explicitly here so that it cannot be lost in a refactoring.
            return false;
        }
        if (brokeAModeledLimit(context, event)) {
            // The generator asked for something one of the engine's documented limits forbids, and
            // EngineTraits is where DDLSmith keeps those limits. Reporting it here accuses the engine of
            // rejecting a construct it never claimed to accept, at high confidence: on the MySQL
            // campaign that produced twenty such reports, every one of them for a key DDLSmith had built
            // itself by widening TEXT key columns to VARCHAR(255) until four of them needed 4080 bytes.
            // The generator has to respect the limit rather than the engine being told about it — and
            // when it does not, the consequence is not silent, because the object never appears: the
            // schema round-trip oracle reports it missing, and the evolution algorithm reports the
            // create script that did not produce the intended schema.
            return false;
        }
        return !isUndeclaredFeatureFailure(context, event);
    }

    /** @return true when the engine refused the statement by naming one of the limits DDLSmith models */
    private static boolean brokeAModeledLimit(OracleContext context, ExecutionEvent event) {
        DatabaseUnderTest database = OracleSupport.database(context, event.getDatabaseName());
        return database != null && org.ddlsmith.ddl.gen.EngineTraits.isModeledLimitFailure(
                database.getDialect()::includesEngine, event.getErrorCode());
    }

    /**
     * @return true for the statements that insert rows rather than define the schema: the seeding
     *         inserts of the data oracles ({@code seed-insert}) and the rows the DDL-only modes write
     *         to make a table non-empty
     */
    private static boolean isDataStatement(ExecutionEvent event) {
        String purpose = FeaturePurpose.base(event.getPurpose());
        if (purpose != null) {
            String normalized = purpose.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("seed-insert") || normalized.startsWith("seed-row")) {
                return true;
            }
        }
        String sql = event.getSql() == null ? "" : event.getSql().trim().toUpperCase(Locale.ROOT);
        // a probe is a deliberate violation and is handled by its own oracle; anything else that
        // inserts is a generated row
        return (sql.startsWith("INSERT ") || sql.startsWith("UPDATE ") || sql.startsWith("DELETE "))
                && !FeaturePurpose.isProbe(event.getPurpose());
    }

    /**
     * @return true when the engine rejected the statement because it does not support a feature, and
     *         the purpose of the statement shows that the dialect never declared that feature: the
     *         generator should not have emitted the statement at all, so this is not an engine bug
     */
    private static boolean isUndeclaredFeatureFailure(OracleContext context, ExecutionEvent event) {
        if (!indicatesUnsupportedFeature(event.getSqlState())) {
            return false;
        }
        List<DDLFeature> required = FeaturePurpose.features(event.getPurpose());
        if (required.isEmpty()) {
            // Nothing in the purpose says which feature was needed, so the dialect cannot be shown to
            // have declined it: report the failure rather than hide it.
            return false;
        }
        DDLFeatureSet declared = OracleSupport.featuresOf(context, OracleSupport.database(context,
                event.getDatabaseName()));
        for (DDLFeature feature : required) {
            if (declared.supports(feature)) {
                return false;
            }
        }
        return true;
    }

    /** @return true for the SQLStates that mean "this engine does not implement that feature" */
    private static boolean indicatesUnsupportedFeature(String sqlState) {
        if (sqlState == null) {
            return false;
        }
        String state = sqlState.trim().toUpperCase(Locale.ROOT);
        // 0A000 "feature not supported" (SQL standard), HYC00 "optional feature not implemented" (ODBC)
        return state.startsWith("0A") || state.equals("HYC00");
    }

    /**
     * Prefers failures of the statements that build the schema over failures of probes, so that the
     * report points at the construct that is most likely to be the actual defect.
     *
     * <p>Both the purpose and the statement itself are inspected: a generator may label its
     * statements {@code create[COMPOSITE_PRIMARY_KEY]} (one purpose for the whole create script) or
     * {@code create-table[COMPOSITE_PRIMARY_KEY]} (one purpose per statement kind), and the SQL
     * keyword is available in either case.</p>
     */
    private static int interestingness(ExecutionEvent event) {
        String base = FeaturePurpose.base(event.getPurpose());
        String purpose = base == null ? "" : base.toLowerCase(Locale.ROOT);
        if (purpose.startsWith(PROBE_PREFIX)) {
            // probes are the least interesting: failing is frequently the correct behaviour
            return 10;
        }
        String sql = OracleSupport.oneLine(event.getSql()).toUpperCase(Locale.ROOT);
        if (purpose.startsWith("create-table") || purpose.startsWith("alter-table")
                || sql.startsWith("CREATE TABLE") || sql.startsWith("ALTER TABLE")) {
            return 100;
        }
        if (purpose.startsWith("create") || purpose.startsWith("alter") || purpose.startsWith("drop")
                || purpose.startsWith("truncate") || sql.startsWith("CREATE ") || sql.startsWith("ALTER ")
                || sql.startsWith("DROP ") || sql.startsWith("TRUNCATE")) {
            return 80;
        }
        return 50;
    }

    /* ------------------------------------------------------------------ reporting */

    private OracleFailure unexpectedFailure(OracleContext context, ExecutionEvent event) {
        DatabaseUnderTest database = OracleSupport.database(context, event.getDatabaseName());
        DDLFeatureSet declared = OracleSupport.featuresOf(context, database);
        List<DDLFeature> required = FeaturePurpose.features(event.getPurpose());
        List<DDLFeature> declaredSupported = new ArrayList<>();
        List<DDLFeature> undeclared = new ArrayList<>();
        for (DDLFeature feature : required) {
            if (declared.supports(feature)) {
                declaredSupported.add(feature);
            } else {
                undeclared.add(feature);
            }
        }

        StringBuilder details = new StringBuilder();
        details.append("A statement that the engine should have accepted failed.\n\n");
        details.append("Database   : ").append(databaseName(database, event)).append('\n');
        details.append("Dialect    : ").append(dialectName(database)).append('\n');
        details.append("Purpose    : ").append(FeaturePurpose.base(event.getPurpose())).append('\n');
        details.append("Statement  : ").append(OracleSupport.oneLine(event.getSql())).append('\n');
        details.append("Error      : ").append(OracleSupport.oneLine(event.getErrorMessage())).append('\n');
        details.append("SQLState   : ").append(event.getSqlState()).append('\n');
        details.append("ErrorCode  : ").append(event.getErrorCode()).append('\n');
        details.append("Duration   : ").append(event.getMillis()).append(" ms\n");
        details.append("\nFeature attribution (from purpose[...]):\n");
        details.append("  required by the statement     : ").append(OracleSupport.describeFeatures(required)).append('\n');
        details.append("  declared by the dialect       : ").append(OracleSupport.describeFeatures(declaredSupported))
                .append('\n');
        details.append("  not declared by the dialect   : ").append(OracleSupport.describeFeatures(undeclared))
                .append('\n');
        if (!FeaturePurpose.featureNames(event.getPurpose()).isEmpty()
                && required.isEmpty() && declaredSupported.isEmpty()) {
            details.append("  raw names in purpose          : ")
                    .append(FeaturePurpose.featureNames(event.getPurpose())).append('\n');
        }
        if (!declaredSupported.isEmpty()) {
            details.append("\nThe dialect declares ").append(OracleSupport.describeFeatures(declaredSupported))
                    .append(" as supported, so the engine rejected a construct it claims to implement.\n");
        } else if (!undeclared.isEmpty()) {
            details.append("\nThe dialect does not declare ")
                    .append(OracleSupport.describeFeatures(undeclared))
                    .append("; the generator should not have emitted this statement for this dialect.\n");
        } else {
            details.append("\nThe purpose names no feature, so nothing in the dialect's feature set excuses"
                    + " this failure.\n");
        }

        List<ExecutionEvent> preceding = OracleSupport.preceding(context, event, PRECEDING_STATEMENTS);
        details.append("\nPreceding statements (").append(preceding.size()).append(" of ")
                .append(context.getExecutionEvents(event.getDatabaseName()).size() - 1).append("):\n");
        int index = 1;
        for (ExecutionEvent candidate : preceding) {
            details.append("  ").append(index++).append(". ")
                    .append(FeaturePurpose.base(candidate.getPurpose())).append(": ")
                    .append(OracleSupport.oneLine(candidate.getSql()));
            if (!candidate.isSucceeded()) {
                details.append("   [failed: ").append(OracleSupport.oneLine(candidate.getErrorMessage())).append(']');
            }
            details.append('\n');
        }

        String title = "engine rejected " + FeaturePurpose.base(event.getPurpose())
                + (declaredSupported.isEmpty() ? "" : " although it declares "
                        + String.join(",", names(declaredSupported)));
        String reproducer = OracleSupport.reproducer(context, event.getDatabaseName(), event);
        return new OracleFailure(FailureKind.UNEXPECTED_ERROR, getName(), title,
                OracleSupport.truncate(details.toString()), reproducer, context.getSeed(),
                databaseName(database, event), null);
    }

    private OracleFailure unexpectedSuccess(OracleContext context, ExecutionEvent event) {
        DatabaseUnderTest database = OracleSupport.database(context, event.getDatabaseName());
        StringBuilder details = new StringBuilder();
        details.append("A statement that was expected to be rejected was accepted.\n\n");
        details.append("Database   : ").append(databaseName(database, event)).append('\n');
        details.append("Dialect    : ").append(dialectName(database)).append('\n');
        details.append("Purpose    : ").append(FeaturePurpose.base(event.getPurpose())).append('\n');
        details.append("Statement  : ").append(OracleSupport.oneLine(event.getSql())).append('\n');
        details.append("Duration   : ").append(event.getMillis()).append(" ms\n");
        details.append("\nThe statement violated a constraint that the schema declares, so the engine was"
                + " expected to raise an error. Accepting it means either that the constraint is not enforced"
                + " or that the probe did not violate it; this is a possible engine defect and has to be"
                + " confirmed against the intended schema.\n");
        List<ExecutionEvent> preceding = OracleSupport.preceding(context, event, PRECEDING_STATEMENTS);
        details.append("\nPreceding statements (").append(preceding.size()).append("):\n");
        int index = 1;
        for (ExecutionEvent candidate : preceding) {
            details.append("  ").append(index++).append(". ")
                    .append(FeaturePurpose.base(candidate.getPurpose())).append(": ")
                    .append(OracleSupport.oneLine(candidate.getSql())).append('\n');
        }
        String title = "expected rejection did not happen for " + FeaturePurpose.base(event.getPurpose());
        return new OracleFailure(FailureKind.UNEXPECTED_ERROR, getName(), title,
                OracleSupport.truncate(details.toString()),
                OracleSupport.reproducer(context, event.getDatabaseName(), event), context.getSeed(),
                databaseName(database, event), null);
    }

    private static String databaseName(DatabaseUnderTest database, ExecutionEvent event) {
        return database == null ? event.getDatabaseName() : database.getFullName();
    }

    private static String dialectName(DatabaseUnderTest database) {
        return database == null ? "<unknown>" : database.getDialect().getName();
    }

    private static List<String> names(List<DDLFeature> features) {
        List<String> names = new ArrayList<>();
        for (DDLFeature feature : features) {
            names.add(feature.name());
        }
        return names;
    }
}
