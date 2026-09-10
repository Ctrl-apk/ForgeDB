package com.forgedb.benchmark;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the timing and throughput results for one benchmark scenario.
 *
 * A BenchmarkResult is immutable after construction. Use {@link Builder}
 * to assemble it incrementally.
 *
 * <h2>Machine-readable output</h2>
 * {@link #toCsv()} produces a single CSV line suitable for appending to a
 * results file. {@link #csvHeader()} produces the matching header.
 *
 * <h2>Human-readable output</h2>
 * {@link #toHumanString()} produces a multi-line summary.
 *
 * <h2>Methodology note</h2>
 * All timings use {@link System#nanoTime()} which measures elapsed wall-clock
 * time on the JVM. Results include JVM warm-up effects for small row counts
 * and GC pauses for large ones. This is intentional — the goal is to show
 * realistic latency as a user of the system would observe it, not to show
 * best-case JIT-optimised throughput. Repeat the benchmark multiple times
 * and discard the first run if you need steady-state numbers.
 */
public final class BenchmarkResult {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final String scenario;          // e.g. "seq_scan" or "index_lookup"
    private final String tableSize;         // e.g. "1000"
    private final long   rowCount;          // exact number of rows in the table
    private final long   operationCount;    // number of queries issued
    private final long   elapsedNanos;      // total wall-clock time for all operations
    private final long   rowsExamined;      // rows read from heap (0 if unknown)
    private final String planType;          // "SEQ_SCAN" or "INDEX_LOOKUP"
    private final Map<String, String> extra;// additional metadata

    private BenchmarkResult(Builder b) {
        this.scenario        = b.scenario;
        this.tableSize       = b.tableSize;
        this.rowCount        = b.rowCount;
        this.operationCount  = b.operationCount;
        this.elapsedNanos    = b.elapsedNanos;
        this.rowsExamined    = b.rowsExamined;
        this.planType        = b.planType;
        this.extra           = Map.copyOf(b.extra);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String scenario()        { return scenario; }
    public String tableSize()       { return tableSize; }
    public long   rowCount()        { return rowCount; }
    public long   operationCount()  { return operationCount; }
    public long   elapsedNanos()    { return elapsedNanos; }
    public long   rowsExamined()    { return rowsExamined; }
    public String planType()        { return planType; }

    /** Elapsed time in milliseconds (rounded). */
    public long elapsedMs() { return elapsedNanos / 1_000_000; }

    /** Average time per operation in microseconds. */
    public double avgUs() {
        if (operationCount == 0) return 0.0;
        return (elapsedNanos / 1_000.0) / operationCount;
    }

    /** Operations per second. */
    public double opsPerSec() {
        if (elapsedNanos == 0) return 0.0;
        return operationCount * 1_000_000_000.0 / elapsedNanos;
    }

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    /**
     * Returns the CSV header matching {@link #toCsv()}.
     */
    public static String csvHeader() {
        return "scenario,table_size,row_count,operation_count," +
               "elapsed_ms,avg_us,ops_per_sec,rows_examined,plan_type";
    }

    /**
     * Returns a single CSV data row (no trailing newline).
     */
    public String toCsv() {
        return String.format("%s,%s,%d,%d,%d,%.2f,%.1f,%d,%s",
            scenario, tableSize, rowCount, operationCount,
            elapsedMs(), avgUs(), opsPerSec(), rowsExamined, planType);
    }

    /**
     * Returns a multi-line human-readable summary.
     *
     * Example:
     * <pre>
     * ┌─ Benchmark: seq_scan (rows=1000)
     * │  Plan:       SEQ_SCAN
     * │  Operations: 100
     * │  Total time: 45 ms
     * │  Avg/op:     450.12 µs
     * │  Ops/sec:    2222.2
     * │  Rows exam:  100000
     * └──────────────────────────────────
     * </pre>
     */
    public String toHumanString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("┌─ Benchmark: %s (rows=%d)\n", scenario, rowCount));
        sb.append(String.format("│  Plan:       %s\n", planType));
        sb.append(String.format("│  Operations: %d\n", operationCount));
        sb.append(String.format("│  Total time: %d ms\n", elapsedMs()));
        sb.append(String.format("│  Avg/op:     %.2f µs\n", avgUs()));
        sb.append(String.format("│  Ops/sec:    %.1f\n", opsPerSec()));
        if (rowsExamined > 0) {
            sb.append(String.format("│  Rows exam:  %d\n", rowsExamined));
        }
        for (var e : extra.entrySet()) {
            sb.append(String.format("│  %-12s%s\n", e.getKey() + ":", e.getValue()));
        }
        sb.append("└──────────────────────────────────");
        return sb.toString();
    }

    @Override
    public String toString() { return toHumanString(); }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder(String scenario, long rowCount) {
        return new Builder(scenario, rowCount);
    }

    public static final class Builder {
        private final String scenario;
        private final long   rowCount;
        private String tableSize    = "";
        private long   operationCount = 0;
        private long   elapsedNanos  = 0;
        private long   rowsExamined  = 0;
        private String planType      = "UNKNOWN";
        private final Map<String, String> extra = new LinkedHashMap<>();

        private Builder(String scenario, long rowCount) {
            this.scenario  = scenario;
            this.rowCount  = rowCount;
            this.tableSize = String.valueOf(rowCount);
        }

        public Builder tableSize(String s)       { this.tableSize = s;       return this; }
        public Builder operations(long n)        { this.operationCount = n;  return this; }
        public Builder elapsedNanos(long ns)     { this.elapsedNanos = ns;   return this; }
        public Builder rowsExamined(long r)      { this.rowsExamined = r;    return this; }
        public Builder planType(String p)        { this.planType = p;        return this; }
        public Builder extra(String k, String v) { extra.put(k, v);          return this; }

        public BenchmarkResult build() { return new BenchmarkResult(this); }
    }
}
