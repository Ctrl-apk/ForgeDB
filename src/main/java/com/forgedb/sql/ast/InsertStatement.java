package com.forgedb.sql.ast;

import java.util.List;

/**
 * AST node for {@code INSERT INTO tableName VALUES (v1, v2, ...)}.
 *
 * Optional column list syntax is also supported:
 * {@code INSERT INTO tableName (col1, col2) VALUES (v1, v2)}.
 * When no column list is given, {@link #columnNames()} is an empty list.
 */
public final class InsertStatement implements Statement {
    private final String          tableName;
    private final List<String>    columnNames;   // empty = all columns in schema order
    private final List<Expression> values;

    public InsertStatement(String tableName,
                           List<String>    columnNames,
                           List<Expression> values) {
        this.tableName   = tableName;
        this.columnNames = List.copyOf(columnNames);
        this.values      = List.copyOf(values);
    }

    public String           tableName()   { return tableName; }
    public List<String>     columnNames() { return columnNames; }
    public List<Expression> values()      { return values; }

    @Override public <T> T accept(StatementVisitor<T> v) throws Exception {
        return v.visitInsert(this);
    }

    @Override public String toString() {
        return "INSERT INTO " + tableName + " VALUES " + values;
    }
}
