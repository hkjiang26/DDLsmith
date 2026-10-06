package org.ddlsmith.algorithm;

import org.ddlsmith.GlobalState;
import org.ddlsmith.MainOptions;

/**
 * The testing algorithms of DDLSmith.
 *
 * <ul>
 *   <li>{@code random} — generate, create, verify (the default).</li>
 *   <li>{@code evolution} — create, then evolve with random {@code ALTER TABLE} and verify after
 *       every step, which pinpoints the statement that caused a divergence.</li>
 *   <li>{@code differential} — create the same schema on several engines and compare them.</li>
 *   <li>{@code constraints} — populate the schema and probe every declared constraint.</li>
 * </ul>
 */
public final class Algorithms {

    private Algorithms() {
    }

    public static TestingAlgorithm forName(String name, MainOptions options) {
        String normalized = name == null ? "random" : name.trim().toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
        case "random":
        case "create":
            return new org.ddlsmith.algorithm.impl.RandomDDLAlgorithm(options);
        case "evolution":
        case "alter":
            return new org.ddlsmith.algorithm.impl.SchemaEvolutionAlgorithm(options);
        case "differential":
        case "compare":
            return new org.ddlsmith.algorithm.impl.DifferentialAlgorithm(options);
        case "constraints":
        case "constraint":
            return new org.ddlsmith.algorithm.impl.ConstraintFocusedAlgorithm(options);
        default:
            throw new IllegalArgumentException("unknown algorithm '" + name + "'; known algorithms: " + names());
        }
    }

    public static java.util.List<String> names() {
        return java.util.List.of("random", "evolution", "differential", "constraints");
    }
}
