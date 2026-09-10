package com.forgedb.catalog;

import java.util.Arrays;

/**
 * A single row of typed values, bound to a Schema.
 *
 * Values are stored as Object[] internally. Each position corresponds to the
 * column at the same index in the associated Schema. A null value at any
 * position represents SQL NULL.
 *
 * Type safety
 * -----------
 * Tuple enforces type compatibility on every set() call: the supplied value's
 * runtime class must be assignable to the DataType's javaType(). Null is
 * always accepted regardless of type.
 *
 * Mutability
 * ----------
 * Tuple is mutable so the execution engine can build rows incrementally.
 * If immutability is needed (e.g. for returning results from a scan) the
 * caller should use copy().
 *
 * Usage example:
 * <pre>
 *   Schema s = Schema.of(new Column("id", DataType.INT),
 *                        new Column("name", DataType.TEXT));
 *   Tuple t = new Tuple(s);
 *   t.setInt(0, 42);
 *   t.setString(1, "Alice");
 *   int id = t.getInt(0);     // 42
 *   String name = t.getString(1); // "Alice"
 * </pre>
 */
public final class Tuple {

    private final Schema   schema;
    private final Object[] values;

    /**
     * Creates a tuple with all values set to NULL.
     *
     * @param schema the schema this tuple conforms to; must not be null
     */
    public Tuple(Schema schema) {
        if (schema == null) throw new NullPointerException("schema must not be null");
        this.schema = schema;
        this.values = new Object[schema.columnCount()];
        // All elements are already null (Java default for Object[])
    }

    /**
     * Creates a tuple from an existing value array.
     * The array is defensively copied, so the caller can modify it after
     * construction without affecting this Tuple.
     *
     * @param schema schema this tuple conforms to
     * @param values values in schema column order; must have same length as schema
     * @throws IllegalArgumentException if values.length != schema.columnCount()
     */
    public Tuple(Schema schema, Object[] values) {
        if (schema == null) throw new NullPointerException("schema must not be null");
        if (values == null) throw new NullPointerException("values must not be null");
        if (values.length != schema.columnCount()) {
            throw new IllegalArgumentException(String.format(
                "values.length (%d) != schema.columnCount() (%d)",
                values.length, schema.columnCount()));
        }
        this.schema = schema;
        this.values = Arrays.copyOf(values, values.length);
        // Validate each non-null value against its column type
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                checkType(i, values[i]);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Schema access
    // -------------------------------------------------------------------------

    /** Returns the schema this tuple is bound to. */
    public Schema schema() {
        return schema;
    }

    // -------------------------------------------------------------------------
    // Generic get / set
    // -------------------------------------------------------------------------

    /**
     * Returns the raw value at the given column index, or null if the value is NULL.
     *
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public Object get(int index) {
        checkIndex(index);
        return values[index];
    }

    /**
     * Returns true if the value at the given column index is NULL.
     *
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public boolean isNull(int index) {
        checkIndex(index);
        return values[index] == null;
    }

    /**
     * Sets the value at the given column index.
     * Pass null to set SQL NULL.
     *
     * @throws IndexOutOfBoundsException if index is out of range
     * @throws IllegalArgumentException  if value type is incompatible with the column's DataType
     */
    public void set(int index, Object value) {
        checkIndex(index);
        if (value != null) checkType(index, value);
        values[index] = value;
    }

    // -------------------------------------------------------------------------
    // Typed getters
    // -------------------------------------------------------------------------

    /**
     * Returns the INT value at the given index.
     *
     * @throws IllegalStateException     if the value is NULL
     * @throws IllegalArgumentException  if the column is not INT
     */
    public int getInt(int index) {
        checkColumnType(index, DataType.INT);
        Object v = getNotNull(index);
        return (Integer) v;
    }

    /**
     * Returns the LONG value at the given index.
     *
     * @throws IllegalStateException     if the value is NULL
     * @throws IllegalArgumentException  if the column is not LONG
     */
    public long getLong(int index) {
        checkColumnType(index, DataType.LONG);
        Object v = getNotNull(index);
        return (Long) v;
    }

    /**
     * Returns the BOOLEAN value at the given index.
     *
     * @throws IllegalStateException     if the value is NULL
     * @throws IllegalArgumentException  if the column is not BOOLEAN
     */
    public boolean getBoolean(int index) {
        checkColumnType(index, DataType.BOOLEAN);
        Object v = getNotNull(index);
        return (Boolean) v;
    }

    /**
     * Returns the DOUBLE value at the given index.
     *
     * @throws IllegalStateException     if the value is NULL
     * @throws IllegalArgumentException  if the column is not DOUBLE
     */
    public double getDouble(int index) {
        checkColumnType(index, DataType.DOUBLE);
        Object v = getNotNull(index);
        return (Double) v;
    }

    /**
     * Returns the TEXT value at the given index.
     *
     * @throws IllegalStateException     if the value is NULL
     * @throws IllegalArgumentException  if the column is not TEXT
     */
    public String getString(int index) {
        checkColumnType(index, DataType.TEXT);
        Object v = getNotNull(index);
        return (String) v;
    }

    // -------------------------------------------------------------------------
    // Typed setters
    // -------------------------------------------------------------------------

    /** Sets an INT value at the given column index. */
    public void setInt(int index, int value) {
        checkColumnType(index, DataType.INT);
        values[index] = value;
    }

    /** Sets a LONG value at the given column index. */
    public void setLong(int index, long value) {
        checkColumnType(index, DataType.LONG);
        values[index] = value;
    }

    /** Sets a BOOLEAN value at the given column index. */
    public void setBoolean(int index, boolean value) {
        checkColumnType(index, DataType.BOOLEAN);
        values[index] = value;
    }

    /** Sets a DOUBLE value at the given column index. */
    public void setDouble(int index, double value) {
        checkColumnType(index, DataType.DOUBLE);
        values[index] = value;
    }

    /** Sets a TEXT value at the given column index. */
    public void setString(int index, String value) {
        checkColumnType(index, DataType.TEXT);
        values[index] = value;  // null allowed — represents SQL NULL
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Returns a deep copy of this tuple. The copy shares the same Schema
     * reference but has an independent values array.
     */
    public Tuple copy() {
        Tuple copy = new Tuple(schema);
        System.arraycopy(values, 0, copy.values, 0, values.length);
        return copy;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Tuple(");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(schema.getColumn(i).name()).append("=");
            sb.append(values[i] == null ? "NULL" : values[i]);
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Tuple other)) return false;
        return schema.equals(other.schema) && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * schema.hashCode() + Arrays.hashCode(values);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void checkIndex(int index) {
        if (index < 0 || index >= values.length) {
            throw new IndexOutOfBoundsException(
                "Column index " + index + " out of range [0, " + values.length + ")");
        }
    }

    private void checkColumnType(int index, DataType expected) {
        checkIndex(index);
        DataType actual = schema.getColumn(index).type();
        if (actual != expected) {
            throw new IllegalArgumentException(String.format(
                "Column %d (\"%s\") is %s, not %s",
                index, schema.getColumn(index).name(), actual, expected));
        }
    }

    private void checkType(int index, Object value) {
        DataType expected = schema.getColumn(index).type();
        Class<?> expectedClass = expected.javaType();
        if (!expectedClass.isInstance(value)) {
            throw new IllegalArgumentException(String.format(
                "Column %d (\"%s\") expects %s (%s) but got %s",
                index, schema.getColumn(index).name(),
                expected, expectedClass.getSimpleName(),
                value.getClass().getSimpleName()));
        }
    }

    private Object getNotNull(int index) {
        Object v = values[index];
        if (v == null) {
            throw new IllegalStateException(
                "Column " + index + " (\"" + schema.getColumn(index).name() + "\") is NULL");
        }
        return v;
    }
}
