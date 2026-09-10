package com.forgedb.sql.ast;

/**
 * A binary infix expression: {@code left op right}.
 *
 * Operators:
 *   Comparison : =  <>  <  <=  >  >=
 *   Logical    : AND  OR
 *   Arithmetic : +  -  (reserved for Milestone 6+)
 */
public final class BinaryExpression implements Expression {

    /** The set of operators supported in binary expressions. */
    public enum Op {
        EQ, NEQ, LT, LTE, GT, GTE,   // comparison
        AND, OR,                       // logical
        PLUS, MINUS                    // arithmetic (reserved)
    }

    private final Expression left;
    private final Op         op;
    private final Expression right;

    public BinaryExpression(Expression left, Op op, Expression right) {
        this.left  = left;
        this.op    = op;
        this.right = right;
    }

    public Expression left()  { return left; }
    public Op         op()    { return op; }
    public Expression right() { return right; }

    @Override public <T> T accept(ExpressionVisitor<T> v) throws Exception {
        return v.visitBinaryExpr(this);
    }

    @Override public String toString() {
        return "(" + left + " " + op + " " + right + ")";
    }
}
