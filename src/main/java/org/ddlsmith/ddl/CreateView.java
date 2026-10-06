package org.ddlsmith.ddl;

import org.ddlsmith.common.schema.View;

public class CreateView implements DDLStatement {

    private final View view;
    private final boolean orReplace;
    private final boolean ifNotExists;

    public CreateView(View view, boolean orReplace, boolean ifNotExists) {
        this.view = view;
        this.orReplace = orReplace;
        this.ifNotExists = ifNotExists;
    }

    public CreateView(View view) {
        this(view, false, false);
    }

    public View getView() {
        return view;
    }

    public boolean isOrReplace() {
        return orReplace;
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
        return "CREATE VIEW " + view.getName();
    }
}
