package com.streamutils.duplicates;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the disk-backed {@link DuplicateFinder#findDuplicates(Stream, long)}.
 */
class DiskBackedDuplicateFinderTest {

    private static final long MAX_IN_MEMORY = 500;

    @Test
    @DisplayName("given example: [b,a,c,c,e,a,c,d,c,d] → [a,c,d]")
    void givenExample() {
        Stream<String> input = Stream.of("b", "a", "c", "c", "e", "a", "c", "d", "c", "d");
        List<String> result = DuplicateFinder.findDuplicates(input, MAX_IN_MEMORY).toList();
        assertEquals(List.of("a", "c", "d"), result);
    }

    @Test
    @DisplayName("empty stream returns empty result")
    void emptyStream() {
        List<String> result = DuplicateFinder.findDuplicates(
                Stream.<String>empty(), MAX_IN_MEMORY).toList();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("no duplicates returns empty result")
    void noDuplicates() {
        List<String> result = DuplicateFinder.findDuplicates(
                Stream.of("a", "b", "c"), MAX_IN_MEMORY).toList();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("all identical elements returns single-element result")
    void allIdentical() {
        List<String> result = DuplicateFinder.findDuplicates(
                Stream.of("x", "x", "x"), MAX_IN_MEMORY).toList();
        assertEquals(List.of("x"), result);
    }

    @Test
    @DisplayName("null stream throws NullPointerException")
    void nullStream() {
        assertThrows(NullPointerException.class,
                () -> DuplicateFinder.findDuplicates(null, MAX_IN_MEMORY));
    }

    @Test
    @DisplayName("null element throws NullPointerException")
    void nullElement() {
        assertThrows(NullPointerException.class,
                () -> DuplicateFinder.findDuplicates(Stream.of("a", null), MAX_IN_MEMORY));
    }

    @Test
    @DisplayName("order preservation")
    void orderPreservation() {
        Stream<String> input = Stream.of("z", "y", "x", "y", "z");
        List<String> result = DuplicateFinder.findDuplicates(input, MAX_IN_MEMORY).toList();
        assertEquals(List.of("z", "y"), result);
    }

    @Test
    @DisplayName("works with custom Serializable objects")
    void customObjects() {
        record Pair(String key, int value) implements Serializable {}
        Stream<Pair> input = Stream.of(
                new Pair("a", 1), new Pair("b", 2),
                new Pair("a", 1), new Pair("c", 3));
        List<Pair> result = DuplicateFinder.findDuplicates(input, MAX_IN_MEMORY).toList();
        assertEquals(List.of(new Pair("a", 1)), result);
    }

    @Test
    @DisplayName("handles 10k elements with known duplicates")
    void largeInput() {
        // 0..4999 twice — all are duplicates
        Stream<Integer> input = Stream.concat(
                IntStream.range(0, 5_000).boxed(),
                IntStream.range(0, 5_000).boxed());
        List<Integer> result = DuplicateFinder.findDuplicates(input, MAX_IN_MEMORY).toList();
        assertEquals(5_000, result.size());
        assertEquals(0, result.getFirst());
        assertEquals(4_999, result.getLast());
    }

    @Test
    @DisplayName("forces recursive sub-partitioning with tight memory budget")
    void tightMemoryBudget() {
        // 1000 elements, only 50 allowed in memory → forces recursive partitioning
        Stream<Integer> input = Stream.concat(
                IntStream.range(0, 500).boxed(),
                IntStream.range(0, 500).boxed());
        List<Integer> result = DuplicateFinder.findDuplicates(input, 50).toList();
        assertEquals(500, result.size());
        assertEquals(0, result.getFirst());
        assertEquals(499, result.getLast());
    }

    @Test
    @DisplayName("invalid maxElementsInMemory throws IllegalArgumentException")
    void invalidMemoryBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> DuplicateFinder.findDuplicates(Stream.of("a"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> DuplicateFinder.findDuplicates(Stream.of("a"), -1));
    }
}
