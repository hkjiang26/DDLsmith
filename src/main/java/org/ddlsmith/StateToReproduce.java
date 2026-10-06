package org.ddlsmith;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures everything needed to reproduce a failure: the seed, the DBMS identity and the exact
 * sequence of statements that was executed.
 */
public class StateToReproduce {

    public String databaseName;
    public String databaseVersion;
    public long seedValue;
    public String exception;

    /** The full DDL/DML script executed for this database. */
    private final List<String> statements = new ArrayList<>();

    /** The script that creates the ER diagram / relational schema. */
    private final List<String> createStatements = new ArrayList<>();

    public StateToReproduce(String databaseName) {
        this.databaseName = databaseName;
    }

    public void logStatement(String statement) {
        if (!statement.endsWith(";")) {
            statement = statement + ";";
        }
        statements.add(statement);
    }

    public void logCreateStatement(String statement) {
        if (!statement.endsWith(";")) {
            statement = statement + ";";
        }
        createStatements.add(statement);
    }

    public List<String> getStatements() {
        return statements;
    }

    public List<String> getCreateStatements() {
        return createStatements;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getDatabaseVersion() {
        return databaseVersion;
    }

    public long getSeedValue() {
        return seedValue;
    }
}
