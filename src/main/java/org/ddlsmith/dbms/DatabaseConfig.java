package org.ddlsmith.dbms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The connection settings of one database that DDLsmith can test.
 *
 * <p>Entries are keyed like GDsmith's {@code config.json}: {@code <dbms>@<label>}, for instance
 * {@code postgres@16} or {@code sqlite@3.49}. The part before the {@code @} identifies the driver
 * and therefore which {@link org.ddlsmith.DatabaseProvider} can use the entry.</p>
 */
public class DatabaseConfig {

    private final String key;
    private String url;
    private String host = "localhost";
    private Integer port;
    private String username;
    private String password;
    private String database;
    private String restartCommand;
    private final Map<String, String> properties = new LinkedHashMap<>();

    public DatabaseConfig(String key) {
        this.key = key;
    }

    /** The part of the key before {@code @}. */
    public String getDBMSName() {
        int at = key.indexOf('@');
        return (at < 0 ? key : key.substring(0, at)).toLowerCase(java.util.Locale.ROOT);
    }

    /** The part of the key after {@code @}, or the whole key when there is none. */
    public String getLabel() {
        int at = key.indexOf('@');
        return at < 0 ? key : key.substring(at + 1);
    }

    public String getKey() {
        return key;
    }

    public String getUrl() {
        return url;
    }

    public DatabaseConfig setUrl(String url) {
        this.url = url;
        return this;
    }

    public String getHost() {
        return host;
    }

    public DatabaseConfig setHost(String host) {
        this.host = host;
        return this;
    }

    public Integer getPort() {
        return port;
    }

    public DatabaseConfig setPort(Integer port) {
        this.port = port;
        return this;
    }

    public String getUsername() {
        return username;
    }

    public DatabaseConfig setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getPassword() {
        return password;
    }

    public DatabaseConfig setPassword(String password) {
        this.password = password;
    return this;
    }

    public String getDatabase() {
        return database;
    }

    public DatabaseConfig setDatabase(String database) {
        this.database = database;
        return this;
    }

    public String getRestartCommand() {
        return restartCommand;
    }

    public DatabaseConfig setRestartCommand(String restartCommand) {
        this.restartCommand = restartCommand;
        return this;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    /** A stable identity used in reports. */
    public String getFullName() {
        return getDBMSName() + "@" + getLabel();
    }

    /**
     * @param databaseName the logical database name DDLsmith generated; substituted for the
     *        {@code {db}} placeholder in a configured URL
     * @return the JDBC URL to connect with
     */
    public String jdbcUrl(String databaseName) {
        if (url != null && !url.isBlank()) {
            return url.replace("{db}", databaseName);
        }
        switch (getDBMSName()) {
        case "sqlite":
            return "jdbc:sqlite:target/db/" + databaseName + ".db";
        case "h2":
            return "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1";
        case "duckdb":
            return "jdbc:duckdb:target/db/" + databaseName + ".duckdb";
        case "postgres":
        case "postgresql":
            return "jdbc:postgresql://" + host + ":" + (port == null ? 5432 : port) + "/"
                    + (database == null ? databaseName : database);
        case "mysql":
            return "jdbc:mysql://" + host + ":" + (port == null ? 3306 : port) + "/"
                    + (database == null ? databaseName : database);
        default:
            throw new IllegalArgumentException("no default JDBC URL known for " + getDBMSName());
        }
    }

    public boolean isEmbedded() {
        switch (getDBMSName()) {
        case "sqlite":
        case "h2":
        case "duckdb":
            return true;
        default:
            return false;
        }
    }

    @Override
    public String toString() {
        return getFullName() + " -> " + (url == null ? "(default url)" : url);
    }

    /** The list of configured databases, in declaration order. */
    public static final class Registry {
        private final List<DatabaseConfig> entries = new ArrayList<>();

        public Registry add(DatabaseConfig config) {
            entries.add(config);
            return this;
        }

        public List<DatabaseConfig> all() {
            return entries;
        }

        public List<DatabaseConfig> forDBMS(String dbmsName) {
            List<DatabaseConfig> result = new ArrayList<>();
            for (DatabaseConfig entry : entries) {
                if (entry.getDBMSName().equalsIgnoreCase(dbmsName)) {
                    result.add(entry);
                }
            }
            return result;
        }

        public Optional<DatabaseConfig> first(String dbmsName) {
            return forDBMS(dbmsName).stream().findFirst();
        }

        public boolean containsDBMS(String dbmsName) {
            return !forDBMS(dbmsName).isEmpty();
        }
    }
}
