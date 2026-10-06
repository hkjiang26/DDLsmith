package org.ddlsmith.bug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces a generated script to the smallest sequence that still reproduces the defect.
 *
 * <p>A generated schema is twenty to forty statements long, and almost none of them are needed to show
 * the defect: an engine that rejects {@code ON DELETE CASCADE} rejects it in a two-table script. A bug
 * report that a maintainer can paste into a client and see fail is worth far more than one that needs
 * the whole generator to be re-run, so the statements that cannot matter are dropped.</p>
 *
 * <p>The reduction keeps everything that could be a dependency: the schema statements (the tables are
 * usually qualified with the schema), and, transitively, the {@code CREATE TABLE} statements for every
 * table the failing statement mentions. Order is preserved, because a table must be created before the
 * statement that references it. The reduction is conservative — when it cannot tell what a statement
 * depends on, it keeps it.</p>
 */
public final class MinimalReproducer {

    /** Statements that name a table: their table is a dependency. */
    private static final Pattern REFERENCES = Pattern.compile(
            "(?i)\\b(?:references|from|join|into|update|table|on)\\s+((?:[a-z_][a-z0-9_]*\\s*\\.\\s*)?[a-z_][a-z0-9_]*)");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*create\\s+(?:temporary\\s+|temp\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?"
                    + "((?:[a-z_][a-z0-9_]*\\s*\\.\\s*)?[a-z_][a-z0-9_]*)");
    private static final Pattern CREATE_SCHEMA = Pattern.compile("(?is)^\\s*create\\s+schema\\b");
    /** Lines that are comments rather than SQL. */
    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*(--|#).*$");

    private MinimalReproducer() {
    }

    /**
     * @param createStatements the full create script, in execution order
     * @param failingStatement the statement the engine rejected, or null when the defect is not a
     *        rejected statement (a schema mismatch, say)
     * @param extraSql additional SQL the oracle supplied as a reproducer; its statements are appended
     *        verbatim, since they carry the probe that demonstrates the defect
     * @return a script that reproduces the defect, or the whole script when no reduction is possible
     */
    public static String minimize(List<String> createStatements, String failingStatement, String extraSql) {
        List<String> script = createStatements == null ? List.of() : createStatements;
        if (script.isEmpty()) {
            return joinWithoutDuplicates(extractSqlLines(extraSql));
        }
        if (failingStatement == null || failingStatement.isBlank()) {
            // Nothing to anchor the reduction on. The full script is still the honest answer, but the
            // oracle's own reproducer is usually shorter and is added in front of it.
            List<String> lines = new ArrayList<>(extractSqlLines(extraSql));
            lines.addAll(script);
            return joinWithoutDuplicates(lines);
        }

        // every CREATE TABLE, keyed by its bare table name, so a reference can be resolved transitively
        Map<String, String> createByTable = new LinkedHashMap<>();
        for (String statement : script) {
            Matcher matcher = CREATE_TABLE.matcher(stripTrailingSemicolon(statement));
            if (matcher.find()) {
                createByTable.put(bareName(matcher.group(1)), statement);
            }
        }

        Set<String> needed = new LinkedHashSet<>();
        collectDependencies(failingStatement, createByTable, needed);

        List<String> reduced = new ArrayList<>();
        for (String statement : script) {
            if (CREATE_SCHEMA.matcher(stripTrailingSemicolon(statement)).find()) {
                reduced.add(statement);
                continue;
            }
            Matcher matcher = CREATE_TABLE.matcher(stripTrailingSemicolon(statement));
            if (matcher.find()) {
                if (needed.contains(bareName(matcher.group(1)))) {
                    reduced.add(statement);
                }
                continue;
            }
            // a statement that is neither a schema nor a table creation (an index, a view, a sequence)
            // is kept only when the failing statement is not a CREATE TABLE, because then it may be the
            // one under test
            if (!failingStatement.trim().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE")) {
                reduced.add(statement);
            }
        }

        // the failing statement itself, if the script did not already contain it
        String failing = stripTrailingSemicolon(failingStatement).trim();
        boolean alreadyPresent = reduced.stream()
                .anyMatch(statement -> stripTrailingSemicolon(statement).trim().equals(failing));
        if (!alreadyPresent && !failing.isEmpty()) {
            reduced.add(failing + ";");
        }

        List<String> lines = new ArrayList<>();
        for (String sql : extractSqlLines(extraSql)) {
            lines.add(sql);
        }
        lines.addAll(reduced);
        if (lines.isEmpty()) {
            return String.join("\n", script);
        }
        return joinWithoutDuplicates(lines);
    }

    /** Adds every table the statement depends on, and their own dependencies, to {@code needed}. */
    private static void collectDependencies(String statement, Map<String, String> createByTable,
            Set<String> needed) {
        List<String> queue = new ArrayList<>();
        Matcher matcher = REFERENCES.matcher(statement);
        while (matcher.find()) {
            queue.add(bareName(matcher.group(1)));
        }
        // the table the statement is about comes first for ALTER/DROP, which "table" already covers, but
        // a CREATE INDEX names its table after ON, which the same pattern also covers
        while (!queue.isEmpty()) {
            String table = queue.remove(0);
            if (!needed.add(table)) {
                continue;
            }
            String create = createByTable.get(table);
            if (create == null) {
                continue;
            }
            Matcher nested = REFERENCES.matcher(create);
            while (nested.find()) {
                queue.add(bareName(nested.group(1)));
            }
        }
    }

    /** @return the identifier without its schema qualifier and without quoting */
    private static String bareName(String name) {
        String result = name.replace("\"", "").replace("`", "").trim();
        int dot = result.lastIndexOf('.');
        if (dot >= 0) {
            result = result.substring(dot + 1);
        }
        return result.toLowerCase(Locale.ROOT).trim();
    }

    private static String stripTrailingSemicolon(String statement) {
        String result = statement == null ? "" : statement.trim();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    /** @return the SQL lines of a reproducer, dropping its comment lines and blank lines */
    static List<String> extractSqlLines(String extraSql) {
        List<String> result = new ArrayList<>();
        if (extraSql == null || extraSql.isBlank()) {
            return result;
        }
        for (String line : extraSql.split("\\R")) {
            if (line.isBlank() || COMMENT_LINE.matcher(line).matches()) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.endsWith(";")) {
                trimmed = trimmed + ";";
            }
            result.add(trimmed);
        }
        return result;
    }

    private static String joinWithoutDuplicates(List<String> statements) {
        List<String> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String statement : statements) {
            String key = stripTrailingSemicolon(statement).toLowerCase(Locale.ROOT);
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            unique.add(statement.endsWith(";") ? statement : statement + ";");
        }
        return String.join("\n", unique);
    }
}
