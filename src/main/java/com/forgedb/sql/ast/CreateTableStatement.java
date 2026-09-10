package com.forgedb.sql.ast;

import java.util.List;

/**
 * AST node for {@code CREATE TABLE tableName (col1 TYPE1, col2 TYPE2, ...)}.
 */
public final class CreateTableStatement implements Statement {
    private final String         tableName;
    private final List<ColumnDef> columns;

    public CreateTableStatement(String tableName, List<ColumnDef> columns) {
        this.tableName = tableName;
        this.columns   = List.copyOf(columns);
    }

    public String          tableName() { return tableName; }
    public List<ColumnDef> columns()   { return columns; }

    @Override public <T> T accept(StatementVisitor<T> v) throws Exception {
        return v.visitCreateTable(this);
    }

    @Override public String toString() {
        return "CREATE TABLE " + tableName + " " + columns;
    }
}
