package org.ddlsmith.ddl;

public class DropTable implements DDLStatement {

    private final String tableName;
    private final String schemaName;
    private final boolean ifExists;
    private final boolean cascade;

    public DropTable(String tableName, boolean ifExists, boolean cascade) {
        this(null, tableName, ifExists, cascade);
    }

    /**
     * @param schemaName the namespace the table lives in. A statement that drops a table DDLsmith
     *        created has to name that namespace: an unqualified {@code DROP TABLE t} addresses the
     *        engine's default namespace, so it either fails or silently does nothing, and the schema
     *        then keeps a table the tool believes it removed.
     */
    public DropTable(String schemaName, String tableName, boolean ifExists, boolean cascade) {
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.ifExists = ifExists;
        this.cascade = cascade;
    }

    public DropTable(String tableName) {
        this(null, tableName, false, false);
    }

    public String getTableName() {
        return tableName;
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
        return "DROP TABLE " + tableName;
    }
}
