package org.ddlsmith.ddl;

/**
 * {@code DROP INDEX}. Some engines qualify the index name with the table name (e.g. MySQL,
 * SQLite), which is why the table name is carried along.
 */
public class DropIndex implements DDLStatement {

    private final String indexName;
    private final String tableName;
    /** The namespace of the indexed table, or null when it lives in the engine's default namespace. */
    private final String schemaName;
    private final boolean ifExists;
    private final boolean cascade;

    public DropIndex(String indexName, String tableName, boolean ifExists, boolean cascade) {
        this(indexName, tableName, null, ifExists, cascade);
    }

    public DropIndex(String indexName, String tableName, String schemaName, boolean ifExists, boolean cascade) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.schemaName = schemaName;
        this.ifExists = ifExists;
        this.cascade = cascade;
    }

    public DropIndex(String indexName, String tableName) {
        this(indexName, tableName, false, false);
    }

    public String getIndexName() {
        return indexName;
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
        return "DROP INDEX " + indexName;
    }
}
