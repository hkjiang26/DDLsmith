import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.ddlsmith.Randomly;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphLabel;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;

/**
 * Focused reachability probe for the ER features the first probe showed to be absent or suspicious:
 * ISA depth, weak-entity chains, n-ary degree, role names, category arity, aggregation arity.
 * Also reports WHY a feature is unreachable, by inspecting the graph schema that fed the mapper.
 */
public final class ErReachabilityProbe {

    static final Map<String, Integer> hits = new TreeMap<>();
    static final Map<String, String> evidence = new TreeMap<>();
    static final Map<Integer, Integer> isaDepthHistogram = new TreeMap<>();
    static final Map<Integer, Integer> degreeHistogram = new TreeMap<>();
    static int diagrams;

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        DefaultGraphToERMapper mapper = new DefaultGraphToERMapper();
        GraphGenerationOptions options = new GraphGenerationOptions();
        for (String name : GraphGenerators.names()) {
            for (int seed = 0; seed < seeds; seed++) {
                Randomly.setSeed(seed);
                GraphSchema schema = new RandomGraphSchemaGenerator().generate();
                GraphModel graph = GraphGenerators.forName(name).generate(schema, options);
                ERDiagram diagram = mapper.map(graph, schema);
                diagrams++;
                inspect(diagram, schema, name + "/" + seed);
            }
        }
        System.out.println("diagrams: " + diagrams + "\n");
        System.out.println("ISA depth histogram: " + isaDepthHistogram);
        System.out.println("relationship degree histogram: " + degreeHistogram + "\n");
        System.out.printf("%-62s %8s%n", "feature", "diagrams");
        for (Map.Entry<String, Integer> entry : hits.entrySet()) {
            System.out.printf("%-62s %8d%n", entry.getKey(), entry.getValue());
        }
        System.out.println("\n=== evidence ===");
        for (Map.Entry<String, String> entry : evidence.entrySet()) {
            System.out.println("  " + entry.getKey() + "\n      " + entry.getValue());
        }
    }

    static void hit(String feature, String example) {
        hits.merge(feature, 1, Integer::sum);
        if (example != null) {
            evidence.putIfAbsent(feature, example);
        }
    }

    static void inspect(ERDiagram diagram, GraphSchema schema, String where) {
        // ---- ISA depth, and the "supertype that is also a subtype" chain
        int maxDepth = 0;
        for (EREntity entity : diagram.getEntities()) {
            int depth = isaDepth(diagram, entity);
            maxDepth = Math.max(maxDepth, depth);
            if (entity.isGeneralizationSubtype() && entity.isGeneralizationSupertype()) {
                hit("entity that is both a subtype and a supertype (chain)", where + " " + entity.getName());
            }
        }
        isaDepthHistogram.merge(maxDepth, 1, Integer::sum);
        if (maxDepth >= 2) {
            hit("ISA hierarchy of depth >= 2", where + " depth=" + maxDepth);
        }
        if (maxDepth >= 3) {
            hit("ISA hierarchy of depth >= 3", where + " depth=" + maxDepth);
        }

        // ---- weak entities
        for (EREntity entity : diagram.getEntities()) {
            if (!entity.isWeak()) {
                continue;
            }
            EREntity owner = diagram.getEntity(entity.getOwnerEntity());
            if (owner != null && owner.isWeak()) {
                hit("weak entity whose owner is weak (ID-dependency chain)",
                        where + " " + entity.getName() + " -> " + owner.getName());
            }
            hit("weak entity with exactly one incident relationship",
                    diagram.getRelationshipsOf(entity.getName()).size() == 1 ? null : "BUG");
            // a weak entity that also carries a multi-valued attribute or is a subtype/category
            if (entity.isGeneralizationSubtype() || entity.isGeneralizationSupertype() || entity.isCategory()) {
                hit("weak entity that is also an ISA/category participant", where + " " + entity.getName());
            }
            for (ERAttribute attribute : entity.getAttributes()) {
                if (attribute.isMultivalued()) {
                    hit("weak entity with a multi-valued attribute", where + " " + entity.getName());
                }
                if (attribute.isComposite()) {
                    hit("weak entity with a composite attribute", where + " " + entity.getName());
                }
            }
        }

        // ---- relationships
        for (ERRelationship relationship : diagram.getRelationships()) {
            degreeHistogram.merge(relationship.getDegree(), 1, Integer::sum);
            if (relationship.getDegree() > 3) {
                hit("relationship of degree > 3", where + " " + relationship.getName());
            }
            if (relationship.getDegree() == 3) {
                int ones = 0;
                for (ERParticipant participant : relationship.getParticipants()) {
                    if (participant.getCardinality() == Cardinality.ONE) {
                        ones++;
                    }
                }
                if (ones >= 2) {
                    hit("n-ary relationship with two or more cardinality-ONE participants",
                            where + " " + relationship.getName());
                }
                if (ones == 3) {
                    hit("n-ary relationship with all participants cardinality ONE",
                            where + " " + relationship.getName());
                }
            }
            if (relationship.isAggregationLink()) {
                hit("aggregation link", null);
            }
            if (relationship.getName().startsWith("Ragg_")) {
                hit("aggregation outer relationship", null);
                for (ERParticipant participant : relationship.getParticipants()) {
                    if (participant.getEntityName().startsWith("Agg_")) {
                        hit("aggregation outer relationship, aggregate side cardinality "
                                + participant.getCardinality(), null);
                        hit("aggregation outer relationship, aggregate side participation "
                                + participant.getParticipation(), null);
                    }
                }
            }
            if (relationship.isIdentifying()) {
                hit("identifying relationship", null);
                hit("identifying relationship of degree " + relationship.getDegree(), null);
                for (ERAttribute attribute : relationship.getAttributes()) {
                    if (attribute.isPrimaryKey() || attribute.isPartialKey()) {
                        hit("identifying relationship with key attributes", where);
                    }
                }
            }
            // role names
            for (ERParticipant participant : relationship.getParticipants()) {
                if (!participant.getRoleName().equals(participant.getEntityName())) {
                    hit("relationship with an explicit role name", null);
                    if (!relationship.isRecursive()) {
                        hit("explicit role name on a NON-recursive relationship", where + " " + relationship.getName());
                    }
                }
            }
            if (relationship.isGeneralization() && relationship.getParticipants().size() != 2) {
                hit("generalization link that is not binary", where);
            }
            if (relationship.isGeneralization()) {
                for (ERParticipant participant : relationship.getParticipants()) {
                    if (participant.getParticipation() == org.ddlsmith.ermodel.Participation.TOTAL) {
                        hit("generalization link with a TOTAL participant", null);
                    }
                }
            }
            // relationship attributes of a structural link
            if (relationship.isStructuralLink() && !relationship.getAttributes().isEmpty()) {
                hit("structural (ISA/category/aggregation) link carrying attributes", where);
            }
            if (relationship.isCategoryLink() && relationship.getParticipants().size() != 2) {
                hit("category link that is not binary", where);
            }
            if (relationship.getMergedTypeNames().size() > 0 && relationship.getDegree() != 3) {
                hit("merged (ternary/aggregation) relationship whose degree is not 3", where);
            }
        }

        // ---- categories
        for (EREntity entity : diagram.getEntities()) {
            if (!entity.isCategory()) {
                continue;
            }
            hit("category with " + entity.getCategorySupers().size() + " superclasses", null);
            if (entity.getCategorySupers().size() > 2) {
                hit("category with more than two superclasses", where + " " + entity.getName());
            }
            if (entity.isGeneralizationSubtype() || entity.isGeneralizationSupertype()) {
                hit("category that is also an ISA participant", where + " " + entity.getName());
            }
            for (String superName : entity.getCategorySupers()) {
                EREntity superEntity = diagram.getEntity(superName);
                if (superEntity != null && superEntity.isCategory()) {
                    hit("category whose superclass is itself a category", where + " " + entity.getName());
                }
            }
        }

        // ---- entities with several relationship types to the same partner
        for (EREntity entity : diagram.getEntities()) {
            Map<String, Integer> partners = new LinkedHashMap<>();
            for (ERRelationship relationship : diagram.getRelationshipsOf(entity.getName())) {
                if (relationship.isStructuralLink() || relationship.getDegree() != 2) {
                    continue;
                }
                for (ERParticipant participant : relationship.getParticipants()) {
                    if (!participant.getEntityName().equals(entity.getName())) {
                        partners.merge(participant.getEntityName(), 1, Integer::sum);
                    }
                }
            }
            for (Map.Entry<String, Integer> entry : partners.entrySet()) {
                if (entry.getValue() > 1) {
                    hit("several relationship types between one entity pair", where + " " + entity.getName() + "-"
                            + entry.getKey() + " x" + entry.getValue());
                    break;
                }
            }
        }

        // ---- schema-level reason hunting: does the schema ever allow an ISA chain?
        boolean anyParentIsChild = false;
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            if (!(type.getFromCardinality() == Cardinality.ONE && type.getToCardinality() == Cardinality.ONE
                    && type.getFromParticipation() == org.ddlsmith.ermodel.Participation.TOTAL)) {
                continue;
            }
            GraphLabel from = schema.getLabel(type.getFromLabelIndex());
            GraphLabel to = schema.getLabel(type.getToLabelIndex());
            if (isStrictSuperset(from, to) && isStrictSuperset(to, schema.getLabel(0))) {
                anyParentIsChild = true;
            }
        }
        if (anyParentIsChild) {
            hit("schema where a would-be supertype is itself a superset of another label", where);
        }
    }

    static boolean isStrictSuperset(GraphLabel sub, GraphLabel sup) {
        List<String> subKeys = new ArrayList<>();
        for (var slot : sub.getProperties()) {
            subKeys.add(slot.getKey());
        }
        List<String> supKeys = new ArrayList<>();
        for (var slot : sup.getProperties()) {
            supKeys.add(slot.getKey());
        }
        return subKeys.size() > supKeys.size() && subKeys.containsAll(supKeys);
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
}
