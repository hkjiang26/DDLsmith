package org.ddlsmith.oracle;

import java.util.Optional;

/**
 * An oracle decides whether the observed behaviour of a database is a defect.
 *
 * <p>DDLSmith's oracles are all black-box: they only use SQL that any client could send, and they
 * compare what the engine reports about itself (catalog contents, error behaviour, query results)
 * against what the generated ER diagram says should be true.</p>
 */
public interface TestOracle {

    /** The name used to select this oracle on the command line. */
    String getName();

    /** A one-line description printed by {@code --help} style listings. */
    String getDescription();

    /**
     * @return a failure if the observed behaviour is wrong, or an empty optional otherwise
     */
    Optional<OracleFailure> verify(OracleContext context) throws Exception;
}
