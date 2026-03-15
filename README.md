# Duplicate Finder - Stage 2 (Disk-Backed, Unbounded Streams)

Extends Stage 1 to handle unbounded streams in memory-constrained environments.

## What Changed

The original in-memory `findDuplicates(Stream<T>)` is preserved. A new overload is added:

```java
DuplicateFinder.findDuplicates(stream, expectedDistinctElements, falsePositiveRate);
```

## How It Works

1. **Bloom filter** (fixed-memory, `BitSet`) — fast "definitely not seen" gate.
2. **Disk-backed sharded map** — confirms duplicates when Bloom reports "maybe seen."
3. **Temp file** — records confirmed duplicates with first-occurrence positions; sorted before returning.

Trade-off: Bloom filter false positives cause extra disk reads, but never miss a real duplicate.

## Requirements

- JDK 17+
- Maven 3.9+

## Build & Test

```bash
mvn clean test
```

## Usage

```java
// In-memory (Stage 1 - bounded streams)
Stream<String> result = DuplicateFinder.findDuplicates(stream);

// Disk-backed (Stage 2 - unbounded streams)
Stream<String> result = DuplicateFinder.findDuplicates(stream, 1_000_000, 0.01);
```

## Design Decisions

- **No third-party libraries** in main code (custom Bloom filter using `BitSet` + double-hashing).
- **Sharded disk map** splits entries across 64 temp files by hash bucket — lookups scan one shard only.
- **Temp files** are cleaned up automatically after the result stream is consumed.
