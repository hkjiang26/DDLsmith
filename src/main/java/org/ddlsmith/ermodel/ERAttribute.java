package org.ddlsmith.ermodel;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.common.schema.DataType;

/**
 * An attribute of an entity or of a relationship.
 *
 * <p>Besides the plain "simple attribute" case, this supports the structural features of the ER
 * model that make relational mapping interesting: keys, partial keys of weak entities,
 * multi-valued attributes, composite attributes and derived attributes.</p>
 */
public class ERAttribute {

    private String name;
    private DataType type;
    /** True if this attribute is a candidate key of its entity. */
    private boolean primaryKey;
    /** True if this attribute is the partial key (discriminator) of a weak entity. */
    private boolean partialKey;
    /** True for a multi-valued attribute: requires its own table in the relational mapping. */
    private boolean multivalued;
    /** True for a composite attribute: flattened into one column per component. */
    private boolean composite;
    /** True for a derived attribute: not stored in the relational mapping. */
    private boolean derived;
    /** True if the attribute may be absent (maps to a nullable column). */
    private boolean optional;
    /** Components of a composite attribute. */
    private final List<ERAttribute> components = new ArrayList<>();
    /** The expression of a derived attribute (informational only). */
    private String derivedExpression;

    /** Name of the graph node/edge attribute slot this attribute came from, for traceability. */
    private String graphSlot;

    public ERAttribute(String name, DataType type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public ERAttribute setName(String name) {
        this.name = name;
        return this;
    }

    public DataType getType() {
        return type;
    }

    public ERAttribute setType(DataType type) {
        this.type = type;
        return this;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public ERAttribute setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
        return this;
    }

    public boolean isPartialKey() {
        return partialKey;
    }

    public ERAttribute setPartialKey(boolean partialKey) {
        this.partialKey = partialKey;
        return this;
    }

    public boolean isMultivalued() {
        return multivalued;
    }

    public ERAttribute setMultivalued(boolean multivalued) {
        this.multivalued = multivalued;
        return this;
    }

    public boolean isComposite() {
        return composite;
    }

    public ERAttribute setComposite(boolean composite) {
        this.composite = composite;
        return this;
    }

    public boolean isDerived() {
        return derived;
    }

    public ERAttribute setDerived(boolean derived) {
        this.derived = derived;
        return this;
    }

    public boolean isOptional() {
        return optional;
    }

    public ERAttribute setOptional(boolean optional) {
        this.optional = optional;
        return this;
    }

    public List<ERAttribute> getComponents() {
        return components;
    }

    public ERAttribute addComponent(ERAttribute component) {
        components.add(component);
        return this;
    }

    public String getDerivedExpression() {
        return derivedExpression;
    }

    public ERAttribute setDerivedExpression(String derivedExpression) {
        this.derivedExpression = derivedExpression;
        return this;
    }

    public String getGraphSlot() {
        return graphSlot;
    }

    public ERAttribute setGraphSlot(String graphSlot) {
        this.graphSlot = graphSlot;
        return this;
    }

    /** @return true if this attribute maps to one or more stored columns */
    public boolean isStored() {
        return !derived;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name).append(':').append(type.getName());
        if (primaryKey) {
            sb.append(" PK");
        }
        if (partialKey) {
            sb.append(" PARTIAL-KEY");
        }
        if (multivalued) {
            sb.append(" MULTIVALUED");
        }
        if (composite) {
            sb.append(" COMPOSITE").append(components);
        }
        if (derived) {
            sb.append(" DERIVED");
        }
        if (optional) {
            sb.append(" NULLABLE");
        }
        return sb.toString();
    }
}
