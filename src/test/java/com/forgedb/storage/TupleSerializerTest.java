package com.forgedb.storage;

import com.forgedb.catalog.*;
import com.forgedb.common.ForgeDBException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TupleSerializer.
 * Covers serialisation round-trips, NULL handling, TEXT edge cases,
 * size calculation, and malformed-data error detection.
 */
class TupleSerializerTest {

    private Schema schema;

    @BeforeEach
    void setUp() {
        schema = Schema.of(
            new Column("id",     DataType.INT),
            new Column("name",   DataType.TEXT),
            new Column("active", DataType.BOOLEAN),
            new Column("score",  DataType.DOUBLE),
            new Column("serial", DataType.LONG)
        );
    }

    // =========================================================================
    // Round-trip correctness
    // =========================================================================

    @Test
    @DisplayName("All fixed-size types serialise and deserialise correctly")
    void roundTrip_fixedTypes() throws ForgeDBException {
        Tuple original = new Tuple(schema,
            new Object[]{42, "Alice", true, 3.14, 100L});

        byte[] bytes = TupleSerializer.serialize(schema, original);
        Tuple  back  = TupleSerializer.deserialize(schema, bytes, 0, bytes.length);

        assertEquals(42,     back.getInt(0));
        assertEquals("Alice",back.getString(1));
        assertTrue(back.getBoolean(2));
        assertEquals(3.14,   back.getDouble(3), 1e-15);
        assertEquals(100L,   back.getLong(4));
    }

    @Test
    @DisplayName("Negative and extreme int values round-trip correctly")
    void roundTrip_intExtremes() throws ForgeDBException {
        Schema s = Schema.of(new Column("v", DataType.INT));

        for (int v : new int[]{0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            Tuple t = new Tuple(s);
            t.setInt(0, v);
            byte[] bytes = TupleSerializer.serialize(s, t);
            Tuple back   = TupleSerializer.deserialize(s, bytes, 0, bytes.length);
            assertEquals(v, back.getInt(0), "Failed for value " + v);
        }
    }

    @Test
    @DisplayName("Long extremes round-trip correctly")
    void roundTrip_longExtremes() throws ForgeDBException {
        Schema s = Schema.of(new Column("v", DataType.LONG));

        for (long v : new long[]{0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            Tuple t = new Tuple(s);
            t.setLong(0, v);
            byte[] bytes = TupleSerializer.serialize(s, t);
            Tuple back   = TupleSerializer.deserialize(s, bytes, 0, bytes.length);
            assertEquals(v, back.getLong(0), "Failed for value " + v);
        }
    }

    @Test
    @DisplayName("Double special values (NaN, Infinity) round-trip correctly")
    void roundTrip_doubleSpecialValues() throws ForgeDBException {
        Schema s = Schema.of(new Column("v", DataType.DOUBLE));

        double[] vals = {0.0, -0.0, 1.0, -1.0,
                         Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                         Double.MAX_VALUE, Double.MIN_VALUE};
        for (double v : vals) {
            Tuple t = new Tuple(s);
            t.setDouble(0, v);
            byte[] bytes = TupleSerializer.serialize(s, t);
            Tuple back   = TupleSerializer.deserialize(s, bytes, 0, bytes.length);
            // Use bit-pattern equality so NaN == NaN passes
            assertEquals(Double.doubleToLongBits(v),
                         Double.doubleToLongBits(back.getDouble(0)),
                         "Failed for value " + v);
        }
    }

    // =========================================================================
    // TEXT edge cases
    // =========================================================================

    @Test
    @DisplayName("Empty string serialises and deserialises correctly")
    void roundTrip_emptyString() throws ForgeDBException {
        Schema s = Schema.of(new Column("t", DataType.TEXT));
        Tuple t  = new Tuple(s);
        t.setString(0, "");

        byte[] bytes = TupleSerializer.serialize(s, t);
        Tuple back   = TupleSerializer.deserialize(s, bytes, 0, bytes.length);
        assertEquals("", back.getString(0));
    }

    @Test
    @DisplayName("Multi-byte Unicode string serialises and deserialises correctly")
    void roundTrip_unicodeString() throws ForgeDBException {
        Schema s = Schema.of(new Column("t", DataType.TEXT));
        String text = "Hello 世界 \uD83D\uDE00 café";  // Chinese + emoji + accent
        Tuple t = new Tuple(s);
        t.setString(0, text);

        byte[] bytes = TupleSerializer.serialize(s, t);
        Tuple back   = TupleSerializer.deserialize(s, bytes, 0, bytes.length);
        assertEquals(text, back.getString(0));
    }

    @Test
    @DisplayName("Long string (4000 chars) round-trips correctly")
    void roundTrip_longString() throws ForgeDBException {
        Schema s = Schema.of(new Column("t", DataType.TEXT));
        String text = "x".repeat(4000);
        Tuple t = new Tuple(s);
        t.setString(0, text);

        byte[] bytes = TupleSerializer.serialize(s, t);
        Tuple back   = TupleSerializer.deserialize(s, bytes, 0, bytes.length);
        assertEquals(text, back.getString(0));
    }

    // =========================================================================
    // NULL handling
    // =========================================================================

    @Test
    @DisplayName("All-NULL tuple serialises and deserialises correctly")
    void roundTrip_allNulls() throws ForgeDBException {
        Tuple original = new Tuple(schema); // all NULL

        byte[] bytes = TupleSerializer.serialize(schema, original);
        Tuple back   = TupleSerializer.deserialize(schema, bytes, 0, bytes.length);

        for (int i = 0; i < schema.columnCount(); i++) {
            assertTrue(back.isNull(i), "Expected NULL at column " + i);
        }
    }

    @Test
    @DisplayName("Mixed NULL and non-NULL tuple round-trips correctly")
    void roundTrip_mixedNulls() throws ForgeDBException {
        Tuple original = new Tuple(schema);
        original.setInt(0, 7);
        // columns 1, 2, 3, 4 remain NULL
        original.setLong(4, 999L);

        byte[] bytes = TupleSerializer.serialize(schema, original);
        Tuple back   = TupleSerializer.deserialize(schema, bytes, 0, bytes.length);

        assertEquals(7,    back.getInt(0));
        assertTrue(back.isNull(1));
        assertTrue(back.isNull(2));
        assertTrue(back.isNull(3));
        assertEquals(999L, back.getLong(4));
    }

    // =========================================================================
    // serializedSize
    // =========================================================================

    @Test
    @DisplayName("serializedSize matches actual byte array length")
    void serializedSize_matchesActualLength() throws ForgeDBException {
        Tuple t = new Tuple(schema, new Object[]{1, "hello", false, 0.0, 0L});
        int predicted = TupleSerializer.serializedSize(schema, t);
        byte[] bytes  = TupleSerializer.serialize(schema, t);
        assertEquals(predicted, bytes.length);
    }

    @Test
    @DisplayName("serializedSize for all-NULL tuple equals one null flag per column")
    void serializedSize_allNullIsOneBytePerColumn() {
        Tuple t  = new Tuple(schema); // all NULL
        int size = TupleSerializer.serializedSize(schema, t);
        assertEquals(schema.columnCount(), size,
            "All-NULL tuple should be exactly 1 byte per column");
    }

    @Test
    @DisplayName("serializedSize accounts for TEXT byte length (UTF-8), not char count")
    void serializedSize_textUsesByteLength() {
        Schema s = Schema.of(new Column("t", DataType.TEXT));
        Tuple t  = new Tuple(s);
        t.setString(0, "é");   // 2 UTF-8 bytes, 1 char

        int size = TupleSerializer.serializedSize(s, t);
        // 1 (null flag) + 4 (length prefix) + 2 (UTF-8 bytes for 'é')
        assertEquals(7, size);
    }

    // =========================================================================
    // Error detection
    // =========================================================================

    @Test
    @DisplayName("deserialize throws on null data array")
    void deserialize_nullDataThrows() {
        assertThrows(ForgeDBException.class,
            () -> TupleSerializer.deserialize(schema, null, 0, 0));
    }

    @Test
    @DisplayName("deserialize throws on truncated data (too few bytes)")
    void deserialize_truncatedDataThrows() throws ForgeDBException {
        Tuple t   = new Tuple(schema, new Object[]{1, "hi", true, 1.0, 2L});
        byte[] full = TupleSerializer.serialize(schema, t);

        // Truncate to half the bytes
        byte[] truncated = new byte[full.length / 2];
        System.arraycopy(full, 0, truncated, 0, truncated.length);

        assertThrows(ForgeDBException.class,
            () -> TupleSerializer.deserialize(schema, truncated, 0, truncated.length));
    }

    @Test
    @DisplayName("deserialize throws on negative offset")
    void deserialize_negativeOffsetThrows() throws ForgeDBException {
        Tuple t    = new Tuple(schema, new Object[]{1, "hi", true, 1.0, 2L});
        byte[] bytes = TupleSerializer.serialize(schema, t);
        assertThrows(ForgeDBException.class,
            () -> TupleSerializer.deserialize(schema, bytes, -1, bytes.length));
    }

    @Test
    @DisplayName("Serialised bytes are deterministic for equal tuples")
    void serialize_isDeterministic() throws ForgeDBException {
        Tuple a = new Tuple(schema, new Object[]{1, "test", false, 2.5, 3L});
        Tuple b = new Tuple(schema, new Object[]{1, "test", false, 2.5, 3L});

        byte[] bytesA = TupleSerializer.serialize(schema, a);
        byte[] bytesB = TupleSerializer.serialize(schema, b);

        assertArrayEquals(bytesA, bytesB);
    }

    @Test
    @DisplayName("Deserialize uses offset correctly when data has prefix bytes")
    void deserialize_honorsOffset() throws ForgeDBException {
        Schema s = Schema.of(new Column("v", DataType.INT));
        Tuple t  = new Tuple(s);
        t.setInt(0, 12345);
        byte[] tupleBytes = TupleSerializer.serialize(s, t);

        // Wrap with 10 prefix bytes of garbage
        byte[] withPrefix = new byte[10 + tupleBytes.length];
        System.arraycopy(tupleBytes, 0, withPrefix, 10, tupleBytes.length);

        Tuple back = TupleSerializer.deserialize(s, withPrefix, 10, tupleBytes.length);
        assertEquals(12345, back.getInt(0));
    }
}
