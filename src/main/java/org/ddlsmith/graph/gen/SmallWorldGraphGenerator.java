package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.Randomly;
import org.ddlsmith.graph.GraphEdge;
import org.ddlsmith.graph.GraphNode;

/**
 * Watts–Strogatz style generator: a ring lattice in which every node is joined to its 2..4 nearest
 * neighbours along the ring, after which each lattice edge is rewired to a random other node with
 * probability 0.1. The result keeps the short average path length of a ring while the rewired edges
 * provide the shortcuts of a small world - a structure with many short cycles, which the relational
 * mapping sees as chains of mutual foreign keys.
 */
public class SmallWorldGraphGenerator extends AbstractGraphGenerator {

    public static final String NAME = "smallworld";
    private static final double REWIRE_PROBABILITY = 0.1;

    public SmallWorldGraphGenerator() {
        super(NAME);
    }

    @Override
    protected void build(GraphBuildContext context) {
        List<GraphNode> nodes = context.getNodes();
        int nodeCount = nodes.size();
        if (nodeCount < 3) {
            if (nodeCount == 2) {
                context.connectEitherWay(nodes.get(0), nodes.get(1));
            }
            return;
        }
        int neighbours = Randomly.getInteger(2, 5);
        int half = Math.max(1, neighbours / 2);
        List<GraphEdge> lattice = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            for (int distance = 1; distance <= half; distance++) {
                GraphNode from = nodes.get(i);
                GraphNode to = nodes.get((i + distance) % nodeCount);
                if (from == to) {
                    continue;
                }
                if (context.connectEitherWay(from, to)) {
                    lattice.add(context.getLastEdge());
                }
            }
        }
        for (GraphEdge edge : lattice) {
            if (Rand.chance(REWIRE_PROBABILITY)) {
                rewire(context, edge);
            }
        }
        // no densification step: the ring lattice (plus its rewired shortcuts) *is* the structure,
        // and letting it stay sparse is what makes this generator different from the others
    }

    /** Replaces the target of {@code edge} with a random node the schema allows. */
    private void rewire(GraphBuildContext context, GraphEdge edge) {
        List<GraphNode> nodes = context.getNodes();
        GraphNode from = nodes.get(edge.getFromId());
        List<GraphNode> candidates = new ArrayList<>();
        for (GraphNode candidate : nodes) {
            if (candidate.getId() == edge.getFromId() || candidate.getId() == edge.getToId()) {
                continue;
            }
            if (context.canConnect(from, candidate)) {
                candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        GraphNode to = Randomly.fromList(candidates);
        context.removeEdge(edge);
        context.connect(from, to);
    }
}
