package org.ddlsmith.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * A node label of the graph schema, i.e. the "type" of a graph node. Every label becomes one
 * entity of the ER diagram.
 */
public class GraphLabel {

    private final int index;
    private final String name;
    private final List<GraphProperty> properties = new ArrayList<>();

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

    public List<GraphProperty> getProperties() {
        return properties;
    }

    public GraphLabel addProperty(GraphProperty property) {
        properties.add(property);
        return this;
    }

    @Override
    public String toString() {
        return name + properties;
    }
}
