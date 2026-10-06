package org.ddlsmith.ddl;

/**
 * A DDL statement of the dialect-independent abstract syntax tree.
 */
public interface DDLStatement {

    <R> R accept(DDLVisitor<R> visitor);

    /** Renders this statement with the given dialect. */
    default String toSql(Dialect dialect) {
        return accept(dialect);
    }

    /** A short description used in failure reports. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
