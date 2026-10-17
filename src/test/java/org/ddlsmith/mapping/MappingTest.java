package org.ddlsmith.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.data.RowGenerator;
import org.ddlsmith.dbms.Dialects;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.mapping.MappingOptions.DerivedStrategy;
import org.ddlsmith.mapping.MappingOptions.IsAStrategy;
import org.ddlsmith.mapping.MappingOptions.KeyStrategy;
import org.ddlsmith.mapping.MappingOptions.ManyToManyStrategy;
import org.ddlsmith.mapping.MappingOptions.MultivaluedStrategy;
import org.ddlsmith.mapping.MappingOptions.NaryStrategy;
import org.ddlsmith.mapping.MappingOptions.OneToManyStrategy;
import org.ddlsmith.mapping.MappingOptions.OneToOneStrategy;
import org.ddlsmith.mapping.MappingOptions.ReferentialActionStrategy;
import org.ddlsmith.mapping.MappingOptions.WeakEntityStrategy;
import org.ddlsmith.mapping.impl.SchemaInvariants;
import org.ddlsmith.mapping.impl.SelfCheck;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of the ER-to-relational mapping.
 *
 * <p>They are intentionally not part of the Maven reactor yet (the rest of the project is written in
 * parallel); run them directly, e.g. with the JUnit platform console launcher, or through
 * {@code org.ddlsmith.mapping.impl.SelfCheck} which performs the same checks from the command
 * line.</p>
 */
class MappingTest {

    /* ------------------------------------------------------------------ the whole sweep */

    @Test
    @DisplayName("every invariant holds for every diagram, option combination and random option set")
    void selfCheckPasses() {
        SelfCheck.Result result = SelfCheck.run();
        System.out.println(result.summary());
        assertTrue(result.mappings > 20000, "the self-check must exercise the mapper at scale");
        assertEquals(0, result.failureCount, () -> "mapping self-check failed:\n" + String.join("\n",
                result.failures));
    }

    /* ------------------------------------------------------------------ strong entities */

    @Test
    @DisplayName("strong entity: key strategies, nullability, composite and multi-valued attributes")
    void strongEntityKeyStrategies() {
        for (KeyStrategy keyStrategy : KeyStrategy.values()) {
            MappingOptions options = SelfCheck.base().setKeyStrategy(keyStrategy);
            RelationalSchema schema = map(SelfCheck.strongDiagram(), options);
            assertInvariants(schema);
            Table table = schema.getTable("t_0");
            assertNotNull(table, "t_0 must exist");
            switch (keyStrategy) {
            case NATURAL_KEY:
                assertEquals(List.of("a0"), table.getPrimaryKey().getColumns());
                break;
            case SURROGATE_KEY:
                assertEquals(List.of("id"), table.getPrimaryKey().getColumns());
                assertTrue(table.getUniqueConstraints().isEmpty());
                break;
            default:
                assertEquals(List.of("id"), table.getPrimaryKey().getColumns());
                assertTrue(hasUnique(table, List.of("a0")), "the natural key must stay unique");
                break;
            }
            assertFalse(table.getColumn("a1").isNullable(), "a mandatory attribute is NOT NULL");
            assertTrue(table.getColumn("a2").isNullable(), "an optional attribute is nullable");
            assertNotNull(table.getColumn("a4a"), "the composite attribute must be flattened");
            assertNotNull(table.getColumn("a4b"), "the composite attribute must be flattened");
            Table values = schema.getTable("mv_e0_0");
            assertNotNull(values, "the multi-valued attribute must get its own table");
            assertEquals(ReferentialAction.CASCADE, values.getForeignKeys().get(0).getOnDelete());
        }
    }

    @Test
    @DisplayName("derived attributes follow the derived strategy, but a derived key is materialized")
    void derivedAttributeStrategies() {
        for (DerivedStrategy strategy : DerivedStrategy.values()) {
            RelationalSchema schema = map(SelfCheck.strongDiagram(),
                    SelfCheck.base().setDerived(strategy).setKeyStrategy(KeyStrategy.SURROGATE_KEY));
            assertInvariants(schema);
            Column column = schema.getTable("t_0").getColumn("a3");
            switch (strategy) {
            case OMIT:
                assertNull(column, "an omitted derived attribute must not be stored");
                break;
            case MATERIALIZE:
                assertNotNull(column);
                assertFalse(column.isGenerated());
                break;
            default:
                assertNotNull(column);
                assertNotNull(column.getGeneratedExpression(), "a generated column needs an expression");
                assertTrue(column.isGeneratedStored());
                break;
            }
        }
        ERDiagram diagram = new ERDiagram().setName("derivedKey");
        EREntity entity = new EREntity("E0");
        entity.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true).setDerived(true)
                .setDerivedExpression("1 + 1"));
        diagram.addEntity(entity);
        RelationalSchema schema = map(diagram, SelfCheck.base().setDerived(DerivedStrategy.OMIT));
        assertInvariants(schema);
        assertNotNull(schema.getTable("t_0").getColumn("a0"),
                "a derived attribute that is the natural key must still be materialized");
    }

    @Test
    @DisplayName("an attribute named id keeps its name and the surrogate key is renamed")
    void attributeNamedIdDoesNotCollideWithTheSurrogateKey() {
        ERDiagram diagram = new ERDiagram().setName("idCollision");
        EREntity entity = new EREntity("E0");
        entity.addAttribute(new ERAttribute("id", DataType.varchar(20)).setPrimaryKey(true));
        entity.addAttribute(new ERAttribute("a0", DataType.integer()));
        diagram.addEntity(entity);
        RelationalSchema schema = map(diagram, SelfCheck.base().setKeyStrategy(KeyStrategy.SURROGATE_KEY));
        assertInvariants(schema);
        Table table = schema.getTable("t_0");
        assertNotNull(table.getColumn("id"));
        assertNotNull(table.getColumn("id_2"));
        assertEquals(List.of("id_2"), table.getPrimaryKey().getColumns());
    }

    /* ------------------------------------------------------------------ weak entities */

    @Test
    @DisplayName("weak entity: composite key = owner key + partial key, cascading foreign key")
    void weakEntityCompositeKey() {
        RelationalSchema schema = map(SelfCheck.weakDiagram(),
                SelfCheck.base().setWeakEntity(WeakEntityStrategy.OWN_TABLE_COMPOSITE_PK));
        assertInvariants(schema);
        Table weak = schema.getTable("t_1");
        Table owner = schema.getTable("t_0");
        assertEquals(1, weak.getForeignKeys().size());
        ForeignKey fk = weak.getForeignKeys().get(0);
        assertEquals("t_0", fk.getReferencedTable());
        assertEquals(owner.getPrimaryKey().getColumns(), fk.getReferencedColumns());
        assertEquals(ReferentialAction.CASCADE, fk.getOnDelete());
        assertFalse(fk.isSelfReferential());
        assertEquals("R0", fk.getDerivedFromRelationship());
        List<String> expected = new ArrayList<>(fk.getColumns());
        expected.add("a2");
        assertEquals(expected, weak.getPrimaryKey().getColumns());
        for (String column : fk.getColumns()) {
            assertFalse(weak.getColumn(column).isNullable(), "the owner key columns are NOT NULL");
        }
        assertFalse(schema.getTable("t_1").getForeignKeys().isEmpty());
    }

    @Test
    @DisplayName("weak entity: surrogate key plus a unique constraint on owner key and partial key")
    void weakEntitySurrogateKey() {
        RelationalSchema schema = map(SelfCheck.weakDiagram(),
                SelfCheck.base().setWeakEntity(WeakEntityStrategy.OWN_TABLE_SURROGATE_PK));
        assertInvariants(schema);
        Table weak = schema.getTable("t_1");
        assertEquals(List.of("id"), weak.getPrimaryKey().getColumns());
        List<String> expected = new ArrayList<>(weak.getForeignKeys().get(0).getColumns());
        expected.add("a2");
        assertTrue(hasUnique(weak, expected), "the owner key and the partial key must be unique together");
    }

    @Test
    @DisplayName("a chain of weak entities resolves the owner key recursively")
    void weakEntityChain() {
        ERDiagram diagram = new ERDiagram().setName("weakChain");
        EREntity e0 = new EREntity("E0");
        e0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        EREntity e1 = new EREntity("E1").setWeak(true).setOwnerEntity("E0").setOwnerRelationship("R0");
        e1.addAttribute(new ERAttribute("a1", DataType.integer()).setPartialKey(true));
        EREntity e2 = new EREntity("E2").setWeak(true).setOwnerEntity("E1").setOwnerRelationship("R1");
        e2.addAttribute(new ERAttribute("a2", DataType.integer()).setPartialKey(true));
        diagram.addEntity(e0).addEntity(e1).addEntity(e2);
        diagram.addRelationship(SelfCheck.identifying("R0", "E0", "E1"));
        diagram.addRelationship(SelfCheck.identifying("R1", "E1", "E2"));
        RelationalSchema schema = map(diagram, SelfCheck.base());
        assertInvariants(schema);
        Table third = schema.getTable("t_2");
        assertEquals("t_1", third.getForeignKeys().get(0).getReferencedTable());
        assertEquals(schema.getTable("t_1").getPrimaryKey().getColumns(),
                third.getForeignKeys().get(0).getReferencedColumns());
        assertEquals(3, third.getPrimaryKey().getColumns().size(),
                "the key of a weak entity of a weak entity is the whole owner key plus the partial key");
    }

    @Test
    @DisplayName("the attributes of an identifying relationship belong to the weak entity's table")
    void identifyingRelationshipAttributes() {
        // The identifying relationship is never mapped as an association, so its attributes have no
        // other home: the classic rule puts them in the weak entity's relation, out of the key.
        ERDiagram diagram = new ERDiagram().setName("identifyingAttributes");
        EREntity owner = new EREntity("E0");
        owner.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        EREntity weak = new EREntity("E1").setWeak(true).setOwnerEntity("E0").setOwnerRelationship("R0");
        weak.addAttribute(new ERAttribute("a1", DataType.integer()).setPartialKey(true));
        weak.addAttribute(new ERAttribute("a2", DataType.varchar(20)));
        diagram.addEntity(owner).addEntity(weak);
        ERRelationship identifying = SelfCheck.identifying("R0", "E0", "E1");
        identifying.addAttribute(new ERAttribute("a3", DataType.date()));
        identifying.addAttribute(new ERAttribute("a4", DataType.integer()).setOptional(true));
        diagram.addRelationship(identifying);

        for (WeakEntityStrategy strategy : WeakEntityStrategy.values()) {
            MappingOptions options = SelfCheck.base().setWeakEntity(strategy);
            MappingTrace trace = new MappingTrace();
            Randomly.setSeed(9L);
            RelationalSchema schema = new StandardERToRelationalMapper().map(diagram, options, "s", trace);
            assertInvariants(schema);
            Table weakTable = schema.getTable("t_1");
            assertNotNull(weakTable.getColumn("a3"), "the identifying relationship's attribute must be stored in "
                    + strategy);
            assertNotNull(weakTable.getColumn("a4"));
            assertFalse(weakTable.getColumn("a3").isNullable(), "a mandatory relationship attribute is NOT NULL");
            assertTrue(weakTable.getColumn("a4").isNullable(), "an optional relationship attribute is nullable");
            assertFalse(weakTable.getPrimaryKey().getColumns().contains("a3"),
                    "the relationship's attributes stay out of the weak entity's key");
            assertEquals("a3", trace.getRelationshipAttributeToColumn().get("R0").get("a3"),
                    "the trace must map the relationship attribute to its column");
            assertEquals("t_1", trace.getRelationshipToTable().get("R0"),
                    "the trace must say which table carries the relationship");
            assertFalse(trace.getDroppedAttributes().containsKey("R0.a3"),
                    "a stored attribute must not also be announced as dropped");
            assertFalse(trace.getDroppedAttributes().containsKey("R0.a4"));
            assertEveryAttributeAccountedFor(diagram, trace);
        }
    }

    /* ------------------------------------------------------------------ binary relationships */

    @Test
    @DisplayName("1:1 relationships: all four strategies")
    void oneToOneStrategies() {
        for (OneToOneStrategy strategy : OneToOneStrategy.values()) {
            RelationalSchema schema = map(SelfCheck.oneToOneDiagram(), SelfCheck.base().setOneToOne(strategy));
            assertInvariants(schema);
            switch (strategy) {
            case FK_ON_TOTAL_SIDE: {
                Table total = schema.getTable("t_0");
                assertEquals(1, total.getForeignKeys().size(), "the key belongs to the total side");
                ForeignKey fk = total.getForeignKeys().get(0);
                assertEquals("t_1", fk.getReferencedTable());
                assertFalse(total.getColumn(fk.getColumns().get(0)).isNullable());
                assertTrue(hasUnique(total, fk.getColumns()), "a 1:1 key must be unique");
                break;
            }
            case FK_ON_EITHER_SIDE: {
                int keys = schema.getTable("t_0").getForeignKeys().size()
                        + schema.getTable("t_1").getForeignKeys().size();
                assertEquals(1, keys);
                break;
            }
            case MERGE_TABLES: {
                assertEquals(1, schema.getTables().size(), "both entities must share one table");
                Table merged = schema.getTable("t_0");
                assertNotNull(merged.getColumn("a0"));
                assertNotNull(merged.getColumn("a2"));
                assertEquals(List.of("a0"), merged.getPrimaryKey().getColumns());
                assertNotNull(merged.getColumn("a4"),
                        "merging the two entity tables IS the mapping of the relationship, so its attributes"
                                + " belong in the merged table");
                break;
            }
            default: {
                Table join = joinTable(schema);
                assertEquals(2, join.getForeignKeys().size());
                assertEquals(2, join.getPrimaryKey().getColumns().size());
                for (ForeignKey fk : join.getForeignKeys()) {
                    assertTrue(hasUnique(join, fk.getColumns()), "each side of a 1:1 join table is unique");
                }
                assertNotNull(join.getColumn("a4"), "the relationship attribute belongs to the join table");
                break;
            }
            }
        }
    }

    @Test
    @DisplayName("1:N relationships: all three strategies")
    void oneToManyStrategies() {
        for (OneToManyStrategy strategy : OneToManyStrategy.values()) {
            for (Participation participation : Participation.values()) {
                RelationalSchema schema = map(SelfCheck.oneToManyDiagram(participation),
                        SelfCheck.base().setOneToMany(strategy));
                assertInvariants(schema);
                if (strategy == OneToManyStrategy.JOIN_TABLE) {
                    Table join = joinTable(schema);
                    assertEquals(2, join.getForeignKeys().size());
                    assertEquals(2, join.getPrimaryKey().getColumns().size());
                    continue;
                }
                Table many = schema.getTable("t_1");
                Table one = schema.getTable("t_0");
                if (strategy == OneToManyStrategy.FK_ON_MANY_SIDE) {
                    assertTrue(one.getForeignKeys().isEmpty(), "the key belongs to the many side");
                    ForeignKey fk = many.getForeignKeys().get(0);
                    assertEquals("t_0", fk.getReferencedTable());
                    assertEquals(participation == Participation.TOTAL,
                            !many.getColumn(fk.getColumns().get(0)).isNullable(),
                            "NOT NULL must follow the participation of the many side");
                } else {
                    assertTrue(many.getForeignKeys().isEmpty());
                    ForeignKey fk = one.getForeignKeys().get(0);
                    assertEquals("t_1", fk.getReferencedTable());
                    assertTrue(hasUnique(one, fk.getColumns()));
                }
            }
        }
    }

    @Test
    @DisplayName("M:N relationships: both join table variants")
    void manyToManyStrategies() {
        for (ManyToManyStrategy strategy : ManyToManyStrategy.values()) {
            RelationalSchema schema = map(SelfCheck.manyToManyDiagram(), SelfCheck.base().setManyToMany(strategy));
            assertInvariants(schema);
            Table join = joinTable(schema);
            assertEquals(2, join.getForeignKeys().size());
            if (strategy == ManyToManyStrategy.JOIN_TABLE) {
                assertEquals(2, join.getPrimaryKey().getColumns().size(),
                        "the primary key of the join table is the pair of foreign keys");
            } else {
                assertEquals(List.of("id"), join.getPrimaryKey().getColumns());
                List<String> keys = new ArrayList<>();
                for (ForeignKey fk : join.getForeignKeys()) {
                    keys.addAll(fk.getColumns());
                }
                assertTrue(hasUnique(join, keys));
            }
            assertNotNull(join.getColumn("a4"), "the relationship attribute belongs to the join table");
        }
    }

    @Test
    @DisplayName("recursive relationships use role-qualified, self-referential keys")
    void recursiveRelationships() {
        RelationalSchema oneToMany = map(SelfCheck.recursiveDiagram("R0", Cardinality.ONE, Cardinality.MANY,
                Participation.PARTIAL, Participation.TOTAL), SelfCheck.base());
        assertInvariants(oneToMany);
        ForeignKey fk = oneToMany.getTable("t_0").getForeignKeys().get(0);
        assertTrue(fk.isSelfReferential());
        assertEquals(List.of("r0_dst_a0"), fk.getColumns());
        assertEquals(List.of("a0"), fk.getReferencedColumns());
        assertFalse(oneToMany.getTable("t_0").getColumn("r0_dst_a0").isNullable(),
                "the storing side participates totally");
        assertTrue(oneToMany.getTable("t_0").getUniqueConstraints().isEmpty(),
                "a 1:N recursive key is not unique");

        RelationalSchema oneToOne = map(SelfCheck.recursiveDiagram("R0", Cardinality.ONE, Cardinality.ONE,
                Participation.PARTIAL, Participation.PARTIAL), SelfCheck.base());
        assertInvariants(oneToOne);
        assertTrue(hasUnique(oneToOne.getTable("t_0"), List.of("r0_dst_a0")),
                "a 1:1 recursive key must be unique");

        RelationalSchema manyToMany = map(SelfCheck.recursiveDiagram("R0", Cardinality.MANY, Cardinality.MANY,
                Participation.PARTIAL, Participation.PARTIAL), SelfCheck.base());
        assertInvariants(manyToMany);
        Table join = joinTable(manyToMany);
        assertEquals(2, join.getForeignKeys().size());
        for (ForeignKey joinKey : join.getForeignKeys()) {
            assertTrue(joinKey.isSelfReferential());
        }
        assertEquals(2, join.getPrimaryKey().getColumns().size());
    }

    @Test
    @DisplayName("n-ary relationships: join table with one key per participant")
    void naryRelationships() {
        for (NaryStrategy strategy : NaryStrategy.values()) {
            RelationalSchema schema = map(SelfCheck.ternaryDiagram(), SelfCheck.base().setNary(strategy));
            assertInvariants(schema);
            Table join = joinTable(schema);
            assertEquals(3, join.getForeignKeys().size());
            if (strategy == NaryStrategy.JOIN_TABLE) {
                // the fixture has one participant with cardinality 1: after the classic n-ary rule
                // its foreign key is not part of the key, the two many-side keys are
                assertEquals(2, join.getPrimaryKey().getColumns().size(),
                        "the key of the cardinality-1 participant is not part of the primary key");
            } else {
                assertEquals(List.of("id"), join.getPrimaryKey().getColumns());
            }
            assertNotNull(join.getColumn("a6"));
        }
        // all participants with cardinality N: every foreign key is part of the key
        RelationalSchema allMany = map(SelfCheck.ternaryDiagram(Cardinality.MANY, Cardinality.MANY, Cardinality.MANY),
                SelfCheck.base().setNary(NaryStrategy.JOIN_TABLE));
        assertInvariants(allMany);
        assertEquals(3, joinTable(allMany).getPrimaryKey().getColumns().size());
        // two participants with cardinality 1: excluding keys would over-constrain the relationship
        RelationalSchema twoOnes = map(SelfCheck.ternaryDiagram(Cardinality.ONE, Cardinality.ONE, Cardinality.MANY),
                SelfCheck.base().setNary(NaryStrategy.JOIN_TABLE));
        assertInvariants(twoOnes);
        assertEquals(3, joinTable(twoOnes).getPrimaryKey().getColumns().size());
    }

    /* ------------------------------------------------------------------ enhanced ER features */

    @Test
    @DisplayName("a shared subclass is mapped in every ISA strategy")
    void sharedSubclassStrategies() {
        for (IsAStrategy strategy : IsAStrategy.values()) {
            MappingTrace trace = new MappingTrace();
            Randomly.setSeed(4711L);
            RelationalSchema schema = new StandardERToRelationalMapper().map(SelfCheck.sharedSubclassDiagram(),
                    SelfCheck.base().setIsa(strategy).setKeyStrategy(KeyStrategy.SURROGATE_KEY), "s", trace);
            assertInvariants(schema);
            switch (strategy) {
            case SINGLE_TABLE: {
                Table table = schema.getTable("t_0");
                for (String column : List.of("a0", "a1", "a2", "a3", "a4")) {
                    assertNotNull(table.getColumn(column), column + " must be in the shared table");
                }
                assertEquals(3, table.getDiscriminatorValues().size(), "E1, E2 and E3 are the discriminator values");
                for (String entity : List.of("E0", "E1", "E2", "E3")) {
                    assertEquals("t_0", trace.getEntityToTable().get(entity),
                            entity + " must share the one table of the lattice");
                }
                break;
            }
            case TABLE_PER_CLASS: {
                Table shared = schema.getTable("t_3");
                for (String column : List.of("a0", "a1", "a2", "a3", "a4")) {
                    assertNotNull(shared.getColumn(column),
                            column + " must be inherited through both chains of the shared subclass");
                }
                break;
            }
            default: {
                Table shared = schema.getTable("t_3");
                assertEquals(2, shared.getForeignKeys().size(),
                        "the shared subclass references both supertypes");
                assertEquals(2, shared.getPrimaryKey().getColumns().size(),
                        "its key combines the keys of both supertypes");
                break;
            }
            }
        }
    }

    @Test
    @DisplayName("a union type (category) gets a surrogate key, one optional key per superclass and a union check")
    void categoryMapping() {
        RelationalSchema schema = map(SelfCheck.categoryDiagram(), SelfCheck.base());
        assertInvariants(schema);
        Table category = schema.getTable("t_2");
        assertEquals(List.of("id"), category.getPrimaryKey().getColumns());
        int membership = 0;
        for (ForeignKey fk : category.getForeignKeys()) {
            if (fk.getReferencedTable().equals("t_0") || fk.getReferencedTable().equals("t_1")) {
                membership++;
                for (String column : fk.getColumns()) {
                    assertTrue(category.getColumn(column).isNullable(),
                            "membership is optional per superclass, so the key must be nullable");
                }
            }
        }
        assertEquals(2, membership, "one membership foreign key per superclass");
        assertTrue(category.getCheckConstraints().stream()
                .anyMatch(check -> check.getExpression().contains("IS NOT NULL")
                        && check.getExpression().contains(" OR ")),
                "the union membership must be expressed as a check constraint");
    }

    @Test
    @DisplayName("a generated category row belongs to at least one superclass, as its own check requires")
    void generatedCategoryRowsSatisfyTheirUnionMembershipCheck() {
        RelationalSchema schema = map(SelfCheck.categoryDiagram(), SelfCheck.base());
        Table category = schema.getTable("t_2");
        // the columns the union-membership check names: the key columns of the membership foreign keys
        List<String> membershipColumns = new ArrayList<>();
        for (ForeignKey foreignKey : category.getForeignKeys()) {
            if (foreignKey.getReferencedTable().equals("t_0") || foreignKey.getReferencedTable().equals("t_1")) {
                membershipColumns.addAll(foreignKey.getColumns());
            }
        }
        assertFalse(membershipColumns.isEmpty(), "the category must have membership columns");

        // The generator used to leave every membership column NULL and emit a row that violated the
        // constraint DDLsmith itself had written - the engine rejected it and the
        // constraint-enforcement oracle reported the engine as too strict. Rows are generated
        // repeatedly because which columns the generator fills is random: the point is that no
        // attempt may produce a row with all of them NULL.
        Randomly.setSeed(20261007L);
        RowGenerator generator = new RowGenerator(schema, Dialects.forName("h2"));
        int generated = 0;
        for (int attempt = 0; attempt < 40; attempt++) {
            Map<String, Object> row = generator.generateRow(category);
            if (row.isEmpty()) {
                continue;
            }
            generated++;
            boolean memberOfSomeSuperclass = membershipColumns.stream().anyMatch(column -> row.get(column) != null);
            assertTrue(memberOfSomeSuperclass,
                    "a category row must satisfy its union-membership check, but every membership column "
                            + "was NULL in " + row);
        }
        assertTrue(generated > 0, "the category table must be populatable");
    }

    @Test
    @DisplayName("an aggregation is stored by the join table of the relationship it aggregates")
    void aggregationMapping() {
        for (ManyToManyStrategy strategy : ManyToManyStrategy.values()) {
            MappingTrace trace = new MappingTrace();
            Randomly.setSeed(4711L);
            RelationalSchema schema = new StandardERToRelationalMapper().map(SelfCheck.aggregationDiagram(),
                    SelfCheck.base().setManyToMany(strategy), "s", trace);
            assertInvariants(schema);
            String innerName = trace.getRelationshipToTable().get("R0");
            assertNotNull(innerName, "the aggregated relationship must map to a join table");
            Table inner = schema.getTable(innerName);
            assertEquals(innerName, trace.getEntityToTable().get("Agg_R0"),
                    "the aggregation entity is stored by the inner join table");
            Table outer = schema.getTable(trace.getRelationshipToTable().get("Ragg_R0"));
            assertNotNull(outer, "the outer relationship must produce a table");
            assertTrue(outer.getForeignKeys().stream()
                    .anyMatch(fk -> fk.getReferencedTable().equals(innerName)),
                    "the outer table references the aggregated join table");
            assertNull(trace.getRelationshipToTable().get("R1"), "an absorbed link produces no table");
            assertEquals("AGGREGATION_LINK", trace.getRelationshipStrategy().get("R1"));
            assertEquals("AGGREGATION_LINK", trace.getRelationshipStrategy().get("R2"));
        }
    }

    @Test
    @DisplayName("a partial specialization keeps the root discriminator value, a total one does not")
    void specializationCompleteness() {
        RelationalSchema partial = map(SelfCheck.partialSpecializationDiagram(),
                SelfCheck.base().setIsa(IsAStrategy.SINGLE_TABLE));
        assertInvariants(partial);
        Table partialTable = partial.getTable("t_0");
        assertTrue(partialTable.getCheckConstraints().stream()
                .anyMatch(check -> check.getExpression().contains("'E0'")),
                "a partial specialization must allow rows of the root type");

        ERDiagram total = SelfCheck.partialSpecializationDiagram();
        total.getEntity("E0").setTotalSpecialization(true);
        RelationalSchema totalSchema = map(total, SelfCheck.base().setIsa(IsAStrategy.SINGLE_TABLE));
        assertInvariants(totalSchema);
        assertFalse(totalSchema.getTable("t_0").getCheckConstraints().stream()
                .anyMatch(check -> check.getExpression().contains("'E0'")),
                "a total specialization must not allow rows of the root type");
    }

    @Test
    @DisplayName("nested composites, composite natural keys and composite partial keys are flattened")
    void compositeStructures() {
        RelationalSchema schema = map(SelfCheck.compositeStructureDiagram(), SelfCheck.base());
        assertInvariants(schema);
        Table owner = schema.getTable("t_0");
        assertEquals(List.of("a0", "a1"), owner.getPrimaryKey().getColumns(),
                "the composite natural key is the two key attributes");
        for (String column : List.of("a2", "a3", "a4")) {
            assertNotNull(owner.getColumn(column), "the nested composite must be flattened down to " + column);
        }
        Table weak = schema.getTable("t_1");
        assertEquals(4, weak.getPrimaryKey().getColumns().size(),
                "the weak entity key is the owner key plus its two partial key attributes");
        Table values = schema.getTable("mv_e1_0");
        assertNotNull(values.getColumn("a8"), "the multi-valued composite must be flattened");
        assertNotNull(values.getColumn("a9"), "the multi-valued composite must be flattened");
    }

    /* ------------------------------------------------------------------ ISA */

    @Test
    @DisplayName("generalization hierarchies: all three strategies")
    void isaStrategies() {
        for (IsAStrategy strategy : IsAStrategy.values()) {
            RelationalSchema schema = map(SelfCheck.isaDiagram(), SelfCheck.base().setIsa(strategy));
            assertInvariants(schema);
            switch (strategy) {
            case SINGLE_TABLE: {
                Table table = schema.getTable("t_0");
                assertEquals("dtype", table.getDiscriminatorColumn());
                assertNotNull(table.getColumn("dtype"));
                assertEquals(2, table.getDiscriminatorValues().size());
                assertNotNull(table.getColumn("a1"));
                assertNotNull(table.getColumn("a2"));
                assertNotNull(table.getColumn("a3"));
                boolean discriminatorCheck = table.getCheckConstraints().stream()
                        .anyMatch(check -> check.getExpression().contains("dtype"));
                assertTrue(discriminatorCheck, "the discriminator must be constrained to the subtype names");
                assertEquals(1, schema.getTables().size());
                break;
            }
            case TABLE_PER_CLASS: {
                Table subtype = schema.getTable("t_1");
                assertNotNull(subtype.getColumn("a0"), "the inherited key must be present");
                assertNotNull(subtype.getColumn("a1"), "the inherited attributes must be present");
                assertNotNull(subtype.getColumn("a2"), "the own attributes must be present");
                assertNotNull(schema.getTable("t_0"), "the supertype has attributes, so it needs a table");
                break;
            }
            default: {
                Table subtype = schema.getTable("t_1");
                ForeignKey fk = subtype.getForeignKeys().get(0);
                assertEquals("t_0", fk.getReferencedTable());
                assertEquals(subtype.getPrimaryKey().getColumns(), fk.getColumns(),
                        "the primary key of the subtype doubles as the foreign key");
                assertEquals(schema.getTable("t_0").getPrimaryKey().getColumns(), fk.getReferencedColumns());
                break;
            }
            }
        }
    }

    @Test
    @DisplayName("the ISA link is never mapped as an association")
    void isaLinkIsNotAnAssociation() {
        MappingTrace trace = new MappingTrace();
        Randomly.setSeed(5L);
        RelationalSchema schema = new StandardERToRelationalMapper().map(SelfCheck.isaDiagram(), SelfCheck.base(),
                "s", trace);
        assertInvariants(schema);
        assertFalse(trace.getRelationshipToTable().containsKey("R8"));
        assertEquals("ISA", trace.getRelationshipStrategy().get("R8"));
        assertEquals("t_0", trace.getEntityToTable().get("E1"));
    }

    /* ------------------------------------------------------------------ attribute accounting */

    @Test
    @DisplayName("every ER attribute is either stored or announced as dropped, never lost in silence")
    void everyAttributeIsAccountedFor() {
        ERDiagram diagram = SelfCheck.isaDiagram();
        // an attribute on the ISA link itself: the link is structural, so the attribute is dropped -
        // but it must be announced with a reason
        ERRelationship isa = diagram.getRelationship("R8");
        isa.addAttribute(new ERAttribute("a99", DataType.integer()));
        // a derived attribute, which the OMIT strategy drops
        diagram.getEntity("E0").addAttribute(new ERAttribute("a98", DataType.integer())
                .setDerived(true).setDerivedExpression("a0 + 1"));

        for (IsAStrategy strategy : IsAStrategy.values()) {
            for (DerivedStrategy derived : DerivedStrategy.values()) {
                MappingOptions options = SelfCheck.base().setIsa(strategy).setDerived(derived);
                MappingTrace trace = new MappingTrace();
                Randomly.setSeed(3L);
                RelationalSchema schema = new StandardERToRelationalMapper().map(diagram, options, "s", trace);
                assertInvariants(schema);
                assertEveryAttributeAccountedFor(diagram, trace);
            }
        }

        // under OMIT the derived attribute must be announced, not silently missing
        MappingTrace omitted = new MappingTrace();
        Randomly.setSeed(3L);
        new StandardERToRelationalMapper().map(diagram,
                SelfCheck.base().setDerived(DerivedStrategy.OMIT), "s", omitted);
        assertTrue(omitted.getDroppedAttributes().containsKey("E0.a98"),
                "the omitted derived attribute must appear in the drop ledger");
        assertTrue(omitted.getDroppedAttributes().get("E0.a98").contains("OMIT"),
                "the reason must name the strategy that caused the drop");
        assertTrue(omitted.getDroppedAttributes().containsKey("R8.a99"),
                "an attribute of a structural link must appear in the drop ledger");
    }

    /**
     * Asserts the contract behind the drop ledger: an ER attribute is either mapped to a column or
     * recorded as deliberately dropped with a reason, so a mapper path can never forget one.
     */
    private static void assertEveryAttributeAccountedFor(ERDiagram diagram, MappingTrace trace) {
        for (EREntity entity : diagram.getEntities()) {
            java.util.Map<String, String> stored = trace.getAttributeToColumn().get(entity.getName());
            for (ERAttribute attribute : entity.getAttributes()) {
                if (stored != null && stored.containsKey(attribute.getName())) {
                    continue;
                }
                assertTrue(trace.getDroppedAttributes().containsKey(entity.getName() + "." + attribute.getName()),
                        "entity attribute " + entity.getName() + "." + attribute.getName()
                                + " is neither stored nor announced as dropped");
            }
        }
        for (ERRelationship relationship : diagram.getRelationships()) {
            java.util.Map<String, String> stored = trace.getRelationshipAttributeToColumn()
                    .get(relationship.getName());
            for (ERAttribute attribute : relationship.getAttributes()) {
                if (stored != null && stored.containsKey(attribute.getName())) {
                    continue;
                }
                assertTrue(trace.getDroppedAttributes()
                        .containsKey(relationship.getName() + "." + attribute.getName()),
                        "relationship attribute " + relationship.getName() + "." + attribute.getName()
                                + " is neither stored nor announced as dropped");
            }
        }
        for (java.util.Map.Entry<String, String> entry : trace.getDroppedAttributes().entrySet()) {
            assertNotNull(entry.getValue(), "the dropped attribute " + entry.getKey() + " has no reason");
            assertFalse(entry.getValue().isBlank(),
                    "the dropped attribute " + entry.getKey() + " has a blank reason");
            assertFalse("no reason given".equals(entry.getValue()),
                    "the dropped attribute " + entry.getKey() + " was recorded without a reason");
        }
    }

    /* ------------------------------------------------------------------ multi-valued attributes */

    @Test
    @DisplayName("multi-valued attributes: both table variants")
    void multivaluedStrategies() {
        for (MultivaluedStrategy strategy : MultivaluedStrategy.values()) {
            RelationalSchema schema = map(SelfCheck.strongDiagram(),
                    SelfCheck.base().setMultivalued(strategy).setKeyStrategy(KeyStrategy.SURROGATE_KEY));
            assertInvariants(schema);
            Table values = schema.getTable("mv_e0_0");
            assertNotNull(values);
            assertEquals("t_0", values.getForeignKeys().get(0).getReferencedTable());
            assertEquals(ReferentialAction.CASCADE, values.getForeignKeys().get(0).getOnDelete());
            assertNotNull(values.getColumn("value"));
            if (strategy == MultivaluedStrategy.SEPARATE_TABLE) {
                assertEquals(2, values.getPrimaryKey().getColumns().size());
                assertEquals("value", values.getPrimaryKey().getColumns().get(1));
            } else {
                assertEquals(List.of("id"), values.getPrimaryKey().getColumns());
            }
        }
    }

    /* ------------------------------------------------------------------ referential actions */

    @Test
    @DisplayName("referential actions never violate the column constraints")
    void referentialActionRules() {
        for (ReferentialActionStrategy strategy : ReferentialActionStrategy.values()) {
            for (int seed = 0; seed < 40; seed++) {
                Randomly.setSeed(seed);
                MappingOptions options = MappingOptions.random().setReferentialActionStrategy(strategy);
                RelationalSchema schema = new StandardERToRelationalMapper().map(SelfCheck.mixedDiagram(), options,
                        "s", new MappingTrace());
                assertInvariants(schema);
                for (RelationalSchema.ForeignKeyRef reference : schema.getAllForeignKeys()) {
                    ForeignKey fk = reference.foreignKey;
                    if (fk.getOnDelete() == ReferentialAction.SET_NULL || fk.getOnUpdate() == ReferentialAction.SET_NULL) {
                        for (String column : fk.getColumns()) {
                            assertTrue(reference.table.getColumn(column).isNullable(),
                                    "SET NULL requires a nullable column");
                        }
                    }
                    assertFalse(fk.getOnDelete() == ReferentialAction.SET_DEFAULT,
                            "SET DEFAULT requires a default on every key column");
                    assertEquals(options.isDeferrableForeignKeys(), fk.isDeferrable());
                    assertFalse(fk.isInitiallyDeferred());
                }
            }
        }
    }

    @Test
    @DisplayName("the NONE strategy leaves ordinary foreign keys as NO ACTION")
    void noneStrategyLeavesNoAction() {
        RelationalSchema schema = map(SelfCheck.manyToManyDiagram(),
                SelfCheck.base().setReferentialActionStrategy(ReferentialActionStrategy.NONE));
        assertInvariants(schema);
        for (RelationalSchema.ForeignKeyRef reference : schema.getAllForeignKeys()) {
            assertEquals(ReferentialAction.NO_ACTION, reference.foreignKey.getOnDelete());
            assertEquals(ReferentialAction.NO_ACTION, reference.foreignKey.getOnUpdate());
        }
    }

    /* ------------------------------------------------------------------ views and sequences */

    @Test
    @DisplayName("views join real columns and sequences follow the identity setting")
    void viewsAndSequences() {
        RelationalSchema withViews = map(SelfCheck.manyToManyDiagram(),
                SelfCheck.base().setGenerateViews(true));
        assertInvariants(withViews);
        assertFalse(withViews.getViews().isEmpty());
        assertTrue(withViews.getViews().size() <= 2);
        int index = 0;
        for (View view : withViews.getViews()) {
            assertEquals("v_" + index, view.getName());
            assertNull(view.getCheckOption());
            assertTrue(view.getQuery().startsWith("SELECT "));
            assertTrue(view.getQuery().contains(" JOIN "));
            assertTrue(view.getQuery().contains(" ON "));
            index++;
        }
        RelationalSchema withoutViews = map(SelfCheck.manyToManyDiagram(),
                SelfCheck.base().setGenerateViews(false));
        assertTrue(withoutViews.getViews().isEmpty());

        RelationalSchema withSequence = map(SelfCheck.strongDiagram(),
                SelfCheck.base().setUseIdentityColumns(false));
        assertEquals(1, withSequence.getSequences().size());
        assertEquals("seq_0", withSequence.getSequences().get(0).getName());
        RelationalSchema withIdentity = map(SelfCheck.strongDiagram(),
                SelfCheck.base().setUseIdentityColumns(true));
        assertTrue(withIdentity.getSequences().isEmpty());
    }

    /* ------------------------------------------------------------------ naming and determinism */

    @Test
    @DisplayName("naming is deterministic and collision free")
    void namingIsDeterministic() {
        for (int seed = 0; seed < 20; seed++) {
            MappingOptions options = SelfCheck.base().setKeyStrategy(KeyStrategy.SURROGATE_KEY)
                    .setGenerateIndexes(true).setGenerateCheckConstraints(true).setGenerateUniqueConstraints(true)
                    .setReferentialActionStrategy(ReferentialActionStrategy.RANDOM);
            Randomly.setSeed(seed);
            RelationalSchema first = new StandardERToRelationalMapper().map(SelfCheck.mixedDiagram(), options, "s",
                    new MappingTrace());
            Randomly.setSeed(seed);
            RelationalSchema second = new StandardERToRelationalMapper().map(SelfCheck.mixedDiagram(), options, "s",
                    new MappingTrace());
            assertEquals(describe(first), describe(second), "the same seed must produce the same schema");
            for (Table table : first.getTables()) {
                assertTrue(table.getName().length() <= SchemaInvariants.MAX_NAME_LENGTH);
                for (Column column : table.getColumns()) {
                    assertTrue(column.getName().length() <= SchemaInvariants.MAX_NAME_LENGTH);
                }
            }
        }
    }

    @Test
    @DisplayName("every entity and relationship is recorded in the mapping trace")
    void traceCoversTheWholeDiagram() {
        MappingTrace trace = new MappingTrace();
        Randomly.setSeed(99L);
        ERDiagram diagram = SelfCheck.mixedDiagram();
        RelationalSchema schema = new StandardERToRelationalMapper().map(diagram, SelfCheck.base(), "s", trace);
        assertInvariants(schema);
        for (EREntity entity : diagram.getEntities()) {
            assertTrue(trace.getEntityToTable().containsKey(entity.getName()),
                    "entity " + entity.getName() + " must be recorded");
            assertNotNull(trace.getEntityStrategy().get(entity.getName()));
        }
        for (ERRelationship relationship : diagram.getRelationships()) {
            assertTrue(trace.getRelationshipStrategy().containsKey(relationship.getName()),
                    "relationship " + relationship.getName() + " must be recorded");
        }
        assertFalse(trace.getNotes().isEmpty(), "the trace must explain the decisions it took");
    }

    @Test
    @DisplayName("the mapper never throws on degenerate diagrams")
    void degenerateDiagramsDoNotThrow() {
        ERDiagram empty = new ERDiagram().setName("empty");
        assertInvariants(map(empty, SelfCheck.base()));

        ERDiagram dangling = new ERDiagram().setName("dangling");
        EREntity entity = new EREntity("E0");
        entity.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        dangling.addEntity(entity);
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0"));
        relationship.addParticipant(new ERParticipant("E404"));
        dangling.addRelationship(relationship);
        assertInvariants(map(dangling, SelfCheck.base()));

        ERDiagram selfOwning = new ERDiagram().setName("selfOwning");
        EREntity weak = new EREntity("E0").setWeak(true).setOwnerEntity("E0").setOwnerRelationship("R0");
        weak.addAttribute(new ERAttribute("a0", DataType.integer()).setPartialKey(true));
        selfOwning.addEntity(weak);
        assertInvariants(map(selfOwning, SelfCheck.base()));

        ERDiagram untyped = new ERDiagram().setName("untyped");
        EREntity untypedEntity = new EREntity("E0");
        untypedEntity.addAttribute(new ERAttribute("a0", null).setPrimaryKey(true));
        untypedEntity.addAttribute(new ERAttribute("a1", null).setMultivalued(true));
        untyped.addEntity(untypedEntity);
        assertInvariants(map(untyped, SelfCheck.base()));
    }

    /* ------------------------------------------------------------------ alternate keys */

    /**
     * An alternate (candidate) key is not a second primary key: the entity is identified by one key
     * and merely <em>unique</em> on the other, which is the classic {@code PRIMARY KEY (code), UNIQUE
     * (name)} shape. Mapping the alternate key to the primary key instead would be a strictly stronger
     * claim about the data, and it would lose the UNIQUE constraint the oracles probe.
     */
    @Test
    @DisplayName("an alternate key becomes a UNIQUE constraint next to the primary key")
    void alternateKeysBecomeUniqueConstraints() {
        ERDiagram single = new ERDiagram().setName("alternate");
        EREntity one = new EREntity("E0");
        one.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        one.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        one.addAttribute(new ERAttribute("a2", DataType.varchar(50)).setAlternateKey(true));
        single.addEntity(one);

        for (KeyStrategy strategy : KeyStrategy.values()) {
            RelationalSchema schema = map(single, SelfCheck.base().setKeyStrategy(strategy));
            assertInvariants(schema);
            Table table = schema.getTable("t_0");
            assertTrue(enforcesUnique(table, List.of("a2")),
                    strategy + ": the alternate key a2 must be enforced uniquely, uniques="
                            + table.getUniqueConstraints() + " pk=" + table.getPrimaryKey().getColumns());
        }

        // two alternate attributes are one composite candidate key, exactly as two primary attributes are
        ERDiagram composite = new ERDiagram().setName("alternateComposite");
        EREntity two = new EREntity("E0");
        two.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        two.addAttribute(new ERAttribute("a1", DataType.varchar(20)).setAlternateKey(true));
        two.addAttribute(new ERAttribute("a2", DataType.varchar(50)).setAlternateKey(true));
        composite.addEntity(two);
        RelationalSchema compositeSchema = map(composite, SelfCheck.base());
        assertInvariants(compositeSchema);
        assertTrue(enforcesUnique(compositeSchema.getTable("t_0"), List.of("a1", "a2")),
                "a composite alternate key must be enforced as one constraint");

        // an "alternate" key that is really the primary key must not be emitted twice: a redundant
        // constraint on the key columns is harmless SQL but it made the conformance planner compare a
        // row with itself under the same key and drop it
        ERDiagram redundant = new ERDiagram().setName("redundantAlternate");
        EREntity same = new EREntity("E0");
        same.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true).setAlternateKey(true));
        redundant.addEntity(same);
        RelationalSchema redundantSchema = map(redundant, SelfCheck.base());
        assertInvariants(redundantSchema);
        Table redundantTable = redundantSchema.getTable("t_0");
        assertTrue(redundantTable.getUniqueConstraints().isEmpty(),
                "the key columns must not be declared unique a second time: " + redundantTable.getUniqueConstraints());
    }

    private static boolean enforcesUnique(Table table, List<String> columns) {
        if (table.getPrimaryKey() != null && sameColumns(table.getPrimaryKey().getColumns(), columns)) {
            return true;
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (sameColumns(unique.getColumns(), columns)) {
                return true;
            }
        }
        for (org.ddlsmith.common.schema.Index index : table.getIndexes()) {
            if (index.isUnique() && !index.isExpressionIndex() && sameColumns(index.getColumns(), columns)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameColumns(List<String> first, List<String> second) {
        return first.size() == second.size() && first.containsAll(second);
    }

    /* ------------------------------------------------------------------ helpers */

    private static RelationalSchema map(ERDiagram diagram, MappingOptions options) {
        Randomly.setSeed(4711L);
        MappingTrace trace = new MappingTrace();
        RelationalSchema schema = new StandardERToRelationalMapper().map(diagram, options, "s", trace);
        assertNotNull(schema);
        return schema;
    }

    private static void assertInvariants(RelationalSchema schema) {
        List<String> problems = SchemaInvariants.validate(schema);
        assertTrue(problems.isEmpty(), () -> "invariant violations: " + problems);
    }

    private static Table joinTable(RelationalSchema schema) {
        Table join = null;
        for (Table table : schema.getTables()) {
            if (table.getDerivedFromRelationship() != null && table.getDerivedFromEntity() == null) {
                join = table;
            }
        }
        assertNotNull(join, "the join table must exist: " + schema.getTableNames());
        return join;
    }

    private static boolean hasUnique(Table table, List<String> columns) {
        return table.getUniqueConstraints().stream()
                .anyMatch(unique -> SchemaInvariants.sameColumns(unique.getColumns(), columns));
    }

    private static String describe(RelationalSchema schema) {
        StringBuilder builder = new StringBuilder();
        for (Table table : schema.getTables()) {
            builder.append(table.getName()).append(table.getColumnNames()).append(table.getPrimaryKey())
                    .append(table.getForeignKeys()).append(table.getUniqueConstraints())
                    .append(table.getCheckConstraints()).append(table.getIndexes()).append('\n');
        }
        for (View view : schema.getViews()) {
            builder.append(view.getName()).append(view.getQuery()).append('\n');
        }
        return builder.toString();
    }
}
