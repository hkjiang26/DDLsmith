package org.ddlsmith.graph;

/**
 * A node label of the base graph, i.e. the "type" of a graph node. Every label becomes one entity of
 * the ER diagram.
 *
 * <p>A label carries no attributes: which attributes it has in a given database is decided by the
 * {@link GraphFilling}, per database. The base graph only says that the label exists.</p>
 */
public class GraphLabel {

    private final int index;
    private final String name;

    public GraphLabel(int index, String name) {
        this.index = index;
        this.name = name;
    }

    public int getIndex() {
        return index;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
