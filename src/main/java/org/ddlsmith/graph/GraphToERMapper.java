package org.ddlsmith.graph;

import org.ddlsmith.ermodel.ERDiagram;

/**
 * Interprets a generated base graph as an ER diagram: every node becomes an entity, every edge
 * becomes a relationship, and the structural features of the graph (self-loops, parallel edges, hubs,
 * leaves) plus the attributes the database filled the graph with drive the ER features that make
 * relational mapping non-trivial (weak entities, recursive relationships, generalization hierarchies,
 * multi-valued attributes).
 */
public interface GraphToERMapper {

    /**
     * @param graph the generated base graph instance (nodes and edges)
     * @param schema the base graph schema the instance belongs to (labels and relationship types)
     * @param filling the attributes this database gave the labels and relationship types
     */
    ERDiagram map(GraphModel graph, GraphSchema schema, GraphFilling filling);
}
