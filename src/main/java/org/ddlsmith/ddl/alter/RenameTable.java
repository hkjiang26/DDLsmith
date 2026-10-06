package org.ddlsmith.ddl.alter;

/** {@code RENAME TO ...} */
public class RenameTable extends AlterTableAction {

    private final String from;
    private final String to;

    public RenameTable(String from, String to) {
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
        return new RenameTable(to, from);
    }

    @Override
    public String toString() {
        return "RENAME TO " + to;
    }
}
