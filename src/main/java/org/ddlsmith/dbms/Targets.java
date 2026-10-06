package org.ddlsmith.dbms;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.MainOptions;

/**
 * Resolves which database to connect to and opens the JDBC connection.
 *
 * <p>Embedded engines (SQLite, H2, DuckDB) are usable with no infrastructure, which is what makes
 * DDLSmith's differential mode practical: {@code --compare-with h2,duckdb} compares the same
 * generated schema across three engines without a single server.</p>
 */
public final class Targets {

    private Targets() {
    }

    /** @return the configuration of the primary database under test */
    public static DatabaseConfig primary(String dbmsName, MainOptions options) {
        DatabaseConfig.Registry registry = Configuration.load(options);
        for (DatabaseConfig config : registry.forDBMS(dbmsName)) {
            return config;
        }
        // no explicit configuration: synthesise a default one for the requested engine
        DatabaseConfig config = new DatabaseConfig(dbmsName + "@default");
        applyConnectionOptions(config, options);
        return config;
    }

    /**
     * Copies the connection options given on the command line onto a configuration that has none.
     *
     * <p>Only client/server engines need this: an embedded engine takes everything from its JDBC URL,
     * and overriding its host or port would be meaningless. It used to be done for PostgreSQL alone,
     * so {@code --host}, {@code --port} and {@code --database} were silently ignored for MySQL.</p>
     */
    private static void applyConnectionOptions(DatabaseConfig config, MainOptions options) {
        if (config.isEmbedded()) {
            return;
        }
        config.setHost(options.getHost()).setPort(options.getPort()).setUsername(options.getUsername())
                .setPassword(options.getPassword()).setDatabase(options.getDatabase());
    }

    /** @return the databases to compare against, as requested by {@code --compare-with} */
    public static List<DatabaseConfig> comparisons(MainOptions options) {
        List<DatabaseConfig> result = new ArrayList<>();
        if (options.getCompareWith() == null || options.getCompareWith().isEmpty()) {
            return result;
        }
        DatabaseConfig.Registry registry = Configuration.load(options);
        for (String requested : options.getCompareWith()) {
            for (String token : requested.split(",")) {
                String name = token.trim();
                if (name.isEmpty()) {
                    continue;
                }
                DatabaseConfig found = null;
                // an exact config key wins, then the first entry for that dbms
                for (DatabaseConfig config : registry.all()) {
                    if (config.getKey().equalsIgnoreCase(name)) {
                        found = config;
                        break;
                    }
                }
                if (found == null) {
                    found = registry.first(name).orElse(null);
                }
                if (found == null) {
                    DatabaseConfig config = new DatabaseConfig(name + "@default");
                    applyConnectionOptions(config, options);
                    found = config;
                }
                if (!result.contains(found)) {
                    result.add(found);
                }
            }
        }
        return result;
    }

    /** Opens a JDBC connection for the given configuration. */
    public static DDLSmithDBConnection open(DatabaseConfig config, String databaseName) throws SQLException {
        return open(config, databaseName, false);
    }

    /**
     * Opens a JDBC connection for the given configuration.
     *
     * @param keepDatabase when false, the file of an embedded file-based engine is deleted first, so
     *        that every run starts from a genuinely empty database. Without this, a second run would
     *        find the tables of the first one and every oracle would report differences caused by
     *        leftover state rather than by the engine.
     */
    public static DDLSmithDBConnection open(DatabaseConfig config, String databaseName, boolean keepDatabase)
            throws SQLException {
        String url = config.jdbcUrl(databaseName);
        if (!keepDatabase) {
            deleteEmbeddedFile(url);
        }
        ensureParentDirectory(url);
        Properties properties = new Properties();
        if (config.getUsername() != null) {
            properties.setProperty("user", config.getUsername());
        }
        if (config.getPassword() != null) {
            properties.setProperty("password", config.getPassword());
        }
        properties.putAll(config.getProperties());
        if (config.getDBMSName().equals("sqlite")) {
            // SQLite needs busy timeouts to survive concurrent access to the same file
            properties.putIfAbsent("busy_timeout", "5000");
        }
        Connection connection = DriverManager.getConnection(url, properties);
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            // engines that do not support autocommit control: ignore
        }
        return new DDLSmithDBConnection(connection, databaseName);
    }

    /**
     * Deletes the file backing an embedded database, together with the sidecar files the engines
     * create next to it, so that the next connection sees an empty database.
     *
     * <p>Failing to delete is not a warning. A leftover database already contains the objects of an
     * earlier run, so every statement of the new run that tries to create them fails, and the oracles
     * then report a schema that has nothing to do with the input under test. That is the one failure
     * mode a testing tool must never have — a wrong answer that looks like a finding — so a file that
     * cannot be removed ends the run instead.</p>
     */
    private static void deleteEmbeddedFile(String url) {
        String path = filePathOf(url);
        if (path == null) {
            return;
        }
        for (String candidate : sidecarPathsOf(path)) {
            File sidecar = new File(candidate);
            if (!sidecar.isFile()) {
                continue;
            }
            // a database file may still be held by a JVM that is shutting down, so a few retries are
            // enough to distinguish a transient lock from a file this process may not touch
            boolean deleted = false;
            for (int attempt = 0; attempt < 10 && !deleted; attempt++) {
                deleted = sidecar.delete();
                if (!deleted) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!deleted && sidecar.isFile()) {
                throw new org.ddlsmith.DatabaseUnavailableException(
                        "a database file from an earlier run could not be deleted: " + sidecar.getAbsolutePath()
                                + ". Testing would continue against a database that already contains objects, which "
                                + "produces findings that say nothing about the engine. Close whatever is holding "
                                + "the file, delete it, or pass --keep-databases to reuse it deliberately.",
                        null);
            }
        }
    }

    /** @return every file an embedded engine may create next to the database itself */
    private static String[] sidecarPathsOf(String path) {
        return new String[] { path, path + "-journal", path + "-wal", path + "-shm", path + ".wal", path + ".tmp",
                // H2 stores a file database as <name>.mv.db, with optional trace and lock files
                path + ".mv.db", path + ".trace.db", path + ".lock.db" };
    }

    /** @return the filesystem path of an embedded database URL, or null when it is not file based */
    private static String filePathOf(String url) {
        String prefix;
        if (url.startsWith("jdbc:sqlite:")) {
            prefix = "jdbc:sqlite:";
        } else if (url.startsWith("jdbc:duckdb:")) {
            prefix = "jdbc:duckdb:";
        } else if (url.startsWith("jdbc:h2:")) {
            // H2 names its database in the URL: mem: is in memory, tcp:/ssl: are remote, and anything
            // else (including an explicit file:) is a file whose name gets the .mv.db suffix
            String rest = url.substring("jdbc:h2:".length());
            if (rest.startsWith("mem:") || rest.startsWith("tcp:") || rest.startsWith("ssl:")
                    || rest.startsWith("zip:")) {
                return null;
            }
            if (rest.startsWith("file:")) {
                rest = rest.substring("file:".length());
            }
            int semicolon = rest.indexOf(';');
            if (semicolon >= 0) {
                rest = rest.substring(0, semicolon);
            }
            return rest.isEmpty() ? null : rest;
        } else {
            return null;
        }
        String path = url.substring(prefix.length());
        if (path.isEmpty() || path.startsWith(":memory:") || path.startsWith("file:") || path.startsWith("mem:")) {
            return null;
        }
        return path;
    }

    private static void ensureParentDirectory(String url) {
        String prefix;
        if (url.startsWith("jdbc:sqlite:")) {
            prefix = "jdbc:sqlite:";
        } else if (url.startsWith("jdbc:duckdb:")) {
            prefix = "jdbc:duckdb:";
        } else {
            return;
        }
        String path = url.substring(prefix.length());
        if (path.isEmpty() || path.startsWith(":memory:") || path.startsWith("file:")) {
            return;
        }
        File file = new File(path);
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            System.err.println("warning: could not create directory " + parent.getAbsolutePath());
        }
    }

    /** @return the list of dbms names for which a driver is on the classpath and a target is configured */
    public static List<String> available(MainOptions options) {
        List<String> names = new ArrayList<>();
        for (DatabaseConfig config : Configuration.load(options).all()) {
            if (!names.contains(config.getDBMSName())) {
                names.add(config.getDBMSName());
            }
        }
        return names;
    }
}
