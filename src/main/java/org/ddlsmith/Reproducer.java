package org.ddlsmith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.dbms.DatabaseConfig;
import org.ddlsmith.dbms.Targets;

/**
 * The {@code --reproduce <log>} mode: it replays the statements recorded in a failure log against
 * the database of the same engine, so that a finding can be replayed and minimised by hand.
 */
public final class Reproducer {

    private Reproducer() {
    }

    public static int run(MainOptions options, String dialectName, Path logFile) {
        List<String> statements;
        try {
            statements = readStatements(logFile);
        } catch (IOException e) {
            System.err.println("could not read " + logFile + ": " + e.getMessage());
            return options.getErrorExitCode();
        }
        if (statements.isEmpty()) {
            System.err.println("no statements found in " + logFile);
            return options.getErrorExitCode();
        }
        DatabaseConfig target = Targets.primary(dialectName, options);
        if (options.getDatabase() != null) {
            target.setDatabase(options.getDatabase());
        }
        String databaseName = "reproduce";
        org.ddlsmith.ddl.Dialect dialect = org.ddlsmith.dbms.Dialects.forName(dialectName);
        System.out.println("replaying " + statements.size() + " statements from " + logFile + " on " + dialectName);
        int executed = 0;
        try (DDLSmithDBConnection connection = Targets.open(target, databaseName)) {
            // The replay namespace is derived and prepared exactly as AbstractJdbcProvider prepares it
            // for a run, and for the same reason. Without this a replay on a client/server engine
            // inherits whatever the previous replay left behind: the second replay of one log stops at
            // its first statement with "table already exists", which this method then reports as
            // "reproduced after 0 successful statement(s)" and exit 0 — the opposite of what happened,
            // and the replay harness reads exit 0 as a faithful reproduction. For an embedded engine
            // both hook lists are empty, so this is the session setup it has always run.
            String namespace = dialect.schemaNameForGeneratedDatabase(options.getSchemaName(), databaseName);
            for (String setup : dialect.dropNamespaceStatements(namespace)) {
                runSetupQuietly(connection, setup);
            }
            for (String setup : dialect.createNamespaceStatements(namespace)) {
                runSetupQuietly(connection, setup);
            }
            for (String setup : dialect.getSessionSetupStatements(namespace)) {
                connection.executeStatement(setup);
            }
            for (String statement : statements) {
                try {
                    connection.executeStatement(statement);
                    executed++;
                } catch (SQLException e) {
                    System.out.println();
                    System.out.println("statement " + (executed + 1) + " failed:");
                    System.out.println("  " + statement);
                    System.out.println("  " + e.getMessage() + " (SQLState " + e.getSQLState() + ", code "
                            + e.getErrorCode() + ")");
                    System.out.println();
                    System.out.println("reproduced after " + executed + " successful statement(s)");
                    return 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("could not connect to " + dialectName + ": " + e.getMessage());
            return options.getErrorExitCode();
        }
        System.out.println("all " + executed + " statements executed successfully: the failure did not reproduce");
        return 1;
    }

    /**
     * Runs a statement that clears the replay namespace. A failure is a warning rather than an error:
     * both of MySQL's statements are {@code IF EXISTS}/{@code IF NOT EXISTS}, and an engine that keeps
     * no namespace of its own gets an empty list here rather than a failure.
     */
    private static void runSetupQuietly(DDLSmithDBConnection connection, String sql) {
        try {
            connection.executeStatement(sql);
        } catch (Exception e) {
            System.err.println("warning: replay setup statement failed: " + sql + " (" + e.getMessage() + ")");
        }
    }

    /** Extracts executable statements from a DDLSmith log, ignoring its comment and report lines. */
    static List<String> readStatements(Path logFile) throws IOException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            // the failure report starts with a banner; everything after it is prose, not SQL
            if (line.startsWith("===") || line.startsWith("DDLSmith failure")) {
                break;
            }
            if (line.startsWith("--")) {
                continue;
            }
            current.append(rawLine).append('\n');
            if (line.endsWith(";")) {
                String statement = current.toString().trim();
                statements.add(statement.substring(0, statement.length() - 1));
                current.setLength(0);
            }
        }
        return statements;
    }
}
