package org.ddlsmith.graph.gen;

import java.util.List;

import org.ddlsmith.Randomly;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphGenerator;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphNode;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;

/**
 * Common skeleton of the six graph generators.
 *
 * <p>{@link #generate(GraphSchema, GraphGenerationOptions)} is a template method: it sets up the
 * shared {@link GraphBuildContext} (node count, labels, lookup tables), lets the concrete generator
 * build its structure, adds the self-loops, enforces the cardinality constraints and finally fills
 * the properties. Subclasses only implement {@link #build(GraphBuildContext)}, i.e. the structural
 * rule that distinguishes them.</p>
 */
public abstract class AbstractGraphGenerator implements GraphGenerator {

    private final String name;

    protected AbstractGraphGenerator(String name) {
        this.name = name;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final GraphModel generate(GraphSchema schema, GraphGenerationOptions options) {
        GraphBuildContext context = new GraphBuildContext(schema, options);
        build(context);
        addSelfLoops(context);
        context.finish();
        if (options.isEnforceCardinality()) {
            CardinalityEnforcer.enforce(context.getGraph(), schema);
        }
        GraphPropertyFiller.fill(context.getGraph(), schema);
        context.getGraph().setGeneratorName(name);
        context.getGraph().setTargetEdgeCount(context.getTargetEdgeCount());
        return context.getGraph();
    }

    /** Builds the structure of the graph; edges must be created through the context. */
    protected abstract void build(GraphBuildContext context);

    /**
     * Adds the self-loop edges. The recursive types that were reserved for self-loops (see
     * {@link GraphBuildContext}) are instantiated here - all nodes when the type is total on either
     * side, otherwise most of them - and the remaining nodes get a self-loop with probability
     * {@link GraphGenerationOptions#getSelfLoopProbability()}.
     */
    private void addSelfLoops(GraphBuildContext context) {
        for (GraphRelationshipType type : context.getSchema().getRelationshipTypes()) {
            if (!context.isSelfLoopOnly(type)) {
                continue;
            }
            List<GraphNode> nodes = context.nodesOfLabel(type.getFromLabelIndex());
            boolean total = type.getFromParticipation() == Participation.TOTAL
                    || type.getToParticipation() == Participation.TOTAL;
            for (GraphNode node : nodes) {
                if (total || Rand.chance(0.6)) {
                    context.addEdge(node.getId(), node.getId(), type.getIndex());
                }
            }
        }
        double probability = context.getOptions().getSelfLoopProbability();
        for (GraphNode node : context.getNodes()) {
            if (context.hasSelfLoop(node.getId()) || !Rand.chance(probability)) {
                continue;
            }
            List<GraphRelationshipType> candidates = context.selfLoopTypes(node.getLabelIndex());
            if (candidates.isEmpty()) {
                continue;
            }
            context.addEdge(node.getId(), node.getId(), Randomly.fromList(candidates).getIndex());
        }
    }

    /**
     * Adds random edges until the graph reaches the target edge count, giving up after a bounded
     * number of attempts (the schema may simply offer too few compatible pairs).
     */
    protected final void addExtraEdges(GraphBuildContext context) {
        int missing = context.getTargetEdgeCount() - context.getGraph().getEdgeCount();
        if (missing <= 0 || context.getNodeCount() < 2) {
            return;
        }
        List<GraphNode> nodes = context.getNodes();
        int attempts = missing * 4;
        for (int i = 0; i < attempts && context.getGraph().getEdgeCount() < context.getTargetEdgeCount(); i++) {
            GraphNode from = Randomly.fromList(nodes);
            GraphNode to = Randomly.fromList(nodes);
            if (from == to) {
                continue;
            }
            context.connect(from, to);
        }
    }
}
