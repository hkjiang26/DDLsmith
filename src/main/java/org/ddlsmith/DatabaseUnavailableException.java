package org.ddlsmith;

/**
 * Thrown when the database under test cannot be reached at all, as opposed to a statement failing.
 *
 * <p>DDLsmith reports this separately because it is not a finding about the engine: it means the
 * test could not be run, which is a different outcome from "the engine behaved correctly" and from
 * "the engine misbehaved".</p>
 */
public class DatabaseUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseUnavailableException(String message) {
        super(message);
    }
}
