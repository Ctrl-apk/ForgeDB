package com.forgedb.sql;

/**
 * Every distinct kind of token the ForgeDB SQL lexer can produce.
 *
 * Categories:
 *   KEYWORD   – reserved SQL words (SELECT, FROM, WHERE, …)
 *   LITERAL   – value tokens (integer, long, double, string, boolean)
 *   OPERATOR  – comparison and assignment symbols (=, <, >, <=, >=, <>)
 *   PUNCT     – punctuation (comma, semicolon, left/right paren, star)
 *   IDENT     – unquoted identifier (table name, column name, …)
 *   EOF       – end of input; always the last token in a token stream
 */
public enum TokenType {

    // -------------------------------------------------------------------------
    // Keywords (alphabetical)
    // -------------------------------------------------------------------------
    AND,
    AS,
    BEGIN,      // transaction
    BOOLEAN,    // type keyword
    BY,
    COMMIT,     // transaction
    CREATE,
    DELETE,
    DOUBLE,     // type keyword
    DROP,
    FALSE,
    FROM,
    INSERT,
    INT,        // type keyword
    INTO,
    IS,
    LONG,       // type keyword
    NOT,
    NULL,
    OR,
    ORDER,
    ROLLBACK,   // transaction
    SELECT,
    SET,
    TABLE,
    TEXT,       // type keyword
    TRUE,
    UPDATE,
    VALUES,
    WHERE,

    // -------------------------------------------------------------------------
    // Literals
    // -------------------------------------------------------------------------
    /** A sequence of decimal digits with no decimal point: 42, -3 */
    INTEGER_LITERAL,
    /** A numeric literal with a decimal point: 3.14, -0.5 */
    DOUBLE_LITERAL,
    /** A single-quoted string: 'Alice' */
    STRING_LITERAL,
    /** true / false (case-insensitive) — also matched as keywords TRUE/FALSE */
    BOOLEAN_LITERAL,

    // -------------------------------------------------------------------------
    // Operators
    // -------------------------------------------------------------------------
    EQ,         // =
    NEQ,        // <>
    LT,         // <
    LTE,        // <=
    GT,         // >
    GTE,        // >=
    PLUS,       // +  (reserved for future arithmetic)
    MINUS,      // -  (reserved; also used to parse negative literals)

    // -------------------------------------------------------------------------
    // Punctuation
    // -------------------------------------------------------------------------
    COMMA,      // ,
    SEMICOLON,  // ;
    LPAREN,     // (
    RPAREN,     // )
    STAR,       // *
    DOT,        // .

    // -------------------------------------------------------------------------
    // Identifier
    // -------------------------------------------------------------------------
    IDENT,

    // -------------------------------------------------------------------------
    // Sentinel
    // -------------------------------------------------------------------------
    EOF
}
