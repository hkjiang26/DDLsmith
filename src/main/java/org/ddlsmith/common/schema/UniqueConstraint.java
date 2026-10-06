package org.ddlsmith.common.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A (possibly composite) UNIQUE constraint. */
public class UniqueConstraint {

    private String name;
    private final List<String> columns = new ArrayList<>();
    private boolean deferrable;
    private boolean initiallyDeferred;

    public UniqueConstraint(List<String> columns) {
        this(null, columns);
    }

    public UniqueConstraint(String name, List<String> columns) {
        this.name = name;
        this.columns.addAll(columns);
    }

    public String getName() {
        return name;
    }

    public UniqueConstraint setName(String name) {
        this.name = name;
        return this;
    }

    public List<String> getColumns() {
        return columns;
    }

    public boolean isDeferrable() {
        return deferrable;
    }

    public UniqueConstraint setDeferrable(boolean deferrable) {
        this.deferrable = deferrable;
        return this;
    }

    public boolean isInitiallyDeferred() {
        return initiallyDeferred;
    }

    public UniqueConstraint setInitiallyDeferred(boolean initiallyDeferred) {
        this.initiallyDeferred = initiallyDeferred;
        return this;
    }

    @Override
    public String toString() {
        return "UNIQUE" + columns;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UniqueConstraint)) {
            return false;
        }
        return Objects.equals(columns, ((UniqueConstraint) o).columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns);
    }
}
