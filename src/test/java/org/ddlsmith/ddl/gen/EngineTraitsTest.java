package org.ddlsmith.ddl.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;

import org.ddlsmith.common.schema.DataType;
import org.junit.jupiter.api.Test;

/**
 * Verifies the engine facts the generator has to respect and that no SQL syntax carries.
 *
 * <p>Every number asserted here was measured against MySQL 26.7.0 with
 * {@code innodb_default_row_format=dynamic} and {@code character_set_server=utf8mb4}, and is stated by
 * the manual: the index key prefix limit is 3072 bytes for the DYNAMIC and COMPRESSED row formats, a
 * multicolumn index takes at most 16 columns, and the limit counts bytes rather than characters.</p>
 */
class EngineTraitsTest {

    private static final Predicate<String> MYSQL = "mysql"::equals;
    private static final Predicate<String> POSTGRES = "postgres"::equals;

    /**
     * Measured: {@code UNIQUE (a,b,c,d)} over four {@code VARCHAR(192)} columns is created, and over
     * four {@code VARCHAR(193)} columns MySQL answers 1071 "Specified key was too long; max key length
     * is 3072 bytes". A {@code VARCHAR(768)} is the most one utf8mb4 column may hold.
     */
    @Test
    void theKeyByteLimitIsTheMeasuredOne() {
        assertEquals(3072, EngineTraits.maxIndexKeyBytes(MYSQL));
        assertEquals(3072, EngineTraits.maxIndexKeyBytes("mariadb"::equals));
        assertEquals(Integer.MAX_VALUE, EngineTraits.maxIndexKeyBytes(POSTGRES),
                "the other engines impose no limit DDLSmith has to know about");
    }

    @Test
    void aCharacterColumnCostsFourBytesPerCharacter() {
        assertEquals(4 * 192, EngineTraits.indexKeyBytes(DataType.varchar(192)));
        assertEquals(4 * 20, EngineTraits.indexKeyBytes(DataType.charType(20)));
        assertEquals(4 * 36, EngineTraits.indexKeyBytes(DataType.uuid()),
                "MySQL has no UUID type, so the dialect renders it as a 36-character column");
    }

    /** The measured sizes, not the widths of the rendered type names. */
    @Test
    void fixedWidthKeyColumnsCostWhatInnoDBStores() {
        assertEquals(1, EngineTraits.indexKeyBytes(DataType.bool()));
        assertEquals(2, EngineTraits.indexKeyBytes(DataType.smallint()));
        assertEquals(4, EngineTraits.indexKeyBytes(DataType.integer()));
        assertEquals(8, EngineTraits.indexKeyBytes(DataType.bigint()));
        assertEquals(8, EngineTraits.indexKeyBytes(DataType.doublePrecision()));
        assertEquals(3, EngineTraits.indexKeyBytes(DataType.date()));
        assertEquals(5, EngineTraits.indexKeyBytes(DataType.timestamp()));
        assertEquals(5, EngineTraits.indexKeyBytes(DataType.decimal(10, 2)),
                "eight integral digits take four bytes and two fractional digits take one");
        assertEquals(2, EngineTraits.indexKeyBytes(DataType.enumOf(java.util.List.of("a", "b", "c"))),
                "an enumeration is indexed by its ordinal, not by its label");
    }

    @Test
    void unboundedTypesAreNotKeyColumns() {
        assertEquals(-1, EngineTraits.indexKeyBytes(DataType.text()));
        assertEquals(-1, EngineTraits.indexKeyBytes(DataType.blob()));
        assertEquals(-1, EngineTraits.indexKeyBytes(DataType.json()));
    }

    @Test
    void theLengthThatFitsIsSharedOutFairly() {
        assertEquals(192, EngineTraits.keyColumnLengthFitting(3072, 4, 255));
        assertEquals(153, EngineTraits.keyColumnLengthFitting(3072, 5, 255));
        assertNull(EngineTraits.keyColumnLengthFitting(3072, 4, 100),
                "a column that already fits must be left alone");
        assertNull(EngineTraits.keyColumnLengthFitting(3072, 1, 768));
        assertEquals(1, EngineTraits.keyColumnLengthFitting(0, 4, 255),
                "the floor is one character, and the fixed part of a key is far below the budget");
    }

    /**
     * The codes MySQL answers when a statement breaks a limit DDLSmith models. Without this list the
     * crash oracle reported each of them as "the engine rejected a construct it claims to implement",
     * because MySQL states them with SQLState {@code 42000} rather than the "feature not supported"
     * class the oracle can recognise.
     */
    @Test
    void modeledLimitsAreRecognisedByTheirErrorCode() {
        assertTrue(EngineTraits.isModeledLimitFailure(MYSQL, 1071), "max key length is 3072 bytes");
        assertTrue(EngineTraits.isModeledLimitFailure(MYSQL, 1070), "max 16 key parts");
        assertTrue(EngineTraits.isModeledLimitFailure(MYSQL, 3823), "CHECK over a referential action column");
        assertFalse(EngineTraits.isModeledLimitFailure(MYSQL, 1146),
                "a missing table is still reportable: dropping it could hide a defect that is the engine's");
        assertFalse(EngineTraits.isModeledLimitFailure(MYSQL, 1064), "a syntax error is not one of them");
        assertFalse(EngineTraits.isModeledLimitFailure(POSTGRES, 1071), "the codes are MySQL's");
    }

    /**
     * The manual: "you cannot use self-referential {@code ON UPDATE CASCADE} or {@code ON UPDATE SET
     * NULL} operations", while "a self-referential {@code ON DELETE SET NULL} … is possible, as is a
     * self-referential {@code ON DELETE CASCADE}". Measured on 26.7.0: error 1451, with
     * {@code innodb_native_foreign_keys} on and off alike; PostgreSQL carries the same schema out.
     */
    @Test
    void selfReferentialUpdateActionsAreRefusedWhereTheManualSaysSo() {
        assertTrue(EngineTraits.refusesSelfReferentialUpdateAction(MYSQL));
        assertTrue(EngineTraits.refusesSelfReferentialUpdateAction("mariadb"::equals));
        assertFalse(EngineTraits.refusesSelfReferentialUpdateAction(POSTGRES),
                "only the ON UPDATE actions are affected, and only on MySQL");
    }

    /**
     * The manual puts InnoDB on the standard's other side of the RESTRICT/NO ACTION split, and 26.7.0
     * behaves that way: a child row held by a RESTRICT foreign key is still removed by a sibling
     * {@code ON DELETE CASCADE} on the same parent key.
     */
    @Test
    void restrictIsNoActionWhereTheEngineSaysSo() {
        assertTrue(EngineTraits.restrictIsNoAction(MYSQL));
        assertTrue(EngineTraits.restrictIsNoAction("mariadb"::equals));
        assertFalse(EngineTraits.restrictIsNoAction(POSTGRES),
                "PostgreSQL checks RESTRICT immediately and cannot defer it");
    }
}
