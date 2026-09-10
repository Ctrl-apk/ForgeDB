package com.forgedb.sql.ast;

/**
 * A unary prefix expression: {@code op expr}.
 * Currently only {@code NOT} is a unary operator in ForgeDB SQL.
 */
public final class UnaryExpression implements Expression {

    public enum Op { NOT }

    private final Op         op;
    private final Expression operand;

    public UnaryExpression(Op op, Expression operand) {
        this.op      = op;
        this.operand = operand;
    }

    public Op         op()      { return op; }
    public Expression operand() { return operand; }

    @Override public <T> T accept(ExpressionVisitor<T> v) throws Exception {
        return v.visitUnaryExpr(this);
    }

    @Override public String toString() { return "(" + op + " " + operand + ")"; }
}
