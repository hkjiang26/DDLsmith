package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.graph.GraphFiller;
import org.ddlsmith.graph.GraphFilling;
import org.ddlsmith.graph.GraphProperty;
import org.ddlsmith.graph.GraphProperty.KeyRole;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;

/**
 * Fills a base graph with attributes, once per database.
 *
 * <p>This is where the complexity of the ER diagrams comes from now: the base graph is only nodes
 * and edges, and every database of a {@code --databases-per-graph} group draws its own filling of
 * that same graph. Two databases of one group therefore differ in how many attributes a table has,
 * which domains those attributes use, which of them are NOT NULL, which form the key - and, because
 * a specialization link is nothing but "this label's attributes are a strict superset of that
 * label's", in which labels are subtypes of which.</p>
 *
 * <p><b>What is drawn per database.</b></p>
 * <ul>
 *   <li><b>A filling profile.</b> One of three widths for the attribute count of every label and
 *       relationship type: lean ({@code 0..3} attributes per label, {@code 0..1} per relationship
 *       type), normal ({@code 2..6} / {@code 0..3}, the historic shape) and wide
 *       ({@code 5..12} / {@code 1..5}). A group therefore contains small tables and wide ones.</li>
 *   <li><b>A mandatory share.</b> The probability that an attribute is mandatory, i.e. NOT NULL
 *       ({@code presenceProbability == 1.0}), drawn from {0.25, 0.45, 0.65, 0.85}. It decides how
 *       often a label has a natural key of its own at all: a label without a single mandatory
 *       attribute gets the synthetic key {@code id}, which is what the ER mapper reads as the
 *       partial key of a weak entity.</li>
 *   <li><b>A domain pool.</b> The types the attributes of this database are drawn from: the common
 *       SQL types plus, each with its own probability, {@code TIME}, {@code TIMESTAMPTZ},
 *       {@code UUID}, {@code JSON}, an {@code ENUM} and an {@code ARRAY} type. The pool is shared by
 *       every attribute of the database, so its tables look like one schema rather than like a pile
 *       of unrelated columns - and two databases look different.</li>
 *   <li><b>Keys.</b> One or two mandatory attributes become the primary key, and - with probability
 *       0.25 - one or two further mandatory attributes become an <em>alternate</em> key, which the
 *       relational mapper emits as a {@code UNIQUE} constraint. Key attributes never get a domain
 *       that cannot be indexed.</li>
 *   <li><b>The inheritance plan.</b> The subtype candidates of the base graph are 1:1 relationship
 *       types that are {@code TOTAL} on the child side. Each of them is realized, with probability
 *       0.6 (0.85 when the child has more than one candidate), by filling the child's attribute set
 *       as a strict superset of the parent's - which is exactly what {@link DefaultGraphToERMapper}
 *       reads as a specialization link. A child may take a second, unrelated parent (a shared
 *       subclass), and a parent may itself be a child, so multi-level hierarchies are reachable.
 *       The plan never contains a cycle, never lets two parents of one child be ISA-related, and
 *       never exceeds {@value #MAX_ISA_LEVELS} levels, because the ER mapper would not recognise
 *       more.</li>
 * </ul>
 *
 * <p>The filler is the only place that decides attributes; like every other random decision in
 * DDLsmith it draws from the seeded {@link Randomly} stream, so the same seed and slot always
 * produce the same filling.</p>
 */
public class RandomGraphFiller implements GraphFiller {

    /** Attribute count of a label per filling profile, as {minimum, maximum} inclusive. */
    private static final int[][] LABEL_ATTRIBUTE_COUNTS = {{0, 3}, {2, 6}, {5, 12}};
    /** Attribute count of a relationship type per filling profile. */
    private static final int[][] TYPE_ATTRIBUTE_COUNTS = {{0, 1}, {0, 3}, {1, 5}};
    private static final double[] PROFILE_WEIGHTS = {0.25, 0.5, 0.25};

    /** Probability that an attribute is mandatory (NOT NULL), drawn once per database. */
    private static final double[] MANDATORY_SHARES = {0.25, 0.45, 0.65, 0.85};
    /** Presence probabilities of a non-mandatory attribute, with the historic relative weights. */
    private static final double[] OPTIONAL_PRESENCE = {0.9, 0.7, 0.5};
    private static final double[] OPTIONAL_PRESENCE_WEIGHTS = {0.4545, 0.3636, 0.1819};

    /** Probability that a label with two mandatory attributes gets a composite primary key. */
    private static final double COMPOSITE_KEY_PROBABILITY = 0.3;
    /** Probability that a label gets an alternate (candidate) key next to its primary key. */
    private static final double ALTERNATE_KEY_PROBABILITY = 0.25;
    /** Probability that an alternate key is composite. */
    private static final double COMPOSITE_ALTERNATE_KEY_PROBABILITY = 0.35;

    private static final double ISA_LINK_PROBABILITY = 0.6;
    private static final double SHARED_SUBCLASS_LINK_PROBABILITY = 0.85;
    /** Levels of an ISA path the ER mapper still recognises (see {@link DefaultGraphToERMapper}). */
    private static final int MAX_ISA_LEVELS = 3;

    /** Probability that each extra domain type joins the pool of a database. */
    private static final double EXTRA_TYPE_PROBABILITY = 0.5;
    private static final double ENUM_TYPE_PROBABILITY = 0.5;
    private static final double ARRAY_TYPE_PROBABILITY = 0.3;
    private static final int MIN_ENUM_VALUES = 3;
    private static final int MAX_ENUM_VALUES = 5;
    private static final String[] ENUM_LABELS = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta"};

    /**
     * Domains an attribute taking part in a key is restricted to. A key column is indexed by the
     * engine, and a semi-structured or large-object type is either not indexable at all or not
     * comparable, so key attributes stay with the plain types - the graph values a key column
     * receives then always fit the column the engine really creates.
     */
    private static final List<DataType> KEY_SAFE_TYPES = List.of(
            DataType.integer(), DataType.bigint(), DataType.smallint(), DataType.decimal(10, 2),
            DataType.doublePrecision(), DataType.varchar(20), DataType.varchar(100), DataType.charType(5),
            DataType.bool(), DataType.date(), DataType.timestamp());

    @Override
    public GraphFilling plan(GraphSchema schema) {
        int labelCount = schema.getLabelCount();
        GraphFilling filling = new GraphFilling(labelCount, schema.getRelationshipTypeCount());

        int profile = pickProfile();
        int labelMinimum = LABEL_ATTRIBUTE_COUNTS[profile][0];
        int labelMaximum = LABEL_ATTRIBUTE_COUNTS[profile][1];
        int typeMinimum = TYPE_ATTRIBUTE_COUNTS[profile][0];
        int typeMaximum = TYPE_ATTRIBUTE_COUNTS[profile][1];
        double mandatoryShare = Randomly.fromList(toList(MANDATORY_SHARES));
        List<DataType> pool = domainPool();
        int[] slotCounter = {0};

        // the inheritance plan is drawn first: the attributes of a child are the parent's plus its
        // own, so the parents have to be known before any attribute is created
        Map<Integer, List<Integer>> planned = planInheritance(schema);
        for (int label : materialisationOrder(labelCount, planned)) {
            materialiseLabel(filling, label, planned.getOrDefault(label, List.of()), labelMinimum, labelMaximum,
                    mandatoryShare, pool, slotCounter);
        }
        for (int type = 0; type < schema.getRelationshipTypeCount(); type++) {
            int count = Randomly.getInteger(typeMinimum, typeMaximum + 1);
            for (int i = 0; i < count; i++) {
                filling.addTypeAttribute(type, new GraphProperty("k" + slotCounter[0]++, randomType(pool),
                        randomPresence(mandatoryShare)));
            }
        }
        return filling;
    }

    /* ------------------------------------------------------------------ profile */

    private int pickProfile() {
        double roll = Rand.uniform();
        double cumulative = 0;
        for (int i = 0; i < PROFILE_WEIGHTS.length; i++) {
            cumulative += PROFILE_WEIGHTS[i];
            if (roll < cumulative) {
                return i;
            }
        }
        return PROFILE_WEIGHTS.length - 1;
    }

    /** @return the domain pool of this database: the common types plus the extra ones it drew */
    private List<DataType> domainPool() {
        List<DataType> pool = new ArrayList<>(DataType.commonTypes());
        if (Rand.chance(EXTRA_TYPE_PROBABILITY)) {
            pool.add(DataType.time());
        }
        if (Rand.chance(EXTRA_TYPE_PROBABILITY)) {
            pool.add(DataType.timestampWithTimeZone());
        }
        if (Rand.chance(EXTRA_TYPE_PROBABILITY)) {
            pool.add(DataType.uuid());
        }
        if (Rand.chance(EXTRA_TYPE_PROBABILITY)) {
            pool.add(DataType.json());
        }
        if (Rand.chance(ENUM_TYPE_PROBABILITY)) {
            pool.add(randomEnumType());
        }
        if (Rand.chance(ARRAY_TYPE_PROBABILITY)) {
            pool.add(DataType.arrayOf(Randomly.fromList(KEY_SAFE_TYPES)));
        }
        return pool;
    }

    private DataType randomEnumType() {
        int count = Randomly.getInteger(MIN_ENUM_VALUES, MAX_ENUM_VALUES + 1);
        List<String> values = new ArrayList<>();
        List<String> available = new ArrayList<>(List.of(ENUM_LABELS));
        Randomly.shuffle(available);
        for (int i = 0; i < count && i < available.size(); i++) {
            values.add(available.get(i));
        }
        return DataType.enumOf(values);
    }

    private DataType randomType(List<DataType> pool) {
        return Randomly.fromList(pool);
    }

    private double randomPresence(double mandatoryShare) {
        if (Rand.chance(mandatoryShare)) {
            return 1.0;
        }
        double roll = Rand.uniform();
        double cumulative = 0;
        for (int i = 0; i < OPTIONAL_PRESENCE.length; i++) {
            cumulative += OPTIONAL_PRESENCE_WEIGHTS[i];
            if (roll < cumulative) {
                return OPTIONAL_PRESENCE[i];
            }
        }
        return OPTIONAL_PRESENCE[OPTIONAL_PRESENCE.length - 1];
    }

    /* ------------------------------------------------------------------ inheritance plan */

    /**
     * Draws which subtype candidates of the base graph really become specialization links here.
     *
     * <p>A candidate is a 1:1 relationship type that is {@code TOTAL} on its from side - the shape a
     * specialization link has (every instance of the subtype belongs to exactly one supertype). The
     * candidates are visited in random order, so the same base graph realizes a different subset in
     * every database; a link is only added when it cannot make the plan cyclic, cannot connect two
     * parents of one child, and cannot push a specialization path past
     * {@value #MAX_ISA_LEVELS} levels.</p>
     *
     * @return child label index -> the parent label indexes it will inherit from
     */
    private Map<Integer, List<Integer>> planInheritance(GraphSchema schema) {
        List<GraphRelationshipType> candidates = new ArrayList<>();
        Map<Integer, Integer> candidatesPerChild = new LinkedHashMap<>();
        for (GraphRelationshipType type : schema.getRelationshipTypes()) {
            if (type.isRecursive() || type.getFromCardinality() != Cardinality.ONE
                    || type.getToCardinality() != Cardinality.ONE
                    || type.getFromParticipation() != Participation.TOTAL) {
                continue;
            }
            candidates.add(type);
            candidatesPerChild.merge(type.getFromLabelIndex(), 1, Integer::sum);
        }
        Randomly.shuffle(candidates);

        Map<Integer, List<Integer>> planned = new LinkedHashMap<>();
        for (GraphRelationshipType candidate : candidates) {
            int child = candidate.getFromLabelIndex();
            int parent = candidate.getToLabelIndex();
            if (child == parent || parent < 0 || parent >= schema.getLabelCount()) {
                continue;
            }
            List<Integer> parents = planned.computeIfAbsent(child, key -> new ArrayList<>());
            if (parents.size() >= 2 || parents.contains(parent)) {
                continue;
            }
            // a cycle: the prospective supertype is already below the child
            if (isDescendant(planned, parent, child)) {
                continue;
            }
            // a second supertype must live in another part of the lattice, or the link is a detour
            boolean connected = false;
            for (int existing : parents) {
                if (isDescendant(planned, existing, parent) || isDescendant(planned, parent, existing)) {
                    connected = true;
                    break;
                }
            }
            if (connected) {
                continue;
            }
            double probability = candidatesPerChild.getOrDefault(child, 1) > 1
                    ? SHARED_SUBCLASS_LINK_PROBABILITY
                    : ISA_LINK_PROBABILITY;
            if (!Rand.chance(probability)) {
                continue;
            }
            parents.add(parent);
            if (exceedsDepth(planned, schema.getLabelCount())) {
                parents.remove(parents.size() - 1);
                if (parents.isEmpty()) {
                    planned.remove(child);
                }
            }
        }
        return planned;
    }

    /** @return true when {@code label}'s level in the plan exceeds {@link #MAX_ISA_LEVELS} */
    private boolean exceedsDepth(Map<Integer, List<Integer>> planned, int labelCount) {
        for (int label = 0; label < labelCount; label++) {
            if (levelOf(planned, label, 0) > MAX_ISA_LEVELS) {
                return true;
            }
        }
        return false;
    }

    /** @return the level of a label: 1 for a root, one more than the deepest supertype above it */
    private int levelOf(Map<Integer, List<Integer>> planned, int label, int guard) {
        if (guard > MAX_ISA_LEVELS + 1) {
            return guard;
        }
        int deepest = 0;
        for (int parent : planned.getOrDefault(label, List.of())) {
            deepest = Math.max(deepest, levelOf(planned, parent, guard + 1));
        }
        return deepest + 1;
    }

    /** @return true when {@code candidate} is below {@code ancestor} in the planned hierarchy */
    private boolean isDescendant(Map<Integer, List<Integer>> planned, int candidate, int ancestor) {
        if (candidate == ancestor) {
            return true;
        }
        for (int parent : planned.getOrDefault(candidate, List.of())) {
            if (isDescendant(planned, parent, ancestor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the labels in an order that materializes every parent before its children. The lowest
     *         index that is ready comes first, so the order is deterministic.
     */
    private List<Integer> materialisationOrder(int labelCount, Map<Integer, List<Integer>> planned) {
        List<Integer> order = new ArrayList<>();
        Set<Integer> placed = new LinkedHashSet<>();
        while (order.size() < labelCount) {
            boolean progress = false;
            for (int label = 0; label < labelCount; label++) {
                if (placed.contains(label) || !placed.containsAll(planned.getOrDefault(label, List.of()))) {
                    continue;
                }
                order.add(label);
                placed.add(label);
                progress = true;
            }
            if (!progress) {
                // a cycle slipped through: append the rest in index order (their parents are then
                // materialized after them, which only means the copies are not filled in)
                for (int label = 0; label < labelCount; label++) {
                    if (!placed.contains(label)) {
                        order.add(label);
                        placed.add(label);
                    }
                }
            }
        }
        return order;
    }

    /* ------------------------------------------------------------------ label attributes */

    private void materialiseLabel(GraphFilling filling, int label, List<Integer> parents, int minimum, int maximum,
            double mandatoryShare, List<DataType> pool, int[] slotCounter) {
        int capacity = maximum;
        List<GraphProperty> inherited = new ArrayList<>();
        List<Integer> fitted = new ArrayList<>();
        List<Integer> bySize = new ArrayList<>(parents);
        bySize.sort(Comparator.comparingInt(parent -> filling.attributesOfLabel(parent).size()));
        for (int parent : bySize) {
            List<GraphProperty> union = union(inherited, filling.attributesOfLabel(parent));
            // one attribute of its own is what makes the child a strict superset of its parent
            if (union.size() + 1 <= capacity) {
                inherited = union;
                fitted.add(parent);
            }
        }
        for (GraphProperty slot : inherited) {
            // the copy keeps the parent's key, type and presence: the child inherits the attribute,
            // it does not declare a second one
            filling.addLabelAttribute(label,
                    new GraphProperty(slot.getKey(), slot.getType(), slot.getPresenceProbability()));
        }
        for (int parent : fitted) {
            filling.addParent(label, parent);
        }

        int ownCount = fitted.isEmpty()
                ? Randomly.getInteger(minimum, maximum + 1)
                : 1 + Randomly.getInteger(0, capacity - inherited.size());
        if (ownCount <= 0) {
            return;
        }
        double[] presences = new double[ownCount];
        DataType[] types = new DataType[ownCount];
        for (int i = 0; i < ownCount; i++) {
            presences[i] = randomPresence(mandatoryShare);
            types[i] = randomType(pool);
        }
        KeyRole[] roles = assignKeyRoles(presences);
        for (int i = 0; i < ownCount; i++) {
            if (roles[i] != KeyRole.NONE && !KEY_SAFE_TYPES.contains(types[i])) {
                // a key column has to be indexable and comparable on every engine
                types[i] = Randomly.fromList(KEY_SAFE_TYPES);
            }
            GraphProperty property = new GraphProperty("k" + slotCounter[0]++, types[i], presences[i]);
            property.setKeyRole(roles[i]);
            filling.addLabelAttribute(label, property);
        }
    }

    /** @return the parent's attributes plus the ones not inherited yet, in a stable order */
    private List<GraphProperty> union(List<GraphProperty> inherited, List<GraphProperty> parentAttributes) {
        List<GraphProperty> result = new ArrayList<>(inherited);
        Set<String> keys = new LinkedHashSet<>();
        for (GraphProperty slot : result) {
            keys.add(slot.getKey());
        }
        for (GraphProperty slot : parentAttributes) {
            if (keys.add(slot.getKey())) {
                result.add(slot);
            }
        }
        return result;
    }

    /**
     * Marks the primary key and - with probability {@value #ALTERNATE_KEY_PROBABILITY} - an alternate
     * key among the mandatory attributes of a label. Only the attributes the label declares itself
     * are considered: an inherited attribute is the supertype's key, not a second key of the subtype.
     */
    private KeyRole[] assignKeyRoles(double[] presences) {
        KeyRole[] roles = new KeyRole[presences.length];
        for (int i = 0; i < roles.length; i++) {
            roles[i] = KeyRole.NONE;
        }
        List<Integer> mandatory = new ArrayList<>();
        for (int i = 0; i < presences.length; i++) {
            if (presences[i] >= 1.0) {
                mandatory.add(i);
            }
        }
        if (mandatory.isEmpty()) {
            return roles;
        }
        int primaryCount = mandatory.size() >= 2 && Rand.chance(COMPOSITE_KEY_PROBABILITY) ? 2 : 1;
        for (int i = 0; i < primaryCount; i++) {
            roles[mandatory.get(i)] = KeyRole.PRIMARY;
        }
        List<Integer> remaining = new ArrayList<>(mandatory.subList(primaryCount, mandatory.size()));
        if (!remaining.isEmpty() && Rand.chance(ALTERNATE_KEY_PROBABILITY)) {
            int alternateCount = remaining.size() >= 2 && Rand.chance(COMPOSITE_ALTERNATE_KEY_PROBABILITY) ? 2 : 1;
            for (int i = 0; i < alternateCount; i++) {
                roles[remaining.get(i)] = KeyRole.ALTERNATE;
            }
        }
        return roles;
    }

    private List<Double> toList(double[] values) {
        List<Double> list = new ArrayList<>();
        for (double value : values) {
            list.add(value);
        }
        return list;
    }
}
