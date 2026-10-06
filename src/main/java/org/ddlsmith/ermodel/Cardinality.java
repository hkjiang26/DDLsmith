package org.ddlsmith.ermodel;

/**
 * The maximum number of instances of an entity that can participate in one relationship instance
 * (the "max" of the classic (min,max) cardinality notation).
 */
public enum Cardinality {
    /** At most one instance (max = 1). */
    ONE,
    /** Arbitrarily many instances (max = n). */
    MANY;

    public static Cardinality fromMax(int max) {
        return max <= 1 ? ONE : MANY;
    }
}
