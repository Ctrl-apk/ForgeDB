package com.forgedb.cli;

import com.forgedb.execution.ExecutionException;
import com.forgedb.execution.Executor;
import com.forgedb.execution.QueryResult;
import com.forgedb.planner.QueryPlan;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DiskManagerImpl;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * ForgeDB interactive SQL shell (REPL).
 *
 * <h2>Usage</h2>
 * <pre>
 *   # Interactive mode (reads from stdin, writes to stdout)
 *   java -jar forgedb.jar
 *   java -jar forgedb.jar mydb.fdb
 *
 *   # Batch mode (read SQL from a file, print results, exit)
 *   java -jar forgedb.jar --file script.sql
 *   java -jar forgedb.jar mydb.fdb --file script.sql
 * </pre>
 *
 * <h2>Supported meta-commands</h2>
 * <pre>
 *   \q   or   exit   or   quit   — exit the shell
 *   \?   or   help              — show built-in help
 *   \tables                    — list all registered tables
 * </pre>
 *
 * <h2>SQL support</h2>
 * All SQL supported by the underlying engine:
 *   CREATE TABLE, INSERT INTO, SELECT, DELETE, UPDATE, EXPLAIN SELECT/DELETE/UPDATE
 *
 * <h2>EXPLAIN</h2>
 * Prefixing any SELECT, DELETE, or UPDATE with {@code EXPLAIN} prints the
 * query plan without executing the statement:
 * <pre>
 *   ForgeDB> EXPLAIN SELECT * FROM users WHERE id = 42;
 *   IndexLookup [ table=users, index=id, op=EQ, key=42, residual=none ]
 * </pre>
 *
 * <h2>Multi-line statements</h2>
 * A statement is submitted when a semicolon is encountered or when the user
 * presses Enter on a blank line. Lines are accumulated until either condition
 * is met.
 *
 * <h2>Error handling</h2>
 * Syntax errors and execution errors are printed with a prefixed {@code ERROR:}
 * label. The shell continues running after any error.
 *
 * <h2>Design</h2>
 * The shell depends only on {@link Executor} (the public API); it never
 * imports storage, B+ tree, or parser classes directly. The {@link Executor}
 * handles parsing internally.
 *
 * EXPLAIN is intercepted at the shell level by stripping the {@code EXPLAIN}
 * keyword and calling {@link Executor#explain(String)} instead of
 * {@link Executor#execute(String)}.
 */
public final class ForgeDBShell {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final String PROMPT        = "ForgeDB> ";
    private static final String PROMPT_CONT   = "      -> ";   // continuation line
    private static final String DEFAULT_DB    = "forgedb.fdb";
    private static final int    POOL_CAPACITY = 256;

    private static final String BANNER =
        "╔══════════════════════════════════════╗\n" +
        "║         ForgeDB v0.1.0-SNAPSHOT      ║\n" +
        "║  Type SQL statements and press Enter  ║\n" +
        "║  \\q or EXIT to quit  |  \\? for help   ║\n" +
        "╚══════════════════════════════════════╝";

    private static final String HELP =
        "ForgeDB SQL Shell — supported commands:\n" +
        "\n" +
        "  SQL Statements:\n" +
        "    CREATE TABLE name (col TYPE, ...);\n" +
        "    INSERT INTO name VALUES (v1, v2, ...);\n" +
        "    SELECT [* | col, ...] FROM name [WHERE expr];\n" +
        "    UPDATE name SET col=val [WHERE expr];\n" +
        "    DELETE FROM name [WHERE expr];\n" +
        "\n" +
        "  EXPLAIN:\n" +
        "    EXPLAIN SELECT * FROM name WHERE col = val;\n" +
        "    EXPLAIN DELETE FROM name WHERE col = val;\n" +
        "    EXPLAIN UPDATE name SET col=val WHERE col2 = val;\n" +
        "\n" +
        "  Supported types: INT  LONG  BOOLEAN  DOUBLE  TEXT\n" +
        "\n" +
        "  Meta-commands:\n" +
        "    \\q  exit  quit   — exit the shell\n" +
        "    \\?  help         — this message\n" +
        "    \\tables          — list all tables in the current database\n" +
        "\n" +
        "  Multi-line: keep typing until ';' ends the statement.\n" +
        "  Blank line also submits the current buffer.\n";

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Executor    executor;
    private final PrintWriter out;
    private final boolean     interactive;

    // -------------------------------------------------------------------------
    // Construction (package-private — main() is the public entry point)
    // -------------------------------------------------------------------------

    ForgeDBShell(Executor executor, PrintWriter out, boolean interactive) {
        this.executor    = executor;
        this.out         = out;
        this.interactive = interactive;
    }

    /** Package-private accessor for tests. */
    Executor executor() { return executor; }

    // -------------------------------------------------------------------------
    // main entry point
    // -------------------------------------------------------------------------

    /**
     * Launches the ForgeDB shell.
     *
     * <pre>
     *   Usage:
     *     java -jar forgedb.jar [dbfile]
     *     java -jar forgedb.jar [dbfile] --file script.sql
     * </pre>
     *
     * Exit codes:
     *   0  — normal exit
     *   1  — startup error (cannot open database file)
     */
    public static void main(String[] args) {
        String  dbFile   = DEFAULT_DB;
        String  sqlFile  = null;

        // Parse CLI arguments
        for (int i = 0; i < args.length; i++) {
            if ("--file".equals(args[i]) || "-f".equals(args[i])) {
                if (i + 1 < args.length) sqlFile = args[++i];
            } else if (!args[i].startsWith("-")) {
                dbFile = args[i];
            }
        }

        // Open database
        Executor exec;
        try {
            BufferPool bp = new BufferPool(new DiskManagerImpl(dbFile), POOL_CAPACITY);
            exec = new Executor(bp);
        } catch (Exception e) {
            System.err.println("ERROR: Cannot open database '" + dbFile + "': " + e.getMessage());
            System.exit(1);
            return;
        }

        PrintWriter pw = new PrintWriter(new OutputStreamWriter(System.out), true);

        if (sqlFile != null) {
            // Batch mode: read from file
            runBatchFile(exec, sqlFile, pw);
        } else {
            // Interactive mode
            boolean tty = System.console() != null;
            pw.println(BANNER);
            pw.println("Database: " + dbFile);
            pw.flush();
            ForgeDBShell shell = new ForgeDBShell(exec, pw, tty);
            shell.runInteractive(new BufferedReader(new InputStreamReader(System.in)));
        }

        try { exec.close(); } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Interactive loop
    // -------------------------------------------------------------------------

    /**
     * Runs the interactive read-eval-print loop until EOF or an exit command.
     *
     * @param reader the input source (stdin or a test-injected reader)
     */
    void runInteractive(BufferedReader reader) {
        List<String> buffer = new ArrayList<>();

        while (true) {
            // Print prompt
            if (interactive) {
                out.print(buffer.isEmpty() ? PROMPT : PROMPT_CONT);
                out.flush();
            }

            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                out.println("ERROR: I/O error reading input: " + e.getMessage());
                break;
            }

            // EOF
            if (line == null) break;

            String trimmed = line.trim();

            // Blank line submits the buffer if non-empty
            if (trimmed.isEmpty()) {
                if (!buffer.isEmpty()) {
                    String sql = String.join(" ", buffer).trim();
                    buffer.clear();
                    if (!sql.isEmpty()) dispatch(sql);
                }
                continue;
            }

            // Meta-commands (only accepted on a fresh line, not inside a statement)
            if (buffer.isEmpty()) {
                String lower = trimmed.toLowerCase();
                if (lower.equals("\\q") || lower.equals("exit") || lower.equals("quit")) {
                    if (interactive) out.println("Bye.");
                    break;
                }
                if (lower.equals("\\?") || lower.equals("help")) {
                    out.print(HELP);
                    out.flush();
                    continue;
                }
                if (lower.equals("\\tables")) {
                    printTables();
                    continue;
                }
            }

            // Accumulate the line into the buffer
            buffer.add(trimmed);

            // Submit when the buffer contains a semicolon-terminated statement
            String joined = String.join(" ", buffer);
            if (joined.contains(";")) {
                // Split on semicolons: each segment ending with ; is a statement
                String[] parts = joined.split(";", -1);
                for (int i = 0; i < parts.length - 1; i++) {
                    String stmt = parts[i].trim();
                    if (!stmt.isEmpty()) dispatch(stmt);
                }
                // Remaining text after last semicolon starts the next buffer
                buffer.clear();
                String remainder = parts[parts.length - 1].trim();
                if (!remainder.isEmpty()) buffer.add(remainder);
            }
        }

        out.flush();
    }

    // -------------------------------------------------------------------------
    // Batch mode
    // -------------------------------------------------------------------------

    private static void runBatchFile(Executor exec, String sqlFile, PrintWriter pw) {
        Path path = Paths.get(sqlFile);
        if (!Files.exists(path)) {
            pw.println("ERROR: File not found: " + sqlFile);
            return;
        }
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            pw.println("ERROR: Cannot read file '" + sqlFile + "': " + e.getMessage());
            return;
        }
        ForgeDBShell shell = new ForgeDBShell(exec, pw, false);
        // Execute all statements in the file
        String[] statements = content.split(";");
        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                shell.dispatch(trimmed);
            }
        }
        pw.flush();
    }

    // -------------------------------------------------------------------------
    // Statement dispatch
    // -------------------------------------------------------------------------

    /**
     * Dispatches a single SQL statement (without trailing semicolon).
     * Handles EXPLAIN, regular SQL, and prints results or errors.
     */
    void dispatch(String sql) {
        String trimmed = sql.trim();
        if (trimmed.isEmpty()) return;

        // EXPLAIN: strip keyword and call explain() instead of execute()
        String upper = trimmed.toUpperCase();
        if (upper.startsWith("EXPLAIN")) {
            String rest = trimmed.substring("EXPLAIN".length()).trim();
            if (rest.isEmpty()) {
                out.println("ERROR: EXPLAIN requires a statement (SELECT, DELETE, or UPDATE).");
                out.flush();
                return;
            }
            runExplain(rest);
            return;
        }

        // Normal execution
        try {
            QueryResult result = executor.execute(trimmed);
            ResultPrinter.print(result, out);
        } catch (ExecutionException e) {
            out.println("ERROR: " + e.getMessage());
        }
        out.flush();
    }

    /** Runs EXPLAIN and prints the plan. */
    private void runExplain(String sql) {
        try {
            QueryPlan plan = executor.explain(sql);
            out.println(plan.explain());
        } catch (ExecutionException e) {
            out.println("ERROR: " + e.getMessage());
        }
        out.flush();
    }

    /** Prints all registered table names. */
    private void printTables() {
        var names = executor.catalog().tableNames();
        if (names.isEmpty()) {
            out.println("(no tables)");
        } else {
            out.println("Tables:");
            names.stream().sorted().forEach(n -> out.println("  " + n));
        }
        out.flush();
    }
}
