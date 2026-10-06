package org.ddlsmith.ddl;

public class DropView implements DDLStatement {

    private final String viewName;
    /** The namespace of the view, or null when it lives in the engine's default namespace. */
    private final String schemaName;
    private final boolean ifExists;
    private final boolean cascade;

    public DropView(String viewName, boolean ifExists, boolean cascade) {
        this(viewName, null, ifExists, cascade);
    }

    public DropView(String viewName, String schemaName, boolean ifExists, boolean cascade) {
        this.viewName = viewName;
        this.schemaName = schemaName;
        this.ifExists = ifExists;
        this.cascade = cascade;
    }

    public DropView(String viewName) {
        this(viewName, false, false);
    }

    public String getViewName() {
        return viewName;
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
        return "DROP VIEW " + viewName;
    }
}
