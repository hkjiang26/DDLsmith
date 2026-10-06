package org.ddlsmith.ddl;

public class CreateSchema implements DDLStatement {

    private final String schemaName;
    private final boolean ifNotExists;

    public CreateSchema(String schemaName, boolean ifNotExists) {
        this.schemaName = schemaName;
        this.ifNotExists = ifNotExists;
    }

    public String getSchemaName() {
        return schemaName;
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
        return "CREATE SCHEMA " + schemaName;
    }
}
