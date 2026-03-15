package com.streamutils.duplicates;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Utility class for detecting duplicate elements in a {@link Stream}.
 *
 * <p>
 * All elements must implement {@link Serializable} and provide meaningful
 * {@link Object#equals(Object)} and {@link Object#hashCode()} implementations,
 * since duplicate detection relies on equality semantics.
 *
 * <p>This class is stateless and thread-safe - all methods are static and
 * create no shared mutable state.
 *
 * <p>
 * Provides two strategies:
 * <ul>
 * <li>{@link #findDuplicates(Stream)} — in-memory, for bounded streams.</li>
 * <li>{@link #findDuplicates(Stream, long, double)} — disk-backed, for
 * unbounded or memory-constrained scenarios.</li>
 * </ul>
 */
public final class DuplicateFinder {

    private DuplicateFinder() {
        // Utility class - not instantiable.
    }

    /**
     * Returns a stream of elements that appear more than once in the input,
     * in the order of their first occurrence.
     *
     * <p>
     * <b>Example:</b>
     * 
     * <pre>{@code
     * Stream<String> input = Stream.of("b", "a", "c", "c", "e", "a", "c", "d", "c", "d");
     * Stream<String> result = DuplicateFinder.findDuplicates(input);
     * // result contains: "a", "c", "d"
     * }</pre>
     *
     * <p>
     * <b>Assumptions:</b>
     * <ul>
     * <li>The input stream fits entirely in memory.</li>
     * <li>Null elements are not permitted and will cause a
     * {@link NullPointerException}.</li>
     * </ul>
     *
     * @param stream the input stream to scan for duplicates
     * @param <T>    element type - must be {@link Serializable} with proper
     *               {@code equals}/{@code hashCode}
     * @return a new stream containing only the duplicated elements, in
     *         first-occurrence order
     * @throws NullPointerException if {@code stream} is null or contains null
     *                              elements
     */
    public static <T extends Serializable> Stream<T> findDuplicates(Stream<T> stream) {
        Objects.requireNonNull(stream, "Input stream must not be null");

        // LinkedHashMap preserves insertion order.
        // Value semantics: FALSE = seen once, TRUE = seen more than once (duplicate).
        Map<T, Boolean> seen = new LinkedHashMap<>();

        stream.forEach(element -> {
            Objects.requireNonNull(element, "Stream elements must not be null");
            seen.merge(element, Boolean.FALSE, (existing, unused) -> Boolean.TRUE);
        });

        return seen.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey);
    }

    /**
     * Disk-backed variant of {@link #findDuplicates(Stream)} for unbounded or
     * memory-constrained environments.
     *
     * <p>
     * Uses a {@link BloomFilter} as a fast "definitely not seen" gate.
     * When the filter reports "maybe seen," a {@link DiskBackedMap} on disk
     * confirms the duplicate. Confirmed duplicates are appended to a temp
     * file to preserve first-occurrence order, and streamed back lazily.
     *
     * <p>
     * <b>Trade-off:</b> the Bloom filter may produce false positives,
     * causing unnecessary disk lookups, but never misses a true duplicate.
     *
     * @param stream             the (potentially unbounded) input stream
     * @param expectedInsertions estimate of distinct elements — used to size the
     *                           Bloom filter
     * @param falsePositiveRate  desired Bloom filter FPR (e.g. 0.01 for 1%)
     * @param <T>                element type
     * @return a stream of duplicated elements in first-occurrence order
     * @throws NullPointerException     if {@code stream} is null or contains null
     *                                  elements
     * @throws IllegalArgumentException if parameters are invalid
     * @throws UncheckedIOException     if disk I/O fails
     */
    public static <T extends Serializable> Stream<T> findDuplicates(
            Stream<T> stream, long expectedInsertions, double falsePositiveRate) {

        Objects.requireNonNull(stream, "Input stream must not be null");

        BloomFilter bloom = new BloomFilter(expectedInsertions, falsePositiveRate);

        try {
            // Value = first-occurrence position (negative means already flagged as
            // duplicate)
            DiskBackedMap<T, Long> seenOnDisk = new DiskBackedMap<>();
            Path duplicatesFile = Files.createTempFile("duplicates-", ".dat");

            // Pass 1: scan all elements, record duplicates with their first-seen position
            long[] position = { 0 };
            int[] duplicateCount = { 0 };

            stream.forEach(element -> {
                Objects.requireNonNull(element, "Stream elements must not be null");
                long pos = position[0]++;
                try {
                    if (bloom.mightContain(element)) {
                        // Bloom says maybe-seen — confirm via disk
                        Long storedPos = seenOnDisk.get(element);
                        if (storedPos == null) {
                            // False positive from Bloom — first real occurrence
                            seenOnDisk.put(element, pos);
                            bloom.put(element);
                        } else if (storedPos >= 0) {
                            // Second real occurrence — it's a duplicate
                            // Negate position to mark as "already recorded"
                            seenOnDisk.put(element, -storedPos - 1);
                            appendToDuplicatesFile(duplicatesFile, element, storedPos);
                            duplicateCount[0]++;
                        }
                        // else: storedPos < 0 → already recorded as duplicate, skip
                    } else {
                        // Definitely not seen — add to Bloom and disk
                        bloom.put(element);
                        seenOnDisk.put(element, pos);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            // Clean up the disk map — no longer needed
            seenOnDisk.close();

            // Pass 2: read duplicates, sort by first-occurrence position, stream back
            if (duplicateCount[0] == 0) {
                Files.deleteIfExists(duplicatesFile);
                return Stream.empty();
            }

            return readAndSortDuplicates(duplicatesFile, duplicateCount[0]);

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- disk I/O helpers ---

    /** Serializable wrapper for (position, element) stored on disk. */
    private record PositionedElement<T extends Serializable>(long position, T element)
            implements Serializable {
    }

    private static <T extends Serializable> void appendToDuplicatesFile(
            Path file, T element, long position) throws IOException {

        try (ObjectOutputStream oos = (Files.size(file) == 0)
                ? new ObjectOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(file)))
                : new AppendableObjectOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(file,
                                java.nio.file.StandardOpenOption.APPEND)))) {
            oos.writeObject(new PositionedElement<>(position, element));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> Stream<T> readAndSortDuplicates(
            Path file, int count) {
        try {
            List<PositionedElement<T>> entries = new ArrayList<>(count);
            try (ObjectInputStream ois = new ObjectInputStream(
                    new BufferedInputStream(Files.newInputStream(file)))) {
                for (int i = 0; i < count; i++) {
                    entries.add((PositionedElement<T>) ois.readObject());
                }
            }
            Files.deleteIfExists(file);

            entries.sort(Comparator.comparingLong(PositionedElement::position));
            return entries.stream().map(PositionedElement::element);

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize duplicates", e);
        }
    }

    /**
     * ObjectOutputStream subclass that suppresses the stream header on append,
     * so multiple objects can be written to the same file across separate
     * open/close cycles and then read back with a single ObjectInputStream.
     */
    private static class AppendableObjectOutputStream extends ObjectOutputStream {
        AppendableObjectOutputStream(OutputStream out) throws IOException {
            super(out);
        }

        @Override
        protected void writeStreamHeader() throws IOException {
            reset();
        }
    }
}
