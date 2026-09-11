package com.forgedb.sql;

import com.forgedb.catalog.DataType;
import com.forgedb.sql.ast.*;

import java.util.ArrayList;
import java.util.List;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the ForgeDB SQL subset.
 *
 * <h2>Usage</h2>
 * <pre>
 *   List&lt;Token&gt; tokens = new Lexer(sql).tokenize();
 *   Statement   stmt   = new Parser(tokens).parse();
 * </pre>
 *
 * <h2>Grammar (informal BNF)</h2>
 * <pre>
 * statement     ::= createTable | insert | select | delete | update
 *
 * createTable   ::= CREATE TABLE ident '(' colDefList ')' ';'?
 * colDefList    ::= colDef (',' colDef)*
 * colDef        ::= ident typeName
 * typeName      ::= INT | LONG | BOOLEAN | DOUBLE | TEXT
 *
 * insert        ::= INSERT INTO ident ['(' identList ')'] VALUES '(' valueList ')' ';'?
 * identList     ::= ident (',' ident)*
 * valueList     ::= expr (',' expr)*
 *
 * select        ::= SELECT selectList FROM ident ['WHERE' expr] ';'?
 * selectList    ::= '*' | selectItem (',' selectItem)*
 * selectItem    ::= expr ['AS' ident]
 *
 * delete        ::= DELETE FROM ident ['WHERE' expr] ';'?
 *
 * update        ::= UPDATE ident SET setClause (',' setClause)* ['WHERE' expr] ';'?
 * setClause     ::= ident '=' expr
 *
 * expr          ::= orExpr
 * orExpr        ::= andExpr  ('OR'  andExpr)*
 * andExpr       ::= notExpr  ('AND' notExpr)*
 * notExpr       ::= 'NOT' notExpr | cmpExpr
 * cmpExpr       ::= primary (('='|'<>'|'<'|'<='|'>'|'>=') primary)?
 * primary       ::= literal | columnRef | '(' expr ')'
 *
 * literal       ::= INTEGER_LITERAL | DOUBLE_LITERAL | STRING_LITERAL
 *                 | TRUE | FALSE | NULL
 * columnRef     ::= ident ['.' ident]
 * </pre>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>The parser is purely syntactic: it builds an AST and does not access
 *       any storage component (no DiskManager, BufferPool, HeapFile, BTree).</li>
 *   <li>It is a single-statement parser: {@link #parse()} reads exactly one
 *       statement. Call {@link #parseAll()} to parse a semicolon-separated
 *       sequence.</li>
 *   <li>Integer literals that exceed {@code Integer.MAX_VALUE} are
 *       automatically promoted to {@code LongLiteral}.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. Create one Parser instance per parse call.
 */
public final class Parser {

    private final List<Token> tokens;
    private int               pos = 0;

    public Parser(List<Token> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException("Token list must not be null or empty");
        }
        this.tokens = tokens;
    }

    // =========================================================================
    // Public entry points
    // =========================================================================

    /**
     * Parses exactly one SQL statement from the token list.
     *
     * @return the parsed Statement AST node
     * @throws SqlException if the token stream does not match any valid statement
     */
    public Statement parse() throws SqlException {
        Statement stmt = parseStatement();
        // Consume optional trailing semicolon
        if (check(TokenType.SEMICOLON)) advance();
        // Allow trailing EOF (do not require it — parseAll chains multiple calls)
        return stmt;
    }

    /**
     * Parses all statements in the token list (semicolon-separated).
     * Stops at EOF. Returns at least one element (or throws if the input is
     * completely empty after skipping whitespace).
     */
    public List<Statement> parseAll() throws SqlException {
        List<Statement> stmts = new ArrayList<>();
        while (!check(TokenType.EOF)) {
            stmts.add(parseStatement());
            // Consume optional semicolon between statements
            if (check(TokenType.SEMICOLON)) advance();
        }
        return stmts;
    }

    // =========================================================================
    // Statement dispatch
    // =========================================================================

    private Statement parseStatement() throws SqlException {
        Token t = peek();
        return switch (t.type()) {
            case CREATE   -> parseCreateTable();
            case INSERT   -> parseInsert();
            case SELECT   -> parseSelect();
            case DELETE   -> parseDelete();
            case UPDATE   -> parseUpdate();
            case BEGIN    -> { advance(); yield BeginStatement.INSTANCE; }
            case COMMIT   -> { advance(); yield CommitStatement.INSTANCE; }
            case ROLLBACK -> { advance(); yield RollbackStatement.INSTANCE; }
            default       -> throw new SqlException(
                               "Expected a SQL statement (CREATE, INSERT, SELECT, " +
                               "DELETE, UPDATE, BEGIN, COMMIT, or ROLLBACK)", t);
        };
    }

    // =========================================================================
    // CREATE TABLE
    // =========================================================================

    private CreateTableStatement parseCreateTable() throws SqlException {
        consume(TokenType.CREATE,  "Expected CREATE");
        consume(TokenType.TABLE,   "Expected TABLE after CREATE");
        String tableName = consumeIdent("Expected table name");

        consume(TokenType.LPAREN,  "Expected '(' after table name");
        List<ColumnDef> columns = parseColumnDefList();
        consume(TokenType.RPAREN,  "Expected ')' after column definitions");

        return new CreateTableStatement(tableName, columns);
    }

    private List<ColumnDef> parseColumnDefList() throws SqlException {
        List<ColumnDef> cols = new ArrayList<>();
        cols.add(parseColumnDef());
        while (check(TokenType.COMMA)) {
            advance();
            cols.add(parseColumnDef());
        }
        return cols;
    }

    private ColumnDef parseColumnDef() throws SqlException {
        String   name = consumeIdent("Expected column name");
        DataType type = parseTypeName();
        return new ColumnDef(name, type);
    }

    private DataType parseTypeName() throws SqlException {
        Token t = peek();
        DataType dt = switch (t.type()) {
            case INT     -> DataType.INT;
            case LONG    -> DataType.LONG;
            case BOOLEAN -> DataType.BOOLEAN;
            case DOUBLE  -> DataType.DOUBLE;
            case TEXT    -> DataType.TEXT;
            default      -> throw new SqlException(
                                "Expected a type name (INT, LONG, BOOLEAN, DOUBLE, TEXT)", t);
        };
        advance();
        return dt;
    }

    // =========================================================================
    // INSERT
    // =========================================================================

    private InsertStatement parseInsert() throws SqlException {
        consume(TokenType.INSERT, "Expected INSERT");
        consume(TokenType.INTO,   "Expected INTO after INSERT");
        String tableName = consumeIdent("Expected table name");

        // Optional column list: (col1, col2, ...)
        List<String> columnNames = new ArrayList<>();
        if (check(TokenType.LPAREN)) {
            advance();
            columnNames.add(consumeIdent("Expected column name"));
            while (check(TokenType.COMMA)) {
                advance();
                columnNames.add(consumeIdent("Expected column name"));
            }
            consume(TokenType.RPAREN, "Expected ')' after column list");
        }

        consume(TokenType.VALUES,  "Expected VALUES");
        consume(TokenType.LPAREN,  "Expected '(' after VALUES");
        List<Expression> values = parseExpressionList();
        consume(TokenType.RPAREN,  "Expected ')' after values");

        return new InsertStatement(tableName, columnNames, values);
    }

    // =========================================================================
    // SELECT
    // =========================================================================

    private SelectStatement parseSelect() throws SqlException {
        consume(TokenType.SELECT, "Expected SELECT");
        List<SelectItem> selectList = parseSelectList();
        consume(TokenType.FROM,   "Expected FROM");
        String tableName = consumeIdent("Expected table name");

        Expression where = null;
        if (check(TokenType.WHERE)) {
            advance();
            where = parseExpression();
        }

        return new SelectStatement(selectList, tableName, where);
    }

    private List<SelectItem> parseSelectList() throws SqlException {
        // Check for wildcard *
        if (check(TokenType.STAR)) {
            advance();
            return List.of(SelectItem.star());
        }
        List<SelectItem> items = new ArrayList<>();
        items.add(parseSelectItem());
        while (check(TokenType.COMMA)) {
            advance();
            items.add(parseSelectItem());
        }
        return items;
    }

    private SelectItem parseSelectItem() throws SqlException {
        Expression expr = parseExpression();
        String alias = null;
        if (check(TokenType.AS)) {
            advance();
            alias = consumeIdent("Expected alias after AS");
        }
        return SelectItem.of(expr, alias);
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    private DeleteStatement parseDelete() throws SqlException {
        consume(TokenType.DELETE,  "Expected DELETE");
        consume(TokenType.FROM,    "Expected FROM after DELETE");
        String tableName = consumeIdent("Expected table name");

        Expression where = null;
        if (check(TokenType.WHERE)) {
            advance();
            where = parseExpression();
        }

        return new DeleteStatement(tableName, where);
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    private UpdateStatement parseUpdate() throws SqlException {
        consume(TokenType.UPDATE, "Expected UPDATE");
        String tableName = consumeIdent("Expected table name");
        consume(TokenType.SET,    "Expected SET after table name");

        List<SetClause> setClauses = new ArrayList<>();
        setClauses.add(parseSetClause());
        while (check(TokenType.COMMA)) {
            advance();
            setClauses.add(parseSetClause());
        }

        Expression where = null;
        if (check(TokenType.WHERE)) {
            advance();
            where = parseExpression();
        }

        return new UpdateStatement(tableName, setClauses, where);
    }

    private SetClause parseSetClause() throws SqlException {
        String col = consumeIdent("Expected column name in SET clause");
        consume(TokenType.EQ,     "Expected '=' in SET clause");
        Expression val = parseExpression();
        return new SetClause(col, val);
    }

    // =========================================================================
    // Expression grammar
    // Precedence (low → high):  OR → AND → NOT → comparison → primary
    // =========================================================================

    private List<Expression> parseExpressionList() throws SqlException {
        List<Expression> exprs = new ArrayList<>();
        exprs.add(parseExpression());
        while (check(TokenType.COMMA)) {
            advance();
            exprs.add(parseExpression());
        }
        return exprs;
    }

    private Expression parseExpression() throws SqlException {
        return parseOrExpr();
    }

    // OR (lowest precedence binary op)
    private Expression parseOrExpr() throws SqlException {
        Expression left = parseAndExpr();
        while (check(TokenType.OR)) {
            advance();
            Expression right = parseAndExpr();
            left = new BinaryExpression(left, BinaryExpression.Op.OR, right);
        }
        return left;
    }

    // AND
    private Expression parseAndExpr() throws SqlException {
        Expression left = parseNotExpr();
        while (check(TokenType.AND)) {
            advance();
            Expression right = parseNotExpr();
            left = new BinaryExpression(left, BinaryExpression.Op.AND, right);
        }
        return left;
    }

    // NOT (unary prefix)
    private Expression parseNotExpr() throws SqlException {
        if (check(TokenType.NOT)) {
            advance();
            Expression operand = parseNotExpr();   // right-associative
            return new UnaryExpression(UnaryExpression.Op.NOT, operand);
        }
        return parseCmpExpr();
    }

    // Comparison operators (non-associative: only one per expression)
    private Expression parseCmpExpr() throws SqlException {
        Expression left = parsePrimary();
        BinaryExpression.Op op = peekComparisonOp();
        if (op != null) {
            advance();
            Expression right = parsePrimary();
            return new BinaryExpression(left, op, right);
        }
        return left;
    }

    /**
     * Returns the BinaryExpression.Op for a comparison token, or null if the
     * current token is not a comparison operator.
     */
    private BinaryExpression.Op peekComparisonOp() {
        return switch (peek().type()) {
            case EQ   -> BinaryExpression.Op.EQ;
            case NEQ  -> BinaryExpression.Op.NEQ;
            case LT   -> BinaryExpression.Op.LT;
            case LTE  -> BinaryExpression.Op.LTE;
            case GT   -> BinaryExpression.Op.GT;
            case GTE  -> BinaryExpression.Op.GTE;
            default   -> null;
        };
    }

    // Primary expression: literal, column reference, or parenthesised expression
    private Expression parsePrimary() throws SqlException {
        Token t = peek();

        // Parenthesised expression
        if (check(TokenType.LPAREN)) {
            advance();
            Expression inner = parseExpression();
            consume(TokenType.RPAREN, "Expected ')' to close expression");
            return inner;
        }

        // Literals
        if (check(TokenType.INTEGER_LITERAL)) {
            advance();
            return parseIntOrLong(t);
        }
        if (check(TokenType.DOUBLE_LITERAL)) {
            advance();
            try {
                return new DoubleLiteral(Double.parseDouble(t.value()));
            } catch (NumberFormatException e) {
                throw new SqlException("Invalid double literal: " + t.value(), t);
            }
        }
        if (check(TokenType.STRING_LITERAL)) {
            advance();
            return new StringLiteral(t.value()); // value already stripped of quotes by Lexer
        }
        if (check(TokenType.TRUE)) {
            advance();
            return new BoolLiteral(true);
        }
        if (check(TokenType.FALSE)) {
            advance();
            return new BoolLiteral(false);
        }
        if (check(TokenType.NULL)) {
            advance();
            return NullLiteral.INSTANCE;
        }

        // Column reference: ident or ident.ident
        if (check(TokenType.IDENT)) {
            String first = t.value();
            advance();
            if (check(TokenType.DOT)) {
                advance();
                String second = consumeIdent("Expected column name after '.'");
                return new ColumnRef(first, second);
            }
            return new ColumnRef(first);
        }

        // Keywords used as identifiers (e.g. column named "name" which is not a keyword,
        // but handle the case where a type keyword is used as a column name)
        if (isTypeKeyword(t.type()) || isNonReservedKeyword(t.type())) {
            advance();
            return new ColumnRef(t.value());
        }

        throw new SqlException("Expected an expression (literal, column, or '(')", t);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Parses an INTEGER_LITERAL token, automatically promoting to LongLiteral
     * if the value exceeds Integer range.
     */
    private Expression parseIntOrLong(Token t) throws SqlException {
        String raw = t.value();
        try {
            // Try int first
            long lv = Long.parseLong(raw);
            if (lv >= Integer.MIN_VALUE && lv <= Integer.MAX_VALUE) {
                return new IntLiteral((int) lv);
            }
            return new LongLiteral(lv);
        } catch (NumberFormatException e) {
            throw new SqlException("Invalid integer literal: " + raw, t);
        }
    }

    /** Returns true if the given TokenType is a SQL type keyword. */
    private boolean isTypeKeyword(TokenType type) {
        return switch (type) {
            case INT, LONG, BOOLEAN, DOUBLE, TEXT -> true;
            default -> false;
        };
    }

    /** Returns the current token without consuming it. */
    private Token peek() {
        return tokens.get(pos);
    }

    /** Returns true if the current token has the given type. */
    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    /** Consumes and returns the current token. */
    private Token advance() {
        Token t = tokens.get(pos);
        if (t.type() != TokenType.EOF) pos++;
        return t;
    }

    /**
     * Asserts the current token has the expected type, consumes it, and
     * returns it. Throws {@link SqlException} with a helpful message otherwise.
     */
    private Token consume(TokenType expected, String message) throws SqlException {
        if (!check(expected)) {
            throw new SqlException(message, peek());
        }
        return advance();
    }

    /**
     * Consumes an identifier (or any token whose value can serve as an
     * identifier, including type keywords used as column/table names).
     */
    private String consumeIdent(String message) throws SqlException {
        Token t = peek();
        // Accept plain identifiers and any keyword-like token that could be
        // used as an identifier (e.g. "text" as a column name)
        if (t.type() == TokenType.IDENT || isTypeKeyword(t.type()) || isNonReservedKeyword(t.type())) {
            advance();
            return t.value();
        }
        throw new SqlException(message, t);
    }

    /**
     * Keywords that are commonly used as identifiers in real SQL and which we
     * treat as non-reserved (can appear as table/column names).
     */
    private boolean isNonReservedKeyword(TokenType type) {
        return switch (type) {
            case AS, BY, ORDER, IS -> true;
            default -> false;
        };
    }
}
