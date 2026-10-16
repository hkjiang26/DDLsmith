package org.ddlsmith;

import org.ddlsmith.common.log.LoggableFactory;

/**
 * A database provider encapsulates everything that is specific to one database management system:
 * how to connect to it, how to render DDL for it, how to introspect its catalog, and which
 * algorithm is used to generate and test databases.
 */
public interface DatabaseProvider<G extends GlobalState<O, C>, O extends DBMSSpecificOptions<?>, C extends DDLsmithDBConnection> {

    /** The name used on the command line; must be lowercase. */
    String getDBMSName();

    Class<G> getGlobalStateClass();

    Class<O> getOptionClass();

    /** Opens a connection and prepares the database so that DDL can be executed. */
    C createDatabase(G state) throws Exception;

    /** Runs the whole generate-and-test loop for one database. */
    void generateAndTestDatabase(G state) throws Exception;

    StateToReproduce getStateToReproduce(String databaseName);

    LoggableFactory getLoggableFactory();
}
