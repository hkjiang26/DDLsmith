package org.ddlsmith.ddl.alter;

/** {@code RENAME COLUMN ... TO ...} */
public class RenameColumn extends AlterTableAction {

    private final String from;
    private final String to;

    public RenameColumn(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    @Override
    public <R> R accept(AlterTableActionVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public AlterTableAction inverse(String tableName) {
        return new RenameColumn(to, from);
    }

    @Override
    public String toString() {
        return "RENAME COLUMN " + from + " TO " + to;
    }
}
