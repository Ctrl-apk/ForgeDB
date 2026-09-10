package com.forgedb.benchmark;

import com.forgedb.execution.ExecutionException;
import com.forgedb.execution.Executor;
import com.forgedb.execution.QueryResult;
import com.forgedb.planner.QueryPlan;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DiskManagerImpl;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Repeatable benchmark harness comparing sequential scan vs. B+ tree index lookup.
 *
 * <h2>Methodology</h2>
 * <ol>
 *   <li>Create a temporary database file on disk.</li>
 *   <li>Insert {@code rowCount} rows: {@code (id INT, name TEXT, value INT)} with
 *       sequential integer ids.</li>
 *   <li>Run {@code queryCount} point-lookup queries (equality on {@code id})
 *       <em>without</em> an index → sequential scan.</li>
 *   <li>Create a B+ tree index on {@code id}.</li>
 *   <li>Run the same {@code queryCount} queries <em>with</em> the index →
 *       index lookup.</li>
 *   <li>Also benchmark INSERT throughput and a range scan.</li>
 *   <li>Emit results as human-readable text and CSV.</li>
 * </ol>
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>All timings use wall-clock time ({@link System#nanoTime()}), which
 *       includes I/O, JVM GC, and OS scheduling jitter.</li>
 *   <li>The JVM is not pre-warmed. Small row counts may show JIT compilation
 *       overhead that larger counts amortise.</li>
 *   <li>The buffer pool has a fixed capacity of 256 pages, so large tables
 *       will saturate the cache and produce more realistic disk-I/O behaviour.</li>
 *   <li>Results are written to {@code benchmark-results.csv} in the current
 *       directory alongside a human-readable {@code benchmark-results.txt}.</li>
 *   <li>Temporary database files are deleted after the benchmark completes.</li>
 * </ul>
 *
 * <h2>Running</h2>
 * <pre>
 *   java -cp forgedb.jar com.forgedb.benchmark.BenchmarkRunner
 *   java -cp forgedb.jar com.forgedb.benchmark.BenchmarkRunner 10000 50
 * </pre>
 * Arguments: [rowCount] [queryCount]
 * Defaults:  rowCount=10000, queryCount=100
 */
public final class BenchmarkRunner {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    private static final int    BUFFER_POOL_CAPACITY = 256;
    private static final String TABLE_NAME           = "bench";
    private static final String CSV_FILE             = "benchmark-results.csv";
    private static final String TXT_FILE             = "benchmark-results.txt";

    // Row counts for the full benchmark suite
    private static final long[] SUITE_ROW_COUNTS  = {1_000, 10_000, 100_000};
    private static final int    SUITE_QUERY_COUNT = 100;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        long rowCount    = 10_000;
        int  queryCount  = 100;
        boolean fullSuite = false;

        if (args.length >= 1) {
            if ("--suite".equals(args[0])) {
                fullSuite = true;
            } else {
                rowCount = Long.parseLong(args[0]);
            }
        }
        if (args.length >= 2) queryCount = Integer.parseInt(args[1]);

        List<BenchmarkResult> results = new ArrayList<>();

        if (fullSuite) {
            System.out.println("Running full benchmark suite...");
            for (long rc : SUITE_ROW_COUNTS) {
                System.out.println("\n=== " + rc + " rows ===");
                results.addAll(runSuite(rc, SUITE_QUERY_COUNT));
            }
        } else {
            results.addAll(runSuite(rowCount, queryCount));
        }

        // Print human-readable to stdout
        PrintWriter stdout = new PrintWriter(new OutputStreamWriter(System.out), true);
        printHuman(results, stdout);

        // Write CSV
        writeCsv(results, CSV_FILE);
        System.out.println("\nCSV results written to: " + CSV_FILE);

        // Write human-readable text file
        try (PrintWriter pw = new PrintWriter(new FileWriter(TXT_FILE))) {
            printHuman(results, pw);
        }
        System.out.println("Text results written to: " + TXT_FILE);
    }

    // -------------------------------------------------------------------------
    // Suite runner
    // -------------------------------------------------------------------------

    /**
     * Runs all benchmark scenarios for a given row count and returns results.
     * Temporary database files are cleaned up after each scenario.
     */
    public static List<BenchmarkResult> runSuite(long rowCount, int queryCount)
            throws Exception {
        List<BenchmarkResult> results = new ArrayList<>();

        // 1. INSERT throughput
        results.add(benchInsert(rowCount));
        System.out.printf("  insert:    %s%n", results.get(results.size()-1).toCsv());

        // 2. Sequential scan — equality lookup on id
        results.add(benchSeqScan(rowCount, queryCount));
        System.out.printf("  seq_scan:  %s%n", results.get(results.size()-1).toCsv());

        // 3. Index lookup — equality on id
        results.add(benchIndexLookup(rowCount, queryCount));
        System.out.printf("  idx_eq:    %s%n", results.get(results.size()-1).toCsv());

        // 4. Range scan — index range on id
        results.add(benchIndexRange(rowCount, queryCount));
        System.out.printf("  idx_range: %s%n", results.get(results.size()-1).toCsv());

        return results;
    }

    // -------------------------------------------------------------------------
    // Individual benchmarks
    // -------------------------------------------------------------------------

    /** Measures INSERT throughput: insert rowCount rows, return ns/row. */
    public static BenchmarkResult benchInsert(long rowCount) throws Exception {
        Path dbPath = Files.createTempFile("forgedb-bench-insert-", ".fdb");
        try {
            try (Executor exec = openExec(dbPath)) {
                exec.execute("CREATE TABLE " + TABLE_NAME +
                             " (id INT, name TEXT, value INT)");

                long start = System.nanoTime();
                for (long i = 0; i < rowCount; i++) {
                    exec.execute("INSERT INTO " + TABLE_NAME +
                                 " VALUES (" + i + ", 'name" + i + "', " + (i * 3) + ")");
                }
                long elapsed = System.nanoTime() - start;

                return BenchmarkResult.builder("insert", rowCount)
                    .operations(rowCount)
                    .elapsedNanos(elapsed)
                    .planType("N/A")
                    .extra("note", "measures heap insert + index not present")
                    .build();
            }
        } finally {
            Files.deleteIfExists(dbPath);
        }
    }

    /**
     * Measures sequential scan for equality lookups — no index.
     * Chooses QUERY_COUNT random ids uniformly from [0, rowCount).
     */
    public static BenchmarkResult benchSeqScan(long rowCount, int queryCount)
            throws Exception {
        Path dbPath = Files.createTempFile("forgedb-bench-seq-", ".fdb");
        try {
            try (Executor exec = openExec(dbPath)) {
                populateTable(exec, rowCount);
                // No index — planner must choose SEQ_SCAN

                int[]  targets = randomIds(rowCount, queryCount);
                long   rows    = 0;
                long   start   = System.nanoTime();
                for (int id : targets) {
                    QueryResult r = exec.execute(
                        "SELECT * FROM " + TABLE_NAME + " WHERE id = " + id);
                    rows += r.rowsAffected();
                }
                long elapsed = System.nanoTime() - start;

                // Verify the planner did choose SEQ_SCAN
                QueryPlan plan = exec.explain(
                    "SELECT * FROM " + TABLE_NAME + " WHERE id = 1");

                return BenchmarkResult.builder("seq_scan", rowCount)
                    .operations(queryCount)
                    .elapsedNanos(elapsed)
                    .rowsExamined(rowCount * (long) queryCount)  // approx: full scan each time
                    .planType(plan.type().name())
                    .extra("note", "full table scan per query, no index")
                    .build();
            }
        } finally {
            Files.deleteIfExists(dbPath);
        }
    }

    /**
     * Measures index equality lookup — with B+ tree on column 0.
     * Uses the same random target ids as {@link #benchSeqScan} for a fair comparison.
     */
    public static BenchmarkResult benchIndexLookup(long rowCount, int queryCount)
            throws Exception {
        Path dbPath = Files.createTempFile("forgedb-bench-idx-", ".fdb");
        try {
            try (Executor exec = openExec(dbPath)) {
                populateTable(exec, rowCount);
                exec.createIndex(TABLE_NAME);   // create B+ tree index on id

                int[]  targets = randomIds(rowCount, queryCount);
                long   rows    = 0;
                long   start   = System.nanoTime();
                for (int id : targets) {
                    QueryResult r = exec.execute(
                        "SELECT * FROM " + TABLE_NAME + " WHERE id = " + id);
                    rows += r.rowsAffected();
                }
                long elapsed = System.nanoTime() - start;

                QueryPlan plan = exec.explain(
                    "SELECT * FROM " + TABLE_NAME + " WHERE id = 1");

                return BenchmarkResult.builder("index_lookup_eq", rowCount)
                    .operations(queryCount)
                    .elapsedNanos(elapsed)
                    .rowsExamined(rows)  // actual matched rows
                    .planType(plan.type().name())
                    .extra("note", "B+ tree equality lookup")
                    .build();
            }
        } finally {
            Files.deleteIfExists(dbPath);
        }
    }

    /**
     * Measures index range scan: SELECT WHERE id >= X AND id < X+rangeSize.
     * Range size = rowCount / 100 (1% of the table).
     */
    public static BenchmarkResult benchIndexRange(long rowCount, int queryCount)
            throws Exception {
        Path dbPath = Files.createTempFile("forgedb-bench-range-", ".fdb");
        long rangeSize = Math.max(1, rowCount / 100);

        try {
            try (Executor exec = openExec(dbPath)) {
                populateTable(exec, rowCount);
                exec.createIndex(TABLE_NAME);

                int[]  starts  = randomIds(rowCount - rangeSize, queryCount);
                long   rows    = 0;
                long   start   = System.nanoTime();
                for (int lo : starts) {
                    long hi = lo + rangeSize;
                    QueryResult r = exec.execute(
                        "SELECT * FROM " + TABLE_NAME +
                        " WHERE id >= " + lo + " AND id < " + hi);
                    rows += r.rowsAffected();
                }
                long elapsed = System.nanoTime() - start;

                QueryPlan plan = exec.explain(
                    "SELECT * FROM " + TABLE_NAME + " WHERE id >= 0 AND id < 10");

                return BenchmarkResult.builder("index_lookup_range", rowCount)
                    .operations(queryCount)
                    .elapsedNanos(elapsed)
                    .rowsExamined(rows)
                    .planType(plan.type().name())
                    .extra("range_size", String.valueOf(rangeSize))
                    .extra("note", "B+ tree range scan (~1% of table per query)")
                    .build();
            }
        } finally {
            Files.deleteIfExists(dbPath);
        }
    }

    // -------------------------------------------------------------------------
    // Output helpers
    // -------------------------------------------------------------------------

    public static void printHuman(List<BenchmarkResult> results, PrintWriter out) {
        out.println("╔══════════════════════════════════════════════════════════════╗");
        out.println("║              ForgeDB Benchmark Results                       ║");
        out.println("╚══════════════════════════════════════════════════════════════╝");
        out.println();
        out.println("Methodology: wall-clock time via System.nanoTime(), no JVM warm-up.");
        out.println("Buffer pool: " + BUFFER_POOL_CAPACITY + " pages.");
        out.println("Each 'operation' is one SELECT query.");
        out.println();
        for (BenchmarkResult r : results) {
            out.println(r.toHumanString());
            out.println();
        }
        // Speedup table if we have both seq and index results at same rowCount
        printSpeedupComparison(results, out);
    }

    /** Prints a speedup comparison table when both SEQ_SCAN and INDEX_LOOKUP exist. */
    private static void printSpeedupComparison(List<BenchmarkResult> results,
                                                PrintWriter out) {
        // Group by rowCount
        Map<Long, Map<String, BenchmarkResult>> byCount = new LinkedHashMap<>();
        for (BenchmarkResult r : results) {
            byCount.computeIfAbsent(r.rowCount(), k -> new LinkedHashMap<>())
                   .put(r.scenario(), r);
        }
        boolean printed = false;
        for (var e : byCount.entrySet()) {
            var map = e.getValue();
            BenchmarkResult seq = map.get("seq_scan");
            BenchmarkResult idx = map.get("index_lookup_eq");
            if (seq != null && idx != null && idx.elapsedNanos() > 0) {
                if (!printed) {
                    out.println("Speedup (seq_scan.avgUs / index_lookup_eq.avgUs):");
                    out.printf("  %-10s  %-12s  %-12s  %s%n",
                               "rows", "seq µs/op", "idx µs/op", "speedup");
                    printed = true;
                }
                double speedup = seq.avgUs() / idx.avgUs();
                out.printf("  %-10d  %-12.1f  %-12.1f  %.1fx%n",
                           e.getKey(), seq.avgUs(), idx.avgUs(), speedup);
            }
        }
        if (printed) out.println();
    }

    public static void writeCsv(List<BenchmarkResult> results, String path)
            throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println(BenchmarkResult.csvHeader());
            for (BenchmarkResult r : results) pw.println(r.toCsv());
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static Executor openExec(Path dbPath) throws Exception {
        BufferPool bp = new BufferPool(
            new DiskManagerImpl(dbPath.toString()), BUFFER_POOL_CAPACITY);
        return new Executor(bp);
    }

    private static void populateTable(Executor exec, long rowCount)
            throws ExecutionException {
        exec.execute("CREATE TABLE " + TABLE_NAME +
                     " (id INT, name TEXT, value INT)");
        for (long i = 0; i < rowCount; i++) {
            exec.execute("INSERT INTO " + TABLE_NAME +
                         " VALUES (" + i + ", 'name" + i + "', " + (i * 3) + ")");
        }
    }

    /** Returns an array of {@code count} random ids in [0, max). Seeded for reproducibility. */
    private static int[] randomIds(long max, int count) {
        Random rng = new Random(42L);   // fixed seed for reproducibility
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = (int) (Math.abs(rng.nextLong()) % max);
        }
        return ids;
    }
}
