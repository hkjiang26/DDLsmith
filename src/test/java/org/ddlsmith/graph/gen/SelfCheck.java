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
import org.ddlsmith.graph.GraphFilling;
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
 * <p>Usage: {@code java -cp "target\gen-classes;target\core-api-classes;<deps>"
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
        public boolean sawAlternateKey;
        public boolean sawExtraDomainType;
        public boolean sawDeepHierarchy;
        /** Graphs whose node count exceeds the configured maximum, i.e. the enforcer added nodes. */
        public int graphsAboveMaxNodes;

        /**
         * How often each ER feature was produced, over every generated database. The booleans above say
         * whether a feature is reachable at all; these counters say how often, which is what makes the
         * effect of a generation change measurable.
         */
        public final Map<String, Integer> featureCounts = new LinkedHashMap<>();
        /** How many attributes of each domain the fillings produced, by type name. */
        public final Map<String, Integer> domainHistogram = new LinkedHashMap<>();
        /** How many labels carry how many attributes, by attribute count. */
        public final Map<Integer, Integer> labelAttributeCountHistogram = new LinkedHashMap<>();
        /** How many relationship types a base graph has, by type count. */
        public final Map<Integer, Integer> typeCountHistogram = new LinkedHashMap<>();
        /** How many subtypes sit at which ISA depth, by depth (1 = a direct subtype of a root). */
        public final Map<Integer, Integer> isaDepthHistogram = new LinkedHashMap<>();
        /** How many databases used the lean, normal and wide filling profile. */
        public int leanFillings;
        public int normalFillings;
        public int wideFillings;

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

        /** Counts one occurrence of a named feature. */
        public void count(String feature) {
            featureCounts.merge(feature, 1, Integer::sum);
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
                GraphFilling filling = new RandomGraphFiller().plan(schema);
                GraphPropertyFiller.fill(graph, filling);
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
                checkGraph(report, name, name + " seed " + seed, schema, graph, filling, options);
                checkBaseGraphShape(report, name + " seed " + seed, schema);
                checkFillingShape(report, name + " seed " + seed, schema, filling);
                recordFilling(report, filling);
                if (!withDiagrams) {
                    continue;
                }
                ERDiagram diagram;
                try {
                    diagram = mapper.map(graph, schema, filling);
                } catch (RuntimeException e) {
                    report.failures.add(name + " seed " + seed + ": mapping threw " + e);
                    continue;
                }
                report.diagrams++;
                checkDiagram(report, name + " seed " + seed, schema, graph, diagram);
                // the printers must never throw: they run in failure paths
                ERDiagramPrinter.describe(diagram);
                ERDiagramPrinter.describe(graph, schema, filling);
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
                    GraphFilling filling = new RandomGraphFiller().plan(schema);
                    GraphPropertyFiller.fill(graph, filling);
                    report.graphs++;
                    if (graph.getNodeCount() > options.getMaxNodes()) {
                        // only the enforcer's last-resort repair can add nodes
                        report.graphsAboveMaxNodes++;
                    }
                    checkGraph(report, name, name + "/case" + caseIndex + " seed " + seed, schema, graph, filling,
                            options);
                    try {
                        ERDiagram diagram = mapper.map(graph, schema, filling);
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
            GraphModel graph, GraphFilling filling, GraphGenerationOptions options) {
        if (options.isEnforceCardinality() && !CardinalityEnforcer.isValid(graph, schema)) {
            report.failures.add(where + ": CardinalityEnforcer.isValid is false\n"
                    + ERDiagramPrinter.describe(graph, schema, filling));
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
            checkProperties(report, where, "node " + i, filling.attributesOfLabel(node.getLabelIndex()),
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
            checkProperties(report, where, "edge " + i,
                    filling.attributesOfRelationshipType(edge.getRelationshipTypeIndex()), edge.getProperties());
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
            return value instanceof String && ((String) value).length() <= 10;
        case JSON:
            // the JSON generator emits a small document such as {"v":42}, which is longer than the
            // short strings the textual types are filled with
            return value instanceof String && ((String) value).startsWith("{");
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
     * Verifies the documented shape of the base graph {@link RandomGraphSchemaGenerator} produces:
     * 4..8 labels named {@code L<i>}, 4..10 relationship types named {@code R<t>}, at least one
     * recursive type, at least two types that are total on one side, no isolated label - and no
     * attributes at all, because a base graph is only nodes and edges.
     */
    public static void checkBaseGraphShape(Report report, String where, GraphSchema schema) {
        int labelCount = schema.getLabelCount();
        if (labelCount < 4 || labelCount > 8) {
            report.failures.add(where + ": " + labelCount + " labels");
        }
        for (GraphLabel label : schema.getLabels()) {
            if (!label.getName().equals("L" + label.getIndex())) {
                report.failures.add(where + ": label " + label.getIndex() + " is named " + label.getName());
            }
        }
        int typeCount = schema.getRelationshipTypeCount();
        if (typeCount < 4 || typeCount > RandomGraphSchemaGenerator.MAX_TOTAL_TYPES) {
            report.failures.add(where + ": " + typeCount + " relationship types");
        }
        report.typeCountHistogram.merge(typeCount, 1, Integer::sum);
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
    }

    /**
     * Verifies the documented shape of a {@link RandomGraphFiller} filling: attribute keys follow the
     * {@code k<j>} convention and are unique across the whole filling, every presence probability is
     * one of {1.0, 0.9, 0.7, 0.5}, a key attribute has an indexable domain, every parent link of the
     * inheritance plan really makes the child a strict superset of the parent, and the plan is acyclic
     * and at most {@code MAX_ISA_LEVELS} levels deep.
     */
    public static void checkFillingShape(Report report, String where, GraphSchema schema, GraphFilling filling) {
        Map<String, Integer> ownerOfKey = new LinkedHashMap<>();
        for (int label = 0; label < schema.getLabelCount(); label++) {
            Set<String> ownKeys = new LinkedHashSet<>();
            for (GraphProperty slot : filling.attributesOfLabel(label)) {
                checkSlot(report, where, "label L" + label, slot);
                if (!ownKeys.add(slot.getKey())) {
                    report.failures.add(where + ": label L" + label + " declares the attribute key "
                            + slot.getKey() + " twice");
                }
                Integer other = ownerOfKey.putIfAbsent(slot.getKey(), label);
                // the same key may only appear in two labels when one inherits from the other; anything
                // else would make two unrelated entities claim the same logical attribute
                if (other != null && other != label && !inherits(filling, label, other)
                        && !inherits(filling, other, label)) {
                    report.failures.add(where + ": the attribute key " + slot.getKey() + " belongs to L" + other
                            + " and to the unrelated L" + label);
                }
                if (slot.getKeyRole() != GraphProperty.KeyRole.NONE && !isKeySafe(slot.getType())) {
                    report.failures.add(where + ": key attribute " + slot.getKey() + " has the domain "
                            + slot.getType().getName() + ", which no engine can index portably");
                }
            }
        }
        for (int type = 0; type < schema.getRelationshipTypeCount(); type++) {
            Set<String> ownKeys = new LinkedHashSet<>();
            for (GraphProperty slot : filling.attributesOfRelationshipType(type)) {
                checkSlot(report, where, "type R" + type, slot);
                if (!ownKeys.add(slot.getKey())) {
                    report.failures.add(where + ": type R" + type + " declares the attribute key "
                            + slot.getKey() + " twice");
                }
                if (ownerOfKey.putIfAbsent(slot.getKey(), -1 - type) != null) {
                    report.failures.add(where + ": the attribute key " + slot.getKey()
                            + " is used by a label and by the relationship type R" + type);
                }
            }
        }
        for (int label = 0; label < schema.getLabelCount(); label++) {
            List<Integer> parents = filling.parentsOf(label);
            if (parents.size() > 2) {
                report.failures.add(where + ": label L" + label + " has " + parents.size() + " supertypes");
            }
            for (int parent : parents) {
                if (parent < 0 || parent >= schema.getLabelCount() || parent == label) {
                    report.failures.add(where + ": label L" + label + " inherits from " + parent);
                    continue;
                }
                if (!isStrictSuperset(filling, label, parent)) {
                    report.failures.add(where + ": label L" + label + " inherits from L" + parent
                            + " without being a strict superset of it, so no ISA link can be recognised");
                }
            }
            if (isaDepth(filling, label, 0) > MAX_FILLING_ISA_LEVELS) {
                report.failures.add(where + ": label L" + label + " is deeper than "
                        + MAX_FILLING_ISA_LEVELS + " ISA levels");
            }
        }
    }

    /** The deepest ISA path the ER mapper still recognises; a filling must not plan more. */
    private static final int MAX_FILLING_ISA_LEVELS = 3;

    private static boolean isKeySafe(DataType type) {
        if (type == null) {
            return false;
        }
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
        case DECIMAL:
        case REAL:
        case DOUBLE:
        case CHAR:
        case VARCHAR:
        case BOOLEAN:
        case DATE:
        case TIMESTAMP:
            return true;
        default:
            return false;
        }
    }

    private static boolean isStrictSuperset(GraphFilling filling, int subtype, int supertype) {        Set<String> subKeys = new LinkedHashSet<>();
        for (GraphProperty slot : filling.attributesOfLabel(subtype)) {
            subKeys.add(slot.getKey());
        }
        Set<String> superKeys = new LinkedHashSet<>();
        for (GraphProperty slot : filling.attributesOfLabel(supertype)) {
            superKeys.add(slot.getKey());
        }
        return subKeys.size() > superKeys.size() && subKeys.containsAll(superKeys);
    }

    /** @return true when {@code label} inherits from {@code candidate}, directly or through a chain */
    private static boolean inherits(GraphFilling filling, int label, int candidate) {
        for (int parent : filling.parentsOf(label)) {
            if (parent == candidate || inherits(filling, parent, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static int isaDepth(GraphFilling filling, int label, int guard) {
        if (guard > MAX_FILLING_ISA_LEVELS + 1) {
            return guard;
        }
        int deepest = 0;
        for (int parent : filling.parentsOf(label)) {
            deepest = Math.max(deepest, isaDepth(filling, parent, guard + 1));
        }
        return deepest + 1;
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

    /** Records the dimensions of a filling: profile width, domains and how many attributes it has. */
    private static void recordFilling(Report report, GraphFilling filling) {
        int labelAttributes = 0;
        int labels = filling.getLabelCount();
        for (int label = 0; label < labels; label++) {
            int count = filling.attributesOfLabel(label).size();
            labelAttributes += count;
            report.labelAttributeCountHistogram.merge(count, 1, Integer::sum);
            for (GraphProperty slot : filling.attributesOfLabel(label)) {
                recordDomain(report, slot.getType());
            }
        }
        for (int type = 0; type < 20; type++) {
            for (GraphProperty slot : filling.attributesOfRelationshipType(type)) {
                recordDomain(report, slot.getType());
            }
        }
        double average = labels == 0 ? 0 : labelAttributes / (double) labels;
        if (average <= 1.5) {
            report.leanFillings++;
        } else if (average <= 6.5) {
            report.normalFillings++;
        } else {
            report.wideFillings++;
        }
    }

    private static void recordDomain(Report report, DataType type) {
        if (type == null) {
            return;
        }
        // by category, so that two ENUMs with different value lists are one entry: the question the
        // census answers is which domains the ER layer reaches, not which literals an enum carries
        report.domainHistogram.merge(type.getCategory().name(), 1, Integer::sum);
        if (!DataType.commonTypes().contains(type)) {
            report.sawExtraDomainType = true;
            report.count("attributes with an extra domain type");
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
                report.count("aggregations");
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
                report.count("weak entities");
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
                report.count("shared subclasses");
            }
            if (entity.isCategory()) {
                report.sawCategory = true;
                report.count("categories");
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
                report.count("subtype links");
                int depth = isaDepth(diagram, entity);
                report.isaDepthHistogram.merge(depth, 1, Integer::sum);
                if (depth > 1) {
                    report.sawDeepHierarchy = true;
                }
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
                report.count("composite keys");
            }
            if (!entity.getAlternateKeyAttributes().isEmpty()) {
                report.sawAlternateKey = true;
                report.count("alternate keys");
            }
            for (ERAttribute attribute : entity.getAttributes()) {
                if (attribute.isMultivalued()) {
                    report.sawMultivaluedAttribute = true;
                    report.count("multi-valued attributes");
                    if (report.multivaluedExample == null) {
                        report.multivaluedExample = where + " -> " + entity.getName() + "." + attribute.getName();
                    }
                }
                if (attribute.isDerived()) {
                    report.sawDerivedAttribute = true;
                    report.count("derived attributes");
                    if (attribute.getDerivedExpression() == null) {
                        report.failures.add(where + ": derived attribute " + attribute.getName()
                                + " has no expression");
                    }
                }
                if (attribute.isComposite()) {
                    report.sawCompositeAttribute = true;
                    report.count("composite attributes");
                    if (attribute.getComponents().size() < 2) {
                        report.failures.add(where + ": composite attribute " + attribute.getName()
                                + " has fewer than two components");
                    }
                    for (ERAttribute component : attribute.getComponents()) {
                        if (component.isComposite()) {
                            report.sawNestedComposite = true;
                            report.count("nested composites");
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
                report.count("ternary relationships");
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
                .append(" alternateKey=").append(report.sawAlternateKey)
                .append(" nestedComposite=").append(report.sawNestedComposite)
                .append(" deepHierarchy=").append(report.sawDeepHierarchy)
                .append(" extraDomain=").append(report.sawExtraDomainType).append('\n');
        // The filling counts are the measurement of how much one base graph can become: each database of
        // a group draws its own filling, so these are the numbers that show the filling stage working.
        sb.append("filling: lean=").append(report.leanFillings)
                .append(" normal=").append(report.normalFillings)
                .append(" wide=").append(report.wideFillings).append('\n');
        sb.append("label attribute counts: ").append(describeHistogram(report.labelAttributeCountHistogram))
                .append('\n');
        sb.append("relationship type counts: ").append(describeHistogram(report.typeCountHistogram)).append('\n');
        sb.append("ISA depths: ").append(describeHistogram(report.isaDepthHistogram)).append('\n');
        sb.append("feature counts: ").append(report.featureCounts).append('\n');
        sb.append("domains: ").append(describeHistogram(report.domainHistogram)).append('\n');
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

    /** @return a histogram as {@code key=count} pairs, in key order (numerically for integer keys) */
    private static String describeHistogram(Map<?, Integer> histogram) {
        List<Map.Entry<?, Integer>> entries = new ArrayList<>(histogram.entrySet());
        entries.sort((a, b) -> compareHistogramKeys(a.getKey(), b.getKey()));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<?, Integer> entry : entries) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join(" ", parts);
    }

    private static int compareHistogramKeys(Object first, Object second) {
        if (first instanceof Integer && second instanceof Integer) {
            return Integer.compare((Integer) first, (Integer) second);
        }
        return String.valueOf(first).compareTo(String.valueOf(second));
    }
}
