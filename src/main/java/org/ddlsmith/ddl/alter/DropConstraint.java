package org.ddlsmith.ddl.alter;

/** {@code DROP CONSTRAINT ...} (or the engine-specific equivalent) */
public class DropConstraint extends AlterTableAction {

    private final String constraintName;
    /** The definition of the dropped constraint when it is known; needed to restore it. */
    private final ConstraintDefinition definition;
    private final boolean ifExists;
    private final boolean cascade;

    public DropConstraint(String constraintName, ConstraintDefinition definition, boolean ifExists, boolean cascade) {
        this.constraintName = constraintName;
        this.definition = definition;
        this.ifExists = ifExists;
        this.cascade = cascade;
    }

    public DropConstraint(String constraintName) {
        this(constraintName, null, false, false);
    }

    public String getConstraintName() {
        return constraintName;
    }

    public ConstraintDefinition getDefinition() {
        return definition;
    }

    public boolean isIfExists() {
        return ifExists;
    }

    public boolean isCascade() {
        return cascade;
    }

    @Override
    public <R> R accept(AlterTableActionVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public AlterTableAction inverse(String tableName) {
        if (definition == null) {
            return null;
        }
        return new AddConstraint(definition);
    }

    @Override
    public String toString() {
        return "DROP CONSTRAINT " + constraintName;
    }
}
