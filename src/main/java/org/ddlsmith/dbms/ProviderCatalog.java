package org.ddlsmith.dbms;

import java.util.List;

import org.ddlsmith.DatabaseProvider;

/**
 * Single place that lists the database providers compiled into DDLsmith.
 *
 * <p>Kept separate from {@link org.ddlsmith.ProviderRegistry} so that the frozen core contract can
 * be compiled independently of the engine specific providers.</p>
 */
public final class ProviderCatalog {

    private ProviderCatalog() {
    }

    public static List<DatabaseProvider<?, ?, ?>> providers() {
        return List.of(
                new org.ddlsmith.dbms.providers.SqliteProvider(),
                new org.ddlsmith.dbms.providers.H2Provider(),
                new org.ddlsmith.dbms.providers.DuckDbProvider(),
                new org.ddlsmith.dbms.providers.PostgresProvider(),
                new org.ddlsmith.dbms.providers.MySqlProvider());
    }
}
