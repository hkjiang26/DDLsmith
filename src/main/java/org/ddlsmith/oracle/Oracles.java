package org.ddlsmith.oracle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.ddlsmith.MainOptions;

/**
 * The registry of oracles.
 *
 * <p>Oracles are selected with {@code --oracle}. The default set is the cheap, always-meaningful
 * combination (crash detection plus the schema round trip); {@code --oracle ALL} enables every
 * oracle, which also runs the DML-heavy constraint, referential-action and pivot-row oracles.</p>
 */
public final class Oracles {

    /** Oracles that are always worth running. */
    private static final List<String> DEFAULT_NAMES = List.of("crash", "schema-round-trip", "idempotence");
    /** Oracles that need a second database to compare with. */
    private static final Set<String> DIFFERENTIAL_NAMES = Set.of("differential-schema");
    /** Oracles that insert rows into the generated schema. */
    private static final Set<String> DATA_NAMES = Set.of("constraint-enforcement", "referential-action",
            "default-value", "pivot-row");

    private static final Map<String, Supplier<TestOracle>> FACTORIES = new LinkedHashMap<>();

    static {
        FACTORIES.put("crash", org.ddlsmith.oracle.impl.CrashOracle::new);
        FACTORIES.put("schema-round-trip", org.ddlsmith.oracle.impl.SchemaRoundTripOracle::new);
        FACTORIES.put("differential-schema", org.ddlsmith.oracle.impl.DifferentialSchemaOracle::new);
        FACTORIES.put("idempotence", org.ddlsmith.oracle.impl.IdempotenceOracle::new);
        FACTORIES.put("constraint-enforcement", org.ddlsmith.oracle.impl.ConstraintEnforcementOracle::new);
        FACTORIES.put("referential-action", org.ddlsmith.oracle.impl.ReferentialActionOracle::new);
        FACTORIES.put("default-value", org.ddlsmith.oracle.impl.DefaultValueOracle::new);
        FACTORIES.put("metamorphic-alter", org.ddlsmith.oracle.impl.MetamorphicAlterOracle::new);
        FACTORIES.put("pivot-row", org.ddlsmith.oracle.impl.PivotRowOracle::new);
    }

    private Oracles() {
    }

    public static List<String> names() {
        return new ArrayList<>(FACTORIES.keySet());
    }

    /** Resolves the oracles requested by the options, filtered by what the context can support. */
    public static List<TestOracle> resolve(MainOptions options, OracleContext context) {
        List<String> requested = options.getOracles();
        boolean all = requested != null && requested.stream().anyMatch(name -> name.equalsIgnoreCase("ALL"));
        Set<String> selected = new LinkedHashSet<>();
        if (all) {
            selected.addAll(FACTORIES.keySet());
        } else if (requested == null || requested.isEmpty()) {
            selected.addAll(DEFAULT_NAMES);
            if (context.getDatabases().size() > 1) {
                selected.addAll(DIFFERENTIAL_NAMES);
            }
            if (options.getEvolveSteps() > 0) {
                selected.add("metamorphic-alter");
            }
        } else {
            for (String name : requested) {
                for (String token : name.split(",")) {
                    String trimmed = token.trim().toLowerCase(Locale.ROOT);
                    if (!trimmed.isEmpty()) {
                        selected.add(trimmed);
                    }
                }
            }
        }
        if (context.getDatabases().size() < 2) {
            selected.removeAll(DIFFERENTIAL_NAMES);
        }
        List<TestOracle> oracles = new ArrayList<>();
        for (String name : selected) {
            Supplier<TestOracle> factory = FACTORIES.get(name);
            if (factory == null) {
                System.err.println("warning: unknown oracle '" + name + "'; known oracles: " + names());
                continue;
            }
            oracles.add(factory.get());
        }
        return oracles;
    }

    /** @return true if the oracle inserts rows and therefore needs a writable database */
    public static boolean isDataOracle(String name) {
        return DATA_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }
}
