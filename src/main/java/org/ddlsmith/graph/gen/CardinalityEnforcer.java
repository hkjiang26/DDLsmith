package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.graph.GraphEdge;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphNode;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;

/**
 * Makes a generated graph a valid instance of its schema: after {@link #enforce(GraphModel,
 * GraphSchema)} all cardinality and participation constraints of every
 * {@link GraphRelationshipType} hold, as checked by {@link #isValid(GraphModel, GraphSchema)}.
 *
 * <p>The constraints are the ones stated on {@link GraphRelationshipType}:</p>
 * <ul>
 *   <li>{@code fromCardinality == ONE}: every node of the <em>target</em> label has at most one
 *       incoming edge of the type;</li>
 *   <li>{@code toCardinality == ONE}: every node of the <em>source</em> label has at most one
 *       outgoing edge of the type;</li>
 *   <li>{@code fromParticipation == TOTAL}: every node of the source label has at least one
 *       outgoing edge of the type;</li>
 *   <li>{@code toParticipation == TOTAL}: every node of the target label has at least one incoming
 *       edge of the type.</li>
 * </ul>
 *
 * <p><b>How it works.</b> Edges that break a {@code ONE} cardinality are dropped first (one edge
 * per over-subscribed node is kept, chosen with {@link Randomly}). Then the missing participation
 * edges are added. Adding an edge can be impossible even though a solution exists, for example
 * when every candidate partner is already taken but some other source node owns two edges of the
 * type; in that case an edge is <em>transferred</em> (its source is changed, or its target is
 * re-pointed) instead of being added, which keeps the donor's own participation intact. When even
 * that is impossible the schema cannot be satisfied with the node counts at hand - the target label
 * simply holds fewer nodes than the source label - and a node of the missing label is added. That
 * last resort is what makes validity unconditional, which the ER and relational mappers rely on;
 * the graph generators avoid it in practice by giving every label the same number of nodes.</p>
 *
 * <p>Every constraint is local to one relationship type (edges of different types never compete),
 * so enforcing the types one after another in any order yields a graph satisfying all of them. The
 * outer loop exists only for the node-adding case, where a fresh node can create a new obligation
 * for a type that was already processed; it runs at most {@link #MAX_REPAIR_ROUNDS} times, so this
 * method always terminates.</p>
 *
 * <p>The methods are static: {@code CardinalityEnforcer.enforce(graph, schema)} and
 * {@code CardinalityEnforcer.isValid(graph, schema)} can be called without an instance.</p>
 */
public final class CardinalityEnforcer {

    /** Bound on the repair rounds; only reached when nodes have to be added. */
    private static final int MAX_REPAIR_ROUNDS = 8;

    private CardinalityEnforcer() {
    }

    /** Records what one enforcement pass changed, so the edge ids can be renumbered afterwards. */
    private static final class EditLog {
        private boolean removedEdges;
        private boolean addedNodes;
    }

    /* ------------------------------------------------------------------ enforcement */

    /** Post-processes {@code graph} so that every constraint of {@code schema} holds. */
    public static void enforce(GraphModel graph, GraphSchema schema) {
        if (graph.getNodeCount() == 0 || schema.getRelationshipTypeCount() == 0) {
            return;
        }
        EditLog log = new EditLog();
        int round = 0;
        do {
            for (GraphRelationshipType type : schema.getRelationshipTypes()) {
                removeExcessEdges(graph, type, log);
            }
            log.addedNodes = false;
            for (GraphRelationshipType type : schema.getRelationshipTypes()) {
                repairParticipation(graph, type, log);
            }
            round++;
        } while (log.addedNodes && round < MAX_REPAIR_ROUNDS);
        if (log.removedEdges) {
            GraphBuildContext.reindexEdges(graph);
        }
    }

    /** Drops the edges of one relationship type that exceed a {@code ONE} cardinality. */
    private static void removeExcessEdges(GraphModel graph, GraphRelationshipType type, EditLog log) {
        List<GraphEdge> candidates = new ArrayList<>();
        for (GraphEdge edge : graph.getEdges()) {
            if (edge.getRelationshipTypeIndex() == type.getIndex() && labelsMatch(graph, edge, type)) {
                candidates.add(edge);
            }
        }
        if (candidates.size() < 2) {
            return;
        }
        Set<GraphEdge> keep = new HashSet<>(candidates);
        if (type.getFromCardinality() == Cardinality.ONE) {
            // at most one incoming edge of this type per target node
            for (List<GraphEdge> group : groupBy(candidates, true).values()) {
                keepOne(keep, group);
            }
        }
        if (type.getToCardinality() == Cardinality.ONE) {
            // at most one outgoing edge of this type per source node
            for (List<GraphEdge> group : groupBy(candidates, false).values()) {
                keepOne(keep, group);
            }
        }
        Set<GraphEdge> doomed = new HashSet<>(candidates);
        doomed.removeAll(keep);
        if (doomed.isEmpty()) {
            return;
        }
        graph.getEdges().removeIf(doomed::contains);
        log.removedEdges = true;
    }

    private static Map<Integer, List<GraphEdge>> groupBy(List<GraphEdge> edges, boolean byTarget) {
        Map<Integer, List<GraphEdge>> groups = new HashMap<>();
        for (GraphEdge edge : edges) {
            int key = byTarget ? edge.getToId() : edge.getFromId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
        }
        return groups;
    }

    private static void keepOne(Set<GraphEdge> keep, List<GraphEdge> group) {
        if (group.size() < 2) {
            return;
        }
        GraphEdge survivor = Randomly.fromList(group);
        for (GraphEdge edge : group) {
            if (edge != survivor) {
                keep.remove(edge);
            }
        }
    }

    /**
     * Adds (or transfers) the edges a {@code TOTAL} participation asks for. A fresh node can create
     * a new obligation for a relationship type that was already processed, so the caller repeats the
     * whole pass while {@code log.addedNodes} is set.
     */
    private static void repairParticipation(GraphModel graph, GraphRelationshipType type, EditLog log) {
        Map<Integer, List<GraphEdge>> incoming = new HashMap<>();
        Map<Integer, List<GraphEdge>> outgoing = new HashMap<>();
        for (GraphEdge edge : graph.getEdges()) {
            if (edge.getRelationshipTypeIndex() != type.getIndex() || !labelsMatch(graph, edge, type)) {
                continue;
            }
            register(outgoing, edge.getFromId(), edge);
            register(incoming, edge.getToId(), edge);
        }
        if (type.getFromParticipation() == Participation.TOTAL) {
            for (GraphNode source : graph.getNodesOfLabel(type.getFromLabelIndex())) {
                if (outgoing.containsKey(source.getId())) {
                    continue;
                }
                ensureOutgoing(graph, type, source, incoming, outgoing, log);
            }
        }
        if (type.getToParticipation() == Participation.TOTAL) {
            for (GraphNode target : graph.getNodesOfLabel(type.getToLabelIndex())) {
                if (incoming.containsKey(target.getId())) {
                    continue;
                }
                ensureIncoming(graph, type, target, incoming, outgoing, log);
            }
        }
    }

    /** Gives {@code source} an outgoing edge of {@code type} without breaking a {@code ONE} cap. */
    private static void ensureOutgoing(GraphModel graph, GraphRelationshipType type, GraphNode source,
            Map<Integer, List<GraphEdge>> incoming, Map<Integer, List<GraphEdge>> outgoing, EditLog log) {
        List<GraphNode> targets = graph.getNodesOfLabel(type.getToLabelIndex());
        if (targets.isEmpty() && !type.isRecursive()) {
            // the target label has no node at all: only an added node can carry the edge
            GraphNode added = addNode(graph, type.getToLabelIndex(), log);
            addEdge(graph, type, source.getId(), added.getId(), incoming, outgoing);
            return;
        }
        boolean singleIncoming = type.getFromCardinality() == Cardinality.ONE;
        if (!singleIncoming) {
            // no cap on how many sources may point at one target
            GraphNode target = Randomly.fromList(targets);
            addEdge(graph, type, source.getId(), target.getId(), incoming, outgoing);
            return;
        }
        List<GraphNode> free = new ArrayList<>();
        for (GraphNode target : targets) {
            if (!incoming.containsKey(target.getId())) {
                free.add(target);
            }
        }
        if (!free.isEmpty()) {
            GraphNode target = Randomly.fromList(free);
            addEdge(graph, type, source.getId(), target.getId(), incoming, outgoing);
            return;
        }
        // Every target already has an incoming edge. When some other source owns two edges of this
        // type it can spare one (it keeps at least one, so its own TOTAL participation survives);
        // transferring instead of adding leaves the target's in-degree untouched.
        List<GraphNode> donors = new ArrayList<>();
        for (GraphNode candidate : graph.getNodesOfLabel(type.getFromLabelIndex())) {
            List<GraphEdge> owned = outgoing.get(candidate.getId());
            if (owned != null && owned.size() >= 2) {
                donors.add(candidate);
            }
        }
        if (!donors.isEmpty()) {
            GraphNode donor = Randomly.fromList(donors);
            GraphEdge donated = Randomly.fromList(outgoing.get(donor.getId()));
            int targetId = donated.getToId();
            removeEdge(graph, donated, incoming, outgoing, log);
            addEdge(graph, type, source.getId(), targetId, incoming, outgoing);
            return;
        }
        // No solution exists for these node counts: the target label holds fewer nodes than the
        // source label, and every target node can accept only one incoming edge. Adding a target
        // node is the only repair that keeps the graph a valid instance of the schema. (Recursive
        // types never get here: with from == to the counts are equal by construction, so a donor or
        // a free target always exists.)
        GraphNode added = addNode(graph, type.getToLabelIndex(), log);
        addEdge(graph, type, source.getId(), added.getId(), incoming, outgoing);
    }

    /** Gives {@code target} an incoming edge of {@code type} without breaking a {@code ONE} cap. */
    private static void ensureIncoming(GraphModel graph, GraphRelationshipType type, GraphNode target,
            Map<Integer, List<GraphEdge>> incoming, Map<Integer, List<GraphEdge>> outgoing, EditLog log) {
        List<GraphNode> sources = graph.getNodesOfLabel(type.getFromLabelIndex());
        if (sources.isEmpty() && !type.isRecursive()) {
            GraphNode added = addNode(graph, type.getFromLabelIndex(), log);
            addEdge(graph, type, added.getId(), target.getId(), incoming, outgoing);
            return;
        }
        boolean singleOutgoing = type.getToCardinality() == Cardinality.ONE;
        if (!singleOutgoing) {
            // no cap on how many targets one source may reach
            GraphNode source = Randomly.fromList(sources);
            addEdge(graph, type, source.getId(), target.getId(), incoming, outgoing);
            return;
        }
        List<GraphNode> free = new ArrayList<>();
        for (GraphNode source : sources) {
            if (!outgoing.containsKey(source.getId())) {
                free.add(source);
            }
        }
        if (!free.isEmpty()) {
            GraphNode source = Randomly.fromList(free);
            addEdge(graph, type, source.getId(), target.getId(), incoming, outgoing);
            return;
        }
        // No source can afford a new edge. Take one away from a target that holds several and point
        // it at the starved target: the donor target keeps at least one incoming edge and the source
        // keeps its single outgoing edge, so neither of them regresses.
        List<GraphNode> donors = new ArrayList<>();
        for (GraphNode candidate : graph.getNodesOfLabel(type.getToLabelIndex())) {
            List<GraphEdge> owned = incoming.get(candidate.getId());
            if (candidate.getId() != target.getId() && owned != null && owned.size() >= 2) {
                donors.add(candidate);
            }
        }
        if (!donors.isEmpty()) {
            GraphNode donor = Randomly.fromList(donors);
            GraphEdge donated = Randomly.fromList(incoming.get(donor.getId()));
            int sourceId = donated.getFromId();
            removeEdge(graph, donated, incoming, outgoing, log);
            addEdge(graph, type, sourceId, target.getId(), incoming, outgoing);
            return;
        }
        // see ensureOutgoing: only an added source node can satisfy this constraint
        GraphNode added = addNode(graph, type.getFromLabelIndex(), log);
        addEdge(graph, type, added.getId(), target.getId(), incoming, outgoing);
    }

    /** Adds a node of the given label; the property filler will fill it like any other node. */
    private static GraphNode addNode(GraphModel graph, int labelIndex, EditLog log) {
        GraphNode node = new GraphNode(graph.getNodeCount(), labelIndex);
        graph.addNode(node);
        log.addedNodes = true;
        return node;
    }

    private static void addEdge(GraphModel graph, GraphRelationshipType type, int fromId, int toId,
            Map<Integer, List<GraphEdge>> incoming, Map<Integer, List<GraphEdge>> outgoing) {
        GraphEdge edge = new GraphEdge(graph.getEdgeCount(), fromId, toId, type.getIndex());
        graph.addEdge(edge);
        register(outgoing, fromId, edge);
        register(incoming, toId, edge);
    }

    private static void removeEdge(GraphModel graph, GraphEdge edge, Map<Integer, List<GraphEdge>> incoming,
            Map<Integer, List<GraphEdge>> outgoing, EditLog log) {
        graph.getEdges().remove(edge);
        log.removedEdges = true;
        unregister(outgoing, edge.getFromId(), edge);
        unregister(incoming, edge.getToId(), edge);
    }

    private static void register(Map<Integer, List<GraphEdge>> map, int nodeId, GraphEdge edge) {
        map.computeIfAbsent(nodeId, key -> new ArrayList<>()).add(edge);
    }

    private static void unregister(Map<Integer, List<GraphEdge>> map, int nodeId, GraphEdge edge) {
        List<GraphEdge> owned = map.get(nodeId);
        if (owned == null) {
            return;
        }
        owned.remove(edge);
        if (owned.isEmpty()) {
            map.remove(nodeId);
        }
    }

    /* ------------------------------------------------------------------ validation */

    /**
     * @return true if {@code graph} satisfies every cardinality and participation constraint of
     *         {@code schema} and every edge uses a relationship type whose labels match its
     *         endpoints. This is exact: it is the property {@link #enforce(GraphModel, GraphSchema)}
     *         tries to establish, and the property the ER mapper relies on.
     */
    public static boolean isValid(GraphModel graph, GraphSchema schema) {
        int nodeCount = graph.getNodeCount();
        for (GraphEdge edge : graph.getEdges()) {
            if (edge.getFromId() < 0 || edge.getFromId() >= nodeCount || edge.getToId() < 0
                    || edge.getToId() >= nodeCount) {
                return false;
            }
            if (edge.getRelationshipTypeIndex() < 0 || edge.getRelationshipTypeIndex() >= schema.getRelationshipTypeCount()) {
                return false;
            }
            GraphRelationshipType type = schema.getRelationshipType(edge.getRelationshipTypeIndex());
            if (graph.getNode(edge.getFromId()).getLabelIndex() != type.getFromLabelIndex()
                    || graph.getNode(edge.getToId()).getLabelIndex() != type.getToLabelIndex()) {
                return false;
            }
        }
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            Map<Integer, Integer> incoming = new HashMap<>();
            Map<Integer, Integer> outgoing = new HashMap<>();
            for (GraphEdge edge : graph.getEdges()) {
                if (edge.getRelationshipTypeIndex() != type.getIndex()) {
                    continue;
                }
                outgoing.merge(edge.getFromId(), 1, Integer::sum);
                incoming.merge(edge.getToId(), 1, Integer::sum);
            }
            for (GraphNode node : graph.getNodesOfLabel(type.getToLabelIndex())) {
                int count = incoming.getOrDefault(node.getId(), 0);
                if (type.getFromCardinality() == Cardinality.ONE && count > 1) {
                    return false;
                }
                if (type.getToParticipation() == Participation.TOTAL && count == 0) {
                    return false;
                }
            }
            for (GraphNode node : graph.getNodesOfLabel(type.getFromLabelIndex())) {
                int count = outgoing.getOrDefault(node.getId(), 0);
                if (type.getToCardinality() == Cardinality.ONE && count > 1) {
                    return false;
                }
                if (type.getFromParticipation() == Participation.TOTAL && count == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean labelsMatch(GraphModel graph, GraphEdge edge, GraphRelationshipType type) {
        if (edge.getFromId() < 0 || edge.getFromId() >= graph.getNodeCount() || edge.getToId() < 0
                || edge.getToId() >= graph.getNodeCount()) {
            return false;
        }
        return graph.getNode(edge.getFromId()).getLabelIndex() == type.getFromLabelIndex()
                && graph.getNode(edge.getToId()).getLabelIndex() == type.getToLabelIndex();
    }
}
