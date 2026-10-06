package org.ddlsmith;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.common.query.QueryResult;

/**
 * Wraps a JDBC connection to the database under test.
 *
 * <p>All statements issued by DDLSmith go through this class so that the exact executed SQL can be
 * logged for reproduction.</p>
 */
public class DDLSmithDBConnection implements AutoCloseable {

    private final Connection connection;
    private final String databaseName;
    private boolean closed;

    public DDLSmithDBConnection(Connection connection, String databaseName) {
        this.connection = connection;
        this.databaseName = databaseName;
    }

    public Connection getConnection() {
        return connection;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    /** @return the version string reported by the engine, used to distinguish DBMS versions in logs */
    public String getDatabaseVersion() throws SQLException {
        return connection.getMetaData().getDatabaseProductVersion();
    }

    public String getDatabaseProductName() throws SQLException {
        return connection.getMetaData().getDatabaseProductName();
    }

    /** Executes a statement that does not return a result set (DDL, DML). */
    public void executeStatement(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Executes a statement and materializes its result set. */
    public QueryResult executeAndGet(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return materialize(rs);
        }
    }

    /** Executes a statement, ignoring whether it produces a result set, and materializes one if present. */
    public QueryResult executeAnyAndGet(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            boolean hasResultSet = statement.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = statement.getResultSet()) {
                    return materialize(rs);
                }
            }
            return QueryResult.empty();
        }
    }

    public static QueryResult materialize(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columnNames = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columnNames.add(metaData.getColumnLabel(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return new QueryResult(columnNames, rows);
    }

    public void commit() throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.commit();
        }
    }

    public void rollback() throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.rollback();
        }
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException {
        connection.setAutoCommit(autoCommit);
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            connection.close();
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
