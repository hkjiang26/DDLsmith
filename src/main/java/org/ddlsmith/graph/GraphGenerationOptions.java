package org.ddlsmith.graph;

/** Parameters that control the size and density of a generated graph. */
public class GraphGenerationOptions {

    private int minNodes = 6;
    private int maxNodes = 40;
    /** Target number of edges as a multiple of the number of nodes. */
    private double edgeFactor = 1.5;
    /** Probability of adding a self-loop to a node that has none. */
    private double selfLoopProbability = 0.08;
    /** Whether parallel edges (several distinct relationship types between the same pair) are wanted. */
    private boolean allowParallelEdges = true;
    /** Whether cardinality and participation constraints of the schema are enforced. */
    private boolean enforceCardinality = true;
    /** Size of the sliding window used by locality-aware generators, as a fraction of node count. */
    private double windowFraction = 0.25;

    public int getMinNodes() {
        return minNodes;
    }

    public GraphGenerationOptions setMinNodes(int minNodes) {
        this.minNodes = minNodes;
        return this;
    }

    public int getMaxNodes() {
        return maxNodes;
    }

    public GraphGenerationOptions setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
        return this;
    }

    public double getEdgeFactor() {
        return edgeFactor;
    }

    public GraphGenerationOptions setEdgeFactor(double edgeFactor) {
        this.edgeFactor = edgeFactor;
        return this;
    }

    public double getSelfLoopProbability() {
        return selfLoopProbability;
    }

    public GraphGenerationOptions setSelfLoopProbability(double selfLoopProbability) {
        this.selfLoopProbability = selfLoopProbability;
        return this;
    }

    public boolean isAllowParallelEdges() {
        return allowParallelEdges;
    }

    public GraphGenerationOptions setAllowParallelEdges(boolean allowParallelEdges) {
        this.allowParallelEdges = allowParallelEdges;
        return this;
    }

    public boolean isEnforceCardinality() {
        return enforceCardinality;
    }

    public GraphGenerationOptions setEnforceCardinality(boolean enforceCardinality) {
        this.enforceCardinality = enforceCardinality;
        return this;
    }

    public double getWindowFraction() {
        return windowFraction;
    }

    public GraphGenerationOptions setWindowFraction(double windowFraction) {
        this.windowFraction = windowFraction;
        return this;
    }
}
