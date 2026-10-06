package org.ddlsmith.dbms.providers;

import org.ddlsmith.dbms.AbstractJdbcProvider;

/**
 * The DuckDB: an embedded analytical engine whose DDL support is deliberately partial.
 */
public class DuckDbProvider extends AbstractJdbcProvider {

    @Override
    protected String engineName() {
        return "duckdb";
    }
}
