package org.ddlsmith.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * The random filling of one base graph: which attributes every node label and every relationship
 * type of the base graph carries <em>in this database</em>.
 *
 * <p>The base graph is only nodes and edges (see {@link GraphSchema}); the complexity of the ER
 * diagram comes from filling it. The filling decides, per database and independently of every other
 * database of the same graph group:</p>
 *
 * <ul>
 *   <li>how many attributes each label and each relationship type gets (from a per-database filling
 *       profile: lean, normal or wide);</li>
 *   <li>the {@link org.ddlsmith.common.schema.DataType} of every attribute, drawn from a per-database
 *       domain pool;</li>
 *   <li>whether the attribute is mandatory ({@code presenceProbability == 1.0}, i.e. a NOT NULL
 *       column) or may be absent;</li>
 *   <li>which attributes form the primary key and which form an alternate (candidate) key;</li>
 *   <li>which labels inherit the attributes of another label, which is what the ER mapper reads as a
 *       specialization (ISA) link.</li>
 * </ul>
 *
 * <p>The filling is a value: the base graph schema and the base graph instance are never mutated by
 * it, so one graph can be read as many different ER diagrams.</p>
 */
public final class GraphFilling {

    private final List<List<GraphProperty>> labelAttributes = new ArrayList<>();
    private final List<List<GraphProperty>> typeAttributes = new ArrayList<>();
    private final List<List<Integer>> parents = new ArrayList<>();

    public GraphFilling(int labelCount, int relationshipTypeCount) {
        for (int i = 0; i < Math.max(0, labelCount); i++) {
            labelAttributes.add(new ArrayList<>());
            parents.add(new ArrayList<>());
        }
        for (int i = 0; i < Math.max(0, relationshipTypeCount); i++) {
            typeAttributes.add(new ArrayList<>());
        }
    }

    /* ------------------------------------------------------------------ attributes */

    /** @return the attributes of a node label, in the order the filling created them */
    public List<GraphProperty> attributesOfLabel(int labelIndex) {
        if (labelIndex < 0 || labelIndex >= labelAttributes.size()) {
            return List.of();
        }
        return labelAttributes.get(labelIndex);
    }

    public GraphFilling addLabelAttribute(int labelIndex, GraphProperty property) {
        if (labelIndex >= 0 && labelIndex < labelAttributes.size()) {
            labelAttributes.get(labelIndex).add(property);
        }
        return this;
    }

    /** @return the attributes of a relationship type, in the order the filling created them */
    public List<GraphProperty> attributesOfRelationshipType(int typeIndex) {
        if (typeIndex < 0 || typeIndex >= typeAttributes.size()) {
            return List.of();
        }
        return typeAttributes.get(typeIndex);
    }

    public GraphFilling addTypeAttribute(int typeIndex, GraphProperty property) {
        if (typeIndex >= 0 && typeIndex < typeAttributes.size()) {
            typeAttributes.get(typeIndex).add(property);
        }
        return this;
    }

    public int getLabelCount() {
        return labelAttributes.size();
    }

    /* ------------------------------------------------------------------ keys */

    /** @return the attributes the filling marked as the primary key of a label (possibly empty) */
    public List<GraphProperty> primaryKeyOf(int labelIndex) {
        List<GraphProperty> key = new ArrayList<>();
        for (GraphProperty property : attributesOfLabel(labelIndex)) {
            if (property.isPrimaryKey()) {
                key.add(property);
            }
        }
        return key;
    }

    /** @return the attributes the filling marked as an alternate key of a label (possibly empty) */
    public List<GraphProperty> alternateKeyOf(int labelIndex) {
        List<GraphProperty> key = new ArrayList<>();
        for (GraphProperty property : attributesOfLabel(labelIndex)) {
            if (property.isAlternateKey()) {
                key.add(property);
            }
        }
        return key;
    }

    /* ------------------------------------------------------------------ inheritance */

    /**
     * @return the labels this label inherits the attributes of, i.e. the specialization links this
     *         database realizes; more than one entry is the shared subclass (multiple inheritance)
     *         case, and a parent may itself have parents, which makes multi-level hierarchies
     *         reachable
     */
    public List<Integer> parentsOf(int labelIndex) {
        if (labelIndex < 0 || labelIndex >= parents.size()) {
            return List.of();
        }
        return parents.get(labelIndex);
    }

    public GraphFilling addParent(int labelIndex, int parentLabelIndex) {
        if (labelIndex >= 0 && labelIndex < parents.size() && !parents.get(labelIndex).contains(parentLabelIndex)) {
            parents.get(labelIndex).add(parentLabelIndex);
        }
        return this;
    }

    /* ------------------------------------------------------------------ reporting */

    /**
     * @return a one-line, content-derived signature of the filling. Two databases that filled their
     *         (identical) base graph the same way produce the same signature, which is what the run
     *         summary counts as "distinct fillings".
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int label = 0; label < labelAttributes.size(); label++) {
            sb.append('L').append(label);
            if (!parents.get(label).isEmpty()) {
                sb.append("<-").append(parents.get(label));
            }
            sb.append('[');
            for (GraphProperty property : labelAttributes.get(label)) {
                sb.append(property).append(',');
            }
            sb.append("] ");
        }
        for (int type = 0; type < typeAttributes.size(); type++) {
            sb.append('R').append(type).append('[');
            for (GraphProperty property : typeAttributes.get(type)) {
                sb.append(property).append(',');
            }
            sb.append("] ");
        }
        return sb.toString().trim();
    }

    @Override
    public String toString() {
        return "GraphFilling[" + describe() + "]";
    }
}
