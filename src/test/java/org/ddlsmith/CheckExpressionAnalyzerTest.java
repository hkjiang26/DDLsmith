package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ddlsmith.data.CheckExpressionAnalyzer;
import org.junit.jupiter.api.Test;

/** Verifies the CHECK expression analysis that the row generator and the constraint oracles rely on. */
class CheckExpressionAnalyzerTest {

    @Test
    void parsesSimpleComparisons() {
        CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse("a3 >= 0");
        assertTrue(analyzer.isUnderstood());
        assertEquals("a3", analyzer.getColumnName());
        assertTrue(analyzer.isSatisfiedBy(5L));
        assertTrue(analyzer.isSatisfiedBy(0L));
        assertFalse(analyzer.isSatisfiedBy(-1L));
        assertEquals(-1L, ((Number) analyzer.violate(5L)).longValue());
        assertEquals(0L, ((Number) analyzer.satisfy(-5L)).longValue());
    }

    @Test
    void parsesNotEqual() {
        CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse("c1 <> 4");
        assertTrue(analyzer.isUnderstood());
        assertFalse(analyzer.isSatisfiedBy(4L));
        assertTrue(analyzer.isSatisfiedBy(5L));
        assertEquals(4L, ((Number) analyzer.violate(5L)).longValue());
    }

    @Test
    void parsesInLists() {
        CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse("dtype IN ('A', 'B')");
        assertTrue(analyzer.isUnderstood());
        assertEquals("dtype", analyzer.getColumnName());
        assertTrue(analyzer.isSatisfiedBy("A"));
        assertFalse(analyzer.isSatisfiedBy("C"));
        assertEquals("A", analyzer.satisfy("C"));
    }

    @Test
    void parsesIsNotNull() {
        CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse("a1 IS NOT NULL");
        assertTrue(analyzer.isUnderstood());
        assertFalse(analyzer.isSatisfiedBy(null));
        assertTrue(analyzer.isSatisfiedBy(1L));
        assertEquals(null, analyzer.violate(1L));
    }

    @Test
    void nullSatisfiesAComparisonBecauseTheResultIsUnknown() {
        CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse("a2 > 10");
        assertTrue(analyzer.isSatisfiedBy(null), "SQL treats UNKNOWN as satisfied for CHECK constraints");
    }

    @Test
    void splitsConjunctions() {
        assertEquals(2, CheckExpressionAnalyzer.splitConjuncts("a1 > 0 AND a2 IS NOT NULL").size());
        assertEquals(1, CheckExpressionAnalyzer.splitConjuncts("a1 > 0").size());
        // the AND inside a string literal must not split
        assertEquals(1, CheckExpressionAnalyzer.splitConjuncts("a1 <> 'x AND y'").size());
    }

    @Test
    void splitsDisjunctions() {
        assertEquals(2, CheckExpressionAnalyzer.splitDisjuncts("a1 IS NOT NULL OR a2 IS NOT NULL").size());
        assertEquals(3, CheckExpressionAnalyzer.splitDisjuncts("a1 IS NOT NULL OR a2 IS NOT NULL OR a3 IS NOT NULL")
                .size());
        assertEquals(1, CheckExpressionAnalyzer.splitDisjuncts("a1 IS NOT NULL").size());
        // the OR inside a string literal must not split
        assertEquals(1, CheckExpressionAnalyzer.splitDisjuncts("a1 <> 'x OR y'").size());
        // an operand that is itself parenthesised stays one operand
        assertEquals(2, CheckExpressionAnalyzer.splitDisjuncts("(a1 IS NOT NULL) OR (a2 IS NOT NULL)").size());
    }

    @Test
    void aKeywordInsideAnIdentifierIsNotASeparator() {
        // "order" contains "or", "android" contains "and": neither may split
        assertEquals(1, CheckExpressionAnalyzer.splitDisjuncts("order_id IS NOT NULL").size());
        assertEquals(1, CheckExpressionAnalyzer.splitConjuncts("android IS NOT NULL").size());
        assertEquals(2, CheckExpressionAnalyzer.splitDisjuncts("a OR order_id IS NOT NULL").size());
    }

    @Test
    void rejectsExpressionsItDoesNotUnderstand() {
        assertFalse(CheckExpressionAnalyzer.isSimple("length(a1) > 3"));
        assertFalse(CheckExpressionAnalyzer.isSimple("a1 + a2 > 3"));
        assertFalse(CheckExpressionAnalyzer.isSimple("a1 > 0 AND a2 > 0"));
    }

    @Test
    void parsesLiterals() {
        assertEquals("x", CheckExpressionAnalyzer.parseLiteral("'x'"));
        assertEquals(42L, CheckExpressionAnalyzer.parseLiteral("42"));
        assertEquals(Boolean.TRUE, CheckExpressionAnalyzer.parseLiteral("TRUE"));
        assertEquals(42L, CheckExpressionAnalyzer.parseLiteral("42::integer"));
    }
}
