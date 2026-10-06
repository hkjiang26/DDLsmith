package org.ddlsmith.dbms.providers;

import org.ddlsmith.dbms.AbstractJdbcProvider;

/**
 * The H2: an embedded engine with a strict, standards oriented DDL implementation.
 */
public class H2Provider extends AbstractJdbcProvider {

    @Override
    protected String engineName() {
        return "h2";
    }
}
