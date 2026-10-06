package org.ddlsmith.dbms;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.ddlsmith.MainOptions;

/**
 * Loads the {@code config.json} file that describes how to connect to each database, following the
 * same convention as GDsmith: the key of an entry is {@code <dbms>@<label>}, and the entry carries
 * either an explicit JDBC {@code url} or host/port/credentials.
 *
 * <p>When no file is present, a built-in default configuration is used that makes the three
 * embedded engines (SQLite, H2, DuckDB) usable with no infrastructure at all. That is what allows
 * DDLSmith to run differential DDL testing out of the box.</p>
 */
public final class Configuration {

    private static final String DEFAULT_FILE = "config.json";

    private Configuration() {
    }

    public static DatabaseConfig.Registry load(MainOptions options) {
        File file = options.getConfigFile() == null ? new File(DEFAULT_FILE) : new File(options.getConfigFile());
        if (file.isFile()) {
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader));
            } catch (IOException | RuntimeException e) {
                System.err.println("warning: could not read " + file.getAbsolutePath() + " (" + e.getMessage()
                        + "); falling back to the built-in configuration");
            }
        }
        return builtIn();
    }

    private static DatabaseConfig.Registry parse(JsonElement root) {
        DatabaseConfig.Registry registry = new DatabaseConfig.Registry();
        if (root == null || !root.isJsonObject()) {
            return builtIn();
        }
        JsonObject object = root.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject value = entry.getValue().getAsJsonObject();
            DatabaseConfig config = new DatabaseConfig(entry.getKey());
            config.setUrl(string(value, "url"));
            if (value.has("host")) {
                config.setHost(string(value, "host"));
            }
            if (value.has("port")) {
                config.setPort(value.get("port").getAsInt());
            }
            config.setUsername(string(value, "username"));
            config.setPassword(string(value, "password"));
            config.setDatabase(string(value, "database"));
            config.setRestartCommand(string(value, "restart-command"));
            if (value.has("properties") && value.get("properties").isJsonObject()) {
                for (Map.Entry<String, JsonElement> property : value.getAsJsonObject("properties").entrySet()) {
                    config.getProperties().put(property.getKey(), property.getValue().getAsString());
                }
            }
            registry.add(config);
        }
        return registry;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    /** The configuration used when no {@code config.json} exists: three embedded engines. */
    public static DatabaseConfig.Registry builtIn() {
        DatabaseConfig.Registry registry = new DatabaseConfig.Registry();
        registry.add(new DatabaseConfig("sqlite@3.49").setUrl("jdbc:sqlite:target/db/{db}.db"));
        registry.add(new DatabaseConfig("h2@2.3").setUrl("jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1"));
        registry.add(new DatabaseConfig("duckdb@1.3").setUrl("jdbc:duckdb:target/db/{db}.duckdb"));
        registry.add(new DatabaseConfig("postgres@server").setHost("localhost").setPort(5432)
                .setUsername("postgres").setPassword("postgres").setDatabase("ddlsmith"));
        registry.add(new DatabaseConfig("mysql@server").setHost("localhost").setPort(3306)
                .setUsername("root").setPassword("root").setDatabase("ddlsmith"));
        return registry;
    }
}
