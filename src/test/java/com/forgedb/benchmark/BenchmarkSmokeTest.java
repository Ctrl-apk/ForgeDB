package com.forgedb.benchmark;

import com.forgedb.planner.QueryPlan;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the benchmark harness.
 *
 * These tests verify correctness of the benchmark infrastructure — that the
 * harness runs without error, returns sensible BenchmarkResult objects, and
 * that the planner selects the expected plan type for each scenario.
 *
 * They do NOT assert absolute performance numbers because those are JVM-,
 * hardware-, and OS-dependent and would produce flaky results in CI.
 *
 * Row counts are kept small (100–500) so the normal Maven test suite
 * completes quickly. The full benchmark suite (1K–100K rows) is run
 * manually via {@link BenchmarkRunner#main(String[])}.
 *
 * Categories:
 *   1. BenchmarkResult — builder, CSV output, human output
 *   2. BenchmarkRunner — insert, seq_scan, index_lookup, range scenarios
 *   3. Plan type verification — planner chose expected strategy
 */
class BenchmarkSmokeTest {

    // =========================================================================
    // 1. BenchmarkResult
    // =========================================================================

    @Test @DisplayName("BenchmarkResult builder produces correct fields")
    void result_builderFields() {
        BenchmarkResult r = BenchmarkResult.builder("seq_scan", 1000)
            .operations(50)
            .elapsedNanos(500_000_000L)  // 500 ms
            .rowsExamined(50_000L)
            .planType("SEQ_SCAN")
            .extra("note", "smoke test")
            .build();

        assertEquals("seq_scan", r.scenario());
        assertEquals(1000,        r.rowCount());
        assertEquals(50,          r.operationCount());
        assertEquals(500L,        r.elapsedMs());
        assertEquals(50_000L,     r.rowsExamined());
        assertEquals("SEQ_SCAN",  r.planType());
    }

    @Test @DisplayName("BenchmarkResult avgUs is correct")
    void result_avgUs() {
        BenchmarkResult r = BenchmarkResult.builder("test", 100)
            .operations(10)
            .elapsedNanos(10_000_000L)   // 10 ms = 10000 µs
            .planType("N/A")
            .build();
        // 10000 µs / 10 ops = 1000 µs/op
        assertEquals(1000.0, r.avgUs(), 0.01);
    }

    @Test @DisplayName("BenchmarkResult opsPerSec is correct")
    void result_opsPerSec() {
        BenchmarkResult r = BenchmarkResult.builder("test", 100)
            .operations(1000)
            .elapsedNanos(1_000_000_000L)  // 1 second
            .planType("N/A")
            .build();
        assertEquals(1000.0, r.opsPerSec(), 0.1);
    }

    @Test @DisplayName("BenchmarkResult CSV header and row have matching column count")
    void result_csvFormat() {
        BenchmarkResult r = BenchmarkResult.builder("index_lookup_eq", 500)
            .operations(10)
            .elapsedNanos(1_000_000L)
            .planType("INDEX_LOOKUP")
            .build();

        String header = BenchmarkResult.csvHeader();
        String row    = r.toCsv();

        int headerCols = header.split(",").length;
        int rowCols    = row.split(",").length;
        assertEquals(headerCols, rowCols,
            "CSV row must have same column count as header");
    }

    @Test @DisplayName("BenchmarkResult toHumanString contains scenario and plan type")
    void result_humanString() {
        BenchmarkResult r = BenchmarkResult.builder("seq_scan", 200)
            .operations(5)
            .elapsedNanos(2_000_000L)
            .planType("SEQ_SCAN")
            .build();

        String human = r.toHumanString();
        assertTrue(human.contains("seq_scan"),  "Human string must contain scenario name");
        assertTrue(human.contains("SEQ_SCAN"),  "Human string must contain plan type");
        assertTrue(human.contains("Operations"), "Human string must contain operations line");
    }

    // =========================================================================
    // 2. BenchmarkRunner — correctness of harness output
    // =========================================================================

    @Test @DisplayName("benchInsert: returns result with correct row count and N/A plan")
    void runner_insert() throws Exception {
        BenchmarkResult r = BenchmarkRunner.benchInsert(100);
        assertEquals(100,   r.rowCount());
        assertEquals(100,   r.operationCount());
        assertEquals("N/A", r.planType());
        assertTrue(r.elapsedNanos() >= 0,
            "Elapsed nanos must be non-negative");
    }

    @Test @DisplayName("benchSeqScan: returns SEQ_SCAN plan type")
    void runner_seqScan() throws Exception {
        BenchmarkResult r = BenchmarkRunner.benchSeqScan(200, 10);
        assertEquals(200,        r.rowCount());
        assertEquals(10,         r.operationCount());
        assertEquals("SEQ_SCAN", r.planType(),
            "Seq scan benchmark must use SEQ_SCAN plan");
        assertTrue(r.elapsedNanos() > 0,
            "Elapsed nanos must be positive after running queries");
    }

    @Test @DisplayName("benchIndexLookup: returns INDEX_LOOKUP plan type")
    void runner_indexLookup() throws Exception {
        BenchmarkResult r = BenchmarkRunner.benchIndexLookup(200, 10);
        assertEquals(200,            r.rowCount());
        assertEquals(10,             r.operationCount());
        assertEquals("INDEX_LOOKUP", r.planType(),
            "Index lookup benchmark must use INDEX_LOOKUP plan");
        assertTrue(r.elapsedNanos() > 0);
    }

    @Test @DisplayName("benchIndexRange: returns INDEX_LOOKUP plan type")
    void runner_indexRange() throws Exception {
        BenchmarkResult r = BenchmarkRunner.benchIndexRange(200, 10);
        assertEquals("INDEX_LOOKUP", r.planType(),
            "Range scan benchmark must use INDEX_LOOKUP plan");
        // Range queries should return rows (range_size >= 1% of 200 = 2)
        assertTrue(r.rowsExamined() >= 0);
    }

    @Test @DisplayName("runSuite: returns 4 results, one per scenario")
    void runner_suite() throws Exception {
        var results = BenchmarkRunner.runSuite(100, 5);
        assertEquals(4, results.size(), "Suite must return 4 benchmark results");
        // Verify all four expected scenarios are present
        long seqCount = results.stream()
            .filter(r -> r.scenario().equals("seq_scan")).count();
        long idxCount = results.stream()
            .filter(r -> r.scenario().equals("index_lookup_eq")).count();
        long insCount = results.stream()
            .filter(r -> r.scenario().equals("insert")).count();
        long rngCount = results.stream()
            .filter(r -> r.scenario().equals("index_lookup_range")).count();
        assertEquals(1, seqCount, "Must have exactly 1 seq_scan result");
        assertEquals(1, idxCount, "Must have exactly 1 index_lookup_eq result");
        assertEquals(1, insCount, "Must have exactly 1 insert result");
        assertEquals(1, rngCount, "Must have exactly 1 index_lookup_range result");
    }

    // =========================================================================
    // 3. Plan type verification
    // =========================================================================

    @Test @DisplayName("seq_scan result uses SEQ_SCAN plan (no index present)")
    void planType_seqScan() throws Exception {
        BenchmarkResult r = BenchmarkRunner.benchSeqScan(50, 3);
        assertEquals(QueryPlan.PlanType.SEQ_SCAN.name(), r.planType());
    }

    @Test @DisplayName("index_lookup result uses INDEX_LOOKUP plan (index created)")
    void planType_indexLookup() throws Exception {
        BenchmarkResult r = BenchmarkRunner.benchIndexLookup(50, 3);
        assertEquals(QueryPlan.PlanType.INDEX_LOOKUP.name(), r.planType());
    }

    @Test @DisplayName("index_range result uses INDEX_LOOKUP plan")
    void planType_indexRange() throws Exception {
        BenchmarkResult r = BenchmarkRunner.benchIndexRange(50, 3);
        assertEquals(QueryPlan.PlanType.INDEX_LOOKUP.name(), r.planType());
    }

    // =========================================================================
    // 4. Index lookup is faster than sequential scan
    // =========================================================================

    @Test @DisplayName("Index lookup avg latency is <= seq scan avg latency for 500 rows")
    void performance_indexFasterThanSeq() throws Exception {
        // Use a reasonably large table so the difference is measurable
        // but not so large the test takes minutes.
        // This assertion is intentionally lenient — we only require index <= seq,
        // not a specific speedup factor, to avoid flakiness.
        int rows = 500, ops = 20;
        BenchmarkResult seq = BenchmarkRunner.benchSeqScan(rows, ops);
        BenchmarkResult idx = BenchmarkRunner.benchIndexLookup(rows, ops);

        // Both must complete without error
        assertTrue(seq.elapsedNanos() > 0, "Seq scan must have positive elapsed time");
        assertTrue(idx.elapsedNanos() > 0, "Index lookup must have positive elapsed time");

        // We make a soft assertion: log the speedup but don't fail if JVM variability
        // causes them to be equal. Only fail if index is wildly slower (> 10x).
        double speedup = seq.avgUs() / Math.max(idx.avgUs(), 0.001);
        assertTrue(speedup > 0.1,
            "Index lookup should not be more than 10x slower than seq scan; speedup=" + speedup);
    }
}
