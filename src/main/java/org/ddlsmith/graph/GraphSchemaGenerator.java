package org.ddlsmith.graph;

/**
 * Generates the schema of a graph: node labels with property slots, and relationship types with
 * cardinalities, participation constraints and property slots.
 *
 * <p>This is the first step of the DDLsmith pipeline and plays the role of GDsmith's Cypher schema
 * generator.</p>
 */
public interface GraphSchemaGenerator {

    GraphSchema generate();
}
