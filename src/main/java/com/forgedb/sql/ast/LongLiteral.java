package com.forgedb.sql.ast;

/** A long integer literal value, e.g. {@code 9999999999}. */
public final class LongLiteral implements Expression {
    private final long value;
    public LongLiteral(long value) { this.value = value; }
    public long value()            { return value; }
    @Override public <T> T accept(ExpressionVisitor<T> v) throws Exception {
        return v.visitLongLiteral(this);
    }
    @Override public String toString() { return value + "L"; }
}
