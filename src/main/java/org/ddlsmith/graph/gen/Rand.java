package org.ddlsmith.graph.gen;

import org.ddlsmith.Randomly;

/**
 * Bernoulli / uniform helpers for the graph generation stage.
 *
 * <p>Every draw goes through {@link Randomly}, so a generation run stays reproducible from its
 * seed. {@link Randomly#getBoolean(double)} looks like the natural call for a Bernoulli trial, but
 * {@link Randomly#getDouble()} consults the constant cache first and that cache can hand back a
 * cached {@code long} (for example {@code 1234567.0}) instead of a value in {@code [0,1)}. A trial
 * written as {@code Randomly.getBoolean(p)} therefore fires roughly half as often as requested,
 * which would silently halve every edge density of the generated graphs.</p>
 *
 * <p>The helpers below scale a bounded {@link Randomly#getLong(long, long)} draw instead: that
 * method maps the cached value with {@code floorMod}, so it always stays inside its range, and it
 * is just as reproducible as any other {@link Randomly} call.</p>
 */
final class Rand {

    private static final long SCALE = 1_000_000L;

    private Rand() {
    }

    /** @return a uniform value in [0,1) */
    static double uniform() {
        return Randomly.getLong(0, SCALE) / (double) SCALE;
    }

    /** @return true with probability {@code p}; {@code p <= 0} never fires, {@code p >= 1} always does */
    static boolean chance(double p) {
        if (p <= 0) {
            return false;
        }
        return p >= 1 || uniform() < p;
    }
}
