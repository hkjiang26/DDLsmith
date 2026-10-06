package org.ddlsmith.ermodel;

/**
 * Whether every instance of an entity must take part in a relationship
 * (the "min" of the classic (min,max) cardinality notation).
 */
public enum Participation {
    /** min = 0: instances may exist without taking part. */
    PARTIAL,
    /** min = 1: every instance must take part (existence dependency for the other side). */
    TOTAL;

    public int min() {
        return this == TOTAL ? 1 : 0;
    }

    public static Participation fromMin(int min) {
        return min >= 1 ? TOTAL : PARTIAL;
    }
}
