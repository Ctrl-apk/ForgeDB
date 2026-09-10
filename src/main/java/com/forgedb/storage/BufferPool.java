package com.forgedb.storage;

import com.forgedb.common.ForgeDBException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory page cache sitting between the upper storage layers and the
 * DiskManager.
 *
 * <h2>Why a buffer pool?</h2>
 * Without caching, every {@code readPage} / {@code writePage} call goes to
 * disk. A table scan over 100 pages that reads each page five times costs 500
 * disk I/Os. With a buffer pool, the second through fifth reads for each page
 * are satisfied from RAM — cost: 100 disk reads total.
 *
 * <h2>Core data structure: the Frame</h2>
 * Each in-memory slot is called a <em>frame</em>. A frame holds:
 * <ul>
 *   <li>the {@link Page} itself</li>
 *   <li>the {@link PageId} it currently maps to</li>
 *   <li>a <em>pin count</em> — how many callers currently hold a reference</li>
 * </ul>
 * A page is "pinned" while a caller is using it. The buffer pool will never
 * evict a pinned page. Callers <strong>must</strong> call {@link #unpin} when
 * they are done with a page, or the buffer pool will fill up and become unable
 * to load new pages.
 *
 * <h2>Page table</h2>
 * A {@code HashMap<PageId, Frame>} maps logical page addresses to frames.
 * {@code PageId.equals} / {@code hashCode} are value-based (confirmed from
 * source), so this works correctly.
 *
 * <h2>Eviction policy: LRU via LinkedHashMap</h2>
 * We maintain a separate {@code LinkedHashMap<PageId, Frame>} in
 * access-order mode. Every time a page is pinned (accessed), it moves to the
 * tail. When eviction is needed, we scan from the head (least recently used)
 * and evict the first unpinned page we find.
 *
 * This is the classic "Clock" or "LRU" approach used by PostgreSQL's buffer
 * manager and InnoDB. The implementation here uses Java's {@link LinkedHashMap}
 * with {@code accessOrder=true} as the LRU queue, which gives O(1) move-to-tail
 * on access and O(1) removal.
 *
 * <h2>Dirty pages and flushing</h2>
 * A page is dirty when its in-memory content differs from the disk copy. The
 * dirty flag is maintained by {@link Page#isDirty()} — any call to a payload
 * mutator ({@code putByte}, {@code putInt}, etc.) sets it. When a dirty page
 * is evicted, the buffer pool flushes it to disk by calling
 * {@code diskManager.writePage()} before reusing the frame. {@code writePage}
 * internally calls {@code page.updateChecksum()} and {@code page.clearDirty()}.
 *
 * <h2>Implementing DiskManager</h2>
 * {@code BufferPool} implements the {@link DiskManager} interface so it can be
 * passed transparently to existing components (e.g. {@link HeapFile}) that
 * already accept a {@code DiskManager}. This avoids changing any existing
 * constructor signatures.
 *
 * When upper-layer code calls {@code readPage}, the buffer pool:
 * <ol>
 *   <li>Checks the page table (cache hit → return pinned page)</li>
 *   <li>On miss: finds a victim frame via LRU eviction</li>
 *   <li>If victim is dirty: flushes it to disk</li>
 *   <li>Loads the requested page from disk into the victim frame</li>
 *   <li>Registers the frame in the page table and LRU order</li>
 *   <li>Pins and returns the page</li>
 * </ol>
 *
 * When upper-layer code calls {@code writePage}, the buffer pool:
 * <ol>
 *   <li>Writes the page to disk immediately (pass-through to DiskManager)</li>
 *   <li>If the page is also in the cache, updates the frame to reflect it is
 *       now clean and has the latest content</li>
 * </ol>
 *
 * <h2>allocatePage and getPageCount</h2>
 * These are delegated directly to the underlying DiskManager because page
 * allocation modifies the database file structure — it does not involve cached
 * content.
 *
 * <h2>Thread safety</h2>
 * BufferPool is NOT thread-safe. Concurrent access must be serialised by the
 * caller (Milestone 7 will add a latch layer).
 */
public class BufferPool implements DiskManager {

    // -------------------------------------------------------------------------
    // Frame: one slot in the buffer pool
    // -------------------------------------------------------------------------

    /**
     * A single buffer pool slot: holds one page and its metadata.
     *
     * Pin count semantics:
     *  - 0  : page is not currently in use; eligible for eviction
     *  - >0 : page is held by one or more callers; must not be evicted
     *
     * Package-private so tests in the same package can inspect frame state.
     */
    static final class Frame {
        Page   page;
        PageId pageId;
        int    pinCount;

        Frame(PageId pageId, Page page) {
            this.pageId   = pageId;
            this.page     = page;
            this.pinCount = 0;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** The disk manager we sit on top of. */
    private final DiskManager diskManager;

    /** Maximum number of pages this pool can hold simultaneously. */
    private final int capacity;

    /**
     * Page table: maps PageId → Frame for every page currently in the pool.
     * Used for O(1) cache lookups.
     */
    private final Map<PageId, Frame> pageTable;

    /**
     * LRU order map: same entries as pageTable, but ordered by recency of
     * access (oldest at head, most-recently-used at tail).
     *
     * {@code LinkedHashMap(capacity, 0.75f, true)} with {@code accessOrder=true}
     * moves an entry to the tail on every {@code get()} call, giving us LRU
     * ordering for free.
     *
     * We never call {@code lruOrder.get()} for value retrieval — we use it only
     * for the access-order side effect and for iterating from the head to find
     * the LRU victim.
     */
    private final LinkedHashMap<PageId, Frame> lruOrder;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a BufferPool with the given capacity.
     *
     * @param diskManager the underlying disk manager to load/flush pages from/to
     * @param capacity    maximum number of pages held in memory at once; must be >= 1
     * @throws IllegalArgumentException if capacity < 1
     */
    public BufferPool(DiskManager diskManager, int capacity) {
        if (diskManager == null) {
            throw new NullPointerException("diskManager must not be null");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException(
                "Buffer pool capacity must be >= 1, got: " + capacity);
        }
        this.diskManager = diskManager;
        this.capacity    = capacity;
        this.pageTable   = new HashMap<>(capacity * 2);
        // accessOrder=true: get() moves the accessed entry to the tail
        this.lruOrder    = new LinkedHashMap<>(capacity * 2, 0.75f, true);
    }

    // -------------------------------------------------------------------------
    // DiskManager implementation
    // -------------------------------------------------------------------------

    /**
     * Returns the requested page, loading it from disk if not already cached.
     *
     * The returned page is <strong>pinned</strong>: the buffer pool will not
     * evict it until {@link #unpin(PageId)} is called. Callers must call
     * {@code unpin} when they are done with the page.
     *
     * Cache hit path: O(1) — page is in the pool, pin count incremented.
     * Cache miss path: find LRU victim → maybe flush → load from disk → register.
     *
     * @param pageId the page to fetch
     * @return the in-memory Page (already pinned)
     * @throws ForgeDBException if the page is not cached, all frames are pinned
     *         (pool exhausted), or an I/O error occurs
     */
    @Override
    public Page readPage(PageId pageId) throws ForgeDBException {
        // --- Cache hit ---
        Frame frame = pageTable.get(pageId);
        if (frame != null) {
            frame.pinCount++;
            // Touch in LRU order (moves to tail = most recently used)
            lruOrder.get(pageId);
            return frame.page;
        }

        // --- Cache miss: need a free frame ---
        Frame victim = evict();   // throws if all pages are pinned

        // Load the page from disk into the victim frame
        Page loaded = diskManager.readPage(pageId);
        victim.pageId   = pageId;
        victim.page     = loaded;
        victim.pinCount = 1;      // pinned by this caller

        // Register in page table and LRU order
        pageTable.put(pageId, victim);
        lruOrder.put(pageId, victim);

        return loaded;
    }

    /**
     * Writes the given page to disk and updates the cached copy if present.
     *
     * This is a write-through operation: the page goes to disk immediately.
     * If the page is also in the cache, its frame is updated to reflect the
     * clean state (since writePage calls page.clearDirty() internally).
     *
     * @param pageId the page address to write
     * @param page   the page content to persist
     * @throws ForgeDBException if the page ID is out of range or an I/O error occurs
     */
    @Override
    public void writePage(PageId pageId, Page page) throws ForgeDBException {
        // Write through to disk — diskManager.writePage stamps checksum and clears dirty
        diskManager.writePage(pageId, page);

        // If this page is in the cache, update the frame with the latest content.
        // This keeps the cached copy consistent with what was just written.
        Frame frame = pageTable.get(pageId);
        if (frame != null) {
            frame.page = page;
        }
    }

    /**
     * Allocates a new page via the underlying DiskManager.
     *
     * The new page is NOT automatically loaded into the buffer pool cache.
     * The caller should subsequently call {@link #readPage} to pin and use it.
     *
     * @return the PageId of the newly allocated page
     * @throws ForgeDBException if an I/O error occurs
     */
    @Override
    public PageId allocatePage() throws ForgeDBException {
        return diskManager.allocatePage();
    }

    /**
     * Returns the total number of pages in the database file (delegates to
     * the underlying DiskManager — this is a file-level count, not a pool count).
     */
    @Override
    public int getPageCount() {
        return diskManager.getPageCount();
    }

    /**
     * Flushes all dirty pages in the pool to disk, then closes the underlying
     * DiskManager.
     *
     * After this call the buffer pool must not be used.
     *
     * @throws ForgeDBException if a flush or close fails
     */
    @Override
    public void close() throws ForgeDBException {
        flushAll();
        diskManager.close();
    }

    // -------------------------------------------------------------------------
    // Pin / Unpin
    // -------------------------------------------------------------------------

    /**
     * Decrements the pin count of the page identified by {@code pageId}.
     *
     * Once the pin count reaches 0 the page becomes eligible for eviction.
     * A page must be unpinned exactly as many times as it was pinned.
     *
     * @param pageId the page to unpin
     * @throws ForgeDBException if the page is not in the pool, or its pin count
     *         is already 0 (indicates a programming error — double-unpin)
     */
    public void unpin(PageId pageId) throws ForgeDBException {
        Frame frame = pageTable.get(pageId);
        if (frame == null) {
            throw new ForgeDBException(
                "Cannot unpin " + pageId + ": page is not in the buffer pool");
        }
        if (frame.pinCount <= 0) {
            throw new ForgeDBException(
                "Cannot unpin " + pageId + ": pin count is already 0 (double-unpin?)");
        }
        frame.pinCount--;
    }

    // -------------------------------------------------------------------------
    // Flush
    // -------------------------------------------------------------------------

    /**
     * Flushes a single dirty page to disk without evicting it from the pool.
     * If the page is clean, this is a no-op.
     *
     * @param pageId the page to flush
     * @throws ForgeDBException if the page is not in the pool or an I/O error occurs
     */
    public void flushPage(PageId pageId) throws ForgeDBException {
        Frame frame = pageTable.get(pageId);
        if (frame == null) {
            throw new ForgeDBException(
                "Cannot flush " + pageId + ": page is not in the buffer pool");
        }
        if (frame.page.isDirty()) {
            diskManager.writePage(frame.pageId, frame.page);
            // writePage clears the dirty flag on success
        }
    }

    /**
     * Flushes all dirty pages currently in the pool to disk.
     * Clean pages are skipped. Does not evict any pages.
     *
     * @throws ForgeDBException if any flush fails
     */
    public void flushAll() throws ForgeDBException {
        for (Frame frame : pageTable.values()) {
            if (frame.page.isDirty()) {
                diskManager.writePage(frame.pageId, frame.page);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Introspection (useful for tests and diagnostics)
    // -------------------------------------------------------------------------

    /** Returns the maximum number of pages this pool can hold simultaneously. */
    public int capacity() {
        return capacity;
    }

    /** Returns the number of pages currently in the pool (pinned or unpinned). */
    public int size() {
        return pageTable.size();
    }

    /**
     * Returns {@code true} if the given page is currently loaded in the pool.
     * Does not pin the page.
     */
    public boolean contains(PageId pageId) {
        return pageTable.containsKey(pageId);
    }

    /**
     * Returns the pin count of the given page, or -1 if the page is not in
     * the pool. Useful for assertions in tests.
     */
    public int getPinCount(PageId pageId) {
        Frame frame = pageTable.get(pageId);
        return frame == null ? -1 : frame.pinCount;
    }

    // -------------------------------------------------------------------------
    // Eviction
    // -------------------------------------------------------------------------

    /**
     * Finds and prepares a victim frame for a new page.
     *
     * Strategy: LRU scan from head of {@code lruOrder} (oldest access first).
     * The first unpinned frame is selected. If the frame's page is dirty, it
     * is flushed to disk before the frame is reused.
     *
     * After this call the victim frame has been removed from both {@code pageTable}
     * and {@code lruOrder}, and its page has been flushed if dirty. It is ready
     * to be loaded with a new page.
     *
     * If the pool has free capacity (size < capacity), a brand-new Frame is
     * returned instead of evicting an existing one.
     *
     * @return a clean, unregistered Frame ready to receive a new page
     * @throws ForgeDBException if all pages in the pool are pinned, or if a
     *         dirty-page flush fails
     */
    private Frame evict() throws ForgeDBException {
        // If we have capacity left, no eviction needed
        if (pageTable.size() < capacity) {
            // Return a placeholder frame (pageId and page will be set by caller)
            return new Frame(null, null);
        }

        // LRU scan: iterate from head (least recently used) to find a victim
        Iterator<Map.Entry<PageId, Frame>> it = lruOrder.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PageId, Frame> entry = it.next();
            Frame candidate = entry.getValue();

            if (candidate.pinCount > 0) {
                continue; // pinned — cannot evict
            }

            // Found an evictable frame
            if (candidate.page.isDirty()) {
                diskManager.writePage(candidate.pageId, candidate.page);
                // writePage calls clearDirty() and updateChecksum() internally
            }

            // Remove from both data structures
            it.remove();                           // removes from lruOrder
            pageTable.remove(candidate.pageId);

            return candidate; // reuse this frame object for the new page
        }

        // Every page in the pool is pinned
        throw new ForgeDBException(
            "Buffer pool exhausted: all " + capacity +
            " frames are pinned. Unpin pages before loading new ones.");
    }
}
