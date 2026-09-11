package com.forgedb.sql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tokenises a SQL string into a list of {@link Token}s.
 *
 * <h2>Design</h2>
 * The lexer is a single-pass, hand-written scanner. It maintains a cursor
 * position (index into the source string) plus the current line and column
 * for error reporting. The public entry point is {@link #tokenize()}, which
 * returns the complete token list including the final {@link TokenType#EOF}
 * token.
 *
 * <h2>Rules (in priority order)</h2>
 * <ol>
 *   <li>Whitespace (spaces, tabs, newlines, carriage returns) → skipped</li>
 *   <li>{@code --} single-line comments → skipped to end of line</li>
 *   <li>Single-quoted string literals → {@link TokenType#STRING_LITERAL}</li>
 *   <li>Digits (and an optional leading {@code -}) → INTEGER_LITERAL or
 *       DOUBLE_LITERAL</li>
 *   <li>Letters / underscore → keyword (case-insensitive) or
 *       {@link TokenType#IDENT}</li>
 *   <li>Two-character operators ({@code <=}, {@code >=}, {@code <>}) →
 *       single token</li>
 *   <li>Single-character punctuation and operators</li>
 * </ol>
 *
 * <h2>Negative numbers</h2>
 * A {@code -} immediately followed by a digit (with no space) is scanned as
 * part of the number literal. A {@code -} followed by a non-digit is emitted
 * as a {@link TokenType#MINUS} operator token.
 *
 * <h2>Case insensitivity</h2>
 * SQL keywords are matched case-insensitively. Identifiers and string literal
 * values preserve their original case.
 *
 * <h2>Thread safety</h2>
 * Lexer is NOT thread-safe. Create a new instance per tokenisation call.
 */
public final class Lexer {

    // -------------------------------------------------------------------------
    // Keyword table
    // -------------------------------------------------------------------------

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("and",      TokenType.AND);
        KEYWORDS.put("as",       TokenType.AS);
        KEYWORDS.put("begin",    TokenType.BEGIN);
        KEYWORDS.put("boolean",  TokenType.BOOLEAN);
        KEYWORDS.put("by",       TokenType.BY);
        KEYWORDS.put("commit",   TokenType.COMMIT);
        KEYWORDS.put("create",   TokenType.CREATE);
        KEYWORDS.put("delete",   TokenType.DELETE);
        KEYWORDS.put("double",   TokenType.DOUBLE);
        KEYWORDS.put("drop",     TokenType.DROP);
        KEYWORDS.put("false",    TokenType.FALSE);
        KEYWORDS.put("from",     TokenType.FROM);
        KEYWORDS.put("insert",   TokenType.INSERT);
        KEYWORDS.put("int",      TokenType.INT);
        KEYWORDS.put("into",     TokenType.INTO);
        KEYWORDS.put("is",       TokenType.IS);
        KEYWORDS.put("long",     TokenType.LONG);
        KEYWORDS.put("not",      TokenType.NOT);
        KEYWORDS.put("null",     TokenType.NULL);
        KEYWORDS.put("or",       TokenType.OR);
        KEYWORDS.put("order",    TokenType.ORDER);
        KEYWORDS.put("rollback", TokenType.ROLLBACK);
        KEYWORDS.put("select",   TokenType.SELECT);
        KEYWORDS.put("set",      TokenType.SET);
        KEYWORDS.put("table",    TokenType.TABLE);
        KEYWORDS.put("text",     TokenType.TEXT);
        KEYWORDS.put("true",     TokenType.TRUE);
        KEYWORDS.put("update",   TokenType.UPDATE);
        KEYWORDS.put("values",   TokenType.VALUES);
        KEYWORDS.put("where",    TokenType.WHERE);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final String source;
    private int    pos  = 0;   // current character index
    private int    line = 1;   // 1-based
    private int    col  = 1;   // 1-based

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public Lexer(String source) {
        if (source == null) throw new NullPointerException("SQL source must not be null");
        this.source = source;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scans the entire source string and returns the token list.
     * The last token is always {@link TokenType#EOF}.
     *
     * @throws SqlException if the input contains an unrecognised character or
     *         an unterminated string literal
     */
    public List<Token> tokenize() throws SqlException {
        List<Token> tokens = new ArrayList<>();

        while (!atEnd()) {
            skipWhitespaceAndComments();
            if (atEnd()) break;

            Token t = nextToken();
            tokens.add(t);
        }

        tokens.add(new Token(TokenType.EOF, "", line, col));
        return tokens;
    }

    // -------------------------------------------------------------------------
    // Core scanning loop
    // -------------------------------------------------------------------------

    private Token nextToken() throws SqlException {
        int startLine = line;
        int startCol  = col;
        char c = peek();

        // String literal
        if (c == '\'') {
            return scanString(startLine, startCol);
        }

        // Number: digit, or '-' immediately followed by digit
        if (Character.isDigit(c) ||
            (c == '-' && pos + 1 < source.length() && Character.isDigit(source.charAt(pos + 1)))) {
            return scanNumber(startLine, startCol);
        }

        // Word: keyword or identifier
        if (Character.isLetter(c) || c == '_') {
            return scanWord(startLine, startCol);
        }

        // Two-character operators
        if (c == '<') {
            advance();
            if (!atEnd() && peek() == '=') { advance(); return tok(TokenType.LTE,  "<=", startLine, startCol); }
            if (!atEnd() && peek() == '>') { advance(); return tok(TokenType.NEQ,  "<>", startLine, startCol); }
            return tok(TokenType.LT, "<", startLine, startCol);
        }
        if (c == '>') {
            advance();
            if (!atEnd() && peek() == '=') { advance(); return tok(TokenType.GTE, ">=", startLine, startCol); }
            return tok(TokenType.GT, ">", startLine, startCol);
        }

        // Single-character tokens
        advance();
        return switch (c) {
            case '=' -> tok(TokenType.EQ,        "=", startLine, startCol);
            case '+' -> tok(TokenType.PLUS,      "+", startLine, startCol);
            case '-' -> tok(TokenType.MINUS,     "-", startLine, startCol);
            case ',' -> tok(TokenType.COMMA,     ",", startLine, startCol);
            case ';' -> tok(TokenType.SEMICOLON, ";", startLine, startCol);
            case '(' -> tok(TokenType.LPAREN,    "(", startLine, startCol);
            case ')' -> tok(TokenType.RPAREN,    ")", startLine, startCol);
            case '*' -> tok(TokenType.STAR,      "*", startLine, startCol);
            case '.' -> tok(TokenType.DOT,       ".", startLine, startCol);
            default  -> throw new SqlException(
                            "Unexpected character '" + c + "'", line, col - 1);
        };
    }

    // -------------------------------------------------------------------------
    // Individual scanners
    // -------------------------------------------------------------------------

    /** Scans a single-quoted string literal. Handles '' as an escaped quote. */
    private Token scanString(int startLine, int startCol) throws SqlException {
        StringBuilder sb = new StringBuilder();
        advance(); // consume opening quote
        while (!atEnd()) {
            char c = peek();
            if (c == '\'') {
                advance(); // consume closing quote
                // Check for escaped quote '' (two consecutive single quotes)
                if (!atEnd() && peek() == '\'') {
                    advance();
                    sb.append('\'');
                    continue;
                }
                // End of string
                return tok(TokenType.STRING_LITERAL, sb.toString(), startLine, startCol);
            }
            if (c == '\n') { line++; col = 1; }
            sb.append(c);
            advance();
        }
        throw new SqlException("Unterminated string literal", startLine, startCol);
    }

    /** Scans an integer or double literal (optionally preceded by '-'). */
    private Token scanNumber(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        if (peek() == '-') { sb.append('-'); advance(); }
        while (!atEnd() && Character.isDigit(peek())) {
            sb.append(peek()); advance();
        }
        // Check for decimal point
        if (!atEnd() && peek() == '.') {
            sb.append('.'); advance();
            while (!atEnd() && Character.isDigit(peek())) {
                sb.append(peek()); advance();
            }
            return tok(TokenType.DOUBLE_LITERAL, sb.toString(), startLine, startCol);
        }
        return tok(TokenType.INTEGER_LITERAL, sb.toString(), startLine, startCol);
    }

    /** Scans a keyword or identifier (letters, digits, underscore). */
    private Token scanWord(int startLine, int startCol) {
        StringBuilder sb = new StringBuilder();
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            sb.append(peek()); advance();
        }
        String word = sb.toString();
        TokenType kwType = KEYWORDS.get(word.toLowerCase());
        if (kwType != null) {
            return tok(kwType, word, startLine, startCol);
        }
        return tok(TokenType.IDENT, word, startLine, startCol);
    }

    // -------------------------------------------------------------------------
    // Whitespace and comment skipping
    // -------------------------------------------------------------------------

    private void skipWhitespaceAndComments() {
        while (!atEnd()) {
            char c = peek();
            if (c == '\n') {
                advance(); line++; col = 1;
            } else if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '-' && pos + 1 < source.length()
                       && source.charAt(pos + 1) == '-') {
                // Single-line comment: skip to end of line
                while (!atEnd() && peek() != '\n') advance();
            } else {
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Character helpers
    // -------------------------------------------------------------------------

    private boolean atEnd() { return pos >= source.length(); }

    private char peek() { return source.charAt(pos); }

    private void advance() {
        pos++;
        col++;
    }

    private Token tok(TokenType type, String value, int line, int col) {
        return new Token(type, value, line, col);
    }
}
