package org.ddlsmith.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.algorithm.Pipeline;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.graph.gen.GraphPropertyFiller;
import org.ddlsmith.graph.gen.RandomGraphFiller;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;
import org.junit.jupiter.api.Test;

/**
 * Tests of the filling stage: the half of the input generation that turns one base graph into many
 * different databases.
 *
 * <p>What has to hold is the pair the whole design rests on. The base graph is only nodes and edges,
 * so the filling is the only source of attributes - and it is drawn per database, so the databases of
 * one group differ in attribute count, domain, nullability and keys while sharing their topology. The
 * filling must also stay inside the limits the rest of the pipeline relies on: a key attribute has an
 * indexable domain, and the inheritance plan is acyclic, at most three levels deep and a strict
 * superset per link, because that is what the ER mapper recognises.</p>
 */
class FillingTest {

    private static final int SEEDS = 60;

    private static GraphFilling plan(GraphSchema schema) {
        return new RandomGraphFiller().plan(schema);
    }

    @Test
    void theSameStreamPlansTheSameFilling() {
        for (int seed = 0; seed < 10; seed++) {
            Randomly.setSeed(seed);
            GraphSchema schema = new RandomGraphSchemaGenerator().generate();
            GraphFilling first = plan(schema);
            Randomly.setSeed(seed);
            GraphSchema second = new RandomGraphSchemaGenerator().generate();
            GraphFilling second2 = plan(second);
            assertEquals(first.describe(), second2.describe(), "the filling is not reproducible at seed " + seed);
        }
    }

    @Test
    void differentSlotsFillOneBaseGraphDifferently() {
        Randomly.setSeed(4242L);
        GraphSchema schema = new RandomGraphSchemaGenerator().generate();
        Set<String> fillings = new LinkedHashSet<>();
        Set<Integer> attributeTotals = new LinkedHashSet<>();
        for (int slot = 0; slot < 8; slot++) {
            Randomly.setSeed(Pipeline.slotSeed(4242L, slot));
            GraphFilling filling = plan(schema);
            fillings.add(filling.describe());
            int attributes = 0;
            for (int label = 0; label < schema.getLabelCount(); label++) {
                attributes += filling.attributesOfLabel(label).size();
            }
            attributeTotals.add(attributes);
        }
        assertTrue(fillings.size() >= 7, "one base graph must be filled differently per slot: " + fillings.size());
        assertTrue(attributeTotals.size() > 1, "the attribute count must differ across slots: " + attributeTotals);
    }

    @Test
    void everyProfileAndEveryExtraDomainIsReached() {
        int lean = 0;
        int wide = 0;
        Set<DataType.Category> extraDomains = new LinkedHashSet<>();
        Map<DataType.Category, Integer> keyDomains = new LinkedHashMap<>();
        for (int seed = 0; seed < SEEDS; seed++) {
            Randomly.setSeed(seed);
            GraphSchema schema = new RandomGraphSchemaGenerator().generate();
            GraphFilling filling = plan(schema);
            for (int label = 0; label < schema.getLabelCount(); label++) {
                int count = filling.attributesOfLabel(label).size();
                if (count <= 1) {
                    lean++;
                }
                if (count >= 8) {
                    wide++;
                }
                for (GraphProperty slot : filling.attributesOfLabel(label)) {
                    if (!DataType.commonTypes().contains(slot.getType())) {
                        extraDomains.add(slot.getType().getCategory());
                    }
                    if (slot.getKeyRole() != GraphProperty.KeyRole.NONE) {
                        keyDomains.merge(slot.getType().getCategory(), 1, Integer::sum);
                    }
                }
            }
        }
        assertTrue(lean > 0, "the lean profile (an almost attribute-less label) was never reached");
        assertTrue(wide > 0, "the wide profile (a label with 8+ attributes) was never reached");
        assertTrue(extraDomains.size() >= 4,
                "the extra domain types must really be used, saw only " + extraDomains);
        // a key has to stay indexable and comparable on every engine, so no key may take an extra domain
        for (DataType.Category category : keyDomains.keySet()) {
            assertTrue(Set.of(DataType.Category.SMALLINT, DataType.Category.INTEGER, DataType.Category.BIGINT,
                    DataType.Category.DECIMAL, DataType.Category.REAL, DataType.Category.DOUBLE,
                    DataType.Category.CHAR, DataType.Category.VARCHAR, DataType.Category.BOOLEAN,
                    DataType.Category.DATE, DataType.Category.TIMESTAMP).contains(category),
                    "key attributes must not use the domain " + category);
        }
    }

    @Test
    void theInheritancePlanIsWhatTheMapperCanRecognise() {
        int links = 0;
        int deep = 0;
        int alternateKeys = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Randomly.setSeed(seed);
            GraphSchema schema = new RandomGraphSchemaGenerator().generate();
            GraphFilling filling = plan(schema);
            for (int label = 0; label < schema.getLabelCount(); label++) {
                List<Integer> parents = filling.parentsOf(label);
                assertTrue(parents.size() <= 2, "a label may inherit from at most two parents");
                for (int parent : parents) {
                    links++;
                    assertTrue(isStrictSuperset(filling, label, parent),
                            "an inheritance link must make the child a strict superset of the parent");
                }
                int depth = depth(filling, label, 0);
                assertTrue(depth <= 3, "the mapper only recognises three ISA levels, saw " + depth);
                if (depth > 1) {
                    deep++;
                }
                if (!filling.alternateKeyOf(label).isEmpty()) {
                    alternateKeys++;
                }
            }
        }
        assertTrue(links > 0, "the filling never planned an inheritance link");
        assertTrue(deep > 0, "multi-level hierarchies must be reachable through the filling");
        assertTrue(alternateKeys > 0, "the filling never declared an alternate key");
    }

    /**
     * The end-to-end promise of an alternate key: what the filling declares as a candidate key has to
     * appear in the relational schema as a {@code UNIQUE} constraint next to the primary key. Mapping
     * an alternate key to the primary key instead would be a strictly stronger claim.
     */
    @Test
    void aDeclaredAlternateKeyBecomesAUniqueConstraint() {
        int declared = 0;
        int emitted = 0;
        for (int seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            GraphSchema schema = new RandomGraphSchemaGenerator().generate();
            GraphModel graph = GraphGenerators.random().generate(schema, new GraphGenerationOptions());
            GraphFilling filling = plan(schema);
            GraphPropertyFiller.fill(graph, filling);
            ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema, filling);
            MappingTrace trace = new MappingTrace();
            MappingOptions options = MappingOptions.random();
            RelationalSchema mapped = new StandardERToRelationalMapper().map(diagram, options, "s", trace);
            for (EREntity entity : diagram.getEntities()) {
                List<ERAttribute> alternate = entity.getAlternateKeyAttributes();
                if (alternate.isEmpty()) {
                    continue;
                }
                declared++;
                String tableName = trace.getEntityToTable().get(entity.getName());
                if (tableName == null) {
                    continue;
                }
                Table table = mapped.getTable(tableName);
                assertNotNull(table, "the entity's table must exist");
                List<String> columns = new ArrayList<>();
                Map<String, String> attributeColumns = trace.getAttributeToColumn().get(entity.getName());
                if (attributeColumns == null) {
                    continue;
                }
                for (ERAttribute attribute : alternate) {
                    String column = attributeColumns.get(attribute.getName());
                    if (column != null) {
                        columns.add(column);
                    }
                }
                if (columns.isEmpty()) {
                    continue;
                }
                assertTrue(enforcesUnique(table, columns),
                        "seed " + seed + ": " + entity.getName() + " declares the alternate key " + columns
                                + " but " + table.getName() + " does not enforce it: uniques="
                                + table.getUniqueConstraints());
                emitted++;
            }
        }
        assertTrue(declared > 0, "no alternate key was declared in 40 databases");
        assertTrue(emitted > 0, "no alternate key reached a table in 40 databases");
    }

    /** @return true when the table enforces uniqueness on exactly these columns */
    private static boolean enforcesUnique(Table table, List<String> columns) {
        if (table.getPrimaryKey() != null && sameColumns(table.getPrimaryKey().getColumns(), columns)) {
            return true;
        }
        for (org.ddlsmith.common.schema.UniqueConstraint unique : table.getUniqueConstraints()) {
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

    private static boolean isStrictSuperset(GraphFilling filling, int subtype, int supertype) {
        Set<String> subKeys = new LinkedHashSet<>();
        for (GraphProperty slot : filling.attributesOfLabel(subtype)) {
            subKeys.add(slot.getKey());
        }
        Set<String> superKeys = new LinkedHashSet<>();
        for (GraphProperty slot : filling.attributesOfLabel(supertype)) {
            superKeys.add(slot.getKey());
        }
        // a lean profile can leave the supertype without an attribute of its own (it then exists as a
        // key-only table); what matters is that the child has strictly more, which is what the mapper's
        // superset test asks for
        return subKeys.size() > superKeys.size() && subKeys.containsAll(superKeys);
    }

    private static int depth(GraphFilling filling, int label, int guard) {
        if (guard > 4) {
            return guard;
        }
        int deepest = 0;
        for (int parent : filling.parentsOf(label)) {
            deepest = Math.max(deepest, depth(filling, parent, guard + 1));
        }
        return deepest + 1;
    }
}
