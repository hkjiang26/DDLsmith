package org.ddlsmith;

/**
 * Thrown when a generated input is known to be uninteresting (e.g. the DBMS does not support a
 * feature) so the current test round can be abandoned without recording a failure.
 */
public class IgnoreMeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IgnoreMeException() {
        super();
    }

    public IgnoreMeException(String message) {
        super(message);
    }

    public IgnoreMeException(String message, Throwable cause) {
        super(message, cause);
    }
}
