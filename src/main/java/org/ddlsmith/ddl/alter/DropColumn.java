package org.ddlsmith.ddl.alter;

import org.ddlsmith.common.schema.Column;

/** {@code DROP [COLUMN] ...} */
public class DropColumn extends AlterTableAction {

    private final String columnName;
    /** The definition of the dropped column when it is known; needed to restore it. */
    private final Column definition;
    private final boolean ifExists;
    private final boolean cascade;

    public DropColumn(String columnName, Column definition, boolean ifExists, boolean cascade) {
        this.columnName = columnName;
        this.definition = definition;
        this.ifExists = ifExists;
        this.cascade = cascade;
    }

    public DropColumn(String columnName, Column definition) {
        this(columnName, definition, false, false);
    }

    public String getColumnName() {
        return columnName;
    }

    public Column getDefinition() {
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
            // without the original definition the column cannot be restored faithfully
            return null;
        }
        return new AddColumn(definition.copy(), false);
    }

    @Override
    public String toString() {
        return "DROP COLUMN " + columnName;
    }
}
