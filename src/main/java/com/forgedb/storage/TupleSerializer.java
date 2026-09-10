package com.forgedb.storage;

import com.forgedb.catalog.DataType;
import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;
import com.forgedb.common.ForgeDBException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Serialises and deserialises Tuples to/from a compact binary format.
 *
 * This class uses explicit ByteBuffer manipulation (big-endian, matching the
 * rest of the codebase). It never uses Java's ObjectOutputStream, Serializable,
 * or reflection.
 *
 * On-disk tuple format
 * --------------------
 * For each column in Schema order:
 *
 *   1 byte   null flag:  0x00 = value present, 0x01 = NULL
 *
 *   if null flag == 0x00 (value present):
 *     INT     → 4 bytes, big-endian signed int
 *     LONG    → 8 bytes, big-endian signed long
 *     BOOLEAN → 1 byte  (0x00 = false, 0x01 = true)
 *     DOUBLE  → 8 bytes, big-endian IEEE 754 (via Double.doubleToLongBits)
 *     TEXT    → 4-byte big-endian int (UTF-8 byte count) + N bytes UTF-8
 *
 *   if null flag == 0x01 (NULL):
 *     (no further bytes for this column)
 *
 * The tuple format is not self-describing: you must supply the same Schema
 * used during serialisation to correctly deserialise. The Schema determines
 * which columns are present and in what order.
 *
 * Thread safety
 * -------------
 * All methods are stateless and static. TupleSerializer is thread-safe.
 */
public final class TupleSerializer {

    private TupleSerializer() { /* static utility class */ }

    // -------------------------------------------------------------------------
    // Null-flag constants
    // -------------------------------------------------------------------------

    private static final byte NULL_FLAG_PRESENT = 0x00;
    private static final byte NULL_FLAG_NULL    = 0x01;

    // -------------------------------------------------------------------------
    // Size calculation
    // -------------------------------------------------------------------------

    /**
     * Returns the exact number of bytes that {@link #serialize} will produce
     * for the given tuple. Use this to check whether a record will fit in a
     * page before attempting to insert it.
     *
     * @param schema schema the tuple conforms to
     * @param tuple  tuple to measure
     * @return exact serialised byte count
     */
    public static int serializedSize(Schema schema, Tuple tuple) {
        int size = 0;
        for (int i = 0; i < schema.columnCount(); i++) {
            size += 1; // null flag
            if (!tuple.isNull(i)) {
                size += valueSize(schema.getColumn(i).type(), tuple.get(i));
            }
        }
        return size;
    }

    // -------------------------------------------------------------------------
    // Serialisation
    // -------------------------------------------------------------------------

    /**
     * Serialises a Tuple to a new byte array.
     *
     * @param schema schema the tuple conforms to
     * @param tuple  tuple to serialise
     * @return serialised bytes; length == serializedSize(schema, tuple)
     * @throws ForgeDBException if a TEXT value cannot be encoded
     */
    public static byte[] serialize(Schema schema, Tuple tuple) throws ForgeDBException {
        int size = serializedSize(schema, tuple);
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < schema.columnCount(); i++) {
            if (tuple.isNull(i)) {
                buf.put(NULL_FLAG_NULL);
                // No value bytes for NULL columns
            } else {
                buf.put(NULL_FLAG_PRESENT);
                writeValue(buf, schema.getColumn(i).type(), tuple.get(i));
            }
        }

        return buf.array();
    }

    // -------------------------------------------------------------------------
    // Deserialisation
    // -------------------------------------------------------------------------

    /**
     * Deserialises a Tuple from raw bytes.
     *
     * @param schema schema to use for interpreting the bytes
     * @param data   source byte array
     * @param offset starting position within data
     * @param length number of bytes to read (must match the serialised size exactly)
     * @return a new Tuple with all values populated
     * @throws ForgeDBException if the bytes are truncated or otherwise malformed
     */
    public static Tuple deserialize(Schema schema, byte[] data, int offset, int length)
            throws ForgeDBException {
        if (data == null) {
            throw new ForgeDBException("Cannot deserialise: data is null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new ForgeDBException(String.format(
                "Cannot deserialise: out of bounds (data.length=%d, offset=%d, length=%d)",
                data.length, offset, length));
        }

        ByteBuffer buf = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN);
        Tuple tuple = new Tuple(schema);

        try {
            for (int i = 0; i < schema.columnCount(); i++) {
                byte nullFlag = buf.get();
                if (nullFlag == NULL_FLAG_NULL) {
                    tuple.set(i, null); // explicit NULL
                } else if (nullFlag == NULL_FLAG_PRESENT) {
                    Object value = readValue(buf, schema.getColumn(i).type());
                    tuple.set(i, value);
                } else {
                    throw new ForgeDBException(String.format(
                        "Malformed tuple: unexpected null flag 0x%02X at column %d (\"%s\")",
                        nullFlag, i, schema.getColumn(i).name()));
                }
            }
        } catch (java.nio.BufferUnderflowException e) {
            throw new ForgeDBException("Truncated tuple data: ran out of bytes during deserialisation", e);
        }

        return tuple;
    }

    // -------------------------------------------------------------------------
    // Internal write helpers
    // -------------------------------------------------------------------------

    private static void writeValue(ByteBuffer buf, DataType type, Object value)
            throws ForgeDBException {
        switch (type) {
            case INT     -> buf.putInt((Integer) value);
            case LONG    -> buf.putLong((Long) value);
            case BOOLEAN -> buf.put((byte) (((Boolean) value) ? 1 : 0));
            case DOUBLE  -> buf.putLong(Double.doubleToLongBits((Double) value));
            case TEXT    -> writeText(buf, (String) value);
        }
    }

    private static void writeText(ByteBuffer buf, String text) throws ForgeDBException {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > Short.MAX_VALUE * 2) {   // sanity cap: ~64KB per text field
            throw new ForgeDBException(String.format(
                "TEXT value too long: %d bytes (max %d)", utf8.length, Short.MAX_VALUE * 2));
        }
        buf.putInt(utf8.length);
        buf.put(utf8);
    }

    // -------------------------------------------------------------------------
    // Internal read helpers
    // -------------------------------------------------------------------------

    private static Object readValue(ByteBuffer buf, DataType type) {
        return switch (type) {
            case INT     -> buf.getInt();
            case LONG    -> buf.getLong();
            case BOOLEAN -> buf.get() != 0;
            case DOUBLE  -> Double.longBitsToDouble(buf.getLong());
            case TEXT    -> readText(buf);
        };
    }

    private static String readText(ByteBuffer buf) {
        int len = buf.getInt();
        byte[] utf8 = new byte[len];
        buf.get(utf8);
        return new String(utf8, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Size of a single non-null value
    // -------------------------------------------------------------------------

    private static int valueSize(DataType type, Object value) {
        return switch (type) {
            case INT     -> 4;
            case LONG    -> 8;
            case BOOLEAN -> 1;
            case DOUBLE  -> 8;
            case TEXT    -> 4 + ((String) value).getBytes(StandardCharsets.UTF_8).length;
        };
    }
}
