package com.forgedb.storage;

import com.forgedb.common.ForgeDBException;

/**
 * The single component responsible for all disk I/O in ForgeDB.
 *
 * Nothing above this layer touches the filesystem directly. All reads and
 * writes go through this interface, which provides three guarantees:
 *
 *   1. Atomicity of access: each call reads or writes exactly one full page.
 *      There are no partial-page reads or writes visible to callers.
 *
 *   2. Logical addressing: callers use PageId (a logical page number), not
 *      byte offsets. The implementation maps page numbers to file offsets.
 *
 *   3. Testability: the interface can be mocked in unit tests and swapped
 *      for alternative implementations (e.g., an in-memory DiskManager for
 *      fast integration tests) without changing any calling code.
 *
 * Checksum behaviour
 * ------------------
 * DiskManagerImpl computes and writes the checksum before every writePage(),
 * and verifies it after every readPage(). Callers do not need to manage
 * checksums manually.
 *
 * Error handling
 * --------------
 * All methods throw ForgeDBException (checked) for I/O errors, invalid page
 * numbers, and checksum failures. Callers must handle or propagate these.
 */
public interface DiskManager extends AutoCloseable {

    /**
     * Reads the page identified by {@code pageId} from disk into a new Page
     * object. The returned page has its dirty flag cleared.
     *
     * @param pageId the logical address of the page to read
     * @return the page with header and payload populated from disk
     * @throws ForgeDBException if the page ID is out of range, an I/O error
     *         occurs, or the checksum does not match
     */
    Page readPage(PageId pageId) throws ForgeDBException;

    /**
     * Writes the given {@code page} to disk at the position identified by
     * {@code pageId}. Updates the checksum in the page header before writing,
     * then clears the page's dirty flag on success.
     *
     * The page's embedded page-ID header field is updated to match
     * {@code pageId} before the write.
     *
     * @param pageId the logical address to write to
     * @param page   the page whose contents should be persisted
     * @throws ForgeDBException if the page ID is out of range or an I/O error occurs
     */
    void writePage(PageId pageId, Page page) throws ForgeDBException;

    /**
     * Allocates a new page at the end of the database file and returns its ID.
     * The new page is initialised with type DATA and a zeroed payload.
     * The database header (page 0) is updated to reflect the new page count.
     *
     * @return the PageId of the newly allocated page
     * @throws ForgeDBException if an I/O error occurs
     */
    PageId allocatePage() throws ForgeDBException;

    /**
     * Returns the total number of pages currently in the database file,
     * including the header page (page 0).
     */
    int getPageCount();

    /**
     * Flushes any OS-level write buffers and closes the underlying file.
     * After this call the DiskManager must not be used.
     *
     * Declared via AutoCloseable so it can be used in try-with-resources.
     *
     * @throws ForgeDBException if an I/O error occurs during flush or close
     */
    @Override
    void close() throws ForgeDBException;
}
