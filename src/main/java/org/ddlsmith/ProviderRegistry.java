package org.ddlsmith;

import java.util.ArrayList;
import java.util.List;

/**
 * The list of database providers that DDLSmith can test. Providers are discovered statically so
 * that the shaded jar has no classpath scanning at startup.
 */
public final class ProviderRegistry {

    private ProviderRegistry() {
    }

    public static List<DatabaseProvider<?, ?, ?>> createProviders() {
        List<DatabaseProvider<?, ?, ?>> providers = new ArrayList<>();
        providers.addAll(org.ddlsmith.dbms.ProviderCatalog.providers());
        return providers;
    }
}
