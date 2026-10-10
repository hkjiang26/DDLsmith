package org.ddlsmith.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.conformance.ConformancePlan.PlannedRow;
import org.ddlsmith.dbms.h2.H2Dialect;
import org.ddlsmith.dbms.h2.H2Introspector;
import org.ddlsmith.dbms.sqlite.SqliteDialect;
import org.ddlsmith.dbms.sqlite.SqliteIntrospector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the materializer does with a row the engine refuses.
 *
 * <p>A refusal is the oracle's {@code CONSTRAINT_TOO_STRICT} finding, and the whole value of that
 * finding is that it is rare: it says the schema cannot hold an instance its own ER diagram describes.
 * Two kinds of refusal do not say that, and both were produced by the two-hour MySQL experiment
 * ({@code runs/REPORT-MYSQL-2H.md} §3.2 and §3.3) — a duplicate the engine's <em>own</em> comparison
 * explains (a case-insensitive collation makes two values one) and a value the engine computes itself
 * (a stored generated column whose expression does not fit its type). Both have to become notes; a
 * refusal about a column the plan really writes, or a duplicate no stored row explains, has to stay a
 * finding.</p>
 *
 * <p>The engines are the real ones, in memory: SQLite with {@code COLLATE NOCASE} is the
 * case-insensitive comparison, H2 enforces the range of a {@code SMALLINT} that SQLite would not.</p>
 */
class ConformancePopulatorTest {

    /* ------------------------------------------------------------------ the comparison the engine makes */

    @Test
    @DisplayName("a duplicate the engine's own comparison explains is a note, not a finding")
    void explainsACaseInsensitiveDuplicate() throws Exception {
        // 'HF' and 'hf' are two values to the model and one value to a NOCASE collation, which is what
        // the campaign's MySQL schemas did with the server's utf8mb4_0900_ai_ci default
        RelationalSchema schema = personSchema(true);
        try (ConformanceTestSupport.Session session = sqlite(schema)) {
            ConformancePlan plan = plan(schema.getTable("person"),
                    Map.of("id", 1, "name", "HF"), Map.of("id", 2, "name", "hf"));

            ConformancePopulator.Result result = populate(session, plan, schema);

            assertEquals(List.of(), sqlsOf(result), "the refusal was reported as an engine defect: "
                    + result.notes);
            assertEquals(1, result.accepted.get("person").size(), "the first row must have been stored");
            assertTrue(result.notes.stream().anyMatch(note -> note.contains("holds it distinct")),
                    "the refusal is not explained in the notes: " + result.notes);
        }
    }

    @Test
    @DisplayName("the same two rows are both stored when the column compares exactly")
    void storesBothValuesWithoutTheCollation() throws Exception {
        // the control for the test above: the collation is what makes the difference, not the values
        RelationalSchema schema = personSchema(false);
        try (ConformanceTestSupport.Session session = sqlite(schema)) {
            ConformancePlan plan = plan(schema.getTable("person"),
                    Map.of("id", 1, "name", "HF"), Map.of("id", 2, "name", "hf"));

            ConformancePopulator.Result result = populate(session, plan, schema);

            assertEquals(List.of(), sqlsOf(result), result.notes.toString());
            assertEquals(2, result.accepted.get("person").size());
        }
    }

    @Test
    @DisplayName("a duplicate with no stored row to explain it stays a finding")
    void reportsADuplicateNoStoredRowExplains() throws Exception {
        RelationalSchema schema = personSchema(true);
        try (ConformanceTestSupport.Session session = sqlite(schema)) {
            // identical values: the plan itself is at fault (the planner's own duplicate check would have
            // dropped the second row, and this test hands it to the populator anyway), and the engine is
            // right to refuse it — the check must not excuse that
            ConformancePlan plan = plan(schema.getTable("person"),
                    Map.of("id", 1, "name", "HF"), Map.of("id", 2, "name", "HF"));

            ConformancePopulator.Result result = populate(session, plan, schema);

            assertEquals(1, result.rejections.size(), "the refusal was excused: " + result.notes);
            assertTrue(result.rejections.get(0).event.getErrorMessage().toUpperCase(java.util.Locale.ROOT)
                    .contains("UNIQUE"), result.rejections.get(0).event.getErrorMessage());
        }
    }

    /* ------------------------------------------------------------------ the value the engine computes */

    @Test
    @DisplayName("a refusal that names a generated column is a note: the engine computes that value")
    void explainsAGeneratedColumnThatOverflows() throws Exception {
        RelationalSchema schema = generatedColumnSchema();
        try (ConformanceTestSupport.Session session = h2(schema)) {
            // a10 SMALLINT AS (b + 1): b = 40000 fits INT, b + 1 does not fit SMALLINT, and MySQL,
            // H2 and PostgreSQL all refuse the row for it (report §3.3)
            ConformancePlan plan = plan(schema.getTable("gen"), Map.of("id", 1, "b", 40000));

            ConformancePopulator.Result result = populate(session, plan, schema);

            assertEquals(List.of(), sqlsOf(result), "the refusal was reported as an engine defect: "
                    + result.notes);
            assertTrue(result.notes.stream().anyMatch(note -> note.contains("GENERATED ALWAYS AS (b + 1)")),
                    "the note does not name the generated column: " + result.notes);
        }
    }

    @Test
    @DisplayName("a refusal that names only a column the plan writes stays a finding")
    void reportsAValueThePlanWritesOutOfRange() throws Exception {
        // the control for the test above: 40000 in a plain SMALLINT column is DDLSmith writing a value
        // the column cannot hold, and that is exactly what CONSTRAINT_TOO_STRICT has to report
        RelationalSchema schema = plainSmallintSchema();
        try (ConformanceTestSupport.Session session = h2(schema)) {
            ConformancePlan plan = plan(schema.getTable("plain"), Map.of("id", 1, "b", 40000));

            ConformancePopulator.Result result = populate(session, plan, schema);

            assertEquals(1, result.rejections.size(), "the refusal was excused: " + result.notes);
        }
    }

    /* ------------------------------------------------------------------ scaffolding */

    private static ConformancePopulator.Result populate(ConformanceTestSupport.Session session,
            ConformancePlan plan, RelationalSchema schema) {
        return ConformancePopulator.populate(session.context, session.database, plan, schema);
    }

    private static List<String> sqlsOf(ConformancePopulator.Result result) {
        return result.rejections.stream().map(rejection -> rejection.sql).toList();
    }

    private static ConformancePlan plan(Table table, Map<String, Object>... rows) {
        ConformancePlan plan = new ConformancePlan();
        int node = 0;
        for (Map<String, Object> row : rows) {
            plan.addRow(new PlannedRow(table, new LinkedHashMap<>(row), "test row " + node, node++, -1));
        }
        return plan;
    }

    private static RelationalSchema personSchema(boolean caseInsensitive) {
        RelationalSchema schema = new RelationalSchema("ddlsmith");
        Table person = new Table("ddlsmith", "person");
        person.addColumn(new Column("id", DataType.integer(), false));
        Column name = new Column("name", DataType.varchar(10), false);
        if (caseInsensitive) {
            // SQLite's spelling of "compare without case"; on MySQL the same effect comes from the
            // server's utf8mb4_0900_ai_ci default, which is what the campaign met
            name.setCollation("NOCASE");
        }
        person.addColumn(name);
        person.setPrimaryKey(new PrimaryKey("pk_person", List.of("id")));
        person.addUniqueConstraint(new UniqueConstraint("uq_person_name", List.of("name")));
        schema.addTable(person);
        return schema;
    }

    private static RelationalSchema generatedColumnSchema() {
        RelationalSchema schema = new RelationalSchema("ddlsmith");
        Table gen = new Table("ddlsmith", "gen");
        gen.addColumn(new Column("id", DataType.integer(), false));
        gen.addColumn(new Column("b", DataType.integer(), true));
        gen.addColumn(new Column("a10", DataType.smallint(), true)
                .setGeneratedExpression("b + 1").setGeneratedStored(true));
        gen.setPrimaryKey(new PrimaryKey("pk_gen", List.of("id")));
        schema.addTable(gen);
        return schema;
    }

    private static RelationalSchema plainSmallintSchema() {
        RelationalSchema schema = new RelationalSchema("ddlsmith");
        Table plain = new Table("ddlsmith", "plain");
        plain.addColumn(new Column("id", DataType.integer(), false));
        plain.addColumn(new Column("b", DataType.smallint(), true));
        plain.setPrimaryKey(new PrimaryKey("pk_plain", List.of("id")));
        schema.addTable(plain);
        return schema;
    }

    private static ConformanceTestSupport.Session sqlite(RelationalSchema schema) throws Exception {
        return ConformanceTestSupport.open("jdbc:sqlite::memory:", "sqlite", new SqliteDialect(),
                new SqliteIntrospector(), input(schema), 1L);
    }

    private static ConformanceTestSupport.Session h2(RelationalSchema schema) throws Exception {
        return ConformanceTestSupport.open("jdbc:h2:mem:populator_" + DATABASES.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1", "h2", new H2Dialect(), new H2Introspector(), input(schema), 1L);
    }

    private static ConformanceTestSupport.Input input(RelationalSchema schema) {
        ConformanceTestSupport.Input input = new ConformanceTestSupport.Input();
        input.adapted = schema;
        return input;
    }

    private static final java.util.concurrent.atomic.AtomicInteger DATABASES =
            new java.util.concurrent.atomic.AtomicInteger();
}
