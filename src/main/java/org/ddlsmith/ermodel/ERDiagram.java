package org.ddlsmith.ermodel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A complete ER diagram: the interpretation of one randomly generated graph.
 *
 * <p>Nodes of the graph become {@link EREntity entity types}; edges become
 * {@link ERRelationship relationship types}. The diagram is then mapped to a relational schema by
 * {@link org.ddlsmith.mapping.ERToRelationalMapper}.</p>
 */
public class ERDiagram {

    private final List<EREntity> entities = new ArrayList<>();
    private final List<ERRelationship> relationships = new ArrayList<>();
    private String name = "er";

    public String getName() {
        return name;
    }

    public ERDiagram setName(String name) {
        this.name = name;
        return this;
    }

    public List<EREntity> getEntities() {
        return entities;
    }

    public ERDiagram addEntity(EREntity entity) {
        entities.add(entity);
        return this;
    }

    public EREntity getEntity(String entityName) {
        for (EREntity entity : entities) {
            if (entity.getName().equals(entityName)) {
                return entity;
            }
        }
        return null;
    }

    public boolean hasEntity(String entityName) {
        return getEntity(entityName) != null;
    }

    public List<String> getEntityNames() {
        List<String> names = new ArrayList<>();
        for (EREntity entity : entities) {
            names.add(entity.getName());
        }
        return names;
    }

    public List<ERRelationship> getRelationships() {
        return relationships;
    }

    public ERDiagram addRelationship(ERRelationship relationship) {
        relationships.add(relationship);
        return this;
    }

    public ERRelationship getRelationship(String relationshipName) {
        for (ERRelationship relationship : relationships) {
            if (relationship.getName().equals(relationshipName)) {
                return relationship;
            }
        }
        return null;
    }

    public List<String> getRelationshipNames() {
        List<String> names = new ArrayList<>();
        for (ERRelationship relationship : relationships) {
            names.add(relationship.getName());
        }
        return names;
    }

    /** @return every relationship that has {@code entityName} as a participant */
    public List<ERRelationship> getRelationshipsOf(String entityName) {
        List<ERRelationship> result = new ArrayList<>();
        for (ERRelationship relationship : relationships) {
            if (relationship.getParticipant(entityName) != null) {
                result.add(relationship);
            }
        }
        return result;
    }

    /** @return all entities that are directly connected to {@code entityName} */
    public Set<String> getNeighbours(String entityName) {
        Set<String> neighbours = new LinkedHashSet<>();
        for (ERRelationship relationship : getRelationshipsOf(entityName)) {
            for (ERParticipant participant : relationship.getParticipants()) {
                if (!participant.getEntityName().equals(entityName)) {
                    neighbours.add(participant.getEntityName());
                }
            }
        }
        return neighbours;
    }

    /** @return the entities that are the target of an ISA link from {@code entityName} */
    public List<EREntity> getSubEntities(String entityName) {
        List<EREntity> result = new ArrayList<>();
        for (EREntity entity : entities) {
            if (!entityName.equals(entity.getName()) && entity.hasSuperEntity(entityName)) {
                result.add(entity);
            }
        }
        return result;
    }

    /**
     * @return the superclasses of a union type (category) as entities; empty when the entity is
     *         not a category or names an unknown entity
     */
    public List<EREntity> getCategorySupers(String entityName) {
        List<EREntity> result = new ArrayList<>();
        EREntity entity = getEntity(entityName);
        if (entity == null) {
            return result;
        }
        for (String name : entity.getCategorySupers()) {
            EREntity superclass = getEntity(name);
            if (superclass != null) {
                result.add(superclass);
            }
        }
        return result;
    }

    /** @return every entity that stands for an aggregated relationship */
    public List<EREntity> getAggregationEntities() {
        List<EREntity> result = new ArrayList<>();
        for (EREntity entity : entities) {
            if (entity.isAggregation()) {
                result.add(entity);
            }
        }
        return result;
    }

    /** @return every entity that is a union type over its category superclasses */
    public List<EREntity> getCategories() {
        List<EREntity> result = new ArrayList<>();
        for (EREntity entity : entities) {
            if (entity.isCategory()) {
                result.add(entity);
            }
        }
        return result;
    }

    /** @return true if the ISA hierarchy starting at {@code entityName} is disjoint */
    public boolean isDisjointHierarchy(String entityName) {
        EREntity entity = getEntity(entityName);
        return entity == null || entity.isDisjoint();
    }

    /** @return every entity that has no key attribute of its own (weak entities) */
    public List<EREntity> getWeakEntities() {
        List<EREntity> result = new ArrayList<>();
        for (EREntity entity : entities) {
            if (entity.isWeak()) {
                result.add(entity);
            }
        }
        return result;
    }

    /**
     * @return the attributes that identify {@code entityName}: its own key attributes, or - when it
     *         declares none because it is a subtype - the key of its nearest supertype, recursively.
     *
     * <p>A subtype <em>inherits</em> its supertype's key rather than declaring one, so
     * {@link EREntity#getPrimaryKeyAttributes()} being empty on a subtype does not mean the entity
     * is unidentifiable. Callers that need "what identifies this entity" must ask here; callers that
     * need "which attributes does this entity declare itself" keep using the entity's own list.</p>
     *
     * <p>A weak entity is identified by its partial key, so that is returned when it has no primary
     * key of its own.</p>
     */
    public List<ERAttribute> getIdentifyingAttributes(String entityName) {
        Set<String> visited = new LinkedHashSet<>();
        EREntity entity = getEntity(entityName);
        while (entity != null && visited.add(entity.getName())) {
            if (!entity.getPrimaryKeyAttributes().isEmpty()) {
                return entity.getPrimaryKeyAttributes();
            }
            if (entity.isWeak() && !entity.getPartialKeyAttributes().isEmpty()) {
                return entity.getPartialKeyAttributes();
            }
            entity = entity.getSuperEntity() == null ? null : getEntity(entity.getSuperEntity());
        }
        return List.of();
    }

    /** @return every relationship whose participants all refer to the same entity */
    public List<ERRelationship> getRecursiveRelationships() {
        List<ERRelationship> result = new ArrayList<>();
        for (ERRelationship relationship : relationships) {
            if (relationship.isRecursive() && !relationship.isGeneralization()) {
                result.add(relationship);
            }
        }
        return result;
    }

    /** @return every relationship with more than two participants */
    public List<ERRelationship> getNaryRelationships() {
        List<ERRelationship> result = new ArrayList<>();
        for (ERRelationship relationship : relationships) {
            if (relationship.isNary()) {
                result.add(relationship);
            }
        }
        return result;
    }

    public ERStatistics getStatistics() {
        ERStatistics statistics = new ERStatistics();
        statistics.entities = entities.size();
        statistics.relationships = relationships.size();
        for (EREntity entity : entities) {
            statistics.attributes += entity.getAttributes().size();
            if (entity.isWeak()) {
                statistics.weakEntities++;
            }
            if (entity.isGeneralizationSupertype()) {
                statistics.generalizations++;
                if (entity.isTotalSpecialization()) {
                    statistics.totalSpecializations++;
                }
            }
            if (entity.isGeneralizationSubtype()) {
                statistics.subtypes++;
            }
            if (entity.isSharedSubclass()) {
                statistics.sharedSubclasses++;
            }
            if (entity.isCategory()) {
                statistics.categories++;
            }
            if (entity.isAggregation()) {
                statistics.aggregations++;
            }
            if (entity.getPrimaryKeyAttributes().size() > 1) {
                statistics.compositeKeys++;
            }
            if (entity.isWeak() && entity.getPartialKeyAttributes().size() > 1) {
                statistics.compositePartialKeys++;
            }
            for (ERAttribute attribute : entity.getAttributes()) {
                if (attribute.isMultivalued()) {
                    statistics.multivaluedAttributes++;
                }
                if (attribute.isComposite()) {
                    statistics.compositeAttributes++;
                }
                if (attribute.isDerived()) {
                    statistics.derivedAttributes++;
                }
            }
        }
        for (ERRelationship relationship : relationships) {
            statistics.relationshipAttributes += relationship.getAttributes().size();
            if (relationship.isUnary()) {
                statistics.unaryRelationships++;
            } else if (relationship.isNary()) {
                statistics.naryRelationships++;
            } else {
                statistics.binaryRelationships++;
                if (relationship.getBinaryCardinality() == ERRelationship.BinaryCardinality.MANY_TO_MANY) {
                    statistics.manyToManyRelationships++;
                } else if (relationship.getBinaryCardinality() == ERRelationship.BinaryCardinality.ONE_TO_ONE) {
                    statistics.oneToOneRelationships++;
                } else {
                    statistics.oneToManyRelationships++;
                }
            }
            if (relationship.isIdentifying()) {
                statistics.identifyingRelationships++;
            }
        }
        return statistics;
    }

    /** Structural statistics of a generated ER diagram. */
    public static final class ERStatistics {
        public int entities;
        public int attributes;
        public int relationships;
        public int binaryRelationships;
        public int unaryRelationships;
        public int naryRelationships;
        public int oneToOneRelationships;
        public int oneToManyRelationships;
        public int manyToManyRelationships;
        public int identifyingRelationships;
        public int relationshipAttributes;
        public int weakEntities;
        public int generalizations;
        public int subtypes;
        public int multivaluedAttributes;
        public int compositeAttributes;
        public int derivedAttributes;
        public int sharedSubclasses;
        public int totalSpecializations;
        public int categories;
        public int aggregations;
        public int compositeKeys;
        public int compositePartialKeys;

        @Override
        public String toString() {
            return "entities=" + entities + " attributes=" + attributes + " relationships=" + relationships
                    + "(1:1=" + oneToOneRelationships + ",1:N=" + oneToManyRelationships + ",M:N="
                    + manyToManyRelationships + ",unary=" + unaryRelationships + ",n-ary=" + naryRelationships
                    + ") identifying=" + identifyingRelationships + " relAttrs=" + relationshipAttributes
                    + " weak=" + weakEntities + " isa=" + generalizations + "/" + subtypes + " multivalued="
                    + multivaluedAttributes + " composite=" + compositeAttributes + " derived=" + derivedAttributes
                    + " shared=" + sharedSubclasses + " totalSpec=" + totalSpecializations + " categories="
                    + categories + " aggregations=" + aggregations + " compositeKeys=" + compositeKeys
                    + " compositePartialKeys=" + compositePartialKeys;
        }
    }

    @Override
    public String toString() {
        return "ERDiagram[" + name + ": " + getEntityNames() + " | " + getRelationshipNames() + "]";
    }
}
