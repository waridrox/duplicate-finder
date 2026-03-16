package com.streamutils.duplicates;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
 * <li>{@link #findDuplicates(Stream, long)} — disk-backed with recursive
 * hash partitioning and external sorting, for unbounded streams.</li>
 * </ul>
 */
public final class DuplicateFinder {

    private static final int NUM_PARTITIONS = 16;
    private static final int MAX_PARTITION_DEPTH = 8;

    private DuplicateFinder() {
        // Utility class - not instantiable.
    }

    // ======================================================================
    // Stage 1: In-memory
    // ======================================================================

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
     * @param stream the input stream (must fit in memory)
     * @param <T>    element type
     * @return duplicated elements in first-occurrence order
     * @throws NullPointerException if {@code stream} or any element is null
     */
    public static <T extends Serializable> Stream<T> findDuplicates(Stream<T> stream) {
        Objects.requireNonNull(stream, "Input stream must not be null");

        Map<T, Boolean> seen = new LinkedHashMap<>();
        stream.forEach(element -> {
            Objects.requireNonNull(element, "Stream elements must not be null");
            seen.merge(element, Boolean.FALSE, (existing, unused) -> Boolean.TRUE);
        });

        return seen.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey);
    }

    // ======================================================================
    // Stage 2: Disk-backed (recursive hash partitioning + external sort)
    // ======================================================================

    /**
     * Disk-backed variant for unbounded or memory-constrained environments.
     *
     * <p>
     * <b>Algorithm:</b>
     * <ol>
     * <li>Partition the stream into {@value NUM_PARTITIONS} disk files by
     * element hash. Each record stores (globalIndex, element).</li>
     * <li>For each partition: if small enough, load into a {@link HashMap}
     * and detect duplicates. If too large, recursively sub-partition
     * with a different hash mixing.</li>
     * <li>Externally sort the collected duplicate records by global index
     * using {@link ExternalSorter}.</li>
     * <li>Stream the sorted result, cleaning up temp files on completion.</li>
     * </ol>
     *
     * @param stream              the (potentially unbounded) input stream
     * @param maxElementsInMemory maximum number of elements to hold in memory
     * @param <T>                 element type
     * @return duplicated elements in first-occurrence order
     * @throws NullPointerException     if stream or elements are null
     * @throws IllegalArgumentException if maxElementsInMemory ≤ 0
     * @throws UncheckedIOException     if disk I/O fails
     */
    public static <T extends Serializable> Stream<T> findDuplicates(
            Stream<T> stream, long maxElementsInMemory) {

        Objects.requireNonNull(stream, "Input stream must not be null");
        if (maxElementsInMemory <= 0) {
            throw new IllegalArgumentException("maxElementsInMemory must be > 0");
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("dupfinder-");

            // Phase 1: partition stream to disk
            long[] partCounts = partitionStream(stream, workDir);

            // Phase 2: find duplicates in each partition (recursive)
            Path duplicatesFile = workDir.resolve("duplicates.dat");
            int totalDups = detectDuplicates(partCounts, workDir, maxElementsInMemory,
                    duplicatesFile);

            if (totalDups == 0) {
                deleteRecursively(workDir);
                return Stream.empty();
            }

            // Phase 3: external sort by global index
            int memBudget = (int) Math.min(maxElementsInMemory, Integer.MAX_VALUE);
            Path sortedFile = ExternalSorter.sort(duplicatesFile, memBudget, workDir);

            // Phase 4: stream results, clean up on close
            return streamFromFile(sortedFile, workDir);

        } catch (IOException e) {
            if (workDir != null) {
                try {
                    deleteRecursively(workDir);
                } catch (IOException ignored) {
                }
            }
            throw new UncheckedIOException(e);
        }
    }

    // --- Phase 1: partitioning ---

    private static <T extends Serializable> long[] partitionStream(
            Stream<T> stream, Path workDir) throws IOException {

        Path partDir = workDir.resolve("level-0");
        Files.createDirectories(partDir);

        long[] counts = new long[NUM_PARTITIONS];
        ObjectOutputStream[] outs = new ObjectOutputStream[NUM_PARTITIONS];

        try {
            for (int i = 0; i < NUM_PARTITIONS; i++) {
                outs[i] = new ObjectOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(partDir.resolve(i + ".dat"))));
            }

            long[] index = { 0 };
            stream.forEach(element -> {
                Objects.requireNonNull(element, "Stream elements must not be null");
                int part = Math.floorMod(element.hashCode(), NUM_PARTITIONS);
                try {
                    outs[part].writeObject(new IndexedElement<>(index[0]++, element));
                    counts[part]++;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } finally {
            closeAll(outs);
        }

        return counts;
    }

    // --- Phase 2: recursive duplicate detection ---

    private static <T extends Serializable> int detectDuplicates(
            long[] partCounts, Path workDir, long maxElements,
            Path duplicatesFile) throws IOException {

        int totalDups = 0;
        try (ObjectOutputStream dupOut = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(duplicatesFile)))) {
            for (int i = 0; i < NUM_PARTITIONS; i++) {
                if (partCounts[i] == 0)
                    continue;
                Path pFile = workDir.resolve("level-0").resolve(i + ".dat");
                totalDups += processPartition(pFile, partCounts[i], 0,
                        maxElements, dupOut, workDir);
            }
        }
        return totalDups;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> int processPartition(
            Path file, long entryCount, int depth, long maxElements,
            ObjectOutputStream dupOut, Path workDir) throws IOException {

        // If small enough or max depth reached, process in memory
        if (entryCount <= maxElements || depth >= MAX_PARTITION_DEPTH) {
            return processInMemory(file, dupOut);
        }

        // Sub-partition using a different hash mixing
        Path subDir = Files.createTempDirectory(workDir, "level-" + (depth + 1) + "-");
        long[] subCounts = new long[NUM_PARTITIONS];
        ObjectOutputStream[] subOuts = new ObjectOutputStream[NUM_PARTITIONS];

        try {
            for (int i = 0; i < NUM_PARTITIONS; i++) {
                subOuts[i] = new ObjectOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(subDir.resolve(i + ".dat"))));
            }
            try (ObjectInputStream ois = new ObjectInputStream(
                    new BufferedInputStream(Files.newInputStream(file)))) {
                IndexedElement<T> entry;
                while ((entry = readNext(ois)) != null) {
                    int sub = partitionFor(entry.element(), depth + 1);
                    subOuts[sub].writeObject(entry);
                    subCounts[sub]++;
                }
            }
        } finally {
            closeAll(subOuts);
        }
        Files.deleteIfExists(file);

        // Recurse into sub-partitions
        int totalDups = 0;
        for (int i = 0; i < NUM_PARTITIONS; i++) {
            if (subCounts[i] > 0) {
                totalDups += processPartition(subDir.resolve(i + ".dat"), subCounts[i],
                        depth + 1, maxElements, dupOut, workDir);
            }
        }
        deleteRecursively(subDir);
        return totalDups;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> int processInMemory(
            Path file, ObjectOutputStream dupOut) throws IOException {

        Map<T, Long> firstSeen = new HashMap<>();
        Set<T> duplicates = new LinkedHashSet<>();

        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            IndexedElement<T> entry;
            while ((entry = readNext(ois)) != null) {
                T elem = entry.element();
                Long first = firstSeen.putIfAbsent(elem, entry.index());
                if (first != null) {
                    duplicates.add(elem);
                }
            }
        }
        Files.deleteIfExists(file);

        for (T dup : duplicates) {
            dupOut.writeObject(new IndexedElement<>(firstSeen.get(dup), dup));
        }
        return duplicates.size();
    }

    // --- Phase 4: stream sorted results ---

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> Stream<T> streamFromFile(
            Path sortedFile, Path workDir) throws IOException {

        ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(sortedFile)));

        Iterator<T> iterator = new Iterator<>() {
            private IndexedElement<T> next = safeReadNext(ois);

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public T next() {
                if (next == null)
                    throw new NoSuchElementException();
                T value = next.element();
                next = safeReadNext(ois);
                if (next == null)
                    cleanup();
                return value;
            }

            private IndexedElement<T> safeReadNext(ObjectInputStream in) {
                try {
                    return readNext(in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            private void cleanup() {
                try {
                    ois.close();
                    deleteRecursively(workDir);
                } catch (IOException ignored) {
                }
            }
        };

        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
    }

    // --- utility ---

    /** Serializable record stored on disk: (globalIndex, element). */
    record IndexedElement<T extends Serializable>(long index, T element)
            implements Serializable, Comparable<IndexedElement<T>> {
        @Override
        public int compareTo(IndexedElement<T> other) {
            return Long.compare(this.index, other.index);
        }
    }

    private static int partitionFor(Object element, int level) {
        int h = element.hashCode();
        h = h ^ (level * 0x9E3779B1);
        h = ((h >>> 16) ^ h) * 0x45d9f3b;
        return Math.floorMod(h, NUM_PARTITIONS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T readNext(ObjectInputStream ois) throws IOException {
        try {
            return (T) ois.readObject();
        } catch (EOFException e) {
            return null;
        } catch (ClassNotFoundException e) {
            throw new IOException("Deserialization error – class not found", e);
        }
    }

    private static void closeAll(ObjectOutputStream[] streams) {
        for (var out : streams) {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
