package org.ddlsmith.oracle.impl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.query.QueryResult;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.data.CheckExpressionAnalyzer;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.data.RowGenerator;
import org.ddlsmith.data.RowGenerator.ConstraintRef;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.StatementRunner;
import org.ddlsmith.oracle.TestOracle;

/**
 * Proves whether the constraints that the ER diagram declares are actually enforced by the engine.
 *
 * <p>The oracle works in two phases per database:</p>
 * <ol>
 *   <li><em>Population.</em> A fresh {@link RowGenerator} produces satisfying rows for every table
 *       the dialect can express, inserted in {@link RowGenerator#insertionOrder()}. Every INSERT is
 *       recorded as an {@code ExecutionEvent} with {@code purpose = "probe-insert"} and
 *       {@code expectedToFail = false}, so that the crash oracle does not report it a second time.
 *       A satisfying row that the engine rejects is a finding of kind
 *       {@link FailureKind#CONSTRAINT_TOO_STRICT}.</li>
 *   <li><em>Violation probes.</em> A bounded number of constraints (at most
 *       {@value #MAX_CONSTRAINT_PROBES} per database, chosen with {@link Randomly}) are probed with
 *       a row that violates exactly that constraint. The statement is recorded with
 *       {@code expectedToFail = true}; if the engine accepts it anyway
 *       ({@link ExecutionEvent#isUnexpectedSuccess()}) the constraint is declared but not
 *       enforced, which is reported as {@link FailureKind#CONSTRAINT_NOT_ENFORCED}.</li>
 * </ol>
 *
 * <h2>False-positive guards (all of them are deliberate, none of them weakens a real finding)</h2>
 * <ul>
 *   <li><b>UNIQUE / PRIMARY KEY probes never use NULL.</b> SQL treats NULLs as distinct in a unique
 *       index, so {@code INSERT}ing a second NULL is <em>legitimately</em> accepted. The probe is
 *       additionally verified against the database itself ({@code SELECT COUNT(*) ... WHERE key =
 *       value} must be non-zero) so that a key value which was never actually stored - for instance
 *       because the row it came from was itself rejected, or because the engine coerced it - is
 *       never used as the "duplicate".</li>
 *   <li><b>CHECK probes must evaluate to FALSE, not UNKNOWN.</b> A CHECK constraint is satisfied
 *       when its expression evaluates to UNKNOWN, which happens whenever an operand is NULL. A
 *       generated violating row is therefore only used when at least one conjunct is provably
 *       FALSE: the operand must be non-NULL (except for {@code IS NOT NULL}, where NULL is a
 *       genuine FALSE), and no conjunct may compare against a NULL literal (for instance
 *       {@code c IN (1, NULL)} is UNKNOWN for {@code c = 2}, not FALSE).</li>
 *   <li><b>NOT NULL probes insert an explicit NULL.</b> The generated row keeps the column in the
 *       INSERT column list with the value {@code NULL}; the column is never omitted, because
 *       omitting it would apply the column DEFAULT and no violation would occur.</li>
 *   <li><b>Satisfying rows are validated by the oracle before the engine is blamed.</b> If the row
 *       generator could not actually satisfy the declared NOT NULL and CHECK constraints of a table
 *       (for example {@code CHECK (c IS NULL)} on a non-nullable column, which the generator cannot
 *       satisfy), the rejected INSERT is recorded as a note instead of a finding.</li>
 *   <li><b>Foreign-key probes are verified against the parent table.</b> The dangling value must not
 *       exist in the referenced parent columns; otherwise the "violation" would not be one (this
 *       matters for small value domains such as a BOOLEAN key, where the generator's dangling value
 *       could coincide with a real parent key).</li>
 *   <li><b>Tables whose dialect features are not declared are skipped</b>, and tables whose parent
 *       table could not be populated are skipped as well, because their INSERTs would fail for a
 *       reason that has nothing to do with the probed constraint.</li>
 * </ul>
 *
 * <p>The interface returns a single {@link OracleFailure}. Findings are therefore grouped by
 * (database, constraint type) and reported once per group, with every occurrence counted and the
 * first one described in full.</p>
 */
public class ConstraintEnforcementOracle implements TestOracle {

    public static final String NAME = "constraint-enforcement";
    /** Upper bound on the number of violating rows executed per database. */
    public static final int MAX_CONSTRAINT_PROBES = 25;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Checks that every declared PRIMARY KEY, UNIQUE, NOT NULL, CHECK and FOREIGN KEY "
                + "constraint is really enforced by rejecting a row that violates it.";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        RelationalSchema schema = context.getIntendedSchema();
        if (schema == null || schema.getTables().isEmpty()) {
            return Optional.empty();
        }
        List<Support.Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (DatabaseUnderTest database : context.getDatabases()) {
            findings.addAll(verifyDatabase(context, database, schema, notes));
        }
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Support.toFailure(NAME, context, findings, notes));
    }

    private List<Support.Finding> verifyDatabase(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema, List<String> notes) {
        List<Support.Finding> findings = new ArrayList<>();
        Support.Population population = Support.populate(context, database, schema, 2, 6);
        notes.addAll(population.notes);
        for (Support.RejectedRow rejected : population.rejected) {
            if (violatesAConstraintTheRowGeneratorDoesNotModel(rejected)) {
                // The row generator models NOT NULL and CHECK, not uniqueness and not references, so a
                // row it believes is valid can still repeat a unique value or point at a parent that
                // does not exist. The engine is right to reject it: this is a limitation of DDLsmith's
                // row generation, and reporting it as "the engine is too strict" filed an engine
                // defect for DDLsmith's own mistake (79 occurrences in one hour of SQLite runs).
                notes.add("[" + database.getName() + "] " + rejected.table.getName()
                        + " could not be populated: the engine rejected the row for a constraint the row "
                        + "generator does not model, so it is not a finding about the engine ("
                        + describeRejection(rejected) + ")");
                continue;
            }
            findings.add(Support.Finding.of(FailureKind.CONSTRAINT_TOO_STRICT, database.getName(), "insert",
                    "a row satisfying every declared constraint was rejected",
                    "Table " + rejected.table.getName() + " could not be populated.\n"
                            + "The row below satisfies every NOT NULL and CHECK constraint of the table as "
                            + "evaluated by DDLsmith, yet the engine rejected it.\n"
                            + "Row: " + org.ddlsmith.graph.GraphValues.format(rejected.row) + "\n"
                            + "Observed: " + StatementRunner.describe(rejected.event) + "\n"
                            + "SQL: " + rejected.sql,
                    rejected.sql));
        }
        findings.addAll(probeConstraints(context, database, schema, population, notes));
        return findings;
    }

    /**
     * @return true when the engine rejected a generated row for a constraint the row generator does
     *         not model, which makes the rejection correct behaviour rather than a finding.
     */
    private static boolean violatesAConstraintTheRowGeneratorDoesNotModel(Support.RejectedRow rejected) {
        String message = describeRejection(rejected);
        String upper = message.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("UNIQUE") || upper.contains("DUPLICATE") || upper.contains("FOREIGN KEY")
                || upper.contains("PRIMARY KEY") || upper.contains("REFERENTIAL");
    }

    private static String describeRejection(Support.RejectedRow rejected) {
        String message = rejected.event == null ? null : rejected.event.getErrorMessage();
        return message == null ? "" : message;
    }

    private List<Support.Finding> probeConstraints(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema, Support.Population population, List<String> notes) {        List<Support.Finding> findings = new ArrayList<>();
        Dialect dialect = database.getDialect();
        Set<String> unusable = new LinkedHashSet<>();
        for (Table table : population.skipped) {
            unusable.add(Support.normalize(table.getName()));
        }

        List<ConstraintRef> candidates = new ArrayList<>();
        for (ConstraintRef constraint : population.generator.collectProbeableConstraints()) {
            String reason = probeExclusion(constraint, population, unusable, dialect);
            if (reason == null) {
                candidates.add(constraint);
            } else {
                notes.add("[" + database.getName() + "] excluded probe " + constraint + ": " + reason);
            }
        }
        if (candidates.isEmpty()) {
            return findings;
        }
        Randomly.shuffle(candidates);
        int limit = Math.min(candidates.size(), MAX_CONSTRAINT_PROBES);
        if (candidates.size() > limit) {
            notes.add("[" + database.getName() + "] only " + limit + " of " + candidates.size()
                    + " probeable constraints were probed (the cap is " + MAX_CONSTRAINT_PROBES + " per database)");
        }
        for (int i = 0; i < limit; i++) {
            Support.Finding finding = probeOne(context, database, population, candidates.get(i), notes);
            if (finding != null) {
                findings.add(finding);
            }
        }
        return findings;
    }

    /**
     * @return the reason why a constraint must not be probed, or null when it may be probed
     */
    private String probeExclusion(ConstraintRef constraint, Support.Population population, Set<String> unusable,
            Dialect dialect) {
        Table table = constraint.table;
        if (unusable.contains(Support.normalize(table.getName()))) {
            return "the table could not be populated, so a violating row cannot be attributed to this constraint";
        }
        if (!Support.tableSupported(table, dialect)) {
            return "the dialect does not declare all features this table uses";
        }
        switch (constraint.type) {
        case CHECK:
            return checkProbeExclusion(constraint);
        case NOT_NULL: {
            Column column = table.getColumn(constraint.columns.get(0));
            if (column == null) {
                return "the column is not part of the table";
            }
            if (column.isIdentity()) {
                return "an IDENTITY column may legitimately assign a value when NULL is inserted";
            }
            if (Support.isSoleIntegralPrimaryKey(column, table)) {
                // SQLite (and MySQL) treat an integral single-column primary key as a rowid/AUTO_INCREMENT
                // alias: inserting an explicit NULL assigns a fresh key instead of storing NULL.
                return "a single-column integral primary key may be auto-assigned when NULL is inserted";
            }
            return null;
        }
        case FOREIGN_KEY: {
            Table parent = population.generator.getModel().getSchema()
                    .getTable(constraint.foreignKey.getReferencedTable());
            if (parent == null) {
                return "the referenced table is not part of the schema";
            }
            if (unusable.contains(Support.normalize(parent.getName()))) {
                return "the referenced table could not be populated, so the child insert would fail for "
                        + "an unrelated reason";
            }
            return null;
        }
        default:
            return null;
        }
    }

    private String checkProbeExclusion(ConstraintRef constraint) {
        String expression = constraint.expression;
        if (expression == null) {
            return "the constraint has no expression";
        }
        boolean understood = false;
        for (String conjunct : CheckExpressionAnalyzer.splitConjuncts(expression)) {
            CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse(conjunct);
            if (!analyzer.isUnderstood()) {
                continue;
            }
            understood = true;
            if (Support.hasNullLiteral(analyzer)) {
                // c IN (1, NULL) and c > NULL evaluate to UNKNOWN for most operands, and a CHECK that
                // evaluates to UNKNOWN is satisfied, so accepting the row would be correct behaviour.
                return "the expression compares against a NULL literal, so a violation would only be "
                        + "UNKNOWN (which a CHECK constraint accepts)";
            }
        }
        if (!understood) {
            return "DDLsmith does not understand the expression, so it cannot construct a violating row";
        }
        return null;
    }

    private Support.Finding probeOne(OracleContext context, DatabaseUnderTest database,
            Support.Population population, ConstraintRef constraint, List<String> notes) {
        Table table = constraint.table;
        Dialect dialect = database.getDialect();
        String purpose = "probe-" + constraint.type.name().toLowerCase(Locale.ROOT);
        Map<String, Object> row;
        try {
            row = population.generator.generateViolatingRow(table, constraint);
        } catch (RuntimeException e) {
            notes.add("[" + database.getName() + "] excluded probe " + constraint
                    + ": the row generator failed (" + e + ")");
            return null;
        }
        if (row == null || row.isEmpty()) {
            notes.add("[" + database.getName() + "] excluded probe " + constraint
                    + ": no violating row could be constructed");
            return null;
        }
        String sql = population.generator.toInsertSql(table, row, Set.of());
        String exclusion = verifyViolationIsReal(context, database, population, constraint, row, sql);
        if (exclusion != null) {
            notes.add("[" + database.getName() + "] excluded probe " + constraint + ": " + exclusion);
            return null;
        }
        ExecutionEvent event = StatementRunner.execute(context, database, sql, true, purpose);
        if (!event.isUnexpectedSuccess()) {
            return null;
        }
        String columns = constraint.columns.isEmpty() ? "<expression>" : constraint.columns.toString();
        String details = "The constraint below is declared by the generated schema but the engine accepted a row "
                + "that violates it.\n"
                + "Constraint : " + constraint + "\n"
                + "Table      : " + table.getName() + "\n"
                + "Columns    : " + columns + "\n"
                + "Row        : " + row + "\n"
                + "SQL        : " + sql + "\n"
                + "Observed   : the row was inserted successfully (" + StatementRunner.describe(event) + ")";
        return Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED, database.getName(),
                constraint.type.name(), "declared " + constraint.type + " constraint is not enforced", details, sql);
    }

    /**
     * @return a reason why the generated row is not a genuine violation of the probed constraint, or
     *         null when the probe may be executed
     */
    private String verifyViolationIsReal(OracleContext context, DatabaseUnderTest database,
            Support.Population population, ConstraintRef constraint, Map<String, Object> row, String sql) {
        Dialect dialect = database.getDialect();
        Table table = constraint.table;
        switch (constraint.type) {
        case PRIMARY_KEY:
        case UNIQUE: {
            for (String column : constraint.columns) {
                if (!Support.hasKey(row, column) || DataModel.getValue(row, column) == null) {
                    return "the duplicate key contains a NULL value, and NULLs are distinct in a unique index";
                }
            }
            String where = Support.whereEquals(dialect, table, constraint.columns, row);
            Long count = Support.countWhere(context, database, table.getName(), where, "probe-verify-duplicate");
            if (count == null) {
                return "the existence of the duplicated key could not be verified against the database";
            }
            if (count == 0L) {
                return "no stored row actually has this key, so the row is not a duplicate";
            }
            return null;
        }
        case NOT_NULL: {
            String column = constraint.columns.get(0);
            if (!Support.hasKey(row, column) || DataModel.getValue(row, column) != null) {
                return "the generated row does not carry an explicit NULL for the column";
            }
            if (!sql.contains(dialect.quote(column))) {
                return "the generated INSERT omits the column, so the DEFAULT would apply instead of storing NULL";
            }
            return null;
        }
        case CHECK: {
            if (!Support.checkGenuinelyViolated(row, constraint.expression)) {
                return "the generated row does not make the CHECK expression evaluate to FALSE "
                        + "(it is satisfied or UNKNOWN, which a CHECK constraint accepts)";
            }
            return null;
        }
        case FOREIGN_KEY: {
            ForeignKey foreignKey = constraint.foreignKey;
            for (String column : foreignKey.getColumns()) {
                if (!Support.hasKey(row, column) || DataModel.getValue(row, column) == null) {
                    return "the generated row does not carry a non-NULL dangling foreign key value";
                }
            }
            Table parent = population.generator.getModel().getSchema().getTable(foreignKey.getReferencedTable());
            if (parent == null) {
                return "the referenced table is not part of the schema";
            }
            Map<String, Object> referenced = new LinkedHashMap<>();
            for (int i = 0; i < foreignKey.getColumns().size(); i++) {
                String childColumn = foreignKey.getColumns().get(i);
                String parentColumn = foreignKey.getReferencedColumns().size() > i
                        ? foreignKey.getReferencedColumns().get(i)
                        : childColumn;
                referenced.put(parentColumn, DataModel.getValue(row, childColumn));
            }
            String where = Support.whereEquals(dialect, parent, foreignKey.getReferencedColumns(), referenced);
            Long count = Support.countWhere(context, database, parent.getName(), where, "probe-verify-dangling");
            if (count == null) {
                return "the absence of the dangling value in the parent table could not be verified";
            }
            if (count > 0L) {
                return "a parent row with this key already exists, so the foreign key is satisfied";
            }
            return null;
        }
        default:
            return null;
        }
    }

    /* ================================================================== shared probe support */

    /**
     * Plumbing shared by the constraint, referential-action and pivot-row oracles: feature gating,
     * schema population with a {@link RowGenerator}, small SQL helpers and the aggregation of
     * findings into a single {@link OracleFailure}.
     *
     * <p>It is nested here so that the oracle package contains exactly the agreed class names.</p>
     */
    public static final class Support {

        private Support() {
        }

        /* ---------------------------------------------------------------- population */

        /** The result of populating a schema: what was stored, and what had to be skipped. */
        public static final class Population {
            public final RowGenerator generator;
            /** Contains only the rows that the engine actually stored. */
            public final DataModel model;
            public final List<Table> populated = new ArrayList<>();
            public final List<Table> skipped = new ArrayList<>();
            public final List<String> notes = new ArrayList<>();
            public final List<RejectedRow> rejected = new ArrayList<>();
            /** Successfully stored rows per (lower-cased) table name. */
            public final Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
            /** Tables that could not be populated, including the ones skipped because of a parent. */
            public final Set<String> failed = new LinkedHashSet<>();
            /** Tables that were emptied before populating, when that was asked for. */
            public final Set<String> reset = new LinkedHashSet<>();

            Population(RowGenerator generator, DataModel model) {
                this.generator = generator;
                this.model = model;
            }

            public List<Map<String, Object>> rowsOf(String tableName) {
                return rows.getOrDefault(normalize(tableName), List.of());
            }
        }

        /** A row that satisfies every declared constraint but that the engine rejected. */
        public static final class RejectedRow {
            public final Table table;
            public final Map<String, Object> row;
            public final String sql;
            public final ExecutionEvent event;

            RejectedRow(Table table, Map<String, Object> row, String sql, ExecutionEvent event) {
                this.table = table;
                this.row = row;
                this.sql = sql;
                this.event = event;
            }
        }

        /**
         * Inserts between {@code minRows} and {@code maxRows - 1} satisfying rows per table, in an
         * order that lets every foreign key point at an existing row.
         *
         * <p>Every INSERT is recorded with {@code expectedToFail = false}. Only rows that were really
         * stored end up in {@link Population#model}, so an oracle that computes expected query results
         * from the model never expects a row the engine does not have.</p>
         */
        public static Population populate(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
                int minRows, int maxRows) {
            return populate(context, database, schema, minRows, maxRows, false);
        }

        /**
         * Inserts between {@code minRows} and {@code maxRows - 1} satisfying rows per table, in an
         * order that lets every foreign key point at an existing row.
         *
         * <p>Every INSERT is recorded with {@code expectedToFail = false}. Only rows that were really
         * stored end up in {@link Population#model}, so an oracle that computes expected query results
         * from the model never expects a row the engine does not have.</p>
         *
         * @param resetTables when true, every table of the schema is emptied first (children before
         *        parents), and a table whose rows could not be deleted is skipped. An oracle that
         *        compares a query result with the rows it inserted itself is only right if those are
         *        the only rows in the table, and the data oracles share one database: the algorithm's
         *        own seed rows and the rows of every oracle that ran before are still there. Without
         *        this, {@code pivot-row} compared its one inserted row against eight stored ones and
         *        reported the engine for returning the truth. The oracles that probe constraints are
         *        deliberately left alone: they expect the engine to reject rows, so rows already
         *        present cannot make them expect something that is not there.</p>
         */
        public static Population populate(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
                int minRows, int maxRows, boolean resetTables) {
            Dialect dialect = database.getDialect();
            Population population = new Population(new RowGenerator(schema, dialect), new DataModel(schema));
            if (resetTables) {
                clearRows(context, database, population);
            }
            for (Table table : population.generator.insertionOrder()) {
                String name = normalize(table.getName());
                if (resetTables && !population.reset.contains(name)) {
                    population.skipped.add(table);
                    population.failed.add(name);
                    continue;
                }
                List<String> missing = unsupportedFeatures(table, dialect);
                if (!missing.isEmpty()) {
                    population.skipped.add(table);
                    population.failed.add(name);
                    population.notes.add("skipped table " + table.getName() + ": the dialect does not declare "
                            + missing);
                    continue;
                }
                String blockedBy = referencesFailedTable(table, population.failed);
                if (blockedBy != null) {
                    population.skipped.add(table);
                    population.failed.add(name);
                    population.notes.add("skipped table " + table.getName() + ": it references " + blockedBy
                            + ", which could not be populated");
                    continue;
                }
                seedSelfReferentialRow(context, database, schema, population, table);
                int wanted = minRows >= maxRows ? minRows : Randomly.getInteger(minRows, maxRows);
                boolean stored = false;
                for (int i = 0; i < wanted; i++) {
                    Map<String, Object> row;
                    try {
                        row = population.generator.generateRow(table);
                    } catch (RuntimeException e) {
                        population.notes.add("skipped table " + table.getName() + ": the row generator failed ("
                                + e + ")");
                        break;
                    }
                    if (row == null || row.isEmpty()) {
                        population.notes.add("skipped table " + table.getName()
                                + ": no satisfying row could be generated (for instance a mandatory foreign key "
                                + "has no parent row yet)");
                        break;
                    }
                    if (!rowSatisfiesIntendedConstraints(table, row)) {
                        population.notes.add("skipped table " + table.getName() + ": the generated row "
                                + row + " does not satisfy the declared NOT NULL/CHECK constraints itself, so a "
                                + "rejection by the engine would not be attributable to the engine");
                        break;
                    }
                    String sql = population.generator.toInsertSql(table, row, Set.of());
                    ExecutionEvent event = StatementRunner.execute(context, database, sql, false, "probe-insert");
                    if (!event.isSucceeded()) {
                        if (hasIdentityColumn(table)) {
                            // RowGenerator always emits an explicit value for an identity column. Engines
                            // that implement GENERATED ALWAYS AS IDENTITY reject that unless the statement
                            // says OVERRIDING SYSTEM VALUE, which the contract's INSERT rendering never
                            // emits, so the rejection is a limitation of the generator and not a defect of
                            // the engine. The table is skipped instead of being reported.
                            population.notes.add("skipped table " + table.getName()
                                    + ": the INSERT carrying an explicit value for an IDENTITY column was "
                                    + "rejected (" + event.getErrorMessage() + "); the rejection is attributed to "
                                    + "the INSERT rendering, not to the engine");
                            break;
                        }
                        population.rejected.add(new RejectedRow(table, row, sql, event));
                        break;
                    }
                    stored = true;
                    population.rows.computeIfAbsent(name, key -> new ArrayList<>()).add(new LinkedHashMap<>(row));
                    population.model.addRow(name, new LinkedHashMap<>(row));
                }
                if (stored) {
                    population.populated.add(table);
                } else {
                    population.failed.add(name);
                    population.skipped.add(table);
                }
            }
            return population;
        }

        /**
         * Deletes every row of every table of the schema, children before parents, and records which
         * tables are now empty.
         *
         * <p>Reverse insertion order is what makes the deletes legal: a parent cannot be emptied while
         * a child row still points at it. A table whose rows could not be removed is reported through
         * {@link Population#reset} (by its absence) and a note, and the caller must not compare a
         * query result against a model of that table — an expectation that cannot be computed is not
         * evidence of anything.</p>
         *
         * <p>The statements carry a {@code probe-} purpose, so the crash oracle treats a rejection as
         * a limitation of this cleanup rather than as an engine defect.</p>
         */
        private static void clearRows(OracleContext context, DatabaseUnderTest database, Population population) {
            Dialect dialect = database.getDialect();
            List<Table> order = new ArrayList<>(population.generator.insertionOrder());
            java.util.Collections.reverse(order);
            for (Table table : order) {
                String name = normalize(table.getName());
                String sql = "DELETE FROM " + dialect.quote(table.getName());
                ExecutionEvent event = StatementRunner.execute(context, database, sql, false, "probe-reset");
                if (event.isSucceeded()) {
                    population.reset.add(name);
                } else {
                    population.notes.add("could not empty " + table.getName() + " before populating it ("
                            + event.getErrorMessage() + "), so nothing is compared against a model of it");
                }
            }
        }

        /** @return true when the table declares an IDENTITY column */
        public static boolean hasIdentityColumn(Table table) {
            for (Column column : table.getColumns()) {
                if (column.isIdentity()) {
                    return true;
                }
            }
            return false;
        }

        private static String referencesFailedTable(Table table, Set<String> failed) {
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                String parent = normalize(foreignKey.getReferencedTable());
                if (parent.equals(normalize(table.getName()))) {
                    continue;
                }
                if (failed.contains(parent)) {
                    return foreignKey.getReferencedTable();
                }
            }
            return null;
        }

        /**
         * Inserts one seed row for a self-referencing table.
         *
         * <p>{@link RowGenerator#generateRow} resolves every foreign key by picking an already
         * inserted parent row and gives up when there is none, so it cannot produce the
         * <em>first</em> row of a self-referencing table - not even when the self-referencing columns
         * are nullable and NULL would be a legal value. Without a seed row such a table is skipped
         * entirely, which loses all coverage of recursive relationships.</p>
         *
         * <p>The workaround keeps the contract untouched: a copy of the schema with the
         * self-references removed is used to generate one row (its other foreign keys are resolved
         * against the rows already inserted), the self-referencing columns are then set to NULL, and
         * the row is registered in the real generator's model so that every later row can reference
         * it normally. A mandatory (NOT NULL) self-reference has no legal first row at all, so the
         * table is left to be skipped.</p>
         */
        private static void seedSelfReferentialRow(OracleContext context, DatabaseUnderTest database,
                RelationalSchema schema, Population population, Table table) {
            List<ForeignKey> selfReferences = new ArrayList<>();
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                if (normalize(foreignKey.getReferencedTable()).equals(normalize(table.getName()))) {
                    selfReferences.add(foreignKey);
                }
            }
            if (selfReferences.isEmpty() || !population.model.getRows(table.getName()).isEmpty()) {
                return;
            }
            for (ForeignKey foreignKey : selfReferences) {
                for (String column : foreignKey.getColumns()) {
                    Column definition = table.getColumn(column);
                    if (definition != null && !definition.isNullable()) {
                        return;
                    }
                }
            }
            RelationalSchema relaxed = schema.copy();
            Table relaxedTable = relaxed.getTable(table.getName());
            relaxedTable.getForeignKeys().removeIf(
                    foreignKey -> normalize(foreignKey.getReferencedTable()).equals(normalize(table.getName())));
            RowGenerator seedGenerator = new RowGenerator(relaxed, database.getDialect());
            for (Table other : relaxed.getTables()) {
                for (Map<String, Object> row : population.model.getRows(other.getName())) {
                    seedGenerator.getModel().addRow(other.getName(), new LinkedHashMap<>(row));
                }
            }
            Map<String, Object> row;
            try {
                row = seedGenerator.generateRow(relaxedTable);
            } catch (RuntimeException e) {
                return;
            }
            if (row == null || row.isEmpty()) {
                return;
            }
            for (ForeignKey foreignKey : selfReferences) {
                for (String column : foreignKey.getColumns()) {
                    row.put(column, null);
                }
            }
            if (!rowSatisfiesIntendedConstraints(table, row)) {
                return;
            }
            String sql = seedGenerator.toInsertSql(relaxedTable, row, Set.of());
            ExecutionEvent event = StatementRunner.execute(context, database, sql, false, "probe-insert");
            if (!event.isSucceeded()) {
                population.rejected.add(new RejectedRow(table, row, sql, event));
                return;
            }
            population.rows.computeIfAbsent(normalize(table.getName()), key -> new ArrayList<>())
                    .add(new LinkedHashMap<>(row));
            population.model.addRow(table.getName(), new LinkedHashMap<>(row));
            population.generator.getModel().addRow(table.getName(), new LinkedHashMap<>(row));
            population.notes.add("inserted one seed row with NULL self-references into " + table.getName()
                    + " so that the recursive relationship can be populated");
        }

        /* ---------------------------------------------------------------- feature gating */

        /** @return true when the dialect declares every DDL feature the table needs */
        public static boolean tableSupported(Table table, Dialect dialect) {
            return unsupportedFeatures(table, dialect).isEmpty();
        }

        /** @return the names of the features the table needs but the dialect does not declare */
        public static List<String> unsupportedFeatures(Table table, Dialect dialect) {
            DDLFeatureSet features = dialect.getFeatures();
            List<String> missing = new ArrayList<>();
            if (table.getPrimaryKey() != null) {
                if (table.getPrimaryKey().isComposite() && !features.supports(DDLFeature.COMPOSITE_PRIMARY_KEY)) {
                    missing.add("COMPOSITE_PRIMARY_KEY");
                }
                if (table.getPrimaryKey().getName() != null && !features.supports(DDLFeature.NAMED_PRIMARY_KEY)) {
                    missing.add("NAMED_PRIMARY_KEY");
                }
            }
            for (UniqueConstraint unique : table.getUniqueConstraints()) {
                if (unique.getColumns().size() > 1 && !features.supports(DDLFeature.COMPOSITE_UNIQUE)) {
                    missing.add("COMPOSITE_UNIQUE");
                }
                if (unique.getName() != null && !features.supports(DDLFeature.NAMED_UNIQUE)) {
                    missing.add("NAMED_UNIQUE");
                }
            }
            for (CheckConstraint check : table.getCheckConstraints()) {
                if (check.getName() != null && !features.supports(DDLFeature.NAMED_CHECK)) {
                    missing.add("NAMED_CHECK");
                }
            }
            for (Column column : table.getColumns()) {
                if (!column.isNullable() && !features.supports(DDLFeature.NOT_NULL_COLUMN)) {
                    missing.add("NOT_NULL_COLUMN");
                }
                if (column.hasDefault()) {
                    if (isCurrentTimestamp(column.getDefaultExpression())) {
                        if (!features.supports(DDLFeature.DEFAULT_CURRENT_TIMESTAMP)) {
                            missing.add("DEFAULT_CURRENT_TIMESTAMP");
                        }
                    } else if (CheckExpressionAnalyzer.parseLiteral(column.getDefaultExpression()) != null
                            || "null".equalsIgnoreCase(column.getDefaultExpression().trim())) {
                        if (!features.supports(DDLFeature.DEFAULT_LITERAL)) {
                            missing.add("DEFAULT_LITERAL");
                        }
                    } else if (!features.supports(DDLFeature.DEFAULT_EXPRESSION)) {
                        missing.add("DEFAULT_EXPRESSION");
                    }
                }
                if (column.isIdentity() && !features.supports(DDLFeature.IDENTITY_COLUMN)
                        && !features.supports(DDLFeature.AUTO_INCREMENT) && !features.supports(DDLFeature.SERIAL)) {
                    missing.add("IDENTITY_COLUMN");
                }
                if (column.isGenerated() && !features.supports(DDLFeature.GENERATED_COLUMN)) {
                    missing.add("GENERATED_COLUMN");
                }
                if (column.isUnique() && !features.supports(DDLFeature.INLINE_UNIQUE)) {
                    missing.add("INLINE_UNIQUE");
                }
                if (column.getCheckExpression() != null && !features.supports(DDLFeature.INLINE_CHECK)) {
                    missing.add("INLINE_CHECK");
                }
                missing.addAll(unsupportedType(column.getType(), features));
            }
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                if (!features.supports(DDLFeature.TABLE_LEVEL_FOREIGN_KEY)
                        && !features.supports(DDLFeature.INLINE_FOREIGN_KEY)) {
                    missing.add("TABLE_LEVEL_FOREIGN_KEY");
                }
                if (foreignKey.isComposite() && !features.supports(DDLFeature.COMPOSITE_FOREIGN_KEY)) {
                    missing.add("COMPOSITE_FOREIGN_KEY");
                }
                if (foreignKey.getName() != null && !features.supports(DDLFeature.NAMED_FOREIGN_KEY)) {
                    missing.add("NAMED_FOREIGN_KEY");
                }
                if (foreignKey.getOnDelete() != org.ddlsmith.common.schema.ReferentialAction.NO_ACTION
                        && !features.supports(DDLFeature.FK_ON_DELETE)) {
                    missing.add("FK_ON_DELETE");
                }
                if (foreignKey.getOnUpdate() != org.ddlsmith.common.schema.ReferentialAction.NO_ACTION
                        && !features.supports(DDLFeature.FK_ON_UPDATE)) {
                    missing.add("FK_ON_UPDATE");
                }
                if (foreignKey.isDeferrable() && !features.supports(DDLFeature.DEFERRABLE_FK)) {
                    missing.add("DEFERRABLE_FK");
                }
            }
            return missing;
        }

        private static List<String> unsupportedType(DataType type, DDLFeatureSet features) {
            if (type == null) {
                return List.of();
            }
            List<String> missing = new ArrayList<>();
            DDLFeature required = null;
            switch (type.getCategory()) {
            case BOOLEAN:
                required = DDLFeature.TYPE_BOOLEAN;
                break;
            case TEXT:
                required = DDLFeature.TYPE_TEXT;
                break;
            case BLOB:
                required = DDLFeature.TYPE_BLOB;
                break;
            case JSON:
                required = DDLFeature.TYPE_JSON;
                break;
            case UUID:
                required = DDLFeature.TYPE_UUID;
                break;
            case ENUM:
                required = DDLFeature.TYPE_ENUM;
                break;
            case ARRAY:
                required = DDLFeature.TYPE_ARRAY;
                break;
            case DATE:
                required = DDLFeature.TYPE_DATE;
                break;
            case TIME:
                required = DDLFeature.TYPE_TIME;
                break;
            case TIMESTAMP:
                required = DDLFeature.TYPE_TIMESTAMP;
                break;
            case TIMESTAMPTZ:
                required = DDLFeature.TYPE_TIMESTAMPTZ;
                break;
            case CHAR:
            case VARCHAR:
                required = DDLFeature.TYPE_CHAR_LENGTH;
                break;
            case DECIMAL:
                required = DDLFeature.TYPE_NUMERIC_PRECISION;
                break;
            default:
                break;
            }
            if (required != null && !features.supports(required)) {
                missing.add(required.name());
            }
            if (type.getElementType() != null) {
                missing.addAll(unsupportedType(type.getElementType(), features));
            }
            return missing;
        }

        public static boolean isCurrentTimestamp(String expression) {
            if (expression == null) {
                return false;
            }
            String lower = expression.trim().toLowerCase(Locale.ROOT);
            return lower.equals("current_timestamp") || lower.equals("current_timestamp()")
                    || lower.equals("now()") || lower.equals("localtimestamp") || lower.equals("current_date")
                    || lower.equals("current_time") || lower.equals("getdate()");
        }

        /* ---------------------------------------------------------------- row validation */

        /** @return true when the row satisfies every NOT NULL and CHECK constraint the schema declares */
        public static boolean rowSatisfiesIntendedConstraints(Table table, Map<String, Object> row) {
            for (Column column : table.getColumns()) {
                if (column.isGenerated()) {
                    continue;
                }
                if (!hasKey(row, column.getName())) {
                    continue;
                }
                if (!column.isNullable() && DataModel.getValue(row, column.getName()) == null) {
                    return false;
                }
            }
            for (CheckConstraint check : table.getCheckConstraints()) {
                if (!checkSatisfied(row, check.getExpression())) {
                    return false;
                }
            }
            for (Column column : table.getColumns()) {
                if (column.getCheckExpression() != null && !checkSatisfied(row, column.getCheckExpression())) {
                    return false;
                }
            }
            return true;
        }

        /** @return true when every conjunct that can be evaluated is satisfied (UNKNOWN counts as satisfied) */
        public static boolean checkSatisfied(Map<String, Object> row, String expression) {
            if (expression == null) {
                return true;
            }
            for (String conjunct : CheckExpressionAnalyzer.splitConjuncts(expression)) {
                CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse(conjunct);
                if (!analyzer.isUnderstood() || !hasKey(row, analyzer.getColumnName())) {
                    continue;
                }
                if (!analyzer.isSatisfiedBy(DataModel.getValue(row, analyzer.getColumnName()))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * @return true when at least one conjunct of the expression is provably FALSE for the row
         *         (as opposed to UNKNOWN, which a CHECK constraint accepts)
         */
        public static boolean checkGenuinelyViolated(Map<String, Object> row, String expression) {
            if (expression == null) {
                return false;
            }
            for (String conjunct : CheckExpressionAnalyzer.splitConjuncts(expression)) {
                CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse(conjunct);
                if (!analyzer.isUnderstood() || !hasKey(row, analyzer.getColumnName())) {
                    continue;
                }
                Object value = DataModel.getValue(row, analyzer.getColumnName());
                if (analyzer.getOperator() == CheckExpressionAnalyzer.Operator.IS_NOT_NULL) {
                    if (value == null) {
                        return true;
                    }
                    continue;
                }
                if (value == null || hasNullLiteral(analyzer)) {
                    // NULL operand: the comparison evaluates to UNKNOWN, which the CHECK accepts
                    continue;
                }
                if (!analyzer.isSatisfiedBy(value)) {
                    return true;
                }
            }
            return false;
        }

        /** @return true when the expression compares against a NULL literal */
        public static boolean hasNullLiteral(CheckExpressionAnalyzer analyzer) {
            if (analyzer.getLiterals() == null) {
                return false;
            }
            for (Object literal : analyzer.getLiterals()) {
                if (literal == null) {
                    return true;
                }
            }
            return false;
        }

        /** Case-insensitive key lookup that distinguishes "absent" from "present but NULL". */
        public static boolean hasKey(Map<String, Object> row, String columnName) {
            if (row == null || columnName == null) {
                return false;
            }
            if (row.containsKey(columnName)) {
                return true;
            }
            for (String key : row.keySet()) {
                if (key.equalsIgnoreCase(columnName)) {
                    return true;
                }
            }
            return false;
        }

        /** @return true for a single-column integral primary key, which some engines auto-assign */
        public static boolean isSoleIntegralPrimaryKey(Column column, Table table) {
            if (table.getPrimaryKey() == null || table.getPrimaryKey().getColumns().size() != 1) {
                return false;
            }
            if (!table.getPrimaryKey().getColumns().get(0).equalsIgnoreCase(column.getName())) {
                return false;
            }
            return column.getType() != null && column.getType().isIntegral();
        }

        /* ---------------------------------------------------------------- SQL helpers */

        public static String whereEquals(Dialect dialect, Table table, List<String> columns,
                Map<String, Object> values) {
            List<String> parts = new ArrayList<>();
            for (String column : columns) {
                Object value = DataModel.getValue(values, column);
                DataType type = null;
                Column definition = table == null ? null : table.getColumn(column);
                if (definition != null) {
                    type = definition.getType();
                }
                parts.add(dialect.quote(column) + " = " + dialect.renderLiteral(value, type));
            }
            return String.join(" AND ", parts);
        }

        /**
         * @return the single scalar of {@code SELECT COUNT(*) ...}, or null when the query could not be
         *         executed (in which case the caller must not draw a conclusion from it)
         */
        public static Long countWhere(OracleContext context, DatabaseUnderTest database, String tableName,
                String where, String purpose) {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ");
            sql.append(database.getDialect().quote(tableName));
            if (where != null && !where.isEmpty()) {
                sql.append(" WHERE ").append(where);
            }
            try {
                QueryResult result = StatementRunner.query(context, database, sql.toString(), purpose);
                if (result.getRowCount() == 0 || result.getColumnNames().isEmpty()) {
                    return null;
                }
                Object value = result.getValue(0, 0);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
                return value == null ? null : Long.parseLong(value.toString().trim());
            } catch (SQLException | RuntimeException e) {
                return null;
            }
        }

        /** Executes a scalar query, returning null when it fails. */
        public static Object scalar(OracleContext context, DatabaseUnderTest database, String sql, String purpose) {
            try {
                QueryResult result = StatementRunner.query(context, database, sql, purpose);
                if (result.getRowCount() == 0 || result.getColumnNames().isEmpty()) {
                    return null;
                }
                return result.getValue(0, 0);
            } catch (SQLException | RuntimeException e) {
                return null;
            }
        }

        public static String normalize(String identifier) {
            return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
        }

        /* ---------------------------------------------------------------- findings */

        /** One observed defect, before it is aggregated into an {@link OracleFailure}. */
        public static final class Finding {
            public final FailureKind kind;
            public final String database;
            /** The grouping key: a constraint type, a referential action, a default, ... */
            public final String group;
            public final String title;
            public final String details;
            public final String reproducer;

            private Finding(FailureKind kind, String database, String group, String title, String details,
                    String reproducer) {
                this.kind = kind;
                this.database = database;
                this.group = group;
                this.title = title;
                this.details = details;
                this.reproducer = reproducer;
            }

            public static Finding of(FailureKind kind, String database, String group, String title, String details,
                    String reproducer) {
                return new Finding(kind, database, group, title, details, reproducer);
            }
        }

        /**
         * Aggregates findings into one failure: at most one representative per (database, group) is
         * described, but every occurrence is counted.
         */
        public static OracleFailure toFailure(String oracleName, OracleContext context, List<Finding> findings) {
            return toFailure(oracleName, context, findings, List.of());
        }

        /**
         * Aggregates findings into one failure: at most one representative per (database, group) is
         * described, but every occurrence is counted. The notes (skipped tables, excluded probes)
         * are appended so that a report always explains what was <em>not</em> checked.
         */
        public static OracleFailure toFailure(String oracleName, OracleContext context, List<Finding> findings,
                List<String> notes) {
            Map<String, List<Finding>> groups = new LinkedHashMap<>();
            Set<String> databases = new LinkedHashSet<>();
            Map<FailureKind, Integer> kindCounts = new LinkedHashMap<>();
            for (Finding finding : findings) {
                groups.computeIfAbsent(finding.database + "|" + finding.group, key -> new ArrayList<>()).add(finding);
                databases.add(finding.database);
                kindCounts.merge(finding.kind, 1, Integer::sum);
            }
            StringBuilder details = new StringBuilder();
            details.append(findings.size()).append(" violation(s) of the declared schema, in ")
                    .append(groups.size()).append(" (database, constraint) group(s).\n")
                    .append("At most one representative is described per group; the count covers every occurrence.\n");
            StringBuilder reproducer = new StringBuilder();
            for (Map.Entry<String, List<Finding>> entry : groups.entrySet()) {
                Finding representative = entry.getValue().get(0);
                details.append("\n================================================================\n");
                details.append("database : ").append(representative.database).append('\n');
                details.append("group    : ").append(representative.group).append('\n');
                details.append("kind     : ").append(representative.kind).append('\n');
                details.append("occurrences in this group: ").append(entry.getValue().size()).append('\n');
                details.append("----------------------------------------------------------------\n");
                details.append(representative.details).append('\n');
                if (representative.reproducer != null && !representative.reproducer.isBlank()) {
                    reproducer.append("-- ").append(representative.database).append(": ")
                            .append(representative.title).append('\n');
                    reproducer.append(representative.reproducer).append('\n');
                }
            }
            StringBuilder title = new StringBuilder();
            title.append(findings.size()).append(" declared constraint(s) not honoured (");
            boolean first = true;
            for (Map.Entry<FailureKind, Integer> entry : kindCounts.entrySet()) {
                if (!first) {
                    title.append(", ");
                }
                first = false;
                title.append(entry.getKey()).append(" x").append(entry.getValue());
            }
            title.append(')');
            if (notes != null && !notes.isEmpty()) {
                details.append("\n================================================================\n");
                details.append("Probe exclusions and skipped tables (")
                        .append(notes.size()).append(" note(s)):\n");
                for (String note : notes) {
                    details.append("  - ").append(note).append('\n');
                }
            }
            return new OracleFailure(findings.get(0).kind, oracleName, title.toString(), details.toString(),
                    reproducer.toString(), context.getSeed(), String.join(", ", databases), null);
        }
    }
}
