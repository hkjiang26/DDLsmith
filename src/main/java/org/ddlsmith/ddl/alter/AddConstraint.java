package org.ddlsmith.ddl.alter;

/** {@code ADD CONSTRAINT ...} */
public class AddConstraint extends AlterTableAction {

    private final ConstraintDefinition definition;

    public AddConstraint(ConstraintDefinition definition) {
        this.definition = definition;
    }

    public ConstraintDefinition getDefinition() {
        return definition;
    }

    @Override
    public <R> R accept(AlterTableActionVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public AlterTableAction inverse(String tableName) {
        return new DropConstraint(definition.getName(), definition, false, false);
    }

    @Override
    public String toString() {
        return "ADD " + definition;
    }
}
