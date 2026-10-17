package org.ddlsmith.graph;

import org.ddlsmith.common.schema.DataType;

/**
 * One attribute slot a graph label or relationship type carries in a particular database.
 *
 * <p>A slot is created by the <em>filling</em> stage, not by the base graph: the base graph only
 * holds nodes and edges, and every database of a {@code --databases-per-graph} group decides on its
 * own how many slots each label and relationship type gets, which {@link DataType} each slot has,
 * how often it is present and whether it takes part in a key. The graph generator then fills these
 * slots with values; the ER mapper turns them into the attributes of an entity or relationship.</p>
 */
public class GraphProperty {

    /**
     * The role a slot plays in the key of its label. The filling decides this, so one base graph can
     * give a label a natural key in one database, a composite key in the next and an extra candidate
     * key in a third.
     */
    public enum KeyRole {
        /** Not part of any key. */
        NONE,
        /** Part of the primary key of its label. */
        PRIMARY,
        /** Part of an alternate (candidate) key of its label, mapped to a UNIQUE constraint. */
        ALTERNATE
    }

    private final String key;
    private final DataType type;
    /** Probability that the property is present on a given node/edge (1.0 = mandatory). */
    private final double presenceProbability;
    private KeyRole keyRole = KeyRole.NONE;

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

    public KeyRole getKeyRole() {
        return keyRole;
    }

    public GraphProperty setKeyRole(KeyRole keyRole) {
        this.keyRole = keyRole == null ? KeyRole.NONE : keyRole;
        return this;
    }

    public boolean isPrimaryKey() {
        return keyRole == KeyRole.PRIMARY;
    }

    public boolean isAlternateKey() {
        return keyRole == KeyRole.ALTERNATE;
    }

    @Override
    public String toString() {
        String role = keyRole == KeyRole.NONE ? "" : " " + keyRole;
        return key + ":" + type.getName() + "(" + presenceProbability + ")" + role;
    }
}
