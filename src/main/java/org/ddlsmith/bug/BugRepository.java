package org.ddlsmith.bug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.ddlsmith.MainOptions;
import org.ddlsmith.StateToReproduce;
import org.ddlsmith.oracle.OracleFailure;

/**
 * The bug directory: one Markdown report per <em>distinct</em> defect an engine was caught committing.
 *
 * <p>This is deliberately separate from {@code logs/}. A log file is the full record of one generated
 * database and exists so that a single run can be replayed; the bug directory is the curated result of
 * a whole testing session. Its job is to answer "what is wrong with this engine", which means:</p>
 *
 * <ul>
 *   <li><b>one report per defect</b>, not per occurrence — the same defect found in forty databases is
 *       forty pieces of evidence for one report, and {@link BugSignature} is what makes them
 *       recognisable as the same defect;</li>
 *   <li><b>a report that can be filed as-is</b>: engine and version, what was asked for, what happened,
 *       why it is a bug, and a pasteable reproduction reduced to the statements that matter;</li>
 *   <li><b>an honest confidence level</b>, because an oracle that is still experimental can produce a
 *       finding that is DDLSmith's fault rather than the engine's.</li>
 * </ul>
 *
 * <p>Layout:</p>
 *
 * <pre>
 * bugs/
 *   INDEX.md                    every defect, one row each
 *   duckdb/
 *     001-parser-error-at-or-near-cascade.md
 *     002-...
 *   h2/
 *     ...
 * </pre>
 */
public final class BugRepository {

    /** The default directory name. */
    public static final String DEFAULT_DIRECTORY = "bugs";

    /**
     * Oracles whose findings are trusted without review. The others are still recorded, but their
     * reports say so: a false positive from an experimental oracle must not be filed against an engine.
     */
    private static final Set<String> VERIFIED_ORACLES = Set.of("crash", "schema-round-trip",
            "differential-schema", "idempotence");

    /** How many occurrences of one defect are recorded in full before only the count grows. */
    private static final int MAX_RECORDED_OCCURRENCES = 50;

    private static final Map<String, BugRepository> INSTANCES = new ConcurrentHashMap<>();

    private final Path root;
    private final Map<String, BugEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> idsByEngine = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private BugRepository(Path root) {
        this.root = root;
        loadExistingReports();
    }

    /**
     * Reads the reports already in the directory, so that a session accumulates instead of
     * restarting.
     *
     * <p>Without this, every run numbered its defects from {@code 001} again and rewrote the files
     * it found: a second run that found a different defect on the same engine replaced the first
     * run's report with its own, and the index was rebuilt from the current run alone. The evidence
     * of a testing session — which is the point of the directory — was therefore thrown away by
     * starting the next session.</p>
     *
     * <p>What is recovered is the identity of each defect (id, signature, kind, oracle, first
     * occurrence) and its evidence (occurrences, seeds, databases, titles). The prose of the report
     * is left untouched: an occurrence of an already-known defect updates the numbers in the file
     * rather than re-rendering it, which also preserves any note a maintainer added by hand.</p>
     */
    private void loadExistingReports() {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> engines = Files.list(root)) {
            for (Path engineDirectory : engines.filter(Files::isDirectory).toList()) {
                String engine = engineDirectory.getFileName().toString();
                try (java.util.stream.Stream<Path> reports = Files.list(engineDirectory)) {
                    for (Path report : reports.filter(path -> path.getFileName().toString().endsWith(".md")).toList()) {
                        loadReport(engine, report);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("warning: could not read the existing reports in " + root + ": " + e.getMessage());
        }
    }

    private void loadReport(String engine, Path report) {
        try {
            String text = Files.readString(report, StandardCharsets.UTF_8);
            String signature = field(text, "Signature");
            String id = field(text, "Defect id");
            if (signature == null || id == null) {
                return;
            }
            BugEntry entry = new BugEntry(id, engine, signature, text, report);
            entries.put(signature, entry);
            // keep numbering after the highest id already used, so a new defect never lands on a file
            // that is already there
            int dash = id.lastIndexOf('-');
            if (dash > 0 && id.substring(0, dash).equals(BugSignature.normalizeEngine(engine))) {
                try {
                    int number = Integer.parseInt(id.substring(dash + 1));
                    idsByEngine.computeIfAbsent(entry.engine, key -> new AtomicInteger()).accumulateAndGet(number,
                            Math::max);
                } catch (NumberFormatException ignored) {
                    // an id a person wrote by hand: nothing to learn from it
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("warning: could not read " + report + ": " + e.getMessage());
        }
    }

    /** @return the value of a {@code | **Name** | `value` |} row of a report, or null */
    private static String field(String text, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\|\\s*\\*\\*" + java.util.regex.Pattern.quote(name) + "\\*\\*\\s*\\|\\s*`([^`]*)`")
                .matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * @return the repository for the configured directory, shared by every thread of the run. A blank
     *         or disabled directory yields a repository that records nothing.
     */
    public static BugRepository forOptions(MainOptions options) {
        String directory = options == null ? null : options.getBugDirectory();
        if (directory == null || directory.isBlank() || "off".equalsIgnoreCase(directory)
                || "none".equalsIgnoreCase(directory)) {
            return new BugRepository(null);
        }
        return INSTANCES.computeIfAbsent(directory, name -> new BugRepository(Paths.get(name)));
    }

    /** @return true when reports are actually written */
    public boolean isEnabled() {
        return root != null;
    }

    /**
     * Forgets the repositories cached for a directory, which is what a new process starts from.
     * Visible for tests that need to observe two runs against the same directory.
     */
    static void resetForTesting() {
        INSTANCES.clear();
    }

    public Path getRoot() {
        return root;
    }

    /** @return the number of distinct defects recorded so far */
    public int getDefectCount() {
        return entries.size();
    }

    /** @return the distinct defects recorded so far, ordered by engine and then by id */
    public List<BugEntry> getEntries() {
        List<BugEntry> result = new ArrayList<>(entries.values());
        result.sort((left, right) -> {
            int byEngine = left.engine.compareTo(right.engine);
            return byEngine != 0 ? byEngine : left.id.compareTo(right.id);
        });
        return result;
    }

    /**
     * Records every failure of one generated database, creating the report for each defect or updating
     * the one that already exists.
     *
     * <p>The failures of one database are filed together on purpose: when an engine rejects a
     * {@code CREATE TABLE}, the schema mismatch that follows is a consequence of that rejection, not a
     * second defect. Each report therefore names the other defects found alongside it, so a reviewer can
     * tell a root cause from its cascade without re-reading the run.</p>
     *
     * @return the report files, in the order the failures were given
     */
    public List<Path> recordAll(String engine, String engineVersion, List<OracleFailure> failures,
            StateToReproduce state, MainOptions options, String detailContext) {
        if (!isEnabled() || failures == null || failures.isEmpty()) {
            return List.of();
        }
        List<BugEntry> filed = new ArrayList<>();
        for (OracleFailure failure : failures) {
            String signature = BugSignature.of(engine, failure);
            BugEntry entry = entries.computeIfAbsent(signature,
                    key -> newEntry(engine, engineVersion, failure, state));
            synchronized (entry) {
                entry.recordOccurrence(engineVersion, failure, state);
                filed.add(entry);
            }
        }
        // a defect found in the same database as another is very often its consequence
        // a defect found in the same database as another is very often its consequence
        boolean engineRejectedAStatement = failures.stream()
                .anyMatch(failure -> failure.getKind() == OracleFailure.FailureKind.UNEXPECTED_ERROR);
        for (BugEntry entry : filed) {
            synchronized (entry) {
                for (BugEntry other : filed) {
                    if (other != entry) {
                        entry.foundAlongside.add(other.id);
                    }
                }
                if (engineRejectedAStatement && entry.kind != OracleFailure.FailureKind.UNEXPECTED_ERROR) {
                    // the catalog cannot match when a CREATE TABLE never ran, so a mismatch that follows a
                    // rejection is the rejection's shadow rather than a second defect
                    entry.suspectedCascade = true;
                }
            }
        }
        List<Path> written = new ArrayList<>();
        for (BugEntry entry : filed) {
            synchronized (entry) {
                write(entry, options, detailContext);
                written.add(entry.file);
            }
        }
        synchronized (writeLock) {
            writeIndex();
        }
        return written;
    }

    private BugEntry newEntry(String engine, String engineVersion, OracleFailure failure, StateToReproduce state) {
        String normalizedEngine = BugSignature.normalizeEngine(engine);
        int number = idsByEngine.computeIfAbsent(normalizedEngine, key -> new AtomicInteger()).incrementAndGet();
        String id = String.format("%s-%03d", normalizedEngine, number);
        return new BugEntry(id, normalizedEngine, failure, state, engineVersion);
    }

    /* ------------------------------------------------------------------ writing */

    private void write(BugEntry entry, MainOptions options, String detailContext) {
        if (entry.loaded) {
            // the report already exists and describes the defect in full; only its evidence grows
            updateExistingReport(entry);
            return;
        }
        try {
            Path directory = root.resolve(entry.engine);
            Files.createDirectories(directory);
            Path file = directory.resolve(entry.fileName());
            Files.write(file, render(entry, options, detailContext).getBytes(StandardCharsets.UTF_8));
            entry.file = file;
        } catch (IOException e) {
            // a bug report is a convenience; it must never turn a testing run into a crash
            System.err.println("warning: could not write the bug report for " + entry.id + ": " + e.getMessage());
        }
    }

    /**
     * Adds the new occurrences of a defect that was reported by an earlier run to its existing
     * report: the occurrence count, the seeds, the generated databases and the titles it appeared
     * under. Everything else in the file is left exactly as it was.
     */
    private void updateExistingReport(BugEntry entry) {
        Path file = entry.file;
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            text = text.replaceFirst("(\\|\\s*\\*\\*Occurrences\\*\\*\\s*\\|\\s*)\\d+(\\s*\\|)",
                    "$1" + entry.occurrences + "$2");
            text = appendToCommaSection(text, "## Seeds that found it", entry.seeds);
            text = appendToCommaSection(text, "## Generated databases that exposed it", entry.observedStatements);
            text = appendBullets(text, "## Titles this defect was reported under", entry.variants);
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("warning: could not update the report for " + entry.id + ": " + e.getMessage());
        }
    }

    /** Appends the values a comma separated section does not list yet, creating the section if needed. */
    private static String appendToCommaSection(String text, String heading, List<String> values) {
        if (values.isEmpty()) {
            return text;
        }
        int headingIndex = text.indexOf(heading);
        if (headingIndex < 0) {
            return text.stripTrailing() + "\n\n" + heading + "\n\n" + String.join(", ", values) + "\n";
        }
        int cursor = text.indexOf('\n', headingIndex + heading.length());
        while (cursor >= 0 && cursor < text.length()) {
            int lineEnd = text.indexOf('\n', cursor + 1);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(cursor + 1, lineEnd).trim();
            if (!line.isEmpty()) {
                if (line.startsWith("## ") || line.startsWith("<details")) {
                    // the section exists but is empty: write the values into it
                    return text.substring(0, cursor + 1) + String.join(", ", values) + "\n"
                            + text.substring(cursor + 1);
                }
                List<String> merged = new ArrayList<>();
                for (String existing : line.split(",")) {
                    if (!existing.trim().isEmpty()) {
                        merged.add(existing.trim());
                    }
                }
                for (String value : values) {
                    if (!merged.contains(value)) {
                        merged.add(value);
                    }
                }
                return text.substring(0, cursor + 1) + String.join(", ", merged) + text.substring(lineEnd);
            }
            cursor = lineEnd;
        }
        return text;
    }

    /** Appends the bullet lines a section does not contain yet, creating the section if needed. */
    private static String appendBullets(String text, String heading, Set<String> values) {
        List<String> missing = new ArrayList<>();
        for (String value : values) {
            if (!text.contains("- `" + value + "`")) {
                missing.add(value);
            }
        }
        if (missing.isEmpty()) {
            return text;
        }
        int headingIndex = text.indexOf(heading);
        if (headingIndex < 0) {
            StringBuilder appended = new StringBuilder(text.stripTrailing()).append("\n\n").append(heading)
                    .append("\n\n");
            for (String value : missing) {
                appended.append("- `").append(value).append("`\n");
            }
            return appended.toString();
        }
        // insert after the last bullet of that section, so the sections that follow stay untouched
        int insertAt = headingIndex + heading.length();
        int cursor = insertAt;
        while (cursor < text.length()) {
            int lineEnd = text.indexOf('\n', cursor);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            String line = text.substring(cursor, lineEnd).trim();
            if (line.startsWith("## ") || line.startsWith("<details")) {
                break;
            }
            if (line.startsWith("- `")) {
                insertAt = lineEnd + 1;
            }
            cursor = lineEnd + 1;
        }
        StringBuilder inserted = new StringBuilder();
        for (String value : missing) {
            inserted.append("- `").append(value).append("`\n");
        }
        return text.substring(0, insertAt) + inserted + text.substring(insertAt);
    }

    private String render(BugEntry entry, MainOptions options, String detailContext) {
        StringBuilder sb = new StringBuilder();
        // the title is normalized, so it reads as a description of the defect rather than repeating the
        // feature list of whichever generated schema happened to expose it first
        sb.append("# ").append(entry.engine).append(' ').append(entry.engineVersion).append(": ")
                .append(BugSignature.normalize(entry.firstTitle)).append('\n').append('\n');

        sb.append("| | |\n|---|---|\n");
        sb.append("| **Engine** | `").append(entry.engine).append(' ').append(entry.engineVersion).append("` |\n");
        sb.append("| **Defect id** | `").append(entry.id).append("` |\n");
        sb.append("| **Failure kind** | `").append(entry.kind).append("` |\n");
        sb.append("| **Oracle** | `").append(entry.oracleName).append("` |\n");
        sb.append("| **Confidence** | ").append(VERIFIED_ORACLES.contains(entry.oracleName)
                ? "high — reported by a verified oracle"
                : "**needs review** — reported by an experimental oracle, which can also be wrong about "
                        + "DDLSmith's own behaviour").append(" |\n");
        if (entry.suspectedCascade) {
            sb.append("| **Suspected cascade** | yes — the engine rejected a statement in the same database, "
                    + "so this may be a consequence of that rejection rather than a defect of its own |\n");
        }
        sb.append("| **Occurrences** | ").append(entry.occurrences).append(" |\n");
        sb.append("| **First seen** | seed=").append(entry.firstSeed).append(", database=")
                .append(entry.firstDatabase).append(" |\n");
        sb.append("| **Signature** | `").append(entry.signature).append("` |\n");
        sb.append('\n');

        sb.append("## Why this is a bug\n\n").append(entry.details).append('\n').append('\n');

        if (!entry.createStatements.isEmpty()) {
            sb.append("## Minimal reproduction\n\n");
            sb.append("```sql\n").append(entry.minimalReproducer()).append("\n```\n\n");
            if (entry.createStatements.size() > entry.reducedStatementCount) {
                sb.append("<details><summary>Full generated script (").append(entry.createStatements.size())
                        .append(" statements)</summary>\n\n```sql\n")
                        .append(String.join("\n", entry.createStatements)).append("\n```\n\n</details>\n\n");
            }
        } else if (entry.reproducerSql != null && !entry.reproducerSql.isBlank()) {
            sb.append("## Minimal reproduction\n\n```sql\n").append(entry.reproducerSql.trim()).append("\n```\n\n");
        }

        if (!entry.engineResponse.isEmpty()) {
            sb.append("## What the engine said\n\n```\n");
            for (String line : entry.engineResponse) {
                sb.append(line).append('\n');
            }
            sb.append("```\n\n");
        } else {
            sb.append("## What the engine said\n\nNo statement was rejected; the defect is in the catalog the ")
                    .append("engine reported afterwards, quoted under *Why this is a bug*.\n\n");
        }

        sb.append("## Reproduce this run\n\n");
        sb.append("```shell\n").append(reproduceCommand(entry, options)).append("\n```\n\n");
        sb.append("The full per-database record, including every executed statement, is in `logs/")
                .append(entry.engine).append('/').append(entry.firstDatabase)
                .append("-failure-*.log`.\n\n");

        if (!entry.foundAlongside.isEmpty()) {
            sb.append("## Found in the same database as\n\n");
            sb.append("These were filed for the same generated database, so they may be consequences of one ")
                    .append("cause rather than independent defects: ")
                    .append(String.join(", ", entry.foundAlongside.stream().map(id -> "`" + id + "`").toList()))
                    .append(".\n\n");
        }

        if (entry.variants.size() > 1) {
            sb.append("## Titles this defect was reported under\n\n");
            for (String variant : entry.variants) {
                sb.append("- `").append(variant).append("`\n");
            }
            sb.append('\n');
        }

        if (entry.seeds.size() > 1) {
            sb.append("## Seeds that found it\n\n");
            sb.append(String.join(", ", entry.seeds)).append("\n\n");
        }

        if (!entry.observedStatements.isEmpty()) {
            sb.append("## Generated databases that exposed it\n\n");
            sb.append(String.join(", ", entry.observedStatements)).append("\n\n");
        }

        if (detailContext != null && !detailContext.isBlank()) {
            sb.append("<details><summary>Generated input for the first occurrence</summary>\n\n")
                    .append("```\n").append(detailContext.trim()).append("\n```\n\n</details>\n");
        }
        return sb.toString();
    }

    /**
     * @return a command line that reproduces the first occurrence, so the report stands on its own
     *
     * <p>Every option that shaped the run is included. Omitting them produced a command that did not
     * reproduce anything: the findings that need {@code --ddl-features} (the crash oracle's whole
     * purpose is to ask an engine about a capability it claims) were filed with a command line that
     * generated a different schema, and the reader concluded the report was wrong.</p>
     */
    private String reproduceCommand(BugEntry entry, MainOptions options) {
        StringBuilder sb = new StringBuilder("java -jar target/DDLSmith.jar ").append(entry.engine);
        sb.append(" --random-seed ").append(entry.firstSeed);
        if (options != null) {
            sb.append(" --num-tries 1");
            sb.append(" --min-node-num ").append(options.getMinNodeNum());
            sb.append(" --max-node-num ").append(options.getMaxNodeNum());
            if (options.getAlgorithm() != null && !"random".equals(options.getAlgorithm())) {
                sb.append(" --algorithm ").append(options.getAlgorithm());
            }
            if (!options.getCompareWith().isEmpty()) {
                sb.append(" --compare-with ").append(String.join(",", options.getCompareWith()));
            }
            if (options.getGraphGenerator() != null && !"mix".equals(options.getGraphGenerator())) {
                sb.append(" --graph-generator ").append(options.getGraphGenerator());
            }
            if (!options.getExtraDdlFeatures().isEmpty()) {
                sb.append(" --ddl-features ").append(String.join(",", options.getExtraDdlFeatures()));
            }
            if (!options.getDisabledDdlFeatures().isEmpty()) {
                sb.append(" --disable-ddl-features ").append(String.join(",", options.getDisabledDdlFeatures()));
            }
            if (!options.getOracles().isEmpty()) {
                sb.append(" --oracle ").append(String.join(",", options.getOracles()));
            }
            if (options.getEvolveSteps() > 0) {
                sb.append(" --evolve-steps ").append(options.getEvolveSteps());
            }
            if (options.getNumDdlStatements() != 50) {
                sb.append(" --num-ddl ").append(options.getNumDdlStatements());
            }
            if (options.getRowsPerTable() != 3) {
                sb.append(" --insert-rows ").append(options.getRowsPerTable());
            }
            if (options.getMaxConstraintProbes() != 25) {
                sb.append(" --max-constraint-probes ").append(options.getMaxConstraintProbes());
            }
            if (options.getSchemaName() != null && !"ddlsmith".equals(options.getSchemaName())) {
                sb.append(" --schema-name ").append(options.getSchemaName());
            }
        }
        return sb.toString();
    }

    /**
     * Writes the index of every defect recorded so far. It is written after each finding and once more
     * at the end of a run, so that a run which found nothing still leaves a directory that says so
     * rather than an empty folder whose meaning has to be guessed.
     */
    public void writeIndex() {
        if (!isEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Database defects found by DDLSmith\n\n");
        sb.append("One report per distinct defect. A defect found in many generated databases appears once, ")
                .append("with the number of occurrences as evidence.\n\n");
        List<BugEntry> all = getEntries();
        if (all.isEmpty()) {
            sb.append("No defect has been found yet.\n");
        } else {
            Map<String, Integer> byEngine = new LinkedHashMap<>();
            for (BugEntry entry : all) {
                byEngine.merge(entry.engine, 1, Integer::sum);
            }
            sb.append("**").append(all.size()).append(" distinct defect(s)** across ")
                    .append(byEngine.size()).append(" engine(s): ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> engine : byEngine.entrySet()) {
                parts.add(engine.getKey() + ": " + engine.getValue());
            }
            sb.append(String.join(", ", parts)).append(".\n\n");

            sb.append("| Id | Engine | Kind | Defect | Occurrences | Confidence |\n");
            sb.append("|----|--------|------|--------|-------------|------------|\n");
            for (BugEntry entry : all) {
                String link = entry.file == null ? entry.fileName()
                        : root.relativize(entry.file).toString().replace('\\', '/');
                sb.append("| `").append(entry.id).append("` | ").append(entry.engine).append(" | `")
                        .append(entry.kind).append("` | [").append(escape(entry.displayTitle())).append("](")
                        .append(link).append(") | ").append(entry.occurrences).append(" | ")
                        .append(VERIFIED_ORACLES.contains(entry.oracleName) ? "high" : "needs review")
                        .append(" |\n");
            }
        }
        try {
            Files.createDirectories(root);
            Files.write(root.resolve("INDEX.md"), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("warning: could not write the bug index: " + e.getMessage());
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    /* ------------------------------------------------------------------ entry */

    /** One distinct defect and the evidence collected for it. */
    public static final class BugEntry {
        private final String id;
        private final String engine;
        private final String signature;
        private final OracleFailure.FailureKind kind;
        private final String oracleName;
        private final String firstTitle;
        private final long firstSeed;
        private final String firstDatabase;
        private final List<String> createStatements;
        private final Set<String> variants = new LinkedHashSet<>();
        private final List<String> seeds = new ArrayList<>();
        private final List<String> observedStatements = new ArrayList<>();
        /** Ids of other defects filed for the same generated database, often the cause of this one. */
        private final Set<String> foundAlongside = new LinkedHashSet<>();
        /** The engine's own words: the error it raised, or the catalog difference it reported. */
        private final List<String> engineResponse = new ArrayList<>();
        /** True when the engine also rejected a statement in the same database, which this may follow from. */
        private boolean suspectedCascade;
        private String engineVersion;
        private String details;
        private String reproducerSql;
        private String minimalReproducer;
        private int reducedStatementCount;
        private int occurrences;
        private Path file;
        /** True when this defect was read back from a report of an earlier run instead of found now. */
        private boolean loaded;

        BugEntry(String id, String engine, OracleFailure failure, StateToReproduce state, String engineVersion) {
            this.id = id;
            this.engine = engine;
            this.signature = BugSignature.of(engine, failure);
            this.kind = failure.getKind();
            this.oracleName = failure.getOracleName();
            this.firstTitle = failure.getTitle();
            this.firstSeed = failure.getSeed();
            this.firstDatabase = state == null ? "unknown" : state.getDatabaseName();
            this.engineVersion = engineVersion == null ? "unknown" : engineVersion;
            this.details = failure.getDetails();
            this.reproducerSql = failure.getReproducerSql();
            this.createStatements = state == null ? List.of() : List.copyOf(state.getCreateStatements());
            this.minimalReproducer = MinimalReproducer.minimize(createStatements, null, reproducerSql);
            this.reducedStatementCount = createStatements.size();
        }

        /**
         * Rebuilds the identity and the evidence of a defect from the report an earlier run wrote.
         *
         * <p>Only what a new occurrence has to update is recovered. The prose — why it is a bug, the
         * minimal reproduction, what the engine said — stays in the file and is never re-rendered,
         * which is also why a note a maintainer added by hand survives.</p>
         */
        BugEntry(String id, String engine, String signature, String report, Path file) {
            this.id = id;
            this.engine = BugSignature.normalizeEngine(engine);
            this.signature = signature;
            this.kind = kindOf(report);
            this.oracleName = field(report, "Oracle");
            this.firstTitle = titleOf(report);
            this.firstSeed = number(report, "seed=(-?\\d+)");
            this.firstDatabase = text(report, "database=([^\\s|,]+)");
            this.engineVersion = versionOf(report, engine);
            this.details = "";
            this.createStatements = List.of();
            this.occurrences = (int) number(report, "\\|\\s*\\*\\*Occurrences\\*\\*\\s*\\|\\s*(\\d+)");
            // The evidence sections are only written once there is more than one occurrence, so the
            // first occurrence is recovered from the header row and added back to them.
            this.seeds.addAll(commaSection(report, "## Seeds that found it"));
            if (this.seeds.isEmpty()) {
                this.seeds.add(String.valueOf(this.firstSeed));
            }
            this.observedStatements.addAll(commaSection(report, "## Generated databases that exposed it"));
            if (this.observedStatements.isEmpty() && this.firstDatabase != null) {
                this.observedStatements.add(this.firstDatabase);
            }
            this.variants.addAll(bulletsOf(report, "## Titles this defect was reported under"));
            if (this.variants.isEmpty()) {
                this.variants.add(this.firstTitle);
            }
            this.file = file;
            this.loaded = true;
        }

        private static OracleFailure.FailureKind kindOf(String report) {
            String kind = field(report, "Failure kind");
            if (kind == null) {
                return OracleFailure.FailureKind.UNEXPECTED_ERROR;
            }
            try {
                return OracleFailure.FailureKind.valueOf(kind.trim());
            } catch (IllegalArgumentException e) {
                return OracleFailure.FailureKind.UNEXPECTED_ERROR;
            }
        }

        /** @return the title of the report, which is its first heading with the engine prefix removed */
        private static String titleOf(String report) {
            for (String line : report.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ")) {
                    String title = trimmed.substring(2);
                    int colon = title.indexOf(": ");
                    return colon < 0 ? title : title.substring(colon + 2);
                }
            }
            return "";
        }

        /** @return the engine version the report was written for, e.g. {@code v1.3.0} */
        private static String versionOf(String report, String engine) {
            String value = field(report, "Engine");
            if (value == null) {
                return "unknown";
            }
            String prefix = BugSignature.normalizeEngine(engine) + " ";
            return value.startsWith(prefix) ? value.substring(prefix.length()).trim() : value.trim();
        }

        private static long number(String report, String pattern) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(report);
            if (!matcher.find()) {
                return 0;
            }
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String text(String report, String pattern) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(report);
            return matcher.find() ? matcher.group(1).trim() : null;
        }

        /** @return the values of a section that holds one comma separated line */
        private static List<String> commaSection(String report, String heading) {
            List<String> result = new ArrayList<>();
            for (String line : sectionOf(report, heading)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    for (String value : trimmed.split(",")) {
                        if (!value.trim().isEmpty()) {
                            result.add(value.trim());
                        }
                    }
                    break;
                }
            }
            return result;
        }

        /** @return the values of a section that holds {@code - `value`} bullets */
        private static List<String> bulletsOf(String report, String heading) {
            List<String> result = new ArrayList<>();
            for (String line : sectionOf(report, heading)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- `") && trimmed.endsWith("`")) {
                    result.add(trimmed.substring(3, trimmed.length() - 1));
                }
            }
            return result;
        }

        /** @return the lines of one {@code ##} section, without its heading */
        private static List<String> sectionOf(String report, String heading) {
            int start = report.indexOf(heading);
            if (start < 0) {
                return List.of();
            }
            int end = report.indexOf("\n## ", start + heading.length());
            String section = end < 0 ? report.substring(start + heading.length()) : report.substring(start, end);
            return List.of(section.split("\\R"));
        }

        void recordOccurrence(String version, OracleFailure failure, StateToReproduce state) {
            occurrences++;
            if (version != null && !version.isBlank()) {
                engineVersion = version;
            }
            variants.add(failure.getTitle());
            // the longest explanation is kept: an oracle that lists every difference it found describes
            // the defect better than the one that reported the first, shortest instance
            if (failure.getDetails() != null && (details == null || failure.getDetails().length() > details.length())) {
                details = failure.getDetails();
            }
            if (failure.getReproducerSql() != null && (reproducerSql == null
                    || failure.getReproducerSql().length() > reproducerSql.length())) {
                reproducerSql = failure.getReproducerSql();
            }
            if (seeds.size() < MAX_RECORDED_OCCURRENCES) {
                String seed = String.valueOf(failure.getSeed());
                if (!seeds.contains(seed)) {
                    seeds.add(seed);
                }
                if (state != null && state.getDatabaseName() != null
                        && !observedStatements.contains(state.getDatabaseName())) {
                    observedStatements.add(state.getDatabaseName());
                }
            }
            for (String line : engineResponseOf(failure)) {
                if (!engineResponse.contains(line)) {
                    engineResponse.add(line);
                }
            }
            String failing = failingStatement(failure);
            minimalReproducer = MinimalReproducer.minimize(createStatements, failing, reproducerSql);
        }

        /**
         * @return the statement the engine rejected, when the failure is about one. The reproducer SQL
         *         the oracle supplies starts with a comment naming the group and the statement, so the
         *         last non-comment line before the error is the statement under test.
         */
        /**
         * @return the lines of the failure that quote the engine rather than describe DDLSmith's
         *         expectation — the error text, the SQL state, and the differences the comparison found
         */
        private static List<String> engineResponseOf(OracleFailure failure) {
            List<String> result = new ArrayList<>();
            String details = failure.getDetails();
            if (details == null) {
                return result;
            }
            for (String line : details.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Error") || trimmed.startsWith("SQLState") || trimmed.startsWith("ErrorCode")
                        || trimmed.startsWith("Observed:") || trimmed.startsWith("expected")
                        || trimmed.startsWith("- ") || trimmed.startsWith("actual")) {
                    result.add(trimmed);
                }
            }
            if (result.isEmpty() && failure.getCause() != null) {
                result.add(String.valueOf(failure.getCause().getMessage()));
            }
            return result;
        }
        private static String failingStatement(OracleFailure failure) {
            String details = failure.getDetails();
            if (details == null) {
                return null;
            }
            for (String line : details.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("SQL:") || trimmed.startsWith("Statement:")) {
                    return trimmed.substring(trimmed.indexOf(':') + 1).trim();
                }
            }
            return null;
        }

        String fileName() {
            return String.format("%s-%s.md", id.substring(id.indexOf('-') + 1), BugSignature.slug(firstTitle));
        }

        String minimalReproducer() {
            return minimalReproducer;
        }

        public String getId() {
            return id;
        }

        public String getEngine() {
            return engine;
        }

        public String getEngineVersion() {
            return engineVersion;
        }

        public OracleFailure.FailureKind getKind() {
            return kind;
        }

        public String getOracleName() {
            return oracleName;
        }

        public String getTitle() {
            return firstTitle;
        }

        /**
         * @return the title with generated identifiers and the varying feature list replaced, which is
         *         what makes a defect recognisable in the index
         */
        public String displayTitle() {
            return BugSignature.normalize(firstTitle);
        }

        public int getOccurrences() {
            return occurrences;
        }

        public List<String> getSeeds() {
            return List.copyOf(seeds);
        }

        public String getSignature() {
            return signature;
        }

        public Path getFile() {
            return file;
        }

        public boolean isVerifiedOracle() {
            return VERIFIED_ORACLES.contains(oracleName);
        }
    }
}
