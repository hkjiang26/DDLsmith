package org.ddlsmith;

/**
 * Marker interface for the per-DBMS command line option objects registered as JCommander sub-commands.
 */
public interface DBMSSpecificOptions<F extends Enum<F>> {

    /** The features that the DBMS under test is expected to support. */
    java.util.List<F> getTestOracleFactory();
}
