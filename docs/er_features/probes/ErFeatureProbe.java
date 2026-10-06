import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.ddlsmith.Randomly;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;

/**
 * Empirical ER-feature coverage probe: which ER/EER features does the graph -> ER pipeline actually
 * reach, and which never appear? Not part of the build; run manually.
 */
public final class ErFeatureProbe {

    static int seeds = 200;
    static int diagrams;
    static final Map<String, Integer> diagramsWith = new TreeMap<>();
    static final Map<String, Integer> occurrences = new TreeMap<>();
    static final Map<String, Integer> maxima = new TreeMap<>();
    static final Map<String, String> examples = new TreeMap<>();

    public static void main(String[] args) {
        seeds = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        DefaultGraphToERMapper mapper = new DefaultGraphToERMapper();
        GraphGenerationOptions options = new GraphGenerationOptions();
        for (String name : GraphGenerators.names()) {
            for (int seed = 0; seed < seeds; seed++) {
                Randomly.setSeed(seed);
                GraphSchema schema = new RandomGraphSchemaGenerator().generate();
                GraphModel graph = GraphGenerators.forName(name).generate(schema, options);
                ERDiagram diagram = mapper.map(graph, schema);
                diagrams++;
                inspect(diagram, name + "/" + seed);
            }
        }
        report();
    }

    /* ------------------------------------------------------------------ counting helpers */

    static void seen(String feature, boolean present, String example) {
        if (!present) {
            return;
        }
        diagramsWith.merge(feature, 1, Integer::sum);
        occurrences.merge(feature, 1, Integer::sum);
        if (example != null) {
            examples.putIfAbsent(feature, example);
        }
    }

    static void count(String feature, int amount, String example) {
        if (amount <= 0) {
            return;
        }
        diagramsWith.merge(feature, 1, Integer::sum);
        occurrences.merge(feature, amount, Integer::sum);
        maxima.merge(feature, amount, Math::max);
        if (example != null) {
            examples.putIfAbsent(feature, example);
        }
    }

    /* ------------------------------------------------------------------ inspection */

    static void inspect(ERDiagram diagram, String where) {
        // ---- entities
        for (EREntity entity : diagram.getEntities()) {
            if (entity.isWeak()) {
                seen("weak entity", true, where + " " + entity.getName());
                count("weak entity partial keys", entity.getPartialKeyAttributes().size(), null);
                // how many relationships does a weak entity take part in?
                int degrees = diagram.getRelationshipsOf(entity.getName()).size();
                count("weak entity participating in >1 relationship", degrees > 1 ? degrees : 0,
                        where + " " + entity.getName() + " in " + degrees);
            }
            if (entity.isGeneralizationSupertype()) {
                seen("generalization supertype", true, where + " " + entity.getName());
                seen("disjoint specialization", entity.isDisjoint(), null);
                seen("overlapping specialization", !entity.isDisjoint(), null);
                seen("total specialization", entity.isTotalSpecialization(), null);
                seen("partial specialization", !entity.isTotalSpecialization(), null);
                seen("discriminator attribute", entity.getDiscriminatorAttribute() != null, null);
                seen("supertype with >1 subtype", entity.getSubEntities().size() > 1, null);
                count("subtypes per supertype", entity.getSubEntities().size(), null);
            }
            if (entity.isGeneralizationSubtype()) {
                seen("generalization subtype", true, where + " " + entity.getName());
                seen("shared subclass (multiple inheritance)", entity.isSharedSubclass(), where + " " + entity.getName());
                count("supertypes per subtype", entity.getSuperEntities().size(), null);
                // does the subtype add attributes of its own beyond the supertype's?
                for (String superName : entity.getSuperEntities()) {
                    EREntity superEntity = diagram.getEntity(superName);
                    if (superEntity == null) {
                        continue;
                    }
                    Set<String> subNames = attributeNames(entity);
                    Set<String> superNames = attributeNames(superEntity);
                    seen("subtype adding no new attribute", subNames.equals(superNames), where + " " + entity.getName());
                    seen("subtype adding attributes", !subNames.equals(superNames), null);
                }
            }
            if (entity.isCategory()) {
                seen("category (union type)", true, where + " " + entity.getName());
                count("category superclasses", entity.getCategorySupers().size(), null);
                seen("category with >2 superclasses", entity.getCategorySupers().size() > 2, null);
            }
            if (entity.isAggregation()) {
                seen("aggregation entity", true, where + " " + entity.getName());
            }
            if (entity.getPrimaryKeyAttributes().size() > 1) {
                seen("composite natural key", true, where + " " + entity.getName());
            }
            if (entity.getPrimaryKeyAttributes().isEmpty() && !entity.isAggregation()) {
                seen("entity without any key", true, where + " " + entity.getName());
            }
            for (ERAttribute attribute : entity.getAttributes()) {
                inspectAttribute("entity attribute", attribute, where + " " + entity.getName(), null);
            }
        }
        // ---- ISA depth
        int depth = 0;
        for (EREntity entity : diagram.getEntities()) {
            depth = Math.max(depth, isaDepth(diagram, entity));
        }
        count("ISA hierarchy depth", depth, null);
        seen("ISA hierarchy deeper than 2", depth > 2, null);

        // ---- relationships
        for (ERRelationship relationship : diagram.getRelationships()) {
            int degree = relationship.getDegree();
            count("relationship degree", degree, null);
            seen("n-ary relationship (degree>=3)", degree >= 3, where + " " + relationship.getName());
            seen("4-ary or higher relationship", degree >= 4, where + " " + relationship.getName());
            seen("unary relationship", degree == 1, null);
            seen("binary relationship", degree == 2, null);
            if (relationship.isRecursive() && !relationship.isGeneralization()) {
                seen("recursive relationship", true, where + " " + relationship.getName());
            }
            if (relationship.isIdentifying()) {
                seen("identifying relationship", true, where + " " + relationship.getName());
            }
            if (relationship.isGeneralization()) {
                seen("ISA link as relationship", true, null);
            }
            if (relationship.isCategoryLink()) {
                seen("category link", true, null);
            }
            if (relationship.isAggregationLink()) {
                seen("aggregation link", true, null);
            }
            if (degree == 2 && !relationship.isRecursive()) {
                seen("binary " + relationship.getBinaryCardinality(), true, null);
            }
            // role names on a non-recursive relationship
            if (degree >= 2 && !relationship.isRecursive()) {
                boolean anyRole = false;
                for (ERParticipant participant : relationship.getParticipants()) {
                    if (!participant.getRoleName().equals(participant.getEntityName())) {
                        anyRole = true;
                    }
                }
                seen("explicit role name on a non-recursive relationship", anyRole,
                        where + " " + relationship.getName());
            }
            // the same pair of entities connected by several relationship types
            if (degree == 2 && relationship.getParticipants().get(0).getEntityName()
                    .equals(relationship.getParticipants().get(1).getEntityName())) {
                seen("self-referencing relationship", true, null);
            }
            for (ERParticipant participant : relationship.getParticipants()) {
                if (participant.getParticipation() == Participation.TOTAL) {
                    seen("total participation", true, null);
                }
                if (participant.getCardinality() == Cardinality.ONE) {
                    seen("cardinality ONE participant", true, null);
                }
            }
            for (ERAttribute attribute : relationship.getAttributes()) {
                inspectAttribute("relationship attribute", attribute, where + " " + relationship.getName(),
                        relationship);
            }
            if (relationship.getName().startsWith("Ragg_")) {
                for (ERParticipant participant : relationship.getParticipants()) {
                    if (participant.getEntityName().startsWith("Agg_")) {
                        count("aggregation outer cardinality on aggregate side",
                                participant.getCardinality() == Cardinality.ONE ? 1 : 2, null);
                        seen("aggregation outer relationship 1:1", participant.getCardinality() == Cardinality.ONE,
                                where + " " + relationship.getName());
                    }
                }
            }
        }

        // ---- parallel relationship types between the same pair of entities
        Map<String, Integer> pairCounts = new LinkedHashMap<>();
        for (ERRelationship relationship : diagram.getRelationships()) {
            if (relationship.getDegree() != 2 || relationship.isStructuralLink()) {
                continue;
            }
            String a = relationship.getParticipants().get(0).getEntityName();
            String b = relationship.getParticipants().get(1).getEntityName();
            String key = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
            pairCounts.merge(key, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : pairCounts.entrySet()) {
            if (entry.getValue() > 1) {
                count("several relationship types between one entity pair", entry.getValue(),
                        where + " " + entry.getKey());
                break;
            }
        }
    }

    static void inspectAttribute(String kind, ERAttribute attribute, String owner, ERRelationship relationship) {
        if (attribute.isMultivalued()) {
            seen(kind + " multi-valued", true, owner + "." + attribute.getName());
            if (relationship != null) {
                seen("multi-valued attribute of a relationship", true, owner + "." + attribute.getName());
            }
        }
        if (attribute.isComposite()) {
            seen(kind + " composite", true, owner + "." + attribute.getName());
            count(kind + " composite components", attribute.getComponents().size(), null);
            int nesting = nestingDepth(attribute);
            count(kind + " composite nesting depth", nesting, null);
            seen(kind + " composite with >=3 components", attribute.getComponents().size() >= 3, null);
            seen(kind + " nested composite (depth>=3)", nesting >= 3, owner + "." + attribute.getName());
            if (relationship != null) {
                seen("composite attribute of a relationship", true, owner + "." + attribute.getName());
            }
            if (attribute.isPrimaryKey() || attribute.isPartialKey()) {
                seen("composite attribute used as a key", true, owner + "." + attribute.getName());
            }
            if (attribute.isMultivalued()) {
                seen(kind + " multi-valued composite", true, owner + "." + attribute.getName());
            }
        }
        if (attribute.isDerived()) {
            seen(kind + " derived", true, owner + "." + attribute.getName());
            if (relationship != null) {
                seen("derived attribute of a relationship", true, owner + "." + attribute.getName());
            }
            seen(kind + " derived with expression", attribute.getDerivedExpression() != null, null);
        }
        if (attribute.isOptional()) {
            seen(kind + " optional", true, null);
        }
        if (attribute.isPartialKey()) {
            seen(kind + " partial key", true, owner + "." + attribute.getName());
        }
    }

    static int nestingDepth(ERAttribute attribute) {
        if (!attribute.isComposite() || attribute.getComponents().isEmpty()) {
            return 1;
        }
        int deepest = 0;
        for (ERAttribute component : attribute.getComponents()) {
            deepest = Math.max(deepest, nestingDepth(component));
        }
        return deepest + 1;
    }

    static int isaDepth(ERDiagram diagram, EREntity entity) {
        int deepest = 0;
        for (String superName : entity.getSuperEntities()) {
            EREntity superEntity = diagram.getEntity(superName);
            if (superEntity != null) {
                deepest = Math.max(deepest, isaDepth(diagram, superEntity));
            }
        }
        return deepest + (entity.isGeneralizationSubtype() ? 1 : 0);
    }

    static Set<String> attributeNames(EREntity entity) {
        Set<String> names = new LinkedHashSet<>();
        for (ERAttribute attribute : entity.getAttributes()) {
            names.add(attribute.getName());
        }
        return names;
    }

    /* ------------------------------------------------------------------ report */

    static void report() {
        System.out.println("diagrams: " + diagrams);
        System.out.println();
        System.out.printf("%-58s %9s %9s %7s%n", "feature", "diagrams", "total", "max");
        List<String> keys = new ArrayList<>(diagramsWith.keySet());
        for (String key : keys) {
            System.out.printf("%-58s %9d %9d %7d%n", key, diagramsWith.get(key), occurrences.get(key),
                    maxima.getOrDefault(key, 0));
        }
        System.out.println();
        System.out.println("=== features with ZERO occurrences ===");
        String[] expected = {
                "weak entity", "weak entity participating in >1 relationship", "unary relationship",
                "4-ary or higher relationship", "multi-valued attribute of a relationship",
                "composite attribute of a relationship", "derived attribute of a relationship",
                "composite attribute used as a key", "category (union type)",
                "category with >2 superclasses", "shared subclass (multiple inheritance)",
                "subtype adding no new attribute", "explicit role name on a non-recursive relationship",
                "aggregation outer relationship 1:1", "several relationship types between one entity pair",
                "entity attribute composite with >=3 components",
                "entity attribute nested composite (depth>=3)", "ISA hierarchy deeper than 2",
                "supertype with >1 subtype", "entity without any key", "aggregation entity",
                "relationship degree", "n-ary relationship (degree>=3)",
        };
        for (String key : expected) {
            if (!diagramsWith.containsKey(key)) {
                System.out.println("  NEVER: " + key);
            }
        }
        System.out.println();
        System.out.println("=== examples ===");
        for (Map.Entry<String, String> entry : examples.entrySet()) {
            System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
        }
    }
}
