package org.ddlsmith.oracle.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.query.QueryResult;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.data.CheckExpressionAnalyzer;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.StatementRunner;
import org.ddlsmith.oracle.TestOracle;

/**
 * Verifies column {@code DEFAULT} and {@code NOT NULL} semantics.
 *
 * <ol>
 *   <li><b>Defaults.</b> For every table with a defaulted column, a row is inserted that
 *       <em>omits</em> those columns ({@link org.ddlsmith.data.RowGenerator#toInsertSql} with
 *       {@link org.ddlsmith.data.RowGenerator#defaultableColumns}), the row is read back with a
 *       {@code SELECT}, and the stored value is compared with the default the schema declared. A
 *       declared default that was not applied at all is a {@link FailureKind#SCHEMA_MISMATCH}; a
 *       stored value that differs from the declared literal is a {@link FailureKind#DATA_MISMATCH}.
 *       Default expressions that can be evaluated in Java ({@code 'x'}, {@code 42}, {@code TRUE},
 *       and literals carrying a {@code ::type} cast) are compared by value; expressions that cannot
 *       be evaluated ({@code CURRENT_TIMESTAMP}, {@code nextval(...)}, sequences) are only required
 *       to produce a non-NULL value, and only when the expression is a known non-NULL-producing
 *       one - an unrecognized expression is skipped instead of guessed at.</li>
 *   <li><b>NOT NULL.</b> For every NOT NULL column an <em>explicit</em> {@code NULL} is inserted.
 *       The column is never omitted, because omitting it would apply the column DEFAULT and no
 *       violation would occur; the probe therefore also covers NOT NULL columns that have a default,
 *       which the task description does not require but which is strictly stronger and cannot
 *       produce a false positive: an explicit NULL in a NOT NULL column must be rejected by every
 *       SQL engine. Accepting it is {@link FailureKind#CONSTRAINT_NOT_ENFORCED}.</li>
 * </ol>
 *
 * <h2>False-positive guards</h2>
 * <ul>
 *   <li>The row is identified with its primary key when it has one; otherwise the {@code SELECT} must
 *       match exactly one row, so a default is never compared against a different row.</li>
 *   <li>NOT NULL probes are skipped for IDENTITY columns and for a single-column integral primary
 *       key: SQLite treats such a column as an alias of the rowid and assigns a fresh value when
 *       NULL is inserted, and MySQL does the same for AUTO_INCREMENT, so accepting the row is
 *       correct behaviour there.</li>
 *   <li>Textual defaults are compared after stripping trailing spaces for {@code CHAR(n)} columns,
 *       because the SQL standard requires the value to be padded to the declared length.</li>
 *   <li>An INSERT that fails is recorded as a note and the probe is abandoned; a failure is never
 *       turned into a default-value finding, because it may have an unrelated cause (the same
 *       INSERT is checked by the constraint-enforcement oracle).</li>
 * </ul>
 */
public class DefaultValueOracle implements TestOracle {

    public static final String NAME = "default-value";
    /** Upper bound on the number of probes (defaults and NOT NULL together) per database. */
    public static final int MAX_PROBES = 20;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Checks that declared column DEFAULTs are applied when the column is omitted, and that "
                + "an explicit NULL in a NOT NULL column is rejected.";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        RelationalSchema schema = context.getIntendedSchema();
        if (schema == null || schema.getTables().isEmpty()) {
            return Optional.empty();
        }
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (DatabaseUnderTest database : context.getDatabases()) {
            findings.addAll(verifyDatabase(context, database, schema, notes));
        }
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ConstraintEnforcementOracle.Support.toFailure(NAME, context, findings, notes));
    }

    private List<ConstraintEnforcementOracle.Support.Finding> verifyDatabase(OracleContext context,
            DatabaseUnderTest database, RelationalSchema schema, List<String> notes) {
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        String prefix = "[" + database.getName() + "] ";
        ConstraintEnforcementOracle.Support.Population population = ConstraintEnforcementOracle.Support.populate(
                context, database, schema, 1, 3);
        notes.addAll(population.notes);
        List<Table> tables = new ArrayList<>(population.populated);
        Randomly.shuffle(tables);
        int probes = 0;
        for (Table table : tables) {
            if (probes >= MAX_PROBES) {
                notes.add(prefix + "probe budget of " + MAX_PROBES + " reached, remaining tables are not probed");
                break;
            }
            if (!population.generator.defaultableColumns(table).isEmpty()) {
                probes++;
                findings.addAll(probeDefaults(context, database, population, table, notes, prefix));
            }
            for (Column column : table.getColumns()) {
                if (probes >= MAX_PROBES) {
                    break;
                }
                if (column.isNullable() || column.isGenerated() || column.isIdentity()) {
                    continue;
                }
                if (ConstraintEnforcementOracle.Support.isSoleIntegralPrimaryKey(column, table)) {
                    notes.add(prefix + "skipped NOT NULL probe on " + table.getName() + "." + column.getName()
                            + ": a single-column integral primary key may be auto-assigned when NULL is inserted");
                    continue;
                }
                probes++;
                ConstraintEnforcementOracle.Support.Finding finding = probeNotNull(context, database, population,
                        table, column);
                if (finding != null) {
                    findings.add(finding);
                }
            }
        }
        return findings;
    }

    /* ------------------------------------------------------------------ defaults */

    private List<ConstraintEnforcementOracle.Support.Finding> probeDefaults(OracleContext context,
            DatabaseUnderTest database, ConstraintEnforcementOracle.Support.Population population, Table table,
            List<String> notes, String prefix) {
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        Dialect dialect = database.getDialect();
        Set<String> omitted = population.generator.defaultableColumns(table);
        Map<String, Object> row;
        try {
            row = population.generator.generateRow(table);
        } catch (RuntimeException e) {
            notes.add(prefix + "default probe on " + table.getName() + " skipped: the row generator failed (" + e
                    + ")");
            return findings;
        }
        if (row == null || row.isEmpty()) {
            notes.add(prefix + "default probe on " + table.getName()
                    + " skipped: no satisfying row could be generated");
            return findings;
        }
        if (!ConstraintEnforcementOracle.Support.rowSatisfiesIntendedConstraints(table, row)) {
            notes.add(prefix + "default probe on " + table.getName()
                    + " skipped: the generated row does not satisfy the declared constraints itself");
            return findings;
        }
        String sql = population.generator.toInsertSql(table, row, omitted);
        ExecutionEvent event = StatementRunner.execute(context, database, sql, false, "probe-default");
        if (!event.isSucceeded()) {
            notes.add(prefix + "default probe on " + table.getName() + " skipped: the INSERT was rejected ("
                    + event.getErrorMessage() + "); this is not attributed to the DEFAULT");
            return findings;
        }
        List<String> selected = new ArrayList<>(omitted);
        String where = identityClause(dialect, table, row, omitted);
        if (where == null) {
            notes.add(prefix + "default probe on " + table.getName()
                    + " skipped: the inserted row cannot be identified for the SELECT");
            return findings;
        }
        boolean identifiedByKey = where.startsWith("KEY:");
        String sqlWhere = identifiedByKey ? where.substring(4) : where;
        StringBuilder select = new StringBuilder("SELECT ");
        List<String> quoted = new ArrayList<>();
        for (String column : selected) {
            quoted.add(dialect.quote(column));
        }
        select.append(String.join(", ", quoted)).append(" FROM ").append(dialect.quote(table.getName()))
                .append(" WHERE ").append(sqlWhere);
        QueryResult result;
        try {
            result = StatementRunner.query(context, database, select.toString(), "probe-default-read");
        } catch (Exception e) {
            notes.add(prefix + "default probe on " + table.getName() + " skipped: the SELECT failed (" + e + ")");
            return findings;
        }
        if (result.getRowCount() == 0) {
            notes.add(prefix + "default probe on " + table.getName()
                    + " skipped: the inserted row could not be found again");
            return findings;
        }
        if (!identifiedByKey && result.getRowCount() != 1) {
            notes.add(prefix + "default probe on " + table.getName() + " skipped: " + result.getRowCount()
                    + " rows match the identification clause, so the defaults cannot be attributed");
            return findings;
        }
        for (String columnName : selected) {
            Column column = table.getColumn(columnName);
            Object stored = valueOf(result, 0, columnName);
            DefaultExpectation expectation = evaluateDefault(column.getDefaultExpression(), column.getType());
            ConstraintEnforcementOracle.Support.Finding finding = compare(expectation, stored, column, table,
                    database, sql, select.toString());
            if (finding != null) {
                findings.add(finding);
            }
            if (expectation.kind == DefaultExpectation.Kind.UNKNOWN) {
                notes.add(prefix + "default on " + table.getName() + "." + columnName + " is '"
                        + column.getDefaultExpression() + "', which DDLSmith cannot evaluate, so only the "
                        + "presence of a value is checked (it is " + describeValue(stored) + ")");
            }
        }
        return findings;
    }

    private ConstraintEnforcementOracle.Support.Finding compare(DefaultExpectation expectation, Object stored,
            Column column, Table table, DatabaseUnderTest database, String insertSql, String selectSql) {
        String path = table.getName() + "." + column.getName();
        String details = "Column     : " + path + "\n"
                + "Declared   : DEFAULT " + column.getDefaultExpression() + "\n"
                + "Stored     : " + describeValue(stored) + "\n"
                + "Insert SQL : " + insertSql + "\n"
                + "Select SQL : " + selectSql;
        switch (expectation.kind) {
        case LITERAL:
        case NULL_LITERAL: {
            Object expected = expectation.value;
            if (expected == null && stored == null) {
                return null;
            }
            if (expected == null) {
                return ConstraintEnforcementOracle.Support.Finding.of(FailureKind.DATA_MISMATCH,
                        database.getName(), FailureKind.DATA_MISMATCH.name(),
                        "a column declared DEFAULT NULL stored a value", details
                                + "\nExpected   : NULL (the declared default)",
                        insertSql);
            }
            if (stored == null) {
                return ConstraintEnforcementOracle.Support.Finding.of(FailureKind.SCHEMA_MISMATCH,
                        database.getName(), FailureKind.SCHEMA_MISMATCH.name(),
                        "a declared column DEFAULT was not applied", details
                                + "\nExpected   : " + describeValue(expected)
                                + "\nThe column was omitted from the INSERT, so the declared DEFAULT had to be "
                                + "applied; the engine stored NULL instead.",
                        insertSql);
            }
            if (!valuesEqual(expected, stored, column.getType())) {
                return ConstraintEnforcementOracle.Support.Finding.of(FailureKind.DATA_MISMATCH,
                        database.getName(), FailureKind.DATA_MISMATCH.name(),
                        "a stored DEFAULT differs from the declared default", details
                                + "\nExpected   : " + describeValue(expected) + " (the declared default)",
                        insertSql);
            }
            return null;
        }
        case MUST_BE_NON_NULL: {
            if (stored == null) {
                return ConstraintEnforcementOracle.Support.Finding.of(FailureKind.SCHEMA_MISMATCH,
                        database.getName(), FailureKind.SCHEMA_MISMATCH.name(),
                        "a declared column DEFAULT was not applied", details
                                + "\nExpected   : a non-NULL value (the declared expression "
                                + column.getDefaultExpression() + " never evaluates to NULL)",
                        insertSql);
            }
            return null;
        }
        default:
            return null;
        }
    }

    /* ------------------------------------------------------------------ NOT NULL */

    private ConstraintEnforcementOracle.Support.Finding probeNotNull(OracleContext context,
            DatabaseUnderTest database, ConstraintEnforcementOracle.Support.Population population, Table table,
            Column column) {
        Map<String, Object> row;
        try {
            row = population.generator.generateRow(table);
        } catch (RuntimeException e) {
            return null;
        }
        if (row == null || row.isEmpty() || !ConstraintEnforcementOracle.Support.hasKey(row, column.getName())) {
            return null;
        }
        row.put(column.getName(), null);
        String sql = population.generator.toInsertSql(table, row, Set.of());
        if (!sql.contains(database.getDialect().quote(column.getName()))) {
            // the column was dropped from the statement, so the DEFAULT would apply instead of NULL
            return null;
        }
        ExecutionEvent event = StatementRunner.execute(context, database, sql, true, "probe-not-null");
        if (!event.isUnexpectedSuccess()) {
            return null;
        }
        String details = "Column     : " + table.getName() + "." + column.getName() + "\n"
                + "Declared   : NOT NULL"
                + (column.hasDefault() ? " DEFAULT " + column.getDefaultExpression() : "") + "\n"
                + "Probe      : an explicit NULL was inserted for the column (it was NOT omitted, so the "
                + "DEFAULT could not apply)\n"
                + "Row        : " + row + "\n"
                + "SQL        : " + sql + "\n"
                + "Observed   : the row was inserted successfully; the column must have stored NULL";
        return ConstraintEnforcementOracle.Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED,
                database.getName(), FailureKind.CONSTRAINT_NOT_ENFORCED.name(),
                "a NOT NULL column accepted an explicit NULL", details, sql);
    }

    /* ------------------------------------------------------------------ helpers */

    /** How the declared default expression can be checked. */
    private static final class DefaultExpectation {
        enum Kind {
            LITERAL, NULL_LITERAL, MUST_BE_NON_NULL, UNKNOWN
        }

        final Kind kind;
        final Object value;

        private DefaultExpectation(Kind kind, Object value) {
            this.kind = kind;
            this.value = value;
        }
    }

    private static DefaultExpectation evaluateDefault(String expression, DataType type) {
        if (expression == null) {
            return new DefaultExpectation(DefaultExpectation.Kind.UNKNOWN, null);
        }
        String candidate = stripOuterParentheses(expression.trim());
        if (candidate.equalsIgnoreCase("null")) {
            return new DefaultExpectation(DefaultExpectation.Kind.NULL_LITERAL, null);
        }
        if (ConstraintEnforcementOracle.Support.isCurrentTimestamp(candidate)
                || isSequenceNextValue(candidate)) {
            return new DefaultExpectation(DefaultExpectation.Kind.MUST_BE_NON_NULL, null);
        }
        String withoutCast = candidate;
        int cast = withoutCast.indexOf("::");
        if (cast > 0) {
            withoutCast = withoutCast.substring(0, cast).trim();
        }
        Object literal = CheckExpressionAnalyzer.parseLiteral(withoutCast);
        if (literal != null) {
            return new DefaultExpectation(DefaultExpectation.Kind.LITERAL, literal);
        }
        return new DefaultExpectation(DefaultExpectation.Kind.UNKNOWN, null);
    }

    private static boolean isSequenceNextValue(String expression) {
        String lower = expression.toLowerCase(Locale.ROOT);
        return lower.startsWith("nextval") || lower.startsWith("next value for") || lower.endsWith(".nextval");
    }

    private static String stripOuterParentheses(String expression) {
        String result = expression;
        while (result.startsWith("(") && result.endsWith(")") && balanced(result.substring(1, result.length() - 1))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean balanced(String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
        }
        return depth == 0;
    }

    /**
     * Builds the {@code WHERE} clause that identifies the inserted row, preferring the primary key.
     * The returned string is prefixed with {@code KEY:} when the primary key was used, so that the
     * caller knows the match is guaranteed to be unique.
     */
    private static String identityClause(Dialect dialect, Table table, Map<String, Object> row,
            Set<String> omitted) {
        if (table.getPrimaryKey() != null && !table.getPrimaryKey().getColumns().isEmpty()) {
            boolean usable = true;
            Map<String, Object> values = new LinkedHashMap<>();
            for (String column : table.getPrimaryKey().getColumns()) {
                Object value = DataModel.getValue(row, column);
                if (value == null) {
                    usable = false;
                    break;
                }
                values.put(column, value);
            }
            if (usable) {
                return "KEY:" + ConstraintEnforcementOracle.Support.whereEquals(dialect, table,
                        table.getPrimaryKey().getColumns(), values);
            }
        }
        List<String> columns = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Column column : table.getColumns()) {
            if (column.isGenerated() || omitted.contains(column.getName())) {
                continue;
            }
            Object value = DataModel.getValue(row, column.getName());
            if (value == null) {
                continue;
            }
            columns.add(column.getName());
            values.put(column.getName(), value);
        }
        if (columns.isEmpty()) {
            return null;
        }
        return ConstraintEnforcementOracle.Support.whereEquals(dialect, table, columns, values);
    }

    /** Case-insensitive column lookup: engines disagree on the case of result labels. */
    private static Object valueOf(QueryResult result, int row, String columnName) {
        List<String> names = result.getColumnNames();
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(columnName)) {
                return result.getValue(row, i);
            }
        }
        return null;
    }

    private static boolean valuesEqual(Object expected, Object stored, DataType type) {
        Object left = DataModel.normalizeForComparison(expected);
        Object right = DataModel.normalizeForComparison(stored);
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof java.math.BigDecimal && right instanceof java.math.BigDecimal) {
            return ((java.math.BigDecimal) left).compareTo((java.math.BigDecimal) right) == 0;
        }
        if (type != null && type.getCategory() == DataType.Category.CHAR) {
            // CHAR(n) is padded to the declared length with spaces
            return left.toString().stripTrailing().equals(right.toString().stripTrailing());
        }
        return left.equals(right);
    }

    private static String describeValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[]) {
            return "X'" + java.util.Arrays.toString((byte[]) value) + "'";
        }
        return value + " (" + value.getClass().getSimpleName() + ")";
    }
}
