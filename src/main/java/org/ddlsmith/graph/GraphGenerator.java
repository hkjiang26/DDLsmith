package org.ddlsmith.graph;

/**
 * Generates a random complex graph which is then interpreted as an ER diagram.
 *
 * <p>Different generators produce structurally different graphs (uniform random, local clusters,
 * trees, scale-free hubs, small worlds). Since the structure of the graph determines the shape of
 * the resulting relational schema, mixing generators is what makes the generated DDL diverse.</p>
 */
public interface GraphGenerator {

    /** The name of the generator, used on the command line and in statistics. */
    String getName();

    /**
     * Generates a graph instance of the given schema.
     *
     * <p>Implementations must satisfy the cardinality and participation constraints of
     * {@link GraphRelationshipType} when {@link GraphGenerationOptions#isEnforceCardinality()} is
     * set, and must fill every node and edge property according to its presence probability.</p>
     */
    GraphModel generate(GraphSchema schema, GraphGenerationOptions options);
}
