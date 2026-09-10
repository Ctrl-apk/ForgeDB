package com.forgedb.wal;

import com.forgedb.common.ForgeDBException;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Write-ahead log manager — the M9 Phase 1 primitive.
 *
 * <h2>Role (Phase 1 only)</h2>
 * A standalone, append-only, checksummed log file with sequential replay.
 * It is a pure primitive: <strong>nothing in the engine writes to or reads
 * from it yet</strong>. Transaction semantics, integration with the
 * BufferPool/Executor, pageLSN enforcement, and recovery all arrive in
 * later phases. This class must remain independently testable.
 *
 * <h2>File format</h2>
 * A WAL file is a sequence of self-delimiting records with no file-level
 * header. Each record:
 *
 * <pre>
 *   offset  size  field
 *   ------  ----  -------------------------------------------------------
 *   +0       4    recordLength (int) — total size of this record in bytes:
 *                 4 + RECORD_HEADER_SIZE + payloadLength (big-endian)
 *   +4      20    RECORD_HEADER_SIZE = 20 bytes:
 *                  +0   4   recordType     (int, stable {@link WALRecordType} id)
 *                  +4   8   txnId          (long, placeholder; 0 = none)
 *                  +12  4   pageId         (int, -1 = not page-specific)
 *                  +16  4   pageLsn        (int, -1 = not applicable)
 *                  [crc32 for the record lives at record end]
 *   +24      N    payload (recordLength - 24 bytes; may be 0)
 *   +24+N    4    CRC32 (int, big-endian) over header + payload
 *                 (bytes [4, 24+N) — the length field itself is excluded)
 * </pre>
 *
 * The length field is excluded from the checksum so a reader can always
 * locate the CRC of a structurally intact record even if header bytes were
 * damaged; any damage to header or payload then fails the CRC check.
 * All integers are big-endian, matching the rest of ForgeDB.
 *
 * <h2>LSN semantics</h2>
 * The LSN of a record is its byte offset in the WAL file. LSNs are therefore
 * naturally ordered and no separate counter is persisted. {@link #append}
 * returns the LSN of the record it just wrote; {@code nextLsn()} returns the
 * LSN the next record will occupy (the current file position).
 *
 * <h2>Durability</h2>
 * Appends go through {@link FileChannel#write(ByteBuffer)} which hands bytes
 * to the OS but does not guarantee device persistence. {@link #sync()} calls
 * {@link FileChannel#force(boolean)}, which is the real durability point.
 * A future commit protocol will be: append redo records → append COMMIT →
 * sync(). Until then, sync() is available but optional.
 *
 * <h2>Corruption and truncation</h2>
 * {@link #readAll()} replays the file from offset 0, validating every record:
 * <ol>
 *   <li>Read the 4-byte length. Fewer than 4 bytes remain, or the length is
 *       structurally impossible (less than the smallest record, larger than
 *       the remaining bytes) → stop; everything before this point is valid.</li>
 *   <li>Read header + payload + CRC. Truncated mid-record (a crash between
 *       append and sync) → stop.</li>
 *   <li>Verify CRC32 over header+payload. Mismatch → stop and report.</li>
 *   <li>Map the type ID through {@link WALRecordType#fromId}. Unknown → stop
 *       and report.</li>
 * </ol>
 * The scan never throws on a damaged tail: it returns the clean prefix and
 * exposes what it found via {@link ScanResult}, so callers can distinguish
 * "clean end of file" from "stopped at corrupt/incomplete record". The WAL
 * file itself is never modified by scanning.
 *
 * <h2>Thread safety</h2>
 * NOT thread-safe. The transaction manager (later phase) will serialise
 * access.
 */
public final class WALManager implements AutoCloseable {

    /** Size of the fixed per-record header (type + txnId + pageId + pageLsn). */
    public static final int RECORD_HEADER_SIZE = 4 + 8 + 4 + 4;

    /** Size of the trailing CRC32 field. */
    public static final int CRC_FIELD_SIZE = 4;

    /**
     * Size of the leading length field + minimal header + CRC: the smallest
     * record that can ever exist (zero-length payload). A recordLength below
     * this is structurally impossible and stops the scan.
     */
    public static final int MIN_RECORD_SIZE = 4 + RECORD_HEADER_SIZE + CRC_FIELD_SIZE;

    /**
     * Hard cap on a single record's size (16 MiB). This is a sanity guard for
     * replay: a garbage length field in a damaged file must not make the
     * reader allocate a huge buffer. It is far above any legitimate record
     * (largest planned payload is a 4 KiB page image).
     */
    public static final int MAX_RECORD_SIZE = 16 * 1024 * 1024;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Path    path;
    private FileChannel   channel;
    private long          writePosition;   // == LSN of the next record
    private boolean       closed;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Opens (or creates) a WAL file at the given path.
     *
     * <p>The write position — and therefore the LSN of the next record —
     * is recovered on open by scanning for the end of the last valid record,
     * so appending to an existing log continues its LSN sequence. (With a
     * clean file this equals the file size; the scan matters for reopening
     * after a crash, which Phase 4 will exercise.)
     *
     * @param path file path; created if absent, appended to if it exists
     * @throws ForgeDBException if the file cannot be opened or positioned
     */
    public WALManager(Path path) throws ForgeDBException {
        if (path == null) throw new NullPointerException("path must not be null");
        this.path = path;

        try {
            this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

            // Recover the write position from the last valid record boundary.
            // For a fresh file this is simply 0.
            this.writePosition = scanEndPosition();

        } catch (IOException e) {
            silentClose();
            throw new ForgeDBException("Cannot open WAL file: " + path, e);
        }
    }

    // -------------------------------------------------------------------------
    // Append
    // -------------------------------------------------------------------------

    /**
     * Appends one record and returns its LSN (the byte offset it was written
     * at). The write reaches the OS but is not device-durable until
     * {@link #sync()}.
     *
     * @param type    record type (must be a valid {@link WALRecordType})
     * @param txnId   transaction id (0 = none)
     * @param pageId  target page, or -1 if not page-specific
     * @param pageLsn pageLSN stamp to carry in the record, or -1
     * @param payload payload bytes (may be null or empty)
     * @return the LSN of the appended record
     * @throws ForgeDBException if the manager is closed, the type is invalid,
     *         the payload is too large, or an I/O error occurs
     */
    public long append(WALRecordType type, long txnId, int pageId, int pageLsn,
                       byte[] payload) throws ForgeDBException {
        ensureOpen();
        if (type == null || WALRecordType.fromId(type.id()) != type) {
            throw new ForgeDBException("Invalid WAL record type: " + type);
        }
        int payloadLength = payload != null ? payload.length : 0;
        int recordLength  = 4 + RECORD_HEADER_SIZE + payloadLength + CRC_FIELD_SIZE;
        if (recordLength > MAX_RECORD_SIZE) {
            throw new ForgeDBException(String.format(
                "WAL record too large: %d bytes (max %d)", recordLength, MAX_RECORD_SIZE));
        }

        long lsn = writePosition;
        ByteBuffer buf = ByteBuffer.allocate(recordLength).order(ByteOrder.BIG_ENDIAN);

        // Length field (excluded from CRC).
        buf.putInt(recordLength);

        // Fixed header.
        buf.putInt(type.id());
        buf.putLong(txnId);
        buf.putInt(pageId);
        buf.putInt(pageLsn);

        // Payload.
        if (payloadLength > 0) {
            buf.put(payload, 0, payloadLength);
        }

        // CRC over header + payload only (bytes [4, recordLength - 4);
        // the length field and the CRC field itself are excluded — identical
        // to the range the scan verifies.
        CRC32 crc = new CRC32();
        buf.flip();
        buf.position(4).limit(recordLength - CRC_FIELD_SIZE);
        crc.update(buf);
        buf.limit(buf.capacity()).position(recordLength - CRC_FIELD_SIZE);
        buf.putInt((int) crc.getValue());

        // Write the whole record at the current end of the log.
        buf.position(0).limit(recordLength);
        try {
            long written = 0;
            while (buf.hasRemaining()) {
                written += channel.write(buf, lsn + written);
            }
        } catch (IOException e) {
            throw new ForgeDBException("WAL append failed at LSN " + lsn, e);
        }

        writePosition = lsn + recordLength;
        return lsn;
    }

    /** Convenience overload: no page association. */
    public long append(WALRecordType type, long txnId, byte[] payload)
            throws ForgeDBException {
        return append(type, txnId, -1, -1, payload);
    }

    // -------------------------------------------------------------------------
    // Durability
    // -------------------------------------------------------------------------

    /**
     * Forces buffered OS writes to durable storage.
     * This is the durability point a future commit protocol will rely on;
     * metadata updates are also forced so newly extended log length persists.
     *
     * @throws ForgeDBException if the force fails
     */
    public void sync() throws ForgeDBException {
        ensureOpen();
        try {
            channel.force(true);
        } catch (IOException e) {
            throw new ForgeDBException("WAL sync failed", e);
        }
    }

    /** Alias for {@link #sync()}, matching WAL terminology. */
    public void force() throws ForgeDBException {
        sync();
    }

    // -------------------------------------------------------------------------
    // Replay / scan
    // -------------------------------------------------------------------------

    /**
     * Result of a sequential scan: the valid records plus where and why the
     * scan stopped.
     */
    public static final class ScanResult {
        private final List<WALRecord> records;
        private final long            validEndOffset;
        private final String          stopReason;   // null = clean EOF

        ScanResult(List<WALRecord> records, long validEndOffset, String stopReason) {
            this.records         = records;
            this.validEndOffset  = validEndOffset;
            this.stopReason      = stopReason;
        }

        /** All records that passed validation, in log order. */
        public List<WALRecord> records()        { return records; }

        /**
         * Byte offset just past the last valid record — the position a
         * recovery pass can treat as the end of the durable log.
         */
        public long validEndOffset()            { return validEndOffset; }

        /**
         * Why the scan stopped before reaching EOF, or null if the whole file
         * was clean (including an empty file).
         */
        public String stopReason()              { return stopReason; }

        /** True if every byte of the file parsed as valid records. */
        public boolean isClean()                { return stopReason == null; }
    }

    /**
     * Replays the log from offset 0, returning every valid record in order.
     * Never modifies the file. Never throws on a damaged tail — see the
     * class javadoc for the exact stopping rules; use {@link ScanResult}
     * accessors to detect what happened.
     *
     * @return the scan result (records, valid end offset, stop reason)
     * @throws ForgeDBException only on I/O errors while reading
     */
    public ScanResult readAll() throws ForgeDBException {
        ensureOpen();
        List<WALRecord> records = new ArrayList<>();
        long pos = 0;
        long fileSize;
        try {
            fileSize = channel.size();
        } catch (IOException e) {
            throw new ForgeDBException("Cannot size WAL file " + path, e);
        }

        while (pos < fileSize) {
            long remaining = fileSize - pos;

            // --- Length field ---
            if (remaining < 4) {
                return new ScanResult(records, pos,
                    "incomplete record at offset " + pos + ": fewer than 4 bytes remain (truncated length field)");
            }
            int recordLength;
            try {
                recordLength = readIntAt(pos);
            } catch (IOException e) {
                throw new ForgeDBException("I/O error reading WAL length at offset " + pos, e);
            }

            // --- Structural validation of the length field ---
            if (recordLength < MIN_RECORD_SIZE) {
                return new ScanResult(records, pos,
                    "corrupt record at offset " + pos + ": impossible length " + recordLength);
            }
            if (recordLength > MAX_RECORD_SIZE || recordLength > remaining) {
                return new ScanResult(records, pos,
                    (recordLength > MAX_RECORD_SIZE
                        ? "corrupt record at offset " + pos + ": length " + recordLength + " exceeds max " + MAX_RECORD_SIZE
                        : "incomplete record at offset " + pos + ": needs " + recordLength
                          + " bytes but only " + remaining + " remain (truncated tail)"));
            }

            // --- Header + payload + CRC ---
            byte[] recordBytes;
            try {
                recordBytes = readFully(pos, recordLength);
            } catch (EOFException eof) {
                return new ScanResult(records, pos,
                    "incomplete record at offset " + pos + ": file ended mid-record");
            } catch (IOException e) {
                throw new ForgeDBException("I/O error reading WAL record at offset " + pos, e);
            }

            ByteBuffer buf = ByteBuffer.wrap(recordBytes).order(ByteOrder.BIG_ENDIAN);
            buf.position(4);   // skip length field
            int    recordType = buf.getInt();
            long   txnId      = buf.getLong();
            int    pageId     = buf.getInt();
            int    pageLsn    = buf.getInt();
            int    payloadLen = recordLength - MIN_RECORD_SIZE;
            byte[] payload    = new byte[payloadLen];
            buf.get(payload);

            // --- CRC over header + payload ---
            CRC32 crc = new CRC32();
            crc.update(recordBytes, 4, recordLength - 4 - CRC_FIELD_SIZE);
            int storedCrc = buf.getInt();   // positioned at the CRC field
            if ((int) crc.getValue() != storedCrc) {
                return new ScanResult(records, pos,
                    "corrupt record at offset " + pos + ": CRC mismatch");
            }

            // --- Type validity ---
            WALRecordType type = WALRecordType.fromId(recordType);
            if (type == null) {
                return new ScanResult(records, pos,
                    "corrupt record at offset " + pos + ": unknown record type " + recordType);
            }

            records.add(new WALRecord(pos, type, txnId, pageId, pageLsn, payload));
            pos += recordLength;
        }

        return new ScanResult(records, pos, null);
    }

    /**
     * Returns the byte offset just past the last valid record — the position
     * new appends continue from. Used internally on open to recover the write
     * position (e.g. after a crash left a torn tail).
     *
     * @throws ForgeDBException on I/O errors
     */
    private long scanEndPosition() throws ForgeDBException {
        return readAll().validEndOffset();
    }

    // -------------------------------------------------------------------------
    // Introspection
    // -------------------------------------------------------------------------

    /** The path of the underlying WAL file. */
    public Path path() { return path; }

    /**
     * The LSN the next appended record will occupy (the recovered end of the
     * valid log). equals the validEndOffset of a scan over the current file.
     */
    public long nextLsn() {
        return writePosition;
    }

    // -------------------------------------------------------------------------
    // Resource management
    // -------------------------------------------------------------------------

    /**
     * Closes the WAL file. A final {@code sync()} is NOT implied — callers
     * that require durability must call {@link #sync()} explicitly first
     * (the OS still holds buffered writes otherwise).
     *
     * @throws ForgeDBException if an I/O error occurs while closing
     */
    @Override
    public void close() throws ForgeDBException {
        if (closed) return;
        closed = true;
        try {
            channel.close();
        } catch (IOException e) {
            throw new ForgeDBException("Error closing WAL file: " + path, e);
        }
    }

    private void ensureOpen() throws ForgeDBException {
        if (closed) {
            throw new ForgeDBException("WALManager is closed: " + path);
        }
    }

    private void silentClose() {
        if (channel != null) {
            try { channel.close(); } catch (IOException ignored) { }
        }
    }

    // -------------------------------------------------------------------------
    // Raw I/O helpers
    // -------------------------------------------------------------------------

    private int readIntAt(long pos) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4);
        readFullyInto(b, pos, 4);
        b.flip();
        return b.getInt();
    }

    private byte[] readFully(long pos, int length) throws IOException {
        byte[] out = new byte[length];
        ByteBuffer b = ByteBuffer.wrap(out);
        readFullyInto(b, pos, length);
        return out;
    }

    /** Reads exactly {@code length} bytes at {@code pos}; EOFException on short read. */
    private void readFullyInto(ByteBuffer b, long pos, int length) throws IOException {
        long bytesRead = 0;
        while (bytesRead < length) {
            int n = channel.read(b, pos + bytesRead);
            if (n < 0) {
                throw new EOFException("short read at WAL offset " + (pos + bytesRead));
            }
            bytesRead += n;
        }
    }
}
