package com.forgedb.transaction;

import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;
import com.forgedb.common.ForgeDBException;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DataPage;
import com.forgedb.storage.HeapFile;
import com.forgedb.storage.Page;
import com.forgedb.storage.PageId;
import com.forgedb.storage.PageType;
import com.forgedb.storage.RecordId;
import com.forgedb.storage.TupleSerializer;
import com.forgedb.transaction.TransactionManager.Transaction;
import com.forgedb.wal.WALManager;
import com.forgedb.wal.WALRecordType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * WAL-integrated wrapper around {@link HeapFile}.
 *
 * <h2>WAL-before-data ordering</h2>
 * Every mutating operation (insert, delete) follows this sequence:
 * <ol>
 *   <li>Write the WAL redo record (appended to the WAL file, still in OS
 *       buffer).</li>
 *   <li>Apply the change to the in-memory page via the underlying HeapFile
 *       or directly.</li>
 *   <li>Stamp the page's LSN field with the WAL record's LSN so the recovery
 *       algorithm can skip already-applied changes (idempotent redo).</li>
 * </ol>
 * The WAL record itself is NOT force-synced here — syncing happens only at
 * COMMIT. This is the NO-FORCE design: data is durable only after COMMIT +
 * sync.
 *
 * <h2>HEAP_INSERT payload format</h2>
 * <pre>
 *   [4 bytes] pageId (int, big-endian)
 *   [4 bytes] slotIndex (int, big-endian)
 *   [N bytes] serialized tuple bytes
 * </pre>
 *
 * <h2>HEAP_DELETE payload format</h2>
 * <pre>
 *   [4 bytes] pageId (int, big-endian)
 *   [4 bytes] slotIndex (int, big-endian)
 * </pre>
 *
 * <h2>pageLSN</h2>
 * The LSN of the WAL record that last modified a page is stored in the page's
 * header (bytes 12–15, the LSN field). During recovery, if a page's stored
 * pageLSN >= the WAL record's LSN, the redo is skipped (already applied).
 */
public final class WalHeapFile {

    private final HeapFile   heap;
    private final WALManager wal;
    private final BufferPool bufferPool;

    /**
     * @param heap       the underlying heap file
     * @param wal        WAL manager for redo records
     * @param bufferPool buffer pool (same one backing the heap)
     */
    public WalHeapFile(HeapFile heap, WALManager wal, BufferPool bufferPool) {
        this.heap       = heap;
        this.wal        = wal;
        this.bufferPool = bufferPool;
    }

    /** Unwrapped heap (for sequential scan, schema access, etc.). */
    public HeapFile heap() { return heap; }

    // -------------------------------------------------------------------------
    // Transactional INSERT
    // -------------------------------------------------------------------------

    /**
     * Inserts a tuple, appending a HEAP_INSERT WAL record before the change.
     *
     * @param txn   the owning transaction (null = non-transactional)
     * @param tuple tuple conforming to this heap's schema
     * @return the RecordId of the new record
     */
    public RecordId insert(Transaction txn, Tuple tuple) throws ForgeDBException {
        if (txn == null) {
            // Non-transactional: delegate directly, no WAL, no pin tracking.
            return heap.insert(tuple);
        }

        // Serialize the tuple — we need the bytes to build the WAL payload.
        Schema schema = heap.schema();
        byte[] tupleBytes = TupleSerializer.serialize(schema, tuple);

        // The heap insert may choose any data page. We need to know WHICH page
        // it lands on to (a) build the WAL payload and (b) stamp the pageLSN.
        RecordId rid = heap.insert(tuple);
        byte[] payload = buildInsertPayload(rid, tupleBytes);
        long walLsn = wal.append(WALRecordType.HEAP_INSERT,
                                  txn.txnId(),
                                  rid.pageId().value(),
                                  -1, payload);
        stampPageLsn(rid.pageId(), (int) walLsn);
        return rid;
    }

    // -------------------------------------------------------------------------
    // Transactional DELETE
    // -------------------------------------------------------------------------

    /**
     * Marks a slot deleted, appending a HEAP_DELETE WAL record before the change.
     *
     * @param txn the owning transaction (null = non-transactional)
     * @param rid the RecordId to delete
     */
    public void delete(Transaction txn, RecordId rid) throws ForgeDBException {
        if (txn == null) {
            heap.delete(rid);
            return;
        }

        // WAL-before-data: log first, then apply.
        byte[] payload = buildDeletePayload(rid);
        long walLsn = wal.append(WALRecordType.HEAP_DELETE,
                                  txn.txnId(),
                                  rid.pageId().value(),
                                  -1,      // pageLSN filled in after
                                  payload);

        // Apply the delete to the heap.
        heap.delete(rid);

        // Stamp the pageLSN on the page.
        stampPageLsn(rid.pageId(), (int) walLsn);
    }

    // -------------------------------------------------------------------------
    // Delegated non-mutating operations
    // -------------------------------------------------------------------------

    public Tuple          read(RecordId rid)     throws ForgeDBException { return heap.read(rid); }
    public List<Tuple>    scan()                 throws ForgeDBException { return heap.scan(); }
    public RecordId       update(Transaction txn, RecordId rid, Tuple newTuple) throws ForgeDBException {
        delete(txn, rid);
        return insert(txn, newTuple);
    }
    public Schema         schema()      { return heap.schema(); }
    public int            pageCount()   { return heap.pageCount(); }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Stamps the pageLSN field (bytes 12–15 of the page header) with the WAL
     * record LSN. This is what enables idempotent redo: if the page's stored
     * pageLSN >= the WAL record's LSN, recovery skips the redo.
     *
     * The pageLSN is stored as an int (truncated from long). With a typical
     * WAL file well under 2 GiB this is sufficient.
     */
    private void stampPageLsn(PageId pageId, int lsn) throws ForgeDBException {
        Page page = bufferPool.readPage(pageId);
        try {
            page.setLsn(lsn);
        } finally {
            bufferPool.unpinPage(pageId);
        }
    }

    /** HEAP_INSERT payload: [pageId(4)][slotIndex(4)][tupleBytes...] */
    private static byte[] buildInsertPayload(RecordId rid, byte[] tupleBytes) {
        ByteBuffer buf = ByteBuffer.allocate(8 + tupleBytes.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(rid.pageId().value());
        buf.putInt(rid.slotIndex());
        buf.put(tupleBytes);
        return buf.array();
    }

    /** HEAP_DELETE payload: [pageId(4)][slotIndex(4)] */
    private static byte[] buildDeletePayload(RecordId rid) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(rid.pageId().value());
        buf.putInt(rid.slotIndex());
        return buf.array();
    }

    // -------------------------------------------------------------------------
    // Static helpers for RecoveryManager
    // -------------------------------------------------------------------------

    /** Decodes a HEAP_INSERT payload: [pageId(4)][slotIndex(4)][tupleBytes...] */
    public static InsertPayload decodeInsertPayload(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int pageIdVal  = buf.getInt();
        int slotIndex  = buf.getInt();
        byte[] tupleBytes = new byte[payload.length - 8];
        buf.get(tupleBytes);
        return new InsertPayload(new PageId(pageIdVal), slotIndex, tupleBytes);
    }

    /** Decodes a HEAP_DELETE payload: [pageId(4)][slotIndex(4)] */
    public static DeletePayload decodeDeletePayload(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        return new DeletePayload(new PageId(buf.getInt()), buf.getInt());
    }

    /** Decoded HEAP_INSERT payload. */
    public record InsertPayload(PageId pageId, int slotIndex, byte[] tupleBytes) {}

    /** Decoded HEAP_DELETE payload. */
    public record DeletePayload(PageId pageId, int slotIndex) {}
}
