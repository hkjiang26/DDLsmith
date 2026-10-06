package org.ddlsmith.graph;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.Participation;

/**
 * A relationship type of the graph schema, i.e. the "type" of a graph edge.
 *
 * <p>Cardinalities are stated from the perspective of the classic (min,max) ER notation:</p>
 * <ul>
 *   <li>{@code fromCardinality} is the maximum number of <em>from</em> instances that may be
 *       related to one <em>to</em> instance. {@code ONE} therefore means that every node of the
 *       target label has at most one incoming edge of this type.</li>
 *   <li>{@code toCardinality} is the maximum number of <em>to</em> instances that may be related to
 *       one <em>from</em> instance. {@code ONE} means that every node of the source label has at
 *       most one outgoing edge of this type.</li>
 *   <li>{@code fromParticipation} is {@code TOTAL} when every node of the source label must have at
 *       least one outgoing edge of this type.</li>
 * </ul>
 *
 * <p>The generator enforces these constraints structurally while it builds the graph, so that the
 * resulting graph really is a valid instance of the ER diagram.</p>
 */
public class GraphRelationshipType {

    private final int index;
    private final String name;
    private final int fromLabelIndex;
    private final int toLabelIndex;
    private Cardinality fromCardinality = Cardinality.MANY;
    private Cardinality toCardinality = Cardinality.MANY;
    private Participation fromParticipation = Participation.PARTIAL;
    private Participation toParticipation = Participation.PARTIAL;
    private final List<GraphProperty> properties = new ArrayList<>();

    public GraphRelationshipType(int index, String name, int fromLabelIndex, int toLabelIndex) {
        this.index = index;
        this.name = name;
        this.fromLabelIndex = fromLabelIndex;
        this.toLabelIndex = toLabelIndex;
    }

    public int getIndex() {
        return index;
    }

    public String getName() {
        return name;
    }

    public int getFromLabelIndex() {
        return fromLabelIndex;
    }

    public int getToLabelIndex() {
        return toLabelIndex;
    }

    public Cardinality getFromCardinality() {
        return fromCardinality;
    }

    public GraphRelationshipType setFromCardinality(Cardinality fromCardinality) {
        this.fromCardinality = fromCardinality;
        return this;
    }

    public Cardinality getToCardinality() {
        return toCardinality;
    }

    public GraphRelationshipType setToCardinality(Cardinality toCardinality) {
        this.toCardinality = toCardinality;
        return this;
    }

    public Participation getFromParticipation() {
        return fromParticipation;
    }

    public GraphRelationshipType setFromParticipation(Participation fromParticipation) {
        this.fromParticipation = fromParticipation;
        return this;
    }

    public Participation getToParticipation() {
        return toParticipation;
    }

    public GraphRelationshipType setToParticipation(Participation toParticipation) {
        this.toParticipation = toParticipation;
        return this;
    }

    public List<GraphProperty> getProperties() {
        return properties;
    }

    public GraphRelationshipType addProperty(GraphProperty property) {
        properties.add(property);
        return this;
    }

    public boolean isRecursive() {
        return fromLabelIndex == toLabelIndex;
    }

    /** @return true if the relationship is many-to-many */
    public boolean isManyToMany() {
        return fromCardinality == Cardinality.MANY && toCardinality == Cardinality.MANY;
    }

    /** @return true if the relationship is one-to-one */
    public boolean isOneToOne() {
        return fromCardinality == Cardinality.ONE && toCardinality == Cardinality.ONE;
    }

    @Override
    public String toString() {
        return name + "(" + fromLabelIndex + " " + fromParticipation + "/" + fromCardinality + " -> " + toLabelIndex
                + " " + toParticipation + "/" + toCardinality + ")";
    }
}
