package org.ddlsmith.graph.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ddlsmith.Randomly;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.graph.GraphLabel;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.GraphSchemaGenerator;

/**
 * Randomly generates the schema of a <em>base</em> graph: 4..8 node labels, 4..10 relationship
 * types, and the cardinalities and participation constraints of the latter.
 *
 * <p>The schema carries no attributes. Attributes are what the {@link RandomGraphFiller} adds
 * afterwards, per database, so that one base graph becomes many different ER diagrams. What the
 * schema does decide is the <em>shape</em> the ER mapper can read features from:</p>
 *
 * <ul>
 *   <li>Labels are named {@code L0..Ln}; every label is incident to at least one relationship type,
 *       so no entity is isolated.</li>
 *   <li>Relationship types are named {@code R0..Rm} and connect a random from-label to a random
 *       to-label; about 20% of them are recursive (from == to) and at least one always is. The
 *       cardinalities are biased towards 1:N, then M:N, then 1:1. Participations favour PARTIAL but
 *       at least two types are total on one side, so total-participation mapping paths get
 *       exercised.</li>
 *   <li><b>Subtype candidates.</b> With probability 0.4 a label is planned as the subtype candidate
 *       of an earlier label, and with probability 0.45 it takes a <em>second</em> earlier label as
 *       well (the image of a shared subclass, i.e. multiple inheritance). For each planned pair a
 *       1:1 relationship type that is {@code TOTAL} on the child side is created with probability
 *       0.6 (0.85 for a shared subclass) - that is exactly the shape the ER mapper reads as a
 *       specialization link. The pair itself is only a <em>candidate</em>: whether it really becomes
 *       an ISA link is decided per database by the filling, which makes the child's attribute set a
 *       strict superset of the parent's. Because a parent may itself be a subtype candidate,
 *       multi-level hierarchies are reachable.</li>
 *   <li><b>Planned patterns.</b> Two further shapes are planned explicitly, because they need
 *       several coordinated relationship types that random drawing almost never produces together:
 *       with probability 0.35 a <em>triangle</em> of three pairwise-connected labels (which the ER
 *       mapper reads as a ternary relationship or an aggregation), and with probability 0.35 a label
 *       with two 1:1 links that are PARTIAL on its own side (which the ER mapper reads as a union
 *       type, a category). Both count towards the planned type budget and are protected from the
 *       retargeting passes.</li>
 *   <li><b>Correlated cardinality and participation.</b> 1:1 relationship types are more likely to
 *       be TOTAL on the from side than the base rate: that is the configuration the ER mapper needs
 *       to recognise a weak entity (each dependent instance has exactly one owner).</li>
 * </ul>
 *
 * <p>The type count is drawn from {@value #MIN_TYPES}..{@value #MAX_TYPES}. The passes that have to
 * hold afterwards - at least one recursive type, and no label without an incident type - may add a
 * type each when no existing plan can be retargeted without destroying a planned shape, so a schema
 * can carry up to {@value #MAX_TOTAL_TYPES} relationship types. The extra ones are not sloppiness:
 * the alternative would be an isolated label (an entity no relationship touches) or a schema
 * without a recursive type, both of which the ER feature coverage depends on.
 */
public class RandomGraphSchemaGenerator implements GraphSchemaGenerator {

    private static final int MIN_LABELS = 4;
    private static final int MAX_LABELS = 8;
    private static final int MIN_TYPES = 4;
    private static final int MAX_TYPES = 10;
    /** Upper bound on the relationship types after the coverage and recursion passes. */
    public static final int MAX_TOTAL_TYPES = MAX_TYPES + MAX_LABELS;

    private static final double RECURSIVE_PROBABILITY = 0.2;
    /** Probability that a label is planned as the subtype candidate of an earlier label. */
    private static final double SUBTYPE_CANDIDATE_PROBABILITY = 0.4;
    /** Probability that a subtype candidate takes a second parent as well (shared subclass). */
    private static final double MULTI_PARENT_PROBABILITY = 0.45;
    private static final double SUBTYPE_LINK_PROBABILITY = 0.6;
    /** Probability of a subtype link when the child has two parents, i.e. is a shared subclass. */
    private static final double SHARED_SUBCLASS_LINK_PROBABILITY = 0.85;
    private static final double BASE_TOTAL_PROBABILITY = 0.22;
    private static final double ONE_TO_ONE_TOTAL_PROBABILITY = 0.5;
    private static final int MIN_TOTAL_TYPES = 2;
    /** Probability that the schema plans a triangle of three pairwise-connected labels. */
    private static final double TRIANGLE_PLAN_PROBABILITY = 0.35;
    /** Probability that the schema plans a label with two partial 1:1 links (a category shape). */
    private static final double CATEGORY_PLAN_PROBABILITY = 0.35;

    @Override
    public GraphSchema generate() {
        GraphSchema schema = new GraphSchema();
        int labelCount = Randomly.getInteger(MIN_LABELS, MAX_LABELS + 1);
        Map<Integer, List<Integer>> subtypeCandidates = new LinkedHashMap<>();
        createLabels(schema, labelCount, subtypeCandidates);
        createRelationshipTypes(schema, labelCount, subtypeCandidates);
        return schema;
    }

    /* ------------------------------------------------------------------ labels */

    private void createLabels(GraphSchema schema, int labelCount, Map<Integer, List<Integer>> subtypeCandidates) {
        for (int i = 0; i < labelCount; i++) {
            schema.addLabel(new GraphLabel(i, "L" + i));
            if (i == 0 || !Rand.chance(SUBTYPE_CANDIDATE_PROBABILITY)) {
                continue;
            }
            List<Integer> parents = new ArrayList<>();
            parents.add(Randomly.getInteger(0, i));
            // a second parent makes the label a candidate shared subclass (multiple inheritance);
            // both parents are earlier labels, so the candidate links can never form a cycle
            if (i >= 2 && Rand.chance(MULTI_PARENT_PROBABILITY)) {
                int second = Randomly.getInteger(0, i);
                while (second == parents.get(0)) {
                    second = (second + 1) % i;
                }
                parents.add(second);
            }
            subtypeCandidates.put(i, parents);
        }
    }

    /* ------------------------------------------------------------------ relationship types */

    private void createRelationshipTypes(GraphSchema schema, int labelCount, Map<Integer, List<Integer>> subtypeCandidates) {
        List<TypePlan> plans = new ArrayList<>();
        int desired = Randomly.getInteger(MIN_TYPES, MAX_TYPES + 1);

        // 1. the candidate subtype links: the graph image of an ISA hierarchy. A label with two
        //    parents gets two links, which is the image of a shared subclass.
        List<Integer> children = new ArrayList<>(subtypeCandidates.keySet());
        Randomly.shuffle(children);
        for (int child : children) {
            boolean sharedSubclass = subtypeCandidates.get(child).size() > 1;
            for (int parent : subtypeCandidates.get(child)) {
                double probability = sharedSubclass ? SHARED_SUBCLASS_LINK_PROBABILITY : SUBTYPE_LINK_PROBABILITY;
                if (plans.size() >= desired || !Rand.chance(probability)) {
                    continue;
                }
                TypePlan plan = new TypePlan();
                plan.from = child;
                plan.to = parent;
                plan.fromCardinality = Cardinality.ONE;
                plan.toCardinality = Cardinality.ONE;
                plan.fromParticipation = Participation.TOTAL;
                plan.toParticipation = Participation.PARTIAL;
                plan.subtypeLink = true;
                plans.add(plan);
            }
        }

        // 1b. a planned category shape: a label with two 1:1 links that are PARTIAL on its own
        //     side, pointing at two other labels. The ER mapper turns this into a union type
        //     (category) when the pattern survives to the instance.
        if (labelCount >= 3 && plans.size() + 2 <= desired && Rand.chance(CATEGORY_PLAN_PROBABILITY)) {
            int category = pickLabel(labelCount, null, null);
            int first = pickLabel(labelCount, category, null);
            int second = pickLabel(labelCount, category, first);
            if (first != second) {
                plans.add(categoryLink(category, first));
                plans.add(categoryLink(category, second));
            }
        }

        // 1c. a planned triangle: three labels pairwise connected by three types, the shape the ER
        //     mapper reads either as a ternary relationship or as an aggregation. The first type
        //     of the three is the M:N side, so the aggregation reading has an inner relationship
        //     that maps to a join table.
        if (labelCount >= 3 && plans.size() + 3 <= desired && Rand.chance(TRIANGLE_PLAN_PROBABILITY)) {
            int a = pickLabel(labelCount, null, null);
            int b = pickLabel(labelCount, a, null);
            int c = pickLabel(labelCount, a, b);
            if (a != b && b != c && a != c) {
                TypePlan inner = new TypePlan();
                inner.from = a;
                inner.to = b;
                inner.fromCardinality = Cardinality.MANY;
                inner.toCardinality = Cardinality.MANY;
                plans.add(inner);
                plans.add(linkPlan(a, c));
                plans.add(linkPlan(b, c));
            }
        }

        // 2. the ordinary random types, biased towards the labels that no plan touches yet, so that
        //    the "every label is incident to at least one type" rule needs no extra plan
        while (plans.size() < desired) {
            TypePlan plan = randomPlan(labelCount);
            List<Integer> uncovered = uncoveredLabels(plans, labelCount);
            if (!uncovered.isEmpty() && Rand.chance(0.75)) {
                int label = Randomly.fromList(uncovered);
                if (plan.recursive) {
                    plan.from = label;
                    plan.to = label;
                } else {
                    plan.from = label;
                    if (plan.to == label) {
                        plan.to = (label + 1 + Randomly.getInteger(0, labelCount - 1)) % labelCount;
                    }
                }
            }
            plans.add(plan);
        }

        // 3. at least one recursive type, so recursive relationships are always reachable. This runs
        //    before the coverage pass so that a later retarget cannot take the recursion away.
        ensureRecursiveType(plans, labelCount);

        // 4. every label incident to at least one type, so no entity is isolated
        ensureCoverage(plans, labelCount);

        // 5. at least a couple of TOTAL participations, so the total-participation mapping paths
        //    (identifying relationships, weak entities) are exercised
        ensureTotalParticipations(plans);

        for (int index = 0; index < plans.size(); index++) {
            TypePlan plan = plans.get(index);
            GraphRelationshipType type = new GraphRelationshipType(index, "R" + index, plan.from, plan.to);
            type.setFromCardinality(plan.fromCardinality);
            type.setToCardinality(plan.toCardinality);
            type.setFromParticipation(plan.fromParticipation);
            type.setToParticipation(plan.toParticipation);
            schema.addRelationshipType(type);
        }
    }

    private TypePlan randomPlan(int labelCount) {
        TypePlan plan = new TypePlan();
        plan.from = Randomly.getInteger(0, labelCount);
        plan.to = Randomly.getInteger(0, labelCount);
        if (Rand.chance(RECURSIVE_PROBABILITY)) {
            plan.recursive = true;
            plan.to = plan.from;
        } else if (plan.to == plan.from) {
            // non-recursive: move to a different label
            int offset = 1 + Randomly.getInteger(0, labelCount - 1);
            plan.to = (plan.from + offset) % labelCount;
        }
        // cardinality bias: 1:N most common, then M:N, then 1:1
        double roll = Rand.uniform();
        if (roll < 0.5) {
            if (Rand.chance(0.5)) {
                plan.fromCardinality = Cardinality.ONE;
                plan.toCardinality = Cardinality.MANY;
            } else {
                plan.fromCardinality = Cardinality.MANY;
                plan.toCardinality = Cardinality.ONE;
            }
        } else if (roll < 0.8) {
            plan.fromCardinality = Cardinality.MANY;
            plan.toCardinality = Cardinality.MANY;
        } else {
            plan.fromCardinality = Cardinality.ONE;
            plan.toCardinality = Cardinality.ONE;
        }
        plan.fromParticipation = Rand.chance(BASE_TOTAL_PROBABILITY) ? Participation.TOTAL : Participation.PARTIAL;
        plan.toParticipation = Rand.chance(BASE_TOTAL_PROBABILITY) ? Participation.TOTAL : Participation.PARTIAL;
        if (plan.fromCardinality == Cardinality.ONE && plan.toCardinality == Cardinality.ONE
                && Rand.chance(ONE_TO_ONE_TOTAL_PROBABILITY)) {
            // ownership-like link: each source instance has exactly one target instance
            plan.fromParticipation = Participation.TOTAL;
            plan.toParticipation = Participation.PARTIAL;
        }
        return plan;
    }

    /** @return a random label that is neither {@code first} nor {@code second} (either may be null) */
    private int pickLabel(int labelCount, Integer first, Integer second) {
        int label = Randomly.getInteger(0, labelCount);
        while ((first != null && label == first) || (second != null && label == second)) {
            label = (label + 1) % labelCount;
        }
        return label;
    }

    /** A 1:1 link, PARTIAL on both sides: the membership shape of a union type (category). */
    private TypePlan categoryLink(int from, int to) {
        TypePlan plan = new TypePlan();
        plan.from = from;
        plan.to = to;
        plan.fromCardinality = Cardinality.ONE;
        plan.toCardinality = Cardinality.ONE;
        plan.fromParticipation = Participation.PARTIAL;
        plan.toParticipation = Participation.PARTIAL;
        plan.patternLink = true;
        return plan;
    }

    /** A random non-recursive link between two labels, used for the two sides of a triangle. */
    private TypePlan linkPlan(int from, int to) {
        TypePlan plan = new TypePlan();
        plan.from = from;
        plan.to = to;
        if (Rand.chance(0.5)) {
            plan.fromCardinality = Cardinality.ONE;
            plan.toCardinality = Cardinality.MANY;
        } else {
            plan.fromCardinality = Cardinality.MANY;
            plan.toCardinality = Cardinality.ONE;
        }
        plan.fromParticipation = Rand.chance(BASE_TOTAL_PROBABILITY) ? Participation.TOTAL : Participation.PARTIAL;
        plan.toParticipation = Rand.chance(BASE_TOTAL_PROBABILITY) ? Participation.TOTAL : Participation.PARTIAL;
        plan.patternLink = true;
        return plan;
    }

    /**
     * Makes one planned type recursive. A plan is only turned into a self-loop when its target label
     * stays covered by another plan, so this cannot undo the coverage pass; otherwise an extra
     * recursive plan is appended.
     */
    private void ensureRecursiveType(List<TypePlan> plans, int labelCount) {
        for (TypePlan plan : plans) {
            if (plan.from == plan.to) {
                return;
            }
        }
        for (TypePlan plan : plans) {
            if (plan.subtypeLink || plan.patternLink) {
                continue;
            }
            if (isCovered(plans, plan.to, plan)) {
                plan.to = plan.from;
                plan.recursive = true;
                return;
            }
        }
        TypePlan extra = randomPlan(labelCount);
        extra.recursive = true;
        extra.to = extra.from;
        plans.add(extra);
    }

    private void ensureCoverage(List<TypePlan> plans, int labelCount) {
        for (int label = 0; label < labelCount; label++) {
            if (isCovered(plans, label, null)) {
                continue;
            }
            TypePlan editable = null;
            for (TypePlan plan : plans) {
                // a subtype link carries the ISA hierarchy, a recursive plan carries the self-loop
                // case, and a pattern link carries a triangle or category shape: none may be
                // retargeted
                if (plan.subtypeLink || plan.recursive || plan.patternLink) {
                    continue;
                }
                if (isCovered(plans, plan.from, plan) && isCovered(plans, plan.to, plan)) {
                    editable = plan;
                    break;
                }
            }
            if (editable != null) {
                if (Rand.chance(0.5)) {
                    editable.from = label;
                } else {
                    editable.to = label;
                }
            } else {
                TypePlan extra = randomPlan(labelCount);
                extra.from = label;
                if (!extra.recursive && extra.to == label) {
                    extra.to = (label + 1) % labelCount;
                }
                if (extra.recursive) {
                    extra.to = label;
                }
                plans.add(extra);
            }
        }
    }

    private boolean isCovered(List<TypePlan> plans, int label, TypePlan excluded) {
        for (TypePlan plan : plans) {
            if (plan == excluded) {
                continue;
            }
            if (plan.from == label || plan.to == label) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> uncoveredLabels(List<TypePlan> plans, int labelCount) {
        List<Integer> uncovered = new ArrayList<>();
        for (int label = 0; label < labelCount; label++) {
            if (!isCovered(plans, label, null)) {
                uncovered.add(label);
            }
        }
        return uncovered;
    }

    private void ensureTotalParticipations(List<TypePlan> plans) {
        int total = 0;
        for (TypePlan plan : plans) {
            if (plan.fromParticipation == Participation.TOTAL || plan.toParticipation == Participation.TOTAL) {
                total++;
            }
        }
        for (TypePlan plan : plans) {
            if (total >= MIN_TOTAL_TYPES) {
                return;
            }
            if (plan.fromParticipation == Participation.TOTAL || plan.toParticipation == Participation.TOTAL) {
                continue;
            }
            plan.fromParticipation = Participation.TOTAL;
            total++;
        }
    }

    /** Mutable plan of one relationship type; the type itself is built once all plans are settled. */
    private static final class TypePlan {
        private int from;
        private int to;
        private Cardinality fromCardinality = Cardinality.MANY;
        private Cardinality toCardinality = Cardinality.MANY;
        private Participation fromParticipation = Participation.PARTIAL;
        private Participation toParticipation = Participation.PARTIAL;
        /** True for a planned subtype candidate link (see the class comment). */
        private boolean subtypeLink;
        /** True if the plan was drawn as a recursive (from == to) relationship type. */
        private boolean recursive;
        /** True for a planned triangle or category shape, which later passes must not retarget. */
        private boolean patternLink;
    }
}
