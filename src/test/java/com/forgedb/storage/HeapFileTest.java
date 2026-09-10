package com.forgedb.storage;

import com.forgedb.catalog.*;
import com.forgedb.common.ForgeDBException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for HeapFile.
 *
 * Each test uses a fresh @TempDir so there is no shared state between tests.
 * Tests cover insert, read, delete, update, scan, multi-page behaviour,
 * variable-length TEXT, NULL values, and persistence across close/reopen.
 */
class HeapFileTest {

    @TempDir
    Path tempDir;

    /** Standard 3-column schema used by most tests. */
    private static final Schema SCHEMA = Schema.of(
        new Column("id",   DataType.INT),
        new Column("name", DataType.TEXT),
        new Column("age",  DataType.INT)
    );

    private String dbPath(String name) {
        return tempDir.resolve(name).toString();
    }

    /** Builds a fully populated tuple for SCHEMA. */
    private static Tuple row(int id, String name, int age) {
        Tuple t = new Tuple(SCHEMA);
        t.setInt(0, id);
        t.setString(1, name);
        t.setInt(2, age);
        return t;
    }

    // =========================================================================
    // Basic insert and read
    // =========================================================================

    @Test
    @DisplayName("insert returns a RecordId; read returns the same tuple")
    void insertAndRead_basicRoundTrip() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("basic.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            Tuple original = row(1, "Alice", 30);
            RecordId rid = heap.insert(original);

            assertNotNull(rid);
            Tuple back = heap.read(rid);
            assertEquals(1,       back.getInt(0));
            assertEquals("Alice", back.getString(1));
            assertEquals(30,      back.getInt(2));
        }
    }

    @Test
    @DisplayName("Multiple inserts return different RecordIds")
    void insert_returnsDistinctRecordIds() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("multi_insert.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            RecordId r1 = heap.insert(row(1, "Alice", 30));
            RecordId r2 = heap.insert(row(2, "Bob",   25));
            RecordId r3 = heap.insert(row(3, "Carol", 35));
            assertNotEquals(r1, r2);
            assertNotEquals(r2, r3);
        }
    }

    @Test
    @DisplayName("Each inserted tuple is independently readable by its RecordId")
    void insert_eachReadableIndependently() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("indep.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            RecordId r1 = heap.insert(row(10, "Alice", 21));
            RecordId r2 = heap.insert(row(20, "Bob",   22));

            assertEquals(10,      heap.read(r1).getInt(0));
            assertEquals("Alice", heap.read(r1).getString(1));
            assertEquals(20,      heap.read(r2).getInt(0));
            assertEquals("Bob",   heap.read(r2).getString(1));
        }
    }

    // =========================================================================
    // NULL values
    // =========================================================================

    @Test
    @DisplayName("Tuples with NULL values round-trip correctly")
    void insertAndRead_nullValues() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("nulls.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            Tuple t = new Tuple(SCHEMA);
            t.setInt(0, 5);
            // name and age intentionally NULL

            RecordId rid  = heap.insert(t);
            Tuple    back = heap.read(rid);

            assertEquals(5,    back.getInt(0));
            assertTrue(back.isNull(1), "name should be NULL");
            assertTrue(back.isNull(2), "age should be NULL");
        }
    }

    // =========================================================================
    // Variable-length TEXT
    // =========================================================================

    @Test
    @DisplayName("Very long TEXT value round-trips correctly")
    void insertAndRead_longText() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("longtext.fdb"))) {
            HeapFile heap  = new HeapFile(dm, SCHEMA);
            String   text  = "x".repeat(3000);
            Tuple    t     = row(1, text, 0);
            RecordId rid   = heap.insert(t);
            assertEquals(text, heap.read(rid).getString(1));
        }
    }

    @Test
    @DisplayName("Unicode TEXT value round-trips correctly")
    void insertAndRead_unicodeText() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("unicode.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            String text   = "こんにちは世界 \uD83D\uDE00";
            Tuple  t      = row(1, text, 0);
            RecordId rid  = heap.insert(t);
            assertEquals(text, heap.read(rid).getString(1));
        }
    }

    // =========================================================================
    // Delete
    // =========================================================================

    @Test
    @DisplayName("delete makes record unreadable")
    void delete_makesRecordUnreadable() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("delete.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            RecordId rid  = heap.insert(row(1, "Alice", 30));
            heap.delete(rid);
            assertThrows(ForgeDBException.class, () -> heap.read(rid));
        }
    }

    @Test
    @DisplayName("delete does not affect adjacent records")
    void delete_adjacentRecordsUnaffected() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("delete_adj.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            RecordId r0 = heap.insert(row(1, "Alice",  30));
            RecordId r1 = heap.insert(row(2, "Bob",    25));
            RecordId r2 = heap.insert(row(3, "Carol",  35));

            heap.delete(r1);  // delete middle record

            assertEquals(1, heap.read(r0).getInt(0));
            assertEquals(3, heap.read(r2).getInt(0));
        }
    }

    @Test
    @DisplayName("delete throws on already-deleted record")
    void delete_throwsOnAlreadyDeleted() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("delete_twice.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            RecordId rid  = heap.insert(row(1, "Alice", 30));
            heap.delete(rid);
            assertThrows(ForgeDBException.class, () -> heap.delete(rid));
        }
    }

    // =========================================================================
    // Update
    // =========================================================================

    @Test
    @DisplayName("update returns new RecordId and new values are readable")
    void update_newValuesReadable() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("update.fdb"))) {
            HeapFile heap  = new HeapFile(dm, SCHEMA);
            RecordId orig  = heap.insert(row(1, "Alice", 30));
            RecordId newId = heap.update(orig, row(1, "AliceUpdated", 31));

            assertEquals("AliceUpdated", heap.read(newId).getString(1));
            assertEquals(31, heap.read(newId).getInt(2));
        }
    }

    @Test
    @DisplayName("update makes old RecordId unreadable")
    void update_oldRecordIdUnreadable() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("update_old.fdb"))) {
            HeapFile heap  = new HeapFile(dm, SCHEMA);
            RecordId orig  = heap.insert(row(1, "Alice", 30));
            heap.update(orig, row(1, "New", 99));
            assertThrows(ForgeDBException.class, () -> heap.read(orig));
        }
    }

    // =========================================================================
    // Sequential scan
    // =========================================================================

    @Test
    @DisplayName("scan returns all non-deleted tuples in insertion order")
    void scan_returnsAllLiveTuples() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("scan.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            heap.insert(row(1, "Alice", 30));
            heap.insert(row(2, "Bob",   25));
            heap.insert(row(3, "Carol", 35));

            List<Tuple> results = heap.scan();
            assertEquals(3, results.size());
            assertEquals(1, results.get(0).getInt(0));
            assertEquals(2, results.get(1).getInt(0));
            assertEquals(3, results.get(2).getInt(0));
        }
    }

    @Test
    @DisplayName("scan excludes deleted records")
    void scan_excludesDeleted() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("scan_del.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            RecordId r0 = heap.insert(row(1, "Alice", 30));
            RecordId r1 = heap.insert(row(2, "Bob",   25));
            RecordId r2 = heap.insert(row(3, "Carol", 35));

            heap.delete(r1);

            List<Tuple> results = heap.scan();
            assertEquals(2, results.size());
            assertEquals(1, results.get(0).getInt(0));
            assertEquals(3, results.get(1).getInt(0));
        }
    }

    @Test
    @DisplayName("scan on empty heap returns empty list")
    void scan_emptyHeap() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("scan_empty.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            assertTrue(heap.scan().isEmpty());
        }
    }

    // =========================================================================
    // Multiple pages
    // =========================================================================

    @Test
    @DisplayName("Heap allocates a new page when current page is full")
    void multiPage_allocatesNewPageWhenFull() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("multipage.fdb"))) {
            HeapFile heap = new HeapFile(dm, SCHEMA);

            // A 2000-char name serialises to roughly 2009 bytes per record.
            // Free space on a fresh page = PAGE_PAYLOAD_SIZE(4080) - DIR_HEADER(8) = 4072 bytes.
            // First record uses 2009 + 4 (slot entry) = 2013 bytes  → 2059 bytes free.
            // Second record needs 2009 + 4 = 2013 bytes > 2059... actually fits (2059 > 2013).
            // Third record needs 2013 bytes but only 46 bytes remain → spills to new page.
            // So inserting 3+ large records must produce at least 2 data pages.
            String bigText = "y".repeat(2000);

            // Insert records until the heap grows to 2 data pages
            int inserted = 0;
            while (heap.pageCount() < 2) {
                heap.insert(row(inserted++, bigText, inserted));
                // Safety: if we somehow insert 100 records without getting 2 pages, fail loudly
                if (inserted > 100) fail("Expected heap to grow to 2 pages within 100 inserts");
            }

            assertTrue(heap.pageCount() >= 2,
                "HeapFile should track at least 2 data pages after filling the first");
            assertTrue(dm.getPageCount() >= 3,
                "DiskManager should have header page + at least 2 data pages");
        }
    }

    @Test
    @DisplayName("Records on multiple pages are all returned by scan")
    void multiPage_scanReturnsAll() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("multipage_scan.fdb"))) {
            HeapFile heap    = new HeapFile(dm, SCHEMA);
            String bigText   = "z".repeat(2000);
            int totalInserted = 0;

            // Insert enough records to span multiple pages
            for (int i = 0; i < 10; i++) {
                heap.insert(row(i, bigText, i));
                totalInserted++;
            }

            List<Tuple> results = heap.scan();
            assertEquals(totalInserted, results.size());
        }
    }

    @Test
    @DisplayName("Records on multiple pages are each individually readable by RecordId")
    void multiPage_eachReadableByRecordId() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("multipage_rid.fdb"))) {
            HeapFile heap     = new HeapFile(dm, SCHEMA);
            String bigText    = "r".repeat(2000);
            RecordId[] rids   = new RecordId[6];

            for (int i = 0; i < 6; i++) {
                rids[i] = heap.insert(row(i * 10, bigText, i));
            }

            for (int i = 0; i < 6; i++) {
                Tuple t = heap.read(rids[i]);
                assertEquals(i * 10, t.getInt(0));
            }
        }
    }

    // =========================================================================
    // Persistence across close / reopen
    // =========================================================================

    @Test
    @DisplayName("Inserted records survive close and reopen")
    void persistence_recordsSurviveReopen() throws ForgeDBException {
        String path = dbPath("persist.fdb");

        RecordId rid;
        try (DiskManager dm = new DiskManagerImpl(path)) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            rid = heap.insert(row(99, "Persistent", 50));
        }

        try (DiskManager dm = new DiskManagerImpl(path)) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            Tuple back    = heap.read(rid);
            assertEquals(99,           back.getInt(0));
            assertEquals("Persistent", back.getString(1));
            assertEquals(50,           back.getInt(2));
        }
    }

    @Test
    @DisplayName("Deleted records remain deleted after close and reopen")
    void persistence_deletionSurvivesReopen() throws ForgeDBException {
        String path = dbPath("persist_del.fdb");

        RecordId rid;
        try (DiskManager dm = new DiskManagerImpl(path)) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            rid = heap.insert(row(1, "TempRecord", 10));
            heap.delete(rid);
        }

        try (DiskManager dm = new DiskManagerImpl(path)) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            final RecordId finalRid = rid;
            assertThrows(ForgeDBException.class, () -> heap.read(finalRid));
        }
    }

    @Test
    @DisplayName("Scan results survive close and reopen")
    void persistence_scanSurvivesReopen() throws ForgeDBException {
        String path = dbPath("persist_scan.fdb");

        try (DiskManager dm = new DiskManagerImpl(path)) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            for (int i = 0; i < 5; i++) {
                heap.insert(row(i, "Name" + i, i * 10));
            }
        }

        try (DiskManager dm = new DiskManagerImpl(path)) {
            HeapFile heap       = new HeapFile(dm, SCHEMA);
            List<Tuple> results = heap.scan();
            assertEquals(5, results.size());
            for (int i = 0; i < 5; i++) {
                assertEquals(i,          results.get(i).getInt(0));
                assertEquals("Name" + i, results.get(i).getString(1));
            }
        }
    }

    @Test
    @DisplayName("Multi-page data survives close and reopen")
    void persistence_multiPageSurvivesReopen() throws ForgeDBException {
        String path    = dbPath("persist_multi.fdb");
        String bigText = "p".repeat(2000);

        try (DiskManager dm = new DiskManagerImpl(path)) {
            HeapFile heap = new HeapFile(dm, SCHEMA);
            for (int i = 0; i < 8; i++) {
                heap.insert(row(i, bigText, i));
            }
        }

        try (DiskManager dm = new DiskManagerImpl(path)) {
            HeapFile heap       = new HeapFile(dm, SCHEMA);
            List<Tuple> results = heap.scan();
            assertEquals(8, results.size());
            for (int i = 0; i < 8; i++) {
                assertEquals(i,       results.get(i).getInt(0));
                assertEquals(bigText, results.get(i).getString(1));
            }
        }
    }

    // =========================================================================
    // All data types
    // =========================================================================

    @Test
    @DisplayName("HeapFile stores and retrieves all five data types correctly")
    void allTypes_roundTrip() throws ForgeDBException {
        Schema allTypes = Schema.of(
            new Column("i", DataType.INT),
            new Column("l", DataType.LONG),
            new Column("b", DataType.BOOLEAN),
            new Column("d", DataType.DOUBLE),
            new Column("t", DataType.TEXT)
        );

        try (DiskManager dm = new DiskManagerImpl(dbPath("alltypes.fdb"))) {
            HeapFile heap = new HeapFile(dm, allTypes);

            Tuple original = new Tuple(allTypes,
                new Object[]{42, Long.MAX_VALUE, true, Math.E, "ForgeDB"});
            RecordId rid = heap.insert(original);
            Tuple back   = heap.read(rid);

            assertEquals(42,           back.getInt(0));
            assertEquals(Long.MAX_VALUE, back.getLong(1));
            assertTrue(back.getBoolean(2));
            assertEquals(Math.E,       back.getDouble(3), 1e-15);
            assertEquals("ForgeDB",    back.getString(4));
        }
    }
}
