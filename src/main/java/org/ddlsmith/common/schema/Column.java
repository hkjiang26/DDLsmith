package org.ddlsmith.common.schema;

import java.util.Objects;

/**
 * A column of a relational table.
 */
public class Column {

    private String name;
    private DataType type;
    private boolean nullable = true;
    /** Raw SQL default expression, or null. */
    private String defaultExpression;
    /** True when the column is an IDENTITY / AUTO_INCREMENT / SERIAL column. */
    private boolean identity;
    /** Raw SQL expression of a generated column, or null. */
    private String generatedExpression;
    private boolean generatedStored;
    private String collation;
    private String comment;
    /** Inline UNIQUE constraint. */
    private boolean unique;
    /** Inline CHECK expression, or null. */
    private String checkExpression;
    /** Ordinal position, filled in by generators and introspectors for stable comparison. */
    private int ordinalPosition;

    /** The ER attribute this column was derived from (null for pure mapping artifacts such as join-table keys). */
    private String derivedFrom;

    public Column(String name, DataType type) {
        this.name = name;
        this.type = type;
    }

    public Column(String name, DataType type, boolean nullable) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
    }

    public String getName() {
        return name;
    }

    public Column setName(String name) {
        this.name = name;
        return this;
    }

    public DataType getType() {
        return type;
    }

    public Column setType(DataType type) {
        this.type = type;
        return this;
    }

    public boolean isNullable() {
        return nullable;
    }

    public Column setNullable(boolean nullable) {
        this.nullable = nullable;
        return this;
    }

    public String getDefaultExpression() {
        return defaultExpression;
    }

    public Column setDefaultExpression(String defaultExpression) {
        this.defaultExpression = defaultExpression;
        return this;
    }

    public boolean hasDefault() {
        return defaultExpression != null;
    }

    public boolean isIdentity() {
        return identity;
    }

    public Column setIdentity(boolean identity) {
        this.identity = identity;
        return this;
    }

    public String getGeneratedExpression() {
        return generatedExpression;
    }

    public Column setGeneratedExpression(String generatedExpression) {
        this.generatedExpression = generatedExpression;
        return this;
    }

    public boolean isGeneratedStored() {
        return generatedStored;
    }

    public Column setGeneratedStored(boolean generatedStored) {
        this.generatedStored = generatedStored;
        return this;
    }

    public boolean isGenerated() {
        return generatedExpression != null;
    }

    public String getCollation() {
        return collation;
    }

    public Column setCollation(String collation) {
        this.collation = collation;
        return this;
    }

    public String getComment() {
        return comment;
    }

    public Column setComment(String comment) {
        this.comment = comment;
        return this;
    }

    public boolean isUnique() {
        return unique;
    }

    public Column setUnique(boolean unique) {
        this.unique = unique;
        return this;
    }

    public String getCheckExpression() {
        return checkExpression;
    }

    public Column setCheckExpression(String checkExpression) {
        this.checkExpression = checkExpression;
        return this;
    }

    public int getOrdinalPosition() {
        return ordinalPosition;
    }

    public Column setOrdinalPosition(int ordinalPosition) {
        this.ordinalPosition = ordinalPosition;
        return this;
    }

    public String getDerivedFrom() {
        return derivedFrom;
    }

    public Column setDerivedFrom(String derivedFrom) {
        this.derivedFrom = derivedFrom;
        return this;
    }

    public Column copy() {
        Column c = new Column(name, type, nullable);
        c.defaultExpression = defaultExpression;
        c.identity = identity;
        c.generatedExpression = generatedExpression;
        c.generatedStored = generatedStored;
        c.collation = collation;
        c.comment = comment;
        c.unique = unique;
        c.checkExpression = checkExpression;
        c.ordinalPosition = ordinalPosition;
        c.derivedFrom = derivedFrom;
        return c;
    }

    @Override
    public String toString() {
        return name + " " + type.getName() + (nullable ? "" : " NOT NULL");
    }

    /** Identity by name only: used when comparing column sets between two versions of a schema. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Column)) {
            return false;
        }
        Column other = (Column) o;
        return Objects.equals(name, other.name) && Objects.equals(type, other.type) && nullable == other.nullable
                && Objects.equals(defaultExpression, other.defaultExpression) && identity == other.identity
                && Objects.equals(generatedExpression, other.generatedExpression) && unique == other.unique
                && Objects.equals(checkExpression, other.checkExpression);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, nullable, defaultExpression, identity, generatedExpression, unique,
                checkExpression);
    }
}
