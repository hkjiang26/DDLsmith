package org.ddlsmith.ddl.gen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.ddlsmith.MainOptions;
import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.AlterTable;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateSequence;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.DropSequence;
import org.ddlsmith.ddl.DropTable;
import org.ddlsmith.ddl.DropView;
import org.ddlsmith.ddl.TruncateTable;
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

/**
 * Generates a random sequence of DDL statements that evolves an existing schema, while keeping an
 * in-memory model of the resulting state.
 *
 * <p>Keeping the model accurate is what makes the oracles usable after the evolution: the schema
 * round-trip oracle compares the database against {@link #getCurrentSchema()}, so every statement
 * this generator emits must be reflected in that model. Operations whose state update would require
 * rewriting unrelated objects (for instance renaming a column that participates in a foreign key)
 * are deliberately not generated; the exclusions are documented on each operation.</p>
 */
public class RandomDDLGenerator {

    private final RelationalSchema current;
    private final Dialect dialect;
    private final MainOptions options;
    private final List<String> notes = new ArrayList<>();
    /** Names of objects this generator created, so that it only drops what it owns. */
    private final Set<String> generatedObjects = new LinkedHashSet<>();
    private final Set<String> originalTables = new LinkedHashSet<>();
    private int counter;
    /** The model as it was before the last statement, so that a rejected statement can be undone. */
    private RelationalSchema schemaBeforeLastStatement;
    private Set<String> objectsBeforeLastStatement;
    private int notesBeforeLastStatement;
    /**
     * Tables that a view reads from. Dropping such a table, or renaming or dropping one of its
     * columns, would leave the view invalid: SQLite then refuses every later DDL statement with
     * "error in view ...: no such table", and the crash oracle reports the engine for a statement
     * DDLSmith itself broke.
     */
    private final Set<String> tablesReferencedByViews = new LinkedHashSet<>();

    public RandomDDLGenerator(RelationalSchema schema, Dialect dialect, MainOptions options) {
        this.current = schema.copy();
        this.dialect = dialect;
        this.options = options;
        for (Table table : schema.getTables()) {
            originalTables.add(normalize(table.getName()));
        }
        refreshViewDependencies();
    }

    /** Recomputes which tables the current views read from, after a view was added or dropped. */
    private void refreshViewDependencies() {
        tablesReferencedByViews.clear();
        for (View view : current.getViews()) {
            tablesReferencedByViews.addAll(referencedTables(view.getQuery()));
        }
    }

    /** @return the table names a view body reads from, without schema or alias */
    private static Set<String> referencedTables(String query) {
        Set<String> result = new LinkedHashSet<>();
        if (query == null) {
            return result;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\b(?:from|join)\\s+([A-Za-z_][A-Za-z0-9_$.]*)").matcher(query);
        while (matcher.find()) {
            String reference = matcher.group(1);
            int dot = reference.lastIndexOf('.');
            result.add(normalize(dot < 0 ? reference : reference.substring(dot + 1)));
        }
        return result;
    }

    /** @return the state of the schema after all generated statements have been applied */
    public RelationalSchema getCurrentSchema() {
        return current;
    }

    public List<String> getNotes() {
        return notes;
    }

    public Set<String> getGeneratedObjects() {
        return generatedObjects;
    }

    /** Generates {@code count} statements, applying each to the in-memory schema model. */
    public List<DDLStatement> generate(int count) {
        List<DDLStatement> statements = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Optional<DDLStatement> statement = nextStatement();
            statement.ifPresent(statements::add);
        }
        return statements;
    }

    /** Generates one statement, or an empty optional when no applicable operation exists. */
    public Optional<DDLStatement> nextStatement() {
        // The model is snapshotted before the operation runs, because every operation applies its
        // change to the model as it builds the statement. If the engine then refuses the statement,
        // the change must be undone (see discardLastStatement) rather than left to produce a
        // divergence the engine did not cause.
        RelationalSchema before = current.copy();
        Set<String> objectsBefore = new LinkedHashSet<>(generatedObjects);
        int notesBefore = notes.size();
        List<java.util.function.Supplier<Optional<DDLStatement>>> operations = new ArrayList<>();
        operations.add(this::addColumn);
        operations.add(this::dropGeneratedColumn);
        operations.add(this::renameColumn);
        operations.add(this::setNotNull);
        operations.add(this::dropNotNull);
        operations.add(this::setDefault);
        operations.add(this::dropDefault);
        operations.add(this::alterColumnType);
        operations.add(this::addUniqueConstraint);
        operations.add(this::addCheckConstraint);
        operations.add(this::addForeignKey);
        operations.add(this::dropGeneratedConstraint);
        operations.add(this::createIndex);
        operations.add(this::dropGeneratedIndex);
        operations.add(this::createView);
        operations.add(this::dropGeneratedView);
        operations.add(this::createTable);
        operations.add(this::dropGeneratedTable);
        operations.add(this::truncateTable);
        operations.add(this::createSequence);
        operations.add(this::dropGeneratedSequence);

        Randomly.shuffle(operations);
        for (java.util.function.Supplier<Optional<DDLStatement>> operation : operations) {
            Optional<DDLStatement> statement = operation.get();
            if (statement.isPresent()) {
                schemaBeforeLastStatement = before;
                objectsBeforeLastStatement = objectsBefore;
                notesBeforeLastStatement = notesBefore;
                return statement;
            }
        }
        return Optional.empty();
    }

    /**
     * Undoes the effect of the last generated statement on the model.
     *
     * <p>The evolution algorithm executes a statement and then compares the engine's catalog with the
     * model. When the engine <em>rejects</em> the statement, the model has still been changed, so the
     * comparison reports the missing column, index or constraint as a divergence — a finding about
     * DDLSmith rather than about the engine, and one that hides the real finding (the rejection
     * itself, which is the crash oracle's business). Rolling the model back keeps the two in step.</p>
     */
    public void discardLastStatement() {
        if (schemaBeforeLastStatement == null) {
            return;
        }
        current.restoreFrom(schemaBeforeLastStatement);
        generatedObjects.clear();
        generatedObjects.addAll(objectsBeforeLastStatement);
        while (notes.size() > notesBeforeLastStatement) {
            notes.remove(notes.size() - 1);
        }
        schemaBeforeLastStatement = null;
        objectsBeforeLastStatement = null;
    }

    /* ------------------------------------------------------------------ column operations */

    /** {@code ALTER TABLE ... ADD COLUMN}: always applicable to a non-empty schema. */
    private Optional<DDLStatement> addColumn() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_ADD_COLUMN)) {
            return Optional.empty();
        }
        Table table = randomTable();
        if (table == null) {
            return Optional.empty();
        }
        String name = freshColumnName(table);
        Column column = new Column(name, randomType());
        // An engine that refuses constraints on a column added to an existing table must not be sent
        // one: DuckDB answers "Adding columns with constraints not yet supported" for NOT NULL, CHECK,
        // UNIQUE and GENERATED alike, the column is then absent, and the evolution oracle reports a
        // divergence the generator caused.
        boolean constraintsAllowed = EngineTraits.allowsConstraintsOnAddedColumn(dialect.getName());
        column.setNullable(!constraintsAllowed || Randomly.getBoolean(0.7));
        if (Randomly.getBoolean(0.3) && dialect.getFeatures().supports(DDLFeature.DEFAULT_LITERAL)) {
            column.setDefaultExpression(defaultLiteralFor(column.getType()));
        }
        if (!column.isNullable() && !column.hasDefault()) {
            // many engines refuse a NOT NULL column without a default on an existing table
            column.setDefaultExpression(defaultLiteralFor(column.getType()));
        }
        if (constraintsAllowed && Randomly.getBoolean(0.15)
                && dialect.getFeatures().supports(DDLFeature.INLINE_CHECK)) {
            column.setCheckExpression(name + " IS NOT NULL");
        }
        table.addColumn(column);
        table.renumberColumns();
        column.setType(dialect.effectiveType(column.getType(), column, table));
        generatedObjects.add(normalize(table.getName()) + "." + normalize(name));
        return Optional.of(new AlterTable(table.getSchemaName(), table.getName(),
                new AddColumn(column.copy(), dialect.getFeatures().supports(DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS))));
    }

    /** {@code ALTER TABLE ... DROP COLUMN}: only for columns this generator added. */
    private Optional<DDLStatement> dropGeneratedColumn() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_DROP_COLUMN)) {
            return Optional.empty();
        }
        Table table = tableForColumnAlteration();
        if (table == null) {
            return Optional.empty();
        }
        for (Column column : new ArrayList<>(table.getColumns())) {
            if (!generatedObjects.contains(normalize(table.getName()) + "." + normalize(column.getName()))) {
                continue;
            }
            if (isReferenced(table, column.getName()) || table.isKeyColumn(column.getName())) {
                continue;
            }
            if (isChecked(table, column.getName())) {
                // An engine drops a CHECK constraint together with the column it mentions, so the
                // model would keep a constraint the database no longer has and the next catalog
                // comparison would report it as missing. H2 did exactly that: after
                // "ADD CONSTRAINT ck CHECK (c <> 4); DROP COLUMN c" the catalog held only the other
                // checks, and the schema-round-trip comparison called it a divergence. Renaming a
                // checked column is refused for the same reason a few lines below.
                continue;
            }
            if (precedesForeignKeyColumn(table, column.getName())) {
                // DuckDB builds an index for the referencing side of a foreign key and refuses to drop
                // a column that precedes one of its columns: "Cannot drop this column: an index depends
                // on a column after it!"
                continue;
            }
            table.removeColumn(column.getName());
            generatedObjects.remove(normalize(table.getName()) + "." + normalize(column.getName()));
            return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new DropColumn(column.getName(), column.copy(),
                    dialect.getFeatures().supports(DDLFeature.DROP_IF_EXISTS), false)));
        }
        return Optional.empty();
    }

    /**
     * {@code ALTER TABLE ... RENAME COLUMN}. Only columns that participate in no key, foreign key,
     * index or check constraint are renamed, so that the in-memory model stays exact without having
     * to rewrite unrelated objects.
     */
    private Optional<DDLStatement> renameColumn() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_RENAME_COLUMN)) {
            return Optional.empty();
        }
        Table table = tableForColumnAlteration();
        if (table == null) {
            return Optional.empty();
        }
        List<Column> candidates = new ArrayList<>();
        for (Column column : table.getColumns()) {
            if (!isReferenced(table, column.getName()) && !table.isKeyColumn(column.getName())
                    && !isChecked(table, column.getName()) && column.getCheckExpression() == null
                    && !isGeneratedDependency(table, column.getName())) {
                candidates.add(column);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Column column = Randomly.fromList(candidates);
        String oldName = column.getName();
        String newName = freshColumnName(table);
        column.setName(newName);
        // Renaming transfers ownership only if the column was this generator's to begin with. Giving
        // the new name to an original column made dropGeneratedColumn treat that column as generated
        // and drop it — and on DuckDB a column that precedes a foreign key column cannot be dropped,
        // so the engine rejected the statement and the crash oracle blamed it.
        boolean owned = generatedObjects.remove(normalize(table.getName()) + "." + normalize(oldName));
        if (owned) {
            generatedObjects.add(normalize(table.getName()) + "." + normalize(newName));
        }
        return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new RenameColumn(oldName, newName)));
    }

    private Optional<DDLStatement> setNotNull() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_SET_NOT_NULL)) {
            return Optional.empty();
        }
        Table table = alterableTable();
        if (table == null) {
            return Optional.empty();
        }
        for (Column column : table.getColumns()) {
            if (column.isNullable() && !column.isGenerated() && !table.isKeyColumn(column.getName())) {
                column.setNullable(false);
                return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new SetNotNull(column.getName(), true)));
            }
        }
        return Optional.empty();
    }

    private Optional<DDLStatement> dropNotNull() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_DROP_NOT_NULL)) {
            return Optional.empty();
        }
        Table table = alterableTable();
        if (table == null) {
            return Optional.empty();
        }
        for (Column column : table.getColumns()) {
            if (!column.isNullable() && !table.isKeyColumn(column.getName()) && !column.isGenerated()
                    && !column.isIdentity()) {
                // an identity column must stay NOT NULL (H2: 'Column "ID_2" must not be nullable')
                column.setNullable(true);
                return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new DropNotNull(column.getName(), false)));
            }
        }
        return Optional.empty();
    }

    private Optional<DDLStatement> setDefault() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_SET_DEFAULT)) {
            return Optional.empty();
        }
        Table table = alterableTable();
        if (table == null) {
            return Optional.empty();
        }
        for (Column column : table.getColumns()) {
            if (column.isGenerated() || column.isIdentity()) {
                // an engine ignores or refuses a default on an identity column (H2 keeps the identity
                // and reports no default), so the model would claim one the database never had
                continue;
            }
            if (!EngineTraits.allowsDefaultOn(dialect::includesEngine, column.getType())) {
                // MySQL: "BLOB, TEXT, GEOMETRY or JSON column 'a3' can't have a default value" (error 1101)
                continue;
            }
            if (table.isKeyColumn(column.getName())) {
                // MySQL accepts `ALTER COLUMN <pk column> SET DEFAULT (CURRENT_TIMESTAMP)` and stores
                // nothing (measured: EXTRA=DEFAULT_GENERATED while COLUMN_DEFAULT stays NULL), so the
                // model would claim a default the engine never had. setNotNull excludes key columns for
                // the same reason.
                continue;
            }
            String previous = column.getDefaultExpression();
            String expression = defaultLiteralFor(column.getType());
            column.setDefaultExpression(expression);
            return Optional.of(new AlterTable(table.getSchemaName(), table.getName(),
                    new SetDefault(column.getName(), expression, previous)));
        }
        return Optional.empty();
    }

    private Optional<DDLStatement> dropDefault() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_DROP_DEFAULT)) {
            return Optional.empty();
        }
        Table table = alterableTable();
        if (table == null) {
            return Optional.empty();
        }
        for (Column column : table.getColumns()) {
            if (column.hasDefault() && !column.isGenerated() && !column.isIdentity()) {
                String previous = column.getDefaultExpression();
                column.setDefaultExpression(null);
                return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new DropDefault(column.getName(), previous)));
            }
        }
        return Optional.empty();
    }

    /** {@code ALTER TABLE ... ALTER COLUMN ... TYPE}: widens a column not used by any key. */
    private Optional<DDLStatement> alterColumnType() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_COLUMN_TYPE)) {
            return Optional.empty();
        }
        Table table = alterableTable();
        if (table == null) {
            return Optional.empty();
        }
        for (Column column : table.getColumns()) {
            if (table.isKeyColumn(column.getName()) || isReferenced(table, column.getName()) || column.isGenerated()) {
                continue;
            }
            if (isChecked(table, column.getName()) && EngineTraits.refusesTypeChangeOnCheckedColumn(dialect.getName())) {
                // DuckDB: "Cannot change the type of a column that has a CHECK constraint specified"
                continue;
            }
            if (EngineTraits.typeChangeReplacesColumnDefinition(dialect::includesEngine)
                    && EngineTraits.hasAttributeBeyondType(column)) {
                // MySQL's MODIFY COLUMN is the new definition rather than an edit of the old one, so a
                // statement carrying only the type drops every other attribute (measured: NOT NULL on a
                // BIGINT type change). The AST action has nowhere to restate them, so the action gives
                // way - the same reasoning DuckDB's CHECK rule above uses.
                continue;
            }
            DataType widened = widen(column.getType());
            if (widened == null) {
                continue;
            }
            if (!wideningIsSafe(table, column, widened)) {
                continue;
            }
            DataType previous = column.getType();
            column.setType(widened);
            return Optional.of(new AlterTable(table.getSchemaName(), table.getName(),
                    new AlterColumnType(column.getName(), widened, previous, null)));
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------ constraint operations */

    private Optional<DDLStatement> addUniqueConstraint() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_ADD_CONSTRAINT)
                || !dialect.getFeatures().supports(DDLFeature.ALTER_ADD_UNIQUE)) {
            return Optional.empty();
        }
        Table table = randomTable();
        if (table == null) {
            return Optional.empty();
        }
        List<Column> candidates = new ArrayList<>();
        for (Column column : table.getColumns()) {
            // a unique constraint is an index, and H2 refuses to index a large object column: asking
            // for it made H2 reject the statement, which the crash oracle then reported as an engine
            // defect although the dialect declares ALTER_ADD_UNIQUE
            if (!table.isKeyColumn(column.getName()) && !column.isGenerated()
                    && dialect.isIndexable(column.getType())) {
                candidates.add(column);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Column column = Randomly.fromList(candidates);
        String name = constraintName("uq", table);
        UniqueConstraint unique = new UniqueConstraint(name, List.of(column.getName()));
        table.addUniqueConstraint(unique);
        generatedObjects.add(normalize(name));
        return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new AddConstraint(ConstraintDefinition.unique(unique))));
    }

    private Optional<DDLStatement> addCheckConstraint() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_ADD_CONSTRAINT)
                || !dialect.getFeatures().supports(DDLFeature.ALTER_ADD_CHECK)) {
            return Optional.empty();
        }
        Table table = randomTable();
        if (table == null) {
            return Optional.empty();
        }
        List<Column> candidates = new ArrayList<>();
        for (Column column : table.getColumns()) {
            if (!column.getType().isNumeric() && !column.getType().isTextual()) {
                continue;
            }
            // MySQL answers "Column 'x' cannot be used in a check constraint: needed in a foreign key
            // constraint referential action" (error 3823) and rejects the whole ALTER. The adapter
            // guards the checks that exist when it runs; this is the same guard, through the same
            // predicate, for the checks the generator appends afterwards.
            if (EngineTraits.refusesCheckOnReferentialActionColumn(dialect::includesEngine)
                    && SchemaAdapter.isReferentialActionTarget(table, column.getName())) {
                continue;
            }
            if (column.isIdentity() && EngineTraits.refusesCheckOnIdentityColumn(dialect::includesEngine)) {
                // MySQL: "Check constraint 'ck_t_1_1' cannot refer to an auto-increment column" (3818)
                continue;
            }
            candidates.add(column);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Column column = Randomly.fromList(candidates);
        String expression;
        if (column.getType().isNumeric()) {
            expression = column.getName() + (Randomly.getBoolean() ? " >= 0" : " <> " + Randomly.getInteger(-5, 5));
        } else {
            expression = column.getName() + " IS NOT NULL";
        }
        String name = constraintName("ck", table);
        CheckConstraint check = new CheckConstraint(name, expression);
        table.addCheckConstraint(check);
        generatedObjects.add(normalize(name));
        return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new AddConstraint(ConstraintDefinition.check(check))));
    }

    /**
     * {@code ALTER TABLE ... ADD COLUMN} plus {@code ADD CONSTRAINT ... FOREIGN KEY}: adds a
     * nullable referencing column and the foreign key on top of it.
     */
    private Optional<DDLStatement> addForeignKey() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_ADD_COLUMN)
                || !dialect.getFeatures().supports(DDLFeature.ALTER_ADD_CONSTRAINT)
                || !dialect.getFeatures().supports(DDLFeature.ALTER_ADD_FOREIGN_KEY)) {
            return Optional.empty();
        }
        if (current.getTables().size() < 2) {
            return Optional.empty();
        }
        Table child = randomTable();
        Table parent = randomTable();
        if (child == null || parent == null || child == parent || parent.getPrimaryKey() == null
                || parent.getPrimaryKey().getColumns().isEmpty()) {
            return Optional.empty();
        }
        List<Column> parentKeyColumns = parent.getPrimaryKeyColumns();
        if (parentKeyColumns.isEmpty() || parentKeyColumns.size() > 2) {
            return Optional.empty();
        }
        List<String> childColumns = new ArrayList<>();
        List<String> parentColumns = new ArrayList<>();
        List<Column> added = new ArrayList<>();
        for (Column parentColumn : parentKeyColumns) {
            String name = freshColumnName(child, parent.getName() + "_" + parentColumn.getName());
            Column column = new Column(name, parentColumn.getType());
            column.setNullable(true);
            child.addColumn(column);
            childColumns.add(name);
            parentColumns.add(parentColumn.getName());
            added.add(column);
            generatedObjects.add(normalize(child.getName()) + "." + normalize(name));
        }
        child.renumberColumns();
        String name = constraintName("fk", child);
        ForeignKey foreignKey = new ForeignKey(childColumns, parent.getName(), parentColumns);
        foreignKey.setName(dialect.getFeatures().supports(DDLFeature.NAMED_FOREIGN_KEY) ? name : null);
        foreignKey.setDerivedFromRelationship("generated");
        child.addForeignKey(foreignKey);
        generatedObjects.add(normalize(name));
        List<AlterTableAction> actions = new ArrayList<>();
        for (Column column : added) {
            actions.add(new AddColumn(column.copy(), false));
        }
        actions.add(new AddConstraint(ConstraintDefinition.foreignKey(foreignKey)));
        return Optional.of(new AlterTable(child.getSchemaName(), child.getName(), actions));
    }

    /** {@code ALTER TABLE ... DROP CONSTRAINT}: only constraints this generator added. */
    private Optional<DDLStatement> dropGeneratedConstraint() {
        if (!dialect.getFeatures().supports(DDLFeature.ALTER_DROP_CONSTRAINT)
                && !dialect.getFeatures().supports(DDLFeature.ALTER_DROP_FOREIGN_KEY)) {
            return Optional.empty();
        }
        List<Table> tables = new ArrayList<>(current.getTables());
        Randomly.shuffle(tables);
        for (Table table : tables) {
            for (UniqueConstraint unique : new ArrayList<>(table.getUniqueConstraints())) {
                if (unique.getName() != null && generatedObjects.contains(normalize(unique.getName()))
                        && !(EngineTraits.requiresIndexForForeignKey(dialect::includesEngine)
                                && foreignKeyUsesColumns(table, unique.getColumns()))) {
                    table.getUniqueConstraints().remove(unique);
                    generatedObjects.remove(normalize(unique.getName()));
                    return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new DropConstraint(unique.getName(),
                            ConstraintDefinition.unique(unique), false, false)));
                }
            }
            for (CheckConstraint check : new ArrayList<>(table.getCheckConstraints())) {
                if (check.getName() != null && generatedObjects.contains(normalize(check.getName()))) {
                    table.getCheckConstraints().remove(check);
                    generatedObjects.remove(normalize(check.getName()));
                    return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new DropConstraint(check.getName(),
                            ConstraintDefinition.check(check), false, false)));
                }
            }
            for (ForeignKey foreignKey : new ArrayList<>(table.getForeignKeys())) {
                if (foreignKey.getName() != null && generatedObjects.contains(normalize(foreignKey.getName()))) {
                    table.getForeignKeys().remove(foreignKey);
                    generatedObjects.remove(normalize(foreignKey.getName()));
                    return Optional.of(new AlterTable(table.getSchemaName(), table.getName(), new DropConstraint(foreignKey.getName(),
                            ConstraintDefinition.foreignKey(foreignKey), false, false)));
                }
            }
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------ index operations */

    private Optional<DDLStatement> createIndex() {
        if (!dialect.getFeatures().supports(DDLFeature.CREATE_INDEX)) {
            return Optional.empty();
        }
        Table table = randomTable();
        if (table == null || table.getColumns().isEmpty()) {
            return Optional.empty();
        }
        List<Column> candidates = new ArrayList<>();
        for (Column column : table.getColumns()) {
            // an engine that cannot index a column of this type rejects the whole statement (H2:
            // "Feature not supported: Index on column ... CHARACTER LARGE OBJECT"), which is a
            // generator defect rather than an engine one; a column of a foreign key or of a key is
            // excluded too, because H2 associates such an index with the constraint it duplicates and
            // then refuses to drop it ("Index ... belongs to constraint UQ_T_2_0")
            if (dialect.isIndexable(column.getType()) && !isReferenced(table, column.getName())
                    && !table.isKeyColumn(column.getName())) {
                candidates.add(column);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int columnCount = Math.min(candidates.size(), Randomly.getInteger(1, 3));
        List<Column> chosen = Randomly.extractNrRandomColumns(candidates, columnCount);
        List<String> columns = new ArrayList<>();
        for (Column column : chosen) {
            columns.add(column.getName());
        }
        boolean unique = Randomly.getBoolean(0.3) && dialect.getFeatures().supports(DDLFeature.CREATE_UNIQUE_INDEX);
        String name = uniqueName("ev_idx_" + table.getName(), existingIndexNames());
        Index index = new Index(name, columns).setUnique(unique);
        if (Randomly.getBoolean(0.2) && dialect.getFeatures().supports(DDLFeature.PARTIAL_INDEX)) {
            Column column = Randomly.fromList(chosen);
            if (column.getType().isNumeric()) {
                index.setPredicate(column.getName() + " >= 0");
            }
        }
        table.addIndex(index);
        generatedObjects.add(normalize(name));
        return Optional.of(new CreateIndex(table.getSchemaName(), table.getName(), index,
                dialect.getFeatures().supports(DDLFeature.CREATE_INDEX_IF_NOT_EXISTS)));
    }

    private Optional<DDLStatement> dropGeneratedIndex() {
        if (!dialect.getFeatures().supports(DDLFeature.DROP_INDEX)) {
            return Optional.empty();
        }
        for (Table table : current.getTables()) {
            for (Index index : new ArrayList<>(table.getIndexes())) {
                if (generatedObjects.contains(normalize(index.getName()))) {
                    table.getIndexes().remove(index);
                    generatedObjects.remove(normalize(index.getName()));
                    return Optional.of(new DropIndex(index.getName(), table.getName(), table.getSchemaName(),
                            dialect.getFeatures().supports(DDLFeature.DROP_INDEX_IF_EXISTS), false));
                }
            }
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------ view operations */

    private Optional<DDLStatement> createView() {
        if (!dialect.getFeatures().supports(DDLFeature.CREATE_VIEW)) {
            return Optional.empty();
        }
        Table table = randomTable();
        if (table == null || table.getColumns().isEmpty()) {
            return Optional.empty();
        }
        List<Column> columns = Randomly.extractNrRandomColumns(new ArrayList<>(table.getColumns()),
                Math.min(2, table.getColumns().size()));
        List<String> columnNames = new ArrayList<>();
        for (Column column : columns) {
            columnNames.add(column.getName());
        }
        String name = uniqueName("ev_v_" + table.getName(), existingViewNames());
        // The view body names its source table, and the view itself is an object in the same
        // namespace as the tables. Leaving either unqualified makes the statement address the
        // engine's default namespace instead: on H2 the CREATE VIEW then fails outright (the table is
        // in the generated schema, not in PUBLIC) and the view is reported as missing, which is a
        // defect of the generator, not of the engine.
        String query = "SELECT " + String.join(", ", columnNames) + " FROM "
                + dialect.qualifyName(table.getSchemaName(), table.getName());
        View view = new View(name, query);
        view.setSchemaName(table.getSchemaName());
        current.addView(view);
        refreshViewDependencies();
        generatedObjects.add(normalize(name));
        return Optional.of(new CreateView(view, dialect.getFeatures().supports(DDLFeature.CREATE_OR_REPLACE_VIEW), false));
    }

    private Optional<DDLStatement> dropGeneratedView() {
        for (View view : new ArrayList<>(current.getViews())) {
            if (generatedObjects.contains(normalize(view.getName()))) {
                current.getViews().remove(view);
                generatedObjects.remove(normalize(view.getName()));
                refreshViewDependencies();
                return Optional.of(new DropView(view.getName(), view.getSchemaName(),
                        dialect.getFeatures().supports(DDLFeature.DROP_IF_EXISTS), false));
            }
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------ table operations */

    private Optional<DDLStatement> createTable() {
        String name = "t_new_" + (counter++);
        Table table = new Table(current.getName(), name);
        String idName = "id";
        Column id = new Column(idName, DataType.bigint(), false);
        if (dialect.getFeatures().supports(DDLFeature.IDENTITY_COLUMN)
                || dialect.getFeatures().supports(DDLFeature.AUTO_INCREMENT)) {
            id.setIdentity(true);
        }
        table.addColumn(id);
        table.setPrimaryKey(new PrimaryKey("pk_" + name, List.of(idName)));
        int extra = Randomly.getInteger(1, 4);
        for (int i = 0; i < extra; i++) {
            Column column = new Column("c" + i, randomType());
            column.setNullable(Randomly.getBoolean(0.6));
            if (Randomly.getBoolean(0.3) && dialect.getFeatures().supports(DDLFeature.DEFAULT_LITERAL)) {
                column.setDefaultExpression(defaultLiteralFor(column.getType()));
            }
            table.addColumn(column);
        }
        table.setDerivedFromEntity("generated");
        table.setMappingStrategy("GENERATED");
        applyEffectiveTypes(table);
        current.addTable(table);
        generatedObjects.add(normalize(name));
        return Optional.of(new CreateTable(table, dialect.getFeatures().supports(DDLFeature.IF_NOT_EXISTS)));
    }

    /**
     * Replaces every column type with the type the dialect will actually store.
     *
     * <p>{@link Dialect#effectiveType} exists because some engines cannot store what they are asked
     * for: SQLite renders an integral identity primary key as
     * {@code INTEGER PRIMARY KEY AUTOINCREMENT}, so a {@code BIGINT} column comes back as
     * {@code INTEGER}. The create script gets this treatment from
     * {@link org.ddlsmith.ddl.gen.SchemaAdapter}; a table this generator adds has to get it too, or
     * the model claims a type the engine never had and the evolution comparison reports a divergence
     * the engine did not cause.</p>
     */
    private void applyEffectiveTypes(Table table) {
        for (Column column : table.getColumns()) {
            column.setType(dialect.effectiveType(column.getType(), column, table));
        }
    }

    /** {@code DROP TABLE}: only tables this generator created and that nothing references. */
    private Optional<DDLStatement> dropGeneratedTable() {
        if (!dialect.getFeatures().supports(DDLFeature.CASCADE_DROP)) {
            // without CASCADE the table must be unreferenced
        }
        for (Table table : new ArrayList<>(current.getTables())) {
            String name = normalize(table.getName());
            if (!generatedObjects.contains(name) || originalTables.contains(name)) {
                continue;
            }
            if (tablesReferencedByViews.contains(name)) {
                // a view reads from this table, so dropping it would leave the view invalid; SQLite
                // then refuses every later statement with "error in view ...: no such table"
                continue;
            }
            boolean referenced = false;
            for (Table other : current.getTables()) {
                for (ForeignKey foreignKey : other.getForeignKeys()) {
                    if (normalize(foreignKey.getReferencedTable()).equals(name)) {
                        referenced = true;
                        break;
                    }
                }
            }
            if (referenced) {
                continue;
            }
            current.removeTable(table.getName());
            generatedObjects.remove(name);
            return Optional.of(new DropTable(table.getSchemaName(), table.getName(),
                    dialect.getFeatures().supports(DDLFeature.DROP_IF_EXISTS), false));
        }
        return Optional.empty();
    }

    private Optional<DDLStatement> truncateTable() {
        if (!dialect.getFeatures().supports(DDLFeature.TRUNCATE_TABLE)) {
            return Optional.empty();
        }
        Table table = randomTable();
        if (table == null) {
            return Optional.empty();
        }
        if (hasDependents(table)) {
            // H2 answers "Cannot truncate <table>" for a table an index or a foreign key depends on,
            // and DuckDB fails on a referenced table with a foreign key violation: the statement is a
            // generator defect, and the crash oracle would report it as an engine one
            return Optional.empty();
        }
        return Optional.of(new TruncateTable(table.getSchemaName(), table.getName(), false, false));
    }

    private Optional<DDLStatement> createSequence() {
        if (!dialect.getFeatures().supports(DDLFeature.CREATE_SEQUENCE)) {
            return Optional.empty();
        }
        String name = "seq_new_" + (counter++);
        Sequence sequence = new Sequence(name).setStart(Randomly.getInteger(1, 100))
                .setIncrement(Randomly.getInteger(1, 10));
        sequence.setSchemaName(current.getName());
        current.addSequence(sequence);
        generatedObjects.add(normalize(name));
        return Optional.of(new CreateSequence(sequence, false));
    }

    private Optional<DDLStatement> dropGeneratedSequence() {
        if (!dialect.getFeatures().supports(DDLFeature.DROP_SEQUENCE)) {
            return Optional.empty();
        }
        for (Sequence sequence : new ArrayList<>(current.getSequences())) {
            if (generatedObjects.contains(normalize(sequence.getName()))) {
                current.getSequences().remove(sequence);
                generatedObjects.remove(normalize(sequence.getName()));
                return Optional.of(new DropSequence(sequence.getSchemaName(), sequence.getName(), true, false));
            }
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------ helpers */

    private Table randomTable() {
        if (current.getTables().isEmpty()) {
            return null;
        }
        return Randomly.fromList(current.getTables());
    }

    /**
     * @return a table that may be altered, or null when there is none.
     *
     * <p>On an engine that refuses to alter a table with dependents (see
     * {@link EngineTraits#alterRequiresNoDependents}) a table that carries an index or that a foreign
     * key points at is excluded, because the statement would be rejected — and the crash oracle would
     * report the rejection as an engine defect. {@code ADD COLUMN} is the one operation such an engine
     * does allow, so it keeps using {@link #randomTable()}.</p>
     */
    private Table alterableTable() {
        if (!EngineTraits.alterRequiresNoDependents(dialect.getName())) {
            return randomTable();
        }
        List<Table> candidates = new ArrayList<>();
        for (Table table : current.getTables()) {
            if (!hasDependents(table)) {
                candidates.add(table);
            }
        }
        return candidates.isEmpty() ? null : Randomly.fromList(candidates);
    }

    /** @return true when an index, a unique constraint or a foreign key of another table depends on it */
    private boolean hasDependents(Table table) {
        if (!table.getIndexes().isEmpty()) {
            return true;
        }
        if (!table.getUniqueConstraints().isEmpty()) {
            // a unique constraint is an index on the engines this rule exists for, and DuckDB refuses
            // to drop a column an index covers ("an index depends on a column after it")
            return true;
        }
        String name = normalize(table.getName());
        for (Table other : current.getTables()) {
            for (ForeignKey foreignKey : other.getForeignKeys()) {
                if (normalize(foreignKey.getReferencedTable()).equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return a table whose columns may be renamed or dropped, or null when there is none.
     *
     * <p>A table a view reads from is excluded on every engine, because the view body names its
     * columns: renaming or dropping one would leave the view referring to something that no longer
     * exists. SQLite refuses the statement outright ({@code error in view ...: no such column}), and
     * on the engines that accept it the view and the model would silently disagree. On an engine that
     * refuses to alter a table with dependents, an indexed table and one a foreign key points at are
     * excluded as well.</p>
     */
    private Table tableForColumnAlteration() {
        List<Table> candidates = new ArrayList<>();
        for (Table table : current.getTables()) {
            if (tablesReferencedByViews.contains(normalize(table.getName()))) {
                continue;
            }
            if (EngineTraits.alterRequiresNoDependents(dialect.getName()) && hasDependents(table)) {
                continue;
            }
            candidates.add(table);
        }
        return candidates.isEmpty() ? null : Randomly.fromList(candidates);
    }

    /**
     * @return true when the engine will accept this column with the widened type
     *
     * <p>The last VARCHAR widening step is {@code VARCHAR(>200) -> TEXT}, and on an engine that
     * cannot index TEXT that step is illegal on a column the engine is indexing. Measured on MySQL
     * 26.7.0, evolution seed 2200072: the algorithm widened a generated VARCHAR(255) foreign key
     * column to TEXT and InnoDB answered
     * {@code ERROR 1170: BLOB/TEXT column 't_4_r_t_1_a1' used in key specification without a key
     * length}, which the crash oracle then reported as MySQL rejecting a statement it declares.</p>
     *
     * <p>Two different routes put a column in that position, which is why the test is not just "is
     * this column a foreign key column". InnoDB indexes a foreign key's columns implicitly, and that
     * index is not an entry in the model, so it is invisible to {@link #isReferenced}. And the index
     * <em>outlives the foreign key</em>: {@code ALTER TABLE t_1 DROP FOREIGN KEY fk_t_1_4} leaves it
     * in place, so the same 1170 comes back later even though no foreign key names the column any
     * more - the model entry that explained the index is gone and nothing in the schema records that
     * the server still indexes it.</p>
     *
     * <p>On an engine with that behaviour the whole VARCHAR → TEXT step therefore gives way: the
     * cost is that a VARCHAR(>200) column stops being widened, and the alternative is emitting a
     * statement the engine refuses. The predicate is the dialect's own {@code isIndexable}, so the
     * generator and the adapter cannot disagree about which types a key column may have.</p>
     */
    private boolean wideningIsSafe(Table table, Column column, DataType widened) {
        if (dialect.isIndexable(widened)) {
            return true;
        }
        if (EngineTraits.requiresIndexForForeignKey(dialect::includesEngine)) {
            return false;
        }
        return !isForeignKeyColumn(table, column.getName());
    }

    /**
     * @return true when the column is one of a foreign key's own columns, i.e. the side the engine has
     *         to index rather than the side it has to make unique
     *
     * <p>{@link #isReferenced} answers a different question - "does anything depend on this column" -
     * and the two are not interchangeable: a foreign key's <em>child</em> columns are what InnoDB
     * indexes, and a key column of this table is excluded by the caller before it gets here.</p>
     */
    private static boolean isForeignKeyColumn(Table table, String columnName) {
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            for (String column : foreignKey.getColumns()) {
                if (column.equalsIgnoreCase(columnName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isReferenced(Table table, String columnName) {
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            if (foreignKey.getColumns().contains(columnName)) {
                return true;
            }
        }
        for (Table other : current.getTables()) {
            for (ForeignKey foreignKey : other.getForeignKeys()) {
                if (normalize(foreignKey.getReferencedTable()).equals(normalize(table.getName()))
                        && foreignKey.getReferencedColumns().contains(columnName)) {
                    return true;
                }
            }
        }
        for (Index index : table.getIndexes()) {
            if (index.getColumns().contains(columnName) || index.getIncludeColumns().contains(columnName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when dropping this column would shift a column an outgoing foreign key uses.
     *
     * <p>DuckDB builds an index for the referencing side of a foreign key and refuses to drop a column
     * that precedes one of its columns ({@code Cannot drop this column: an index depends on a column
     * after it!}); a column that comes after the foreign key column can be dropped. Probed directly:
     * with {@code FOREIGN KEY (t_3_a6)}, dropping {@code c_new} or {@code a1} fails and dropping
     * {@code a17} — which comes after — succeeds.</p>
     */
    private static boolean precedesForeignKeyColumn(Table table, String columnName) {
        int position = positionOf(table, columnName);
        if (position < 0) {
            return false;
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            for (String foreignKeyColumn : foreignKey.getColumns()) {
                if (positionOf(table, foreignKeyColumn) > position) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int positionOf(Table table, String columnName) {
        for (int i = 0; i < table.getColumns().size(); i++) {
            if (table.getColumns().get(i).getName().equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isChecked(Table table, String columnName) {
        for (CheckConstraint check : table.getCheckConstraints()) {
            if (check.getExpression() != null
                    && check.getExpression().toLowerCase(Locale.ROOT).contains(columnName.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when a foreign key's referencing columns are exactly these, so that the index
     *         implementing this constraint is the one the engine keeps for the foreign key
     */
    private static boolean foreignKeyUsesColumns(Table table, List<String> columns) {
        List<String> wanted = new ArrayList<>();
        for (String column : columns) {
            wanted.add(column.toLowerCase(Locale.ROOT));
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            List<String> referencing = new ArrayList<>();
            for (String column : foreignKey.getColumns()) {
                referencing.add(column.toLowerCase(Locale.ROOT));
            }
            if (referencing.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when a generated column computes from this column, which MySQL refuses to rename:
     *         {@code Column 'a1' has a generated column dependency} (error 3108)
     *
     * <p>The rename candidate list only excluded columns a foreign key or a key uses, so a base column
     * of a generated column was renamed happily and the engine rejected the statement.</p>
     */
    private static boolean isGeneratedDependency(Table table, String columnName) {
        String wanted = columnName.toLowerCase(Locale.ROOT);
        for (Column column : table.getColumns()) {
            if (!column.isGenerated() || column.getGeneratedExpression() == null) {
                continue;
            }
            if (column.getGeneratedExpression().toLowerCase(Locale.ROOT).contains(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return a name that no object of the schema currently uses.
     *
     * <p>The generator's own names must not collide with the ones the mapper produced. A collision is
     * not harmless: {@code CREATE INDEX IF NOT EXISTS <name>} on an engine that treats index names as
     * schema-global silently does nothing when the name is taken, so the in-memory model would claim an
     * index the database never created — and the round-trip oracle would then report a divergence that
     * DDLSmith itself caused.</p>
     */
    private String uniqueName(String prefix, Set<String> taken) {
        String candidate = prefix + "_" + (counter++);
        while (taken.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = prefix + "_" + (counter++);
        }
        return candidate;
    }

    private Set<String> existingIndexNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Table table : current.getTables()) {
            for (Index index : table.getIndexes()) {
                names.add(index.getName().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private Set<String> existingViewNames() {
        Set<String> names = new LinkedHashSet<>();
        for (View view : current.getViews()) {
            names.add(view.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }
    private String freshColumnName(Table table) {
        return freshColumnName(table, "c_new");
    }

    private String freshColumnName(Table table, String base) {
        String candidate = base;
        int suffix = 0;
        while (table.hasColumn(candidate)) {
            candidate = base + "_" + (++suffix);
        }
        return candidate;
    }

    /**
     * @return a constraint name that the table does not already use.
     *
     * <p>The counter alone is not enough: the create script already gave the table constraints named
     * {@code uq_<table>_0} and {@code ck_<table>_0}, so the first generated one collided with an
     * existing name and H2 rejected it with {@code Constraint "UQ_T_3_0" already exists} — reported by
     * the crash oracle as an engine defect.</p>
     */
    private String constraintName(String prefix, Table table) {
        String candidate = prefix + "_" + table.getName() + "_" + (counter++);
        while (tableUsesConstraintName(table, candidate)) {
            candidate = prefix + "_" + table.getName() + "_" + (counter++);
        }
        return candidate;
    }

    private static boolean tableUsesConstraintName(Table table, String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (unique.getName() != null && unique.getName().toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        for (CheckConstraint check : table.getCheckConstraints()) {
            if (check.getName() != null && check.getName().toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            if (foreignKey.getName() != null && foreignKey.getName().toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return table.getPrimaryKey() != null && table.getPrimaryKey().getName() != null
                && table.getPrimaryKey().getName().toLowerCase(Locale.ROOT).equals(normalized);
    }

    private DataType randomType() {
        List<DataType> candidates = new ArrayList<>();
        candidates.add(DataType.integer());
        candidates.add(DataType.bigint());
        candidates.add(DataType.varchar(20));
        candidates.add(DataType.varchar(100));
        candidates.add(DataType.bool());
        candidates.add(DataType.decimal(10, 2));
        candidates.add(DataType.date());
        candidates.add(DataType.timestamp());
        DataType chosen = Randomly.fromList(candidates);
        return SchemaAdapter.adaptType(chosen, dialect, notes);
    }

    private String defaultLiteralFor(DataType type) {
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
            return String.valueOf(Randomly.getInteger(0, 100));
        case DECIMAL:
        case REAL:
        case DOUBLE:
            return String.valueOf(Randomly.getInteger(0, 100));
        case BOOLEAN:
            return Randomly.getBoolean() ? "TRUE" : "FALSE";
        case DATE:
            return "'2000-01-01'";
        case TIME:
            return "'00:00:00'";
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return dialect.getFeatures().supports(DDLFeature.DEFAULT_CURRENT_TIMESTAMP) ? "CURRENT_TIMESTAMP"
                    : "'2000-01-01 00:00:00'";
        default:
            return "'" + Randomly.getStringWithLength(3) + "'";
        }
    }

    /**
     * @return a strictly wider type of the same family, or null when the column cannot be widened
     *         (which makes the caller skip the column rather than emit a statement the engine refuses)
     */
    private DataType widen(DataType type) {
        switch (type.getCategory()) {
        case SMALLINT:
            return DataType.integer();
        case INTEGER:
            return DataType.bigint();
        case CHAR:
            return DataType.varchar(type.getLength() == null ? 50 : type.getLength() * 2);
        case VARCHAR:
            if (type.getLength() == null || type.getLength() < 200) {
                return DataType.varchar(type.getLength() == null ? 100 : type.getLength() * 2);
            }
            return DataType.text();
        case REAL:
            return DataType.doublePrecision();
        case DECIMAL:
            // every step adds five digits, so a column that is widened repeatedly walks past what the
            // engine can express; DuckDB stops at 38 and answers "Width must be between 1 and 38!"
            int precision = type.getPrecision() == null ? 20 : type.getPrecision() + 5;
            if (precision > EngineTraits.maxDecimalPrecision(dialect.getName())) {
                return null;
            }
            return DataType.decimal(precision, type.getScale() == null ? 2 : type.getScale());
        default:
            return null;
        }
    }

    private static String normalize(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }
}
