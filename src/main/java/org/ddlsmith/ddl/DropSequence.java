package org.ddlsmith.ddl;

public class DropSequence implements DDLStatement {

    private final String sequenceName;
    private final String schemaName;
    private final boolean ifExists;
    private final boolean cascade;

    public DropSequence(String sequenceName, boolean ifExists, boolean cascade) {
        this(null, sequenceName, ifExists, cascade);
    }

    /** @param schemaName the namespace the sequence lives in, so the statement names the right object */
    public DropSequence(String schemaName, String sequenceName, boolean ifExists, boolean cascade) {
        this.schemaName = schemaName;
        this.sequenceName = sequenceName;
        this.ifExists = ifExists;
        this.cascade = cascade;
    }

    public DropSequence(String sequenceName) {
        this(null, sequenceName, false, false);
    }

    public String getSequenceName() {
        return sequenceName;
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
        return "DROP SEQUENCE " + sequenceName;
    }
}
