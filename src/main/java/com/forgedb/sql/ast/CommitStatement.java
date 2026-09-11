package com.forgedb.sql.ast;

/** AST node for {@code COMMIT} (commit the current transaction). */
public final class CommitStatement implements Statement {
    public static final CommitStatement INSTANCE = new CommitStatement();
    private CommitStatement() {}

    @Override
    public <T> T accept(StatementVisitor<T> visitor) throws Exception {
        return visitor.visitCommit(this);
    }

    @Override public String toString() { return "COMMIT"; }
}
