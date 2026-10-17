package org.ddlsmith.graph;

import java.util.HashMap;
import java.util.Map;

/** A directed edge of the generated graph. Becomes a relationship of the ER diagram. */
public class GraphEdge {

    private final int id;
    private final int fromId;
    private final int toId;
    private final int relationshipTypeIndex;
    private final Map<String, Object> properties = new HashMap<>();

    public GraphEdge(int id, int fromId, int toId, int relationshipTypeIndex) {
        this.id = id;
        this.fromId = fromId;
        this.toId = toId;
        this.relationshipTypeIndex = relationshipTypeIndex;
    }

    public int getId() {
        return id;
    }

    public int getFromId() {
        return fromId;
    }

    public int getToId() {
        return toId;
    }

    public int getRelationshipTypeIndex() {
        return relationshipTypeIndex;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public GraphEdge putProperty(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }

    public boolean isSelfLoop() {
        return fromId == toId;
    }

    @Override
    public String toString() {
        return "e" + id + ":R" + relationshipTypeIndex + "(n" + fromId + "->n" + toId + ")"
                + GraphValues.format(properties);
    }
}
