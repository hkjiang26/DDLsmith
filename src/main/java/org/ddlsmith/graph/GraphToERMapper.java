package org.ddlsmith.graph;

import org.ddlsmith.ermodel.ERDiagram;

/**
 * Interprets a generated graph as an ER diagram: every node becomes an entity, every edge becomes
 * a relationship, and the structural features of the graph (self-loops, parallel edges, hubs,
 * leaves) drive the ER features that make relational mapping non-trivial (weak entities,
 * recursive relationships, generalization hierarchies, multi-valued attributes).
 */
public interface GraphToERMapper {

    ERDiagram map(GraphModel graph, GraphSchema schema);
}
