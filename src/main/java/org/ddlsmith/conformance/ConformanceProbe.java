package org.ddlsmith.conformance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One assertion about a row the graph instance does <em>not</em> hold.
 *
 * <p>The {@link SemanticQuery} bank asks questions about the instance that <em>is</em> stored: the rows
 * the graph produced, the joins they form, the counts they imply. Every one of those questions is asked
 * about a legal instance, which is why the oracle can be precise and why it is blind to the opposite
 * failure: an engine that accepts what the schema forbids. This class carries the missing direction.</p>
 *
 * <p>Two kinds of assertion, and they are not symmetrical:</p>
 * <ul>
 *   <li>{@link Kind#MUST_REJECT} — the declared schema forbids the row (a reference with no parent, a
 *       second child of a one-to-one parent, a partially NULL reference the engine's own index makes
 *       unchecked), so the engine has to refuse it with an <em>integrity</em> violation. If the engine
 *       stores the row, the constraint is declared and not enforced: {@code CONSTRAINT_NOT_ENFORCED}.</li>
 *   <li>{@link Kind#MUST_ACCEPT} — the standard says the row is legal (a partially NULL composite key is
 *       not a reference at all: MySQL "in effect enforces {@code MATCH SIMPLE} semantics full-time",
 *       1.6.3.2 FOREIGN KEY Constraints), so the engine has to store it. If the engine refuses it with an
 *       integrity violation, it enforces more than the schema declares:
 *       {@code CONSTRAINT_TOO_STRICT}.</li>
 * </ul>
 *
 * <p>A probe is not a finding until the engine's answer is classified. A refusal is only evidence of
 * enforcement when it is an <em>integrity</em> refusal: a data exception (out of range {@code 1264},
 * truncation {@code 1406}) means the probe's own row was invalid, and the probe asserts nothing —
 * which is the lesson the referential-action oracle learned the hard way, having read a
 * {@code 1264} as "the declared action did not happen".</p>
 */
public final class ConformanceProbe {

    /** What the schema, and the standard, say must happen to the row. */
    public enum Kind {
        /** The schema forbids the row; the engine has to refuse it with an integrity violation. */
        MUST_REJECT,
        /** The standard allows the row; the engine has to store it. */
        MUST_ACCEPT
    }

    private final Kind kind;
    /** The grouping key of the finding: distinct per probe shape and per foreign key. */
    private final String group;
    /** The table the probe row belongs to, for the report. */
    private final String table;
    private final String description;
    private final String sql;
    /**
     * Undoes the probe when the engine accepted it, so the materialized instance is left exactly as the
     * read-only questions found it. A cleanup that fails is a note, never a finding.
     */
    private final String cleanupSql;
    private final String provenance;
    /** The values of the row the probe inserts (or the row it updates), for the report and the cleanup. */
    private final Map<String, Object> row;

    public ConformanceProbe(Kind kind, String group, String table, String description, String sql,
            String cleanupSql, String provenance, Map<String, Object> row) {
        this.kind = kind;
        this.group = group;
        this.table = table;
        this.description = description;
        this.sql = sql;
        this.cleanupSql = cleanupSql;
        this.provenance = provenance;
        this.row = row == null ? Map.of() : new LinkedHashMap<>(row);
    }

    public Kind getKind() {
        return kind;
    }

    public String getGroup() {
        return group;
    }

    public String getTable() {
        return table;
    }

    public String getDescription() {
        return description;
    }

    public String getSql() {
        return sql;
    }

    public String getCleanupSql() {
        return cleanupSql;
    }

    public String getProvenance() {
        return provenance;
    }

    public Map<String, Object> getRow() {
        return row;
    }

    @Override
    public String toString() {
        return kind + " " + group + ": " + sql;
    }
}
