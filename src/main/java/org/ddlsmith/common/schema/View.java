package org.ddlsmith.common.schema;

import java.util.ArrayList;
import java.util.List;

/** A view, optionally materialized and/or with a CHECK OPTION. */
public class View {

    private String name;
    private String schemaName;
    /** The SELECT statement that defines the view. */
    private String query;
    private boolean materialized;
    /** WITH [LOCAL|CASCADED] CHECK OPTION, or null. */
    private String checkOption;
    private final List<String> columnNames = new ArrayList<>();
    private String comment;

    public View(String name, String query) {
        this.name = name;
        this.query = query;
    }

    public String getName() {
        return name;
    }

    public View setName(String name) {
        this.name = name;
        return this;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public View setSchemaName(String schemaName) {
        this.schemaName = schemaName;
        return this;
    }

    public String getQuery() {
        return query;
    }

    public View setQuery(String query) {
        this.query = query;
        return this;
    }

    public boolean isMaterialized() {
        return materialized;
    }

    public View setMaterialized(boolean materialized) {
        this.materialized = materialized;
        return this;
    }

    public String getCheckOption() {
        return checkOption;
    }

    public View setCheckOption(String checkOption) {
        this.checkOption = checkOption;
        return this;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public String getComment() {
        return comment;
    }

    public View setComment(String comment) {
        this.comment = comment;
        return this;
    }

    @Override
    public String toString() {
        return (materialized ? "MATERIALIZED " : "") + "VIEW " + name + " AS " + query;
    }
}
