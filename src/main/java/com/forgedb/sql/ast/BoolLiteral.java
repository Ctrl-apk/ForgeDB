package com.forgedb.sql.ast;

/** A boolean literal: {@code TRUE} or {@code FALSE}. */
public final class BoolLiteral implements Expression {
    private final boolean value;
    public BoolLiteral(boolean value) { this.value = value; }
    public boolean value()            { return value; }
    @Override public <T> T accept(ExpressionVisitor<T> v) throws Exception {
        return v.visitBoolLiteral(this);
    }
    @Override public String toString() { return value ? "TRUE" : "FALSE"; }
}
