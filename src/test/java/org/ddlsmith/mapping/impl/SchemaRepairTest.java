package org.ddlsmith.mapping.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.mapping.MappingTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for a defect that only a real run could find: H2 rejected a whole {@code CREATE
 * TABLE} with {@code Column "A1" not found} because one generated column was computed from another.
 *
 * <p>H2 accepts a generated column based on a base column and refuses one based on a generated
 * column. The situation arose from an ISA hierarchy mapped to a single table: the subtype's derived
 * attribute names the supertype's attribute, and after the merge that name belongs to a generated
 * column. The repair pass now stores such a column instead of computing it, which is the only
 * mapping both engines can express.</p>
 */
class SchemaRepairTest {

    @Test
    @DisplayName("a generated column based on another generated column is stored instead")
    void chainedGeneratedColumnsAreStored() {
        RelationalSchema schema = new RelationalSchema("ddlsmith");
        Table table = new Table("ddlsmith", "t_0");
        table.addColumn(new Column("a0", DataType.decimal(10, 2)).setNullable(false));
        table.addColumn(new Column("a1", DataType.decimal(10, 2)).setNullable(false)
                .setGeneratedExpression("a0 + 1").setGeneratedStored(true));
        table.addColumn(new Column("a3_2", DataType.decimal(10, 2)).setNullable(false)
                .setGeneratedExpression("a1 + 1").setGeneratedStored(true));
        table.setPrimaryKey(new PrimaryKey("pk_t_0", java.util.List.of("a0")));
        schema.addTable(table);

        MappingTrace trace = new MappingTrace();
        SchemaRepair.repair(schema, trace);

        Column base = schema.getTable("t_0").getColumn("a1");
        assertNotNull(base.getGeneratedExpression(), "a generated column on a base column is fine");
        Column chained = schema.getTable("t_0").getColumn("a3_2");
        assertNull(chained.getGeneratedExpression(),
                "a generated column on a generated column must be stored, because H2 refuses it");
        assertFalse(chained.isGenerated());
        assertTrue(chained.isNullable(),
                "the column must be insertable, or the data oracles fail for a reason that is not the engine's");
        assertTrue(trace.getNotes().stream().anyMatch(note -> note.contains("based on another generated column")),
                "the repair must be recorded: " + trace.getNotes());
    }
}
