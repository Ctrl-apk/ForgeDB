package com.forgedb.wal;

import java.util.Arrays;

/**
 * One decoded WAL record — an immutable value object.
 *
 * <p>The binary layout it corresponds to is documented on
 * {@link WALManager#RECORD_HEADER_SIZE}. Instances handed out by
 * {@link WALManager#readAll()} have already passed CRC32 and structural
 * validation; a record that fails validation is never surfaced (the scan
 * stops safely at it instead).
 *
 * <p>This class carries no behaviour: interpreting payloads into redo
 * actions is Phase 3/4 work. Phase 1 only stores and validates bytes.
 */
public final class WALRecord {

    private final long          lsn;
    private final WALRecordType type;
    private final long          txnId;
    private final int           pageId;
    private final int           pageLsn;
    private final byte[]        payload;

    /**
     * @param lsn     file offset of this record (byte where the length field starts)
     * @param type    validated record type (never null for a replayed record)
     * @param txnId   transaction id placeholder (0 = none assigned yet)
     * @param pageId  target page, or -1 if the record is not page-specific
     * @param pageLsn caller-supplied pageLSN stamp to store, or -1
     * @param payload record payload (cloned defensively)
     */
    WALRecord(long lsn, WALRecordType type, long txnId,
              int pageId, int pageLsn, byte[] payload) {
        this.lsn     = lsn;
        this.type    = type;
        this.txnId   = txnId;
        this.pageId  = pageId;
        this.pageLsn = pageLsn;
        this.payload = payload != null ? payload.clone() : new byte[0];
    }

    /** File offset of this record — its Log Sequence Number. */
    public long lsn()             { return lsn; }

    /** The record's type (what the payload means). */
    public WALRecordType type()   { return type; }

    /** Transaction id placeholder for later phases; 0 = not assigned. */
    public long txnId()           { return txnId; }

    /**
     * Target page id, or -1 for records that are not page-specific
     * (BEGIN/COMMIT/ROLLBACK/CHECKPOINT_*).
     */
    public int pageId()           { return pageId; }

    /**
     * Caller-supplied pageLSN stamp carried in this record (the LSN a page
     * should carry once this redo is applied), or -1 when not applicable.
     */
    public int pageLsn()          { return pageLsn; }

    /** The validated payload bytes (a defensive copy). */
    public byte[] payload()       { return payload.clone(); }

    /** The payload length in bytes. */
    public int payloadLength()    { return payload.length; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WALRecord r)) return false;
        return lsn == r.lsn
            && txnId == r.txnId
            && pageId == r.pageId
            && pageLsn == r.pageLsn
            && type == r.type
            && Arrays.equals(payload, r.payload);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(lsn);
        result = 31 * result + type.hashCode();
        result = 31 * result + Long.hashCode(txnId);
        result = 31 * result + pageId;
        result = 31 * result + pageLsn;
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return String.format(
            "WALRecord{lsn=%d, type=%s, txnId=%d, pageId=%d, pageLsn=%d, payloadLen=%d}",
            lsn, type, txnId, pageId, pageLsn, payload.length);
    }
}
