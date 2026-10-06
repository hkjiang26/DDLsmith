package org.ddlsmith.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.CardinalityEnforcer;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.ERDiagramPrinter;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.graph.gen.GraphPropertyFiller;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;
import org.ddlsmith.graph.gen.SelfCheck;
import org.junit.jupiter.api.Test;

/**
 * Tests of the graph generation stage and of the graph-to-ER interpretation.
 *
 * <p>The bulk of the checks lives in {@link SelfCheck}, which runs the same assertions without a
 * test runner; this class asserts on its report and adds the focused, deterministic cases (registry,
 * schema shape, property typing, and the ER features that the mapper derives from the graph
 * structure).</p>
 */
class GraphGenerationTest {

    private static final int SEEDS = SelfCheck.DEFAULT_SEEDS;
    private static SelfCheck.Report sharedReport;

    /** Runs the 200-seed battery once and prints the collected statistics for debugging. */
    private static synchronized SelfCheck.Report sharedReport() {
        if (sharedReport == null) {
            sharedReport = SelfCheck.run(SEEDS);
            System.out.println(SelfCheck.describe(sharedReport));
        }
        return sharedReport;
    }

    private static void assertNoFailures(SelfCheck.Report report, String what) {
        if (!report.failures.isEmpty()) {
            StringBuilder sb = new StringBuilder(what + ": " + report.failures.size() + " failure(s)\n");
            for (int i = 0; i < Math.min(25, report.failures.size()); i++) {
                sb.append("  - ").append(report.failures.get(i)).append('\n');
            }
            fail(sb.toString());
        }
    }

    /* ------------------------------------------------------------------ the main battery */

    @Test
    void sixGeneratorsProduceValidGraphsForTwoHundredSeeds() {
        SelfCheck.Report report = sharedReport();
        assertNoFailures(report, "graph invariants");
        assertEquals(SEEDS * GraphGenerators.names().size(), report.graphs, "graphs checked");
        assertEquals(report.graphs, report.diagrams, "diagrams checked");
        for (SelfCheck.GeneratorStats stats : report.perGenerator.values()) {
            assertTrue(stats.averageEdges() > 0, stats.name + " produced no edge at all");
            assertTrue(stats.averageComponents() >= 1, stats.name + " has no connected component");
        }
        assertTrue(report.sawRecursiveRelationship, "no recursive relationship was produced");
        assertTrue(report.sawWeakEntity, "no weak entity was produced");
        assertTrue(report.sawIsaHierarchy, "no ISA hierarchy was produced");
        assertTrue(report.sawMultivaluedAttribute, "no multi-valued attribute was produced");
        System.out.println("features: recursive=" + report.recursiveExample + " | weak=" + report.weakExample
                + " | isa=" + report.isaExample + " | multivalued=" + report.multivaluedExample);
    }

    @Test
    void generatorsDifferStructurally() {
        SelfCheck.Report report = sharedReport();
        List<String> structural = SelfCheck.structuralChecks(report);
        if (!structural.isEmpty()) {
            fail("generators are not structurally distinct: " + structural + "\n" + SelfCheck.describe(report));
        }
        SelfCheck.GeneratorStats preferential = report.perGenerator.get("preferential");
        SelfCheck.GeneratorStats sliding = report.perGenerator.get("sliding");
        assertTrue(preferential.averageMaxDegree() > sliding.averageMaxDegree(),
                "preferential should reach a higher max degree than sliding");
        assertTrue(preferential.averageDegree() > sliding.averageDegree(),
                "preferential should be denser than sliding");
    }

    @Test
    void optionMatrixKeepsEveryInvariant() {
        SelfCheck.Report matrix = SelfCheck.runOptionsMatrix(12);
        assertNoFailures(matrix, "option matrix");
        assertTrue(matrix.graphs >= 12 * GraphGenerators.names().size(), "matrix coverage");
        assertTrue(matrix.graphsAboveMaxNodes > 0,
                "the enforcer's node-adding repair was never exercised by the awkward node ranges");
    }

    /* ------------------------------------------------------------------ registry and schema shape */

    @Test
    void generatorRegistryIsComplete() {
        assertEquals(List.of("random", "sliding", "tree", "preferential", "smallworld", "hybrid"),
                GraphGenerators.names());
        for (String name : GraphGenerators.names()) {
            GraphGenerator generator = GraphGenerators.forName(name);
            assertNotNull(generator);
            assertEquals(name, generator.getName());
        }
        assertEquals("hybrid", GraphGenerators.forName(" HYBRID ").getName());
        assertThrows(IllegalArgumentException.class, () -> GraphGenerators.forName("nope"));
        assertThrows(IllegalArgumentException.class, () -> GraphGenerators.forName(null));
        Randomly.setSeed(7);
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(GraphGenerators.random().getName());
        }
        assertEquals(new LinkedHashSet<>(GraphGenerators.names()), seen, "random() must cover all generators");
    }

    @Test
    void schemaGeneratorShapeIsStable() {
        Set<Integer> labelCounts = new LinkedHashSet<>();
        Set<Integer> typeCounts = new LinkedHashSet<>();
        for (int seed = 0; seed < SEEDS; seed++) {
            Randomly.setSeed(seed);
            GraphSchema schema = new RandomGraphSchemaGenerator().generate();
            SelfCheck.Report report = new SelfCheck.Report();
            SelfCheck.checkSchemaShape(report, "seed " + seed, schema);
            assertNoFailures(report, "schema shape at seed " + seed);
            labelCounts.add(schema.getLabelCount());
            typeCounts.add(schema.getRelationshipTypeCount());
        }
        assertTrue(labelCounts.contains(4) && labelCounts.contains(8),
                "the label count should span 4..8, saw " + labelCounts);
        assertTrue(typeCounts.contains(4), "the relationship type count should reach its minimum, saw " + typeCounts);
    }

    /* ------------------------------------------------------------------ property filling */

    @Test
    void propertyFillerCoversEveryDataType() {
        GraphSchema schema = new GraphSchema();
        GraphLabel label = new GraphLabel(0, "L0");
        List<DataType> types = List.of(DataType.smallint(), DataType.integer(), DataType.bigint(),
                DataType.decimal(10, 2), DataType.real(), DataType.doublePrecision(), DataType.charType(5),
                DataType.varchar(20), DataType.text(), DataType.bool(), DataType.date(), DataType.time(),
                DataType.timestamp(), DataType.timestampWithTimeZone(), DataType.blob(), DataType.json(),
                DataType.uuid(), DataType.enumOf(List.of("alpha", "beta")), DataType.arrayOf(DataType.integer()));
        int index = 0;
        for (DataType type : types) {
            label.addProperty(new GraphProperty("k" + index++, type, 1.0));
        }
        // one optional slot, to check that an absent key is how "null" is modelled
        label.addProperty(new GraphProperty("k" + index, DataType.integer(), 0.5));
        int optionalIndex = index;
        schema.addLabel(label);

        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        int nodeCount = 40;
        for (int i = 0; i < nodeCount; i++) {
            graph.addNode(new GraphNode(i, 0));
        }
        Randomly.setSeed(1234);
        GraphPropertyFiller.fill(graph, schema);

        Set<Object> integerValues = new LinkedHashSet<>();
        int present = 0;
        int absent = 0;
        for (GraphNode node : graph.getNodes()) {
            for (int i = 0; i < types.size(); i++) {
                String key = "k" + i;
                Object value = node.getProperty(key);
                assertNotNull(value, "mandatory slot " + key + " must be present on " + node);
                DataType type = types.get(i);
                assertTrue(SelfCheck.valueMatches(type, value),
                        "slot " + key + " (" + type.getName() + ") holds a " + value.getClass().getSimpleName());
                if (type.getCategory() == DataType.Category.INTEGER) {
                    integerValues.add(value);
                }
                if (type.getCategory() == DataType.Category.BLOB) {
                    assertTrue(((byte[]) value).length > 0, "blob values must not be empty");
                }
                if (type.getCategory() == DataType.Category.ARRAY) {
                    assertTrue(((Object[]) value).length > 0, "array values must not be empty");
                }
            }
            Object optional = node.getProperty("k" + optionalIndex);
            if (optional == null) {
                absent++;
            } else {
                present++;
                assertTrue(SelfCheck.valueMatches(DataType.integer(), optional), "optional slot value type");
            }
        }
        assertTrue(present > 0 && absent > 0,
                "an optional slot should be present sometimes and absent sometimes: " + present + "/" + absent);
        assertTrue(integerValues.size() <= 12,
                "integer values must come from a small repeating pool, saw " + integerValues.size());
        assertTrue(integerValues.size() >= 2, "integer values should vary");
    }

    /* ------------------------------------------------------------------ ER mapping */

    @Test
    void mapperTurnsSelfLoopTypesIntoMultivaluedAttributes() {
        GraphSchema schema = new GraphSchema();
        GraphLabel label = new GraphLabel(0, "L0");
        label.addProperty(new GraphProperty("k0", DataType.varchar(20), 1.0));
        schema.addLabel(label);
        GraphRelationshipType type = new GraphRelationshipType(0, "R0", 0, 0);
        type.setFromCardinality(Cardinality.MANY).setToCardinality(Cardinality.MANY);
        schema.addRelationshipType(type);

        GraphModel selfLoops = new GraphModel().setGeneratorName("fixture");
        selfLoops.addNode(new GraphNode(0, 0));
        selfLoops.addNode(new GraphNode(1, 0));
        selfLoops.addEdge(new GraphEdge(0, 0, 0, 0));
        selfLoops.addEdge(new GraphEdge(1, 1, 1, 0));

        int converted = 0;
        int kept = 0;
        for (int seed = 0; seed < 30; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(selfLoops, schema);
            EREntity entity = diagram.getEntity("E0");
            assertNotNull(entity);
            ERAttribute multivalued = entity.getAttribute("mv_R0");
            ERRelationship relationship = diagram.getRelationship("R0");
            assertTrue(multivalued == null ^ relationship == null,
                    "a self-loop-only recursive type must become either an attribute or a relationship");
            if (multivalued != null) {
                converted++;
                assertTrue(multivalued.isMultivalued());
                assertEquals(DataType.varchar(20), multivalued.getType());
            } else {
                kept++;
                assertEquals(2, relationship.getParticipants().size());
                assertTrue(relationship.isRecursive());
            }
        }
        assertTrue(converted > 0, "the multi-valued branch never fired in 30 seeds");
        assertTrue(kept > 0, "the relationship branch never fired in 30 seeds");

        // a recursive type with a non-self-loop edge must never become a multi-valued attribute
        GraphModel withRealEdge = new GraphModel().setGeneratorName("fixture");
        withRealEdge.addNode(new GraphNode(0, 0));
        withRealEdge.addNode(new GraphNode(1, 0));
        withRealEdge.addEdge(new GraphEdge(0, 0, 0, 0));
        withRealEdge.addEdge(new GraphEdge(1, 1, 1, 0));
        withRealEdge.addEdge(new GraphEdge(2, 0, 1, 0));
        for (int seed = 0; seed < 30; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(withRealEdge, schema);
            assertNull(diagram.getEntity("E0").getAttribute("mv_R0"),
                    "a recursive type with a non-self-loop edge is a relationship");
            assertNotNull(diagram.getRelationship("R0"));
        }
    }

    @Test
    void mapperDetectsWeakEntities() {
        GraphSchema schema = new GraphSchema();
        GraphLabel dependent = new GraphLabel(0, "L0");
        dependent.addProperty(new GraphProperty("k0", DataType.varchar(20), 0.5));
        schema.addLabel(dependent);
        GraphLabel owner = new GraphLabel(1, "L1");
        owner.addProperty(new GraphProperty("k1", DataType.integer(), 1.0));
        schema.addLabel(owner);
        GraphRelationshipType ownership = new GraphRelationshipType(0, "R0", 0, 1);
        ownership.setFromCardinality(Cardinality.ONE).setToCardinality(Cardinality.ONE);
        ownership.setFromParticipation(Participation.TOTAL);
        schema.addRelationshipType(ownership);

        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        graph.addNode(new GraphNode(0, 0));
        graph.addNode(new GraphNode(1, 0));
        graph.addNode(new GraphNode(2, 1));
        graph.addNode(new GraphNode(3, 1));
        graph.addEdge(new GraphEdge(0, 0, 2, 0));
        graph.addEdge(new GraphEdge(1, 1, 3, 0));

        Randomly.setSeed(3);
        ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
        EREntity entity = diagram.getEntity("E0");
        assertTrue(entity.isWeak(), "E0 has no key of its own and exactly one owner relationship");
        assertEquals("E1", entity.getOwnerEntity());
        assertEquals("R0", entity.getOwnerRelationship());
        ERAttribute key = entity.getAttribute("id");
        assertNotNull(key, "a label without a mandatory slot gets a synthetic key");
        assertTrue(key.isPartialKey() && !key.isPrimaryKey(), "the synthetic key becomes a partial key");
        assertTrue(diagram.getRelationship("R0").isIdentifying());
        assertFalse(diagram.getEntity("E1").isWeak(), "the owner has a mandatory key and is not weak");

        // a second incident relationship type makes the label a regular entity again
        GraphRelationshipType extra = new GraphRelationshipType(1, "R1", 0, 1);
        extra.setFromCardinality(Cardinality.MANY).setToCardinality(Cardinality.MANY);
        schema.addRelationshipType(extra);
        Randomly.setSeed(3);
        ERDiagram second = new DefaultGraphToERMapper().map(graph, schema);
        assertFalse(second.getEntity("E0").isWeak(), "with two incident types the heuristic must not fire");
        assertTrue(second.getEntity("E0").hasPrimaryKey(), "the synthetic key stays a primary key");
    }

    @Test
    void mapperDetectsGeneralizationHierarchiesUpToThreeLevels() {
        GraphSchema schema = new GraphSchema();
        GraphLabel sub = new GraphLabel(0, "L0");
        sub.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        sub.addProperty(new GraphProperty("k1", DataType.varchar(20), 1.0));
        sub.addProperty(new GraphProperty("k2", DataType.bool(), 1.0));
        schema.addLabel(sub);
        GraphLabel middle = new GraphLabel(1, "L1");
        middle.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        middle.addProperty(new GraphProperty("k1", DataType.varchar(20), 1.0));
        schema.addLabel(middle);
        GraphLabel top = new GraphLabel(2, "L2");
        top.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        schema.addLabel(top);
        GraphRelationshipType first = new GraphRelationshipType(0, "R0", 0, 1);
        first.setFromCardinality(Cardinality.ONE).setToCardinality(Cardinality.ONE);
        first.setFromParticipation(Participation.TOTAL);
        schema.addRelationshipType(first);
        GraphRelationshipType second = new GraphRelationshipType(1, "R1", 1, 2);
        second.setFromCardinality(Cardinality.ONE).setToCardinality(Cardinality.ONE);
        second.setFromParticipation(Participation.TOTAL);
        schema.addRelationshipType(second);

        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        graph.addNode(new GraphNode(0, 0));
        graph.addNode(new GraphNode(1, 1));
        graph.addNode(new GraphNode(2, 2));
        graph.addEdge(new GraphEdge(0, 0, 1, 0));
        graph.addEdge(new GraphEdge(1, 1, 2, 1));

        int hierarchies = 0;
        int chains = 0;
        for (int seed = 0; seed < 30; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
            for (EREntity entity : diagram.getEntities()) {
                assertTrue(isaDepth(diagram, entity) <= 3,
                        "hierarchies must stay at most three levels deep, " + entity.getName() + " is deeper");
                if (entity.isGeneralizationSubtype() && entity.isGeneralizationSupertype()) {
                    chains++;
                }
                if (entity.isGeneralizationSubtype()) {
                    hierarchies++;
                    for (String superName : entity.getSuperEntities()) {
                        EREntity supertype = diagram.getEntity(superName);
                        assertNotNull(supertype, "the supertype must exist");
                        assertTrue(supertype.getSubEntities().contains(entity.getName()));
                        assertNotNull(supertype.getDiscriminatorAttribute(), "the supertype needs a discriminator");
                        // a subtype inherits its supertype's key instead of declaring one, so a
                        // supertype that is itself a subtype has no key attributes of its own: its
                        // identity comes from further up the chain
                        assertFalse(diagram.getIdentifyingAttributes(supertype.getName()).isEmpty(),
                                "supertype " + supertype.getName() + " must be identifiable, directly or by"
                                        + " inheritance");
                    }
                }
            }
        }
        assertTrue(hierarchies > 0, "no ISA hierarchy was derived from the inheritance fixture");
        assertTrue(chains > 0, "a three-level chain (a subtype that is also a supertype) must be reachable");
    }

    /** @return the number of ISA levels above {@code entity}, 1 for a root */
    private static int isaDepth(ERDiagram diagram, EREntity entity) {
        int deepest = 0;
        for (String superName : entity.getSuperEntities()) {
            EREntity supertype = diagram.getEntity(superName);
            if (supertype != null) {
                deepest = Math.max(deepest, isaDepth(diagram, supertype));
            }
        }
        return deepest + 1;
    }

    @Test
    void mapperMakesAttributeInheritanceImplicit() {
        // A subtype inherits its supertype's attributes; it must not re-declare them. The graph
        // schema generator models inheritance by copying the parent's property slots into the child
        // label, so before this was fixed every subtype carried its own copies, and merging a
        // lattice into one table emitted both `a0` and `a0_2` for one logical attribute.
        int subtypes = 0;
        for (String name : GraphGenerators.names()) {
            for (int seed = 0; seed < 60; seed++) {
                Randomly.setSeed(seed);
                GraphSchema schema = new RandomGraphSchemaGenerator().generate();
                GraphModel graph = GraphGenerators.forName(name).generate(schema, new GraphGenerationOptions());
                ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
                for (EREntity entity : diagram.getEntities()) {
                    if (!entity.isGeneralizationSubtype()) {
                        continue;
                    }
                    subtypes++;
                    Set<String> own = new LinkedHashSet<>();
                    for (ERAttribute attribute : entity.getAttributes()) {
                        own.add(attribute.getName());
                    }
                    for (String superName : entity.getSuperEntities()) {
                        EREntity supertype = diagram.getEntity(superName);
                        assertNotNull(supertype, "the supertype must exist");
                        for (ERAttribute inherited : supertype.getAttributes()) {
                            assertFalse(own.contains(inherited.getName()),
                                    entity.getName() + " is a subtype of " + superName + " but re-declares its"
                                            + " attribute " + inherited.getName() + "; a subtype keeps only the"
                                            + " attributes it adds");
                        }
                    }
                    // the subtype must still be identifiable: its key comes from the supertype
                    assertFalse(diagram.getIdentifyingAttributes(entity.getName()).isEmpty(),
                            "subtype " + entity.getName() + " must inherit a key from " + entity.getSuperEntities());
                }
            }
        }
        assertTrue(subtypes > 0, "the run must have produced at least one subtype");
    }

    @Test
    void mapperKeepsDiagramInvariantsForHandBuiltSchemas() {
        // every label gets an entity, even when it has no node at all
        GraphSchema schema = new GraphSchema();
        GraphLabel empty = new GraphLabel(0, "L0");
        empty.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        schema.addLabel(empty);
        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        Randomly.setSeed(1);
        ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
        assertEquals(1, diagram.getEntities().size());
        assertFalse(diagram.getEntity("E0").getAttributes().isEmpty(), "an entity always has an attribute");
        assertTrue(diagram.getRelationships().isEmpty(), "a type without edges produces no relationship");
        assertEquals("er_fixture", diagram.getName());
    }

    @Test
    void mapperSurvivesDegenerateInputs() {
        DefaultGraphToERMapper mapper = new DefaultGraphToERMapper();
        Randomly.setSeed(0);
        ERDiagram empty = mapper.map(new GraphModel().setGeneratorName("none"), new GraphSchema());
        assertNotNull(empty);
        assertEquals(0, empty.getEntities().size());
        assertEquals(0, empty.getRelationships().size());
        assertTrue(ERDiagramPrinter.toMermaid(empty).startsWith("erDiagram"));

        // a relationship type that points at labels the schema does not have must be ignored
        GraphSchema broken = new GraphSchema();
        GraphLabel label = new GraphLabel(0, "L0");
        label.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        broken.addLabel(label);
        broken.addRelationshipType(new GraphRelationshipType(0, "R0", 5, 9));
        GraphModel graph = new GraphModel().setGeneratorName("broken");
        graph.addNode(new GraphNode(0, 0));
        graph.addEdge(new GraphEdge(0, 0, 0, 0));
        graph.addEdge(new GraphEdge(1, 0, 0, 99));
        Randomly.setSeed(0);
        ERDiagram diagram = mapper.map(graph, broken);
        assertEquals(1, diagram.getEntities().size());
        assertTrue(diagram.getRelationships().isEmpty());
        for (ERRelationship relationship : diagram.getRelationships()) {
            assertTrue(relationship.getParticipants().size() >= 2);
            for (ERParticipant participant : relationship.getParticipants()) {
                assertTrue(diagram.hasEntity(participant.getEntityName()));
            }
        }
    }

    @Test
    void mapperMergesTrianglesIntoTernaryRelationships() {
        GraphSchema schema = triangleSchema();
        GraphModel graph = triangleGraph();
        int ternaries = 0;
        int keptBinary = 0;
        for (int seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
            ERRelationship ternary = null;
            for (ERRelationship relationship : diagram.getRelationships()) {
                if (relationship.isNary()) {
                    ternary = relationship;
                }
            }
            if (ternary == null) {
                if (!diagram.getAggregationEntities().isEmpty()) {
                    // the triangle was read as an aggregation instead: the inner relationship, the
                    // outer relationship and the two absorbed links remain
                    continue;
                }
                keptBinary++;
                assertEquals(3, diagram.getRelationships().size(),
                        "without a ternary or an aggregation the three binary relationships must remain");
                continue;
            }
            ternaries++;
            assertEquals(3, ternary.getParticipants().size(), "a ternary has three participants");
            assertEquals(3, ternary.getMergedTypeNames().size(), "the merged types must be recorded");
            for (ERParticipant participant : ternary.getParticipants()) {
                assertTrue(diagram.hasEntity(participant.getEntityName()),
                        "every participant must reference an existing entity");
            }
            // the merged attributes of the three binary types move to the ternary
            assertEquals(3, ternary.getAttributes().size(),
                    "the relationship attributes of the merged types must move to the ternary");
            for (String merged : ternary.getMergedTypeNames()) {
                assertNull(diagram.getRelationship(merged), merged + " must not survive as a binary relationship");
            }
        }
        assertTrue(ternaries > 0, "the ternary branch never fired in 40 seeds");
        assertTrue(keptBinary > 0, "the ternary branch must not fire every time");
    }

    @Test
    void mapperReadsTrianglesAsAggregations() {
        GraphSchema schema = triangleSchema();
        GraphModel graph = triangleGraph();
        int aggregations = 0;
        for (int seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
            List<EREntity> aggregates = diagram.getAggregationEntities();
            if (aggregates.isEmpty()) {
                continue;
            }
            aggregations++;
            for (EREntity aggregate : aggregates) {
                ERRelationship inner = diagram.getRelationship(aggregate.getAggregatedRelationship());
                assertNotNull(inner, "the aggregated relationship must exist");
                assertEquals(ERRelationship.BinaryCardinality.MANY_TO_MANY, inner.getBinaryCardinality(),
                        "only an M:N relationship can be aggregated");
                assertEquals(0, aggregate.getAttributes().size(), "an aggregation entity has no attributes of its own");
                // the outer relationship points at the aggregation and absorbed the two links
                ERRelationship outer = null;
                for (ERRelationship relationship : diagram.getRelationships()) {
                    if (relationship.getParticipant(aggregate.getName()) != null) {
                        outer = relationship;
                    }
                }
                assertNotNull(outer, "the aggregation must be referenced by an outer relationship");
                assertEquals(2, outer.getParticipants().size());
                assertEquals(2, outer.getMergedTypeNames().size(), "the two absorbed links must be recorded");
                int absorbed = 0;
                for (ERRelationship relationship : diagram.getRelationships()) {
                    if (relationship.isAggregationLink()) {
                        absorbed++;
                        assertTrue(outer.getMergedTypeNames().contains(relationship.getName()),
                                "every absorbed link must be recorded on the outer relationship");
                    }
                }
                assertEquals(2, absorbed, "exactly the two links of the third label are absorbed");
            }
        }
        assertTrue(aggregations > 0, "the aggregation branch never fired in 40 seeds");
    }

    @Test
    void mapperDetectsUnionTypes() {
        GraphSchema schema = new GraphSchema();
        GraphLabel first = new GraphLabel(0, "L0");
        first.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        schema.addLabel(first);
        GraphLabel second = new GraphLabel(1, "L1");
        second.addProperty(new GraphProperty("k1", DataType.varchar(20), 1.0));
        schema.addLabel(second);
        GraphLabel category = new GraphLabel(2, "L2");
        category.addProperty(new GraphProperty("k2", DataType.integer(), 1.0));
        schema.addLabel(category);
        // two 1:1 links that are PARTIAL on the category's own side: the union-type shape
        GraphRelationshipType toFirst = new GraphRelationshipType(0, "R0", 2, 0);
        toFirst.setFromCardinality(Cardinality.ONE).setToCardinality(Cardinality.ONE);
        schema.addRelationshipType(toFirst);
        GraphRelationshipType toSecond = new GraphRelationshipType(1, "R1", 2, 1);
        toSecond.setFromCardinality(Cardinality.ONE).setToCardinality(Cardinality.ONE);
        schema.addRelationshipType(toSecond);
        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        graph.addNode(new GraphNode(0, 0));
        graph.addNode(new GraphNode(1, 1));
        graph.addNode(new GraphNode(2, 2));
        graph.addNode(new GraphNode(3, 2));
        graph.addEdge(new GraphEdge(0, 2, 0, 0));
        graph.addEdge(new GraphEdge(1, 3, 1, 1));

        int categories = 0;
        for (int seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
            EREntity entity = diagram.getEntity("E2");
            if (!entity.isCategory()) {
                continue;
            }
            categories++;
            assertEquals(List.of("E0", "E1"), entity.getCategorySupers(), "the two superclasses must be recorded");
            for (String superName : entity.getCategorySupers()) {
                assertTrue(diagram.hasEntity(superName));
            }
            for (String link : List.of("R0", "R1")) {
                ERRelationship relationship = diagram.getRelationship(link);
                assertNotNull(relationship, link + " must stay in the diagram as evidence");
                assertTrue(relationship.isCategoryLink(), link + " must be marked as a category link");
                assertFalse(relationship.isGeneralization(), "a category link is not an ISA link");
            }
        }
        assertTrue(categories > 0, "the union-type branch never fired in 40 seeds");
    }

    @Test
    void mapperProducesCompositeKeysAndCompositePartialKeys() {
        // a label with two mandatory slots sometimes gets both as its key
        GraphSchema schema = new GraphSchema();
        GraphLabel label = new GraphLabel(0, "L0");
        label.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        label.addProperty(new GraphProperty("k1", DataType.varchar(20), 1.0));
        schema.addLabel(label);
        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        graph.addNode(new GraphNode(0, 0));
        int compositeKeys = 0;
        for (int seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
            if (diagram.getEntity("E0").getPrimaryKeyAttributes().size() == 2) {
                compositeKeys++;
            }
        }
        assertTrue(compositeKeys > 0, "a composite natural key was never produced in 40 seeds");

        // a weak entity sometimes gets two partial key attributes
        GraphSchema weakSchema = new GraphSchema();
        GraphLabel dependent = new GraphLabel(0, "L0");
        dependent.addProperty(new GraphProperty("k0", DataType.varchar(20), 0.5));
        weakSchema.addLabel(dependent);
        GraphLabel owner = new GraphLabel(1, "L1");
        owner.addProperty(new GraphProperty("k1", DataType.integer(), 1.0));
        weakSchema.addLabel(owner);
        GraphRelationshipType ownership = new GraphRelationshipType(0, "R0", 0, 1);
        ownership.setFromCardinality(Cardinality.ONE).setToCardinality(Cardinality.ONE);
        ownership.setFromParticipation(Participation.TOTAL);
        weakSchema.addRelationshipType(ownership);
        GraphModel weakGraph = new GraphModel().setGeneratorName("fixture");
        weakGraph.addNode(new GraphNode(0, 0));
        weakGraph.addNode(new GraphNode(1, 1));
        weakGraph.addEdge(new GraphEdge(0, 0, 1, 0));
        int compositePartial = 0;
        for (int seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(weakGraph, weakSchema);
            EREntity entity = diagram.getEntity("E0");
            assertTrue(entity.isWeak(), "the fixture must produce a weak entity");
            if (entity.getPartialKeyAttributes().size() == 2) {
                compositePartial++;
            }
        }
        assertTrue(compositePartial > 0, "a composite partial key was never produced in 40 seeds");
    }

    @Test
    void mapperProducesNestedAndMultivaluedCompositeAttributes() {
        // nested composites: three same-typed attributes let the second component become composite
        GraphSchema schema = new GraphSchema();
        GraphLabel label = new GraphLabel(0, "L0");
        label.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        label.addProperty(new GraphProperty("k1", DataType.varchar(20), 0.6));
        label.addProperty(new GraphProperty("k2", DataType.varchar(20), 0.6));
        label.addProperty(new GraphProperty("k3", DataType.varchar(20), 0.6));
        schema.addLabel(label);
        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        graph.addNode(new GraphNode(0, 0));
        int nested = 0;
        for (int seed = 0; seed < 60; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
            for (ERAttribute attribute : diagram.getEntity("E0").getAttributes()) {
                if (!attribute.isComposite()) {
                    continue;
                }
                for (ERAttribute component : attribute.getComponents()) {
                    if (component.isComposite()) {
                        nested++;
                    }
                }
            }
        }
        assertTrue(nested > 0, "a nested composite attribute was never produced in 60 seeds");

        // a multi-valued attribute taken from a self-loop type can be composite
        GraphSchema mvSchema = new GraphSchema();
        GraphLabel mvLabel = new GraphLabel(0, "L0");
        mvLabel.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        mvSchema.addLabel(mvLabel);
        GraphRelationshipType selfLoop = new GraphRelationshipType(0, "R0", 0, 0);
        selfLoop.setFromCardinality(Cardinality.MANY).setToCardinality(Cardinality.MANY);
        selfLoop.addProperty(new GraphProperty("k1", DataType.integer(), 1.0));
        selfLoop.addProperty(new GraphProperty("k2", DataType.varchar(20), 1.0));
        mvSchema.addRelationshipType(selfLoop);
        GraphModel mvGraph = new GraphModel().setGeneratorName("fixture");
        mvGraph.addNode(new GraphNode(0, 0));
        mvGraph.addEdge(new GraphEdge(0, 0, 0, 0));
        int multivaluedComposite = 0;
        int multivaluedSimple = 0;
        for (int seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(mvGraph, mvSchema);
            ERAttribute attribute = diagram.getEntity("E0").getAttribute("mv_R0");
            if (attribute == null) {
                // the conversion is probabilistic: the other branch keeps the relationship
                assertNotNull(diagram.getRelationship("R0"), "either the attribute or the relationship must exist");
                continue;
            }
            assertTrue(attribute.isMultivalued());
            if (attribute.isComposite()) {
                multivaluedComposite++;
                assertEquals(2, attribute.getComponents().size());
            } else {
                multivaluedSimple++;
            }
        }
        assertTrue(multivaluedComposite > 0, "a composite multi-valued attribute was never produced in 40 seeds");
        assertTrue(multivaluedSimple > 0, "a simple multi-valued attribute was never produced in 40 seeds");
    }

    @Test
    void mapperDrawsSpecializationCompleteness() {
        GraphSchema schema = new GraphSchema();
        GraphLabel sub = new GraphLabel(0, "L0");
        sub.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        sub.addProperty(new GraphProperty("k1", DataType.varchar(20), 1.0));
        schema.addLabel(sub);
        GraphLabel top = new GraphLabel(1, "L1");
        top.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        schema.addLabel(top);
        GraphRelationshipType link = new GraphRelationshipType(0, "R0", 0, 1);
        link.setFromCardinality(Cardinality.ONE).setToCardinality(Cardinality.ONE);
        link.setFromParticipation(Participation.TOTAL);
        schema.addRelationshipType(link);
        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        graph.addNode(new GraphNode(0, 0));
        graph.addNode(new GraphNode(1, 1));
        graph.addEdge(new GraphEdge(0, 0, 1, 0));
        int total = 0;
        int partial = 0;
        for (int seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
            EREntity supertype = diagram.getEntity("E1");
            if (!supertype.isGeneralizationSupertype()) {
                continue;
            }
            if (supertype.isTotalSpecialization()) {
                total++;
            } else {
                partial++;
            }
        }
        assertTrue(total > 0 && partial > 0,
                "both completeness values must be reachable: total=" + total + " partial=" + partial);
    }

    /* ------------------------------------------------------------------ fixtures */

    /**
     * Three labels pairwise connected by three relationship types, each instantiated: the shape the
     * mapper reads either as a ternary relationship or as an aggregation. The first pair is M:N, so
     * the aggregation reading has an inner relationship that maps to a join table.
     */
    private static GraphSchema triangleSchema() {
        GraphSchema schema = new GraphSchema();
        for (int i = 0; i < 3; i++) {
            GraphLabel label = new GraphLabel(i, "L" + i);
            label.addProperty(new GraphProperty("k" + i, DataType.integer(), 1.0));
            schema.addLabel(label);
        }
        GraphRelationshipType inner = new GraphRelationshipType(0, "R0", 0, 1);
        inner.setFromCardinality(Cardinality.MANY).setToCardinality(Cardinality.MANY);
        inner.addProperty(new GraphProperty("k10", DataType.integer(), 1.0));
        schema.addRelationshipType(inner);
        GraphRelationshipType first = new GraphRelationshipType(1, "R1", 0, 2);
        first.setFromCardinality(Cardinality.ONE).setToCardinality(Cardinality.MANY);
        first.addProperty(new GraphProperty("k11", DataType.varchar(20), 1.0));
        schema.addRelationshipType(first);
        GraphRelationshipType second = new GraphRelationshipType(2, "R2", 1, 2);
        second.setFromCardinality(Cardinality.ONE).setToCardinality(Cardinality.MANY);
        second.addProperty(new GraphProperty("k12", DataType.date(), 1.0));
        schema.addRelationshipType(second);
        return schema;
    }

    private static GraphModel triangleGraph() {
        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        graph.addNode(new GraphNode(0, 0));
        graph.addNode(new GraphNode(1, 1));
        graph.addNode(new GraphNode(2, 2));
        graph.addEdge(new GraphEdge(0, 0, 1, 0));
        graph.addEdge(new GraphEdge(1, 0, 2, 1));
        graph.addEdge(new GraphEdge(2, 1, 2, 2));
        return graph;
    }

    /* ------------------------------------------------------------------ printers */

    @Test
    void printersRenderDiagramsAndGraphs() {
        ERDiagram diagram = new ERDiagram().setName("er_test");
        EREntity parent = new EREntity("E0");
        parent.addAttribute(new ERAttribute("id", DataType.integer()).setPrimaryKey(true));
        ERAttribute composite = new ERAttribute("comp_0", DataType.varchar(20)).setComposite(true);
        composite.addComponent(new ERAttribute("a0", DataType.varchar(20)));
        composite.addComponent(new ERAttribute("a1", DataType.varchar(20)));
        parent.addAttribute(composite);
        parent.addAttribute(new ERAttribute("a2", DataType.decimal(10, 2)).setDerived(true)
                .setDerivedExpression("a0 + 1"));
        diagram.addEntity(parent);
        EREntity child = new EREntity("E1");
        child.addAttribute(new ERAttribute("a3", DataType.bool()));
        child.setSuperEntity("E0");
        child.setWeak(true);
        child.setOwnerEntity("E0");
        child.setOwnerRelationship("R0");
        diagram.addEntity(child);
        parent.addSubEntity("E1");
        parent.setDisjoint(false);
        parent.setDiscriminatorAttribute("a3");
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.TOTAL));
        relationship.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        relationship.addAttribute(new ERAttribute("a4", DataType.date()));
        relationship.setIdentifying(true);
        diagram.addRelationship(relationship);

        String description = ERDiagramPrinter.describe(diagram);
        assertTrue(description.contains("er_test"), description);
        assertTrue(description.contains("E0"), description);
        assertTrue(description.contains("R0"), description);
        assertTrue(description.contains("statistics"), description);
        assertTrue(description.contains("IDENTIFYING"), description);
        assertTrue(description.contains("WEAK"), description);

        String mermaid = ERDiagramPrinter.toMermaid(diagram);
        assertTrue(mermaid.startsWith("erDiagram"), mermaid);
        assertTrue(mermaid.contains("E0 {"), mermaid);
        assertTrue(mermaid.contains("INTEGER id PK"), mermaid);
        assertTrue(mermaid.contains("VARCHAR_20_ a0"), "composites are flattened: " + mermaid);
        assertFalse(mermaid.contains("comp_0"), "the composite itself is not a column: " + mermaid);
        assertTrue(mermaid.contains("E0 ||--o{ E1 : R0"), mermaid);
        assertTrue(mermaid.contains("\"derived\""), mermaid);

        // the graph description must mention the schema and the statistics too
        Randomly.setSeed(5);
        GraphSchema schema = new RandomGraphSchemaGenerator().generate();
        GraphModel graph = GraphGenerators.forName("random").generate(schema, new GraphGenerationOptions());
        String graphDescription = ERDiagramPrinter.describe(graph, schema);
        assertTrue(graphDescription.contains("graph random"), graphDescription);
        assertTrue(graphDescription.contains("labels"), graphDescription);
        assertTrue(graphDescription.contains("statistics"), graphDescription);
        assertTrue(graphDescription.contains("L0"), graphDescription);
    }

    /* ------------------------------------------------------------------ cardinality enforcer */

    @Test
    void enforcerRepairsEveryConstraintKind() {
        GraphSchema schema = new GraphSchema();
        GraphLabel from = new GraphLabel(0, "L0");
        from.addProperty(new GraphProperty("k0", DataType.integer(), 1.0));
        schema.addLabel(from);
        GraphLabel to = new GraphLabel(1, "L1");
        to.addProperty(new GraphProperty("k1", DataType.integer(), 1.0));
        schema.addLabel(to);
        GraphRelationshipType type = new GraphRelationshipType(0, "R0", 0, 1);
        type.setFromCardinality(Cardinality.ONE);
        type.setToCardinality(Cardinality.ONE);
        type.setFromParticipation(Participation.TOTAL);
        type.setToParticipation(Participation.TOTAL);
        schema.addRelationshipType(type);

        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        for (int i = 0; i < 4; i++) {
            graph.addNode(new GraphNode(i, i < 2 ? 0 : 1));
        }
        // two sources point at the same target: one edge must go, and the other source needs one
        graph.addEdge(new GraphEdge(0, 0, 2, 0));
        graph.addEdge(new GraphEdge(1, 1, 2, 0));

        assertFalse(CardinalityEnforcer.isValid(graph, schema), "the fixture starts out invalid");
        Randomly.setSeed(11);
        CardinalityEnforcer.enforce(graph, schema);
        assertTrue(CardinalityEnforcer.isValid(graph, schema),
                "every constraint must hold after enforce\n" + ERDiagramPrinter.describe(graph, schema));
        assertEquals(2, graph.getEdgesOfType(0).size(), "a bijection between the two labels");
        for (int i = 0; i < graph.getNodeCount(); i++) {
            int label = graph.getNode(i).getLabelIndex();
            int outgoing = graph.getOutEdges(i, 0).size();
            int incoming = graph.getInEdges(i, 0).size();
            if (label == 0) {
                // source side: TOTAL participation and toCardinality == ONE, so exactly one outgoing
                assertEquals(1, outgoing, "node " + i + " (source) must have one outgoing edge");
            } else {
                // target side: TOTAL participation and fromCardinality == ONE, so exactly one incoming
                assertEquals(1, incoming, "node " + i + " (target) must have one incoming edge");
            }
            assertTrue(outgoing <= 1, "toCardinality ONE caps the outgoing edges");
            assertTrue(incoming <= 1, "fromCardinality ONE caps the incoming edges");
        }
        for (int i = 0; i < graph.getEdgeCount(); i++) {
            assertEquals(i, graph.getEdge(i).getId(), "edge ids stay aligned with their index");
        }
        // enforcing a valid graph again must not change it
        int edges = graph.getEdgeCount();
        CardinalityEnforcer.enforce(graph, schema);
        assertEquals(edges, graph.getEdgeCount());
        assertTrue(CardinalityEnforcer.isValid(graph, schema));
    }

    @Test
    void enforcerAddsNodesWhenTheNodeCountsMakeTheSchemaUnsatisfiable() {
        GraphSchema schema = new GraphSchema();
        GraphLabel from = new GraphLabel(0, "L0");
        schema.addLabel(from);
        GraphLabel to = new GraphLabel(1, "L1");
        schema.addLabel(to);
        GraphRelationshipType type = new GraphRelationshipType(0, "R0", 0, 1);
        type.setFromCardinality(Cardinality.ONE);
        type.setToCardinality(Cardinality.ONE);
        type.setFromParticipation(Participation.TOTAL);
        schema.addRelationshipType(type);

        // three sources, one target: no injection exists, so a target node has to be added
        GraphModel graph = new GraphModel().setGeneratorName("fixture");
        for (int i = 0; i < 3; i++) {
            graph.addNode(new GraphNode(i, 0));
        }
        graph.addNode(new GraphNode(3, 1));
        Randomly.setSeed(2);
        CardinalityEnforcer.enforce(graph, schema);
        assertTrue(CardinalityEnforcer.isValid(graph, schema), "the added nodes must make the schema satisfiable");
        assertTrue(graph.getNodeCount() > 4, "target nodes were added");
    }
}
