import java.util.List;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ermodel.Cardinality;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;

/**
 * Hand-built ER diagrams, one per construct the graph pipeline cannot produce, pushed through the
 * ER-to-relational mapper: does the mapper handle the construct, or does it fall back and say so?
 */
public final class ErConstructProbe {

    public static void main(String[] args) {
        unaryRelationship();
        fourAryRelationship();
        multivaluedRelationshipAttribute();
        compositeRelationshipAttribute();
        derivedRelationshipAttribute();
        categoryWithThreeSuperclasses();
        subtypeWithoutOwnAttributes();
        aggregationOverOneToMany();
        nestedAggregation();
        severalCandidateKeys();
    }

    /* ------------------------------------------------------------------ helpers */

    static EREntity entity(ERDiagram diagram, String name, String... keys) {
        EREntity entity = new EREntity(name);
        for (String key : keys) {
            entity.addAttribute(new ERAttribute(key, DataType.integer()).setPrimaryKey(true));
        }
        diagram.addEntity(entity);
        return entity;
    }

    static ERAttribute plain(String name, DataType type) {
        return new ERAttribute(name, type);
    }

    static void run(String title, String question, ERDiagram diagram, MappingOptions options) {
        System.out.println("======================================================================");
        System.out.println(title);
        System.out.println("  question: " + question);
        MappingTrace trace = new MappingTrace();
        try {
            Randomly.setSeed(7);
            RelationalSchema schema = new StandardERToRelationalMapper().map(diagram, options, "s", trace);
            System.out.println("  tables:");
            for (Table table : schema.getTables()) {
                StringBuilder columns = new StringBuilder();
                for (Column column : table.getColumns()) {
                    if (columns.length() > 0) {
                        columns.append(", ");
                    }
                    columns.append(column.getName());
                }
                System.out.println("    " + table.getName() + " [" + table.getMappingStrategy() + "] (" + columns + ")"
                        + " pk=" + (table.getPrimaryKey() == null ? "-" : table.getPrimaryKey().getColumns())
                        + " fks=" + table.getForeignKeys().size()
                        + " checks=" + table.getCheckConstraints().size());
            }
            System.out.println("  notes:");
            for (String note : trace.getNotes()) {
                System.out.println("    - " + note);
            }
        } catch (RuntimeException e) {
            System.out.println("  THREW: " + e);
        }
        System.out.println();
    }

    /* ------------------------------------------------------------------ cases */

    /** A unary relationship: one participant, degree 1. The graph mapper never builds one. */
    static void unaryRelationship() {
        ERDiagram diagram = new ERDiagram().setName("unary");
        entity(diagram, "E0", "id");
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0", "E0_self").setCardinality(Cardinality.ONE)
                .setParticipation(Participation.PARTIAL));
        diagram.addRelationship(relationship);
        run("CASE 1  unary relationship (degree 1)", "mapped as a self-referencing FK?", diagram,
                new MappingOptions());
    }

    /** A 4-ary relationship. The graph mapper only ever synthesizes ternary relationships. */
    static void fourAryRelationship() {
        ERDiagram diagram = new ERDiagram().setName("four-ary");
        for (int i = 0; i < 4; i++) {
            entity(diagram, "E" + i, "id");
        }
        ERRelationship relationship = new ERRelationship("R0");
        for (int i = 0; i < 4; i++) {
            relationship.addParticipant(new ERParticipant("E" + i).setCardinality(Cardinality.MANY)
                    .setParticipation(Participation.PARTIAL));
        }
        diagram.addRelationship(relationship);
        run("CASE 2  4-ary relationship", "join table with four foreign keys?", diagram, new MappingOptions());
    }

    /** A multi-valued attribute of a relationship. The mapper documents that it drops it. */
    static void multivaluedRelationshipAttribute() {
        ERDiagram diagram = new ERDiagram().setName("mv-rel-attr");
        entity(diagram, "E0", "id");
        entity(diagram, "E1", "id");
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.MANY));
        relationship.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY));
        relationship.addAttribute(plain("tags", DataType.varchar(20)).setMultivalued(true).setOptional(true));
        diagram.addRelationship(relationship);
        run("CASE 3  multi-valued attribute of a relationship",
                "does it get its own table keyed by the participants?", diagram, new MappingOptions());
    }

    /** A composite attribute of a relationship. */
    static void compositeRelationshipAttribute() {
        ERDiagram diagram = new ERDiagram().setName("comp-rel-attr");
        entity(diagram, "E0", "id");
        entity(diagram, "E1", "id");
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.MANY));
        relationship.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY));
        ERAttribute composite = new ERAttribute("period", DataType.date()).setComposite(true);
        composite.addComponent(plain("from_date", DataType.date()));
        composite.addComponent(plain("to_date", DataType.date()));
        relationship.addAttribute(composite);
        diagram.addRelationship(relationship);
        run("CASE 4  composite attribute of a relationship", "flattened into the join table?", diagram,
                new MappingOptions());
    }

    /** A derived attribute of a relationship. */
    static void derivedRelationshipAttribute() {
        ERDiagram diagram = new ERDiagram().setName("derived-rel-attr");
        entity(diagram, "E0", "id");
        entity(diagram, "E1", "id");
        ERRelationship relationship = new ERRelationship("R0");
        relationship.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.MANY));
        relationship.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY));
        relationship.addAttribute(plain("duration", DataType.integer()).setDerived(true)
                .setDerivedExpression("to_date - from_date"));
        diagram.addRelationship(relationship);
        MappingOptions options = new MappingOptions();
        options.setDerived(MappingOptions.DerivedStrategy.GENERATED_COLUMN);
        run("CASE 5  derived attribute of a relationship", "materialized as a generated column?", diagram, options);
    }

    /** A category over three superclasses. The graph mapper only ever builds two-superclass ones. */
    static void categoryWithThreeSuperclasses() {
        ERDiagram diagram = new ERDiagram().setName("category-3");
        for (int i = 0; i < 3; i++) {
            entity(diagram, "E" + i, "id");
        }
        EREntity category = entity(diagram, "C", "id");
        category.addCategorySuper("E0");
        category.addCategorySuper("E1");
        category.addCategorySuper("E2");
        run("CASE 6  category (union type) with three superclasses",
                "three nullable FKs plus the at-least-one CHECK?", diagram, new MappingOptions());
    }

    /** A subtype that adds no attribute of its own (a pure subset subtype). */
    static void subtypeWithoutOwnAttributes() {
        ERDiagram diagram = new ERDiagram().setName("pure-subset");
        EREntity supertype = entity(diagram, "E0", "id");
        supertype.addAttribute(plain("a", DataType.varchar(20)));
        EREntity subtype = new EREntity("E1");
        subtype.addAttribute(new ERAttribute("id", DataType.integer()).setPrimaryKey(true));
        subtype.addAttribute(plain("a", DataType.varchar(20)));
        subtype.addSuperEntity("E0");
        supertype.addSubEntity("E1");
        supertype.setDisjoint(true);
        supertype.setTotalSpecialization(false);
        supertype.setDiscriminatorAttribute("a");
        diagram.addEntity(subtype);
        run("CASE 7  subtype that adds no attribute of its own", "mapped like any other subtype?", diagram,
                new MappingOptions());
    }

    /** An aggregation drawn around a 1:N relationship instead of an M:N one. */
    static void aggregationOverOneToMany() {
        ERDiagram diagram = new ERDiagram().setName("agg-1n");
        entity(diagram, "E0", "id");
        entity(diagram, "E1", "id");
        entity(diagram, "E2", "id");
        ERRelationship inner = new ERRelationship("R0");
        inner.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.ONE));
        inner.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY));
        diagram.addRelationship(inner);
        EREntity aggregate = new EREntity("Agg_R0").setAggregatedRelationship("R0");
        diagram.addEntity(aggregate);
        ERRelationship outer = new ERRelationship("Ragg_R0");
        outer.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.MANY));
        outer.addParticipant(new ERParticipant("Agg_R0").setCardinality(Cardinality.MANY));
        diagram.addRelationship(outer);
        run("CASE 8  aggregation over a 1:N relationship", "does the aggregation still get a table?", diagram,
                new MappingOptions());
    }

    /** An aggregation of an aggregation: an aggregation entity that another relationship aggregates. */
    static void nestedAggregation() {
        ERDiagram diagram = new ERDiagram().setName("nested-agg");
        entity(diagram, "E0", "id");
        entity(diagram, "E1", "id");
        entity(diagram, "E2", "id");
        ERRelationship inner = new ERRelationship("R0");
        inner.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.MANY));
        inner.addParticipant(new ERParticipant("E1").setCardinality(Cardinality.MANY));
        diagram.addRelationship(inner);
        EREntity aggregate = new EREntity("Agg_R0").setAggregatedRelationship("R0");
        diagram.addEntity(aggregate);
        ERRelationship outer = new ERRelationship("Ragg_R0");
        outer.addParticipant(new ERParticipant("E2").setCardinality(Cardinality.MANY));
        outer.addParticipant(new ERParticipant("Agg_R0").setCardinality(Cardinality.MANY));
        diagram.addRelationship(outer);
        // a second-level aggregation that treats the outer relationship as its inner one
        EREntity nested = new EREntity("Agg_Ragg_R0").setAggregatedRelationship("Ragg_R0");
        diagram.addEntity(nested);
        ERRelationship top = new ERRelationship("Ragg2");
        top.addParticipant(new ERParticipant("E0").setCardinality(Cardinality.MANY));
        top.addParticipant(new ERParticipant("Agg_Ragg_R0").setCardinality(Cardinality.MANY));
        diagram.addRelationship(top);
        run("CASE 9  aggregation of an aggregation", "does the second level resolve?", diagram, new MappingOptions());
    }

    /** Two candidate keys on one entity: the model can only flag one primary key. */
    static void severalCandidateKeys() {
        ERDiagram diagram = new ERDiagram().setName("two-keys");
        EREntity entity = new EREntity("E0");
        entity.addAttribute(new ERAttribute("code", DataType.varchar(20)).setPrimaryKey(true));
        entity.addAttribute(new ERAttribute("name", DataType.varchar(20)).setPrimaryKey(true));
        entity.addAttribute(new ERAttribute("ssn", DataType.varchar(20)));
        diagram.addEntity(entity);
        System.out.println("======================================================================");
        System.out.println("CASE 10  two candidate keys on one entity");
        System.out.println("  model check: ERAttribute exposes only isPrimaryKey(); there is no way to say");
        System.out.println("  'code is the primary key and name is an alternate unique key'. Marking both makes");
        System.out.println("  them ONE composite key, which is a different schema:");
        run("CASE 10  two candidate keys on one entity", "are they one composite key or two unique keys?", diagram,
                new MappingOptions());
    }
}
