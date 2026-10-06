package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.graph.GraphNode;

/**
 * Barabási–Albert style generator: the nodes are added one by one and each new node attaches to
 * 1..3 already present nodes, chosen with a probability proportional to their current degree (plus
 * one, so that isolated nodes can still be picked). A few nodes end up with a very high degree -
 * hubs - while most keep a degree of one or two, which is the scale-free shape that produces the
 * "many relationships attached to one entity" cases of the relational schema.
 *
 * <p>Only nodes the schema can actually relate to the new node (in either direction) enter the
 * candidate pool.</p>
 */
public class PreferentialAttachmentGraphGenerator extends AbstractGraphGenerator {

    public static final String NAME = "preferential";
    private static final int MIN_ATTACHMENTS = 1;
    private static final int MAX_ATTACHMENTS = 3;

    public PreferentialAttachmentGraphGenerator() {
        super(NAME);
    }

    @Override
    protected void build(GraphBuildContext context) {
        List<GraphNode> nodes = context.getNodes();
        if (nodes.size() > 1) {
            context.connectEitherWay(nodes.get(0), nodes.get(1));
        }
        for (int i = 2; i < nodes.size(); i++) {
            GraphNode node = nodes.get(i);
            int attachments = Randomly.getInteger(MIN_ATTACHMENTS, MAX_ATTACHMENTS + 1);
            Set<GraphNode> chosen = new LinkedHashSet<>();
            for (int a = 0; a < attachments; a++) {
                List<GraphNode> candidates = new ArrayList<>();
                long totalWeight = 0;
                for (int j = 0; j < i; j++) {
                    GraphNode candidate = nodes.get(j);
                    if (chosen.contains(candidate) || !context.canConnectEitherWay(candidate, node)) {
                        continue;
                    }
                    candidates.add(candidate);
                    totalWeight += context.getGraph().getDegree(candidate.getId()) + 1L;
                }
                if (candidates.isEmpty()) {
                    break;
                }
                GraphNode picked = pickWeighted(context, candidates, totalWeight);
                chosen.add(picked);
                context.connectEitherWay(picked, node);
            }
        }
        // no densification step: the attachment rule (1..3 edges per new node, degree-proportional)
        // already determines the density, and leaving it alone keeps the hub profile visible
    }

    /** @return a candidate drawn with probability proportional to {@code degree + 1} */
    private GraphNode pickWeighted(GraphBuildContext context, List<GraphNode> candidates, long totalWeight) {
        long draw = Randomly.getLong(0, totalWeight);
        long cumulative = 0;
        for (GraphNode candidate : candidates) {
            cumulative += context.getGraph().getDegree(candidate.getId()) + 1L;
            if (draw < cumulative) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }
}
