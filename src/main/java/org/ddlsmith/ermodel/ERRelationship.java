package org.ddlsmith.ermodel;

import java.util.ArrayList;
import java.util.List;

/**
 * A relationship type of the ER diagram, derived from an edge of the randomly generated graph.
 *
 * <p>A relationship has one participant per connected entity. Binary relationships are the common
 * case; unary (recursive) and n-ary relationships are generated as well because they exercise
 * different parts of the ER-to-relational mapping.</p>
 */
public class ERRelationship {

    private String name;
    private final List<ERParticipant> participants = new ArrayList<>();
    private final List<ERAttribute> attributes = new ArrayList<>();
    /** True if this relationship identifies a weak entity. */
    private boolean identifying;
    /** True if this relationship is a generalization (ISA) link rather than an association. */
    private boolean generalization;
    /**
     * True if this relationship is a superclass membership link of a union type (category) rather
     * than an association. Like {@link #generalization} it is never mapped as an association.
     */
    private boolean categoryLink;
    /**
     * True if this relationship was absorbed by an aggregation: its meaning is subsumed by the
     * outer relationship that connects the aggregating entity, so it is never mapped on its own.
     */
    private boolean aggregationLink;
    /**
     * The graph relationship types whose semantics were merged into this relationship — set when
     * several binary types are synthesized into one n-ary relationship.
     */
    private final List<String> mergedTypeNames = new ArrayList<>();

    /** The id of the graph edge this relationship was derived from; -1 for synthetic relationships. */
    private int graphEdgeId = -1;

    public ERRelationship(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public ERRelationship setName(String name) {
        this.name = name;
        return this;
    }

    public List<ERParticipant> getParticipants() {
        return participants;
    }

    public ERRelationship addParticipant(ERParticipant participant) {
        participants.add(participant);
        return this;
    }

    public List<ERAttribute> getAttributes() {
        return attributes;
    }

    public ERRelationship addAttribute(ERAttribute attribute) {
        attributes.add(attribute);
        return this;
    }

    public boolean isIdentifying() {
        return identifying;
    }

    public ERRelationship setIdentifying(boolean identifying) {
        this.identifying = identifying;
        return this;
    }

    public boolean isGeneralization() {
        return generalization;
    }

    public ERRelationship setGeneralization(boolean generalization) {
        this.generalization = generalization;
        return this;
    }

    public boolean isCategoryLink() {
        return categoryLink;
    }

    public ERRelationship setCategoryLink(boolean categoryLink) {
        this.categoryLink = categoryLink;
        return this;
    }

    public boolean isAggregationLink() {
        return aggregationLink;
    }

    public ERRelationship setAggregationLink(boolean aggregationLink) {
        this.aggregationLink = aggregationLink;
        return this;
    }

    public List<String> getMergedTypeNames() {
        return mergedTypeNames;
    }

    public ERRelationship addMergedTypeName(String typeName) {
        if (typeName != null && !mergedTypeNames.contains(typeName)) {
            mergedTypeNames.add(typeName);
        }
        return this;
    }

    /**
     * @return true when this relationship carries no association semantics of its own: ISA links,
     *         category membership links and aggregation-absorbed links are structural only
     */
    public boolean isStructuralLink() {
        return generalization || categoryLink || aggregationLink;
    }

    public int getGraphEdgeId() {
        return graphEdgeId;
    }

    public ERRelationship setGraphEdgeId(int graphEdgeId) {
        this.graphEdgeId = graphEdgeId;
        return this;
    }

    /** @return the degree of the relationship: 1 for unary/recursive, 2 for binary, n for n-ary */
    public int getDegree() {
        return participants.size();
    }

    public boolean isBinary() {
        return participants.size() == 2;
    }

    public boolean isUnary() {
        return participants.size() == 1;
    }

    public boolean isNary() {
        return participants.size() > 2;
    }

    /** @return true if all participants refer to the same entity */
    public boolean isRecursive() {
        if (participants.size() < 2) {
            return true;
        }
        String first = participants.get(0).getEntityName();
        for (ERParticipant participant : participants) {
            if (!participant.getEntityName().equals(first)) {
                return false;
            }
        }
        return true;
    }

    public ERParticipant getParticipant(String entityName) {
        for (ERParticipant participant : participants) {
            if (participant.getEntityName().equals(entityName)) {
                return participant;
            }
        }
        return null;
    }

    /** @return the participant with the given role name */
    public ERParticipant getParticipantByRole(String roleName) {
        for (ERParticipant participant : participants) {
            if (participant.getRoleName().equals(roleName)) {
                return participant;
            }
        }
        return null;
    }

    /**
     * @return the effective cardinality of a binary relationship, used to choose the mapping rule
     */
    public BinaryCardinality getBinaryCardinality() {
        if (!isBinary()) {
            throw new IllegalStateException(name + " is not binary");
        }
        Cardinality left = participants.get(0).getCardinality();
        Cardinality right = participants.get(1).getCardinality();
        if (left == Cardinality.ONE && right == Cardinality.ONE) {
            return BinaryCardinality.ONE_TO_ONE;
        }
        if (left == Cardinality.MANY && right == Cardinality.MANY) {
            return BinaryCardinality.MANY_TO_MANY;
        }
        return BinaryCardinality.ONE_TO_MANY;
    }

    public enum BinaryCardinality {
        ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < participants.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(participants.get(i));
        }
        sb.append(')');
        if (!attributes.isEmpty()) {
            sb.append(' ').append(attributes);
        }
        if (identifying) {
            sb.append(" IDENTIFYING");
        }
        if (generalization) {
            sb.append(" GENERALIZATION");
        }
        if (categoryLink) {
            sb.append(" CATEGORY-LINK");
        }
        if (aggregationLink) {
            sb.append(" AGGREGATION-LINK");
        }
        if (!mergedTypeNames.isEmpty()) {
            sb.append(" MERGED-FROM ").append(mergedTypeNames);
        }
        return sb.toString();
    }
}
