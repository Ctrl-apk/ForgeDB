package com.forgedb.planner;

import com.forgedb.catalog.Column;
import com.forgedb.catalog.DataType;
import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;
import com.forgedb.execution.ExecutionException;
import com.forgedb.execution.Executor;
import com.forgedb.execution.QueryResult;
import com.forgedb.index.BTree;
import com.forgedb.index.BTreeKey;
import com.forgedb.sql.Lexer;
import com.forgedb.sql.Parser;
import com.forgedb.sql.ast.*;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DiskManagerImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the Milestone 7 Query Planner and index-accelerated execution.
 *
 * Categories:
 *  1.  QueryPlanner — plan selection (index vs seq scan)
 *  2.  EXPLAIN output
 *  3.  Indexed equality SELECT
 *  4.  Indexed range SELECT (LT, LTE, GT, GTE)
 *  5.  Non-indexed column → seq scan
 *  6.  AND conjunction: index + residual
 *  7.  No index → seq scan
 *  8.  Indexed UPDATE
 *  9.  Indexed DELETE
 * 10.  Multiple matching RecordIds (duplicates)
 * 11.  Large table — index lookup vs full scan
 * 12.  Predicate flip (literal op col)
 * 13.  NEQ → seq scan
 * 14.  B+ Tree persistence after reopen
 * 15.  Pre-existing M6 tests still pass with planner wired in
 */
class QueryPlannerTest {

    @TempDir
    Path tempDir;

    private String dbPath(String name) {
        return tempDir.resolve(name).toString();
    }

    private Executor newExec(String file) throws Exception {
        return new Executor(new BufferPool(new DiskManagerImpl(dbPath(file)), 64));
    }

    private QueryResult ok(Executor e, String sql) throws Exception {
        return e.execute(sql);
    }

    // =========================================================================
    // 1. QueryPlanner — plan selection
    // =========================================================================

    @Test @DisplayName("Planner: no index → SEQ_SCAN chosen")
    void planner_noIndexSeqScan() throws Exception {
        try (Executor exec = newExec("p_noidx.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            // No index created
            QueryPlan plan = exec.explain("SELECT * FROM t WHERE id = 5");
            assertEquals(QueryPlan.PlanType.SEQ_SCAN, plan.type());
        }
    }

    @Test @DisplayName("Planner: index on col 0, equality predicate → INDEX_LOOKUP")
    void planner_indexLookupChosen() throws Exception {
        try (Executor exec = newExec("p_idx.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            exec.createIndex("t");
            QueryPlan plan = exec.explain("SELECT * FROM t WHERE id = 5");
            assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type());
            assertEquals("id", plan.indexedColumn());
            assertEquals(BinaryExpression.Op.EQ, plan.indexOp());
            assertEquals(5, plan.indexKeyValue());
        }
    }

    @Test @DisplayName("Planner: predicate on non-indexed column → SEQ_SCAN")
    void planner_nonIndexedColumn() throws Exception {
        try (Executor exec = newExec("p_noncol.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            exec.createIndex("t");   // index on col 0 = id
            // Predicate on name (col 1) — cannot use index
            QueryPlan plan = exec.explain("SELECT * FROM t WHERE name = 'Alice'");
            assertEquals(QueryPlan.PlanType.SEQ_SCAN, plan.type());
        }
    }

    @Test @DisplayName("Planner: range predicate on indexed column → INDEX_LOOKUP")
    void planner_rangeIndexLookup() throws Exception {
        try (Executor exec = newExec("p_range.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            exec.createIndex("t");
            for (var op : new String[]{"<", "<=", ">", ">="}) {
                QueryPlan plan = exec.explain("SELECT * FROM t WHERE id " + op + " 10");
                assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type(),
                    "Expected INDEX_LOOKUP for op: " + op);
            }
        }
    }

    @Test @DisplayName("Planner: NEQ predicate → SEQ_SCAN (not worth using index)")
    void planner_neqSeqScan() throws Exception {
        try (Executor exec = newExec("p_neq.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            QueryPlan plan = exec.explain("SELECT * FROM t WHERE id <> 5");
            assertEquals(QueryPlan.PlanType.SEQ_SCAN, plan.type(),
                "NEQ should not use the index");
        }
    }

    @Test @DisplayName("Planner: AND with indexed col on left → INDEX_LOOKUP with residual")
    void planner_andResidualLeft() throws Exception {
        try (Executor exec = newExec("p_and.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, age INT)");
            exec.createIndex("t");
            QueryPlan plan = exec.explain("SELECT * FROM t WHERE id = 3 AND age > 20");
            assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type());
            assertEquals("id", plan.indexedColumn());
            assertNotNull(plan.residualWhere(), "Should have residual for age > 20");
        }
    }

    @Test @DisplayName("Planner: AND with indexed col on right → INDEX_LOOKUP with residual")
    void planner_andResidualRight() throws Exception {
        try (Executor exec = newExec("p_and_r.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, age INT)");
            exec.createIndex("t");
            QueryPlan plan = exec.explain("SELECT * FROM t WHERE age > 20 AND id = 3");
            assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type());
            assertEquals("id", plan.indexedColumn());
        }
    }

    @Test @DisplayName("Planner: OR predicate → SEQ_SCAN (OR is not indexable)")
    void planner_orSeqScan() throws Exception {
        try (Executor exec = newExec("p_or.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            QueryPlan plan = exec.explain("SELECT * FROM t WHERE id = 1 OR id = 2");
            assertEquals(QueryPlan.PlanType.SEQ_SCAN, plan.type());
        }
    }

    @Test @DisplayName("Planner: null WHERE → SEQ_SCAN")
    void planner_nullWhere() throws Exception {
        try (Executor exec = newExec("p_null.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            QueryPlan plan = exec.explain("SELECT * FROM t");
            assertEquals(QueryPlan.PlanType.SEQ_SCAN, plan.type());
        }
    }

    // =========================================================================
    // 2. EXPLAIN output
    // =========================================================================

    @Test @DisplayName("EXPLAIN: seq scan output contains table name and filter")
    void explain_seqScanOutput() throws Exception {
        try (Executor exec = newExec("exp_seq.fdb")) {
            ok(exec, "CREATE TABLE users (id INT)");
            QueryPlan plan = exec.explain("SELECT * FROM users WHERE id > 5");
            String out = plan.explain();
            assertTrue(out.contains("SeqScan"), "Should mention SeqScan: " + out);
            assertTrue(out.contains("users"),   "Should mention table: " + out);
        }
    }

    @Test @DisplayName("EXPLAIN: index lookup output contains table, column, op, key")
    void explain_indexLookupOutput() throws Exception {
        try (Executor exec = newExec("exp_idx.fdb")) {
            ok(exec, "CREATE TABLE users (id INT, name TEXT)");
            exec.createIndex("users");
            QueryPlan plan = exec.explain("SELECT * FROM users WHERE id = 42");
            String out = plan.explain();
            assertTrue(out.contains("IndexLookup"), "Should mention IndexLookup: " + out);
            assertTrue(out.contains("users"),       "Should mention table: " + out);
            assertTrue(out.contains("id"),          "Should mention indexed col: " + out);
            assertTrue(out.contains("EQ"),          "Should mention operator: " + out);
            assertTrue(out.contains("42"),          "Should mention key value: " + out);
        }
    }

    @Test @DisplayName("EXPLAIN on DELETE statement returns a plan")
    void explain_deleteStatement() throws Exception {
        try (Executor exec = newExec("exp_del.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            QueryPlan plan = exec.explain("DELETE FROM t WHERE id = 1");
            assertNotNull(plan);
            assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type());
        }
    }

    @Test @DisplayName("EXPLAIN on UPDATE statement returns a plan")
    void explain_updateStatement() throws Exception {
        try (Executor exec = newExec("exp_upd.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            exec.createIndex("t");
            QueryPlan plan = exec.explain("UPDATE t SET val = 'x' WHERE id = 7");
            assertNotNull(plan);
            assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type());
        }
    }

    // =========================================================================
    // 3. Indexed equality SELECT
    // =========================================================================

    @Test @DisplayName("Index EQ lookup returns correct row")
    void indexSelect_eq() throws Exception {
        try (Executor exec = newExec("isel_eq.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (2, 'Bob')");
            ok(exec, "INSERT INTO t VALUES (3, 'Carol')");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id = 2");
            assertEquals(1, r.rows().size());
            assertEquals("Bob", r.rows().get(0).getString(1));
        }
    }

    @Test @DisplayName("Index EQ lookup returns empty for missing key")
    void indexSelect_eqMiss() throws Exception {
        try (Executor exec = newExec("isel_miss.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id = 99");
            assertTrue(r.rows().isEmpty());
        }
    }

    @Test @DisplayName("Index EQ lookup works on TEXT column")
    void indexSelect_textEq() throws Exception {
        try (Executor exec = newExec("isel_text.fdb")) {
            ok(exec, "CREATE TABLE t (name TEXT, score INT)");
            exec.createIndex("t");   // index on name (col 0)
            ok(exec, "INSERT INTO t VALUES ('Alice', 90)");
            ok(exec, "INSERT INTO t VALUES ('Bob', 80)");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE name = 'Alice'");
            assertEquals(1, r.rows().size());
            assertEquals(90, r.rows().get(0).getInt(1));
        }
    }

    // =========================================================================
    // 4. Indexed range SELECT
    // =========================================================================

    @Test @DisplayName("Index LT lookup returns rows below threshold")
    void indexSelect_lt() throws Exception {
        try (Executor exec = newExec("isel_lt.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, v TEXT)");
            exec.createIndex("t");
            for (int i = 1; i <= 10; i++) exec.execute("INSERT INTO t VALUES (" + i + ", 'r')");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id < 4");
            assertEquals(3, r.rows().size());
            assertTrue(r.rows().stream().allMatch(t -> t.getInt(0) < 4));
        }
    }

    @Test @DisplayName("Index LTE lookup returns rows at and below threshold")
    void indexSelect_lte() throws Exception {
        try (Executor exec = newExec("isel_lte.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            for (int i = 1; i <= 5; i++) exec.execute("INSERT INTO t VALUES (" + i + ")");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id <= 3");
            assertEquals(3, r.rows().size());
        }
    }

    @Test @DisplayName("Index GT lookup returns rows above threshold")
    void indexSelect_gt() throws Exception {
        try (Executor exec = newExec("isel_gt.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            for (int i = 1; i <= 10; i++) exec.execute("INSERT INTO t VALUES (" + i + ")");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id > 7");
            assertEquals(3, r.rows().size());
            assertTrue(r.rows().stream().allMatch(t -> t.getInt(0) > 7));
        }
    }

    @Test @DisplayName("Index GTE lookup returns rows at and above threshold")
    void indexSelect_gte() throws Exception {
        try (Executor exec = newExec("isel_gte.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            for (int i = 1; i <= 5; i++) exec.execute("INSERT INTO t VALUES (" + i + ")");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id >= 4");
            assertEquals(2, r.rows().size());
        }
    }

    // =========================================================================
    // 5. Non-indexed column → seq scan produces correct results
    // =========================================================================

    @Test @DisplayName("Predicate on col 1 (no index) uses seq scan and returns correct rows")
    void seqScan_nonIndexedCol() throws Exception {
        try (Executor exec = newExec("seq_noncol.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            exec.createIndex("t");   // index on id
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (2, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (3, 'Bob')");

            // name is col 1, not indexed — must fall back to seq scan
            QueryResult r = ok(exec, "SELECT * FROM t WHERE name = 'Alice'");
            assertEquals(2, r.rows().size());
        }
    }

    // =========================================================================
    // 6. AND conjunction: index + residual
    // =========================================================================

    @Test @DisplayName("AND: index on id=5, residual on age>18 applied correctly")
    void and_indexPlusResidual() throws Exception {
        try (Executor exec = newExec("and_res.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, age INT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (5, 25)");
            ok(exec, "INSERT INTO t VALUES (5, 15)");   // same id, age below residual
            ok(exec, "INSERT INTO t VALUES (6, 30)");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id = 5 AND age > 18");
            assertEquals(1, r.rows().size());
            assertEquals(25, r.rows().get(0).getInt(1));
        }
    }

    @Test @DisplayName("AND: residual on left side handled correctly")
    void and_residualOnLeft() throws Exception {
        try (Executor exec = newExec("and_resl.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, score INT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (1, 50)");
            ok(exec, "INSERT INTO t VALUES (1, 80)");   // same id
            ok(exec, "INSERT INTO t VALUES (2, 70)");

            // Residual on left, index pred on right
            QueryResult r = ok(exec, "SELECT * FROM t WHERE score > 60 AND id = 1");
            assertEquals(1, r.rows().size());
            assertEquals(80, r.rows().get(0).getInt(1));
        }
    }

    // =========================================================================
    // 7. No index → seq scan
    // =========================================================================

    @Test @DisplayName("Without index, equality SELECT returns correct rows via seq scan")
    void noIndex_seqScanCorrect() throws Exception {
        try (Executor exec = newExec("noidx_sel.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (2, 'Bob')");
            ok(exec, "INSERT INTO t VALUES (1, 'AnotherAlice')");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id = 1");
            assertEquals(2, r.rows().size());
        }
    }

    // =========================================================================
    // 8. Indexed UPDATE
    // =========================================================================

    @Test @DisplayName("Index-backed UPDATE changes correct row only")
    void indexUpdate_correctRow() throws Exception {
        try (Executor exec = newExec("iupd.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (2, 'Bob')");
            ok(exec, "INSERT INTO t VALUES (3, 'Carol')");

            QueryResult ur = ok(exec, "UPDATE t SET name = 'Updated' WHERE id = 2");
            assertEquals(1, ur.rowsAffected());

            QueryResult sr = ok(exec, "SELECT * FROM t WHERE id = 2");
            assertEquals(1, sr.rows().size());
            assertEquals("Updated", sr.rows().get(0).getString(1));

            // Others unchanged
            assertEquals("Alice", ok(exec, "SELECT * FROM t WHERE id = 1").rows().get(0).getString(1));
            assertEquals("Carol", ok(exec, "SELECT * FROM t WHERE id = 3").rows().get(0).getString(1));
        }
    }

    @Test @DisplayName("Index-backed UPDATE: 0 rows affected when no match")
    void indexUpdate_noMatch() throws Exception {
        try (Executor exec = newExec("iupd_miss.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (1, 'a')");
            QueryResult r = ok(exec, "UPDATE t SET val = 'x' WHERE id = 99");
            assertEquals(0, r.rowsAffected());
        }
    }

    // =========================================================================
    // 9. Indexed DELETE
    // =========================================================================

    @Test @DisplayName("Index-backed DELETE removes correct row only")
    void indexDelete_correctRow() throws Exception {
        try (Executor exec = newExec("idel.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (2, 'Bob')");
            ok(exec, "INSERT INTO t VALUES (3, 'Carol')");

            QueryResult dr = ok(exec, "DELETE FROM t WHERE id = 2");
            assertEquals(1, dr.rowsAffected());

            QueryResult sr = ok(exec, "SELECT * FROM t");
            assertEquals(2, sr.rows().size());
            assertTrue(sr.rows().stream().noneMatch(t -> t.getInt(0) == 2));
        }
    }

    @Test @DisplayName("After indexed DELETE, key is gone from index")
    void indexDelete_removesFromIndex() throws Exception {
        try (Executor exec = newExec("idel_idx.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (42, 'answer')");
            ok(exec, "DELETE FROM t WHERE id = 42");

            BTree index = exec.getIndex("t");
            List<com.forgedb.storage.RecordId> rids =
                index.search(BTreeKey.ofInt(42));
            assertTrue(rids.isEmpty(), "Key 42 must be gone from index after DELETE");
        }
    }

    // =========================================================================
    // 10. Multiple matching RecordIds (duplicates in index)
    // =========================================================================

    @Test @DisplayName("Index EQ with duplicate keys returns all matching rows")
    void index_duplicateKeys() throws Exception {
        try (Executor exec = newExec("dup_keys.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (5, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (5, 'AliceToo')");
            ok(exec, "INSERT INTO t VALUES (6, 'Bob')");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id = 5");
            assertEquals(2, r.rows().size());
        }
    }

    // =========================================================================
    // 11. Large table — index lookup vs full scan
    // =========================================================================

    @Test @DisplayName("Index lookup on large table returns single correct row")
    void large_indexLookupSingleRow() throws Exception {
        try (Executor exec = newExec("large.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            exec.createIndex("t");

            int n = 500;
            for (int i = 0; i < n; i++) {
                exec.execute("INSERT INTO t VALUES (" + i + ", 'row" + i + "')");
            }

            // Point lookup at a specific key
            QueryResult r = ok(exec, "SELECT * FROM t WHERE id = 250");
            assertEquals(1, r.rows().size());
            assertEquals("row250", r.rows().get(0).getString(1));
        }
    }

    @Test @DisplayName("Large table: planner chose INDEX_LOOKUP for EQ predicate")
    void large_plannerChoosesIndex() throws Exception {
        try (Executor exec = newExec("large_plan.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            for (int i = 0; i < 100; i++) exec.execute("INSERT INTO t VALUES (" + i + ")");

            QueryPlan plan = exec.explain("SELECT * FROM t WHERE id = 50");
            assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type());
        }
    }

    // =========================================================================
    // 12. Predicate flip (literal op col)
    // =========================================================================

    @Test @DisplayName("Predicate '5 = id' is normalised and uses index")
    void flip_literalOnLeft() throws Exception {
        try (Executor exec = newExec("flip_eq.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (5, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (6, 'Bob')");

            QueryPlan plan = exec.explain("SELECT * FROM t WHERE 5 = id");
            assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type(),
                "Should use index even when literal is on left");
            assertEquals(BinaryExpression.Op.EQ, plan.indexOp());

            QueryResult r = ok(exec, "SELECT * FROM t WHERE 5 = id");
            assertEquals(1, r.rows().size());
            assertEquals("Alice", r.rows().get(0).getString(1));
        }
    }

    @Test @DisplayName("Predicate '10 > id' is normalised to 'id < 10' and uses index")
    void flip_gtLiteral() throws Exception {
        try (Executor exec = newExec("flip_gt.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            for (int i = 1; i <= 15; i++) exec.execute("INSERT INTO t VALUES (" + i + ")");

            QueryPlan plan = exec.explain("SELECT * FROM t WHERE 10 > id");
            assertEquals(QueryPlan.PlanType.INDEX_LOOKUP, plan.type());
            assertEquals(BinaryExpression.Op.LT, plan.indexOp(),
                "10 > id should flip to id < 10");

            QueryResult r = ok(exec, "SELECT * FROM t WHERE 10 > id");
            assertEquals(9, r.rows().size());
        }
    }

    // =========================================================================
    // 13. NEQ → seq scan
    // =========================================================================

    @Test @DisplayName("NEQ predicate falls back to seq scan and returns correct rows")
    void neq_seqScanResult() throws Exception {
        try (Executor exec = newExec("neq_seq.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            exec.createIndex("t");
            for (int i = 1; i <= 5; i++) exec.execute("INSERT INTO t VALUES (" + i + ")");

            QueryPlan plan = exec.explain("SELECT * FROM t WHERE id <> 3");
            assertEquals(QueryPlan.PlanType.SEQ_SCAN, plan.type());

            QueryResult r = ok(exec, "SELECT * FROM t WHERE id <> 3");
            assertEquals(4, r.rows().size());
            assertTrue(r.rows().stream().noneMatch(t -> t.getInt(0) == 3));
        }
    }

    // =========================================================================
    // 14. QueryPlan unit tests
    // =========================================================================

    @Test @DisplayName("QueryPlan.seqScan produces correct explain string")
    void queryPlan_seqScanExplain() throws Exception {
        Expression where = new BinaryExpression(
            new ColumnRef("id"), BinaryExpression.Op.GT, new IntLiteral(5));
        QueryPlan plan = QueryPlan.seqScan("users", where, null);
        String explain = plan.explain();
        assertTrue(explain.startsWith("SeqScan"));
        assertTrue(explain.contains("users"));
    }

    @Test @DisplayName("QueryPlan.indexLookup produces correct explain string")
    void queryPlan_indexLookupExplain() {
        QueryPlan plan = QueryPlan.indexLookup("orders", null, null,
            "order_id", BinaryExpression.Op.EQ, 99, null);
        String explain = plan.explain();
        assertTrue(explain.startsWith("IndexLookup"));
        assertTrue(explain.contains("orders"));
        assertTrue(explain.contains("order_id"));
        assertTrue(explain.contains("EQ"));
        assertTrue(explain.contains("99"));
        assertTrue(explain.contains("none")); // no residual
    }

    // =========================================================================
    // 15. Pre-existing M6 tests — verify planner doesn't break them
    // =========================================================================

    @Test @DisplayName("M6 regression: SELECT * without index still works")
    void regression_selectStarNoIndex() throws Exception {
        try (Executor exec = newExec("reg_sel.fdb")) {
            ok(exec, "CREATE TABLE users (id INT, name TEXT, age INT)");
            ok(exec, "INSERT INTO users VALUES (1, 'Alice', 21)");
            ok(exec, "INSERT INTO users VALUES (2, 'Bob', 24)");
            QueryResult r = ok(exec, "SELECT * FROM users");
            assertEquals(2, r.rows().size());
        }
    }

    @Test @DisplayName("M6 regression: full target-demo workflow still works")
    void regression_targetDemo() throws Exception {
        try (Executor exec = newExec("reg_demo.fdb")) {
            exec.executeAll(
                "CREATE TABLE users (id INT, name TEXT, age INT);\n" +
                "INSERT INTO users VALUES (1, 'Alice', 21);\n" +
                "INSERT INTO users VALUES (2, 'Bob', 24);\n"
            );
            QueryResult all = exec.execute("SELECT * FROM users");
            assertEquals(2, all.rows().size());

            QueryResult one = exec.execute("SELECT * FROM users WHERE id = 2");
            assertEquals(1, one.rows().size());
            assertEquals("Bob", one.rows().get(0).getString(1));

            exec.execute("UPDATE users SET age = 25 WHERE id = 2");
            assertEquals(25,
                exec.execute("SELECT * FROM users WHERE id = 2")
                    .rows().get(0).getInt(2));

            exec.execute("DELETE FROM users WHERE id = 1");
            assertEquals(1, exec.execute("SELECT * FROM users").rows().size());
        }
    }

    @Test @DisplayName("M6 regression: DELETE/UPDATE without index still correct")
    void regression_deleteUpdateNoIndex() throws Exception {
        try (Executor exec = newExec("reg_del.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            ok(exec, "INSERT INTO t VALUES (1, 'a')");
            ok(exec, "INSERT INTO t VALUES (2, 'b')");
            ok(exec, "INSERT INTO t VALUES (3, 'c')");

            ok(exec, "DELETE FROM t WHERE id = 2");
            assertEquals(2, ok(exec, "SELECT * FROM t").rows().size());

            ok(exec, "UPDATE t SET val = 'z' WHERE id = 1");
            assertEquals("z",
                ok(exec, "SELECT * FROM t WHERE id = 1").rows().get(0).getString(1));
        }
    }
}
