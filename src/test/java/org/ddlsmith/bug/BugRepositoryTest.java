package org.ddlsmith.bug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.ddlsmith.MainOptions;
import org.ddlsmith.StateToReproduce;
import org.ddlsmith.oracle.OracleFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bug directory only earns its place if the same defect is filed once. These tests pin the two
 * properties that make that true — signature normalization and report accumulation — and the property
 * that makes a report usable, which is that its reproduction is short enough to paste.
 */
class BugRepositoryTest {

    private static OracleFailure failure(String title, String details, long seed) {
        return new OracleFailure(OracleFailure.FailureKind.SCHEMA_MISMATCH, "schema-round-trip", title, details,
                null, seed, "database0", null);
    }

    /**
     * The crash oracle's title ends with the feature list of the generated schema, which changes from
     * one database to the next. Treating it as part of the identity filed the same defect four times.
     */
    @Test
    @DisplayName("the varying feature list is not part of a defect's identity")
    void featureListsDoNotSplitADefect() {
        String first = BugSignature.of("duckdb", failure(
                "engine rejected create although it declares FK_ON_DELETE,NOT_NULL_COLUMN,TYPE_TEXT", "", 1));
        String second = BugSignature.of("duckdb", failure(
                "engine rejected create although it declares FK_ON_CASCADE,NAMED_CHECK,TYPE_DATE,TYPE_BOOLEAN", "", 2));
        assertEquals(first, second, "the same defect must have one signature whatever features it lists");
    }

    /** Generated identifiers differ on every database, so they must not distinguish defects either. */
    @Test
    @DisplayName("generated identifiers and database names are normalized away")
    void generatedNamesDoNotSplitADefect() {
        String first = BugSignature.of("h2", failure("MISSING_INDEX at t_3_r.idx([t_3_a10, t_1_a4])", "", 1));
        String second = BugSignature.of("h2", failure("MISSING_INDEX at t_0_r.idx([t_0_a1, a2])", "", 2));
        assertEquals(first, second);

        String database1 = BugSignature.of("h2", failure("schema mismatch on database1: MISSING_TABLE at t_1", "", 1));
        String database7 = BugSignature.of("h2", failure("schema mismatch on database7: MISSING_TABLE at t_5", "", 2));
        assertEquals(database1, database7);
    }

    /** Normalization must stay moderate: genuinely different defects must not be merged. */
    @Test
    @DisplayName("different defects keep different signatures")
    void differentDefectsStayApart() {
        String missingIndex = BugSignature.of("h2", failure("MISSING_INDEX at t_0.idx([a1])", "", 1));
        String missingTable = BugSignature.of("h2", failure("MISSING_TABLE at t_0", "", 1));
        assertNotEquals(missingIndex, missingTable);

        String onH2 = BugSignature.of("h2", failure("MISSING_INDEX at t_0.idx([a1])", "", 1));
        String onSqlite = BugSignature.of("sqlite", failure("MISSING_INDEX at t_0.idx([a1])", "", 1));
        assertNotEquals(onH2, onSqlite, "the same defect on two engines is two defects");
    }

    /** One defect found in many databases is one report whose occurrence count is the evidence. */
    @Test
    @DisplayName("repeated occurrences accumulate into one report")
    void occurrencesAccumulateIntoOneReport(@TempDir Path directory) throws Exception {
        MainOptions options = new MainOptions();
        options.setBugDirectory(directory.toString());
        BugRepository repository = BugRepository.forOptions(options);
        assertTrue(repository.isEnabled());

        for (int i = 0; i < 5; i++) {
            StateToReproduce state = new StateToReproduce("database" + i);
            state.seedValue = 100 + i;
            repository.recordAll("duckdb", "v1.3.0", List.of(failure(
                    "engine rejected create although it declares FK_ON_DELETE,TYPE_TEXT", "Error: nope", 100 + i)),
                    state, options, null);
        }

        assertEquals(1, repository.getDefectCount(), "five occurrences of one defect are one defect");
        BugRepository.BugEntry entry = repository.getEntries().get(0);
        assertEquals(5, entry.getOccurrences());
        assertEquals(5, entry.getSeeds().size());
        assertTrue(entry.isVerifiedOracle());

        Path report = entry.getFile();
        assertNotNull(report);
        assertTrue(Files.exists(report), "the report must exist on disk");
        String content = Files.readString(report);
        assertTrue(content.contains("| **Occurrences** | 5 |"), content);
        assertTrue(content.contains("high — reported by a verified oracle"));
        assertTrue(Files.exists(directory.resolve("INDEX.md")), "an index must be written");
    }

    /** A mismatch that follows a rejected statement is the rejection's shadow, and must say so. */
    @Test
    @DisplayName("a mismatch after a rejected statement is marked as a suspected cascade")
    void cascadesAreMarked(@TempDir Path directory) throws Exception {
        MainOptions options = new MainOptions();
        options.setBugDirectory(directory.toString());
        BugRepository repository = BugRepository.forOptions(options);

        OracleFailure rejected = new OracleFailure(OracleFailure.FailureKind.UNEXPECTED_ERROR, "crash",
                "engine rejected create although it declares FK_ON_DELETE,TYPE_TEXT", "Error: nope", null, 1,
                "database0", null);
        OracleFailure mismatch = failure("schema mismatch on database0: MISSING_TABLE at t_1", "details", 1);
        StateToReproduce state = new StateToReproduce("database0");
        repository.recordAll("duckdb", "v1.3.0", List.of(rejected, mismatch), state, options, null);

        BugRepository.BugEntry cascade = repository.getEntries().stream()
                .filter(entry -> entry.getKind() == OracleFailure.FailureKind.SCHEMA_MISMATCH).findFirst().orElseThrow();
        String content = Files.readString(cascade.getFile());
        assertTrue(content.contains("Suspected cascade"), content);
        assertTrue(content.contains("Found in the same database as"), content);
    }

    /** An experimental oracle's finding is recorded but must not read as a confirmed engine defect. */
    @Test
    @DisplayName("a finding from an experimental oracle is marked as needing review")
    void experimentalOraclesAreMarked(@TempDir Path directory) throws Exception {
        MainOptions options = new MainOptions();
        options.setBugDirectory(directory.toString());
        BugRepository repository = BugRepository.forOptions(options);

        OracleFailure experimental = new OracleFailure(OracleFailure.FailureKind.DATA_MISMATCH, "pivot-row",
                "a stored value was not returned", "details", null, 1, "database0", null);
        repository.recordAll("h2", "2.3.232", List.of(experimental), new StateToReproduce("database0"), options,
                null);

        assertFalse(repository.getEntries().get(0).isVerifiedOracle());
        assertTrue(Files.readString(repository.getEntries().get(0).getFile()).contains("needs review"));
    }

    /** A report is only useful if a maintainer can paste it, so the reduction must drop what cannot matter. */
    @Test
    @DisplayName("the minimal reproduction keeps only the statements the failure depends on")
    void minimalReproductionIsReduced() {
        List<String> script = List.of(
                "CREATE SCHEMA IF NOT EXISTS s;",
                "CREATE TABLE s.parent (id INTEGER NOT NULL, PRIMARY KEY (id));",
                "CREATE TABLE s.unrelated (id INTEGER NOT NULL, PRIMARY KEY (id));",
                "CREATE TABLE s.child (id INTEGER NOT NULL, pid INTEGER, PRIMARY KEY (id), "
                        + "CONSTRAINT fk FOREIGN KEY (pid) REFERENCES s.parent (id) ON DELETE CASCADE);");
        String failing = "CREATE TABLE s.child (id INTEGER NOT NULL, pid INTEGER, PRIMARY KEY (id), "
                + "CONSTRAINT fk FOREIGN KEY (pid) REFERENCES s.parent (id) ON DELETE CASCADE)";

        String reduced = MinimalReproducer.minimize(script, failing, null);
        assertTrue(reduced.contains("CREATE SCHEMA IF NOT EXISTS s;"), reduced);
        assertTrue(reduced.contains("CREATE TABLE s.parent"), "the referenced table is needed: " + reduced);
        assertTrue(reduced.contains("CREATE TABLE s.child"), reduced);
        assertFalse(reduced.contains("unrelated"), "an unreferenced table cannot matter: " + reduced);
    }

    /** A defect whose statement is not known must still produce a usable reproduction. */
    @Test
    @DisplayName("without a failing statement the whole script is kept rather than a wrong subset")
    void minimalReproductionFallsBackToTheWholeScript() {
        List<String> script = List.of("CREATE TABLE s.a (id INTEGER);", "CREATE TABLE s.b (id INTEGER);");
        String reduced = MinimalReproducer.minimize(script, null, "SELECT * FROM s.a;");
        assertTrue(reduced.contains("SELECT * FROM s.a;"), reduced);
        assertTrue(reduced.contains("CREATE TABLE s.a"), reduced);
        assertTrue(reduced.contains("CREATE TABLE s.b"), reduced);
    }

    /** A blank or 'off' directory disables filing, which is how a user turns it off. */
    @Test
    @DisplayName("the bug directory can be disabled")
    void theDirectoryCanBeDisabled() {
        MainOptions options = new MainOptions();
        options.setBugDirectory("off");
        BugRepository repository = BugRepository.forOptions(options);
        assertFalse(repository.isEnabled());
        assertEquals(0, repository.recordAll("h2", "1", List.of(failure("t", "d", 1)),
                new StateToReproduce("database0"), options, null).size());
    }

    /**
     * The directory is the result of a whole testing session, so a second run must add to it. It used
     * to number its defects from 001 again and rewrite whatever it found, so a run that found a
     * different defect on the same engine deleted the previous run's report.
     */
    @Test
    @DisplayName("a second run updates the reports of the first instead of overwriting them")
    void reportsAccumulateAcrossRuns(@TempDir Path directory) throws Exception {
        MainOptions options = new MainOptions();
        options.setBugDirectory(directory.toString());

        BugRepository firstRun = BugRepository.forOptions(options);
        StateToReproduce firstState = new StateToReproduce("database0");
        firstState.seedValue = 100;
        firstRun.recordAll("duckdb", "v1.3.0", List.of(failure("MISSING_INDEX at t_0.idx([a1])", "first details", 100)),
                firstState, options, null);
        Path report = firstRun.getEntries().get(0).getFile();
        // a note a maintainer added by hand: it must survive the next run
        Files.writeString(report, Files.readString(report) + "\n<!-- triaged by hand -->\n");

        BugRepository.resetForTesting();
        BugRepository secondRun = BugRepository.forOptions(options);
        StateToReproduce secondState = new StateToReproduce("database1");
        secondState.seedValue = 200;
        // the same defect again, in another database and under another generated name
        secondRun.recordAll("duckdb", "v1.3.0", List.of(failure("MISSING_INDEX at t_3_r.idx([t_1_a4])", "second", 200)),
                secondState, options, null);
        // and a genuinely different one, which must not land on the first one's file
        StateToReproduce thirdState = new StateToReproduce("database2");
        thirdState.seedValue = 300;
        secondRun.recordAll("duckdb", "v1.3.0", List.of(failure("MISSING_TABLE at t_9", "third", 300)), thirdState,
                options, null);

        assertEquals(2, secondRun.getDefectCount(), "the second run must see the defect of the first");
        BugRepository.BugEntry known = secondRun.getEntries().stream()
                .filter(entry -> entry.getSignature().contains("missing_index")).findFirst()
                .orElse(secondRun.getEntries().get(0));
        assertEquals("duckdb-001", known.getId(), "a defect keeps the id it was first reported under");
        assertEquals(2, known.getOccurrences(), "the occurrence of the second run is evidence, not a new defect");
        assertEquals(List.of("100", "200"), known.getSeeds());

        BugRepository.BugEntry fresh = secondRun.getEntries().stream().filter(entry -> entry != known).findFirst()
                .orElseThrow();
        assertEquals("duckdb-002", fresh.getId(), "a new defect must not be numbered onto an existing file");
        assertNotEquals(report, fresh.getFile());

        String content = Files.readString(report);
        assertTrue(content.contains("| **Occurrences** | 2 |"), content);
        assertTrue(content.contains("200"), "the seed of the new occurrence must be recorded: " + content);
        assertTrue(content.contains("<!-- triaged by hand -->"),
                "the report is updated in place, so everything a maintainer wrote stays: " + content);
    }

    /**
     * A report is only reproducible if the command it prints carries the options that shaped the run.
     * Findings that need {@code --ddl-features} were filed with a command line that generated a
     * different schema, so replaying it found nothing and the report looked wrong.
     */
    @Test
    @DisplayName("the reproduction command keeps the options that shaped the run")
    void theReproduceCommandKeepsTheRunOptions(@TempDir Path directory) throws Exception {
        MainOptions options = new MainOptions();
        options.setBugDirectory(directory.toString());
        options.getExtraDdlFeatures().add("FK_CASCADE");
        options.getExtraDdlFeatures().add("FK_ON_DELETE");
        options.setOracles(List.of("crash", "schema-round-trip"));
        options.setGraphGenerator("tree");
        options.setEvolveSteps(25);
        options.setRowsPerTable(7);

        BugRepository repository = BugRepository.forOptions(options);
        repository.recordAll("duckdb", "v1.3.0",
                List.of(failure("engine rejected create although it declares FK_CASCADE", "Error: nope", 901)),
                new StateToReproduce("database0"), options, null);

        String content = Files.readString(repository.getEntries().get(0).getFile());
        assertTrue(content.contains("--ddl-features FK_CASCADE,FK_ON_DELETE"), content);
        assertTrue(content.contains("--oracle crash,schema-round-trip"), content);
        assertTrue(content.contains("--graph-generator tree"), content);
        assertTrue(content.contains("--evolve-steps 25"), content);
        assertTrue(content.contains("--insert-rows 7"), content);
        assertTrue(content.contains("--random-seed 901"), content);
    }
}
