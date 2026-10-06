package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.Randomly;
import org.ddlsmith.graph.GraphNode;

/**
 * Locality-aware generator in the spirit of GDsmith: the nodes are visited in index order and each
 * one is connected to the nodes inside a forward window of
 * {@code max(2, round(windowFraction * nodeCount))} positions, which produces locally dense
 * clusters instead of a uniform sprinkle of edges. About 10% of the target edge count is added as
 * long-range edges, so the clusters are not completely separated.
 *
 * <p>The window probability is calibrated against the number of compatible pairs actually inside
 * the windows, so the graph still reaches roughly the target edge count when the schema relates
 * only a few label pairs.</p>
 */
public class SlidingGraphGenerator extends AbstractGraphGenerator {

    public static final String NAME = "sliding";
    private static final double LONG_RANGE_SHARE = 0.1;

    public SlidingGraphGenerator() {
        super(NAME);
    }

    @Override
    protected void build(GraphBuildContext context) {
        List<GraphNode> nodes = context.getNodes();
        int nodeCount = nodes.size();
        if (nodeCount < 2) {
            return;
        }
        int window = Math.max(2, (int) Math.round(context.getOptions().getWindowFraction() * nodeCount));
        int windowPairs = 0;
        int compatiblePairs = 0;
        for (int i = 0; i < nodeCount; i++) {
            for (int j = i + 1; j <= i + window && j < nodeCount; j++) {
                windowPairs++;
                if (context.canConnect(nodes.get(i), nodes.get(j)) || context.canConnect(nodes.get(j), nodes.get(i))) {
                    compatiblePairs++;
                }
            }
        }
        if (compatiblePairs > 0) {
            double probability = Math.min(1.0, context.getTargetEdgeCount() / (double) compatiblePairs);
            for (int i = 0; i < nodeCount; i++) {
                GraphNode from = nodes.get(i);
                for (int j = i + 1; j <= i + window && j < nodeCount; j++) {
                    if (Rand.chance(probability)) {
                        context.connectEitherWay(from, nodes.get(j));
                    }
                }
            }
        }
        addLongRangeEdges(context, window);
    }

    /** Adds the ~10% long-range edges that keep the clusters connected. */
    private void addLongRangeEdges(GraphBuildContext context, int window) {
        List<GraphNode> nodes = context.getNodes();
        int nodeCount = nodes.size();
        int longRange = Math.max(1, (int) Math.round(LONG_RANGE_SHARE * context.getTargetEdgeCount()));
        for (int i = 0; i < longRange; i++) {
            GraphNode from = Randomly.fromList(nodes);
            List<GraphNode> far = new ArrayList<>();
            for (int j = 0; j < nodeCount; j++) {
                int distance = Math.abs(j - from.getId());
                if (Math.min(distance, nodeCount - distance) > window) {
                    far.add(nodes.get(j));
                }
            }
            if (far.isEmpty()) {
                far = nodes;
            }
            context.connectEitherWay(from, Randomly.fromList(far));
        }
    }
}
