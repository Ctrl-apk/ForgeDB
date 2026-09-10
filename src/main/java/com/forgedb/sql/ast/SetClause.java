package com.forgedb.sql.ast;

/**
 * One assignment in an UPDATE statement's SET list:
 * {@code columnName = value}.
 */
public final class SetClause {
    private final String     columnName;
    private final Expression value;

    public SetClause(String columnName, Expression value) {
        this.columnName = columnName;
        this.value      = value;
    }

    public String     columnName() { return columnName; }
    public Expression value()      { return value; }

    @Override public String toString() { return columnName + " = " + value; }
}
