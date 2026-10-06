package org.ddlsmith.ddl;

import org.ddlsmith.common.schema.Table;

/** {@code CREATE TABLE} for a table of the relational schema. */
public class CreateTable implements DDLStatement {

    private final Table table;
    private final boolean ifNotExists;

    public CreateTable(Table table, boolean ifNotExists) {
        this.table = table;
        this.ifNotExists = ifNotExists;
    }

    public CreateTable(Table table) {
        this(table, false);
    }

    public Table getTable() {
        return table;
    }

    public boolean isIfNotExists() {
        return ifNotExists;
    }

    @Override
    public <R> R accept(DDLVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String describe() {
        return "CREATE TABLE " + table.getName();
    }
}
