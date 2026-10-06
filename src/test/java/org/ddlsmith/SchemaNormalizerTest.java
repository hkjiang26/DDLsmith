package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;
import org.ddlsmith.introspection.SchemaNormalizer;
import org.junit.jupiter.api.Test;

/**
 * Verifies the schema comparison that the round-trip and differential oracles are built on: it must
 * be tolerant of cosmetic rewrites and strict about anything with observable semantics.
 */
class SchemaNormalizerTest {

    @Test
    void identicalSchemasHaveNoDifferences() {
        assertTrue(SchemaNormalizer.diff(sample(), sample()).isEmpty());
    }

    /**
     * SQLite stores a BOOLEAN column with NUMERIC affinity and hands {@code true} back as the integer
     * 1, so {@code SELECT MAX(c)} over such a column returns an Integer where the model holds a
     * Boolean. The normalized forms have to be the same Java type for that to compare equal:
     * {@code Integer(1).equals(BigDecimal(1))} is false, and the pivot-row oracle reported every such
     * query as a DATA_MISMATCH (19 findings, 16 of them literally {@code true} against {@code 1}).
     */
    @Test
    void booleansNormalizeToTheSameTypeAsNumbers() {
        assertEquals(org.ddlsmith.data.DataModel.normalizeForComparison(1),
                org.ddlsmith.data.DataModel.normalizeForComparison(true));
        assertEquals(org.ddlsmith.data.DataModel.normalizeForComparison(0),
                org.ddlsmith.data.DataModel.normalizeForComparison(false));
        assertEquals(org.ddlsmith.data.DataModel.normalizeForComparison(1L),
                org.ddlsmith.data.DataModel.normalizeForComparison(true));
    }

    /**
     * DuckDB stores {@code DEFAULT FALSE} as {@code cast('f' as boolean)} and reports it that way.
     * Comparing the two texts reported a lost default on every boolean column that was given one —
     * the only remaining divergence of an hour-long evolution run on DuckDB after the generator
     * defects were fixed.
     */
    @Test
    void duckdbBooleanDefaultsAreRecognised() {
        assertEquals(SchemaNormalizer.canonicalExpression("false"),
                SchemaNormalizer.canonicalExpression("CAST('f' AS BOOLEAN)"));
        assertEquals(SchemaNormalizer.canonicalExpression("true"),
                SchemaNormalizer.canonicalExpression("cast('t' as boolean)"));
        assertEquals(SchemaNormalizer.canonicalExpression("true"),
                SchemaNormalizer.canonicalExpression("CAST('true' AS BOOLEAN)"));
        assertFalse(SchemaNormalizer.canonicalExpression("false")
                .equals(SchemaNormalizer.canonicalExpression("true")));
    }

    @Test
    void detectsAMissingTable() {
        RelationalSchema actual = sample();
        actual.removeTable("parent");
        List<SchemaDifference> differences = SchemaNormalizer.diff(sample(), actual);
        assertEquals(1, differences.size());
        assertEquals(SchemaDifference.Kind.MISSING_TABLE, differences.get(0).getKind());
    }

    @Test
    void detectsAnUnexpectedTable() {
        RelationalSchema actual = sample();
        actual.addTable(new Table("surprise").addColumn(new Column("x", DataType.integer())));
        List<SchemaDifference> differences = SchemaNormalizer.diff(sample(), actual);
        assertTrue(differences.stream().anyMatch(d -> d.getKind() == SchemaDifference.Kind.UNEXPECTED_TABLE));
    }

    @Test
    void detectsAWrongColumnType() {
        RelationalSchema actual = sample();
        actual.getTable("parent").getColumn("name").setType(DataType.varchar(50));
        List<SchemaDifference> differences = SchemaNormalizer.diff(sample(), actual);
        assertTrue(differences.stream().anyMatch(d -> d.getKind() == SchemaDifference.Kind.COLUMN_TYPE));
    }

    @Test
    void detectsLostNullability() {
        RelationalSchema actual = sample();
        actual.getTable("parent").getColumn("name").setNullable(true);
        List<SchemaDifference> differences = SchemaNormalizer.diff(sample(), actual);
        assertTrue(differences.stream().anyMatch(d -> d.getKind() == SchemaDifference.Kind.COLUMN_NULLABILITY));
    }

    @Test
    void detectsALostForeignKey() {
        RelationalSchema actual = sample();
        actual.getTable("child").getForeignKeys().clear();
        List<SchemaDifference> differences = SchemaNormalizer.diff(sample(), actual);
        assertTrue(differences.stream().anyMatch(d -> d.getKind() == SchemaDifference.Kind.MISSING_FOREIGN_KEY));
    }

    @Test
    void detectsAChangedReferentialAction() {
        RelationalSchema actual = sample();
        actual.getTable("child").getForeignKeys().get(0).setOnDelete(ReferentialAction.NO_ACTION);
        List<SchemaDifference> differences = SchemaNormalizer.diff(sample(), actual);
        assertTrue(differences.stream().anyMatch(d -> d.getKind() == SchemaDifference.Kind.FOREIGN_KEY_ON_DELETE));
    }

    @Test
    void detectsALostPrimaryKey() {
        RelationalSchema actual = sample();
        actual.getTable("parent").setPrimaryKey(null);
        List<SchemaDifference> differences = SchemaNormalizer.diff(sample(), actual);
        assertTrue(differences.stream().anyMatch(d -> d.getKind() == SchemaDifference.Kind.MISSING_PRIMARY_KEY));
    }

    @Test
    void detectsALostCheckConstraint() {
        RelationalSchema actual = sample();
        actual.getTable("parent").getCheckConstraints().clear();
        List<SchemaDifference> differences = SchemaNormalizer.diff(sample(), actual);
        assertTrue(differences.stream().anyMatch(d -> d.getKind() == SchemaDifference.Kind.MISSING_CHECK_CONSTRAINT));
    }

    @Test
    void aUniqueIndexCountsAsAUniqueConstraint() {
        // engines differ in whether a UNIQUE constraint is reported as a constraint or as an index
        RelationalSchema actual = sample();
        Table parent = actual.getTable("parent");
        parent.getUniqueConstraints().clear();
        parent.addIndex(new Index("uq_parent_name", List.of("name")).setUnique(true));
        assertTrue(SchemaNormalizer.diff(sample(), actual).stream()
                .noneMatch(d -> d.getKind() == SchemaDifference.Kind.MISSING_UNIQUE_CONSTRAINT));
    }

    @Test
    void ignoresTheBackingIndexOfAPrimaryKey() {
        RelationalSchema actual = sample();
        actual.getTable("parent").addIndex(new Index("pk_parent", List.of("id")).setUnique(true));
        assertTrue(SchemaNormalizer.diff(sample(), actual).isEmpty(),
                "an index that merely implements the primary key must not be reported");
    }

    @Test
    void tolerantOfCosmeticDefaultRewrite() {
        RelationalSchema intended = sample();
        intended.getTable("parent").getColumn("flag").setDefaultExpression("0");
        RelationalSchema actual = sample();
        actual.getTable("parent").getColumn("flag").setDefaultExpression("(0)::integer");
        assertTrue(SchemaNormalizer.diff(intended, actual).isEmpty());
    }

    @Test
    void tolerantOfCurrentTimestampSpelling() {
        assertEquals(SchemaNormalizer.canonicalExpression("CURRENT_TIMESTAMP"),
                SchemaNormalizer.canonicalExpression("now()"));
        assertEquals(SchemaNormalizer.canonicalExpression("CURRENT_TIMESTAMP"),
                SchemaNormalizer.canonicalExpression("current_timestamp()"));
    }

    @Test
    void tolerantOfIdentifierCaseAndQuoting() {
        assertEquals(SchemaNormalizer.canonicalExpression("\"a3\" > 0"),
                SchemaNormalizer.canonicalExpression("A3 > 0"));
    }

    /**
     * H2 reports the discriminator CHECK of a single-table ISA hierarchy back as
     * {@code "DTYPE" IN('E2', 'E4')} — quoted column and no space after the keyword. Both are
     * cosmetic, so they must compare equal to what DDLSmith asked for; before this rule, every
     * such hierarchy was reported as a lost CHECK constraint.
     */
    @Test
    void tolerantOfTheSpacingH2UsesForInLists() {
        assertEquals(SchemaNormalizer.canonicalExpression("dtype IN ('E2', 'E4')"),
                SchemaNormalizer.canonicalExpression("\"DTYPE\" IN('E2', 'E4')"));
        assertEquals(SchemaNormalizer.canonicalExpression("a1 NOT IN ('x', 'y')"),
                SchemaNormalizer.canonicalExpression("a1 NOT IN('x', 'y')"));
    }

    @Test
    void strictAboutTypeNamesWithinOneEngine() {
        RelationalSchema intended = sample();
        RelationalSchema actual = sample();
        actual.getTable("parent").getColumn("id").setType(DataType.integer());
        assertFalse(SchemaNormalizer.diff(intended, actual).isEmpty(),
                "an INTEGER column where BIGINT was asked for must be reported");
    }

    @Test
    void acrossEnginesOnlyTheTypeCategoryIsCompared() {
        RelationalSchema a = sample();
        RelationalSchema b = sample();
        b.getTable("parent").getColumn("id").setType(DataType.integer());
        assertTrue(SchemaNormalizer.diff(a, b, false, true).isEmpty(),
                "INTEGER and BIGINT are the same category and must compare equal across engines");
    }

    @Test
    void eraseRemovesWhatCannotBeObserved() {
        RelationalSchema erased = SchemaNormalizer.erase(sample(),
                java.util.EnumSet.of(IntrospectionCapability.COLUMNS, IntrospectionCapability.PRIMARY_KEYS));
        Table parent = erased.getTable("parent");
        assertFalse(parent.getColumn("id").isIdentity(), "identity cannot be observed, so it must be erased");
        assertEquals(null, parent.getColumn("flag").getDefaultExpression());
        assertTrue(parent.getForeignKeys().isEmpty());
        assertTrue(parent.getCheckConstraints().isEmpty());
        assertTrue(parent.getUniqueConstraints().isEmpty());
        assertEquals(2, erased.getTables().size(), "tables themselves are always observable");
        assertEquals(List.of("id"), parent.getPrimaryKey().getColumns(), "primary keys were declared observable");
    }

    /** A small schema exercising every construct the comparison cares about. */
    static RelationalSchema sample() {
        RelationalSchema schema = new RelationalSchema("s");
        Table parent = new Table("s", "parent");
        parent.addColumn(new Column("id", DataType.bigint(), false).setIdentity(true));
        parent.addColumn(new Column("name", DataType.varchar(20), false));
        parent.addColumn(new Column("flag", DataType.bool(), true).setDefaultExpression("0"));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        parent.addUniqueConstraint(new UniqueConstraint("uq_parent_name", List.of("name")));
        parent.addCheckConstraint(new CheckConstraint("ck_parent_name", "name IS NOT NULL"));
        schema.addTable(parent);

        Table child = new Table("s", "child");
        child.addColumn(new Column("id", DataType.bigint(), false));
        child.addColumn(new Column("parent_id", DataType.bigint(), false));
        child.setPrimaryKey(new PrimaryKey("pk_child", List.of("id")));
        ForeignKey foreignKey = new ForeignKey(List.of("parent_id"), "parent", List.of("id"));
        foreignKey.setName("fk_child_parent");
        foreignKey.setOnDelete(ReferentialAction.CASCADE);
        child.addForeignKey(foreignKey);
        schema.addTable(child);
        return schema;
    }
}
