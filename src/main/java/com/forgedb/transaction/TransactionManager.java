package com.forgedb.transaction;

import com.forgedb.common.ForgeDBException;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.PageId;
import com.forgedb.wal.WALManager;
import com.forgedb.wal.WALRecordType;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transaction lifecycle manager for ForgeDB.
 *
 * <h2>Recovery policy: NO-STEAL / NO-FORCE / REDO-ONLY</h2>
 *
 * <b>NO-STEAL</b>: A dirty page modified by an active transaction must NEVER
 * be written to disk before that transaction commits. This is enforced by
 * keeping all transaction-dirtied pages pinned in the BufferPool while the
 * transaction is active; a pinned frame cannot be evicted.
 *
 * <b>NO-FORCE</b>: At commit time, dirty pages do NOT need to be immediately
 * flushed to disk. Instead, COMMIT durability is guaranteed by writing and
 * force-syncing the WAL before releasing pins. After the WAL is durable, the
 * recovery algorithm can always redo the committed changes.
 *
 * <b>REDO-ONLY</b>: Because uncommitted pages never reach disk (no-steal),
 * there is no need for UNDO records. Recovery reads the WAL, identifies all
 * transactions whose COMMIT record is present, and redoes their writes. No
 * UNDO pass is needed.
 *
 * <h2>Transaction lifecycle</h2>
 * <pre>
 *   begin()   → assigns a unique txnId, appends WAL BEGIN record
 *             → returns a Transaction handle
 *
 *   logInsert/logDelete → caller appends redo WAL records,
 *             → then pages are written to the heap;
 *             → every modified page is added to the transaction's dirty-set
 *               and its pin count is incremented (no-steal pin)
 *
 *   commit()  → 1. append COMMIT WAL record
 *             → 2. WAL sync/force (durability point)
 *             → 3. unpin all transaction pages (allow eviction)
 *
 *   rollback() → 1. discard all dirty pages via BufferPool.discardPage()
 *              → 2. append ROLLBACK WAL record
 *              → 3. release all transaction pins
 * </pre>
 *
 * <h2>Page tracking</h2>
 * The transaction maintains two sets per page:
 *   - dirtyPages: all pages modified in this transaction (for rollback)
 *   - txnPins: all extra pin counts added by the no-steal protocol
 *     (one pin per transaction per page, regardless of how many times
 *     the page was modified in this transaction)
 *
 * <h2>Thread safety</h2>
 * NOT thread-safe. All calls must be serialised externally (Milestone 10).
 */
public final class TransactionManager {

    /** Counter for generating monotonically increasing transaction IDs. */
    private static final AtomicLong TXN_ID_COUNTER = new AtomicLong(1);

    private final WALManager  wal;
    private final BufferPool  bufferPool;

    /** Active transactions by id. */
    private final Map<Long, Transaction> active = new HashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param wal        WAL manager to log transaction records into
     * @param bufferPool buffer pool used to pin/unpin/discard pages
     */
    public TransactionManager(WALManager wal, BufferPool bufferPool) {
        if (wal == null)        throw new NullPointerException("wal must not be null");
        if (bufferPool == null) throw new NullPointerException("bufferPool must not be null");
        this.wal        = wal;
        this.bufferPool = bufferPool;
    }

    // -------------------------------------------------------------------------
    // Begin
    // -------------------------------------------------------------------------

    /**
     * Starts a new transaction and returns its handle.
     * Appends a WAL BEGIN record.
     *
     * @return a new active Transaction
     * @throws ForgeDBException if WAL append fails
     */
    public Transaction begin() throws ForgeDBException {
        long txnId = TXN_ID_COUNTER.getAndIncrement();
        wal.append(WALRecordType.BEGIN, txnId, null);
        Transaction txn = new Transaction(txnId);
        active.put(txnId, txn);
        return txn;
    }

    // -------------------------------------------------------------------------
    // Page dirty tracking (called by WAL-integrated heap operations)
    // -------------------------------------------------------------------------

    /**
     * Records that {@code pageId} was dirtied by transaction {@code txn}.
     *
     * The first time a page is dirtied by a transaction, the TransactionManager
     * acquires an extra pin on that page (via BufferPool.readPage + keeping it
     * pinned). This is the no-steal lock: the LRU eviction algorithm skips
     * pinned pages, so the dirty content cannot reach disk before commit.
     *
     * Subsequent dirty calls for the same page in the same transaction are
     * no-ops (the pin is already held).
     *
     * @param txn    the owning transaction
     * @param pageId the page that was dirtied
     * @throws ForgeDBException if pin acquisition fails (pool exhausted)
     */
    public void trackDirtyPage(Transaction txn, PageId pageId) throws ForgeDBException {
        if (!txn.hasPinForPage(pageId)) {
            // Acquire an extra pin to prevent eviction.
            // readPage increments pin count by 1; we keep that pin until
            // commit (which calls unpin) or rollback (which calls discard).
            bufferPool.readPage(pageId);   // pin acquired; page is now in pool
            txn.addPin(pageId);
        }
        txn.addDirtyPage(pageId);
    }

    // -------------------------------------------------------------------------
    // Commit
    // -------------------------------------------------------------------------

    /**
     * Commits the transaction.
     *
     * <ol>
     *   <li>Appends WAL COMMIT record.</li>
     *   <li>Forces WAL to durable storage (the actual durability point).</li>
     *   <li>Releases all no-steal pins, allowing the buffer pool to evict
     *       dirty pages when needed.</li>
     * </ol>
     *
     * @param txn the transaction to commit
     * @throws ForgeDBException if WAL sync fails or the transaction is not active
     */
    public void commit(Transaction txn) throws ForgeDBException {
        requireActive(txn);
        try {
            // 1. Log the commit decision
            wal.append(WALRecordType.COMMIT, txn.txnId(), null);
            // 2. Force WAL — durability point
            wal.force();
            // 3. Release all no-steal pins
            releaseTransactionPins(txn);
            txn.markCommitted();
        } finally {
            active.remove(txn.txnId());
        }
    }

    // -------------------------------------------------------------------------
    // Rollback
    // -------------------------------------------------------------------------

    /**
     * Rolls back (aborts) the transaction.
     *
     * All dirty pages are discarded from the buffer pool without being written
     * to disk, preserving the NO-STEAL invariant: the on-disk state is never
     * tainted by uncommitted data.
     *
     * <ol>
     *   <li>For each page dirtied by the transaction: call
     *       {@code BufferPool.discardPage()} if it is still in the pool.
     *       The page must first be unpinned to make discard possible.</li>
     *   <li>Appends WAL ROLLBACK record (advisory; recovery ignores
     *       uncommitted transactions anyway).</li>
     * </ol>
     *
     * @param txn the transaction to roll back
     * @throws ForgeDBException if discard fails unexpectedly
     */
    public void rollback(Transaction txn) throws ForgeDBException {
        requireActive(txn);
        try {
            // Unpin each page first (discard requires pin count == 0),
            // then discard dirty pages (drops in-memory content without flushing).
            for (PageId pageId : txn.pinnedPages()) {
                if (bufferPool.contains(pageId)) {
                    bufferPool.unpinPage(pageId);   // release our no-steal pin
                }
            }
            // Discard every dirty page the transaction touched.
            for (PageId pageId : txn.dirtyPages()) {
                if (bufferPool.contains(pageId) && bufferPool.getPinCount(pageId) == 0) {
                    bufferPool.discardPage(pageId);
                }
            }
            // Log the rollback (non-critical; aids human debugging)
            wal.append(WALRecordType.ROLLBACK, txn.txnId(), null);
            txn.markRolledBack();
        } finally {
            active.remove(txn.txnId());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the set of currently active transaction IDs (read-only).
     * Used by the RecoveryManager to identify in-flight transactions during
     * checkpointing.
     */
    public Set<Long> activeTransactionIds() {
        return Collections.unmodifiableSet(active.keySet());
    }

    /** Returns true if there is currently an active transaction. */
    public boolean hasActiveTransaction() { return !active.isEmpty(); }

    private void requireActive(Transaction txn) throws ForgeDBException {
        if (!txn.isActive()) {
            throw new ForgeDBException(
                "Transaction " + txn.txnId() + " is not active (state=" + txn.state() + ")");
        }
        if (!active.containsKey(txn.txnId())) {
            throw new ForgeDBException(
                "Transaction " + txn.txnId() + " is not registered with this manager");
        }
    }

    private void releaseTransactionPins(Transaction txn) throws ForgeDBException {
        for (PageId pageId : txn.pinnedPages()) {
            if (bufferPool.contains(pageId)) {
                bufferPool.unpinPage(pageId);
            }
        }
    }

    // =========================================================================
    // Transaction handle
    // =========================================================================

    /**
     * An active transaction handle. Callers hold this object and pass it to
     * {@link #commit(Transaction)} or {@link #rollback(Transaction)}.
     *
     * The state machine is: ACTIVE → COMMITTED or ROLLED_BACK.
     */
    public static final class Transaction {

        public enum State { ACTIVE, COMMITTED, ROLLED_BACK }

        private final long        txnId;
        private       State       state    = State.ACTIVE;
        private final Set<PageId> dirty    = new HashSet<>();
        private final Set<PageId> pinned   = new HashSet<>();

        Transaction(long txnId) {
            this.txnId = txnId;
        }

        public long    txnId() { return txnId; }
        public State   state() { return state; }
        public boolean isActive()      { return state == State.ACTIVE; }
        public boolean isCommitted()   { return state == State.COMMITTED; }
        public boolean isRolledBack()  { return state == State.ROLLED_BACK; }

        void addDirtyPage(PageId id)   { dirty.add(id); }
        void addPin(PageId id)         { pinned.add(id); }
        boolean hasPinForPage(PageId id) { return pinned.contains(id); }

        /** Unmodifiable view of pages dirtied by this transaction. */
        Set<PageId> dirtyPages()  { return Collections.unmodifiableSet(dirty); }

        /** Unmodifiable view of pages for which a no-steal pin is held. */
        Set<PageId> pinnedPages() { return Collections.unmodifiableSet(pinned); }

        void markCommitted()  { state = State.COMMITTED; }
        void markRolledBack() { state = State.ROLLED_BACK; }

        @Override
        public String toString() {
            return "Transaction{txnId=" + txnId + ", state=" + state
                + ", dirtyPages=" + dirty.size() + "}";
        }
    }
}
