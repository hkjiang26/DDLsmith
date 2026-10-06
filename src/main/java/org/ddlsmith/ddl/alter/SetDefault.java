package org.ddlsmith.ddl.alter;

/** {@code ALTER [COLUMN] ... SET DEFAULT ...} */
public class SetDefault extends AlterTableAction {

    private final String columnName;
    private final String expression;
    /** The default the column had before, used to invert the action. */
    private final String previousExpression;

    public SetDefault(String columnName, String expression, String previousExpression) {
        this.columnName = columnName;
        this.expression = expression;
        this.previousExpression = previousExpression;
    }

    public SetDefault(String columnName, String expression) {
        this(columnName, expression, null);
    }

    public String getColumnName() {
        return columnName;
    }

    public String getExpression() {
        return expression;
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
            return new DropDefault(columnName, expression);
        }
        return new SetDefault(columnName, previousExpression, expression);
    }

    @Override
    public String toString() {
        return "ALTER COLUMN " + columnName + " SET DEFAULT " + expression;
    }
}
