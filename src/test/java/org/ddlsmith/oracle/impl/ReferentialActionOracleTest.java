package org.ddlsmith.oracle.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.junit.jupiter.api.Test;

/**
 * Verifies how the referential action oracle describes the constraint it is reporting on.
 *
 * <p>The report quotes the actions the catalog holds, and the campaign showed those coming from the
 * wrong constraint: a child table with two foreign keys to the same parent — a recursive relationship
 * mapped with a source and a destination column — had the second one's report announce the first one's
 * actions, which told the reader the engine had stored a {@code CASCADE} where it had in fact stored
 * the {@code RESTRICT} DDLSmith asked for.</p>
 */
class ReferentialActionOracleTest {

    /**
     * The exact shape from the MySQL campaign, verified against the live catalog:
     * {@code information_schema.REFERENTIAL_CONSTRAINTS} reports {@code fk_t_4_r_0 -> CASCADE} and
     * {@code fk_t_4_r_1 -> RESTRICT} for these two constraints.
     */
    @Test
    void aSiblingForeignKeyToTheSameParentDoesNotSupplyTheActions() {
        RelationalSchema schema = twoForeignKeysToTheSameParent();
        Table child = schema.getTable("t_4_r");

        assertEquals("ON DELETE RESTRICT, ON UPDATE NO_ACTION",
                ReferentialActionOracle.introspectedActions(schema, child, named(child, "fk_t_4_r_1")),
                "the second constraint must report its own actions, not its sibling's");
        assertEquals("ON DELETE CASCADE, ON UPDATE RESTRICT",
                ReferentialActionOracle.introspectedActions(schema, child, named(child, "fk_t_4_r_0")));
    }

    @Test
    void aConstraintTheCatalogDoesNotHoldHasNoActionsToQuote() {
        RelationalSchema schema = twoForeignKeysToTheSameParent();
        Table child = schema.getTable("t_4_r");
        ForeignKey absent = new ForeignKey(List.of("id"), "t_4", List.of("a11"));
        absent.setName("fk_never_created").setOnDelete(ReferentialAction.SET_NULL);

        assertNull(ReferentialActionOracle.introspectedActions(schema, child, absent),
                "guessing a neighbour's actions is worse than saying they are not available");
    }

    /** @return a schema whose {@code t_4_r} holds {@code fk_t_4_r_0} and {@code fk_t_4_r_1} */
    private static RelationalSchema twoForeignKeysToTheSameParent() {
        RelationalSchema schema = new RelationalSchema("s");
        Table parent = new Table("s", "t_4");
        parent.addColumn(new Column("a11", DataType.integer(), false));
        parent.setPrimaryKey(new PrimaryKey("pk_t_4", List.of("a11")));
        schema.addTable(parent);

        Table child = new Table("s", "t_4_r");
        child.addColumn(new Column("id", DataType.bigint(), false));
        child.addColumn(new Column("e4_src_a11", DataType.integer(), false));
        child.addColumn(new Column("e4_dst_a11", DataType.integer(), false));
        child.setPrimaryKey(new PrimaryKey("pk_t_4_r", List.of("id")));
        child.addForeignKey(new ForeignKey(List.of("e4_src_a11"), "t_4", List.of("a11"))
                .setName("fk_t_4_r_0").setOnDelete(ReferentialAction.CASCADE)
                .setOnUpdate(ReferentialAction.RESTRICT));
        child.addForeignKey(new ForeignKey(List.of("e4_dst_a11"), "t_4", List.of("a11"))
                .setName("fk_t_4_r_1").setOnDelete(ReferentialAction.RESTRICT));
        schema.addTable(child);
        return schema;
    }

    private static ForeignKey named(Table table, String name) {
        return table.getForeignKeys().stream().filter(key -> name.equals(key.getName())).findFirst()
                .orElseThrow();
    }

    /**
     * The probe writes a fresh key value into the parent's key column, and the column's own range
     * decides what that value may be. The campaign's probe added a fixed step and handed the result to
     * the engine unfitted, so on a {@code SMALLINT} key it asked for {@code a18 = 1000019} and MySQL
     * refused the statement itself with {@code 1264 Out of range value for column 'a18'} — before any
     * referential action could be observed, which the oracle reported as a defect of the engine
     * (report §3.4).
     */
    @Test
    void aFreshKeyValueIsFittedToTheColumnsRange() {
        Table parent = smallintKeyParent();

        assertEquals(32767L, ReferentialActionOracle
                .freshKeyValues(parent, List.of("a18"), Map.<String, Object>of("a18", 16L)).get("a18"),
                "the campaign's 16 + 1000003 does not fit SMALLINT");
        assertEquals(1002803L, ReferentialActionOracle
                .freshKeyValues(smallintKeyParent(DataType.integer()), List.of("a18"),
                        Map.<String, Object>of("a18", 2800L))
                .get("a18"), "a column wide enough keeps the step");
    }

    /** At the maximum there is no room upwards, so the probe has to look downwards instead. */
    @Test
    void aKeyAtItsMaximumMovesTheOtherWay() {
        Table parent = smallintKeyParent();

        assertEquals(-32768L, ReferentialActionOracle
                .freshKeyValues(parent, List.of("a18"), Map.<String, Object>of("a18", 32767L)).get("a18"));
    }

    private static Table smallintKeyParent() {
        return smallintKeyParent(DataType.smallint());
    }

    private static Table smallintKeyParent(DataType type) {
        RelationalSchema schema = new RelationalSchema("s");
        Table parent = new Table("s", "t_4");
        parent.addColumn(new Column("a18", type, false));
        parent.setPrimaryKey(new PrimaryKey("pk_t_4", List.of("a18")));
        schema.addTable(parent);
        return parent;
    }

    /**
     * The campaign shape: two single-column foreign keys of the same child, both cascading onto the
     * same parent key, and a unique constraint over both columns. One statement moves the parent key,
     * both columns are rewritten, and the rows that shared the parent collide — MySQL says so with
     * error 1761. Neither column is unique on its own, which is why the guard that looked at the
     * constraint under test alone let the finding through.
     */
    @Test
    void aCascadeThatCollidesOnASiblingKeyIsRecognised() {
        RelationalSchema schema = twoCascadingForeignKeysOntoTheSameKey();
        Table child = schema.getTable("t_3_r");

        assertTrue(ReferentialActionOracle.cascadeWrittenColumnsAreUnique(child, named(child, "fk_t_3_r_1")));
        assertTrue(ReferentialActionOracle.cascadeWrittenColumnsAreUnique(child, named(child, "fk_t_3_r_0")));
    }

    /** Without the sibling there is nothing to collide with: one column is not the unique key. */
    @Test
    void aLoneCascadeIsNotExcused() {
        RelationalSchema schema = twoCascadingForeignKeysOntoTheSameKey();
        Table child = schema.getTable("t_3_r");
        ForeignKey sibling = named(child, "fk_t_3_r_0");
        child.getForeignKeys().remove(sibling);

        assertFalse(ReferentialActionOracle.cascadeWrittenColumnsAreUnique(child, named(child, "fk_t_3_r_1")),
                "the guard may only fire when the statement really writes a whole key");
    }

    /**
     * A sibling that cascades onto a different key of the same parent is not rewritten by this
     * statement, so summing its columns would excuse a collision that cannot happen.
     */
    @Test
    void aCascadeOntoAnotherKeyDoesNotWidenTheGuard() {
        RelationalSchema schema = twoCascadingForeignKeysOntoTheSameKey();
        Table child = schema.getTable("t_3_r");
        child.getForeignKeys().remove(named(child, "fk_t_3_r_0"));
        Table parent = schema.getTable("t_4");
        parent.addColumn(new Column("other", DataType.date(), true));
        child.addUniqueConstraint(new org.ddlsmith.common.schema.UniqueConstraint("uq_other", List.of("other")));
        child.addForeignKey(new ForeignKey(List.of("e4_src_a18"), "t_4", List.of("other"))
                .setName("fk_onto_another_key").setOnUpdate(ReferentialAction.CASCADE));

        assertFalse(ReferentialActionOracle.cascadeWrittenColumnsAreUnique(child, named(child, "fk_t_3_r_1")),
                "only the columns this statement rewrites may count");
    }

    /** @return the campaign shape, with {@code UNIQUE (e4_src_a18, e4_dst_a18)} on the child */
    private static RelationalSchema twoCascadingForeignKeysOntoTheSameKey() {
        RelationalSchema schema = new RelationalSchema("s");
        Table parent = new Table("s", "t_4");
        parent.addColumn(new Column("a18", DataType.date(), false));
        parent.setPrimaryKey(new PrimaryKey("pk_t_4", List.of("a18")));
        schema.addTable(parent);

        Table child = new Table("s", "t_3_r");
        child.addColumn(new Column("id", DataType.bigint(), false));
        child.addColumn(new Column("e4_src_a18", DataType.date(), false));
        child.addColumn(new Column("e4_dst_a18", DataType.date(), false));
        child.setPrimaryKey(new PrimaryKey("pk_t_3_r", List.of("id")));
        child.addUniqueConstraint(new org.ddlsmith.common.schema.UniqueConstraint("uq_t_3_r_0",
                List.of("e4_src_a18", "e4_dst_a18")));
        child.addForeignKey(new ForeignKey(List.of("e4_src_a18"), "t_4", List.of("a18"))
                .setName("fk_t_3_r_0").setOnUpdate(ReferentialAction.CASCADE));
        child.addForeignKey(new ForeignKey(List.of("e4_dst_a18"), "t_4", List.of("a18"))
                .setName("fk_t_3_r_1").setOnUpdate(ReferentialAction.CASCADE));
        schema.addTable(child);
        return schema;
    }
}
