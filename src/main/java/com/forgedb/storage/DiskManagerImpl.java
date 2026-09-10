package com.forgedb.storage;

import com.forgedb.common.Constants;
import com.forgedb.common.ForgeDBException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * File-backed implementation of DiskManager.
 *
 * Storage model
 * -------------
 * The entire database lives in a single flat binary file. Pages are laid out
 * sequentially:
 *
 *   [Page 0 — DB header][Page 1 — data][Page 2 — data] ...
 *
 * Page N starts at byte offset N * PAGE_SIZE. This makes random access O(1)
 * with no additional lookup structures.
 *
 * We use {@link RandomAccessFile} opened in "rwd" mode:
 *   r  — read
 *   w  — write
 *   d  — every write is synchronously flushed to the storage device
 *        (equivalent to calling fsync after every write)
 *
 * "rwd" is the safest option: it prevents data loss on a process crash because
 * writes reach the device before the call returns. The cost is higher write
 * latency. In Milestone 8 (WAL) we will switch to buffered writes + explicit
 * fsync at transaction commit, which is the approach used by PostgreSQL and
 * SQLite.
 *
 * Initialisation
 * --------------
 * If the database file does not exist, or exists but is empty, it is
 * initialised: a header page is written at offset 0 containing the magic
 * number, version, and initial page count (1).
 *
 * If the file already exists and is non-empty, the header page is read and
 * validated: the magic number must match and the version must be supported.
 *
 * Concurrency
 * -----------
 * DiskManagerImpl is NOT thread-safe. All concurrent access must be
 * synchronised by the buffer pool (Milestone 3) or transaction manager
 * (Milestone 7).
 */
public class DiskManagerImpl implements DiskManager {

    private final RandomAccessFile file;

    /**
     * Number of pages currently allocated in the file, including page 0.
     * Kept in sync with the value written in the header page.
     */
    private int pageCount;

    // -------------------------------------------------------------------------
    // Construction / open
    // -------------------------------------------------------------------------

    /**
     * Opens an existing database file or creates a new one.
     *
     * @param filePath path to the database file (created if absent)
     * @throws ForgeDBException if the file cannot be opened, or if an existing
     *         file has an invalid header (wrong magic / unsupported version)
     */
    public DiskManagerImpl(String filePath) throws ForgeDBException {
        File dbFile = new File(filePath);
        try {
            // "rwd": read+write, every write synchronously flushed to device
            file = new RandomAccessFile(dbFile, "rwd");
        } catch (IOException e) {
            throw new ForgeDBException("Cannot open database file: " + filePath, e);
        }

        try {
            if (file.length() == 0) {
                // Brand-new file — write the header page
                initNewDatabase();
            } else {
                // Existing file — validate its header
                validateExistingDatabase();
            }
        } catch (IOException e) {
            silentClose();
            throw new ForgeDBException("I/O error during database initialisation", e);
        } catch (ForgeDBException e) {
            // Close the file handle so we don't leak it
            silentClose();
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // DiskManager implementation
    // -------------------------------------------------------------------------

    @Override
    public Page readPage(PageId pageId) throws ForgeDBException {
        validatePageId(pageId);

        byte[] buffer = new byte[Constants.PAGE_SIZE];
        try {
            file.seek(pageId.fileOffset());
            int bytesRead = file.read(buffer);
            if (bytesRead != Constants.PAGE_SIZE) {
                throw new ForgeDBException(String.format(
                    "Short read on page %s: expected %d bytes, got %d",
                    pageId, Constants.PAGE_SIZE, bytesRead));
            }
        } catch (IOException e) {
            throw new ForgeDBException("I/O error reading " + pageId, e);
        }

        Page page = new Page(buffer);
        page.verifyChecksum();  // throws ForgeDBException on corruption
        return page;
    }

    @Override
    public void writePage(PageId pageId, Page page) throws ForgeDBException {
        validatePageId(pageId);

        // Stamp the page-ID into the header so the header is always consistent
        // with the physical location of the page in the file.
        page.setPageId(pageId.value());

        // Recompute the checksum over the current payload before writing.
        page.updateChecksum();

        try {
            file.seek(pageId.fileOffset());
            file.write(page.rawData());
        } catch (IOException e) {
            throw new ForgeDBException("I/O error writing " + pageId, e);
        }

        page.clearDirty();
    }

    @Override
    public PageId allocatePage() throws ForgeDBException {
        // The new page sits at the current end of the file.
        PageId newPageId = new PageId(pageCount);

        // Build a blank DATA page.
        Page newPage = new Page();
        newPage.setPageId(newPageId.value());
        newPage.setPageType(PageType.DATA);
        // LSN defaults to 0 (reserved for Milestone 8)

        // writePage stamps the checksum and flushes.
        // We must increment pageCount BEFORE calling writePage so that
        // validatePageId passes — the page is conceptually allocated now.
        pageCount++;

        writePage(newPageId, newPage);

        // Persist the updated page count in the header page.
        updateHeaderPageCount();

        return newPageId;
    }

    @Override
    public int getPageCount() {
        return pageCount;
    }

    @Override
    public void close() throws ForgeDBException {
        try {
            file.close();
        } catch (IOException e) {
            throw new ForgeDBException("Error closing database file", e);
        }
    }

    // -------------------------------------------------------------------------
    // Initialisation helpers
    // -------------------------------------------------------------------------

    /**
     * Writes the initial header page to a brand-new (empty) database file.
     * After this call the file contains exactly one page (page 0) and
     * {@code pageCount} is set to 1.
     */
    private void initNewDatabase() throws ForgeDBException {
        pageCount = 1; // header page itself counts as page 0

        Page headerPage = buildHeaderPage();
        writePage(new PageId(Constants.HEADER_PAGE_ID), headerPage);
    }

    /**
     * Reads page 0 from an existing file and validates it.
     * Populates {@code pageCount} from the stored value.
     *
     * @throws ForgeDBException if the magic number or version is wrong,
     *         or if the checksum fails
     */
    private void validateExistingDatabase() throws ForgeDBException {
        // Temporarily set pageCount high enough so readPage doesn't reject id 0.
        pageCount = Integer.MAX_VALUE;

        Page headerPage = readPage(new PageId(Constants.HEADER_PAGE_ID));

        int magic = headerPage.getInt(Constants.DB_HEADER_OFFSET_MAGIC);
        if (magic != Constants.MAGIC_NUMBER) {
            throw new ForgeDBException(String.format(
                "Not a ForgeDB file: bad magic number 0x%08X (expected 0x%08X)",
                magic, Constants.MAGIC_NUMBER));
        }

        int version = headerPage.getInt(Constants.DB_HEADER_OFFSET_VERSION);
        if (version != Constants.DB_VERSION) {
            throw new ForgeDBException(String.format(
                "Unsupported database version %d (this build supports version %d)",
                version, Constants.DB_VERSION));
        }

        pageCount = headerPage.getInt(Constants.DB_HEADER_OFFSET_PAGE_COUNT);
        if (pageCount < 1) {
            throw new ForgeDBException("Corrupt header: page count is " + pageCount);
        }
    }

    /**
     * Constructs the header page in memory without writing it to disk.
     * The caller must call writePage() to persist it.
     */
    private Page buildHeaderPage() {
        Page page = new Page();
        page.setPageType(PageType.HEADER);
        page.putInt(Constants.DB_HEADER_OFFSET_MAGIC,      Constants.MAGIC_NUMBER);
        page.putInt(Constants.DB_HEADER_OFFSET_VERSION,    Constants.DB_VERSION);
        page.putInt(Constants.DB_HEADER_OFFSET_PAGE_COUNT, pageCount);
        return page;
    }

    /**
     * Re-reads the current header page, updates the page-count field, and
     * writes it back. Called after every {@link #allocatePage()} so the header
     * always reflects the true page count even after a crash.
     */
    private void updateHeaderPageCount() throws ForgeDBException {
        PageId headerId = new PageId(Constants.HEADER_PAGE_ID);
        Page headerPage = readPage(headerId);
        headerPage.putInt(Constants.DB_HEADER_OFFSET_PAGE_COUNT, pageCount);
        writePage(headerId, headerPage);
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures the given PageId refers to a page that actually exists in the
     * file. Page 0 is always valid (it is the header page).
     *
     * @throws ForgeDBException if the page ID is beyond the current page count
     */
    private void validatePageId(PageId pageId) throws ForgeDBException {
        if (pageId.value() >= pageCount) {
            throw new ForgeDBException(String.format(
                "Page ID %d is out of range (page count is %d)",
                pageId.value(), pageCount));
        }
    }

    /** Closes the file handle without throwing — used during error recovery in the constructor. */
    private void silentClose() {
        try { file.close(); } catch (IOException ignored) { }
    }
}
