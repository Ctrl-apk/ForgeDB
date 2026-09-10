package com.forgedb.storage;

import com.forgedb.common.Constants;
import com.forgedb.common.ForgeDBException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DataPage.
 *
 * DataPage is tested in isolation: no DiskManager or HeapFile involved.
 * We construct Page objects directly and wrap them in DataPage.
 */
class DataPageTest {

    private Page     rawPage;
    private DataPage dp;

    @BeforeEach
    void setUp() {
        rawPage = new Page();
        rawPage.setPageType(PageType.DATA);
        dp = DataPage.init(rawPage);
    }

    // =========================================================================
    // Initialisation
    // =========================================================================

    @Test
    @DisplayName("init() sets slotCount to 0")
    void init_slotCountIsZero() {
        assertEquals(0, dp.getSlotCount());
    }

    @Test
    @DisplayName("init() sets freeSpacePtr to DIR_HEADER_SIZE (8)")
    void init_freeSpacePtrIsDirectoryHeaderSize() {
        assertEquals(DataPage.DIR_HEADER_SIZE, dp.getFreeSpacePtr());
    }

    @Test
    @DisplayName("init() sets endOfRecords to PAGE_PAYLOAD_SIZE (4080)")
    void init_endOfRecordsIsPayloadSize() {
        assertEquals(Constants.PAGE_PAYLOAD_SIZE, dp.getEndOfRecords());
    }

    @Test
    @DisplayName("Fresh page free space = PAGE_PAYLOAD_SIZE - DIR_HEADER_SIZE")
    void init_freeSpaceIsCorrect() {
        int expected = Constants.PAGE_PAYLOAD_SIZE - DataPage.DIR_HEADER_SIZE;
        assertEquals(expected, dp.getFreeSpace());
    }

    // =========================================================================
    // canFit
    // =========================================================================

    @Test
    @DisplayName("canFit returns true for a record that fits")
    void canFit_trueForSmallRecord() {
        assertTrue(dp.canFit(100));
    }

    @Test
    @DisplayName("canFit returns false when record + slot entry exceeds free space")
    void canFit_falseWhenTooLarge() {
        // Free space = 4072. A record of 4073 bytes needs 4073+4=4077 > 4072.
        assertFalse(dp.canFit(4073));
    }

    @Test
    @DisplayName("canFit accounts for the 4-byte slot entry overhead")
    void canFit_accountsForSlotEntry() {
        int freeSpace = dp.getFreeSpace(); // 4072 on a fresh page
        // Record of (freeSpace - 4) bytes exactly fills the page
        assertTrue(dp.canFit(freeSpace - DataPage.SLOT_ENTRY_SIZE));
        // Record of (freeSpace - 3) bytes would need freeSpace+1 bytes → doesn't fit
        assertFalse(dp.canFit(freeSpace - DataPage.SLOT_ENTRY_SIZE + 1));
    }

    // =========================================================================
    // insertRecord
    // =========================================================================

    @Test
    @DisplayName("First insertRecord returns slotIndex 0")
    void insert_firstSlotIsZero() throws ForgeDBException {
        byte[] rec = new byte[]{1, 2, 3, 4};
        int slot = dp.insertRecord(rec);
        assertEquals(0, slot);
    }

    @Test
    @DisplayName("insertRecord returns sequential slot indices")
    void insert_returnsSequentialSlotIndices() throws ForgeDBException {
        assertEquals(0, dp.insertRecord(new byte[]{1}));
        assertEquals(1, dp.insertRecord(new byte[]{2}));
        assertEquals(2, dp.insertRecord(new byte[]{3}));
    }

    @Test
    @DisplayName("insertRecord updates slotCount")
    void insert_updatesSlotCount() throws ForgeDBException {
        dp.insertRecord(new byte[]{10});
        dp.insertRecord(new byte[]{20});
        assertEquals(2, dp.getSlotCount());
    }

    @Test
    @DisplayName("insertRecord updates freeSpacePtr by SLOT_ENTRY_SIZE per insert")
    void insert_updatesFreeSpacePtr() throws ForgeDBException {
        int ptr0 = dp.getFreeSpacePtr(); // 8
        dp.insertRecord(new byte[]{1, 2});
        assertEquals(ptr0 + DataPage.SLOT_ENTRY_SIZE, dp.getFreeSpacePtr());
        dp.insertRecord(new byte[]{3, 4});
        assertEquals(ptr0 + DataPage.SLOT_ENTRY_SIZE * 2, dp.getFreeSpacePtr());
    }

    @Test
    @DisplayName("insertRecord updates endOfRecords")
    void insert_updatesEndOfRecords() throws ForgeDBException {
        int end = dp.getEndOfRecords(); // 4080
        byte[] rec = new byte[10];
        dp.insertRecord(rec);
        assertEquals(end - rec.length, dp.getEndOfRecords());
    }

    @Test
    @DisplayName("insertRecord throws when record is too large")
    void insert_throwsWhenTooLarge() {
        byte[] huge = new byte[5000]; // larger than entire payload
        assertThrows(ForgeDBException.class, () -> dp.insertRecord(huge));
    }

    @Test
    @DisplayName("insertRecord throws on null record")
    void insert_throwsOnNull() {
        assertThrows(ForgeDBException.class, () -> dp.insertRecord(null));
    }

    @Test
    @DisplayName("insertRecord throws on zero-length record")
    void insert_throwsOnZeroLength() {
        assertThrows(ForgeDBException.class, () -> dp.insertRecord(new byte[0]));
    }

    // =========================================================================
    // readRecord
    // =========================================================================

    @Test
    @DisplayName("readRecord returns correct bytes for inserted record")
    void read_correctBytes() throws ForgeDBException {
        byte[] original = {10, 20, 30, 40, 50};
        int slot = dp.insertRecord(original);
        byte[] readBack = dp.readRecord(slot);
        assertArrayEquals(original, readBack);
    }

    @Test
    @DisplayName("Multiple records are read back independently")
    void read_multipleRecordsIndependent() throws ForgeDBException {
        byte[] r0 = {1, 2, 3};
        byte[] r1 = {4, 5, 6, 7};
        byte[] r2 = {8};

        int s0 = dp.insertRecord(r0);
        int s1 = dp.insertRecord(r1);
        int s2 = dp.insertRecord(r2);

        assertArrayEquals(r0, dp.readRecord(s0));
        assertArrayEquals(r1, dp.readRecord(s1));
        assertArrayEquals(r2, dp.readRecord(s2));
    }

    @Test
    @DisplayName("readRecord throws on out-of-range slot index")
    void read_throwsOnOutOfRange() {
        assertThrows(ForgeDBException.class, () -> dp.readRecord(0));
        assertThrows(ForgeDBException.class, () -> dp.readRecord(-1));
    }

    @Test
    @DisplayName("readRecord throws on deleted slot")
    void read_throwsOnDeletedSlot() throws ForgeDBException {
        int slot = dp.insertRecord(new byte[]{99});
        dp.deleteRecord(slot);
        assertThrows(ForgeDBException.class, () -> dp.readRecord(slot));
    }

    // =========================================================================
    // deleteRecord
    // =========================================================================

    @Test
    @DisplayName("deleteRecord marks slot as deleted")
    void delete_marksAsDeleted() throws ForgeDBException {
        int slot = dp.insertRecord(new byte[]{42});
        assertFalse(dp.isDeleted(slot));
        dp.deleteRecord(slot);
        assertTrue(dp.isDeleted(slot));
    }

    @Test
    @DisplayName("deleteRecord does not reduce slotCount")
    void delete_doesNotReduceSlotCount() throws ForgeDBException {
        int slot = dp.insertRecord(new byte[]{1});
        dp.insertRecord(new byte[]{2});
        dp.deleteRecord(slot);
        assertEquals(2, dp.getSlotCount()); // slot count unchanged
    }

    @Test
    @DisplayName("deleteRecord throws on already-deleted slot")
    void delete_throwsOnAlreadyDeleted() throws ForgeDBException {
        int slot = dp.insertRecord(new byte[]{7});
        dp.deleteRecord(slot);
        assertThrows(ForgeDBException.class, () -> dp.deleteRecord(slot));
    }

    @Test
    @DisplayName("deleteRecord throws on out-of-range slot")
    void delete_throwsOnOutOfRange() {
        assertThrows(ForgeDBException.class, () -> dp.deleteRecord(0));
    }

    @Test
    @DisplayName("Non-deleted slots remain readable after a deletion")
    void delete_otherSlotsUnaffected() throws ForgeDBException {
        byte[] r0 = {10, 11};
        byte[] r1 = {20, 21};
        byte[] r2 = {30, 31};

        int s0 = dp.insertRecord(r0);
        int s1 = dp.insertRecord(r1);
        int s2 = dp.insertRecord(r2);

        dp.deleteRecord(s1); // delete middle record

        assertArrayEquals(r0, dp.readRecord(s0));
        assertArrayEquals(r2, dp.readRecord(s2));
    }

    // =========================================================================
    // Slot directory survives Page round-trip
    // =========================================================================

    @Test
    @DisplayName("Slot directory state is preserved across Page wrapping")
    void slotDirectory_survivesPageRewrap() throws ForgeDBException {
        byte[] r0 = {1, 2, 3};
        byte[] r1 = {4, 5};
        dp.insertRecord(r0);
        dp.insertRecord(r1);

        // Wrap the same underlying Page in a new DataPage (simulating a read from disk)
        DataPage dp2 = new DataPage(rawPage);
        assertEquals(2, dp2.getSlotCount());
        assertArrayEquals(r0, dp2.readRecord(0));
        assertArrayEquals(r1, dp2.readRecord(1));
    }

    // =========================================================================
    // Capacity: fill a page completely
    // =========================================================================

    @Test
    @DisplayName("Page fills to capacity without error")
    void capacity_fillPageCompletely() throws ForgeDBException {
        // Insert records of 100 bytes until the page is full
        byte[] record = new byte[100];
        Arrays.fill(record, (byte) 0xAB);

        int inserted = 0;
        while (dp.canFit(record.length)) {
            dp.insertRecord(record);
            inserted++;
        }
        // At least a few records must fit in 4072 bytes of free space
        assertTrue(inserted > 0, "Expected at least one record to fit");
        // Next insert must throw
        assertThrows(ForgeDBException.class, () -> dp.insertRecord(record));
    }

    @Test
    @DisplayName("Single maximum-size record fills page correctly")
    void capacity_singleMaxRecord() throws ForgeDBException {
        // Maximum single record = freeSpace - SLOT_ENTRY_SIZE
        int maxRecordSize = dp.getFreeSpace() - DataPage.SLOT_ENTRY_SIZE;
        byte[] maxRecord = new byte[maxRecordSize];
        Arrays.fill(maxRecord, (byte) 0xFF);

        int slot = dp.insertRecord(maxRecord);
        assertArrayEquals(maxRecord, dp.readRecord(slot));
        // One more byte should not fit
        assertFalse(dp.canFit(1));
    }

    // =========================================================================
    // RecordId
    // =========================================================================

    @Test
    @DisplayName("RecordId equality and hashCode are value-based")
    void recordId_equalityAndHashCode() {
        PageId pid = new PageId(5);
        RecordId a = new RecordId(pid, 3);
        RecordId b = new RecordId(pid, 3);
        RecordId c = new RecordId(pid, 4);

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("RecordId rejects negative slotIndex")
    void recordId_rejectsNegativeSlot() {
        assertThrows(IllegalArgumentException.class,
            () -> new RecordId(new PageId(1), -1));
    }

    @Test
    @DisplayName("RecordId rejects null pageId")
    void recordId_rejectsNullPageId() {
        assertThrows(NullPointerException.class,
            () -> new RecordId(null, 0));
    }
}
