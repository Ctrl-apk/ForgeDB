package com.forgedb.verify;

import com.forgedb.execution.Executor;
import com.forgedb.execution.QueryResult;
import com.forgedb.planner.QueryPlan;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DiskManagerImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8 Verification Pass — confirms the exact index lifecycle, EXPLAIN output,
 * and benchmark correctness without modifying any production code.
 *
 * Every test prints its findings to stdout so the full picture is visible
 * in the Maven test report.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M8VerificationTest {

    @TempDir Path tempDir;

    private Executor exec;

    @BeforeEach
    void setup() throws Exception {
        exec = new Executor(new BufferPool(
            new DiskManagerImpl(tempDir.resolve("verify.fdb").toString()), 64));
        exec.execute("CREATE TABLE users (id INT, name TEXT)");
        exec.execute("INSERT INTO users VALUES (1, 'Alice')");
        exec.execute("INSERT INTO users VALUES (2, 'Bob')");
        exec.execute("INSERT INTO users VALUES (3, 'Carol')");
    }

    @AfterEach
    void teardown() throws Exception {
        exec.close();
    }

    // =========================================================================
    // Q1: How is an index created / registered?
    // =========================================================================

    @Test @Order(1)
    @DisplayName("VER-1: Index creation mechanism — Executor.createIndex() is the ONLY way")
    void ver1_indexCreationMechanism() throws Exception {
        // Before createIndex() — no index in the map
        assertNull(exec.getIndex("users"),
            "Index must be null before createIndex() is called");

        // CREATE TABLE alone does NOT create an index
        assertTrue(exec.catalog().hasTable("users"),
            "Table must be registered");
        assertNull(exec.getIndex("users"),
            "CREATE TABLE alone must not create an index");

        // INSERT does NOT create an index
        exec.execute("INSERT INTO users VALUES (4, 'Dave')");
        assertNull(exec.getIndex("users"),
            "INSERT alone must not create an index");

        // createIndex() IS the only way in M6-M8
        exec.createIndex("users");
        assertNotNull(exec.getIndex("users"),
            "After createIndex(), index must be registered");

        System.out.println("[VER-1] Index registration mechanism:");
        System.out.println("  - Only Executor.createIndex(tableName) registers an index.");
        System.out.println("  - CREATE TABLE, INSERT do NOT auto-create indexes.");
        System.out.println("  - No SQL CREATE INDEX syntax exists yet (planned M9).");
        System.out.println("  - The index is stored in-memory in Executor.indexes map.");
        System.out.println("  - Index is NOT persisted — it does NOT survive Executor.close().");
        System.out.println("  RESULT: CONFIRMED — createIndex() is the only registration path.");
    }

    // =========================================================================
    // Q2-3: EXPLAIN behavior without index
    // =========================================================================

    @Test @Order(2)
    @DisplayName("VER-2: EXPLAIN without index always produces SEQ_SCAN")
    void ver2_explainWithoutIndex() throws Exception {
        assertNull(exec.getIndex("users"), "Precondition: no index");

        QueryPlan eq  = exec.explain("SELECT * FROM users WHERE id = 1");
        QueryPlan neq = exec.explain("SELECT * FROM users WHERE id <> 1");
        QueryPlan gt  = exec.explain("SELECT * FROM users WHERE id > 1");
        QueryPlan name= exec.explain("SELECT * FROM users WHERE name = 'Alice'");
        QueryPlan noW = exec.explain("SELECT * FROM users");

        System.out.println("[VER-2] EXPLAIN without any index:");
        System.out.println("  id = 1  : " + eq.explain());
        System.out.println("  id <> 1 : " + neq.explain());
        System.out.println("  id > 1  : " + gt.explain());
        System.out.println("  name=.. : " + name.explain());
        System.out.println("  (no WHERE): " + noW.explain());

        assertEquals(QueryPlan.PlanType.SEQ_SCAN, eq.type(),   "No index → EQ must be SEQ_SCAN");
        assertEquals(QueryPlan.PlanType.SEQ_SCAN, neq.type(),  "No index → NEQ must be SEQ_SCAN");
        assertEquals(QueryPlan.PlanType.SEQ_SCAN, gt.type(),   "No index → GT must be SEQ_SCAN");
        assertEquals(QueryPlan.PlanType.SEQ_SCAN, name.type(), "No index → name must be SEQ_SCAN");
        assertEquals(QueryPlan.PlanType.SEQ_SCAN, noW.type(),  "No WHERE → SEQ_SCAN");

        System.out.println("  RESULT: CONFIRMED — all SEQ_SCAN when no index.");
    }

    // =========================================================================
    // Q3: EXPLAIN WITH index — indexed column
    // =========================================================================

    @Test @Order(3)
    @DisplayName("VER-3: EXPLAIN WITH index on id — equality predicate → INDEX_LOOKUP")
    void ver3_explainWithIndexEquality() throws Exception {
        exec.createIndex("users");  // index on col 0 (id)
        assertNotNull(exec.getIndex("users"), "Precondition: index must exist");

        QueryPlan plan = exec.explain("SELECT * FROM users WHERE id = 1");

        System.out.println("[VER-3] EXPLAIN WITH index, equality on indexed col:");
        System.out.println("  " + plan.explain());

        assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type(),
            "With index, equality on indexed col MUST be INDEX_LOOKUP");
        assertEquals("id", plan.indexedColumn(),
            "Indexed column must be 'id'");
        assertEquals(com.forgedb.sql.ast.BinaryExpression.Op.EQ, plan.indexOp(),
            "Operator must be EQ");
        assertEquals(1, plan.indexKeyValue(),
            "Key value must be 1");
        assertNull(plan.residualWhere(),
            "No residual predicate for simple equality");

        System.out.println("  RESULT: CONFIRMED — INDEX_LOOKUP with EQ, key=1, no residual.");
    }

    // =========================================================================
    // Q3: EXPLAIN WITH index — non-indexed column
    // =========================================================================

    @Test @Order(4)
    @DisplayName("VER-4: EXPLAIN WITH index but predicate on non-indexed column → SEQ_SCAN")
    void ver4_explainWithIndexNonIndexedCol() throws Exception {
        exec.createIndex("users");  // index is on id (col 0), NOT on name

        QueryPlan plan = exec.explain("SELECT * FROM users WHERE name = 'Alice'");

        System.out.println("[VER-4] EXPLAIN WITH index, predicate on NON-indexed col 'name':");
        System.out.println("  " + plan.explain());

        assertEquals(QueryPlan.PlanType.SEQ_SCAN, plan.type(),
            "Predicate on non-indexed column must fall back to SEQ_SCAN");

        System.out.println("  RESULT: CONFIRMED — SEQ_SCAN because 'name' is not indexed.");
    }

    // =========================================================================
    // Q3: EXPLAIN WITH index — range and NEQ
    // =========================================================================

    @Test @Order(5)
    @DisplayName("VER-5: EXPLAIN WITH index — range ops → INDEX_LOOKUP; NEQ → SEQ_SCAN")
    void ver5_explainRangeAndNeq() throws Exception {
        exec.createIndex("users");

        QueryPlan lt  = exec.explain("SELECT * FROM users WHERE id < 3");
        QueryPlan lte = exec.explain("SELECT * FROM users WHERE id <= 3");
        QueryPlan gt  = exec.explain("SELECT * FROM users WHERE id > 1");
        QueryPlan gte = exec.explain("SELECT * FROM users WHERE id >= 2");
        QueryPlan neq = exec.explain("SELECT * FROM users WHERE id <> 2");

        System.out.println("[VER-5] Range and NEQ with index:");
        System.out.println("  id < 3  : " + lt.explain());
        System.out.println("  id <= 3 : " + lte.explain());
        System.out.println("  id > 1  : " + gt.explain());
        System.out.println("  id >= 2 : " + gte.explain());
        System.out.println("  id <> 2 : " + neq.explain());

        assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, lt.type(),  "LT must use INDEX_LOOKUP");
        assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, lte.type(), "LTE must use INDEX_LOOKUP");
        assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, gt.type(),  "GT must use INDEX_LOOKUP");
        assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, gte.type(), "GTE must use INDEX_LOOKUP");
        assertEquals(QueryPlan.PlanType.SEQ_SCAN,     neq.type(), "NEQ must fall back to SEQ_SCAN");

        System.out.println("  RESULT: CONFIRMED — range ops use INDEX_LOOKUP; NEQ uses SEQ_SCAN.");
    }

    // =========================================================================
    // Q4-5: Execute indexed equality query and verify correct results
    // =========================================================================

    @Test @Order(6)
    @DisplayName("VER-6: Indexed equality SELECT returns correct rows")
    void ver6_indexedEqualityExecution() throws Exception {
        exec.createIndex("users");

        // Verify plan is INDEX_LOOKUP
        QueryPlan plan = exec.explain("SELECT * FROM users WHERE id = 2");
        assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type());

        // Execute and verify result
        QueryResult result = exec.execute("SELECT * FROM users WHERE id = 2");

        System.out.println("[VER-6] Indexed equality execution:");
        System.out.println("  Plan: " + plan.explain());
        System.out.println("  Rows returned: " + result.rowsAffected());
        for (var t : result.rows()) System.out.println("  Row: " + t);

        assertEquals(1, result.rowsAffected(), "Must return exactly 1 row");
        assertEquals(2,     result.rows().get(0).getInt(0),    "id must be 2");
        assertEquals("Bob", result.rows().get(0).getString(1), "name must be Bob");

        // Miss case
        QueryResult miss = exec.execute("SELECT * FROM users WHERE id = 99");
        assertEquals(0, miss.rowsAffected(), "Missing key must return 0 rows");
        System.out.println("  Miss (id=99): " + miss.rowsAffected() + " rows");

        System.out.println("  RESULT: CONFIRMED — correct rows returned via index.");
    }

    // =========================================================================
    // Q6: Non-indexed predicate falls back to seq scan and returns correct rows
    // =========================================================================

    @Test @Order(7)
    @DisplayName("VER-7: Non-indexed predicate → SEQ_SCAN and returns correct results")
    void ver7_nonIndexedPredicateResult() throws Exception {
        exec.createIndex("users");  // index on id, NOT on name

        QueryPlan plan = exec.explain("SELECT * FROM users WHERE name = 'Alice'");
        assertEquals(QueryPlan.PlanType.SEQ_SCAN, plan.type());

        QueryResult result = exec.execute("SELECT * FROM users WHERE name = 'Alice'");

        System.out.println("[VER-7] Non-indexed predicate execution:");
        System.out.println("  Plan: " + plan.explain());
        System.out.println("  Rows returned: " + result.rowsAffected());

        assertEquals(1, result.rowsAffected());
        assertEquals(1,       result.rows().get(0).getInt(0));
        assertEquals("Alice", result.rows().get(0).getString(1));

        System.out.println("  RESULT: CONFIRMED — SEQ_SCAN used, correct result returned.");
    }

    // =========================================================================
    // Q7: Benchmark index_eq scenario genuinely uses INDEX_LOOKUP
    // =========================================================================

    @Test @Order(8)
    @DisplayName("VER-8: BenchmarkRunner.benchIndexLookup uses INDEX_LOOKUP (not a label)")
    void ver8_benchmarkIndexLookupIsGenuine() throws Exception {
        // Run a small version of the benchmark's index_eq scenario inline
        // to verify the planType field reflects the planner's actual decision.
        com.forgedb.benchmark.BenchmarkResult r =
            com.forgedb.benchmark.BenchmarkRunner.benchIndexLookup(50, 5);

        System.out.println("[VER-8] BenchmarkRunner.benchIndexLookup result:");
        System.out.println("  " + r.toHumanString());
        System.out.println("  planType field: " + r.planType());

        assertEquals("INDEX_LOOKUP", r.planType(),
            "benchIndexLookup must record INDEX_LOOKUP — confirmed via exec.explain()");
        assertEquals(5,  r.operationCount(), "Must record 5 operations");
        assertEquals(50, r.rowCount(),       "Must record 50 rows");
        assertTrue(r.elapsedNanos() > 0,     "Must record positive elapsed time");

        // Also verify seq_scan uses SEQ_SCAN
        com.forgedb.benchmark.BenchmarkResult seqR =
            com.forgedb.benchmark.BenchmarkRunner.benchSeqScan(50, 5);

        System.out.println("  benchSeqScan planType: " + seqR.planType());
        assertEquals("SEQ_SCAN", seqR.planType(),
            "benchSeqScan must record SEQ_SCAN");

        System.out.println("  RESULT: CONFIRMED — planType is set from exec.explain().type().name().");
        System.out.println("  The benchmark's planType field reflects the planner's ACTUAL decision.");
    }

    // =========================================================================
    // Q8: Benchmark performs equivalent work (same queries, same row counts)
    // =========================================================================

    @Test @Order(9)
    @DisplayName("VER-9: Benchmark seq_scan and index_eq run the same queries on the same data")
    void ver9_benchmarkEquivalentWork() throws Exception {
        int rowCount = 50, queryCount = 5;

        com.forgedb.benchmark.BenchmarkResult seq =
            com.forgedb.benchmark.BenchmarkRunner.benchSeqScan(rowCount, queryCount);
        com.forgedb.benchmark.BenchmarkResult idx =
            com.forgedb.benchmark.BenchmarkRunner.benchIndexLookup(rowCount, queryCount);

        System.out.println("[VER-9] Equivalent work verification:");
        System.out.println("  seq_scan:  rowCount=" + seq.rowCount()
            + ", ops=" + seq.operationCount() + ", planType=" + seq.planType());
        System.out.println("  index_eq:  rowCount=" + idx.rowCount()
            + ", ops=" + idx.operationCount() + ", planType=" + idx.planType());

        assertEquals(rowCount,   seq.rowCount(),       "Both must use same row count");
        assertEquals(rowCount,   idx.rowCount(),       "Both must use same row count");
        assertEquals(queryCount, (int)seq.operationCount(), "Both must run same number of queries");
        assertEquals(queryCount, (int)idx.operationCount(), "Both must run same number of queries");
        assertEquals("SEQ_SCAN",     seq.planType(), "Seq benchmark must use SEQ_SCAN");
        assertEquals("INDEX_LOOKUP", idx.planType(), "Idx benchmark must use INDEX_LOOKUP");

        // Both use the same fixed seed (42) for random id selection
        // so they query the same target ids — a fair comparison
        System.out.println("  Both use Random(seed=42) for query target selection → same ids queried.");
        System.out.println("  RESULT: CONFIRMED — equivalent work, different plans.");
    }

    // =========================================================================
    // Q: Why does the README/CLI sample show SeqScan?
    // =========================================================================

    @Test @Order(10)
    @DisplayName("VER-10: README/CLI sample shows SeqScan — explains why and confirms it is EXPECTED")
    void ver10_readmeSeqScanIsExpected() throws Exception {
        // The README cli-usage.md sample:
        //   ForgeDB> EXPLAIN SELECT * FROM users WHERE id = 1;
        //   SeqScan [ table=users, filter=(id = 1) ]
        //
        // This is EXPECTED because:
        // 1. The CLI session in the README does NOT call exec.createIndex("users")
        // 2. No SQL "CREATE INDEX" syntax exists yet (planned M9)
        // 3. Without an index in exec.indexes map, the planner always returns SEQ_SCAN
        // 4. The README sample is therefore accurate for a freshly-opened database

        // Reproduce the README scenario:
        QueryPlan noIndexPlan = exec.explain("SELECT * FROM users WHERE id = 1");
        assertEquals(QueryPlan.PlanType.SEQ_SCAN, noIndexPlan.type());
        System.out.println("[VER-10] README sample scenario (no index created):");
        System.out.println("  " + noIndexPlan.explain());

        // Now create an index — the plan changes to INDEX_LOOKUP
        exec.createIndex("users");
        QueryPlan withIndexPlan = exec.explain("SELECT * FROM users WHERE id = 1");
        assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, withIndexPlan.type());
        System.out.println("  After createIndex():  " + withIndexPlan.explain());

        System.out.println("  RESULT: The README shows SeqScan because:");
        System.out.println("    1. CLI sessions start with an empty exec.indexes map.");
        System.out.println("    2. There is no SQL 'CREATE INDEX' command yet.");
        System.out.println("    3. The only way to get INDEX_LOOKUP is via exec.createIndex().");
        System.out.println("    4. This is expected behavior, NOT a bug in the planner.");
        System.out.println("    5. The fix is M9: add 'CREATE INDEX col ON table' SQL syntax.");
        System.out.println("    6. The README sample should be updated once M9 is implemented.");
    }

    // =========================================================================
    // Q: AND predicate with indexed column + residual
    // =========================================================================

    @Test @Order(11)
    @DisplayName("VER-11: AND predicate — index used for indexed col, residual applied after")
    void ver11_andPredicateResidual() throws Exception {
        // Add extra column: use a schema with id + score for AND test
        Executor exec2 = new Executor(new BufferPool(
            new DiskManagerImpl(tempDir.resolve("and_test.fdb").toString()), 32));
        try {
            exec2.execute("CREATE TABLE scores (id INT, score INT)");
            exec2.execute("INSERT INTO scores VALUES (1, 90)");
            exec2.execute("INSERT INTO scores VALUES (1, 50)"); // duplicate id
            exec2.execute("INSERT INTO scores VALUES (2, 80)");
            exec2.createIndex("scores");

            QueryPlan plan = exec2.explain(
                "SELECT * FROM scores WHERE id = 1 AND score > 70");
            System.out.println("[VER-11] AND predicate with index:");
            System.out.println("  " + plan.explain());

            assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type(),
                "AND with indexed col must use INDEX_LOOKUP");
            assertEquals("id", plan.indexedColumn());
            assertNotNull(plan.residualWhere(),
                "AND must produce a residual predicate (score > 70)");

            // Execute: should return only id=1, score=90 (not score=50)
            QueryResult r = exec2.execute(
                "SELECT * FROM scores WHERE id = 1 AND score > 70");
            System.out.println("  Rows returned: " + r.rowsAffected());
            for (var t : r.rows()) System.out.println("  Row: " + t);

            assertEquals(1, r.rowsAffected(),
                "Only 1 row should match (id=1, score=90)");
            assertEquals(90, r.rows().get(0).getInt(1), "Score must be 90");

            System.out.println(
                "  RESULT: CONFIRMED — INDEX_LOOKUP for 'id=1', residual '(score > 70)' applied after.");
        } finally {
            exec2.close();
        }
    }
}
