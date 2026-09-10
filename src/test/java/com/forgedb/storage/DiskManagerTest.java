package com.forgedb.storage;

import com.forgedb.common.Constants;
import com.forgedb.common.ForgeDBException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DiskManagerImpl.
 *
 * Each test gets a fresh temporary directory (JUnit's @TempDir) so tests are
 * completely isolated and no cleanup code is needed.
 *
 * Test categories:
 *   1. Initialisation  — new file creation, header validation
 *   2. Read / Write    — round-trip correctness
 *   3. Allocation      — sequential page IDs, file growth
 *   4. Persistence     — data survives close and reopen
 *   5. Checksums       — corruption is detected on read
 *   6. Error handling  — out-of-range page IDs, bad magic number
 */
class DiskManagerTest {

    @TempDir
    Path tempDir;

    /** Convenience: returns a path string inside the temp directory. */
    private String dbPath(String name) {
        return tempDir.resolve(name).toString();
    }

    // =========================================================================
    // 1. Initialisation
    // =========================================================================

    @Test
    @DisplayName("New database file is created with a valid header page")
    void newFile_createsHeaderPage() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("new.fdb"))) {
            // A brand-new database must have exactly 1 page (the header page).
            assertEquals(1, dm.getPageCount());
        }
    }

    @Test
    @DisplayName("Header page contains correct magic number after creation")
    void newFile_headerHasCorrectMagicNumber() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("magic.fdb"))) {
            Page header = dm.readPage(new PageId(Constants.HEADER_PAGE_ID));
            assertEquals(Constants.MAGIC_NUMBER,
                         header.getInt(Constants.DB_HEADER_OFFSET_MAGIC),
                         "Magic number in header page must match Constants.MAGIC_NUMBER");
        }
    }

    @Test
    @DisplayName("Header page contains correct version after creation")
    void newFile_headerHasCorrectVersion() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("version.fdb"))) {
            Page header = dm.readPage(new PageId(Constants.HEADER_PAGE_ID));
            assertEquals(Constants.DB_VERSION,
                         header.getInt(Constants.DB_HEADER_OFFSET_VERSION),
                         "Version in header page must match Constants.DB_VERSION");
        }
    }

    @Test
    @DisplayName("Header page type is HEADER")
    void newFile_headerPageTypeIsHeader() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("type.fdb"))) {
            Page header = dm.readPage(new PageId(Constants.HEADER_PAGE_ID));
            assertEquals(PageType.HEADER, header.getPageType());
        }
    }

    // =========================================================================
    // 2. Read / Write round-trip
    // =========================================================================

    @Test
    @DisplayName("Written payload bytes are read back correctly")
    void writeAndRead_payloadRoundTrip() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("rw.fdb"))) {
            PageId id = dm.allocatePage();

            // Write a recognisable pattern into the page payload.
            Page page = dm.readPage(id);
            for (int i = 0; i < 256; i++) {
                page.putByte(i, (byte) i);
            }
            dm.writePage(id, page);

            // Read it back and assert equality.
            Page readBack = dm.readPage(id);
            for (int i = 0; i < 256; i++) {
                assertEquals((byte) i, readBack.getByte(i),
                             "Byte mismatch at payload offset " + i);
            }
        }
    }

    @Test
    @DisplayName("Written int values are read back correctly")
    void writeAndRead_intRoundTrip() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("int.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);

            page.putInt(0,  0xDEADBEEF);
            page.putInt(4,  Integer.MAX_VALUE);
            page.putInt(8,  Integer.MIN_VALUE);
            page.putInt(12, 0);
            dm.writePage(id, page);

            Page readBack = dm.readPage(id);
            assertEquals(0xDEADBEEF,       readBack.getInt(0));
            assertEquals(Integer.MAX_VALUE, readBack.getInt(4));
            assertEquals(Integer.MIN_VALUE, readBack.getInt(8));
            assertEquals(0,                readBack.getInt(12));
        }
    }

    @Test
    @DisplayName("Written long values are read back correctly")
    void writeAndRead_longRoundTrip() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("long.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);

            long sentinel = 0xCAFEBABEDEADBEEFL;
            page.putLong(0, sentinel);
            dm.writePage(id, page);

            Page readBack = dm.readPage(id);
            assertEquals(sentinel, readBack.getLong(0));
        }
    }

    @Test
    @DisplayName("Page dirty flag is cleared after writePage")
    void writePage_clearsDirtyFlag() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("dirty.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);
            page.putInt(0, 42);
            assertTrue(page.isDirty(), "Page should be dirty after putInt");

            dm.writePage(id, page);
            assertFalse(page.isDirty(), "Page should be clean after writePage");
        }
    }

    @Test
    @DisplayName("Freshly read page has dirty flag cleared")
    void readPage_freshPageIsClean() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("clean.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);
            assertFalse(page.isDirty(), "Freshly read page should not be dirty");
        }
    }

    // =========================================================================
    // 3. Page allocation
    // =========================================================================

    @Test
    @DisplayName("allocatePage returns sequential page IDs starting at 1")
    void allocatePage_returnsSequentialIds() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("alloc.fdb"))) {
            PageId p1 = dm.allocatePage();
            PageId p2 = dm.allocatePage();
            PageId p3 = dm.allocatePage();

            assertEquals(1, p1.value(), "First allocated page should be 1 (page 0 is header)");
            assertEquals(2, p2.value());
            assertEquals(3, p3.value());
        }
    }

    @Test
    @DisplayName("getPageCount reflects allocated pages")
    void allocatePage_updatesPageCount() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("count.fdb"))) {
            assertEquals(1, dm.getPageCount()); // header only

            dm.allocatePage();
            assertEquals(2, dm.getPageCount());

            dm.allocatePage();
            dm.allocatePage();
            assertEquals(4, dm.getPageCount());
        }
    }

    @Test
    @DisplayName("Newly allocated pages have type DATA")
    void allocatePage_newPageHasDataType() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("datatype.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);
            assertEquals(PageType.DATA, page.getPageType());
        }
    }

    @Test
    @DisplayName("Newly allocated pages have zeroed payload")
    void allocatePage_newPageHasZeroedPayload() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("zero.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);
            for (int i = 0; i < Constants.PAGE_PAYLOAD_SIZE; i++) {
                assertEquals(0, page.getByte(i),
                             "Payload byte at offset " + i + " should be zero");
            }
        }
    }

    // =========================================================================
    // 4. Persistence — data survives close and reopen
    // =========================================================================

    @Test
    @DisplayName("Data written before close is readable after reopen")
    void persistence_datasurvivesReopenGap() throws ForgeDBException {
        String path = dbPath("persist.fdb");
        int sentinel = 0xFEED_CAFE;

        // Write phase
        PageId writtenId;
        try (DiskManager dm = new DiskManagerImpl(path)) {
            writtenId = dm.allocatePage();
            Page page = dm.readPage(writtenId);
            page.putInt(0, sentinel);
            dm.writePage(writtenId, page);
        }

        // Read phase (new DiskManager instance, same file)
        try (DiskManager dm = new DiskManagerImpl(path)) {
            Page page = dm.readPage(writtenId);
            assertEquals(sentinel, page.getInt(0),
                         "Sentinel value must survive close and reopen");
        }
    }

    @Test
    @DisplayName("Page count is restored correctly after reopen")
    void persistence_pageCountSurvivesReopen() throws ForgeDBException {
        String path = dbPath("count_reopen.fdb");

        try (DiskManager dm = new DiskManagerImpl(path)) {
            dm.allocatePage();
            dm.allocatePage();
            dm.allocatePage();
            assertEquals(4, dm.getPageCount());
        }

        try (DiskManager dm = new DiskManagerImpl(path)) {
            assertEquals(4, dm.getPageCount(),
                         "Page count must be persisted in the header page");
        }
    }

    @Test
    @DisplayName("Multiple pages all persist across close/reopen")
    void persistence_multiplePagesSurviveReopen() throws ForgeDBException {
        String path = dbPath("multi_persist.fdb");
        int numPages = 10;
        int[] sentinels = new int[numPages];
        for (int i = 0; i < numPages; i++) sentinels[i] = 0xABCD_0000 + i;

        // Write phase
        try (DiskManager dm = new DiskManagerImpl(path)) {
            for (int i = 0; i < numPages; i++) {
                PageId id = dm.allocatePage();
                Page page = dm.readPage(id);
                page.putInt(0, sentinels[i]);
                dm.writePage(id, page);
            }
        }

        // Read phase
        try (DiskManager dm = new DiskManagerImpl(path)) {
            assertEquals(numPages + 1, dm.getPageCount()); // +1 for header
            for (int i = 0; i < numPages; i++) {
                PageId id = new PageId(i + 1); // pages 1..numPages
                Page page = dm.readPage(id);
                assertEquals(sentinels[i], page.getInt(0),
                             "Sentinel mismatch at page " + id);
            }
        }
    }

    // =========================================================================
    // 5. Checksum validation
    // =========================================================================

    @Test
    @DisplayName("Checksum is stored and verified correctly for written pages")
    void checksum_writtenPagePassesVerification() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("csum_ok.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);
            page.putInt(0, 12345);
            dm.writePage(id, page);

            // Reading back triggers verifyChecksum() inside readPage — should not throw.
            assertDoesNotThrow(() -> dm.readPage(id));
        }
    }

    @Test
    @DisplayName("Corrupted page payload is detected via checksum mismatch")
    void checksum_corruptedPayloadThrowsException() throws Exception {
        String path = dbPath("corrupt.fdb");

        // Write a valid page.
        PageId targetId;
        try (DiskManager dm = new DiskManagerImpl(path)) {
            targetId = dm.allocatePage();
            Page page = dm.readPage(targetId);
            page.putInt(0, 99999);
            dm.writePage(targetId, page);
        }

        // Directly corrupt one byte in the payload region of the file.
        // Payload starts at PAGE_HEADER_SIZE bytes into the page.
        long corruptOffset = targetId.fileOffset()
                           + Constants.PAGE_HEADER_SIZE
                           + 4; // a few bytes into payload
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(corruptOffset);
            byte original = raf.readByte();
            raf.seek(corruptOffset);
            raf.writeByte(original ^ 0xFF); // flip all bits
        }

        // Reopen and attempt to read the corrupted page — must throw.
        try (DiskManager dm = new DiskManagerImpl(path)) {
            ForgeDBException ex = assertThrows(ForgeDBException.class,
                () -> dm.readPage(targetId),
                "Reading a corrupted page should throw ForgeDBException");
            assertTrue(ex.getMessage().toLowerCase().contains("checksum"),
                       "Exception message should mention 'checksum'");
        }
    }

    // =========================================================================
    // 6. Error handling
    // =========================================================================

    @Test
    @DisplayName("Reading a page ID beyond page count throws ForgeDBException")
    void errorHandling_readOutOfRangePageThrows() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("oob.fdb"))) {
            // Only page 0 (header) exists.
            assertThrows(ForgeDBException.class,
                () -> dm.readPage(new PageId(999)),
                "Reading page 999 when only page 0 exists must throw");
        }
    }

    @Test
    @DisplayName("Writing a page ID beyond page count throws ForgeDBException")
    void errorHandling_writeOutOfRangePageThrows() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("oob_write.fdb"))) {
            Page page = new Page();
            assertThrows(ForgeDBException.class,
                () -> dm.writePage(new PageId(999), page),
                "Writing page 999 when only page 0 exists must throw");
        }
    }

    @Test
    @DisplayName("PageId rejects negative values")
    void errorHandling_negativePageIdThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new PageId(-1),
            "Negative page ID must be rejected by PageId constructor");
    }

    @Test
    @DisplayName("Opening a file with wrong magic number throws ForgeDBException")
    void errorHandling_wrongMagicNumberThrows() throws Exception {
        String path = dbPath("bad_magic.fdb");

        // Create a valid database first.
        try (DiskManager dm = new DiskManagerImpl(path)) {
            // just open and close to create the file
        }

        // Overwrite the magic-number bytes in the file payload.
        // Magic number is at: PAGE_HEADER_SIZE + DB_HEADER_OFFSET_MAGIC
        long magicOffset = Constants.PAGE_HEADER_SIZE + Constants.DB_HEADER_OFFSET_MAGIC;
        try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
            raf.seek(magicOffset);
            raf.writeInt(0xDEADBEEF); // definitely not our magic number
        }

        // Reopening must detect the bad magic and throw.
        assertThrows(ForgeDBException.class,
            () -> new DiskManagerImpl(path),
            "Opening a file with wrong magic number must throw ForgeDBException");
    }

    @Test
    @DisplayName("PageId fileOffset is computed correctly")
    void pageId_fileOffsetCalculation() {
        assertEquals(0L,                                  new PageId(0).fileOffset());
        assertEquals((long) Constants.PAGE_SIZE,          new PageId(1).fileOffset());
        assertEquals((long) Constants.PAGE_SIZE * 100,    new PageId(100).fileOffset());
    }

    @Test
    @DisplayName("PageId equality and hashCode are value-based")
    void pageId_equalityAndHashCode() {
        PageId a = new PageId(42);
        PageId b = new PageId(42);
        PageId c = new PageId(43);

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // =========================================================================
    // 7. Page type round-trip
    // =========================================================================

    @Test
    @DisplayName("PageType round-trips through fromCode correctly")
    void pageType_fromCodeRoundTrip() {
        for (PageType type : PageType.values()) {
            assertEquals(type, PageType.fromCode(type.code()));
        }
    }

    @Test
    @DisplayName("PageType.fromCode throws on unknown code")
    void pageType_unknownCodeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> PageType.fromCode(9999));
    }

    @Test
    @DisplayName("Setting page type persists correctly through DiskManager")
    void pageType_persistsThroughDiskManager() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("pagetype.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);
            page.setPageType(PageType.BTREE);
            dm.writePage(id, page);

            Page readBack = dm.readPage(id);
            assertEquals(PageType.BTREE, readBack.getPageType());
        }
    }

    // =========================================================================
    // 8. Payload boundary
    // =========================================================================

    @Test
    @DisplayName("Writing at the last valid payload offset succeeds")
    void payload_writeAtLastByteSucceeds() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("boundary.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);
            int lastOffset = Constants.PAGE_PAYLOAD_SIZE - 1;
            assertDoesNotThrow(() -> page.putByte(lastOffset, (byte) 0xFF));
        }
    }

    @Test
    @DisplayName("Writing one byte past the payload end throws IndexOutOfBoundsException")
    void payload_writeOutOfBoundsThrows() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("oob_payload.fdb"))) {
            PageId id = dm.allocatePage();
            Page page = dm.readPage(id);
            assertThrows(IndexOutOfBoundsException.class,
                () -> page.putByte(Constants.PAGE_PAYLOAD_SIZE, (byte) 0));
        }
    }
}
