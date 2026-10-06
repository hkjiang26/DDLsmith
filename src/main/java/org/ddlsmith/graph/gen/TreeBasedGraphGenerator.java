package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.Randomly;
import org.ddlsmith.graph.GraphNode;

/**
 * Tree-based generator: node {@code i} is attached to a random earlier node, which yields a random
 * spanning tree (and therefore a single connected component whenever the schema allows the labels
 * involved to be related at all). Afterwards the graph is densified with random extra edges until
 * the target edge count is reached, so the result is a tree plus {@code edgeFactor}-driven chords -
 * the structure that produces the many leaves and the few deep paths that stress the relational
 * mapping in a different way than the uniform generator does.
 *
 * <p>A node whose label cannot be related to any earlier node in either direction stays
 * disconnected; that is reported by the component count rather than hidden.</p>
 */
public class TreeBasedGraphGenerator extends AbstractGraphGenerator {

    public static final String NAME = "tree";

    public TreeBasedGraphGenerator() {
        super(NAME);
    }

    @Override
    protected void build(GraphBuildContext context) {
        List<GraphNode> nodes = context.getNodes();
        for (int i = 1; i < nodes.size(); i++) {
            GraphNode node = nodes.get(i);
            List<GraphNode> candidates = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                if (context.canConnectEitherWay(nodes.get(j), node)) {
                    candidates.add(nodes.get(j));
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            GraphNode parent = Randomly.fromList(candidates);
            context.connectEitherWay(parent, node);
        }
        addExtraEdges(context);
    }
}
