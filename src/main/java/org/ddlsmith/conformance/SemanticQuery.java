package org.ddlsmith.conformance;

import java.util.List;

import org.ddlsmith.common.schema.DataType;

/**
 * One query of the semantic bank, together with the answer DDLSmith computed for it from the
 * materialized graph instance.
 *
 * <p>Three shapes exist, all of them black-box SQL:</p>
 * <ul>
 *   <li>{@link Kind#MULTISET} — the engine must return exactly the expected multiset of tuples
 *       (a table's rows, or the rows of a join).</li>
 *   <li>{@link Kind#EMPTY} — the engine must return nothing (an anti-join over a foreign key must
 *       find no dangling reference). This is the "must be empty" shape the Emptee oracle made
 *       popular, applied to a declared reference instead of to a rewritten query.</li>
 *   <li>{@link Kind#UNFOLD} — two queries the schema says mean the same thing (a view and its body)
 *       must return the same rows.</li>
 * </ul>
 *
 * <p>The expected tuples are canonical strings (see
 * {@link org.ddlsmith.data.DataModel#canonical(Object, DataType)}), so the comparison does not
 * depend on the Java type a driver chose or on the spelling of a value.</p>
 */
public final class SemanticQuery {

    public enum Kind {
        /** The result must equal the expected multiset. */
        MULTISET,
        /** The result must be empty. */
        EMPTY,
        /** The result must equal the result of {@link #getReferenceSql()}. */
        UNFOLD
    }

    private final Kind kind;
    /** The finding group: what part of the schema semantics this query checks. */
    private final String group;
    /** A one-line explanation of what the query means, written into the failure report. */
    private final String description;
    private final String sql;
    /** The expected tuples, canonical, for {@link Kind#MULTISET}. */
    private final List<String> expected;
    /** The declared types of the selected columns, in order, used to canonicalize the result. */
    private final List<DataType> types;
    /** The second query of an {@link Kind#UNFOLD} check. */
    private final String referenceSql;
    /** Which graph element or ER construct the query is about. */
    private final String provenance;

    public SemanticQuery(Kind kind, String group, String description, String sql, List<String> expected,
            List<DataType> types, String referenceSql, String provenance) {
        this.kind = kind;
        this.group = group;
        this.description = description;
        this.sql = sql;
        this.expected = expected;
        this.types = types;
        this.referenceSql = referenceSql;
        this.provenance = provenance;
    }

    public Kind getKind() {
        return kind;
    }

    public String getGroup() {
        return group;
    }

    public String getDescription() {
        return description;
    }

    public String getSql() {
        return sql;
    }

    public List<String> getExpected() {
        return expected;
    }

    public List<DataType> getTypes() {
        return types;
    }

    public String getReferenceSql() {
        return referenceSql;
    }

    public String getProvenance() {
        return provenance;
    }

    @Override
    public String toString() {
        return kind + " " + group + ": " + sql;
    }
}
