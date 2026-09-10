package com.forgedb.storage;

import com.forgedb.common.Constants;
import com.forgedb.common.ForgeDBException;

/**
 * Interprets the payload of a DATA page as a slotted-page record store.
 *
 * DataPage wraps an existing {@link Page} object and reads/writes its payload
 * using the slotted-page layout described below. It does NOT touch the
 * DiskManager — persistence is the caller's responsibility.
 *
 * Slotted-page layout (all within the 4080-byte page payload)
 * -----------------------------------------------------------
 *
 *   Payload offset 0–7:   Slot directory header (8 bytes)
 *     [0–1] slotCount    (2-byte unsigned short) — total slot entries (including deleted)
 *     [2–3] freeSpacePtr (2-byte unsigned short) — payload offset of first free byte
 *                                                   (just past the last slot entry)
 *     [4–5] endOfRecords (2-byte unsigned short) — payload offset of the lowest record byte
 *                                                   (records pack from the top downward)
 *     [6–7] reserved (2 bytes, zero)
 *
 *   Payload offset 8 + i*4:   Slot entry i (4 bytes each), grows downward
 *     [0–1] recordOffset (2-byte unsigned short) — payload offset where the record starts
 *                                                   0xFFFF = deleted sentinel
 *     [2–3] recordLength (2-byte unsigned short) — byte length of the record
 *
 *   High end of payload (from PAGE_PAYLOAD_SIZE - 1 downward):
 *     Records packed in insertion order (newest nearest the slot directory,
 *     oldest at the high end). Records are never compacted in Milestone 2.
 *
 * Invariant:
 *   endOfRecords >= freeSpacePtr + (slotCount * 4)    [records never overlap directory]
 *   freeSpacePtr = 8 + slotCount * 4                  [directory is contiguous]
 *
 * Verified layout parameters (from Constants.java / Page.java):
 *   PAGE_PAYLOAD_SIZE = 4080
 *   Max recordOffset  = 4079  (fits in unsigned short, max = 65535) ✓
 *   Max freeSpacePtr  = 4080  (fits in unsigned short) ✓
 *   Deleted sentinel  = 0xFFFF = 65535 (> 4079, unambiguous) ✓
 *
 * The Page payload accessors (getInt/putInt/getBytes/putBytes) are used for
 * all reads and writes. Shorts are stored as 2-byte big-endian values using
 * getInt/putInt with masking (the Page API does not expose a getShort, and
 * we want to stay consistent with the rest of the codebase).
 */
public final class DataPage {

    // -------------------------------------------------------------------------
    // Slot directory layout constants (payload-relative)
    // -------------------------------------------------------------------------

    /** Payload offset of the slotCount field in the directory header. */
    static final int DIR_OFFSET_SLOT_COUNT      = 0;

    /** Payload offset of the freeSpacePtr field in the directory header. */
    static final int DIR_OFFSET_FREE_SPACE_PTR  = 2;

    /** Payload offset of the endOfRecords field in the directory header. */
    static final int DIR_OFFSET_END_OF_RECORDS  = 4;

    /** Size of the slot directory header in bytes. */
    static final int DIR_HEADER_SIZE = 8;

    /** Size of each slot entry in bytes. */
    static final int SLOT_ENTRY_SIZE = 4;

    /** Sentinel value stored in recordOffset when a slot is deleted. */
    static final int DELETED_SENTINEL = 0xFFFF;

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Page page;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Wraps an existing page that already has a valid slot directory initialised
     * (either by {@link #init(Page)} or loaded from disk).
     *
     * @param page a DATA page with an initialised slot directory
     */
    public DataPage(Page page) {
        this.page = page;
    }

    /**
     * Initialises the slot directory header on a freshly allocated, zeroed page
     * and returns a DataPage wrapping it.
     *
     * Call this exactly once on a new page. Do NOT call it on a page loaded
     * from disk — that would corrupt the existing directory.
     *
     * Initial state:
     *   slotCount    = 0
     *   freeSpacePtr = DIR_HEADER_SIZE (= 8)   — directory starts right after header
     *   endOfRecords = PAGE_PAYLOAD_SIZE (= 4080) — no records yet; high watermark
     *
     * @param page a blank DATA page (all payload bytes must be 0)
     * @return a DataPage wrapper ready for insert
     */
    public static DataPage init(Page page) {
        DataPage dp = new DataPage(page);
        dp.setSlotCount(0);
        dp.setFreeSpacePtr(DIR_HEADER_SIZE);
        dp.setEndOfRecords(Constants.PAGE_PAYLOAD_SIZE);
        return dp;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the number of bytes currently available for new records
     * (including space for the new slot entry itself).
     */
    public int getFreeSpace() {
        return getEndOfRecords() - getFreeSpacePtr();
    }

    /**
     * Returns true if a record of {@code recordByteSize} bytes can be inserted
     * without overflow. Accounts for the 4-byte slot entry that accompanies it.
     */
    public boolean canFit(int recordByteSize) {
        return getFreeSpace() >= recordByteSize + SLOT_ENTRY_SIZE;
    }

    /**
     * Inserts a record and returns the slot index assigned to it.
     *
     * The record is written to the page payload at the current top of the
     * record region (growing downward). A new slot entry is appended to the
     * slot directory (growing upward). Both freeSpacePtr and endOfRecords are
     * updated.
     *
     * @param record serialised record bytes
     * @return zero-based slot index for this record (use to build a RecordId)
     * @throws ForgeDBException if the record is too large to fit in this page
     */
    public int insertRecord(byte[] record) throws ForgeDBException {
        if (record == null || record.length == 0) {
            throw new ForgeDBException("Cannot insert null or zero-length record");
        }
        if (!canFit(record.length)) {
            throw new ForgeDBException(String.format(
                "Record of %d bytes does not fit in page (free space: %d bytes)",
                record.length, getFreeSpace() - SLOT_ENTRY_SIZE));
        }

        // Place the record just below the current endOfRecords
        int newEndOfRecords = getEndOfRecords() - record.length;
        page.putBytes(newEndOfRecords, record, 0, record.length);

        // Append a new slot entry at freeSpacePtr
        int slotIndex  = getSlotCount();
        int slotOffset = DIR_HEADER_SIZE + slotIndex * SLOT_ENTRY_SIZE;
        setSlotEntry(slotOffset, newEndOfRecords, record.length);

        // Update directory header
        setSlotCount(slotIndex + 1);
        setFreeSpacePtr(getFreeSpacePtr() + SLOT_ENTRY_SIZE);
        setEndOfRecords(newEndOfRecords);

        return slotIndex;
    }

    /**
     * Reads and returns the raw bytes of the record at the given slot index.
     *
     * @param slotIndex zero-based slot index
     * @return a fresh byte array containing the record bytes
     * @throws ForgeDBException if slotIndex is out of range or the slot is deleted
     */
    public byte[] readRecord(int slotIndex) throws ForgeDBException {
        validateSlotIndex(slotIndex);

        int slotOffset = DIR_HEADER_SIZE + slotIndex * SLOT_ENTRY_SIZE;
        int recordOffset = getSlotOffset(slotOffset);
        int recordLength = getSlotLength(slotOffset);

        if (recordOffset == DELETED_SENTINEL) {
            throw new ForgeDBException(
                "Slot " + slotIndex + " has been deleted");
        }

        byte[] record = new byte[recordLength];
        page.getBytes(recordOffset, record, 0, recordLength);
        return record;
    }

    /**
     * Marks the slot at {@code slotIndex} as deleted.
     *
     * The record bytes are NOT zeroed out — they remain in the page until
     * compaction (a future operation). The slot entry's recordOffset is set
     * to {@link #DELETED_SENTINEL} (0xFFFF) so future reads know it is gone.
     * The slot index itself is never reused, preserving RecordId stability.
     *
     * @param slotIndex zero-based slot index
     * @throws ForgeDBException if slotIndex is out of range or already deleted
     */
    public void deleteRecord(int slotIndex) throws ForgeDBException {
        validateSlotIndex(slotIndex);

        int slotOffset   = DIR_HEADER_SIZE + slotIndex * SLOT_ENTRY_SIZE;
        int recordOffset = getSlotOffset(slotOffset);

        if (recordOffset == DELETED_SENTINEL) {
            throw new ForgeDBException(
                "Slot " + slotIndex + " is already deleted");
        }

        // Preserve the length so readers know how much space was used,
        // but mark offset as deleted.
        int recordLength = getSlotLength(slotOffset);
        setSlotEntry(slotOffset, DELETED_SENTINEL, recordLength);
    }

    /** Returns the total number of slot entries (including deleted ones). */
    public int getSlotCount() {
        return getUnsignedShort(DIR_OFFSET_SLOT_COUNT);
    }

    /**
     * Returns true if the slot at the given index has been deleted.
     *
     * @throws ForgeDBException if slotIndex is out of range
     */
    public boolean isDeleted(int slotIndex) throws ForgeDBException {
        validateSlotIndex(slotIndex);
        int slotOffset = DIR_HEADER_SIZE + slotIndex * SLOT_ENTRY_SIZE;
        return getSlotOffset(slotOffset) == DELETED_SENTINEL;
    }

    /** Returns the wrapped {@link Page} object (for passing to DiskManager). */
    public Page getPage() {
        return page;
    }

    // -------------------------------------------------------------------------
    // Directory header read/write helpers
    // -------------------------------------------------------------------------

    /** Payload offset just past the last slot entry (start of free space). */
    int getFreeSpacePtr() {
        return getUnsignedShort(DIR_OFFSET_FREE_SPACE_PTR);
    }

    /** Payload offset of the lowest byte occupied by any record. */
    int getEndOfRecords() {
        return getUnsignedShort(DIR_OFFSET_END_OF_RECORDS);
    }

    private void setSlotCount(int count) {
        putUnsignedShort(DIR_OFFSET_SLOT_COUNT, count);
    }

    private void setFreeSpacePtr(int ptr) {
        putUnsignedShort(DIR_OFFSET_FREE_SPACE_PTR, ptr);
    }

    private void setEndOfRecords(int end) {
        putUnsignedShort(DIR_OFFSET_END_OF_RECORDS, end);
    }

    // -------------------------------------------------------------------------
    // Slot entry read/write helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the recordOffset field from a slot entry at the given payload offset.
     * Returns DELETED_SENTINEL (0xFFFF) if the slot is deleted.
     */
    private int getSlotOffset(int slotPayloadOffset) {
        // The 4-byte slot entry is stored as two packed unsigned shorts.
        // We use getInt to read all 4 bytes, then extract the high 2 bytes.
        int word = page.getInt(slotPayloadOffset);
        return (word >>> 16) & 0xFFFF;  // high 2 bytes = recordOffset
    }

    /** Reads the recordLength field from a slot entry at the given payload offset. */
    private int getSlotLength(int slotPayloadOffset) {
        int word = page.getInt(slotPayloadOffset);
        return word & 0xFFFF;           // low 2 bytes = recordLength
    }

    /** Writes both fields of a slot entry atomically as one 4-byte int. */
    private void setSlotEntry(int slotPayloadOffset, int recordOffset, int recordLength) {
        int word = ((recordOffset & 0xFFFF) << 16) | (recordLength & 0xFFFF);
        page.putInt(slotPayloadOffset, word);
    }

    // -------------------------------------------------------------------------
    // Unsigned short read/write on page payload
    // -------------------------------------------------------------------------

    /**
     * Reads 2 bytes from the payload at {@code payloadOffset} as an unsigned
     * value. We store shorts in the high half of a 4-byte int pair — but the
     * directory header fields are NOT packed this way; they are separate 2-byte
     * quantities at offsets 0, 2, 4, 6.
     *
     * Because Page.getInt reads 4 bytes at a time and the header fields are
     * only 2 bytes each, we read a full 4-byte int that straddles two header
     * fields and extract the relevant half, being careful to align correctly.
     *
     * Simpler approach adopted: pack header fields as short-pairs into ints.
     *
     *   Int at offset 0: high 2 bytes = slotCount,    low 2 bytes = freeSpacePtr
     *   Int at offset 4: high 2 bytes = endOfRecords, low 2 bytes = reserved
     *
     * This lets us use page.getInt/putInt exclusively.
     */
    private int getUnsignedShort(int shortFieldOffset) {
        // shortFieldOffset is 0, 2, 4, or 6 — map to the correct int and half
        int intOffset = shortFieldOffset & ~1; // round down to even int boundary (0 or 4 when stride=2... actually 0,2,4,6 map to ints at 0 and 4)
        // For offsets 0 and 2: read int at 0, pick high or low half
        // For offsets 4 and 6: read int at 4, pick high or low half
        int alignedOffset = (shortFieldOffset / 4) * 4;  // 0→0, 2→0, 4→4, 6→4
        int word = page.getInt(alignedOffset);
        if (shortFieldOffset % 4 == 0) {
            return (word >>> 16) & 0xFFFF;  // high half
        } else {
            return word & 0xFFFF;           // low half
        }
    }

    private void putUnsignedShort(int shortFieldOffset, int value) {
        int alignedOffset = (shortFieldOffset / 4) * 4;
        int word = page.getInt(alignedOffset);
        if (shortFieldOffset % 4 == 0) {
            word = (word & 0x0000FFFF) | ((value & 0xFFFF) << 16);
        } else {
            word = (word & 0xFFFF0000) | (value & 0xFFFF);
        }
        page.putInt(alignedOffset, word);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private void validateSlotIndex(int slotIndex) throws ForgeDBException {
        int count = getSlotCount();
        if (slotIndex < 0 || slotIndex >= count) {
            throw new ForgeDBException(String.format(
                "Slot index %d out of range [0, %d)", slotIndex, count));
        }
    }
}
