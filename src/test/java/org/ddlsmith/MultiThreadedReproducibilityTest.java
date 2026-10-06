package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for the second half of the shared-cache defect: with more than one thread, the
 * same seed did not generate the same database.
 *
 * <p>Every worker seeds itself before generating, and seeding clears the constant cache. While the
 * caches were static, that clear (and every value another thread added to them) was visible to all
 * workers, so what a database contained depended on which other databases happened to be generated
 * at the same moment. The tool's promise is the opposite: the seed is the only input that matters.
 * The test compares the DDL that a run with one thread and a run with six threads dump for the same
 * six seeds.</p>
 */
class MultiThreadedReproducibilityTest {

    @Test
    @DisplayName("the same seed generates the same DDL no matter how many threads run")
    void theSameSeedGeneratesTheSameDdlWithAnyThreadCount(@TempDir Path directory) throws Exception {
        Path singleThreaded = directory.resolve("single");
        Path multiThreaded = directory.resolve("multi");
        int databases = 6;
        String seed = "20260101";

        run(databases, 1, seed, singleThreaded);
        run(databases, databases, seed, multiThreaded);

        for (int i = 0; i < databases; i++) {
            Path expected = singleThreaded.resolve("database" + i + ".sql");
            Path actual = multiThreaded.resolve("database" + i + ".sql");
            assertTrue(Files.exists(expected), "the single-threaded run must dump database" + i);
            assertTrue(Files.exists(actual),
                    "the multi-threaded run must dump database" + i + " instead of failing in the generator");
            assertEquals(Files.readString(expected), Files.readString(actual),
                    "database" + i + " must not depend on which other databases were generated at the same time");
        }
    }

    /** Runs the tool on SQLite, dumping every generated create script to {@code dumpDirectory}. */
    private static void run(int databases, int threads, String seed, Path dumpDirectory) {
        Main.executeMain("sqlite",
                "--num-tries", String.valueOf(databases),
                "--threads", String.valueOf(threads),
                "--random-seed", seed,
                "--ddl-dump-dir", dumpDirectory.toString(),
                "--bug-dir", "off",
                "--log-each-select=false",
                "--print-progress-information=false",
                "--quiet");
    }
}
