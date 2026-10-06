package org.ddlsmith.oracle.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.ddlsmith.ddl.DDLFeature;

/**
 * Reads and writes the <em>purpose convention</em> that DDLSmith uses to attach the DDL features a
 * statement relies on to the {@code purpose} field of
 * {@link org.ddlsmith.oracle.OracleContext.ExecutionEvent}.
 *
 * <h2>The convention</h2>
 *
 * <p>{@code ExecutionEvent.purpose} is formatted as</p>
 *
 * <pre>
 *     &lt;purpose&gt;[&lt;FEATURE&gt;,&lt;FEATURE&gt;]
 * </pre>
 *
 * <p>for example {@code create-table[COMPOSITE_PRIMARY_KEY]} or
 * {@code create-schema[COMPOSITE_FOREIGN_KEY,DEFERRABLE_FK]}. The part before the bracket is the
 * human readable purpose (as before the convention existed), and the bracketed part is a comma
 * separated list of {@link DDLFeature} names that the statement needs in order to be accepted by
 * the engine. The list may be empty or absent for statements that rely on no optional feature, and
 * a feature name that this build does not know is ignored by {@link #features(String)} (but is kept
 * by {@link #featureNames(String)}, so that a mismatch between the generator and this class is
 * visible in reports).</p>
 *
 * <p>Generators are expected to use {@link #encode(String, DDLFeature...)} (or the equivalent
 * literal syntax) when they record a statement, so that the crash oracle can attribute a failure to
 * the feature the engine claims to support. A statement whose purpose names no feature can never be
 * excused as "the dialect never declared that feature".</p>
 */
public final class FeaturePurpose {

    private FeaturePurpose() {
    }

    /**
     * @return the purpose without the bracketed feature list, e.g. {@code create-table} for
     *         {@code create-table[COMPOSITE_PRIMARY_KEY]}; {@code null} stays {@code null} and a
     *         purpose without brackets is returned unchanged
     */
    public static String base(String purpose) {
        if (purpose == null) {
            return null;
        }
        int open = purpose.lastIndexOf('[');
        if (open < 0 || !purpose.trim().endsWith("]")) {
            return purpose.trim();
        }
        return purpose.substring(0, open).trim();
    }

    /**
     * @return the features named in the bracketed list of the purpose, in the order in which they
     *         appear; unknown names are skipped
     */
    public static List<DDLFeature> features(String purpose) {
        List<DDLFeature> result = new ArrayList<>();
        for (String name : featureNames(purpose)) {
            DDLFeature feature = parse(name);
            if (feature != null && !result.contains(feature)) {
                result.add(feature);
            }
        }
        return result;
    }

    /**
     * @return the raw names in the bracketed list of the purpose, including names that are not known
     *         to this build; used for reporting
     */
    public static List<String> featureNames(String purpose) {
        List<String> result = new ArrayList<>();
        if (purpose == null) {
            return result;
        }
        String trimmed = purpose.trim();
        if (!trimmed.endsWith("]")) {
            return result;
        }
        int open = trimmed.lastIndexOf('[');
        if (open < 0 || open == trimmed.length() - 1) {
            return result;
        }
        String body = trimmed.substring(open + 1, trimmed.length() - 1);
        for (String part : body.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                result.add(name);
            }
        }
        return result;
    }

    /** @return true if the purpose marks the statement as a probe (see the crash oracle) */
    public static boolean isProbe(String purpose) {
        String base = base(purpose);
        return base != null && base.toLowerCase(Locale.ROOT).startsWith("probe-");
    }

    /** Renders a purpose with its feature list, e.g. {@code create-table[COMPOSITE_PRIMARY_KEY]}. */
    public static String encode(String purpose, DDLFeature... required) {
        return encode(purpose, java.util.Arrays.asList(required));
    }

    /** Renders a purpose with its feature list; an empty list produces the bare purpose. */
    public static String encode(String purpose, Collection<DDLFeature> required) {
        String base = base(purpose);
        if (required == null || required.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base == null ? "" : base).append('[');
        boolean first = true;
        for (DDLFeature feature : required) {
            if (!first) {
                sb.append(',');
            }
            sb.append(feature.name());
            first = false;
        }
        return sb.append(']').toString();
    }

    private static DDLFeature parse(String name) {
        try {
            return DDLFeature.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
