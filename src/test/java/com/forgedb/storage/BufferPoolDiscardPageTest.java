package com.forgedb.storage;

import com.forgedb.common.ForgeDBException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BufferPool#discardPage(PageId)}.
 *
 * discardPage() removes a frame from the pool WITHOUT flushing it: the
 * on-disk copy (the last persisted state) remains authoritative. This is the
 * primitive the M9 no-steal rollback design will use to abandon uncommitted
 * in-memory page changes. Contract under test:
 *
 *  - clean page: removed from the pool (page table + LRU order)
 *  - dirty page: removed WITHOUT being written to disk
 *  - pinned page: rejected with a clear error
 *  - absent page: rejected with a clear error
 *  - pool stays usable and its LRU/eviction/flush behaviour is unchanged
 */
class BufferPoolDiscardPageTest {

    @TempDir
    Path tempDir;

    private BufferPool newPool(String name, int capacity) throws ForgeDBException {
        return new BufferPool(
            new DiskManagerImpl(tempDir.resolve(name).toString()), capacity);
    }

    /** Writes an int at payload offset 0 of the page and leaves it unpinned in the pool. */
    private static void writeValue(BufferPool pool, PageId id, int value) throws ForgeDBException {
        Page page = pool.readPage(id);          // pins
        page.putInt(0, value);
        pool.unpin(id);
    }

    /** Reads the int at payload offset 0 of the page (pin acquired and released). */
    private static int readValue(BufferPool pool, PageId id) throws ForgeDBException {
        Page page = pool.readPage(id);          // pins
        int v = page.getInt(0);
        pool.unpin(id);
        return v;
    }

    // =========================================================================
    // Clean page discard
    // =========================================================================

    @Test
    @DisplayName("discardPage removes a clean page from the pool")
    void discardsCleanPage() throws ForgeDBException {
        try (BufferPool pool = newPool("clean.fdb", 4)) {
            PageId id = pool.allocatePage();
            writeValue(pool, id, 111);
            pool.flushPage(id);          // now the frame is genuinely clean

            assertTrue(pool.contains(id));
            assertEquals(0, pool.getPinCount(id));
            assertFalse(pool.readPage(id).isDirty(), "frame must be clean");
            pool.unpin(id);

            pool.discardPage(id);

            assertFalse(pool.contains(id), "page must be gone from the page table");
            assertEquals(-1, pool.getPinCount(id), "absent pages report pin count -1");
            assertTrue(pool.size() <= pool.capacity(), "pool size must stay within capacity");
        }
    }

    // =========================================================================
    // Dirty page discard — the core no-steal rollback primitive
    // =========================================================================

    @Test
    @DisplayName("discardPage drops a dirty page WITHOUT persisting it")
    void discardsDirtyPageWithoutPersisting() throws ForgeDBException {
        try (BufferPool pool = newPool("dirty.fdb", 4)) {
            PageId id = pool.allocatePage();

            // Persist an initial value so the disk copy is well-defined.
            writeValue(pool, id, 100);
            pool.flushPage(id);

            // Load, mutate (dirty!), then discard without flushing.
            Page page = pool.readPage(id);
            page.putInt(0, 999);
            assertTrue(page.isDirty(), "precondition: page must be dirty");
            pool.unpin(id);

            pool.discardPage(id);   // must NOT write 999 anywhere

            assertFalse(pool.contains(id));

            // Reload from disk: the persisted value must still be there.
            assertEquals(100, readValue(pool, id),
                "dirty content must NOT have leaked to disk");
        }
    }

    @Test
    @DisplayName("discarding a dirty page then re-reading gives the disk version")
    void reReadAfterDiscardReturnsDiskVersion() throws ForgeDBException {
        try (BufferPool pool = newPool("reread.fdb", 4)) {
            PageId id = pool.allocatePage();
            writeValue(pool, id, 42);
            pool.flushPage(id);              // disk copy = 42

            Page page = pool.readPage(id);
            page.putInt(0, 4242);            // dirty, never flushed
            pool.unpin(id);

            pool.discardPage(id);

            // readPage now reloads from disk, proving the page left the cache.
            Page reloaded = pool.readPage(id);
            assertFalse(reloaded.isDirty(), "reloaded page must be clean from disk");
            assertEquals(42, reloaded.getInt(0));
            pool.unpin(id);
        }
    }

    // =========================================================================
    // Rejections
    // =========================================================================

    @Test
    @DisplayName("discardPage rejects a pinned page with a clear error")
    void rejectsPinnedPage() throws ForgeDBException {
        try (BufferPool pool = newPool("pinned.fdb", 4)) {
            PageId id = pool.allocatePage();

            Page page = pool.readPage(id);   // pins (pinCount = 1)
            try {
                ForgeDBException ex = assertThrows(ForgeDBException.class,
                    () -> pool.discardPage(id),
                    "discarding a pinned page must fail");
                assertTrue(ex.getMessage().contains("pin"),
                    "error message must mention the pin (was: " + ex.getMessage() + ")");
                assertEquals(1, pool.getPinCount(id),
                    "failed discard must not change the pin count");
            } finally {
                pool.unpin(id);
            }

            // Once unpinned, the same page can be discarded.
            pool.discardPage(id);
            assertFalse(pool.contains(id));
        }
    }

    @Test
    @DisplayName("discardPage rejects a page that is not in the pool")
    void rejectsAbsentPage() throws ForgeDBException {
        try (BufferPool pool = newPool("absent.fdb", 4)) {
            PageId neverLoaded = pool.allocatePage();
            // Never readPage()d: allocation does not populate the cache.

            assertFalse(pool.contains(neverLoaded));
            ForgeDBException ex = assertThrows(ForgeDBException.class,
                () -> pool.discardPage(neverLoaded));
            assertTrue(ex.getMessage().contains("not in the buffer pool"),
                "error must say the page is not pooled (was: " + ex.getMessage() + ")");
        }
    }

    // =========================================================================
    // Pool remains healthy after discards
    // =========================================================================

    @Test
    @DisplayName("eviction and dirty-flush behaviour are unchanged after discards")
    void evictionStillFlushesDirtyVictimsAfterDiscard() throws ForgeDBException {
        try (BufferPool pool = newPool("evict.fdb", 3)) {
            PageId a = pool.allocatePage();
            PageId b = pool.allocatePage();
            PageId c = pool.allocatePage();
            PageId d = pool.allocatePage();

            writeValue(pool, a, 1);
            writeValue(pool, b, 2);
            writeValue(pool, c, 3);

            // Discard a, then fill the pool again: {b, c, d}.
            pool.discardPage(a);
            writeValue(pool, d, 4);
            assertTrue(pool.contains(b) && pool.contains(c) && pool.contains(d));
            assertFalse(pool.contains(a));

            // Make c dirty but don't flush it. readPage(c) moves c to the
            // LRU tail (most recently used), so it is NOT the victim yet.
            Page cPage = pool.readPage(c);
            cPage.putInt(0, 30);
            assertTrue(cPage.isDirty());
            pool.unpin(c);

            // Re-touch b and d (LRU order becomes [c, b, d]) so that the
            // dirty page c is at the head when eviction next runs.
            pool.readPage(b);
            pool.unpin(b);
            pool.readPage(d);
            pool.unpin(d);

            // Loading a 5th page must evict the LRU victim (dirty c) and
            // FLUSH it first — existing eviction behaviour, unchanged by
            // discardPage.
            PageId e = pool.allocatePage();
            writeValue(pool, e, 50);
            assertEquals(3, pool.size(), "capacity must still be enforced");
            assertFalse(pool.contains(c), "LRU victim c must have been evicted");

            // c was a dirty victim: its flushed value must now be on disk.
            assertEquals(30, readValue(pool, c),
                "dirty victim must have been flushed on eviction");
            assertEquals(50, readValue(pool, e));
            assertEquals(2, readValue(pool, b));
        }
    }

    @Test
    @DisplayName("pool remains fully usable after repeated allocate/discard cycles")
    void poolUsableAfterRepeatedDiscards() throws ForgeDBException {
        try (BufferPool pool = newPool("usable.fdb", 2)) {
            PageId a = pool.allocatePage();
            PageId b = pool.allocatePage();

            writeValue(pool, a, 7);
            writeValue(pool, b, 8);

            pool.discardPage(a);

            // Repeated allocate / touch / discard cycles on a tiny pool.
            for (int i = 0; i < 50; i++) {
                PageId tmp = pool.allocatePage();
                writeValue(pool, tmp, 1000 + i);
                assertEquals(1000 + i, readValue(pool, tmp));
                pool.discardPage(tmp);
                assertFalse(pool.contains(tmp));
            }

            // The surviving page still holds its value and the pool is healthy.
            assertEquals(8, readValue(pool, b));
            assertTrue(pool.size() <= pool.capacity());
        }
    }

    @Test
    @DisplayName("flushPage and flushAll ignore discarded pages")
    void flushIgnoresDiscardedPages() throws ForgeDBException {
        try (BufferPool pool = newPool("flush.fdb", 4)) {
            PageId kept   = pool.allocatePage();
            PageId thrown = pool.allocatePage();

            writeValue(pool, kept, 1);
            writeValue(pool, thrown, 2);

            pool.discardPage(thrown);

            // Neither flushPage on the discarded id (clear error, no write of
            // phantom state) nor flushAll may resurrect or corrupt anything.
            assertThrows(ForgeDBException.class, () -> pool.flushPage(thrown));

            pool.flushAll();   // must not throw; flushes only pooled pages

            assertEquals(1, readValue(pool, kept));
        }
    }
}
