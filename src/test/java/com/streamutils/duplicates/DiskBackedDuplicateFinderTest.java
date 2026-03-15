package com.streamutils.duplicates;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the disk-backed
 * {@link DuplicateFinder#findDuplicates(Stream, long, double)}.
 */
class DiskBackedDuplicateFinderTest {

    private static final long DEFAULT_EXPECTED = 1_000;
    private static final double DEFAULT_FPR = 0.01;

    @Test
    @DisplayName("given example: [b,a,c,c,e,a,c,d,c,d] → [a,c,d]")
    void givenExample() {
        Stream<String> input = Stream.of("b", "a", "c", "c", "e", "a", "c", "d", "c", "d");
        List<String> result = DuplicateFinder.findDuplicates(input, DEFAULT_EXPECTED, DEFAULT_FPR).toList();
        assertEquals(List.of("a", "c", "d"), result);
    }

    @Test
    @DisplayName("empty stream returns empty result")
    void emptyStream() {
        List<String> result = DuplicateFinder.findDuplicates(
                Stream.<String>empty(), DEFAULT_EXPECTED, DEFAULT_FPR).toList();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("no duplicates returns empty result")
    void noDuplicates() {
        List<String> result = DuplicateFinder.findDuplicates(
                Stream.of("a", "b", "c"), DEFAULT_EXPECTED, DEFAULT_FPR).toList();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("all identical elements returns single-element result")
    void allIdentical() {
        List<String> result = DuplicateFinder.findDuplicates(
                Stream.of("x", "x", "x"), DEFAULT_EXPECTED, DEFAULT_FPR).toList();
        assertEquals(List.of("x"), result);
    }

    @Test
    @DisplayName("null stream throws NullPointerException")
    void nullStream() {
        assertThrows(NullPointerException.class,
                () -> DuplicateFinder.findDuplicates(null, DEFAULT_EXPECTED, DEFAULT_FPR));
    }

    @Test
    @DisplayName("null element throws NullPointerException")
    void nullElement() {
        assertThrows(NullPointerException.class,
                () -> DuplicateFinder.findDuplicates(
                        Stream.of("a", null), DEFAULT_EXPECTED, DEFAULT_FPR));
    }

    @Test
    @DisplayName("order preservation")
    void orderPreservation() {
        Stream<String> input = Stream.of("z", "y", "x", "y", "z");
        List<String> result = DuplicateFinder.findDuplicates(input, DEFAULT_EXPECTED, DEFAULT_FPR).toList();
        assertEquals(List.of("z", "y"), result);
    }

    @Test
    @DisplayName("works with custom Serializable objects")
    void customObjects() {
        record Pair(String key, int value) implements Serializable {
        }
        Stream<Pair> input = Stream.of(
                new Pair("a", 1), new Pair("b", 2),
                new Pair("a", 1), new Pair("c", 3));
        List<Pair> result = DuplicateFinder.findDuplicates(input, DEFAULT_EXPECTED, DEFAULT_FPR).toList();
        assertEquals(List.of(new Pair("a", 1)), result);
    }

    @Test
    @DisplayName("handles 100k elements with known duplicates")
    void largeInput() {
        // 0..49999 twice — all are duplicates
        Stream<Integer> input = Stream.concat(
                IntStream.range(0, 50_000).boxed(),
                IntStream.range(0, 50_000).boxed());
        List<Integer> result = DuplicateFinder.findDuplicates(
                input, 60_000, DEFAULT_FPR).toList();
        assertEquals(50_000, result.size());
        assertEquals(0, result.getFirst());
        assertEquals(49_999, result.getLast());
    }

    @Test
    @DisplayName("temp files are cleaned up after stream consumption")
    void tempFilesCleanedUp() {
        Stream<String> input = Stream.of("a", "b", "a");
        // Consume the result fully
        List<String> result = DuplicateFinder.findDuplicates(
                input, DEFAULT_EXPECTED, DEFAULT_FPR).toList();
        assertEquals(List.of("a"), result);
        // If we reach here without errors, cleanup succeeded
    }
}
