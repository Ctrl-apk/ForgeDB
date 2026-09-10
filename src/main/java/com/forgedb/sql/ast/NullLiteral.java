package com.forgedb.sql.ast;

/** The SQL {@code NULL} literal. */
public final class NullLiteral implements Expression {
    public static final NullLiteral INSTANCE = new NullLiteral();
    private NullLiteral() {}
    @Override public <T> T accept(ExpressionVisitor<T> v) throws Exception {
        return v.visitNullLiteral(this);
    }
    @Override public String toString() { return "NULL"; }
}
