package org.ddlsmith.bug;

import java.util.Locale;
import java.util.regex.Pattern;

import org.ddlsmith.oracle.OracleFailure;

/**
 * Identifies a defect by <em>what it is</em> rather than by the run that found it.
 *
 * <p>This is what makes the bug directory useful. A single engine defect — say DuckDB rejecting
 * {@code ON DELETE CASCADE} — shows up in dozens of generated databases, and the failure titles differ
 * every time only because the generated identifiers differ ({@code MISSING_INDEX at t_3_r.idx([t_3_a10,
 * t_1_a4])} versus {@code MISSING_INDEX at t_0_r.idx([t_0_a1, a2])}). Filing one report per occurrence
 * would bury the defect in noise; normalizing the generated names away turns the occurrences into
 * evidence for one report.</p>
 *
 * <p>Normalization is deliberately moderate. It removes names DDLSmith invented (tables, columns,
 * index names, sequence and view names, literal numbers) and keeps everything that describes the
 * defect: the failure kind, the oracle, and the words of the title.</p>
 */
public final class BugSignature {

    /** Generated table names: {@code t_0}, {@code t_12}, {@code t_3_r}, {@code mv_e0_0}, {@code t_new_4}. */
    private static final Pattern TABLE = Pattern.compile("\\b(?:t|t_new|mv_e\\d+|mv_r\\d+)_\\d+(_r)?\\b");
    /** Generated column names: {@code a0}, {@code a26}, {@code e0_dst_a0}, {@code t_1_a4}, {@code id_2}. */
    private static final Pattern COLUMN = Pattern.compile("\\b(?:[a-z]\\d+|[a-z]\\d+_[a-z_]*\\d+|[a-z]+_\\d+_a\\d+)\\b");
    /** Generated constraint and index names: {@code idx_t_0_0}, {@code uidx_t_5_r_2}, {@code pk_t_0_0}. */
    private static final Pattern NAMED_OBJECT = Pattern.compile("\\b(?:idx|uidx|pk|uq|fk|ck|ev_idx|ev_v)_[a-z0-9_]+\\b");
    /** Generated view, sequence and surrogate-key names. */
    private static final Pattern VIEW_SEQUENCE = Pattern.compile("\\b(?:v_\\d+|seq_\\w+|seq_new_\\d+|id(?:_\\d+)?)\\b");
    /** Any remaining bare number, including seeds and row counts. */
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\b");
    /**
     * A run of feature names, as printed by the crash oracle's title. Which features happen to be
     * declared varies with the generated schema, so the list must not be part of the identity — the
     * defect is "the engine rejected a statement it declared support for", whatever the list says.
     */
    private static final Pattern FEATURE_LIST = Pattern.compile(
            "\\b[a-z][a-z0-9_]*(?:\\s*,\\s*[a-z][a-z0-9_]*){2,}\\b");
    /** The generated database name, e.g. {@code database3}. */
    private static final Pattern DATABASE_NAME = Pattern.compile("\\b(?:database|db)\\s*_?\\d+\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private BugSignature() {
    }

    /**
     * @return a stable key for the defect described by {@code failure} on {@code engine}. Two failures
     *         share a key exactly when they are the same defect reported in different words.
     */
    public static String of(String engine, OracleFailure failure) {
        String normalizedTitle = normalize(failure.getTitle());
        return normalizeEngine(engine) + "|" + failure.getKind() + "|" + failure.getOracleName() + "|"
                + normalizedTitle;
    }

    /** @return the title with every generated identifier replaced by a placeholder */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String result = text.toLowerCase(Locale.ROOT);
        result = DATABASE_NAME.matcher(result).replaceAll("<database>");
        result = FEATURE_LIST.matcher(result).replaceAll("<features>");
        result = NAMED_OBJECT.matcher(result).replaceAll("<object>");
        result = TABLE.matcher(result).replaceAll("<table>");
        result = VIEW_SEQUENCE.matcher(result).replaceAll("<key>");
        result = COLUMN.matcher(result).replaceAll("<column>");
        result = NUMBER.matcher(result).replaceAll("<n>");
        result = WHITESPACE.matcher(result).replaceAll(" ");
        return result.trim();
    }

    /** @return a lower-cased engine name without any version suffix, for use in a file path */
    public static String normalizeEngine(String engine) {
        if (engine == null || engine.isBlank()) {
            return "unknown";
        }
        String result = engine.toLowerCase(Locale.ROOT).trim();
        int at = result.indexOf('@');
        if (at > 0) {
            result = result.substring(0, at);
        }
        return result.replaceAll("[^a-z0-9_.-]", "_");
    }

    /**
     * @return a short, human readable slug of the title, safe to use in a file name, so that a report
     *         can be recognised from its name alone
     */
    public static String slug(String title) {
        if (title == null || title.isBlank()) {
            return "defect";
        }
        String result = normalize(title).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (result.length() > 60) {
            result = result.substring(0, 60).replaceAll("-+$", "");
        }
        return result.isBlank() ? "defect" : result;
    }
}
