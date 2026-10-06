package org.ddlsmith.ermodel;

import java.util.ArrayList;
import java.util.List;

/**
 * An entity type of the ER diagram. Entity types are produced by mapping the nodes of a randomly
 * generated graph: every node becomes one entity, and the node's attribute slots become the
 * entity's attributes.
 */
public class EREntity {

    private String name;
    private final List<ERAttribute> attributes = new ArrayList<>();

    /** True for a weak entity: it has no key of its own and depends on an owner entity. */
    private boolean weak;
    /** For weak entities: the identifying relationship to the owner entity. */
    private String ownerRelationship;
    /** For weak entities: the owner entity name. */
    private String ownerEntity;

    /**
     * Generalization: the supertype entities. The list has more than one element for a shared
     * subclass (multiple inheritance); a regular subtype has exactly one.
     */
    private final List<String> superEntities = new ArrayList<>();
    /** Generalization: the subtype entities. */
    private final List<String> subEntities = new ArrayList<>();
    /** True for a generalization supertype that is declared as disjoint. */
    private boolean disjoint = true;
    /**
     * True when the specialization of this supertype is total (complete): every instance of the
     * supertype belongs to at least one subtype. False means partial specialization.
     */
    private boolean totalSpecialization;
    /** The attribute used as the discriminator of a generalization hierarchy. */
    private String discriminatorAttribute;

    /**
     * Union type / category: the superclasses this entity is the union of. Unlike
     * {@link #superEntities}, the links are membership links (each instance belongs to at least
     * one superclass) rather than attribute-inheriting specialization links.
     */
    private final List<String> categorySupers = new ArrayList<>();

    /**
     * Aggregation: when set, this entity does not stand for a graph label but for an aggregated
     * relationship — that relationship (plus its participants) treated as a higher-level entity
     * that other relationships can point at.
     */
    private String aggregatedRelationship;

    /** The id of the graph node this entity was derived from. */
    private int graphNodeId = -1;

    public EREntity(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public EREntity setName(String name) {
        this.name = name;
        return this;
    }

    public List<ERAttribute> getAttributes() {
        return attributes;
    }

    public EREntity addAttribute(ERAttribute attribute) {
        attributes.add(attribute);
        return this;
    }

    public ERAttribute getAttribute(String attributeName) {
        for (ERAttribute attribute : attributes) {
            if (attribute.getName().equals(attributeName)) {
                return attribute;
            }
        }
        return null;
    }

    public List<ERAttribute> getPrimaryKeyAttributes() {
        List<ERAttribute> result = new ArrayList<>();
        for (ERAttribute attribute : attributes) {
            if (attribute.isPrimaryKey()) {
                result.add(attribute);
            }
        }
        return result;
    }

    public List<ERAttribute> getPartialKeyAttributes() {
        List<ERAttribute> result = new ArrayList<>();
        for (ERAttribute attribute : attributes) {
            if (attribute.isPartialKey()) {
                result.add(attribute);
            }
        }
        return result;
    }

    public List<ERAttribute> getMultivaluedAttributes() {
        List<ERAttribute> result = new ArrayList<>();
        for (ERAttribute attribute : attributes) {
            if (attribute.isMultivalued()) {
                result.add(attribute);
            }
        }
        return result;
    }

    public boolean hasPrimaryKey() {
        return !getPrimaryKeyAttributes().isEmpty();
    }

    public boolean isWeak() {
        return weak;
    }

    public EREntity setWeak(boolean weak) {
        this.weak = weak;
        return this;
    }

    public String getOwnerRelationship() {
        return ownerRelationship;
    }

    public EREntity setOwnerRelationship(String ownerRelationship) {
        this.ownerRelationship = ownerRelationship;
        return this;
    }

    public String getOwnerEntity() {
        return ownerEntity;
    }

    public EREntity setOwnerEntity(String ownerEntity) {
        this.ownerEntity = ownerEntity;
        return this;
    }

    /** @return the first supertype, or null; single-inheritance shorthand for {@link #getSuperEntities()} */
    public String getSuperEntity() {
        return superEntities.isEmpty() ? null : superEntities.get(0);
    }

    /** @return every supertype of this entity; a shared subclass has more than one */
    public List<String> getSuperEntities() {
        return superEntities;
    }

    /** Replaces the supertype list with the single given supertype. */
    public EREntity setSuperEntity(String superEntity) {
        superEntities.clear();
        if (superEntity != null) {
            superEntities.add(superEntity);
        }
        return this;
    }

    /** Adds a further supertype (multiple inheritance / shared subclass). */
    public EREntity addSuperEntity(String superEntity) {
        if (superEntity != null && !superEntities.contains(superEntity)) {
            superEntities.add(superEntity);
        }
        return this;
    }

    public boolean hasSuperEntity(String entityName) {
        return superEntities.contains(entityName);
    }

    /** @return true when this entity inherits from more than one supertype (shared subclass) */
    public boolean isSharedSubclass() {
        return superEntities.size() > 1;
    }

    public List<String> getSubEntities() {
        return subEntities;
    }

    public EREntity addSubEntity(String subEntity) {
        subEntities.add(subEntity);
        return this;
    }

    public boolean isGeneralizationSupertype() {
        return !subEntities.isEmpty();
    }

    public boolean isGeneralizationSubtype() {
        return !superEntities.isEmpty();
    }

    public boolean isDisjoint() {
        return disjoint;
    }

    public EREntity setDisjoint(boolean disjoint) {
        this.disjoint = disjoint;
        return this;
    }

    /**
     * @return true when the specialization of this supertype is total: every instance belongs to
     *         at least one subtype (the completeness constraint of the hierarchy)
     */
    public boolean isTotalSpecialization() {
        return totalSpecialization;
    }

    public EREntity setTotalSpecialization(boolean totalSpecialization) {
        this.totalSpecialization = totalSpecialization;
        return this;
    }

    /** @return the superclasses of this union type; empty for a regular entity */
    public List<String> getCategorySupers() {
        return categorySupers;
    }

    public EREntity addCategorySuper(String superclassName) {
        if (superclassName != null && !categorySupers.contains(superclassName)) {
            categorySupers.add(superclassName);
        }
        return this;
    }

    /** @return true when this entity is a union type (category) over its {@link #getCategorySupers()} */
    public boolean isCategory() {
        return !categorySupers.isEmpty();
    }

    /** @return the relationship this entity aggregates, or null for a regular entity */
    public String getAggregatedRelationship() {
        return aggregatedRelationship;
    }

    public EREntity setAggregatedRelationship(String aggregatedRelationship) {
        this.aggregatedRelationship = aggregatedRelationship;
        return this;
    }

    /** @return true when this entity stands for an aggregated relationship */
    public boolean isAggregation() {
        return aggregatedRelationship != null;
    }

    public String getDiscriminatorAttribute() {
        return discriminatorAttribute;
    }

    public EREntity setDiscriminatorAttribute(String discriminatorAttribute) {
        this.discriminatorAttribute = discriminatorAttribute;
        return this;
    }

    public int getGraphNodeId() {
        return graphNodeId;
    }

    public EREntity setGraphNodeId(int graphNodeId) {
        this.graphNodeId = graphNodeId;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        if (weak) {
            sb.append(" (weak, owner=").append(ownerEntity).append(" via ").append(ownerRelationship).append(')');
        }
        if (isGeneralizationSupertype()) {
            sb.append(" (supertype of ").append(subEntities)
                    .append(disjoint ? ", disjoint" : ", overlapping")
                    .append(totalSpecialization ? ", total" : ", partial").append(')');
        }
        if (isGeneralizationSubtype()) {
            sb.append(" (subtype of ").append(superEntities).append(')');
        }
        if (isCategory()) {
            sb.append(" (category of ").append(categorySupers).append(')');
        }
        if (isAggregation()) {
            sb.append(" (aggregation of ").append(aggregatedRelationship).append(')');
        }
        sb.append(' ').append(attributes);
        return sb.toString();
    }
}
