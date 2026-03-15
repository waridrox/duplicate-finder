package com.streamutils.duplicates;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * External merge sort for {@link Serializable} and {@link Comparable} records
 * that do not fit in memory.
 *
 * <ol>
 * <li>Split input into sorted runs that fit within a memory budget.</li>
 * <li>K-way merge all runs using a {@link PriorityQueue}.</li>
 * </ol>
 */
final class ExternalSorter {

    private ExternalSorter() {
    }

    /**
     * Sorts records in {@code inputFile} and writes the result to a new file.
     *
     * @param inputFile          file of serialized records
     * @param maxRecordsInMemory max records to hold in memory per run
     * @param workDir            directory for temporary run files
     * @return path to the sorted output file
     */
    static <T extends Serializable & Comparable<? super T>> Path sort(
            Path inputFile, int maxRecordsInMemory, Path workDir) throws IOException {

        List<Path> runs = createSortedRuns(inputFile, maxRecordsInMemory, workDir);

        if (runs.isEmpty())
            return inputFile;
        if (runs.size() == 1)
            return runs.getFirst();

        return mergeRuns(runs, workDir);
    }

    // --- Phase 1: create sorted runs ---

    @SuppressWarnings("unchecked")
    private static <T extends Serializable & Comparable<? super T>> List<Path> createSortedRuns(Path input,
            int maxRecords, Path workDir) throws IOException {

        List<Path> runs = new ArrayList<>();
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(input)))) {
            List<T> buffer = new ArrayList<>(maxRecords);
            boolean eof = false;
            while (!eof) {
                try {
                    buffer.add((T) ois.readObject());
                    if (buffer.size() >= maxRecords) {
                        runs.add(flushRun(buffer, workDir, runs.size()));
                        buffer.clear();
                    }
                } catch (EOFException e) {
                    eof = true;
                } catch (ClassNotFoundException e) {
                    throw new IOException("Deserialization error", e);
                }
            }
            if (!buffer.isEmpty()) {
                runs.add(flushRun(buffer, workDir, runs.size()));
            }
        }
        return runs;
    }

    private static <T extends Serializable & Comparable<? super T>> Path flushRun(List<T> buffer, Path workDir,
            int index) throws IOException {
        Collections.sort(buffer);
        Path file = workDir.resolve("run-" + index + ".dat");
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            for (T record : buffer) {
                oos.writeObject(record);
            }
        }
        return file;
    }

    // --- Phase 2: k-way merge ---

    @SuppressWarnings("unchecked")
    private static <T extends Serializable & Comparable<? super T>> Path mergeRuns(List<Path> runs, Path workDir)
            throws IOException {

        Path outputFile = workDir.resolve("sorted-output.dat");
        PriorityQueue<MergeEntry<T>> pq = new PriorityQueue<>();
        List<ObjectInputStream> inputs = new ArrayList<>();

        try {
            for (int i = 0; i < runs.size(); i++) {
                ObjectInputStream ois = new ObjectInputStream(
                        new BufferedInputStream(Files.newInputStream(runs.get(i))));
                inputs.add(ois);
                T first = readNext(ois);
                if (first != null) {
                    pq.add(new MergeEntry<>(first, i));
                }
            }

            try (ObjectOutputStream out = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(outputFile)))) {
                while (!pq.isEmpty()) {
                    MergeEntry<T> entry = pq.poll();
                    out.writeObject(entry.value);
                    T next = readNext(inputs.get(entry.sourceIndex));
                    if (next != null) {
                        pq.add(new MergeEntry<>(next, entry.sourceIndex));
                    }
                }
            }
        } finally {
            for (ObjectInputStream ois : inputs) {
                try {
                    ois.close();
                } catch (IOException ignored) {
                }
            }
            for (Path run : runs) {
                Files.deleteIfExists(run);
            }
        }

        return outputFile;
    }

    @SuppressWarnings("unchecked")
    private static <T> T readNext(ObjectInputStream ois) throws IOException {
        try {
            return (T) ois.readObject();
        } catch (EOFException e) {
            return null;
        } catch (ClassNotFoundException e) {
            throw new IOException("Deserialization error", e);
        }
    }

    private record MergeEntry<T extends Comparable<? super T>>(T value, int sourceIndex)
            implements Comparable<MergeEntry<T>> {
        @Override
        public int compareTo(MergeEntry<T> other) {
            return this.value.compareTo(other.value);
        }
    }
}
