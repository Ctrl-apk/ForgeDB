package com.forgedb.catalog;

import java.util.Objects;

/**
 * Describes one column in a table schema.
 *
 * A Column is an immutable value object: once constructed, its name and type
 * never change. This makes Schema safe to share across threads and to use as
 * a stable reference from HeapFile, TupleSerializer, and the future query
 * engine.
 *
 * Column names are stored exactly as supplied (case-sensitive). Callers that
 * want case-insensitive lookup should normalise before constructing.
 */
public final class Column {

    private final String   name;
    private final DataType type;

    /**
     * @param name  column name — must be non-null and non-blank
     * @param type  column data type — must be non-null
     * @throws IllegalArgumentException if name is null, empty, or blank
     * @throws NullPointerException     if type is null
     */
    public Column(String name, DataType type) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                "Column name must be non-null and non-blank, got: " +
                (name == null ? "null" : "\"" + name + "\""));
        }
        this.name = name;
        this.type = Objects.requireNonNull(type, "Column type must not be null");
    }

    /** Returns the column name. */
    public String name() {
        return name;
    }

    /** Returns the column data type. */
    public DataType type() {
        return type;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Column other)) return false;
        return name.equals(other.name) && type == other.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return name + " " + type;
    }
}
