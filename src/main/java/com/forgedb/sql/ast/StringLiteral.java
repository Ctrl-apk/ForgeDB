package com.forgedb.sql.ast;

/**
 * A string literal value, e.g. {@code 'Alice'}.
 * The value field holds the content without surrounding quotes.
 */
public final class StringLiteral implements Expression {
    private final String value;
    public StringLiteral(String value) { this.value = value; }
    public String value()              { return value; }
    @Override public <T> T accept(ExpressionVisitor<T> v) throws Exception {
        return v.visitStringLiteral(this);
    }
    @Override public String toString() { return "'" + value + "'"; }
}
