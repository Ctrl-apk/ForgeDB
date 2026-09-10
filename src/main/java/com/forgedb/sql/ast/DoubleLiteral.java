package com.forgedb.sql.ast;

/** A floating-point literal value, e.g. {@code 3.14}. */
public final class DoubleLiteral implements Expression {
    private final double value;
    public DoubleLiteral(double value) { this.value = value; }
    public double value()              { return value; }
    @Override public <T> T accept(ExpressionVisitor<T> v) throws Exception {
        return v.visitDoubleLiteral(this);
    }
    @Override public String toString() { return String.valueOf(value); }
}
