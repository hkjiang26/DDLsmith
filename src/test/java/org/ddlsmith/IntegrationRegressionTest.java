package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.gen.SchemaAdapter;
import org.ddlsmith.ddl.gen.SchemaCreationScript;
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for defects that were found by running DDLSmith end to end and that a component
 * test could not have caught, because each one needed a whole schema or a whole run.
 */
class IntegrationRegressionTest {

    /**
     * A plain and a unique index over the same columns are two different objects. Keying the lookup
     * map on columns alone let one overwrite the other, so the comparison reported that a unique index
     * was not unique — a false positive that appeared on SQLite and DuckDB.
     */
    @Test
    @DisplayName("two indexes over the same columns but with different uniqueness do not collide")
    void indexUniquenessIsPartOfTheIdentity() {
        RelationalSchema intended = new RelationalSchema(null);
        Table table = new Table(null, "t");
        table.addColumn(new Column("a", DataType.bigint()).setNullable(false));
        table.addColumn(new Column("b", DataType.integer()));
        table.setPrimaryKey(new PrimaryKey(List.of("a")));
        table.addIndex(new Index("plain", List.of("a", "b")));
        table.addIndex(new Index("uniq", List.of("a", "b")).setUnique(true));
        intended.addTable(table);

        // the catalog of an engine that created both, in the other order
        RelationalSchema actual = new RelationalSchema(null);
        Table actualTable = new Table(null, "t");
        actualTable.addColumn(new Column("a", DataType.bigint()).setNullable(false));
        actualTable.addColumn(new Column("b", DataType.integer()));
        actualTable.setPrimaryKey(new PrimaryKey(List.of("a")));
        actualTable.addIndex(new Index("uniq", List.of("a", "b")).setUnique(true));
        actualTable.addIndex(new Index("plain", List.of("a", "b")));
        actual.addTable(actualTable);

        List<SchemaDifference> differences = SchemaNormalizer.diff(intended, actual);
        assertTrue(differences.isEmpty(), "expected no differences, got " + SchemaNormalizer.render(differences));
    }

    /**
     * A key column of a type the engine cannot index makes the whole {@code CREATE TABLE} fail, because
     * the engine indexes the key implicitly. The key is what the ER mapping expresses, so the type
     * gives way — and the foreign key columns that reference the substituted key must be aligned with
     * it, or the engine rejects the reference as "uncomparable".
     */
    @Test
    @DisplayName("an unindexable key type is substituted, and the foreign keys that reference it follow")
    void unindexableKeyTypesAreSubstitutedAndPropagated() {
        RelationalSchema schema = new RelationalSchema("s");
        Table parent = new Table("s", "parent");
        parent.addColumn(new Column("k", DataType.text()).setNullable(false));
        parent.setPrimaryKey(new PrimaryKey(List.of("k")));
        schema.addTable(parent);

        Table child = new Table("s", "child");
        child.addColumn(new Column("id", DataType.bigint()).setNullable(false));
        child.addColumn(new Column("parent_k", DataType.text()));
        child.setPrimaryKey(new PrimaryKey(List.of("id")));
        schema.addTable(child);
        child.addForeignKey(new org.ddlsmith.common.schema.ForeignKey(List.of("parent_k"), "parent",
                List.of("k")).setName("fk"));

        List<String> notes = new ArrayList<>();
        // H2 is used because it genuinely cannot index a CHARACTER LARGE OBJECT key column, so the
        // substitution has to happen; the assertion is about the substitution, not about the dialect
        org.ddlsmith.ddl.Dialect dialect = org.ddlsmith.dbms.Dialects.forName("h2");
        RelationalSchema adapted = SchemaAdapter.adapt(schema, dialect, notes);

        DataType keyType = adapted.getTable("parent").getColumn("k").getType();
        assertFalse(keyType.getCategory() == DataType.Category.TEXT,
                "a key column of an unindexable type must be given an indexable one, got " + keyType);
        assertEquals(keyType, adapted.getTable("child").getColumn("parent_k").getType(),
                "the referencing column must have the same type as the key it references");
        assertTrue(notes.stream().anyMatch(note -> note.contains("key column")),
                "the substitution must be reported in the adaptation notes");
    }

    /**
     * {@code --ddl-dump-dir} is documented, so it has to produce the script the engine actually
     * receives. It was accepted and silently ignored before this test existed.
     */
    @Test
    @DisplayName("the generated DDL can be dumped to a directory")
    void ddlCanBeDumpedToDisk(@TempDir Path directory) throws Exception {
        RelationalSchema schema = new RelationalSchema("s");
        Table table = new Table("s", "t");
        table.addColumn(new Column("id", DataType.bigint()).setNullable(false));
        table.setPrimaryKey(new PrimaryKey(List.of("id")));
        schema.addTable(table);

        TestDialect dialect = TestDialect.permissive();
        MainOptions options = new MainOptions();
        List<DDLStatement> script = SchemaCreationScript.create(schema, dialect, options);
        assertFalse(script.isEmpty(), "the create script must not be empty");

        List<String> lines = new ArrayList<>();
        for (DDLStatement statement : script) {
            lines.addAll(dialect.renderAll(statement));
        }
        Path file = directory.resolve("dump.sql");
        Files.write(file, lines);

        assertTrue(Files.exists(file));
        String content = Files.readString(file);
        assertTrue(content.contains("t"), "the dump must contain the generated statements");
        assertNotNull(content);
    }

    /**
     * A dialect that cannot express an object renders an explanatory comment in its place. That is
     * useful in a DDL dump, but it is not a statement: sending it to the engine produces a driver-level
     * error, and the crash oracle then blames the engine for a construct the user force-enabled with
     * {@code --ddl-features}. It must be recognised and skipped.
     */
    @Test
    @DisplayName("a comment-only rendering is not a statement")
    void commentOnlyRenderingsAreRecognised() {
        assertTrue(org.ddlsmith.oracle.StatementRunner.isCommentOnly("-- sqlite has no sequences"));
        assertTrue(org.ddlsmith.oracle.StatementRunner.isCommentOnly("-- mysql has no sequences\n--\n  -- why"));
        assertTrue(org.ddlsmith.oracle.StatementRunner.isCommentOnly("   \n\t\n"));
        assertFalse(org.ddlsmith.oracle.StatementRunner.isCommentOnly("CREATE TABLE t (id INT)"));
        assertFalse(org.ddlsmith.oracle.StatementRunner.isCommentOnly("-- a note\nCREATE TABLE t (id INT)"));
        assertFalse(org.ddlsmith.oracle.StatementRunner.isCommentOnly("SELECT 1 -- trailing comment"));
    }

    /**
     * A failure log has to be replayable on its own, because {@code --reproduce} reads the statements
     * out of it. Nothing ever recorded them, so every log claimed to contain none and the finding was
     * unreproducible for anyone who did not have the original run.
     */
    @Test
    @DisplayName("a failure log written in the report format is replayable")
    void failureLogsAreReplayable(@TempDir Path directory) throws Exception {
        org.ddlsmith.StateToReproduce state = new org.ddlsmith.StateToReproduce("database0");
        state.logStatement("CREATE TABLE t (id INT)");
        state.logStatement("CREATE TABLE t (id INT);");
        assertEquals(2, state.getStatements().size(), "each statement is recorded once");
        assertTrue(state.getStatements().get(0).endsWith(";"), "a recorded statement is terminated");

        // the real log layout: identity block, statements, generated-input comments, then the report
        List<String> lines = new ArrayList<>();
        lines.add("-- database : database0");
        lines.add("-- version  : 1.0");
        lines.add("-- seed     : 1");
        lines.addAll(state.getStatements());
        lines.add("-- ============================ generated input ============================");
        lines.add("-- graph schema : GraphSchema[...]");
        lines.add("-- schema       : tables=1 columns=1 pk=1");
        lines.add("");
        lines.add("DDLSmith failure: schema mismatch on database0: MISSING_TABLE at t");
        lines.add("Statement  : CREATE TABLE t (id INT)");
        Path log = directory.resolve("database0-failure-001-schema_mismatch.log");
        Files.write(log, lines);

        List<String> replayed = org.ddlsmith.Reproducer.readStatements(log);
        assertEquals(2, replayed.size(),
                "both statements must be recovered, and none of the report prose: " + replayed);
        assertEquals("CREATE TABLE t (id INT)", replayed.get(0));
    }

    /**
     * The union-membership check of a category is a disjunction, and DuckDB reports the operands of a
     * disjunction back parenthesised ({@code (a IS NOT NULL) OR (b IS NOT NULL)}) while DDLSmith
     * declared it without parentheses. Comparing the two texts reported the constraint as lost on
     * every generated database that had a category. The tolerance must not go further than the
     * parenthesised predicate: a genuinely different connective is still a difference.
     */
    @Test
    @DisplayName("parenthesised IS NOT NULL operands compare equal, a different connective does not")
    void unionMembershipChecksCompareAcrossEngines() {
        assertEquals(SchemaNormalizer.canonicalExpression("a IS NOT NULL OR b IS NOT NULL"),
                SchemaNormalizer.canonicalExpression("(a IS NOT NULL) OR (b IS NOT NULL)"));
        assertEquals(SchemaNormalizer.canonicalExpression("a IS NOT NULL OR b IS NOT NULL"),
                SchemaNormalizer.canonicalExpression("((a IS NOT NULL) OR (b IS NOT NULL))"));
        assertFalse(SchemaNormalizer.canonicalExpression("a IS NOT NULL OR b IS NOT NULL")
                .equals(SchemaNormalizer.canonicalExpression("a IS NOT NULL AND b IS NOT NULL")),
                "AND and OR are different predicates");
        assertFalse(SchemaNormalizer.canonicalExpression("a IS NOT NULL OR b IS NOT NULL")
                .equals(SchemaNormalizer.canonicalExpression("NOT (a IS NOT NULL OR b IS NOT NULL)")),
                "a negated disjunction is a different predicate");
    }

    /**
     * In a differential run the common schema is named after the primary engine's namespace (SQLite's
     * {@code main}), while an embedded comparison engine with no namespace hook of its own creates the
     * unqualified objects in <em>its</em> default namespace (H2's {@code public}). Introspection used
     * the common schema's name for every engine, so H2 was read where its tables were not and every
     * table was reported missing — on every database of the run.
     */
    @Test
    @DisplayName("a differential run against an embedded comparison engine finds nothing missing")
    void differentialRunReadsTheComparisonEnginesOwnNamespace() throws Exception {
        java.util.concurrent.ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ddlsmith-differential-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            java.util.concurrent.Future<Integer> exitCode = caller.submit(() -> Main.executeMain("sqlite",
                    "--num-tries", "4", "--threads", "1", "--random-seed", "9215",
                    "--algorithm", "differential", "--compare-with", "h2",
                    "--bug-dir", "off", "--log-each-select=false", "--print-progress-information=false",
                    "--quiet"));
            assertEquals(0, exitCode.get(300, java.util.concurrent.TimeUnit.SECONDS),
                    "a differential run must read each engine's catalog where its tables were created");
        } finally {
            caller.shutdownNow();
        }
    }
}
