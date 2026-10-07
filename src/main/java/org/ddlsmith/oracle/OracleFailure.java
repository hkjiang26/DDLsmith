package org.ddlsmith.oracle;

/**
 * A defect found by an oracle. The report is designed to be self-contained: it carries the seed,
 * the ER diagram, the intended schema and the exact SQL that reproduces the problem.
 */
public class OracleFailure {

    /** The name of the oracle that found the failure. */
    private final String oracleName;
    /** A short, human readable title, e.g. "foreign key not introspected". */
    private final String title;
    /** A detailed explanation, including expected and actual values. */
    private final String details;
    /** The statements that reproduce the failure. */
    private final String reproducerSql;
    /** The seed of the run. */
    private final long seed;
    /** The database(s) the failure was observed on. */
    private final String databases;
    /** Optional underlying exception. */
    private final Throwable cause;
    /** The kind of defect, used for grouping in the summary. */
    private final FailureKind kind;
    /**
     * Which database of its {@code --databases-per-graph} group found this. The seed names the graph
     * and the slot names which interpretation of that graph was tested, so both are needed to
     * regenerate the input. Left at its default when every database has a graph of its own.
     */
    private int inputSlot;
    private int databasesPerGraph = 1;

    public enum FailureKind {
        /** An unexpected error was raised for a statement the engine claims to support. */
        UNEXPECTED_ERROR,
        /** The engine accepted a statement but the resulting schema differs from the intended one. */
        SCHEMA_MISMATCH,
        /** Two engines disagree about the same schema. */
        DIFFERENTIAL,
        /** A declared constraint is not enforced. */
        CONSTRAINT_NOT_ENFORCED,
        /** A declared constraint rejects data it should accept. */
        CONSTRAINT_TOO_STRICT,
        /** A metamorphic relation between two DDL sequences is violated. */
        METAMORPHIC,
        /** A statement is not idempotent although the engine declares support for IF [NOT] EXISTS. */
        IDEMPOTENCE,
        /** A query returns a result inconsistent with the schema semantics. */
        DATA_MISMATCH,
        /** The engine's behavior differs from the SQL standard in an observable way. */
        STANDARD_DEVIATION
    }

    public OracleFailure(FailureKind kind, String oracleName, String title, String details, String reproducerSql,
            long seed, String databases, Throwable cause) {
        this.kind = kind;
        this.oracleName = oracleName;
        this.title = title;
        this.details = details;
        this.reproducerSql = reproducerSql;
        this.seed = seed;
        this.databases = databases;
        this.cause = cause;
    }

    public String getOracleName() {
        return oracleName;
    }

    public String getTitle() {
        return title;
    }

    public String getDetails() {
        return details;
    }

    public String getReproducerSql() {
        return reproducerSql;
    }

    public long getSeed() {
        return seed;
    }

    public String getDatabases() {
        return databases;
    }

    public Throwable getCause() {
        return cause;
    }

    public FailureKind getKind() {
        return kind;
    }

    public int getInputSlot() {
        return inputSlot;
    }

    public int getDatabasesPerGraph() {
        return databasesPerGraph;
    }

    /**
     * Records which database of a graph group found this failure. Called once, where the failures of a
     * database are reported, so that every report carries the identity of its input.
     */
    public OracleFailure setInputIdentity(int inputSlot, int databasesPerGraph) {
        this.inputSlot = inputSlot;
        this.databasesPerGraph = Math.max(1, databasesPerGraph);
        return this;
    }

    /** @return the text written into the failure log */
    public String toLogString() {
        StringBuilder sb = new StringBuilder();
        sb.append("================================================================================\n");
        sb.append("DDLSmith failure: ").append(title).append('\n');
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("kind      : ").append(kind).append('\n');
        sb.append("oracle    : ").append(oracleName).append('\n');
        sb.append("database  : ").append(databases).append('\n');
        sb.append("seed      : ").append(seed);
        if (databasesPerGraph > 1) {
            sb.append("   (slot ").append(inputSlot).append(" of ").append(databasesPerGraph)
                    .append(": this database shares its graph with the others of the group)");
        }
        sb.append('\n');
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(details).append('\n');
        if (reproducerSql != null && !reproducerSql.isBlank()) {
            sb.append("--------------------------------------------------------------------------------\n");
            sb.append("Reproducer:\n");
            sb.append(reproducerSql);
            if (!reproducerSql.endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (cause != null) {
            sb.append("--------------------------------------------------------------------------------\n");
            sb.append("Cause:\n");
            java.io.StringWriter writer = new java.io.StringWriter();
            cause.printStackTrace(new java.io.PrintWriter(writer));
            sb.append(writer);
        }
        sb.append("================================================================================\n");
        return sb.toString();
    }

    /** @return the file name safe summary used for the log file of this failure */
    public String toShortString() {
        String slot = databasesPerGraph > 1 ? ", slot=" + inputSlot + "/" + databasesPerGraph : "";
        return kind + ": " + title + " (seed=" + seed + slot + ", db=" + databases + ")";
    }

    @Override
    public String toString() {
        return toShortString();
    }
}
