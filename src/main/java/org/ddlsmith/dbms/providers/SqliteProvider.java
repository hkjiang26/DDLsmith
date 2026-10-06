package org.ddlsmith.dbms.providers;

import org.ddlsmith.dbms.AbstractJdbcProvider;

/**
 * The SQLite: an embedded, dynamically typed engine whose foreign keys are off by default.
 */
public class SqliteProvider extends AbstractJdbcProvider {

    @Override
    protected String engineName() {
        return "sqlite";
    }
}
