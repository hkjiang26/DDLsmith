import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;

/**
 * Verifies two suspected mapper defects on the REAL pipeline:
 *  1. attribute inheritance: does a subtype carry attributes with the same names as its supertype,
 *     and does the single-table / table-per-class mapping then emit duplicated columns?
 *  2. MERGE_TABLES for 1:1: are the relationship's attributes lost?
 */
public final class ErInheritanceProbe {

    static int diagrams;
    static int subtypes;
    static int subtypesSharingNames;
    static int singleTableDuplicates;
    static int singleTableSiblingCollisions;
    static int singleTableChecked;
    static int perClassDuplicates;
    static int perClassChecked;
    static final Map<String, String> examples = new LinkedHashMap<>();    static int mergedOneToOne;
    static int mergedOneToOneAttributes;
    static int mergedOneToOneAttributesLost;

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        DefaultGraphToERMapper graphMapper = new DefaultGraphToERMapper();
        GraphGenerationOptions graphOptions = new GraphGenerationOptions();
        for (String name : GraphGenerators.names()) {
            for (int seed = 0; seed < seeds; seed++) {
                Randomly.setSeed(seed);
                GraphSchema schema = new RandomGraphSchemaGenerator().generate();
                GraphModel graph = GraphGenerators.forName(name).generate(schema, graphOptions);
                ERDiagram diagram = graphMapper.map(graph, schema);
                diagrams++;
                inspectNames(diagram, name + "/" + seed);
                inspectSingleTable(diagram, name + "/" + seed);
                inspectPerClass(diagram, name + "/" + seed);
                inspectMergeTables(diagram);
            }
        }
        System.out.println("diagrams                          : " + diagrams);
        System.out.println("subtypes                          : " + subtypes);
        System.out.println("  sharing attribute names with a supertype : " + subtypesSharingNames);
        System.out.println("single-table hierarchies checked  : " + singleTableChecked
                + ", with duplicated inherited columns: " + singleTableDuplicates
                + ", with sibling name collisions (legitimate): " + singleTableSiblingCollisions);
        System.out.println("table-per-class hierarchies checked: " + perClassChecked
                + ", with duplicated columns: " + perClassDuplicates);
        System.out.println("1:1 MERGE_TABLES relationships    : " + mergedOneToOne
                + ", attributes: " + mergedOneToOneAttributes
                + ", lost: " + mergedOneToOneAttributesLost);
        System.out.println("\nexamples:");
        for (Map.Entry<String, String> entry : examples.entrySet()) {
            System.out.println("  " + entry.getKey() + "\n      " + entry.getValue());
        }
    }

    static void note(String key, String value) {
        examples.putIfAbsent(key, value);
    }

    /** Does a subtype carry attributes named exactly like its supertype's? */
    static void inspectNames(ERDiagram diagram, String where) {
        for (EREntity entity : diagram.getEntities()) {
            if (!entity.isGeneralizationSubtype()) {
                continue;
            }
            subtypes++;
            for (String superName : entity.getSuperEntities()) {
                EREntity superEntity = diagram.getEntity(superName);
                if (superEntity == null) {
                    continue;
                }
                Set<String> superNames = new LinkedHashSet<>();
                for (ERAttribute attribute : superEntity.getAttributes()) {
                    superNames.add(attribute.getName());
                }
                Set<String> shared = new LinkedHashSet<>();
                for (ERAttribute attribute : entity.getAttributes()) {
                    if (superNames.contains(attribute.getName())) {
                        shared.add(attribute.getName());
                    }
                }
                if (!shared.isEmpty()) {
                    subtypesSharingNames++;
                    note("subtype sharing attribute names with its supertype",
                            where + " " + entity.getName() + " ISA " + superName + " shares " + shared
                                    + "; own=" + names(entity) + " super=" + names(superEntity));
                }
            }
        }
    }

    static Set<String> names(EREntity entity) {
        Set<String> names = new LinkedHashSet<>();
        for (ERAttribute attribute : entity.getAttributes()) {
            names.add(attribute.getName());
        }
        return names;
    }

    /**
     * @return the ER attribute names that this table stores on behalf of two entities that are
     *         related by ISA - the signature of attribute inheritance materialized as duplication.
     *
     * <p>The check goes through the mapping trace rather than through the columns, so that the
     * legitimate cases do not produce false positives: a composite attribute legitimately owns
     * several columns, and every column of a foreign key carries the relationship's name in
     * {@code Column.getDerivedFrom()}.</p>
     *
     * <p>Two <em>sibling</em> subtypes that each declare an attribute of the same name also share a
     * column name, and there they legitimately need two columns - the ER diagram declares two
     * distinct attributes. Those are counted separately by
     * {@link #siblingCollisions(MapResult, Table, ERDiagram)} and are not defects.</p>
     */
    static Set<String> duplicatedAttributes(MapResult result, Table table, ERDiagram diagram) {
        Set<String> duplicated = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : contributorsByAttribute(result, table).entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            if (anyIsaRelated(diagram, entry.getValue())) {
                duplicated.add(entry.getKey() + " contributed by " + entry.getValue());
            }
        }
        return duplicated;
    }

    /** @return the same-name contributions that come from entities that are <em>not</em> ISA related */
    static Set<String> siblingCollisions(MapResult result, Table table, ERDiagram diagram) {
        Set<String> collisions = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : contributorsByAttribute(result, table).entrySet()) {
            if (entry.getValue().size() >= 2 && !anyIsaRelated(diagram, entry.getValue())) {
                collisions.add(entry.getKey() + " contributed by " + entry.getValue());
            }
        }
        return collisions;
    }

    static Map<String, Set<String>> contributorsByAttribute(MapResult result, Table table) {
        Map<String, Set<String>> entitiesByAttribute = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : result.trace.getAttributeToColumn().entrySet()) {
            String entity = entry.getKey();
            if (!table.getName().equals(result.trace.getEntityToTable().get(entity))) {
                continue;
            }
            for (String attribute : entry.getValue().keySet()) {
                entitiesByAttribute.computeIfAbsent(attribute, key -> new LinkedHashSet<>()).add(entity);
            }
        }
        return entitiesByAttribute;
    }

    /** @return true when some entity of {@code names} is an ISA ancestor of another one */
    static boolean anyIsaRelated(ERDiagram diagram, Set<String> names) {
        for (String first : names) {
            for (String second : names) {
                if (!first.equals(second) && isAncestor(diagram, first, second)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isAncestor(ERDiagram diagram, String ancestor, String descendant) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(descendant);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            EREntity entity = diagram.getEntity(current);
            if (entity == null) {
                continue;
            }
            for (String superName : entity.getSuperEntities()) {
                if (superName.equals(ancestor)) {
                    return true;
                }
                queue.add(superName);
            }
        }
        return false;
    }

    static void inspectSingleTable(ERDiagram diagram, String where) {
        MappingOptions options = new MappingOptions().setIsa(MappingOptions.IsAStrategy.SINGLE_TABLE);
        MapResult result = map(diagram, options);
        if (result == null) {
            return;
        }
        for (Table table : result.schema.getTables()) {
            if (!"SINGLE_TABLE".equals(table.getMappingStrategy())) {
                continue;
            }
            singleTableChecked++;
            Set<String> suffixed = duplicatedAttributes(result, table, diagram);
            if (!suffixed.isEmpty()) {
                singleTableDuplicates++;
                note("single-table ISA emits duplicated inherited columns",
                        where + " " + table.getName() + " columns=" + table.getColumnNames()
                                + " duplicated attributes=" + suffixed);
            }
            Set<String> collisions = siblingCollisions(result, table, diagram);
            if (!collisions.isEmpty()) {
                singleTableSiblingCollisions++;
                note("single-table ISA: sibling subtypes declare same-named attributes (legitimate)",
                        where + " " + table.getName() + " collisions=" + collisions);
            }
        }
    }

    static void inspectPerClass(ERDiagram diagram, String where) {
        MappingOptions options = new MappingOptions().setIsa(MappingOptions.IsAStrategy.TABLE_PER_CLASS);
        MapResult result = map(diagram, options);
        if (result == null) {
            return;
        }
        for (Table table : result.schema.getTables()) {
            if (!"TABLE_PER_CLASS".equals(table.getMappingStrategy())) {
                continue;
            }
            perClassChecked++;
            Set<String> suffixed = duplicatedAttributes(result, table, diagram);
            if (!suffixed.isEmpty()) {
                perClassDuplicates++;
                note("table-per-class ISA emits duplicated inherited columns",
                        where + " " + table.getName() + " columns=" + table.getColumnNames()
                                + " duplicated attributes=" + suffixed);
            }
        }
    }

    static void inspectMergeTables(ERDiagram diagram) {
        MappingOptions options = new MappingOptions().setOneToOne(MappingOptions.OneToOneStrategy.MERGE_TABLES);
        MapResult result = map(diagram, options);
        if (result == null) {
            return;
        }
        for (ERRelationship relationship : diagram.getRelationships()) {
            if (!"MERGE_TABLES".equals(result.trace.getRelationshipStrategy().get(relationship.getName()))) {
                continue;
            }
            mergedOneToOne++;
            Map<String, String> mapped = result.trace.getRelationshipAttributeToColumn().get(relationship.getName());
            for (ERAttribute attribute : relationship.getAttributes()) {
                mergedOneToOneAttributes++;
                if (mapped == null || !mapped.containsKey(attribute.getName())) {
                    mergedOneToOneAttributesLost++;
                    note("1:1 MERGE_TABLES loses the relationship attribute",
                            relationship.getName() + " attribute " + attribute.getName() + " lost");
                }
            }
        }
    }

    static final class MapResult {
        RelationalSchema schema;
        MappingTrace trace;
    }

    static MapResult map(ERDiagram diagram, MappingOptions options) {
        try {
            Randomly.setSeed(11);
            MapResult result = new MapResult();
            result.trace = new MappingTrace();
            result.schema = new StandardERToRelationalMapper().map(diagram, options, "s", result.trace);
            return result;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
