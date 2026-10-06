package org.ddlsmith.oracle.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.ddlsmith.common.schema.DataType;
import org.junit.jupiter.api.Test;

/**
 * The canonical form the pivot-row oracle uses for key tuples.
 *
 * <p>A generated database of the MySQL campaign reported a {@code DATA_MISMATCH} on
 * {@code SELECT t_2_a6, t_1_a5 FROM t_6_r ORDER BY t_2_a6, t_1_a5} whose two sides held the same
 * four rows: the model renders a temporal value through {@link java.sql.Timestamp}
 * ({@code 2000-01-01 00:00:06.0}) and MySQL Connector/J returns a {@link java.time.LocalDateTime}
 * ({@code 2000-01-01T00:00:06}). The comparison must be driven by the column's declared type, not by
 * the Java type the driver happened to return — and it must still notice a value the engine really
 * did coerce.</p>
 */
class PivotRowOracleCanonicalTest {

    private static final DataType DATE = DataType.date();
    private static final DataType TIME = DataType.time();
    private static final DataType TIMESTAMP = DataType.timestamp();

    @Test
    void timestampAndLocalDateTimeDescribeTheSameValue() {
        assertEquals("2000-01-01 00:00:06",
                PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-01 00:00:06"), TIMESTAMP));
        assertEquals("2000-01-01 00:00:06",
                PivotRowOracle.canonical(java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 6), TIMESTAMP));
        assertEquals(PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-01 00:00:06"), TIMESTAMP),
                PivotRowOracle.canonical(java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 6), TIMESTAMP));
        // SQLite stores the driver's own rendering of the Timestamp and hands back text
        assertEquals(PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-01 00:00:06"), TIMESTAMP),
                PivotRowOracle.canonical("2000-01-01 00:00:06.0", TIMESTAMP));
    }

    @Test
    void fractionalSecondsKeepTheirSignificantDigits() {
        assertEquals("2000-01-01 00:00:06.5",
                PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-01 00:00:06.5"), TIMESTAMP));
        assertEquals(PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-01 00:00:06.5"), TIMESTAMP),
                PivotRowOracle.canonical(java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 6, 500_000_000), TIMESTAMP));
    }

    @Test
    void aDateColumnComparesItsDateAndATimeColumnItsTime() {
        assertEquals("2000-01-12",
                PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-12 00:00:00"), DATE));
        assertEquals("2000-01-12", PivotRowOracle.canonical(java.time.LocalDate.of(2000, 1, 12), DATE));
        assertEquals(PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-12 00:00:00"), DATE),
                PivotRowOracle.canonical("2000-01-12 00:00:00.0", DATE));

        assertEquals("00:00:06", PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-01 00:00:06"), TIME));
        assertEquals("00:00:06", PivotRowOracle.canonical(java.time.LocalTime.of(0, 0, 6), TIME));
        assertEquals(PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-01 00:00:06"), TIME),
                PivotRowOracle.canonical(java.time.LocalTime.of(0, 0, 6), TIME));
    }

    @Test
    void aDifferentInstantIsStillDifferent() {
        assertNotEquals(PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-01 00:00:06"), TIMESTAMP),
                PivotRowOracle.canonical(java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 7), TIMESTAMP));
        assertNotEquals(PivotRowOracle.canonical(java.sql.Timestamp.valueOf("2000-01-01 00:00:06"), TIMESTAMP),
                PivotRowOracle.canonical(java.time.LocalDateTime.of(2000, 1, 2, 0, 0, 6), TIMESTAMP));
        // a second the engine dropped is a coercion, not a rendering difference
        assertNotEquals(PivotRowOracle.canonical(java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 6), TIMESTAMP),
                PivotRowOracle.canonical(java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 6, 500_000_000), TIMESTAMP));
    }

    @Test
    void nonTemporalColumnsKeepTheirOwnRules() {
        assertEquals("1", PivotRowOracle.canonical(1, DataType.integer()));
        assertEquals(PivotRowOracle.canonical(1, DataType.integer()),
                PivotRowOracle.canonical(1L, DataType.integer()));
        assertEquals("abc", PivotRowOracle.canonical("abc  ", DataType.charType(5)));
        // a textual column whose value merely looks like a date is not a temporal column
        assertEquals("2000-01-01 00:00:06.0",
                PivotRowOracle.canonical("2000-01-01 00:00:06.0", DataType.varchar(20)));
    }
}
