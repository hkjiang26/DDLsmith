package org.ddlsmith.graph;

import org.ddlsmith.common.schema.DataType;

/**
 * A property slot of a graph label or relationship type. The graph generator fills these with
 * values; the ER mapper turns them into the attributes of an entity or relationship.
 */
public class GraphProperty {

    private final String key;
    private final DataType type;
    /** Probability that the property is present on a given node/edge (1.0 = mandatory). */
    private final double presenceProbability;

    public GraphProperty(String key, DataType type, double presenceProbability) {
        this.key = key;
        this.type = type;
        this.presenceProbability = presenceProbability;
    }

    public String getKey() {
        return key;
    }

    public DataType getType() {
        return type;
    }

    public double getPresenceProbability() {
        return presenceProbability;
    }

    public boolean isMandatory() {
        return presenceProbability >= 1.0;
    }

    @Override
    public String toString() {
        return key + ":" + type.getName() + "(" + presenceProbability + ")";
    }
}
