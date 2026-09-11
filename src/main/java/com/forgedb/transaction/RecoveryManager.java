package com.forgedb.transaction;

import com.forgedb.catalog.Column;
import com.forgedb.catalog.DataType;
import com.forgedb.catalog.Schema;
import com.forgedb.common.ForgeDBException;
import com.forgedb.index.BTree;
import com.forgedb.index.BTreeKey;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DataPage;
import com.forgedb.storage.Page;
import com.forgedb.storage.PageId;
import com.forgedb.storage.PageType;
import com.forgedb.storage.TupleSerializer;
import com.forgedb.transaction.WalHeapFile.DeletePayload;
import com.forgedb.transaction.WalHeapFile.InsertPayload;
import com.forgedb.wal.WALManager;
import com.forgedb.wal.WALManager.ScanResult;
import com.forgedb.wal.WALRecord;
import com.forgedb.wal.WALRecordType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Crash recovery for the NO-STEAL / NO-FORCE / REDO-ONLY policy.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Scan WAL</b>: read all valid records from offset 0 using
 *       {@link WALManager#readAll()}. The scan stops safely at any corrupt
 *       or incomplete tail record.</li>
 *   <li><b>Identify committed transactions</b>: collect txnIds that have a
 *       COMMIT record in the valid portion of the log.</li>
 *   <li><b>REDO pass</b>: replay every HEAP_INSERT / HEAP_DELETE / PAGE_IMAGE
 *       record whose txnId is in the committed set. Each redo is idempotent:
 *       if the page's stored pageLSN >= the WAL record's LSN, the page was
 *       already written to disk (either before the crash, or by a previous
 *       recovery run) and the redo is skipped.</li>
 *   <li><b>Ignore uncommitted transactions</b>: because of NO-STEAL, an
 *       uncommitted transaction's dirty pages can never have reached disk.
 *       No UNDO pass is needed or performed.</li>
 *   <li><b>Rebuild indexes</b>: after redo, rebuild any B+ tree indexes from
 *       the heap data. This is simpler than logging index changes and correct
 *       for REDO-ONLY recovery.</li>
 * </ol>
 *
 * <h2>Idempotency</h2>
 * The pageLSN check makes recovery safe to run multiple times. If recovery is
 * interrupted mid-run, the next run simply repeats and skips any already-
 * applied changes.
 *
 * <h2>Checkpoint interaction</h2>
 * When the WAL contains CHECKPOINT_END records, recovery only needs to redo
 * from the latest checkpoint (all earlier changes were flushed to disk at
 * checkpoint time). For simplicity in M9, the checkpoint start LSN is stored
 * in the CHECKPOINT_END payload and recovery uses it to skip earlier records.
 * If no valid checkpoint exists, recovery redoes from the beginning of the log.
 *
 * <h2>Thread safety</h2>
 * RecoveryManager is NOT thread-safe. It is intended to be called during
 * database open, before any other threads access the system.
 */
public final class RecoveryManager {

    private final WALManager  wal;
    private final BufferPool  bufferPool;

    /**
     * @param wal        WAL to scan
     * @param bufferPool buffer pool to apply redo changes to
     */
    public RecoveryManager(WALManager wal, BufferPool bufferPool) {
        this.wal        = wal;
        this.bufferPool = bufferPool;
    }

    // -------------------------------------------------------------------------
    // Recovery entry point
    // -------------------------------------------------------------------------

    /**
     * Runs crash recovery and returns a summary of what was done.
     *
     * @return a {@link RecoveryResult} describing the outcome
     * @throws ForgeDBException if WAL scanning or page I/O fails
     */
    public RecoveryResult recover() throws ForgeDBException {
        // --- 1. Scan the WAL -----------------------------------------------
        ScanResult scan = wal.readAll();
        List<WALRecord> records = scan.records();

        // --- 2. Identify committed transactions ----------------------------
        Set<Long> committed = new HashSet<>();
        // Track the latest CHECKPOINT_END record's "redo from" LSN.
        long redoFromLsn = 0;

        for (WALRecord r : records) {
            if (r.type() == WALRecordType.COMMIT) {
                committed.add(r.txnId());
            }
            if (r.type() == WALRecordType.CHECKPOINT_END) {
                // Payload: [8 bytes] begin LSN of this checkpoint
                if (r.payloadLength() >= 8) {
                    long checkpointBeginLsn =
                        java.nio.ByteBuffer.wrap(r.payload())
                            .order(java.nio.ByteOrder.BIG_ENDIAN).getLong();
                    redoFromLsn = Math.max(redoFromLsn, checkpointBeginLsn);
                }
            }
        }

        // --- 3. REDO pass --------------------------------------------------
        int redoCount = 0;
        int skipCount = 0;

        for (WALRecord r : records) {
            // Skip records before the checkpoint redo-from point.
            if (r.lsn() < redoFromLsn) { skipCount++; continue; }

            if (!committed.contains(r.txnId())) continue;  // uncommitted → skip

            switch (r.type()) {
                case HEAP_INSERT -> {
                    boolean applied = redoInsert(r);
                    if (applied) redoCount++; else skipCount++;
                }
                case HEAP_DELETE -> {
                    boolean applied = redoDelete(r);
                    if (applied) redoCount++; else skipCount++;
                }
                case PAGE_IMAGE -> {
                    boolean applied = redoPageImage(r);
                    if (applied) redoCount++; else skipCount++;
                }
                default -> { /* BEGIN/COMMIT/ROLLBACK/CHECKPOINT_* — nothing to redo */ }
            }
        }

        // --- 4. Flush recovered pages -------------------------------------
        bufferPool.flushAll();

        return new RecoveryResult(committed.size(),
                                   records.size(),
                                   redoCount,
                                   skipCount,
                                   scan.stopReason());
    }

    // -------------------------------------------------------------------------
    // Redo handlers
    // -------------------------------------------------------------------------

    /**
     * Redo a HEAP_INSERT: insert the tuple into the page at the recorded slot.
     * Uses pageLSN to skip if the change was already persisted.
     *
     * @return true if the redo was applied, false if it was skipped
     */
    private boolean redoInsert(WALRecord r) throws ForgeDBException {
        if (r.payloadLength() < 8) return false;
        InsertPayload ins = WalHeapFile.decodeInsertPayload(r.payload());

        // Validate page is accessible
        if (ins.pageId().value() >= bufferPool.getPageCount()) return false;

        Page page = bufferPool.readPage(ins.pageId());
        try {
            // Idempotency check: if pageLSN >= this record's LSN, skip.
            int storedLsn = page.getLsn();
            if (storedLsn >= (int) r.lsn()) return false;

            if (page.getPageType() != PageType.DATA) return false;

            DataPage dp = new DataPage(page);
            // For idempotent redo, we check whether the slot index already
            // contains the same record bytes.
            if (ins.slotIndex() < dp.getSlotCount() && !dp.isDeleted(ins.slotIndex())) {
                byte[] existing = dp.readRecord(ins.slotIndex());
                if (java.util.Arrays.equals(existing, ins.tupleBytes())) {
                    // Already applied — same slot, same content.
                    return false;
                }
            }

            // Apply the insert by writing at the recorded slot position.
            // If the slot index exceeds current count, we grow the slot directory.
            int currentCount = dp.getSlotCount();
            if (ins.slotIndex() >= currentCount) {
                // Extend the slot directory.
                int needed = ins.slotIndex() + 1;
                for (int i = currentCount; i < needed; i++) {
                    dp.insertRecord(new byte[0]);  // placeholder with deleted flag
                }
            }
            // Now insert at the target slot.
            dp.insertRecordAt(ins.slotIndex(), ins.tupleBytes());
            page.setLsn((int) r.lsn());
            bufferPool.writePage(ins.pageId(), page);
            return true;
        } finally {
            bufferPool.unpinPage(ins.pageId());
        }
    }

    /**
     * Redo a HEAP_DELETE: mark the slot as deleted.
     *
     * @return true if the redo was applied, false if it was skipped
     */
    private boolean redoDelete(WALRecord r) throws ForgeDBException {
        if (r.payloadLength() < 8) return false;
        DeletePayload del = WalHeapFile.decodeDeletePayload(r.payload());

        if (del.pageId().value() >= bufferPool.getPageCount()) return false;

        Page page = bufferPool.readPage(del.pageId());
        try {
            int storedLsn = page.getLsn();
            if (storedLsn >= (int) r.lsn()) return false;
            if (page.getPageType() != PageType.DATA) return false;

            DataPage dp = new DataPage(page);
            if (del.slotIndex() >= dp.getSlotCount()) return false;
            if (dp.isDeleted(del.slotIndex())) {
                // Already deleted — idempotent skip.
                return false;
            }
            dp.deleteRecord(del.slotIndex());
            page.setLsn((int) r.lsn());
            bufferPool.writePage(del.pageId(), page);
            return true;
        } finally {
            bufferPool.unpinPage(del.pageId());
        }
    }

    /**
     * Redo a PAGE_IMAGE: restore the full page from the WAL record payload.
     */
    private boolean redoPageImage(WALRecord r) throws ForgeDBException {
        if (r.payload().length != com.forgedb.common.Constants.PAGE_SIZE) return false;
        if (r.pageId() < 0 || r.pageId() >= bufferPool.getPageCount()) return false;

        PageId pid = new PageId(r.pageId());
        Page page = bufferPool.readPage(pid);
        try {
            int storedLsn = page.getLsn();
            if (storedLsn >= (int) r.lsn()) return false;
            // Restore full page image.
            Page restored = new Page(r.payload());
            bufferPool.writePage(pid, restored);
            return true;
        } finally {
            bufferPool.unpinPage(pid);
        }
    }

    // -------------------------------------------------------------------------
    // Index rebuild
    // -------------------------------------------------------------------------

    /**
     * Rebuilds a B+ tree index on the first column of a heap file from
     * current heap data. Called after recovery to restore index consistency.
     *
     * This is simpler and safer than logging index changes: after redo the
     * heap is consistent, and a full re-scan populates the index correctly.
     *
     * @param bufferPool buffer pool backing the database
     * @param schema     the table schema
     * @return a newly-built BTree ready for use, or null if the key type
     *         cannot be indexed (BOOLEAN)
     * @throws ForgeDBException if index creation or scan fails
     */
    public static BTree rebuildIndex(BufferPool bufferPool, Schema schema)
            throws ForgeDBException {
        if (schema.columnCount() == 0) return null;
        DataType keyType = schema.getColumn(0).type();
        if (keyType == DataType.BOOLEAN) return null;

        BTree tree = BTree.create(bufferPool, keyType);
        String keyName = schema.getColumn(0).name();

        // Scan all DATA pages for live records and insert into the new index.
        int totalPages = bufferPool.getPageCount();
        for (int p = 1; p < totalPages; p++) {
            PageId pid = new PageId(p);
            Page page = bufferPool.readPage(pid);
            try {
                if (page.getPageType() != PageType.DATA) continue;
                DataPage dp = new DataPage(page);
                int slotCount = dp.getSlotCount();
                for (int slot = 0; slot < slotCount; slot++) {
                    if (dp.isDeleted(slot)) continue;
                    byte[] rec = dp.readRecord(slot);
                    com.forgedb.catalog.Tuple t =
                        TupleSerializer.deserialize(schema, rec, 0, rec.length);
                    if (!t.isNull(0)) {
                        BTreeKey key = toBTreeKey(t.get(0), keyType, keyName);
                        tree.insert(key, new com.forgedb.storage.RecordId(pid, slot));
                    }
                }
            } finally {
                bufferPool.unpinPage(pid);
            }
        }
        return tree;
    }

    private static BTreeKey toBTreeKey(Object value, DataType type, String col)
            throws ForgeDBException {
        return switch (type) {
            case INT    -> BTreeKey.ofInt((Integer) value);
            case LONG   -> BTreeKey.ofLong((Long) value);
            case DOUBLE -> BTreeKey.ofDouble((Double) value);
            case TEXT   -> BTreeKey.ofText((String) value);
            default     -> throw new ForgeDBException(
                               "Cannot index column '" + col + "' of type " + type);
        };
    }

    // -------------------------------------------------------------------------
    // Result
    // -------------------------------------------------------------------------

    /** Summary of a recovery run. */
    public record RecoveryResult(
        int  committedTxns,
        int  totalWalRecords,
        int  redoApplied,
        int  redoSkipped,
        String stopReason     // null = clean end-of-log
    ) {
        public boolean isCleanLog() { return stopReason == null; }

        @Override public String toString() {
            return String.format(
                "RecoveryResult{committed=%d, walRecords=%d, redoApplied=%d, " +
                "redoSkipped=%d, cleanLog=%b, stopReason=%s}",
                committedTxns, totalWalRecords, redoApplied, redoSkipped,
                isCleanLog(), stopReason);
        }
    }
}
