package com.forgedb.sql.ast;

/**
 * AST node for {@code DELETE FROM tableName [WHERE condition]}.
 *
 * {@code whereClause} is null when no WHERE clause was provided
 * (meaning delete all rows).
 */
public final class DeleteStatement implements Statement {
    private final String     tableName;
    private final Expression whereClause;   // nullable

    public DeleteStatement(String tableName, Expression whereClause) {
        this.tableName   = tableName;
        this.whereClause = whereClause;
    }

    public String     tableName()   { return tableName; }
    public Expression whereClause() { return whereClause; }

    @Override public <T> T accept(StatementVisitor<T> v) throws Exception {
        return v.visitDelete(this);
    }

    @Override public String toString() {
        String w = whereClause == null ? "" : " WHERE " + whereClause;
        return "DELETE FROM " + tableName + w;
    }
}
