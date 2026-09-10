package com.forgedb.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Schema and Column.
 */
class SchemaTest {

    // =========================================================================
    // Column construction
    // =========================================================================

    @Test
    @DisplayName("Column stores name and type correctly")
    void column_storesNameAndType() {
        Column c = new Column("age", DataType.INT);
        assertEquals("age", c.name());
        assertEquals(DataType.INT, c.type());
    }

    @Test
    @DisplayName("Column rejects null name")
    void column_rejectsNullName() {
        assertThrows(IllegalArgumentException.class, () -> new Column(null, DataType.INT));
    }

    @Test
    @DisplayName("Column rejects blank name")
    void column_rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new Column("  ", DataType.INT));
        assertThrows(IllegalArgumentException.class, () -> new Column("", DataType.INT));
    }

    @Test
    @DisplayName("Column rejects null type")
    void column_rejectsNullType() {
        assertThrows(NullPointerException.class, () -> new Column("age", null));
    }

    @Test
    @DisplayName("Column equality is value-based")
    void column_equalityIsValueBased() {
        Column a = new Column("id", DataType.INT);
        Column b = new Column("id", DataType.INT);
        Column c = new Column("id", DataType.LONG);
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // =========================================================================
    // Schema construction
    // =========================================================================

    @Test
    @DisplayName("Schema.of() creates schema with correct columns")
    void schema_of_createsCorrectly() {
        Schema s = Schema.of(
            new Column("id",   DataType.INT),
            new Column("name", DataType.TEXT),
            new Column("age",  DataType.INT)
        );
        assertEquals(3, s.columnCount());
    }

    @Test
    @DisplayName("Schema rejects null column list")
    void schema_rejectsNullList() {
        assertThrows(IllegalArgumentException.class, () -> new Schema(null));
    }

    @Test
    @DisplayName("Schema rejects empty column list")
    void schema_rejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> new Schema(List.of()));
    }

    @Test
    @DisplayName("Schema rejects duplicate column names")
    void schema_rejectsDuplicateNames() {
        assertThrows(IllegalArgumentException.class, () -> Schema.of(
            new Column("id", DataType.INT),
            new Column("id", DataType.TEXT)   // duplicate
        ));
    }

    @Test
    @DisplayName("Schema rejects null column in list")
    void schema_rejectsNullColumn() {
        List<Column> cols = new java.util.ArrayList<>();
        cols.add(new Column("id", DataType.INT));
        cols.add(null);
        assertThrows(IllegalArgumentException.class, () -> new Schema(cols));
    }

    // =========================================================================
    // Schema accessors
    // =========================================================================

    @Test
    @DisplayName("getColumn(int) returns correct column")
    void schema_getColumnByIndex() {
        Schema s = Schema.of(
            new Column("id",  DataType.INT),
            new Column("val", DataType.DOUBLE)
        );
        assertEquals("id",  s.getColumn(0).name());
        assertEquals("val", s.getColumn(1).name());
    }

    @Test
    @DisplayName("getColumn(int) throws on out-of-range index")
    void schema_getColumnByIndex_outOfRange() {
        Schema s = Schema.of(new Column("id", DataType.INT));
        assertThrows(IndexOutOfBoundsException.class, () -> s.getColumn(1));
        assertThrows(IndexOutOfBoundsException.class, () -> s.getColumn(-1));
    }

    @Test
    @DisplayName("getColumn(String) returns correct column")
    void schema_getColumnByName() {
        Schema s = Schema.of(
            new Column("id",   DataType.INT),
            new Column("name", DataType.TEXT)
        );
        assertEquals(DataType.INT,  s.getColumn("id").type());
        assertEquals(DataType.TEXT, s.getColumn("name").type());
    }

    @Test
    @DisplayName("getColumn(String) throws on unknown name")
    void schema_getColumnByName_unknown() {
        Schema s = Schema.of(new Column("id", DataType.INT));
        assertThrows(IllegalArgumentException.class, () -> s.getColumn("nonexistent"));
    }

    @Test
    @DisplayName("getColumnIndex returns correct index")
    void schema_getColumnIndex() {
        Schema s = Schema.of(
            new Column("a", DataType.INT),
            new Column("b", DataType.LONG),
            new Column("c", DataType.TEXT)
        );
        assertEquals(0, s.getColumnIndex("a"));
        assertEquals(1, s.getColumnIndex("b"));
        assertEquals(2, s.getColumnIndex("c"));
    }

    @Test
    @DisplayName("hasColumn returns correct result")
    void schema_hasColumn() {
        Schema s = Schema.of(new Column("id", DataType.INT));
        assertTrue(s.hasColumn("id"));
        assertFalse(s.hasColumn("missing"));
    }

    @Test
    @DisplayName("Schema equality is value-based")
    void schema_equalityIsValueBased() {
        Schema a = Schema.of(new Column("id", DataType.INT));
        Schema b = Schema.of(new Column("id", DataType.INT));
        Schema c = Schema.of(new Column("id", DataType.LONG));
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("columns() returns unmodifiable list in insertion order")
    void schema_columnsListIsOrdered() {
        Schema s = Schema.of(
            new Column("z", DataType.INT),
            new Column("a", DataType.TEXT)
        );
        List<Column> cols = s.columns();
        assertEquals("z", cols.get(0).name());
        assertEquals("a", cols.get(1).name());
        // Must be unmodifiable
        assertThrows(UnsupportedOperationException.class,
            () -> cols.add(new Column("x", DataType.INT)));
    }

    // =========================================================================
    // DataType
    // =========================================================================

    @Test
    @DisplayName("Fixed-size types report correct byte sizes")
    void dataType_fixedSizes() {
        assertEquals(4, DataType.INT.fixedSize());
        assertEquals(8, DataType.LONG.fixedSize());
        assertEquals(1, DataType.BOOLEAN.fixedSize());
        assertEquals(8, DataType.DOUBLE.fixedSize());
    }

    @Test
    @DisplayName("TEXT.fixedSize() throws UnsupportedOperationException")
    void dataType_textHasNoFixedSize() {
        assertThrows(UnsupportedOperationException.class, DataType.TEXT::fixedSize);
    }

    @Test
    @DisplayName("DataType.isFixedSize() returns correct values")
    void dataType_isFixedSize() {
        assertTrue(DataType.INT.isFixedSize());
        assertTrue(DataType.LONG.isFixedSize());
        assertTrue(DataType.BOOLEAN.isFixedSize());
        assertTrue(DataType.DOUBLE.isFixedSize());
        assertFalse(DataType.TEXT.isFixedSize());
    }

    @Test
    @DisplayName("DataType.javaType() returns expected classes")
    void dataType_javaTypes() {
        assertEquals(Integer.class, DataType.INT.javaType());
        assertEquals(Long.class,    DataType.LONG.javaType());
        assertEquals(Boolean.class, DataType.BOOLEAN.javaType());
        assertEquals(Double.class,  DataType.DOUBLE.javaType());
        assertEquals(String.class,  DataType.TEXT.javaType());
    }
}
