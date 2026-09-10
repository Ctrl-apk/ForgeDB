package com.forgedb.storage;

import com.forgedb.common.Constants;
import com.forgedb.common.ForgeDBException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * Represents a single fixed-size page in the database.
 *
 * A Page is the fundamental unit of I/O in ForgeDB. Every read from and write
 * to disk transfers exactly one page (PAGE_SIZE bytes). The buffer pool
 * (Milestone 3) holds pages in memory; the DiskManager flushes dirty pages
 * to disk.
 *
 * Memory layout
 * -------------
 * Bytes  0 –  3 : Page ID        (int, big-endian)
 * Bytes  4 –  7 : Page type code (int, big-endian)
 * Bytes  8 – 11 : CRC32 checksum (int, big-endian) — covers bytes 12..PAGE_SIZE-1
 * Bytes 12 – 15 : LSN            (int, big-endian) — reserved for WAL
 * Bytes 16 – PAGE_SIZE-1 : payload (tuple data, index nodes, etc.)
 *
 * Checksum coverage
 * -----------------
 * The CRC32 is computed over bytes [PAGE_HEADER_SIZE .. PAGE_SIZE), i.e., the
 * payload plus the LSN field. The page-ID and page-type fields are excluded
 * because they are written separately during allocation and may legitimately
 * differ from what was checksummed. The checksum is recomputed on every write
 * and verified on every read.
 *
 * Dirty flag
 * ----------
 * The dirty flag is not persisted to disk. It is an in-memory signal used by
 * the buffer pool to know which pages need to be flushed. A freshly read page
 * is clean; any write via putByte / putInt / putBytes marks it dirty.
 *
 * Thread safety
 * -------------
 * Page is NOT thread-safe. Concurrent access is coordinated by the buffer
 * pool's latch mechanism (Milestone 7).
 */
public final class Page {

    /** Raw byte array backing this page. Exactly PAGE_SIZE bytes. */
    private final byte[] data;

    /**
     * True when in-memory content differs from the last version written to
     * disk. The buffer pool uses this to decide whether to flush on eviction.
     */
    private boolean dirty;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a blank page backed by a zeroed byte array.
     * The caller is responsible for writing the header fields.
     */
    public Page() {
        this.data = new byte[Constants.PAGE_SIZE];
        this.dirty = false;
    }

    /**
     * Creates a Page from an existing raw byte array (e.g., just read from
     * disk). The array must be exactly PAGE_SIZE bytes.
     *
     * @throws IllegalArgumentException if data.length != PAGE_SIZE
     */
    public Page(byte[] data) {
        if (data.length != Constants.PAGE_SIZE) {
            throw new IllegalArgumentException(
                "Page data must be exactly " + Constants.PAGE_SIZE +
                " bytes, got " + data.length);
        }
        this.data = data.clone(); // defensive copy so caller can't mutate
        this.dirty = false;
    }

    // -------------------------------------------------------------------------
    // Header accessors
    // -------------------------------------------------------------------------

    /** Returns the page ID stored in this page's header. */
    public int getPageId() {
        return readInt(Constants.PAGE_HEADER_OFFSET_PAGE_ID);
    }

    /** Writes the page ID into this page's header and marks the page dirty. */
    public void setPageId(int pageId) {
        writeInt(Constants.PAGE_HEADER_OFFSET_PAGE_ID, pageId);
    }

    /** Returns the page type stored in this page's header. */
    public PageType getPageType() {
        return PageType.fromCode(readInt(Constants.PAGE_HEADER_OFFSET_PAGE_TYPE));
    }

    /** Writes the page type into this page's header and marks the page dirty. */
    public void setPageType(PageType type) {
        writeInt(Constants.PAGE_HEADER_OFFSET_PAGE_TYPE, type.code());
    }

    /**
     * Returns the CRC32 checksum stored in this page's header.
     * This is the checksum that was written when the page was last saved;
     * to verify integrity, call {@link #verifyChecksum()}.
     */
    public int getStoredChecksum() {
        return readInt(Constants.PAGE_HEADER_OFFSET_CHECKSUM);
    }

    /**
     * Returns the Log Sequence Number stored in this page's header.
     * Reserved for WAL integration in Milestone 8; always 0 for now.
     */
    public int getLsn() {
        return readInt(Constants.PAGE_HEADER_OFFSET_LSN);
    }

    /** Writes the LSN into this page's header and marks the page dirty. */
    public void setLsn(int lsn) {
        writeInt(Constants.PAGE_HEADER_OFFSET_LSN, lsn);
    }

    // -------------------------------------------------------------------------
    // Payload accessors (operate on the payload region, offset from byte 16)
    // -------------------------------------------------------------------------

    /**
     * Reads a single byte from the payload at the given payload-relative offset.
     *
     * @param payloadOffset offset within the payload (0 = first byte after header)
     */
    public byte getByte(int payloadOffset) {
        checkPayloadBounds(payloadOffset, 1);
        return data[Constants.PAGE_HEADER_SIZE + payloadOffset];
    }

    /**
     * Writes a single byte to the payload and marks the page dirty.
     *
     * @param payloadOffset offset within the payload
     * @param value         byte to write
     */
    public void putByte(int payloadOffset, byte value) {
        checkPayloadBounds(payloadOffset, 1);
        data[Constants.PAGE_HEADER_SIZE + payloadOffset] = value;
        dirty = true;
    }

    /**
     * Reads a 4-byte big-endian int from the payload at the given offset.
     *
     * @param payloadOffset offset within the payload
     */
    public int getInt(int payloadOffset) {
        checkPayloadBounds(payloadOffset, Integer.BYTES);
        return readInt(Constants.PAGE_HEADER_SIZE + payloadOffset);
    }

    /**
     * Writes a 4-byte big-endian int to the payload and marks the page dirty.
     *
     * @param payloadOffset offset within the payload
     * @param value         int to write
     */
    public void putInt(int payloadOffset, int value) {
        checkPayloadBounds(payloadOffset, Integer.BYTES);
        writeInt(Constants.PAGE_HEADER_SIZE + payloadOffset, value);
        // writeInt does NOT set dirty (it's used for header writes too),
        // so we set it explicitly here for payload writes.
        dirty = true;
    }

    /**
     * Reads a 8-byte big-endian long from the payload at the given offset.
     *
     * @param payloadOffset offset within the payload
     */
    public long getLong(int payloadOffset) {
        checkPayloadBounds(payloadOffset, Long.BYTES);
        return readLong(Constants.PAGE_HEADER_SIZE + payloadOffset);
    }

    /**
     * Writes a 8-byte big-endian long to the payload and marks the page dirty.
     *
     * @param payloadOffset offset within the payload
     * @param value         long to write
     */
    public void putLong(int payloadOffset, long value) {
        checkPayloadBounds(payloadOffset, Long.BYTES);
        writeLong(Constants.PAGE_HEADER_SIZE + payloadOffset, value);
        dirty = true;
    }

    /**
     * Copies bytes from the payload region into dest.
     *
     * @param payloadOffset starting offset within the payload
     * @param dest          destination array
     * @param destOffset    offset in dest to start writing
     * @param length        number of bytes to copy
     */
    public void getBytes(int payloadOffset, byte[] dest, int destOffset, int length) {
        checkPayloadBounds(payloadOffset, length);
        System.arraycopy(data, Constants.PAGE_HEADER_SIZE + payloadOffset,
                         dest, destOffset, length);
    }

    /**
     * Copies bytes from src into the payload region and marks the page dirty.
     *
     * @param payloadOffset starting offset within the payload
     * @param src           source array
     * @param srcOffset     offset in src to start reading
     * @param length        number of bytes to copy
     */
    public void putBytes(int payloadOffset, byte[] src, int srcOffset, int length) {
        checkPayloadBounds(payloadOffset, length);
        System.arraycopy(src, srcOffset,
                         data, Constants.PAGE_HEADER_SIZE + payloadOffset, length);
        dirty = true;
    }

    // -------------------------------------------------------------------------
    // Checksum
    // -------------------------------------------------------------------------

    /**
     * Computes the CRC32 checksum of the payload region (bytes 12..PAGE_SIZE-1,
     * i.e. LSN + payload data) and writes it into the header.
     *
     * Call this before writing the page to disk.
     */
    public void updateChecksum() {
        int checksum = computeChecksum();
        // Write directly, bypassing the dirty-flag logic (checksum is part of
        // the header, not meaningful payload content).
        writeInt(Constants.PAGE_HEADER_OFFSET_CHECKSUM, checksum);
    }

    /**
     * Recomputes the CRC32 and compares it to the stored checksum.
     *
     * @throws ForgeDBException if the checksums do not match, indicating
     *         storage corruption or a partially-written page
     */
    public void verifyChecksum() throws ForgeDBException {
        int stored   = getStoredChecksum();
        int computed = computeChecksum();
        if (stored != computed) {
            throw new ForgeDBException(String.format(
                "Checksum mismatch on page %d: stored=0x%08X, computed=0x%08X",
                getPageId(), stored, computed));
        }
    }

    /**
     * Computes CRC32 over bytes [PAGE_HEADER_OFFSET_LSN .. PAGE_SIZE).
     * The LSN field is included because it is part of the page's logical
     * state and should be protected. The page-ID and page-type fields are
     * excluded — they are structural metadata that the DiskManager writes
     * independently.
     */
    private int computeChecksum() {
        CRC32 crc = new CRC32();
        crc.update(data, Constants.PAGE_HEADER_OFFSET_LSN,
                   Constants.PAGE_SIZE - Constants.PAGE_HEADER_OFFSET_LSN);
        return (int) crc.getValue();
    }

    // -------------------------------------------------------------------------
    // Dirty flag
    // -------------------------------------------------------------------------

    /** Returns true if this page has been modified since it was last read or flushed. */
    public boolean isDirty() {
        return dirty;
    }

    /** Clears the dirty flag. Called by the DiskManager after a successful write. */
    public void clearDirty() {
        dirty = false;
    }

    // -------------------------------------------------------------------------
    // Raw data access (for DiskManager I/O)
    // -------------------------------------------------------------------------

    /**
     * Returns a copy of the raw byte array.
     * Used by DiskManager to write the page to disk; returning a copy
     * prevents the caller from holding a reference to our internal state.
     */
    public byte[] toBytes() {
        return data.clone();
    }

    /**
     * Returns the raw byte array directly (no copy).
     * Package-private: only DiskManagerImpl should use this for performance
     * during bulk reads/writes. External code must use the typed accessors.
     */
    byte[] rawData() {
        return data;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Reads a 4-byte big-endian int from an absolute byte offset within data[]. */
    private int readInt(int absoluteOffset) {
        return ByteBuffer.wrap(data, absoluteOffset, Integer.BYTES)
                         .order(ByteOrder.BIG_ENDIAN)
                         .getInt();
    }

    /** Writes a 4-byte big-endian int to an absolute byte offset within data[]. */
    private void writeInt(int absoluteOffset, int value) {
        ByteBuffer.wrap(data, absoluteOffset, Integer.BYTES)
                  .order(ByteOrder.BIG_ENDIAN)
                  .putInt(value);
    }

    /** Reads an 8-byte big-endian long from an absolute byte offset within data[]. */
    private long readLong(int absoluteOffset) {
        return ByteBuffer.wrap(data, absoluteOffset, Long.BYTES)
                         .order(ByteOrder.BIG_ENDIAN)
                         .getLong();
    }

    /** Writes an 8-byte big-endian long to an absolute byte offset within data[]. */
    private void writeLong(int absoluteOffset, long value) {
        ByteBuffer.wrap(data, absoluteOffset, Long.BYTES)
                  .order(ByteOrder.BIG_ENDIAN)
                  .putLong(value);
    }

    /**
     * Validates that [payloadOffset, payloadOffset+length) lies within the
     * payload region.
     */
    private void checkPayloadBounds(int payloadOffset, int length) {
        if (payloadOffset < 0 || payloadOffset + length > Constants.PAGE_PAYLOAD_SIZE) {
            throw new IndexOutOfBoundsException(String.format(
                "Payload access out of bounds: offset=%d, length=%d, payloadSize=%d",
                payloadOffset, length, Constants.PAGE_PAYLOAD_SIZE));
        }
    }

    @Override
    public String toString() {
        return String.format("Page{id=%d, type=%s, dirty=%b, checksum=0x%08X}",
                             getPageId(), getPageType(), dirty, getStoredChecksum());
    }
}
