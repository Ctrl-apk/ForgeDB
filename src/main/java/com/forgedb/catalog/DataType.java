package com.forgedb.catalog;

/**
 * The set of scalar types supported by ForgeDB columns.
 *
 * Fixed-size types have a known, constant serialised byte size.
 * TEXT is variable-length: its serialised size depends on the string content.
 *
 * On-disk serialisation (handled by TupleSerializer):
 *
 *   INT      → 4 bytes, big-endian signed int
 *   LONG     → 8 bytes, big-endian signed long
 *   BOOLEAN  → 1 byte  (0x00 = false, 0x01 = true)
 *   DOUBLE   → 8 bytes, big-endian IEEE 754 double (via Double.doubleToLongBits)
 *   TEXT     → 4-byte big-endian int (UTF-8 byte length) + N UTF-8 bytes
 *
 * Each value is additionally preceded by a 1-byte null flag in the tuple
 * stream (0x00 = present, 0x01 = NULL).
 *
 * Adding new types in future milestones only requires adding an enum constant
 * here plus handling in TupleSerializer — no other classes need changing.
 */
public enum DataType {

    INT(true, 4),
    LONG(true, 8),
    BOOLEAN(true, 1),
    DOUBLE(true, 8),
    TEXT(false, -1);   // variable-length; -1 signals "no fixed size"

    private final boolean fixedSize;
    private final int bytes;          // -1 for variable-length types

    DataType(boolean fixedSize, int bytes) {
        this.fixedSize = fixedSize;
        this.bytes     = bytes;
    }

    /** Returns {@code true} if every value of this type occupies the same number of bytes. */
    public boolean isFixedSize() {
        return fixedSize;
    }

    /**
     * Returns the fixed byte size of a non-null value of this type.
     *
     * @throws UnsupportedOperationException if called on a variable-length type (TEXT)
     */
    public int fixedSize() {
        if (!fixedSize) {
            throw new UnsupportedOperationException(
                this + " is variable-length; fixed size is not defined");
        }
        return bytes;
    }

    /**
     * Returns the Java class that holds runtime values of this type.
     * Used for type-checking in Tuple.set().
     */
    public Class<?> javaType() {
        return switch (this) {
            case INT     -> Integer.class;
            case LONG    -> Long.class;
            case BOOLEAN -> Boolean.class;
            case DOUBLE  -> Double.class;
            case TEXT    -> String.class;
        };
    }
}
