package com.forgedb.index;

import com.forgedb.catalog.DataType;
import com.forgedb.common.ForgeDBException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An immutable, comparable, serialisable B+ tree key.
 *
 * A BTreeKey wraps a single scalar value (INT, LONG, DOUBLE, or TEXT) and
 * provides:
 *   1. Comparison — BTreeKey implements Comparable so keys can be ordered.
 *   2. Serialisation — serialize() produces a fixed or variable-length byte[]
 *      that can be written directly into a B+ tree node page.
 *   3. Deserialisation — static factory methods reconstruct a BTreeKey from
 *      raw bytes.
 *
 * Encoding strategy
 * -----------------
 * All keys are encoded big-endian (matching the rest of ForgeDB).
 *
 *   INT    → 4 bytes, raw big-endian int.
 *             Comparison: standard integer ordering.
 *
 *   LONG   → 8 bytes, raw big-endian long.
 *             Comparison: standard long ordering.
 *
 *   DOUBLE → 8 bytes. To make byte-level comparison equivalent to numeric
 *             comparison, we apply the standard IEEE 754 trick:
 *               bits = Double.doubleToLongBits(value)
 *               if (bits < 0) bits ^= 0x7FFFFFFFFFFFFFFF   (flip all except sign)
 *               if (bits >= 0) bits ^= 0x8000000000000000  (flip sign bit)
 *             Wait — simpler: we store as a long with sign-magnitude transform:
 *               long raw = Double.doubleToLongBits(d)
 *               stored  = (raw < 0) ? (raw ^ Long.MIN_VALUE) >>> 1 : (raw | Long.MIN_VALUE)
 *             Actually, the cleanest correct approach used by most databases:
 *               raw  = Double.doubleToLongBits(d)
 *               xor  = (raw >>> 63 == 1) ? 0xFFFFFFFFFFFFFFFFL : 0x8000000000000000L
 *               stored = raw ^ xor
 *             This maps the IEEE 754 space to a monotonically ordered unsigned
 *             long space. NaN is handled by doubleToLongBits (NaN → a canonical
 *             positive pattern).
 *
 *   TEXT   → 4 bytes (UTF-8 byte length) + N bytes UTF-8.
 *             Variable length. Comparison: UTF-8 lexicographic (matching Java's
 *             String.compareTo on the serialised bytes).
 *
 * On-page storage
 * ---------------
 * INT and LONG: each entry is exactly fixedSerializedSize() bytes.
 * DOUBLE: stored as the 8-byte transformed long — fixed size.
 * TEXT: 4-byte length prefix + N bytes — variable. The BTreeNode tracks
 *       the total byte size of each key slot when writing.
 *
 * Thread safety: BTreeKey is immutable and thread-safe.
 */
public final class BTreeKey implements Comparable<BTreeKey> {

    private final DataType type;
    private final Object   value;   // Integer, Long, Double, or String

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    public static BTreeKey ofInt(int v)      { return new BTreeKey(DataType.INT,    v); }
    public static BTreeKey ofLong(long v)    { return new BTreeKey(DataType.LONG,   v); }
    public static BTreeKey ofDouble(double v){ return new BTreeKey(DataType.DOUBLE, v); }
    public static BTreeKey ofText(String v)  {
        if (v == null) throw new NullPointerException("TEXT key value must not be null");
        return new BTreeKey(DataType.TEXT, v);
    }

    private BTreeKey(DataType type, Object value) {
        this.type  = type;
        this.value = value;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public DataType type()     { return type; }
    public int    asInt()      { return (Integer) value; }
    public long   asLong()     { return (Long)    value; }
    public double asDouble()   { return (Double)  value; }
    public String asText()     { return (String)  value; }
    public Object rawValue()   { return value; }

    // -------------------------------------------------------------------------
    // Serialised size
    // -------------------------------------------------------------------------

    /**
     * Returns the number of bytes that {@link #serialize()} will produce.
     * For fixed-size types this is a constant; for TEXT it depends on content.
     */
    public int serializedSize() {
        return switch (type) {
            case INT    -> 4;
            case LONG   -> 8;
            case DOUBLE -> 8;
            case TEXT   -> 4 + ((String) value).getBytes(StandardCharsets.UTF_8).length;
            default     -> throw new UnsupportedOperationException("Unsupported key type: " + type);
        };
    }

    /**
     * Returns the fixed serialised size for a given type, or -1 for TEXT.
     * Used by BTreeNode to compute page capacity.
     */
    public static int fixedSerializedSize(DataType type) {
        return switch (type) {
            case INT    -> 4;
            case LONG   -> 8;
            case DOUBLE -> 8;
            case TEXT   -> -1;  // variable
            default     -> throw new UnsupportedOperationException("Unsupported key type: " + type);
        };
    }

    // -------------------------------------------------------------------------
    // Serialisation
    // -------------------------------------------------------------------------

    /**
     * Serialises this key to a byte array.
     * The format matches the description in the class Javadoc.
     */
    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(serializedSize()).order(ByteOrder.BIG_ENDIAN);
        switch (type) {
            case INT    -> buf.putInt((Integer) value);
            case LONG   -> buf.putLong((Long) value);
            case DOUBLE -> buf.putLong(doubleToSortableLong((Double) value));
            case TEXT   -> {
                byte[] utf8 = ((String) value).getBytes(StandardCharsets.UTF_8);
                buf.putInt(utf8.length);
                buf.put(utf8);
            }
        }
        return buf.array();
    }

    // -------------------------------------------------------------------------
    // Deserialisation
    // -------------------------------------------------------------------------

    /**
     * Deserialises a key of the given type from {@code data} starting at
     * {@code offset}. Returns the key and advances the read position.
     * The caller must use {@link #serializedSize()} to know how many bytes
     * were consumed (or use {@link #deserializeWithSize}).
     */
    public static BTreeKey deserialize(DataType type, byte[] data, int offset)
            throws ForgeDBException {
        return deserializeWithSize(type, data, offset).key;
    }

    /**
     * Deserialises a key and returns both the key and the number of bytes
     * consumed. Needed for variable-length TEXT keys.
     */
    public static DeserializeResult deserializeWithSize(DataType type, byte[] data, int offset)
            throws ForgeDBException {
        if (data == null) throw new ForgeDBException("Cannot deserialise key: data is null");
        ByteBuffer buf = ByteBuffer.wrap(data, offset, data.length - offset)
                                   .order(ByteOrder.BIG_ENDIAN);
        try {
            return switch (type) {
                case INT -> {
                    int v = buf.getInt();
                    yield new DeserializeResult(ofInt(v), 4);
                }
                case LONG -> {
                    long v = buf.getLong();
                    yield new DeserializeResult(ofLong(v), 8);
                }
                case DOUBLE -> {
                    long bits = buf.getLong();
                    yield new DeserializeResult(ofDouble(sortableLongToDouble(bits)), 8);
                }
                case TEXT -> {
                    int len  = buf.getInt();
                    byte[] utf8 = new byte[len];
                    buf.get(utf8);
                    yield new DeserializeResult(ofText(new String(utf8, StandardCharsets.UTF_8)),
                                                4 + len);
                }
                default -> throw new ForgeDBException("Unsupported key type: " + type);
            };
        } catch (java.nio.BufferUnderflowException e) {
            throw new ForgeDBException("Truncated key bytes for type " + type, e);
        }
    }

    /** Result holder for deserialiseWithSize. */
    public static final class DeserializeResult {
        public final BTreeKey key;
        public final int      bytesConsumed;
        DeserializeResult(BTreeKey key, int bytesConsumed) {
            this.key = key;
            this.bytesConsumed = bytesConsumed;
        }
    }

    // -------------------------------------------------------------------------
    // Comparison
    // -------------------------------------------------------------------------

    @Override
    public int compareTo(BTreeKey other) {
        if (this.type != other.type) {
            throw new IllegalArgumentException(
                "Cannot compare keys of different types: " + this.type + " vs " + other.type);
        }
        return switch (type) {
            case INT    -> Integer.compare((Integer)  value, (Integer)  other.value);
            case LONG   -> Long.compare   ((Long)     value, (Long)     other.value);
            case DOUBLE -> Double.compare ((Double)   value, (Double)   other.value);
            case TEXT   -> ((String) value).compareTo((String) other.value);
            default     -> throw new UnsupportedOperationException("Unsupported key type: " + type);
        };
    }

    // -------------------------------------------------------------------------
    // Double ↔ sortable-long transform
    // -------------------------------------------------------------------------

    /**
     * Converts a double to a long whose natural (unsigned) ordering matches
     * the double's numeric ordering. This makes byte-level comparison of the
     * serialised long equivalent to numeric comparison of the original doubles.
     *
     * Algorithm (standard IEEE 754 sort trick):
     *   raw  = doubleToLongBits(d)
     *   if raw is negative (i.e. the double was negative or -0.0):
     *     flip all 64 bits so negative doubles map below positive doubles
     *   else:
     *     flip only the sign bit so +0.0 and positive doubles are in order
     *
     * NaN: doubleToLongBits canonicalises all NaN variants to one pattern
     * (0x7FF8000000000000), which ends up sorted after all finite positives.
     */
    static long doubleToSortableLong(double d) {
        long bits = Double.doubleToLongBits(d);
        return (bits < 0) ? ~bits : (bits ^ Long.MIN_VALUE);
    }

    static double sortableLongToDouble(long sortable) {
        // Inverse: if the sign bit of sortable is 1, the original was positive → flip sign bit back
        //          if the sign bit is 0, the original was negative → flip all bits
        long bits = (sortable & Long.MIN_VALUE) != 0 ? (sortable ^ Long.MIN_VALUE) : ~sortable;
        return Double.longBitsToDouble(bits);
    }

    // -------------------------------------------------------------------------
    // equals / hashCode / toString
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BTreeKey other)) return false;
        return type == other.type && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + value.hashCode();
    }

    @Override
    public String toString() {
        return "BTreeKey(" + type + ":" + value + ")";
    }
}
