package org.ddlsmith.mapping.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
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
 * Self-check of the ER-to-relational mapping.
 *
 * <p>Builds ER diagrams by hand for every construct of the ER model, maps them under every relevant
 * combination of {@link MappingOptions} (plus randomly drawn option sets) and validates the
 * structural invariants of the produced schema. Exits with status 1 and a clear message when an
 * invariant is violated.</p>
 *
 * <pre>
 * java -cp "target\map-classes;target\contract-classes;&lt;deps&gt;" org.ddlsmith.mapping.impl.SelfCheck
 * </pre>
 */
public final class SelfCheck {

    private static final OneToOneStrategy[] ONE_TO_ONE = OneToOneStrategy.values();
    private static final OneToManyStrategy[] ONE_TO_MANY = OneToManyStrategy.values();
    private static final ManyToManyStrategy[] MANY_TO_MANY = ManyToManyStrategy.values();
    private static final NaryStrategy[] NARY = NaryStrategy.values();
    private static final WeakEntityStrategy[] WEAK = WeakEntityStrategy.values();
    private static final IsAStrategy[] ISA = IsAStrategy.values();
    private static final MultivaluedStrategy[] MULTIVALUED = MultivaluedStrategy.values();
    private static final DerivedStrategy[] DERIVED = DerivedStrategy.values();
    private static final KeyStrategy[] KEYS = KeyStrategy.values();
    private static final ReferentialActionStrategy[] REF_ACTIONS = ReferentialActionStrategy.values();

    private static final int RANDOM_OPTION_INSTANCES = 300;

    private SelfCheck() {
    }

    public static void main(String[] args) {
        Result result = run();
        System.out.println();
        System.out.println(result.summary());
        if (result.failureCount > 0) {
            System.out.println();
            System.out.println("SELF-CHECK FAILED: " + result.failureCount + " problem(s)");
            for (String failure : result.failures) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
        System.out.println();
        System.out.println("SELF-CHECK PASSED: every invariant holds for every mapped schema");
    }

    /** Runs every self-check; the returned result is empty when all invariants hold. */
    public static Result run() {
        Checker checker = new Checker();
        long started = System.currentTimeMillis();
        try {
            featureCases(checker);
            strategySweep(checker);
            booleanFlagSweep(checker);
            randomOptionSweep(checker);
            determinismCheck(checker);
        } catch (Throwable t) {
            checker.fail("the self-check aborted: " + t);
            t.printStackTrace(System.out);
        }
        return new Result(checker, System.currentTimeMillis() - started);
    }

    /** The outcome of {@link #run()}: the failures plus the coverage counters. */
    public static final class Result {
        public final List<String> failures;
        public final int failureCount;
        public final int mappings;
        public final int combinations;
        public final int randomInstances;
        public final int tables;
        public final int foreignKeys;
        public final int selfReferential;
        public final int joinTables;
        public final int multivaluedTables;
        public final int views;
        public final int sequences;
        public final int assertions;
        /** ER attributes that were deliberately not stored, each carrying a recorded reason. */
        public final int droppedAttributes;
        public final long elapsedMillis;

        Result(Checker checker, long elapsedMillis) {
            this.failures = new ArrayList<>(checker.messages);
            this.failureCount = checker.failureCount;
            this.mappings = checker.mappings;
            this.combinations = checker.combinations;
            this.randomInstances = checker.randomInstances;
            this.tables = checker.tables;
            this.foreignKeys = checker.foreignKeys;
            this.selfReferential = checker.selfReferential;
            this.joinTables = checker.joinTables;
            this.multivaluedTables = checker.multivaluedTables;
            this.views = checker.views;
            this.sequences = checker.sequences;
            this.assertions = checker.checks;
            this.droppedAttributes = checker.droppedAttributes;
            this.elapsedMillis = elapsedMillis;
        }

        public String summary() {
            StringBuilder builder = new StringBuilder();
            builder.append("=== DDLsmith ER-to-relational mapping self-check ===\n");
            builder.append("  mappings executed          : ").append(mappings).append('\n');
            builder.append("  strategy combinations      : ").append(combinations).append('\n');
            builder.append("  random option instances    : ").append(randomInstances).append('\n');
            builder.append("  tables produced            : ").append(tables).append('\n');
            builder.append("  foreign keys produced      : ").append(foreignKeys).append(" (self-referential ")
                    .append(selfReferential).append(")\n");
            builder.append("  join tables / mv tables    : ").append(joinTables).append(" / ")
                    .append(multivaluedTables).append('\n');
            builder.append("  views / sequences produced : ").append(views).append(" / ").append(sequences)
                    .append('\n');
            builder.append("  dropped ER attributes      : ").append(droppedAttributes)
                    .append(" (each announced in the trace with a reason; none lost silently)\n");
            builder.append("  invariant violations       : ").append(failureCount).append(" (").append(assertions)
                    .append(" assertions, ").append(mappings).append(" schemas validated)\n");
            builder.append("  elapsed                    : ").append(elapsedMillis).append(" ms");
            return builder.toString();
        }
    }

    /* ================================================================== cases */

    private static void featureCases(Checker checker) {
        strongEntityCases(checker);
        weakEntityCases(checker);
        oneToOneCases(checker);
        oneToManyCases(checker);
        manyToManyCases(checker);
        recursiveCases(checker);
        naryCases(checker);
        isaCases(checker);
        multivaluedCases(checker);
        enhancedFeatureCases(checker);
        viewAndSequenceCases(checker);
        collisionCases(checker);
        robustnessCases(checker);
    }

    /* ---------------------------------------------------------------- enhanced ER features */

    private static void enhancedFeatureCases(Checker checker) {
        sharedSubclassCases(checker);
        categoryCases(checker);
        aggregationCases(checker);
        partialSpecializationCases(checker);
        compositeStructureCases(checker);
    }

    /** A shared subclass (multiple inheritance) under every ISA strategy. */
    private static void sharedSubclassCases(Checker checker) {
        for (IsAStrategy strategy : ISA) {
            Mapped mapped = runOne(checker, "sharedSubclass/" + strategy, sharedSubclassDiagram(),
                    base().setIsa(strategy).setKeyStrategy(KeyStrategy.SURROGATE_KEY), 200L);
            RelationalSchema schema = mapped.schema;
            switch (strategy) {
            case SINGLE_TABLE: {
                Table table = schema.getTable("t_0");
                checker.check(table != null, "shared subclass single table: t_0 must exist");
                if (table == null) {
                    break;
                }
                for (String column : List.of("a0", "a1", "a2", "a3", "a4")) {
                    checker.check(table.getColumn(column) != null,
                            "shared subclass single table: the lattice must contribute the column " + column);
                }
                checker.check(table.getDiscriminatorValues().size() == 3,
                        "shared subclass single table: expected three discriminator values (E1, E2, E3) but was "
                                + table.getDiscriminatorValues().keySet());
                for (String entity : List.of("E1", "E2", "E3")) {
                    checker.check("t_0".equals(mapped.trace.getEntityToTable().get(entity)),
                            "shared subclass single table: " + entity + " must be stored in t_0");
                }
                break;
            }
            case TABLE_PER_CLASS: {
                Table shared = schema.getTable("t_3");
                checker.check(shared != null, "shared subclass table per class: t_3 must exist");
                if (shared == null) {
                    break;
                }
                for (String column : List.of("a0", "a1", "a2", "a3", "a4")) {
                    checker.check(shared.getColumn(column) != null,
                            "shared subclass table per class: the shared subclass must inherit " + column
                                    + " through both chains");
                }
                break;
            }
            default: {
                Table shared = schema.getTable("t_3");
                checker.check(shared != null, "shared subclass: t_3 must exist");
                if (shared == null) {
                    break;
                }
                checker.check(shared.getForeignKeys().size() == 2,
                        "shared subclass: the shared subclass must reference both supertypes but had "
                                + shared.getForeignKeys().size() + " foreign key(s)");
                checker.check(shared.getPrimaryKey().getColumns().size() == 2,
                        "shared subclass: the key must combine the keys of both supertypes but was "
                                + shared.getPrimaryKey().getColumns());
                Set<String> referenced = new HashSet<>();
                for (ForeignKey fk : shared.getForeignKeys()) {
                    referenced.add(fk.getReferencedTable());
                }
                checker.check(referenced.equals(new HashSet<>(Arrays.asList("t_1", "t_2"))),
                        "shared subclass: the foreign keys must reference t_1 and t_2 but referenced " + referenced);
                break;
            }
            }
        }
    }

    /** A union type (category): surrogate key, one optional foreign key per superclass, union check. */
    private static void categoryCases(Checker checker) {
        for (KeyStrategy keys : KEYS) {
            Mapped mapped = runOne(checker, "category/" + keys, categoryDiagram(), base().setKeyStrategy(keys), 210L);
            Table category = mapped.schema.getTable("t_2");
            checker.check(category != null, "category: t_2 must exist");
            if (category == null) {
                continue;
            }
            int membershipKeys = 0;
            for (ForeignKey fk : category.getForeignKeys()) {
                if (fk.getReferencedTable().equals("t_0") || fk.getReferencedTable().equals("t_1")) {
                    membershipKeys++;
                }
            }
            checker.check(membershipKeys == 2,
                    "category: one membership foreign key per superclass is expected but found " + membershipKeys);
            checker.check(category.getPrimaryKey().getColumns().size() == 1,
                    "category: the key is the surrogate key but was " + category.getPrimaryKey().getColumns());
            boolean unionCheck = false;
            for (org.ddlsmith.common.schema.CheckConstraint check : category.getCheckConstraints()) {
                unionCheck |= check.getExpression() != null && check.getExpression().contains("IS NOT NULL")
                        && check.getExpression().contains(" OR ");
            }
            checker.check(unionCheck, "category: a union-membership check is expected");
            for (ForeignKey fk : category.getForeignKeys()) {
                if (!fk.getReferencedTable().equals("t_0") && !fk.getReferencedTable().equals("t_1")) {
                    continue;
                }
                for (String column : fk.getColumns()) {
                    Column fkColumn = category.getColumn(column);
                    checker.check(fkColumn != null && fkColumn.isNullable(),
                            "category: the membership column " + column + " must be nullable");
                }
            }
            checker.check(category.getColumn("a2") != null, "category: the own attribute must be stored");
            checker.check("CATEGORY".equals(mapped.trace.getEntityStrategy().get("E2")),
                    "category: the entity strategy must be recorded as CATEGORY");
            checker.check("CATEGORY_LINK".equals(mapped.trace.getRelationshipStrategy().get("R0")),
                    "category: the membership link must be recorded as a category link");
        }
    }

    /** An aggregation: the aggregation entity is stored by the join table of the inner relationship. */
    private static void aggregationCases(Checker checker) {
        for (ManyToManyStrategy strategy : MANY_TO_MANY) {
            Mapped mapped = runOne(checker, "aggregation/" + strategy, aggregationDiagram(),
                    base().setManyToMany(strategy), 220L);
            String innerName = mapped.trace.getRelationshipToTable().get("R0");
            Table inner = innerName == null ? null : mapped.schema.getTable(innerName);
            String outerName = mapped.trace.getRelationshipToTable().get("Ragg_R0");
            Table outer = outerName == null ? null : mapped.schema.getTable(outerName);
            checker.check(inner != null, "aggregation: the inner relationship must map to a join table");
            checker.check(outer != null, "aggregation: the outer relationship must map to a join table");
            checker.check(inner != null && inner.getName().equals(mapped.trace.getEntityToTable().get("Agg_R0")),
                    "aggregation: the aggregation entity must be stored by the inner join table");
            if (inner != null && outer != null) {
                boolean referencesInner = false;
                for (ForeignKey fk : outer.getForeignKeys()) {
                    referencesInner |= fk.getReferencedTable().equals(inner.getName());
                }
                checker.check(referencesInner,
                        "aggregation: the outer table must reference the aggregated join table");
            }
            checker.check("AGGREGATION_LINK".equals(mapped.trace.getRelationshipStrategy().get("R1")),
                    "aggregation: the absorbed link R1 must be recorded as an aggregation link");
            checker.check("AGGREGATION_LINK".equals(mapped.trace.getRelationshipStrategy().get("R2")),
                    "aggregation: the absorbed link R2 must be recorded as an aggregation link");
            checker.check(mapped.trace.getRelationshipToTable().get("R1") == null,
                    "aggregation: an absorbed link must not produce a table");
        }
    }

    /** A partial specialization: the single-table discriminator must allow the root value. */
    private static void partialSpecializationCases(Checker checker) {
        Mapped partial = runOne(checker, "partialSpecialization", partialSpecializationDiagram(),
                base().setIsa(IsAStrategy.SINGLE_TABLE), 230L);
        Table table = partial.schema.getTable("t_0");
        checker.check(table != null, "partial specialization: t_0 must exist");
        if (table == null) {
            return;
        }
        boolean allowsRoot = false;
        for (org.ddlsmith.common.schema.CheckConstraint check : table.getCheckConstraints()) {
            allowsRoot |= check.getExpression() != null && check.getExpression().contains("'E0'");
        }
        checker.check(allowsRoot,
                "partial specialization: the discriminator must allow the root value E0 but the check is "
                        + table.getCheckConstraints());
        // and a total specialization of the same shape must not allow it
        ERDiagram total = partialSpecializationDiagram();
        total.getEntity("E0").setTotalSpecialization(true);
        Mapped totalMapped = runOne(checker, "totalSpecialization", total, base().setIsa(IsAStrategy.SINGLE_TABLE), 231L);
        Table totalTable = totalMapped.schema.getTable("t_0");
        if (totalTable != null) {
            boolean totalAllowsRoot = false;
            for (org.ddlsmith.common.schema.CheckConstraint check : totalTable.getCheckConstraints()) {
                totalAllowsRoot |= check.getExpression() != null && check.getExpression().contains("'E0'");
            }
            checker.check(!totalAllowsRoot, "total specialization: the discriminator must not allow the root value E0");
        }
    }

    /** Nested composites, composite natural keys, composite partial keys and multi-valued composites. */
    private static void compositeStructureCases(Checker checker) {
        Mapped mapped = runOne(checker, "compositeStructure", compositeStructureDiagram(), base(), 240L);
        Table owner = mapped.schema.getTable("t_0");
        checker.check(owner != null, "compositeStructure: t_0 must exist");
        if (owner != null) {
            checker.check(owner.getPrimaryKey().getColumns().size() == 2,
                    "composite key: the key must be the two key attributes but was "
                            + owner.getPrimaryKey().getColumns());
            for (String column : List.of("a2", "a3", "a4")) {
                checker.check(owner.getColumn(column) != null,
                        "nested composite: the leaf " + column + " must become a column");
            }
        }
        Table weak = mapped.schema.getTable("t_1");
        checker.check(weak != null, "compositeStructure: t_1 must exist");
        if (weak != null) {
            checker.check(weak.getPrimaryKey().getColumns().size() == 4,
                    "composite partial key: the key is the owner key plus the two partial keys but was "
                            + weak.getPrimaryKey().getColumns());
        }
        Table values = mapped.schema.getTable("mv_e1_0");
        checker.check(values != null, "multi-valued composite: the value table must exist");
        if (values != null) {
            checker.check(values.getColumn("a8") != null && values.getColumn("a9") != null,
                    "multi-valued composite: the components must be flattened into the value table");
        }
    }

    /* ---------------------------------------------------------------- strong entity */

    private static void strongEntityCases(Checker checker) {
        ERDiagram diagram = strongDiagram();
        for (KeyStrategy keyStrategy : KEYS) {
            for (boolean identity : new boolean[] {false, true}) {
                MappingOptions options = base().setKeyStrategy(keyStrategy).setUseIdentityColumns(identity);
                Mapped mapped = runOne(checker, "strong/" + keyStrategy + "/identity=" + identity, diagram, options, 7L);
                Table table = mapped.schema.getTable("t_0");
                checker.check(table != null, "strong: table t_0 is missing");
                if (table == null) {
                    continue;
                }
                List<String> pk = table.getPrimaryKey().getColumns();
                switch (keyStrategy) {
                case NATURAL_KEY:
                    checker.check(pk.equals(List.of("a0")), "NATURAL_KEY: expected pk [a0] but was " + pk);
                    break;
                case SURROGATE_KEY:
                    checker.check(pk.equals(List.of("id")), "SURROGATE_KEY: expected pk [id] but was " + pk);
                    checker.check(table.getUniqueConstraints().isEmpty(),
                            "SURROGATE_KEY: unexpected unique constraints " + table.getUniqueConstraints());
                    break;
                default:
                    checker.check(pk.equals(List.of("id")),
                            "SURROGATE_KEY_WITH_NATURAL_UNIQUE: expected pk [id] but was " + pk);
                    checker.check(hasUnique(table, List.of("a0")),
                            "SURROGATE_KEY_WITH_NATURAL_UNIQUE: missing unique constraint on [a0]");
                    break;
                }
                Column surrogate = table.getColumn("id");
                if (surrogate != null) {
                    checker.check(surrogate.isIdentity() == identity,
                            "surrogate column identity flag should be " + identity);
                    checker.check(!surrogate.isNullable(), "surrogate column must be NOT NULL");
                }
                Column a1 = table.getColumn("a1");
                Column a2 = table.getColumn("a2");
                checker.check(a1 != null && !a1.isNullable(), "a1 must exist and be NOT NULL");
                checker.check(a2 != null && a2.isNullable(), "a2 must exist and be nullable");
                checker.check(table.getColumn("a4a") != null && table.getColumn("a4b") != null,
                        "the composite attribute must be flattened into a4a and a4b");
                Table mv = mapped.schema.getTable("mv_e0_0");
                checker.check(mv != null, "the multi-valued attribute must produce table mv_e0_0");
                if (mv != null) {
                    checker.check(mv.getColumn("value") != null, "mv_e0_0 must have a value column");
                    checker.check(mv.getForeignKeys().size() == 1, "mv_e0_0 must have exactly one foreign key");
                    for (ForeignKey foreignKey : mv.getForeignKeys()) {
                        checker.check(foreignKey.getOnDelete() == ReferentialAction.CASCADE,
                                "the multi-valued foreign key must use ON DELETE CASCADE");
                        checker.check(foreignKey.getReferencedTable().equals("t_0"),
                                "the multi-valued foreign key must reference t_0");
                        checker.check(SchemaInvariants.sameColumns(foreignKey.getReferencedColumns(),
                                table.getPrimaryKey().getColumns()),
                                "the multi-valued foreign key must reference the owner key");
                    }
                }
                // the derived attribute must never become a key or an identity column
                Column a3 = table.getColumn("a3");
                if (a3 != null) {
                    checker.check(!a3.isIdentity(), "a derived attribute must not be an identity column");
                }
            }
        }
        for (DerivedStrategy derived : DERIVED) {
            MappingOptions options = base().setDerived(derived).setKeyStrategy(KeyStrategy.SURROGATE_KEY);
            Mapped mapped = runOne(checker, "derived/" + derived, strongDiagram(), options, 11L);
            Table table = mapped.schema.getTable("t_0");
            if (table == null) {
                checker.fail("derived/" + derived + ": table t_0 is missing");
                continue;
            }
            Column a3 = table.getColumn("a3");
            switch (derived) {
            case OMIT:
                checker.check(a3 == null, "OMIT: derived attribute a3 must not be stored");
                break;
            case MATERIALIZE:
                checker.check(a3 != null && !a3.isGenerated(), "MATERIALIZE: a3 must be a plain column");
                break;
            default:
                checker.check(a3 != null, "GENERATED_COLUMN: a3 must exist");
                if (a3 != null) {
                    checker.check(a3.getGeneratedExpression() != null,
                            "GENERATED_COLUMN: a3 must have a generated expression");
                    checker.check(a3.isGeneratedStored(), "GENERATED_COLUMN: a3 must be stored");
                    checker.check(!a3.isIdentity(), "GENERATED_COLUMN: a3 must not be an identity column");
                }
                break;
            }
        }
        // a derived attribute that is part of the natural key must still be materialized
        ERDiagram derivedKey = new ERDiagram().setName("derivedKey");
        EREntity entity = new EREntity("E0");
        entity.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true).setDerived(true)
                .setDerivedExpression("1 + 1"));
        entity.addAttribute(new ERAttribute("a1", DataType.integer()));
        derivedKey.addEntity(entity);
        MappingOptions options = base().setDerived(DerivedStrategy.OMIT);
        Mapped mapped = runOne(checker, "derivedKey/OMIT", derivedKey, options, 13L);
        Table table = mapped.schema.getTable("t_0");
        checker.check(table != null && table.getColumn("a0") != null,
                "a derived key attribute must be materialized even when derived attributes are omitted");
    }

    /* ---------------------------------------------------------------- weak entity */

    private static void weakEntityCases(Checker checker) {
        for (WeakEntityStrategy strategy : WEAK) {
            for (KeyStrategy keyStrategy : KEYS) {
                MappingOptions options = base().setWeakEntity(strategy).setKeyStrategy(keyStrategy);
                Mapped mapped = runOne(checker, "weak/" + strategy + "/" + keyStrategy, weakDiagram(), options, 17L);
                Table weak = mapped.schema.getTable("t_1");
                Table owner = mapped.schema.getTable("t_0");
                checker.check(weak != null && owner != null, "weak: t_1 and t_0 must exist");
                if (weak == null || owner == null) {
                    continue;
                }
                checker.check(weak.getForeignKeys().size() == 1, "weak: t_1 must have exactly one foreign key");
                ForeignKey fk = weak.getForeignKeys().isEmpty() ? null : weak.getForeignKeys().get(0);
                if (fk != null) {
                    checker.check(fk.getReferencedTable().equals("t_0"),
                            "weak: the foreign key must reference t_0 but references " + fk.getReferencedTable());
                    checker.check(SchemaInvariants.sameColumns(fk.getReferencedColumns(),
                            owner.getPrimaryKey().getColumns()),
                            "weak: the foreign key must reference the owner primary key");
                    checker.check(fk.getOnDelete() == ReferentialAction.CASCADE,
                            "weak: the owner foreign key must use ON DELETE CASCADE");
                    checker.check(!fk.isSelfReferential(), "weak: the owner foreign key is not self-referential");
                    checker.check("R0".equals(fk.getDerivedFromRelationship()),
                            "weak: the foreign key must be derived from the identifying relationship R0");
                    for (String column : fk.getColumns()) {
                        Column fkColumn = weak.getColumn(column);
                        checker.check(fkColumn != null && !fkColumn.isNullable(),
                                "weak: owner foreign key columns must be NOT NULL");
                    }
                }
                List<String> pk = weak.getPrimaryKey().getColumns();
                if (strategy == WeakEntityStrategy.OWN_TABLE_COMPOSITE_PK) {
                    List<String> expected = new ArrayList<>(fk == null ? List.of() : fk.getColumns());
                    expected.add("a2");
                    checker.check(SchemaInvariants.sameColumns(pk, expected),
                            "weak composite pk: expected " + expected + " but was " + pk);
                } else {
                    checker.check(pk.size() == 1 && pk.get(0).equals("id"),
                            "weak surrogate pk: expected [id] but was " + pk);
                    List<String> expected = new ArrayList<>(fk == null ? List.of() : fk.getColumns());
                    expected.add("a2");
                    checker.check(hasUnique(weak, expected),
                            "weak surrogate pk: expected a unique constraint on " + expected);
                }
                // the partial key attribute must be stored in the weak entity table
                checker.check(weak.getColumn("a2") != null, "weak: the partial key column a2 must exist");
            }
        }
        // a weak entity whose owner is weak itself (multi-level ownership)
        ERDiagram chain = new ERDiagram().setName("weakChain");
        EREntity e0 = new EREntity("E0");
        e0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        EREntity e1 = new EREntity("E1").setWeak(true).setOwnerEntity("E0").setOwnerRelationship("R0");
        e1.addAttribute(new ERAttribute("a1", DataType.integer()).setPartialKey(true));
        EREntity e2 = new EREntity("E2").setWeak(true).setOwnerEntity("E1").setOwnerRelationship("R1");
        e2.addAttribute(new ERAttribute("a2", DataType.integer()).setPartialKey(true));
        chain.addEntity(e0).addEntity(e1).addEntity(e2);
        chain.addRelationship(identifying("R0", "E0", "E1"));
        chain.addRelationship(identifying("R1", "E1", "E2"));
        for (WeakEntityStrategy strategy : WEAK) {
            Mapped mapped = runOne(checker, "weakChain/" + strategy, chain, base().setWeakEntity(strategy), 19L);
            Table third = mapped.schema.getTable("t_2");
            checker.check(third != null, "weakChain: t_2 must exist");
            if (third != null && third.getForeignKeys().size() == 1) {
                ForeignKey fk = third.getForeignKeys().get(0);
                checker.check(fk.getReferencedTable().equals("t_1"),
                        "weakChain: E2 must reference its owner E1 (t_1)");
                Table owner = mapped.schema.getTable("t_1");
                checker.check(owner != null && SchemaInvariants.sameColumns(fk.getReferencedColumns(),
                        owner.getPrimaryKey().getColumns()),
                        "weakChain: the foreign key must reference the owner primary key");
            }
        }
        // a weak entity whose owner cannot be resolved must fall back, not throw
        ERDiagram orphan = new ERDiagram().setName("orphan");
        EREntity orphanEntity = new EREntity("E9").setWeak(true).setOwnerEntity("E404").setOwnerRelationship("R404");
        orphanEntity.addAttribute(new ERAttribute("a0", DataType.integer()).setPartialKey(true));
        orphan.addEntity(orphanEntity);
        runOne(checker, "weakOrphan", orphan, base(), 23L);
    }

    /* ---------------------------------------------------------------- 1:1 */

    private static void oneToOneCases(Checker checker) {
        for (OneToOneStrategy strategy : ONE_TO_ONE) {
            for (ReferentialActionStrategy actions : REF_ACTIONS) {
                for (boolean identity : new boolean[] {false, true}) {
                    MappingOptions options = base().setOneToOne(strategy).setReferentialActionStrategy(actions)
                            .setUseIdentityColumns(identity);
                    Mapped mapped = runOne(checker, "1:1/" + strategy + "/" + actions, oneToOneDiagram(), options, 29L);
                    RelationalSchema schema = mapped.schema;
                    switch (strategy) {
                    case FK_ON_TOTAL_SIDE: {
                        Table holder = schema.getTable("t_0");
                        checker.check(holder != null, "1:1 total side: t_0 must exist");
                        if (holder == null) {
                            break;
                        }
                        checker.check(holder.getForeignKeys().size() == 1,
                                "1:1 total side: the foreign key must be on the total side (t_0)");
                        ForeignKey fk = holder.getForeignKeys().isEmpty() ? null : holder.getForeignKeys().get(0);
                        if (fk != null) {
                            checker.check(fk.getReferencedTable().equals("t_1"),
                                    "1:1 total side: the foreign key must reference t_1");
                            checker.check(hasUnique(holder, fk.getColumns()),
                                    "1:1: the foreign key columns must be unique");
                            for (String column : fk.getColumns()) {
                                checker.check(!holder.getColumn(column).isNullable(),
                                        "1:1 total side: the foreign key must be NOT NULL");
                            }
                        }
                        break;
                    }
                    case FK_ON_EITHER_SIDE: {
                        Table t0 = schema.getTable("t_0");
                        Table t1 = schema.getTable("t_1");
                        int foreignKeys = (t0 == null ? 0 : t0.getForeignKeys().size())
                                + (t1 == null ? 0 : t1.getForeignKeys().size());
                        checker.check(foreignKeys == 1,
                                "1:1 either side: exactly one foreign key expected but found " + foreignKeys);
                        Table holder = t0 != null && !t0.getForeignKeys().isEmpty() ? t0 : t1;
                        if (holder != null && !holder.getForeignKeys().isEmpty()) {
                            checker.check(hasUnique(holder, holder.getForeignKeys().get(0).getColumns()),
                                    "1:1 either side: the foreign key columns must be unique");
                        }
                        break;
                    }
                    case MERGE_TABLES: {
                        checker.check(schema.getTables().size() == 1,
                                "1:1 merge: expected one table but found " + schema.getTableNames());
                        Table merged = schema.getTable("t_0");
                        checker.check(merged != null, "1:1 merge: the merged table must be t_0");
                        if (merged != null) {
                            checker.check(merged.getColumn("a0") != null && merged.getColumn("a2") != null,
                                    "1:1 merge: the merged table must contain the columns of both entities");
                            checker.check(merged.getPrimaryKey().getColumns().equals(List.of("a0")),
                                    "1:1 merge: the primary key must be the first entity's key");
                        }
                        checker.check("t_0".equals(mapped.trace.getEntityToTable().get("E1")),
                                "1:1 merge: both entities must be recorded as mapped to t_0");
                        break;
                    }
                    default: {
                        String joinName = mapped.trace.getRelationshipToTable().get("R0");
                        Table join = joinName == null ? null : schema.getTable(joinName);
                        checker.check(join != null, "1:1 join table: the join table must exist");
                        if (join == null) {
                            break;
                        }
                        checker.check(join.getForeignKeys().size() == 2,
                                "1:1 join table: expected two foreign keys");
                        checker.check(join.getPrimaryKey().getColumns().size() == 2,
                                "1:1 join table: the primary key must be the two foreign keys");
                        for (ForeignKey fk : join.getForeignKeys()) {
                            checker.check(hasUnique(join, fk.getColumns()),
                                    "1:1 join table: each foreign key must be unique");
                        }
                        checker.check(join.getColumn("a4") != null,
                                "1:1 join table: the relationship attribute must be in the join table");
                        break;
                    }
                    }
                }
            }
        }
    }

    /* ---------------------------------------------------------------- 1:N */

    private static void oneToManyCases(Checker checker) {
        for (OneToManyStrategy strategy : ONE_TO_MANY) {
            for (Participation participation : Participation.values()) {
                ERDiagram diagram = oneToManyDiagram(participation);
                MappingOptions options = base().setOneToMany(strategy);
                Mapped mapped = runOne(checker, "1:N/" + strategy + "/" + participation, diagram, options, 31L);
                RelationalSchema schema = mapped.schema;
                if (strategy == OneToManyStrategy.JOIN_TABLE) {
                    String joinName = mapped.trace.getRelationshipToTable().get("R0");
                    Table join = joinName == null ? null : schema.getTable(joinName);
                    checker.check(join != null, "1:N join table: the join table must exist");
                    if (join != null) {
                        checker.check(join.getForeignKeys().size() == 2, "1:N join table: expected two foreign keys");
                        checker.check(join.getPrimaryKey().getColumns().size() == 2,
                                "1:N join table: the primary key must be the two foreign keys");
                        checker.check(hasUnique(join, join.getForeignKeys().get(1).getColumns()),
                                "1:N join table: the many-side foreign key must be unique");
                    }
                    continue;
                }
                Table t0 = schema.getTable("t_0");
                Table t1 = schema.getTable("t_1");
                if (t0 == null || t1 == null) {
                    checker.fail("1:N: t_0 and t_1 must exist");
                    continue;
                }
                if (strategy == OneToManyStrategy.FK_ON_MANY_SIDE) {
                    checker.check(t1.getForeignKeys().size() == 1 && t0.getForeignKeys().isEmpty(),
                            "1:N many side: the foreign key must be on the many side (t_1)");
                    if (!t1.getForeignKeys().isEmpty()) {
                        ForeignKey fk = t1.getForeignKeys().get(0);
                        checker.check(fk.getReferencedTable().equals("t_0"),
                                "1:N many side: the foreign key must reference t_0");
                        boolean notNull = !t1.getColumn(fk.getColumns().get(0)).isNullable();
                        checker.check(notNull == (participation == Participation.TOTAL),
                                "1:N many side: NOT NULL must follow the participation of the many side");
                    }
                    checker.check(t1.getColumn("a3") != null,
                            "1:N many side: the relationship attribute must be in the table that receives the key");
                } else {
                    checker.check(t0.getForeignKeys().size() == 1 && t1.getForeignKeys().isEmpty(),
                            "1:N one side: the foreign key must be on the one side (t_0)");
                    if (!t0.getForeignKeys().isEmpty()) {
                        ForeignKey fk = t0.getForeignKeys().get(0);
                        checker.check(fk.getReferencedTable().equals("t_1"),
                                "1:N one side: the foreign key must reference t_1 (the many side)");
                        checker.check(hasUnique(t0, fk.getColumns()),
                                "1:N one side: the foreign key must be unique");
                    }
                }
            }
        }
    }

    /* ---------------------------------------------------------------- M:N */

    private static void manyToManyCases(Checker checker) {
        for (ManyToManyStrategy strategy : MANY_TO_MANY) {
            for (KeyStrategy keys : KEYS) {
                MappingOptions options = base().setManyToMany(strategy).setKeyStrategy(keys);
                Mapped mapped = runOne(checker, "M:N/" + strategy + "/" + keys, manyToManyDiagram(), options, 37L);
                String joinName = mapped.trace.getRelationshipToTable().get("R0");
                Table join = joinName == null ? null : mapped.schema.getTable(joinName);
                checker.check(join != null, "M:N: the join table must exist");
                if (join == null) {
                    continue;
                }
                checker.check(join.getForeignKeys().size() == 2, "M:N: expected two foreign keys");
                checker.check(join.getColumn("a4") != null, "M:N: the relationship attribute must be in the join table");
                if (strategy == ManyToManyStrategy.JOIN_TABLE) {
                    checker.check(join.getPrimaryKey().getColumns().size() == 2,
                            "M:N join table: the primary key must be the two foreign keys");
                    checker.check(join.getUniqueConstraints().isEmpty(),
                            "M:N join table: no extra unique constraint is expected");
                } else {
                    List<String> pk = join.getPrimaryKey().getColumns();
                    checker.check(pk.size() == 1 && pk.get(0).equals("id"),
                            "M:N surrogate join table: expected primary key [id] but was " + pk);
                    List<String> fkColumns = new ArrayList<>();
                    for (ForeignKey fk : join.getForeignKeys()) {
                        fkColumns.addAll(fk.getColumns());
                    }
                    checker.check(hasUnique(join, fkColumns),
                            "M:N surrogate join table: expected a unique constraint on " + fkColumns);
                }
                for (ForeignKey fk : join.getForeignKeys()) {
                    for (String column : fk.getColumns()) {
                        checker.check(!join.getColumn(column).isNullable(),
                                "M:N: join table foreign key columns must be NOT NULL");
                    }
                }
            }
        }
    }

    /* ---------------------------------------------------------------- recursive */

    private static void recursiveCases(Checker checker) {
        // 1:N recursive: the _dst side stores the reference to the _src side
        for (Participation participation : Participation.values()) {
            ERDiagram diagram = recursiveDiagram("R0", Cardinality.ONE, Cardinality.MANY, participation,
                    participation);
            Mapped mapped = runOne(checker, "recursive1N/" + participation, diagram, base(), 41L);
            Table table = mapped.schema.getTable("t_0");
            checker.check(table != null, "recursive 1:N: t_0 must exist");
            if (table == null) {
                continue;
            }
            checker.check(table.getForeignKeys().size() == 1, "recursive 1:N: exactly one self-referencing key");
            if (!table.getForeignKeys().isEmpty()) {
                ForeignKey fk = table.getForeignKeys().get(0);
                checker.check(fk.isSelfReferential(), "recursive 1:N: the foreign key must be self-referential");
                checker.check(fk.getReferencedTable().equals("t_0"),
                        "recursive 1:N: the foreign key must reference its own table");
                checker.check(fk.getColumns().equals(List.of("r0_dst_a0")),
                        "recursive 1:N: expected the role-qualified column r0_dst_a0 but was " + fk.getColumns());
                boolean notNull = !table.getColumn(fk.getColumns().get(0)).isNullable();
                checker.check(notNull == (participation == Participation.TOTAL),
                        "recursive 1:N: NOT NULL must follow the participation of the storing side");
                checker.check(table.getUniqueConstraints().isEmpty(),
                        "recursive 1:N: no unique constraint is expected");
            }
        }
        // 1:1 recursive: unique self-referencing column
        Mapped mapped = runOne(checker, "recursive11", recursiveDiagram("R0", Cardinality.ONE, Cardinality.ONE,
                Participation.PARTIAL, Participation.PARTIAL), base(), 43L);
        Table table = mapped.schema.getTable("t_0");
        if (table != null && !table.getForeignKeys().isEmpty()) {
            ForeignKey fk = table.getForeignKeys().get(0);
            checker.check(fk.isSelfReferential(), "recursive 1:1: the foreign key must be self-referential");
            checker.check(fk.getColumns().equals(List.of("r0_dst_a0")),
                    "recursive 1:1: expected the role-qualified column r0_dst_a0 but was " + fk.getColumns());
            checker.check(hasUnique(table, fk.getColumns()), "recursive 1:1: the foreign key must be unique");
        } else {
            checker.fail("recursive 1:1: the self-referencing foreign key is missing");
        }
        // M:N recursive: join table with two self-referencing keys
        for (ManyToManyStrategy strategy : MANY_TO_MANY) {
            MappingOptions options = base().setManyToMany(strategy);
            Mapped many = runOne(checker, "recursiveMN/" + strategy, recursiveDiagram("R0", Cardinality.MANY,
                    Cardinality.MANY, Participation.PARTIAL, Participation.PARTIAL), options, 47L);
            String joinName = many.trace.getRelationshipToTable().get("R0");
            Table join = joinName == null ? null : many.schema.getTable(joinName);
            checker.check(join != null, "recursive M:N: the join table must exist");
            if (join == null) {
                continue;
            }
            checker.check(join.getForeignKeys().size() == 2, "recursive M:N: expected two foreign keys");
            Set<String> columns = new HashSet<>();
            for (ForeignKey fk : join.getForeignKeys()) {
                checker.check(fk.isSelfReferential(), "recursive M:N: both foreign keys must be self-referential");
                columns.addAll(fk.getColumns());
            }
            checker.check(columns.equals(new HashSet<>(Arrays.asList("r0_src_a0", "r0_dst_a0"))),
                    "recursive M:N: expected role-qualified columns but was " + columns);
            if (strategy == ManyToManyStrategy.JOIN_TABLE) {
                checker.check(join.getPrimaryKey().getColumns().size() == 2,
                        "recursive M:N: the primary key must be the two foreign keys");
            } else {
                checker.check(join.getPrimaryKey().getColumns().equals(List.of("id")),
                        "recursive M:N surrogate: expected primary key [id]");
            }
        }
        // a recursive relationship without role names falls back to the many side
        ERDiagram anonymous = new ERDiagram().setName("recursiveAnonymous");
        EREntity entity = new EREntity("E0");
        entity.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        anonymous.addEntity(entity);
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.ONE));
        relationship.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.MANY));
        anonymous.addRelationship(relationship);
        runOne(checker, "recursiveAnonymous", anonymous, base(), 53L);
    }

    /* ---------------------------------------------------------------- n-ary */

    private static void naryCases(Checker checker) {
        for (NaryStrategy strategy : NARY) {
            MappingOptions options = base().setNary(strategy);
            Mapped mapped = runOne(checker, "nary/" + strategy, ternaryDiagram(), options, 59L);
            String joinName = mapped.trace.getRelationshipToTable().get("R0");
            Table join = joinName == null ? null : mapped.schema.getTable(joinName);
            checker.check(join != null, "n-ary: the join table must exist");
            if (join == null) {
                continue;
            }
            checker.check(join.getForeignKeys().size() == 3, "n-ary: expected three foreign keys");
            checker.check(join.getColumn("a6") != null, "n-ary: the relationship attribute must be in the join table");
            if (strategy == NaryStrategy.JOIN_TABLE) {
                // the fixture has one participant with cardinality 1 (E0) and two with cardinality
                // N, so the classic rule makes the two many-side keys the primary key
                List<String> pk = join.getPrimaryKey().getColumns();
                checker.check(pk.size() == 2, "n-ary join table: expected the two many-side keys but was " + pk);
                String oneSideTable = mapped.trace.getEntityToTable().get("E0");
                for (ForeignKey fk : join.getForeignKeys()) {
                    if (fk.getReferencedTable().equals(oneSideTable)) {
                        checker.check(!pk.containsAll(fk.getColumns()),
                                "n-ary join table: the key of the cardinality-1 participant must not be in the"
                                        + " primary key");
                    }
                }
            } else {
                checker.check(join.getPrimaryKey().getColumns().equals(List.of("id")),
                        "n-ary surrogate: expected primary key [id]");
                List<String> fkColumns = new ArrayList<>();
                for (ForeignKey fk : join.getForeignKeys()) {
                    fkColumns.addAll(fk.getColumns());
                }
                checker.check(hasUnique(join, fkColumns), "n-ary surrogate: expected a unique constraint on the keys");
            }
        }
        // a ternary whose participants all have cardinality N keeps every foreign key in the key
        Mapped allMany = runOne(checker, "naryAllMany", ternaryDiagram(Cardinality.MANY, Cardinality.MANY,
                Cardinality.MANY), base().setNary(NaryStrategy.JOIN_TABLE), 60L);
        Table allManyJoin = joinTableOf(allMany, "R0");
        if (allManyJoin != null) {
            checker.check(allManyJoin.getPrimaryKey().getColumns().size() == 3,
                    "n-ary all-many: expected the three foreign keys in the primary key but was "
                            + allManyJoin.getPrimaryKey().getColumns());
        }
        // with two cardinality-1 participants, excluding keys would over-constrain the relationship,
        // so every foreign key stays in the key
        Mapped twoOnes = runOne(checker, "naryTwoOnes", ternaryDiagram(Cardinality.ONE, Cardinality.ONE,
                Cardinality.MANY), base().setNary(NaryStrategy.JOIN_TABLE), 62L);
        Table twoOnesJoin = joinTableOf(twoOnes, "R0");
        if (twoOnesJoin != null) {
            checker.check(twoOnesJoin.getPrimaryKey().getColumns().size() == 3,
                    "n-ary two-ones: expected all three foreign keys in the primary key but was "
                            + twoOnesJoin.getPrimaryKey().getColumns());
        }
    }

    private static Table joinTableOf(Mapped mapped, String relationship) {
        String joinName = mapped.trace.getRelationshipToTable().get(relationship);
        return joinName == null ? null : mapped.schema.getTable(joinName);
    }

    /* ---------------------------------------------------------------- ISA */

    private static void isaCases(Checker checker) {
        for (IsAStrategy strategy : ISA) {
            for (KeyStrategy keys : KEYS) {
                MappingOptions options = base().setIsa(strategy).setKeyStrategy(keys);
                Mapped mapped = runOne(checker, "isa/" + strategy + "/" + keys, isaDiagram(), options, 61L);
                RelationalSchema schema = mapped.schema;
                switch (strategy) {
                case SINGLE_TABLE: {
                    Table table = schema.getTable("t_0");
                    checker.check(table != null, "ISA single table: t_0 must exist");
                    if (table == null) {
                        break;
                    }
                    checker.check("dtype".equals(table.getDiscriminatorColumn()),
                            "ISA single table: the discriminator column must be dtype");
                    Column dtype = table.getColumn("dtype");
                    checker.check(dtype != null && !dtype.isNullable() && dtype.getType().getLength() == 20,
                            "ISA single table: dtype must be a NOT NULL VARCHAR(20)");
                    checker.check(table.getDiscriminatorValues().size() == 2,
                            "ISA single table: two discriminator values are expected");
                    checker.check(table.getColumn("a1") != null && table.getColumn("a2") != null
                            && table.getColumn("a3") != null,
                            "ISA single table: the attributes of supertype and subtypes must share the table");
                    boolean checkFound = false;
                    for (org.ddlsmith.common.schema.CheckConstraint check : table.getCheckConstraints()) {
                        checkFound |= check.getExpression() != null && check.getExpression().contains("dtype");
                    }
                    checker.check(checkFound, "ISA single table: a check constraint on dtype is expected");
                    checker.check("t_0".equals(mapped.trace.getEntityToTable().get("E1"))
                            && "t_0".equals(mapped.trace.getEntityToTable().get("E2")),
                            "ISA single table: the subtypes must be recorded in the trace");
                    break;
                }
                case TABLE_PER_CLASS: {
                    Table subtype1 = schema.getTable("t_1");
                    Table subtype2 = schema.getTable("t_2");
                    checker.check(subtype1 != null && subtype2 != null,
                            "ISA table per class: t_1 and t_2 must exist");
                    if (subtype1 == null || subtype2 == null) {
                        break;
                    }
                    checker.check(subtype1.getColumn("a0") != null && subtype1.getColumn("a1") != null
                            && subtype1.getColumn("a2") != null,
                            "ISA table per class: the subtype table must hold the inherited attributes");
                    checker.check(schema.getTable("t_0") != null,
                            "ISA table per class: the supertype has its own attributes, so it needs a table");
                    List<String> expectedKey = keys == KeyStrategy.NATURAL_KEY ? List.of("a0") : List.of("id");
                    checker.check(subtype1.getPrimaryKey().getColumns().equals(expectedKey),
                            "ISA table per class: the subtype key must be " + expectedKey);
                    break;
                }
                default: {
                    Table supertype = schema.getTable("t_0");
                    Table subtype1 = schema.getTable("t_1");
                    checker.check(supertype != null && subtype1 != null,
                            "ISA table per subclass: t_0 and t_1 must exist");
                    if (supertype == null || subtype1 == null) {
                        break;
                    }
                    checker.check(subtype1.getForeignKeys().size() == 1,
                            "ISA table per subclass: the subtype must have exactly one foreign key");
                    if (!subtype1.getForeignKeys().isEmpty()) {
                        ForeignKey fk = subtype1.getForeignKeys().get(0);
                        checker.check(fk.getReferencedTable().equals("t_0"),
                                "ISA table per subclass: the foreign key must reference the supertype table");
                        checker.check(SchemaInvariants.sameColumns(fk.getColumns(),
                                subtype1.getPrimaryKey().getColumns()),
                                "ISA table per subclass: the primary key must also be the foreign key");
                        checker.check(SchemaInvariants.sameColumns(fk.getReferencedColumns(),
                                supertype.getPrimaryKey().getColumns()),
                                "ISA table per subclass: the foreign key must reference the supertype key");
                    }
                    checker.check(subtype1.getColumn("a2") != null,
                            "ISA table per subclass: the subtype attributes must be in the subtype table");
                    break;
                }
                }
                // the ISA relationship must never be mapped as an association
                checker.check(!mapped.trace.getRelationshipToTable().containsKey("R9"),
                        "ISA: the generalization relationship must not produce a table");
                checker.check("ISA".equals(mapped.trace.getRelationshipStrategy().get("R9")),
                        "ISA: the generalization relationship must be recorded as ISA");
            }
        }
        // TABLE_PER_CLASS with a supertype that has no attributes of its own
        ERDiagram bare = new ERDiagram().setName("isaBare");
        EREntity root = new EREntity("E0");
        EREntity child = new EREntity("E1").setSuperEntity("E0");
        child.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        child.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        root.addSubEntity("E1");
        bare.addEntity(root).addEntity(child);
        ERRelationship isa = new ERRelationship("R0").setGeneralization(true);
        isa.addParticipant(new ERParticipant("E0"));
        isa.addParticipant(new ERParticipant("E1"));
        bare.addRelationship(isa);
        runOne(checker, "isaBare/tablePerClass", bare, base().setIsa(IsAStrategy.TABLE_PER_CLASS), 67L);
        // a multi-level hierarchy
        ERDiagram multi = new ERDiagram().setName("isaMulti");
        EREntity l0 = new EREntity("E0");
        l0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        l0.addSubEntity("E1");
        EREntity l1 = new EREntity("E1").setSuperEntity("E0");
        l1.addAttribute(new ERAttribute("a1", DataType.integer()));
        l1.addSubEntity("E2");
        EREntity l2 = new EREntity("E2").setSuperEntity("E1");
        l2.addAttribute(new ERAttribute("a2", DataType.integer()));
        multi.addEntity(l0).addEntity(l1).addEntity(l2);
        for (IsAStrategy strategy : ISA) {
            Mapped mapped = runOne(checker, "isaMulti/" + strategy, multi, base().setIsa(strategy), 71L);
            if (strategy == IsAStrategy.TABLE_PER_CLASS) {
                Table deepest = mapped.schema.getTable("t_2");
                checker.check(deepest != null && deepest.getColumn("a0") != null && deepest.getColumn("a1") != null
                        && deepest.getColumn("a2") != null,
                        "ISA multi-level: the deepest subtype must inherit every attribute");
            }
            if (strategy == IsAStrategy.TABLE_PER_SUBCLASS_WITH_FK) {
                Table deepest = mapped.schema.getTable("t_2");
                checker.check(deepest != null && deepest.getForeignKeys().size() == 1
                        && deepest.getForeignKeys().get(0).getReferencedTable().equals("t_1"),
                        "ISA multi-level: the deepest subtype must reference its direct parent");
            }
        }
    }

    /* ---------------------------------------------------------------- multi-valued */

    private static void multivaluedCases(Checker checker) {
        for (MultivaluedStrategy strategy : MULTIVALUED) {
            for (KeyStrategy keys : KEYS) {
                MappingOptions options = base().setMultivalued(strategy).setKeyStrategy(keys);
                Mapped mapped = runOne(checker, "multivalued/" + strategy + "/" + keys, strongDiagram(), options, 73L);
                Table mv = mapped.schema.getTable("mv_e0_0");
                checker.check(mv != null, "multi-valued: mv_e0_0 must exist");
                if (mv == null) {
                    continue;
                }
                Table owner = mapped.schema.getTable("t_0");
                ForeignKey fk = mv.getForeignKeys().isEmpty() ? null : mv.getForeignKeys().get(0);
                checker.check(fk != null, "multi-valued: the owner foreign key must exist");
                if (fk != null && owner != null) {
                    checker.check(SchemaInvariants.sameColumns(fk.getReferencedColumns(),
                            owner.getPrimaryKey().getColumns()),
                            "multi-valued: the foreign key must reference the owner primary key");
                    checker.check(fk.getOnDelete() == ReferentialAction.CASCADE,
                            "multi-valued: ON DELETE CASCADE is expected");
                }
                if (strategy == MultivaluedStrategy.SEPARATE_TABLE) {
                    List<String> pk = mv.getPrimaryKey().getColumns();
                    checker.check(pk.size() == 2 && pk.get(1).equals("value"),
                            "multi-valued separate table: the primary key must be (owner key, value) but was " + pk);
                } else {
                    checker.check(mv.getPrimaryKey().getColumns().equals(List.of("id")),
                            "multi-valued surrogate table: the primary key must be [id]");
                    List<String> expected = new ArrayList<>(fk == null ? List.of() : fk.getColumns());
                    expected.add("value");
                    checker.check(hasUnique(mv, expected),
                            "multi-valued surrogate table: expected a unique constraint on " + expected);
                }
            }
        }
        // a multi-valued attribute of a weak entity must reference the composite key
        ERDiagram diagram = weakDiagram();
        diagram.getEntity("E1").addAttribute(new ERAttribute("a5", DataType.integer()).setMultivalued(true));
        Mapped mapped = runOne(checker, "multivaluedWeak", diagram, base(), 79L);
        Table mv = mapped.schema.getTable("mv_e1_0");
        checker.check(mv != null, "multi-valued weak: mv_e1_0 must exist");
        if (mv != null && !mv.getForeignKeys().isEmpty()) {
            Table owner = mapped.schema.getTable("t_1");
            checker.check(owner != null && SchemaInvariants.sameColumns(mv.getForeignKeys().get(0)
                    .getReferencedColumns(), owner.getPrimaryKey().getColumns()),
                    "multi-valued weak: the foreign key must reference the weak entity's composite key");
        }
        // a multi-valued composite attribute is flattened
        ERDiagram composite = new ERDiagram().setName("multivaluedComposite");
        EREntity entity = new EREntity("E0");
        entity.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        ERAttribute multivalued = new ERAttribute("a1", DataType.varchar(20)).setMultivalued(true).setComposite(true);
        multivalued.addComponent(new ERAttribute("a2", DataType.integer()));
        multivalued.addComponent(new ERAttribute("a3", DataType.varchar(20)));
        entity.addAttribute(multivalued);
        composite.addEntity(entity);
        Mapped mappedComposite = runOne(checker, "multivaluedComposite", composite, base(), 83L);
        Table mvComposite = mappedComposite.schema.getTable("mv_e0_0");
        checker.check(mvComposite != null && mvComposite.getColumn("a2") != null
                && mvComposite.getColumn("a3") != null,
                "multi-valued composite: the components must be flattened into the value table");
    }

    /* ---------------------------------------------------------------- views and sequences */

    private static void viewAndSequenceCases(Checker checker) {
        MappingOptions withViews = base().setGenerateViews(true).setGenerateIndexes(true)
                .setGenerateCheckConstraints(true).setGenerateUniqueConstraints(true);
        Mapped mapped = runOne(checker, "views/on", manyToManyDiagram(), withViews, 89L);
        checker.check(!mapped.schema.getViews().isEmpty(), "views: at least one view is expected");
        checker.check(mapped.schema.getViews().size() <= 2, "views: at most two views are expected");
        int viewIndex = 0;
        for (View view : mapped.schema.getViews()) {
            checker.check(view.getName().equals("v_" + viewIndex), "views: expected the name v_" + viewIndex);
            checker.check(view.getCheckOption() == null, "views: the check option must be null");
            checker.check(view.getSchemaName() != null, "views: the schema name must be set");
            checker.check(view.getQuery() != null && view.getQuery().startsWith("SELECT ")
                    && view.getQuery().contains(" JOIN ") && view.getQuery().contains(" ON "),
                    "views: the query must be a join but was " + view.getQuery());
            checker.check(!view.isMaterialized(), "views: views must not be materialized");
            viewIndex++;
        }
        Mapped withoutViews = runOne(checker, "views/off", manyToManyDiagram(),
                base().setGenerateViews(false), 97L);
        checker.check(withoutViews.schema.getViews().isEmpty(), "views: no view is expected when they are disabled");

        Mapped withSequences = runOne(checker, "sequences/on", strongDiagram(),
                base().setUseIdentityColumns(false), 101L);
        checker.check(withSequences.schema.getSequences().size() == 1,
                "sequences: one sequence is expected when identity columns are disabled and there are 3+ tables");
        if (!withSequences.schema.getSequences().isEmpty()) {
            checker.check(withSequences.schema.getSequences().get(0).getName().equals("seq_0"),
                    "sequences: expected the name seq_0");
        }
        Mapped identity = runOne(checker, "sequences/off", strongDiagram(),
                base().setUseIdentityColumns(true), 103L);
        checker.check(identity.schema.getSequences().isEmpty(),
                "sequences: no sequence is expected when identity columns are used");
        ERDiagram twoTables = manyToManyDiagram();
        Mapped small = runOne(checker, "sequences/small", twoTables, base().setUseIdentityColumns(false), 107L);
        if (small.schema.getTables().size() < 3) {
            checker.check(small.schema.getSequences().isEmpty(),
                    "sequences: no sequence is expected for a schema with fewer than three tables");
        }
        // indexes, unique constraints and check constraints must be well formed when enabled
        Mapped rich = runOne(checker, "constraints/rich", strongDiagram(),
                base().setGenerateIndexes(true).setGenerateCheckConstraints(true).setGenerateUniqueConstraints(true)
                        .setGenerateViews(true),
                109L);
        for (Table table : rich.schema.getTables()) {
            for (Index index : table.getIndexes()) {
                checker.check(!index.getColumns().isEmpty(), "indexes: an index must have columns");
                checker.check(index.getName().startsWith(index.isUnique() ? "uidx_" : "idx_"),
                        "indexes: unexpected name " + index.getName());
            }            for (UniqueConstraint unique : table.getUniqueConstraints()) {
                checker.check(!unique.getColumns().isEmpty(), "unique constraints: must have columns");
            }
            for (org.ddlsmith.common.schema.CheckConstraint check : table.getCheckConstraints()) {
                checker.check(check.getExpression() != null && !check.getExpression().isBlank(),
                        "check constraints: must have an expression");
            }
        }
    }

    /* ---------------------------------------------------------------- collisions and edge cases */

    private static void collisionCases(Checker checker) {
        // an entity whose attribute is already named id, mapped with a surrogate key
        ERDiagram diagram = new ERDiagram().setName("idCollision");
        EREntity entity = new EREntity("E0");
        entity.addAttribute(new ERAttribute("id", DataType.varchar(20)).setPrimaryKey(true));
        entity.addAttribute(new ERAttribute("a0", DataType.integer()));
        diagram.addEntity(entity);
        Mapped mapped = runOne(checker, "idCollision/surrogate", diagram,
                base().setKeyStrategy(KeyStrategy.SURROGATE_KEY), 113L);
        Table table = mapped.schema.getTable("t_0");
        checker.check(table != null, "id collision: t_0 must exist");
        if (table != null) {
            checker.check(table.getColumn("id") != null && table.getColumn("id_2") != null,
                    "id collision: the attribute keeps id and the surrogate key is renamed");
            checker.check(table.getPrimaryKey().getColumns().size() == 1
                    && table.getPrimaryKey().getColumns().get(0).equals("id_2"),
                    "id collision: the surrogate key must be the primary key");
        }
        // two entities with the same table index in their name must not collide
        ERDiagram duplicateNames = new ERDiagram().setName("duplicateNames");
        duplicateNames.addEntity(new EREntity("E0").addAttribute(
                new ERAttribute("a0", DataType.integer()).setPrimaryKey(true)));
        duplicateNames.addEntity(new EREntity("E0").addAttribute(
                new ERAttribute("a1", DataType.integer()).setPrimaryKey(true)));
        runOne(checker, "duplicateEntityNames", duplicateNames, base(), 127L);
        // a relationship between an entity and itself with only one participant
        ERDiagram unary = new ERDiagram().setName("unary");
        EREntity unaryEntity = new EREntity("E0");
        unaryEntity.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        unary.addEntity(unaryEntity);
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0", "r0_dst"));
        unary.addRelationship(relationship);
        runOne(checker, "unary", unary, base(), 131L);
        // an n-ary relationship that references the same entity twice
        ERDiagram repeated = new ERDiagram().setName("repeatedParticipant");
        EREntity repeatedEntity = new EREntity("E0");
        repeatedEntity.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        repeated.addEntity(repeatedEntity);
        ERRelationship repeatedRelationship = new ERRelationship("R0");
        repeatedRelationship.addParticipant(new ERParticipant("E0", "r0_src").setCardinality(Cardinality.MANY));
        repeatedRelationship.addParticipant(new ERParticipant("E0", "r0_dst").setCardinality(Cardinality.MANY));
        repeatedRelationship.addParticipant(new ERParticipant("E0", "r0_aux").setCardinality(Cardinality.MANY));
        repeated.addRelationship(repeatedRelationship);
        runOne(checker, "repeatedParticipant", repeated, base(), 137L);
        // two subtypes of one hierarchy that share a table but are related to each other
        ERDiagram isaRelation = new ERDiagram().setName("isaRelation");
        EREntity sharedSupertype = new EREntity("E0");
        sharedSupertype.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        sharedSupertype.addSubEntity("E1");
        sharedSupertype.addSubEntity("E2");
        EREntity sharedSub1 = new EREntity("E1").setSuperEntity("E0");
        sharedSub1.addAttribute(new ERAttribute("a1", DataType.integer()));
        EREntity sharedSub2 = new EREntity("E2").setSuperEntity("E0");
        sharedSub2.addAttribute(new ERAttribute("a2", DataType.integer()));
        isaRelation.addEntity(sharedSupertype).addEntity(sharedSub1).addEntity(sharedSub2);
        ERRelationship isaLink = new ERRelationship("R8").setGeneralization(true);
        isaLink.addParticipant(new ERParticipant("E0"));
        isaLink.addParticipant(new ERParticipant("E1"));
        isaRelation.addRelationship(isaLink);
        ERRelationship sharedRelationship = new ERRelationship("R0");
        sharedRelationship.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.TOTAL));
        sharedRelationship.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        sharedRelationship.addAttribute(new ERAttribute("a3", DataType.integer()));
        isaRelation.addRelationship(sharedRelationship);
        for (IsAStrategy strategy : ISA) {
            runOne(checker, "isaRelation/" + strategy, isaRelation, base().setIsa(strategy), 181L);
        }
        // a relationship to an entity that does not exist
        ERDiagram dangling = new ERDiagram().setName("dangling");
        EREntity known = new EREntity("E0");
        known.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        dangling.addEntity(known);
        ERRelationship danglingRelationship = new ERRelationship("R0");
        danglingRelationship.addParticipant(new ERParticipant("E0"));
        danglingRelationship.addParticipant(new ERParticipant("E404"));
        dangling.addRelationship(danglingRelationship);
        runOne(checker, "danglingParticipant", dangling, base(), 139L);
        // a long entity name must be truncated deterministically
        ERDiagram longNames = new ERDiagram().setName("longNames");
        EREntity longEntity = new EREntity("E" + "9".repeat(80));
        longEntity.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        longNames.addEntity(longEntity);
        Mapped longMapped = runOne(checker, "longNames", longNames, base(), 149L);
        for (Table longTable : longMapped.schema.getTables()) {
            checker.check(longTable.getName().length() <= SchemaInvariants.MAX_NAME_LENGTH,
                    "long names: table name too long: " + longTable.getName());
        }
        // a null schema name and a null trace must not break the mapper
        Randomly.setSeed(151L);
        try {
            RelationalSchema schema = new StandardERToRelationalMapper().map(strongDiagram(), base(), null, null);
            checker.mappings++;
            for (String problem : SchemaInvariants.validate(schema)) {
                checker.fail("nullSchemaName: " + problem);
            }
        } catch (RuntimeException e) {
            checker.fail("nullSchemaName: the mapper threw " + e);
        }
        // an empty diagram
        runOne(checker, "emptyDiagram", new ERDiagram().setName("empty"), base(), 157L);
    }

    private static void robustnessCases(Checker checker) {
        // a weak entity that owns itself, an ISA cycle and an empty relationship must not throw
        ERDiagram selfOwning = new ERDiagram().setName("selfOwning");
        EREntity entity = new EREntity("E0").setWeak(true).setOwnerEntity("E0").setOwnerRelationship("R0");
        entity.addAttribute(new ERAttribute("a0", DataType.integer()).setPartialKey(true));
        selfOwning.addEntity(entity);
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0"));
        relationship.addParticipant(new ERParticipant("E0"));
        selfOwning.addRelationship(relationship);
        runOne(checker, "selfOwningWeak", selfOwning, base(), 163L);

        ERDiagram isaCycle = new ERDiagram().setName("isaCycle");
        EREntity a = new EREntity("E0").setSuperEntity("E1");
        EREntity b = new EREntity("E1").setSuperEntity("E0");
        a.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        b.addAttribute(new ERAttribute("a1", DataType.integer()).setPrimaryKey(true));
        a.addSubEntity("E1");
        b.addSubEntity("E0");
        isaCycle.addEntity(a).addEntity(b);
        runOne(checker, "isaCycle", isaCycle, base().setIsa(IsAStrategy.TABLE_PER_CLASS), 167L);

        ERDiagram emptyRelationship = new ERDiagram().setName("emptyRelationship");
        emptyRelationship.addEntity(new EREntity("E0").addAttribute(
                new ERAttribute("a0", DataType.integer()).setPrimaryKey(true)));
        emptyRelationship.addRelationship(new ERRelationship("R0"));
        runOne(checker, "emptyRelationship", emptyRelationship, base(), 173L);

        // an attribute without a type must still produce a column
        ERDiagram untyped = new ERDiagram().setName("untyped");
        EREntity untypedEntity = new EREntity("E0");
        untypedEntity.addAttribute(new ERAttribute("a0", null).setPrimaryKey(true));
        untypedEntity.addAttribute(new ERAttribute("a1", null).setMultivalued(true));
        untypedEntity.addAttribute(new ERAttribute("a2", null).setDerived(true).setDerivedExpression("1"));
        untyped.addEntity(untypedEntity);
        runOne(checker, "untypedAttributes", untyped, base(), 179L);
    }

    /* ================================================================== sweeps */

    private static void strategySweep(Checker checker) {
        ERDiagram[] diagrams = {mixedDiagram(), isaDiagram(), sharedSubclassDiagram(), categoryDiagram(),
                aggregationDiagram()};
        for (OneToOneStrategy oneToOne : ONE_TO_ONE) {
            for (OneToManyStrategy oneToMany : ONE_TO_MANY) {
                for (ManyToManyStrategy manyToMany : MANY_TO_MANY) {
                    for (NaryStrategy nary : NARY) {
                        for (WeakEntityStrategy weak : WEAK) {
                            for (IsAStrategy isa : ISA) {
                                for (MultivaluedStrategy multivalued : MULTIVALUED) {
                                    for (DerivedStrategy derived : DERIVED) {
                                        for (KeyStrategy keys : KEYS) {
                                            for (ReferentialActionStrategy actions : REF_ACTIONS) {
                                                MappingOptions options = base().setOneToOne(oneToOne)
                                                        .setOneToMany(oneToMany).setManyToMany(manyToMany)
                                                        .setNary(nary).setWeakEntity(weak).setIsa(isa)
                                                        .setMultivalued(multivalued).setDerived(derived)
                                                        .setKeyStrategy(keys).setReferentialActionStrategy(actions)
                                                        .setGenerateIndexes(true).setGenerateCheckConstraints(true)
                                                        .setGenerateUniqueConstraints(true)
                                                        .setUseIdentityColumns(keys != KeyStrategy.NATURAL_KEY);
                                                checker.combinations++;
                                                for (ERDiagram diagram : diagrams) {
                                                    runOne(checker, "sweep/" + diagram.getName(), diagram, options,
                                                            1000L + checker.combinations);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void booleanFlagSweep(Checker checker) {
        ERDiagram[] diagrams = {mixedDiagram(), strongDiagram(), compositeStructureDiagram(), categoryDiagram()};
        for (int mask = 0; mask < 128; mask++) {
            MappingOptions options = base()
                    .setGenerateIndexes((mask & 1) != 0)
                    .setGenerateViews((mask & 2) != 0)
                    .setGenerateCheckConstraints((mask & 4) != 0)
                    .setGenerateUniqueConstraints((mask & 8) != 0)
                    .setUseNamedConstraints((mask & 16) != 0)
                    .setDeferrableForeignKeys((mask & 32) != 0)
                    .setUseIdentityColumns((mask & 64) != 0)
                    .setKeyStrategy(KeyStrategy.SURROGATE_KEY)
                    .setReferentialActionStrategy(ReferentialActionStrategy.RANDOM)
                    .setDerived(DerivedStrategy.GENERATED_COLUMN)
                    .setMultivalued(MultivaluedStrategy.SEPARATE_TABLE_WITH_SURROGATE_KEY);
            for (ERDiagram diagram : diagrams) {
                Mapped mapped = runOne(checker, "flags/" + mask, diagram, options, 2000L + mask);
                for (Table table : mapped.schema.getTables()) {
                    for (ForeignKey fk : table.getForeignKeys()) {
                        checker.check(fk.isDeferrable() == options.isDeferrableForeignKeys(),
                                "flags: the deferrable flag must follow the options");
                        checker.check(!fk.isInitiallyDeferred(), "flags: foreign keys are never initially deferred");
                        if (fk.getOnDelete() == ReferentialAction.SET_NULL) {
                            for (String column : fk.getColumns()) {
                                checker.check(table.getColumn(column).isNullable(),
                                        "flags: SET NULL is only allowed on nullable columns");
                            }
                        }
                        checker.check(fk.getOnDelete() != ReferentialAction.SET_DEFAULT,
                                "flags: SET DEFAULT requires defaults on all key columns");
                    }
                    for (Column column : table.getColumns()) {
                        checker.check(!(column.isIdentity() && column.isGenerated()),
                                "flags: a column is never both identity and generated");
                    }
                }
                if (!options.isUseIdentityColumns() && mapped.schema.getTables().size() >= 3) {
                    checker.check(mapped.schema.getSequences().size() == 1,
                            "flags: a sequence is expected when identity columns are disabled");
                }
                if (options.isUseIdentityColumns()) {
                    checker.check(mapped.schema.getSequences().isEmpty(),
                            "flags: no sequence is expected when identity columns are used");
                }
                checker.check(mapped.schema.getViews().size() <= 2, "flags: at most two views are expected");
            }
        }
    }

    private static void randomOptionSweep(Checker checker) {
        ERDiagram[] diagrams = {mixedDiagram(), isaDiagram(), weakDiagram(), recursiveDiagram("R0", Cardinality.ONE,
                Cardinality.MANY, Participation.PARTIAL, Participation.PARTIAL), sharedSubclassDiagram(),
                categoryDiagram(), aggregationDiagram(), partialSpecializationDiagram(), compositeStructureDiagram()};
        for (int i = 0; i < RANDOM_OPTION_INSTANCES; i++) {
            Randomly.setSeed(5000L + i);
            MappingOptions options = MappingOptions.random();
            checker.randomInstances++;
            for (ERDiagram diagram : diagrams) {
                runOne(checker, "random/" + i + "/" + diagram.getName(), diagram, options, 5000L + i);
            }
        }
    }

    private static void determinismCheck(Checker checker) {
        for (int i = 0; i < 25; i++) {
            long seed = 9000L + i;
            Randomly.setSeed(seed);
            MappingOptions options = MappingOptions.random();
            Randomly.setSeed(seed);
            RelationalSchema first = new StandardERToRelationalMapper()
                    .map(mixedDiagram(), options, "s", new MappingTrace());
            Randomly.setSeed(seed);
            RelationalSchema second = new StandardERToRelationalMapper()
                    .map(mixedDiagram(), options, "s", new MappingTrace());
            checker.check(describe(first).equals(describe(second)),
                    "determinism: the same seed must produce the same schema");
            checker.mappings += 2;
        }
    }

    /* ================================================================== diagrams */

    public static MappingOptions base() {
        return new MappingOptions()
                .setOneToOne(OneToOneStrategy.FK_ON_TOTAL_SIDE)
                .setOneToMany(OneToManyStrategy.FK_ON_MANY_SIDE)
                .setManyToMany(ManyToManyStrategy.JOIN_TABLE)
                .setNary(NaryStrategy.JOIN_TABLE)
                .setWeakEntity(WeakEntityStrategy.OWN_TABLE_COMPOSITE_PK)
                .setIsa(IsAStrategy.SINGLE_TABLE)
                .setMultivalued(MultivaluedStrategy.SEPARATE_TABLE)
                .setDerived(DerivedStrategy.MATERIALIZE)
                .setKeyStrategy(KeyStrategy.NATURAL_KEY)
                .setReferentialActionStrategy(ReferentialActionStrategy.NONE)
                .setGenerateIndexes(false)
                .setGenerateViews(false)
                .setGenerateCheckConstraints(false)
                .setGenerateUniqueConstraints(false)
                .setUseNamedConstraints(true)
                .setDeferrableForeignKeys(false)
                .setUseIdentityColumns(false);
    }

    public static ERDiagram strongDiagram() {
        ERDiagram diagram = new ERDiagram().setName("strong");
        EREntity e0 = new EREntity("E0");
        e0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        e0.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        e0.addAttribute(new ERAttribute("a2", DataType.integer()).setOptional(true));
        e0.addAttribute(new ERAttribute("a3", DataType.integer()).setDerived(true).setDerivedExpression("a2 + 1"));
        ERAttribute composite = new ERAttribute("a4", DataType.varchar(20)).setComposite(true);
        composite.addComponent(new ERAttribute("a4a", DataType.integer()));
        composite.addComponent(new ERAttribute("a4b", DataType.varchar(20)).setOptional(true));
        e0.addAttribute(composite);
        e0.addAttribute(new ERAttribute("a5", DataType.integer()).setMultivalued(true));
        EREntity e1 = new EREntity("E1");
        e1.addAttribute(new ERAttribute("a6", DataType.integer()).setPrimaryKey(true));
        e1.addAttribute(new ERAttribute("a7", DataType.varchar(50)));
        EREntity e2 = new EREntity("E2");
        e2.addAttribute(new ERAttribute("a8", DataType.integer()).setPrimaryKey(true));
        diagram.addEntity(e0).addEntity(e1).addEntity(e2);
        return diagram;
    }

    public static ERDiagram weakDiagram() {
        ERDiagram diagram = new ERDiagram().setName("weak");
        EREntity owner = new EREntity("E0");
        owner.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        owner.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        EREntity weak = new EREntity("E1").setWeak(true).setOwnerEntity("E0").setOwnerRelationship("R0");
        weak.addAttribute(new ERAttribute("a2", DataType.integer()).setPartialKey(true));
        weak.addAttribute(new ERAttribute("a3", DataType.varchar(20)));
        diagram.addEntity(owner).addEntity(weak);
        diagram.addRelationship(identifying("R0", "E0", "E1"));
        return diagram;
    }

    public static ERRelationship identifying(String name, String owner, String weak) {
        ERRelationship relationship = new ERRelationship(name).setIdentifying(true);
        relationship.addParticipant(new ERParticipant(owner).setCardinality(Cardinality.ONE)
                .setParticipation(Participation.TOTAL));
        relationship.addParticipant(new ERParticipant(weak).setCardinality(Cardinality.MANY)
                .setParticipation(Participation.TOTAL));
        return relationship;
    }

    public static ERDiagram oneToOneDiagram() {
        ERDiagram diagram = new ERDiagram().setName("oneToOne");
        EREntity e0 = new EREntity("E0");
        e0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        e0.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        EREntity e1 = new EREntity("E1");
        e1.addAttribute(new ERAttribute("a2", DataType.integer()).setPrimaryKey(true));
        e1.addAttribute(new ERAttribute("a3", DataType.varchar(20)));
        diagram.addEntity(e0).addEntity(e1);
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.TOTAL));
        relationship.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.PARTIAL));
        relationship.addAttribute(new ERAttribute("a4", DataType.integer()));
        diagram.addRelationship(relationship);
        return diagram;
    }

    public static ERDiagram oneToManyDiagram(Participation manyParticipation) {
        ERDiagram diagram = new ERDiagram().setName("oneToMany");
        EREntity e0 = new EREntity("E0");
        e0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        e0.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        EREntity e1 = new EREntity("E1");
        e1.addAttribute(new ERAttribute("a2", DataType.integer()).setPrimaryKey(true));
        e1.addAttribute(new ERAttribute("a3", DataType.varchar(20)));
        diagram.addEntity(e0).addEntity(e1);
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.PARTIAL));
        relationship.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY)
                .setParticipation(manyParticipation));
        relationship.addAttribute(new ERAttribute("a4", DataType.integer()));
        diagram.addRelationship(relationship);
        return diagram;
    }

    public static ERDiagram manyToManyDiagram() {
        ERDiagram diagram = new ERDiagram().setName("manyToMany");
        EREntity e0 = new EREntity("E0");
        e0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        e0.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        EREntity e1 = new EREntity("E1");
        e1.addAttribute(new ERAttribute("a2", DataType.integer()).setPrimaryKey(true));
        e1.addAttribute(new ERAttribute("a3", DataType.varchar(20)));
        diagram.addEntity(e0).addEntity(e1);
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        relationship.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.TOTAL));
        relationship.addAttribute(new ERAttribute("a4", DataType.integer()));
        diagram.addRelationship(relationship);
        return diagram;
    }

    public static ERDiagram recursiveDiagram(String name, Cardinality source, Cardinality target,
            Participation sourceParticipation, Participation targetParticipation) {
        ERDiagram diagram = new ERDiagram().setName("recursive");
        EREntity entity = new EREntity("E0");
        entity.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        entity.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        diagram.addEntity(entity);
        ERRelationship relationship = new ERRelationship(name);
        relationship.addParticipant(new ERParticipant("E0", "r0_src").setCardinality(source)
                .setParticipation(sourceParticipation));
        relationship.addParticipant(new ERParticipant("E0", "r0_dst").setCardinality(target)
                .setParticipation(targetParticipation));
        diagram.addRelationship(relationship);
        return diagram;
    }

    public static ERDiagram ternaryDiagram() {
        return ternaryDiagram(Cardinality.ONE, Cardinality.MANY, Cardinality.MANY);
    }

    /** A ternary relationship whose three participants carry the given cardinalities. */
    public static ERDiagram ternaryDiagram(Cardinality first, Cardinality second, Cardinality third) {
        ERDiagram diagram = new ERDiagram().setName("ternary");
        EREntity e0 = new EREntity("E0");
        e0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        EREntity e1 = new EREntity("E1");
        e1.addAttribute(new ERAttribute("a2", DataType.integer()).setPrimaryKey(true));
        EREntity e2 = new EREntity("E2");
        e2.addAttribute(new ERAttribute("a4", DataType.integer()).setPrimaryKey(true));
        diagram.addEntity(e0).addEntity(e1).addEntity(e2);
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0").setCardinality(first)
                .setParticipation(Participation.TOTAL));
        relationship.addParticipant(new ERParticipant("E1").setCardinality(second)
                .setParticipation(Participation.PARTIAL));
        relationship.addParticipant(new ERParticipant("E2").setCardinality(third)
                .setParticipation(Participation.PARTIAL));
        relationship.addAttribute(new ERAttribute("a6", DataType.varchar(20)));
        diagram.addRelationship(relationship);
        return diagram;
    }

    public static ERDiagram isaDiagram() {
        ERDiagram diagram = new ERDiagram().setName("isa");
        EREntity supertype = new EREntity("E0");
        supertype.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        supertype.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        supertype.addSubEntity("E1");
        supertype.addSubEntity("E2");
        EREntity subtype1 = new EREntity("E1").setSuperEntity("E0");
        subtype1.addAttribute(new ERAttribute("a2", DataType.integer()));
        EREntity subtype2 = new EREntity("E2").setSuperEntity("E0");
        subtype2.addAttribute(new ERAttribute("a3", DataType.varchar(20)));
        diagram.addEntity(supertype).addEntity(subtype1).addEntity(subtype2);
        ERRelationship isa1 = new ERRelationship("R8").setGeneralization(true);
        isa1.addParticipant(new ERParticipant("E0"));
        isa1.addParticipant(new ERParticipant("E1"));
        ERRelationship isa2 = new ERRelationship("R9").setGeneralization(true);
        isa2.addParticipant(new ERParticipant("E0"));
        isa2.addParticipant(new ERParticipant("E2"));
        diagram.addRelationship(isa1).addRelationship(isa2);
        return diagram;
    }

    /* ---------------------------------------------------------------- enhanced ER fixtures */

    /**
     * A generalization lattice: E1 and E2 are subtypes of E0, and E3 is a shared subclass of both
     * of them (multiple inheritance), which connects the two hierarchies into one component.
     */
    public static ERDiagram sharedSubclassDiagram() {
        ERDiagram diagram = new ERDiagram().setName("sharedSubclass");
        EREntity root = new EREntity("E0");
        root.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        root.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        EREntity first = new EREntity("E1").setSuperEntity("E0");
        first.addAttribute(new ERAttribute("a2", DataType.integer()));
        EREntity second = new EREntity("E2").setSuperEntity("E0");
        second.addAttribute(new ERAttribute("a3", DataType.varchar(20)));
        EREntity shared = new EREntity("E3").setSuperEntity("E1");
        shared.addSuperEntity("E2");
        shared.addAttribute(new ERAttribute("a4", DataType.date()));
        root.addSubEntity("E1");
        root.addSubEntity("E2");
        first.addSubEntity("E3");
        second.addSubEntity("E3");
        EREntity other = new EREntity("E4");
        other.addAttribute(new ERAttribute("a5", DataType.integer()).setPrimaryKey(true));
        diagram.addEntity(root).addEntity(first).addEntity(second).addEntity(shared).addEntity(other);
        ERRelationship isa1 = new ERRelationship("R8").setGeneralization(true);
        isa1.addParticipant(new ERParticipant("E0"));
        isa1.addParticipant(new ERParticipant("E1"));
        ERRelationship isa2 = new ERRelationship("R9").setGeneralization(true);
        isa2.addParticipant(new ERParticipant("E0"));
        isa2.addParticipant(new ERParticipant("E2"));
        ERRelationship isa3 = new ERRelationship("R10").setGeneralization(true);
        isa3.addParticipant(new ERParticipant("E1"));
        isa3.addParticipant(new ERParticipant("E3"));
        ERRelationship isa4 = new ERRelationship("R11").setGeneralization(true);
        isa4.addParticipant(new ERParticipant("E2"));
        isa4.addParticipant(new ERParticipant("E3"));
        diagram.addRelationship(isa1).addRelationship(isa2).addRelationship(isa3).addRelationship(isa4);
        ERRelationship association = new ERRelationship("R0");
        association.addParticipant(new ERParticipant("E3").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        association.addParticipant(new ERParticipant("E4").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        association.addAttribute(new ERAttribute("a6", DataType.integer()));
        diagram.addRelationship(association);
        return diagram;
    }

    /**
     * A union type: E2 is a category over E0 and E1 (every E2 instance is an E0 or an E1), and it
     * takes part in an ordinary 1:N relationship with E3 as well.
     */
    public static ERDiagram categoryDiagram() {
        ERDiagram diagram = new ERDiagram().setName("category");
        EREntity first = new EREntity("E0");
        first.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        EREntity second = new EREntity("E1");
        second.addAttribute(new ERAttribute("a1", DataType.varchar(20)).setPrimaryKey(true));
        EREntity category = new EREntity("E2");
        category.addAttribute(new ERAttribute("a2", DataType.integer()));
        category.addCategorySuper("E0");
        category.addCategorySuper("E1");
        EREntity other = new EREntity("E3");
        other.addAttribute(new ERAttribute("a3", DataType.integer()).setPrimaryKey(true));
        diagram.addEntity(first).addEntity(second).addEntity(category).addEntity(other);
        ERRelationship membership0 = new ERRelationship("R0").setCategoryLink(true);
        membership0.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.ONE));
        membership0.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.ONE));
        ERRelationship membership1 = new ERRelationship("R1").setCategoryLink(true);
        membership1.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.ONE));
        membership1.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.ONE));
        ERRelationship association = new ERRelationship("R2");
        association.addParticipant(new ERParticipant("E3").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.TOTAL));
        association.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        association.addAttribute(new ERAttribute("a4", DataType.integer()));
        diagram.addRelationship(membership0).addRelationship(membership1).addRelationship(association);
        return diagram;
    }

    /**
     * An aggregation: the M:N relationship R0 between E0 and E1 is aggregated into the entity
     * Agg_R0, and E2 relates to that aggregation through the outer relationship Ragg_R0. The two
     * absorbed links R1 and R2 carry the graph evidence and are never mapped as associations.
     */
    public static ERDiagram aggregationDiagram() {
        ERDiagram diagram = new ERDiagram().setName("aggregation");
        EREntity e0 = new EREntity("E0");
        e0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        EREntity e1 = new EREntity("E1");
        e1.addAttribute(new ERAttribute("a1", DataType.integer()).setPrimaryKey(true));
        EREntity e2 = new EREntity("E2");
        e2.addAttribute(new ERAttribute("a2", DataType.integer()).setPrimaryKey(true));
        EREntity aggregate = new EREntity("Agg_R0").setAggregatedRelationship("R0");
        diagram.addEntity(e0).addEntity(e1).addEntity(e2).addEntity(aggregate);
        ERRelationship inner = new ERRelationship("R0");
        inner.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        inner.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        inner.addAttribute(new ERAttribute("a3", DataType.varchar(20)));
        ERRelationship link0 = new ERRelationship("R1").setAggregationLink(true);
        link0.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.MANY));
        link0.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.ONE));
        ERRelationship link1 = new ERRelationship("R2").setAggregationLink(true);
        link1.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY));
        link1.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.ONE));
        ERRelationship outer = new ERRelationship("Ragg_R0");
        outer.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        outer.addParticipant(new ERParticipant("Agg_R0").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        outer.addMergedTypeName("R1");
        outer.addMergedTypeName("R2");
        diagram.addRelationship(inner).addRelationship(link0).addRelationship(link1).addRelationship(outer);
        return diagram;
    }

    /** A specialization that is partial: instances of the root itself are legal as well. */
    public static ERDiagram partialSpecializationDiagram() {
        ERDiagram diagram = new ERDiagram().setName("partialSpecialization");
        EREntity root = new EREntity("E0");
        root.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        root.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        root.addSubEntity("E1");
        root.addSubEntity("E2");
        root.setTotalSpecialization(false);
        EREntity first = new EREntity("E1").setSuperEntity("E0");
        first.addAttribute(new ERAttribute("a2", DataType.integer()));
        EREntity second = new EREntity("E2").setSuperEntity("E0");
        second.addAttribute(new ERAttribute("a3", DataType.varchar(20)));
        diagram.addEntity(root).addEntity(first).addEntity(second);
        ERRelationship isa1 = new ERRelationship("R8").setGeneralization(true);
        isa1.addParticipant(new ERParticipant("E0"));
        isa1.addParticipant(new ERParticipant("E1"));
        ERRelationship isa2 = new ERRelationship("R9").setGeneralization(true);
        isa2.addParticipant(new ERParticipant("E0"));
        isa2.addParticipant(new ERParticipant("E2"));
        diagram.addRelationship(isa1).addRelationship(isa2);
        return diagram;
    }

    /** A nested (multi-level) composite attribute, a composite natural key and a composite partial key. */
    public static ERDiagram compositeStructureDiagram() {
        ERDiagram diagram = new ERDiagram().setName("compositeStructure");
        EREntity owner = new EREntity("E0");
        owner.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        owner.addAttribute(new ERAttribute("a1", DataType.varchar(20)).setPrimaryKey(true));
        ERAttribute outer = new ERAttribute("comp_0", DataType.integer()).setComposite(true);
        ERAttribute inner = new ERAttribute("comp_1", DataType.integer()).setComposite(true);
        inner.addComponent(new ERAttribute("a2", DataType.integer()));
        inner.addComponent(new ERAttribute("a3", DataType.integer()));
        outer.addComponent(new ERAttribute("a4", DataType.varchar(20)));
        outer.addComponent(inner);
        owner.addAttribute(outer);
        EREntity weak = new EREntity("E1").setWeak(true).setOwnerEntity("E0").setOwnerRelationship("R0");
        weak.addAttribute(new ERAttribute("a5", DataType.integer()).setPartialKey(true));
        weak.addAttribute(new ERAttribute("a6", DataType.integer()).setPartialKey(true));
        weak.addAttribute(new ERAttribute("a7", DataType.varchar(20)));
        ERAttribute weakComposite = new ERAttribute("comp_2", DataType.integer()).setComposite(true)
                .setMultivalued(true);
        weakComposite.addComponent(new ERAttribute("a8", DataType.integer()));
        weakComposite.addComponent(new ERAttribute("a9", DataType.varchar(20)));
        weak.addAttribute(weakComposite);
        diagram.addEntity(owner).addEntity(weak);
        diagram.addRelationship(identifying("R0", "E0", "E1"));
        return diagram;
    }

    public static ERDiagram mixedDiagram() {
        ERDiagram diagram = new ERDiagram().setName("mixed");
        EREntity e0 = new EREntity("E0");
        e0.addAttribute(new ERAttribute("a0", DataType.integer()).setPrimaryKey(true));
        e0.addAttribute(new ERAttribute("a1", DataType.varchar(20)));
        e0.addAttribute(new ERAttribute("a2", DataType.integer()).setOptional(true));
        e0.addAttribute(new ERAttribute("a3", DataType.integer()).setDerived(true).setDerivedExpression("a2 + 1"));
        ERAttribute composite = new ERAttribute("a4", DataType.varchar(20)).setComposite(true);
        composite.addComponent(new ERAttribute("a4a", DataType.integer()));
        composite.addComponent(new ERAttribute("a4b", DataType.varchar(20)).setOptional(true));
        e0.addAttribute(composite);
        e0.addAttribute(new ERAttribute("a5", DataType.integer()).setMultivalued(true));

        EREntity e1 = new EREntity("E1");
        e1.addAttribute(new ERAttribute("a6", DataType.integer()).setPrimaryKey(true));
        e1.addAttribute(new ERAttribute("a7", DataType.varchar(50)));

        EREntity e2 = new EREntity("E2");
        e2.addAttribute(new ERAttribute("a8", DataType.integer()).setPrimaryKey(true));
        e2.addAttribute(new ERAttribute("a9", DataType.integer()).setOptional(true));

        EREntity e3 = new EREntity("E3").setWeak(true).setOwnerEntity("E0").setOwnerRelationship("R2");
        e3.addAttribute(new ERAttribute("a10", DataType.integer()).setPartialKey(true));
        e3.addAttribute(new ERAttribute("a11", DataType.varchar(20)));

        EREntity e4 = new EREntity("E4");
        e4.addAttribute(new ERAttribute("a12", DataType.integer()).setPrimaryKey(true));

        EREntity e5 = new EREntity("E5");
        e5.addAttribute(new ERAttribute("a13", DataType.integer()).setPrimaryKey(true));
        e5.addSubEntity("E6");
        e5.addSubEntity("E7");
        EREntity e6 = new EREntity("E6").setSuperEntity("E5");
        e6.addAttribute(new ERAttribute("a14", DataType.integer()));
        EREntity e7 = new EREntity("E7").setSuperEntity("E5");
        e7.addAttribute(new ERAttribute("a15", DataType.varchar(20)));

        EREntity e8 = new EREntity("E8");
        e8.addAttribute(new ERAttribute("a16", DataType.integer()).setPrimaryKey(true));
        e8.addAttribute(new ERAttribute("a17", DataType.integer()).setOptional(true));

        diagram.addEntity(e0).addEntity(e1).addEntity(e2).addEntity(e3).addEntity(e4).addEntity(e5)
                .addEntity(e6).addEntity(e7).addEntity(e8);

        ERRelationship r0 = new ERRelationship("R0");
        r0.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.TOTAL));
        r0.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.PARTIAL));
        r0.addAttribute(new ERAttribute("a18", DataType.integer()));
        diagram.addRelationship(r0);

        ERRelationship r1 = new ERRelationship("R1");
        r1.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.PARTIAL));
        r1.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.TOTAL));
        r1.addAttribute(new ERAttribute("a19", DataType.varchar(20)).setOptional(true));
        diagram.addRelationship(r1);

        diagram.addRelationship(identifying("R2", "E0", "E3"));

        ERRelationship r3 = new ERRelationship("R3");
        r3.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        r3.addParticipant(new ERParticipant("E4").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        r3.addAttribute(new ERAttribute("a20", DataType.integer()));
        diagram.addRelationship(r3);

        ERRelationship r4 = new ERRelationship("R4");
        r4.addParticipant(new ERParticipant("E8", "r4_src").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.PARTIAL));
        r4.addParticipant(new ERParticipant("E8", "r4_dst").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        diagram.addRelationship(r4);

        ERRelationship r5 = new ERRelationship("R5");
        r5.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.PARTIAL));
        r5.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        r5.addParticipant(new ERParticipant("E4").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        r5.addAttribute(new ERAttribute("a21", DataType.integer()));
        diagram.addRelationship(r5);

        ERRelationship r6 = new ERRelationship("R6").setGeneralization(true);
        r6.addParticipant(new ERParticipant("E5"));
        r6.addParticipant(new ERParticipant("E6"));
        diagram.addRelationship(r6);
        ERRelationship r7 = new ERRelationship("R7").setGeneralization(true);
        r7.addParticipant(new ERParticipant("E5"));
        r7.addParticipant(new ERParticipant("E7"));
        diagram.addRelationship(r7);
        return diagram;
    }

    /* ================================================================== harness */

    private static final class Mapped {
        final RelationalSchema schema;
        final MappingTrace trace;

        Mapped(RelationalSchema schema, MappingTrace trace) {
            this.schema = schema;
            this.trace = trace;
        }
    }

    private static Mapped runOne(Checker checker, String label, ERDiagram diagram, MappingOptions options,
            long seed) {
        Randomly.setSeed(seed);
        MappingTrace trace = new MappingTrace();
        RelationalSchema schema;
        try {
            schema = new StandardERToRelationalMapper().map(diagram, options, "s", trace);
        } catch (Throwable t) {
            checker.fail(label + " [" + options.describe() + "] the mapper threw " + t);
            return new Mapped(new RelationalSchema("s"), trace);
        }
        checker.mappings++;
        checker.tables += schema.getTables().size();
        checker.views += schema.getViews().size();
        checker.sequences += schema.getSequences().size();
        for (RelationalSchema.ForeignKeyRef reference : schema.getAllForeignKeys()) {
            checker.foreignKeys++;
            if (reference.foreignKey.isSelfReferential()) {
                checker.selfReferential++;
            }
        }
        for (Table table : schema.getTables()) {
            if (table.getName().startsWith("mv_")) {
                checker.multivaluedTables++;
            } else if (table.getDerivedFromRelationship() != null) {
                checker.joinTables++;
            }
        }
        for (String problem : SchemaInvariants.validate(schema)) {
            checker.fail(label + " [" + options.describe() + "] " + problem);
        }
        for (Table table : schema.getTables()) {
            if (table.getMappingStrategy() == null || table.getMappingStrategy().isBlank()) {
                checker.fail(label + " [" + options.describe() + "] table " + table.getName()
                        + " has no mapping strategy");
            }
            if (table.getDerivedFromEntity() == null && table.getDerivedFromRelationship() == null) {
                checker.fail(label + " [" + options.describe() + "] table " + table.getName()
                        + " has no ER provenance");
            }
            if (!"s".equals(table.getSchemaName())) {
                checker.fail(label + " [" + options.describe() + "] table " + table.getName()
                        + " has the wrong schema name " + table.getSchemaName());
            }
        }
        for (Map.Entry<String, Map<String, String>> entry : trace.getAttributeToColumn().entrySet()) {
            String tableName = trace.getEntityToTable().get(entry.getKey());
            Table table = tableName == null ? null : schema.getTable(tableName);
            if (table == null) {
                continue;
            }
            for (Map.Entry<String, String> attribute : entry.getValue().entrySet()) {
                if (!table.hasColumn(attribute.getValue())
                        && !existsInValueTable(schema, table, attribute.getValue())) {
                    checker.fail(label + " [" + options.describe() + "] the trace maps " + entry.getKey() + "."
                            + attribute.getKey() + " to the unknown column " + tableName + "."
                            + attribute.getValue());
                }
            }
        }
        for (Map.Entry<String, Map<String, String>> entry : trace.getRelationshipAttributeToColumn().entrySet()) {
            String tableName = trace.getRelationshipToTable().get(entry.getKey());
            Table table = tableName == null ? null : schema.getTable(tableName);
            if (table == null) {
                continue;
            }
            for (Map.Entry<String, String> attribute : entry.getValue().entrySet()) {
                if (!table.hasColumn(attribute.getValue())) {
                    checker.fail(label + " [" + options.describe() + "] the trace maps the relationship attribute "
                            + entry.getKey() + "." + attribute.getKey() + " to the unknown column " + tableName + "."
                            + attribute.getValue());
                }
            }
        }
        for (EREntity entity : diagram.getEntities()) {
            boolean mustBeMapped = !(options.getIsa() == IsAStrategy.TABLE_PER_CLASS
                    && entity.isGeneralizationSupertype() && entity.getAttributes().isEmpty());
            if (mustBeMapped && !trace.getEntityToTable().containsKey(entity.getName())) {
                checker.fail(label + " [" + options.describe() + "] entity " + entity.getName()
                        + " is not recorded in the mapping trace");
            }
        }
        for (ERRelationship relationship : diagram.getRelationships()) {
            if (!trace.getRelationshipStrategy().containsKey(relationship.getName())) {
                checker.fail(label + " [" + options.describe() + "] relationship " + relationship.getName()
                        + " is not recorded in the mapping trace");
            }
        }
        checkAttributeAccounting(checker, label, diagram, trace, options);
        return new Mapped(schema, trace);
    }

    /**
     * Asserts that every attribute of the diagram is accounted for: it was either stored in the
     * schema or explicitly announced as dropped in the trace's ledger.
     *
     * <p>This is the converse of the trace checks above, and it is the one that was missing: those
     * only verify that an attribute <em>present</em> in the trace points at a real column, so a
     * mapper path that silently forgot an attribute stayed invisible. Requiring an explanation for
     * every attribute turns each silent loss into a failure - which is how the attributes of an
     * identifying relationship, of a merged 1:1 relationship and of every structural link were
     * found to be disappearing.</p>
     */
    private static void checkAttributeAccounting(Checker checker, String label, ERDiagram diagram,
            MappingTrace trace, MappingOptions options) {
        for (EREntity entity : diagram.getEntities()) {
            Map<String, String> stored = trace.getAttributeToColumn().get(entity.getName());
            for (ERAttribute attribute : entity.getAttributes()) {
                if (stored != null && stored.containsKey(attribute.getName())) {
                    continue;
                }
                if (isAnnouncedAsDropped(trace, entity.getName(), attribute)) {
                    continue;
                }
                checker.fail(label + " [" + options.describe() + "] entity attribute " + entity.getName() + "."
                        + attribute.getName() + " is neither stored nor announced as dropped");
            }
        }
        for (ERRelationship relationship : diagram.getRelationships()) {
            Map<String, String> stored = trace.getRelationshipAttributeToColumn().get(relationship.getName());
            for (ERAttribute attribute : relationship.getAttributes()) {
                if (stored != null && stored.containsKey(attribute.getName())) {
                    continue;
                }
                if (isAnnouncedAsDropped(trace, relationship.getName(), attribute)) {
                    continue;
                }
                checker.fail(label + " [" + options.describe() + "] relationship attribute "
                        + relationship.getName() + "." + attribute.getName()
                        + " is neither stored nor announced as dropped");
            }
        }
        // an announced drop must carry a real reason, or the ledger is just a second silence
        for (Map.Entry<String, String> entry : trace.getDroppedAttributes().entrySet()) {
            checker.checks++;
            checker.droppedAttributes++;
            if (entry.getValue() == null || entry.getValue().isBlank()
                    || "no reason given".equals(entry.getValue())) {
                checker.fail(label + " [" + options.describe() + "] the dropped attribute " + entry.getKey()
                        + " is recorded without a reason");
            }
        }
    }

    /**
     * @return true when the attribute - or, for a composite, every one of its components - is
     *         announced as dropped, so a composite whose components are all omitted counts as
     *         accounted for even though it produced no column
     */
    private static boolean isAnnouncedAsDropped(MappingTrace trace, String prefix, ERAttribute attribute) {
        String path = prefix + "." + attribute.getName();
        if (trace.getDroppedAttributes().containsKey(path)) {
            return true;
        }
        if (!attribute.isComposite() || attribute.getComponents().isEmpty()) {
            return false;
        }
        for (ERAttribute component : attribute.getComponents()) {
            if (!isAnnouncedAsDropped(trace, path, component)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return true if {@code column} lives in a table that stores the values of a multi-valued
     *         attribute of the entity stored in {@code owner} (such a table references the owner key)
     */
    private static boolean existsInValueTable(RelationalSchema schema, Table owner, String column) {
        for (Table table : schema.getTables()) {
            if (table == owner || !table.hasColumn(column)) {
                continue;
            }
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                if (foreignKey.getReferencedTable().equals(owner.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasUnique(Table table, List<String> columns) {
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (SchemaInvariants.sameColumns(unique.getColumns(), columns)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(RelationalSchema schema) {
        StringBuilder builder = new StringBuilder();
        for (Table table : schema.getTables()) {
            builder.append("TABLE ").append(table.getName()).append(' ');
            for (Column column : table.getColumns()) {
                builder.append(column.getName()).append(':').append(column.getType()).append(':')
                        .append(column.isNullable()).append(':').append(column.isIdentity()).append(':')
                        .append(column.getGeneratedExpression()).append(' ');
            }
            builder.append(table.getPrimaryKey()).append(' ');
            builder.append(table.getForeignKeys()).append(' ');
            builder.append(table.getUniqueConstraints()).append(' ');
            builder.append(table.getCheckConstraints()).append(' ');
            builder.append(table.getIndexes()).append(' ');
            builder.append(table.getDiscriminatorColumn()).append(table.getDiscriminatorValues()).append('\n');
        }
        for (View view : schema.getViews()) {
            builder.append("VIEW ").append(view.getName()).append(' ').append(view.getQuery()).append('\n');
        }
        for (org.ddlsmith.common.schema.Sequence sequence : schema.getSequences()) {
            builder.append("SEQUENCE ").append(sequence.getName()).append('\n');
        }
        return builder.toString();
    }

    private static final class Checker {
        final List<String> messages = new ArrayList<>();
        int failureCount;
        int failures;
        int mappings;
        int combinations;
        int randomInstances;
        int tables;
        int foreignKeys;
        int selfReferential;
        int joinTables;
        int multivaluedTables;
        int views;
        int sequences;
        int checks;
        /** ER attributes the mapper deliberately did not store, each with a recorded reason. */
        int droppedAttributes;

        void fail(String message) {
            failureCount++;
            failures++;
            if (messages.size() < 60) {
                messages.add(message);
            }
        }

        void check(boolean condition, String message) {
            checks++;
            if (!condition) {
                fail(message);
            }
        }
    }
}
