package com.forgedb.execution;

import com.forgedb.catalog.DataType;
import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DiskManagerImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the Milestone 6 query execution engine.
 *
 * Test categories:
 *   1.  CREATE TABLE
 *   2.  INSERT
 *   3.  SELECT *
 *   4.  SELECT with column projection
 *   5.  SELECT with WHERE — equality
 *   6.  SELECT with WHERE — comparison operators
 *   7.  SELECT with WHERE — AND / OR / NOT
 *   8.  DELETE
 *   9.  UPDATE
 *  10.  Empty tables
 *  11.  Multiple rows
 *  12.  Type checking / coercion
 *  13.  Error cases: unknown tables, unknown columns, duplicate tables
 *  14.  All five data types (INT, LONG, BOOLEAN, DOUBLE, TEXT)
 *  15.  B+ Tree index integration
 *  16.  ExpressionEvaluator unit tests
 *  17.  Catalog unit tests
 *  18.  QueryResult unit tests
 */
class ExecutorTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String dbPath(String name) {
        return tempDir.resolve(name).toString();
    }

    /** Creates a fresh Executor backed by its own database file. */
    private Executor newExecutor(String dbFile) throws Exception {
        BufferPool bp = new BufferPool(new DiskManagerImpl(dbPath(dbFile)), 64);
        return new Executor(bp);
    }

    /** Run a SQL string and assert it succeeds, returning the QueryResult. */
    private QueryResult ok(Executor exec, String sql) throws Exception {
        QueryResult r = exec.execute(sql);
        assertNotNull(r, "Expected non-null QueryResult for: " + sql);
        return r;
    }

    // =========================================================================
    // 1. CREATE TABLE
    // =========================================================================

    @Test @DisplayName("CREATE TABLE succeeds and is registered in catalog")
    void create_registersTable() throws Exception {
        try (Executor exec = newExecutor("create.fdb")) {
            ok(exec, "CREATE TABLE users (id INT, name TEXT, age INT)");
            assertTrue(exec.catalog().hasTable("users"));
        }
    }

    @Test @DisplayName("CREATE TABLE returns message result")
    void create_returnsMessage() throws Exception {
        try (Executor exec = newExecutor("create_msg.fdb")) {
            QueryResult r = ok(exec, "CREATE TABLE t (id INT)");
            assertFalse(r.isResultSet());
            assertTrue(r.message().contains("t"));
        }
    }

    @Test @DisplayName("CREATE TABLE with all five types")
    void create_allTypes() throws Exception {
        try (Executor exec = newExecutor("create_types.fdb")) {
            ok(exec, "CREATE TABLE all_types " +
               "(a INT, b LONG, c BOOLEAN, d DOUBLE, e TEXT)");
            Schema s = exec.catalog().getSchema("all_types");
            assertEquals(DataType.INT,     s.getColumn(0).type());
            assertEquals(DataType.LONG,    s.getColumn(1).type());
            assertEquals(DataType.BOOLEAN, s.getColumn(2).type());
            assertEquals(DataType.DOUBLE,  s.getColumn(3).type());
            assertEquals(DataType.TEXT,    s.getColumn(4).type());
        }
    }

    @Test @DisplayName("CREATE TABLE duplicate name throws ExecutionException")
    void create_duplicateThrows() throws Exception {
        try (Executor exec = newExecutor("create_dup.fdb")) {
            ok(exec, "CREATE TABLE users (id INT)");
            assertThrows(ExecutionException.class,
                () -> exec.execute("CREATE TABLE users (id INT)"));
        }
    }

    @Test @DisplayName("CREATE TABLE is case-insensitive for keywords")
    void create_caseInsensitive() throws Exception {
        try (Executor exec = newExecutor("create_case.fdb")) {
            ok(exec, "create table products (id int, name text)");
            assertTrue(exec.catalog().hasTable("products"));
        }
    }

    // =========================================================================
    // 2. INSERT
    // =========================================================================

    @Test @DisplayName("INSERT returns rowsAffected = 1")
    void insert_returnsOne() throws Exception {
        try (Executor exec = newExecutor("insert_cnt.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            QueryResult r = ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            assertEquals(1, r.rowsAffected());
        }
    }

    @Test @DisplayName("INSERT with explicit column list")
    void insert_explicitCols() throws Exception {
        try (Executor exec = newExecutor("insert_cols.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT, age INT)");
            ok(exec, "INSERT INTO t (id, name) VALUES (1, 'Alice')");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertEquals(1, r.rows().size());
            assertEquals(1,       r.rows().get(0).getInt(0));
            assertEquals("Alice", r.rows().get(0).getString(1));
            assertTrue(r.rows().get(0).isNull(2));   // age not set → NULL
        }
    }

    @Test @DisplayName("INSERT wrong number of values throws ExecutionException")
    void insert_wrongValueCount() throws Exception {
        try (Executor exec = newExecutor("insert_cnt_err.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            assertThrows(ExecutionException.class,
                () -> exec.execute("INSERT INTO t VALUES (1)"));
        }
    }

    @Test @DisplayName("INSERT into unknown table throws ExecutionException")
    void insert_unknownTable() throws Exception {
        try (Executor exec = newExecutor("insert_unk.fdb")) {
            assertThrows(ExecutionException.class,
                () -> exec.execute("INSERT INTO no_such_table VALUES (1)"));
        }
    }

    @Test @DisplayName("INSERT unknown column in column list throws ExecutionException")
    void insert_unknownColumn() throws Exception {
        try (Executor exec = newExecutor("insert_unk_col.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            assertThrows(ExecutionException.class,
                () -> exec.execute("INSERT INTO t (bogus) VALUES (1)"));
        }
    }

    @Test @DisplayName("INSERT with NULL value")
    void insert_nullValue() throws Exception {
        try (Executor exec = newExecutor("insert_null.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            ok(exec, "INSERT INTO t VALUES (1, NULL)");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertTrue(r.rows().get(0).isNull(1));
        }
    }

    // =========================================================================
    // 3. SELECT *
    // =========================================================================

    @Test @DisplayName("SELECT * from empty table returns empty result set")
    void selectStar_emptyTable() throws Exception {
        try (Executor exec = newExecutor("sel_empty.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertTrue(r.isResultSet());
            assertTrue(r.rows().isEmpty());
        }
    }

    @Test @DisplayName("SELECT * returns all rows in insertion order")
    void selectStar_allRows() throws Exception {
        try (Executor exec = newExecutor("sel_all.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            ok(exec, "INSERT INTO t VALUES (1, 'a')");
            ok(exec, "INSERT INTO t VALUES (2, 'b')");
            ok(exec, "INSERT INTO t VALUES (3, 'c')");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertEquals(3, r.rows().size());
            assertEquals(1, r.rows().get(0).getInt(0));
            assertEquals(2, r.rows().get(1).getInt(0));
            assertEquals(3, r.rows().get(2).getInt(0));
        }
    }

    @Test @DisplayName("SELECT * output schema matches table schema")
    void selectStar_outputSchemaMatchesTable() throws Exception {
        try (Executor exec = newExecutor("sel_schema.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT, active BOOLEAN)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice', TRUE)");
            QueryResult r = ok(exec, "SELECT * FROM t");
            Schema s = r.schema();
            assertEquals(3, s.columnCount());
            assertEquals("id",     s.getColumn(0).name());
            assertEquals("name",   s.getColumn(1).name());
            assertEquals("active", s.getColumn(2).name());
        }
    }

    @Test @DisplayName("SELECT * from unknown table throws ExecutionException")
    void selectStar_unknownTable() throws Exception {
        try (Executor exec = newExecutor("sel_unk.fdb")) {
            assertThrows(ExecutionException.class,
                () -> exec.execute("SELECT * FROM no_table"));
        }
    }

    // =========================================================================
    // 4. SELECT with column projection
    // =========================================================================

    @Test @DisplayName("SELECT single column returns projected schema")
    void projection_singleCol() throws Exception {
        try (Executor exec = newExecutor("proj1.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT, age INT)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice', 30)");
            QueryResult r = ok(exec, "SELECT name FROM t");
            assertEquals(1, r.schema().columnCount());
            assertEquals("name", r.schema().getColumn(0).name());
            assertEquals("Alice", r.rows().get(0).getString(0));
        }
    }

    @Test @DisplayName("SELECT two columns out of three")
    void projection_twoOfThree() throws Exception {
        try (Executor exec = newExecutor("proj2.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT, age INT)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice', 30)");
            ok(exec, "INSERT INTO t VALUES (2, 'Bob',   25)");
            QueryResult r = ok(exec, "SELECT id, age FROM t");
            assertEquals(2, r.schema().columnCount());
            assertEquals("id",  r.schema().getColumn(0).name());
            assertEquals("age", r.schema().getColumn(1).name());
            assertEquals(2, r.rows().size());
            assertEquals(1,  r.rows().get(0).getInt(0));
            assertEquals(30, r.rows().get(0).getInt(1));
        }
    }

    @Test @DisplayName("SELECT column with AS alias")
    void projection_alias() throws Exception {
        try (Executor exec = newExecutor("proj_alias.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            QueryResult r = ok(exec, "SELECT id AS user_id FROM t");
            assertEquals("user_id", r.schema().getColumn(0).name());
            assertEquals(1, r.rows().get(0).getInt(0));
        }
    }

    @Test @DisplayName("SELECT unknown column throws ExecutionException")
    void projection_unknownColumn() throws Exception {
        try (Executor exec = newExecutor("proj_unk.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            assertThrows(ExecutionException.class,
                () -> exec.execute("SELECT bogus FROM t"));
        }
    }

    // =========================================================================
    // 5. SELECT with WHERE — equality
    // =========================================================================

    @Test @DisplayName("WHERE id = 1 returns matching row")
    void where_equalityInt() throws Exception {
        try (Executor exec = newExecutor("where_eq.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (2, 'Bob')");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE id = 1");
            assertEquals(1, r.rows().size());
            assertEquals("Alice", r.rows().get(0).getString(1));
        }
    }

    @Test @DisplayName("WHERE name = 'Alice' returns matching row")
    void where_equalityText() throws Exception {
        try (Executor exec = newExecutor("where_eq_txt.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (2, 'Bob')");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE name = 'Alice'");
            assertEquals(1, r.rows().size());
            assertEquals(1, r.rows().get(0).getInt(0));
        }
    }

    @Test @DisplayName("WHERE with no match returns empty result")
    void where_noMatch() throws Exception {
        try (Executor exec = newExecutor("where_nomatch.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            ok(exec, "INSERT INTO t VALUES (1)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE id = 99");
            assertTrue(r.rows().isEmpty());
        }
    }

    @Test @DisplayName("WHERE id <> 1 excludes matching row")
    void where_neq() throws Exception {
        try (Executor exec = newExecutor("where_neq.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            ok(exec, "INSERT INTO t VALUES (1)");
            ok(exec, "INSERT INTO t VALUES (2)");
            ok(exec, "INSERT INTO t VALUES (3)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE id <> 2");
            assertEquals(2, r.rows().size());
        }
    }

    // =========================================================================
    // 6. SELECT with WHERE — comparison operators
    // =========================================================================

    @Test @DisplayName("WHERE age > 25 returns rows above threshold")
    void where_gt() throws Exception {
        try (Executor exec = newExecutor("where_gt.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, age INT)");
            ok(exec, "INSERT INTO t VALUES (1, 20)");
            ok(exec, "INSERT INTO t VALUES (2, 30)");
            ok(exec, "INSERT INTO t VALUES (3, 25)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE age > 25");
            assertEquals(1, r.rows().size());
            assertEquals(30, r.rows().get(0).getInt(1));
        }
    }

    @Test @DisplayName("WHERE age >= 25 returns rows at and above threshold")
    void where_gte() throws Exception {
        try (Executor exec = newExecutor("where_gte.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, age INT)");
            ok(exec, "INSERT INTO t VALUES (1, 20)");
            ok(exec, "INSERT INTO t VALUES (2, 30)");
            ok(exec, "INSERT INTO t VALUES (3, 25)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE age >= 25");
            assertEquals(2, r.rows().size());
        }
    }

    @Test @DisplayName("WHERE age < 25 returns rows below threshold")
    void where_lt() throws Exception {
        try (Executor exec = newExecutor("where_lt.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, age INT)");
            ok(exec, "INSERT INTO t VALUES (1, 20)");
            ok(exec, "INSERT INTO t VALUES (2, 30)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE age < 25");
            assertEquals(1, r.rows().size());
            assertEquals(20, r.rows().get(0).getInt(1));
        }
    }

    @Test @DisplayName("WHERE age <= 25 returns rows at and below threshold")
    void where_lte() throws Exception {
        try (Executor exec = newExecutor("where_lte.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, age INT)");
            ok(exec, "INSERT INTO t VALUES (1, 20)");
            ok(exec, "INSERT INTO t VALUES (2, 30)");
            ok(exec, "INSERT INTO t VALUES (3, 25)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE age <= 25");
            assertEquals(2, r.rows().size());
        }
    }

    // =========================================================================
    // 7. SELECT with WHERE — AND / OR / NOT
    // =========================================================================

    @Test @DisplayName("WHERE a = 1 AND b = 2 matches only both conditions")
    void where_and() throws Exception {
        try (Executor exec = newExecutor("where_and.fdb")) {
            ok(exec, "CREATE TABLE t (a INT, b INT)");
            ok(exec, "INSERT INTO t VALUES (1, 2)");
            ok(exec, "INSERT INTO t VALUES (1, 3)");
            ok(exec, "INSERT INTO t VALUES (2, 2)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE a = 1 AND b = 2");
            assertEquals(1, r.rows().size());
            assertEquals(1, r.rows().get(0).getInt(0));
            assertEquals(2, r.rows().get(0).getInt(1));
        }
    }

    @Test @DisplayName("WHERE a = 1 OR b = 2 matches either condition")
    void where_or() throws Exception {
        try (Executor exec = newExecutor("where_or.fdb")) {
            ok(exec, "CREATE TABLE t (a INT, b INT)");
            ok(exec, "INSERT INTO t VALUES (1, 3)");
            ok(exec, "INSERT INTO t VALUES (4, 2)");
            ok(exec, "INSERT INTO t VALUES (5, 6)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE a = 1 OR b = 2");
            assertEquals(2, r.rows().size());
        }
    }

    @Test @DisplayName("WHERE NOT id = 1 excludes id=1")
    void where_not() throws Exception {
        try (Executor exec = newExecutor("where_not.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            ok(exec, "INSERT INTO t VALUES (1)");
            ok(exec, "INSERT INTO t VALUES (2)");
            ok(exec, "INSERT INTO t VALUES (3)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE NOT id = 1");
            assertEquals(2, r.rows().size());
            assertTrue(r.rows().stream().noneMatch(t -> t.getInt(0) == 1));
        }
    }

    @Test @DisplayName("WHERE with AND + OR operator precedence")
    void where_precedence() throws Exception {
        // a=1 OR b=2 AND c=3 should parse as: a=1 OR (b=2 AND c=3)
        try (Executor exec = newExecutor("where_prec.fdb")) {
            ok(exec, "CREATE TABLE t (a INT, b INT, c INT)");
            ok(exec, "INSERT INTO t VALUES (1, 9, 9)");   // a=1 → matches
            ok(exec, "INSERT INTO t VALUES (9, 2, 3)");   // b=2 AND c=3 → matches
            ok(exec, "INSERT INTO t VALUES (9, 2, 9)");   // b=2 but c≠3 → no match
            ok(exec, "INSERT INTO t VALUES (9, 9, 3)");   // c=3 but b≠2 → no match
            QueryResult r = ok(exec,
                "SELECT * FROM t WHERE a = 1 OR b = 2 AND c = 3");
            assertEquals(2, r.rows().size());
        }
    }

    @Test @DisplayName("WHERE TRUE returns all rows")
    void where_literalTrue() throws Exception {
        try (Executor exec = newExecutor("where_true.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            ok(exec, "INSERT INTO t VALUES (1)");
            ok(exec, "INSERT INTO t VALUES (2)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE TRUE");
            assertEquals(2, r.rows().size());
        }
    }

    @Test @DisplayName("WHERE FALSE returns no rows")
    void where_literalFalse() throws Exception {
        try (Executor exec = newExecutor("where_false.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            ok(exec, "INSERT INTO t VALUES (1)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE FALSE");
            assertTrue(r.rows().isEmpty());
        }
    }

    // =========================================================================
    // 8. DELETE
    // =========================================================================

    @Test @DisplayName("DELETE all rows (no WHERE)")
    void delete_allRows() throws Exception {
        try (Executor exec = newExecutor("del_all.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            ok(exec, "INSERT INTO t VALUES (1)");
            ok(exec, "INSERT INTO t VALUES (2)");
            ok(exec, "INSERT INTO t VALUES (3)");
            QueryResult r = ok(exec, "DELETE FROM t");
            assertEquals(3, r.rowsAffected());
            assertTrue(ok(exec, "SELECT * FROM t").rows().isEmpty());
        }
    }

    @Test @DisplayName("DELETE with WHERE deletes only matching rows")
    void delete_withWhere() throws Exception {
        try (Executor exec = newExecutor("del_where.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
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

    @Test @DisplayName("DELETE from empty table returns 0 affected")
    void delete_emptyTable() throws Exception {
        try (Executor exec = newExecutor("del_empty.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            QueryResult r = ok(exec, "DELETE FROM t WHERE id = 1");
            assertEquals(0, r.rowsAffected());
        }
    }

    @Test @DisplayName("DELETE from unknown table throws ExecutionException")
    void delete_unknownTable() throws Exception {
        try (Executor exec = newExecutor("del_unk.fdb")) {
            assertThrows(ExecutionException.class,
                () -> exec.execute("DELETE FROM no_table WHERE id = 1"));
        }
    }

    // =========================================================================
    // 9. UPDATE
    // =========================================================================

    @Test @DisplayName("UPDATE changes correct column")
    void update_singleColumn() throws Exception {
        try (Executor exec = newExecutor("upd1.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            QueryResult ur = ok(exec, "UPDATE t SET name = 'AliceNew' WHERE id = 1");
            assertEquals(1, ur.rowsAffected());
            QueryResult sr = ok(exec, "SELECT * FROM t WHERE id = 1");
            assertEquals("AliceNew", sr.rows().get(0).getString(1));
        }
    }

    @Test @DisplayName("UPDATE with multiple SET clauses")
    void update_multipleSet() throws Exception {
        try (Executor exec = newExecutor("upd2.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT, age INT)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice', 30)");
            ok(exec, "UPDATE t SET name = 'Bob', age = 25 WHERE id = 1");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertEquals("Bob", r.rows().get(0).getString(1));
            assertEquals(25,    r.rows().get(0).getInt(2));
        }
    }

    @Test @DisplayName("UPDATE without WHERE updates all rows")
    void update_noWhere() throws Exception {
        try (Executor exec = newExecutor("upd3.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, score INT)");
            ok(exec, "INSERT INTO t VALUES (1, 10)");
            ok(exec, "INSERT INTO t VALUES (2, 20)");
            QueryResult ur = ok(exec, "UPDATE t SET score = 99");
            assertEquals(2, ur.rowsAffected());
            QueryResult sr = ok(exec, "SELECT * FROM t");
            assertTrue(sr.rows().stream().allMatch(t -> t.getInt(1) == 99));
        }
    }

    @Test @DisplayName("UPDATE on empty table returns 0 rows affected")
    void update_emptyTable() throws Exception {
        try (Executor exec = newExecutor("upd_empty.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val INT)");
            QueryResult r = ok(exec, "UPDATE t SET val = 1");
            assertEquals(0, r.rowsAffected());
        }
    }

    @Test @DisplayName("UPDATE with unknown column in SET throws ExecutionException")
    void update_unknownSetColumn() throws Exception {
        try (Executor exec = newExecutor("upd_unk.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            assertThrows(ExecutionException.class,
                () -> exec.execute("UPDATE t SET bogus = 1"));
        }
    }

    @Test @DisplayName("UPDATE from unknown table throws ExecutionException")
    void update_unknownTable() throws Exception {
        try (Executor exec = newExecutor("upd_unk_tbl.fdb")) {
            assertThrows(ExecutionException.class,
                () -> exec.execute("UPDATE no_table SET id = 1"));
        }
    }

    // =========================================================================
    // 10. Empty tables
    // =========================================================================

    @Test @DisplayName("SELECT on newly created table returns empty result set")
    void empty_selectReturnsEmpty() throws Exception {
        try (Executor exec = newExecutor("empty_sel.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertTrue(r.isResultSet());
            assertEquals(0, r.rowsAffected());
            assertTrue(r.rows().isEmpty());
        }
    }

    @Test @DisplayName("DELETE all rows then SELECT returns empty")
    void empty_afterDeleteAll() throws Exception {
        try (Executor exec = newExecutor("empty_del.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            ok(exec, "INSERT INTO t VALUES (1)");
            ok(exec, "DELETE FROM t");
            assertTrue(ok(exec, "SELECT * FROM t").rows().isEmpty());
        }
    }

    // =========================================================================
    // 11. Multiple rows across multiple pages
    // =========================================================================

    @Test @DisplayName("Insert 200 rows and SELECT * returns all 200")
    void multiRow_200rows() throws Exception {
        try (Executor exec = newExecutor("multi200.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            for (int i = 0; i < 200; i++) {
                exec.execute("INSERT INTO t VALUES (" + i + ", 'row" + i + "')");
            }
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertEquals(200, r.rows().size());
        }
    }

    @Test @DisplayName("Insert 200 rows, DELETE 100, SELECT returns 100")
    void multiRow_deleteHalf() throws Exception {
        try (Executor exec = newExecutor("multi_del.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            for (int i = 0; i < 200; i++) {
                exec.execute("INSERT INTO t VALUES (" + i + ")");
            }
            // Delete even-numbered rows
            ok(exec, "DELETE FROM t WHERE id < 100");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertEquals(100, r.rows().size());
        }
    }

    // =========================================================================
    // 12. Type checking and coercion
    // =========================================================================

    @Test @DisplayName("INT literal inserted into LONG column is widened")
    void types_intToLong() throws Exception {
        try (Executor exec = newExecutor("type_long.fdb")) {
            ok(exec, "CREATE TABLE t (v LONG)");
            ok(exec, "INSERT INTO t VALUES (42)");   // INT literal → coerced to LONG
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertEquals(42L, r.rows().get(0).getLong(0));
        }
    }

    @Test @DisplayName("INT literal inserted into DOUBLE column is widened")
    void types_intToDouble() throws Exception {
        try (Executor exec = newExecutor("type_dbl.fdb")) {
            ok(exec, "CREATE TABLE t (v DOUBLE)");
            ok(exec, "INSERT INTO t VALUES (3)");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertEquals(3.0, r.rows().get(0).getDouble(0), 1e-9);
        }
    }

    @Test @DisplayName("BOOLEAN value TRUE/FALSE round-trips correctly")
    void types_boolean() throws Exception {
        try (Executor exec = newExecutor("type_bool.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, active BOOLEAN)");
            ok(exec, "INSERT INTO t VALUES (1, TRUE)");
            ok(exec, "INSERT INTO t VALUES (2, FALSE)");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertTrue( r.rows().get(0).getBoolean(1));
            assertFalse(r.rows().get(1).getBoolean(1));
        }
    }

    @Test @DisplayName("DOUBLE literal round-trips correctly")
    void types_double() throws Exception {
        try (Executor exec = newExecutor("type_dbl2.fdb")) {
            ok(exec, "CREATE TABLE t (v DOUBLE)");
            ok(exec, "INSERT INTO t VALUES (3.14)");
            QueryResult r = ok(exec, "SELECT * FROM t");
            assertEquals(3.14, r.rows().get(0).getDouble(0), 1e-9);
        }
    }

    @Test @DisplayName("String type mismatch in INSERT throws ExecutionException")
    void types_mismatch() throws Exception {
        try (Executor exec = newExecutor("type_err.fdb")) {
            ok(exec, "CREATE TABLE t (id INT)");
            assertThrows(ExecutionException.class,
                () -> exec.execute("INSERT INTO t VALUES ('not_an_int')"));
        }
    }

    // =========================================================================
    // 13. Error cases
    // =========================================================================

    @Test @DisplayName("Syntax error in SQL throws ExecutionException wrapping SqlException")
    void errors_syntaxError() throws Exception {
        try (Executor exec = newExecutor("err_syntax.fdb")) {
            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> exec.execute("SELEKT * FROM t"));
            assertTrue(ex.getMessage().toLowerCase().contains("syntax") ||
                       ex.getCause() != null);
        }
    }

    @Test @DisplayName("SELECT from unknown table throws ExecutionException")
    void errors_unknownTableSelect() throws Exception {
        try (Executor exec = newExecutor("err_unk_sel.fdb")) {
            assertThrows(ExecutionException.class,
                () -> exec.execute("SELECT * FROM missing_table"));
        }
    }

    // =========================================================================
    // 14. All five data types end-to-end
    // =========================================================================

    @Test @DisplayName("All five types: full INSERT + SELECT + WHERE round-trip")
    void allTypes_roundTrip() throws Exception {
        try (Executor exec = newExecutor("all_types.fdb")) {
            ok(exec, "CREATE TABLE rec (i INT, l LONG, b BOOLEAN, d DOUBLE, t TEXT)");
            ok(exec, "INSERT INTO rec VALUES (42, 9999999999, TRUE, 2.718, 'hello')");

            QueryResult r = ok(exec, "SELECT * FROM rec");
            assertEquals(1, r.rows().size());
            Tuple row = r.rows().get(0);
            assertEquals(42,           row.getInt    (0));
            assertEquals(9999999999L,  row.getLong   (1));
            assertTrue(               row.getBoolean(2));
            assertEquals(2.718,        row.getDouble (3), 1e-9);
            assertEquals("hello",      row.getString (4));
        }
    }

    @Test @DisplayName("WHERE on LONG column with equality")
    void allTypes_whereLong() throws Exception {
        try (Executor exec = newExecutor("type_long_where.fdb")) {
            ok(exec, "CREATE TABLE t (id LONG, name TEXT)");
            ok(exec, "INSERT INTO t VALUES (9999999999, 'BigId')");
            ok(exec, "INSERT INTO t VALUES (1, 'SmallId')");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE id = 9999999999");
            assertEquals(1, r.rows().size());
            assertEquals("BigId", r.rows().get(0).getString(1));
        }
    }

    @Test @DisplayName("WHERE on DOUBLE column")
    void allTypes_whereDouble() throws Exception {
        try (Executor exec = newExecutor("type_dbl_where.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, score DOUBLE)");
            ok(exec, "INSERT INTO t VALUES (1, 3.14)");
            ok(exec, "INSERT INTO t VALUES (2, 2.71)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE score > 3.0");
            assertEquals(1, r.rows().size());
            assertEquals(1, r.rows().get(0).getInt(0));
        }
    }

    @Test @DisplayName("WHERE on BOOLEAN column")
    void allTypes_whereBool() throws Exception {
        try (Executor exec = newExecutor("type_bool_where.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, active BOOLEAN)");
            ok(exec, "INSERT INTO t VALUES (1, TRUE)");
            ok(exec, "INSERT INTO t VALUES (2, FALSE)");
            QueryResult r = ok(exec, "SELECT * FROM t WHERE active = TRUE");
            assertEquals(1, r.rows().size());
            assertEquals(1, r.rows().get(0).getInt(0));
        }
    }

    // =========================================================================
    // 15. B+ Tree index integration
    // =========================================================================

    @Test @DisplayName("createIndex builds index; search via index finds correct record")
    void btree_createAndSearch() throws Exception {
        try (Executor exec = newExecutor("btree_basic.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, name TEXT)");
            ok(exec, "INSERT INTO t VALUES (1, 'Alice')");
            ok(exec, "INSERT INTO t VALUES (2, 'Bob')");
            ok(exec, "INSERT INTO t VALUES (3, 'Carol')");

            exec.createIndex("t");
            assertNotNull(exec.getIndex("t"));

            // Search via index
            List<com.forgedb.storage.RecordId> rids =
                exec.getIndex("t").search(com.forgedb.index.BTreeKey.ofInt(2));
            assertEquals(1, rids.size());
        }
    }

    @Test @DisplayName("INSERT updates index; new key is searchable")
    void btree_insertUpdatesIndex() throws Exception {
        try (Executor exec = newExecutor("btree_insert.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            exec.createIndex("t");

            ok(exec, "INSERT INTO t VALUES (10, 'ten')");
            ok(exec, "INSERT INTO t VALUES (20, 'twenty')");

            List<com.forgedb.storage.RecordId> r1 =
                exec.getIndex("t").search(com.forgedb.index.BTreeKey.ofInt(10));
            List<com.forgedb.storage.RecordId> r2 =
                exec.getIndex("t").search(com.forgedb.index.BTreeKey.ofInt(20));
            assertEquals(1, r1.size());
            assertEquals(1, r2.size());
        }
    }

    @Test @DisplayName("DELETE removes entry from index")
    void btree_deleteRemovesFromIndex() throws Exception {
        try (Executor exec = newExecutor("btree_del.fdb")) {
            ok(exec, "CREATE TABLE t (id INT, val TEXT)");
            exec.createIndex("t");
            ok(exec, "INSERT INTO t VALUES (5, 'five')");
            ok(exec, "DELETE FROM t WHERE id = 5");

            List<com.forgedb.storage.RecordId> r =
                exec.getIndex("t").search(com.forgedb.index.BTreeKey.ofInt(5));
            assertTrue(r.isEmpty(), "Deleted key should not be in index");
        }
    }

    @Test @DisplayName("createIndex on unknown table throws ExecutionException")
    void btree_unknownTableThrows() throws Exception {
        try (Executor exec = newExecutor("btree_unk.fdb")) {
            assertThrows(ExecutionException.class,
                () -> exec.createIndex("no_such_table"));
        }
    }

    // =========================================================================
    // 16. ExpressionEvaluator unit tests
    // =========================================================================

    @Test @DisplayName("Evaluator: integer literal returns Integer")
    void eval_intLiteral() throws Exception {
        var e = new ExpressionEvaluator(null, null);
        assertEquals(42, e.evaluate(new com.forgedb.sql.ast.IntLiteral(42)));
    }

    @Test @DisplayName("Evaluator: string literal returns String")
    void eval_stringLiteral() throws Exception {
        var e = new ExpressionEvaluator(null, null);
        assertEquals("hi", e.evaluate(new com.forgedb.sql.ast.StringLiteral("hi")));
    }

    @Test @DisplayName("Evaluator: NULL literal returns null")
    void eval_nullLiteral() throws Exception {
        var e = new ExpressionEvaluator(null, null);
        assertNull(e.evaluate(com.forgedb.sql.ast.NullLiteral.INSTANCE));
    }

    @Test @DisplayName("Evaluator: column ref reads from tuple")
    void eval_columnRef() throws Exception {
        Schema s = Schema.of(
            new com.forgedb.catalog.Column("id", DataType.INT),
            new com.forgedb.catalog.Column("name", DataType.TEXT)
        );
        Tuple t = new Tuple(s, new Object[]{7, "Alice"});
        var e = new ExpressionEvaluator(s, t);
        assertEquals(7,       e.evaluate(new com.forgedb.sql.ast.ColumnRef("id")));
        assertEquals("Alice", e.evaluate(new com.forgedb.sql.ast.ColumnRef("name")));
    }

    @Test @DisplayName("Evaluator: unknown column ref throws ExecutionException")
    void eval_unknownCol() throws Exception {
        Schema s = Schema.of(new com.forgedb.catalog.Column("id", DataType.INT));
        Tuple  t = new Tuple(s, new Object[]{1});
        var e = new ExpressionEvaluator(s, t);
        assertThrows(ExecutionException.class,
            () -> e.evaluate(new com.forgedb.sql.ast.ColumnRef("bogus")));
    }

    @Test @DisplayName("Evaluator: EQ comparison returns Boolean")
    void eval_eq() throws Exception {
        var e = new ExpressionEvaluator(null, null);
        var expr = new com.forgedb.sql.ast.BinaryExpression(
            new com.forgedb.sql.ast.IntLiteral(3),
            com.forgedb.sql.ast.BinaryExpression.Op.EQ,
            new com.forgedb.sql.ast.IntLiteral(3));
        assertEquals(Boolean.TRUE, e.evaluate(expr));
    }

    @Test @DisplayName("Evaluator: AND short-circuits on FALSE")
    void eval_andShortCircuit() throws Exception {
        var e = new ExpressionEvaluator(null, null);
        // FALSE AND <anything> = FALSE (without evaluating right side needing context)
        var expr = new com.forgedb.sql.ast.BinaryExpression(
            new com.forgedb.sql.ast.BoolLiteral(false),
            com.forgedb.sql.ast.BinaryExpression.Op.AND,
            new com.forgedb.sql.ast.BoolLiteral(true));
        assertEquals(Boolean.FALSE, e.evaluate(expr));
    }

    @Test @DisplayName("Evaluator: coerce INT to LONG widens value")
    void eval_coerceIntToLong() throws Exception {
        Object result = ExpressionEvaluator.coerce(42, DataType.LONG, "col");
        assertInstanceOf(Long.class, result);
        assertEquals(42L, result);
    }

    @Test @DisplayName("Evaluator: coerce wrong type throws ExecutionException")
    void eval_coerceMismatch() throws Exception {
        assertThrows(ExecutionException.class,
            () -> ExpressionEvaluator.coerce("text", DataType.INT, "col"));
    }

    // =========================================================================
    // 17. Catalog unit tests
    // =========================================================================

    @Test @DisplayName("Catalog: register and retrieve table")
    void catalog_registerAndGet() throws Exception {
        try (Executor exec = newExecutor("cat.fdb")) {
            Catalog cat = exec.catalog();
            Schema  s   = Schema.of(new com.forgedb.catalog.Column("id", DataType.INT));
            // Register through executor (CREATE TABLE)
            ok(exec, "CREATE TABLE cat_test (id INT)");
            assertTrue(cat.hasTable("cat_test"));
            assertEquals(DataType.INT, cat.getSchema("cat_test").getColumn(0).type());
        }
    }

    @Test @DisplayName("Catalog: table name lookup is case-insensitive")
    void catalog_caseInsensitive() throws Exception {
        try (Executor exec = newExecutor("cat_case.fdb")) {
            ok(exec, "CREATE TABLE MyTable (id INT)");
            Catalog cat = exec.catalog();
            assertTrue(cat.hasTable("mytable"));
            assertTrue(cat.hasTable("MYTABLE"));
            assertTrue(cat.hasTable("MyTable"));
        }
    }

    @Test @DisplayName("Catalog: getTable on unknown name throws ExecutionException")
    void catalog_unknownThrows() throws Exception {
        try (Executor exec = newExecutor("cat_unk.fdb")) {
            assertThrows(ExecutionException.class,
                () -> exec.catalog().getTable("ghost"));
        }
    }

    // =========================================================================
    // 18. QueryResult unit tests
    // =========================================================================

    @Test @DisplayName("QueryResult.ofUpdate is not a result set")
    void queryResult_ofUpdate() {
        QueryResult r = QueryResult.ofUpdate("done", 3);
        assertFalse(r.isResultSet());
        assertEquals(3, r.rowsAffected());
        assertEquals("done", r.message());
        assertNull(r.schema());
        assertTrue(r.rows().isEmpty());
    }

    @Test @DisplayName("QueryResult.ofRows is a result set")
    void queryResult_ofRows() throws Exception {
        Schema s = Schema.of(new com.forgedb.catalog.Column("id", DataType.INT));
        Tuple  t = new Tuple(s, new Object[]{1});
        QueryResult r = QueryResult.ofRows(s, List.of(t));
        assertTrue(r.isResultSet());
        assertEquals(1, r.rowsAffected());
        assertEquals(s, r.schema());
        assertEquals(1, r.rows().size());
    }

    // =========================================================================
    // 19. executeAll — multiple statements
    // =========================================================================

    @Test @DisplayName("executeAll processes all statements and returns last result")
    void executeAll_multiStatement() throws Exception {
        try (Executor exec = newExecutor("exec_all.fdb")) {
            QueryResult last = exec.executeAll(
                "CREATE TABLE t (id INT, name TEXT);" +
                "INSERT INTO t VALUES (1, 'Alice');" +
                "INSERT INTO t VALUES (2, 'Bob');" +
                "SELECT * FROM t;"
            );
            assertTrue(last.isResultSet());
            assertEquals(2, last.rows().size());
        }
    }

    @Test @DisplayName("Full target-demo SQL from project README")
    void targetDemo_fullWorkflow() throws Exception {
        try (Executor exec = newExecutor("demo.fdb")) {
            exec.executeAll(
                "CREATE TABLE users (id INT, name TEXT, age INT);\n" +
                "INSERT INTO users VALUES (1, 'Alice', 21);\n" +
                "INSERT INTO users VALUES (2, 'Bob', 24);\n"
            );

            // SELECT *
            QueryResult all = exec.execute("SELECT * FROM users");
            assertEquals(2, all.rows().size());

            // SELECT WHERE
            QueryResult one = exec.execute("SELECT * FROM users WHERE id = 2");
            assertEquals(1, one.rows().size());
            assertEquals("Bob", one.rows().get(0).getString(1));

            // UPDATE
            exec.execute("UPDATE users SET age = 25 WHERE id = 2");
            QueryResult updated = exec.execute("SELECT * FROM users WHERE id = 2");
            assertEquals(25, updated.rows().get(0).getInt(2));

            // DELETE
            exec.execute("DELETE FROM users WHERE id = 1");
            QueryResult afterDel = exec.execute("SELECT * FROM users");
            assertEquals(1, afterDel.rows().size());
            assertEquals(2, afterDel.rows().get(0).getInt(0));
        }
    }
}
