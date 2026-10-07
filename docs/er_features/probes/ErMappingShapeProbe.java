import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.dbms.duckdb.DuckDbDialect;
import org.ddlsmith.dbms.h2.H2Dialect;
import org.ddlsmith.dbms.sqlite.SqliteDialect;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.ddl.gen.SchemaCreationScript;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingOptions.IsAStrategy;
import org.ddlsmith.mapping.MappingOptions.KeyStrategy;
import org.ddlsmith.mapping.MappingOptions.OneToOneStrategy;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.mapping.impl.SchemaInvariants;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;

/**
 * Prints the relational schema the mapper produces for hand-built ER diagrams that the random
 * pipeline cannot (or rarely) reach, so that each mapping rule can be read next to its ER input.
 *
 * <p>The scenarios and the questions they answer:</p>
 * <ol>
 *   <li>1:1 {@code MERGE_TABLES} with identity columns — how many identity columns does the merged
 *       table get?</li>
 *   <li>1:1 {@code MERGE_TABLES} where the merged-away table is an ISA subtype table — is the ISA
 *       foreign key still there afterwards?</li>
 *   <li>1:1 {@code MERGE_TABLES} where the merged-away table is a category table — what happens to
 *       the membership foreign keys and the union-membership CHECK?</li>
 *   <li>1:1 {@code MERGE_TABLES} with {@code (0,1)} on both sides — can a row represent an entity
 *       that has no partner?</li>
 *   <li>1:1 between two entities that already share one table (single-table ISA) — is the
 *       relationship represented at all?</li>
 *   <li>weak entity with {@code ownerEntity} but no {@code ownerRelationship} — how often is the
 *       identifying relationship mapped?</li>
 *   <li>category over two superclasses — can the same superclass instance be a member twice?</li>
 *   <li>category whose superclass has a composite key — does the union-membership CHECK require the
 *       whole key?</li>
 *   <li>total specialization under {@code TABLE_PER_SUBCLASS_WITH_FK} — is it expressed, or even
 *       mentioned?</li>
 *   <li>weak entity that is also an ISA subtype — is the owner foreign key created?</li>
 *   <li>{@code TABLE_PER_CLASS} with a shared subclass — does the subtype key cover both
 *       parents?</li>
 *   <li>ternary {@code (1,N,N)} and {@code (1,1,N)} — which participant keys form the join table's
 *       primary key?</li>
 *   <li>aggregation of an M:N relationship — where does the outer relationship store its foreign
 *       key?</li>
 *   <li>multi-valued attribute on a subtype — which table does its value table reference?</li>
 *   <li>recursive 1:1 — where do the foreign key and the unique constraint land?</li>
 *   <li>1:N between a subtype and its supertype in a single table — is the foreign key
 *       self-referencing?</li>
 *   <li>1:1 {@code MERGE_TABLES} with natural keys — does the merged-away entity keep its
 *       uniqueness?</li>
 *   <li>weak entity that is also a category — is the weakness kept, or even mentioned?</li>
 *   <li>category that is also an ISA subtype — is the membership represented, as the trace
 *       claims?</li>
 * </ol>
 *
 * <p>It also renders the merged table of scenario 1 as DDL for H2, SQLite and DuckDB.</p>
 */
public class ErMappingShapeProbe {

    private static final StandardERToRelationalMapper MAPPER = new StandardERToRelationalMapper();

    public static void main(String[] args) {
        mergeWithIdentityColumns();
        mergeSwallowsIsaForeignKey();
        mergeSwallowsCategoryMembership();
        mergeOfAPartialOneToOne();
        oneToOneBetweenEntitiesOfOneTable();
        weakEntityWithoutOwnerRelationship();
        categoryMembershipIsNotUnique();
        categoryWithCompositeSuperclassKey();
        totalSpecializationUnderForeignKeyStrategy();
        weakEntityThatIsAlsoASubtype();
        tablePerClassSharedSubclassKey();
        naryPrimaryKeyRule();
        aggregation();
        multivaluedAttributeOnASubtype();
        recursiveOneToOne();
        relationshipBetweenSubtypeAndSupertype();
        mergeLosesNaturalKeyUniqueness();
        weakEntityThatIsAlsoACategory();
        categoryThatIsAlsoASubtype();
    }

    /* ------------------------------------------------------------------ 1:1 MERGE_TABLES */

    private static void mergeWithIdentityColumns() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "name");
        plain(entity(d, "E2"), "label");
        binary(d, "R1", "E1", Cardinality.ONE, Participation.PARTIAL, "E2", Cardinality.ONE, Participation.PARTIAL);

        MappingOptions options = base()
                .setOneToOne(OneToOneStrategy.MERGE_TABLES)
                .setUseIdentityColumns(true);
        RelationalSchema schema = dump("1. 1:1 MERGE_TABLES with identity columns", d, options);
        System.out.println("  --- the same table as DDL ---");
        for (Dialect dialect : List.of(new H2Dialect(), new SqliteDialect(), new DuckDbDialect())) {
            List<String> notes = new ArrayList<>();
            RelationalSchema adapted = SchemaAdapter.adapt(schema, dialect, notes);
            for (DDLStatement statement : SchemaCreationScript.create(adapted, dialect, null)) {
                System.out.println("  [" + dialect.getName() + "] " + dialect.render(statement).replace('\n', ' '));
            }
            for (String note : notes) {
                System.out.println("  [" + dialect.getName() + "] adapt-note: " + note);
            }
        }
        System.out.println();
    }

    private static void mergeSwallowsIsaForeignKey() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        EREntity subtype = entity(d, "E2");
        plain(subtype, "b");
        isa(d, "E1", "E2");
        plain(entity(d, "E3"), "c");
        binary(d, "R1", "E3", Cardinality.ONE, Participation.PARTIAL, "E2", Cardinality.ONE, Participation.PARTIAL);

        dump("2. 1:1 MERGE_TABLES where E2 is an ISA subtype table (E2 has a foreign key to E1)",
                d, base().setIsa(IsAStrategy.TABLE_PER_SUBCLASS_WITH_FK).setOneToOne(OneToOneStrategy.MERGE_TABLES));
    }

    private static void mergeSwallowsCategoryMembership() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        plain(entity(d, "E2"), "b");
        EREntity category = entity(d, "X");
        plain(category, "note");
        category.addCategorySuper("E1");
        category.addCategorySuper("E2");
        categoryLink(d, "RC1", "X", "E1");
        categoryLink(d, "RC2", "X", "E2");
        plain(entity(d, "E3"), "c");
        binary(d, "R1", "E3", Cardinality.ONE, Participation.PARTIAL, "X", Cardinality.ONE, Participation.PARTIAL);

        dump("3. 1:1 MERGE_TABLES where X is a category table (two membership FKs plus a union CHECK)",
                d, base().setOneToOne(OneToOneStrategy.MERGE_TABLES));
    }

    private static void mergeOfAPartialOneToOne() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        plain(entity(d, "E2"), "b");
        binary(d, "R1", "E1", Cardinality.ONE, Participation.PARTIAL, "E2", Cardinality.ONE, Participation.PARTIAL);

        dump("4. 1:1 MERGE_TABLES with (0,1) on both sides", d,
                base().setOneToOne(OneToOneStrategy.MERGE_TABLES));
    }

    private static void oneToOneBetweenEntitiesOfOneTable() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        plain(entity(d, "E2"), "b");
        plain(entity(d, "E3"), "c");
        isa(d, "E1", "E2");
        isa(d, "E1", "E3");
        binary(d, "R1", "E2", Cardinality.ONE, Participation.PARTIAL, "E3", Cardinality.ONE, Participation.PARTIAL);

        dump("5. 1:1 between two subtypes that share the single table", d,
                base().setIsa(IsAStrategy.SINGLE_TABLE).setOneToOne(OneToOneStrategy.MERGE_TABLES));
    }

    /* ------------------------------------------------------------------ weak entities */

    private static void weakEntityWithoutOwnerRelationship() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        EREntity weak = entity(d, "E2");
        weak.setWeak(true).setOwnerEntity("E1");
        weak.addAttribute(new ERAttribute("k", DataType.integer()).setPartialKey(true));
        binary(d, "R1", "E1", Cardinality.ONE, Participation.TOTAL, "E2", Cardinality.MANY, Participation.TOTAL)
                .setIdentifying(true);

        dump("6. weak entity with ownerEntity set but ownerRelationship NOT set (R1 identifying)", d, base());
    }

    /* ------------------------------------------------------------------ categories */

    private static void categoryMembershipIsNotUnique() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        plain(entity(d, "E2"), "b");
        EREntity category = entity(d, "X");
        plain(category, "note");
        category.addCategorySuper("E1");
        category.addCategorySuper("E2");
        categoryLink(d, "RC1", "X", "E1");
        categoryLink(d, "RC2", "X", "E2");

        dump("7. category X over E1 and E2", d, base());
    }

    private static void categoryWithCompositeSuperclassKey() {
        ERDiagram d = new ERDiagram();
        EREntity e1 = entity(d, "E1");
        key(e1, "a");
        key(e1, "b");
        key(entity(d, "E2"), "x");
        EREntity category = entity(d, "X");
        plain(category, "note");
        category.addCategorySuper("E1");
        category.addCategorySuper("E2");
        categoryLink(d, "RC1", "X", "E1");
        categoryLink(d, "RC2", "X", "E2");

        dump("8. category X whose superclass E1 has the composite key (a,b)", d,
                base().setKeyStrategy(KeyStrategy.NATURAL_KEY));
    }

    /* ------------------------------------------------------------------ generalization */

    private static void totalSpecializationUnderForeignKeyStrategy() {
        ERDiagram d = new ERDiagram();
        EREntity root = entity(d, "E1");
        plain(root, "a");
        root.setTotalSpecialization(true).setDisjoint(true);
        plain(entity(d, "E2"), "b");
        plain(entity(d, "E3"), "c");
        isa(d, "E1", "E2");
        isa(d, "E1", "E3");

        dump("9. total specialization of E1 under TABLE_PER_SUBCLASS_WITH_FK", d,
                base().setIsa(IsAStrategy.TABLE_PER_SUBCLASS_WITH_FK));
    }

    private static void weakEntityThatIsAlsoASubtype() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        EREntity subtype = entity(d, "E2");
        plain(subtype, "b");
        isa(d, "E1", "E2");
        subtype.setWeak(true).setOwnerEntity("E3");
        subtype.addAttribute(new ERAttribute("k", DataType.integer()).setPartialKey(true));
        plain(entity(d, "E3"), "c");

        dump("10. weak entity E2 that is also a subtype of E1 (owner E3)", d,
                base().setIsa(IsAStrategy.TABLE_PER_SUBCLASS_WITH_FK));
    }

    private static void tablePerClassSharedSubclassKey() {
        ERDiagram d = new ERDiagram();
        key(entity(d, "E1"), "a");
        key(entity(d, "E2"), "b");
        plain(entity(d, "E3"), "c");
        isa(d, "E1", "E3");
        isa(d, "E2", "E3");

        dump("11. TABLE_PER_CLASS with the shared subclass E3 of E1 and E2", d,
                base().setIsa(IsAStrategy.TABLE_PER_CLASS).setKeyStrategy(KeyStrategy.NATURAL_KEY));
    }

    /* ------------------------------------------------------------------ relationships */

    private static void naryPrimaryKeyRule() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        plain(entity(d, "E2"), "b");
        plain(entity(d, "E3"), "c");
        ERRelationship r = new ERRelationship("R1");
        r.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.ONE));
        r.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.MANY));
        r.addParticipant(new ERParticipant("E3").setCardinality(Cardinality.MANY));
        d.addRelationship(r);
        dump("12a. ternary (1,N,N)", d, base());

        ERDiagram second = new ERDiagram();
        plain(entity(second, "E1"), "a");
        plain(entity(second, "E2"), "b");
        plain(entity(second, "E3"), "c");
        ERRelationship r2 = new ERRelationship("R1");
        r2.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.ONE));
        r2.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.ONE));
        r2.addParticipant(new ERParticipant("E3").setCardinality(Cardinality.MANY));
        second.addRelationship(r2);
        dump("12b. ternary (1,1,N)", second, base());
    }

    private static void aggregation() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        plain(entity(d, "E2"), "b");
        binary(d, "R1", "E1", Cardinality.MANY, Participation.PARTIAL, "E2", Cardinality.MANY,
                Participation.PARTIAL);
        plain(entity(d, "E3"), "c");
        EREntity aggregate = entity(d, "Agg_R1");
        aggregate.setAggregatedRelationship("R1");
        ERRelationship outer = new ERRelationship("Ragg_R1");
        outer.addParticipant(new ERParticipant("E3").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.TOTAL));
        outer.addParticipant(new ERParticipant("Agg_R1").setCardinality(Cardinality.MANY)
                .setParticipation(Participation.PARTIAL));
        d.addRelationship(outer);

        dump("13. aggregation of the M:N relationship R1", d, base());
    }

    private static void multivaluedAttributeOnASubtype() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        EREntity subtype = entity(d, "E2");
        plain(subtype, "b");
        subtype.addAttribute(new ERAttribute("tags", DataType.varchar(30)).setMultivalued(true));
        isa(d, "E1", "E2");

        dump("14a. multi-valued attribute on a subtype (SINGLE_TABLE)", d,
                base().setIsa(IsAStrategy.SINGLE_TABLE));
        dump("14b. multi-valued attribute on a subtype (TABLE_PER_CLASS)", d,
                base().setIsa(IsAStrategy.TABLE_PER_CLASS));
    }

    private static void recursiveOneToOne() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        ERRelationship r = new ERRelationship("R1");
        r.addParticipant(new ERParticipant("E1", "E1_src").setCardinality(Cardinality.ONE));
        r.addParticipant(new ERParticipant("E1", "E1_dst").setCardinality(Cardinality.ONE));
        d.addRelationship(r);

        dump("15. recursive 1:1", d, base().setGenerateCheckConstraints(false));
    }

    private static void relationshipBetweenSubtypeAndSupertype() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        plain(entity(d, "E2"), "b");
        isa(d, "E1", "E2");
        binary(d, "R1", "E2", Cardinality.MANY, Participation.PARTIAL, "E1", Cardinality.ONE, Participation.PARTIAL);

        dump("16. 1:N between the subtype E2 and the supertype E1 (SINGLE_TABLE)", d, base());
    }

    /* ------------------------------------------------------------------ combinations */

    private static void mergeLosesNaturalKeyUniqueness() {
        ERDiagram d = new ERDiagram();
        key(entity(d, "E1"), "code");
        key(entity(d, "E2"), "number");
        binary(d, "R1", "E1", Cardinality.ONE, Participation.PARTIAL, "E2", Cardinality.ONE, Participation.PARTIAL);

        dump("17. 1:1 MERGE_TABLES with NATURAL_KEY: does E2 keep its uniqueness?", d,
                base().setOneToOne(OneToOneStrategy.MERGE_TABLES).setKeyStrategy(KeyStrategy.NATURAL_KEY));
    }

    private static void weakEntityThatIsAlsoACategory() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        plain(entity(d, "E2"), "b");
        EREntity weak = entity(d, "W");
        plain(weak, "note");
        weak.setWeak(true).setOwnerEntity("E1");
        weak.addAttribute(new ERAttribute("k", DataType.integer()).setPartialKey(true));
        weak.addCategorySuper("E1");
        weak.addCategorySuper("E2");
        categoryLink(d, "RC1", "W", "E1");
        categoryLink(d, "RC2", "W", "E2");

        dump("18. weak entity W that is also a category over E1 and E2", d, base());
    }

    private static void categoryThatIsAlsoASubtype() {
        ERDiagram d = new ERDiagram();
        plain(entity(d, "E1"), "a");
        plain(entity(d, "E2"), "b");
        plain(entity(d, "E4"), "r");
        EREntity category = entity(d, "X");
        plain(category, "note");
        category.addCategorySuper("E1");
        category.addCategorySuper("E2");
        categoryLink(d, "RC1", "X", "E1");
        categoryLink(d, "RC2", "X", "E2");
        isa(d, "E4", "X");

        dump("19. category X that is also a subtype of E4 (the trace claims the membership is represented)",
                d, base().setIsa(IsAStrategy.SINGLE_TABLE));
    }

    /* ------------------------------------------------------------------ helpers */

    private static MappingOptions base() {
        return new MappingOptions()
                .setKeyStrategy(KeyStrategy.SURROGATE_KEY)
                .setGenerateIndexes(false)
                .setGenerateViews(false)
                .setGenerateUniqueConstraints(false);
    }

    private static EREntity entity(ERDiagram diagram, String name) {
        EREntity entity = new EREntity(name);
        diagram.addEntity(entity);
        return entity;
    }

    private static void plain(EREntity entity, String name) {
        entity.addAttribute(new ERAttribute(name, DataType.varchar(30)));
    }

    private static void key(EREntity entity, String name) {
        entity.addAttribute(new ERAttribute(name, DataType.integer()).setPrimaryKey(true));
    }

    private static ERRelationship binary(ERDiagram diagram, String name, String left, Cardinality leftCardinality,
            Participation leftParticipation, String right, Cardinality rightCardinality,
            Participation rightParticipation) {
        ERRelationship relationship = new ERRelationship(name);
        relationship.addParticipant(new ERParticipant(left).setCardinality(leftCardinality)
                .setParticipation(leftParticipation));
        relationship.addParticipant(new ERParticipant(right).setCardinality(rightCardinality)
                .setParticipation(rightParticipation));
        diagram.addRelationship(relationship);
        return relationship;
    }

    private static void isa(ERDiagram diagram, String parent, String child) {
        ERRelationship relationship = new ERRelationship("ISA_" + parent + "_" + child);
        relationship.setGeneralization(true);
        relationship.addParticipant(new ERParticipant(parent).setCardinality(Cardinality.ONE)
                .setParticipation(Participation.PARTIAL));
        relationship.addParticipant(new ERParticipant(child).setCardinality(Cardinality.ONE)
                .setParticipation(Participation.TOTAL));
        diagram.addRelationship(relationship);
        diagram.getEntity(child).addSuperEntity(parent);
        diagram.getEntity(parent).addSubEntity(child);
    }

    private static void categoryLink(ERDiagram diagram, String name, String category, String superclass) {
        ERRelationship relationship = new ERRelationship(name);
        relationship.setCategoryLink(true);
        relationship.addParticipant(new ERParticipant(category).setCardinality(Cardinality.ONE));
        relationship.addParticipant(new ERParticipant(superclass).setCardinality(Cardinality.ONE));
        diagram.addRelationship(relationship);
    }

    private static RelationalSchema dump(String label, ERDiagram diagram, MappingOptions options) {
        MappingTrace trace = new MappingTrace();
        RelationalSchema schema = MAPPER.map(diagram, options, "s", trace);
        System.out.println("================ " + label);
        System.out.println("  ER: " + diagram);
        for (Table table : schema.getTables()) {
            System.out.println("  TABLE " + table.getName()
                    + "  strategy=" + table.getMappingStrategy()
                    + " entity=" + table.getDerivedFromEntity()
                    + " rel=" + table.getDerivedFromRelationship()
                    + (table.getDiscriminatorColumn() == null ? "" : " discriminator=" + table.getDiscriminatorColumn()));
            for (Column column : table.getColumns()) {
                System.out.println("     col " + column.getName() + " " + column.getType().getName()
                        + (column.isNullable() ? "" : " NOT NULL")
                        + (column.isIdentity() ? " IDENTITY" : "")
                        + (column.isGenerated() ? " GENERATED(" + column.getGeneratedExpression() + ")" : "")
                        + (column.getDerivedFrom() == null ? "" : " derivedFrom=" + column.getDerivedFrom()));
            }
            if (table.getPrimaryKey() != null) {
                System.out.println("     PK " + table.getPrimaryKey().getColumns());
            }
            for (UniqueConstraint unique : table.getUniqueConstraints()) {
                System.out.println("     UNIQUE " + unique.getColumns());
            }
            for (CheckConstraint check : table.getCheckConstraints()) {
                System.out.println("     CHECK " + check.getExpression());
            }
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                System.out.println("     FK " + foreignKey.getColumns() + " -> " + foreignKey.getReferencedTable()
                        + foreignKey.getReferencedColumns());
            }
        }
        for (String note : trace.getNotes()) {
            System.out.println("  note: " + note);
        }
        for (Map.Entry<String, String> entry : trace.getDroppedAttributes().entrySet()) {
            System.out.println("  dropped: " + entry.getKey());
        }
        for (String violation : SchemaInvariants.validate(schema)) {
            System.out.println("  INVARIANT VIOLATION: " + violation);
        }
        System.out.println();
        return schema;
    }

    private ErMappingShapeProbe() {
    }
}
