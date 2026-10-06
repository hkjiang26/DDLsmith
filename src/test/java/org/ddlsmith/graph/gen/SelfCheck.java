package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.graph.GraphEdge;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphGenerator;
import org.ddlsmith.graph.GraphLabel;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphNode;
import org.ddlsmith.graph.GraphProperty;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;

/**
 * Self-contained smoke test of the graph generation stage.
 *
 * <p>It runs the same battery of checks as {@code GraphGenerationTest} (which delegates to
 * {@link #run(int)}) without needing a test runner: build a schema, generate a graph, verify every
 * structural and typing invariant, map the graph to an ER diagram and verify the diagram's
 * invariants, for all six generators and a range of seeds. It prints the statistics it collected
 * plus which structural ER features were seen, so a failure is debuggable from the output alone.</p>
 *
 * <p>Usage: {@code java -cp "target\gen-classes;target\contract-classes;<deps>"
 * org.ddlsmith.graph.gen.SelfCheck [seeds]}</p>
 */
public final class SelfCheck {

    public static final int DEFAULT_SEEDS = 200;

    private SelfCheck() {
    }

    /* ------------------------------------------------------------------ report */

    /** Everything the checks observed; {@link #failures} is empty exactly when all checks passed. */
    public static final class Report {
        public final List<String> failures = new ArrayList<>();
        public final Map<String, GeneratorStats> perGenerator = new LinkedHashMap<>();
        public int graphs;
        public int diagrams;

        public boolean sawRecursiveRelationship;
        public boolean sawWeakEntity;
        public boolean sawIsaHierarchy;
        public boolean sawMultivaluedAttribute;
        public boolean sawDerivedAttribute;
        public boolean sawCompositeAttribute;
        public boolean sawIdentifyingRelationship;
        public boolean sawTotalParticipation;
        public boolean sawParallelEdges;
        public boolean sawSelfLoop;
        public boolean sawTernaryRelationship;
        public boolean sawAggregation;
        public boolean sawCategory;
        public boolean sawSharedSubclass;
        public boolean sawCompositeKey;
        public boolean sawNestedComposite;
        public boolean sawTotalSpecialization;
        /** Graphs whose node count exceeds the configured maximum, i.e. the enforcer added nodes. */
        public int graphsAboveMaxNodes;

        public String recursiveExample;
        public String weakExample;
        public String isaExample;
        public String multivaluedExample;
        public String ternaryExample;
        public String aggregationExample;
        public String categoryExample;

        public boolean isValid() {
            return failures.isEmpty();
        }
    }

    /** Aggregate statistics of one generator over all seeds. */
    public static final class GeneratorStats {
        public String name;
        public int graphs;
        public int nodes;
        public int edges;
        public int maxDegree;
        public double degreeSum;
        public double degreeSquareSum;
        public double maxDegreeSum;
        public double componentsSum;
        public double selfLoops;
        public double clusteringSum;
        public double minAverageDegree = Double.MAX_VALUE;
        public double maxAverageDegree = -Double.MAX_VALUE;

        public double averageDegree() {
            return graphs == 0 ? 0 : degreeSum / graphs;
        }

        /** @return the standard error of {@link #averageDegree()} over the sampled seeds */
        public double degreeStandardError() {
            if (graphs < 2) {
                return Double.MAX_VALUE;
            }
            double mean = averageDegree();
            double variance = Math.max(0, degreeSquareSum / graphs - mean * mean) * graphs / (graphs - 1.0);
            return Math.sqrt(variance / graphs);
        }

        public double averageMaxDegree() {
            return graphs == 0 ? 0 : maxDegreeSum / graphs;
        }

        public double averageComponents() {
            return graphs == 0 ? 0 : componentsSum / graphs;
        }

        public double averageClustering() {
            return graphs == 0 ? 0 : clusteringSum / graphs;
        }

        public double averageEdges() {
            return graphs == 0 ? 0 : edges / (double) graphs;
        }

        public double averageSelfLoops() {
            return graphs == 0 ? 0 : selfLoops / graphs;
        }
    }

    /* ------------------------------------------------------------------ entry points */

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_SEEDS;
        Report report = run(seeds);
        System.out.print(describe(report));
        List<String> structural = structuralChecks(report);
        Report matrix = runOptionsMatrix(Math.max(4, seeds / 10));
        System.out.println("options matrix: " + matrix.graphs + " graphs, " + matrix.diagrams + " diagrams, "
                + matrix.graphsAboveMaxNodes + " graph(s) needed a node added by the enforcer, "
                + matrix.failures.size() + " failure(s)");
        for (int i = 0; i < Math.min(10, matrix.failures.size()); i++) {
            System.out.println("  - " + matrix.failures.get(i));
        }
        if (!report.failures.isEmpty() || !structural.isEmpty() || !matrix.failures.isEmpty()) {
            System.out.println("SELF CHECK FAILED");
            for (String failure : report.failures) {
                System.out.println("  invariant: " + failure);
            }
            for (String failure : structural) {
                System.out.println("  structural: " + failure);
            }
            throw new IllegalStateException("self check failed: " + report.failures.size() + " invariant failure(s), "
                    + structural.size() + " structural failure(s), " + matrix.failures.size()
                    + " option matrix failure(s)");
        }
        System.out.println("SELF CHECK OK");
    }

    /** Runs the whole battery with the default generation options. */
    public static Report run(int seeds) {
        return run(seeds, new GraphGenerationOptions(), true);
    }

    /**
     * Runs the whole battery.
     *
     * @param seeds number of seeds per generator
     * @param options generation options applied to every graph
     * @param withDiagrams whether to also map every graph to an ER diagram and check it
     */
    public static Report run(int seeds, GraphGenerationOptions options, boolean withDiagrams) {
        Report report = new Report();
        DefaultGraphToERMapper mapper = new DefaultGraphToERMapper();
        for (String name : GraphGenerators.names()) {
            GeneratorStats stats = new GeneratorStats();
            stats.name = name;
            report.perGenerator.put(name, stats);
            GraphGenerator generator = GraphGenerators.forName(name);
            for (int seed = 0; seed < seeds; seed++) {
                Randomly.setSeed(seed);
                GraphSchema schema = new RandomGraphSchemaGenerator().generate();
                GraphModel graph = generator.generate(schema, options);
                report.graphs++;
                stats.graphs++;
                stats.nodes += graph.getNodeCount();
                stats.edges += graph.getEdgeCount();
                stats.selfLoops += graph.getSelfLoopCount();
                double degreeSum = 0;
                int maxDegree = 0;
                for (GraphNode node : graph.getNodes()) {
                    int degree = graph.getDegree(node.getId());
                    degreeSum += degree;
                    maxDegree = Math.max(maxDegree, degree);
                }
                double averageDegree = graph.getNodeCount() == 0 ? 0 : degreeSum / graph.getNodeCount();
                stats.degreeSum += averageDegree;
                stats.degreeSquareSum += averageDegree * averageDegree;
                stats.minAverageDegree = Math.min(stats.minAverageDegree, averageDegree);
                stats.maxAverageDegree = Math.max(stats.maxAverageDegree, averageDegree);
                stats.maxDegreeSum += maxDegree;
                stats.maxDegree = Math.max(stats.maxDegree, maxDegree);
                stats.componentsSum += graph.getConnectedComponents().size();
                stats.clusteringSum += clusteringCoefficient(graph);
                if (graph.getSelfLoopCount() > 0) {
                    report.sawSelfLoop = true;
                }
                if (graph.getParallelEdgePairCount() > 0) {
                    report.sawParallelEdges = true;
                }
                checkGraph(report, name, name + " seed " + seed, schema, graph, options);
                checkSchemaShape(report, name + " seed " + seed, schema);
                if (!withDiagrams) {
                    continue;
                }
                ERDiagram diagram;
                try {
                    diagram = mapper.map(graph, schema);
                } catch (RuntimeException e) {
                    report.failures.add(name + " seed " + seed + ": mapping threw " + e);
                    continue;
                }
                report.diagrams++;
                checkDiagram(report, name + " seed " + seed, schema, graph, diagram);
                // the printers must never throw: they run in failure paths
                ERDiagramPrinter.describe(diagram);
                ERDiagramPrinter.describe(graph, schema);
                ERDiagramPrinter.toMermaid(diagram);
            }
        }
        return report;
    }

    /* ------------------------------------------------------------------ graph checks */

    /**
     * Runs the checks for a set of option combinations that stress the generator differently from
     * the defaults: node ranges that contain no multiple of the label count (where the enforcer has
     * to add nodes to keep the graph valid), a range narrower than the label count, a fixed node
     * count, extreme edge factors, disabled parallel edges and disabled cardinality enforcement.
     *
     * @param seedsPerCase number of seeds per generator and option combination
     */
    public static Report runOptionsMatrix(int seedsPerCase) {
        List<GraphGenerationOptions> cases = List.of(
                new GraphGenerationOptions().setMinNodes(10).setMaxNodes(15),
                new GraphGenerationOptions().setMinNodes(6).setMaxNodes(7),
                new GraphGenerationOptions().setMinNodes(20).setMaxNodes(20),
                new GraphGenerationOptions().setMinNodes(8).setMaxNodes(24).setEdgeFactor(0.4)
                        .setSelfLoopProbability(0.3).setWindowFraction(0.5),
                new GraphGenerationOptions().setMinNodes(8).setMaxNodes(24).setEdgeFactor(3.0)
                        .setAllowParallelEdges(false),
                new GraphGenerationOptions().setMinNodes(12).setMaxNodes(24).setEnforceCardinality(false));
        Report report = new Report();
        DefaultGraphToERMapper mapper = new DefaultGraphToERMapper();
        int caseIndex = 0;
        for (GraphGenerationOptions options : cases) {
            caseIndex++;
            for (String name : GraphGenerators.names()) {
                GraphGenerator generator = GraphGenerators.forName(name);
                for (int seed = 0; seed < seedsPerCase; seed++) {
                    Randomly.setSeed(seed);
                    GraphSchema schema = new RandomGraphSchemaGenerator().generate();
                    GraphModel graph = generator.generate(schema, options);
                    report.graphs++;
                    if (graph.getNodeCount() > options.getMaxNodes()) {
                        // only the enforcer's last-resort repair can add nodes
                        report.graphsAboveMaxNodes++;
                    }
                    checkGraph(report, name, name + "/case" + caseIndex + " seed " + seed, schema, graph, options);
                    try {
                        ERDiagram diagram = mapper.map(graph, schema);
                        report.diagrams++;
                        checkDiagram(report, name + "/case" + caseIndex + " seed " + seed, schema, graph, diagram);
                    } catch (RuntimeException e) {
                        report.failures.add(name + "/case" + caseIndex + " seed " + seed + ": mapping threw " + e);
                    }
                }
            }
        }
        return report;
    }

    private static void checkGraph(Report report, String generatorName, String where, GraphSchema schema,
            GraphModel graph, GraphGenerationOptions options) {
        if (options.isEnforceCardinality() && !CardinalityEnforcer.isValid(graph, schema)) {
            report.failures.add(where + ": CardinalityEnforcer.isValid is false\n"
                    + ERDiagramPrinter.describe(graph, schema));
        }
        if (graph.getNodeCount() < options.getMinNodes()) {
            report.failures.add(where + ": " + graph.getNodeCount() + " nodes < minNodes " + options.getMinNodes());
        }
        if (!generatorName.equals(graph.getGeneratorName())) {
            report.failures.add(where + ": generator name is " + graph.getGeneratorName());
        }
        for (int i = 0; i < graph.getNodeCount(); i++) {
            GraphNode node = graph.getNode(i);
            if (node.getId() != i) {
                report.failures.add(where + ": node " + i + " has id " + node.getId());
            }
            if (node.getLabelIndex() < 0 || node.getLabelIndex() >= schema.getLabelCount()) {
                report.failures.add(where + ": node " + i + " has label " + node.getLabelIndex());
                continue;
            }
            checkProperties(report, where, "node " + i, schema.getLabel(node.getLabelIndex()).getProperties(),
                    node.getProperties());
        }
        for (int i = 0; i < graph.getEdgeCount(); i++) {
            GraphEdge edge = graph.getEdge(i);
            if (edge.getId() != i) {
                report.failures.add(where + ": edge " + i + " has id " + edge.getId());
            }
            if (edge.getFromId() < 0 || edge.getFromId() >= graph.getNodeCount() || edge.getToId() < 0
                    || edge.getToId() >= graph.getNodeCount()) {
                report.failures.add(where + ": edge " + i + " references a missing node");
                continue;
            }
            if (edge.getRelationshipTypeIndex() < 0
                    || edge.getRelationshipTypeIndex() >= schema.getRelationshipTypeCount()) {
                report.failures.add(where + ": edge " + i + " has type " + edge.getRelationshipTypeIndex());
                continue;
            }
            GraphRelationshipType type = schema.getRelationshipType(edge.getRelationshipTypeIndex());
            if (graph.getNode(edge.getFromId()).getLabelIndex() != type.getFromLabelIndex()
                    || graph.getNode(edge.getToId()).getLabelIndex() != type.getToLabelIndex()) {
                report.failures.add(where + ": edge " + i + " connects labels "
                        + graph.getNode(edge.getFromId()).getLabelIndex() + "->"
                        + graph.getNode(edge.getToId()).getLabelIndex() + " but " + type);
            }
            checkProperties(report, where, "edge " + i, type.getProperties(), edge.getProperties());
        }
    }

    private static void checkProperties(Report report, String where, String what, List<GraphProperty> slots,
            Map<String, Object> values) {        for (GraphProperty slot : slots) {
            Object value = values.get(slot.getKey());
            if (value == null) {
                if (slot.isMandatory()) {
                    report.failures.add(where + ": mandatory slot " + slot.getKey() + " missing on " + what);
                }
                continue;
            }
            if (!valueMatches(slot.getType(), value)) {
                report.failures.add(where + ": " + what + " slot " + slot.getKey() + " holds a "
                        + value.getClass().getSimpleName() + " for " + slot.getType().getName());
            }
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            boolean declared = false;
            for (GraphProperty slot : slots) {
                if (slot.getKey().equals(entry.getKey())) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                report.failures.add(where + ": " + what + " carries undeclared property " + entry.getKey());
            }
            if (entry.getValue() == null) {
                report.failures.add(where + ": " + what + " stores an explicit null for " + entry.getKey());
            }
        }
    }

    /** @return true if {@code value} is a legal Java representation of {@code type} */
    public static boolean valueMatches(DataType type, Object value) {
        if (type == null || value == null) {
            return false;
        }
        switch (type.getCategory()) {
        case SMALLINT:
            return value instanceof Short;
        case INTEGER:
            return value instanceof Integer;
        case BIGINT:
            return value instanceof Long;
        case DECIMAL:
        case REAL:
        case DOUBLE:
            return value instanceof Double;
        case CHAR:
        case VARCHAR:
        case TEXT:
        case JSON:
            return value instanceof String && ((String) value).length() <= 10;
        case BOOLEAN:
            return value instanceof Boolean;
        case DATE:
            return value instanceof java.sql.Date;
        case TIME:
            return value instanceof java.sql.Time;
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return value instanceof java.sql.Timestamp;
        case BLOB:
            return value instanceof byte[];
        case UUID:
            return value instanceof java.util.UUID;
        case ENUM:
            return value instanceof String;
        case ARRAY:
            return value instanceof Object[];
        default:
            return true;
        }
    }

    /**
     * @return the average local clustering coefficient of the graph (edges treated as undirected):
     *         how often the neighbours of a node are neighbours of each other. It separates the
     *         locality-aware generators (sliding, small world, hybrid) from the uniform and
     *         hub-based ones far more sharply than the average degree does.
     */
    public static double clusteringCoefficient(GraphModel graph) {
        int nodeCount = graph.getNodeCount();
        if (nodeCount < 3) {
            return 0;
        }
        boolean[][] adjacent = new boolean[nodeCount][nodeCount];
        for (GraphEdge edge : graph.getEdges()) {
            if (edge.isSelfLoop() || edge.getFromId() >= nodeCount || edge.getToId() >= nodeCount) {
                continue;
            }
            adjacent[edge.getFromId()][edge.getToId()] = true;
            adjacent[edge.getToId()][edge.getFromId()] = true;
        }
        double sum = 0;
        int counted = 0;
        for (int i = 0; i < nodeCount; i++) {
            List<Integer> neighbours = new ArrayList<>();
            for (int j = 0; j < nodeCount; j++) {
                if (adjacent[i][j]) {
                    neighbours.add(j);
                }
            }
            int degree = neighbours.size();
            if (degree < 2) {
                continue;
            }
            int links = 0;
            for (int a = 0; a < degree; a++) {
                for (int b = a + 1; b < degree; b++) {
                    if (adjacent[neighbours.get(a)][neighbours.get(b)]) {
                        links++;
                    }
                }
            }
            sum += links / (double) (degree * (degree - 1) / 2);
            counted++;
        }
        return counted == 0 ? 0 : sum / counted;
    }

    /* ------------------------------------------------------------------ schema checks */

    /**
     * Verifies the documented shape of {@link RandomGraphSchemaGenerator}: 4..8 labels named
     * {@code L<i>}, 2..6 slots named {@code k<j>} per label with a presence probability from
     * {1.0, 0.9, 0.7, 0.5}, 4..10 relationship types named {@code R<t>} with 0..3 slots, at least
     * one recursive type, at least two types that are total on one side, and no isolated label.
     */
    public static void checkSchemaShape(Report report, String where, GraphSchema schema) {
        int labelCount = schema.getLabelCount();
        if (labelCount < 4 || labelCount > 8) {
            report.failures.add(where + ": " + labelCount + " labels");
        }
        Set<String> allKeys = new LinkedHashSet<>();
        for (GraphLabel label : schema.getLabels()) {
            if (!label.getName().equals("L" + label.getIndex())) {
                report.failures.add(where + ": label " + label.getIndex() + " is named " + label.getName());
            }
            int slots = label.getProperties().size();
            if (slots < 2 || slots > 6) {
                report.failures.add(where + ": label " + label.getName() + " has " + slots + " slots");
            }
            for (GraphProperty slot : label.getProperties()) {
                checkSlot(report, where, label.getName(), slot);
                allKeys.add(slot.getKey());
            }
        }
        int typeCount = schema.getRelationshipTypeCount();
        if (typeCount < 4 || typeCount > 10) {
            report.failures.add(where + ": " + typeCount + " relationship types");
        }
        boolean recursive = false;
        int totalTypes = 0;
        boolean[] covered = new boolean[labelCount];
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            if (!type.getName().equals("R" + type.getIndex())) {
                report.failures.add(where + ": type " + type.getIndex() + " is named " + type.getName());
            }
            if (type.getFromLabelIndex() < 0 || type.getFromLabelIndex() >= labelCount
                    || type.getToLabelIndex() < 0 || type.getToLabelIndex() >= labelCount) {
                report.failures.add(where + ": type " + type.getName() + " connects unknown labels");
                continue;
            }
            covered[type.getFromLabelIndex()] = true;
            covered[type.getToLabelIndex()] = true;
            if (type.isRecursive()) {
                recursive = true;
            }
            if (type.getFromParticipation() == org.ddlsmith.ermodel.Participation.TOTAL
                    || type.getToParticipation() == org.ddlsmith.ermodel.Participation.TOTAL) {
                totalTypes++;
            }
            if (type.getProperties().size() > 3) {
                report.failures.add(where + ": type " + type.getName() + " has "
                        + type.getProperties().size() + " slots");
            }
            for (GraphProperty slot : type.getProperties()) {
                checkSlot(report, where, type.getName(), slot);
                allKeys.add(slot.getKey());
            }
        }
        if (!recursive) {
            report.failures.add(where + ": no recursive relationship type");
        }
        if (totalTypes < 2) {
            report.failures.add(where + ": only " + totalTypes + " total participation type(s)");
        }
        for (int label = 0; label < labelCount; label++) {
            if (!covered[label]) {
                report.failures.add(where + ": label L" + label + " is isolated in the schema");
            }
        }
        for (String key : allKeys) {
            if (key.length() < 2 || key.charAt(0) != 'k') {
                report.failures.add(where + ": slot key " + key + " does not follow the k<j> convention");
                break;
            }
        }
    }

    private static void checkSlot(Report report, String where, String owner, GraphProperty slot) {
        if (slot.getType() == null) {
            report.failures.add(where + ": slot " + slot.getKey() + " of " + owner + " has no type");
        }
        double presence = slot.getPresenceProbability();
        if (presence != 1.0 && presence != 0.9 && presence != 0.7 && presence != 0.5) {
            report.failures.add(where + ": slot " + slot.getKey() + " of " + owner + " has presence " + presence);
        }
    }

    /* ------------------------------------------------------------------ diagram checks */
    private static void checkDiagram(Report report, String where, GraphSchema schema, GraphModel graph,
            ERDiagram diagram) {
        int aggregationEntities = diagram.getAggregationEntities().size();
        if (diagram.getEntities().size() != schema.getLabelCount() + aggregationEntities) {
            report.failures.add(where + ": " + diagram.getEntities().size() + " entities for "
                    + schema.getLabelCount() + " labels and " + aggregationEntities + " aggregation(s)");
        }
        for (EREntity entity : diagram.getEntities()) {
            if (entity.isAggregation()) {
                report.sawAggregation = true;
                if (report.aggregationExample == null) {
                    report.aggregationExample = where + " -> " + entity.getName() + " aggregates "
                            + entity.getAggregatedRelationship();
                }
            }
            if (entity.isAggregation() && entity.getAttributes().isEmpty()) {
                // an aggregation entity stands for a relationship; it has no attributes of its own
                if (diagram.getRelationship(entity.getAggregatedRelationship()) == null) {
                    report.failures.add(where + ": aggregation entity " + entity.getName()
                            + " aggregates the unknown relationship " + entity.getAggregatedRelationship());
                }
                continue;
            }
            if (entity.getAttributes().isEmpty()) {
                report.failures.add(where + ": entity " + entity.getName() + " has no attribute");
            }
            if (entity.getGraphNodeId() != -1) {
                report.failures.add(where + ": entity " + entity.getName() + " has graphNodeId "
                        + entity.getGraphNodeId());
            }
            List<String> names = new ArrayList<>();
            for (ERAttribute attribute : entity.getAttributes()) {
                if (names.contains(attribute.getName())) {
                    report.failures.add(where + ": entity " + entity.getName() + " has duplicate attribute "
                            + attribute.getName());
                }
                names.add(attribute.getName());
                if (attribute.getType() == null) {
                    report.failures.add(where + ": attribute " + attribute.getName() + " has no type");
                }
            }
            if (entity.isWeak()) {
                report.sawWeakEntity = true;
                if (report.weakExample == null) {
                    report.weakExample = where + " -> " + entity.getName() + " owner=" + entity.getOwnerEntity()
                            + " via " + entity.getOwnerRelationship();
                }
                if (entity.getOwnerEntity() == null || !diagram.hasEntity(entity.getOwnerEntity())) {
                    report.failures.add(where + ": weak entity " + entity.getName() + " has owner "
                            + entity.getOwnerEntity());
                }
                if (entity.getOwnerRelationship() == null
                        || diagram.getRelationship(entity.getOwnerRelationship()) == null) {
                    report.failures.add(where + ": weak entity " + entity.getName() + " has owner relationship "
                            + entity.getOwnerRelationship());
                }
                if (entity.getPartialKeyAttributes().isEmpty()) {
                    report.failures.add(where + ": weak entity " + entity.getName() + " has no partial key");
                }
            }
            if (entity.isSharedSubclass()) {
                report.sawSharedSubclass = true;
            }
            if (entity.isCategory()) {
                report.sawCategory = true;
                if (report.categoryExample == null) {
                    report.categoryExample = where + " -> " + entity.getName() + " category-of "
                            + entity.getCategorySupers();
                }
                if (entity.getCategorySupers().size() < 2) {
                    report.failures.add(where + ": category " + entity.getName() + " has "
                            + entity.getCategorySupers().size() + " superclasses");
                }
                for (String superName : entity.getCategorySupers()) {
                    if (!diagram.hasEntity(superName)) {
                        report.failures.add(where + ": category " + entity.getName() + " has the unknown"
                                + " superclass " + superName);
                    }
                }
            }
            if (entity.isAggregation()) {
                report.sawAggregation = true;
                if (report.aggregationExample == null) {
                    report.aggregationExample = where + " -> " + entity.getName() + " aggregates "
                            + entity.getAggregatedRelationship();
                }
            }
            if (entity.isGeneralizationSubtype()) {
                report.sawIsaHierarchy = true;
                for (String superName : entity.getSuperEntities()) {
                    if (!diagram.hasEntity(superName)) {
                        report.failures.add(where + ": subtype " + entity.getName() + " has unknown supertype "
                                + superName);
                    }
                    if (report.isaExample == null) {
                        report.isaExample = where + " -> " + entity.getName() + " ISA " + superName;
                    }
                }
            }
            if (entity.isGeneralizationSupertype()) {
                report.sawIsaHierarchy = true;
                if (entity.isTotalSpecialization()) {
                    report.sawTotalSpecialization = true;
                }
                if (!entity.getAttributes().isEmpty() && entity.getDiscriminatorAttribute() == null) {
                    report.failures.add(where + ": supertype " + entity.getName()
                            + " has attributes but no discriminator");
                }
                for (String sub : entity.getSubEntities()) {
                    EREntity subEntity = diagram.getEntity(sub);
                    if (subEntity == null || !subEntity.hasSuperEntity(entity.getName())) {
                        report.failures.add(where + ": supertype " + entity.getName() + " lists unknown subtype "
                                + sub);
                    }
                }
            }
            if (entity.getPrimaryKeyAttributes().size() > 1) {
                report.sawCompositeKey = true;
            }
            for (ERAttribute attribute : entity.getAttributes()) {
                if (attribute.isMultivalued()) {
                    report.sawMultivaluedAttribute = true;
                    if (report.multivaluedExample == null) {
                        report.multivaluedExample = where + " -> " + entity.getName() + "." + attribute.getName();
                    }
                }
                if (attribute.isDerived()) {
                    report.sawDerivedAttribute = true;
                    if (attribute.getDerivedExpression() == null) {
                        report.failures.add(where + ": derived attribute " + attribute.getName()
                                + " has no expression");
                    }
                }
                if (attribute.isComposite()) {
                    report.sawCompositeAttribute = true;
                    if (attribute.getComponents().size() < 2) {
                        report.failures.add(where + ": composite attribute " + attribute.getName()
                                + " has fewer than two components");
                    }
                    for (ERAttribute component : attribute.getComponents()) {
                        if (component.isComposite()) {
                            report.sawNestedComposite = true;
                        }
                    }
                }
            }
        }
        for (ERRelationship relationship : diagram.getRelationships()) {
            if (relationship.getParticipants().size() < 2) {
                report.failures.add(where + ": relationship " + relationship.getName() + " has "
                        + relationship.getParticipants().size() + " participant(s)");
            }
            if (relationship.getGraphEdgeId() != -1) {
                report.failures.add(where + ": relationship " + relationship.getName() + " has graphEdgeId "
                        + relationship.getGraphEdgeId());
            }
            for (ERParticipant participant : relationship.getParticipants()) {
                if (!diagram.hasEntity(participant.getEntityName())) {
                    report.failures.add(where + ": relationship " + relationship.getName() + " references unknown "
                            + "entity " + participant.getEntityName());
                }
            }
            if (relationship.isIdentifying()) {
                report.sawIdentifyingRelationship = true;
            }
            if (relationship.isRecursive() && !relationship.isGeneralization()) {
                report.sawRecursiveRelationship = true;
                if (report.recursiveExample == null) {
                    report.recursiveExample = where + " -> " + relationship;
                }
            }
            if (relationship.isNary()) {
                report.sawTernaryRelationship = true;
                if (report.ternaryExample == null) {
                    report.ternaryExample = where + " -> " + relationship;
                }
            }
            for (ERParticipant participant : relationship.getParticipants()) {
                if (participant.getParticipation() == org.ddlsmith.ermodel.Participation.TOTAL) {
                    report.sawTotalParticipation = true;
                }
            }
        }
        checkRelationshipCoverage(report, where, schema, graph, diagram);
        checkStatistics(report, where, diagram);
    }

    /**
     * Cross-checks the relationships against the schema: a relationship type with edges must be
     * present with the cardinalities and participations of the type, unless it was turned into a
     * multi-valued attribute or merged into a ternary relationship; a type without edges must not be
     * present at all.
     */
    private static void checkRelationshipCoverage(Report report, String where, GraphSchema schema, GraphModel graph,
            ERDiagram diagram) {
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            ERRelationship relationship = diagram.getRelationship("R" + type.getIndex());
            if (graph.getEdgesOfType(type.getIndex()).isEmpty()) {
                if (relationship != null) {
                    report.failures.add(where + ": " + type.getName() + " has no edge but produced a relationship");
                }
                continue;
            }
            if (relationship == null) {
                EREntity entity = diagram.getEntity("E" + type.getFromLabelIndex());
                if (!type.isRecursive() || type.getToCardinality() != Cardinality.MANY || entity == null
                        || entity.getAttribute("mv_" + type.getName()) == null) {
                    if (!mergedIntoNaryRelationship(diagram, type.getName())) {
                        report.failures.add(where + ": " + type.getName() + " has edges but produced no relationship "
                                + "and no multi-valued attribute");
                    }
                }
                continue;
            }
            if (relationship.getParticipants().size() != 2) {
                // n-ary relationships merge several types; the per-type checks do not apply
                continue;
            }
            ERParticipant from = relationship.getParticipants().get(0);
            ERParticipant to = relationship.getParticipants().get(1);
            if (!from.getEntityName().equals("E" + type.getFromLabelIndex())
                    || !to.getEntityName().equals("E" + type.getToLabelIndex())) {
                report.failures.add(where + ": " + relationship.getName() + " connects " + from.getEntityName()
                        + "->" + to.getEntityName() + " but the type connects L" + type.getFromLabelIndex()
                        + "->L" + type.getToLabelIndex());
            }
            if (from.getCardinality() != type.getFromCardinality() || to.getCardinality() != type.getToCardinality()
                    || from.getParticipation() != type.getFromParticipation()
                    || to.getParticipation() != type.getToParticipation()) {
                report.failures.add(where + ": " + relationship.getName() + " does not carry the cardinalities of "
                        + type);
            }
            if (type.isRecursive() && (!from.getRoleName().equals(from.getEntityName() + "_src")
                    || !to.getRoleName().equals(to.getEntityName() + "_dst"))) {
                report.failures.add(where + ": recursive " + relationship.getName() + " has roles "
                        + from.getRoleName() + "/" + to.getRoleName());
            }
        }
    }

    /** @return true when some n-ary relationship of the diagram merged the given type into itself */
    private static boolean mergedIntoNaryRelationship(ERDiagram diagram, String typeName) {
        for (ERRelationship relationship : diagram.getRelationships()) {
            if (relationship.getMergedTypeNames().contains(typeName)) {
                return true;
            }
        }
        return false;
    }

    /** Recomputes the statistics by hand and compares them with what the diagram reports. */
    private static void checkStatistics(Report report, String where, ERDiagram diagram) {
        ERDiagram.ERStatistics stats = diagram.getStatistics();
        int attributes = 0;
        int weak = 0;
        int multivalued = 0;
        int composite = 0;
        int derived = 0;
        int sharedSubclasses = 0;
        int categories = 0;
        int aggregations = 0;
        int totalSpecializations = 0;
        int compositeKeys = 0;
        int compositePartialKeys = 0;
        for (EREntity entity : diagram.getEntities()) {
            attributes += entity.getAttributes().size();
            if (entity.isWeak()) {
                weak++;
            }
            if (entity.isSharedSubclass()) {
                sharedSubclasses++;
            }
            if (entity.isCategory()) {
                categories++;
            }
            if (entity.isAggregation()) {
                aggregations++;
            }
            if (entity.isGeneralizationSupertype() && entity.isTotalSpecialization()) {
                totalSpecializations++;
            }
            if (entity.getPrimaryKeyAttributes().size() > 1) {
                compositeKeys++;
            }
            if (entity.isWeak() && entity.getPartialKeyAttributes().size() > 1) {
                compositePartialKeys++;
            }
            for (ERAttribute attribute : entity.getAttributes()) {
                if (attribute.isMultivalued()) {
                    multivalued++;
                }
                if (attribute.isComposite()) {
                    composite++;
                }
                if (attribute.isDerived()) {
                    derived++;
                }
            }
        }
        if (stats.entities != diagram.getEntities().size() || stats.relationships != diagram.getRelationships().size()
                || stats.attributes != attributes || stats.weakEntities != weak
                || stats.multivaluedAttributes != multivalued || stats.compositeAttributes != composite
                || stats.derivedAttributes != derived || stats.sharedSubclasses != sharedSubclasses
                || stats.categories != categories || stats.aggregations != aggregations
                || stats.totalSpecializations != totalSpecializations || stats.compositeKeys != compositeKeys
                || stats.compositePartialKeys != compositePartialKeys) {
            report.failures.add(where + ": statistics mismatch: " + stats + " vs entities="
                    + diagram.getEntities().size() + " attributes=" + attributes + " weak=" + weak + " multivalued="
                    + multivalued + " composite=" + composite + " derived=" + derived + " shared=" + sharedSubclasses
                    + " categories=" + categories + " aggregations=" + aggregations + " totalSpec="
                    + totalSpecializations + " compositeKeys=" + compositeKeys + " compositePartialKeys="
                    + compositePartialKeys);
        }
        if (stats.entities != diagram.getEntities().size()) {
            report.failures.add(where + ": entity count mismatch");
        }
    }

    /* ------------------------------------------------------------------ aggregate checks */

    /**
     * @return the failures of the aggregate expectations: the generators must differ structurally,
     *         and the 200 seeds must have produced every ER feature the mapping stage cares about
     */
    public static List<String> structuralChecks(Report report) {
        List<String> failures = new ArrayList<>();
        if (report.perGenerator.size() != GraphGenerators.names().size()) {
            failures.add("not every generator was exercised: " + report.perGenerator.keySet());
            return failures;
        }
        double minDegree = Double.MAX_VALUE;
        double maxDegree = -Double.MAX_VALUE;
        double minComponents = Double.MAX_VALUE;
        double maxComponents = -Double.MAX_VALUE;
        double minClustering = Double.MAX_VALUE;
        double maxClustering = -Double.MAX_VALUE;
        for (GeneratorStats stats : report.perGenerator.values()) {
            minDegree = Math.min(minDegree, stats.averageDegree());
            maxDegree = Math.max(maxDegree, stats.averageDegree());
            minComponents = Math.min(minComponents, stats.averageComponents());
            maxComponents = Math.max(maxComponents, stats.averageComponents());
            minClustering = Math.min(minClustering, stats.averageClustering());
            maxClustering = Math.max(maxClustering, stats.averageClustering());
        }
        // the average degrees differ by far more than the standard error of the means, i.e. the
        // difference is a property of the generators and not of the sampled seeds
        if (maxDegree - minDegree < 0.15) {
            failures.add("the generators do not differ in average degree: " + report.perGenerator.keySet());
        }
        if (maxComponents - minComponents < 0.25) {
            failures.add("the generators do not differ in component count");
        }
        if (maxClustering - minClustering < 0.02) {
            failures.add("the generators do not differ in clustering coefficient");
        }
        // the sparsest and the densest generator must be separated by more than three standard
        // errors of their means, i.e. the difference must come from the generator, not the seeds
        double largestGap = 0;
        for (GeneratorStats first : report.perGenerator.values()) {
            for (GeneratorStats second : report.perGenerator.values()) {
                double gap = Math.abs(first.averageDegree() - second.averageDegree());
                double error = 3 * Math.hypot(first.degreeStandardError(), second.degreeStandardError());
                if (gap > error) {
                    largestGap = Math.max(largestGap, gap);
                }
            }
        }
        if (largestGap <= 0) {
            failures.add("no two generators differ in average degree by more than three standard errors");
        }
        GeneratorStats preferential = report.perGenerator.get(PreferentialAttachmentGraphGenerator.NAME);
        GeneratorStats sliding = report.perGenerator.get(SlidingGraphGenerator.NAME);
        if (preferential != null && sliding != null
                && preferential.averageMaxDegree() <= sliding.averageMaxDegree()) {
            failures.add("preferential (" + preferential.averageMaxDegree() + ") does not reach a higher max degree "
                    + "than sliding (" + sliding.averageMaxDegree() + ")");
        }
        if (!report.sawRecursiveRelationship) {
            failures.add("no recursive relationship was produced in " + report.graphs + " graphs");
        }
        if (!report.sawWeakEntity) {
            failures.add("no weak entity was produced in " + report.diagrams + " diagrams");
        }
        if (!report.sawIsaHierarchy) {
            failures.add("no ISA hierarchy was produced in " + report.diagrams + " diagrams");
        }
        if (!report.sawMultivaluedAttribute) {
            failures.add("no multi-valued attribute was produced in " + report.diagrams + " diagrams");
        }
        if (!report.sawTernaryRelationship) {
            failures.add("no ternary relationship was produced in " + report.diagrams + " diagrams");
        }
        if (!report.sawAggregation) {
            failures.add("no aggregation was produced in " + report.diagrams + " diagrams");
        }
        if (!report.sawCategory) {
            failures.add("no union type (category) was produced in " + report.diagrams + " diagrams");
        }
        if (!report.sawSharedSubclass) {
            failures.add("no shared subclass was produced in " + report.diagrams + " diagrams");
        }
        if (!report.sawCompositeKey) {
            failures.add("no composite natural key was produced in " + report.diagrams + " diagrams");
        }
        if (!report.sawNestedComposite) {
            failures.add("no nested composite attribute was produced in " + report.diagrams + " diagrams");
        }
        return failures;
    }

    /* ------------------------------------------------------------------ printing */

    public static String describe(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph generation self check: ").append(report.graphs).append(" graphs, ")
                .append(report.diagrams).append(" diagrams\n");
        sb.append(String.format("%-14s %7s %8s %8s %9s %8s %9s %9s %9s %9s%n", "generator", "nodes", "edges",
                "avgDeg", "seDeg", "maxDeg", "avgMaxDeg", "avgComps", "clustering", "selfLoops"));
        for (GeneratorStats stats : report.perGenerator.values()) {
            sb.append(String.format("%-14s %7.1f %8.1f %8.3f %9.3f %8d %9.2f %9.2f %9.3f %9.2f%n", stats.name,
                    stats.graphs == 0 ? 0 : stats.nodes / (double) stats.graphs, stats.averageEdges(),
                    stats.averageDegree(), stats.degreeStandardError(), stats.maxDegree, stats.averageMaxDegree(),
                    stats.averageComponents(), stats.averageClustering(), stats.averageSelfLoops()));
        }
        sb.append("features seen: recursive=").append(report.sawRecursiveRelationship)
                .append(" weak=").append(report.sawWeakEntity)
                .append(" isa=").append(report.sawIsaHierarchy)
                .append(" multivalued=").append(report.sawMultivaluedAttribute)
                .append(" derived=").append(report.sawDerivedAttribute)
                .append(" composite=").append(report.sawCompositeAttribute)
                .append(" identifying=").append(report.sawIdentifyingRelationship)
                .append(" totalParticipation=").append(report.sawTotalParticipation)
                .append(" selfLoop=").append(report.sawSelfLoop)
                .append(" parallelEdges=").append(report.sawParallelEdges).append('\n');
        sb.append("EER features: ternary=").append(report.sawTernaryRelationship)
                .append(" aggregation=").append(report.sawAggregation)
                .append(" category=").append(report.sawCategory)
                .append(" sharedSubclass=").append(report.sawSharedSubclass)
                .append(" totalSpecialization=").append(report.sawTotalSpecialization)
                .append(" compositeKey=").append(report.sawCompositeKey)
                .append(" nestedComposite=").append(report.sawNestedComposite).append('\n');
        sb.append("examples: recursive[").append(report.recursiveExample).append("] weak[")
                .append(report.weakExample).append("] isa[").append(report.isaExample).append("] multivalued[")
                .append(report.multivaluedExample).append("] ternary[").append(report.ternaryExample)
                .append("] aggregation[").append(report.aggregationExample).append("] category[")
                .append(report.categoryExample).append("]\n");
        if (!report.failures.isEmpty()) {
            sb.append(report.failures.size()).append(" invariant failure(s):\n");
            int shown = Math.min(20, report.failures.size());
            for (int i = 0; i < shown; i++) {
                sb.append("  - ").append(report.failures.get(i)).append('\n');
            }
        }
        return sb.toString();
    }
}
