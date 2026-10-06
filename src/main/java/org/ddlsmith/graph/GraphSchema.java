package org.ddlsmith.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * The schema of a randomly generated graph: which node labels and relationship types exist, which
 * properties they carry, and which cardinality constraints the generated graph must satisfy.
 *
 * <p>This plays the role that the Cypher schema plays in GDsmith: it is generated first, and both
 * the graph instance and the ER diagram are derived from it.</p>
 */
public class GraphSchema {

    private final List<GraphLabel> labels = new ArrayList<>();
    private final List<GraphRelationshipType> relationshipTypes = new ArrayList<>();

    public List<GraphLabel> getLabels() {
        return labels;
    }

    public GraphSchema addLabel(GraphLabel label) {
        labels.add(label);
        return this;
    }

    public GraphLabel getLabel(int index) {
        return labels.get(index);
    }

    public List<GraphRelationshipType> getRelationshipTypes() {
        return relationshipTypes;
    }

    public GraphSchema addRelationshipType(GraphRelationshipType relationshipType) {
        relationshipTypes.add(relationshipType);
        return this;
    }

    public GraphRelationshipType getRelationshipType(int index) {
        return relationshipTypes.get(index);
    }

    /** @return every relationship type whose source label is {@code labelIndex} */
    public List<GraphRelationshipType> getOutgoingRelationshipTypes(int labelIndex) {
        List<GraphRelationshipType> result = new ArrayList<>();
        for (GraphRelationshipType relationshipType : relationshipTypes) {
            if (relationshipType.getFromLabelIndex() == labelIndex) {
                result.add(relationshipType);
            }
        }
        return result;
    }

    /** @return every relationship type whose target label is {@code labelIndex} */
    public List<GraphRelationshipType> getIncomingRelationshipTypes(int labelIndex) {
        List<GraphRelationshipType> result = new ArrayList<>();
        for (GraphRelationshipType relationshipType : relationshipTypes) {
            if (relationshipType.getToLabelIndex() == labelIndex) {
                result.add(relationshipType);
            }
        }
        return result;
    }

    /** @return every relationship type that touches {@code labelIndex} on either end */
    public List<GraphRelationshipType> getRelationshipTypesOf(int labelIndex) {
        List<GraphRelationshipType> result = new ArrayList<>();
        for (GraphRelationshipType relationshipType : relationshipTypes) {
            if (relationshipType.getFromLabelIndex() == labelIndex || relationshipType.getToLabelIndex() == labelIndex) {
                result.add(relationshipType);
            }
        }
        return result;
    }

    public int getLabelCount() {
        return labels.size();
    }

    public int getRelationshipTypeCount() {
        return relationshipTypes.size();
    }

    @Override
    public String toString() {
        return "GraphSchema[labels=" + labels + " relationships=" + relationshipTypes + "]";
    }
}
