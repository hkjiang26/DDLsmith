package org.ddlsmith.ddl;

public class TruncateTable implements DDLStatement {

    private final String tableName;
    private final String schemaName;
    private final boolean cascade;
    private final boolean restartIdentity;

    public TruncateTable(String tableName, boolean cascade, boolean restartIdentity) {
        this(null, tableName, cascade, restartIdentity);
    }

    /**
     * @param schemaName the namespace the table lives in, so that the statement addresses the table
     *        DDLsmith created rather than one of the same name in the engine's default namespace
     */
    public TruncateTable(String schemaName, String tableName, boolean cascade, boolean restartIdentity) {
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.cascade = cascade;
        this.restartIdentity = restartIdentity;
    }

    public String getTableName() {
        return tableName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public boolean isCascade() {
        return cascade;
    }

    public boolean isRestartIdentity() {
        return restartIdentity;
    }

    @Override
    public <R> R accept(DDLVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String describe() {
        return "TRUNCATE TABLE " + tableName;
    }
}
