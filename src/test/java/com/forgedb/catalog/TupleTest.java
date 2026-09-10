package com.forgedb.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Tuple.
 */
class TupleTest {

    private Schema schema;

    @BeforeEach
    void setUp() {
        schema = Schema.of(
            new Column("id",      DataType.INT),
            new Column("name",    DataType.TEXT),
            new Column("active",  DataType.BOOLEAN),
            new Column("score",   DataType.DOUBLE),
            new Column("serial",  DataType.LONG)
        );
    }

    // =========================================================================
    // Construction
    // =========================================================================

    @Test
    @DisplayName("New tuple has all NULL values")
    void tuple_defaultAllNull() {
        Tuple t = new Tuple(schema);
        for (int i = 0; i < schema.columnCount(); i++) {
            assertTrue(t.isNull(i), "Expected NULL at index " + i);
            assertNull(t.get(i));
        }
    }

    @Test
    @DisplayName("Constructor from Object[] copies values correctly")
    void tuple_constructorFromArray() {
        Tuple t = new Tuple(schema, new Object[]{1, "Alice", true, 9.5, 100L});
        assertEquals(1,       t.getInt(0));
        assertEquals("Alice", t.getString(1));
        assertEquals(true,    t.getBoolean(2));
        assertEquals(9.5,     t.getDouble(3));
        assertEquals(100L,    t.getLong(4));
    }

    @Test
    @DisplayName("Constructor rejects wrong array length")
    void tuple_constructorRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class,
            () -> new Tuple(schema, new Object[]{1, "Alice"}));
    }

    @Test
    @DisplayName("Constructor rejects type-incompatible values")
    void tuple_constructorRejectsWrongTypes() {
        // INT column but String value
        assertThrows(IllegalArgumentException.class,
            () -> new Tuple(schema, new Object[]{"notAnInt", null, null, null, null}));
    }

    @Test
    @DisplayName("Constructor rejects null schema")
    void tuple_rejectsNullSchema() {
        assertThrows(NullPointerException.class, () -> new Tuple(null));
    }

    // =========================================================================
    // Typed setters and getters
    // =========================================================================

    @Test
    @DisplayName("setInt/getInt round-trip")
    void tuple_intRoundTrip() {
        Tuple t = new Tuple(schema);
        t.setInt(0, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, t.getInt(0));
        t.setInt(0, Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, t.getInt(0));
        t.setInt(0, 0);
        assertEquals(0, t.getInt(0));
    }

    @Test
    @DisplayName("setString/getString round-trip including empty string")
    void tuple_textRoundTrip() {
        Tuple t = new Tuple(schema);
        t.setString(1, "Hello, 世界!");
        assertEquals("Hello, 世界!", t.getString(1));
        t.setString(1, "");
        assertEquals("", t.getString(1));
    }

    @Test
    @DisplayName("setBoolean/getBoolean round-trip")
    void tuple_booleanRoundTrip() {
        Tuple t = new Tuple(schema);
        t.setBoolean(2, true);
        assertTrue(t.getBoolean(2));
        t.setBoolean(2, false);
        assertFalse(t.getBoolean(2));
    }

    @Test
    @DisplayName("setDouble/getDouble round-trip including special values")
    void tuple_doubleRoundTrip() {
        Tuple t = new Tuple(schema);
        t.setDouble(3, Math.PI);
        assertEquals(Math.PI, t.getDouble(3));
        t.setDouble(3, Double.NaN);
        assertTrue(Double.isNaN(t.getDouble(3)));
        t.setDouble(3, Double.POSITIVE_INFINITY);
        assertTrue(Double.isInfinite(t.getDouble(3)));
    }

    @Test
    @DisplayName("setLong/getLong round-trip")
    void tuple_longRoundTrip() {
        Tuple t = new Tuple(schema);
        t.setLong(4, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, t.getLong(4));
        t.setLong(4, Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, t.getLong(4));
    }

    // =========================================================================
    // NULL handling
    // =========================================================================

    @Test
    @DisplayName("set(index, null) sets SQL NULL")
    void tuple_setNull() {
        Tuple t = new Tuple(schema);
        t.setInt(0, 42);
        assertFalse(t.isNull(0));
        t.set(0, null);
        assertTrue(t.isNull(0));
        assertNull(t.get(0));
    }

    @Test
    @DisplayName("getInt on NULL column throws IllegalStateException")
    void tuple_getOnNullThrows() {
        Tuple t = new Tuple(schema);
        assertThrows(IllegalStateException.class, () -> t.getInt(0));
        assertThrows(IllegalStateException.class, () -> t.getString(1));
    }

    // =========================================================================
    // Type safety
    // =========================================================================

    @Test
    @DisplayName("set() rejects values of wrong type")
    void tuple_setRejectsWrongType() {
        Tuple t = new Tuple(schema);
        // Column 0 is INT; setting a String should fail
        assertThrows(IllegalArgumentException.class, () -> t.set(0, "notAnInt"));
    }

    @Test
    @DisplayName("getInt on a TEXT column throws IllegalArgumentException")
    void tuple_getIntOnTextColumnThrows() {
        Tuple t = new Tuple(schema);
        t.setString(1, "hello");
        assertThrows(IllegalArgumentException.class, () -> t.getInt(1));
    }

    @Test
    @DisplayName("getLong on an INT column throws IllegalArgumentException")
    void tuple_getLongOnIntColumnThrows() {
        Tuple t = new Tuple(schema);
        t.setInt(0, 5);
        assertThrows(IllegalArgumentException.class, () -> t.getLong(0));
    }

    // =========================================================================
    // Index bounds
    // =========================================================================

    @Test
    @DisplayName("get() throws on negative index")
    void tuple_negativeIndexThrows() {
        Tuple t = new Tuple(schema);
        assertThrows(IndexOutOfBoundsException.class, () -> t.get(-1));
    }

    @Test
    @DisplayName("get() throws on index >= columnCount")
    void tuple_overflowIndexThrows() {
        Tuple t = new Tuple(schema);
        assertThrows(IndexOutOfBoundsException.class,
            () -> t.get(schema.columnCount()));
    }

    // =========================================================================
    // copy()
    // =========================================================================

    @Test
    @DisplayName("copy() produces independent Tuple with same values")
    void tuple_copyIsIndependent() {
        Tuple original = new Tuple(schema);
        original.setInt(0, 99);
        original.setString(1, "orig");

        Tuple copy = original.copy();
        assertEquals(99,     copy.getInt(0));
        assertEquals("orig", copy.getString(1));

        // Modifying copy does not affect original
        copy.setInt(0, 0);
        assertEquals(99, original.getInt(0));
    }

    // =========================================================================
    // equals / hashCode / toString
    // =========================================================================

    @Test
    @DisplayName("Tuples with same schema and values are equal")
    void tuple_equality() {
        Tuple a = new Tuple(schema, new Object[]{1, "Alice", true, 1.0, 100L});
        Tuple b = new Tuple(schema, new Object[]{1, "Alice", true, 1.0, 100L});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("Tuples with different values are not equal")
    void tuple_inequalityOnDifferentValues() {
        Tuple a = new Tuple(schema, new Object[]{1, "Alice", true, 1.0, 100L});
        Tuple b = new Tuple(schema, new Object[]{2, "Alice", true, 1.0, 100L});
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("toString includes column names and values")
    void tuple_toStringContainsFields() {
        Tuple t = new Tuple(schema, new Object[]{42, "Bob", false, 3.14, 0L});
        String str = t.toString();
        assertTrue(str.contains("id=42"));
        assertTrue(str.contains("name=Bob"));
    }
}
