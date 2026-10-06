package org.ddlsmith.oracle.impl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.ddl.alter.DropColumn;
import org.ddlsmith.ddl.alter.DropConstraint;
import org.ddlsmith.ddl.alter.DropDefault;
import org.ddlsmith.ddl.alter.DropNotNull;
import org.ddlsmith.ddl.alter.RenameColumn;
import org.ddlsmith.ddl.alter.SetDefault;
import org.ddlsmith.ddl.alter.SetNotNull;
import org.ddlsmith.ddl.gen.EngineTraits;
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;
import org.ddlsmith.introspection.SchemaNormalizer;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.StatementRunner;
import org.ddlsmith.oracle.TestOracle;

/**
 * Metamorphic testing of {@code ALTER TABLE}: two DDL sequences that must produce the same schema
 * are executed and their introspected results compared.
 *
 * <h2>Relation A - an action followed by its inverse restores the schema</h2>
 * <p>A table of the intended schema is materialized as a <em>scratch table</em> (a clone with a
 * fresh name), the schema is introspected, a sequence of supported
 * {@link AlterTableAction}s is applied, the schema is introspected again, then every action's
 * {@link AlterTableAction#inverse(String)} is applied in reverse order and the schema is
 * introspected a final time. The first and the last introspection must be equal under
 * {@link SchemaNormalizer#diff}, after erasing both with {@link SchemaNormalizer#erase} for the
 * capabilities the introspector actually has.</p>
 *
 * <p><b>Documented deviation.</b> The relation is applied to a scratch clone of a table of the
 * intended schema rather than to the materialized table itself. This is deliberate:</p>
 * <ul>
 *   <li>the schema under test is not mutated, so the oracle cannot leave the database in a state
 *       that breaks the oracles running after it;</li>
 *   <li>the scratch table is empty, so an action can never fail for a data-dependent reason
 *       ({@code SET NOT NULL} over existing NULLs, a narrowing type change over existing values)
 *       and produce a finding that says nothing about {@code ALTER TABLE};</li>
 *   <li>the baseline is the schema the engine <em>reports</em> after the {@code CREATE TABLE}, not
 *       the DDL text, which is what makes the comparison meaningful.</li>
 * </ul>
 *
 * <h2>Relation B - ALTER-created schema equals directly-created schema</h2>
 * <p>Two scratch tables are created: {@code T1} with the full definition (primary key, a nullable
 * column with a default, a unique constraint and a check constraint) and {@code T2} with a reduced
 * definition that is then completed with {@code ALTER TABLE ... ADD COLUMN} and
 * {@code ALTER TABLE ... ADD CONSTRAINT}. Both are introspected, {@code T2} is renamed to
 * {@code T1} in the introspected copy, and the two normalized schemas must be identical.</p>
 *
 * <h2>Guards</h2>
 * <ul>
 *   <li>Only actions whose {@link DDLFeature} the dialect declares are chosen, and only on columns
 *       that are not part of a key, a unique constraint, a foreign key or an inline constraint, so
 *       that an engine-specific cascade can never be mistaken for a defect.</li>
 *   <li>{@code DROP COLUMN} is only chosen for the <em>last</em> column of the table, because
 *       {@code ADD COLUMN} appends and re-adding a column elsewhere would change the ordinal
 *       position, which {@link SchemaNormalizer} reports as a {@code COLUMN_ORDER} difference that
 *       is not a semantic defect.</li>
 *   <li>{@code ALTER COLUMN TYPE} is only chosen for integral and {@code VARCHAR} columns, whose
 *       widened and restored forms round-trip through every supported dialect's type mapper.</li>
 *   <li>If an action fails, the sequence stops there and only the successfully applied prefix is
 *       inverted; a failing action is a note, not a finding.</li>
 *   <li>At most one action per column is selected, so two actions of the sequence can never
 *       interfere with each other.</li>
 *   <li>If any inverse is unavailable ({@link AlterTableAction#isInvertible()}), the relation is
 *       abandoned for that table instead of being compared.</li>
 * </ul>
 */
public class MetamorphicAlterOracle implements TestOracle {

    public static final String NAME = "metamorphic-alter";
    /** Upper bound on the number of actions applied per table. */
    public static final int MAX_ACTIONS = 3;
    /** Upper bound on the number of tables used for relation A per database. */
    public static final int MAX_TABLES = 3;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Compares an ALTER TABLE sequence with its inverse, and an ALTER-created table with a "
                + "directly created one (metamorphic relations).";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        RelationalSchema schema = context.getIntendedSchema();
        if (schema == null || schema.getTables().isEmpty()) {
            return Optional.empty();
        }
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (DatabaseUnderTest database : context.getDatabases()) {
            findings.addAll(relationAlterThenInverse(context, database, schema, notes));
            findings.addAll(relationAlterVersusCreate(context, database, notes));
        }
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ConstraintEnforcementOracle.Support.toFailure(NAME, context, findings, notes));
    }

    /* ================================================================== relation A */

    private List<ConstraintEnforcementOracle.Support.Finding> relationAlterThenInverse(OracleContext context,
            DatabaseUnderTest database, RelationalSchema schema, List<String> notes) {
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        Dialect dialect = database.getDialect();
        String prefix = "[" + database.getName() + "] relation A: ";
        if (!supportsAnyAlter(dialect.getFeatures())) {
            notes.add(prefix + "skipped, the dialect declares no ALTER TABLE action that can be inverted");
            return findings;
        }
        List<Table> tables = new ArrayList<>(schema.getTables());
        Randomly.shuffle(tables);
        int used = 0;
        for (Table table : tables) {
            if (used >= MAX_TABLES) {
                break;
            }
            if (!ConstraintEnforcementOracle.Support.tableSupported(table, dialect)) {
                continue;
            }
            String scratch = scratchName(schema, "mm_a_", table.getName());
            Table clone = cloneForScratch(table, scratch);
            dropScratch(context, database, scratch);
            String createSql = dialect.render(new CreateTable(clone));
            ExecutionEvent created = StatementRunner.execute(context, database, createSql, false,
                    "metamorphic-create-scratch");
            if (!created.isSucceeded()) {
                notes.add(prefix + "table " + table.getName() + " skipped, the scratch CREATE TABLE failed: "
                        + created.getErrorMessage());
                continue;
            }
            used++;
            List<String> reproducer = new ArrayList<>();
            reproducer.add(createSql);
            RelationalSchema baseline = introspectTable(context, database, scratch, dialect);
            if (baseline == null) {
                notes.add(prefix + "table " + table.getName() + " skipped, the scratch table is not visible "
                        + "through the introspector");
                dropScratch(context, database, scratch);
                continue;
            }
            List<AlterTableAction> chosen = chooseActions(clone, dialect, scratch);
            List<AlterTableAction> applied = new ArrayList<>();
            for (AlterTableAction action : chosen) {
                String sql = dialect.render(new org.ddlsmith.ddl.AlterTable(scratch, action));
                ExecutionEvent event = StatementRunner.execute(context, database, sql, false, "metamorphic-alter");
                if (!event.isSucceeded()) {
                    notes.add(prefix + "action " + action + " on " + scratch + " was rejected ("
                            + event.getErrorMessage() + "); the sequence stops there");
                    break;
                }
                applied.add(action);
                reproducer.add(sql);
                // introspect after every action so that a divergence can be located in the sequence
                if (introspectTable(context, database, scratch, dialect) == null) {
                    notes.add(prefix + "table " + scratch + " disappeared after " + action);
                    applied.clear();
                    break;
                }
            }
            if (applied.isEmpty()) {
                notes.add(prefix + "table " + table.getName()
                        + " skipped, no ALTER TABLE action of the sequence was accepted");
                dropScratch(context, database, scratch);
                continue;
            }
            List<AlterTableAction> inverses = inversesFor(applied, scratch);
            if (inverses == null) {
                notes.add(prefix + "table " + table.getName()
                        + " skipped, at least one action is not invertible without further information");
                dropScratch(context, database, scratch);
                continue;
            }
            boolean aborted = false;
            for (AlterTableAction inverse : inverses) {
                String sql = dialect.render(new org.ddlsmith.ddl.AlterTable(scratch, inverse));
                ExecutionEvent event = StatementRunner.execute(context, database, sql, false, "metamorphic-inverse");
                reproducer.add(sql);
                if (!event.isSucceeded()) {
                    notes.add(prefix + "the inverse action " + inverse + " on " + scratch + " was rejected ("
                            + event.getErrorMessage() + "), so the relation cannot be evaluated");
                    aborted = true;
                    break;
                }
            }
            if (aborted) {
                dropScratch(context, database, scratch);
                continue;
            }
            RelationalSchema after = introspectTable(context, database, scratch, dialect);
            if (after == null) {
                notes.add(prefix + "table " + scratch + " is not visible after the inverse sequence");
                dropScratch(context, database, scratch);
                continue;
            }
            List<SchemaDifference> differences = SchemaNormalizer.diff(baseline, after);
            if (!differences.isEmpty()) {
                findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.METAMORPHIC,
                        database.getName(), "relation-A",
                        "ALTER TABLE followed by its inverse did not restore the schema",
                        "Relation  : apply a sequence of ALTER TABLE actions, then each action's inverse in "
                                + "reverse order; the introspected schema must be unchanged.\n"
                                + "Table     : " + table.getName() + " (scratch table " + scratch + ")\n"
                                + "Actions   : " + applied + "\n"
                                + "Inverses  : " + inverses + "\n"
                                + "Differences (" + differences.size() + "):\n"
                                + SchemaNormalizer.render(differences),
                        String.join("\n", reproducer)));
            }
            dropScratch(context, database, scratch);
        }
        return findings;
    }

    /**
     * Test seam: the actions that must undo {@code applied}, in execution order.
     *
     * <p>The default implementation returns {@link AlterTableAction#inverse(String)} of every action
     * in reverse order, or null when one of them is not invertible. A test can override this to
     * deliberately drop an inverse and check that the relation reports a
     * {@link FailureKind#METAMORPHIC} failure.</p>
     */
    protected List<AlterTableAction> inversesFor(List<AlterTableAction> applied, String tableName) {
        List<AlterTableAction> inverses = new ArrayList<>();
        for (int i = applied.size() - 1; i >= 0; i--) {
            AlterTableAction inverse = applied.get(i).inverse(tableName);
            if (inverse == null) {
                return null;
            }
            inverses.add(inverse);
        }
        return inverses;
    }

    private static boolean supportsAnyAlter(DDLFeatureSet features) {
        return features.supports(DDLFeature.ALTER_ADD_COLUMN) || features.supports(DDLFeature.ALTER_DROP_COLUMN)
                || features.supports(DDLFeature.ALTER_RENAME_COLUMN) || features.supports(DDLFeature.ALTER_SET_NOT_NULL)
                || features.supports(DDLFeature.ALTER_DROP_NOT_NULL) || features.supports(DDLFeature.ALTER_SET_DEFAULT)
                || features.supports(DDLFeature.ALTER_DROP_DEFAULT)
                || features.supports(DDLFeature.ALTER_COLUMN_TYPE)
                || features.supports(DDLFeature.ALTER_ADD_CONSTRAINT)
                || features.supports(DDLFeature.ALTER_DROP_CONSTRAINT);
    }

    /**
     * @return up to {@link #MAX_ACTIONS} independent, invertible actions for the table
     */
    private List<AlterTableAction> chooseActions(Table table, Dialect dialect, String scratchName) {
        DDLFeatureSet features = dialect.getFeatures();
        List<AlterTableAction> candidates = new ArrayList<>();
        Set<String> usedColumns = new LinkedHashSet<>();
        if (features.supports(DDLFeature.ALTER_ADD_COLUMN)) {
            Column added = new Column(uniqueColumnName(table, "mm_added"), DataType.integer(), true);
            candidates.add(new AddColumn(added));
            usedColumns.add(ConstraintEnforcementOracle.Support.normalize(added.getName()));
        }
        if (features.supports(DDLFeature.ALTER_DROP_COLUMN) && table.getColumns().size() > 1) {
            // only the last column: ADD COLUMN appends, so re-adding it restores the ordinal position
            Column last = table.getColumns().get(table.getColumns().size() - 1);
            String lastName = ConstraintEnforcementOracle.Support.normalize(last.getName());
            // ... and only when re-adding it really restores it. A dialect that has to relax the
            // definition to express ADD COLUMN at all (SQLite drops UNIQUE and gives a NOT NULL
            // column without a default a typed zero default) would otherwise make the comparison
            // report the relaxation as an engine defect.
            if (isFreeColumn(last, table) && !usedColumns.contains(lastName)
                    && EngineTraits.canAddColumnExactly(dialect.getName(), last)) {
                candidates.add(new DropColumn(last.getName(), last.copy()));
                usedColumns.add(lastName);
            }
        }
        if (features.supports(DDLFeature.ALTER_RENAME_COLUMN)) {
            for (Column column : table.getColumns()) {
                String name = ConstraintEnforcementOracle.Support.normalize(column.getName());
                // the column may already be claimed by another candidate. Without this test the
                // rename could pick the very column DROP COLUMN had chosen, and whichever of the two
                // the shuffle put second failed with "no such column" - 154 of 617 metamorphic logs
                // in one acceptance session were that self-inflicted rejection.
                if (usedColumns.contains(name) || !isFreeColumn(column, table)) {
                    continue;
                }
                String target = uniqueColumnName(table, column.getName() + "_mm");
                candidates.add(new RenameColumn(column.getName(), target));
                usedColumns.add(name);
                break;
            }
        }
        if (features.supports(DDLFeature.ALTER_SET_NOT_NULL)) {
            for (Column column : table.getColumns()) {
                String name = ConstraintEnforcementOracle.Support.normalize(column.getName());
                if (column.isNullable() && isFreeColumn(column, table) && !usedColumns.contains(name)) {
                    candidates.add(new SetNotNull(column.getName(), true));
                    usedColumns.add(name);
                    break;
                }
            }
        }
        if (features.supports(DDLFeature.ALTER_DROP_NOT_NULL)) {
            for (Column column : table.getColumns()) {
                String name = ConstraintEnforcementOracle.Support.normalize(column.getName());
                // dropping NOT NULL from a key column is meaningless (the key implies it) and several
                // engines reject it, which would stop the sequence for a non-defect
                if (!column.isNullable() && !column.isIdentity() && !table.isKeyColumn(column.getName())
                        && !usedColumns.contains(name)) {
                    candidates.add(new DropNotNull(column.getName(), true));
                    usedColumns.add(name);
                    break;
                }
            }
        }
        if (features.supports(DDLFeature.ALTER_SET_DEFAULT)) {
            for (Column column : table.getColumns()) {
                String name = ConstraintEnforcementOracle.Support.normalize(column.getName());
                if (!column.hasDefault() && column.getType() != null && column.getType().isIntegral()
                        && !usedColumns.contains(name)) {
                    candidates.add(new SetDefault(column.getName(), "7", null));
                    usedColumns.add(name);
                    break;
                }
            }
        }
        if (features.supports(DDLFeature.ALTER_DROP_DEFAULT)) {
            for (Column column : table.getColumns()) {
                String name = ConstraintEnforcementOracle.Support.normalize(column.getName());
                if (column.hasDefault() && !usedColumns.contains(name)) {
                    candidates.add(new DropDefault(column.getName(), column.getDefaultExpression()));
                    usedColumns.add(name);
                    break;
                }
            }
        }
        if (features.supports(DDLFeature.ALTER_COLUMN_TYPE)) {
            for (Column column : table.getColumns()) {
                String name = ConstraintEnforcementOracle.Support.normalize(column.getName());
                if (usedColumns.contains(name) || column.getType() == null) {
                    continue;
                }
                if (EngineTraits.typeChangeReplacesColumnDefinition(dialect::includesEngine)
                        && EngineTraits.hasAttributeBeyondType(column)) {
                    // an engine that replaces the whole column definition on a type change would drop the
                    // attributes this statement does not restate, and the inverse of the action could not
                    // put them back; that is not a metamorphic relation, it is a schema change
                    continue;
                }
                DataType widened = widen(column.getType(), dialect);
                if (widened != null) {
                    candidates.add(new AlterColumnType(column.getName(), widened, column.getType(), null));
                    usedColumns.add(name);
                    break;
                }
            }
        }
        if (features.supportsAll(DDLFeature.ALTER_ADD_CONSTRAINT, DDLFeature.ALTER_ADD_CHECK)
                && features.supports(DDLFeature.NAMED_CHECK)) {
            String name = uniqueConstraintName(table, constraintName("mm_ck", scratchName));
            candidates.add(new AddConstraint(ConstraintDefinition.check(new CheckConstraint(name, "1 = 1"))));
        }
        if (features.supportsAll(DDLFeature.ALTER_DROP_CONSTRAINT, DDLFeature.ALTER_ADD_CHECK)) {
            for (CheckConstraint check : table.getCheckConstraints()) {
                if (check.getName() != null) {
                    candidates.add(new DropConstraint(check.getName(),
                            ConstraintDefinition.check(check), false, false));
                    break;
                }
            }
        }
        Randomly.shuffle(candidates);
        List<AlterTableAction> chosen = new ArrayList<>();
        for (AlterTableAction candidate : candidates) {
            if (chosen.size() >= MAX_ACTIONS) {
                break;
            }
            if (candidate.inverse("t") == null) {
                continue;
            }
            chosen.add(candidate);
        }
        return chosen;
    }

    private static DataType widen(DataType type, Dialect dialect) {
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
            return DataType.bigint();
        case BIGINT:
            return DataType.decimal(20, 0);
        case VARCHAR:
            return type.getLength() == null ? null : DataType.varchar(type.getLength() + 20);
        case DECIMAL:
            if (type.getPrecision() == null) {
                return null;
            }
            int precision = type.getPrecision() + 5;
            if (precision > EngineTraits.maxDecimalPrecision(dialect.getName())) {
                // beyond what the engine can express; the caller skips the column
                return null;
            }
            return DataType.decimal(precision, type.getScale());
        default:
            return null;
        }
    }

    /** @return true when the column can be dropped or renamed without touching a key or a constraint */
    private static boolean isFreeColumn(Column column, Table table) {
        if (column.isIdentity() || column.isGenerated() || column.isUnique()
                || column.getCheckExpression() != null) {
            return false;
        }
        String name = ConstraintEnforcementOracle.Support.normalize(column.getName());
        if (table.getPrimaryKey() != null) {
            for (String keyColumn : table.getPrimaryKey().getColumns()) {
                if (ConstraintEnforcementOracle.Support.normalize(keyColumn).equals(name)) {
                    return false;
                }
            }
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            for (String keyColumn : unique.getColumns()) {
                if (ConstraintEnforcementOracle.Support.normalize(keyColumn).equals(name)) {
                    return false;
                }
            }
        }
        for (Index index : table.getIndexes()) {
            for (String keyColumn : index.getColumns()) {
                if (ConstraintEnforcementOracle.Support.normalize(keyColumn).equals(name)) {
                    return false;
                }
            }
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            for (String keyColumn : foreignKey.getColumns()) {
                if (ConstraintEnforcementOracle.Support.normalize(keyColumn).equals(name)) {
                    return false;
                }
            }
        }
        for (CheckConstraint check : table.getCheckConstraints()) {
            String expression = check.getExpression() == null ? "" : check.getExpression().toLowerCase(Locale.ROOT);
            if (expression.contains(name)) {
                return false;
            }
        }
        return true;
    }

    private static String uniqueColumnName(Table table, String wanted) {
        String candidate = wanted;
        int counter = 0;
        while (table.hasColumn(candidate)) {
            candidate = wanted + (++counter);
        }
        return candidate;
    }

    private static String uniqueConstraintName(Table table, String wanted) {
        String candidate = wanted;
        int counter = 0;
        while (hasConstraint(table, candidate)) {
            candidate = wanted + (++counter);
        }
        return candidate;
    }

    private static boolean hasConstraint(Table table, String name) {
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (name.equalsIgnoreCase(unique.getName())) {
                return true;
            }
        }
        for (CheckConstraint check : table.getCheckConstraints()) {
            if (name.equalsIgnoreCase(check.getName())) {
                return true;
            }
        }
        return table.getPrimaryKey() != null && name.equalsIgnoreCase(table.getPrimaryKey().getName());
    }

    /* ================================================================== relation B */

    private List<ConstraintEnforcementOracle.Support.Finding> relationAlterVersusCreate(OracleContext context,
            DatabaseUnderTest database, List<String> notes) {
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        Dialect dialect = database.getDialect();
        DDLFeatureSet features = dialect.getFeatures();
        String prefix = "[" + database.getName() + "] relation B: ";
        if (!features.supportsAll(DDLFeature.ALTER_ADD_COLUMN, DDLFeature.ALTER_ADD_CONSTRAINT,
                DDLFeature.ALTER_ADD_UNIQUE, DDLFeature.ALTER_ADD_CHECK, DDLFeature.DEFAULT_LITERAL,
                DDLFeature.NOT_NULL_COLUMN, DDLFeature.NAMED_UNIQUE, DDLFeature.NAMED_CHECK)) {
            notes.add(prefix + "skipped, the dialect does not declare ALTER TABLE ... ADD COLUMN/ADD CONSTRAINT "
                    + "together with named UNIQUE and CHECK constraints and column DEFAULTs");
            return findings;
        }
        String direct = scratchName(null, "mm_b_direct_", "t");
        String altered = scratchName(null, "mm_b_altered_", "t");
        dropScratch(context, database, direct);
        dropScratch(context, database, altered);

        Table full = new Table(direct);
        full.addColumn(new Column("id", DataType.integer(), false));
        full.addColumn(new Column("extra", DataType.integer(), true));
        full.addColumn(new Column("label", DataType.varchar(20), true).setDefaultExpression("'dflt'"));
        full.setPrimaryKey(new PrimaryKey(List.of("id")));
        full.addUniqueConstraint(new UniqueConstraint(constraintName("mm_uq", direct), List.of("label")));
        full.addCheckConstraint(new CheckConstraint(constraintName("mm_ck", direct), "id > 0"));

        Table reduced = new Table(altered);
        reduced.addColumn(new Column("id", DataType.integer(), false));
        reduced.addColumn(new Column("extra", DataType.integer(), true));
        reduced.setPrimaryKey(new PrimaryKey(List.of("id")));

        List<String> reproducer = new ArrayList<>();
        String createFull = dialect.render(new CreateTable(full));
        ExecutionEvent event = StatementRunner.execute(context, database, createFull, false,
                "metamorphic-create-full");
        reproducer.add(createFull);
        if (!event.isSucceeded()) {
            notes.add(prefix + "skipped, the directly created table could not be created: "
                    + event.getErrorMessage());
            dropScratch(context, database, direct);
            return findings;
        }
        String createReduced = dialect.render(new CreateTable(reduced));
        event = StatementRunner.execute(context, database, createReduced, false, "metamorphic-create-reduced");
        reproducer.add(createReduced);
        if (!event.isSucceeded()) {
            notes.add(prefix + "skipped, the reduced table could not be created: " + event.getErrorMessage());
            dropScratch(context, database, direct);
            dropScratch(context, database, altered);
            return findings;
        }
        List<String> steps = new ArrayList<>();
        steps.add(dialect.render(new org.ddlsmith.ddl.AlterTable(altered,
                new AddColumn(new Column("label", DataType.varchar(20), true).setDefaultExpression("'dflt'")))));
        steps.add(dialect.render(new org.ddlsmith.ddl.AlterTable(altered, new AddConstraint(
                ConstraintDefinition.unique(new UniqueConstraint(constraintName("mm_uq", altered),
                        List.of("label")))))));
        steps.add(dialect.render(new org.ddlsmith.ddl.AlterTable(altered, new AddConstraint(
                ConstraintDefinition.check(new CheckConstraint(constraintName("mm_ck", altered), "id > 0"))))));
        boolean aborted = false;
        for (String step : steps) {
            ExecutionEvent stepEvent = StatementRunner.execute(context, database, step, false,
                    "metamorphic-alter-to-match");
            reproducer.add(step);
            if (!stepEvent.isSucceeded()) {
                notes.add(prefix + "step " + step + " was rejected (" + stepEvent.getErrorMessage()
                        + "), so the relation cannot be evaluated");
                aborted = true;
                break;
            }
        }
        if (aborted) {
            dropScratch(context, database, direct);
            dropScratch(context, database, altered);
            return findings;
        }
        RelationalSchema directSchema = introspectTable(context, database, direct, dialect);
        RelationalSchema alteredSchema = introspectTable(context, database, altered, dialect);
        if (directSchema == null || alteredSchema == null) {
            notes.add(prefix + "skipped, one of the scratch tables is not visible through the introspector");
            dropScratch(context, database, direct);
            dropScratch(context, database, altered);
            return findings;
        }
        // compare the two tables under the same name, ignoring which scratch table they came from
        alteredSchema.getTables().get(0).setName(direct);
        List<SchemaDifference> differences = SchemaNormalizer.diff(directSchema, alteredSchema);
        if (!differences.isEmpty()) {
            findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.METAMORPHIC,
                    database.getName(), "relation-B",
                    "a schema built with ALTER TABLE differs from the directly created schema",
                    "Relation  : CREATE TABLE with the full definition must equal CREATE TABLE with a "
                            + "reduced definition followed by ADD COLUMN / ADD CONSTRAINT.\n"
                            + "Full definition     : " + full.getColumnNames() + " PK" + full.getPrimaryKey().getColumns()
                            + " UNIQUE" + List.of("label") + " CHECK(id > 0)\n"
                            + "Incremental steps   : " + steps + "\n"
                            + "Differences (" + differences.size() + "):\n"
                            + SchemaNormalizer.render(differences),
                    String.join("\n", reproducer)));
        }
        dropScratch(context, database, direct);
        dropScratch(context, database, altered);
        return findings;
    }

    /* ================================================================== helpers */

    private Table cloneForScratch(Table table, String scratchName) {
        Table clone = table.copy();
        clone.setName(scratchName);
        clone.setSchemaName(null);
        // a self-referencing foreign key would point at the original table after the rename
        clone.getForeignKeys().removeIf(foreignKey -> ConstraintEnforcementOracle.Support
                .normalize(foreignKey.getReferencedTable()).equals(ConstraintEnforcementOracle.Support
                        .normalize(table.getName())));
        // constraint names are schema-global on several engines (H2, PostgreSQL, Oracle, SQL Server),
        // so a clone carrying the original names would collide with the table it was cloned from
        renameConstraints(clone, scratchName);
        return clone;
    }

    private static void renameConstraints(Table table, String suffix) {
        if (table.getPrimaryKey() != null && table.getPrimaryKey().getName() != null) {
            table.getPrimaryKey().setName(constraintName(table.getPrimaryKey().getName(), suffix));
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (unique.getName() != null) {
                unique.setName(constraintName(unique.getName(), suffix));
            }
        }
        for (CheckConstraint check : table.getCheckConstraints()) {
            if (check.getName() != null) {
                check.setName(constraintName(check.getName(), suffix));
            }
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            if (foreignKey.getName() != null) {
                foreignKey.setName(constraintName(foreignKey.getName(), suffix));
            }
        }
    }

    /** Builds a schema-unique constraint name that stays inside the usual 63 character limit. */
    private static String constraintName(String base, String suffix) {
        String candidate = base + "_" + suffix;
        if (candidate.length() <= 60) {
            return candidate;
        }
        int keep = Math.max(1, 60 - suffix.length() - 1);
        return base.substring(0, Math.min(keep, base.length())) + "_" + suffix;
    }

    private static String scratchName(RelationalSchema schema, String prefix, String table) {
        String base = prefix + table.toLowerCase(Locale.ROOT);
        if (base.length() > 24) {
            base = base.substring(0, 24);
        }
        String candidate = base;
        int counter = 0;
        while (schema != null && schema.hasTable(candidate)) {
            candidate = base + "_" + (++counter);
        }
        return candidate;
    }

    private RelationalSchema introspectTable(OracleContext context, DatabaseUnderTest database, String tableName,
            Dialect dialect) {
        RelationalSchema full;
        try {
            full = database.getIntrospector().introspect(database.getConnection(), dialect.getDefaultSchemaName());
        } catch (SQLException | RuntimeException e) {
            return null;
        }
        Table found = null;
        for (Table table : full.getTables()) {
            if (ConstraintEnforcementOracle.Support.normalize(table.getName())
                    .equals(ConstraintEnforcementOracle.Support.normalize(tableName))) {
                found = table;
                break;
            }
        }
        if (found == null) {
            return null;
        }
        RelationalSchema result = new RelationalSchema("metamorphic");
        result.addTable(found);
        Set<IntrospectionCapability> capabilities = database.getIntrospector().getCapabilities();
        return SchemaNormalizer.erase(result, capabilities);
    }

    private void dropScratch(OracleContext context, DatabaseUnderTest database, String tableName) {
        Dialect dialect = database.getDialect();
        if (dialect.getFeatures().supports(DDLFeature.DROP_IF_EXISTS)) {
            // IF EXISTS never fails, so the statement is not a probe that is expected to be rejected
            StatementRunner.execute(context, database,
                    "DROP TABLE IF EXISTS " + dialect.quote(tableName), false, "metamorphic-drop-scratch");
            return;
        }
        if (tableExists(database, dialect, tableName)) {
            StatementRunner.execute(context, database,
                    "DROP TABLE " + dialect.quote(tableName), false, "metamorphic-drop-scratch");
        }
    }

    private boolean tableExists(DatabaseUnderTest database, Dialect dialect, String tableName) {
        try {
            RelationalSchema schema = database.getIntrospector().introspect(database.getConnection(),
                    dialect.getDefaultSchemaName());
            for (Table table : schema.getTables()) {
                if (ConstraintEnforcementOracle.Support.normalize(table.getName())
                        .equals(ConstraintEnforcementOracle.Support.normalize(tableName))) {
                    return true;
                }
            }
            return false;
        } catch (SQLException | RuntimeException e) {
            // without introspection the existence cannot be established; leaving the table alone is the
            // conservative choice, because dropping it could fail for an unrelated reason
            return false;
        }
    }
}
