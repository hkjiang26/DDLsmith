package org.ddlsmith;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.beust.jcommander.JCommander;

import org.ddlsmith.common.log.Loggable;
import org.ddlsmith.common.log.LoggableFactory;
import org.ddlsmith.common.query.QueryResult;
import org.ddlsmith.oracle.OracleFailure;

/**
 * Entry point of DDLSmith.
 *
 * <p>The overall flow mirrors GDsmith: each thread repeatedly creates a fresh database, generates
 * an input for it, executes the generated statements, runs the oracles, and records every failure
 * in {@code logs/} together with the seed and the full statement script needed to reproduce it.</p>
 */
public final class Main {

    public static final File LOG_DIRECTORY = new File("logs");

    public static final AtomicLong nrQueries = new AtomicLong();
    public static final AtomicLong nrDatabases = new AtomicLong();
    public static final AtomicLong nrSuccessfulActions = new AtomicLong();
    public static final AtomicLong nrUnsuccessfulActions = new AtomicLong();
    public static final AtomicLong nrFailures = new AtomicLong();
    public static final AtomicLong nrGeneratedGraphs = new AtomicLong();
    /** Databases that could not be reached at all, which is not a finding about the engine. */
    public static final AtomicLong unavailableDatabases = new AtomicLong();

    /**
     * How many worker tasks have finished, used only by the progress line. It is an
     * {@link AtomicInteger} rather than a plain {@code volatile int} because several threads
     * increment it concurrently, and it must never be used to decide when the run is over: that
     * decision belongs to the executor alone (see {@code executeMain}).
     */
    private static final AtomicInteger threadsShutdown = new AtomicInteger();
    private static volatile boolean progressMonitorStarted;
    /**
     * The bug directory of the current run, kept so that the summary can report how many distinct
     * defects were filed. It is set once the options are parsed, and a run that writes nothing leaves
     * it null.
     */
    private static volatile org.ddlsmith.bug.BugRepository bugRepositoryForSummary;
    private static final Map<String, AtomicLong> FAILURES_BY_KIND = new ConcurrentHashMap<>();

    /**
     * The distinct ER-diagram and relational-schema profiles the run produced.
     *
     * <p>They exist to make {@code --databases-per-graph} visible: a group of K databases shares one
     * graph, so the graph shape repeats by construction and the summary has to report how much of the
     * input space was actually new rather than how many databases were tested.</p>
     */
    public static final java.util.Set<String> distinctErProfiles = ConcurrentHashMap.newKeySet();
    public static final java.util.Set<String> distinctSchemaProfiles = ConcurrentHashMap.newKeySet();

    static {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "ERROR");
        if (!LOG_DIRECTORY.exists() && !LOG_DIRECTORY.mkdir()) {
            System.err.println("warning: could not create log directory " + LOG_DIRECTORY.getAbsolutePath());
        }
    }

    private Main() {
    }

    /* ------------------------------------------------------------------ logging */

    /** Writes the log of one database under test and the failures that were found in it. */
    public static final class StateLogger {

        private final String databaseName;
        private final File dir;
        private final DatabaseProvider<?, ?, ?> provider;
        private final MainOptions options;
        private int failureCounter;
        private FileWriter currentFileWriter;
        private final File currentFile;

        private static final java.util.Set<String> INITIALIZED_PROVIDER_NAMES = ConcurrentHashMap.newKeySet();

        /** A writer that echoes to stderr as well, so a live run shows its statements. */
        private static final class AlsoWriteToConsoleFileWriter extends FileWriter {
            AlsoWriteToConsoleFileWriter(File file) throws IOException {
                super(file);
            }

            @Override
            public Writer append(CharSequence arg0) throws IOException {
                System.err.println(arg0);
                return super.append(arg0);
            }

            @Override
            public void write(String str) throws IOException {
                System.err.println(str);
                super.write(str);
            }
        }

        public StateLogger(String databaseName, DatabaseProvider<?, ?, ?> provider, MainOptions options) {
            this.databaseName = databaseName;
            this.provider = provider;
            this.options = options;
            this.dir = new File(LOG_DIRECTORY, provider.getDBMSName());
            ensureExists(dir);
            this.currentFile = new File(dir, databaseName + "-cur.log");
        }

        /**
         * Creates the log directory if it is not there yet.
         *
         * <p>Threads share one directory per engine, so this runs concurrently. It is deliberately
         * idempotent and unconditional rather than guarded by a "have I already done this" flag:
         * {@code createDirectories} succeeds on an existing directory, whereas a flag check races —
         * and losing that race means the directory is missing exactly when a finding needs to be
         * written, which turns a reportable engine defect into an exception in the reporter.</p>
         */
        private static void ensureExists(File dir) {
            if (INITIALIZED_PROVIDER_NAMES.contains(dir.getName()) && dir.isDirectory()) {
                return;
            }
            try {
                Files.createDirectories(dir.toPath());
            } catch (IOException e) {
                throw new AssertionError("could not create the log directory " + dir.getAbsolutePath(), e);
            }
            INITIALIZED_PROVIDER_NAMES.add(dir.getName());
        }

        public File getDirectory() {
            return dir;
        }

        /**
         * Returns the file a report with this name is to be written to, numbered when the name is
         * already taken.
         *
         * <p>A log name identifies a database index and a failure ordinal, not the run that produced
         * them: a second run in the same working directory - the next chunk of a campaign, a different
         * seed block - generates {@code database0} again and used to replace the first run's file
         * silently. The 2026-10-05 MySQL round recorded 12 failures and left 8 logs behind, and the
         * census that reads those logs therefore reported the defect a third smaller than it was. The
         * first writer keeps the documented name; every later one gets {@code -2}, {@code -3}, ... so
         * that one failure log can never destroy another.</p>
         *
         * <p>Synchronized because the threads of a run share one directory per engine and two of them
         * can reach this method at the same time; without it they could pick the same free name and the
         * race would put the silent overwrite back.</p>
         */
        private static synchronized File freeFile(File dir, String name) {
            File candidate = new File(dir, name);
            if (!candidate.exists()) {
                return candidate;
            }
            int dot = name.lastIndexOf('.');
            String stem = dot < 0 ? name : name.substring(0, dot);
            String extension = dot < 0 ? "" : name.substring(dot);
            for (int i = 2; i < 10000; i++) {
                candidate = new File(dir, stem + "-" + i + extension);
                if (!candidate.exists()) {
                    return candidate;
                }
            }
            return new File(dir, name);
        }

        public FileWriter getCurrentFileWriter() {
            if (currentFileWriter == null) {
                ensureExists(dir);
                try {
                    currentFileWriter = new AlsoWriteToConsoleFileWriter(currentFile);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            return currentFileWriter;
        }

        /** Records a statement as it is executed, so that a crash can be reproduced exactly. */
        public void writeCurrent(String input) {
            if (!options.logEachSelect()) {
                return;
            }
            try {
                getCurrentFileWriter().write(provider.getLoggableFactory().createLoggable(input).getLogString());
                currentFileWriter.flush();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        public void writeCurrent(StateToReproduce state) {
            if (!options.logEachSelect()) {
                return;
            }
            try {
                getCurrentFileWriter().write(renderState(state));
                currentFileWriter.flush();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        /** Writes a failure report to its own file and returns that file. */
        public File logFailure(OracleFailure failure, StateToReproduce state, String extraContext) {
            ensureExists(dir);
            // A report has to identify its input on its own. The caller already knows the slot, but
            // setting it here as well makes the log independent of which path filed the failure.
            if (state != null) {
                failure.setInputIdentity(state.getInputSlot(), options.getDatabasesPerGraph());
            }
            String name = String.format("%s-failure-%03d-%s.log", databaseName, ++failureCounter,
                    failure.getKind().name().toLowerCase());
            File file = freeFile(dir, name);
            StringBuilder sb = new StringBuilder();
            sb.append(renderState(state));
            if (extraContext != null && !extraContext.isBlank()) {
                sb.append("--------------------------------------------------------------------------------\n");
                sb.append(extraContext);
                if (!extraContext.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            sb.append('\n').append(failure.toLogString());
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(sb.toString());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            FAILURES_BY_KIND.computeIfAbsent(failure.getKind().name(), k -> new AtomicLong()).incrementAndGet();
            nrFailures.incrementAndGet();
            return file;
        }

        /** Writes an unexpected exception, with everything needed to reproduce it. */
        public File logException(Throwable throwable, StateToReproduce state, String extraContext) {
            ensureExists(dir);
            String name = String.format("%s-crash-%03d.log", databaseName, ++failureCounter);
            File file = freeFile(dir, name);
            StringBuilder sb = new StringBuilder();
            sb.append(renderState(state));
            if (extraContext != null && !extraContext.isBlank()) {
                sb.append(extraContext).append('\n');
            }
            sb.append(provider.getLoggableFactory().convertStacktraceToLoggable(throwable).getLogString());
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(sb.toString());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return file;
        }

        private String renderState(StateToReproduce state) {
            StringBuilder sb = new StringBuilder();
            sb.append(provider.getLoggableFactory()
                    .getInfo(state.getDatabaseName(), state.getDatabaseVersion(), state.getSeedValue()).getLogString());
            // The seed alone identifies the graph; when several databases share one graph, the slot
            // says which of them this log is about. It is written into every log - the live trace, a
            // failure report and a harness crash - for the same reason the seed is.
            if (options.getDatabasesPerGraph() > 1) {
                sb.append("-- slot    : ").append(state.getInputSlot()).append(" of ")
                        .append(options.getDatabasesPerGraph())
                        .append(" (this database shares its graph with the others of the group)\n");
            }
            for (String statement : state.getStatements()) {
                sb.append(statement).append('\n');
            }
            return sb.toString();
        }

        public void close() {
            if (currentFileWriter != null) {
                try {
                    currentFileWriter.close();
                } catch (IOException e) {
                    // ignore
                }
                currentFileWriter = null;
            }
        }
    }

    /* ------------------------------------------------------------------ execution */

    public static class DBMSExecutor<G extends GlobalState<O, C>, O extends DBMSSpecificOptions<?>, C extends DDLSmithDBConnection> {

        private final DatabaseProvider<G, O, C> provider;
        private final MainOptions options;
        private final O command;
        private final String databaseName;
        private final Randomly r;
        /**
         * Which database of its {@code --databases-per-graph} group this executor generates. The
         * worker thread is seeded with the group's seed, so the pipeline needs the slot to know which
         * interpretation of that shared graph this database stands for.
         */
        private final int inputSlot;
        private StateLogger logger;
        private StateToReproduce stateToRepro;

        public DBMSExecutor(DatabaseProvider<G, O, C> provider, MainOptions options, O command, String databaseName,
                Randomly r) {
            this(provider, options, command, databaseName, r, 0);
        }

        public DBMSExecutor(DatabaseProvider<G, O, C> provider, MainOptions options, O command, String databaseName,
                Randomly r, int inputSlot) {
            this.provider = provider;
            this.options = options;
            this.command = command;
            this.databaseName = databaseName;
            this.r = r;
            this.inputSlot = inputSlot;
        }

        private G createGlobalState() {
            try {
                return provider.getGlobalStateClass().getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        public O getCommand() {
            return command;
        }

        public StateLogger getLogger() {
            return logger;
        }

        public StateToReproduce getStateToReproduce() {
            return stateToRepro;
        }

        public void testConnection() throws Exception {
            G state = getInitializedGlobalState(options.getRandomSeed());
            try (C connection = provider.createDatabase(state)) {
                // nothing to do: we only want to know whether connecting works
            }
        }

        private G getInitializedGlobalState(long seed) {
            G state = createGlobalState();
            stateToRepro = provider.getStateToReproduce(databaseName);
            stateToRepro.seedValue = seed;
            stateToRepro.inputSlot = inputSlot;
            state.setState(stateToRepro);
            logger = new StateLogger(databaseName, provider, options);
            state.setRandomly(new Randomly());
            state.setDatabaseName(databaseName);
            state.setMainOptions(options);
            state.setDbmsSpecificOptions(command);
            return state;
        }

        public void run() throws Exception {
            G state = createGlobalState();
            stateToRepro = provider.getStateToReproduce(databaseName);
            stateToRepro.seedValue = r.getSeed();
            stateToRepro.inputSlot = inputSlot;
            state.setState(stateToRepro);
            logger = new StateLogger(databaseName, provider, options);
            state.setRandomly(r);
            state.setDatabaseName(databaseName);
            state.setMainOptions(options);
            state.setDbmsSpecificOptions(command);
            try (C connection = provider.createDatabase(state)) {
                try {
                    stateToRepro.databaseVersion = connection.getDatabaseVersion();
                } catch (Exception e) {
                    stateToRepro.databaseVersion = "unknown";
                }
                state.setConnection(connection);
                state.setStateLogger(logger);
                nrDatabases.incrementAndGet();
                provider.generateAndTestDatabase(state);
            } finally {
                logger.close();
            }
        }
    }

    public static class DBMSExecutorFactory<G extends GlobalState<O, C>, O extends DBMSSpecificOptions<?>, C extends DDLSmithDBConnection> {

        private final DatabaseProvider<G, O, C> provider;
        private final MainOptions options;
        private final O command;

        public DBMSExecutorFactory(DatabaseProvider<G, O, C> provider, MainOptions options) {
            this.provider = provider;
            this.options = options;
            this.command = createCommand();
        }

        private O createCommand() {
            try {
                return provider.getOptionClass().getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        public O getCommand() {
            return command;
        }

        public DatabaseProvider<G, O, C> getProvider() {
            return provider;
        }

        public DBMSExecutor<G, O, C> getDBMSExecutor(String databaseName, Randomly r) {
            return getDBMSExecutor(databaseName, r, 0);
        }

        /**
         * @param inputSlot which database of its {@code --databases-per-graph} group the executor
         *                  generates; every slot of a group shares the graph of the group's seed
         */
        public DBMSExecutor<G, O, C> getDBMSExecutor(String databaseName, Randomly r, int inputSlot) {
            try {
                return new DBMSExecutor<>(provider.getClass().getDeclaredConstructor().newInstance(), options, command,
                        databaseName, r, inputSlot);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    /* ------------------------------------------------------------------ providers */

    private static List<DatabaseProvider<?, ?, ?>> providers = new ArrayList<>();

    public static List<DatabaseProvider<?, ?, ?>> getDBMSProviders() {
        if (providers.isEmpty()) {
            providers.addAll(ProviderRegistry.createProviders());
        }
        return providers;
    }

    /* ------------------------------------------------------------------ main */

    public static void main(String[] args) {
        System.exit(executeMain(args));
    }

    public static int executeMain(String... args) {
        // The counters are static so that every part of the tool can increment them without passing a
        // context around. Resetting them here means a second run in the same JVM (a test, an embedded
        // use) starts from zero instead of inheriting the previous run's failures.
        nrQueries.set(0);
        nrDatabases.set(0);
        nrSuccessfulActions.set(0);
        nrUnsuccessfulActions.set(0);
        nrFailures.set(0);
        nrGeneratedGraphs.set(0);
        threadsShutdown.set(0);
        FAILURES_BY_KIND.clear();
        distinctErProfiles.clear();
        distinctSchemaProfiles.clear();
        unavailableDatabases.set(0);
        List<DatabaseProvider<?, ?, ?>> availableProviders = getDBMSProviders();
        Map<String, DBMSExecutorFactory<?, ?, ?>> nameToProvider = new LinkedHashMap<>();
        MainOptions options = new MainOptions();
        for (DatabaseProvider<?, ?, ?> provider : availableProviders) {
            String name = provider.getDBMSName();
            if (!name.equals(name.toLowerCase())) {
                throw new AssertionError(name + " should be lowercase!");
            }
            nameToProvider.put(name, new DBMSExecutorFactory<>(provider, options));
        }

        // A single argument object with the engine as a positional parameter, so that options may
        // appear in any order: `DDLSmith sqlite --num-tries 20` and
        // `DDLSmith --num-tries 20 sqlite` both work.
        JCommander jc = JCommander.newBuilder().addObject(options).programName("DDLSmith").build();
        try {
            jc.parse(args);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.err.println();
            jc.usage();
            return 1;
        }

        String dbmsName = options.getDbms().isEmpty() ? null : options.getDbms().get(0).toLowerCase();
        if (dbmsName == null || options.isHelp()) {
            jc.usage();
            System.out.println();
            System.out.println("Available databases : " + String.join(", ", nameToProvider.keySet()));
            System.out.println("Available algorithms: " + String.join(", ", org.ddlsmith.algorithm.Algorithms.names()));
            System.out.println("Available oracles   : " + String.join(", ", org.ddlsmith.oracle.Oracles.names()));
            System.out.println("Available generators: " + String.join(", ",
                    org.ddlsmith.graph.gen.GraphGenerators.names()));
            return options.isHelp() ? 0 : 1;
        }
        if (!nameToProvider.containsKey(dbmsName)) {
            System.err.println("unknown database '" + dbmsName + "'; available: "
                    + String.join(", ", nameToProvider.keySet()));
            return 1;
        }

        // The group size decides how the seed is split into (graph, slot), so an unusable value has to
        // be refused here rather than producing a silently different input. 999999 is the bound the slot
        // mixing relies on: the slot is folded into the seed as seed * 1000000 + slot.
        if (options.getDatabasesPerGraph() < 1) {
            System.err.println("--databases-per-graph must be at least 1, but is " + options.getDatabasesPerGraph());
            return 1;
        }
        if (options.getDatabasesPerGraph() > 999_999) {
            System.err.println("--databases-per-graph must be at most 999999, but is "
                    + options.getDatabasesPerGraph());
            return 1;
        }
        if (options.getInputSlot() < 0 || options.getInputSlot() >= options.getDatabasesPerGraph()) {
            System.err.println("--input-slot must be in [0, " + options.getDatabasesPerGraph() + "), but is "
                    + options.getInputSlot());
            return 1;
        }

        Randomly.initialize(options);

        // special modes that do not need a provider loop
        if (options.getReproduceFile() != null) {
            return Reproducer.run(options, dbmsName, java.nio.file.Path.of(options.getReproduceFile()));
        }
        if (options.isPrintDdlOnly()) {
            return DDLOnly.run(options, dbmsName);
        }

        // one bug directory per run, shared by every thread
        bugRepositoryForSummary = org.ddlsmith.bug.BugRepository.forOptions(options);

        if (!options.isQuiet()) {
            printBanner(dbmsName, options);
        }

        // --quiet silences the periodic progress line, which is what the option promises; the final
        // summary is a result rather than chatter and stays, unless it is switched off explicitly
        if (options.printProgressInformation() && !options.isQuiet()) {
            startProgressMonitor();
        }
        if (options.printProgressInformation() && options.printProgressSummary()) {
            Runtime.getRuntime().addShutdownHook(new Thread(Main::printSummary));
        }

        DBMSExecutorFactory<?, ?, ?> executorFactory = nameToProvider.get(dbmsName);

        if (options.performConnectionTest()) {
            try {
                executorFactory.getDBMSExecutor(options.getDatabasePrefix() + "connectiontest", new Randomly())
                        .testConnection();
            } catch (Exception e) {
                System.err.println("DDLSmith could not create a test database; check the host, port, username and "
                        + "password options.");
                e.printStackTrace();
                return options.getErrorExitCode();
            }
        }

        ExecutorService execService = Executors.newFixedThreadPool(options.getNumberConcurrentThreads());
        int databasesPerGraph = options.getDatabasesPerGraph();
        for (int i = 0; i < options.getTotalNumberTries(); i++) {
            final String databaseName = options.getDatabasePrefix() + i;
            // Databases of one group share the graph, so the worker is seeded with the group's seed and
            // told which slot of that group it generates. The pipeline re-seeds itself for the ER
            // interpretation and the mapping decisions afterwards; with a group size of 1 (the default)
            // the slot is always 0 and the stream is the single one it has always been.
            final int graphIndex = i / databasesPerGraph;
            final int inputSlot = options.getInputSlot() != 0 ? options.getInputSlot() : i % databasesPerGraph;
            final long seed = options.getRandomSeed() == -1 ? System.currentTimeMillis() + graphIndex
                    : options.getRandomSeed() + graphIndex;
            execService.execute(() -> {
                Thread.currentThread().setName(databaseName);
                Randomly.setSeed(seed);
                DBMSExecutor<?, ?, ?> executor = null;
                try {
                    executor = executorFactory.getDBMSExecutor(databaseName, new Randomly(), inputSlot);
                    executor.run();
                } catch (DatabaseUnavailableException e) {
                    System.err.println("DDLSmith: " + e.getMessage());
                    System.err.println("DDLSmith: the test could not be run. Start the database, add an entry for it "
                            + "to config.json, or pass --url.");
                    unavailableDatabases.incrementAndGet();
                } catch (IgnoreMeException e) {
                    // the input was not interesting; nothing to report
                } catch (Throwable throwable) {
                    // Anything reaching here is a defect in DDLSmith itself: an engine defect would have
                    // been reported as an OracleFailure by now. It must therefore fail the run — counting
                    // it only as an unsuccessful action would let the process exit 0 after a crash in the
                    // harness, which is exactly the kind of silent pass this tool exists to prevent.
                    System.err.println("DDLSmith: unexpected failure in thread " + databaseName);
                    throwable.printStackTrace();
                    // A stack trace alone does not say which database and seed produced it, and the seed
                    // is the only handle on the input. The crash is therefore written to a log file too,
                    // in the same shape as a failure log, so that it can be replayed and fixed.
                    logHarnessCrash(executor, databaseName, seed, throwable);
                    nrUnsuccessfulActions.incrementAndGet();
                    nrFailures.incrementAndGet();
                    FAILURES_BY_KIND.computeIfAbsent("HARNESS_CRASH", k -> new AtomicLong()).incrementAndGet();
                } finally {
                    threadsShutdown.incrementAndGet();
                }
            });
        }
        // Every task has been submitted, so the pool is told to shut down here, by the thread that
        // owns it. Deciding this inside the workers (as "have all N of us finished?") is a lost-update
        // race: two workers incrementing a shared counter can overwrite each other, the count never
        // reaches N, shutdown() is never called, and awaitTermination below then blocks forever even
        // though every database has been tested. The pool is the authority on when it is done.
        execService.shutdown();
        try {
            if (options.getTimeoutSeconds() == -1) {
                execService.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            } else {
                execService.awaitTermination(options.getTimeoutSeconds(), TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return nrFailures.get() == 0 ? 0 : options.getErrorExitCode();
    }

    private static void printBanner(String command, MainOptions options) {
        System.out.println("DDLSmith: random graph -> ER diagram -> relational DDL testing");
        System.out.println("target database   : " + command);
        System.out.println("seed              : " + (options.getRandomSeed() == -1 ? "time-based" : options.getRandomSeed()));
        System.out.println("databases         : " + options.getTotalNumberTries());
        System.out.println("databases/graph   : " + options.getDatabasesPerGraph()
                + (options.getDatabasesPerGraph() > 1
                        ? " (one graph, " + options.getDatabasesPerGraph() + " ER interpretations"
                                + (options.isRerollInstance() ? ", instance re-rolled per database)" : ")")
                        : ""));
        System.out.println("statements/db     : " + options.getNumDdlStatements());
        System.out.println("entities/graph    : " + options.getMinNodeNum() + ".." + options.getMaxNodeNum());
        System.out.println("algorithm         : " + options.getAlgorithm());
        System.out.println("oracles           : "
                + (options.getOracles().isEmpty() ? "default" : String.join(",", options.getOracles())));
        System.out.println("logs              : " + LOG_DIRECTORY.getAbsolutePath());
        System.out.println("bug reports       : " + (options.getBugDirectory() == null || options.getBugDirectory().isBlank()
                ? "disabled" : java.nio.file.Paths.get(options.getBugDirectory()).toAbsolutePath()));
        System.out.println();
    }

    private static void startProgressMonitor() {
        if (progressMonitorStarted) {
            return;
        }
        progressMonitorStarted = true;
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ddlsmith-progress");
            thread.setDaemon(true);
            return thread;
        }).scheduleAtFixedRate(() -> {
            DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
            System.out.println(String.format(
                    "[%s] databases=%d graphs=%d statements=%d queries=%d failures=%d threads-done=%d",
                    dateFormat.format(new Date()), nrDatabases.get(), nrGeneratedGraphs.get(),
                    nrSuccessfulActions.get(), nrQueries.get(), nrFailures.get(), threadsShutdown.get()));
        }, 10, 10, TimeUnit.SECONDS);
    }

    private static void printSummary() {
        System.out.println();
        System.out.println("Overall execution statistics");
        System.out.println("============================");
        System.out.println(formatInteger(nrDatabases.get()) + " databases");
        System.out.println(formatInteger(nrSuccessfulActions.get()) + " successfully executed statements");
        System.out.println(formatInteger(nrQueries.get()) + " queries");
        System.out.println(formatInteger(nrGeneratedGraphs.get()) + " generated graphs");
        // With a shared graph the number of databases says nothing about how much of the input space was
        // reached, so the distinct shapes are reported next to it: the first line counts ER diagrams, the
        // second the relational schemas they were mapped to.
        System.out.println(formatInteger(distinctErProfiles.size()) + " distinct ER diagrams");
        System.out.println(formatInteger(distinctSchemaProfiles.size()) + " distinct relational schemas");
        System.out.println(formatInteger(nrFailures.get()) + " failures");
        org.ddlsmith.bug.BugRepository bugs = bugRepositoryForSummary;
        if (bugs != null && bugs.isEnabled()) {
            bugs.writeIndex();
            System.out.println(formatInteger(bugs.getDefectCount()) + " distinct defects -> "
                    + bugs.getRoot().toAbsolutePath());
        }
        if (unavailableDatabases.get() > 0) {
            System.out.println(formatInteger(unavailableDatabases.get()) + " databases could not be reached");
        }
        if (!FAILURES_BY_KIND.isEmpty()) {
            Map<String, AtomicLong> sorted = new HashMap<>(FAILURES_BY_KIND);
            sorted.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .forEach(entry -> System.out.println("    " + entry.getKey() + ": " + entry.getValue().get()));
        }
        System.out.println("logs written to " + LOG_DIRECTORY.getAbsolutePath());
    }

    private static String formatInteger(long value) {
        if (value > 1000) {
            return String.format("%,9dk", value / 1000);
        }
        return String.format("%,10d", value);
    }

    /**
     * Writes a failure of the harness itself to a log file.
     *
     * <p>A stack trace on stderr does not say which database and which seed produced it, and the seed
     * is the only handle on the input that caused it. Recording it in the same shape as an engine
     * failure makes the crash diagnosable and replayable instead of a line in a terminal.</p>
     */
    private static void logHarnessCrash(DBMSExecutor<?, ?, ?> executor, String databaseName, long seed,
            Throwable throwable) {
        if (executor == null) {
            return;
        }
        try {
            StateLogger logger = executor.getLogger();
            if (logger == null) {
                return;
            }
            StateToReproduce state = executor.getStateToReproduce();
            if (state == null) {
                state = new StateToReproduce(databaseName);
                state.seedValue = seed;
            }
            File file = logger.logException(throwable, state,
                    "the tool itself failed while testing " + databaseName
                            + "; the statements above are the ones that had been executed when it happened");
            System.err.println("DDLSmith: the crash was recorded in " + file.getAbsolutePath());
        } catch (Throwable ignored) {
            // reporting a crash must never mask the crash itself
        }
    }

    /* ------------------------------------------------------------------ helper for providers */

    /** A convenience for providers that need to execute a query and log it. */
    public static QueryResult executeQuery(GlobalState<?, ?> state, String sql) throws Exception {
        return state.executeQuery(sql);
    }

    /** A convenience for providers that need the loggable factory. */
    public static Loggable toLoggable(LoggableFactory factory, String input) {
        return factory.createLoggable(input);
    }
}
