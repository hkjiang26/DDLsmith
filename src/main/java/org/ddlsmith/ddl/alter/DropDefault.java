package org.ddlsmith.ddl.alter;

/** {@code ALTER [COLUMN] ... DROP DEFAULT} */
public class DropDefault extends AlterTableAction {

    private final String columnName;
    /** The default that was dropped, used to invert the action. */
    private final String previousExpression;

    public DropDefault(String columnName, String previousExpression) {
        this.columnName = columnName;
        this.previousExpression = previousExpression;
    }

    public DropDefault(String columnName) {
        this(columnName, null);
    }

    public String getColumnName() {
        return columnName;
    }

    public String getPreviousExpression() {
        return previousExpression;
    }

    @Override
    public <R> R accept(AlterTableActionVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public AlterTableAction inverse(String tableName) {
        if (previousExpression == null) {
            return null;
        }
        return new SetDefault(columnName, previousExpression, null);
    }

    @Override
    public String toString() {
        return "ALTER COLUMN " + columnName + " DROP DEFAULT";
    }
}
