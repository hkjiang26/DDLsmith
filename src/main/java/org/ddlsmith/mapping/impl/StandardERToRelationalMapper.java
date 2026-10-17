package org.ddlsmith.mapping.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.mapping.ERToRelationalMapper;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingOptions.DerivedStrategy;
import org.ddlsmith.mapping.MappingOptions.IsAStrategy;
import org.ddlsmith.mapping.MappingOptions.KeyStrategy;
import org.ddlsmith.mapping.MappingOptions.ManyToManyStrategy;
import org.ddlsmith.mapping.MappingOptions.MultivaluedStrategy;
import org.ddlsmith.mapping.MappingOptions.NaryStrategy;
import org.ddlsmith.mapping.MappingOptions.OneToManyStrategy;
import org.ddlsmith.mapping.MappingOptions.OneToOneStrategy;
import org.ddlsmith.mapping.MappingOptions.ReferentialActionStrategy;
import org.ddlsmith.mapping.MappingOptions.WeakEntityStrategy;
import org.ddlsmith.mapping.MappingTrace;

/**
 * The classic Elmasri/Navathe ER-to-relational mapping algorithm, with the alternatives selected by
 * {@link MappingOptions}.
 *
 * <p>Every mapping decision is deterministic given (a) the ER diagram, (b) the options and (c) the
 * state of {@link Randomly}: all randomness flows through {@code Randomly}.</p>
 *
 * <p>Beyond the classic constructs, the mapper understands the enhanced ER features the graph stage
 * can now produce: generalization <em>lattices</em> (a shared subclass with several supertypes is
 * mapped by treating the whole connected component, in every ISA strategy), the completeness
 * constraint of a specialization (a partial specialization allows discriminator values of the root
 * itself in a single-table mapping), union types / categories (a table of its own with a surrogate
 * key, one optional foreign key per superclass and a union-membership check), aggregation (the
 * aggregation entity is stored by the join table of the relationship it aggregates, which the
 * outer relationship references), multi-level composite attributes (flattened recursively), and
 * composite natural and partial keys.</p>
 *
 * <p>The mapper never throws: constructs that cannot be mapped under the chosen options fall back to
 * the safest rule and are documented in the {@link MappingTrace}.</p>
 */
public class StandardERToRelationalMapper implements ERToRelationalMapper {

    /** The maximum length of any generated identifier; safe in every supported engine. */
    public static final int MAX_NAME_LENGTH = 60;
    /** Bound on the recursion used to resolve the owner key of a chain of weak entities. */
    private static final int MAX_OWNER_DEPTH = 16;

    private static final Pattern ENTITY_NAME_PATTERN = Pattern.compile("[Ee](\\d+)");
    private static final Pattern RELATIONSHIP_NAME_PATTERN = Pattern.compile("[Rr](\\d+)");

    /* ------------------------------------------------------------------ state */

    private ERDiagram diagram;
    private MappingOptions options;
    private String schemaName;
    private MappingTrace trace;
    private RelationalSchema schema;

    /** ER entity name -> the table that stores it, its key columns and the applied strategy. */
    private final Map<String, EntityInfo> entityInfos = new LinkedHashMap<>();
    /** "entity.attribute" -> the columns the attribute was mapped to. */
    private final Map<String, List<String>> attributeColumns = new LinkedHashMap<>();
    /** Names of identifying relationships whose foreign key was created by the weak-entity rule. */
    private final Set<String> handledIdentifying = new HashSet<>();
    /** Names of 1:1 relationships that were mapped by merging their two entity tables. */
    private final Set<String> mergedRelationships = new HashSet<>();
    /** Names of the relationships that were already mapped; mapping one twice would duplicate it. */
    private final Set<String> mappedRelationships = new HashSet<>();
    /** Entities currently being mapped, used to detect cycles in weak-entity ownership. */
    private final Set<String> inProgress = new LinkedHashSet<>();

    /** What a mapped ER entity became: a table, its key columns in that table and the strategy. */
    private static final class EntityInfo {
        final String entityName;
        Table table;
        List<String> pkColumns = new ArrayList<>();
        String strategy;
        boolean weak;
        /** True when the table is shared with another entity (merged table or single-table ISA). */
        boolean shared;

        EntityInfo(String entityName, Table table) {
            this.entityName = entityName;
            this.table = table;
        }
    }

    /* ------------------------------------------------------------------ entry point */

    @Override
    public RelationalSchema map(ERDiagram diagram, MappingOptions options, String schemaName, MappingTrace trace) {
        this.diagram = diagram == null ? new ERDiagram() : diagram;
        this.options = options == null ? new MappingOptions() : options;
        this.trace = trace == null ? new MappingTrace() : trace;
        this.schemaName = schemaName != null ? schemaName
                : (this.diagram.getName() != null ? this.diagram.getName() : "s");
        this.schema = new RelationalSchema(this.schemaName);
        this.entityInfos.clear();
        this.attributeColumns.clear();
        this.handledIdentifying.clear();
        this.mergedRelationships.clear();
        this.mappedRelationships.clear();
        this.inProgress.clear();

        try {
            mapIsaHierarchies();
            mapRemainingEntities();
            mergeOneToOneEntities();
            mapRelationships();
            mapMultivaluedAttributes();
            emitAlternateKeys();
            generateChecksUniquesAndIndexes();
            generateViews();
            generateSequences();
        } catch (RuntimeException e) {
            note("mapping aborted with " + e + "; the partially mapped schema is repaired and returned");
        }

        SchemaRepair.repair(schema, this.trace);
        for (Table table : schema.getTables()) {
            table.renumberColumns();
        }
        for (String violation : SchemaInvariants.validate(schema)) {
            note("INVARIANT VIOLATION: " + violation);
        }
        return schema;
    }

    /* ------------------------------------------------------------------ entities */

    /**
     * Maps every generalization lattice of the diagram. A lattice is the connected component of
     * ISA links: a plain hierarchy has one root, but a shared subclass (multiple inheritance)
     * connects two hierarchies into one component with two roots, and the mapping strategies must
     * treat the component as a whole - a single-table mapping of the component has one table for
     * every entity in it, a table-per-subclass mapping gives the shared subclass a foreign key to
     * each of its supertypes.
     */
    private void mapIsaHierarchies() {
        Set<String> mapped = new HashSet<>();
        for (EREntity entity : new ArrayList<>(diagram.getEntities())) {
            if (mapped.contains(entity.getName())) {
                continue;
            }
            boolean involved = entity.isGeneralizationSubtype() || entity.isGeneralizationSupertype();
            if (!involved) {
                continue;
            }
            List<EREntity> component = isaComponent(entity);
            for (EREntity member : component) {
                mapped.add(member.getName());
            }
            List<EREntity> roots = new ArrayList<>();
            int subtypes = 0;
            for (EREntity member : component) {
                if (member.isGeneralizationSubtype()) {
                    subtypes++;
                } else {
                    roots.add(member);
                }
            }
            if (subtypes == 0 || roots.isEmpty()) {
                // only cyclic garbage reaches this; the component is skipped and the entities are
                // mapped as stand-alone below
                note("the ISA links around " + entity.getName() + " do not form a hierarchy; the entities"
                        + " are mapped as stand-alone entities");
                continue;
            }
            mapIsaHierarchy(roots, component);
        }
    }

    /** @return every entity connected to {@code seed} by supertype or subtype links, cycle safe */
    private List<EREntity> isaComponent(EREntity seed) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        names.add(seed.getName());
        queue.add(seed.getName());
        while (!queue.isEmpty()) {
            String currentName = queue.poll();
            EREntity current = diagram.getEntity(currentName);
            if (current == null) {
                continue;
            }
            for (String superName : current.getSuperEntities()) {
                if (diagram.hasEntity(superName) && names.add(superName)) {
                    queue.add(superName);
                }
            }
            for (EREntity child : childEntities(current)) {
                if (names.add(child.getName())) {
                    queue.add(child.getName());
                }
            }
        }
        List<EREntity> component = new ArrayList<>();
        for (EREntity member : diagram.getEntities()) {
            if (names.contains(member.getName())) {
                component.add(member);
            }
        }
        return component;
    }

    private void mapRemainingEntities() {
        for (EREntity entity : new ArrayList<>(diagram.getEntities())) {
            if (entityInfos.containsKey(entity.getName())) {
                // a second declaration of an already-mapped entity name is indistinguishable from
                // the first one in every name-keyed lookup, so it gets no table of its own; its
                // attributes are announced rather than dropped in silence
                if (diagram.getEntity(entity.getName()) != entity) {
                    dropEntityAttributes(entity, "another entity with the same name is already mapped, and the"
                            + " mapper keys every table by entity name, so this declaration has no table");
                }
                continue;
            }
            if (entity.isAggregation()) {
                // an aggregation entity has no table of its own: it is resolved when the outer
                // relationship asks for the table of the relationship it aggregates
                note("aggregation entity " + entity.getName() + " is mapped through the relationship it"
                        + " aggregates (" + entity.getAggregatedRelationship() + ")");
                continue;
            }
            if (mapEntityByName(entity.getName()) == null) {
                dropEntityAttributes(entity, "the entity could not be mapped to any table, so its attributes have"
                        + " nowhere to go");
            }
        }
    }

    /** @return the entities that are the target of an ISA link from {@code entityName} */
    private List<EREntity> childEntities(EREntity parent) {
        Set<String> names = new LinkedHashSet<>();
        for (EREntity candidate : diagram.getEntities()) {
            if (!parent.getName().equals(candidate.getName()) && candidate.hasSuperEntity(parent.getName())) {
                names.add(candidate.getName());
            }
        }
        for (String name : parent.getSubEntities()) {
            if (!parent.getName().equals(name) && diagram.hasEntity(name)) {
                names.add(name);
            }
        }
        List<EREntity> children = new ArrayList<>();
        for (String name : names) {
            children.add(diagram.getEntity(name));
        }
        return children;
    }

    /** @return every entity below {@code root}, in breadth-first order; cycle safe */
    private List<EREntity> collectDescendants(EREntity root) {
        List<EREntity> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(root.getName());
        Deque<EREntity> queue = new ArrayDeque<>(childEntities(root));
        while (!queue.isEmpty()) {
            EREntity entity = queue.poll();
            if (entity == null || !visited.add(entity.getName())) {
                continue;
            }
            result.add(entity);
            queue.addAll(childEntities(entity));
        }
        return result;
    }

    /**
     * @return every ancestor of {@code entity} through <em>all</em> of its supertypes - the union
     *         of the inheritance chains, roots first - which is the attribute set a shared subclass
     *         inherits; cycle safe
     */
    private List<EREntity> allAncestors(EREntity entity) {
        Set<String> ancestorNames = new LinkedHashSet<>();
        collectAncestorNames(entity, ancestorNames, new HashSet<>());
        List<EREntity> remaining = new ArrayList<>();
        for (EREntity candidate : diagram.getEntities()) {
            if (ancestorNames.contains(candidate.getName())) {
                remaining.add(candidate);
            }
        }
        List<EREntity> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            EREntity pick = null;
            for (EREntity candidate : remaining) {
                if (allSupersPlaced(candidate, ordered, ancestorNames)) {
                    pick = candidate;
                    break;
                }
            }
            if (pick == null) {
                // a cycle: keep the diagram order rather than looping forever
                ordered.addAll(remaining);
                break;
            }
            remaining.remove(pick);
            ordered.add(pick);
        }
        return ordered;
    }

    private void collectAncestorNames(EREntity entity, Set<String> names, Set<String> visited) {
        for (String superName : entity.getSuperEntities()) {
            EREntity superEntity = diagram.getEntity(superName);
            if (superEntity == null || !visited.add(superName)) {
                continue;
            }
            names.add(superName);
            collectAncestorNames(superEntity, names, visited);
        }
    }

    private boolean allSupersPlaced(EREntity entity, List<EREntity> placed, Set<String> ancestorNames) {
        for (String superName : entity.getSuperEntities()) {
            if (!ancestorNames.contains(superName)) {
                continue;
            }
            boolean found = false;
            for (EREntity done : placed) {
                if (done.getName().equals(superName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private void mapIsaHierarchy(List<EREntity> roots, List<EREntity> component) {
        EREntity primaryRoot = roots.get(0);
        for (EREntity root : roots) {
            if (root.isWeak()) {
                note("entity " + root.getName() + " is both weak and a generalization supertype;"
                        + " the ISA hierarchy wins");
            }
        }
        if (roots.size() > 1) {
            note("the generalization lattice of " + primaryRoot.getName() + " has " + roots.size()
                    + " roots (" + entityNames(roots) + ") connected by a shared subclass; the lattice is"
                    + " mapped as one structure");
        }
        IsAStrategy strategy = options.getIsa();
        switch (strategy) {
        case SINGLE_TABLE:
            mapIsaSingleTable(roots, component, primaryRoot);
            break;
        case TABLE_PER_CLASS:
            mapIsaTablePerClass(roots, component, primaryRoot);
            break;
        case TABLE_PER_SUBCLASS_WITH_FK:
            mapIsaWithForeignKey(roots, component, primaryRoot);
            break;
        default:
            note("unknown ISA strategy " + strategy + " for " + primaryRoot.getName() + "; using SINGLE_TABLE");
            mapIsaSingleTable(roots, component, primaryRoot);
            break;
        }
    }

    private void mapIsaSingleTable(List<EREntity> roots, List<EREntity> component, EREntity primaryRoot) {
        String strategy = IsAStrategy.SINGLE_TABLE.name();
        Table table = createTable(uniqueTableName(entityTableName(primaryRoot), false), primaryRoot.getName(), null,
                strategy);
        EntityInfo rootInfo = new EntityInfo(primaryRoot.getName(), table);
        rootInfo.shared = true;
        rootInfo.strategy = strategy;
        entityInfos.put(primaryRoot.getName(), rootInfo);

        Set<String> added = new HashSet<>();
        addEntityAttributes(table, primaryRoot, false, options.getKeyStrategy() == KeyStrategy.NATURAL_KEY, added);
        for (EREntity root : roots) {
            if (root != primaryRoot) {
                addEntityAttributes(table, root, false, false, added);
            }
        }
        for (EREntity subtype : descendantsInOrder(roots, component)) {
            addEntityAttributes(table, subtype, true, added);
        }

        String discriminator = uniqueColumnName(table, "dtype");
        table.addColumn(new Column(discriminator, DataType.varchar(20)).setNullable(false).setDerivedFrom(null));
        table.setDiscriminatorColumn(discriminator);
        List<String> subtypeNames = new ArrayList<>();
        List<String> allowedValues = new ArrayList<>();
        for (EREntity member : component) {
            if (!member.isGeneralizationSubtype()) {
                continue;
            }
            table.getDiscriminatorValues().put(member.getName(), member.getName());
            subtypeNames.add(member.getName());
            allowedValues.add(member.getName());
        }
        for (EREntity root : roots) {
            if (!root.isDisjoint()) {
                note("the specialization of " + root.getName() + " is overlapping; a single discriminator column"
                        + " records one subtype per row, so the overlap is not represented in "
                        + table.getName());
            }
            if (!root.isTotalSpecialization()) {
                // partial specialization: instances of the root type itself stay legal, and the
                // discriminator must say so
                allowedValues.add(root.getName());
            }
        }
        table.addCheckConstraint(new CheckConstraint(
                options.isUseNamedConstraints() ? constraintName("ck", table, table.getCheckConstraints().size()) : null,
                discriminator + " IN (" + quotedList(allowedValues) + ")"));

        List<String> pk = applyKeyStrategy(table, primaryRoot);
        rootInfo.pkColumns = pk;
        trace.recordEntity(primaryRoot.getName(), table.getName(), strategy);
        for (EREntity root : roots) {
            if (root == primaryRoot) {
                continue;
            }
            EntityInfo otherRoot = new EntityInfo(root.getName(), table);
            otherRoot.pkColumns = pk;
            otherRoot.shared = true;
            otherRoot.strategy = strategy;
            entityInfos.put(root.getName(), otherRoot);
            trace.recordEntity(root.getName(), table.getName(), strategy);
        }
        for (EREntity subtype : descendantsInOrder(roots, component)) {
            EntityInfo info = new EntityInfo(subtype.getName(), table);
            info.pkColumns = pk;
            info.shared = true;
            info.strategy = strategy;
            if (subtype.isWeak()) {
                note("weak entity " + subtype.getName() + " is a subtype in a single-table hierarchy; it is stored in "
                        + table.getName());
            }
            entityInfos.put(subtype.getName(), info);
            trace.recordEntity(subtype.getName(), table.getName(), strategy);
        }
        note("ISA hierarchy rooted at " + primaryRoot.getName() + " mapped to the single table " + table.getName()
                + " with discriminator " + discriminator + " over " + allowedValues + " (" + subtypeNames.size()
                + " subtypes, " + (roots.size() == 1 && roots.get(0).isTotalSpecialization() ? "total"
                        : "partial") + " specialization)");
    }

    /** @return the non-root members of {@code component}, breadth-first from the roots */
    private List<EREntity> descendantsInOrder(List<EREntity> roots, List<EREntity> component) {
        List<EREntity> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EREntity root : roots) {
            for (EREntity descendant : collectDescendants(root)) {
                if (seen.add(descendant.getName()) && component.contains(descendant)) {
                    result.add(descendant);
                }
            }
        }
        // members of the component that the breadth-first walk missed (cyclic links) still get
        // their attributes into the table
        for (EREntity member : component) {
            if (member.isGeneralizationSubtype() && seen.add(member.getName())) {
                result.add(member);
            }
        }
        return result;
    }

    private void mapIsaTablePerClass(List<EREntity> roots, List<EREntity> component, EREntity primaryRoot) {
        String strategy = IsAStrategy.TABLE_PER_CLASS.name();
        for (EREntity subtype : component) {
            if (!subtype.isGeneralizationSubtype()) {
                continue;
            }
            Table table = createTable(uniqueTableName(entityTableName(subtype), false), subtype.getName(), null, strategy);
            EntityInfo info = new EntityInfo(subtype.getName(), table);
            info.strategy = strategy;
            entityInfos.put(subtype.getName(), info);
            Set<String> added = new HashSet<>();
            // a shared subclass inherits the attributes of every ancestor, through all its
            // supertypes, not just one chain; its own attributes come last
            List<EREntity> ancestors = allAncestors(subtype);
            for (EREntity ancestor : ancestors) {
                addEntityAttributes(table, ancestor, false,
                        ancestor == primaryRoot && options.getKeyStrategy() == KeyStrategy.NATURAL_KEY, added);
            }
            addEntityAttributes(table, subtype, false, added);
            if (!subtype.getPrimaryKeyAttributes().isEmpty()) {
                note("subtype " + subtype.getName() + " declares its own key attributes; in " + strategy
                        + " its identity is the key inherited from " + primaryRoot.getName());
            }
            info.pkColumns = applyKeyStrategy(table, primaryRoot);
            trace.recordEntity(subtype.getName(), table.getName(), strategy);
            note("TABLE_PER_CLASS: subtype " + subtype.getName() + " owns table " + table.getName()
                    + " holding the attributes of " + subtype.getName() + " and its ancestors " + entityNames(ancestors));
        }
        for (EREntity root : roots) {
            if (!root.getAttributes().isEmpty()) {
                EntityInfo info = mapStrongEntity(root, strategy);
                info.strategy = strategy;
                note("TABLE_PER_CLASS: supertype " + root.getName() + " has its own table " + info.table.getName());
            } else {
                note("TABLE_PER_CLASS: supertype " + root.getName()
                        + " has no attributes of its own and gets no table; a table is created on demand if a"
                        + " relationship needs one");
            }
        }
    }

    private void mapIsaWithForeignKey(List<EREntity> roots, List<EREntity> component, EREntity primaryRoot) {
        String strategy = IsAStrategy.TABLE_PER_SUBCLASS_WITH_FK.name();
        for (EREntity root : roots) {
            EntityInfo rootInfo = mapStrongEntity(root, strategy);
            rootInfo.strategy = strategy;
            note("TABLE_PER_SUBCLASS_WITH_FK: supertype " + root.getName() + " owns table "
                    + rootInfo.table.getName());
        }
        for (EREntity subtype : subtypesInTopologicalOrder(component)) {
            List<EntityInfo> supers = new ArrayList<>();
            boolean allMapped = true;
            for (String superName : subtype.getSuperEntities()) {
                EntityInfo superInfo = entityInfos.get(superName);
                if (superInfo == null || superInfo.table == null) {
                    note("subtype " + subtype.getName() + " cannot be linked to its supertype " + superName
                            + "; it is mapped as a stand-alone entity");
                    allMapped = false;
                    break;
                }
                supers.add(superInfo);
            }
            if (!allMapped) {
                mapStrongEntity(subtype, strategy);
                continue;
            }
            Table table = createTable(uniqueTableName(entityTableName(subtype), false), subtype.getName(), null, strategy);
            EntityInfo info = new EntityInfo(subtype.getName(), table);
            info.strategy = strategy;
            entityInfos.put(subtype.getName(), info);
            // the key of a shared subclass is the combination of the keys of all its supertypes,
            // and its primary key doubles as one foreign key per supertype
            List<String> pk = new ArrayList<>();
            List<ForeignKey> foreignKeys = new ArrayList<>();
            for (EntityInfo superInfo : supers) {
                EREntity superEntity = diagram.getEntity(superInfo.entityName);
                List<String> fkColumns = new ArrayList<>();
                for (String parentColumn : superInfo.pkColumns) {
                    Column column = superInfo.table.getColumn(parentColumn);
                    DataType type = column != null ? column.getType() : DataType.bigint();
                    String name = uniqueColumnName(table, parentColumn);
                    table.addColumn(new Column(name, type).setNullable(false)
                            .setDerivedFrom("isa:" + superInfo.entityName));
                    pk.add(name);
                    fkColumns.add(name);
                }
                ForeignKey fk = new ForeignKey(fkColumns, superInfo.table.getName(),
                        new ArrayList<>(superInfo.pkColumns));
                if (options.isUseNamedConstraints()) {
                    fk.setName(constraintName("fk", table, table.getForeignKeys().size() + foreignKeys.size()));
                }
                fk.setDerivedFromRelationship(
                        superEntity == null ? null : isaRelationshipName(superEntity, subtype));
                foreignKeys.add(fk);
            }
            if (pk.isEmpty()) {
                String surrogate = addSurrogate(table, true);
                pk = new ArrayList<>(List.of(surrogate));
                note("subtype " + subtype.getName() + " has no inherited key; it uses a surrogate key");
            }
            setPrimaryKey(table, pk);
            for (ForeignKey fk : foreignKeys) {
                configureForeignKey(fk, table, fk.getColumns(), false, false);
                table.addForeignKey(fk);
            }
            Set<String> added = new HashSet<>();
            addEntityAttributes(table, subtype, false, added);
            info.pkColumns = pk;
            trace.recordEntity(subtype.getName(), table.getName(), strategy);
            note("TABLE_PER_SUBCLASS_WITH_FK: subtype " + subtype.getName() + " table " + table.getName()
                    + " has a primary key that is also a foreign key to each of its supertypes "
                    + subtype.getSuperEntities());
        }
    }

    /**
     * @return the subtypes of {@code component} ordered so that every supertype precedes its
     *         subtypes, which is the order the foreign-key strategy needs; cycle safe
     */
    private List<EREntity> subtypesInTopologicalOrder(List<EREntity> component) {
        List<EREntity> subtypes = new ArrayList<>();
        for (EREntity member : component) {
            if (member.isGeneralizationSubtype()) {
                subtypes.add(member);
            }
        }
        List<EREntity> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (EREntity root : component) {
            if (!root.isGeneralizationSubtype()) {
                placed.add(root.getName());
            }
        }
        List<EREntity> remaining = new ArrayList<>(subtypes);
        while (!remaining.isEmpty()) {
            EREntity pick = null;
            for (EREntity candidate : remaining) {
                if (placed.containsAll(candidate.getSuperEntities())) {
                    pick = candidate;
                    break;
                }
            }
            if (pick == null) {
                ordered.addAll(remaining);
                break;
            }
            remaining.remove(pick);
            ordered.add(pick);
            placed.add(pick.getName());
        }
        return ordered;
    }

    private String isaRelationshipName(EREntity parent, EREntity subtype) {
        for (ERRelationship relationship : diagram.getRelationships()) {
            if (!relationship.isGeneralization()) {
                continue;
            }
            boolean hasParent = false;
            boolean hasSubtype = false;
            for (ERParticipant participant : relationship.getParticipants()) {
                hasParent |= participant.getEntityName().equals(parent.getName());
                hasSubtype |= participant.getEntityName().equals(subtype.getName());
            }
            if (hasParent && hasSubtype) {
                return relationship.getName();
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ single entities */

    private EntityInfo mapEntityByName(String name) {
        EntityInfo existing = entityInfos.get(name);
        if (existing != null) {
            return existing;
        }
        EREntity entity = diagram.getEntity(name);
        if (entity == null) {
            return null;
        }
        if (inProgress.contains(name)) {
            note("cycle detected in the ownership chain of weak entity " + name
                    + "; the entity is mapped as a stand-alone entity");
            return null;
        }
        if (inProgress.size() >= MAX_OWNER_DEPTH) {
            note("ownership chain longer than " + MAX_OWNER_DEPTH + " at " + name
                    + "; the entity is mapped as a stand-alone entity");
            return null;
        }
        inProgress.add(name);
        try {
            if (entity.isAggregation()) {
                return resolveAggregation(entity);
            }
            if (entity.isCategory()) {
                return mapCategory(entity);
            }
            if (entity.isWeak()) {
                return mapWeakEntity(entity);
            }
            return mapStrongEntity(entity, options.getKeyStrategy().name());
        } finally {
            inProgress.remove(name);
        }
    }

    /* ------------------------------------------------------------------ union types (categories) */

    /**
     * Maps a union type (category) after the classic rule: the category gets a table of its own
     * with a surrogate key, plus one <em>nullable</em> foreign key to each category superclass -
     * an instance belongs to at least one superclass, but no single one of them - and a CHECK
     * constraint expressing exactly that union membership. When the superclasses are disjoint the
     * stronger rule would be "exactly one"; the at-least-one form is correct in general (it also
     * covers overlapping superclasses) and is the one used here.
     */
    private EntityInfo mapCategory(EREntity entity) {
        String strategy = "CATEGORY";
        Table table = createTable(uniqueTableName(entityTableName(entity), false), entity.getName(), null, strategy);
        EntityInfo info = new EntityInfo(entity.getName(), table);
        info.strategy = strategy;
        entityInfos.put(entity.getName(), info);

        Set<String> added = new HashSet<>();
        addEntityAttributes(table, entity, false, false, added);

        List<String> membershipColumns = new ArrayList<>();
        List<String> mappedSupers = new ArrayList<>();
        for (String superName : entity.getCategorySupers()) {
            EntityInfo superInfo = infoFor(superName);
            if (superInfo == null || superInfo.table == null || superInfo.pkColumns.isEmpty()) {
                note("category " + entity.getName() + ": the superclass " + superName
                        + " cannot be mapped; its membership link is dropped");
                continue;
            }
            List<String> columns = addFkColumns(table, superInfo.table, superInfo.pkColumns, null,
                    "category:" + superName);
            if (columns.isEmpty()) {
                note("category " + entity.getName() + ": the key of the superclass " + superName
                        + " cannot be copied; its membership link is dropped");
                continue;
            }
            // membership is optional per superclass: an instance belongs to at least one, not to all
            ERRelationship link = categoryLink(entity.getName(), superName);
            addForeignKey(table, columns, superInfo.table, superInfo.pkColumns, link, false, false);
            membershipColumns.addAll(columns);
            mappedSupers.add(superName);
        }
        String surrogate = addSurrogate(table, true);
        setPrimaryKey(table, List.of(surrogate));
        info.pkColumns = new ArrayList<>(List.of(surrogate));
        if (membershipColumns.size() >= 2) {
            StringBuilder expression = new StringBuilder();
            for (int i = 0; i < membershipColumns.size(); i++) {
                if (i > 0) {
                    expression.append(" OR ");
                }
                expression.append(membershipColumns.get(i)).append(" IS NOT NULL");
            }
            table.addCheckConstraint(new CheckConstraint(
                    options.isUseNamedConstraints()
                            ? constraintName("ck", table, table.getCheckConstraints().size())
                            : null,
                    expression.toString()));
        } else if (membershipColumns.size() == 1) {
            note("category " + entity.getName() + " has only one mappable superclass; the union membership"
                    + " check is omitted");
        }
        trace.recordEntity(entity.getName(), table.getName(), strategy);
        note("category " + entity.getName() + " mapped to table " + table.getName() + " with a surrogate key and"
                + " one optional foreign key per superclass " + mappedSupers
                + (membershipColumns.size() >= 2 ? ", plus the union-membership check" : ""));
        return info;
    }

    /** @return the category membership link between {@code categoryEntity} and {@code superEntity}, if any */
    private ERRelationship categoryLink(String categoryEntity, String superEntity) {
        for (ERRelationship relationship : diagram.getRelationships()) {
            if (!relationship.isCategoryLink()) {
                continue;
            }
            boolean hasCategory = false;
            boolean hasSuper = false;
            for (ERParticipant participant : relationship.getParticipants()) {
                hasCategory |= participant.getEntityName().equals(categoryEntity);
                hasSuper |= participant.getEntityName().equals(superEntity);
            }
            if (hasCategory && hasSuper) {
                return relationship;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ aggregation */

    /**
     * Resolves an aggregation entity: it has no table of its own, it <em>is</em> the join table of
     * the relationship it aggregates (the relationship that the aggregation was drawn around). The
     * inner relationship must map to a join table - which the aggregation guarantees by only being
     * recognized around an M:N relationship - and relationships that connect to the aggregation
     * then reference that table's key.
     */
    private EntityInfo resolveAggregation(EREntity entity) {
        String innerName = entity.getAggregatedRelationship();
        ERRelationship inner = innerName == null ? null : diagram.getRelationship(innerName);
        if (inner == null || inner.isGeneralization() || inner.isCategoryLink() || inner.isAggregationLink()) {
            note("aggregation entity " + entity.getName() + " has no aggregatable relationship; it is mapped as"
                    + " a stand-alone entity");
            return mapStrongEntity(entity, "AGGREGATION");
        }
        if (!mappedRelationships.contains(inner.getName())) {
            // the inner relationship has not been mapped yet: map it now so the aggregation can
            // stand on its join table
            mapRelationship(inner);
        }
        String joinTableName = trace.getRelationshipToTable().get(inner.getName());
        Table joinTable = joinTableName == null ? null : schema.getTable(joinTableName);
        if (joinTable == null || joinTable.getPrimaryKey() == null || joinTable.getPrimaryKey().getColumns().isEmpty()) {
            note("aggregation entity " + entity.getName() + ": the aggregated relationship " + inner.getName()
                    + " produced no join table; the aggregation is mapped as a stand-alone entity");
            return mapStrongEntity(entity, "AGGREGATION");
        }
        EntityInfo info = new EntityInfo(entity.getName(), joinTable);
        info.shared = true;
        info.pkColumns = new ArrayList<>(joinTable.getPrimaryKey().getColumns());
        info.strategy = "AGGREGATION";
        entityInfos.put(entity.getName(), info);
        trace.recordEntity(entity.getName(), joinTable.getName(), "AGGREGATION");
        // the aggregation has no table of its own: it *is* the join table, whose columns come from
        // the relationship, so any attribute declared on the aggregation entity itself is not stored
        dropEntityAttributes(entity, "an aggregation entity is stored by the join table of the relationship it"
                + " aggregates; that table carries the relationship's columns, not the entity's own attributes");
        note("aggregation entity " + entity.getName() + " is stored by the join table " + joinTable.getName()
                + " of " + inner.getName() + "; relationships to the aggregation reference its key "
                + info.pkColumns);
        return info;
    }

    private EntityInfo mapStrongEntity(EREntity entity, String strategy) {
        String tableName = uniqueTableName(entityTableName(entity), false);
        Table table = createTable(tableName, entity.getName(), null, strategy);
        EntityInfo info = new EntityInfo(entity.getName(), table);
        info.strategy = strategy;
        entityInfos.put(entity.getName(), info);

        KeyStrategy keyStrategy = options.getKeyStrategy();
        List<ERAttribute> naturalKey = new ArrayList<>(entity.getPrimaryKeyAttributes());
        boolean surrogate = keyStrategy != KeyStrategy.NATURAL_KEY || naturalKey.isEmpty();
        boolean attributeNamedId = hasAttributeNamed(entity, "id");
        Set<String> added = new HashSet<>();
        String surrogateColumn = null;
        if (surrogate && !attributeNamedId) {
            surrogateColumn = addSurrogate(table, true);
        }
        addEntityAttributes(table, entity, false, keyStrategy == KeyStrategy.NATURAL_KEY, added);
        if (surrogate && attributeNamedId) {
            surrogateColumn = addSurrogate(table, false);
        }

        List<String> pk;
        if (surrogate) {
            if (naturalKey.isEmpty() && keyStrategy == KeyStrategy.NATURAL_KEY) {
                note("entity " + entity.getName() + " has no key attribute; a surrogate key is used instead");
            }
            pk = new ArrayList<>();
            if (surrogateColumn != null) {
                pk.add(surrogateColumn);
            }
            if (keyStrategy == KeyStrategy.SURROGATE_KEY_WITH_NATURAL_UNIQUE && !naturalKey.isEmpty()) {
                List<String> naturalColumns = columnsOf(entity.getName(), naturalKey, table);
                if (!naturalColumns.isEmpty()) {
                    addUnique(table, naturalColumns);
                } else {
                    note("entity " + entity.getName() + " has no mappable natural key; no unique constraint is created");
                }
            }
        } else {
            pk = columnsOf(entity.getName(), naturalKey, table);
            if (pk.isEmpty()) {
                surrogateColumn = addSurrogate(table, false);
                pk = new ArrayList<>(List.of(surrogateColumn));
                note("entity " + entity.getName() + " has no mappable key attribute; a surrogate key is used instead");
            }
        }
        if (pk.isEmpty()) {
            surrogateColumn = addSurrogate(table, false);
            pk = new ArrayList<>(List.of(surrogateColumn));
        }
        setPrimaryKey(table, pk);
        info.pkColumns = pk;
        trace.recordEntity(entity.getName(), table.getName(), strategy);
        return info;
    }

    private EntityInfo mapWeakEntity(EREntity entity) {
        String ownerName = resolveOwnerName(entity);
        EntityInfo owner = ownerName == null ? null : mapEntityByName(ownerName);
        if (owner == null || owner.table == null || owner.pkColumns.isEmpty()) {
            note("weak entity " + entity.getName() + ": its owner " + ownerName
                    + " cannot be resolved; falling back to a stand-alone table with a surrogate key");
            EntityInfo info = mapStrongEntity(entity, WeakEntityStrategy.OWN_TABLE_SURROGATE_PK.name());
            info.weak = true;
            return info;
        }
        WeakEntityStrategy strategy = options.getWeakEntity();
        Table table = createTable(uniqueTableName(entityTableName(entity), false), entity.getName(), null, strategy.name());
        EntityInfo info = new EntityInfo(entity.getName(), table);
        info.weak = true;
        info.strategy = strategy.name();
        entityInfos.put(entity.getName(), info);

        Set<String> added = new HashSet<>();
        List<String> partial = new ArrayList<>();
        for (ERAttribute attribute : entity.getPartialKeyAttributes()) {
            // the partial keys are added before the rest of the attributes, so that they are the
            // first columns of the composite key; they must still be recorded in the trace, or the
            // trace would not account for them (the shared `added` set makes the later
            // addEntityAttributes pass skip them)
            List<String> columns = addAttribute(table, entity.getName(), attribute, true, false, added);
            partial.addAll(columns);
            if (!columns.isEmpty()) {
                trace.recordAttribute(entity.getName(), attribute.getName(), columns.get(0));
            }
        }
        addEntityAttributes(table, entity, false, added);

        List<String> ownerColumns = addFkColumns(table, owner.table, owner.pkColumns, null, entity.getOwnerRelationship());
        if (ownerColumns.isEmpty()) {
            note("weak entity " + entity.getName() + ": the owner key columns could not be copied; using a surrogate key");
            String surrogate = addSurrogate(table, false);
            setPrimaryKey(table, List.of(surrogate));
            info.pkColumns = new ArrayList<>(List.of(surrogate));
            trace.recordEntity(entity.getName(), table.getName(), strategy.name());
            return info;
        }
        setNotNull(ownerColumns, true, table);
        ForeignKey fk = new ForeignKey(new ArrayList<>(ownerColumns), owner.table.getName(),
                new ArrayList<>(owner.pkColumns));
        if (options.isUseNamedConstraints()) {
            fk.setName(constraintName("fk", table, table.getForeignKeys().size()));
        }
        if (entity.getOwnerRelationship() != null) {
            fk.setDerivedFromRelationship(entity.getOwnerRelationship());
            handledIdentifying.add(entity.getOwnerRelationship());
        }
        configureForeignKey(fk, table, ownerColumns, true, true);
        table.addForeignKey(fk);

        // The classic rule: the attributes of an identifying relationship belong to the weak
        // entity's relation. The relationship is what gives the weak entity its identity, so its
        // attributes describe the weak entity in the context of its owner - and nowhere else, since
        // the identifying relationship is never mapped as an association. They stay out of the
        // primary key: the key is the owner key plus the partial key.
        ERRelationship identifying = entity.getOwnerRelationship() == null
                ? null
                : diagram.getRelationship(entity.getOwnerRelationship());
        if (identifying != null) {
            addRelationshipAttributes(table, identifying);
        }

        List<String> pk;
        if (strategy == WeakEntityStrategy.OWN_TABLE_COMPOSITE_PK) {
            pk = new ArrayList<>(ownerColumns);
            pk.addAll(partial);
            if (partial.isEmpty()) {
                note("weak entity " + entity.getName() + " has no partial key attribute; its primary key is the owner key");
            }
        } else {
            String surrogate = addSurrogate(table, true);
            pk = new ArrayList<>(List.of(surrogate));
            List<String> uniqueColumns = new ArrayList<>(ownerColumns);
            uniqueColumns.addAll(partial);
            addUnique(table, uniqueColumns);
        }
        setPrimaryKey(table, pk);
        info.pkColumns = pk;
        trace.recordEntity(entity.getName(), table.getName(), strategy.name());
        note("weak entity " + entity.getName() + " mapped with " + strategy + ": owner " + ownerName
                + " contributes columns " + ownerColumns + ", partial key columns " + partial);
        return info;
    }

    private String resolveOwnerName(EREntity entity) {
        if (entity.getOwnerEntity() != null && diagram.hasEntity(entity.getOwnerEntity())
                && !entity.getOwnerEntity().equals(entity.getName())) {
            return entity.getOwnerEntity();
        }
        if (entity.getOwnerRelationship() != null) {
            ERRelationship relationship = diagram.getRelationship(entity.getOwnerRelationship());
            if (relationship != null) {
                for (ERParticipant participant : relationship.getParticipants()) {
                    if (!participant.getEntityName().equals(entity.getName())
                            && diagram.hasEntity(participant.getEntityName())) {
                        return participant.getEntityName();
                    }
                }
            }
        }
        for (ERRelationship relationship : diagram.getRelationshipsOf(entity.getName())) {
            if (!relationship.isIdentifying()) {
                continue;
            }
            for (ERParticipant participant : relationship.getParticipants()) {
                if (!participant.getEntityName().equals(entity.getName())
                        && diagram.hasEntity(participant.getEntityName())) {
                    return participant.getEntityName();
                }
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ 1:1 merging */

    private void mergeOneToOneEntities() {
        if (options.getOneToOne() != OneToOneStrategy.MERGE_TABLES) {
            return;
        }
        for (ERRelationship relationship : new ArrayList<>(diagram.getRelationships())) {
            if (relationship.isGeneralization() || !relationship.isBinary() || relationship.isRecursive()) {
                continue;
            }
            if (relationship.getBinaryCardinality() != ERRelationship.BinaryCardinality.ONE_TO_ONE) {
                continue;
            }
            attemptMerge(relationship);
        }
    }

    private void attemptMerge(ERRelationship relationship) {
        List<ERParticipant> participants = relationship.getParticipants();
        EntityInfo first = entityInfos.get(participants.get(0).getEntityName());
        EntityInfo second = entityInfos.get(participants.get(1).getEntityName());
        if (first == null || second == null || first.table == null || second.table == null) {
            note("1:1 relationship " + relationship.getName()
                    + " cannot be merged: one of its entities has no table; falling back to FK_ON_TOTAL_SIDE");
            return;
        }
        if (first.table == second.table) {
            mergedRelationships.add(relationship.getName());
            trace.recordRelationship(relationship.getName(), first.table.getName(), OneToOneStrategy.MERGE_TABLES.name());
            // the two entities already share one table, so that table is where the relationship's
            // own attributes belong - the same place the other 1:1 strategies put them
            addRelationshipAttributes(first.table, relationship);
            note("1:1 relationship " + relationship.getName() + ": both entities already share table "
                    + first.table.getName() + "; nothing to merge");
            return;
        }
        if (first.weak || second.weak || isShared(first) || isShared(second) || isReferenced(second.table)) {
            note("1:1 relationship " + relationship.getName()
                    + " cannot be merged safely (weak entity or shared/referenced table); falling back to FK_ON_TOTAL_SIDE");
            return;
        }
        Table target = first.table;
        Table removed = second.table;
        Map<String, String> renames = new HashMap<>();
        for (Column column : new ArrayList<>(removed.getColumns())) {
            String oldName = column.getName();
            String newName = uniqueColumnName(target, oldName);
            if (!newName.equals(oldName)) {
                column.setName(newName);
                renames.put(oldName, newName);
            }
            target.addColumn(column);
        }
        // the keys of the merged entity must stay unique in the merged table
        for (UniqueConstraint unique : removed.getUniqueConstraints()) {
            List<String> columns = new ArrayList<>();
            for (String column : unique.getColumns()) {
                columns.add(renames.getOrDefault(column, column));
            }
            addUnique(target, columns);
        }
        schema.removeTable(removed.getName());
        Map<String, Map<String, String>> attributes = trace.getAttributeToColumn();
        Map<String, String> secondAttributes = attributes.get(second.entityName);
        if (secondAttributes != null) {
            for (Map.Entry<String, String> entry : new ArrayList<>(secondAttributes.entrySet())) {
                String renamed = renames.get(entry.getValue());
                if (renamed != null) {
                    entry.setValue(renamed);
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : attributeColumns.entrySet()) {
            if (entry.getKey().startsWith(second.entityName + ".")) {
                List<String> renamed = new ArrayList<>();
                for (String column : entry.getValue()) {
                    renamed.add(renames.getOrDefault(column, column));
                }
                entry.setValue(renamed);
            }
        }
        second.table = target;
        second.pkColumns = first.pkColumns;
        second.shared = true;
        second.strategy = OneToOneStrategy.MERGE_TABLES.name();
        target.setDerivedFromRelationship(relationship.getName());
        target.setMappingStrategy(OneToOneStrategy.MERGE_TABLES.name());
        mergedRelationships.add(relationship.getName());
        trace.recordEntity(second.entityName, target.getName(), OneToOneStrategy.MERGE_TABLES.name());
        trace.recordRelationship(relationship.getName(), target.getName(), OneToOneStrategy.MERGE_TABLES.name());
        // the merged table also carries the relationship's own attributes: merging the two entity
        // tables is the mapping of the relationship, so its attributes live in the merged table,
        // exactly as they live in the surviving table under the other three 1:1 strategies
        addRelationshipAttributes(target, relationship);
        note("1:1 relationship " + relationship.getName() + " mapped with MERGE_TABLES: " + removed.getName()
                + " merged into " + target.getName() + (renames.isEmpty() ? "" : " (renamed " + renames + ")"));
    }

    private boolean isShared(EntityInfo info) {
        int users = 0;
        for (EntityInfo other : entityInfos.values()) {
            if (other.table == info.table) {
                users++;
            }
        }
        return users > 1 || info.shared;
    }

    private boolean isReferenced(Table table) {
        for (RelationalSchema.ForeignKeyRef ref : schema.getAllForeignKeys()) {
            if (ref.foreignKey.getReferencedTable().equals(table.getName()) && ref.table != table) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ relationships */

    private void mapRelationships() {
        for (ERRelationship relationship : new ArrayList<>(diagram.getRelationships())) {
            try {
                mapRelationship(relationship);
            } catch (RuntimeException e) {
                trace.recordRelationship(relationship.getName(), null, "SKIPPED");
                note("relationship " + relationship.getName() + " could not be mapped (" + e
                        + "); it is skipped");
                dropRelationshipAttributes(relationship, "the relationship could not be mapped, so its attributes"
                        + " have no table");
            }
        }
        // safety net: an aggregation entity that no outer relationship asked for (because the
        // outer relationship could not be mapped) is still resolved, so its entity is not lost
        for (EREntity entity : diagram.getEntities()) {
            if (entity.isAggregation() && !entityInfos.containsKey(entity.getName())) {
                try {
                    resolveAggregation(entity);
                } catch (RuntimeException e) {
                    note("aggregation entity " + entity.getName() + " could not be resolved (" + e + ")");
                }
            }
        }
    }

    private void mapRelationship(ERRelationship relationship) {
        if (!mappedRelationships.add(relationship.getName())) {
            return;
        }
        if (relationship.isGeneralization()) {
            trace.recordRelationship(relationship.getName(), null, "ISA");
            note("generalization link " + relationship.getName() + " is not mapped as an association");
            dropRelationshipAttributes(relationship, "a generalization link is structural: it produces no table,"
                    + " so its attributes have nowhere to go");
            return;
        }
        if (relationship.isCategoryLink()) {
            trace.recordRelationship(relationship.getName(), null, "CATEGORY_LINK");
            note("category membership link " + relationship.getName()
                    + " is represented by the mapping of the category itself; it is not mapped as an association");
            dropRelationshipAttributes(relationship, "a category membership link is structural: the category's own"
                    + " table carries the membership, so the link's attributes have nowhere to go");
            return;
        }
        if (relationship.isAggregationLink()) {
            trace.recordRelationship(relationship.getName(), null, "AGGREGATION_LINK");
            note("aggregation link " + relationship.getName()
                    + " is absorbed by the outer relationship of the aggregation; it is not mapped as an"
                    + " association");
            dropRelationshipAttributes(relationship, "an absorbed aggregation link is structural: the outer"
                    + " relationship replaces it, and the link's attributes are not merged into it");
            return;
        }
        if (mergedRelationships.contains(relationship.getName())) {
            // the merge already stored the relationship's attributes in the surviving table
            return;
        }
        if (relationship.isIdentifying() && handledIdentifying.contains(relationship.getName())) {
            String table = null;
            for (ERParticipant participant : relationship.getParticipants()) {
                EntityInfo info = entityInfos.get(participant.getEntityName());
                if (info != null && info.weak) {
                    table = info.table.getName();
                    break;
                }
            }
            trace.recordRelationship(relationship.getName(), table, "IDENTIFYING_RELATIONSHIP");
            note("identifying relationship " + relationship.getName()
                    + " is already represented by the foreign key of its weak entity; it is not mapped again");
            // its attributes were copied into the weak entity's table by mapWeakEntity
            return;
        }
        int degree = relationship.getParticipants().size();
        if (degree == 0) {
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            note("relationship " + relationship.getName() + " has no participant; it is skipped");
            dropRelationshipAttributes(relationship, "the relationship has no participant, so no table was created"
                    + " for it");
            return;
        }
        if (relationship.isRecursive() && degree == 2) {
            mapRecursive(relationship);
            return;
        }
        if (degree == 1) {
            mapUnary(relationship);
            return;
        }
        if (degree == 2) {
            switch (relationship.getBinaryCardinality()) {
            case ONE_TO_ONE:
                mapOneToOne(relationship);
                break;
            case ONE_TO_MANY:
                mapOneToMany(relationship);
                break;
            default:
                mapManyToMany(relationship);
                break;
            }
            return;
        }
        mapNary(relationship);
    }

    private void mapOneToOne(ERRelationship relationship) {
        OneToOneStrategy strategy = options.getOneToOne();
        if (strategy == OneToOneStrategy.MERGE_TABLES) {
            note("1:1 relationship " + relationship.getName() + " was not merged; falling back to FK_ON_TOTAL_SIDE");
            strategy = OneToOneStrategy.FK_ON_TOTAL_SIDE;
        }
        List<ERParticipant> participants = relationship.getParticipants();
        if (strategy == OneToOneStrategy.JOIN_TABLE) {
            Set<Integer> unique = new LinkedHashSet<>();
            unique.add(0);
            unique.add(1);
            buildJoinTable(relationship, OneToOneStrategy.JOIN_TABLE.name(), false, unique);
            return;
        }
        int holderIndex;
        if (strategy == OneToOneStrategy.FK_ON_TOTAL_SIDE) {
            boolean total0 = participants.get(0).getParticipation() == Participation.TOTAL;
            boolean total1 = participants.get(1).getParticipation() == Participation.TOTAL;
            if (total0 && !total1) {
                holderIndex = 0;
            } else if (total1 && !total0) {
                holderIndex = 1;
            } else {
                holderIndex = Randomly.getBoolean() ? 0 : 1;
                note("1:1 relationship " + relationship.getName()
                        + ": both sides have the same participation; side " + holderIndex + " was picked randomly");
            }
        } else {
            holderIndex = Randomly.getBoolean() ? 0 : 1;
        }
        ERParticipant holder = participants.get(holderIndex);
        ERParticipant other = participants.get(1 - holderIndex);
        EntityInfo holderInfo = infoFor(holder.getEntityName());
        EntityInfo otherInfo = infoFor(other.getEntityName());
        if (holderInfo == null || otherInfo == null) {
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            note("1:1 relationship " + relationship.getName() + " references an unknown entity; it is skipped");
            dropRelationshipAttributes(relationship, "the relationship references an entity that is not in the"
                    + " diagram, so no table was created for it");
            return;
        }
        boolean notNull = holder.getParticipation() == Participation.TOTAL;
        List<String> columns = addFkColumns(holderInfo.table, otherInfo.table, otherInfo.pkColumns,
                holderInfo.table == otherInfo.table ? holder.getRoleName() : null, relationship.getName());
        if (columns.isEmpty()) {
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            note("1:1 relationship " + relationship.getName() + " has no usable key columns; it is skipped");
            dropRelationshipAttributes(relationship, "no usable key columns were available, so no table was created"
                    + " for the relationship");
            return;
        }
        setNotNull(columns, notNull, holderInfo.table);
        addForeignKey(holderInfo.table, columns, otherInfo.table, otherInfo.pkColumns, relationship, notNull, false);
        addUnique(holderInfo.table, columns);
        addRelationshipAttributes(holderInfo.table, relationship);
        trace.recordRelationship(relationship.getName(), holderInfo.table.getName(), strategy.name());
        note("1:1 relationship " + relationship.getName() + " mapped with " + strategy + ": foreign key "
                + columns + " in " + holderInfo.table.getName() + " referencing " + otherInfo.table.getName()
                + " (NOT NULL=" + notNull + ")");
    }

    private void mapOneToMany(ERRelationship relationship) {
        OneToManyStrategy strategy = options.getOneToMany();
        List<ERParticipant> participants = relationship.getParticipants();
        int manyIndex = participants.get(0).getCardinality() == Cardinality.MANY ? 0 : 1;
        if (strategy == OneToManyStrategy.JOIN_TABLE) {
            Set<Integer> unique = new LinkedHashSet<>();
            unique.add(manyIndex);
            buildJoinTable(relationship, OneToManyStrategy.JOIN_TABLE.name(), false, unique);
            return;
        }
        int holderIndex = strategy == OneToManyStrategy.FK_ON_MANY_SIDE ? manyIndex : 1 - manyIndex;
        ERParticipant holder = participants.get(holderIndex);
        ERParticipant other = participants.get(1 - holderIndex);
        EntityInfo holderInfo = infoFor(holder.getEntityName());
        EntityInfo otherInfo = infoFor(other.getEntityName());
        if (holderInfo == null || otherInfo == null) {
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            note("1:N relationship " + relationship.getName() + " references an unknown entity; it is skipped");
            dropRelationshipAttributes(relationship, "the relationship references an entity that is not in the"
                    + " diagram, so no table was created for it");
            return;
        }
        boolean notNull = holder.getParticipation() == Participation.TOTAL;
        List<String> columns = addFkColumns(holderInfo.table, otherInfo.table, otherInfo.pkColumns,
                holderInfo.table == otherInfo.table ? holder.getRoleName() : null, relationship.getName());
        if (columns.isEmpty()) {
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            note("1:N relationship " + relationship.getName() + " has no usable key columns; it is skipped");
            dropRelationshipAttributes(relationship, "no usable key columns were available, so no table was created"
                    + " for the relationship");
            return;
        }
        setNotNull(columns, notNull, holderInfo.table);
        addForeignKey(holderInfo.table, columns, otherInfo.table, otherInfo.pkColumns, relationship, notNull, false);
        if (strategy == OneToManyStrategy.FK_ON_ONE_SIDE_UNIQUE) {
            addUnique(holderInfo.table, columns);
        }
        addRelationshipAttributes(holderInfo.table, relationship);
        trace.recordRelationship(relationship.getName(), holderInfo.table.getName(), strategy.name());
        note("1:N relationship " + relationship.getName() + " mapped with " + strategy + ": foreign key " + columns
                + " in " + holderInfo.table.getName() + " referencing " + otherInfo.table.getName() + " (NOT NULL="
                + notNull + ")");
    }

    private void mapManyToMany(ERRelationship relationship) {
        ManyToManyStrategy strategy = options.getManyToMany();
        buildJoinTable(relationship, strategy.name(),
                strategy == ManyToManyStrategy.JOIN_TABLE_WITH_SURROGATE_KEY, new LinkedHashSet<>());
    }

    private void mapNary(ERRelationship relationship) {
        NaryStrategy strategy = options.getNary();
        if (relationship.isRecursive()) {
            note("n-ary relationship " + relationship.getName()
                    + " connects one entity several times; role-qualified foreign key columns are used");
        }
        buildJoinTable(relationship, strategy.name(), strategy == NaryStrategy.JOIN_TABLE_WITH_SURROGATE_KEY,
                new LinkedHashSet<>());
    }

    private void mapRecursive(ERRelationship relationship) {
        List<ERParticipant> participants = relationship.getParticipants();
        ERParticipant first = participants.get(0);
        ERParticipant second = participants.get(1);
        if (first.getCardinality() == Cardinality.MANY && second.getCardinality() == Cardinality.MANY) {
            ManyToManyStrategy strategy = options.getManyToMany();
            buildJoinTable(relationship, strategy.name(),
                    strategy == ManyToManyStrategy.JOIN_TABLE_WITH_SURROGATE_KEY, new LinkedHashSet<>());
            return;
        }
        ERParticipant holder = storingParticipant(relationship);
        if (holder == null) {
            holder = first;
        }
        ERParticipant other = holder == first ? second : first;
        EntityInfo info = infoFor(holder.getEntityName());
        if (info == null) {
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            note("recursive relationship " + relationship.getName() + " references an unknown entity; it is skipped");
            dropRelationshipAttributes(relationship, "the relationship references an entity that is not in the"
                    + " diagram, so no table was created for it");
            return;
        }
        boolean oneToOne = first.getCardinality() == Cardinality.ONE && second.getCardinality() == Cardinality.ONE;
        String strategy = oneToOne ? "RECURSIVE_ONE_TO_ONE" : "RECURSIVE_ONE_TO_MANY";
        boolean notNull = holder.getParticipation() == Participation.TOTAL;
        List<String> columns = addFkColumns(info.table, info.table, info.pkColumns, holder.getRoleName(),
                relationship.getName());
        if (columns.isEmpty()) {
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            note("recursive relationship " + relationship.getName() + " has no usable key columns; it is skipped");
            dropRelationshipAttributes(relationship, "no usable key columns were available, so no table was created"
                    + " for the relationship");
            return;
        }
        setNotNull(columns, notNull, info.table);
        ForeignKey fk = addForeignKey(info.table, columns, info.table, info.pkColumns, relationship, notNull, false);
        fk.setSelfReferential(true);
        if (oneToOne) {
            addUnique(info.table, columns);
        }
        addRelationshipAttributes(info.table, relationship);
        trace.recordRelationship(relationship.getName(), info.table.getName(), strategy);
        note("recursive relationship " + relationship.getName() + " mapped with " + strategy + ": column " + columns
                + " in " + info.table.getName() + " references its own primary key (role " + holder.getRoleName()
                + ", NOT NULL=" + notNull + ")");
        if (other == null) {
            note("recursive relationship " + relationship.getName() + " has an inconsistent participant list");
        }
    }

    /** @return the participant that stores the reference to the other side of a recursive relationship */
    private ERParticipant storingParticipant(ERRelationship relationship) {
        List<ERParticipant> participants = relationship.getParticipants();
        for (ERParticipant participant : participants) {
            String role = participant.getRoleName();
            if (role != null && role.toLowerCase(Locale.ROOT).endsWith("_dst")) {
                return participant;
            }
        }
        ERParticipant many = null;
        int manyCount = 0;
        for (ERParticipant participant : participants) {
            if (participant.getCardinality() == Cardinality.MANY) {
                many = participant;
                manyCount++;
            }
        }
        if (manyCount == 1) {
            return many;
        }
        if (manyCount == 0) {
            return participants.get(0);
        }
        return null;
    }

    private void mapUnary(ERRelationship relationship) {
        ERParticipant participant = relationship.getParticipants().get(0);
        EntityInfo info = infoFor(participant.getEntityName());
        if (info == null) {
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            note("unary relationship " + relationship.getName() + " references an unknown entity; it is skipped");
            dropRelationshipAttributes(relationship, "the relationship references an entity that is not in the"
                    + " diagram, so no table was created for it");
            return;
        }
        boolean notNull = participant.getParticipation() == Participation.TOTAL;
        List<String> columns = addFkColumns(info.table, info.table, info.pkColumns, participant.getRoleName(),
                relationship.getName());
        if (columns.isEmpty()) {
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            // this used to be the only path that skipped a relationship without saying why
            note("unary relationship " + relationship.getName() + " has no usable key columns; it is skipped");
            dropRelationshipAttributes(relationship, "no usable key columns were available, so no table was created"
                    + " for the relationship");
            return;
        }
        setNotNull(columns, notNull, info.table);
        ForeignKey fk = addForeignKey(info.table, columns, info.table, info.pkColumns, relationship, notNull, false);
        fk.setSelfReferential(true);
        addRelationshipAttributes(info.table, relationship);
        trace.recordRelationship(relationship.getName(), info.table.getName(), "RECURSIVE_UNARY");
        note("relationship " + relationship.getName() + " has a single participant; it is mapped as the self-referencing"
                + " foreign key " + columns + " in " + info.table.getName());
    }

    private void buildJoinTable(ERRelationship relationship, String strategyLabel, boolean surrogateKey,
            Set<Integer> uniqueGroups) {
        Table table = createTable(uniqueTableName(relationshipTableName(relationship), true), null,
                relationship.getName(), strategyLabel);
        List<String> allColumns = new ArrayList<>();
        List<List<String>> columnsPerParticipant = new ArrayList<>();
        Set<Table> usedTables = new HashSet<>();
        int index = 0;
        for (ERParticipant participant : relationship.getParticipants()) {
            List<String> columns = List.of();
            EntityInfo info = infoFor(participant.getEntityName());
            if (info != null && !info.pkColumns.isEmpty()) {
                String prefix = relationship.isRecursive() || usedTables.contains(info.table)
                        ? participant.getRoleName()
                        : null;
                columns = addFkColumns(table, info.table, info.pkColumns, prefix, relationship.getName());
                if (!columns.isEmpty()) {
                    setNotNull(columns, true, table);
                    addForeignKey(table, columns, info.table, info.pkColumns, relationship, true, false);
                    if (uniqueGroups.contains(index)) {
                        addUnique(table, columns);
                    }
                    allColumns.addAll(columns);
                    usedTables.add(info.table);
                }
            } else {
                note("participant " + participant.getEntityName() + " of relationship " + relationship.getName()
                        + " could not be mapped; it is left out of " + table.getName());
            }
            columnsPerParticipant.add(columns);
            index++;
        }
        if (allColumns.isEmpty()) {
            schema.removeTable(table.getName());
            trace.recordRelationship(relationship.getName(), null, "SKIPPED");
            note("relationship " + relationship.getName() + " has no mappable participant; no join table was created");
            dropRelationshipAttributes(relationship, "none of the participants could be mapped, so the join table"
                    + " was removed and the attributes have nowhere to go");
            return;
        }
        if (surrogateKey) {
            String surrogate = addSurrogate(table, true);
            setPrimaryKey(table, List.of(surrogate));
            addUnique(table, allColumns);
        } else {
            setPrimaryKey(table, naryPrimaryKey(relationship, columnsPerParticipant, allColumns));
        }
        addRelationshipAttributes(table, relationship);
        trace.recordRelationship(relationship.getName(), table.getName(), strategyLabel);
        note("relationship " + relationship.getName() + " mapped to join table " + table.getName() + " ("
                + strategyLabel + ") with foreign keys " + allColumns);
    }

    /**
     * The primary key of an n-ary relationship's join table. The classic rule: the foreign keys of
     * the participants that take part with cardinality N (many) form the key, and the foreign key
     * of a participant with cardinality 1 does not - that participant pairs with at most one
     * instance for each combination of the others, which the key of the others already expresses.
     * When no participant has cardinality N, or when two or more have cardinality 1, excluding the
     * one-side keys would over-constrain the relationship (two instances that differ only in a
     * one-side participant would collide), so every foreign key becomes part of the key instead.
     */
    private List<String> naryPrimaryKey(ERRelationship relationship, List<List<String>> columnsPerParticipant,
            List<String> allColumns) {
        if (relationship.getDegree() <= 2) {
            return allColumns;
        }
        int ones = 0;
        for (ERParticipant participant : relationship.getParticipants()) {
            if (participant.getCardinality() == Cardinality.ONE) {
                ones++;
            }
        }
        if (ones != 1) {
            return allColumns;
        }
        List<String> pk = new ArrayList<>();
        List<ERParticipant> participants = relationship.getParticipants();
        for (int i = 0; i < participants.size(); i++) {
            if (participants.get(i).getCardinality() == Cardinality.MANY) {
                pk.addAll(columnsPerParticipant.get(i));
            }
        }
        if (pk.isEmpty()) {
            return allColumns;
        }
        note("n-ary relationship " + relationship.getName() + ": the primary key of its join table is the foreign"
                + " keys of the participants with cardinality many, after the classic n-ary rule");
        return pk;
    }

    /* ------------------------------------------------------------------ multi-valued attributes */

    private void mapMultivaluedAttributes() {
        for (EREntity entity : new ArrayList<>(diagram.getEntities())) {
            EntityInfo info = entityInfos.get(entity.getName());
            if (info == null || info.table == null || info.pkColumns.isEmpty()) {
                // the entity has no table of its own to hang the value table off; every multi-valued
                // attribute of it is lost, so each one is announced
                for (ERAttribute attribute : entity.getAttributes()) {
                    if (attribute.isMultivalued()) {
                        trace.recordDroppedAttribute(entity.getName(), attribute.getName(),
                                "the multi-valued attribute needs its own table keyed by the owner's key, and the"
                                        + " entity has no mapped table or key to reference");
                    }
                }
                continue;
            }
            int index = 0;
            for (ERAttribute attribute : entity.getAttributes()) {
                if (!attribute.isMultivalued()) {
                    continue;
                }
                mapMultivaluedAttribute(info, entity, attribute, index++);
            }
        }
    }

    private void mapMultivaluedAttribute(EntityInfo owner, EREntity entity, ERAttribute attribute, int index) {
        if (attribute.isDerived() && options.getDerived() == DerivedStrategy.OMIT) {
            note("multi-valued derived attribute " + entity.getName() + "." + attribute.getName()
                    + " is omitted because derived attributes are omitted");
            trace.recordDroppedAttribute(entity.getName(), attribute.getName(),
                    "the attribute is both multi-valued and derived, and derived attributes are mapped with the"
                            + " OMIT strategy in this run");
            return;
        }
        MultivaluedStrategy strategy = options.getMultivalued();
        String base = "mv_" + sanitize(entity.getName()) + "_" + index;
        Table table = createTable(uniqueTableName(base, false), entity.getName(), null, strategy.name());
        List<String> ownerColumns = addFkColumns(table, owner.table, owner.pkColumns, null, null);
        if (ownerColumns.isEmpty()) {
            schema.removeTable(table.getName());
            note("multi-valued attribute " + entity.getName() + "." + attribute.getName()
                    + " cannot be mapped: the owner key is not available");
            trace.recordDroppedAttribute(entity.getName(), attribute.getName(),
                    "the multi-valued attribute needs its own table keyed by the owner's key, and the owner's key"
                            + " columns could not be copied");
            return;
        }
        setNotNull(ownerColumns, true, table);
        ForeignKey fk = new ForeignKey(new ArrayList<>(ownerColumns), owner.table.getName(),
                new ArrayList<>(owner.pkColumns));
        if (options.isUseNamedConstraints()) {
            fk.setName(constraintName("fk", table, table.getForeignKeys().size()));
        }
        fk.setDerivedFromRelationship(null);
        configureForeignKey(fk, table, ownerColumns, true, true);
        table.addForeignKey(fk);

        List<String> valueColumns = new ArrayList<>();
        if (attribute.isComposite() && !attribute.getComponents().isEmpty()) {
            for (ERAttribute component : attribute.getComponents()) {
                valueColumns.addAll(addCompositeComponent(table, component,
                        attribute.isOptional() || component.isOptional(), false,
                        entity.getName() + "." + attribute.getName() + "." + component.getName(), 0));
            }
            note("multi-valued composite attribute " + entity.getName() + "." + attribute.getName()
                    + " is flattened into the columns " + valueColumns);
        } else {
            String name = uniqueColumnName(table, "value");
            DataType type = attribute.getType() != null ? attribute.getType() : DataType.varchar(100);
            table.addColumn(new Column(name, type).setNullable(attribute.isOptional())
                    .setDerivedFrom(attribute.getName()));
            valueColumns.add(name);
        }
        if (strategy == MultivaluedStrategy.SEPARATE_TABLE) {
            List<String> pk = new ArrayList<>(ownerColumns);
            pk.addAll(valueColumns);
            setPrimaryKey(table, pk);
        } else {
            String surrogate = addSurrogate(table, true);
            setPrimaryKey(table, List.of(surrogate));
            List<String> uniqueColumns = new ArrayList<>(ownerColumns);
            uniqueColumns.addAll(valueColumns);
            addUnique(table, uniqueColumns);
        }
        trace.recordAttribute(entity.getName(), attribute.getName(), valueColumns.get(0));
        attributeColumns.put(entity.getName() + "." + attribute.getName(), valueColumns);
        note("multi-valued attribute " + entity.getName() + "." + attribute.getName() + " mapped to table "
                + table.getName() + " (" + strategy + ") with owner columns " + ownerColumns);
    }

    /* ------------------------------------------------------------------ constraints */

    /**
     * Emits the {@code UNIQUE} constraint of every alternate (candidate) key the ER diagram declares.
     *
     * <p>A strong entity often has more than one key: the filling marks one of them as the primary
     * key and, with probability 0.25, one or two further mandatory attributes as an alternate key -
     * "the entity is identified by {@code code} and unique on {@code name}". Mapping the alternate
     * key to a primary key would be wrong (a composite primary key is a strictly stronger claim), so
     * it becomes a unique constraint next to the primary key, which is the classic mapping and a
     * useful oracle input in its own right.</p>
     *
     * <p>This runs after every entity has a table and before the random check/unique/index pass, so
     * the random pass sees the alternate key as a key and does not put a second unique constraint on
     * the same column.</p>
     */
    private void emitAlternateKeys() {
        Set<String> emitted = new LinkedHashSet<>();
        for (EREntity entity : diagram.getEntities()) {
            List<ERAttribute> alternate = entity.getAlternateKeyAttributes();
            if (alternate.isEmpty()) {
                continue;
            }
            EntityInfo info = entityInfos.get(entity.getName());
            if (info == null || info.table == null) {
                note("the alternate key of " + entity.getName() + " is dropped: the entity has no table");
                continue;
            }
            List<String> columns = columnsOf(entity.getName(), alternate, info.table);
            if (columns.isEmpty()) {
                note("the alternate key of " + entity.getName() + " is dropped: none of its attributes "
                        + alternate + " reached the table " + info.table.getName());
                continue;
            }
            if (info.table.getPrimaryKey() != null
                    && sameColumns(info.table.getPrimaryKey().getColumns(), columns)) {
                note("the alternate key of " + entity.getName() + " is the primary key of "
                        + info.table.getName() + "; no second constraint is added");
                continue;
            }
            String signature = info.table.getName() + "(" + String.join(",", columns) + ")";
            if (!emitted.add(signature)) {
                note("the alternate key of " + entity.getName() + " repeats a unique constraint of "
                        + info.table.getName() + "; it is not added twice");
                continue;
            }
            addUnique(info.table, columns);
            note("alternate key of " + entity.getName() + " -> UNIQUE(" + columns + ") on "
                    + info.table.getName());
        }
    }

    private void generateChecksUniquesAndIndexes() {
        for (Table table : new ArrayList<>(schema.getTables())) {
            if (options.isGenerateCheckConstraints()) {
                for (Column column : new ArrayList<>(table.getColumns())) {
                    if (!column.getType().isNumeric() || column.isIdentity() || column.isGenerated()) {
                        continue;
                    }
                    if (!Randomly.getBoolean(0.3)) {
                        continue;
                    }
                    long bound = Randomly.getLong(-100, 100);
                    String expression = Randomly.getBoolean() ? column.getName() + " >= " + bound
                            : column.getName() + " <> " + bound;
                    table.addCheckConstraint(new CheckConstraint(
                            options.isUseNamedConstraints()
                                    ? constraintName("ck", table, table.getCheckConstraints().size())
                                    : null,
                            expression));
                }
            }
            if (options.isGenerateUniqueConstraints() && Randomly.getBoolean(0.2)) {
                List<Column> candidates = new ArrayList<>();
                for (Column column : table.getColumns()) {
                    if (isKeyColumn(table, column.getName())) {
                        continue;
                    }
                    candidates.add(column);
                }
                if (!candidates.isEmpty()) {
                    Column chosen = Randomly.fromList(candidates);
                    addUnique(table, List.of(chosen.getName()));
                }
            }
            if (options.isGenerateIndexes()) {
                int count = Randomly.getInteger(0, 4);
                for (int i = 0; i < count; i++) {
                    List<Column> columns = new ArrayList<>(table.getColumns());
                    if (columns.isEmpty()) {
                        break;
                    }
                    List<Column> chosen;
                    if (columns.size() >= 2 && Randomly.getBoolean(0.15)) {
                        int size = Randomly.getInteger(2, Math.min(3, columns.size()) + 1);
                        chosen = Randomly.nonEmptySubset(columns, size);
                    } else {
                        chosen = Randomly.nonEmptySubset(columns);
                    }
                    boolean unique = Randomly.getBoolean(0.3);
                    List<String> names = new ArrayList<>();
                    for (Column column : chosen) {
                        names.add(column.getName());
                    }
                    if (hasIndex(table, names, unique)) {
                        continue;
                    }
                    String name = (unique ? "uidx_" : "idx_") + table.getName() + "_" + table.getIndexes().size();
                    table.addIndex(new Index(truncate(name, MAX_NAME_LENGTH), names).setUnique(unique));
                }
            }
        }
    }

    private boolean hasIndex(Table table, List<String> columns, boolean unique) {
        for (Index index : table.getIndexes()) {
            if (index.isUnique() == unique && !index.isExpressionIndex()
                    && sameColumns(index.getColumns(), columns)) {
                return true;
            }
        }
        return false;
    }

    private boolean isKeyColumn(Table table, String column) {
        if (table.getPrimaryKey() != null && table.getPrimaryKey().getColumns().contains(column)) {
            return true;
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (unique.getColumns().contains(column)) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ views and sequences */

    private void generateViews() {
        if (!options.isGenerateViews()) {
            return;
        }
        int created = 0;
        for (Table table : new ArrayList<>(schema.getTables())) {
            if (created >= 2) {
                break;
            }
            ForeignKey chosen = null;
            Table referenced = null;
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                Table candidate = schema.getTable(foreignKey.getReferencedTable());
                if (candidate != null && !foreignKey.getColumns().isEmpty()
                        && foreignKey.getColumns().size() == foreignKey.getReferencedColumns().size()) {
                    chosen = foreignKey;
                    referenced = candidate;
                    break;
                }
            }
            if (chosen == null) {
                continue;
            }
            StringBuilder select = new StringBuilder();
            StringBuilder condition = new StringBuilder();
            List<String> columns = new ArrayList<>();
            for (int i = 0; i < chosen.getColumns().size(); i++) {
                String left = chosen.getColumns().get(i);
                String right = chosen.getReferencedColumns().get(i);
                if (i > 0) {
                    select.append(", ");
                    condition.append(" AND ");
                }
                select.append("a.").append(left).append(", b.").append(right);
                condition.append("a.").append(left).append(" = b.").append(right);
                columns.add(left);
                columns.add(right);
            }
            String query = "SELECT " + select + " FROM " + table.getName() + " a JOIN " + referenced.getName()
                    + " b ON " + condition;
            View view = new View("v_" + created, query).setSchemaName(schemaName).setCheckOption(null);
            view.getColumnNames().addAll(columns);
            schema.addView(view);
            note("view v_" + created + " joins " + table.getName() + " and " + referenced.getName() + " on "
                    + chosen.getColumns());
            created++;
        }
    }

    private void generateSequences() {
        if (options.isUseIdentityColumns() || schema.getTables().size() < 3) {
            return;
        }
        Sequence sequence = new Sequence("seq_0").setSchemaName(schemaName).setStart(1).setIncrement(1);
        schema.addSequence(sequence);
        note("created sequence seq_0 because identity columns are disabled and the schema has "
                + schema.getTables().size() + " tables");
    }

    /* ------------------------------------------------------------------ attribute mapping */

    private void addEntityAttributes(Table table, EREntity entity, boolean forceNullable, Set<String> added) {
        addEntityAttributes(table, entity, forceNullable, false, added);
    }

    /**
     * Adds the columns of every attribute of {@code entity}.
     *
     * @param keysAreKeyContext true when the attributes flagged as primary keys really are the
     *            primary key of {@code table}: they are then materialized even when the chosen
     *            derived-attribute strategy would omit them
     */
    private void addEntityAttributes(Table table, EREntity entity, boolean forceNullable, boolean keysAreKeyContext,
            Set<String> added) {
        for (ERAttribute attribute : entity.getAttributes()) {
            boolean keyContext = attribute.isPartialKey() || (keysAreKeyContext && attribute.isPrimaryKey());
            List<String> columns = addAttribute(table, entity.getName(), attribute, keyContext, forceNullable, added);
            if (!columns.isEmpty()) {
                trace.recordAttribute(entity.getName(), attribute.getName(), columns.get(0));
                if (columns.size() > 1) {
                    note("composite attribute " + entity.getName() + "." + attribute.getName() + " maps to columns "
                            + columns);
                }
            }
        }
    }

    private List<String> addAttribute(Table table, String owner, ERAttribute attribute, boolean keyContext,
            boolean forceNullable, Set<String> added) {
        List<String> result = new ArrayList<>();
        if (attribute == null) {
            return result;
        }
        String key = owner + "." + attribute.getName();
        if (!added.add(key)) {
            return result;
        }
        if (attribute.isMultivalued() && !keyContext) {
            if (diagram.getRelationship(owner) != null) {
                note("multi-valued attribute " + key + " of a relationship is not stored; it would need a table"
                        + " keyed by the participants of that relationship, which is not generated");
                trace.recordDroppedAttribute(key, "a multi-valued attribute of a relationship would need its own"
                        + " table keyed by the participants of that relationship, and the mapper does not build one");
            }
            return result;
        }
        if (attribute.isMultivalued()) {
            note("multi-valued attribute " + key + " is part of a key; it is stored as a plain column of "
                    + table.getName());
        }
        if (attribute.isDerived() && options.getDerived() == DerivedStrategy.OMIT && !keyContext) {
            note("derived attribute " + key + " is omitted");
            trace.recordDroppedAttribute(key, "derived attributes are mapped with the OMIT strategy in this run");
            return result;
        }
        if (attribute.isDerived() && keyContext && options.getDerived() == DerivedStrategy.OMIT) {
            note("derived key attribute " + key + " must be stored; it is materialized as a plain column");
        }
        boolean nullable = forceNullable || attribute.isOptional();
        if (attribute.isComposite() && !attribute.getComponents().isEmpty()) {
            note("composite attribute " + key + " is flattened into " + attribute.getComponents().size() + " columns");
            for (ERAttribute component : attribute.getComponents()) {
                if (component.isDerived() && options.getDerived() == DerivedStrategy.OMIT && !keyContext) {
                    note("derived component " + key + "." + component.getName() + " is omitted");
                    trace.recordDroppedAttribute(key + "." + component.getName(),
                            "derived attributes are mapped with the OMIT strategy in this run");
                    continue;
                }
                result.addAll(addCompositeComponent(table, component, nullable || component.isOptional(), keyContext,
                        key + "." + component.getName(), 0));
            }
            if (result.isEmpty()) {
                // a composite whose components are all omitted produces no column at all
                trace.recordDroppedAttribute(key, "every component of the composite attribute is omitted, so the"
                        + " composite itself produces no column");
            }
        } else {
            result.add(addPlainColumn(table, attribute, nullable, attribute.isDerived(), keyContext, key));
        }
        if (!result.isEmpty()) {
            attributeColumns.put(key, result);
        }
        return result;
    }

    /**
     * Adds the columns of one component of a composite attribute. A component that is composite
     * itself - the multi-level case - is flattened recursively, so an arbitrarily nested composite
     * attribute ends up as one column per leaf.
     *
     * @param depth guards against a cyclic component structure
     */
    private List<String> addCompositeComponent(Table table, ERAttribute component, boolean nullable,
            boolean keyContext, String traceKey, int depth) {
        List<String> result = new ArrayList<>();
        if (component.isComposite() && !component.getComponents().isEmpty() && depth < 8) {
            note("nested composite attribute " + traceKey + " is flattened recursively into "
                    + component.getComponents().size() + " columns");
            for (ERAttribute inner : component.getComponents()) {
                if (inner.isDerived() && options.getDerived() == DerivedStrategy.OMIT && !keyContext) {
                    note("derived component " + traceKey + "." + inner.getName() + " is omitted");
                    trace.recordDroppedAttribute(traceKey + "." + inner.getName(),
                            "derived attributes are mapped with the OMIT strategy in this run");
                    continue;
                }
                result.addAll(addCompositeComponent(table, inner, nullable || inner.isOptional(), keyContext,
                        traceKey + "." + inner.getName(), depth + 1));
            }
            return result;
        }
        if (component.isMultivalued()) {
            note("multi-valued component " + traceKey + " of a composite attribute is stored as a plain column of "
                    + table.getName() + "; only a multi-valued attribute of a whole entity gets its own table");
        }
        result.add(addPlainColumn(table, component, nullable, component.isDerived(), keyContext, traceKey));
        return result;
    }

    private String addPlainColumn(Table table, ERAttribute attribute, boolean nullable, boolean derived,
            boolean keyContext, String traceKey) {
        String name = uniqueColumnName(table, sanitize(attribute.getName()));
        DataType type = attribute.getType() != null ? attribute.getType() : DataType.varchar(100);
        Column column = new Column(name, type).setNullable(nullable).setDerivedFrom(attribute.getName());
        if (derived && !keyContext) {
            if (options.getDerived() == DerivedStrategy.GENERATED_COLUMN) {
                if (attribute.getDerivedExpression() != null) {
                    column.setGeneratedExpression(attribute.getDerivedExpression()).setGeneratedStored(true);
                } else {
                    note("derived attribute " + traceKey
                            + " has no expression; it is stored as a plain column instead of a generated column");
                }
            }
        } else if (derived) {
            note("derived key attribute " + traceKey + " is materialized as a plain column");
        }
        table.addColumn(column);
        return name;
    }

    private void addRelationshipAttributes(Table table, ERRelationship relationship) {
        Set<String> added = new HashSet<>();
        for (ERAttribute attribute : relationship.getAttributes()) {
            List<String> columns = addAttribute(table, relationship.getName(), attribute, false, false, added);
            if (!columns.isEmpty()) {
                trace.recordRelationshipAttribute(relationship.getName(), attribute.getName(), columns.get(0));
                attributeColumns.put(relationship.getName() + "." + attribute.getName(), columns);
            }
        }
    }

    /* ------------------------------------------------------------------ dropped attributes */

    /**
     * Announces every attribute of {@code relationship} as deliberately not stored, with the reason.
     *
     * <p>The mapping self-check requires every ER attribute to be either stored or explained, so
     * every path that leaves a relationship's attributes without a table must call this. The reason
     * is what turns a silent loss into a recorded decision.</p>
     */
    private void dropRelationshipAttributes(ERRelationship relationship, String reason) {
        for (ERAttribute attribute : relationship.getAttributes()) {
            trace.recordDroppedAttribute(relationship.getName(), attribute.getName(), reason);
        }
    }

    /** Announces every attribute of {@code entity} as deliberately not stored, with the reason. */
    private void dropEntityAttributes(EREntity entity, String reason) {
        for (ERAttribute attribute : entity.getAttributes()) {
            trace.recordDroppedAttribute(entity.getName(), attribute.getName(), reason);
        }
    }

    /* ------------------------------------------------------------------ columns, keys, foreign keys */

    private String addSurrogate(Table table, boolean first) {
        String name = uniqueColumnName(table, "id");
        Column column = new Column(name, DataType.bigint()).setNullable(false)
                .setIdentity(options.isUseIdentityColumns());
        if (first) {
            table.getColumns().add(0, column);
        } else {
            table.addColumn(column);
        }
        if (!"id".equals(name)) {
            note("the surrogate key column of " + table.getName() + " was renamed to " + name
                    + " because an attribute already uses the name id");
        }
        return name;
    }

    private List<String> addFkColumns(Table target, Table referenced, List<String> referencedKeyColumns,
            String rolePrefix, String derivedFrom) {
        List<String> result = new ArrayList<>();
        for (String referencedColumn : referencedKeyColumns) {
            Column column = referenced.getColumn(referencedColumn);
            if (column == null) {
                continue;
            }
            String base = rolePrefix != null && !rolePrefix.isEmpty()
                    ? sanitize(rolePrefix) + "_" + referencedColumn
                    : referenced.getName() + "_" + referencedColumn;
            String name = uniqueColumnName(target, base);
            target.addColumn(new Column(name, column.getType()).setNullable(true).setDerivedFrom(derivedFrom));
            result.add(name);
        }
        return result;
    }

    private ForeignKey addForeignKey(Table table, List<String> columns, Table referenced,
            List<String> referencedColumns, ERRelationship relationship, boolean notNull, boolean mandatoryCascade) {
        ForeignKey foreignKey = new ForeignKey(new ArrayList<>(columns), referenced.getName(),
                new ArrayList<>(referencedColumns));
        foreignKey.setDerivedFromRelationship(relationship != null ? relationship.getName() : null);
        if (options.isUseNamedConstraints()) {
            foreignKey.setName(constraintName("fk", table, table.getForeignKeys().size()));
        }
        configureForeignKey(foreignKey, table, columns, notNull, mandatoryCascade);
        if (relationship != null && relationship.isRecursive()) {
            // the ER relationship is self-referencing, even when the foreign key lives in a join table
            foreignKey.setSelfReferential(true);
        }
        table.addForeignKey(foreignKey);
        return foreignKey;
    }

    private void configureForeignKey(ForeignKey foreignKey, Table table, List<String> columns, boolean notNull,
            boolean mandatoryCascade) {
        foreignKey.setDeferrable(options.isDeferrableForeignKeys());
        foreignKey.setInitiallyDeferred(false);
        foreignKey.setSelfReferential(foreignKey.getReferencedTable().equals(table.getName()));
        boolean none = options.getReferentialActionStrategy() == ReferentialActionStrategy.NONE;
        if (mandatoryCascade) {
            foreignKey.setOnDelete(ReferentialAction.CASCADE);
            if (none) {
                note("referential actions are NONE but " + table.getName()
                        + " depends on the referenced row; ON DELETE CASCADE is kept");
            }
        } else {
            foreignKey.setOnDelete(none ? ReferentialAction.NO_ACTION : randomReferentialAction(table, columns));
        }
        foreignKey.setOnUpdate(none ? ReferentialAction.NO_ACTION : randomReferentialAction(table, columns));
        if (notNull) {
            setNotNull(columns, true, table);
        }
    }

    private ReferentialAction randomReferentialAction(Table table, List<String> columns) {
        List<ReferentialAction> candidates = new ArrayList<>(List.of(ReferentialAction.NO_ACTION,
                ReferentialAction.CASCADE, ReferentialAction.RESTRICT, ReferentialAction.SET_NULL));
        boolean allNotNull = !columns.isEmpty();
        boolean allHaveDefault = !columns.isEmpty();
        for (String name : columns) {
            Column column = table.getColumn(name);
            if (column == null) {
                continue;
            }
            if (column.isNullable()) {
                allNotNull = false;
            }
            if (!column.hasDefault()) {
                allHaveDefault = false;
            }
        }
        if (allNotNull) {
            candidates.remove(ReferentialAction.SET_NULL);
        }
        if (allHaveDefault) {
            candidates.add(ReferentialAction.SET_DEFAULT);
        }
        return Randomly.fromList(candidates);
    }

    private void setPrimaryKey(Table table, List<String> columns) {
        List<String> pk = new ArrayList<>();
        for (String column : columns) {
            if (column != null && table.hasColumn(column) && !pk.contains(column)) {
                pk.add(column);
            }
        }
        if (pk.isEmpty()) {
            return;
        }
        table.setPrimaryKey(new PrimaryKey(
                options.isUseNamedConstraints() ? constraintName("pk", table, 0) : null, pk));
        for (String column : pk) {
            Column keyColumn = table.getColumn(column);
            if (keyColumn != null) {
                keyColumn.setNullable(false);
            }
        }
    }

    private void addUnique(Table table, List<String> columns) {
        List<String> unique = new ArrayList<>();
        for (String column : columns) {
            if (column != null && table.hasColumn(column) && !unique.contains(column)) {
                unique.add(column);
            }
        }
        if (unique.isEmpty()) {
            return;
        }
        for (UniqueConstraint existing : table.getUniqueConstraints()) {
            if (sameColumns(existing.getColumns(), unique)) {
                return;
            }
        }
        table.addUniqueConstraint(new UniqueConstraint(
                options.isUseNamedConstraints() ? constraintName("uq", table, table.getUniqueConstraints().size())
                        : null,
                unique));
    }

    private void setNotNull(List<String> columns, boolean notNull, Table table) {
        if (!notNull) {
            return;
        }
        for (String name : columns) {
            Column column = table.getColumn(name);
            if (column != null) {
                column.setNullable(false);
            }
        }
    }

    private List<String> applyKeyStrategy(Table table, EREntity keyOwner) {
        KeyStrategy keyStrategy = options.getKeyStrategy();
        List<ERAttribute> naturalKey = new ArrayList<>(keyOwner.getPrimaryKeyAttributes());
        boolean surrogate = keyStrategy != KeyStrategy.NATURAL_KEY || naturalKey.isEmpty();
        List<String> pk = new ArrayList<>();
        if (surrogate) {
            if (naturalKey.isEmpty() && keyStrategy == KeyStrategy.NATURAL_KEY) {
                note("entity " + keyOwner.getName() + " has no key attribute; a surrogate key is used instead");
            }
            pk.add(addSurrogate(table, false));
            if (keyStrategy == KeyStrategy.SURROGATE_KEY_WITH_NATURAL_UNIQUE && !naturalKey.isEmpty()) {
                List<String> naturalColumns = columnsOf(keyOwner.getName(), naturalKey, table);
                if (!naturalColumns.isEmpty()) {
                    addUnique(table, naturalColumns);
                }
            }
        } else {
            pk = columnsOf(keyOwner.getName(), naturalKey, table);
            if (pk.isEmpty()) {
                pk = new ArrayList<>(List.of(addSurrogate(table, false)));
                note("entity " + keyOwner.getName() + " has no mappable key attribute; a surrogate key is used instead");
            }
        }
        setPrimaryKey(table, pk);
        return pk;
    }

    private List<String> columnsOf(String entityName, List<ERAttribute> attributes, Table table) {
        List<String> result = new ArrayList<>();
        for (ERAttribute attribute : attributes) {
            List<String> columns = attributeColumns.get(entityName + "." + attribute.getName());
            if (columns == null) {
                continue;
            }
            for (String column : columns) {
                if (table.hasColumn(column) && !result.contains(column)) {
                    result.add(column);
                }
            }
        }
        return result;
    }

    /* ------------------------------------------------------------------ helpers */

    private EntityInfo infoFor(String entityName) {
        EntityInfo info = entityInfos.get(entityName);
        if (info != null) {
            return info;
        }
        EREntity entity = diagram.getEntity(entityName);
        if (entity == null) {
            return null;
        }
        info = mapEntityByName(entityName);
        if (info != null) {
            note("table for entity " + entityName + " was created on demand while mapping a relationship");
        }
        return info;
    }

    private Table createTable(String name, String entityName, String relationshipName, String strategy) {
        Table table = new Table(schemaName, name);
        table.setSchemaName(schemaName);
        table.setDerivedFromEntity(entityName);
        table.setDerivedFromRelationship(relationshipName);
        table.setMappingStrategy(strategy);
        schema.addTable(table);
        return table;
    }

    private String entityTableName(EREntity entity) {
        String name = entity.getName() == null ? "" : entity.getName();
        Matcher matcher = ENTITY_NAME_PATTERN.matcher(name);
        String base = matcher.matches() ? "t_" + matcher.group(1) : "t_" + sanitize(name);
        return truncate(base, MAX_NAME_LENGTH);
    }

    private String relationshipTableName(ERRelationship relationship) {
        String name = relationship.getName() == null ? "" : relationship.getName();
        Matcher matcher = RELATIONSHIP_NAME_PATTERN.matcher(name);
        String base = matcher.matches() ? "t_" + matcher.group(1) : "t_" + sanitize(name);
        return truncate(base, MAX_NAME_LENGTH);
    }

    private String uniqueTableName(String base, boolean joinTable) {
        String name = truncate(base, MAX_NAME_LENGTH);
        if (!schema.hasTable(name)) {
            return name;
        }
        if (joinTable) {
            String candidate = truncate(base, MAX_NAME_LENGTH - 2) + "_r";
            int i = 2;
            while (schema.hasTable(candidate)) {
                String suffix = "_r" + i;
                candidate = truncate(base, MAX_NAME_LENGTH - suffix.length()) + suffix;
                i++;
            }
            return candidate;
        }
        int i = 2;
        String candidate = truncate(base, MAX_NAME_LENGTH - 2) + "_" + i;
        while (schema.hasTable(candidate)) {
            i++;
            String suffix = "_" + i;
            candidate = truncate(base, MAX_NAME_LENGTH - suffix.length()) + suffix;
        }
        return candidate;
    }

    private String uniqueColumnName(Table table, String base) {
        String name = truncate(base, MAX_NAME_LENGTH);
        if (!table.hasColumn(name)) {
            return name;
        }
        int i = 2;
        while (true) {
            String suffix = "_" + i;
            String candidate = truncate(base, MAX_NAME_LENGTH - suffix.length()) + suffix;
            if (!table.hasColumn(candidate)) {
                return candidate;
            }
            i++;
        }
    }

    private String constraintName(String prefix, Table table, int index) {
        return truncate(prefix + "_" + table.getName() + "_" + index, MAX_NAME_LENGTH);
    }

    private boolean hasAttributeNamed(EREntity entity, String columnBase) {
        for (ERAttribute attribute : entity.getAttributes()) {
            if (sanitize(attribute.getName()).equals(columnBase)) {
                return true;
            }
            for (ERAttribute component : attribute.getComponents()) {
                if (sanitize(component.getName()).equals(columnBase)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameColumns(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        List<String> copy = new ArrayList<>(right);
        for (String column : left) {
            if (!copy.remove(column)) {
                return false;
            }
        }
        return copy.isEmpty();
    }

    private static String quotedList(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append('\'').append(values.get(i).replace("'", "''")).append('\'');
        }
        return builder.toString();
    }

    private static String entityNames(List<EREntity> entities) {
        List<String> names = new ArrayList<>();
        for (EREntity entity : entities) {
            names.add(entity.getName());
        }
        return names.toString();
    }

    /** Sanitizes an identifier fragment to {@code [a-z0-9_]}. */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "x";
        }
        String sanitized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        if (sanitized.isEmpty()) {
            sanitized = "x";
        }
        if (!Character.isLetter(sanitized.charAt(0))) {
            sanitized = "n" + sanitized;
        }
        return sanitized;
    }

    /** Truncates an identifier deterministically, keeping it unique through a hash suffix. */
    public static String truncate(String name, int maxLength) {
        if (name == null || name.length() <= maxLength) {
            return name;
        }
        String hash = Integer.toHexString(name.hashCode());
        String suffix = "_" + hash;
        int keep = Math.max(1, maxLength - suffix.length());
        return name.substring(0, keep) + suffix;
    }

    private void note(String message) {
        trace.note(message);
    }
}
