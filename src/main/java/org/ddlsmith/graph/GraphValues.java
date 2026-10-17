package org.ddlsmith.graph;

import java.util.Arrays;
import java.util.Map;

/**
 * Deterministic rendering of graph property values for logs, descriptions and signatures.
 *
 * <p>A node's or edge's property map is printed in failure reports and compared as a signature, so
 * an array or blob value must render as its <em>content</em> rather than as
 * {@code [Ljava.lang.Object;@3eb77ea8}: the identity hash differs between two runs of the same seed,
 * which would make a reproducible run look irreproducible (and a failure log unreadable).</p>
 */
public final class GraphValues {

    private GraphValues() {
    }

    /** @return the value rendered by content: arrays element by element, everything else as itself */
    public static String format(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Object[] array) {
            return Arrays.deepToString(array);
        }
        if (value instanceof byte[] bytes) {
            return Arrays.toString(bytes);
        }
        return String.valueOf(value);
    }

    /** @return a property map rendered as {@code {k=v, k=v}} with {@link #format(Object)} values */
    public static String format(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(format(entry.getValue()));
        }
        return sb.append('}').toString();
    }
}
