package org.ddlsmith.mapping;

import org.ddlsmith.Randomly;

/**
 * The mapping decisions used when translating an ER diagram into a relational schema. Every
 * decision corresponds to one of the well-known alternatives of the ER-to-relational algorithm,
 * and DDLSmith randomizes them so that all of them are exercised.
 */
public class MappingOptions {

    /** How a 1:1 relationship is mapped. */
    public enum OneToOneStrategy {
        /** Foreign key on the side with total participation. */
        FK_ON_TOTAL_SIDE,
        /** Foreign key on an arbitrary side. */
        FK_ON_EITHER_SIDE,
        /** Both entity types are merged into a single table. */
        MERGE_TABLES,
        /** A separate join table with two unique foreign keys. */
        JOIN_TABLE
    }

    /** How a 1:N relationship is mapped. */
    public enum OneToManyStrategy {
        /** Foreign key on the "many" side, referencing the "one" side. */
        FK_ON_MANY_SIDE,
        /** Foreign key on the "one" side, made unique. */
        FK_ON_ONE_SIDE_UNIQUE,
        /** A separate join table. */
        JOIN_TABLE
    }

    /** How an M:N relationship is mapped. */
    public enum ManyToManyStrategy {
        /** Join table whose primary key is the pair of foreign keys. */
        JOIN_TABLE,
        /** Join table with a surrogate key and a unique constraint on the foreign keys. */
        JOIN_TABLE_WITH_SURROGATE_KEY
    }

    /** How an n-ary relationship is mapped. */
    public enum NaryStrategy {
        /** Join table whose primary key is the tuple of foreign keys. */
        JOIN_TABLE,
        /** Join table with a surrogate key and a unique constraint on the foreign keys. */
        JOIN_TABLE_WITH_SURROGATE_KEY
    }

    /** How a weak entity is mapped. */
    public enum WeakEntityStrategy {
        /** Own table whose primary key is the owner key plus the partial key. */
        OWN_TABLE_COMPOSITE_PK,
        /** Own table with a surrogate key plus a foreign key to the owner. */
        OWN_TABLE_SURROGATE_PK
    }

    /** How a generalization hierarchy is mapped. */
    public enum IsAStrategy {
        /** One table for the whole hierarchy, with a discriminator column. */
        SINGLE_TABLE,
        /** One table per subclass, each containing all inherited attributes. */
        TABLE_PER_CLASS,
        /** One table per subclass plus one for the superclass, linked by foreign keys. */
        TABLE_PER_SUBCLASS_WITH_FK
    }

    /** How a multi-valued attribute is mapped. */
    public enum MultivaluedStrategy {
        /** A separate table with a foreign key to the owner and the value column. */
        SEPARATE_TABLE,
        /** A separate table with a surrogate key. */
        SEPARATE_TABLE_WITH_SURROGATE_KEY
    }

    /** How a derived attribute is mapped. */
    public enum DerivedStrategy {
        /** Not stored at all. */
        OMIT,
        /** Stored as a plain column. */
        MATERIALIZE,
        /** Stored as a generated column. */
        GENERATED_COLUMN
    }

    /** How primary keys are chosen. */
    public enum KeyStrategy {
        /** The natural candidate key of the entity. */
        NATURAL_KEY,
        /** A generated surrogate key column. */
        SURROGATE_KEY,
        /** A surrogate key plus a unique constraint on the natural key. */
        SURROGATE_KEY_WITH_NATURAL_UNIQUE
    }

    /** How referential actions are chosen. */
    public enum ReferentialActionStrategy {
        /** Always NO ACTION. */
        NONE,
        /** Randomly chosen actions, including CASCADE and SET NULL. */
        RANDOM
    }

    private OneToOneStrategy oneToOne = OneToOneStrategy.FK_ON_TOTAL_SIDE;
    private OneToManyStrategy oneToMany = OneToManyStrategy.FK_ON_MANY_SIDE;
    private ManyToManyStrategy manyToMany = ManyToManyStrategy.JOIN_TABLE;
    private NaryStrategy nary = NaryStrategy.JOIN_TABLE;
    private WeakEntityStrategy weakEntity = WeakEntityStrategy.OWN_TABLE_COMPOSITE_PK;
    private IsAStrategy isa = IsAStrategy.SINGLE_TABLE;
    private MultivaluedStrategy multivalued = MultivaluedStrategy.SEPARATE_TABLE;
    private DerivedStrategy derived = DerivedStrategy.OMIT;
    private KeyStrategy keyStrategy = KeyStrategy.NATURAL_KEY;
    private ReferentialActionStrategy referentialActionStrategy = ReferentialActionStrategy.RANDOM;

    private boolean generateIndexes = true;
    private boolean generateViews = true;
    private boolean generateCheckConstraints = true;
    private boolean generateUniqueConstraints = true;
    private boolean useNamedConstraints = true;
    private boolean deferrableForeignKeys;
    private boolean useIdentityColumns;

    /** Picks a random combination of mapping decisions. */
    public static MappingOptions random() {
        MappingOptions options = new MappingOptions();
        options.oneToOne = Randomly.fromOptions(OneToOneStrategy.values());
        options.oneToMany = Randomly.fromOptions(OneToManyStrategy.FK_ON_MANY_SIDE, OneToManyStrategy.FK_ON_MANY_SIDE,
                OneToManyStrategy.FK_ON_ONE_SIDE_UNIQUE, OneToManyStrategy.JOIN_TABLE);
        options.manyToMany = Randomly.fromOptions(ManyToManyStrategy.values());
        options.nary = Randomly.fromOptions(NaryStrategy.values());
        options.weakEntity = Randomly.fromOptions(WeakEntityStrategy.values());
        options.isa = Randomly.fromOptions(IsAStrategy.values());
        options.multivalued = Randomly.fromOptions(MultivaluedStrategy.values());
        options.derived = Randomly.fromOptions(DerivedStrategy.values());
        options.keyStrategy = Randomly.fromOptions(KeyStrategy.values());
        options.referentialActionStrategy = Randomly.fromOptions(ReferentialActionStrategy.values());
        options.generateIndexes = Randomly.getBoolean(0.8);
        options.generateViews = Randomly.getBoolean(0.4);
        options.generateCheckConstraints = Randomly.getBoolean(0.8);
        options.generateUniqueConstraints = Randomly.getBoolean(0.7);
        options.useNamedConstraints = Randomly.getBoolean(0.8);
        options.deferrableForeignKeys = Randomly.getBoolean(0.2);
        options.useIdentityColumns = Randomly.getBoolean(0.4);
        return options;
    }

    public OneToOneStrategy getOneToOne() {
        return oneToOne;
    }

    public MappingOptions setOneToOne(OneToOneStrategy oneToOne) {
        this.oneToOne = oneToOne;
        return this;
    }

    public OneToManyStrategy getOneToMany() {
        return oneToMany;
    }

    public MappingOptions setOneToMany(OneToManyStrategy oneToMany) {
        this.oneToMany = oneToMany;
        return this;
    }

    public ManyToManyStrategy getManyToMany() {
        return manyToMany;
    }

    public MappingOptions setManyToMany(ManyToManyStrategy manyToMany) {
        this.manyToMany = manyToMany;
        return this;
    }

    public NaryStrategy getNary() {
        return nary;
    }

    public MappingOptions setNary(NaryStrategy nary) {
        this.nary = nary;
        return this;
    }

    public WeakEntityStrategy getWeakEntity() {
        return weakEntity;
    }

    public MappingOptions setWeakEntity(WeakEntityStrategy weakEntity) {
        this.weakEntity = weakEntity;
        return this;
    }

    public IsAStrategy getIsa() {
        return isa;
    }

    public MappingOptions setIsa(IsAStrategy isa) {
        this.isa = isa;
        return this;
    }

    public MultivaluedStrategy getMultivalued() {
        return multivalued;
    }

    public MappingOptions setMultivalued(MultivaluedStrategy multivalued) {
        this.multivalued = multivalued;
        return this;
    }

    public DerivedStrategy getDerived() {
        return derived;
    }

    public MappingOptions setDerived(DerivedStrategy derived) {
        this.derived = derived;
        return this;
    }

    public KeyStrategy getKeyStrategy() {
        return keyStrategy;
    }

    public MappingOptions setKeyStrategy(KeyStrategy keyStrategy) {
        this.keyStrategy = keyStrategy;
        return this;
    }

    public ReferentialActionStrategy getReferentialActionStrategy() {
        return referentialActionStrategy;
    }

    public MappingOptions setReferentialActionStrategy(ReferentialActionStrategy referentialActionStrategy) {
        this.referentialActionStrategy = referentialActionStrategy;
        return this;
    }

    public boolean isGenerateIndexes() {
        return generateIndexes;
    }

    public MappingOptions setGenerateIndexes(boolean generateIndexes) {
        this.generateIndexes = generateIndexes;
        return this;
    }

    public boolean isGenerateViews() {
        return generateViews;
    }

    public MappingOptions setGenerateViews(boolean generateViews) {
        this.generateViews = generateViews;
        return this;
    }

    public boolean isGenerateCheckConstraints() {
        return generateCheckConstraints;
    }

    public MappingOptions setGenerateCheckConstraints(boolean generateCheckConstraints) {
        this.generateCheckConstraints = generateCheckConstraints;
        return this;
    }

    public boolean isGenerateUniqueConstraints() {
        return generateUniqueConstraints;
    }

    public MappingOptions setGenerateUniqueConstraints(boolean generateUniqueConstraints) {
        this.generateUniqueConstraints = generateUniqueConstraints;
        return this;
    }

    public boolean isUseNamedConstraints() {
        return useNamedConstraints;
    }

    public MappingOptions setUseNamedConstraints(boolean useNamedConstraints) {
        this.useNamedConstraints = useNamedConstraints;
        return this;
    }

    public boolean isDeferrableForeignKeys() {
        return deferrableForeignKeys;
    }

    public MappingOptions setDeferrableForeignKeys(boolean deferrableForeignKeys) {
        this.deferrableForeignKeys = deferrableForeignKeys;
        return this;
    }

    public boolean isUseIdentityColumns() {
        return useIdentityColumns;
    }

    public MappingOptions setUseIdentityColumns(boolean useIdentityColumns) {
        this.useIdentityColumns = useIdentityColumns;
        return this;
    }

    /** @return a one-line summary of the chosen decisions, printed in the run header */
    public String describe() {
        return "1:1=" + oneToOne + " 1:N=" + oneToMany + " M:N=" + manyToMany + " n-ary=" + nary + " weak="
                + weakEntity + " isa=" + isa + " multivalued=" + multivalued + " derived=" + derived + " keys="
                + keyStrategy + " refActions=" + referentialActionStrategy;
    }
}
