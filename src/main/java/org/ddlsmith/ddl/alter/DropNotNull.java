package org.ddlsmith.ddl.alter;

/** {@code ALTER [COLUMN] ... DROP NOT NULL} */
public class DropNotNull extends AlterTableAction {

    private final String columnName;
    /** Whether the column was NOT NULL before the action; used to invert it. */
    private final boolean wasNotNull;

    public DropNotNull(String columnName, boolean wasNotNull) {
        this.columnName = columnName;
        this.wasNotNull = wasNotNull;
    }

    public DropNotNull(String columnName) {
        this(columnName, false);
    }

    public String getColumnName() {
        return columnName;
    }

    public boolean wasNotNull() {
        return wasNotNull;
    }

    @Override
    public <R> R accept(AlterTableActionVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public AlterTableAction inverse(String tableName) {
        if (!wasNotNull) {
            return null;
        }
        return new SetNotNull(columnName, false);
    }

    @Override
    public String toString() {
        return "ALTER COLUMN " + columnName + " DROP NOT NULL";
    }
}
