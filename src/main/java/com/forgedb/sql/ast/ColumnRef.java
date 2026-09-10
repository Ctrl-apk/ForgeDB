package com.forgedb.sql.ast;

/**
 * A reference to a table column in an expression, e.g. {@code id} or
 * {@code users.id}.
 *
 * {@code tableName} is null when no table qualifier was supplied.
 */
public final class ColumnRef implements Expression {
    private final String tableName;   // nullable
    private final String columnName;

    public ColumnRef(String tableName, String columnName) {
        this.tableName  = tableName;
        this.columnName = columnName;
    }

    public ColumnRef(String columnName) {
        this(null, columnName);
    }

    /** Returns the table qualifier, or null if none was specified. */
    public String tableName()  { return tableName; }

    /** Returns the column name. Never null. */
    public String columnName() { return columnName; }

    @Override public <T> T accept(ExpressionVisitor<T> v) throws Exception {
        return v.visitColumnRef(this);
    }

    @Override public String toString() {
        return tableName == null ? columnName : tableName + "." + columnName;
    }
}
