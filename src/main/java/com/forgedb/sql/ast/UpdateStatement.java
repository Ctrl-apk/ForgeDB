package com.forgedb.sql.ast;

import java.util.List;

/**
 * AST node for {@code UPDATE tableName SET col=val [, col=val ...] [WHERE condition]}.
 *
 * {@code whereClause} is null when no WHERE clause was provided.
 */
public final class UpdateStatement implements Statement {
    private final String         tableName;
    private final List<SetClause> setClauses;
    private final Expression      whereClause;   // nullable

    public UpdateStatement(String tableName,
                           List<SetClause> setClauses,
                           Expression whereClause) {
        this.tableName   = tableName;
        this.setClauses  = List.copyOf(setClauses);
        this.whereClause = whereClause;
    }

    public String          tableName()   { return tableName; }
    public List<SetClause> setClauses()  { return setClauses; }
    public Expression      whereClause() { return whereClause; }

    @Override public <T> T accept(StatementVisitor<T> v) throws Exception {
        return v.visitUpdate(this);
    }

    @Override public String toString() {
        String w = whereClause == null ? "" : " WHERE " + whereClause;
        return "UPDATE " + tableName + " SET " + setClauses + w;
    }
}
