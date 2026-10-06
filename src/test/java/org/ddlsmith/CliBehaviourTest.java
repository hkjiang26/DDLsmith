package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.ddlsmith.dbms.DatabaseConfig;
import org.ddlsmith.dbms.Targets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the command line behaviour of the tool itself: an option that is documented
 * has to do what it says, a failure of the tool has to leave something to diagnose, and connection
 * options have to reach every engine they are documented for.
 */
class CliBehaviourTest {

    /**
     * {@code --log-each-select} is documented as writing {@code logs/<engine>/<database>-cur.log}.
     * Nothing ever wrote it: the option was accepted, the flag was read by a code path no algorithm
     * used, and the file it promised never appeared.
     */
    @Test
    @DisplayName("--log-each-select writes the per-database log it promises")
    void logEachSelectWritesTheLiveLog() throws Exception {
        Path log = Path.of("logs", "sqlite", "database0-cur.log");
        Files.deleteIfExists(log);

        int exit = Main.executeMain("sqlite", "--num-tries", "1", "--random-seed", "99", "--log-each-select",
                "--bug-dir", "off", "--quiet", "--print-progress-information=false");
        assertEquals(0, exit);
        assertTrue(Files.exists(log), "the live log must exist at " + log.toAbsolutePath());
        String content = Files.readString(log);
        assertTrue(content.contains("CREATE TABLE"), "the live log must hold the executed statements: " + content);

        Files.deleteIfExists(log);
    }

    /**
     * A crash in the harness used to leave nothing but a stack trace on stderr, which does not say
     * which seed produced it. The seed is the only handle on the input, so the crash is written to a
     * log file as well.
     */
    @Test
    @DisplayName("a failure of the harness itself is recorded with its seed")
    void harnessFailuresAreRecorded() throws Exception {
        Path directory = Path.of("logs", "h2");
        deleteCrashLogs(directory);

        int exit = Main.executeMain("h2", "--num-tries", "1", "--random-seed", "77",
                "--algorithm", "no-such-algorithm", "--bug-dir", "off", "--quiet",
                "--print-progress-information=false");

        assertEquals(1, exit, "a failure of the tool must fail the run");
        List<Path> crashes;
        try (var files = Files.list(directory)) {
            crashes = files.filter(path -> path.getFileName().toString().contains("-crash-")).toList();
        }
        assertEquals(1, crashes.size(), "the crash must be recorded once, in " + directory.toAbsolutePath());
        String content = Files.readString(crashes.get(0));
        assertTrue(content.contains("seed     : 77"), "the seed must be in the record: " + content);
        assertTrue(content.contains("unknown algorithm"), "the cause must be in the record: " + content);
        Files.delete(crashes.get(0));
    }

    private static void deleteCrashLogs(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().contains("-crash-")).toList()) {
                Files.delete(file);
            }
        }
    }

    /**
     * The connection options are documented for every client/server engine, but they were applied to
     * PostgreSQL alone, so {@code --host}, {@code --port} and {@code --database} were silently ignored
     * for MySQL. An embedded engine takes its settings from the URL and must not be touched by them.
     */
    @Test
    @DisplayName("connection options reach every client/server engine and no embedded one")
    void connectionOptionsApplyToServerEngines(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.json");
        // an empty configuration: the command line options are the only source of settings
        Files.writeString(config, "{}");

        MainOptions options = new MainOptions();
        options.setConfigFile(config.toString());
        options.setHost("db.example.com");
        options.setPort(3307);
        options.setDatabase("ddlsmith_test");
        options.setUsername("tester");
        options.setPassword("secret");

        DatabaseConfig mysql = Targets.primary("mysql", options);
        assertEquals("jdbc:mysql://db.example.com:3307/ddlsmith_test", mysql.jdbcUrl("database0"));
        assertEquals("tester", mysql.getUsername());

        DatabaseConfig postgres = Targets.primary("postgres", options);
        assertEquals("jdbc:postgresql://db.example.com:3307/ddlsmith_test", postgres.jdbcUrl("database0"));

        DatabaseConfig sqlite = Targets.primary("sqlite", options);
        assertEquals("jdbc:sqlite:target/db/database0.db", sqlite.jdbcUrl("database0"),
                "an embedded engine must keep its file URL");
    }
}
