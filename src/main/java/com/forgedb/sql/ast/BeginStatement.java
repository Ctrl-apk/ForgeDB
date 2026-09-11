package com.forgedb.sql.ast;

/** AST node for {@code BEGIN} (start a transaction). */
public final class BeginStatement implements Statement {
    public static final BeginStatement INSTANCE = new BeginStatement();
    private BeginStatement() {}

    @Override
    public <T> T accept(StatementVisitor<T> visitor) throws Exception {
        return visitor.visitBegin(this);
    }

    @Override public String toString() { return "BEGIN"; }
}
