package org.ddlsmith.common.schema;

import java.util.Objects;

/** A CHECK constraint with a raw SQL boolean expression. */
public class CheckConstraint {

    private String name;
    private String expression;

    public CheckConstraint(String expression) {
        this(null, expression);
    }

    public CheckConstraint(String name, String expression) {
        this.name = name;
        this.expression = expression;
    }

    public String getName() {
        return name;
    }

    public CheckConstraint setName(String name) {
        this.name = name;
        return this;
    }

    public String getExpression() {
        return expression;
    }

    public CheckConstraint setExpression(String expression) {
        this.expression = expression;
        return this;
    }

    @Override
    public String toString() {
        return "CHECK (" + expression + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CheckConstraint)) {
            return false;
        }
        CheckConstraint other = (CheckConstraint) o;
        return Objects.equals(normalize(expression), normalize(other.expression));
    }

    private static String normalize(String expr) {
        return expr == null ? null : expr.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalize(expression));
    }
}
