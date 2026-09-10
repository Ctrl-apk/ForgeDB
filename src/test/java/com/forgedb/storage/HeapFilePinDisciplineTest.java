package com.forgedb.storage;

import com.forgedb.catalog.Column;
import com.forgedb.catalog.DataType;
import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;
import com.forgedb.common.ForgeDBException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the HeapFile / BufferPool pin/unpin contract.
 *
 * Background: HeapFile accepts any {@link DiskManager}, including a caching
 * {@link BufferPool} (which implements DiskManager). A BufferPool pins every
 * page returned by readPage(); a plain DiskManagerImpl does not. Before
 * Milestone 9 Phase 0, HeapFile never unpinned, so any workload touching more
 * distinct pages than the pool capacity exhausted the pool with
 * "all frames are pinned".
 *
 * These tests run HeapFile against a small BufferPool and assert that:
 *  - operations return with pin counts back at zero (no pin leaks);
 *  - scans spanning more pages than the pool capacity succeed repeatedly;
 *  - exceptions escaping from page operations cannot leave pages pinned;
 *  - a HeapFile backed by a plain DiskManager still behaves identically.
 *
 * Records are made large (~4 KB TEXT) so that each row occupies its own page;
 * this forces multi-page heaps with only a handful of rows, keeping the tests
 * fast while genuinely exceeding small pool capacities.
 */
class HeapFilePinDisciplineTest {

    @TempDir
    Path tempDir;

    private static final Schema SCHEMA = Schema.of(
        new Column("id",   DataType.INT),
        new Column("name", DataType.TEXT)
    );

    /** Large enough that one record per row fills nearly a whole page. */
    private static final String BIG_NAME = "x".repeat(4000);

    private static Tuple row(int id, String name) {
        Tuple t = new Tuple(SCHEMA);
        t.setInt(0, id);
        t.setString(1, name);
        return t;
    }

    private BufferPool newPool(String name, int capacity) throws ForgeDBException {
        return new BufferPool(
            new DiskManagerImpl(tempDir.resolve(name).toString()), capacity);
    }

    /** Asserts every page in the FILE that is currently pooled has pin count 0. */
    private static void assertNoLeakedPins(BufferPool pool) {
        for (int i = 0; i < pool.getPageCount(); i++) {
            PageId pid = new PageId(i);
            if (pool.contains(pid)) {
                assertEquals(0, pool.getPinCount(pid),
                    "page " + i + " leaked a pin");
            }
        }
    }

    // =========================================================================
    // Pin counts return to zero after every operation
    // =========================================================================

    @Test
    @DisplayName("insert/read/delete/scan leave zero pins on a small buffer pool")
    void operationsLeaveZeroPins() throws ForgeDBException {
        try (BufferPool pool = newPool("ops.fdb", 4)) {
            HeapFile heap = new HeapFile(pool, SCHEMA);

            RecordId r1 = heap.insert(row(1, "Alice"));
            RecordId r2 = heap.insert(row(2, "Bob"));
            assertNoLeakedPins(pool);

            heap.read(r1);
            heap.read(r2);
            assertNoLeakedPins(pool);

            heap.delete(r2);
            assertNoLeakedPins(pool);

            heap.scan();
            assertNoLeakedPins(pool);

            // Sanity: the data actually round-tripped.
            assertEquals(1, heap.read(r1).getInt(0));
            assertEquals(1, heap.scan().size(), "one live row remains after delete");
        }
    }

    // =========================================================================
    // Scans beyond pool capacity — the original failure mode
    // =========================================================================

    @Test
    @DisplayName("scan touching more pages than pool capacity does not exhaust the pool")
    void scanBeyondCapacitySucceeds() throws ForgeDBException {
        int capacity = 4;
        try (BufferPool pool = newPool("beyond.fdb", capacity)) {
            HeapFile heap = new HeapFile(pool, SCHEMA);

            // One ~4 KB record per row: forces one page per row.
            int rows = capacity * 6;
            for (int i = 0; i < rows; i++) {
                heap.insert(row(i, BIG_NAME));
            }
            assertTrue(heap.pageCount() > capacity,
                "test precondition: heap must span more pages than the pool holds (got "
                    + heap.pageCount() + " pages)");

            List<Tuple> scanned = heap.scan();
            assertEquals(rows, scanned.size(),
                "scan must see every row despite pool capacity");

            assertNoLeakedPins(pool);
        }
    }

    @Test
    @DisplayName("repeated scans over a multi-page heap never exhaust the pool")
    void repeatedScansDoNotExhaustPool() throws ForgeDBException {
        try (BufferPool pool = newPool("repeat.fdb", 3)) {
            HeapFile heap = new HeapFile(pool, SCHEMA);

            for (int i = 0; i < 20; i++) {
                heap.insert(row(i, BIG_NAME));
            }
            assertTrue(heap.pageCount() > 3,
                "test precondition: heap must span multiple pages (got "
                    + heap.pageCount() + ")");

            // Without unpin discipline, the second scan would already throw
            // "Buffer pool exhausted: all frames are pinned".
            for (int round = 0; round < 10; round++) {
                List<Tuple> scanned = heap.scan();
                assertEquals(20, scanned.size());
                assertNoLeakedPins(pool);
            }
        }
    }

    @Test
    @DisplayName("many point reads across a multi-page heap leave zero pins")
    void manyReadsLeaveZeroPins() throws ForgeDBException {
        try (BufferPool pool = newPool("reads.fdb", 2)) {
            HeapFile heap = new HeapFile(pool, SCHEMA);

            List<RecordId> rids = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                rids.add(heap.insert(row(i, BIG_NAME)));
            }
            assertTrue(heap.pageCount() > 2);

            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < rids.size(); i++) {
                    Tuple t = heap.read(rids.get(i));
                    assertEquals(i, t.getInt(0));
                }
                assertNoLeakedPins(pool);
            }
        }
    }

    // =========================================================================
    // Exception paths must not leak pins
    // =========================================================================

    @Test
    @DisplayName("exception inside scan cannot leave the current page pinned")
    void exceptionInScanDoesNotLeakPin() throws ForgeDBException {
        try (BufferPool pool = newPool("except.fdb", 3)) {
            HeapFile heap = new HeapFile(pool, SCHEMA);
            for (int i = 0; i < 10; i++) {
                heap.insert(row(i, "row-" + i));
            }

            // A schema whose deserializer runs past the end of the stored
            // records: 4 x INT needs 20 bytes but rows were written as
            // (INT, TEXT) ~15-16 bytes, so TupleSerializer throws mid-scan.
            Schema bogus = Schema.of(
                new Column("a", DataType.INT),
                new Column("b", DataType.INT),
                new Column("c", DataType.INT),
                new Column("d", DataType.INT));

            HeapFile broken = new HeapFile(pool, bogus);
            assertThrows(Exception.class, broken::scan,
                "mismatched schema must blow up during deserialization");

            // The try/finally in scan() must have released the pinned page.
            assertNoLeakedPins(pool);
        }
    }

    @Test
    @DisplayName("read of an already-deleted slot throws but leaves zero pins")
    void readDeletedThrowsWithoutLeakingPin() throws ForgeDBException {
        try (BufferPool pool = newPool("deleted.fdb", 3)) {
            HeapFile heap = new HeapFile(pool, SCHEMA);

            RecordId rid = heap.insert(row(7, "Gone"));
            heap.delete(rid);

            assertThrows(ForgeDBException.class, () -> heap.read(rid),
                "reading a deleted slot must fail");
            assertNoLeakedPins(pool);

            // Pool must remain fully usable afterwards.
            RecordId again = heap.insert(row(8, "Back"));
            assertEquals(8, heap.read(again).getInt(0));
        }
    }

    // =========================================================================
    // Plain DiskManager behaviour is unchanged
    // =========================================================================

    @Test
    @DisplayName("HeapFile on a plain DiskManagerImpl is unaffected by pin handling")
    void plainDiskManagerStillWorks() throws ForgeDBException {
        try (DiskManagerImpl dm = new DiskManagerImpl(
                tempDir.resolve("plain.fdb").toString())) {
            HeapFile heap = new HeapFile(dm, SCHEMA);

            RecordId rid = heap.insert(row(1, "Alice"));
            assertEquals(1, heap.read(rid).getInt(0));
            assertEquals(1, heap.scan().size());

            heap.delete(rid);
            assertEquals(0, heap.scan().size());
        }
    }
}
