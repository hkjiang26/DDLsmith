package org.ddlsmith.oracle.impl;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateSchema;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.DropSchema;
import org.ddlsmith.ddl.DropTable;
import org.ddlsmith.ddl.DropView;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaNormalizer;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.StatementRunner;
import org.ddlsmith.oracle.TestOracle;

/**
 * Checks the idempotence guarantees that a dialect declares.
 *
 * <p>Each probe is only run when the dialect claims the corresponding feature, and is run twice with
 * the same statement. Both executions must succeed, and for {@code CREATE ... IF NOT EXISTS} the
 * second execution must not change the schema (the catalog is introspected before and after and the
 * two snapshots are compared with {@link SchemaNormalizer#diff(RelationalSchema, RelationalSchema)}).
 * A dialect that declares a feature and then rejects the second execution is contradicted by its own
 * feature set, which is what this oracle reports.</p>
 *
 * <p>The probes use a scratch table built from a small fixed {@link Table} object (an integer primary
 * key, a {@code varchar(20)} with a default that is also {@code NOT NULL}, and a nullable integer) so
 * that the check exercises realistic column definitions. The scratch table lives inside the generated
 * schema under a name ({@code idem_probe_<n>}) that the generator cannot produce, and everything the
 * oracle creates is dropped again at the end.</p>
 *
 * <p>Statement purposes: the probes themselves are recorded as {@code probe-idempotence-...} with
 * {@code expectedToFail = false}, so a failing probe is also visible to the crash oracle (it is an
 * engine error, not a constraint doing its job). The best effort cleanup at the end is recorded with
 * the purpose {@code idempotence-cleanup} and {@code expectedToFail = true}: it deliberately matches
 * neither the crash oracle's probe rule (the purpose does not start with {@code probe-}) nor its
 * unexpected-failure rule, because a leftover scratch object must never turn into a finding of its
 * own.</p>
 */
public class IdempotenceOracle implements TestOracle {

    /** The name used to select this oracle on the command line. */
    public static final String NAME = "idempotence";

    /** The purpose of a best effort cleanup statement (never reported as a failure). */
    private static final String CLEANUP_PURPOSE = "idempotence-cleanup";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "checks that IF [NOT] EXISTS and OR REPLACE statements are idempotent on every database";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        int index = 0;
        for (DatabaseUnderTest database : context.getDatabases()) {
            index++;
            Optional<OracleFailure> failure = verifyDatabase(context, database, index);
            if (failure.isPresent()) {
                return failure;
            }
        }
        return Optional.empty();
    }

    /**
     * A short identifier fragment that makes this database's probe schema its own.
     *
     * <p>A schema cannot be nested inside the generated namespace, so the probe schema name is the one
     * probe object that is global to the server. With {@code --threads} several generated databases
     * probe at the same time, and PostgreSQL's {@code CREATE SCHEMA IF NOT EXISTS} is not atomic: two
     * sessions that both find the schema absent both try to insert it, and one fails with
     * {@code duplicate key value violates unique constraint "pg_namespace_nspname_index"}. The oracle
     * reported that as PostgreSQL not honouring the guarantee it declares, when the real cause was
     * that DDLSmith had asked two concurrent databases about the same name.</p>
     */
    private static String scope(String resolvedSchema, DatabaseUnderTest database) {
        // The name has to be unique per *shared namespace*, because that is where two concurrent
        // generated databases can collide, and the resolved schema is exactly that namespace. Taking the
        // database's own name instead left every comparison database sharing one probe name - a
        // differential run names its participants after the engine, so all of them asked for
        // `idem_schema_postgres_2` and PostgreSQL's non-atomic CREATE SCHEMA IF NOT EXISTS failed for one
        // of them. The database's name remains the fallback where an engine keeps its objects in the
        // connection's default namespace and nothing more specific can be resolved.
        String name = resolvedSchema == null || resolvedSchema.isBlank() ? database.getName() : resolvedSchema;
        if (name == null || name.isBlank()) {
            return "";
        }
        String cleaned = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (cleaned.length() > 24) {
            // keep the tail: the generated database's number is at the end, and truncating from the front
            // is what keeps two of them apart
            cleaned = cleaned.substring(cleaned.length() - 24);
        }
        return cleaned.isEmpty() ? "" : cleaned + "_";
    }

    private Optional<OracleFailure> verifyDatabase(OracleContext context, DatabaseUnderTest database, int index) {
        DDLFeatureSet features = database.getDialect().getFeatures();
        String schemaName = OracleSupport.resolveSchemaName(context, database);
        String tableName = "idem_probe_" + index;
        String indexName = "idem_probe_idx_" + index;
        String viewName = "idem_view_" + index;
        String probeSchemaName = "idem_schema_" + scope(schemaName, database) + index;
        Table scratch = scratchTable(schemaName, tableName);
        boolean schemaCreated = false;
        boolean tableCreated = false;
        boolean viewCreated = false;
        boolean viewRemoved = true;
        try {
            /* ---- CREATE SCHEMA IF NOT EXISTS, twice ---- */
            if (features.supportsAll(DDLFeature.CREATE_SCHEMA, DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS)) {
                String sql = database.getDialect().render(new CreateSchema(probeSchemaName, true));
                Optional<OracleFailure> failure = twice(context, database, sql,
                        "probe-idempotence-create-schema", "CREATE SCHEMA IF NOT EXISTS",
                        "the dialect declares CREATE_SCHEMA_IF_NOT_EXISTS, so creating the schema twice must succeed");
                if (failure.isPresent()) {
                    return failure;
                }
                schemaCreated = true;
            }

            /* ---- CREATE TABLE IF NOT EXISTS, twice, and the second one must not change the schema ---- */
            if (features.supports(DDLFeature.IF_NOT_EXISTS)) {
                String sql = database.getDialect().render(new CreateTable(scratch, true));
                ExecutionEvent first = StatementRunner.execute(context, database, sql,
                        "probe-idempotence-create-table");
                if (!first.isSucceeded()) {
                    return Optional.of(violation(context, database, "CREATE TABLE IF NOT EXISTS",
                            "the dialect declares IF_NOT_EXISTS, so the first execution must succeed", sql, first,
                            "first execution"));
                }
                RelationalSchema before = introspect(database, schemaName);
                ExecutionEvent second = StatementRunner.execute(context, database, sql,
                        "probe-idempotence-create-table");
                if (!second.isSucceeded()) {
                    return Optional.of(violation(context, database, "CREATE TABLE IF NOT EXISTS",
                            "the dialect declares IF_NOT_EXISTS, so the second execution must succeed too", sql,
                            second, "second execution"));
                }
                if (before != null) {
                    RelationalSchema after = introspect(database, schemaName);
                    if (after != null) {
                        List<SchemaDifference> changes = SchemaNormalizer.diff(before, after);
                        if (!changes.isEmpty()) {
                            return Optional.of(schemaChanged(context, database, sql, changes));
                        }
                    }
                }
                tableCreated = true;
            }

            /* ---- CREATE INDEX IF NOT EXISTS, twice ---- */
            if (tableCreated && features.supportsAll(DDLFeature.CREATE_INDEX, DDLFeature.CREATE_INDEX_IF_NOT_EXISTS)) {
                // the table was created in the resolved namespace, so the index must name it there too:
                // an unqualified name would resolve to the engine's default schema and not find it
                String sql = database.getDialect().render(new CreateIndex(
                        OracleSupport.qualifiedName(database, schemaName, tableName),
                        new Index(indexName, List.of("amount")), true));
                Optional<OracleFailure> failure = twice(context, database, sql,
                        "probe-idempotence-create-index", "CREATE INDEX IF NOT EXISTS",
                        "the dialect declares CREATE_INDEX_IF_NOT_EXISTS, so creating the index twice must succeed");
                if (failure.isPresent()) {
                    return failure;
                }
            }

            /* ---- DROP INDEX IF EXISTS, twice ---- */
            if (tableCreated && features.supportsAll(DDLFeature.DROP_INDEX, DDLFeature.DROP_INDEX_IF_EXISTS)) {
                String sql = database.getDialect().render(new DropIndex(indexName,
                        OracleSupport.qualifiedName(database, schemaName, tableName), true, false));
                Optional<OracleFailure> failure = twice(context, database, sql, "probe-idempotence-drop-index",
                        "DROP INDEX IF EXISTS",
                        "the dialect declares DROP_INDEX_IF_EXISTS, so dropping the index twice must succeed");
                if (failure.isPresent()) {
                    return failure;
                }
            }

            /* ---- CREATE VIEW OR REPLACE, twice, and the view must stay in place ---- */
            if (tableCreated && features.supportsAll(DDLFeature.CREATE_VIEW, DDLFeature.CREATE_OR_REPLACE_VIEW)) {
                String tableReference = OracleSupport.qualifiedName(database, schemaName, tableName);
                String sql = database.getDialect().render(new CreateView(
                        new View(viewName, "SELECT id, label FROM " + tableReference), true, false));
                Optional<OracleFailure> failure = twice(context, database, sql, "probe-idempotence-create-view",
                        "CREATE OR REPLACE VIEW",
                        "the dialect declares CREATE_OR_REPLACE_VIEW, so redefining the view must succeed");
                if (failure.isPresent()) {
                    return failure;
                }
                viewCreated = true;
                // CreateView renders the view name unqualified, so the view lives in the engine's default
                // namespace; the verification query uses the same unqualified name on purpose.
                String viewReference = OracleSupport.quote(database, viewName);
                ExecutionEvent check = StatementRunner.execute(context, database,
                        "SELECT COUNT(*) FROM " + viewReference, "probe-idempotence-view-in-place");
                if (!check.isSucceeded()) {
                    return Optional.of(violation(context, database, "CREATE OR REPLACE VIEW",
                            "the second CREATE OR REPLACE VIEW must leave the view in place and queryable",
                            "SELECT COUNT(*) FROM " + viewReference, check, "verification query"));
                }
                viewRemoved = cleanupStatement(context, database, database.getDialect()
                        .render(new DropView(viewName, true, false)));
            }

            /* ---- DROP TABLE IF EXISTS, twice ---- */
            if (tableCreated && features.supports(DDLFeature.DROP_IF_EXISTS)) {
                if (!viewRemoved) {
                    // The scratch view still depends on the scratch table, so dropping the table could
                    // fail for a reason that has nothing to do with idempotence: skip the probe.
                    return Optional.empty();
                }
                String sql = database.getDialect().render(new DropTable(tableName, true, false));
                Optional<OracleFailure> failure = twice(context, database, sql, "probe-idempotence-drop-table",
                        "DROP TABLE IF EXISTS",
                        "the dialect declares DROP_IF_EXISTS, so dropping the table twice must succeed");
                if (failure.isPresent()) {
                    return failure;
                }
            }
        } finally {
            cleanup(context, database, features, tableName, viewName, probeSchemaName, schemaCreated, viewCreated);
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------ probes */

    /** Executes a statement twice; both executions must succeed. */
    private Optional<OracleFailure> twice(OracleContext context, DatabaseUnderTest database, String sql,
            String purpose, String guarantee, String expectation) {
        ExecutionEvent first = StatementRunner.execute(context, database, sql, purpose);
        if (!first.isSucceeded()) {
            return Optional.of(violation(context, database, guarantee, expectation, sql, first, "first execution"));
        }
        ExecutionEvent second = StatementRunner.execute(context, database, sql, purpose);
        if (!second.isSucceeded()) {
            return Optional.of(violation(context, database, guarantee, expectation, sql, second, "second execution"));
        }
        return Optional.empty();
    }

    /** @return the introspection of the database, or {@code null} when it is not available */
    private RelationalSchema introspect(DatabaseUnderTest database, String schemaName) {
        try {
            return database.getIntrospector().introspect(database.getConnection(), schemaName);
        } catch (SQLException e) {
            // Without a catalog snapshot the "schema unchanged" half of the probe cannot be evaluated;
            // the two executions were already checked, so this is not turned into a finding.
            return null;
        }
    }

    /** Runs a best effort statement whose failure must not become a finding. */
    private boolean cleanupStatement(OracleContext context, DatabaseUnderTest database, String sql) {
        return StatementRunner.execute(context, database, sql, true, CLEANUP_PURPOSE).isSucceeded();
    }

    private void cleanup(OracleContext context, DatabaseUnderTest database, DDLFeatureSet features, String tableName,
            String viewName, String probeSchemaName, boolean schemaCreated, boolean viewCreated) {
        if (viewCreated) {
            cleanupStatement(context, database,
                    database.getDialect().render(new DropView(viewName, true, false)));
        }
        if (features.supports(DDLFeature.DROP_IF_EXISTS)) {
            cleanupStatement(context, database,
                    database.getDialect().render(new DropTable(tableName, true, false)));
        }
        if (schemaCreated && features.supports(DDLFeature.DROP_SCHEMA)) {
            cleanupStatement(context, database, database.getDialect()
                    .render(new DropSchema(probeSchemaName, true, features.supports(DDLFeature.DROP_SCHEMA_CASCADE))));
        }
    }

    /**
     * The scratch table: three columns that cover the interesting column definition paths, and a
     * primary key so that the table is a realistic DDLSmith table.
     */
    private static Table scratchTable(String schemaName, String tableName) {
        Table table = new Table(schemaName, tableName);
        table.addColumn(new Column("id", DataType.integer(), false));
        table.addColumn(new Column("label", DataType.varchar(20), false).setDefaultExpression("'probe'"));
        table.addColumn(new Column("amount", DataType.integer(), true));
        table.setPrimaryKey(new PrimaryKey(List.of("id")));
        return table;
    }

    /* ------------------------------------------------------------------ reporting */

    private OracleFailure violation(OracleContext context, DatabaseUnderTest database, String guarantee,
            String expectation, String sql, ExecutionEvent event, String which) {
        StringBuilder details = new StringBuilder();
        details.append("An idempotence guarantee the dialect declares was not honoured.\n\n");
        details.append("Database   : ").append(database.getFullName()).append('\n');
        details.append("Dialect    : ").append(database.getDialect().getName()).append('\n');
        details.append("Guarantee  : ").append(guarantee).append('\n');
        details.append("Expectation: ").append(expectation).append('\n');
        details.append("Statement  : ").append(OracleSupport.oneLine(sql)).append('\n');
        details.append("Execution  : ").append(which).append('\n');
        details.append("Error      : ").append(OracleSupport.oneLine(event.getErrorMessage())).append('\n');
        details.append("SQLState   : ").append(event.getSqlState()).append('\n');
        details.append("ErrorCode  : ").append(event.getErrorCode()).append('\n');
        details.append("Purpose    : ").append(FeaturePurpose.base(event.getPurpose())).append('\n');
        details.append("\nThe dialect declares the feature this statement uses, so the statement must be"
                + " accepted; either the feature set overstates what the engine supports, or the engine's"
                + " implementation is broken.\n");
        appendPreceding(details, context, database.getName());
        String title = guarantee + " is not idempotent on " + database.getName();
        return new OracleFailure(FailureKind.IDEMPOTENCE, getName(), title,
                OracleSupport.truncate(details.toString()),
                OracleSupport.reproducer(context, database.getName(), event), context.getSeed(),
                database.getFullName(), null);
    }

    private OracleFailure schemaChanged(OracleContext context, DatabaseUnderTest database, String sql,
            List<SchemaDifference> changes) {
        StringBuilder details = new StringBuilder();
        details.append("The second execution of an IF NOT EXISTS statement changed the schema.\n\n");
        details.append("Database   : ").append(database.getFullName()).append('\n');
        details.append("Dialect    : ").append(database.getDialect().getName()).append('\n');
        details.append("Guarantee  : CREATE TABLE IF NOT EXISTS\n");
        details.append("Expectation: the second execution must be a no-op, because the table already exists\n");
        details.append("Statement  : ").append(OracleSupport.oneLine(sql)).append('\n');
        details.append("\nSchema changes observed by the introspector (").append(changes.size()).append("):\n");
        details.append(SchemaNormalizer.render(changes));
        appendPreceding(details, context, database.getName());
        String title = "CREATE TABLE IF NOT EXISTS changed the schema on " + database.getName();
        return new OracleFailure(FailureKind.IDEMPOTENCE, getName(), title,
                OracleSupport.truncate(details.toString()),
                OracleSupport.reproducer(context, database.getName(), null), context.getSeed(),
                database.getFullName(), null);
    }

    private void appendPreceding(StringBuilder details, OracleContext context, String databaseName) {
        List<ExecutionEvent> events = context.getExecutionEvents(databaseName);
        int from = Math.max(0, events.size() - 5);
        details.append("\nPreceding statements (last ").append(events.size() - from).append("):\n");
        int index = 1;
        for (ExecutionEvent candidate : events.subList(from, events.size())) {
            details.append("  ").append(index++).append(". ")
                    .append(FeaturePurpose.base(candidate.getPurpose())).append(": ")
                    .append(OracleSupport.oneLine(candidate.getSql())).append('\n');
        }
    }
}
