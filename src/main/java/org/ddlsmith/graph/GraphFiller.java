package org.ddlsmith.graph;

/**
 * Decides the attributes of a base graph for one database.
 *
 * <p>This is the second half of the input generation and the reason the tool produces base graphs
 * only: {@link GraphSchemaGenerator} produces the topology (labels and relationship types),
 * {@link GraphGenerator} produces an instance of it (nodes and edges), and this stage fills that
 * graph with attributes. Because the filling is drawn per database, one base graph becomes many
 * different ER diagrams - with different attribute counts, domains, nullability, keys and
 * specialization links - instead of exactly one.</p>
 */
public interface GraphFiller {

    /** @return the attributes every label and relationship type of {@code schema} carries here */
    GraphFilling plan(GraphSchema schema);
}
