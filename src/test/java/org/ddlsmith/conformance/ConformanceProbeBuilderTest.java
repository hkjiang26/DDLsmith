package org.ddlsmith.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.conformance.ConformancePlan.PlannedRow;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.dbms.sqlite.SqliteDialect;
import org.ddlsmith.dbms.sqlite.SqliteIntrospector;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the two things a negative probe has to get right before it may say anything about an engine:
 * the value it invents has to fit the column and be absent from the parent table, and the shape it
 * builds has to be one the engine <em>can</em> be shown to skip the check with — otherwise a refusal is
 * read as enforcement and an acceptance as a defect, both wrongly.
 */
class ConformanceProbeBuilderTest {

    /* ------------------------------------------------------------------ values */

    @Test
    @DisplayName("a dangling value fits its column and is absent from the parent table")
    void danglingValueFitsTheColumnAndAvoidsStoredKeys() {
        Column smallint = new Column("k", DataType.smallint());
        Object next = ConformanceProbeBuilder.danglingValue(smallint, List.of(1, 2, 3));
        assertNotNull(next);
        assertTrue(((Number) next).longValue() > 3, "expected a value beyond the stored keys, got " + next);

        // the top of the range is taken: the value has to come from somewhere else inside it
        Object other = ConformanceProbeBuilder.danglingValue(smallint, List.of(32767));
        assertNotNull(other);
        long asLong = ((Number) other).longValue();
        assertTrue(asLong >= -32768 && asLong <= 32767, "out of range for SMALLINT: " + asLong);
        assertTrue(asLong != 32767, "the value the parent already holds was reused");

        // a textual key is probeable too: the candidate has to fit the column's length and differ from
        // everything the parent holds
        Object text = ConformanceProbeBuilder.danglingValue(new Column("t", DataType.varchar(20)), List.of("a"));
        assertNotNull(text, "a textual key column has a fitted dangling value");
        assertFalse("a".equals(text), "the value the parent already holds was reused");
        assertTrue(String.valueOf(text).length() <= 20, "the candidate does not fit the column: " + text);

        Object day = ConformanceProbeBuilder.danglingValue(new Column("d", DataType.date()),
                List.of(java.sql.Date.valueOf("2024-08-22")));
        assertNotNull(day);
        assertEquals("2024-08-23", DataModel.canonical(day, DataType.date()));

        // a value the row itself will carry must not be chosen: for a self-referencing key the row becomes
        // its own parent, so the engine would be right to accept it (measured on SQLite seed 2)
        Object selfExcluded = ConformanceProbeBuilder.danglingValue(smallint, List.of(1, 2, 3), List.of(4));
        assertNotNull(selfExcluded);
        assertTrue(((Number) selfExcluded).longValue() != 4L,
                "the value the probe's own row will store was chosen: " + selfExcluded);
    }

    /* ------------------------------------------------------------------ shapes */

    @Test
    @DisplayName("the NULL-beside-the-key shape needs a longer index and no shorter one")
    void nullNeighbourShapeNeedsALongerIndexAndNoShorterOne() {
        Table longer = child("c", new Column("f1", DataType.bigint()), new Column("other", DataType.bigint(), true));
        longer.addIndex(new Index("i", List.of("f1", "other")));
        ConformanceProbeBuilder.IndexChoice choice =
                ConformanceProbeBuilder.nullNeighbourShape(longer, foreignKey("f1"));
        assertNotNull(choice, "an index longer than the key with a nullable extra column is the shape");
        assertEquals("other", choice.column);

        Table shorter = child("c", new Column("f1", DataType.bigint()), new Column("other", DataType.bigint(), true));
        shorter.addIndex(new Index("i", List.of("f1")));
        assertNull(ConformanceProbeBuilder.nullNeighbourShape(shorter, foreignKey("f1")),
                "an index that is exactly the key makes the engine enforce it (measured on 26.7.0)");

        Table both = child("c", new Column("f1", DataType.bigint()), new Column("other", DataType.bigint(), true));
        both.addIndex(new Index("i1", List.of("f1")));
        both.addIndex(new Index("i2", List.of("f1", "other")));
        assertNull(ConformanceProbeBuilder.nullNeighbourShape(both, foreignKey("f1")),
                "measured: with both indexes present 26.7.0 refuses the row, so the shape shows nothing");

        Table notNullable =
                child("c", new Column("f1", DataType.bigint()), new Column("other", DataType.bigint(), false));
        notNullable.addIndex(new Index("i", List.of("f1", "other")));
        assertNull(ConformanceProbeBuilder.nullNeighbourShape(notNullable, foreignKey("f1")),
                "the extra column has to be nullable for a NULL to be possible at all");
    }

    @Test
    @DisplayName("a one-to-one parent is recognised by a key that is exactly the foreign key's columns")
    void oneToOneIsAKeyOverTheKeyColumns() {
        Table unique = child("c", new Column("f1", DataType.bigint()), new Column("other", DataType.bigint(), true));
        unique.addUniqueConstraint(new UniqueConstraint("uq", List.of("f1")));
        assertTrue(ConformanceProbeBuilder.isOneToOne(unique, foreignKey("f1")));

        Table key = child("c", new Column("f1", DataType.bigint()));
        assertTrue(ConformanceProbeBuilder.isOneToOne(key, foreignKey("f1")), "the primary key counts as a key");

        Table none = child("c", new Column("f1", DataType.bigint()), new Column("id", DataType.bigint()));
        assertFalse(ConformanceProbeBuilder.isOneToOne(none, foreignKey("f1")));
    }

    /* ------------------------------------------------------------------ classification */

    @Test
    @DisplayName("a refusal is classified by what it means, not by its SQLSTATE class alone")
    void classifiesARefusalByWhatItMeans() {
        assertTrue(ConformanceProbeBuilder.isIntegrityRejection(event("23000", 1452)),
                "a foreign key violation is an integrity refusal");
        assertTrue(ConformanceProbeBuilder.isIntegrityRejection(event("23000", 1062)),
                "a duplicate key is an integrity refusal");
        assertTrue(ConformanceProbeBuilder.isIntegrityRejection(event("HY000", 3819)),
                "measured on 26.7.0: a CHECK violation reports HY000 with code 3819");
        assertFalse(ConformanceProbeBuilder.isIntegrityRejection(event("22003", 1264)),
                "out of range is the probe's own row being invalid, not a constraint being enforced");
        assertFalse(ConformanceProbeBuilder.isIntegrityRejection(event("42000", 1054)),
                "an unknown column says nothing about a constraint");
        assertTrue(ConformanceProbeBuilder.isDataException(event("22003", 1264)));
        assertTrue(ConformanceProbeBuilder.isDataException(event("22001", 1406)));
        assertFalse(ConformanceProbeBuilder.isDataException(event("23000", 1062)));
    }

    /* ------------------------------------------------------------------ on a generated database */

    @Test
    @DisplayName("probes are built for a generated database and every one of them is specific")
    void buildsProbesForAGeneratedDatabase() throws Exception {
        int built = 0;
        int dangling = 0;
        for (long seed : List.of(1L, 2L, 3L, 4L, 5L)) {
            try (ConformanceTestSupport.Session session = sqlite(seed)) {
                RelationalSchema schema = session.input.adapted;
                ConformancePlan plan = new ConformancePlanner(session.input.graph, session.input.erDiagram, schema,
                        session.input.trace, session.database.getDialect()).plan();
                ConformancePopulator.Result population = ConformancePopulator.populate(session.context,
                        session.database, plan, schema);
                List<String> notes = new ArrayList<>();
                List<ConformanceProbe> probes = ConformanceProbeBuilder.build(session.context, session.database,
                        schema, plan, population, notes);
                for (ConformanceProbe probe : probes) {
                    built++;
                    assertNotNull(probe.getKind());
                    assertNotNull(probe.getGroup());
                    assertTrue(probe.getSql().startsWith("INSERT") || probe.getSql().startsWith("UPDATE"),
                            "a probe is an INSERT or an UPDATE, not: " + probe.getSql());
                    assertFalse(probe.getCleanupSql().isBlank(), "every probe has to be undone again");
                    if (probe.getKind() == ConformanceProbe.Kind.MUST_ACCEPT) {
                        assertTrue(probe.getGroup().contains("partially NULL"),
                                "the only row the standard allows and the schema's keys would refuse is the"
                                        + " partially NULL reference, not: " + probe.getGroup());
                    }
                    if (probe.getGroup().contains("a reference with no parent")) {
                        dangling++;
                    }
                }
            }
        }
        assertTrue(built > 0, "no probe was built for five generated databases");
        assertTrue(dangling > 0, "no dangling-reference probe was built for five generated databases");
    }

    @Test
    @DisplayName("most usable foreign keys get a dangling-reference probe")
    void mostUsableForeignKeysGetAProbe() throws Exception {
        int usableForeignKeys = 0;
        int probesBuilt = 0;
        StringBuilder skipped = new StringBuilder();
        for (long seed : List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)) {
            try (ConformanceTestSupport.Session session = sqlite(seed)) {
                RelationalSchema schema = session.input.adapted;
                ConformancePlan plan = new ConformancePlanner(session.input.graph, session.input.erDiagram, schema,
                        session.input.trace, session.database.getDialect()).plan();
                ConformancePopulator.Result population = ConformancePopulator.populate(session.context,
                        session.database, plan, schema);
                List<String> notes = new ArrayList<>();
                List<ConformanceProbe> probes = ConformanceProbeBuilder.build(session.context, session.database,
                        schema, plan, population, notes);
                for (RelationalSchema.ForeignKeyRef ref : schema.getAllForeignKeys()) {
                    RelationalSchema.ForeignKeyRef reference = ref;
                    String parent = reference.foreignKey.getReferencedTable();
                    if (schema.getTable(parent) == null || !plan.populates(reference.table.getName())
                            || !plan.populates(parent)) {
                        continue;
                    }
                    if (!population.isUsable(reference.table.getName()) || !population.isUsable(parent)
                            || population.acceptedRows(parent).isEmpty()) {
                        continue;
                    }
                    usableForeignKeys++;
                    boolean covered = probes.stream().anyMatch(probe -> probe.getGroup()
                            .contains("references of " + reference.table.getName() + "." + reference.foreignKey
                                    .getColumns()));
                    if (covered) {
                        probesBuilt++;
                    }
                }
                for (String note : notes) {
                    if (note.contains("no dangling-reference probe") || note.contains("was not built")) {
                        skipped.append("\n    seed ").append(seed).append(": ").append(note);
                    }
                }
            }
        }
        assertTrue(usableForeignKeys > 0, "no usable foreign key in ten generated databases");
        double covered = 100.0 * probesBuilt / usableForeignKeys;
        assertTrue(covered >= 70.0, String.format(
                "only %.0f%% of the usable foreign keys got a probe (%d of %d); what was skipped:%s",
                covered, probesBuilt, usableForeignKeys, skipped));
    }

    @Test
    @DisplayName("every reference a probe row carries, except the one under test, resolves to an accepted row")
    void everyOtherReferenceOfAProbeRowResolves() throws Exception {
        for (long seed : List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)) {
            try (ConformanceTestSupport.Session session = sqlite(seed)) {
                RelationalSchema schema = session.input.adapted;
                ConformancePlan plan = new ConformancePlanner(session.input.graph, session.input.erDiagram, schema,
                        session.input.trace, session.database.getDialect()).plan();
                ConformancePopulator.Result population = ConformancePopulator.populate(session.context,
                        session.database, plan, schema);
                for (ConformanceProbe probe : ConformanceProbeBuilder.build(session.context, session.database, schema,
                        plan, population, new ArrayList<>())) {
                    if (probe.getGroup().contains("moved by UPDATE") || probe.getRow().isEmpty()) {
                        continue; // its row map describes the key it targets, not a row it carries
                    }
                    Table table = schema.getTable(probe.getTable());
                    List<String> broken = brokenColumns(probe.getGroup());
                    for (ForeignKey foreignKey : table.getForeignKeys()) {
                        if (lower(foreignKey.getColumns()).equals(broken)) {
                            continue; // the reference this probe is about, and the only one it may break
                        }
                        List<Object> values = new ArrayList<>();
                        boolean present = true;
                        for (String column : foreignKey.getColumns()) {
                            Object value = DataModel.getValue(probe.getRow(), column);
                            values.add(value);
                            present = present && value != null;
                        }
                        if (!present) {
                            continue; // a partially NULL key is not a reference
                        }
                        assertTrue(resolves(population, foreignKey, values),
                                "seed " + seed + ": the probe '" + probe.getGroup() + "' carries the reference "
                                        + foreignKey.getColumns() + " = " + values + ", which no accepted row of "
                                        + foreignKey.getReferencedTable() + " satisfies: " + probe.getSql());
                    }
                }
            }
        }
    }

    /** @return the foreign key columns the probe's group names, lower-cased */
    private static List<String> brokenColumns(String group) {
        int open = group.indexOf('[');
        int close = group.indexOf(']', open);
        if (open < 0 || close < 0) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        for (String column : group.substring(open + 1, close).split(",")) {
            columns.add(column.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return columns;
    }

    private static boolean resolves(ConformancePopulator.Result population, ForeignKey foreignKey,
            List<Object> values) {
        for (PlannedRow candidate : population.acceptedRows(foreignKey.getReferencedTable())) {
            boolean same = true;
            for (int i = 0; i < values.size(); i++) {
                Object stored = DataModel.getValue(candidate.getValues(), foreignKey.getReferencedColumns().get(i));
                if (!DataModel.canonical(values.get(i), null).equals(DataModel.canonical(stored, null))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    private static List<String> lower(List<String> names) {
        List<String> lowered = new ArrayList<>();
        for (String name : names) {
            lowered.add(name.toLowerCase(java.util.Locale.ROOT));
        }
        return lowered;
    }

    /* ------------------------------------------------------------------ fixtures */

    private static Table child(String name, Column... columns) {
        Table table = new Table(name);
        for (Column column : columns) {
            table.addColumn(column);
        }
        table.setPrimaryKey(new PrimaryKey(List.of(columns[columns.length - 1].getName())));
        return table;
    }

    private static ForeignKey foreignKey(String column) {
        ForeignKey foreignKey = new ForeignKey(List.of(column), "p", List.of("k"));
        foreignKey.setName("fk_" + column);
        return foreignKey;
    }

    private static ExecutionEvent event(String sqlState, int errorCode) {
        return new ExecutionEvent("db", "INSERT INTO t VALUES (1)", false, "refused", sqlState, errorCode, 1L, false,
                "test");
    }

    private ConformanceTestSupport.Session sqlite(long seed) throws Exception {
        SqliteDialect dialect = new SqliteDialect();
        ConformanceTestSupport.Input input = ConformanceTestSupport.generate(seed, dialect);
        return ConformanceTestSupport.open("jdbc:sqlite::memory:", "sqlite", dialect, new SqliteIntrospector(), input,
                seed);
    }
}
