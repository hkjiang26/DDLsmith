package org.ddlsmith.mapping;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records how every ER construct was mapped to relational constructs. Used for reporting, for
 * coverage statistics and by the oracles that need to know which constraint corresponds to which
 * ER construct.
 */
public class MappingTrace {

    /** ER entity name -> table name. */
    private final Map<String, String> entityToTable = new LinkedHashMap<>();
    /** ER relationship name -> table name (join tables and merged tables). */
    private final Map<String, String> relationshipToTable = new LinkedHashMap<>();
    /** ER entity name -> (attribute name -> column name). */
    private final Map<String, Map<String, String>> attributeToColumn = new LinkedHashMap<>();
    /** ER relationship name -> (attribute name -> column name). */
    private final Map<String, Map<String, String>> relationshipAttributeToColumn = new LinkedHashMap<>();
    /** ER relationship name -> the strategy that was applied to it. */
    private final Map<String, String> relationshipStrategy = new LinkedHashMap<>();
    /** ER entity name -> the strategy that was applied to it. */
    private final Map<String, String> entityStrategy = new LinkedHashMap<>();
    /** {@code owner.attribute} -> why that ER attribute is not stored in the schema. */
    private final Map<String, String> droppedAttributes = new LinkedHashMap<>();
    /** Human readable notes about the decisions taken, for failure reports. */
    private final List<String> notes = new java.util.ArrayList<>();

    public Map<String, String> getEntityToTable() {
        return entityToTable;
    }

    public Map<String, String> getRelationshipToTable() {
        return relationshipToTable;
    }

    public Map<String, Map<String, String>> getAttributeToColumn() {
        return attributeToColumn;
    }

    public Map<String, Map<String, String>> getRelationshipAttributeToColumn() {
        return relationshipAttributeToColumn;
    }

    public Map<String, String> getRelationshipStrategy() {
        return relationshipStrategy;
    }

    public Map<String, String> getEntityStrategy() {
        return entityStrategy;
    }

    /**
     * @return every ER attribute that the mapping deliberately did <em>not</em> store, keyed by
     *         {@code owner.attribute} (the owner being an entity or a relationship name), mapped to
     *         the reason. The mapping self-check requires every attribute of the diagram to appear
     *         either here or in {@link #getAttributeToColumn()} / {@link
     *         #getRelationshipAttributeToColumn()}, so a loss can never be silent.
     */
    public Map<String, String> getDroppedAttributes() {
        return droppedAttributes;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void recordEntity(String entity, String table, String strategy) {
        entityToTable.put(entity, table);
        entityStrategy.put(entity, strategy);
    }

    public void recordRelationship(String relationship, String table, String strategy) {
        if (table != null) {
            relationshipToTable.put(relationship, table);
        }
        relationshipStrategy.put(relationship, strategy);
    }

    public void recordAttribute(String entity, String attribute, String column) {
        attributeToColumn.computeIfAbsent(entity, k -> new HashMap<>()).put(attribute, column);
    }

    public void recordRelationshipAttribute(String relationship, String attribute, String column) {
        relationshipAttributeToColumn.computeIfAbsent(relationship, k -> new HashMap<>()).put(attribute, column);
    }

    /**
     * Records that an ER attribute is deliberately not stored in the schema, with the reason.
     *
     * <p>Every attribute the mapper does not turn into a column must be recorded here: the mapping
     * self-check fails when an attribute is neither stored nor explained, so a loss can never be
     * silent. The reason should say <em>why</em>, not merely restate that it was dropped.</p>
     *
     * @param owner the entity or relationship the attribute belongs to
     * @param attribute the attribute name
     * @param reason why it is not stored; {@code null} is replaced by a placeholder so that the
     *            ledger can never hold an empty explanation
     */
    public void recordDroppedAttribute(String owner, String attribute, String reason) {
        recordDroppedAttribute(owner + "." + attribute, reason);
    }

    /**
     * Records a dropped attribute by its full path, for a component of a composite attribute whose
     * path is already qualified (for example {@code E0.comp_0.from_date}).
     *
     * @param path the fully qualified {@code owner.attribute[.component...]} path
     * @param reason why it is not stored
     */
    public void recordDroppedAttribute(String path, String reason) {
        droppedAttributes.put(path, reason == null || reason.isBlank() ? "no reason given" : reason);
    }

    public void note(String note) {
        notes.add(note);
    }

    @Override
    public String toString() {
        return "MappingTrace[entities=" + entityToTable + " relationships=" + relationshipToTable + "]";
    }
}
