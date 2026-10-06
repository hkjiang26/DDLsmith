package org.ddlsmith.ddl.alter;

import org.ddlsmith.common.schema.Column;

/** {@code ADD [COLUMN] ...} */
public class AddColumn extends AlterTableAction {

    private final Column column;
    private final boolean ifNotExists;

    public AddColumn(Column column, boolean ifNotExists) {
        this.column = column;
        this.ifNotExists = ifNotExists;
    }

    public AddColumn(Column column) {
        this(column, false);
    }

    public Column getColumn() {
        return column;
    }

    public boolean isIfNotExists() {
        return ifNotExists;
    }

    @Override
    public <R> R accept(AlterTableActionVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public AlterTableAction inverse(String tableName) {
        // the definition is kept so that the inverse can restore the exact column
        return new DropColumn(column.getName(), column.copy(), false, false);
    }

    @Override
    public String toString() {
        return "ADD COLUMN " + column;
    }
}
