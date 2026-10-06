package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Verifies that every random decision is reproducible from the seed. */
class RandomlyTest {

    @Test
    void sameSeedProducesSameSequence() {
        List<String> first = sequence(12345L);
        List<String> second = sequence(12345L);
        assertEquals(first, second, "the same seed must produce exactly the same sequence");
    }

    @Test
    void differentSeedsProduceDifferentSequences() {
        assertNotEquals(sequence(1L), sequence(2L));
    }

    @Test
    void integerBoundsAreRespected() {
        Randomly.setSeed(7L);
        for (int i = 0; i < 10_000; i++) {
            int value = Randomly.getInteger(3, 9);
            assertTrue(value >= 3 && value < 9, "value out of range: " + value);
        }
    }

    @Test
    void getIntegerWithBoundIsNonNegative() {
        Randomly.setSeed(11L);
        for (int i = 0; i < 10_000; i++) {
            int value = Randomly.getInteger(5);
            assertTrue(value >= 0 && value < 5, "value out of range: " + value);
        }
    }

    @Test
    void emptyInputsAreRejected() {
        Randomly.setSeed(3L);
        assertThrows(IllegalArgumentException.class, () -> Randomly.fromList(List.of()));
        assertThrows(IllegalArgumentException.class, () -> Randomly.fromOptions());
        assertThrows(IllegalArgumentException.class, () -> Randomly.extractNrRandomColumns(List.of("a"), 2));
    }

    @Test
    void subsetHasRequestedSizeAndNoDuplicates() {
        Randomly.setSeed(99L);
        List<Integer> source = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            source.add(i);
        }
        for (int i = 0; i < 1_000; i++) {
            List<Integer> subset = Randomly.extractNrRandomColumns(source, 5);
            assertEquals(5, subset.size());
            assertEquals(5, new java.util.LinkedHashSet<>(subset).size(), "subset must not contain duplicates");
        }
    }

    @Test
    void shuffleIsAPermutation() {
        Randomly.setSeed(5L);
        List<Integer> source = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            source.add(i);
        }
        List<Integer> copy = new ArrayList<>(source);
        Randomly.shuffle(copy);
        assertEquals(source.size(), copy.size());
        assertEquals(new java.util.TreeSet<>(source), new java.util.TreeSet<>(copy));
    }

    @Test
    void booleanProbabilityIsRoughlyCorrect() {
        Randomly.setSeed(17L);
        int hits = 0;
        int trials = 100_000;
        for (int i = 0; i < trials; i++) {
            if (Randomly.getBoolean(0.25)) {
                hits++;
            }
        }
        double ratio = (double) hits / trials;
        assertTrue(ratio > 0.23 && ratio < 0.27, "unexpected ratio " + ratio);
    }

    private static List<String> sequence(long seed) {
        Randomly.setSeed(seed);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            values.add(Randomly.getInteger() + ":" + Randomly.getBoolean() + ":" + Randomly.getString());
        }
        return values;
    }
}
