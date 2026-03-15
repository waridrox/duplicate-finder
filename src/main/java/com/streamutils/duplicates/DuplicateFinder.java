package com.streamutils.duplicates;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Utility class for detecting duplicate elements in a {@link Stream}.
 *
 * <p>All elements must implement {@link Serializable} and provide meaningful
 * {@link Object#equals(Object)} and {@link Object#hashCode()} implementations,
 * since duplicate detection relies on equality semantics.
 *
 * <p>This class is stateless and thread-safe - all methods are static and
 * create no shared mutable state.
 */
public final class DuplicateFinder {

    private DuplicateFinder() {
        // Utility class - not instantiable.
    }

    /**
     * Returns a stream of elements that appear more than once in the input,
     * in the order of their first occurrence.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * Stream<String> input  = Stream.of("b","a","c","c","e","a","c","d","c","d");
     * Stream<String> result = DuplicateFinder.findDuplicates(input);
     * // result contains: "a", "c", "d"
     * }</pre>
     *
     * <p><b>Assumptions:</b>
     * <ul>
     *   <li>The input stream fits entirely in memory.</li>
     *   <li>Null elements are not permitted and will cause a {@link NullPointerException}.</li>
     * </ul>
     *
     * @param stream the input stream to scan for duplicates
     * @param <T>    element type - must be {@link Serializable} with proper
     *               {@code equals}/{@code hashCode}
     * @return a new stream containing only the duplicated elements, in first-occurrence order
     * @throws NullPointerException if {@code stream} is null or contains null elements
     */
    public static <T extends Serializable> Stream<T> findDuplicates(Stream<T> stream) {
        Objects.requireNonNull(stream, "Input stream must not be null");

        // LinkedHashMap preserves insertion order.
        // Value semantics: null = seen once, Boolean.TRUE = seen more than once (duplicate).
        Map<T, Boolean> seen = new LinkedHashMap<>();

        stream.forEach(element -> {
            Objects.requireNonNull(element, "Stream elements must not be null");
            seen.merge(element, Boolean.FALSE, (existing, unused) -> Boolean.TRUE);
        });

        return seen.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey);
    }
}
