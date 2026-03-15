package com.streamutils.duplicates;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A simple disk-backed key-value store that shards serialized entries across
 * multiple files on disk.
 *
 * <p>
 * Designed for the "memory-constrained but disk-unlimited" scenario: keys
 * and values are serialized to temp files grouped by hash bucket, so a lookup
 * only needs to scan one shard rather than the entire dataset.
 *
 * <p>
 * Intended as an internal component — not part of the public API.
 *
 * @param <K> key type (must be {@link Serializable})
 * @param <V> value type (must be {@link Serializable})
 */
final class DiskBackedMap<K extends Serializable, V extends Serializable> implements Closeable {

    private static final int DEFAULT_SHARD_COUNT = 64;

    private final int shardCount;
    private final Path tempDir;

    /**
     * Creates a new disk-backed map with the default number of shards.
     *
     * @throws IOException if the temp directory cannot be created
     */
    DiskBackedMap() throws IOException {
        this(DEFAULT_SHARD_COUNT);
    }

    /**
     * Creates a new disk-backed map with the specified number of shards.
     *
     * @param shardCount number of file shards to distribute entries across
     * @throws IOException if the temp directory cannot be created
     */
    DiskBackedMap(int shardCount) throws IOException {
        this.shardCount = shardCount;
        this.tempDir = Files.createTempDirectory("diskmap-");
    }

    /**
     * Stores a key-value pair. If the key already exists, the new value
     * replaces the old one on disk.
     */
    void put(K key, V value) throws IOException {
        int shard = shardFor(key);
        Path shardFile = shardPath(shard);

        List<Entry<K, V>> entries = readShard(shardFile);
        boolean replaced = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key().equals(key)) {
                entries.set(i, new Entry<>(key, value));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entries.add(new Entry<>(key, value));
        }
        writeShard(shardFile, entries);
    }

    /**
     * Returns the value associated with the key, or {@code null} if absent.
     */
    V get(K key) throws IOException {
        Path shardFile = shardPath(shardFor(key));
        if (!Files.exists(shardFile)) {
            return null;
        }
        for (Entry<K, V> entry : readShard(shardFile)) {
            if (entry.key().equals(key)) {
                return entry.value();
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if the key is present in the map.
     */
    boolean containsKey(K key) throws IOException {
        return get(key) != null;
    }

    /**
     * Deletes all shard files and the temp directory.
     */
    @Override
    public void close() throws IOException {
        if (Files.exists(tempDir)) {
            try (var files = Files.walk(tempDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                // Best-effort cleanup
                            }
                        });
            }
        }
    }

    // --- internals ---

    private int shardFor(K key) {
        return Math.floorMod(key.hashCode(), shardCount);
    }

    private Path shardPath(int shard) {
        return tempDir.resolve("shard-" + shard + ".dat");
    }

    @SuppressWarnings("unchecked")
    private List<Entry<K, V>> readShard(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            return (List<Entry<K, V>>) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Corrupt shard file: " + path, e);
        }
    }

    private void writeShard(Path path, List<Entry<K, V>> entries) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {
            oos.writeObject(entries);
        }
    }

    /** Serializable key-value pair stored on disk. */
    record Entry<K extends Serializable, V extends Serializable>(K key, V value)
            implements Serializable {
    }
}
