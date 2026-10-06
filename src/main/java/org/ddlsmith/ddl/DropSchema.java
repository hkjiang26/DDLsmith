package org.ddlsmith.ddl;

public class DropSchema implements DDLStatement {

    private final String schemaName;
    private final boolean ifExists;
    private final boolean cascade;

    public DropSchema(String schemaName, boolean ifExists, boolean cascade) {
        this.schemaName = schemaName;
        this.ifExists = ifExists;
        this.cascade = cascade;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public boolean isIfExists() {
        return ifExists;
    }

    public boolean isCascade() {
        return cascade;
    }

    @Override
    public <R> R accept(DDLVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String describe() {
        return "DROP SCHEMA " + schemaName;
    }
}
