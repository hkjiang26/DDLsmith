package org.ddlsmith.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A randomly generated directed multigraph: the object that DDLSmith interprets as an ER diagram.
 *
 * <p>Nodes carry a label (their "type") and property values; edges are directed, carry a
 * relationship type and property values. Parallel edges and self-loops are allowed, because they
 * are exactly what makes the ER-to-relational mapping interesting: parallel edges become several
 * distinct relationships between the same pair of entities, and self-loops become recursive
 * relationships.</p>
 */
public class GraphModel {

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private String generatorName = "unknown";
    /** Target edge count that the generator aimed for, used for statistics. */
    private int targetEdgeCount;

    public List<GraphNode> getNodes() {
        return nodes;
    }

    public List<GraphEdge> getEdges() {
        return edges;
    }

    public GraphNode addNode(GraphNode node) {
        nodes.add(node);
        return node;
    }

    public GraphEdge addEdge(GraphEdge edge) {
        edges.add(edge);
        return edge;
    }

    public GraphNode getNode(int id) {
        return nodes.get(id);
    }

    /**
     * @return the edge with the given id. Generators that remove edges (rewiring, cardinality repair)
     *         may leave gaps in the id sequence, so this searches by id rather than indexing the list;
     *         the common case where ids equal indices is a direct lookup.
     */
    public GraphEdge getEdge(int id) {
        if (id >= 0 && id < edges.size() && edges.get(id).getId() == id) {
            return edges.get(id);
        }
        for (GraphEdge edge : edges) {
            if (edge.getId() == id) {
                return edge;
            }
        }
        throw new java.util.NoSuchElementException("no edge with id " + id);
    }

    /** @return true if an edge with the given id exists */
    public boolean hasEdge(int id) {
        if (id >= 0 && id < edges.size() && edges.get(id).getId() == id) {
            return true;
        }
        for (GraphEdge edge : edges) {
            if (edge.getId() == id) {
                return true;
            }
        }
        return false;
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getEdgeCount() {
        return edges.size();
    }

    public String getGeneratorName() {
        return generatorName;
    }

    public GraphModel setGeneratorName(String generatorName) {
        this.generatorName = generatorName;
        return this;
    }

    public int getTargetEdgeCount() {
        return targetEdgeCount;
    }

    public GraphModel setTargetEdgeCount(int targetEdgeCount) {
        this.targetEdgeCount = targetEdgeCount;
        return this;
    }

    /* ------------------------------------------------------------------ structure */

    public List<GraphEdge> getOutEdges(int nodeId) {
        List<GraphEdge> result = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (edge.getFromId() == nodeId) {
                result.add(edge);
            }
        }
        return result;
    }

    public List<GraphEdge> getInEdges(int nodeId) {
        List<GraphEdge> result = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (edge.getToId() == nodeId) {
                result.add(edge);
            }
        }
        return result;
    }

    public List<GraphEdge> getOutEdges(int nodeId, int relationshipTypeIndex) {
        List<GraphEdge> result = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (edge.getFromId() == nodeId && edge.getRelationshipTypeIndex() == relationshipTypeIndex) {
                result.add(edge);
            }
        }
        return result;
    }

    public List<GraphEdge> getInEdges(int nodeId, int relationshipTypeIndex) {
        List<GraphEdge> result = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (edge.getToId() == nodeId && edge.getRelationshipTypeIndex() == relationshipTypeIndex) {
                result.add(edge);
            }
        }
        return result;
    }

    public List<GraphEdge> getEdgesOfType(int relationshipTypeIndex) {
        List<GraphEdge> result = new ArrayList<>();
        for (GraphEdge edge : edges) {
            if (edge.getRelationshipTypeIndex() == relationshipTypeIndex) {
                result.add(edge);
            }
        }
        return result;
    }

    public List<GraphNode> getNodesOfLabel(int labelIndex) {
        List<GraphNode> result = new ArrayList<>();
        for (GraphNode node : nodes) {
            if (node.getLabelIndex() == labelIndex) {
                result.add(node);
            }
        }
        return result;
    }

    /** @return the degree of a node (in + out, self-loops counted once) */
    public int getDegree(int nodeId) {
        int degree = 0;
        for (GraphEdge edge : edges) {
            if (edge.getFromId() == nodeId) {
                degree++;
            }
            if (edge.getToId() == nodeId && edge.getFromId() != nodeId) {
                degree++;
            }
        }
        return degree;
    }

    /** @return the number of distinct relationship types incident to a node */
    public int getDistinctIncidentTypes(int nodeId) {
        Set<Integer> types = new LinkedHashSet<>();
        for (GraphEdge edge : edges) {
            if (edge.getFromId() == nodeId || edge.getToId() == nodeId) {
                types.add(edge.getRelationshipTypeIndex());
            }
        }
        return types.size();
    }

    /** @return the number of distinct labels adjacent to the nodes of a given label */
    public int getAdjacentLabelCount(int labelIndex) {
        Set<Integer> labels = new LinkedHashSet<>();
        for (GraphEdge edge : edges) {
            int fromLabel = nodes.get(edge.getFromId()).getLabelIndex();
            int toLabel = nodes.get(edge.getToId()).getLabelIndex();
            if (fromLabel == labelIndex) {
                labels.add(toLabel);
            }
            if (toLabel == labelIndex) {
                labels.add(fromLabel);
            }
        }
        return labels.size();
    }

    /** @return the weakly connected components of the graph, as lists of node ids */
    public List<List<Integer>> getConnectedComponents() {
        int[] component = new int[nodes.size()];
        java.util.Arrays.fill(component, -1);
        Map<Integer, List<Integer>> adjacency = new HashMap<>();
        for (GraphNode node : nodes) {
            adjacency.put(node.getId(), new ArrayList<>());
        }
        for (GraphEdge edge : edges) {
            adjacency.get(edge.getFromId()).add(edge.getToId());
            adjacency.get(edge.getToId()).add(edge.getFromId());
        }
        int currentComponent = 0;
        for (GraphNode node : nodes) {
            if (component[node.getId()] != -1) {
                continue;
            }
            List<Integer> queue = new ArrayList<>();
            queue.add(node.getId());
            component[node.getId()] = currentComponent;
            for (int i = 0; i < queue.size(); i++) {
                int current = queue.get(i);
                for (int neighbour : adjacency.get(current)) {
                    if (component[neighbour] == -1) {
                        component[neighbour] = currentComponent;
                        queue.add(neighbour);
                    }
                }
            }
            currentComponent++;
        }
        Map<Integer, List<Integer>> byComponent = new HashMap<>();
        for (GraphNode node : nodes) {
            byComponent.computeIfAbsent(component[node.getId()], k -> new ArrayList<>()).add(node.getId());
        }
        return new ArrayList<>(byComponent.values());
    }

    /** @return the number of self-loop edges */
    public int getSelfLoopCount() {
        int count = 0;
        for (GraphEdge edge : edges) {
            if (edge.isSelfLoop()) {
                count++;
            }
        }
        return count;
    }

    /** @return the number of unordered node pairs connected by more than one edge */
    public int getParallelEdgePairCount() {
        Map<Long, Integer> counts = new HashMap<>();
        for (GraphEdge edge : edges) {
            int a = Math.min(edge.getFromId(), edge.getToId());
            int b = Math.max(edge.getFromId(), edge.getToId());
            long key = (long) a * nodes.size() + b;
            counts.merge(key, 1, Integer::sum);
        }
        int pairs = 0;
        for (int count : counts.values()) {
            if (count > 1) {
                pairs++;
            }
        }
        return pairs;
    }

    public GraphStatistics getStatistics() {
        GraphStatistics statistics = new GraphStatistics();
        statistics.nodes = nodes.size();
        statistics.edges = edges.size();
        statistics.selfLoops = getSelfLoopCount();
        statistics.parallelEdgePairs = getParallelEdgePairCount();
        statistics.components = getConnectedComponents().size();
        double degreeSum = 0;
        for (GraphNode node : nodes) {
            degreeSum += getDegree(node.getId());
        }
        statistics.averageDegree = nodes.isEmpty() ? 0 : degreeSum / nodes.size();
        statistics.density = nodes.size() < 2 ? 0
                : (double) edges.size() / ((double) nodes.size() * (nodes.size() - 1));
        return statistics;
    }

    /** Structural statistics of a generated graph. */
    public static final class GraphStatistics {
        public int nodes;
        public int edges;
        public int selfLoops;
        public int parallelEdgePairs;
        public int components;
        public double averageDegree;
        public double density;

        @Override
        public String toString() {
            return "nodes=" + nodes + " edges=" + edges + " avgDegree=" + String.format("%.2f", averageDegree)
                    + " density=" + String.format("%.4f", density) + " selfLoops=" + selfLoops
                    + " parallelPairs=" + parallelEdgePairs + " components=" + components;
        }
    }

    @Override
    public String toString() {
        return "GraphModel[" + generatorName + ": " + getStatistics() + "]";
    }
}
