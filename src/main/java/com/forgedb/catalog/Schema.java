package com.forgedb.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes the structure of a table: an ordered list of named, typed columns.
 *
 * Schema is immutable after construction. The column order is significant —
 * it determines the field order in every serialised tuple that uses this schema.
 *
 * Column names must be unique within a schema (case-sensitive). Duplicate
 * names are rejected at construction time.
 *
 * Design note: Schema lives in the {@code catalog} package because it describes
 * the logical meaning of data. The storage layer (Page, DataPage, HeapFile)
 * accepts a Schema but does not own or interpret it beyond passing it to
 * TupleSerializer.
 */
public final class Schema {

    /** Ordered list of columns. Immutable after construction. */
    private final List<Column> columns;

    /**
     * Name → index map for O(1) column lookup by name.
     * LinkedHashMap preserves insertion order (matches columns list order).
     */
    private final Map<String, Integer> nameToIndex;

    /**
     * Constructs a Schema from a list of columns.
     *
     * @param columns ordered list of columns; must not be null, must not be empty,
     *                no two columns may share the same name
     * @throws IllegalArgumentException if columns is null, empty, or contains
     *                                  duplicate column names
     */
    public Schema(List<Column> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException(
                "Schema must have at least one column");
        }

        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            Column col = columns.get(i);
            if (col == null) {
                throw new IllegalArgumentException(
                    "Column at index " + i + " is null");
            }
            if (idx.containsKey(col.name())) {
                throw new IllegalArgumentException(
                    "Duplicate column name: \"" + col.name() + "\"");
            }
            idx.put(col.name(), i);
        }

        this.columns     = Collections.unmodifiableList(new ArrayList<>(columns));
        this.nameToIndex = Collections.unmodifiableMap(idx);
    }

    /**
     * Convenience factory that accepts varargs instead of a List.
     *
     * @param columns one or more Column instances
     */
    public static Schema of(Column... columns) {
        return new Schema(List.of(columns));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the number of columns in this schema. */
    public int columnCount() {
        return columns.size();
    }

    /**
     * Returns the column at the given zero-based index.
     *
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public Column getColumn(int index) {
        if (index < 0 || index >= columns.size()) {
            throw new IndexOutOfBoundsException(
                "Column index " + index + " out of range [0, " + columns.size() + ")");
        }
        return columns.get(index);
    }

    /**
     * Returns the column with the given name, or throws if not found.
     *
     * @throws IllegalArgumentException if no column has this name
     */
    public Column getColumn(String name) {
        int idx = getColumnIndex(name);
        return columns.get(idx);
    }

    /**
     * Returns the zero-based index of the column with the given name.
     *
     * @throws IllegalArgumentException if no column has this name
     */
    public int getColumnIndex(String name) {
        Integer idx = nameToIndex.get(name);
        if (idx == null) {
            throw new IllegalArgumentException(
                "No column named \"" + name + "\" in schema " + this);
        }
        return idx;
    }

    /** Returns true if the schema contains a column with the given name. */
    public boolean hasColumn(String name) {
        return nameToIndex.containsKey(name);
    }

    /** Returns an unmodifiable view of the column list. */
    public List<Column> columns() {
        return columns;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Schema other)) return false;
        return columns.equals(other.columns);
    }

    @Override
    public int hashCode() {
        return columns.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Schema(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(columns.get(i));
        }
        sb.append(")");
        return sb.toString();
    }
}
