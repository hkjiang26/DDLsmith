package org.ddlsmith.graph.gen;

import java.util.List;

import org.ddlsmith.graph.GraphNode;

/**
 * Erdős–Rényi style generator: every ordered pair of nodes that the schema allows to be connected
 * gets an edge with the same probability. The probability is calibrated so that the expected number
 * of edges is {@code edgeFactor * nodeCount} (the target edge count), which makes the density
 * comparable with the structurally biased generators.
 *
 * <p>Pairs for which no relationship type exists are skipped, so the effective density also
 * depends on how many of the label pairs the schema relates.</p>
 */
public class RandomGraphGenerator extends AbstractGraphGenerator {

    public static final String NAME = "random";

    public RandomGraphGenerator() {
        super(NAME);
    }

    @Override
    protected void build(GraphBuildContext context) {
        List<GraphNode> nodes = context.getNodes();
        int compatiblePairs = 0;
        for (GraphNode from : nodes) {
            for (GraphNode to : nodes) {
                if (from != to && context.canConnect(from, to)) {
                    compatiblePairs++;
                }
            }
        }
        if (compatiblePairs == 0) {
            return;
        }
        double probability = Math.min(1.0, context.getTargetEdgeCount() / (double) compatiblePairs);
        for (GraphNode from : nodes) {
            for (GraphNode to : nodes) {
                if (from == to) {
                    continue;
                }
                if (Rand.chance(probability)) {
                    context.connect(from, to);
                }
            }
        }
    }
}
