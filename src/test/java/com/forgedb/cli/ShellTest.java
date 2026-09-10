package com.forgedb.cli;

import com.forgedb.execution.Executor;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DiskManagerImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for the ForgeDB CLI shell.
 *
 * IMPORTANT — resource management on Windows:
 * Each test that opens an Executor registers it in {@code openExecutors}.
 * The @AfterEach method closes all of them so the RandomAccessFile handle
 * is released before JUnit tries to delete the @TempDir. Without this,
 * Windows throws "file is being used by another process" during cleanup.
 *
 * Categories:
 *   1.  ResultPrinter — table formatting
 *   2.  Shell dispatch — SQL execution
 *   3.  EXPLAIN command
 *   4.  Meta-commands (\q, \tables, \?)
 *   5.  Error handling — bad SQL, unknown table
 *   6.  Multi-line statement accumulation
 *   7.  Multiple statements in one session
 *   8.  CREATE TABLE, INSERT, SELECT, DELETE, UPDATE end-to-end
 */
class ShellTest {

    @TempDir
    Path tempDir;

    /** All Executors opened in this test — closed in @AfterEach. */
    private final List<Executor> openExecutors = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (Executor e : openExecutors) {
            try { e.close(); } catch (Exception ignored) {}
        }
        openExecutors.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String dbPath(String name) {
        return tempDir.resolve(name).toString();
    }

    /** Creates a fresh shell backed by an isolated database file. */
    private ForgeDBShell newShell(StringWriter sw, String dbFile) throws Exception {
        BufferPool bp = new BufferPool(new DiskManagerImpl(dbPath(dbFile)), 64);
        Executor exec = new Executor(bp);
        openExecutors.add(exec);          // register for cleanup
        PrintWriter pw = new PrintWriter(sw, true);
        return new ForgeDBShell(exec, pw, /*interactive=*/false);
    }

    /**
     * Creates a fresh shell, runs the given lines, and returns captured output.
     * The Executor is registered in openExecutors for @AfterEach cleanup.
     */
    private String runFresh(String dbFile, String... lines) throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, dbFile);
        StringReader sr = new StringReader(String.join("\n", lines) + "\n");
        shell.runInteractive(new BufferedReader(sr));
        return sw.toString();
    }

    // =========================================================================
    // 1. ResultPrinter
    // =========================================================================

    @Test @DisplayName("ResultPrinter: SELECT * formats as ASCII table with header and rows")
    void printer_selectFormatted() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "print1.fdb");
        shell.dispatch("CREATE TABLE t (id INT, name TEXT)");
        shell.dispatch("INSERT INTO t VALUES (1, 'Alice')");
        shell.dispatch("INSERT INTO t VALUES (2, 'Bob')");
        shell.dispatch("SELECT * FROM t");
        String out = sw.toString();

        assertTrue(out.contains("|"),      "Output must contain column separators");
        assertTrue(out.contains("id"),     "Output must contain column header 'id'");
        assertTrue(out.contains("name"),   "Output must contain column header 'name'");
        assertTrue(out.contains("Alice"),  "Output must contain row data 'Alice'");
        assertTrue(out.contains("Bob"),    "Output must contain row data 'Bob'");
        assertTrue(out.contains("2 rows"), "Footer must show row count");
    }

    @Test @DisplayName("ResultPrinter: empty table shows header and 0 rows")
    void printer_emptyTable() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "print2.fdb");
        shell.dispatch("CREATE TABLE t (id INT, name TEXT)");
        shell.dispatch("SELECT * FROM t");
        String out = sw.toString();
        assertTrue(out.contains("id"),     "Header must appear even for empty table");
        assertTrue(out.contains("0 rows"), "Footer must show 0 rows");
    }

    @Test @DisplayName("ResultPrinter: NULL values shown as literal NULL")
    void printer_nullValues() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "print3.fdb");
        shell.dispatch("CREATE TABLE t (id INT, name TEXT)");
        shell.dispatch("INSERT INTO t VALUES (1, NULL)");
        shell.dispatch("SELECT * FROM t");
        String out = sw.toString();
        assertTrue(out.contains("NULL"), "NULL value must be shown as 'NULL'");
    }

    @Test @DisplayName("ResultPrinter: table has +---+ separator lines")
    void printer_alignment() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "print4.fdb");
        shell.dispatch("CREATE TABLE t (id INT, name TEXT)");
        shell.dispatch("INSERT INTO t VALUES (42, 'x')");
        shell.dispatch("SELECT * FROM t");
        String out = sw.toString();
        assertTrue(out.contains("+"), "Must have +---+ separator lines");
    }

    @Test @DisplayName("ResultPrinter: INSERT result shows affected row message")
    void printer_insertMessage() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "print5.fdb");
        shell.dispatch("CREATE TABLE t (id INT)");
        shell.dispatch("INSERT INTO t VALUES (1)");
        String out = sw.toString();
        assertTrue(out.contains("1 row inserted"), "INSERT should report '1 row inserted'");
    }

    @Test @DisplayName("ResultPrinter: DELETE result shows affected row count")
    void printer_deleteMessage() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "print6.fdb");
        shell.dispatch("CREATE TABLE t (id INT)");
        shell.dispatch("INSERT INTO t VALUES (1)");
        shell.dispatch("INSERT INTO t VALUES (2)");
        shell.dispatch("DELETE FROM t WHERE id = 1");
        String out = sw.toString();
        assertTrue(out.contains("1 row(s) deleted"),
            "DELETE should report deleted count; got: " + out);
    }

    // =========================================================================
    // 2. Shell dispatch
    // =========================================================================

    @Test @DisplayName("dispatch: CREATE TABLE produces message")
    void dispatch_createTable() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "disp1.fdb");
        shell.dispatch("CREATE TABLE users (id INT, name TEXT)");
        String out = sw.toString();
        assertTrue(out.toLowerCase().contains("created") ||
                   out.toLowerCase().contains("users"),
            "CREATE TABLE should produce a confirmation: " + out);
    }

    @Test @DisplayName("dispatch: SELECT returns result rows")
    void dispatch_select() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "disp2.fdb");
        shell.dispatch("CREATE TABLE t (id INT)");
        shell.dispatch("INSERT INTO t VALUES (7)");
        shell.dispatch("SELECT * FROM t");
        String out = sw.toString();
        assertTrue(out.contains("7"), "SELECT should show value '7'");
    }

    @Test @DisplayName("dispatch: trailing semicolon stripped correctly")
    void dispatch_withSemicolon() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "disp3.fdb");
        shell.dispatch("CREATE TABLE t (id INT);");
        shell.dispatch("INSERT INTO t VALUES (1);");
        shell.dispatch("SELECT * FROM t;");
        String out = sw.toString();
        assertFalse(out.contains("ERROR"), "Should not error with semicolons in dispatch");
    }

    // =========================================================================
    // 3. EXPLAIN command
    // =========================================================================

    @Test @DisplayName("EXPLAIN SELECT without index shows SeqScan")
    void explain_seqScan() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "exp1.fdb");
        shell.dispatch("CREATE TABLE t (id INT, name TEXT)");
        shell.dispatch("EXPLAIN SELECT * FROM t WHERE id = 5");
        String out = sw.toString();
        assertTrue(out.contains("SeqScan"),
            "EXPLAIN without index must show SeqScan; got: " + out);
        assertTrue(out.contains("t"),
            "EXPLAIN must mention the table name; got: " + out);
    }

    @Test @DisplayName("EXPLAIN SELECT with index shows IndexLookup")
    void explain_indexLookup() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "exp2.fdb");
        shell.dispatch("CREATE TABLE t (id INT, name TEXT)");
        shell.dispatch("INSERT INTO t VALUES (1, 'a')");
        shell.executor().createIndex("t");
        shell.dispatch("EXPLAIN SELECT * FROM t WHERE id = 5");
        String out = sw.toString();
        assertTrue(out.contains("IndexLookup"),
            "EXPLAIN with index must show IndexLookup; got: " + out);
        assertTrue(out.contains("id"),
            "EXPLAIN must mention indexed column; got: " + out);
        assertTrue(out.contains("EQ"),
            "EXPLAIN must mention operator; got: " + out);
    }

    @Test @DisplayName("EXPLAIN DELETE shows a plan")
    void explain_delete() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "exp3.fdb");
        shell.dispatch("CREATE TABLE t (id INT)");
        shell.dispatch("EXPLAIN DELETE FROM t WHERE id = 1");
        String out = sw.toString();
        assertTrue(out.contains("SeqScan") || out.contains("IndexLookup"),
            "EXPLAIN DELETE should show a plan; got: " + out);
    }

    @Test @DisplayName("EXPLAIN UPDATE shows a plan")
    void explain_update() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "exp4.fdb");
        shell.dispatch("CREATE TABLE t (id INT, val TEXT)");
        shell.dispatch("EXPLAIN UPDATE t SET val = 'x' WHERE id = 1");
        String out = sw.toString();
        assertTrue(out.contains("SeqScan") || out.contains("IndexLookup"),
            "EXPLAIN UPDATE should show a plan; got: " + out);
    }

    @Test @DisplayName("EXPLAIN with unknown table shows ERROR")
    void explain_unknownTable() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "exp5.fdb");
        shell.dispatch("EXPLAIN SELECT * FROM ghost WHERE id = 1");
        String out = sw.toString();
        assertTrue(out.startsWith("ERROR"),
            "EXPLAIN on unknown table must print ERROR; got: " + out);
    }

    @Test @DisplayName("EXPLAIN with no following statement shows ERROR")
    void explain_noStatement() throws Exception {
        StringWriter sw = new StringWriter();
        ForgeDBShell shell = newShell(sw, "exp6.fdb");
        shell.dispatch("EXPLAIN");
        String out = sw.toString();
        assertTrue(out.startsWith("ERROR"),
            "EXPLAIN alone must print ERROR; got: " + out);
    }

    // =========================================================================
    // 4. Meta-commands
    // =========================================================================

    @Test @DisplayName("\\q exits the shell immediately")
    void meta_quit() throws Exception {
        String out = runFresh("meta1.fdb",
            "CREATE TABLE t (id INT);",
            "\\q",
            "INSERT INTO t VALUES (999)");
        assertFalse(out.contains("999"),
            "Commands after \\q must not be executed; got: " + out);
    }

    @Test @DisplayName("exit keyword exits the shell")
    void meta_exit() throws Exception {
        String out = runFresh("meta2.fdb",
            "CREATE TABLE t (id INT);",
            "exit",
            "INSERT INTO t VALUES (999)");
        assertFalse(out.contains("999"),
            "Commands after 'exit' must not be executed");
    }

    @Test @DisplayName("\\tables lists registered tables")
    void meta_tables() throws Exception {
        String out = runFresh("meta3.fdb",
            "CREATE TABLE users (id INT);",
            "CREATE TABLE products (id INT);",
            "\\tables",
            "\\q");
        assertTrue(out.contains("users"),    "\\tables must list 'users'");
        assertTrue(out.contains("products"), "\\tables must list 'products'");
    }

    @Test @DisplayName("\\tables on empty database shows (no tables)")
    void meta_tablesEmpty() throws Exception {
        String out = runFresh("meta4.fdb", "\\tables", "\\q");
        assertTrue(out.contains("no tables"),
            "\\tables on empty db must say no tables; got: " + out);
    }

    @Test @DisplayName("\\? prints help text")
    void meta_help() throws Exception {
        String out = runFresh("meta5.fdb", "\\?", "\\q");
        assertTrue(out.contains("CREATE TABLE"), "Help must mention CREATE TABLE");
        assertTrue(out.contains("SELECT"),       "Help must mention SELECT");
        assertTrue(out.contains("EXPLAIN"),      "Help must mention EXPLAIN");
    }

    // =========================================================================
    // 5. Error handling
    // =========================================================================

    @Test @DisplayName("Invalid SQL prints ERROR and shell continues")
    void error_invalidSql() throws Exception {
        String out = runFresh("err1.fdb",
            "SELEKT * FROM users;",          // syntax error
            "CREATE TABLE t (id INT);",
            "INSERT INTO t VALUES (1);",
            "SELECT * FROM t;",
            "\\q");
        assertTrue(out.contains("ERROR"), "Syntax error must print ERROR");
        // After the error the table was created and query executed
        assertTrue(out.contains("1 row"), "Shell must continue and execute after error");
    }

    @Test @DisplayName("SELECT from unknown table prints ERROR and continues")
    void error_unknownTable() throws Exception {
        String out = runFresh("err2.fdb",
            "SELECT * FROM no_such_table;",
            "CREATE TABLE t (id INT);",
            "SELECT * FROM t;",
            "\\q");
        assertTrue(out.contains("ERROR"),  "Unknown table must print ERROR");
        // The second SELECT on the freshly created (empty) table must succeed
        assertTrue(out.contains("0 rows"), "Shell must continue after error and show empty result");
    }

    @Test @DisplayName("Type mismatch in INSERT prints ERROR")
    void error_typeMismatch() throws Exception {
        String out = runFresh("err3.fdb",
            "CREATE TABLE t (id INT);",
            "INSERT INTO t VALUES ('not_an_int');",
            "\\q");
        assertTrue(out.contains("ERROR"), "Type mismatch must print ERROR");
    }

    // =========================================================================
    // 6. Multi-line statement accumulation
    // =========================================================================

    @Test @DisplayName("Multi-line statement accumulates until semicolon")
    void multiline_untilSemicolon() throws Exception {
        String out = runFresh("ml1.fdb",
            "CREATE TABLE t",
            "(id INT,",
            " name TEXT);",
            "INSERT INTO t VALUES (1, 'Alice');",
            "SELECT * FROM t;",
            "\\q");
        assertFalse(out.contains("ERROR"),
            "Multi-line CREATE TABLE must not error; got: " + out);
        assertTrue(out.contains("Alice"),
            "Multi-line statement must produce correct result");
    }

    @Test @DisplayName("Blank line submits accumulated buffer")
    void multiline_blankLineSubmits() throws Exception {
        String out = runFresh("ml2.fdb",
            "CREATE TABLE t (id INT)",
            "",
            "INSERT INTO t VALUES (42)",
            "",
            "SELECT * FROM t",
            "",
            "\\q");
        assertFalse(out.contains("ERROR"),
            "Blank-line submission must not error; got: " + out);
        assertTrue(out.contains("42"),
            "Value 42 must appear in SELECT result");
    }

    // =========================================================================
    // 7. Multiple statements in one session
    // =========================================================================

    @Test @DisplayName("Multiple DML statements in sequence produce correct results")
    void multi_dmlSequence() throws Exception {
        String out = runFresh("multi1.fdb",
            "CREATE TABLE users (id INT, name TEXT, age INT);",
            "INSERT INTO users VALUES (1, 'Alice', 21);",
            "INSERT INTO users VALUES (2, 'Bob', 24);",
            "INSERT INTO users VALUES (3, 'Carol', 30);",
            "SELECT * FROM users;",
            "DELETE FROM users WHERE id = 2;",
            "UPDATE users SET age = 99 WHERE id = 1;",
            "SELECT * FROM users;",
            "\\q");
        assertFalse(out.contains("ERROR"),
            "Multi-statement session must not error; got: " + out);
        assertTrue(out.contains("99"), "Updated value 99 must appear");
        // The second SELECT (after delete/update) must not contain Bob.
        // We identify the second SELECT's output as the content after the
        // "1 row(s) deleted" line.
        int deletePos = out.indexOf("row(s) deleted");
        assertTrue(deletePos >= 0, "Must have a deleted-rows message");
        String afterDelete = out.substring(deletePos);
        assertFalse(afterDelete.contains("Bob"),
            "Deleted row 'Bob' must not appear in second SELECT output");
    }

    // =========================================================================
    // 8. Complete CRUD end-to-end
    // =========================================================================

    @Test @DisplayName("Full CRUD workflow produces consistent results")
    void crud_fullWorkflow() throws Exception {
        String out = runFresh("crud.fdb",
            "CREATE TABLE products (id INT, name TEXT, price DOUBLE);",
            "INSERT INTO products VALUES (1, 'Widget', 9.99);",
            "INSERT INTO products VALUES (2, 'Gadget', 24.99);",
            "INSERT INTO products VALUES (3, 'Doohickey', 4.99);",
            "SELECT * FROM products;",
            "UPDATE products SET price = 19.99 WHERE id = 1;",
            "DELETE FROM products WHERE id = 3;",
            "SELECT * FROM products;",
            "\\q");
        assertFalse(out.contains("ERROR"),
            "CRUD workflow must not error; got: " + out);
        assertTrue(out.contains("Widget"), "Widget must appear");
        assertTrue(out.contains("Gadget"), "Gadget must appear");
        // The second SELECT (after delete) must not contain Doohickey.
        // Identify second SELECT region: after the "row(s) deleted" line.
        int deletePos = out.indexOf("row(s) deleted");
        assertTrue(deletePos >= 0, "Must have a deleted-rows message");
        String afterDelete = out.substring(deletePos);
        assertFalse(afterDelete.contains("Doohickey"),
            "Deleted Doohickey must not appear in second SELECT output");
    }
}
