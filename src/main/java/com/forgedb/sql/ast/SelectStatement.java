package com.forgedb.sql.ast;

import java.util.List;

/**
 * AST node for a SELECT statement.
 *
 * Grammar subset supported:
 * {@code SELECT selectList FROM tableName [WHERE condition]}
 *
 * {@code whereClause} is null when no WHERE clause was provided.
 */
public final class SelectStatement implements Statement {
    private final List<SelectItem> selectList;
    private final String           tableName;
    private final Expression       whereClause;   // nullable

    public SelectStatement(List<SelectItem> selectList,
                           String tableName,
                           Expression whereClause) {
        this.selectList  = List.copyOf(selectList);
        this.tableName   = tableName;
        this.whereClause = whereClause;
    }

    public List<SelectItem> selectList()  { return selectList; }
    public String           tableName()   { return tableName; }
    public Expression       whereClause() { return whereClause; }

    @Override public <T> T accept(StatementVisitor<T> v) throws Exception {
        return v.visitSelect(this);
    }

    @Override public String toString() {
        String w = whereClause == null ? "" : " WHERE " + whereClause;
        return "SELECT " + selectList + " FROM " + tableName + w;
    }
}
