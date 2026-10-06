package org.ddlsmith.ddl.alter;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.UniqueConstraint;

/**
 * A table constraint in a form that can be added to or dropped from a table with {@code ALTER TABLE}.
 */
public class ConstraintDefinition {

    public enum Kind {
        PRIMARY_KEY, FOREIGN_KEY, UNIQUE, CHECK
    }

    private final Kind kind;
    private final PrimaryKey primaryKey;
    private final ForeignKey foreignKey;
    private final UniqueConstraint uniqueConstraint;
    private final CheckConstraint checkConstraint;

    private ConstraintDefinition(Kind kind, PrimaryKey primaryKey, ForeignKey foreignKey,
            UniqueConstraint uniqueConstraint, CheckConstraint checkConstraint) {
        this.kind = kind;
        this.primaryKey = primaryKey;
        this.foreignKey = foreignKey;
        this.uniqueConstraint = uniqueConstraint;
        this.checkConstraint = checkConstraint;
    }

    public static ConstraintDefinition primaryKey(PrimaryKey primaryKey) {
        return new ConstraintDefinition(Kind.PRIMARY_KEY, primaryKey, null, null, null);
    }

    public static ConstraintDefinition foreignKey(ForeignKey foreignKey) {
        return new ConstraintDefinition(Kind.FOREIGN_KEY, null, foreignKey, null, null);
    }

    public static ConstraintDefinition unique(UniqueConstraint uniqueConstraint) {
        return new ConstraintDefinition(Kind.UNIQUE, null, null, uniqueConstraint, null);
    }

    public static ConstraintDefinition check(CheckConstraint checkConstraint) {
        return new ConstraintDefinition(Kind.CHECK, null, null, null, checkConstraint);
    }

    public Kind getKind() {
        return kind;
    }

    public PrimaryKey getPrimaryKey() {
        return primaryKey;
    }

    public ForeignKey getForeignKey() {
        return foreignKey;
    }

    public UniqueConstraint getUniqueConstraint() {
        return uniqueConstraint;
    }

    public CheckConstraint getCheckConstraint() {
        return checkConstraint;
    }

    /** @return the declared name of the constraint, or null when it is unnamed */
    public String getName() {
        switch (kind) {
        case PRIMARY_KEY:
            return primaryKey.getName();
        case FOREIGN_KEY:
            return foreignKey.getName();
        case UNIQUE:
            return uniqueConstraint.getName();
        case CHECK:
            return checkConstraint.getName();
        default:
            throw new IllegalStateException();
        }
    }

    @Override
    public String toString() {
        switch (kind) {
        case PRIMARY_KEY:
            return primaryKey.toString();
        case FOREIGN_KEY:
            return foreignKey.toString();
        case UNIQUE:
            return uniqueConstraint.toString();
        default:
            return checkConstraint.toString();
        }
    }
}
