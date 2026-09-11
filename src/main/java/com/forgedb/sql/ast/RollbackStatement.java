package com.forgedb.sql.ast;

/** AST node for {@code ROLLBACK} (abort the current transaction). */
public final class RollbackStatement implements Statement {
    public static final RollbackStatement INSTANCE = new RollbackStatement();
    private RollbackStatement() {}

    @Override
    public <T> T accept(StatementVisitor<T> visitor) throws Exception {
        return visitor.visitRollback(this);
    }

    @Override public String toString() { return "ROLLBACK"; }
}
