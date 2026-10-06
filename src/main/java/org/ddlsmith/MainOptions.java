package org.ddlsmith;

import java.util.ArrayList;
import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import org.ddlsmith.Randomly.StringGenerationStrategy;

/**
 * Options shared by every DBMS provider of DDLSmith (mirrors GDsmith's {@code MainOptions}).
 */
@Parameters(separators = "=", commandDescription = "Random graph -> ER diagram -> relational DDL testing")
public class MainOptions {

    @Parameter(description = "<dbms>  the database to test: sqlite, h2, duckdb, postgres or mysql")
    private List<String> dbms = new ArrayList<>();

    @Parameter(names = "--help", description = "Prints the list of available options", help = true)
    private boolean help;

    @Parameter(names = "--url", description = "An explicit JDBC URL for the database under test, overriding config.json.")
    private String url;

    @Parameter(names = "--ddl-features", description = "Comma-separated list of extra DDL features to assume the engine supports.")
    private List<String> extraDdlFeatures = new ArrayList<>();

    @Parameter(names = "--disable-ddl-features", description = "Comma-separated list of DDL features to assume the engine does NOT support.")
    private List<String> disabledDdlFeatures = new ArrayList<>();

    public List<String> getDbms() {
        return dbms;
    }

    public String getUrl() {
        return url;
    }

    public List<String> getExtraDdlFeatures() {
        return extraDdlFeatures;
    }

    public List<String> getDisabledDdlFeatures() {
        return disabledDdlFeatures;
    }

    @Parameter(names = { "--random-seed", "-s" }, description = "The seed to use for the random generation. If not set, the current time is used.")
    private long randomSeed = -1;

    @Parameter(names = "--num-tries", description = "The number of ER diagrams (i.e. databases) to generate in the run "
            + "(a total, not a per-thread count).")
    private int totalNumberTries = 1;

    @Parameter(names = "--num-ddl", description = "The number of DDL statements generated per ER diagram.")
    private int numDdlStatements = 50;

    @Parameter(names = "--threads", description = "The number of concurrent threads.")
    private int numberConcurrentThreads = 1;

    @Parameter(names = "--timeout-seconds", description = "The time in seconds after which the tool stops; -1 means unlimited.")
    private int timeoutSeconds = -1;

    @Parameter(names = "--database-prefix", description = "The prefix used when naming generated databases.")
    private String databasePrefix = "database";

    /**
     * Whether every executed statement is also written to the live per-database log
     * ({@code logs/<engine>/<database>-cur.log}) and echoed to stderr.
     *
     * <p>It is off by default because it is a debugging aid, not part of reproduction: the statements
     * of a database are always recorded in memory and written into every failure log, so
     * {@code --reproduce} works without this option. The option used to be declared as on while
     * nothing ever read the log it promised, which is why it is now off explicitly and honoured.</p>
     */
    @Parameter(names = "--log-each-select", description = "Logs every generated statement as it is executed, both to "
            + "logs/<engine>/<database>-cur.log and to stderr (default off; failure logs always record the statements).")
    private boolean logEachSelect;

    @Parameter(names = "--print-progress-information", description = "Whether to print progress information.")
    private boolean printProgressInformation = true;

    @Parameter(names = "--print-progress-summary", description = "Whether to print an overall execution summary at the end.")
    private boolean printProgressSummary = true;

    @Parameter(names = "--error-exit-code", description = "The exit code to use when a failure is detected.")
    private int errorExitCode = 1;

    @Parameter(names = "--perform-connection-test", description = "Whether to test the connection before running.")
    private boolean performConnectionTest;

    @Parameter(names = "--max-string-length", description = "The maximum length of generated strings.")
    private int maxStringLength = 10;

    @Parameter(names = "--use-constant-caching", description = "Whether constants are cached and re-used.")
    private boolean useConstantCaching = true;

    @Parameter(names = "--string-generation-strategy", description = "How strings are generated: ALPHANUMERIC, NUMERIC, ALPHANUMERIC_SPECIALCHAR, SOPHISTICATED.")
    private StringGenerationStrategy stringGenerationStrategy = StringGenerationStrategy.ALPHANUMERIC;

    @Parameter(names = "--max-node-num", description = "The maximum number of nodes (entities) of a generated graph.")
    private int maxNodeNum = 40;

    @Parameter(names = "--min-node-num", description = "The minimum number of nodes (entities) of a generated graph.")
    private int minNodeNum = 6;

    @Parameter(names = "--oracle", description = "The comma-separated list of oracles to use, or ALL.")
    private List<String> oracles = new ArrayList<>();

    @Parameter(names = "--algorithm", description = "The DDL testing algorithm to use.")
    private String algorithm = "random";

    @Parameter(names = "--print-ddl-only", description = "Only print the generated DDL to stdout without connecting to a database.")
    private boolean printDdlOnly;

    @Parameter(names = "--reproduce", description = "Path of a failure log file to replay instead of generating new input.")
    private String reproduceFile;

    @Parameter(names = "--ddl-dump-dir", description = "If set, every generated DDL script is written to this directory.")
    private String ddlDumpDir;

    @Parameter(names = "--bug-dir", description = "Directory for the reports of the defects found, one file per distinct "
            + "defect (default: bugs). Use 'off' to disable.")
    private String bugDirectory = "bugs";

    @Parameter(names = "--schema-name", description = "The schema/namespace name used for generated objects.")
    private String schemaName = "ddlsmith";

    @Parameter(names = "--keep-databases", description = "Whether generated databases are kept for manual inspection.")
    private boolean keepDatabases;

    @Parameter(names = "--quiet", description = "Suppress informational output on stdout.")
    private boolean quiet;

    @Parameter(names = "--config", description = "Path of the configuration file describing the databases to connect to.")
    private String configFile;

    @Parameter(names = "--compare-with", description = "Comma-separated list of databases to compare against for differential testing, e.g. h2,duckdb or postgres@server.")
    private List<String> compareWith = new ArrayList<>();

    @Parameter(names = "--graph-generator", description = "The graph generator to use: random, sliding, tree, preferential, smallworld, hybrid, or mix.")
    private String graphGenerator = "mix";

    @Parameter(names = "--evolve-steps", description = "The number of random ALTER TABLE statements generated after the schema has been created.")
    private int evolveSteps = 0;

    @Parameter(names = "--insert-rows", description = "The number of rows per table inserted before the constraint oracles run.")
    private int rowsPerTable = 3;

    @Parameter(names = "--max-constraint-probes", description = "The maximum number of constraint probes executed per database.")
    private int maxConstraintProbes = 25;

    @Parameter(names = "--host", description = "The host of the database server (for client/server engines).")
    private String host = "localhost";

    @Parameter(names = "--port", description = "The port of the database server (for client/server engines).")
    private int port = -1;

    @Parameter(names = "--username", description = "The user name used to connect to the database server.")
    private String username;

    @Parameter(names = "--password", description = "The password used to connect to the database server.")
    private String password;

    @Parameter(names = "--database", description = "The database name used when connecting to a server.")
    private String database;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port == -1 ? null : port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public List<String> getCompareWith() {
        return compareWith;
    }

    public String getGraphGenerator() {
        return graphGenerator;
    }

    public int getEvolveSteps() {
        return evolveSteps;
    }

    public int getRowsPerTable() {
        return rowsPerTable;
    }

    public int getMaxConstraintProbes() {
        return maxConstraintProbes;
    }

    public void setCompareWith(List<String> compareWith) {
        this.compareWith = compareWith;
    }

    public void setGraphGenerator(String graphGenerator) {
        this.graphGenerator = graphGenerator;
    }

    public void setEvolveSteps(int evolveSteps) {
        this.evolveSteps = evolveSteps;
    }

    public void setRowsPerTable(int rowsPerTable) {
        this.rowsPerTable = rowsPerTable;
    }

    public boolean isHelp() {
        return help;
    }

    public long getRandomSeed() {
        return randomSeed;
    }

    public int getTotalNumberTries() {
        return totalNumberTries;
    }

    public int getNumDdlStatements() {
        return numDdlStatements;
    }

    public int getNumberConcurrentThreads() {
        return numberConcurrentThreads;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getDatabasePrefix() {
        return databasePrefix;
    }

    public boolean logEachSelect() {
        return logEachSelect;
    }

    public boolean printProgressInformation() {
        return printProgressInformation;
    }

    public boolean printProgressSummary() {
        return printProgressSummary;
    }

    public int getErrorExitCode() {
        return errorExitCode;
    }

    public boolean performConnectionTest() {
        return performConnectionTest;
    }

    public int getMaxStringLength() {
        return maxStringLength;
    }

    public boolean useConstantCaching() {
        return useConstantCaching;
    }

    public StringGenerationStrategy getStringGenerationStrategy() {
        return stringGenerationStrategy;
    }

    public int getMaxNodeNum() {
        return maxNodeNum;
    }

    public int getMinNodeNum() {
        return minNodeNum;
    }

    public List<String> getOracles() {
        return oracles;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public boolean isPrintDdlOnly() {
        return printDdlOnly;
    }

    public String getReproduceFile() {
        return reproduceFile;
    }

    public String getDdlDumpDir() {
        return ddlDumpDir;
    }

    public String getBugDirectory() {
        return bugDirectory;
    }

    public void setBugDirectory(String bugDirectory) {
        this.bugDirectory = bugDirectory;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public boolean isKeepDatabases() {
        return keepDatabases;
    }

    public boolean isQuiet() {
        return quiet;
    }

    public void setRandomSeed(long randomSeed) {
        this.randomSeed = randomSeed;
    }

    public void setNumDdlStatements(int numDdlStatements) {
        this.numDdlStatements = numDdlStatements;
    }

    public void setMaxNodeNum(int maxNodeNum) {
        this.maxNodeNum = maxNodeNum;
    }

    public void setMinNodeNum(int minNodeNum) {
        this.minNodeNum = minNodeNum;
    }

    public void setOracles(List<String> oracles) {
        this.oracles = oracles;
    }

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }

    public void setTotalNumberTries(int totalNumberTries) {
        this.totalNumberTries = totalNumberTries;
    }

    public void setLogEachSelect(boolean logEachSelect) {
        this.logEachSelect = logEachSelect;
    }
}
