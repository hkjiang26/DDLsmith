package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.ddlsmith.Randomly;
import org.ddlsmith.graph.GraphGenerator;

/**
 * Registry of the graph generators, used by the command line and by the statistics code to iterate
 * over all of them.
 */
public final class GraphGenerators {

    private static final List<String> NAMES = List.of(RandomGraphGenerator.NAME, SlidingGraphGenerator.NAME,
            TreeBasedGraphGenerator.NAME, PreferentialAttachmentGraphGenerator.NAME,
            SmallWorldGraphGenerator.NAME, HybridGraphGenerator.NAME);

    private GraphGenerators() {
    }

    /**
     * @return the names of all generators, in the canonical order. A fresh mutable list, so callers
     *         may sort or filter it without affecting the registry.
     */
    public static List<String> names() {
        return new ArrayList<>(NAMES);
    }

    /**
     * @return a new instance of the named generator
     * @throws IllegalArgumentException if the name is unknown (null included)
     */
    public static GraphGenerator forName(String name) {
        if (name != null) {
            switch (name.trim().toLowerCase(Locale.ROOT)) {
            case RandomGraphGenerator.NAME:
                return new RandomGraphGenerator();
            case SlidingGraphGenerator.NAME:
                return new SlidingGraphGenerator();
            case TreeBasedGraphGenerator.NAME:
                return new TreeBasedGraphGenerator();
            case PreferentialAttachmentGraphGenerator.NAME:
                return new PreferentialAttachmentGraphGenerator();
            case SmallWorldGraphGenerator.NAME:
                return new SmallWorldGraphGenerator();
            case HybridGraphGenerator.NAME:
                return new HybridGraphGenerator();
            default:
                break;
            }
        }
        throw new IllegalArgumentException("unknown graph generator: " + name + "; known generators: " + NAMES);
    }

    /** @return a randomly chosen generator (reproducible: the choice goes through {@link Randomly}) */
    public static GraphGenerator random() {
        return forName(Randomly.fromList(NAMES));
    }
}
