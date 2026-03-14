package com.streamutils.duplicates;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateFinderTest {

    @Test
    @DisplayName("given example: [b,a,c,c,e,a,c,d,c,d] → [a,c,d]")
    void givenExample() {
        Stream<String> input = Stream.of("b", "a", "c", "c", "e", "a", "c", "d", "c", "d");
        List<String> result = DuplicateFinder.findDuplicates(input).toList();
        assertEquals(List.of("a", "c", "d"), result);
    }

    @Test
    @DisplayName("empty stream returns empty result")
    void emptyStream() {
        List<String> result = DuplicateFinder.findDuplicates(Stream.<String>empty()).toList();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("no duplicates returns empty result")
    void noDuplicates() {
        List<String> result = DuplicateFinder.findDuplicates(Stream.of("a", "b", "c")).toList();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("all identical elements returns single-element result")
    void allIdentical() {
        List<String> result = DuplicateFinder.findDuplicates(Stream.of("x", "x", "x")).toList();
        assertEquals(List.of("x"), result);
    }

    @Test
    @DisplayName("single element returns empty result")
    void singleElement() {
        List<String> result = DuplicateFinder.findDuplicates(Stream.of("a")).toList();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("null stream throws NullPointerException")
    void nullStream() {
        assertThrows(NullPointerException.class, () -> DuplicateFinder.findDuplicates(null));
    }

    @Test
    @DisplayName("null element in stream throws NullPointerException")
    void nullElement() {
        Stream<String> input = Stream.of("a", null, "b");
        assertThrows(NullPointerException.class, () -> DuplicateFinder.findDuplicates(input));
    }

    @Test
    @DisplayName("preserves first-occurrence order of duplicates")
    void orderPreservation() {
        Stream<String> input = Stream.of("z", "y", "x", "y", "z");
        List<String> result = DuplicateFinder.findDuplicates(input).toList();
        assertEquals(List.of("z", "y"), result);
    }

    @Test
    @DisplayName("works with Integer (Serializable with equals/hashCode)")
    void integerDuplicates() {
        Stream<Integer> input = Stream.of(1, 2, 3, 2, 4, 3, 5);
        List<Integer> result = DuplicateFinder.findDuplicates(input).toList();
        assertEquals(List.of(2, 3), result);
    }

    @Test
    @DisplayName("works with custom Serializable objects")
    void customObjects() {
        Stream<Pair> input = Stream.of(
                new Pair("a", 1), new Pair("b", 2),
                new Pair("a", 1), new Pair("c", 3));
        List<Pair> result = DuplicateFinder.findDuplicates(input).toList();
        assertEquals(List.of(new Pair("a", 1)), result);
    }

    @Test
    @DisplayName("large input with known duplicates")
    void largeInput() {
        // 10_000 elements: 0..4999 each appearing twice
        Stream<Integer> input = Stream.concat(
                Stream.iterate(0, i -> i + 1).limit(5000),
                Stream.iterate(0, i -> i + 1).limit(5000));
        List<Integer> result = DuplicateFinder.findDuplicates(input).toList();
        assertEquals(5000, result.size());
        assertEquals(0, result.getFirst());
        assertEquals(4999, result.getLast());
    }

    /** Simple serializable pair for testing custom objects. */
    record Pair(String key, int value) implements Serializable {
    }
}
