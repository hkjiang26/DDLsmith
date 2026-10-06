package org.ddlsmith.dbms;

import org.ddlsmith.DBMSSpecificOptions;
import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.GlobalState;

/**
 * The global state used by all JDBC providers.
 *
 * <p>Unlike GDsmith, DDLSmith does not need one state class per engine: everything engine specific
 * lives in the {@link org.ddlsmith.ddl.Dialect} and the
 * {@link org.ddlsmith.introspection.SchemaIntrospector}, which the provider attaches to the state.
 * The state itself only carries the generated input and the execution log.</p>
 */
public class SimpleGlobalState<O extends DBMSSpecificOptions<?>> extends GlobalState<O, DDLSmithDBConnection> {

    public SimpleGlobalState() {
        super();
    }
}
