package org.ddlsmith.ddl;

import org.ddlsmith.common.schema.Index;

public class CreateIndex implements DDLStatement {

    private final String tableName;
    /** The namespace of the indexed table, or null when it lives in the engine's default namespace. */
    private final String schemaName;
    private final Index index;
    private final boolean ifNotExists;

    public CreateIndex(String tableName, Index index, boolean ifNotExists) {
        this(null, tableName, index, ifNotExists);
    }

    public CreateIndex(String schemaName, String tableName, Index index, boolean ifNotExists) {
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.index = index;
        this.ifNotExists = ifNotExists;
    }

    public CreateIndex(String tableName, Index index) {
        this(tableName, index, false);
    }

    public String getTableName() {
        return tableName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public Index getIndex() {
        return index;
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
        return "CREATE INDEX " + index.getName() + " ON " + tableName;
    }
}
