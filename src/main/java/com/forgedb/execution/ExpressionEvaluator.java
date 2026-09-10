package com.forgedb.execution;

import com.forgedb.catalog.DataType;
import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;
import com.forgedb.sql.ast.*;

/**
 * Evaluates a SQL expression AST node against an optional row context (Tuple).
 *
 * <h2>Usage</h2>
 * <pre>
 *   // Evaluate a WHERE clause against a tuple
 *   ExpressionEvaluator eval = new ExpressionEvaluator(schema, tuple);
 *   Object result = eval.evaluate(whereClause);
 *   boolean matches = Boolean.TRUE.equals(result);
 *
 *   // Evaluate a literal (no tuple context needed)
 *   ExpressionEvaluator eval = new ExpressionEvaluator(null, null);
 *   Object value = eval.evaluate(insertExpr);
 * </pre>
 *
 * <h2>Return types</h2>
 * {@link #evaluate} returns a boxed Java value whose type corresponds to the
 * SQL type of the expression:
 * <pre>
 *   SQL INT      → Integer
 *   SQL LONG     → Long
 *   SQL DOUBLE   → Double
 *   SQL BOOLEAN  → Boolean
 *   SQL TEXT     → String
 *   SQL NULL     → null
 * </pre>
 *
 * <h2>Comparison semantics</h2>
 * Comparisons between numeric types are widened: INT and LONG can be compared
 * to DOUBLE by promoting both sides to Double before comparing. NULL propagates
 * (any comparison involving NULL returns null, which is treated as false by
 * the executor).
 *
 * <h2>Boolean logic</h2>
 * {@code AND} and {@code OR} short-circuit. {@code NOT} inverts a Boolean
 * operand. All logical operators require Boolean operands (throw
 * ExecutionException on type mismatch).
 *
 * <h2>Thread safety</h2>
 * ExpressionEvaluator holds a reference to the current tuple context; create
 * a new instance per row evaluation.
 */
public final class ExpressionEvaluator implements ExpressionVisitor<Object> {

    private final Schema schema;   // nullable — null when evaluating pure literals
    private final Tuple  tuple;    // nullable — null when evaluating pure literals

    /**
     * Creates an evaluator with a row context (used for WHERE, SET, and
     * INSERT value expressions that reference columns).
     *
     * @param schema the schema of the current row; may be null for pure-literal evaluation
     * @param tuple  the current row; may be null for pure-literal evaluation
     */
    public ExpressionEvaluator(Schema schema, Tuple tuple) {
        this.schema = schema;
        this.tuple  = tuple;
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Evaluates the expression and returns a boxed Java value.
     * Returns null to represent SQL NULL.
     *
     * @param expr the expression to evaluate
     * @return boxed Java value, or null for SQL NULL
     * @throws ExecutionException if evaluation fails (type error, unknown column, etc.)
     */
    public Object evaluate(Expression expr) throws ExecutionException {
        try {
            return expr.accept(this);
        } catch (ExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExecutionException("Expression evaluation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Evaluates the expression and asserts the result is a non-null Boolean.
     * Useful for evaluating WHERE clauses.
     *
     * @param expr the boolean expression to evaluate
     * @return true or false
     * @throws ExecutionException if the result is not Boolean or is NULL
     */
    public boolean evaluateBoolean(Expression expr) throws ExecutionException {
        Object result = evaluate(expr);
        if (result == null) return false;   // NULL predicate = false
        if (!(result instanceof Boolean)) {
            throw new ExecutionException(
                "WHERE expression must evaluate to BOOLEAN, got: " +
                result.getClass().getSimpleName());
        }
        return (Boolean) result;
    }

    // -------------------------------------------------------------------------
    // ExpressionVisitor — literals
    // -------------------------------------------------------------------------

    @Override
    public Object visitIntLiteral(IntLiteral expr) { return expr.value(); }

    @Override
    public Object visitLongLiteral(LongLiteral expr) { return expr.value(); }

    @Override
    public Object visitDoubleLiteral(DoubleLiteral expr) { return expr.value(); }

    @Override
    public Object visitStringLiteral(StringLiteral expr) { return expr.value(); }

    @Override
    public Object visitBoolLiteral(BoolLiteral expr) { return expr.value(); }

    @Override
    public Object visitNullLiteral(NullLiteral expr) { return null; }

    // -------------------------------------------------------------------------
    // ExpressionVisitor — column reference
    // -------------------------------------------------------------------------

    @Override
    public Object visitColumnRef(ColumnRef expr) throws ExecutionException {
        if (schema == null || tuple == null) {
            throw new ExecutionException(
                "Column reference '" + expr.columnName() +
                "' cannot be evaluated without a row context");
        }
        String colName = expr.columnName();
        if (!schema.hasColumn(colName)) {
            throw new ExecutionException(
                "Unknown column: '" + colName + "'");
        }
        int idx = schema.getColumnIndex(colName);
        return tuple.get(idx);   // returns null for SQL NULL
    }

    // -------------------------------------------------------------------------
    // ExpressionVisitor — binary expression
    // -------------------------------------------------------------------------

    @Override
    public Object visitBinaryExpr(BinaryExpression expr) throws ExecutionException {
        BinaryExpression.Op op = expr.op();

        // Short-circuit logical operators
        if (op == BinaryExpression.Op.AND) return evaluateAnd(expr);
        if (op == BinaryExpression.Op.OR)  return evaluateOr(expr);

        // Evaluate both sides
        Object left  = evaluate(expr.left());
        Object right = evaluate(expr.right());

        return switch (op) {
            case EQ    -> evalEq(left, right);
            case NEQ   -> evalNeq(left, right);
            case LT    -> evalCmp(left, right, op);
            case LTE   -> evalCmp(left, right, op);
            case GT    -> evalCmp(left, right, op);
            case GTE   -> evalCmp(left, right, op);
            default    -> throw new ExecutionException(
                              "Unsupported binary operator: " + op);
        };
    }

    // -------------------------------------------------------------------------
    // ExpressionVisitor — unary expression
    // -------------------------------------------------------------------------

    @Override
    public Object visitUnaryExpr(UnaryExpression expr) throws ExecutionException {
        Object operand = evaluate(expr.operand());
        if (expr.op() == UnaryExpression.Op.NOT) {
            if (operand == null)            return null;   // NOT NULL = NULL
            if (!(operand instanceof Boolean)) {
                throw new ExecutionException(
                    "NOT requires a BOOLEAN operand, got: " +
                    operand.getClass().getSimpleName());
            }
            return !((Boolean) operand);
        }
        throw new ExecutionException("Unsupported unary operator: " + expr.op());
    }

    // -------------------------------------------------------------------------
    // Comparison helpers
    // -------------------------------------------------------------------------

    private Boolean evalEq(Object left, Object right) throws ExecutionException {
        if (left == null || right == null) return null;   // NULL = anything → NULL
        return compareValues(left, right) == 0;
    }

    private Boolean evalNeq(Object left, Object right) throws ExecutionException {
        if (left == null || right == null) return null;
        return compareValues(left, right) != 0;
    }

    private Boolean evalCmp(Object left, Object right, BinaryExpression.Op op)
            throws ExecutionException {
        if (left == null || right == null) return null;
        int cmp = compareValues(left, right);
        return switch (op) {
            case LT  -> cmp <  0;
            case LTE -> cmp <= 0;
            case GT  -> cmp >  0;
            case GTE -> cmp >= 0;
            default  -> throw new ExecutionException("Not a comparison op: " + op);
        };
    }

    /**
     * Compares two non-null Java values.
     *
     * Numeric widening: if one side is Double and the other is Integer or Long,
     * both are widened to Double before comparing. This lets WHERE clauses like
     * {@code score = 3} work correctly on DOUBLE columns when a plain INT
     * literal is used.
     *
     * @throws ExecutionException if the types are incompatible
     */
    @SuppressWarnings("unchecked")
    private int compareValues(Object left, Object right) throws ExecutionException {
        // Numeric widening: promote to Double when types differ but are both numeric
        if (isNumeric(left) && isNumeric(right) && !left.getClass().equals(right.getClass())) {
            double l = toDouble(left);
            double r = toDouble(right);
            return Double.compare(l, r);
        }

        // Same type (or String) — use natural ordering
        if (left instanceof Integer  l && right instanceof Integer  r) return Integer.compare(l, r);
        if (left instanceof Long     l && right instanceof Long     r) return Long.compare(l, r);
        if (left instanceof Double   l && right instanceof Double   r) return Double.compare(l, r);
        if (left instanceof Boolean  l && right instanceof Boolean  r) return Boolean.compare(l, r);
        if (left instanceof String   l && right instanceof String   r) return l.compareTo(r);

        throw new ExecutionException(String.format(
            "Cannot compare values of types %s and %s",
            left.getClass().getSimpleName(),
            right.getClass().getSimpleName()));
    }

    private boolean isNumeric(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Double;
    }

    private double toDouble(Object v) {
        if (v instanceof Integer i) return i.doubleValue();
        if (v instanceof Long    l) return l.doubleValue();
        if (v instanceof Double  d) return d;
        throw new IllegalArgumentException("Not numeric: " + v);
    }

    // -------------------------------------------------------------------------
    // Logical helpers (short-circuit)
    // -------------------------------------------------------------------------

    private Boolean evaluateAnd(BinaryExpression expr) throws ExecutionException {
        Object left = evaluate(expr.left());
        // Short-circuit: FALSE AND anything = FALSE
        if (Boolean.FALSE.equals(left)) return false;
        Object right = evaluate(expr.right());
        if (Boolean.FALSE.equals(right)) return false;
        // NULL AND TRUE = NULL, TRUE AND TRUE = TRUE
        if (left == null || right == null) return null;
        return true;
    }

    private Boolean evaluateOr(BinaryExpression expr) throws ExecutionException {
        Object left = evaluate(expr.left());
        // Short-circuit: TRUE OR anything = TRUE
        if (Boolean.TRUE.equals(left)) return true;
        Object right = evaluate(expr.right());
        if (Boolean.TRUE.equals(right)) return true;
        // NULL OR FALSE = NULL, FALSE OR FALSE = FALSE
        if (left == null || right == null) return null;
        return false;
    }

    // -------------------------------------------------------------------------
    // Static utility: coerce a raw Java value to fit a target DataType
    // -------------------------------------------------------------------------

    /**
     * Coerces a value obtained by evaluating a SQL literal to the target
     * column's DataType. This handles the common case where an INT literal
     * (Java Integer) is used in an INSERT/UPDATE for a LONG or DOUBLE column.
     *
     * Returns null unchanged (SQL NULL is compatible with any type).
     *
     * @param value      the value to coerce (from expression evaluation)
     * @param targetType the column's declared type
     * @param colName    column name for error messages
     * @throws ExecutionException if the value is not compatible with targetType
     */
    public static Object coerce(Object value, DataType targetType, String colName)
            throws ExecutionException {
        if (value == null) return null;   // NULL is always compatible

        return switch (targetType) {
            case INT -> {
                if (value instanceof Integer i)  yield i;
                if (value instanceof Long    l) {
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) yield (int)(long) l;
                    throw new ExecutionException(
                        "Value " + l + " is out of range for INT column '" + colName + "'");
                }
                throw typeMismatch(value, targetType, colName);
            }
            case LONG -> {
                if (value instanceof Long    l) yield l;
                if (value instanceof Integer i) yield (long)(int) i;   // widening
                throw typeMismatch(value, targetType, colName);
            }
            case DOUBLE -> {
                if (value instanceof Double  d) yield d;
                if (value instanceof Integer i) yield (double)(int) i;  // widening
                if (value instanceof Long    l) yield (double)(long) l;  // widening
                throw typeMismatch(value, targetType, colName);
            }
            case BOOLEAN -> {
                if (value instanceof Boolean b) yield b;
                throw typeMismatch(value, targetType, colName);
            }
            case TEXT -> {
                if (value instanceof String s) yield s;
                throw typeMismatch(value, targetType, colName);
            }
        };
    }

    private static ExecutionException typeMismatch(
            Object value, DataType target, String colName) {
        return new ExecutionException(String.format(
            "Type mismatch for column '%s': expected %s but got %s (%s)",
            colName, target,
            value.getClass().getSimpleName(), value));
    }
}
