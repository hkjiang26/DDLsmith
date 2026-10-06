package org.ddlsmith.ddl.alter;

import org.ddlsmith.common.schema.DataType;

/** {@code ALTER [COLUMN] ... [SET DATA] TYPE ...} */
public class AlterColumnType extends AlterTableAction {

    private final String columnName;
    private final DataType newType;
    /** The type the column had before, used to invert the action. */
    private final DataType previousType;
    /** The USING expression required by some engines, or null. */
    private final String usingExpression;

    public AlterColumnType(String columnName, DataType newType, DataType previousType, String usingExpression) {
        this.columnName = columnName;
        this.newType = newType;
        this.previousType = previousType;
        this.usingExpression = usingExpression;
    }

    public String getColumnName() {
        return columnName;
    }

    public DataType getNewType() {
        return newType;
    }

    public DataType getPreviousType() {
        return previousType;
    }

    public String getUsingExpression() {
        return usingExpression;
    }

    @Override
    public <R> R accept(AlterTableActionVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public AlterTableAction inverse(String tableName) {
        if (previousType == null) {
            return null;
        }
        return new AlterColumnType(columnName, previousType, newType, null);
    }

    @Override
    public String toString() {
        return "ALTER COLUMN " + columnName + " TYPE " + newType.getName();
    }
}
