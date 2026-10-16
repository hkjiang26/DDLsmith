package org.ddlsmith.dbms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.ddlsmith.introspection.SchemaIntrospector;

/**
 * The catalog introspectors compiled into DDLsmith, one per engine.
 *
 * <p>Each engine module provides its dialect and its introspector together, because the two must
 * agree on how types are spelled: the dialect renders a canonical type and the introspector parses
 * the engine's spelling of it back into the same canonical type.</p>
 */
public final class Introspectors {

    private static final Map<String, Supplier<SchemaIntrospector>> FACTORIES = new LinkedHashMap<>();

    static {
        FACTORIES.put("sqlite", org.ddlsmith.dbms.sqlite.SqliteIntrospector::new);
        FACTORIES.put("h2", org.ddlsmith.dbms.h2.H2Introspector::new);
        FACTORIES.put("duckdb", org.ddlsmith.dbms.duckdb.DuckDbIntrospector::new);
        FACTORIES.put("postgres", org.ddlsmith.dbms.postgres.PostgresIntrospector::new);
        FACTORIES.put("postgresql", org.ddlsmith.dbms.postgres.PostgresIntrospector::new);
        FACTORIES.put("mysql", org.ddlsmith.dbms.mysql.MySqlIntrospector::new);
        FACTORIES.put("mariadb", org.ddlsmith.dbms.mysql.MySqlIntrospector::new);
    }

    private Introspectors() {
    }

    public static SchemaIntrospector forName(String name) {
        Supplier<SchemaIntrospector> factory = FACTORIES.get(name.toLowerCase(Locale.ROOT));
        if (factory == null) {
            throw new IllegalArgumentException("unknown introspector '" + name + "'; known engines: " + names());
        }
        return factory.get();
    }

    public static boolean exists(String name) {
        return FACTORIES.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public static List<String> names() {
        return new ArrayList<>(FACTORIES.keySet());
    }
}
