import java.util.LinkedHashMap;
import java.util.Map;

import org.ddlsmith.Randomly;
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
 * Counts ER attributes that never reach the relational schema, split by the kind of construct that
 * owns them. Anything that is dropped silently (no fallback note) is a fidelity gap.
 */
public final class ErAttributeLossProbe {

    static final Map<String, Integer> total = new LinkedHashMap<>();
    static final Map<String, Integer> mapped = new LinkedHashMap<>();
    static int diagrams;

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        DefaultGraphToERMapper graphMapper = new DefaultGraphToERMapper();
        GraphGenerationOptions graphOptions = new GraphGenerationOptions();
        MappingOptions mappingOptions = new MappingOptions();
        for (String name : GraphGenerators.names()) {
            for (int seed = 0; seed < seeds; seed++) {
                Randomly.setSeed(seed);
                GraphSchema schema = new RandomGraphSchemaGenerator().generate();
                GraphModel graph = GraphGenerators.forName(name).generate(schema, graphOptions);
                ERDiagram diagram = graphMapper.map(graph, schema);
                diagrams++;
                MappingTrace trace = new MappingTrace();
                new StandardERToRelationalMapper().map(diagram, mappingOptions, "s", trace);
                inspect(diagram, trace);
            }
        }
        System.out.println("diagrams: " + diagrams + "\n");
        System.out.printf("%-52s %8s %8s %8s%n", "attribute owner", "total", "mapped", "lost");
        for (String key : total.keySet()) {
            int all = total.get(key);
            int ok = mapped.getOrDefault(key, 0);
            System.out.printf("%-52s %8d %8d %8d%n", key, all, ok, all - ok);
        }
    }

    static void count(String key, boolean wasMapped) {
        total.merge(key, 1, Integer::sum);
        if (wasMapped) {
            mapped.merge(key, 1, Integer::sum);
        }
    }

    static void inspect(ERDiagram diagram, MappingTrace trace) {
        for (EREntity entity : diagram.getEntities()) {
            Map<String, String> columns = trace.getAttributeToColumn().get(entity.getName());
            for (ERAttribute attribute : entity.getAttributes()) {
                boolean ok = columns != null && columns.containsKey(attribute.getName());
                String kind = "entity attribute";
                if (attribute.isMultivalued()) {
                    kind = "entity attribute: multi-valued";
                } else if (attribute.isDerived()) {
                    kind = "entity attribute: derived";
                } else if (attribute.isComposite()) {
                    kind = "entity attribute: composite";
                }
                count(kind, ok);
            }
        }
        for (ERRelationship relationship : diagram.getRelationships()) {
            Map<String, String> columns = trace.getRelationshipAttributeToColumn().get(relationship.getName());
            String owner;
            if (relationship.isGeneralization()) {
                owner = "relationship attribute on an ISA link";
            } else if (relationship.isCategoryLink()) {
                owner = "relationship attribute on a category link";
            } else if (relationship.isAggregationLink()) {
                owner = "relationship attribute on an absorbed aggregation link";
            } else if (relationship.getMergedTypeNames().size() > 0) {
                owner = "relationship attribute on a merged (ternary/aggregation) relationship";
            } else if (relationship.getDegree() > 2) {
                owner = "relationship attribute on an n-ary relationship";
            } else if (relationship.isRecursive()) {
                owner = "relationship attribute on a recursive relationship";
            } else {
                owner = "relationship attribute on a binary relationship";
            }
            for (ERAttribute attribute : relationship.getAttributes()) {
                boolean ok = columns != null && columns.containsKey(attribute.getName());
                if (!ok && owner.equals("relationship attribute on a binary relationship")) {
                    String strategy = trace.getRelationshipStrategy().get(relationship.getName());
                    count("  lost binary rel attr, strategy=" + strategy
                            + (relationship.isIdentifying() ? " (IDENTIFYING)" : ""), false);
                }
                count(owner, ok);
            }
        }
    }
}
