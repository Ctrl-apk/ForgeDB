package com.forgedb.sql.ast;

import com.forgedb.catalog.DataType;

/**
 * A column definition inside a CREATE TABLE statement:
 * {@code columnName TYPE}
 * e.g. {@code id INT}, {@code name TEXT}.
 */
public final class ColumnDef {
    private final String   name;
    private final DataType type;

    public ColumnDef(String name, DataType type) {
        this.name = name;
        this.type = type;
    }

    public String   name() { return name; }
    public DataType type() { return type; }

    @Override public String toString() { return name + " " + type; }
}
