package com.forgedb.sql;

/**
 * A single lexical unit produced by the {@link Lexer}.
 *
 * Every token carries:
 *   type   — what kind of token this is
 *   value  — the raw source text of the token (the lexeme)
 *   line   — 1-based line number where the token starts
 *   col    — 1-based column number where the token starts
 *
 * The value is always the original source text (e.g. a string literal retains
 * its surrounding quotes; the parsed content is computed lazily by the Parser).
 *
 * Token is immutable.
 */
public final class Token {

    private final TokenType type;
    private final String    value;
    private final int       line;
    private final int       col;

    public Token(TokenType type, String value, int line, int col) {
        this.type  = type;
        this.value = value;
        this.line  = line;
        this.col   = col;
    }

    /** The category of this token. */
    public TokenType type()  { return type; }

    /** The raw source text that produced this token. */
    public String    value() { return value; }

    /** 1-based line number in the source where this token begins. */
    public int       line()  { return line; }

    /** 1-based column number in the source where this token begins. */
    public int       col()   { return col; }

    /**
     * Returns a human-readable position string such as {@code [line 3, col 5]}.
     * Used in error messages.
     */
    public String position() {
        return "[line " + line + ", col " + col + "]";
    }

    @Override
    public String toString() {
        return type + "(" + value + ")" + position();
    }
}
