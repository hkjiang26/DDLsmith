package org.ddlsmith.common.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A (possibly composite) primary key. */
public class PrimaryKey {

    private String name;
    private final List<String> columns = new ArrayList<>();

    public PrimaryKey(String name, List<String> columns) {
        this.name = name;
        this.columns.addAll(columns);
    }

    public PrimaryKey(List<String> columns) {
        this(null, columns);
    }

    public String getName() {
        return name;
    }

    public PrimaryKey setName(String name) {
        this.name = name;
        return this;
    }

    public List<String> getColumns() {
        return columns;
    }

    public boolean isComposite() {
        return columns.size() > 1;
    }

    @Override
    public String toString() {
        return "PRIMARY KEY" + columns;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PrimaryKey)) {
            return false;
        }
        return Objects.equals(columns, ((PrimaryKey) o).columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns);
    }
}
