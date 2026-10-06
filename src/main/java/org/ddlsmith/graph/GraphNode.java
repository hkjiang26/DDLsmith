package org.ddlsmith.graph;

import java.util.HashMap;
import java.util.Map;

/** A node of the generated graph. Becomes an entity of the ER diagram. */
public class GraphNode {

    private final int id;
    private int labelIndex;
    private final Map<String, Object> properties = new HashMap<>();

    public GraphNode(int id, int labelIndex) {
        this.id = id;
        this.labelIndex = labelIndex;
    }

    public int getId() {
        return id;
    }

    public int getLabelIndex() {
        return labelIndex;
    }

    public GraphNode setLabelIndex(int labelIndex) {
        this.labelIndex = labelIndex;
        return this;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public GraphNode putProperty(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }

    @Override
    public String toString() {
        return "n" + id + ":L" + labelIndex + properties;
    }
}
