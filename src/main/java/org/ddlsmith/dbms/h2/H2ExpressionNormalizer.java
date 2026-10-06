package org.ddlsmith.dbms.h2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Undoes the two purely syntactic rewrites H2 applies when it stores an expression.
 *
 * <p>H2 does not keep the text of a {@code CHECK} clause, a column default or a view definition: it
 * re-prints the parsed expression tree. Two of its habits break the textual comparison that
 * {@link org.ddlsmith.introspection.SchemaNormalizer} performs, and this class reverses exactly
 * those two — nothing else:</p>
 *
 * <ol>
 *   <li><b>Redundant parentheses.</b> Every operand of a boolean operator and every operand of a
 *       comparison is wrapped in parentheses, so {@code budget >= 0 AND hours > 0} comes back as
 *       {@code ("BUDGET" >= 0)\n AND ("HOURS" > 0)}. A pair of parentheses is dropped only when it
 *       is provably redundant: the enclosed expression's lowest-precedence top-level operator must
 *       bind <em>strictly tighter</em> than the operator the group is an operand of. Parentheses
 *       around function arguments ({@code LENGTH(x)}, {@code CAST(x AS y)}) and around
 *       {@code IN (…)}-style lists are always kept.</li>
 *   <li><b>Casts around literals.</b> A literal that is compared with a column of a different type
 *       is printed with an explicit cast, e.g. {@code budget >= 0} on a
 *       {@code DECIMAL(12,2)} column comes back as {@code "BUDGET" >= CAST(0 AS NUMERIC(1))}. The
 *       cast only restates the literal's type, so it is removed — the same reasoning that makes
 *       {@code SchemaNormalizer.canonicalExpression} strip {@code ::type} casts. When the cast
 *       target is a floating-point type H2 also promotes an integer literal to {@code 0.0}, so the
 *       trailing zero fraction is removed as well. Casts around anything that is not a single
 *       literal (e.g. {@code CAST(x AS INTEGER)}) are left untouched.</li>
 * </ol>
 *
 * <p>Known H2 rewrites that this class deliberately does <em>not</em> undo, because they change the
 * expression rather than how it is printed. They are reported by {@code SelfCheck} as a limitation
 * of textual expression comparison on H2:</p>
 * <ul>
 *   <li>{@code NOT (a > 0)} is normalized to {@code a <= 0};</li>
 *   <li>operands of {@code AND}/{@code OR} are reordered into a canonical form, e.g.
 *       {@code a > 0 AND b < 5 OR c = 1} becomes {@code c = 1 OR (a > 0 AND b < 5)};</li>
 *   <li>function names are canonicalized, e.g. {@code LENGTH(b)} becomes {@code CHAR_LENGTH(b)};</li>
 *   <li>constant expressions are folded, e.g. {@code DEFAULT (1 + 2)} becomes {@code 3};</li>
 *   <li>an {@code IN} value list loses the space before the parenthesis
 *       ({@code a IN (1, 2, 3)} becomes {@code "A" IN(1, 2, 3)}).</li>
 * </ul>
 */
final class H2ExpressionNormalizer {

    private H2ExpressionNormalizer() {
    }

    /* precedence levels, lowest first */
    private static final int PREC_OR = 1;
    private static final int PREC_AND = 2;
    private static final int PREC_NOT = 3;
    private static final int PREC_COMPARISON = 4;
    private static final int PREC_CONCAT = 5;
    private static final int PREC_ADDITIVE = 6;
    private static final int PREC_MULTIPLICATIVE = 7;
    /** no top-level operator at all: binds tighter than everything */
    private static final int PREC_ATOM = 100;

    private static final Set<String> OPERATOR_KEYWORDS = new HashSet<>(Arrays.asList(
            "AND", "OR", "NOT", "IS", "LIKE", "ILIKE", "REGEXP", "RLIKE", "BETWEEN", "IN", "EXISTS",
            "ANY", "ALL", "SOME", "ESCAPE", "OVERLAPS", "SIMILAR", "MOD", "DIV", "XOR"));

    /** Operator keywords that are never followed by an operand parenthesis. */
    private static final Set<String> NON_FUNCTION_PREFIXES = new HashSet<>(Arrays.asList(
            "AND", "OR", "NOT", "IS", "LIKE", "ILIKE", "REGEXP", "RLIKE", "BETWEEN",
            "ANY", "ALL", "SOME", "ESCAPE", "OVERLAPS", "SIMILAR", "MOD", "DIV", "XOR"));

    /**
     * Keywords whose following parenthesis is part of their syntax and must be kept even when
     * whitespace separates them from it.
     */
    private static final Set<String> FUNCTION_PREFIX_KEYWORDS = new HashSet<>(Arrays.asList(
            "IN", "EXISTS", "VALUES", "ANY", "ALL", "SOME", "CAST", "ARRAY", "ROW"));

    /**
     * SQL clause keywords. A parenthesis directly after one of these is a grouping parenthesis, not
     * a function call, even without intervening whitespace ({@code WHERE("X" = 1)}).
     */
    private static final Set<String> CLAUSE_KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "ON", "HAVING", "GROUP", "ORDER", "BY", "SET", "VALUES", "INTO",
            "AS", "DISTINCT", "WHEN", "THEN", "ELSE", "CASE", "END", "UNION", "INTERSECT", "EXCEPT",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "FULL", "USING", "LIMIT", "OFFSET",
            "WITH", "RETURNING", "OVER", "PARTITION", "FILTER", "WITHIN", "ASC", "DESC", "NULLS"));

    private static final Set<String> FLOATING_TYPES = new HashSet<>(Arrays.asList(
            "REAL", "FLOAT", "FLOAT4", "FLOAT8", "DOUBLE", "DOUBLE PRECISION"));

    private enum Kind {
        WORD, NUMBER, STRING, QUOTED, OPERATOR, GROUP, PUNCT
    }

    private static final class Token {
        final Kind kind;
        final int start;
        final int end;
        final String text;
        /** true when this token directly follows the previous one without whitespace */
        final boolean adjacentToPrevious;

        Token(Kind kind, int start, int end, String text, boolean adjacentToPrevious) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.text = text;
            this.adjacentToPrevious = adjacentToPrevious;
        }

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        boolean isOperator() {
            if (kind == Kind.OPERATOR) {
                return true;
            }
            return kind == Kind.WORD && OPERATOR_KEYWORDS.contains(upper());
        }
    }

    /** @return the expression with H2's redundant parentheses and literal casts removed */
    static String normalize(String expression) {
        if (expression == null) {
            return null;
        }
        return process(expression, 0, expression.length()).trim();
    }

    private static String process(String text, int from, int to) {
        List<Token> tokens = tokenize(text, from, to);
        StringBuilder out = new StringBuilder();
        int cursor = from;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            out.append(text, cursor, token.start);
            if (token.kind == Kind.WORD && token.upper().equals("CAST") && i + 1 < tokens.size()
                    && tokens.get(i + 1).kind == Kind.GROUP) {
                Token group = tokens.get(i + 1);
                String inner = process(text, group.start + 1, group.end - 1);
                String literal = simplifyLiteralCast(inner);
                if (literal != null) {
                    out.append(literal);
                } else {
                    out.append(text, token.start, token.end);
                    out.append('(').append(inner).append(')');
                }
                cursor = group.end;
                i++;
                continue;
            }
            if (token.kind == Kind.GROUP) {
                String inner = process(text, token.start + 1, token.end - 1);
                if (isRedundant(tokens, i, inner)) {
                    out.append(inner);
                } else {
                    out.append('(').append(inner).append(')');
                }
            } else {
                out.append(text, token.start, token.end);
            }
            cursor = token.end;
        }
        out.append(text, cursor, to);
        return out.toString();
    }

    private static boolean isRedundant(List<Token> tokens, int index, String inner) {
        if (index > 0) {
            Token previous = tokens.get(index - 1);
            Token group = tokens.get(index);
            if (previous.kind == Kind.WORD || previous.kind == Kind.QUOTED) {
                String upper = previous.upper();
                if (FUNCTION_PREFIX_KEYWORDS.contains(upper)) {
                    // IN (…), EXISTS (…), VALUES (…): the parentheses are part of the syntax
                    return false;
                }
                if (group.adjacentToPrevious && !CLAUSE_KEYWORDS.contains(upper)
                        && !NON_FUNCTION_PREFIXES.contains(upper)) {
                    // LENGTH(x), CHAR_LENGTH(x), CAST(x AS y), COUNT(*): an argument list
                    return false;
                }
            }
        }
        int innerPrecedence = lowestPrecedence(inner);
        Token before = null;
        for (int i = index - 1; i >= 0; i--) {
            if (tokens.get(i).isOperator()) {
                before = tokens.get(i);
                break;
            }
        }
        Token after = null;
        for (int i = index + 1; i < tokens.size(); i++) {
            if (tokens.get(i).isOperator()) {
                after = tokens.get(i);
                break;
            }
        }
        if (before != null && innerPrecedence <= precedence(before)) {
            return false;
        }
        if (after != null && innerPrecedence <= precedence(after)) {
            return false;
        }
        return true;
    }

    /**
     * @return the literal when {@code inner} is exactly {@code <literal> AS <type>}, otherwise null
     */
    private static String simplifyLiteralCast(String inner) {
        List<Token> tokens = tokenize(inner, 0, inner.length());
        int index = 0;
        String sign = "";
        if (index < tokens.size() && tokens.get(index).kind == Kind.OPERATOR
                && (tokens.get(index).text.equals("-") || tokens.get(index).text.equals("+"))) {
            sign = tokens.get(index).text.equals("-") ? "-" : "";
            index++;
        }
        if (tokens.size() < index + 3) {
            return null;
        }
        Token literal = tokens.get(index);
        boolean isLiteral = literal.kind == Kind.NUMBER || literal.kind == Kind.STRING
                || (literal.kind == Kind.WORD && (literal.upper().equals("TRUE")
                        || literal.upper().equals("FALSE") || literal.upper().equals("NULL")));
        if (!isLiteral) {
            return null;
        }
        Token as = tokens.get(index + 1);
        if (as.kind != Kind.WORD || !as.upper().equals("AS")) {
            return null;
        }
        Token type = tokens.get(index + 2);
        if (type.kind != Kind.WORD) {
            return null;
        }
        String typeName = inner.substring(type.start).trim().toUpperCase(Locale.ROOT);
        String text = sign + literal.text;
        if (literal.kind == Kind.NUMBER && FLOATING_TYPES.contains(typeName)
                && text.matches("-?\\d+\\.0+")) {
            // H2 promotes an integer literal compared with a floating-point column to 0.0
            text = text.substring(0, text.indexOf('.'));
        }
        return text;
    }

    private static int lowestPrecedence(String expression) {
        int lowest = PREC_ATOM;
        for (Token token : tokenize(expression, 0, expression.length())) {
            if (token.isOperator()) {
                lowest = Math.min(lowest, precedence(token));
            }
        }
        return lowest;
    }

    private static int precedence(Token token) {
        if (token.kind == Kind.OPERATOR) {
            switch (token.text) {
            case "||":
                return PREC_CONCAT;
            case "+":
            case "-":
                return PREC_ADDITIVE;
            case "*":
            case "/":
            case "%":
                return PREC_MULTIPLICATIVE;
            default:
                return PREC_COMPARISON;
            }
        }
        switch (token.upper()) {
        case "OR":
        case "XOR":
            return PREC_OR;
        case "AND":
            return PREC_AND;
        case "NOT":
            return PREC_NOT;
        case "MOD":
        case "DIV":
            return PREC_MULTIPLICATIVE;
        default:
            return PREC_COMPARISON;
        }
    }

    /* ------------------------------------------------------------------ tokenizing */

    private static List<Token> tokenize(String text, int from, int to) {
        List<Token> tokens = new ArrayList<>();
        int i = from;
        boolean adjacent = false;
        boolean sawToken = false;
        while (i < to) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                adjacent = false;
                i++;
                continue;
            }
            int start = i;
            Kind kind;
            if (c == '\'') {
                i = skipQuoted(text, i, to, '\'');
                kind = Kind.STRING;
            } else if (c == '"') {
                i = skipQuoted(text, i, to, '"');
                kind = Kind.QUOTED;
            } else if (c == '(') {
                int end = skipGroup(text, i, to);
                if (end < 0) {
                    i = start + 1;
                    kind = Kind.PUNCT;
                } else {
                    i = end;
                    kind = Kind.GROUP;
                }
            } else if (Character.isDigit(c)) {
                i++;
                while (i < to && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.'
                        || text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
                    i++;
                }
                kind = Kind.NUMBER;
            } else if (Character.isLetter(c) || c == '_') {
                i++;
                while (i < to && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_'
                        || text.charAt(i) == '$')) {
                    i++;
                }
                kind = Kind.WORD;
            } else {
                i++;
                if (i < to) {
                    String two = text.substring(start, i + 1);
                    if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=")
                            || two.equals("||") || two.equals("!<") || two.equals("!>")) {
                        i++;
                        kind = Kind.OPERATOR;
                    } else {
                        kind = isSingleCharOperator(c) ? Kind.OPERATOR : Kind.PUNCT;
                    }
                } else {
                    kind = isSingleCharOperator(c) ? Kind.OPERATOR : Kind.PUNCT;
                }
            }
            tokens.add(new Token(kind, start, i, text.substring(start, i), sawToken && adjacent));
            sawToken = true;
            adjacent = true;
        }
        return tokens;
    }

    /** @return the index just past the closing quote */
    private static int skipQuoted(String text, int start, int to, char quote) {
        int i = start + 1;
        while (i < to) {
            if (text.charAt(i) == quote) {
                if (i + 1 < to && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return to;
    }

    /** @return the index just past the matching {@code )}, or -1 when the group is unterminated */
    private static int skipGroup(String text, int start, int to) {
        int i = start;
        int depth = 0;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(text, i, to, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return -1;
    }

    private static boolean isSingleCharOperator(char c) {
        return c == '=' || c == '<' || c == '>' || c == '+' || c == '-' || c == '*' || c == '/' || c == '%';
    }
}
