package org.ddlsmith.graph.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.graph.GraphEdge;
import org.ddlsmith.graph.GraphFilling;
import org.ddlsmith.graph.GraphLabel;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphNode;
import org.ddlsmith.graph.GraphProperty;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.GraphToERMapper;

/**
 * Interprets a generated base graph plus its filling as an ER diagram.
 *
 * <p>Entities and relationships come straight from the base graph: one entity {@code E<i>} per label
 * {@code L<i>} (with one attribute {@code a<j>} per attribute slot {@code k<j>} the filling gave that
 * label), one relationship {@code R<t>} per relationship type that actually has edges in the graph
 * instance. A recursive type yields a binary relationship whose two participants carry the roles
 * {@code <entity>_src} and {@code <entity>_dst}, so the mapping stage sees an ordinary binary
 * relationship instead of an unary one.</p>
 *
 * <p>The attributes of an entity or relationship are not part of the base graph: they are drawn per
 * database by the {@link RandomGraphFiller}, which decides how many there are, which domain each one
 * has, whether it is mandatory and which of them form the primary and the alternate key. The mapper
 * reads them from the {@link GraphFilling}; only the structural features below are derived from the
 * graph itself.</p>
 *
 * <p>The structural features of the ER model are derived from the <em>instance</em>, not only from
 * the schema, and every heuristic below is deliberately conservative - it fires only when the graph
 * really shows the pattern:</p>
 *
 * <ul>
 *   <li><b>Keys.</b> The filling decides which mandatory attributes identify the entity: one or two
 *       of them become the primary key (a composite natural key exercises multi-column keys all the
 *       way down the mapping), and one or two further mandatory attributes may become an
 *       <em>alternate</em> key, which the relational mapping emits as a {@code UNIQUE} constraint.
 *       Only when the filling left a label without a primary key does the mapper fall back to the
 *       historic rule - a random mandatory attribute, or the synthetic key {@code id} when there is
 *       none.</li>
 *   <li><b>Weak entities.</b> A label becomes weak when all of the following hold: it has no
 *       mandatory property slot, so its key had to be synthesised; exactly one relationship type is
 *       incident to it; that type leaves the label ({@code from} side) towards another label and is
 *       {@code fromCardinality == ONE} there, i.e. the label is the "one" side of the link; and
 *       every node of the label has exactly one outgoing edge of that type, so each instance really
 *       depends on exactly one owner. The synthetic key then becomes a partial key - sometimes
 *       accompanied by a second synthetic partial key, giving a composite discriminator - and the
 *       owner relationship is flagged as identifying. The graph generators produce this shape through
 *       a 1:1 relationship type that is TOTAL on the from side (each dependent instance must have an
 *       owner); a 1:N type whose instances happen to give every node exactly one outgoing edge
 *       qualifies as well.</li>
 *   <li><b>Generalization (ISA).</b> Label {@code A} becomes a subtype of label {@code B} when
 *       {@code A}'s attribute slots are a strict superset of {@code B}'s - which is exactly how the
 *       filling realizes a specialization: it copies the supertype's slots into the subtype and adds
 *       at least one attribute of its own - and a relationship type {@code A -> B} exists with
 *       {@code ONE/ONE} cardinalities and TOTAL participation on the {@code A} side, with probability
 *       0.5. A label that inherited from <em>two</em> parents can take part in two such links and
 *       becomes a <b>shared subclass</b> (multiple inheritance), provided the two supertypes are not
 *       already ISA-connected. Hierarchies are kept at most {@value #MAX_ISA_DEPTH} levels deep.
 *       Disjointness and completeness (total vs. partial specialization) are drawn at random, and the
 *       discriminator is a random attribute of the supertype. The subtype then keeps only the
 *       attributes it <em>adds</em>: the copies it was built with are removed, because in the ER
 *       model a subtype inherits its supertype's attributes rather than re-declaring them (see
 *       {@link #removeInheritedAttributes}).</li>
 *   <li><b>Union types (categories).</b> A label linked to two or more other labels by 1:1 types
 *       that are PARTIAL on its own side becomes a category over two of them: every instance
 *       belongs to at least one superclass. The links become category links - superclass membership
 *       rather than associations.</li>
 *   <li><b>Multi-valued attributes.</b> A recursive relationship type all of whose edges are
 *       self-loops, with {@code toCardinality == MANY}, is turned into an attribute
 *       {@code mv_<R>} of the label with probability 0.5 instead of a relationship; with
 *       probability 0.35 the attribute is composite, its components taken from the type's first two
 *       attribute slots. The type of a single-valued attribute is the type of the last attribute slot
 *       of the relationship type (relationship attributes are not side-specific in the filling), or
 *       {@code VARCHAR(20)} when the type has no slots; the attribute is optional, because a node
 *       without a self-loop of that type simply has no values for it.</li>
 *   <li><b>Derived attributes.</b> With probability 0.15 an entity that owns at least two numeric
 *       attributes has its last non-key numeric attribute marked as derived, with the expression
 *       {@code "<first numeric attribute> + 1"}.</li>
 *   <li><b>Composite attributes.</b> With probability 0.15 an entity that owns at least two
 *       non-key attributes of the same type gets them grouped into a new composite attribute
 *       {@code comp_<i>}; with probability 0.3 and a third same-typed attribute, one component is
 *       itself made composite (a multi-level/nested composite). The two originals are removed from
 *       the attribute list. The relational mapper flattens composites - recursively - so this
 *       changes the ER shape without changing the column set it implies.</li>
 *   <li><b>Ternary relationships.</b> Three labels pairwise connected by three relationship types,
 *       all instantiated, are with probability 0.5 merged into one ternary relationship named
 *       {@code Rtern_<i>_<j>_<k>} that carries the merged attributes; the n-ary mapping path is
 *       otherwise unreachable from a binary graph.</li>
 *   <li><b>Aggregation.</b> The same triangle can instead be read as an aggregation: with
 *       probability 0.4 the M:N side is kept as a relationship and aggregated - it becomes the
 *       synthetic entity {@code Agg_<R>} - while the two links of the third label are absorbed
 *       into one outer relationship between that label and the aggregation.</li>
 * </ul>
 *
 * <p>Invariants guaranteed for every diagram produced here: every participant references an entity
 * that exists in the diagram, no relationship has fewer than two participants, and every entity
 * standing for a graph label has at least one attribute (a synthetic aggregation entity has none -
 * its "attributes" are those of the relationship it aggregates).</p>
 */
public class DefaultGraphToERMapper implements GraphToERMapper {

    /** Name of the surrogate key added when a label has no mandatory slot. */
    public static final String SYNTHETIC_KEY_NAME = "id";

    private static final double MULTIVALUED_PROBABILITY = 0.5;
    private static final double MULTIVALUED_COMPOSITE_PROBABILITY = 0.35;
    private static final double GENERALIZATION_PROBABILITY = 0.5;
    private static final double DERIVED_PROBABILITY = 0.15;
    private static final double COMPOSITE_PROBABILITY = 0.15;
    private static final double NESTED_COMPOSITE_PROBABILITY = 0.3;
    /** Probability that an entity with at least two mandatory attributes gets a composite key. */
    private static final double COMPOSITE_KEY_PROBABILITY = 0.3;
    /** Probability that a detected weak entity gets a second (composite) partial key attribute. */
    private static final double COMPOSITE_PARTIAL_KEY_PROBABILITY = 0.4;
    /** Probability that a triangle of pairwise-connected labels is merged into one ternary relationship. */
    private static final double TERNARY_PROBABILITY = 0.5;
    /** Probability that a triangle is instead read as an aggregation of its M:N side. */
    private static final double AGGREGATION_PROBABILITY = 0.4;
    /** Probability that a label with two partial 1:1 links becomes a union type (category). */
    private static final double CATEGORY_PROBABILITY = 0.5;
    /** Maximum number of entity levels in one generalization hierarchy or lattice. */
    private static final int MAX_ISA_DEPTH = 3;
    private static final DataType MULTIVALUED_FALLBACK_TYPE = DataType.varchar(20);

    /**
     * The filling of the graph currently being mapped. Every attribute of the diagram comes from it,
     * which is why it is stored for the duration of one {@link #map} call - the mapper itself is
     * stateless between calls and safe to share.
     */
    private GraphFilling filling;

    @Override
    public ERDiagram map(GraphModel graph, GraphSchema schema, GraphFilling filling) {
        this.filling = filling != null ? filling
                : new GraphFilling(schema == null ? 0 : schema.getLabelCount(),
                        schema == null ? 0 : schema.getRelationshipTypeCount());
        ERDiagram diagram = new ERDiagram().setName("er_" + graph.getGeneratorName());
        Map<Integer, EREntity> entities = new LinkedHashMap<>();
        Set<Integer> syntheticKeys = new LinkedHashSet<>();
        createEntities(diagram, schema, entities, syntheticKeys);
        Set<Integer> multivaluedTypes = convertSelfLoopTypes(diagram, graph, schema, entities);
        Map<Integer, ERRelationship> relationships = createRelationships(diagram, graph, schema, multivaluedTypes);
        detectWeakEntities(graph, schema, entities, syntheticKeys, relationships);
        detectCategories(graph, schema, entities, diagram, relationships);
        // aggregations are synthesized before ternaries: both read a triangle of labels, but the
        // aggregation needs the rarer shape (an M:N side plus two links), so it gets first refusal
        Set<Integer> consumedTypes = synthesizeAggregations(diagram, graph, schema, entities, relationships);
        synthesizeTernaries(diagram, graph, schema, relationships, consumedTypes);
        detectGeneralizations(schema, entities, diagram, relationships);
        markDerivedAttributes(diagram);
        groupCompositeAttributes(diagram);
        return diagram;
    }

    /* ------------------------------------------------------------------ entities */

    private void createEntities(ERDiagram diagram, GraphSchema schema, Map<Integer, EREntity> entities,
            Set<Integer> syntheticKeys) {
        for (GraphLabel label : schema.getLabels()) {
            EREntity entity = new EREntity("E" + label.getIndex());
            // the entity stands for a label, i.e. a type, not for one particular graph node
            entity.setGraphNodeId(-1);
            List<ERAttribute> mandatory = new ArrayList<>();
            for (GraphProperty slot : filling.attributesOfLabel(label.getIndex())) {
                ERAttribute attribute = new ERAttribute(attributeName(slot), slot.getType());
                attribute.setOptional(!slot.isMandatory());
                attribute.setGraphSlot(slot.getKey());
                attribute.setPrimaryKey(slot.isPrimaryKey());
                attribute.setAlternateKey(slot.isAlternateKey());
                entity.addAttribute(attribute);
                if (slot.isMandatory()) {
                    mandatory.add(attribute);
                }
            }
            if (mandatory.isEmpty()) {
                ERAttribute key = new ERAttribute(SYNTHETIC_KEY_NAME, DataType.integer());
                key.setPrimaryKey(true);
                entity.addAttribute(key);
                syntheticKeys.add(label.getIndex());
            } else if (!entity.hasPrimaryKey()) {
                // the filling left the key open - a hand-built filling, or a label whose mandatory
                // attributes were all inherited - so the historic rule applies
                if (mandatory.size() >= 2 && Rand.chance(COMPOSITE_KEY_PROBABILITY)) {
                    // a composite (multi-attribute) natural key: two mandatory attributes together
                    // identify the entity, the classic "the pair is unique, neither alone is"
                    List<ERAttribute> shuffled = new ArrayList<>(mandatory);
                    Randomly.shuffle(shuffled);
                    shuffled.get(0).setPrimaryKey(true);
                    shuffled.get(1).setPrimaryKey(true);
                } else {
                    Randomly.fromList(mandatory).setPrimaryKey(true);
                }
            }
            diagram.addEntity(entity);
            entities.put(label.getIndex(), entity);
        }
    }

    /* ------------------------------------------------------------------ multi-valued attributes */

    /**
     * Turns recursive relationship types whose edges are all self-loops into multi-valued
     * attributes, some of them composite (a multi-valued attribute of several components).
     *
     * @return the indices of the relationship types that were converted (they produce no
     *         relationship)
     */
    private Set<Integer> convertSelfLoopTypes(ERDiagram diagram, GraphModel graph, GraphSchema schema,
            Map<Integer, EREntity> entities) {
        Set<Integer> converted = new LinkedHashSet<>();
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            if (!type.isRecursive() || type.getToCardinality() != Cardinality.MANY) {
                continue;
            }
            List<GraphEdge> edges = graph.getEdgesOfType(type.getIndex());
            if (edges.isEmpty() || !allSelfLoops(edges)) {
                continue;
            }
            EREntity entity = entities.get(type.getFromLabelIndex());
            if (entity == null || !Rand.chance(MULTIVALUED_PROBABILITY)) {
                continue;
            }
            ERAttribute attribute = new ERAttribute("mv_" + type.getName(),
                    multivaluedAttributeType(type.getIndex()));
            attribute.setMultivalued(true);
            attribute.setOptional(true);
            List<GraphProperty> slots = filling.attributesOfRelationshipType(type.getIndex());
            if (slots.size() >= 2 && Rand.chance(MULTIVALUED_COMPOSITE_PROBABILITY)) {
                // a multi-valued composite attribute: each value is a tuple of the first two slots
                attribute.setComposite(true);
                for (int slot = 0; slot < 2; slot++) {
                    ERAttribute component = new ERAttribute(attributeName(slots.get(slot)),
                            slots.get(slot).getType());
                    component.setOptional(!slots.get(slot).isMandatory());
                    component.setGraphSlot(slots.get(slot).getKey());
                    attribute.addComponent(component);
                }
            }
            entity.addAttribute(attribute);
            converted.add(type.getIndex());
        }
        return converted;
    }

    private boolean allSelfLoops(List<GraphEdge> edges) {
        for (GraphEdge edge : edges) {
            if (!edge.isSelfLoop()) {
                return false;
            }
        }
        return true;
    }

    private DataType multivaluedAttributeType(int typeIndex) {
        List<GraphProperty> slots = filling.attributesOfRelationshipType(typeIndex);
        if (slots.isEmpty()) {
            return MULTIVALUED_FALLBACK_TYPE;
        }
        return slots.get(slots.size() - 1).getType();
    }

    /* ------------------------------------------------------------------ relationships */

    private Map<Integer, ERRelationship> createRelationships(ERDiagram diagram, GraphModel graph, GraphSchema schema,
            Set<Integer> skippedTypes) {
        Map<Integer, ERRelationship> relationships = new LinkedHashMap<>();
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            if (skippedTypes.contains(type.getIndex()) || graph.getEdgesOfType(type.getIndex()).isEmpty()) {
                continue;
            }
            if (type.getFromLabelIndex() < 0 || type.getFromLabelIndex() >= schema.getLabelCount()
                    || type.getToLabelIndex() < 0 || type.getToLabelIndex() >= schema.getLabelCount()) {
                continue;
            }
            ERRelationship relationship = new ERRelationship("R" + type.getIndex());
            relationship.setGraphEdgeId(-1);
            if (type.isRecursive()) {
                // one participant per role, both on the same entity: a binary recursive relationship
                String entityName = "E" + type.getFromLabelIndex();
                relationship.addParticipant(new ERParticipant(entityName, entityName + "_src")
                        .setCardinality(type.getFromCardinality()).setParticipation(type.getFromParticipation()));
                relationship.addParticipant(new ERParticipant(entityName, entityName + "_dst")
                        .setCardinality(type.getToCardinality()).setParticipation(type.getToParticipation()));
            } else {
                relationship.addParticipant(new ERParticipant("E" + type.getFromLabelIndex())
                        .setCardinality(type.getFromCardinality()).setParticipation(type.getFromParticipation()));
                relationship.addParticipant(new ERParticipant("E" + type.getToLabelIndex())
                        .setCardinality(type.getToCardinality()).setParticipation(type.getToParticipation()));
            }
            for (GraphProperty slot : filling.attributesOfRelationshipType(type.getIndex())) {
                ERAttribute attribute = new ERAttribute(attributeName(slot), slot.getType());
                attribute.setOptional(!slot.isMandatory());
                attribute.setGraphSlot(slot.getKey());
                relationship.addAttribute(attribute);
            }
            diagram.addRelationship(relationship);
            relationships.put(type.getIndex(), relationship);
        }
        return relationships;
    }

    /* ------------------------------------------------------------------ weak entities */

    private void detectWeakEntities(GraphModel graph, GraphSchema schema, Map<Integer, EREntity> entities,
            Set<Integer> syntheticKeys, Map<Integer, ERRelationship> relationships) {
        for (int labelIndex : syntheticKeys) {
            EREntity entity = entities.get(labelIndex);
            List<GraphRelationshipType> incident = schema.getRelationshipTypesOf(labelIndex);
            if (entity == null || incident.size() != 1) {
                continue;
            }
            GraphRelationshipType type = incident.get(0);
            if (type.getFromLabelIndex() != labelIndex || type.getToLabelIndex() == labelIndex) {
                continue;
            }
            if (type.getFromCardinality() != Cardinality.ONE) {
                continue;
            }
            // either a genuine 1:N ownership or a total 1:1 link; in both cases the instance must
            // show exactly one outgoing edge per node of the label
            if (type.getToCardinality() != Cardinality.MANY && type.getFromParticipation() != Participation.TOTAL) {
                continue;
            }
            List<GraphNode> nodes = graph.getNodesOfLabel(labelIndex);
            if (nodes.isEmpty()) {
                continue;
            }
            boolean exactlyOneOwner = true;
            for (GraphNode node : nodes) {
                if (graph.getOutEdges(node.getId(), type.getIndex()).size() != 1) {
                    exactlyOneOwner = false;
                    break;
                }
            }
            ERRelationship owner = relationships.get(type.getIndex());
            if (!exactlyOneOwner || owner == null) {
                continue;
            }
            entity.setWeak(true);
            entity.setOwnerEntity("E" + type.getToLabelIndex());
            entity.setOwnerRelationship(owner.getName());
            ERAttribute key = entity.getAttribute(SYNTHETIC_KEY_NAME);
            if (key != null) {
                key.setPrimaryKey(false);
                key.setPartialKey(true);
                if (Rand.chance(COMPOSITE_PARTIAL_KEY_PROBABILITY)) {
                    // a composite partial key: the discriminator of the weak entity is a pair of
                    // attributes, so its primary key becomes owner key + two columns
                    ERAttribute second = new ERAttribute(SYNTHETIC_KEY_NAME + "2", DataType.integer());
                    second.setPartialKey(true);
                    entity.addAttribute(second);
                }
            }
            owner.setIdentifying(true);
        }
    }

    /* ------------------------------------------------------------------ ternary relationships */

    /**
     * Merges triangles of pairwise-connected labels into single ternary relationships. A property
     * graph has only binary edges, so a ternary relationship cannot come from one edge; but three
     * relationship types that pairwise connect three labels, all instantiated in the graph, are
     * exactly the graph's way of showing a 3-way association, and the n-ary mapping path is
     * otherwise unreachable from the graph pipeline.
     *
     * <p>The three binary relationships are removed and replaced by one relationship with one
     * participant per label. The participant's cardinality is {@code ONE} only when the label was
     * the "one" side in both of its binary types, and its participation is {@code TOTAL} only when
     * it was total in both — conservative approximations of the ternary's structural constraint.
     * The three types' attributes move to the ternary, whose name records what it merged.</p>
     *
     * @param consumed the type indices already used by an earlier synthesis step
     * @return the type indices consumed here; later synthesis steps must leave them alone
     */
    private Set<Integer> synthesizeTernaries(ERDiagram diagram, GraphModel graph, GraphSchema schema,
            Map<Integer, ERRelationship> relationships, Set<Integer> consumed) {
        int labelCount = schema.getLabelCount();
        if (labelCount < 3) {
            return consumed;
        }
        for (int a = 0; a < labelCount; a++) {
            for (int b = a + 1; b < labelCount; b++) {
                for (int c = b + 1; c < labelCount; c++) {
                    GraphRelationshipType ab = pairType(schema, graph, relationships, consumed, a, b);
                    GraphRelationshipType ac = pairType(schema, graph, relationships, consumed, a, c);
                    GraphRelationshipType bc = pairType(schema, graph, relationships, consumed, b, c);
                    if (ab == null || ac == null || bc == null || !Rand.chance(TERNARY_PROBABILITY)) {
                        continue;
                    }
                    ERRelationship ternary = new ERRelationship("Rtern_" + a + "_" + b + "_" + c);
                    ternary.addParticipant(participantOfLabel(a, ab, ac));
                    ternary.addParticipant(participantOfLabel(b, ab, bc));
                    ternary.addParticipant(participantOfLabel(c, ac, bc));
                    ternary.addMergedTypeName(ab.getName());
                    ternary.addMergedTypeName(ac.getName());
                    ternary.addMergedTypeName(bc.getName());
                    for (GraphRelationshipType merged : List.of(ab, ac, bc)) {
                        ERRelationship removed = relationships.remove(merged.getIndex());
                        if (removed != null) {
                            diagram.getRelationships().remove(removed);
                            ternary.getAttributes().addAll(removed.getAttributes());
                        }
                        consumed.add(merged.getIndex());
                    }
                    diagram.addRelationship(ternary);
                }
            }
        }
        return consumed;
    }

    /**
     * @return the first usable relationship type that connects {@code x} and {@code y} — one that
     *         has edges, whose ER relationship still exists, and that no synthesis step consumed;
     *         {@code null} when the pair has none
     */
    private GraphRelationshipType pairType(GraphSchema schema, GraphModel graph,
            Map<Integer, ERRelationship> relationships, Set<Integer> consumed, int x, int y) {
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            boolean matches = (type.getFromLabelIndex() == x && type.getToLabelIndex() == y)
                    || (type.getFromLabelIndex() == y && type.getToLabelIndex() == x);
            if (!matches || type.isRecursive() || consumed.contains(type.getIndex())) {
                continue;
            }
            if (graph.getEdgesOfType(type.getIndex()).isEmpty()) {
                continue;
            }
            ERRelationship relationship = relationships.get(type.getIndex());
            if (relationship == null || relationship.isIdentifying() || relationship.isStructuralLink()) {
                continue;
            }
            return type;
        }
        return null;
    }

    /** @return the participant of label {@code label} for a ternary merged from its two binary types */
    private ERParticipant participantOfLabel(int label, GraphRelationshipType first, GraphRelationshipType second) {
        ERParticipant participant = new ERParticipant("E" + label);
        boolean oneEverywhere = cardinalityOfLabel(label, first) == Cardinality.ONE
                && cardinalityOfLabel(label, second) == Cardinality.ONE;
        boolean totalEverywhere = participationOfLabel(label, first) == Participation.TOTAL
                && participationOfLabel(label, second) == Participation.TOTAL;
        participant.setCardinality(oneEverywhere ? Cardinality.ONE : Cardinality.MANY);
        participant.setParticipation(totalEverywhere ? Participation.TOTAL : Participation.PARTIAL);
        return participant;
    }

    /** @return the cardinality that {@code label} has in the binary {@code type} */
    private Cardinality cardinalityOfLabel(int label, GraphRelationshipType type) {
        return type.getFromLabelIndex() == label ? type.getFromCardinality() : type.getToCardinality();
    }

    /** @return the participation that {@code label} has in the binary {@code type} */
    private Participation participationOfLabel(int label, GraphRelationshipType type) {
        return type.getFromLabelIndex() == label ? type.getFromParticipation() : type.getToParticipation();
    }

    /* ------------------------------------------------------------------ aggregation */

    /**
     * Reads a triangle of labels the other way: instead of a ternary relationship, the M:N side
     * {@code R(a,b)} is kept as a relationship and <em>aggregated</em> — the relationship plus its
     * two entities treated as one higher-level entity — while the two links from the third label
     * {@code c} to {@code a} and {@code b} are absorbed into one outer relationship between
     * {@code c} and the aggregation.
     *
     * <p>The aggregation becomes a synthetic entity named {@code Agg_R<t>} that stands for the
     * aggregated relationship; the two absorbed links stay in the diagram as
     * {@link ERRelationship#isAggregationLink() aggregation links} (they carry the graph evidence
     * but are never mapped as associations), and the outer relationship is an ordinary binary
     * relationship between {@code E<c>} and the synthetic entity. The outer relationship's
     * cardinality is {@code ONE} on the {@code c} side exactly when {@code c} was the "one" side in
     * both absorbed links, and the aggregate side is {@code MANY}.</p>
     *
     * @return the type indices consumed here; later synthesis steps must leave them alone
     */
    private Set<Integer> synthesizeAggregations(ERDiagram diagram, GraphModel graph, GraphSchema schema,
            Map<Integer, EREntity> entities, Map<Integer, ERRelationship> relationships) {
        Set<Integer> consumed = new LinkedHashSet<>();
        int labelCount = schema.getLabelCount();
        if (labelCount < 3) {
            return consumed;
        }
        for (int a = 0; a < labelCount; a++) {
            for (int b = a + 1; b < labelCount; b++) {
                for (int c = 0; c < labelCount; c++) {
                    if (c == a || c == b) {
                        continue;
                    }
                    // the aggregated side must be a usable M:N relationship
                    GraphRelationshipType inner = pairType(schema, graph, relationships, consumed, a, b);
                    if (inner == null || !inner.isManyToMany()) {
                        continue;
                    }
                    GraphRelationshipType linkA = pairType(schema, graph, relationships, consumed, a, c);
                    GraphRelationshipType linkB = pairType(schema, graph, relationships, consumed, b, c);
                    if (linkA == null || linkB == null || !Rand.chance(AGGREGATION_PROBABILITY)) {
                        continue;
                    }
                    ERRelationship innerRelationship = relationships.get(inner.getIndex());
                    ERRelationship linkARelationship = relationships.get(linkA.getIndex());
                    ERRelationship linkBRelationship = relationships.get(linkB.getIndex());
                    if (innerRelationship == null || linkARelationship == null || linkBRelationship == null) {
                        continue;
                    }
                    EREntity aggregate = new EREntity("Agg_" + inner.getName());
                    aggregate.setAggregatedRelationship(inner.getName());
                    diagram.addEntity(aggregate);

                    linkARelationship.setAggregationLink(true);
                    linkBRelationship.setAggregationLink(true);
                    consumed.add(inner.getIndex());
                    consumed.add(linkA.getIndex());
                    consumed.add(linkB.getIndex());

                    ERRelationship outer = new ERRelationship("Ragg_" + inner.getName());
                    ERParticipant outerC = new ERParticipant("E" + c);
                    boolean oneEverywhere = cardinalityOfLabel(c, linkA) == Cardinality.ONE
                            && cardinalityOfLabel(c, linkB) == Cardinality.ONE;
                    boolean totalEverywhere = participationOfLabel(c, linkA) == Participation.TOTAL
                            && participationOfLabel(c, linkB) == Participation.TOTAL;
                    outerC.setCardinality(oneEverywhere ? Cardinality.ONE : Cardinality.MANY);
                    outerC.setParticipation(totalEverywhere ? Participation.TOTAL : Participation.PARTIAL);
                    ERParticipant outerAgg = new ERParticipant(aggregate.getName());
                    outerAgg.setCardinality(Cardinality.MANY);
                    outerAgg.setParticipation(Participation.PARTIAL);
                    outer.addParticipant(outerC);
                    outer.addParticipant(outerAgg);
                    outer.addMergedTypeName(linkA.getName());
                    outer.addMergedTypeName(linkB.getName());
                    diagram.addRelationship(outer);
                }
            }
        }
        return consumed;
    }

    /* ------------------------------------------------------------------ generalization */

    /**
     * Recognizes specialization links: a 1:1 relationship type with TOTAL participation on the
     * subtype side, whose label owns a strict superset of the parent's property slots.
     *
     * <p>One subtype may take part in more than one such link, in which case it becomes a shared
     * subclass with several supertypes (multiple inheritance) — provided the two supertypes are
     * not already related by ISA, so the second link really adds a lattice and not a detour.
     * Hierarchies are capped at {@link #MAX_ISA_DEPTH} levels. Every specialization is randomly
     * declared disjoint or overlapping, and its completeness (total or partial specialization) is
     * drawn at random as well.</p>
     */
    private void detectGeneralizations(GraphSchema schema, Map<Integer, EREntity> entities, ERDiagram diagram,
            Map<Integer, ERRelationship> relationships) {
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            int subIndex = type.getFromLabelIndex();
            int superIndex = type.getToLabelIndex();
            if (subIndex == superIndex || type.getFromCardinality() != Cardinality.ONE
                    || type.getToCardinality() != Cardinality.ONE
                    || type.getFromParticipation() != Participation.TOTAL) {
                continue;
            }
            ERRelationship relationship = relationships.get(type.getIndex());
            EREntity subtype = entities.get(subIndex);
            EREntity supertype = entities.get(superIndex);
            if (relationship == null || relationship.isStructuralLink() || subtype == null || supertype == null) {
                continue;
            }
            if (!isStrictSuperset(subIndex, superIndex)) {
                continue;
            }
            if (subtype.isWeak() || supertype.isWeak() || subtype.isCategory() || supertype.isCategory()
                    || subtype.isAggregation() || supertype.isAggregation()) {
                continue;
            }
            // no cycles: the would-be supertype must not already be below the subtype
            if (isaConnected(diagram, subtype, supertype)) {
                continue;
            }
            // no detours: a second supertype must live in a different part of the lattice
            if (subtype.isGeneralizationSubtype() && anySuperConnectedTo(diagram, subtype, supertype)) {
                continue;
            }
            // depth cap: the level the subtype would reach is one below the deepest chain above the
            // new supertype
            if (isaDepth(diagram, supertype, subtype) + 1 > MAX_ISA_DEPTH) {
                continue;
            }
            if (!Rand.chance(GENERALIZATION_PROBABILITY)) {
                continue;
            }
            // the specialization properties belong to the supertype, and are drawn when it gains its
            // first subtype - a supertype that only receives its first subtype through a shared
            // subclass must get them too, or its hierarchy would silently stay at the defaults
            boolean firstSubtype = !supertype.isGeneralizationSupertype();
            subtype.addSuperEntity(supertype.getName());
            supertype.addSubEntity(subtype.getName());
            // the subtype now inherits the supertype's attributes; it must not keep its own copies
            removeInheritedAttributes(subtype, superIndex);
            if (firstSubtype) {
                supertype.setDisjoint(Randomly.getBoolean());
                supertype.setTotalSpecialization(Randomly.getBoolean());
                if (!supertype.getAttributes().isEmpty()) {
                    supertype.setDiscriminatorAttribute(Randomly.fromList(supertype.getAttributes()).getName());
                }
            }
            relationship.setGeneralization(true);
        }
    }

    /**
     * Removes the attributes a subtype inherited from its new supertype.
     *
     * <p>The filling models inheritance by copying the supertype's attribute slots into the child
     * label, so the child entity is built with a copy of every inherited attribute. In the ER
     * model a subtype <em>inherits</em> its supertype's attributes; it does not re-declare them.
     * Leaving the copies in place made the relational mapping materialize the same logical attribute
     * twice whenever a lattice is merged into one table: the single-table strategy emitted both
     * {@code a0} and {@code a0_2} for one attribute, in every hierarchy the generator produced.</p>
     *
     * <p>Removing them here makes inheritance implicit, which is what every mapping strategy already
     * expects - {@code TABLE_PER_CLASS} walks the supertype chain itself, {@code SINGLE_TABLE} adds
     * the root and then each subtype, and {@code TABLE_PER_SUBCLASS_WITH_FK} copies only the parent's
     * key. A subtype therefore keeps exactly the attributes it adds of its own.</p>
     *
     * <p>The removal is driven by the supertype's <em>filled</em> slots rather than by its current
     * attribute list, so the result does not depend on the order in which the ISA links are
     * recognized: in a chain the middle entity loses its own inherited attributes later, and the
     * bottom entity must still lose them.</p>
     */
    private void removeInheritedAttributes(EREntity subtype, int supertypeLabelIndex) {
        Set<String> inheritedSlots = new LinkedHashSet<>();
        for (GraphProperty slot : filling.attributesOfLabel(supertypeLabelIndex)) {
            inheritedSlots.add(slot.getKey());
        }
        // an attribute without a graph slot (a multi-valued attribute derived from a self-loop, or a
        // composite built earlier) was never inherited, so it always stays
        subtype.getAttributes().removeIf(attribute -> attribute.getGraphSlot() != null
                && inheritedSlots.contains(attribute.getGraphSlot()));
        // the synthetic key is the placeholder for "this label has no key of its own". A subtype
        // always inherits a key - its supertype has a real key or a synthetic one of its own - so
        // the placeholder is redundant, and leaving it in place would materialize a second key
        // column next to the inherited one.
        subtype.getAttributes().removeIf(attribute -> attribute.getGraphSlot() == null
                && SYNTHETIC_KEY_NAME.equals(attribute.getName()));
    }

    /** @return true when {@code first} and {@code second} are connected by ISA links, in any direction */
    private boolean isaConnected(ERDiagram diagram, EREntity first, EREntity second) {
        return reachableFrom(diagram, first.getName(), second.getName(), true)
                || reachableFrom(diagram, second.getName(), first.getName(), true);
    }

    /** @return true when any supertype of {@code subtype} is ISA-connected to {@code candidate} */
    private boolean anySuperConnectedTo(ERDiagram diagram, EREntity subtype, EREntity candidate) {
        for (String superName : subtype.getSuperEntities()) {
            EREntity superEntity = diagram.getEntity(superName);
            if (superEntity != null && isaConnected(diagram, superEntity, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the number of ISA levels a subtype gains when {@code candidate} becomes one of its
     *         supertypes: the longest chain from {@code candidate} upwards, plus one
     */
    private int isaDepth(ERDiagram diagram, EREntity candidate, EREntity below) {
        int deepest = 0;
        for (String superName : candidate.getSuperEntities()) {
            EREntity superEntity = diagram.getEntity(superName);
            if (superEntity != null && !superEntity.getName().equals(below.getName())) {
                deepest = Math.max(deepest, isaDepth(diagram, superEntity, below));
            }
        }
        return deepest + 1;
    }

    /**
     * @return true when {@code target} is reachable from {@code start} through supertype and
     *         subtype links (both directions are followed, so this detects any ISA connection)
     */
    private boolean reachableFrom(ERDiagram diagram, String start, String target, boolean followDown) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty() && visited.size() < 256) {
            String currentName = queue.poll();
            if (!visited.add(currentName)) {
                continue;
            }
            if (currentName.equals(target)) {
                return true;
            }
            EREntity current = diagram.getEntity(currentName);
            if (current == null) {
                continue;
            }
            queue.addAll(current.getSuperEntities());
            if (followDown) {
                for (EREntity child : diagram.getSubEntities(currentName)) {
                    queue.add(child.getName());
                }
            }
        }
        return visited.contains(target);
    }

    /* ------------------------------------------------------------------ union types (categories) */

    /**
     * Recognizes union types: a label that is linked to two or more other labels by 1:1
     * relationship types that are PARTIAL on its own side — in the instance, a node of the label
     * points at a node of one superclass or the other, but never needs both. Such a label becomes
     * a <em>category</em>: every instance of it is an instance of at least one of its category
     * superclasses, which is union semantics rather than the intersection semantics of a shared
     * subclass (whose links would be TOTAL).
     *
     * <p>The two links are re-marked as {@link ERRelationship#isCategoryLink() category links}:
     * they carry the graph evidence, but the relational mapping of the category is driven by the
     * {@link EREntity#getCategorySupers()} list, and the links themselves are never mapped as
     * associations.</p>
     */
    private void detectCategories(GraphModel graph, GraphSchema schema, Map<Integer, EREntity> entities,
            ERDiagram diagram, Map<Integer, ERRelationship> relationships) {
        for (int labelIndex = 0; labelIndex < schema.getLabelCount(); labelIndex++) {
            EREntity entity = entities.get(labelIndex);
            if (entity == null || entity.isWeak() || entity.isGeneralizationSubtype()
                    || entity.isGeneralizationSupertype() || entity.isCategory() || entity.isAggregation()) {
                continue;
            }
            Map<Integer, GraphRelationshipType> candidates = new LinkedHashMap<>();
            for (GraphRelationshipType type : schema.getRelationshipTypes()) {
                if (type.isRecursive() || graph.getEdgesOfType(type.getIndex()).isEmpty()) {
                    continue;
                }
                int other = -1;
                if (type.getFromLabelIndex() == labelIndex) {
                    other = type.getToLabelIndex();
                } else if (type.getToLabelIndex() == labelIndex) {
                    other = type.getFromLabelIndex();
                } else {
                    continue;
                }
                if (other == labelIndex) {
                    continue;
                }
                if (type.getFromCardinality() != Cardinality.ONE || type.getToCardinality() != Cardinality.ONE) {
                    continue;
                }
                if (cardinalityOfLabel(labelIndex, type) != Cardinality.ONE) {
                    continue;
                }
                if (participationOfLabel(labelIndex, type) != Participation.PARTIAL
                        || participationOfLabel(other, type) != Participation.PARTIAL) {
                    continue;
                }
                ERRelationship relationship = relationships.get(type.getIndex());
                if (relationship == null || relationship.isIdentifying() || relationship.isStructuralLink()) {
                    continue;
                }
                EREntity otherEntity = entities.get(other);
                if (otherEntity == null || otherEntity.isAggregation()) {
                    continue;
                }
                // the superclass must not be an ISA relative of the candidate category
                if (otherEntity.hasSuperEntity(entity.getName()) || entity.hasSuperEntity(otherEntity.getName())) {
                    continue;
                }
                candidates.putIfAbsent(other, type);
            }
            if (candidates.size() < 2 || !Rand.chance(CATEGORY_PROBABILITY)) {
                continue;
            }
            List<Integer> superLabels = new ArrayList<>(candidates.keySet());
            int first = superLabels.get(0);
            int second = superLabels.get(1);
            entity.addCategorySuper("E" + first);
            entity.addCategorySuper("E" + second);
            relationships.get(candidates.get(first).getIndex()).setCategoryLink(true);
            relationships.get(candidates.get(second).getIndex()).setCategoryLink(true);
        }
    }

    /** @return true if {@code subtype}'s filled slots contain all of {@code supertype}'s, plus some */
    private boolean isStrictSuperset(int subtypeLabelIndex, int supertypeLabelIndex) {
        Set<String> subKeys = new LinkedHashSet<>();
        for (GraphProperty slot : filling.attributesOfLabel(subtypeLabelIndex)) {
            subKeys.add(slot.getKey());
        }
        Set<String> superKeys = new LinkedHashSet<>();
        for (GraphProperty slot : filling.attributesOfLabel(supertypeLabelIndex)) {
            superKeys.add(slot.getKey());
        }
        return subKeys.size() > superKeys.size() && subKeys.containsAll(superKeys);
    }

    /* ------------------------------------------------------------------ derived and composite attributes */

    private void markDerivedAttributes(ERDiagram diagram) {
        for (EREntity entity : diagram.getEntities()) {
            List<ERAttribute> numeric = new ArrayList<>();
            for (ERAttribute attribute : entity.getAttributes()) {
                if (attribute.getType() != null && attribute.getType().isNumeric() && !attribute.isPrimaryKey()
                        && !attribute.isPartialKey() && !attribute.isDerived()) {
                    numeric.add(attribute);
                }
            }
            if (numeric.size() < 2 || !Rand.chance(DERIVED_PROBABILITY)) {
                continue;
            }
            // the key attributes are excluded: a derived key would be meaningless for the mapping
            ERAttribute derived = numeric.get(numeric.size() - 1);
            derived.setDerived(true);
            derived.setDerivedExpression(numeric.get(0).getName() + " + 1");
        }
    }

    private void groupCompositeAttributes(ERDiagram diagram) {
        int counter = 0;
        for (EREntity entity : diagram.getEntities()) {
            if (!Rand.chance(COMPOSITE_PROBABILITY)) {
                continue;
            }
            List<ERAttribute> groupable = new ArrayList<>();
            for (ERAttribute attribute : entity.getAttributes()) {
                if (!attribute.isPrimaryKey() && !attribute.isPartialKey() && !attribute.isDerived()
                        && !attribute.isMultivalued() && !attribute.isComposite()) {
                    groupable.add(attribute);
                }
            }
            if (groupable.size() < 2) {
                continue;
            }
            ERAttribute[] pair = findSameTypePair(groupable);
            if (pair == null) {
                continue;
            }
            ERAttribute composite = new ERAttribute("comp_" + counter++, pair[0].getType());
            composite.setComposite(true);
            composite.setOptional(pair[0].isOptional() || pair[1].isOptional());
            composite.addComponent(pair[0]);
            composite.addComponent(pair[1]);
            // a nested composite: with a third attribute of the same type, the second component
            // itself becomes a composite of two — the multi-level case the relational mapper must
            // flatten recursively
            ERAttribute third = null;
            if (groupable.size() >= 3 && Rand.chance(NESTED_COMPOSITE_PROBABILITY)) {
                for (ERAttribute candidate : groupable) {
                    if (candidate != pair[0] && candidate != pair[1] && pair[1].getType() != null
                            && pair[1].getType().equals(candidate.getType())) {
                        third = candidate;
                        break;
                    }
                }
            }
            if (third != null) {
                ERAttribute inner = new ERAttribute("comp_" + counter++, pair[1].getType());
                inner.setComposite(true);
                inner.setOptional(pair[1].isOptional() || third.isOptional());
                inner.addComponent(pair[1]);
                inner.addComponent(third);
                composite.getComponents().remove(pair[1]);
                composite.addComponent(inner);
                entity.getAttributes().remove(third);
            }
            // the originals are replaced by the composite (the relational mapper flattens it)
            entity.getAttributes().remove(pair[0]);
            entity.getAttributes().remove(pair[1]);
            entity.addAttribute(composite);
        }
    }

    private ERAttribute[] findSameTypePair(List<ERAttribute> attributes) {
        for (int i = 0; i < attributes.size(); i++) {
            for (int j = i + 1; j < attributes.size(); j++) {
                DataType first = attributes.get(i).getType();
                DataType second = attributes.get(j).getType();
                if (first != null && first.equals(second)) {
                    return new ERAttribute[] {attributes.get(i), attributes.get(j)};
                }
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ names */

    /** @return {@code a<j>} where {@code j} is the global index encoded in the slot key {@code k<j>} */
    private String attributeName(GraphProperty slot) {
        return "a" + slotIndex(slot);
    }

    private int slotIndex(GraphProperty slot) {
        String key = slot.getKey();
        if (key != null && key.length() > 1 && (key.charAt(0) == 'k' || key.charAt(0) == 'K')) {
            try {
                return Integer.parseInt(key.substring(1));
            } catch (NumberFormatException ignored) {
                // fall through to the hash based fallback below
            }
        }
        // a schema that does not follow the k<j> convention: derive a stable index from the key
        return key == null ? 0 : Math.abs(key.hashCode() % 100000);
    }
}
