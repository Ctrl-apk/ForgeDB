package com.forgedb.sql.ast;

/** An integer literal value in a SQL expression, e.g. {@code 42}. */
public final class IntLiteral implements Expression {
    private final int value;
    public IntLiteral(int value)   { this.value = value; }
    public int value()             { return value; }
    @Override public <T> T accept(ExpressionVisitor<T> v) throws Exception {
        return v.visitIntLiteral(this);
    }
    @Override public String toString() { return String.valueOf(value); }
}
