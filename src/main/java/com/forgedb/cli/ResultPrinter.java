package com.forgedb.cli;

import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;
import com.forgedb.execution.QueryResult;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Formats a {@link QueryResult} for human-readable terminal output.
 *
 * SELECT results are rendered as a bordered ASCII table:
 * <pre>
 * +----+-------+-----+
 * | id | name  | age |
 * +----+-------+-----+
 * |  1 | Alice |  21 |
 * |  2 | Bob   |  24 |
 * +----+-------+-----+
 * 2 row(s)
 * </pre>
 *
 * Non-SELECT results (INSERT, UPDATE, DELETE, CREATE TABLE) print a
 * single status line such as:
 * <pre>
 * 1 row inserted
 * </pre>
 *
 * NULL values are rendered as the literal string {@code NULL}.
 *
 * Column widths are computed in a single pass over all rows before
 * any output is produced, so the table is always correctly aligned.
 *
 * This class is stateless; all methods are static.
 */
public final class ResultPrinter {

    private ResultPrinter() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Prints the result to {@code out}. Does not flush or close the writer.
     *
     * @param result the query result to display
     * @param out    the destination writer
     */
    public static void print(QueryResult result, PrintWriter out) {
        if (result.isResultSet()) {
            printTable(result, out);
        } else {
            out.println(result.message());
        }
    }

    // -------------------------------------------------------------------------
    // Table formatting
    // -------------------------------------------------------------------------

    private static void printTable(QueryResult result, PrintWriter out) {
        Schema      schema = result.schema();
        List<Tuple> rows   = result.rows();
        int         ncols  = schema.columnCount();

        // ---- Compute column widths -----------------------------------------------
        // Width = max(header length, max cell length across all rows)
        int[] widths = new int[ncols];
        for (int c = 0; c < ncols; c++) {
            widths[c] = schema.getColumn(c).name().length();
        }
        // Pre-render all cells to strings for width calculation
        List<String[]> cells = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            String[] rowCells = new String[ncols];
            for (int c = 0; c < ncols; c++) {
                rowCells[c] = cellString(row, c);
                widths[c]   = Math.max(widths[c], rowCells[c].length());
            }
            cells.add(rowCells);
        }

        // ---- Build separator line ------------------------------------------------
        String separator = buildSeparator(widths);

        // ---- Print header --------------------------------------------------------
        out.println(separator);
        out.print("|");
        for (int c = 0; c < ncols; c++) {
            // Left-align column headers
            out.print(" " + padRight(schema.getColumn(c).name(), widths[c]) + " |");
        }
        out.println();
        out.println(separator);

        // ---- Print rows ---------------------------------------------------------
        for (String[] rowCells : cells) {
            out.print("|");
            for (int c = 0; c < ncols; c++) {
                String cell  = rowCells[c];
                boolean isNum = isNumeric(schema.getColumn(c).type().name());
                // Right-align numbers, left-align text/boolean/null
                String padded = isNum
                    ? padLeft (cell, widths[c])
                    : padRight(cell, widths[c]);
                out.print(" " + padded + " |");
            }
            out.println();
        }

        // ---- Footer -------------------------------------------------------------
        out.println(separator);
        int n = rows.size();
        out.println(n + " row" + (n == 1 ? "" : "s"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Converts the cell at column {@code col} to its display string. */
    private static String cellString(Tuple row, int col) {
        if (row.isNull(col)) return "NULL";
        Object v = row.get(col);
        return v.toString();
    }

    /**
     * Returns true for type names that should be right-aligned in the table
     * (INT, LONG, DOUBLE).
     */
    private static boolean isNumeric(String typeName) {
        return typeName.equals("INT") || typeName.equals("LONG")
            || typeName.equals("DOUBLE");
    }

    private static String buildSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            sb.append("-".repeat(w + 2)).append("+");
        }
        return sb.toString();
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private static String padLeft(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }
}
