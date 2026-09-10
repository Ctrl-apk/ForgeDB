package com.forgedb.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the SQL Lexer.
 *
 * Categories:
 *   1. Whitespace / comments
 *   2. Keywords (case-insensitive)
 *   3. Identifiers
 *   4. Integer literals
 *   5. Double literals
 *   6. String literals
 *   7. Operators
 *   8. Punctuation
 *   9. Position tracking
 *  10. Error cases
 *  11. Real SQL snippets
 */
class LexerTest {

    private List<Token> lex(String sql) throws SqlException {
        return new Lexer(sql).tokenize();
    }

    /** Returns the token at position i (0-based), excluding EOF. */
    private Token tok(List<Token> tokens, int i) {
        return tokens.get(i);
    }

    /** Asserts last token is EOF. */
    private void assertEof(List<Token> tokens) {
        assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type());
    }

    // =========================================================================
    // 1. Whitespace and comments
    // =========================================================================

    @Test @DisplayName("Empty input produces only EOF")
    void whitespace_emptyInput() throws SqlException {
        List<Token> t = lex("");
        assertEquals(1, t.size());
        assertEquals(TokenType.EOF, t.get(0).type());
    }

    @Test @DisplayName("Whitespace-only input produces only EOF")
    void whitespace_onlySpaces() throws SqlException {
        List<Token> t = lex("   \t\n  ");
        assertEquals(1, t.size());
        assertEof(t);
    }

    @Test @DisplayName("Single-line comment is skipped")
    void whitespace_singleLineComment() throws SqlException {
        List<Token> t = lex("-- this is a comment\nSELECT");
        assertEquals(2, t.size()); // SELECT + EOF
        assertEquals(TokenType.SELECT, t.get(0).type());
    }

    @Test @DisplayName("Comment after token is skipped")
    void whitespace_commentAfterToken() throws SqlException {
        List<Token> t = lex("SELECT -- comment");
        assertEquals(2, t.size());
        assertEquals(TokenType.SELECT, t.get(0).type());
    }

    // =========================================================================
    // 2. Keywords (case-insensitive)
    // =========================================================================

    @Test @DisplayName("Keywords matched case-insensitively")
    void keywords_caseInsensitive() throws SqlException {
        String[] variants = {"SELECT", "select", "Select", "sElEcT"};
        for (String v : variants) {
            List<Token> t = lex(v);
            assertEquals(TokenType.SELECT, t.get(0).type(),
                "Expected SELECT for input: " + v);
        }
    }

    @Test @DisplayName("All statement keywords are recognised")
    void keywords_allStatementKeywords() throws SqlException {
        TokenType[] expected = {
            TokenType.CREATE, TokenType.TABLE,
            TokenType.INSERT, TokenType.INTO, TokenType.VALUES,
            TokenType.SELECT, TokenType.FROM, TokenType.WHERE,
            TokenType.DELETE, TokenType.UPDATE, TokenType.SET
        };
        String[] words = {
            "CREATE", "TABLE",
            "INSERT", "INTO", "VALUES",
            "SELECT", "FROM", "WHERE",
            "DELETE", "UPDATE", "SET"
        };
        for (int i = 0; i < words.length; i++) {
            List<Token> t = lex(words[i]);
            assertEquals(expected[i], t.get(0).type(), "Failed for: " + words[i]);
        }
    }

    @Test @DisplayName("Type keywords are recognised")
    void keywords_typeKeywords() throws SqlException {
        String[] types = {"INT", "LONG", "BOOLEAN", "DOUBLE", "TEXT"};
        TokenType[] expected = {
            TokenType.INT, TokenType.LONG, TokenType.BOOLEAN,
            TokenType.DOUBLE, TokenType.TEXT
        };
        for (int i = 0; i < types.length; i++) {
            List<Token> t = lex(types[i]);
            assertEquals(expected[i], t.get(0).type());
        }
    }

    @Test @DisplayName("Logical keywords AND, OR, NOT are recognised")
    void keywords_logicalKeywords() throws SqlException {
        assertEquals(TokenType.AND, lex("AND").get(0).type());
        assertEquals(TokenType.OR,  lex("OR").get(0).type());
        assertEquals(TokenType.NOT, lex("NOT").get(0).type());
    }

    @Test @DisplayName("NULL, TRUE, FALSE keywords are recognised")
    void keywords_nullTrueFalse() throws SqlException {
        assertEquals(TokenType.NULL,  lex("NULL").get(0).type());
        assertEquals(TokenType.TRUE,  lex("TRUE").get(0).type());
        assertEquals(TokenType.FALSE, lex("FALSE").get(0).type());
    }

    // =========================================================================
    // 3. Identifiers
    // =========================================================================

    @Test @DisplayName("Plain identifiers are tokenised as IDENT")
    void ident_plain() throws SqlException {
        List<Token> t = lex("users");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals("users", t.get(0).value());
    }

    @Test @DisplayName("Identifiers with underscores and digits are tokenised")
    void ident_underscoreAndDigits() throws SqlException {
        List<Token> t = lex("my_table_2");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals("my_table_2", t.get(0).value());
    }

    @Test @DisplayName("Identifier value preserves original case")
    void ident_casePreserved() throws SqlException {
        List<Token> t = lex("MyTable");
        assertEquals(TokenType.IDENT, t.get(0).type());
        assertEquals("MyTable", t.get(0).value());
    }

    // =========================================================================
    // 4. Integer literals
    // =========================================================================

    @Test @DisplayName("Positive integer literal")
    void intLiteral_positive() throws SqlException {
        List<Token> t = lex("42");
        assertEquals(TokenType.INTEGER_LITERAL, t.get(0).type());
        assertEquals("42", t.get(0).value());
    }

    @Test @DisplayName("Zero integer literal")
    void intLiteral_zero() throws SqlException {
        assertEquals(TokenType.INTEGER_LITERAL, lex("0").get(0).type());
    }

    @Test @DisplayName("Negative integer literal (no space)")
    void intLiteral_negative() throws SqlException {
        // '-' immediately followed by digit → scanned as single number token
        List<Token> t = lex("-5");
        assertEquals(TokenType.INTEGER_LITERAL, t.get(0).type());
        assertEquals("-5", t.get(0).value());
    }

    @Test @DisplayName("Minus with space produces MINUS token then integer")
    void intLiteral_minusWithSpace() throws SqlException {
        List<Token> t = lex("- 5");
        assertEquals(TokenType.MINUS,           t.get(0).type());
        assertEquals(TokenType.INTEGER_LITERAL, t.get(1).type());
    }

    // =========================================================================
    // 5. Double literals
    // =========================================================================

    @Test @DisplayName("Positive double literal")
    void doubleLiteral_positive() throws SqlException {
        List<Token> t = lex("3.14");
        assertEquals(TokenType.DOUBLE_LITERAL, t.get(0).type());
        assertEquals("3.14", t.get(0).value());
    }

    @Test @DisplayName("Negative double literal (no space)")
    void doubleLiteral_negative() throws SqlException {
        List<Token> t = lex("-0.5");
        assertEquals(TokenType.DOUBLE_LITERAL, t.get(0).type());
        assertEquals("-0.5", t.get(0).value());
    }

    @Test @DisplayName("Double literal with leading zero")
    void doubleLiteral_leadingZero() throws SqlException {
        List<Token> t = lex("0.001");
        assertEquals(TokenType.DOUBLE_LITERAL, t.get(0).type());
    }

    // =========================================================================
    // 6. String literals
    // =========================================================================

    @Test @DisplayName("Simple string literal")
    void stringLiteral_simple() throws SqlException {
        List<Token> t = lex("'Alice'");
        assertEquals(TokenType.STRING_LITERAL, t.get(0).type());
        assertEquals("Alice", t.get(0).value()); // quotes stripped
    }

    @Test @DisplayName("Empty string literal")
    void stringLiteral_empty() throws SqlException {
        List<Token> t = lex("''");
        assertEquals(TokenType.STRING_LITERAL, t.get(0).type());
        assertEquals("", t.get(0).value());
    }

    @Test @DisplayName("String literal with escaped single quote ('')")
    void stringLiteral_escapedQuote() throws SqlException {
        List<Token> t = lex("'it''s'");
        assertEquals(TokenType.STRING_LITERAL, t.get(0).type());
        assertEquals("it's", t.get(0).value());
    }

    @Test @DisplayName("String literal with spaces inside")
    void stringLiteral_withSpaces() throws SqlException {
        List<Token> t = lex("'hello world'");
        assertEquals("hello world", t.get(0).value());
    }

    @Test @DisplayName("String literal with unicode")
    void stringLiteral_unicode() throws SqlException {
        List<Token> t = lex("'café 日本'");
        assertEquals("café 日本", t.get(0).value());
    }

    // =========================================================================
    // 7. Operators
    // =========================================================================

    @Test @DisplayName("All comparison operators are tokenised")
    void operators_comparison() throws SqlException {
        String[] ops    = {"=", "<>", "<", "<=", ">", ">="};
        TokenType[] exp = {
            TokenType.EQ, TokenType.NEQ,
            TokenType.LT, TokenType.LTE,
            TokenType.GT, TokenType.GTE
        };
        for (int i = 0; i < ops.length; i++) {
            List<Token> t = lex(ops[i]);
            assertEquals(exp[i], t.get(0).type(), "Failed for operator: " + ops[i]);
        }
    }

    @Test @DisplayName("Two-character operators are not split")
    void operators_twoCharNotSplit() throws SqlException {
        assertEquals(TokenType.LTE, lex("<=").get(0).type());
        assertEquals(TokenType.GTE, lex(">=").get(0).type());
        assertEquals(TokenType.NEQ, lex("<>").get(0).type());
        // Verify they produce exactly 1 operator token + EOF
        assertEquals(2, lex("<=").size());
        assertEquals(2, lex("<>").size());
    }

    // =========================================================================
    // 8. Punctuation
    // =========================================================================

    @Test @DisplayName("Punctuation tokens are recognised")
    void punct_allTypes() throws SqlException {
        assertEquals(TokenType.COMMA,     lex(",").get(0).type());
        assertEquals(TokenType.SEMICOLON, lex(";").get(0).type());
        assertEquals(TokenType.LPAREN,    lex("(").get(0).type());
        assertEquals(TokenType.RPAREN,    lex(")").get(0).type());
        assertEquals(TokenType.STAR,      lex("*").get(0).type());
        assertEquals(TokenType.DOT,       lex(".").get(0).type());
    }

    // =========================================================================
    // 9. Position tracking
    // =========================================================================

    @Test @DisplayName("First token on line 1 has line=1")
    void position_firstToken() throws SqlException {
        Token t = lex("SELECT").get(0);
        assertEquals(1, t.line());
        assertEquals(1, t.col());
    }

    @Test @DisplayName("Token after newline has correct line number")
    void position_afterNewline() throws SqlException {
        List<Token> t = lex("SELECT\nFROM");
        assertEquals(1, t.get(0).line()); // SELECT on line 1
        assertEquals(2, t.get(1).line()); // FROM on line 2
        assertEquals(1, t.get(1).col());
    }

    @Test @DisplayName("Column tracking is correct within a line")
    void position_columnTracking() throws SqlException {
        List<Token> t = lex("SELECT *");
        assertEquals(1, t.get(0).col()); // SELECT starts at col 1
        assertEquals(8, t.get(1).col()); // * starts at col 8
    }

    // =========================================================================
    // 10. Error cases
    // =========================================================================

    @Test @DisplayName("Unterminated string literal throws SqlException")
    void error_unterminatedString() {
        assertThrows(SqlException.class, () -> lex("'unterminated"));
    }

    @Test @DisplayName("Unknown character throws SqlException")
    void error_unknownCharacter() {
        assertThrows(SqlException.class, () -> lex("@"));
        assertThrows(SqlException.class, () -> lex("#"));
        assertThrows(SqlException.class, () -> lex("$"));
    }

    @Test @DisplayName("SqlException contains position info")
    void error_positionInMessage() {
        SqlException ex = assertThrows(SqlException.class, () -> lex("@"));
        assertTrue(ex.getMessage().contains("line"),
            "Error message should include line info");
    }

    // =========================================================================
    // 11. Real SQL snippets
    // =========================================================================

    @Test @DisplayName("CREATE TABLE tokenises correctly")
    void real_createTable() throws SqlException {
        List<Token> t = lex("CREATE TABLE users (id INT, name TEXT, age INT);");
        assertEof(t);
        assertEquals(TokenType.CREATE, t.get(0).type());
        assertEquals(TokenType.TABLE,  t.get(1).type());
        assertEquals(TokenType.IDENT,  t.get(2).type());
        assertEquals("users",          t.get(2).value());
        assertEquals(TokenType.LPAREN, t.get(3).type());
    }

    @Test @DisplayName("INSERT INTO tokenises correctly")
    void real_insert() throws SqlException {
        List<Token> t = lex("INSERT INTO users VALUES (1, 'Alice', 20);");
        assertEquals(TokenType.INSERT, t.get(0).type());
        assertEquals(TokenType.INTO,   t.get(1).type());
        // Find the string literal
        boolean foundAlice = t.stream()
            .anyMatch(tok -> tok.type() == TokenType.STRING_LITERAL && tok.value().equals("Alice"));
        assertTrue(foundAlice, "Expected string literal 'Alice'");
    }

    @Test @DisplayName("SELECT * FROM tokenises correctly")
    void real_selectStar() throws SqlException {
        List<Token> t = lex("SELECT * FROM users;");
        assertEquals(TokenType.SELECT, t.get(0).type());
        assertEquals(TokenType.STAR,   t.get(1).type());
        assertEquals(TokenType.FROM,   t.get(2).type());
    }

    @Test @DisplayName("WHERE clause with operator tokenises correctly")
    void real_whereClause() throws SqlException {
        List<Token> t = lex("SELECT id FROM users WHERE id = 1");
        // Find the EQ operator
        boolean foundEq = t.stream().anyMatch(tok -> tok.type() == TokenType.EQ);
        assertTrue(foundEq);
    }

    @Test @DisplayName("UPDATE SET tokenises correctly")
    void real_updateSet() throws SqlException {
        List<Token> t = lex("UPDATE users SET name = 'Bob' WHERE id = 1;");
        assertEquals(TokenType.UPDATE, t.get(0).type());
        assertEquals(TokenType.SET,    t.get(2).type());
    }

    @Test @DisplayName("Multiple tokens produce correct count including EOF")
    void real_tokenCount() throws SqlException {
        // SELECT * FROM t ; → SELECT STAR FROM IDENT SEMICOLON EOF = 6 tokens
        List<Token> t = lex("SELECT * FROM t;");
        assertEquals(6, t.size());
    }
}
