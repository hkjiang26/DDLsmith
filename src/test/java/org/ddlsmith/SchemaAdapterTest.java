package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.dbms.mysql.MySqlDialect;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a schema is degraded to exactly the constructs a dialect can express, because the
 * round-trip oracle treats the adapted schema as the ground truth.
 */
class SchemaAdapterTest {

    @Test
    void permissiveDialectKeepsEverything() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(), TestDialect.permissive(), notes);
        Table table = adapted.getTable("t");
        assertTrue(notes.isEmpty(), "nothing should be degraded: " + notes);
        assertEquals(1, table.getForeignKeys().size());
        assertEquals(ReferentialAction.CASCADE, table.getForeignKeys().get(0).getOnDelete());
        assertTrue(table.getForeignKeys().get(0).isDeferrable());
        assertEquals(DataType.json(), table.getColumn("payload").getType());
        assertNotNull(table.getColumn("uid"));
        assertEquals(1, table.getIndexes().size());
        assertTrue(table.getIndexes().get(0).isPartial());
        assertNotNull(table.getComment());
    }

    @Test
    void restrictiveDialectDropsWhatItCannotExpress() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(), TestDialect.minimal(), notes);
        Table table = adapted.getTable("t");
        assertTrue(table.getForeignKeys().isEmpty(), "foreign keys are unsupported and must be dropped");
        assertTrue(table.getCheckConstraints().isEmpty(), "check constraints are unsupported");
        assertNull(table.getComment(), "table comments are unsupported");
        assertFalse(notes.isEmpty(), "every degradation must be recorded as a note");
    }

    /**
     * MySQL compares character columns case- and accent-insensitively by default, which is not how the
     * schema's own keys, checks and views mean to compare: the two-hour experiment's largest
     * false-positive class was DDLsmith planning two values that differ only in case for one unique
     * column and MySQL refusing the second (report §3.2). The adapted schema therefore names the
     * collation, and the note says so once rather than once per column.
     */
    @Test
    void mysqlCharacterColumnsAreDeclaredWithAnExactComparisonCollation() {
        RelationalSchema schema = new RelationalSchema("s");
        Table table = new Table("s", "t");
        table.addColumn(new Column("id", DataType.integer(), false));
        table.addColumn(new Column("v", DataType.varchar(20), true));
        table.addColumn(new Column("c", DataType.charType(5), true));
        table.addColumn(new Column("t", DataType.text(), true));
        table.addColumn(new Column("kind", DataType.enumOf(List.of("a", "b")), true));
        table.addColumn(new Column("uid", DataType.uuid(), true));
        table.addColumn(new Column("payload", DataType.json(), true));
        table.setPrimaryKey(new PrimaryKey("pk_t", List.of("id")));
        schema.addTable(table);

        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(schema, new MySqlDialect(), notes);
        Table result = adapted.getTable("t");

        assertEquals("utf8mb4_0900_bin", result.getColumn("v").getCollation(), "a VARCHAR column");
        assertEquals("utf8mb4_0900_bin", result.getColumn("c").getCollation(), "a CHAR column");
        assertEquals("utf8mb4_0900_bin", result.getColumn("t").getCollation(), "a TEXT column");
        assertEquals("utf8mb4_0900_bin", result.getColumn("kind").getCollation(),
                "an ENUM compares its labels under the column's collation");
        assertEquals("utf8mb4_0900_bin", result.getColumn("uid").getCollation(),
                "MySQL stores a UUID as CHAR(36), so it is a character column here");
        assertNull(result.getColumn("payload").getCollation(), "JSON is not a collated type");
        assertNull(result.getColumn("id").getCollation(), "a numeric column has no collation");
        assertEquals(1, notes.stream().filter(note -> note.contains("COLLATE utf8mb4_0900_bin")).count(),
                "one note covers the decision, not one per column: " + notes);
    }

    @Test
    void aCollationTheSchemaNamesItselfIsKept() {
        RelationalSchema schema = new RelationalSchema("s");
        Table table = new Table("s", "t");
        table.addColumn(new Column("id", DataType.integer(), false));
        table.addColumn(new Column("c", DataType.varchar(10), true).setCollation("utf8mb4_bin"));
        table.setPrimaryKey(new PrimaryKey("pk_t", List.of("id")));
        schema.addTable(table);

        RelationalSchema adapted = SchemaAdapter.adapt(schema, new MySqlDialect(), new ArrayList<>());

        assertEquals("utf8mb4_bin", adapted.getTable("t").getColumn("c").getCollation(),
                "a schema that names its own collation was explicit about it");
    }

    @Test
    void anEngineThatAlreadyComparesExactlyIsLeftAlone() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(), new org.ddlsmith.dbms.sqlite.SqliteDialect(),
                notes);

        assertNull(adapted.getTable("t").getColumn("kind").getCollation(),
                "SQLite's default comparison is the model's, so there is nothing to declare");
        assertTrue(notes.stream().noneMatch(note -> note.contains("COLLATE")), notes.toString());
    }

    @Test
    void unsupportedTypesAreMappedToSupportedOnes() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(), TestDialect.minimal(), notes);
        Table table = adapted.getTable("t");
        assertEquals(DataType.text(), table.getColumn("payload").getType(), "JSON must fall back to TEXT");
        assertEquals(DataType.varchar(36), table.getColumn("uid").getType(), "UUID must fall back to VARCHAR(36)");
        assertEquals(DataType.varchar(64), table.getColumn("kind").getType(), "ENUM must fall back to VARCHAR(64)");
        assertEquals(DataType.timestamp(), table.getColumn("seen_at").getType(),
                "TIMESTAMPTZ must fall back to TIMESTAMP");
    }

    @Test
    void unsupportedReferentialActionsBecomeNoAction() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(),
                TestDialect.without(DDLFeature.FK_SET_NULL), notes);
        ForeignKey foreignKey = adapted.getTable("t").getForeignKeys().get(0);
        assertEquals(ReferentialAction.CASCADE, foreignKey.getOnDelete(), "CASCADE is still supported");
        assertEquals(ReferentialAction.NO_ACTION, foreignKey.getOnUpdate(),
                "SET NULL is unsupported, so it must become NO ACTION");
    }

    @Test
    void deferrabilityIsRemovedWhenUnsupported() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(),
                TestDialect.without(DDLFeature.DEFERRABLE_FK), notes);
        assertFalse(adapted.getTable("t").getForeignKeys().get(0).isDeferrable());
    }

    @Test
    void partialPredicateIsRemovedWhenUnsupported() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(),
                TestDialect.without(DDLFeature.PARTIAL_INDEX), notes);
        Index index = adapted.getTable("t").getIndexes().get(0);
        assertFalse(index.isPartial(), "the index must survive, but without its predicate");
        assertTrue(notes.stream().anyMatch(note -> note.contains("non-partial")), "the change must be noted: " + notes);
    }

    @Test
    void expressionIndexesAreDroppedWhenUnsupported() {
        RelationalSchema schema = richSchema();
        Index expressionIndex = new Index("idx_expr");
        expressionIndex.getExpressions().add("lower(name)");
        schema.getTable("t").addIndex(expressionIndex);

        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(schema, TestDialect.without(DDLFeature.EXPRESSION_INDEX), notes);
        assertEquals(1, adapted.getTable("t").getIndexes().size(), "only the expression index should disappear");
        assertTrue(notes.stream().anyMatch(note -> note.contains("expression index")), notes.toString());
    }

    @Test
    void viewsAndSequencesFollowTheFeatureSet() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(), TestDialect.minimal(), notes);
        assertTrue(adapted.getViews().isEmpty(), "CREATE VIEW is unsupported");
        assertTrue(adapted.getSequences().isEmpty(), "CREATE SEQUENCE is unsupported");
    }

    @Test
    void materializedViewsBecomePlainViews() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(),
                TestDialect.without(DDLFeature.MATERIALIZED_VIEW), notes);
        assertEquals(1, adapted.getViews().size());
        assertFalse(adapted.getViews().get(0).isMaterialized());
    }

    @Test
    void generatedColumnsAreMaterializedWhenUnsupported() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(richSchema(),
                TestDialect.without(DDLFeature.GENERATED_COLUMN), notes);
        Column generated = adapted.getTable("t").getColumn("doubled");
        assertNotNull(generated, "the column must survive");
        assertFalse(generated.isGenerated(), "it must become a plain column");
        assertTrue(generated.isNullable(), "a materialized derived column must be nullable");
        assertTrue(notes.stream().anyMatch(note -> note.contains("materialized")), notes.toString());
    }

    /**
     * MySQL refuses to <em>store</em> a generated column whose expression reads a column a cascading
     * foreign key maintains: {@code CREATE TABLE t (pid INT, g INT GENERATED ALWAYS AS (pid * 2) STORED,
     * FOREIGN KEY (pid) REFERENCES p (id) ON UPDATE CASCADE)} is answered with error 1215 ("Cannot add
     * foreign key constraint"), and the message names neither the generated column nor the action.
     *
     * <p>The fix keeps the generated column and relaxes only its storage, because the same expression
     * is accepted with {@code VIRTUAL}. Both directions are asserted: a dialect whose engine has the
     * restriction must demote, and one that does not must leave the schema exactly as it was, or the
     * adapter would be silencing a real round-trip difference on the other four engines.</p>
     */
    @Test
    void storedGeneratedColumnOverACascadingActionIsMadeVirtual() {
        List<String> notes = new ArrayList<>();
        // the real MySQL feature set, so the assertion is about the engine and not about a test dialect
        RelationalSchema adapted = SchemaAdapter.adapt(generatedOverCascadingActionSchema(),
                new org.ddlsmith.dbms.mysql.MySqlDialect(), notes);
        Table child = adapted.getTable("child");
        Column generated = child.getColumn("derived");
        assertTrue(generated.isGenerated(), "the expression must survive: the engine accepts it");
        assertFalse(generated.isGeneratedStored(), "InnoDB refuses to store it, so it must be VIRTUAL");
        assertTrue(notes.stream().anyMatch(note -> note.contains("made VIRTUAL")),
                "the change must be recorded as a note: " + notes);
        assertTrue(child.getColumn("unrelated").isGeneratedStored(),
                "only a generated column that reads the cascading column is affected");
    }

    @Test
    void storedGeneratedColumnOverACascadingActionIsUntouchedOnOtherEngines() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(generatedOverCascadingActionSchema(),
                TestDialect.permissive(), notes);
        assertTrue(adapted.getTable("child").getColumn("derived").isGeneratedStored(),
                "only MySQL and MariaDB refuse this combination");
        assertTrue(notes.isEmpty(), "nothing may be degraded on an engine without the restriction: " + notes);
    }

    /** A stored generated column reading the column of a cascading foreign key — the 1215 shape. */
    private static RelationalSchema generatedOverCascadingActionSchema() {
        RelationalSchema schema = new RelationalSchema("s");
        Table parent = new Table("s", "parent");
        parent.addColumn(new Column("id", DataType.bigint(), false));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        schema.addTable(parent);

        Table child = new Table("s", "child");
        child.addColumn(new Column("pid", DataType.bigint(), false));
        child.addColumn(new Column("qty", DataType.integer(), true));
        // reads the foreign key column, which is what InnoDB refuses to store
        child.addColumn(new Column("derived", DataType.bigint(), true)
                .setGeneratedExpression("pid * 2").setGeneratedStored(true));
        // reads a column no foreign key maintains: this one must stay STORED on every engine
        child.addColumn(new Column("unrelated", DataType.integer(), true)
                .setGeneratedExpression("qty + 1").setGeneratedStored(true));
        child.setPrimaryKey(new PrimaryKey("pk_child", List.of("pid")));
        ForeignKey foreignKey = new ForeignKey(List.of("pid"), "parent", List.of("id"));
        foreignKey.setName("fk_child_parent").setOnUpdate(ReferentialAction.CASCADE);
        child.addForeignKey(foreignKey);
        schema.addTable(child);
        return schema;
    }

    /**
     * The 1215 action set is {@code {CASCADE, SET NULL} × {DELETE, UPDATE}} — symmetric, and narrower
     * than the "writes the column" predicate the {@code CHECK}/3823 rule needs, which also refuses both
     * {@code SET DEFAULT} actions. A guard built on the broader predicate would demote generated columns
     * the engine would happily have stored.
     */
    @Test
    void onlyTheActionsMySQLRefusesMakeAStoredColumnVirtual() {
        assertFalse(adaptStoredColumnWith(ReferentialAction.CASCADE, ReferentialAction.NO_ACTION)
                .isGeneratedStored(), "ON DELETE CASCADE refuses the STORED form (1215)");
        assertFalse(adaptStoredColumnWith(ReferentialAction.NO_ACTION, ReferentialAction.CASCADE)
                .isGeneratedStored(), "ON UPDATE CASCADE refuses the STORED form (1215)");
        assertFalse(adaptStoredColumnWith(ReferentialAction.SET_NULL, ReferentialAction.NO_ACTION)
                .isGeneratedStored(), "ON DELETE SET NULL refuses the STORED form (1215)");
        assertFalse(adaptStoredColumnWith(ReferentialAction.NO_ACTION, ReferentialAction.SET_NULL)
                .isGeneratedStored(), "ON UPDATE SET NULL refuses the STORED form (1215)");
        // these are refused by the CHECK rule (3823) but accepted by the STORED rule (1215)
        assertTrue(adaptStoredColumnWith(ReferentialAction.SET_DEFAULT, ReferentialAction.NO_ACTION)
                .isGeneratedStored(), "ON DELETE SET DEFAULT accepts the STORED form");
        assertTrue(adaptStoredColumnWith(ReferentialAction.NO_ACTION, ReferentialAction.SET_DEFAULT)
                .isGeneratedStored(), "ON UPDATE SET DEFAULT accepts the STORED form");
        assertTrue(adaptStoredColumnWith(ReferentialAction.RESTRICT, ReferentialAction.RESTRICT)
                .isGeneratedStored(), "RESTRICT on both sides accepts the STORED form");
        assertTrue(adaptStoredColumnWith(ReferentialAction.NO_ACTION, ReferentialAction.NO_ACTION)
                .isGeneratedStored(), "a foreign key with no action clause accepts the STORED form");
    }

    /** @return the adapted form of a column generated over a foreign key column, for the given actions */
    private static Column adaptStoredColumnWith(ReferentialAction onDelete, ReferentialAction onUpdate) {
        RelationalSchema schema = new RelationalSchema("s");
        Table parent = new Table("s", "parent");
        parent.addColumn(new Column("id", DataType.bigint(), false));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        schema.addTable(parent);

        Table child = new Table("s", "child");
        child.addColumn(new Column("pid", DataType.bigint(), false));
        child.addColumn(new Column("derived", DataType.bigint(), true)
                .setGeneratedExpression("pid * 2").setGeneratedStored(true));
        child.setPrimaryKey(new PrimaryKey("pk_child", List.of("pid")));
        ForeignKey foreignKey = new ForeignKey(List.of("pid"), "parent", List.of("id"));
        foreignKey.setName("fk_child_parent").setOnDelete(onDelete).setOnUpdate(onUpdate);
        child.addForeignKey(foreignKey);
        schema.addTable(child);

        RelationalSchema adapted = SchemaAdapter.adapt(schema,
                new org.ddlsmith.dbms.mysql.MySqlDialect(), new ArrayList<>());
        return adapted.getTable("child").getColumn("derived");
    }

    @Test
    void adaptationIsStableUnderRepeatedApplication() {
        List<String> first = new ArrayList<>();
        RelationalSchema once = SchemaAdapter.adapt(richSchema(), TestDialect.minimal(), first);
        List<String> second = new ArrayList<>();
        RelationalSchema twice = SchemaAdapter.adapt(once, TestDialect.minimal(), second);
        assertTrue(second.isEmpty(), "adapting an already adapted schema must be a no-op: " + second);
        assertEquals(once.getStatistics().toString(), twice.getStatistics().toString());
    }

    @Test
    void adaptationDoesNotModifyTheInput() {
        RelationalSchema original = richSchema();
        String before = original.getStatistics().toString();
        SchemaAdapter.adapt(original, TestDialect.minimal(), new ArrayList<>());
        assertEquals(before, original.getStatistics().toString(), "the input schema must not be mutated");
    }

    /**
     * The campaign shape: a join table whose primary key is the foreign keys of its participants, and
     * whose participants key on {@code TEXT}. Each of the four columns is widened to
     * {@code VARCHAR(255)} so that it can be a key column at all, and the key then needs
     * 4 x 255 x 4 = 4080 bytes. InnoDB caps one index key at 3072 bytes and answers error 1071, so the
     * table was never created — and the rejection was filed as an engine defect.
     */
    @Test
    void textKeyColumnsAreNarrowedUntilTheKeyFits() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(joinTableOverTextKeys(), new org.ddlsmith.dbms.mysql.MySqlDialect(), notes);
        Table join = adapted.getTable("t_join");
        for (String column : List.of("a_src", "b_src", "a_dst", "b_dst")) {
            assertEquals(DataType.varchar(192), join.getColumn(column).getType(),
                    column + " must be narrowed so that four of them fit in 3072 bytes");
        }
        assertEquals(3072, keyBytes(join.getPrimaryKey().getColumns(), join),
                "the key must land exactly on the engine's limit, not near it");
        assertTrue(notes.stream().anyMatch(note -> note.contains("narrowed") && note.contains("3072")),
                "the narrowing must be recorded as a note: " + notes);
    }

    @Test
    void aKeyThatAlreadyFitsIsLeftAlone() {
        RelationalSchema schema = new RelationalSchema("s");
        Table table = new Table("s", "t");
        for (String column : List.of("a", "b", "c", "d")) {
            table.addColumn(new Column(column, DataType.varchar(100), false));
        }
        table.setPrimaryKey(new PrimaryKey("pk_t", List.of("a", "b", "c", "d")));
        schema.addTable(table);

        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(schema, new org.ddlsmith.dbms.mysql.MySqlDialect(), notes);
        assertEquals(DataType.varchar(100), adapted.getTable("t").getColumn("a").getType());
        assertTrue(notes.stream().noneMatch(note -> note.contains("narrowed")),
                "4 x VARCHAR(100) is 1600 bytes and fits: " + notes);
    }

    /** A separate {@code CREATE INDEX} is rejected on its own, so it is capped by the same budget. */
    @Test
    void separateIndexesAreNarrowedToo() {
        RelationalSchema schema = new RelationalSchema("s");
        Table table = new Table("s", "t");
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            table.addColumn(new Column("c" + i, DataType.varchar(100), true));
            columns.add("c" + i);
        }
        table.addColumn(new Column("id", DataType.bigint(), false));
        table.setPrimaryKey(new PrimaryKey("pk_t", List.of("id")));
        table.addIndex(new Index("idx_wide", columns));
        schema.addTable(table);

        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(schema, new org.ddlsmith.dbms.mysql.MySqlDialect(), notes);
        Table result = adapted.getTable("t");
        assertTrue(keyBytes(adapted.getTable("t").getIndexes().get(0).getColumns(), result) <= 3072,
                "12 x VARCHAR(100) is 4800 bytes and must be brought within the limit");
        assertEquals(DataType.bigint(), result.getColumn("id").getType(),
                "a fixed-width column is never narrowed");
    }

    /**
     * Narrowing a key column changes the type the columns referencing it must be declared with, and
     * an engine that compares types refuses the foreign key when they drift apart.
     */
    @Test
    void narrowingKeepsReferencingColumnsInStep() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(joinTableOverTextKeys(), new org.ddlsmith.dbms.mysql.MySqlDialect(), notes);
        Table referencing = adapted.getTable("t_ref");
        assertEquals(adapted.getTable("t_join").getColumn("a_src").getType(),
                referencing.getColumn("ref_a_src").getType(),
                "the referencing column must be narrowed with the key column it points at");
    }

    /**
     * The campaign shape at full size: the join table's key is four foreign key columns and is narrowed
     * as a whole, while the single-column key each of them points at is not. Alignment must not widen
     * them back — and a character length is not something a foreign key compares, so it has no reason
     * to.
     */
    @Test
    void alignmentDoesNotUndoANarrowedKeyColumn() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(joinTableOverTextKeys(), new org.ddlsmith.dbms.mysql.MySqlDialect(),
                notes);
        Table join = adapted.getTable("t_join");
        for (String column : List.of("a_src", "b_src", "a_dst", "b_dst")) {
            assertEquals(DataType.varchar(192), join.getColumn(column).getType(),
                    column + " must stay narrowed after the foreign key alignment runs");
        }
        assertTrue(keyBytes(join.getPrimaryKey().getColumns(), join) <= 3072,
                "the key must still fit once the whole adaptation has settled");
    }

    /** Adaptation has to reach the same schema however the rules are ordered, so it must converge. */
    @Test
    void keyBudgetAndAlignmentSettle() {
        List<String> first = new ArrayList<>();
        RelationalSchema once = SchemaAdapter.adapt(joinTableOverTextKeys(),
                new org.ddlsmith.dbms.mysql.MySqlDialect(), first);
        List<String> second = new ArrayList<>();
        RelationalSchema twice = SchemaAdapter.adapt(once, new org.ddlsmith.dbms.mysql.MySqlDialect(), second);
        assertTrue(second.isEmpty(), "a second pass must find nothing left to do: " + second);
        assertEquals(once.getStatistics().toString(), twice.getStatistics().toString());
    }

    @Test
    void enginesWithoutAKeyByteLimitAreUntouched() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(joinTableOverTextKeys(), TestDialect.permissive(), notes);
        Table join = adapted.getTable("t_join");
        assertEquals(DataType.text(), join.getColumn("a_src").getType(),
                "an engine that indexes TEXT keeps it, and only MySQL and MariaDB cap the key size");
        assertTrue(notes.isEmpty(), "nothing may be degraded on an engine without the cap: " + notes);
    }

    /** @return the bytes the engine charges for a key made of these columns */
    private static int keyBytes(List<String> columns, Table table) {
        int total = 0;
        for (String column : columns) {
            total += org.ddlsmith.ddl.gen.EngineTraits.indexKeyBytes(table.getColumn(column).getType());
        }
        return total;
    }

    /**
     * A join table over two entities that each key on a pair of {@code TEXT} columns — four TEXT key
     * columns in one primary key, which is what the campaign generated.
     */
    private static RelationalSchema joinTableOverTextKeys() {
        RelationalSchema schema = new RelationalSchema("s");
        Table join = new Table("s", "t_join");
        join.addColumn(new Column("a_src", DataType.text(), false));
        join.addColumn(new Column("b_src", DataType.text(), false));
        join.addColumn(new Column("a_dst", DataType.text(), false));
        join.addColumn(new Column("b_dst", DataType.text(), false));
        join.setPrimaryKey(new PrimaryKey("pk_t_join", List.of("a_src", "b_src", "a_dst", "b_dst")));
        schema.addTable(join);

        Table referencing = new Table("s", "t_ref");
        referencing.addColumn(new Column("id", DataType.bigint(), false));
        referencing.addColumn(new Column("ref_a_src", DataType.text(), true));
        referencing.setPrimaryKey(new PrimaryKey("pk_t_ref", List.of("id")));
        ForeignKey foreignKey = new ForeignKey(List.of("ref_a_src"), "t_join", List.of("a_src"));
        foreignKey.setName("fk_ref_join");
        referencing.addForeignKey(foreignKey);
        schema.addTable(referencing);
        return schema;
    }

    /**
     * The manual states it as a rule: "If {@code ON UPDATE CASCADE} or {@code ON UPDATE SET NULL}
     * recurses to update the same table it has previously updated during the same cascade, it acts
     * like RESTRICT. This means that you cannot use self-referential {@code ON UPDATE CASCADE} or
     * {@code ON UPDATE SET NULL} operations." MySQL answers error 1451, so the action has to give way
     * exactly as an unsupported feature does — it was the largest source of false findings in the
     * campaign, at 186 generated databases.
     */
    @Test
    void aSelfReferentialUpdateActionMySQLRefusesIsDropped() {
        for (ReferentialAction refused : List.of(ReferentialAction.CASCADE, ReferentialAction.SET_NULL)) {
            List<String> notes = new ArrayList<>();
            RelationalSchema adapted = SchemaAdapter.adapt(selfReferentialSchema(refused, refused),
                    new org.ddlsmith.dbms.mysql.MySqlDialect(), notes);
            ForeignKey key = adapted.getTable("t").getForeignKeys().get(0);
            assertEquals(ReferentialAction.NO_ACTION, key.getOnUpdate(),
                    "ON UPDATE " + refused + " is unavailable on a self-referencing key");
            assertEquals(refused, key.getOnDelete(),
                    "the manual says a self-referential ON DELETE may cascade, so it must be left alone");
            assertTrue(notes.stream().anyMatch(note -> note.contains("self-referential ON UPDATE")),
                    "the change must be recorded as a note: " + notes);
        }
    }

    @Test
    void aSelfReferentialUpdateActionOtherEnginesAcceptIsKept() {
        List<String> notes = new ArrayList<>();
        RelationalSchema adapted = SchemaAdapter.adapt(
                selfReferentialSchema(ReferentialAction.CASCADE, ReferentialAction.CASCADE),
                TestDialect.permissive(), notes);
        ForeignKey key = adapted.getTable("t").getForeignKeys().get(0);
        assertEquals(ReferentialAction.CASCADE, key.getOnUpdate(),
                "PostgreSQL and SQLite carry a self-referential ON UPDATE CASCADE out");
        assertTrue(notes.isEmpty(), "nothing may be degraded on an engine without the rule: " + notes);
    }

    /** A foreign key from a table onto its own key, with the given actions on both sides. */
    private static RelationalSchema selfReferentialSchema(ReferentialAction onDelete, ReferentialAction onUpdate) {
        RelationalSchema schema = new RelationalSchema("s");
        Table table = new Table("s", "t");
        table.addColumn(new Column("id", DataType.bigint(), false));
        table.addColumn(new Column("parent_id", DataType.bigint(), true));
        table.setPrimaryKey(new PrimaryKey("pk_t", List.of("id")));
        ForeignKey foreignKey = new ForeignKey(List.of("parent_id"), "t", List.of("id"));
        foreignKey.setName("fk_t_self").setOnDelete(onDelete).setOnUpdate(onUpdate);
        foreignKey.setSelfReferential(true);
        table.addForeignKey(foreignKey);
        schema.addTable(table);
        return schema;
    }

    /** A schema using every construct whose degradation the adapter handles. */
    static RelationalSchema richSchema() {
        RelationalSchema schema = new RelationalSchema("s");
        Table parent = new Table("s", "parent");
        parent.addColumn(new Column("id", DataType.bigint(), false));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        schema.addTable(parent);

        Table table = new Table("s", "t");
        table.addColumn(new Column("id", DataType.bigint(), false));
        table.addColumn(new Column("parent_id", DataType.bigint(), false));
        table.addColumn(new Column("payload", DataType.json(), true));
        table.addColumn(new Column("uid", DataType.uuid(), true));
        table.addColumn(new Column("kind", DataType.enumOf(List.of("a", "b")), true));
        table.addColumn(new Column("seen_at", DataType.timestampWithTimeZone(), true));
        table.addColumn(new Column("doubled", DataType.integer(), true).setGeneratedExpression("id * 2")
                .setGeneratedStored(true));
        table.addColumn(new Column("name", DataType.varchar(20), true).setCollation("NOCASE"));
        table.setPrimaryKey(new PrimaryKey("pk_t", List.of("id")));
        table.addCheckConstraint(new org.ddlsmith.common.schema.CheckConstraint("ck_t_name", "name IS NOT NULL"));
        table.setComment("a table comment");
        ForeignKey foreignKey = new ForeignKey(List.of("parent_id"), "parent", List.of("id"));
        foreignKey.setName("fk_t_parent").setOnDelete(ReferentialAction.CASCADE)
                .setOnUpdate(ReferentialAction.SET_NULL).setDeferrable(true);
        table.addForeignKey(foreignKey);
        Index index = new Index("idx_t_name", List.of("name")).setPredicate("name IS NOT NULL");
        table.addIndex(index);
        schema.addTable(table);

        View view = new View("v_t", "SELECT id FROM t").setMaterialized(true).setCheckOption("LOCAL");
        schema.addView(view);
        schema.addSequence(new org.ddlsmith.common.schema.Sequence("seq_t"));
        return schema;
    }
}
