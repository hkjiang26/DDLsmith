package org.ddlsmith.dbms.providers;

import org.ddlsmith.dbms.AbstractJdbcProvider;

/**
 * The PostgreSQL: a client/server engine with the richest DDL support.
 */
public class PostgresProvider extends AbstractJdbcProvider {

    @Override
    protected String engineName() {
        return "postgres";
    }
}
