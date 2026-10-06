package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.graph.GraphEdge;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphNode;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;

/**
 * Shared state of one graph generation run: the node set with its labels, the lookup tables that
 * map a label pair to the relationship types that may connect it, and the edge bookkeeping.
 *
 * <p>All six generators build their structure on top of this context, so that the rules that are
 * easy to get wrong live in exactly one place:</p>
 * <ul>
 *   <li>a node count that is a multiple of the label count whenever the configured range allows
 *       one, so that every label holds the same number of nodes (see below);</li>
 *   <li>edges are only ever created with a relationship type whose from/to labels match the
 *       endpoints, and never twice with the same type between the same ordered pair;</li>
 *   <li>recursive relationship types that are reserved for self-loops (they are the ones the ER
 *       mapper may turn into multi-valued attributes).</li>
 * </ul>
 *
 * <p><b>Why equal label sizes matter.</b> A {@code TOTAL} participation on a side whose opposite
 * cardinality is {@code ONE} needs at least as many nodes on the other side (every source node
 * needs one outgoing edge, and each target node accepts at most one incoming edge, so the edges
 * form an injection). With {@code |L_i| == |L_j|} for all labels that requirement is always met,
 * which is what lets {@link CardinalityEnforcer} guarantee that the produced graph really is a
 * valid instance of the schema. Node counts are therefore picked from the multiples of the label
 * count inside {@code [minNodes, maxNodes]}; only when the configured range contains no multiple
 * does the generator fall back to a plain random count (and then an unsatisfiable schema can, in
 * principle, leave one participation constraint unfulfilled).</p>
 */
final class GraphBuildContext {

    private final GraphSchema schema;
    private final GraphGenerationOptions options;
    private final GraphModel graph = new GraphModel();
    private final List<GraphNode> nodes = new ArrayList<>();
    private final Map<Integer, List<GraphNode>> nodesByLabel = new LinkedHashMap<>();
    private final Map<Long, List<GraphRelationshipType>> pairTypeCache = new HashMap<>();
    private final Map<Long, Set<Integer>> usedTypesByPair = new HashMap<>();
    private final Set<Integer> selfLoopOnlyTypes = new LinkedHashSet<>();
    private final int targetEdgeCount;

    private int nextEdgeId;
    private boolean edgesRemoved;
    private GraphEdge lastEdge;

    GraphBuildContext(GraphSchema schema, GraphGenerationOptions options) {
        this.schema = schema;
        this.options = options;
        int labelCount = Math.max(1, schema.getLabelCount());
        int min = Math.max(1, options.getMinNodes());
        int max = Math.max(min, options.getMaxNodes());
        int nodeCount = pickNodeCount(labelCount, min, max);
        this.targetEdgeCount = Math.max(0, (int) Math.round(options.getEdgeFactor() * nodeCount));
        createNodes(nodeCount, labelCount);
        reserveSelfLoopOnlyTypes();
    }

    /* ------------------------------------------------------------------ construction */

    /**
     * Picks the node count, preferring a multiple of the label count so that all labels end up with
     * exactly the same number of nodes (see the class comment).
     */
    private static int pickNodeCount(int labelCount, int min, int max) {
        List<Integer> multiples = new ArrayList<>();
        for (int candidate = min; candidate <= max; candidate++) {
            if (candidate % labelCount == 0) {
                multiples.add(candidate);
            }
        }
        int nodeCount = multiples.isEmpty() ? Randomly.getInteger(min, max + 1) : Randomly.fromList(multiples);
        if (nodeCount < labelCount) {
            // every label should appear at least once; only possible if the range allows it
            nodeCount = Math.max(nodeCount, Math.min(labelCount, max));
        }
        return nodeCount;
    }

    private void createNodes(int nodeCount, int labelCount) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < labelCount; i++) {
            order.add(i);
        }
        Randomly.shuffle(order);
        List<Integer> assignment = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            assignment.add(order.get(i % labelCount));
        }
        // round-robin gives every label the same share; the shuffle decides where those nodes sit,
        // which is what makes the index-ordered generators (sliding, small world, hybrid) see a
        // random mix of labels inside each locality window
        Randomly.shuffle(assignment);
        for (int i = 0; i < nodeCount; i++) {
            GraphNode node = new GraphNode(i, assignment.get(i));
            graph.addNode(node);
            nodes.add(node);
            nodesByLabel.computeIfAbsent(node.getLabelIndex(), key -> new ArrayList<>()).add(node);
        }
    }

    /**
     * Reserves some recursive relationship types for self-loops only. Such a type is never used for
     * an edge between two distinct nodes, so every edge of the type is a self-loop; that is exactly
     * the shape the ER mapper turns into a multi-valued attribute (see
     * {@link DefaultGraphToERMapper}).
     */
    private void reserveSelfLoopOnlyTypes() {
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            if (!type.isRecursive() || type.getToCardinality() != Cardinality.MANY) {
                continue;
            }
            if (!nodesByLabel.containsKey(type.getFromLabelIndex())) {
                continue;
            }
            if (Rand.chance(0.5)) {
                selfLoopOnlyTypes.add(type.getIndex());
            }
        }
    }

    /* ------------------------------------------------------------------ accessors */

    GraphModel getGraph() {
        return graph;
    }

    GraphSchema getSchema() {
        return schema;
    }

    GraphGenerationOptions getOptions() {
        return options;
    }

    List<GraphNode> getNodes() {
        return nodes;
    }

    int getNodeCount() {
        return nodes.size();
    }

    int getTargetEdgeCount() {
        return targetEdgeCount;
    }

    /** @return the nodes carrying {@code labelIndex} (possibly empty) */
    List<GraphNode> nodesOfLabel(int labelIndex) {
        List<GraphNode> result = nodesByLabel.get(labelIndex);
        return result == null ? List.of() : result;
    }

    boolean isSelfLoopOnly(GraphRelationshipType type) {
        return selfLoopOnlyTypes.contains(type.getIndex());
    }

    /** @return the recursive relationship types of a label that are still available for self-loops */
    List<GraphRelationshipType> selfLoopTypes(int labelIndex) {
        List<GraphRelationshipType> result = new ArrayList<>();
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            if (type.isRecursive() && type.getFromLabelIndex() == labelIndex && !isSelfLoopOnly(type)) {
                result.add(type);
            }
        }
        return result;
    }

    /**
     * @return the relationship types that may connect a node of {@code fromLabel} to a node of
     *         {@code toLabel}; reserved self-loop-only types are excluded, so a pair edge can never
     *         spoil the "all edges are self-loops" property of those types
     */
    List<GraphRelationshipType> pairTypes(int fromLabel, int toLabel) {
        long key = pairKey(fromLabel, toLabel);
        List<GraphRelationshipType> cached = pairTypeCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<GraphRelationshipType> result = new ArrayList<>();
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            if (type.getFromLabelIndex() != fromLabel || type.getToLabelIndex() != toLabel) {
                continue;
            }
            if (isSelfLoopOnly(type)) {
                continue;
            }
            result.add(type);
        }
        List<GraphRelationshipType> immutable = List.copyOf(result);
        pairTypeCache.put(key, immutable);
        return immutable;
    }

    private static long pairKey(int from, int to) {
        return ((long) from << 32) | (to & 0xffffffffL);
    }

    /* ------------------------------------------------------------------ edges */

    /** @return true if a node of {@code a}'s label may point at a node of {@code b}'s label */
    boolean canConnect(GraphNode a, GraphNode b) {
        return !pairTypes(a.getLabelIndex(), b.getLabelIndex()).isEmpty();
    }

    /** @return true if at least one of the two directions is allowed by the schema */
    boolean canConnectEitherWay(GraphNode a, GraphNode b) {
        return canConnect(a, b) || canConnect(b, a);
    }

    /**
     * Creates an edge between two nodes, picking a random relationship type compatible with the
     * endpoint labels. The pair is skipped (no edge, no exception) when the schema offers no
     * compatible type, or when the parallel-edge policy forbids another edge for this pair.
     *
     * @return true if an edge was created
     */
    boolean connect(GraphNode from, GraphNode to) {
        List<GraphRelationshipType> types = pairTypes(from.getLabelIndex(), to.getLabelIndex());
        if (types.isEmpty()) {
            return false;
        }
        Set<Integer> used = usedTypesByPair.computeIfAbsent(pairKey(from.getId(), to.getId()), key -> new HashSet<>());
        List<GraphRelationshipType> usable = new ArrayList<>();
        for (GraphRelationshipType type : types) {
            if (options.isAllowParallelEdges()) {
                // parallel edges are wanted, but a second edge of the very same type between the very
                // same pair carries no information: distinct types between one pair are the point
                if (!used.contains(type.getIndex())) {
                    usable.add(type);
                }
            } else if (used.isEmpty()) {
                usable.add(type);
            }
        }
        if (usable.isEmpty()) {
            return false;
        }
        GraphRelationshipType chosen = Randomly.fromList(usable);
        addEdge(from.getId(), to.getId(), chosen.getIndex());
        return true;
    }

    /** @return true if an edge was created between the two nodes, in whichever direction is allowed */
    boolean connectEitherWay(GraphNode a, GraphNode b) {
        boolean forward = canConnect(a, b);
        boolean backward = canConnect(b, a);
        if (forward && backward) {
            return Rand.chance(0.5) ? connect(a, b) : connect(b, a);
        }
        if (forward) {
            return connect(a, b);
        }
        if (backward) {
            return connect(b, a);
        }
        return false;
    }

    GraphEdge addEdge(int fromId, int toId, int relationshipTypeIndex) {
        GraphEdge edge = new GraphEdge(nextEdgeId++, fromId, toId, relationshipTypeIndex);
        graph.addEdge(edge);
        lastEdge = edge;
        return edge;
    }

    /** @return the edge {@link #addEdge(int, int, int)} created last (used by the rewiring step) */
    GraphEdge getLastEdge() {
        return lastEdge;
    }

    void removeEdge(GraphEdge edge) {
        if (graph.getEdges().remove(edge)) {
            edgesRemoved = true;
        }
    }

    boolean hasSelfLoop(int nodeId) {
        for (GraphEdge edge : graph.getEdges()) {
            if (edge.getFromId() == nodeId && edge.getToId() == nodeId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called once the structure is complete: re-numbers the edges so that {@code id == index} holds
     * again, which is what {@link GraphModel#getEdge(int)} assumes.
     */
    void finish() {
        if (edgesRemoved) {
            reindexEdges(graph);
            edgesRemoved = false;
        }
    }

    /**
     * Rebuilds the edge list with fresh sequential ids. The generators and
     * {@link CardinalityEnforcer} both delete edges, which would otherwise leave holes in the id
     * sequence and make {@link GraphModel#getEdge(int)} (a plain list lookup) return the wrong edge.
     */
    static void reindexEdges(GraphModel graph) {
        List<GraphEdge> copy = new ArrayList<>(graph.getEdges());
        graph.getEdges().clear();
        int id = 0;
        for (GraphEdge edge : copy) {
            GraphEdge fresh = new GraphEdge(id++, edge.getFromId(), edge.getToId(), edge.getRelationshipTypeIndex());
            fresh.getProperties().putAll(edge.getProperties());
            graph.addEdge(fresh);
        }
    }
}
