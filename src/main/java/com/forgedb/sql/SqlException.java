package com.forgedb.sql;

/**
 * Thrown when the SQL lexer or parser encounters invalid input.
 *
 * SqlException is a checked exception, consistent with the rest of ForgeDB's
 * error handling. The message always includes position information
 * (line and column) so the user can find the offending text.
 *
 * Higher-level components (CLI, query engine) catch SqlException and present
 * it to the user as a syntax error rather than a system error.
 */
public class SqlException extends Exception {

    private final int line;
    private final int col;

    public SqlException(String message, int line, int col) {
        super(message + " [line " + line + ", col " + col + "]");
        this.line = line;
        this.col  = col;
    }

    public SqlException(String message, Token nearToken) {
        this(message + " near '" + nearToken.value() + "'",
             nearToken.line(), nearToken.col());
    }

    /** 1-based line number where the error was detected. */
    public int line() { return line; }

    /** 1-based column number where the error was detected. */
    public int col()  { return col; }
}
