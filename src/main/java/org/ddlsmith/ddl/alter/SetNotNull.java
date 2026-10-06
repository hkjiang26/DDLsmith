package org.ddlsmith.ddl.alter;

/** {@code ALTER [COLUMN] ... SET NOT NULL} */
public class SetNotNull extends AlterTableAction {

    private final String columnName;
    /** Whether the column was nullable before the action; used to invert it. */
    private final boolean wasNullable;

    public SetNotNull(String columnName, boolean wasNullable) {
        this.columnName = columnName;
        this.wasNullable = wasNullable;
    }

    public SetNotNull(String columnName) {
        this(columnName, true);
    }

    public String getColumnName() {
        return columnName;
    }

    public boolean wasNullable() {
        return wasNullable;
    }

    @Override
    public <R> R accept(AlterTableActionVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public AlterTableAction inverse(String tableName) {
        if (!wasNullable) {
            return null;
        }
        return new DropNotNull(columnName, false);
    }

    @Override
    public String toString() {
        return "ALTER COLUMN " + columnName + " SET NOT NULL";
    }
}
