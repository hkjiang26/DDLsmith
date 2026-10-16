package org.ddlsmith.oracle;

import java.sql.SQLException;

import org.ddlsmith.common.query.QueryResult;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;

/**
 * Executes statements for the oracles while recording the outcome in the {@link OracleContext}.
 *
 * <p>Recording whether a statement was <em>expected</em> to fail is what lets the crash oracle
 * distinguish "the engine rejected a valid DDL statement" (a finding) from "the engine correctly
 * rejected a constraint violation" (the desired behaviour).</p>
 */
public final class StatementRunner {

    private StatementRunner() {
    }

    /** Executes a statement that is expected to succeed and records the outcome. */
    public static ExecutionEvent execute(OracleContext context, DatabaseUnderTest database, String sql,
            String purpose) {
        return execute(context, database, sql, false, purpose);
    }

    /** Executes a statement, recording whether it succeeded and whether it was expected to fail. */
    public static ExecutionEvent execute(OracleContext context, DatabaseUnderTest database, String sql,
            boolean expectedToFail, String purpose) {
        // A dialect that cannot express an object renders an explanatory comment in its place, which is
        // useful in a DDL dump but is not a statement. Sending it to the engine produces a driver-level
        // error ("the prepared statement has been finalized" on SQLite) and the crash oracle then blames
        // the engine for a construct the user force-enabled with --ddl-features. Skipping it keeps the
        // engine out of it: the object simply does not exist, and the round-trip oracle reports the
        // mismatch truthfully as a missing object.
        if (isCommentOnly(sql)) {
            // expectedToFail, because DDLsmith itself knows the object will not appear: the dialect
            // cannot express it, so there is nothing for the engine to have done wrong. Flagging it as
            // an unexpected failure would make the crash oracle blame the engine for a construct the
            // user force-enabled, which is the opposite of a finding.
            ExecutionEvent event = new ExecutionEvent(database.getName(), sql, false,
                    "not executed: the dialect rendered a comment instead of a statement, so the object was never "
                            + "created",
                    null, 0, 0, true, purpose);
            context.addExecutionEvent(event);
            String reason = sql.trim();
            context.getTrace().note("skipped a comment-only rendering for purpose '" + purpose + "': " + reason);
            System.err.println("DDLsmith: warning: nothing was executed for purpose '" + purpose + "' because "
                    + database.getDialect().getName() + " cannot express it (" + reason
                    + "); a --ddl-features override for this construct has no effect");
            return event;
        }
        long start = System.nanoTime();
        boolean succeeded;
        String message = null;
        String sqlState = null;
        int errorCode = 0;
        try {
            database.getConnection().executeStatement(sql);
            succeeded = true;
        } catch (SQLException e) {
            succeeded = false;
            message = e.getMessage();
            sqlState = e.getSQLState();
            errorCode = e.getErrorCode();
        } catch (RuntimeException e) {
            succeeded = false;
            message = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        ExecutionEvent event = new ExecutionEvent(database.getName(), sql, succeeded, message, sqlState, errorCode,
                millis, expectedToFail, purpose);
        context.addExecutionEvent(event);
        database.getExecutedStatements().add((succeeded ? "" : "-- FAILED: ") + sql);
        // Record the statement for the failure log. This is what makes a failure log self-contained:
        // the replay mode reads statements out of the log, so a log that omitted them could never
        // reproduce anything, and the finding would be unreproducible for anyone without DDLsmith.
        if (context.getStateToReproduce() != null) {
            context.getStateToReproduce().logStatement(sql);
        }
        // --log-each-select: the same statement, written to the live per-database log as it happens
        writeToLiveLog(context, database, sql);
        if (succeeded) {
            // keep the run-wide counters meaningful: the algorithms execute everything through this
            // class, so without this the summary would report zero statements
            org.ddlsmith.Main.nrSuccessfulActions.incrementAndGet();
        }
        return event;
    }

    /**
     * Writes a statement to the live log of the database under test when {@code --log-each-select}
     * is on. This is the only place that knows every statement the engine receives, so it is where
     * the option has to be honoured; without it the option was accepted and did nothing.
     */
    private static void writeToLiveLog(OracleContext context, DatabaseUnderTest database, String sql) {
        org.ddlsmith.Main.StateLogger logger = database.getStateLogger();
        if (logger == null || context.getOptions() == null || !context.getOptions().logEachSelect()) {
            return;
        }
        logger.writeCurrent(sql);
    }

    /**
     * @return true if the text carries no executable SQL, only {@code --} comment lines and whitespace.
     *         A dialect uses such a rendering to explain why it cannot express an object.
     */
    public static boolean isCommentOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            return true;
        }
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                return false;
            }
        }
        return true;
    }

    /** Executes a query, recording it as an event and returning its result. */
    public static QueryResult query(OracleContext context, DatabaseUnderTest database, String sql, String purpose)
            throws SQLException {
        writeToLiveLog(context, database, sql);
        long start = System.nanoTime();
        try {
            QueryResult result = database.getConnection().executeAndGet(sql);
            org.ddlsmith.Main.nrQueries.incrementAndGet();
            context.addExecutionEvent(new ExecutionEvent(database.getName(), sql, true, null, null, 0,
                    (System.nanoTime() - start) / 1_000_000, false, purpose));
            return result;
        } catch (SQLException e) {
            context.addExecutionEvent(new ExecutionEvent(database.getName(), sql, false, e.getMessage(),
                    e.getSQLState(), e.getErrorCode(), (System.nanoTime() - start) / 1_000_000, false, purpose));
            throw e;
        }
    }

    /** Executes a query that is expected to fail, recording the outcome; returns true when it failed. */
    public static boolean queryExpectingFailure(OracleContext context, DatabaseUnderTest database, String sql,
            String purpose) {
        ExecutionEvent event = execute(context, database, sql, true, purpose);
        return !event.isSucceeded();
    }

    /** @return a short description of the failure recorded in an event */
    public static String describe(ExecutionEvent event) {
        if (event.isSucceeded()) {
            return "succeeded";
        }
        return "failed: " + event.getErrorMessage() + " (SQLState " + event.getSqlState() + ", code "
                + event.getErrorCode() + ")";
    }
}
