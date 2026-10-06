package org.ddlsmith.dbms.providers;

import org.ddlsmith.dbms.AbstractJdbcProvider;

/**
 * The MySQL: a client/server engine whose DDL syntax differs in many places.
 */
public class MySqlProvider extends AbstractJdbcProvider {

    @Override
    protected String engineName() {
        return "mysql";
    }
}
