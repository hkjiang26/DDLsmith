package org.ddlsmith.dbms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.ddlsmith.ddl.Dialect;

/**
 * The dialects compiled into DDLSmith.
 *
 * <p>Dialects are created on demand and cached, because rendering is stateless and the same
 * instance can be shared by every thread.</p>
 */
public final class Dialects {

    private static final Map<String, Supplier<Dialect>> FACTORIES = new LinkedHashMap<>();

    static {
        FACTORIES.put("sqlite", org.ddlsmith.dbms.sqlite.SqliteDialect::new);
        FACTORIES.put("h2", org.ddlsmith.dbms.h2.H2Dialect::new);
        FACTORIES.put("duckdb", org.ddlsmith.dbms.duckdb.DuckDbDialect::new);
        FACTORIES.put("postgres", org.ddlsmith.dbms.postgres.PostgresDialect::new);
        FACTORIES.put("postgresql", org.ddlsmith.dbms.postgres.PostgresDialect::new);
        FACTORIES.put("mysql", org.ddlsmith.dbms.mysql.MySqlDialect::new);
        FACTORIES.put("mariadb", org.ddlsmith.dbms.mysql.MySqlDialect::new);
    }

    private Dialects() {
    }

    public static Dialect forName(String name) {
        Supplier<Dialect> factory = FACTORIES.get(name.toLowerCase(java.util.Locale.ROOT));
        if (factory == null) {
            throw new IllegalArgumentException("unknown dialect '" + name + "'; known dialects: " + names());
        }
        return factory.get();
    }

    public static boolean exists(String name) {
        return FACTORIES.containsKey(name.toLowerCase(java.util.Locale.ROOT));
    }

    public static List<String> names() {
        return new ArrayList<>(FACTORIES.keySet());
    }
}
