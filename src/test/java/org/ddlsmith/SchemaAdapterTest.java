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
