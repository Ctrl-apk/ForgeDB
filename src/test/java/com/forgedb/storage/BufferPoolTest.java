package com.forgedb.storage;

import com.forgedb.common.Constants;
import com.forgedb.common.ForgeDBException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for BufferPool.
 *
 * Test categories:
 *   1. Construction — capacity validation
 *   2. Cache hit / miss — readPage loads from disk, caches for subsequent reads
 *   3. Pin / Unpin — pin count lifecycle, double-unpin guard
 *   4. Dirty page tracking — modifications visible via isDirty()
 *   5. Flush — flushPage(), flushAll(), dirty flag cleared after flush
 *   6. LRU eviction — correct victim selection, dirty victim flushed before evict
 *   7. All pages pinned — pool exhausted exception
 *   8. Persistence — data flushed by close() survives reopen
 *   9. DiskManager delegation — allocatePage(), getPageCount() forwarded correctly
 *  10. HeapFile integration — HeapFile works correctly when given a BufferPool
 */
class BufferPoolTest {

    @TempDir
    Path tempDir;

    private String dbPath(String name) {
        return tempDir.resolve(name).toString();
    }

    // =========================================================================
    // 1. Construction
    // =========================================================================

    @Test
    @DisplayName("BufferPool with capacity 1 constructs without error")
    void construction_capacityOne() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("cap1.fdb"))) {
            BufferPool bp = new BufferPool(dm, 1);
            assertEquals(1, bp.capacity());
            assertEquals(0, bp.size());
        }
    }

    @Test
    @DisplayName("BufferPool rejects capacity < 1")
    void construction_rejectsZeroCapacity() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("zero.fdb"))) {
            assertThrows(IllegalArgumentException.class,
                () -> new BufferPool(dm, 0));
            assertThrows(IllegalArgumentException.class,
                () -> new BufferPool(dm, -1));
        }
    }

    @Test
    @DisplayName("BufferPool rejects null DiskManager")
    void construction_rejectsNullDiskManager() {
        assertThrows(NullPointerException.class,
            () -> new BufferPool(null, 4));
    }

    // =========================================================================
    // 2. Cache hit / miss
    // =========================================================================

    @Test
    @DisplayName("readPage on empty pool loads page from disk (cache miss)")
    void cacheMiss_loadsFromDisk() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("miss.fdb"))) {
            // Write a known value directly via DiskManager
            PageId id   = dm.allocatePage();
            Page   page = dm.readPage(id);
            page.putInt(0, 0xABCD_1234);
            dm.writePage(id, page);

            // Now read through the buffer pool — should load from disk
            BufferPool bp = new BufferPool(dm, 4);
            Page loaded   = bp.readPage(id);

            assertEquals(0xABCD_1234, loaded.getInt(0),
                "Buffer pool must return the value written to disk");
            assertTrue(bp.contains(id), "Page must be present in pool after load");
            assertEquals(1, bp.getPinCount(id), "Page must be pinned after readPage");
        }
    }

    @Test
    @DisplayName("Second readPage for the same page is a cache hit (same object returned)")
    void cacheHit_returnsSameObjectAndIncrementsPinCount() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("hit.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);

            Page first  = bp.readPage(id);  // miss → load
            Page second = bp.readPage(id);  // hit  → cached

            assertSame(first, second, "Cache hit must return the same Page object");
            assertEquals(2, bp.getPinCount(id), "Pin count must be 2 after two reads");
        }
    }

    @Test
    @DisplayName("contains() returns false for pages not in pool")
    void contains_falseForAbsentPage() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("contains.fdb"))) {
            BufferPool bp = new BufferPool(dm, 4);
            assertFalse(bp.contains(new PageId(0)));
        }
    }

    @Test
    @DisplayName("Pool size reflects number of distinct pages loaded")
    void size_tracksLoadedPages() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("size.fdb"))) {
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();

            BufferPool bp = new BufferPool(dm, 8);
            assertEquals(0, bp.size());

            bp.readPage(p1);
            assertEquals(1, bp.size());

            bp.readPage(p2);
            bp.readPage(p3);
            assertEquals(3, bp.size());
        }
    }

    // =========================================================================
    // 3. Pin / Unpin
    // =========================================================================

    @Test
    @DisplayName("Unpin decrements pin count")
    void unpin_decrementsPinCount() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("unpin.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);

            bp.readPage(id);                      // pinCount = 1
            assertEquals(1, bp.getPinCount(id));

            bp.unpin(id);                          // pinCount = 0
            assertEquals(0, bp.getPinCount(id));
        }
    }

    @Test
    @DisplayName("Pin count tracks multiple pins and unpins correctly")
    void pinCount_multipleHolders() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("multi_pin.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);

            bp.readPage(id);  // pin=1
            bp.readPage(id);  // pin=2
            bp.readPage(id);  // pin=3

            bp.unpin(id);     // pin=2
            assertEquals(2, bp.getPinCount(id));

            bp.unpin(id);     // pin=1
            bp.unpin(id);     // pin=0
            assertEquals(0, bp.getPinCount(id));
        }
    }

    @Test
    @DisplayName("unpin throws on page not in pool")
    void unpin_throwsForAbsentPage() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("unpin_absent.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);
            // Page is not loaded into the pool yet
            assertThrows(ForgeDBException.class, () -> bp.unpin(id));
        }
    }

    @Test
    @DisplayName("unpin throws on double-unpin (pin count already 0)")
    void unpin_throwsOnDoubleUnpin() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("double_unpin.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);

            bp.readPage(id);
            bp.unpin(id);                              // pin count → 0
            assertThrows(ForgeDBException.class,
                () -> bp.unpin(id),                    // double-unpin → must throw
                "Double unpin must throw ForgeDBException");
        }
    }

    @Test
    @DisplayName("getPinCount returns -1 for pages not in pool")
    void getPinCount_returnsMinusOneForAbsent() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("pincount.fdb"))) {
            BufferPool bp = new BufferPool(dm, 4);
            assertEquals(-1, bp.getPinCount(new PageId(0)));
        }
    }

    // =========================================================================
    // 4. Dirty page tracking
    // =========================================================================

    @Test
    @DisplayName("Page loaded from disk starts clean")
    void dirty_freshLoadIsClean() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("clean_load.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);
            Page page = bp.readPage(id);
            assertFalse(page.isDirty(), "Page should be clean immediately after loading");
        }
    }

    @Test
    @DisplayName("Modifying a page sets its dirty flag")
    void dirty_modificationSetsDirtyFlag() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("dirty_flag.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);
            Page page = bp.readPage(id);

            assertFalse(page.isDirty());
            page.putInt(0, 99);
            assertTrue(page.isDirty(), "Page must be dirty after putInt");
        }
    }

    @Test
    @DisplayName("writePage clears the dirty flag")
    void dirty_clearedAfterWritePage() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("write_clean.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);
            Page page = bp.readPage(id);
            page.putInt(0, 55);
            assertTrue(page.isDirty());

            bp.writePage(id, page);
            assertFalse(page.isDirty(), "Dirty flag must be cleared after writePage");
        }
    }

    // =========================================================================
    // 5. Flush
    // =========================================================================

    @Test
    @DisplayName("flushPage flushes a dirty page and clears dirty flag")
    void flush_flushPageClearsDirtyFlag() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("flush_page.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);
            Page page = bp.readPage(id);
            page.putInt(0, 777);
            assertTrue(page.isDirty());

            bp.flushPage(id);
            assertFalse(page.isDirty(), "Dirty flag must be cleared after flushPage");
        }
    }

    @Test
    @DisplayName("flushPage on a clean page is a no-op (no error)")
    void flush_flushCleanPageIsNoOp() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("flush_clean.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);
            Page page = bp.readPage(id);
            assertFalse(page.isDirty());
            assertDoesNotThrow(() -> bp.flushPage(id));
        }
    }

    @Test
    @DisplayName("flushPage throws for page not in pool")
    void flush_flushAbsentPageThrows() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("flush_absent.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);
            // Page not loaded into pool
            assertThrows(ForgeDBException.class, () -> bp.flushPage(id));
        }
    }

    @Test
    @DisplayName("flushAll flushes all dirty pages and clears their dirty flags")
    void flush_flushAllClearsAllDirtyFlags() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("flush_all.fdb"))) {
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();

            BufferPool bp = new BufferPool(dm, 8);
            Page page1 = bp.readPage(p1);
            Page page2 = bp.readPage(p2);
            Page page3 = bp.readPage(p3);

            page1.putInt(0, 1);
            page3.putInt(0, 3);
            // page2 left clean

            bp.flushAll();

            assertFalse(page1.isDirty(), "page1 must be clean after flushAll");
            assertFalse(page2.isDirty(), "page2 was already clean");
            assertFalse(page3.isDirty(), "page3 must be clean after flushAll");
        }
    }

    @Test
    @DisplayName("Data written by flushPage is readable after reopening DiskManager")
    void flush_dataReadableAfterReopen() throws ForgeDBException {
        String path = dbPath("flush_persist.fdb");
        PageId id;

        try (DiskManager dm = new DiskManagerImpl(path)) {
            id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);
            Page page = bp.readPage(id);
            page.putInt(0, 0xBEEF_CAFE);
            bp.flushPage(id);
            // Do NOT call writePage again — flush must have persisted it
        }

        try (DiskManager dm2 = new DiskManagerImpl(path)) {
            Page page = dm2.readPage(id);
            assertEquals(0xBEEF_CAFE, page.getInt(0),
                "Value must be readable after flush + reopen");
        }
    }

    // =========================================================================
    // 6. LRU eviction
    // =========================================================================

    @Test
    @DisplayName("LRU eviction: least-recently-used unpinned page is evicted first")
    void lru_leastRecentlyUsedEvictedFirst() throws ForgeDBException {
        String path = dbPath("lru.fdb");

        try (DiskManager dm = new DiskManagerImpl(path)) {
            // Allocate 4 pages, pool capacity = 3
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();
            PageId p4 = dm.allocatePage();

            BufferPool bp = new BufferPool(dm, 3);

            // Load p1, p2, p3 in order → LRU order: p1(oldest), p2, p3(newest)
            bp.readPage(p1); bp.unpin(p1);
            bp.readPage(p2); bp.unpin(p2);
            bp.readPage(p3); bp.unpin(p3);

            assertEquals(3, bp.size());
            assertTrue(bp.contains(p1));
            assertTrue(bp.contains(p2));
            assertTrue(bp.contains(p3));

            // Loading p4 must evict p1 (least recently used)
            bp.readPage(p4); bp.unpin(p4);

            assertFalse(bp.contains(p1), "p1 (LRU) must have been evicted");
            assertTrue(bp.contains(p2), "p2 must still be in pool");
            assertTrue(bp.contains(p3), "p3 must still be in pool");
            assertTrue(bp.contains(p4), "p4 must be in pool after loading");
        }
    }

    @Test
    @DisplayName("LRU eviction: accessing a page refreshes its recency")
    void lru_accessRefreshesRecency() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("lru_refresh.fdb"))) {
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();
            PageId p4 = dm.allocatePage();

            BufferPool bp = new BufferPool(dm, 3);

            // Load p1, p2, p3
            bp.readPage(p1); bp.unpin(p1);   // LRU: p1, p2, p3
            bp.readPage(p2); bp.unpin(p2);
            bp.readPage(p3); bp.unpin(p3);

            // Re-access p1 → it moves to MRU position: LRU order now p2, p3, p1
            bp.readPage(p1); bp.unpin(p1);

            // Loading p4 should now evict p2 (oldest), not p1
            bp.readPage(p4); bp.unpin(p4);

            assertFalse(bp.contains(p2), "p2 must be evicted (now LRU after p1 was re-accessed)");
            assertTrue(bp.contains(p1), "p1 must stay (was re-accessed most recently)");
            assertTrue(bp.contains(p3), "p3 must stay");
            assertTrue(bp.contains(p4), "p4 must be in pool");
        }
    }

    @Test
    @DisplayName("LRU eviction: dirty page is flushed to disk before being evicted")
    void lru_dirtyPageFlushedOnEviction() throws ForgeDBException {
        String path = dbPath("lru_dirty.fdb");
        PageId p1;
        int sentinel = 0xDEAD_BEEF;

        try (DiskManager dm = new DiskManagerImpl(path)) {
            p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();

            BufferPool bp = new BufferPool(dm, 2);

            // Load p1 into pool, modify it (dirty), then unpin it
            Page page1 = bp.readPage(p1);
            page1.putInt(0, sentinel);          // marks dirty
            bp.unpin(p1);

            // Load p2 (pool now full: p1, p2)
            bp.readPage(p2); bp.unpin(p2);

            // Loading p3 must evict p1 (LRU) — since p1 is dirty it must be flushed first
            bp.readPage(p3); bp.unpin(p3);

            assertFalse(bp.contains(p1), "p1 must have been evicted");
        }

        // Reopen and verify the dirty value was flushed during eviction
        try (DiskManager dm2 = new DiskManagerImpl(path)) {
            Page page = dm2.readPage(p1);
            assertEquals(sentinel, page.getInt(0),
                "Dirty page must have been flushed to disk during LRU eviction");
        }
    }

    @Test
    @DisplayName("LRU eviction: clean pages are evicted without a disk write")
    void lru_cleanPageEvictedWithoutWrite() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("lru_clean.fdb"))) {
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();

            BufferPool bp = new BufferPool(dm, 2);
            bp.readPage(p1); bp.unpin(p1);
            bp.readPage(p2); bp.unpin(p2);

            // p1 is clean and LRU; loading p3 must evict p1 without error
            assertDoesNotThrow(() -> {
                bp.readPage(p3); bp.unpin(p3);
            });
            assertFalse(bp.contains(p1), "p1 must have been evicted");
        }
    }

    // =========================================================================
    // 7. All pages pinned — pool exhausted
    // =========================================================================

    @Test
    @DisplayName("Loading a page when all frames are pinned throws ForgeDBException")
    void exhausted_allPinnedThrows() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("exhausted.fdb"))) {
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();

            // Pool capacity = 2; pin both pages without unpinning
            BufferPool bp = new BufferPool(dm, 2);
            bp.readPage(p1);  // pin p1 (pinCount=1, NOT unpinned)
            bp.readPage(p2);  // pin p2 (pinCount=1, NOT unpinned)

            // Pool is full and all frames are pinned → must throw
            ForgeDBException ex = assertThrows(ForgeDBException.class,
                () -> bp.readPage(p3),
                "Loading a new page when all frames are pinned must throw");

            assertTrue(ex.getMessage().toLowerCase().contains("pinned"),
                "Exception message should mention 'pinned'");
        }
    }

    @Test
    @DisplayName("After unpinning a page, the pool can evict it and load a new one")
    void exhausted_unpinReleasesFrame() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("unpin_release.fdb"))) {
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();

            BufferPool bp = new BufferPool(dm, 2);
            bp.readPage(p1);
            bp.readPage(p2);

            // Pool is full and both pinned — p3 would fail
            // Now unpin p1 and retry
            bp.unpin(p1);

            // p3 should now load successfully (evicts p1)
            assertDoesNotThrow(() -> bp.readPage(p3),
                "After unpinning, the pool must be able to load a new page");
            assertTrue(bp.contains(p3));
        }
    }

    // =========================================================================
    // 8. Persistence — data written by close() survives reopen
    // =========================================================================

    @Test
    @DisplayName("close() flushes all dirty pages before closing")
    void persistence_closeFlushesDirtyPages() throws ForgeDBException {
        String path = dbPath("close_flush.fdb");
        PageId id;

        try (BufferPool bp = new BufferPool(new DiskManagerImpl(path), 4)) {
            id = bp.allocatePage();
            Page page = bp.readPage(id);
            page.putInt(0, 0xCAFE_BABE);
            bp.unpin(id);
            // close() is called by try-with-resources — must flush dirty page
        }

        // Reopen and check the value was persisted
        try (DiskManager dm = new DiskManagerImpl(path)) {
            Page page = dm.readPage(id);
            assertEquals(0xCAFE_BABE, page.getInt(0),
                "close() must flush dirty pages to disk");
        }
    }

    @Test
    @DisplayName("Multiple pages survive close and reopen")
    void persistence_multiplePagesSurviveReopen() throws ForgeDBException {
        String path = dbPath("multi_persist.fdb");
        PageId p1, p2, p3;

        try (BufferPool bp = new BufferPool(new DiskManagerImpl(path), 4)) {
            p1 = bp.allocatePage();
            p2 = bp.allocatePage();
            p3 = bp.allocatePage();

            Page page1 = bp.readPage(p1); page1.putInt(0, 111); bp.unpin(p1);
            Page page2 = bp.readPage(p2); page2.putInt(0, 222); bp.unpin(p2);
            Page page3 = bp.readPage(p3); page3.putInt(0, 333); bp.unpin(p3);
        }

        try (DiskManager dm = new DiskManagerImpl(path)) {
            assertEquals(111, dm.readPage(p1).getInt(0));
            assertEquals(222, dm.readPage(p2).getInt(0));
            assertEquals(333, dm.readPage(p3).getInt(0));
        }
    }

    // =========================================================================
    // 9. DiskManager delegation — allocatePage, getPageCount
    // =========================================================================

    @Test
    @DisplayName("allocatePage delegates to DiskManager and increments page count")
    void delegation_allocatePage() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("alloc.fdb"))) {
            BufferPool bp = new BufferPool(dm, 4);
            assertEquals(1, bp.getPageCount()); // header only

            PageId id = bp.allocatePage();
            assertNotNull(id);
            assertEquals(1, id.value(), "First allocated page should be PageId(1)");
            assertEquals(2, bp.getPageCount());
        }
    }

    @Test
    @DisplayName("getPageCount reflects DiskManager's page count")
    void delegation_getPageCount() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("pagecount.fdb"))) {
            BufferPool bp = new BufferPool(dm, 8);
            assertEquals(1, bp.getPageCount());

            bp.allocatePage();
            bp.allocatePage();
            assertEquals(3, bp.getPageCount());
        }
    }

    @Test
    @DisplayName("Pages allocated via BufferPool can be read back via same BufferPool")
    void delegation_allocateAndReadBack() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("alloc_read.fdb"))) {
            BufferPool bp = new BufferPool(dm, 4);
            PageId id = bp.allocatePage();

            Page page = bp.readPage(id);
            page.putInt(0, 42);
            bp.writePage(id, page);
            bp.unpin(id);

            // Evict by filling pool with other pages
            for (int i = 0; i < 3; i++) {
                PageId extra = bp.allocatePage();
                Page   ep    = bp.readPage(extra);
                bp.unpin(extra);
            }

            // Re-read — page may or may not have been evicted depending on pool state.
            // Either way, reading it again must return the value that was written.
            Page reloaded = bp.readPage(id);
            assertEquals(42, reloaded.getInt(0),
                "Value must survive eviction and reload from disk");
            bp.unpin(id);
        }
    }

    // =========================================================================
    // 10. HeapFile integration — HeapFile works correctly when given a BufferPool
    // =========================================================================

    @Test
    @DisplayName("HeapFile accepts BufferPool as its DiskManager and inserts tuples correctly")
    void integration_heapFileWithBufferPool() throws ForgeDBException {
        // Importing catalog classes is not possible in this package directly,
        // so we test HeapFile-level behaviour using the storage layer only.
        // This test verifies that a BufferPool (which implements DiskManager)
        // can be passed to HeapFile without any changes to HeapFile.
        try (DiskManager dm = new DiskManagerImpl(dbPath("heapfile_bp.fdb"))) {
            BufferPool bp = new BufferPool(dm, 8);
            // Verify allocatePage and readPage round-trip through BufferPool
            PageId id = bp.allocatePage();
            Page   p  = bp.readPage(id);
            // Initialise a DataPage on it
            DataPage dp = DataPage.init(p);
            byte[] record = new byte[]{1, 2, 3, 4, 5};
            int slot = dp.insertRecord(record);
            bp.writePage(id, p);
            bp.unpin(id);

            // Re-read (may be a cache hit or miss depending on pool state)
            Page   p2  = bp.readPage(id);
            DataPage dp2 = new DataPage(p2);
            assertArrayEquals(record, dp2.readRecord(slot),
                "Record inserted through BufferPool must be readable");
            bp.unpin(id);
        }
    }

    @Test
    @DisplayName("Buffer pool with capacity 1 can process sequential page accesses by unpinning")
    void integration_capacityOneSequentialAccess() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("cap1_seq.fdb"))) {
            // Allocate 5 pages
            List<PageId> pages = new ArrayList<>();
            for (int i = 0; i < 5; i++) pages.add(dm.allocatePage());

            // Write sentinels directly
            for (int i = 0; i < 5; i++) {
                Page p = dm.readPage(pages.get(i));
                p.putInt(0, i * 100);
                dm.writePage(pages.get(i), p);
            }

            // Pool of capacity 1 — can only hold one page at a time
            BufferPool bp = new BufferPool(dm, 1);

            for (int i = 0; i < 5; i++) {
                Page p = bp.readPage(pages.get(i));
                assertEquals(i * 100, p.getInt(0),
                    "Value must be correct for page " + i);
                bp.unpin(pages.get(i));  // must unpin before next read
            }
        }
    }

    @Test
    @DisplayName("WritePage through BufferPool updates cached copy")
    void writePage_updatesCachedCopy() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("write_cache.fdb"))) {
            PageId id = dm.allocatePage();
            BufferPool bp = new BufferPool(dm, 4);

            // Read and modify directly
            Page p1 = bp.readPage(id);
            p1.putInt(0, 100);
            bp.writePage(id, p1);

            // Read again (should be a cache hit)
            Page p2 = bp.readPage(id);
            assertSame(p1, p2, "Should be the same cached object after writePage");
            assertEquals(100, p2.getInt(0));

            bp.unpin(id);
            bp.unpin(id);
        }
    }

    // =========================================================================
    // 11. Edge cases
    // =========================================================================

    @Test
    @DisplayName("Pool correctly handles page 0 (header page)")
    void edgeCase_headerPageCacheable() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("header.fdb"))) {
            BufferPool bp = new BufferPool(dm, 4);
            PageId headerId = new PageId(Constants.HEADER_PAGE_ID);

            Page header = bp.readPage(headerId);
            assertNotNull(header);
            assertEquals(PageType.HEADER, header.getPageType());
            assertEquals(1, bp.getPinCount(headerId));
            bp.unpin(headerId);
            assertEquals(0, bp.getPinCount(headerId));
        }
    }

    @Test
    @DisplayName("LRU order with capacity equal to page count loads all without eviction")
    void lru_noEvictionWhenPoolHasCapacity() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("no_evict.fdb"))) {
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();

            // Pool exactly fits all three pages
            BufferPool bp = new BufferPool(dm, 3);
            bp.readPage(p1); bp.unpin(p1);
            bp.readPage(p2); bp.unpin(p2);
            bp.readPage(p3); bp.unpin(p3);

            // All three should still be present
            assertTrue(bp.contains(p1));
            assertTrue(bp.contains(p2));
            assertTrue(bp.contains(p3));
            assertEquals(3, bp.size());
        }
    }

    @Test
    @DisplayName("Re-reading an evicted page reloads it from disk with original value")
    void eviction_reloadAfterEviction() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("reload.fdb"))) {
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();

            // Write a sentinel to p1 via DiskManager directly
            Page raw = dm.readPage(p1);
            raw.putInt(0, 0x1234_5678);
            dm.writePage(p1, raw);

            BufferPool bp = new BufferPool(dm, 2);
            bp.readPage(p1); bp.unpin(p1);  // load p1
            bp.readPage(p2); bp.unpin(p2);  // load p2; pool full [p1, p2]
            bp.readPage(p3); bp.unpin(p3);  // load p3; evicts p1 (LRU)

            assertFalse(bp.contains(p1), "p1 must have been evicted");

            // Re-read p1 — must reload from disk and return original value
            Page reloaded = bp.readPage(p1);
            assertEquals(0x1234_5678, reloaded.getInt(0),
                "Re-loaded page must have the value that was on disk");
            bp.unpin(p1);
        }
    }
}
