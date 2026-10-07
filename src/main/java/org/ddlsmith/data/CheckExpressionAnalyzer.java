package org.ddlsmith.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands the simple CHECK expressions that DDLSmith generates, so that the row generator can
 * produce a value that satisfies them and a value that violates them.
 *
 * <p>Only the shapes DDLSmith itself emits are supported: {@code col OP literal},
 * {@code col IN (v1, v2, ...)}, {@code col IS NOT NULL}, {@code col IS NULL} and conjunctions of
 * those. Anything else is reported as "not understood" and the caller skips the corresponding
 * check, instead of guessing and producing a false finding.</p>
 */
public final class CheckExpressionAnalyzer {

    public enum Operator {
        GREATER_OR_EQUAL, GREATER, LESS_OR_EQUAL, LESS, NOT_EQUAL, EQUAL, IS_NOT_NULL, IS_NULL, IN, NOT_IN, UNKNOWN
    }

    private static final Pattern COMPARISON = Pattern.compile(
            "^\\s*\"?`?\\[?([A-Za-z_][A-Za-z0-9_]*)\\]?`?\"?\\s*(>=|<=|<>|!=|>|<|=)\\s*(.+?)\\s*$");
    private static final Pattern IS_NULL = Pattern.compile(
            "^\\s*\"?`?\\[?([A-Za-z_][A-Za-z0-9_]*)\\]?`?\"?\\s+IS\\s+(NOT\\s+)?NULL\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_LIST = Pattern.compile(
            "^\\s*\"?`?\\[?([A-Za-z_][A-Za-z0-9_]*)\\]?`?\"?\\s+(NOT\\s+)?IN\\s*\\((.*)\\)\\s*$", Pattern.CASE_INSENSITIVE);

    private final String columnName;
    private final Operator operator;
    private final List<Object> literals;

    private CheckExpressionAnalyzer(String columnName, Operator operator, List<Object> literals) {
        this.columnName = columnName;
        this.operator = operator;
        this.literals = literals;
    }

    public String getColumnName() {
        return columnName;
    }

    public Operator getOperator() {
        return operator;
    }

    public List<Object> getLiterals() {
        return literals;
    }

    public boolean isUnderstood() {
        return operator != Operator.UNKNOWN && columnName != null;
    }

    /** @return true when the expression constrains a single column in a way this class understands */
    public static boolean isSimple(String expression) {
        return parse(expression).isUnderstood();
    }

    /** Parses a single-column CHECK expression; an UNKNOWN analyzer is returned otherwise. */
    public static CheckExpressionAnalyzer parse(String expression) {
        if (expression == null) {
            return new CheckExpressionAnalyzer(null, Operator.UNKNOWN, List.of());
        }
        String trimmed = stripOuterParentheses(expression.trim());
        // conjunctions are handled by the caller one conjunct at a time
        if (containsTopLevelAnd(trimmed)) {
            return new CheckExpressionAnalyzer(null, Operator.UNKNOWN, List.of());
        }
        Matcher inList = IN_LIST.matcher(trimmed);
        if (inList.matches()) {
            List<Object> values = parseLiteralList(inList.group(3));
            if (values == null) {
                return new CheckExpressionAnalyzer(null, Operator.UNKNOWN, List.of());
            }
            Operator operator = inList.group(2) == null ? Operator.IN : Operator.NOT_IN;
            return new CheckExpressionAnalyzer(inList.group(1).toLowerCase(Locale.ROOT), operator, values);
        }
        Matcher isNull = IS_NULL.matcher(trimmed);
        if (isNull.matches()) {
            Operator operator = isNull.group(2) == null ? Operator.IS_NULL : Operator.IS_NOT_NULL;
            return new CheckExpressionAnalyzer(isNull.group(1).toLowerCase(Locale.ROOT), operator, List.of());
        }
        Matcher comparison = COMPARISON.matcher(trimmed);
        if (comparison.matches()) {
            Object literal = parseLiteral(comparison.group(3));
            if (literal == null && !"null".equalsIgnoreCase(comparison.group(3).trim())) {
                return new CheckExpressionAnalyzer(null, Operator.UNKNOWN, List.of());
            }
            Operator operator;
            switch (comparison.group(2)) {
            case ">=":
                operator = Operator.GREATER_OR_EQUAL;
                break;
            case ">":
                operator = Operator.GREATER;
                break;
            case "<=":
                operator = Operator.LESS_OR_EQUAL;
                break;
            case "<":
                operator = Operator.LESS;
                break;
            case "<>":
            case "!=":
                operator = Operator.NOT_EQUAL;
                break;
            default:
                operator = Operator.EQUAL;
                break;
            }
            return new CheckExpressionAnalyzer(comparison.group(1).toLowerCase(Locale.ROOT), operator,
                    java.util.Collections.singletonList(literal));
        }
        return new CheckExpressionAnalyzer(null, Operator.UNKNOWN, List.of());
    }

    /** Splits a top-level {@code AND} conjunction into its conjuncts. */
    public static List<String> splitConjuncts(String expression) {
        return splitTopLevel(expression, "and");
    }

    /**
     * Splits a top-level {@code OR} disjunction into its operands.
     *
     * <p>The row generator needs this for the one disjunction DDLSmith itself produces: the
     * union-membership check of a category, {@code col1 IS NOT NULL OR col2 IS NOT NULL}, which
     * {@link org.ddlsmith.mapping.impl.StandardERToRelationalMapper} writes for a union type with two or
     * more superclasses. That constraint is an <em>at-least-one</em> rule, so its operands may not be
     * satisfied one by one — the caller has to see them together.</p>
     */
    public static List<String> splitDisjuncts(String expression) {
        return splitTopLevel(expression, "or");
    }

    /**
     * Splits an expression on a top-level binary keyword, ignoring occurrences inside parentheses or
     * string literals. A keyword is only recognised when it stands alone, so a column named
     * {@code android} or {@code order_id} is not mistaken for {@code and}/{@code or}.
     */
    private static List<String> splitTopLevel(String expression, String keyword) {
        List<String> parts = new ArrayList<>();
        String trimmed = stripOuterParentheses(expression == null ? "" : expression.trim());
        int length = keyword.length();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (depth == 0 && Character.toLowerCase(c) == keyword.charAt(0)
                        && matchesKeyword(trimmed, i, keyword)) {
                    parts.add(trimmed.substring(start, i).trim());
                    i += length - 1;
                    start = i + 1;
                }
            }
        }
        parts.add(trimmed.substring(start).trim());
        return parts;
    }

    private static boolean matchesKeyword(String text, int index, String keyword) {
        int length = keyword.length();
        if (index + length > text.length()) {
            return false;
        }
        String candidate = text.substring(index, index + length);
        if (!candidate.equalsIgnoreCase(keyword)) {
            return false;
        }
        boolean leftOk = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
        boolean rightOk = index + length >= text.length() || !Character.isLetterOrDigit(text.charAt(index + length));
        return leftOk && rightOk;
    }

    private static boolean containsTopLevelAnd(String expression) {
        return splitConjuncts(expression).size() > 1;
    }

    private static String stripOuterParentheses(String expression) {
        String result = expression;
        while (result.startsWith("(") && result.endsWith(")") && balanced(result.substring(1, result.length() - 1))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    /**
     * @return true when the parentheses in {@code text} are balanced <em>and</em> properly nested, so
     *         that the text is a well-formed fragment on its own
     *
     * <p>Checking only that the depth returns to zero is not enough: {@code a) OR (b} also ends at zero,
     * because the first closing parenthesis takes the depth to -1 and the second returns it. Treating
     * that as balanced made {@link #stripOuterParentheses} remove the parentheses of
     * {@code (a1 IS NOT NULL) OR (a2 IS NOT NULL)} — they are not a pair — and then split the mangled
     * text at the wrong depth. A depth that ever goes negative means the first {@code (} does not open
     * the whole text, which is exactly what this predicate has to detect.</p>
     */
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
                    if (depth < 0) {
                        return false;
                    }
                }
            }
        }
        return depth == 0;
    }

    /** Parses a SQL literal; returns null when it is not a literal this class understands. */
    public static Object parseLiteral(String text) {
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("true")) {
            return Boolean.TRUE;
        }
        if (lower.equals("false")) {
            return Boolean.FALSE;
        }
        if (lower.equals("null")) {
            return null;
        }
        // strip a cast such as 5::integer
        int castIndex = value.indexOf("::");
        if (castIndex > 0) {
            value = value.substring(0, castIndex).trim();
        }
        try {
            if (value.contains(".") || value.contains("e") || value.contains("E")) {
                return new java.math.BigDecimal(value);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Object> parseLiteralList(String text) {
        List<Object> values = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inString = !inString;
                current.append(c);
            } else if (!inString && c == '(') {
                depth++;
                current.append(c);
            } else if (!inString && c == ')') {
                depth--;
                current.append(c);
            } else if (!inString && depth == 0 && c == ',') {
                Object literal = parseLiteral(current.toString());
                if (literal == null && !current.toString().trim().equalsIgnoreCase("null")) {
                    return null;
                }
                values.add(literal);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.toString().trim().isEmpty()) {
            return null;
        }
        Object literal = parseLiteral(current.toString());
        if (literal == null && !current.toString().trim().equalsIgnoreCase("null")) {
            return null;
        }
        values.add(literal);
        return values;
    }

    /** @return true when {@code value} satisfies this constraint */
    public boolean isSatisfiedBy(Object value) {
        if (operator == Operator.IS_NOT_NULL) {
            return value != null;
        }
        if (operator == Operator.IS_NULL) {
            return value == null;
        }
        if (value == null) {
            // SQL CHECK constraints are satisfied when the expression evaluates to UNKNOWN
            return true;
        }
        if (operator == Operator.IN || operator == Operator.NOT_IN) {
            boolean contained = false;
            for (Object literal : literals) {
                if (equalsValue(value, literal)) {
                    contained = true;
                    break;
                }
            }
            return operator == Operator.IN ? contained : !contained;
        }
        Object literal = literals.get(0);
        if (literal == null) {
            return true;
        }
        int comparison = compareValues(value, literal);
        switch (operator) {
        case GREATER_OR_EQUAL:
            return comparison >= 0;
        case GREATER:
            return comparison > 0;
        case LESS_OR_EQUAL:
            return comparison <= 0;
        case LESS:
            return comparison < 0;
        case NOT_EQUAL:
            return comparison != 0;
        case EQUAL:
            return comparison == 0;
        default:
            return true;
        }
    }

    /**
     * @param value a candidate value
     * @return a value derived from {@code value} that satisfies this constraint, or null when no
     *         such value could be produced
     */
    public Object satisfy(Object value) {
        if (isSatisfiedBy(value)) {
            return value;
        }
        switch (operator) {
        case IS_NOT_NULL:
            return value == null ? defaultFor(literals) : value;
        case GREATER_OR_EQUAL:
        case GREATER:
            return numericAdjust(literals.get(0), operator == Operator.GREATER ? 1 : 0);
        case LESS_OR_EQUAL:
        case LESS:
            return numericAdjust(literals.get(0), operator == Operator.LESS ? -1 : 0);
        case EQUAL:
            return literals.get(0);
        case NOT_EQUAL:
            return numericAdjust(literals.get(0), 1);
        case IN:
            return literals.isEmpty() ? null : literals.get(0);
        case NOT_IN:
            return notInValue();
        default:
            return null;
        }
    }

    /**
     * @param value a value that currently satisfies this constraint
     * @return a value that violates it, or null when no such value could be produced
     */
    public Object violate(Object value) {
        switch (operator) {
        case IS_NOT_NULL:
            return null;
        case IS_NULL:
            return value == null ? "x" : value;
        case GREATER_OR_EQUAL:
            return numericAdjust(literals.get(0), -1);
        case GREATER:
            return literals.get(0);
        case LESS_OR_EQUAL:
            return numericAdjust(literals.get(0), 1);
        case LESS:
            return literals.get(0);
        case EQUAL:
            return numericAdjust(literals.get(0), 1);
        case NOT_EQUAL:
            return literals.get(0);
        case IN:
            return notInValue();
        case NOT_IN:
            return literals.isEmpty() ? null : literals.get(0);
        default:
            return null;
        }
    }

    private Object notInValue() {
        // find a literal that is definitely not in the list
        for (long candidate = 0; candidate < 1000; candidate++) {
            boolean contained = false;
            for (Object literal : literals) {
                if (literal instanceof Number && ((Number) literal).longValue() == candidate) {
                    contained = true;
                    break;
                }
            }
            if (!contained) {
                return candidate;
            }
        }
        return "zzz_not_in_list";
    }

    private static Object numericAdjust(Object literal, int delta) {
        if (literal instanceof Number) {
            long base = ((Number) literal).longValue();
            return base + delta;
        }
        if (literal instanceof String) {
            return delta >= 0 ? ((String) literal) + "z" : "";
        }
        return null;
    }

    private static Object defaultFor(List<Object> literals) {
        if (!literals.isEmpty() && literals.get(0) != null) {
            return literals.get(0);
        }
        return 0L;
    }

    private static boolean equalsValue(Object a, Object b) {
        return compareValues(a, b) == 0;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compareValues(Object a, Object b) {
        if (a == null || b == null) {
            return a == b ? 0 : (a == null ? -1 : 1);
        }
        if (a instanceof Number && b instanceof Number) {
            return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()));
        }
        if (a instanceof Number || b instanceof Number) {
            try {
                return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()));
            } catch (NumberFormatException e) {
                return a.toString().compareTo(b.toString());
            }
        }
        if (a instanceof Comparable && a.getClass().isInstance(b)) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }

    @Override
    public String toString() {
        return columnName + " " + operator + " " + literals;
    }
}
