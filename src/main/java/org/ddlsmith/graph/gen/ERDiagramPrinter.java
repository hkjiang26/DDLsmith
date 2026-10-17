package org.ddlsmith.graph.gen;

import java.util.List;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.graph.GraphEdge;
import org.ddlsmith.graph.GraphFilling;
import org.ddlsmith.graph.GraphLabel;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphProperty;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;

/**
 * Compact, dependency-free rendering of a generated graph and of the ER diagram derived from it.
 *
 * <p>{@link #describe(ERDiagram)} and {@link #describe(GraphModel, GraphSchema)} are meant for
 * failure logs: they print the entities with their attributes, the relationships with their
 * cardinalities and the structural statistics of the diagram (respectively of the graph), so that a
 * failing seed can be understood without re-running it. {@link #toMermaid(ERDiagram)} renders the
 * same diagram as a Mermaid {@code erDiagram} block for documentation.</p>
 */
public final class ERDiagramPrinter {

    private ERDiagramPrinter() {
    }

    /* ------------------------------------------------------------------ ER diagram */

    /** @return a multi-line description of {@code diagram}, including its statistics */
    public static String describe(ERDiagram diagram) {
        StringBuilder sb = new StringBuilder();
        sb.append("ER diagram ").append(diagram.getName()).append('\n');
        sb.append("  entities (").append(diagram.getEntities().size()).append("):\n");
        for (EREntity entity : diagram.getEntities()) {
            sb.append("    ").append(entity.getName());
            if (entity.isWeak()) {
                sb.append(" WEAK owner=").append(entity.getOwnerEntity())
                        .append(" via ").append(entity.getOwnerRelationship());
            }
            if (entity.isGeneralizationSubtype()) {
                // a subtype lists only the attributes it adds of its own; the inherited ones belong
                // to the supertype and are not repeated here
                sb.append(" SUBTYPE-OF ").append(entity.getSuperEntities())
                        .append(" (inherits their attributes)");
            }
            if (entity.isGeneralizationSupertype()) {
                sb.append(" SUPERTYPE-OF ").append(entity.getSubEntities())
                        .append(entity.isDisjoint() ? " DISJOINT" : " OVERLAPPING")
                        .append(entity.isTotalSpecialization() ? " TOTAL-SPECIALIZATION" : " PARTIAL-SPECIALIZATION");
                if (entity.getDiscriminatorAttribute() != null) {
                    sb.append(" discriminator=").append(entity.getDiscriminatorAttribute());
                }
            }
            if (entity.isCategory()) {
                sb.append(" CATEGORY-OF ").append(entity.getCategorySupers());
            }
            if (entity.isAggregation()) {
                sb.append(" AGGREGATION-OF ").append(entity.getAggregatedRelationship());
            }
            sb.append('\n');
            for (ERAttribute attribute : entity.getAttributes()) {
                sb.append("      ").append(attribute).append('\n');
            }
        }
        sb.append("  relationships (").append(diagram.getRelationships().size()).append("):\n");
        for (ERRelationship relationship : diagram.getRelationships()) {
            sb.append("    ").append(relationship.getName()).append('(');
            List<ERParticipant> participants = relationship.getParticipants();
            for (int i = 0; i < participants.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(participants.get(i));
            }
            sb.append(')');
            if (relationship.isIdentifying()) {
                sb.append(" IDENTIFYING");
            }
            if (relationship.isGeneralization()) {
                sb.append(" GENERALIZATION");
            }
            if (relationship.isCategoryLink()) {
                sb.append(" CATEGORY-LINK");
            }
            if (relationship.isAggregationLink()) {
                sb.append(" AGGREGATION-LINK");
            }
            if (!relationship.getMergedTypeNames().isEmpty()) {
                sb.append(" MERGED-FROM ").append(relationship.getMergedTypeNames());
            }
            sb.append('\n');
            for (ERAttribute attribute : relationship.getAttributes()) {
                sb.append("      ").append(attribute).append('\n');
            }
        }
        sb.append("  statistics: ").append(diagram.getStatistics()).append('\n');
        return sb.toString();
    }

    /* ------------------------------------------------------------------ graph */

    /**
     * @return a multi-line description of {@code graph}, {@code schema} and the {@code filling} this
     *         database gave them, with graph statistics
     */
    public static String describe(GraphModel graph, GraphSchema schema, GraphFilling filling) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph ").append(graph.getGeneratorName())
                .append(" (targetEdges=").append(graph.getTargetEdgeCount()).append(")\n");
        sb.append("  labels (").append(schema.getLabelCount()).append("):\n");
        for (GraphLabel label : schema.getLabels()) {
            sb.append("    ").append(label.getName())
                    .append(" nodes=").append(graph.getNodesOfLabel(label.getIndex()).size());
            if (filling != null && !filling.parentsOf(label.getIndex()).isEmpty()) {
                sb.append(" inherits=L").append(filling.parentsOf(label.getIndex()));
            }
            sb.append('\n');
            if (filling != null) {
                for (GraphProperty slot : filling.attributesOfLabel(label.getIndex())) {
                    sb.append("      ").append(slot).append('\n');
                }
            }
        }
        sb.append("  relationship types (").append(schema.getRelationshipTypeCount()).append("):\n");
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            sb.append("    ").append(type)
                    .append(" edges=").append(graph.getEdgesOfType(type.getIndex()).size()).append('\n');
            if (filling != null) {
                for (GraphProperty slot : filling.attributesOfRelationshipType(type.getIndex())) {
                    sb.append("      ").append(slot).append('\n');
                }
            }
        }
        sb.append("  nodes:\n");
        for (int i = 0; i < graph.getNodeCount(); i++) {
            sb.append("    ").append(graph.getNode(i)).append('\n');
        }
        sb.append("  edges:\n");
        for (GraphEdge edge : graph.getEdges()) {
            sb.append("    ").append(edge).append('\n');
        }
        sb.append("  statistics: ").append(graph.getStatistics()).append('\n');
        return sb.toString();
    }

    /* ------------------------------------------------------------------ mermaid */

    /**
     * @return a Mermaid {@code erDiagram} block. Types and names are sanitised to single tokens
     *         ({@code varchar(20)} becomes {@code varchar_20_}) because Mermaid's attribute grammar
     *         does not accept punctuation. Composite attributes are flattened, derived and
     *         multi-valued attributes are kept with a comment saying so, aggregation entities are
     *         rendered with the attributes of the relationship they aggregate, and relationships
     *         with more than two participants are rendered for their first two participants plus a
     *         comment. A subtype lists only the attributes it adds of its own - Mermaid has no
     *         inheritance notation - so the inherited ones are announced in a comment.
     */
    public static String toMermaid(ERDiagram diagram) {
        StringBuilder sb = new StringBuilder("erDiagram\n");
        for (EREntity entity : diagram.getEntities()) {
            if (entity.isGeneralizationSubtype()) {
                sb.append("    %% ").append(sanitize(entity.getName())).append(" is a subtype of ")
                        .append(entity.getSuperEntities())
                        .append(" and inherits their attributes; only its own are listed below\n");
            }
            if (entity.isCategory()) {
                sb.append("    %% ").append(sanitize(entity.getName())).append(" is a union type (category) of ")
                        .append(entity.getCategorySupers()).append('\n');
            }
            if (entity.isAggregation()) {
                sb.append("    %% ").append(sanitize(entity.getName())).append(" aggregates the relationship ")
                        .append(entity.getAggregatedRelationship()).append('\n');
            }
            sb.append("    ").append(sanitize(entity.getName())).append(" {\n");
            for (ERAttribute attribute : entity.getAttributes()) {
                appendMermaidAttribute(sb, attribute);
            }
            if (entity.isAggregation()) {
                // an aggregation stands for its relationship, so it shows that relationship's attributes
                ERRelationship aggregated = diagram.getRelationship(entity.getAggregatedRelationship());
                if (aggregated != null) {
                    for (ERAttribute attribute : aggregated.getAttributes()) {
                        appendMermaidAttribute(sb, attribute);
                    }
                }
            }
            sb.append("    }\n");
        }
        for (ERRelationship relationship : diagram.getRelationships()) {
            List<ERParticipant> participants = relationship.getParticipants();
            if (participants.size() < 2) {
                sb.append("    %% ").append(relationship.getName())
                        .append(" skipped: fewer than two participants\n");
                continue;
            }
            ERParticipant left = participants.get(0);
            ERParticipant right = participants.get(1);
            sb.append("    ").append(sanitize(left.getEntityName())).append(' ').append(cardinalityToken(left))
                    .append(relationship.isIdentifying() ? "--" : "..").append(cardinalityToken(right))
                    .append(' ').append(sanitize(right.getEntityName()))
                    .append(" : ").append(sanitize(relationship.getName()));
            if (relationship.isGeneralization()) {
                sb.append(" (ISA)");
            }
            if (relationship.isCategoryLink()) {
                sb.append(" (category link)");
            }
            if (relationship.isAggregationLink()) {
                sb.append(" (aggregation link)");
            }
            sb.append('\n');
            if (participants.size() > 2) {
                sb.append("    %% ").append(relationship.getName()).append(" is n-ary (")
                        .append(participants.size()).append(" participants); only the first two are shown\n");
            }
        }
        return sb.toString();
    }

    private static void appendMermaidAttribute(StringBuilder sb, ERAttribute attribute) {
        if (attribute.isComposite() && !attribute.getComponents().isEmpty()) {
            // composites are flattened into one line per component, which is what the mapping does
            for (ERAttribute component : attribute.getComponents()) {
                appendMermaidAttribute(sb, component);
            }
            return;
        }
        if (attribute.isDerived()) {
            sb.append("        ").append(mermaidType(attribute.getType())).append(' ')
                    .append(sanitize(attribute.getName())).append(" \"derived\"\n");
            return;
        }
        sb.append("        ").append(mermaidType(attribute.getType())).append(' ')
                .append(sanitize(attribute.getName()));
        if (attribute.isPrimaryKey() || attribute.isPartialKey()) {
            sb.append(" PK");
        }
        if (attribute.isMultivalued()) {
            // a multi-valued attribute is not a column of this table: it gets a table of its own
            sb.append(" \"multi-valued\"");
        }
        sb.append('\n');
    }

    private static String mermaidType(DataType type) {
        return sanitize(type == null ? "unknown" : type.getName());
    }

    /** @return the Mermaid cardinality token for a participant, e.g. {@code |o} / {@code }| */
    private static String cardinalityToken(ERParticipant participant) {
        boolean one = participant.max() == 1;
        boolean total = participant.min() >= 1;
        if (one) {
            return total ? "||" : "o|";
        }
        return total ? "}|" : "o{";
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        return sb.toString();
    }
}
