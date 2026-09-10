package com.forgedb.sql;

import com.forgedb.catalog.DataType;
import com.forgedb.sql.ast.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the SQL Parser.
 *
 * Categories:
 *   1.  CREATE TABLE
 *   2.  INSERT
 *   3.  SELECT — basic
 *   4.  SELECT — WHERE clause
 *   5.  DELETE
 *   6.  UPDATE
 *   7.  Expression parsing: literals
 *   8.  Expression parsing: operators and precedence
 *   9.  Case insensitivity
 *  10.  Optional semicolon
 *  11.  parseAll — multiple statements
 *  12.  Syntax error cases
 *  13.  Edge cases (NULL, TRUE, FALSE, quoted strings)
 */
class ParserTest {

    private Statement parse(String sql) throws SqlException {
        return new Parser(new Lexer(sql).tokenize()).parse();
    }

    private List<Statement> parseAll(String sql) throws SqlException {
        return new Parser(new Lexer(sql).tokenize()).parseAll();
    }

    // =========================================================================
    // 1. CREATE TABLE
    // =========================================================================

    @Test @DisplayName("CREATE TABLE with single INT column")
    void create_singleColumn() throws SqlException {
        var stmt = (CreateTableStatement) parse("CREATE TABLE t (id INT)");
        assertEquals("t", stmt.tableName());
        assertEquals(1,   stmt.columns().size());
        assertEquals("id",       stmt.columns().get(0).name());
        assertEquals(DataType.INT, stmt.columns().get(0).type());
    }

    @Test @DisplayName("CREATE TABLE with all supported types")
    void create_allTypes() throws SqlException {
        var stmt = (CreateTableStatement) parse(
            "CREATE TABLE x (a INT, b LONG, c BOOLEAN, d DOUBLE, e TEXT)");
        List<ColumnDef> cols = stmt.columns();
        assertEquals(5, cols.size());
        assertEquals(DataType.INT,     cols.get(0).type());
        assertEquals(DataType.LONG,    cols.get(1).type());
        assertEquals(DataType.BOOLEAN, cols.get(2).type());
        assertEquals(DataType.DOUBLE,  cols.get(3).type());
        assertEquals(DataType.TEXT,    cols.get(4).type());
    }

    @Test @DisplayName("CREATE TABLE with three columns")
    void create_threeColumns() throws SqlException {
        var stmt = (CreateTableStatement) parse(
            "CREATE TABLE users (id INT, name TEXT, age INT)");
        assertEquals("users", stmt.tableName());
        assertEquals(3, stmt.columns().size());
        assertEquals("name",    stmt.columns().get(1).name());
        assertEquals(DataType.TEXT, stmt.columns().get(1).type());
    }

    @Test @DisplayName("CREATE TABLE with trailing semicolon")
    void create_withSemicolon() throws SqlException {
        var stmt = (CreateTableStatement) parse(
            "CREATE TABLE t (id INT);");
        assertEquals("t", stmt.tableName());
    }

    // =========================================================================
    // 2. INSERT
    // =========================================================================

    @Test @DisplayName("INSERT INTO VALUES with integer")
    void insert_intValue() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO users VALUES (42)");
        assertEquals("users", stmt.tableName());
        assertEquals(1, stmt.values().size());
        assertEquals(42, ((IntLiteral) stmt.values().get(0)).value());
    }

    @Test @DisplayName("INSERT INTO VALUES with string literal")
    void insert_stringValue() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO users VALUES ('Alice')");
        assertEquals("Alice", ((StringLiteral) stmt.values().get(0)).value());
    }

    @Test @DisplayName("INSERT INTO VALUES with multiple values")
    void insert_multipleValues() throws SqlException {
        var stmt = (InsertStatement) parse(
            "INSERT INTO users VALUES (1, 'Alice', 20)");
        assertEquals(3, stmt.values().size());
        assertEquals(1,       ((IntLiteral)    stmt.values().get(0)).value());
        assertEquals("Alice", ((StringLiteral) stmt.values().get(1)).value());
        assertEquals(20,      ((IntLiteral)    stmt.values().get(2)).value());
    }

    @Test @DisplayName("INSERT INTO with explicit column list")
    void insert_withColumnList() throws SqlException {
        var stmt = (InsertStatement) parse(
            "INSERT INTO users (id, name) VALUES (1, 'Bob')");
        assertEquals(List.of("id", "name"), stmt.columnNames());
        assertEquals(2, stmt.values().size());
    }

    @Test @DisplayName("INSERT without column list has empty columnNames")
    void insert_noColumnList_emptyNames() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (1)");
        assertTrue(stmt.columnNames().isEmpty());
    }

    @Test @DisplayName("INSERT with NULL value")
    void insert_nullValue() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (NULL)");
        assertSame(NullLiteral.INSTANCE, stmt.values().get(0));
    }

    @Test @DisplayName("INSERT with TRUE and FALSE")
    void insert_boolValues() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (TRUE, FALSE)");
        assertTrue( ((BoolLiteral) stmt.values().get(0)).value());
        assertFalse(((BoolLiteral) stmt.values().get(1)).value());
    }

    @Test @DisplayName("INSERT with double literal")
    void insert_doubleValue() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (3.14)");
        assertEquals(3.14, ((DoubleLiteral) stmt.values().get(0)).value(), 1e-9);
    }

    @Test @DisplayName("INSERT with negative integer")
    void insert_negativeInt() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (-99)");
        assertEquals(-99, ((IntLiteral) stmt.values().get(0)).value());
    }

    @Test @DisplayName("INSERT with large integer promoted to LONG")
    void insert_largeIntPromotion() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (9999999999)");
        assertInstanceOf(LongLiteral.class, stmt.values().get(0));
        assertEquals(9999999999L, ((LongLiteral) stmt.values().get(0)).value());
    }

    // =========================================================================
    // 3. SELECT — basic
    // =========================================================================

    @Test @DisplayName("SELECT * FROM table")
    void select_star() throws SqlException {
        var stmt = (SelectStatement) parse("SELECT * FROM users");
        assertEquals("users", stmt.tableName());
        assertEquals(1, stmt.selectList().size());
        assertTrue(stmt.selectList().get(0).isStar());
        assertNull(stmt.whereClause());
    }

    @Test @DisplayName("SELECT single column")
    void select_singleColumn() throws SqlException {
        var stmt = (SelectStatement) parse("SELECT id FROM users");
        assertEquals(1, stmt.selectList().size());
        assertFalse(stmt.selectList().get(0).isStar());
        var ref = (ColumnRef) stmt.selectList().get(0).expr();
        assertEquals("id", ref.columnName());
    }

    @Test @DisplayName("SELECT multiple columns")
    void select_multipleColumns() throws SqlException {
        var stmt = (SelectStatement) parse("SELECT id, name, age FROM users");
        assertEquals(3, stmt.selectList().size());
    }

    @Test @DisplayName("SELECT with AS alias")
    void select_withAlias() throws SqlException {
        var stmt = (SelectStatement) parse("SELECT id AS user_id FROM users");
        assertEquals("user_id", stmt.selectList().get(0).alias());
    }

    // =========================================================================
    // 4. SELECT — WHERE clause
    // =========================================================================

    @Test @DisplayName("SELECT with simple equality WHERE")
    void select_whereEquality() throws SqlException {
        var stmt = (SelectStatement) parse("SELECT * FROM users WHERE id = 1");
        assertNotNull(stmt.whereClause());
        var where = (BinaryExpression) stmt.whereClause();
        assertEquals(BinaryExpression.Op.EQ, where.op());
        assertEquals("id", ((ColumnRef) where.left()).columnName());
        assertEquals(1,    ((IntLiteral) where.right()).value());
    }

    @Test @DisplayName("SELECT WHERE with string comparison")
    void select_whereStringCmp() throws SqlException {
        var stmt = (SelectStatement) parse("SELECT * FROM users WHERE name = 'Alice'");
        var where = (BinaryExpression) stmt.whereClause();
        assertEquals(BinaryExpression.Op.EQ, where.op());
        assertEquals("Alice", ((StringLiteral) where.right()).value());
    }

    @Test @DisplayName("SELECT WHERE with AND")
    void select_whereAnd() throws SqlException {
        var stmt = (SelectStatement) parse(
            "SELECT * FROM users WHERE age > 18 AND active = TRUE");
        var where = (BinaryExpression) stmt.whereClause();
        assertEquals(BinaryExpression.Op.AND, where.op());
    }

    @Test @DisplayName("SELECT WHERE with OR")
    void select_whereOr() throws SqlException {
        var stmt = (SelectStatement) parse(
            "SELECT * FROM t WHERE x = 1 OR y = 2");
        var where = (BinaryExpression) stmt.whereClause();
        assertEquals(BinaryExpression.Op.OR, where.op());
    }

    @Test @DisplayName("SELECT WHERE with NOT")
    void select_whereNot() throws SqlException {
        var stmt = (SelectStatement) parse(
            "SELECT * FROM t WHERE NOT x = 1");
        var where = (UnaryExpression) stmt.whereClause();
        assertEquals(UnaryExpression.Op.NOT, where.op());
    }

    @Test @DisplayName("SELECT WHERE with all comparison operators")
    void select_whereAllOperators() throws SqlException {
        String[] ops     = {"=", "<>", "<", "<=", ">", ">="};
        BinaryExpression.Op[] expected = {
            BinaryExpression.Op.EQ, BinaryExpression.Op.NEQ,
            BinaryExpression.Op.LT, BinaryExpression.Op.LTE,
            BinaryExpression.Op.GT, BinaryExpression.Op.GTE
        };
        for (int i = 0; i < ops.length; i++) {
            var stmt = (SelectStatement) parse(
                "SELECT * FROM t WHERE x " + ops[i] + " 1");
            var where = (BinaryExpression) stmt.whereClause();
            assertEquals(expected[i], where.op(), "Operator mismatch for " + ops[i]);
        }
    }

    @Test @DisplayName("SELECT WHERE with parenthesised expression")
    void select_whereParens() throws SqlException {
        var stmt = (SelectStatement) parse(
            "SELECT * FROM t WHERE (x = 1)");
        var where = (BinaryExpression) stmt.whereClause();
        assertEquals(BinaryExpression.Op.EQ, where.op());
    }

    // =========================================================================
    // 5. DELETE
    // =========================================================================

    @Test @DisplayName("DELETE without WHERE deletes all rows")
    void delete_noWhere() throws SqlException {
        var stmt = (DeleteStatement) parse("DELETE FROM users");
        assertEquals("users", stmt.tableName());
        assertNull(stmt.whereClause());
    }

    @Test @DisplayName("DELETE with WHERE clause")
    void delete_withWhere() throws SqlException {
        var stmt = (DeleteStatement) parse("DELETE FROM users WHERE id = 1");
        assertNotNull(stmt.whereClause());
        var where = (BinaryExpression) stmt.whereClause();
        assertEquals(BinaryExpression.Op.EQ, where.op());
        assertEquals("id", ((ColumnRef) where.left()).columnName());
    }

    // =========================================================================
    // 6. UPDATE
    // =========================================================================

    @Test @DisplayName("UPDATE with single SET clause")
    void update_singleSet() throws SqlException {
        var stmt = (UpdateStatement) parse(
            "UPDATE users SET name = 'Bob'");
        assertEquals("users", stmt.tableName());
        assertEquals(1, stmt.setClauses().size());
        assertEquals("name",  stmt.setClauses().get(0).columnName());
        assertEquals("Bob",   ((StringLiteral) stmt.setClauses().get(0).value()).value());
        assertNull(stmt.whereClause());
    }

    @Test @DisplayName("UPDATE with multiple SET clauses")
    void update_multipleSet() throws SqlException {
        var stmt = (UpdateStatement) parse(
            "UPDATE users SET name = 'Bob', age = 25");
        assertEquals(2, stmt.setClauses().size());
        assertEquals("name", stmt.setClauses().get(0).columnName());
        assertEquals("age",  stmt.setClauses().get(1).columnName());
    }

    @Test @DisplayName("UPDATE with WHERE clause")
    void update_withWhere() throws SqlException {
        var stmt = (UpdateStatement) parse(
            "UPDATE users SET age = 25 WHERE id = 2");
        assertNotNull(stmt.whereClause());
        var where = (BinaryExpression) stmt.whereClause();
        assertEquals("id", ((ColumnRef) where.left()).columnName());
    }

    // =========================================================================
    // 7. Expression parsing: literals
    // =========================================================================

    @Test @DisplayName("Integer literal parsed to IntLiteral")
    void expr_intLiteral() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (123)");
        assertInstanceOf(IntLiteral.class, stmt.values().get(0));
        assertEquals(123, ((IntLiteral) stmt.values().get(0)).value());
    }

    @Test @DisplayName("Double literal parsed to DoubleLiteral")
    void expr_doubleLiteral() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (2.718)");
        assertInstanceOf(DoubleLiteral.class, stmt.values().get(0));
        assertEquals(2.718, ((DoubleLiteral) stmt.values().get(0)).value(), 1e-9);
    }

    @Test @DisplayName("NULL literal produces NullLiteral singleton")
    void expr_nullLiteral() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (NULL)");
        assertSame(NullLiteral.INSTANCE, stmt.values().get(0));
    }

    @Test @DisplayName("TRUE literal produces BoolLiteral(true)")
    void expr_trueLiteral() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (TRUE)");
        assertInstanceOf(BoolLiteral.class, stmt.values().get(0));
        assertTrue(((BoolLiteral) stmt.values().get(0)).value());
    }

    @Test @DisplayName("FALSE literal produces BoolLiteral(false)")
    void expr_falseLiteral() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES (FALSE)");
        assertFalse(((BoolLiteral) stmt.values().get(0)).value());
    }

    // =========================================================================
    // 8. Expression parsing: operators and precedence
    // =========================================================================

    @Test @DisplayName("AND has higher precedence than OR")
    void expr_andHigherThanOr() throws SqlException {
        // a OR b AND c  →  a OR (b AND c)
        var stmt = (SelectStatement) parse(
            "SELECT * FROM t WHERE a = 1 OR b = 2 AND c = 3");
        var root = (BinaryExpression) stmt.whereClause();
        assertEquals(BinaryExpression.Op.OR, root.op());
        // right operand should be AND
        var right = (BinaryExpression) root.right();
        assertEquals(BinaryExpression.Op.AND, right.op());
    }

    @Test @DisplayName("NOT has higher precedence than AND")
    void expr_notHigherThanAnd() throws SqlException {
        // NOT a AND b  →  (NOT a) AND b
        var stmt = (SelectStatement) parse(
            "SELECT * FROM t WHERE NOT x = 1 AND y = 2");
        var root = (BinaryExpression) stmt.whereClause();
        assertEquals(BinaryExpression.Op.AND, root.op());
        assertInstanceOf(UnaryExpression.class, root.left());
    }

    @Test @DisplayName("Parentheses override precedence")
    void expr_parenthesesOverride() throws SqlException {
        // (a OR b) AND c
        var stmt = (SelectStatement) parse(
            "SELECT * FROM t WHERE (x = 1 OR y = 2) AND z = 3");
        var root = (BinaryExpression) stmt.whereClause();
        assertEquals(BinaryExpression.Op.AND, root.op());
        assertInstanceOf(BinaryExpression.class, root.left());
        assertEquals(BinaryExpression.Op.OR,
            ((BinaryExpression) root.left()).op());
    }

    // =========================================================================
    // 9. Case insensitivity
    // =========================================================================

    @Test @DisplayName("Keywords are case-insensitive in full statements")
    void case_keywords() throws SqlException {
        assertInstanceOf(SelectStatement.class,
            parse("select * from users"));
        assertInstanceOf(SelectStatement.class,
            parse("SELECT * FROM users"));
        assertInstanceOf(SelectStatement.class,
            parse("Select * From Users"));
    }

    @Test @DisplayName("CREATE TABLE with lowercase type names")
    void case_typesLowercase() throws SqlException {
        var stmt = (CreateTableStatement) parse(
            "create table t (id int, name text)");
        assertEquals(DataType.INT,  stmt.columns().get(0).type());
        assertEquals(DataType.TEXT, stmt.columns().get(1).type());
    }

    // =========================================================================
    // 10. Optional semicolon
    // =========================================================================

    @Test @DisplayName("Statement with semicolon parses correctly")
    void semicolon_withSemicolon() throws SqlException {
        assertDoesNotThrow(() -> parse("SELECT * FROM t;"));
    }

    @Test @DisplayName("Statement without semicolon parses correctly")
    void semicolon_withoutSemicolon() throws SqlException {
        assertDoesNotThrow(() -> parse("SELECT * FROM t"));
    }

    // =========================================================================
    // 11. parseAll — multiple statements
    // =========================================================================

    @Test @DisplayName("parseAll returns list of all statements")
    void parseAll_threeStatements() throws SqlException {
        List<Statement> stmts = parseAll(
            "CREATE TABLE t (id INT);" +
            "INSERT INTO t VALUES (1);" +
            "SELECT * FROM t;"
        );
        assertEquals(3, stmts.size());
        assertInstanceOf(CreateTableStatement.class, stmts.get(0));
        assertInstanceOf(InsertStatement.class,      stmts.get(1));
        assertInstanceOf(SelectStatement.class,      stmts.get(2));
    }

    @Test @DisplayName("parseAll on single statement returns list of one")
    void parseAll_single() throws SqlException {
        assertEquals(1, parseAll("SELECT * FROM t").size());
    }

    // =========================================================================
    // 12. Syntax error cases
    // =========================================================================

    @Test @DisplayName("Missing table name in CREATE TABLE throws SqlException")
    void error_createMissingTableName() {
        assertThrows(SqlException.class, () -> parse("CREATE TABLE (id INT)"));
    }

    @Test @DisplayName("Missing columns in CREATE TABLE throws SqlException")
    void error_createMissingColumns() {
        assertThrows(SqlException.class, () -> parse("CREATE TABLE t ()"));
    }

    @Test @DisplayName("Unknown type in CREATE TABLE throws SqlException")
    void error_createUnknownType() {
        assertThrows(SqlException.class, () -> parse("CREATE TABLE t (id FLOAT)"));
    }

    @Test @DisplayName("Missing VALUES in INSERT throws SqlException")
    void error_insertMissingValues() {
        assertThrows(SqlException.class, () -> parse("INSERT INTO t (1, 2)"));
    }

    @Test @DisplayName("Missing FROM in SELECT throws SqlException")
    void error_selectMissingFrom() {
        assertThrows(SqlException.class, () -> parse("SELECT * users"));
    }

    @Test @DisplayName("Missing table in SELECT throws SqlException")
    void error_selectMissingTable() {
        assertThrows(SqlException.class, () -> parse("SELECT * FROM"));
    }

    @Test @DisplayName("Missing WHERE expression after WHERE keyword throws SqlException")
    void error_missingWhereExpr() {
        assertThrows(SqlException.class, () -> parse("SELECT * FROM t WHERE"));
    }

    @Test @DisplayName("Unclosed parenthesis in expression throws SqlException")
    void error_unclosedParen() {
        assertThrows(SqlException.class, () -> parse("SELECT * FROM t WHERE (x = 1"));
    }

    @Test @DisplayName("Empty input to parser throws or returns empty list")
    void error_emptyInput() throws SqlException {
        // parseAll on empty input should return an empty list, not throw
        List<Statement> stmts = parseAll("");
        assertTrue(stmts.isEmpty());
    }

    @Test @DisplayName("Unknown starting keyword throws SqlException")
    void error_unknownStatement() {
        assertThrows(SqlException.class, () -> parse("DROP TABLE t"));
    }

    @Test @DisplayName("SqlException message includes position information")
    void error_positionInMessage() {
        SqlException ex = assertThrows(SqlException.class,
            () -> parse("SELECT * FROM"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("line") || msg.contains("col"),
            "Exception message should contain position info: " + msg);
    }

    // =========================================================================
    // 13. Edge cases
    // =========================================================================

    @Test @DisplayName("INSERT with empty string literal")
    void edge_insertEmptyString() throws SqlException {
        var stmt = (InsertStatement) parse("INSERT INTO t VALUES ('')");
        assertEquals("", ((StringLiteral) stmt.values().get(0)).value());
    }

    @Test @DisplayName("Column name same as non-reserved keyword works as identifier")
    void edge_columnSameAsNonReserved() throws SqlException {
        // 'order' is listed as non-reserved; using it as a column name should work
        assertDoesNotThrow(() -> parse("SELECT * FROM t WHERE order = 1"));
    }

    @Test @DisplayName("Table name with underscores is valid")
    void edge_tableNameWithUnderscore() throws SqlException {
        var stmt = (CreateTableStatement) parse("CREATE TABLE my_table (id INT)");
        assertEquals("my_table", stmt.tableName());
    }

    @Test @DisplayName("Negative double in WHERE clause")
    void edge_negativeDoubleWhere() throws SqlException {
        var stmt = (SelectStatement) parse("SELECT * FROM t WHERE score > -1.5");
        var where = (BinaryExpression) stmt.whereClause();
        assertEquals(-1.5, ((DoubleLiteral) where.right()).value(), 1e-9);
    }

    @Test @DisplayName("Full target demo: all five statement types parse correctly")
    void edge_targetDemoStatements() throws SqlException {
        List<Statement> stmts = parseAll(
            "CREATE TABLE users (id INT, name TEXT, age INT);\n" +
            "INSERT INTO users VALUES (1, 'Alice', 21);\n" +
            "INSERT INTO users VALUES (2, 'Bob', 24);\n" +
            "SELECT * FROM users;\n" +
            "SELECT * FROM users WHERE id = 2;\n" +
            "UPDATE users SET age = 25 WHERE id = 2;\n" +
            "DELETE FROM users WHERE id = 1;"
        );
        assertEquals(7, stmts.size());
        assertInstanceOf(CreateTableStatement.class, stmts.get(0));
        assertInstanceOf(InsertStatement.class,      stmts.get(1));
        assertInstanceOf(InsertStatement.class,      stmts.get(2));
        assertInstanceOf(SelectStatement.class,      stmts.get(3));
        assertInstanceOf(SelectStatement.class,      stmts.get(4));
        assertInstanceOf(UpdateStatement.class,      stmts.get(5));
        assertInstanceOf(DeleteStatement.class,      stmts.get(6));
    }
}
