package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.dbms.Dialects;
import org.ddlsmith.ddl.AlterTable;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.ddlsmith.ddl.gen.RandomDDLGenerator;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.introspection.SchemaNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for defects that the hour-long evolution runs exposed: statements that addressed
 * the wrong namespace, a key type that was rewritten without the foreign keys following it, an
 * added column that carried a constraint the engine refuses, and a model that kept a statement the
 * engine had rejected.
 *
 * <p>Each of them made DDLSmith report a divergence the engine had not caused — the most expensive
 * kind of defect a testing tool can have.</p>
 */
class GeneratorRegressionTest {

    /**
     * The evolution runs reported {@code MISSING_VIEW} on H2 in 47 databases and {@code
     * UNEXPECTED_TABLE} in 14: the generated {@code CREATE VIEW}, {@code DROP TABLE} and {@code
     * TRUNCATE} statements named their objects without the schema, so they addressed H2's default
     * namespace instead of the one the tables live in. On H2 the view then failed to be created, the
     * drop silently did nothing, and the truncate was rejected — and the crash oracle blamed the
     * engine for a statement DDLSmith had rendered wrongly.
     */
    @Test
    @DisplayName("statements that address generated objects name their schema")
    void generatedStatementsNameTheSchema() {
        Dialect h2 = Dialects.forName("h2");
        int checked = 0;
        for (long seed = 0; seed < 30; seed++) {
            Randomly.setSeed(seed);
            RandomDDLGenerator generator = new RandomDDLGenerator(schemaInNamespace("ddlsmith"), h2, new MainOptions());
            for (DDLStatement statement : generator.generate(120)) {
                for (String sql : h2.renderAll(statement)) {
                    if (!mentionsGeneratedObject(sql)) {
                        continue;
                    }
                    checked++;
                    assertTrue(sql.contains("ddlsmith."),
                            "a statement about a generated object must name its schema: " + sql
                                    + " [" + statement.getClass().getSimpleName() + " "
                                    + (statement instanceof org.ddlsmith.ddl.DropSequence drop
                                            ? "schema=" + drop.getSchemaName() : "")
                                    + "]");
                }
            }
        }
        assertTrue(checked > 20, "the run must actually exercise these statements, saw " + checked);
    }

    /** @return true for the statement kinds that address a table, view or sequence by name */
    private static boolean mentionsGeneratedObject(String sql) {
        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("CREATE OR REPLACE VIEW") || upper.startsWith("CREATE VIEW")
                || upper.startsWith("DROP VIEW") || upper.startsWith("DROP TABLE") || upper.startsWith("TRUNCATE")
                || upper.startsWith("DROP SEQUENCE") || upper.startsWith("CREATE SEQUENCE");
    }

    /**
     * A key type can be rewritten in several steps: the surrogate key of a weak entity is aligned to
     * the key it references, and a table that references <em>that</em> column is a step further down
     * the chain. A single alignment pass left the last link at {@code BIGINT} while the column it
     * pointed at had become {@code INTEGER}, and DuckDB refused the table with "incompatible types
     * between column id_2 (INTEGER) and column t_6_id_2 (BIGINT)" — a schema DDLSmith could have
     * expressed correctly.
     */
    @Test
    @DisplayName("a rewritten key type propagates along a chain of foreign keys")
    void keyTypeRewritesPropagateAlongTheChain() {
        RelationalSchema schema = new RelationalSchema("ddlsmith");
        // t_3.id_2 is an identity primary key: SQLite stores it as INTEGER whatever it was asked for
        Table root = new Table("ddlsmith", "t_3");
        root.addColumn(new Column("id_2", DataType.bigint()).setNullable(false).setIdentity(true));
        root.setPrimaryKey(new PrimaryKey("pk_t_3", List.of("id_2")));
        schema.addTable(root);

        // the middle table references the root, and the last table references the middle one; it is
        // added before the middle table so that a single pass would look at it too early
        Table leaf = new Table("ddlsmith", "t_1");
        leaf.addColumn(new Column("id", DataType.bigint()).setNullable(false));
        leaf.addColumn(new Column("t_6_id_2", DataType.bigint()));
        leaf.setPrimaryKey(new PrimaryKey("pk_t_1", List.of("id")));
        leaf.addForeignKey(new ForeignKey(List.of("t_6_id_2"), "t_6", List.of("id_2")).setName("fk_t_1"));
        schema.addTable(leaf);

        Table middle = new Table("ddlsmith", "t_6");
        middle.addColumn(new Column("id_2", DataType.bigint()).setNullable(false));
        middle.setPrimaryKey(new PrimaryKey("pk_t_6", List.of("id_2")));
        middle.addForeignKey(new ForeignKey(List.of("id_2"), "t_3", List.of("id_2")).setName("fk_t_6"));
        schema.addTable(middle);

        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(schema, Dialects.forName("sqlite"), notes);

        DataType rootType = adapted.getTable("t_3").getColumn("id_2").getType();
        DataType middleType = adapted.getTable("t_6").getColumn("id_2").getType();
        DataType leafType = adapted.getTable("t_1").getColumn("t_6_id_2").getType();
        assertEquals(rootType, middleType, "the middle link must follow the key it references: " + notes);
        assertEquals(rootType, leafType,
                "the last link of the chain must follow too, or the engine rejects the foreign key: " + notes);
    }

    /**
     * DuckDB answers {@code Adding columns with constraints not yet supported} for {@code ALTER TABLE
     * ... ADD COLUMN} with NOT NULL, CHECK, UNIQUE or GENERATED. The generator emitted them anyway,
     * the column was never created, and the evolution oracle reported the missing column as a
     * divergence in dozens of databases.
     */
    @Test
    @DisplayName("DuckDB is never asked for a constraint on an added column")
    void duckdbIsNeverAskedForAConstraintOnAnAddedColumn() {
        Dialect duckdb = Dialects.forName("duckdb");
        int added = 0;
        for (long seed = 0; seed < 60; seed++) {
            Randomly.setSeed(seed);
            RandomDDLGenerator generator = new RandomDDLGenerator(schemaInNamespace("main"), duckdb, new MainOptions());
            for (DDLStatement statement : generator.generate(80)) {
                if (!(statement instanceof AlterTable alterTable)) {
                    continue;
                }
                for (AlterTableAction action : alterTable.getActions()) {
                    if (!(action instanceof AddColumn addColumn)) {
                        continue;
                    }
                    added++;
                    Column column = addColumn.getColumn();
                    assertTrue(column.isNullable(),
                            "DuckDB refuses a NOT NULL column added to an existing table: " + column.getName());
                    assertNull(column.getCheckExpression(),
                            "DuckDB refuses a CHECK on an added column: " + column.getName());
                    assertFalse(column.isUnique(), "DuckDB refuses a UNIQUE added column: " + column.getName());
                    assertFalse(column.isGenerated(), "DuckDB refuses a GENERATED added column: " + column.getName());
                }
            }
        }
        assertTrue(added > 20, "the run must actually add columns, saw " + added);
    }

    /**
     * The evolution algorithm executes a statement and then compares the catalog with the model. When
     * the engine <em>rejected</em> the statement, the model had still been changed, so every rejected
     * statement became a divergence — which is a finding about DDLSmith, and one that hides the
     * rejection itself.
     */
    @Test
    @DisplayName("a statement the engine rejected can be undone in the model")
    void aRejectedStatementCanBeUndone() {
        int changing = 0;
        for (long seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            RandomDDLGenerator generator = new RandomDDLGenerator(schemaInNamespace("ddlsmith"),
                    TestDialect.permissive(), new MainOptions());
            // move away from the starting state, so that the statement under test is not the first
            generator.generate(5);
            RelationalSchema before = generator.getCurrentSchema().copy();

            DDLStatement statement = generator.nextStatement().orElse(null);
            assertNotNull(statement, "a generated statement is expected");
            boolean changed = !SchemaNormalizer.diff(before, generator.getCurrentSchema()).isEmpty()
                    || !before.getStatistics().toString()
                            .equals(generator.getCurrentSchema().getStatistics().toString());
            if (changed) {
                changing++;
            }

            generator.discardLastStatement();
            assertEquals(before.getStatistics().toString(), generator.getCurrentSchema().getStatistics().toString(),
                    "the model must be back where it was after " + statement.describe() + " (seed " + seed + ")");
            List<org.ddlsmith.introspection.SchemaDifference> differences = SchemaNormalizer.diff(before,
                    generator.getCurrentSchema());
            assertTrue(differences.isEmpty(), "and structurally identical, but "
                    + SchemaNormalizer.render(differences) + " (seed " + seed + ")");
        }
        assertTrue(changing > 20,
                "the rollback must be exercised on statements that really change the model, only " + changing
                        + " of 40 did");
    }

    /**
     * DuckDB refuses every {@code ALTER TABLE} except {@code ADD COLUMN} on a table that carries an
     * index or that a foreign key points at: {@code Dependency Error: Cannot alter entry "t" because
     * there are entries that depend on it}. The generator asked anyway, so the crash oracle reported
     * DuckDB as rejecting statements it declares support for — the whole remaining output of an
     * hour-long evolution run on DuckDB was made of these false accusations.
     */
    @Test
    @DisplayName("DuckDB is never asked to alter a table that has dependents")
    void duckdbIsNeverAskedToAlterATableWithDependents() {
        Dialect duckdb = Dialects.forName("duckdb");
        int altered = 0;
        for (long seed = 0; seed < 60; seed++) {
            Randomly.setSeed(seed);
            RandomDDLGenerator generator = new RandomDDLGenerator(schemaInNamespace("main"), duckdb, new MainOptions());
            for (DDLStatement statement : generator.generate(80)) {
                if (!(statement instanceof AlterTable alterTable)) {
                    continue;
                }
                boolean addsOnly = alterTable.getActions().stream().allMatch(action -> action instanceof AddColumn);
                if (addsOnly) {
                    continue;
                }
                altered++;
                // t_0 is referenced by t_1's foreign key from the start, so no other ALTER may target it
                assertFalse(alterTable.getTableName().equalsIgnoreCase("t_0"),
                        "DuckDB refuses this statement, because a foreign key points at t_0: "
                                + duckdb.render(statement));
            }
        }
        assertTrue(altered > 20, "the run must actually alter tables, saw " + altered);
    }

    /**
     * Three rules the hour-long evolution runs showed to be missing, each of which made the crash
     * oracle report an engine defect that was DDLSmith's:
     *
     * <ul>
     *   <li>a view reads from a table, so the table and its columns must stay: SQLite otherwise
     *       refuses every later statement with {@code error in view ...: no such table};</li>
     *   <li>a constraint name must not collide with one the create script already used — H2 answered
     *       {@code Constraint "UQ_T_3_0" already exists} in 14 databases of one run;</li>
     *   <li>a column an engine cannot index must not be put in a unique constraint or an index — H2
     *       answered {@code Feature not supported: Index on column ... CHARACTER LARGE OBJECT}.</li>
     * </ul>
     */
    @Test
    @DisplayName("the generator does not break its own views, names or indexable columns")
    void generatedStatementsRespectTheSchemaTheyBuild() {
        Dialect h2 = Dialects.forName("h2");
        int statements = 0;
        for (long seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            RandomDDLGenerator generator = new RandomDDLGenerator(schemaWithViewAndClobColumn("ddlsmith"), h2,
                    new MainOptions());
            for (DDLStatement statement : generator.generate(120)) {
                statements++;
                if (statement instanceof org.ddlsmith.ddl.DropTable drop) {
                    assertFalse(drop.getTableName().equalsIgnoreCase("t_0"),
                            "a view reads from t_0, so it must not be dropped: " + h2.render(statement));
                }
                if (!(statement instanceof AlterTable alterTable)) {
                    continue;
                }
                for (AlterTableAction action : alterTable.getActions()) {
                    if (action instanceof org.ddlsmith.ddl.alter.RenameColumn
                            || action instanceof org.ddlsmith.ddl.alter.DropColumn) {
                        assertFalse(alterTable.getTableName().equalsIgnoreCase("t_0"),
                                "a view names t_0's columns, so they must not be renamed or dropped: "
                                        + h2.render(statement));
                    }
                    if (action instanceof org.ddlsmith.ddl.alter.AddConstraint addConstraint) {
                        String name = addConstraint.getDefinition().getName();
                        assertFalse("uq_t_0_0".equalsIgnoreCase(name),
                                "the create script already used that name: " + h2.render(statement));
                    }
                }
                if (statement instanceof org.ddlsmith.ddl.CreateIndex createIndex) {
                    for (String column : createIndex.getIndex().getColumns()) {
                        assertFalse("payload".equalsIgnoreCase(column),
                                "H2 cannot index a CHARACTER LARGE OBJECT column: " + h2.render(statement));
                    }
                }
            }
        }
        assertTrue(statements > 200, "the run must generate statements, saw " + statements);
    }

    /** The fixture schema plus a view over {@code t_0}, a pre-existing constraint name and a CLOB column. */
    private static RelationalSchema schemaWithViewAndClobColumn(String namespace) {
        RelationalSchema schema = schemaInNamespace(namespace);
        Table table = schema.getTable("t_0");
        table.addColumn(new Column("payload", DataType.text()));
        table.addUniqueConstraint(new org.ddlsmith.common.schema.UniqueConstraint("uq_t_0_0", List.of("label")));
        schema.addView(new View("v_0", "SELECT id, value_col FROM " + namespace + ".t_0").setSchemaName(namespace));
        return schema;
    }

    /**
     * Two more DuckDB rules the acceptance runs exposed, both reported as engine defects at high
     * confidence: it refuses to drop a column an index or a unique constraint covers ({@code an index
     * depends on a column after it}) and to change the type of a column a {@code CHECK} constraint
     * mentions ({@code Cannot change the type of a column that has a CHECK constraint specified}).
     */
    @Test
    @DisplayName("DuckDB is never asked to drop a covered column or retype a checked one")
    void duckdbColumnOperationsRespectItsRules() {
        Dialect duckdb = Dialects.forName("duckdb");
        int statements = 0;
        for (long seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            RandomDDLGenerator generator = new RandomDDLGenerator(schemaWithCheckedTable("main"), duckdb,
                    new MainOptions());
            for (DDLStatement statement : generator.generate(120)) {
                statements++;
                if (!(statement instanceof AlterTable alterTable)) {
                    continue;
                }
                for (AlterTableAction action : alterTable.getActions()) {
                    if (action instanceof org.ddlsmith.ddl.alter.DropColumn
                            && alterTable.getTableName().equalsIgnoreCase("t_0")) {
                        // t_0 carries a unique constraint from the start
                        assertTrue(false, "t_0 has a unique constraint, which DuckDB implements as an index: "
                                + duckdb.render(statement));
                    }
                    if (action instanceof org.ddlsmith.ddl.alter.DropColumn dropColumn
                            && alterTable.getTableName().equalsIgnoreCase("t_1")) {
                        assertFalse(dropColumn.getColumnName().equalsIgnoreCase("t_0_id")
                                || dropColumn.getColumnName().equalsIgnoreCase("id"),
                                "a key column and a foreign key column are never dropped: "
                                        + duckdb.render(statement));
                    }
                    if (action instanceof org.ddlsmith.ddl.alter.AlterColumnType alterColumnType
                            && alterTable.getTableName().equalsIgnoreCase("t_2")
                            && alterColumnType.getColumnName().equalsIgnoreCase("amount")) {
                        assertTrue(false, "t_2.amount is named in a CHECK constraint: " + duckdb.render(statement));
                    }
                }
            }
        }
        assertTrue(statements > 200, "the run must generate statements, saw " + statements);
    }

    /** The fixture schema plus a table whose column is named in a CHECK constraint and nothing else. */
    private static RelationalSchema schemaWithCheckedTable(String namespace) {
        RelationalSchema schema = schemaInNamespace(namespace);
        Table table = schema.getTable("t_0");
        table.addUniqueConstraint(new org.ddlsmith.common.schema.UniqueConstraint("uq_t_0_0", List.of("label")));

        Table checked = new Table(namespace, "t_2");
        checked.addColumn(new Column("id", DataType.bigint()).setNullable(false));
        checked.addColumn(new Column("amount", DataType.integer()));
        checked.setPrimaryKey(new PrimaryKey("pk_t_2", List.of("id")));
        checked.addCheckConstraint(new org.ddlsmith.common.schema.CheckConstraint("ck_t_2_0", "amount >= 0"));
        schema.addTable(checked);
        return schema;
    }

    /**
     * An engine drops a {@code CHECK} constraint together with the column it mentions. The generator
     * dropped such a column anyway, kept the constraint in its model, and the next catalog comparison
     * reported the constraint as missing: H2 answered exactly that in
     * {@code runs/round1/evolution/logs/h2/database72-failure-001-schema_mismatch.log} after
     * {@code ADD CONSTRAINT ck_t_2_3 CHECK (t_4_r_id <> 4)} followed by
     * {@code DROP COLUMN IF EXISTS t_4_r_id}. The statement must simply not be generated.
     */
    @Test
    @DisplayName("a column named in a CHECK constraint is never dropped")
    void aCheckedColumnIsNeverDropped() {
        Dialect h2 = Dialects.forName("h2");
        int statements = 0;
        for (long seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            RandomDDLGenerator generator = new RandomDDLGenerator(schemaWithCheckedTable("ddlsmith"), h2,
                    new MainOptions());
            for (DDLStatement statement : generator.generate(120)) {
                statements++;
                if (!(statement instanceof AlterTable alterTable)) {
                    continue;
                }
                for (AlterTableAction action : alterTable.getActions()) {
                    if (action instanceof org.ddlsmith.ddl.alter.DropColumn dropColumn) {
                        assertFalse(dropColumn.getColumnName().equalsIgnoreCase("amount"),
                                "t_2.amount is named in a CHECK constraint, so dropping it would take the "
                                        + "constraint with it and leave the model claiming it: " + h2.render(statement));
                    }
                }
            }
        }
        assertTrue(statements > 200, "the run must generate statements, saw " + statements);
    }

    /**
     * A widening type change adds five digits to a DECIMAL on every step, so a column that is widened
     * repeatedly walks past what the engine can express. DuckDB stops at 38 and answered
     * {@code Parser Error: Width must be between 1 and 38!} for {@code DECIMAL(40,2)}, which the crash
     * oracle reported as an engine defect.
     */
    @Test
    @DisplayName("a DECIMAL is never widened past what the engine accepts")
    void decimalWideningStopsAtTheEngineLimit() {
        for (String engine : new String[] { "duckdb", "mysql" }) {
            Dialect dialect = Dialects.forName(engine);
            int limit = org.ddlsmith.ddl.gen.EngineTraits.maxDecimalPrecision(engine);
            int widened = 0;
            for (long seed = 0; seed < 40; seed++) {
                Randomly.setSeed(seed);
                RandomDDLGenerator generator = new RandomDDLGenerator(schemaWithDecimalColumn("main"), dialect,
                        new MainOptions());
                for (DDLStatement statement : generator.generate(150)) {
                    if (!(statement instanceof AlterTable alterTable)) {
                        continue;
                    }
                    for (AlterTableAction action : alterTable.getActions()) {
                        if (!(action instanceof org.ddlsmith.ddl.alter.AlterColumnType alterColumnType)) {
                            continue;
                        }
                        DataType type = alterColumnType.getNewType();
                        if (type == null || type.getPrecision() == null) {
                            continue;
                        }
                        widened++;
                        assertTrue(type.getPrecision() <= limit,
                                engine + " accepts at most " + limit + " digits: " + dialect.render(statement));
                    }
                }
            }
            assertTrue(widened > 0, engine + ": the run must actually widen a DECIMAL, saw " + widened);
        }
    }

    /** The fixture schema plus a DECIMAL column that can be widened over and over. */
    private static RelationalSchema schemaWithDecimalColumn(String namespace) {
        RelationalSchema schema = schemaInNamespace(namespace);
        Table table = schema.getTable("t_1");
        table.addColumn(new Column("amount", DataType.decimal(10, 2)));
        return schema;
    }

    /** A schema whose tables, views and sequences live in the given namespace. */
    private static RelationalSchema schemaInNamespace(String namespace) {        RelationalSchema schema = new RelationalSchema(namespace);
        Table table = new Table(namespace, "t_0");
        table.addColumn(new Column("id", DataType.bigint()).setNullable(false));
        table.addColumn(new Column("value_col", DataType.integer()));
        table.addColumn(new Column("label", DataType.varchar(20)));
        table.setPrimaryKey(new PrimaryKey("pk_t_0", List.of("id")));
        schema.addTable(table);

        Table child = new Table(namespace, "t_1");
        child.addColumn(new Column("id", DataType.bigint()).setNullable(false));
        child.addColumn(new Column("t_0_id", DataType.bigint()));
        child.setPrimaryKey(new PrimaryKey("pk_t_1", List.of("id")));
        child.addForeignKey(new ForeignKey(List.of("t_0_id"), "t_0", List.of("id")).setName("fk_t_1"));
        schema.addTable(child);

        schema.addView(new View("v_0", "SELECT id FROM " + namespace + ".t_0").setSchemaName(namespace));
        return schema;
    }
}
