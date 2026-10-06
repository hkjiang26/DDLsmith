package org.ddlsmith.common.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A secondary index. */
public class Index {

    private String name;
    private final List<String> columns = new ArrayList<>();
    /** Raw SQL expressions for expression indexes (mutually exclusive with {@link #columns}). */
    private final List<String> expressions = new ArrayList<>();
    private boolean unique;
    /** Access method, e.g. btree/hash; null means engine default. */
    private String method;
    /** Partial index predicate, or null. */
    private String predicate;
    private final List<String> includeColumns = new ArrayList<>();

    public Index(String name) {
        this.name = name;
    }

    public Index(String name, List<String> columns) {
        this.name = name;
        this.columns.addAll(columns);
    }

    public String getName() {
        return name;
    }

    public Index setName(String name) {
        this.name = name;
        return this;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<String> getExpressions() {
        return expressions;
    }

    public boolean isUnique() {
        return unique;
    }

    public Index setUnique(boolean unique) {
        this.unique = unique;
        return this;
    }

    public String getMethod() {
        return method;
    }

    public Index setMethod(String method) {
        this.method = method;
        return this;
    }

    public String getPredicate() {
        return predicate;
    }

    public Index setPredicate(String predicate) {
        this.predicate = predicate;
        return this;
    }

    public List<String> getIncludeColumns() {
        return includeColumns;
    }

    public boolean isExpressionIndex() {
        return !expressions.isEmpty();
    }

    public boolean isPartial() {
        return predicate != null && !predicate.isBlank();
    }

    @Override
    public String toString() {
        return (unique ? "UNIQUE " : "") + "INDEX " + name + " ON " + (isExpressionIndex() ? expressions : columns);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Index)) {
            return false;
        }
        Index other = (Index) o;
        return unique == other.unique && Objects.equals(columns, other.columns)
                && Objects.equals(expressions, other.expressions) && Objects.equals(predicate, other.predicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns, expressions, unique, predicate);
    }
}
