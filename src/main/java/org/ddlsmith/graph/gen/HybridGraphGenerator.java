package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.Randomly;
import org.ddlsmith.graph.GraphNode;

/**
 * Community-structure generator: the nodes are split into 2..4 contiguous regions and each region
 * is built with a randomly chosen local rule (a clique, a ring lattice, a star or a small tree), so
 * the regions are dense inside and sparse between. The regions are then joined by a few bridge
 * edges.
 *
 * <p>This produces the shape that stresses the relational mapping most: tightly coupled groups of
 * entities that reference each other, plus a small number of long-range foreign keys connecting
 * them - the case where the choice between "one table per entity" and "merge the relationship" has
 * the largest effect.</p>
 */
public class HybridGraphGenerator extends AbstractGraphGenerator {

    public static final String NAME = "hybrid";
    private static final int MIN_REGIONS = 2;
    private static final int MAX_REGIONS = 4;

    public HybridGraphGenerator() {
        super(NAME);
    }

    @Override
    protected void build(GraphBuildContext context) {
        List<GraphNode> nodes = context.getNodes();
        if (nodes.isEmpty()) {
            return;
        }
        int regionCount = Math.min(nodes.size(), Randomly.getInteger(MIN_REGIONS, MAX_REGIONS + 1));
        List<List<GraphNode>> regions = split(nodes, regionCount);
        for (List<GraphNode> region : regions) {
            buildRegion(context, region);
        }
        for (int a = 0; a < regions.size(); a++) {
            for (int b = a + 1; b < regions.size(); b++) {
                int bridges = 1 + Randomly.getInteger(0, 3);
                for (int i = 0; i < bridges; i++) {
                    context.connectEitherWay(Randomly.fromList(regions.get(a)), Randomly.fromList(regions.get(b)));
                }
            }
        }
        // no densification step: the communities and their bridges are the structure
    }

    private List<List<GraphNode>> split(List<GraphNode> nodes, int regionCount) {
        List<List<GraphNode>> regions = new ArrayList<>();
        int base = nodes.size() / regionCount;
        int remainder = nodes.size() % regionCount;
        int index = 0;
        for (int r = 0; r < regionCount; r++) {
            int size = base + (r < remainder ? 1 : 0);
            regions.add(new ArrayList<>(nodes.subList(index, index + size)));
            index += size;
        }
        return regions;
    }

    /** Builds one region with a randomly chosen local rule. */
    private void buildRegion(GraphBuildContext context, List<GraphNode> region) {
        int size = region.size();
        if (size < 2) {
            return;
        }
        switch (Randomly.getInteger(0, 4)) {
        case 0:
            buildClique(context, region);
            break;
        case 1:
            buildLattice(context, region);
            break;
        case 2:
            buildStar(context, region);
            break;
        default:
            buildTree(context, region);
            break;
        }
    }

    private void buildClique(GraphBuildContext context, List<GraphNode> region) {
        double probability = Math.min(1.0, 3.0 / (region.size() - 1));
        for (GraphNode from : region) {
            for (GraphNode to : region) {
                if (from != to && Rand.chance(probability)) {
                    context.connect(from, to);
                }
            }
        }
    }

    private void buildLattice(GraphBuildContext context, List<GraphNode> region) {
        int size = region.size();
        for (int i = 0; i < size; i++) {
            context.connectEitherWay(region.get(i), region.get((i + 1) % size));
            context.connectEitherWay(region.get(i), region.get((i + 2) % size));
        }
    }

    private void buildStar(GraphBuildContext context, List<GraphNode> region) {
        GraphNode center = Randomly.fromList(region);
        for (GraphNode node : region) {
            if (node != center) {
                context.connectEitherWay(center, node);
            }
        }
    }

    private void buildTree(GraphBuildContext context, List<GraphNode> region) {
        for (int i = 1; i < region.size(); i++) {
            context.connectEitherWay(Randomly.fromList(region.subList(0, i)), region.get(i));
        }
    }
}
