package com.forgedb.storage;

import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;
import com.forgedb.common.Constants;
import com.forgedb.common.ForgeDBException;

import java.util.ArrayList;
import java.util.List;

/**
 * A table stored as a sequence of DATA pages in a database file.
 *
 * HeapFile manages all the pages that belong to a single logical table.
 * It uses the DiskManager for I/O, DataPage for page-level record layout,
 * and TupleSerializer for converting Tuples to/from bytes.
 *
 * There is no buffer pool yet (Milestone 3). Every insert/read/delete/scan
 * goes to disk through DiskManager. This is correct but not optimised for
 * performance — the buffer pool will cache pages in memory.
 *
 * Page tracking
 * -------------
 * HeapFile remembers which DiskManager pages belong to it via a simple
 * in-memory list of PageIds populated at construction time (for an existing
 * heap) or grown via allocatePage() (for new pages). This list is NOT
 * persisted separately — it is rebuilt by scanning the DiskManager page
 * count on open. This is safe because in Milestone 2 there is only one table
 * per database file. A catalog table will be added in a later milestone to
 * track multiple tables.
 *
 * Insert strategy
 * ---------------
 * Linear scan through known pages looking for one where canFit() returns
 * true. If no existing page has room, allocate a new one. This is O(pages)
 * but simple and correct. The buffer pool in Milestone 3 will make it fast.
 *
 * Deleted records
 * ---------------
 * delete() marks the slot as deleted via DataPage.deleteRecord(). The slot
 * index is never reused; existing RecordIds remain stable.
 *
 * Update
 * ------
 * update() is implemented as delete + insert. The new RecordId may differ
 * from the old one if the serialised size changed. Callers should not hold
 * onto the old RecordId after an update.
 *
 * Thread safety
 * -------------
 * HeapFile is NOT thread-safe. Concurrent access requires external locking
 * (Milestone 7).
 */
public class HeapFile {

    private final DiskManager diskManager;
    private final Schema      schema;

    /**
     * PageIds of all DATA pages belonging to this heap, in insertion order.
     * Page 0 (the DiskManager header) is never included.
     */
    private final List<PageId> dataPages;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Opens or creates a HeapFile backed by the given DiskManager.
     *
     * If the DiskManager already has data pages (pageCount > 1), all pages
     * beyond page 0 are assumed to belong to this heap and are loaded into
     * the dataPages list.
     *
     * @param diskManager the DiskManager that owns the database file
     * @param schema      the schema of the table stored in this heap
     */
    public HeapFile(DiskManager diskManager, Schema schema) {
        this.diskManager = diskManager;
        this.schema      = schema;
        this.dataPages   = new ArrayList<>();

        // Rebuild the in-memory page list from the DiskManager's page count.
        // Page 0 is the DB header; pages 1..N-1 are data pages.
        int pageCount = diskManager.getPageCount();
        for (int i = 1; i < pageCount; i++) {
            dataPages.add(new PageId(i));
        }
    }

    // -------------------------------------------------------------------------
    // Insert
    // -------------------------------------------------------------------------

    /**
     * Inserts a tuple into the heap and returns the RecordId that identifies it.
     *
     * The tuple is serialised to bytes and written into the first data page
     * that has enough free space. If no existing page has room, a new page is
     * allocated.
     *
     * @param tuple tuple to insert; must conform to this heap's schema
     * @return the RecordId (pageId, slotIndex) of the newly inserted record
     * @throws ForgeDBException if serialisation fails or an I/O error occurs
     */
    public RecordId insert(Tuple tuple) throws ForgeDBException {
        byte[] record = TupleSerializer.serialize(schema, tuple);

        // Find a page with enough free space
        for (PageId pageId : dataPages) {
            Page page = diskManager.readPage(pageId);
            DataPage dp = new DataPage(page);
            if (dp.canFit(record.length)) {
                int slotIndex = dp.insertRecord(record);
                diskManager.writePage(pageId, page);
                return new RecordId(pageId, slotIndex);
            }
        }

        // No existing page has room — allocate a new one
        PageId newPageId = allocateNewDataPage();
        Page page = diskManager.readPage(newPageId);
        DataPage dp = new DataPage(page);
        int slotIndex = dp.insertRecord(record);
        diskManager.writePage(newPageId, page);
        return new RecordId(newPageId, slotIndex);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Reads and returns the tuple identified by the given RecordId.
     *
     * @param rid the RecordId returned by a previous {@link #insert}
     * @return the deserialised Tuple
     * @throws ForgeDBException if the record is deleted, the slot is out of
     *         range, or an I/O error occurs
     */
    public Tuple read(RecordId rid) throws ForgeDBException {
        Page page     = diskManager.readPage(rid.pageId());
        DataPage dp   = new DataPage(page);
        byte[] record = dp.readRecord(rid.slotIndex());
        return TupleSerializer.deserialize(schema, record, 0, record.length);
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Marks the tuple identified by {@code rid} as deleted.
     *
     * The space is not reclaimed in Milestone 2. The slot index remains in
     * the page's slot directory with a deleted sentinel, keeping all other
     * RecordIds stable.
     *
     * @param rid the RecordId of the tuple to delete
     * @throws ForgeDBException if the slot is already deleted, out of range,
     *         or an I/O error occurs
     */
    public void delete(RecordId rid) throws ForgeDBException {
        Page page   = diskManager.readPage(rid.pageId());
        DataPage dp = new DataPage(page);
        dp.deleteRecord(rid.slotIndex());
        diskManager.writePage(rid.pageId(), page);
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    /**
     * Updates the tuple at {@code rid} by deleting it and re-inserting the
     * new version.
     *
     * Because the new tuple may be a different size, it may land on a
     * different page. The returned RecordId is the location of the new record.
     * Callers must use the returned RecordId for all subsequent access.
     *
     * @param rid      the RecordId of the tuple to replace
     * @param newTuple the replacement tuple (must conform to this heap's schema)
     * @return the RecordId of the newly inserted replacement record
     * @throws ForgeDBException if the original record is deleted, or an I/O error occurs
     */
    public RecordId update(RecordId rid, Tuple newTuple) throws ForgeDBException {
        delete(rid);
        return insert(newTuple);
    }

    // -------------------------------------------------------------------------
    // Sequential scan
    // -------------------------------------------------------------------------

    /**
     * Returns all non-deleted tuples in insertion order (page 1, slot 0, 1, …;
     * page 2, slot 0, 1, …; etc.).
     *
     * This is a full table scan — O(pages × records-per-page). In later
     * milestones this will be replaced by an iterator-based scan operator that
     * the query engine can pipeline without materialising all results at once.
     *
     * @return list of all live tuples in heap order
     * @throws ForgeDBException if an I/O error occurs while reading pages
     */
    public List<Tuple> scan() throws ForgeDBException {
        List<Tuple> results = new ArrayList<>();

        for (PageId pageId : dataPages) {
            Page page     = diskManager.readPage(pageId);
            DataPage dp   = new DataPage(page);
            int slotCount = dp.getSlotCount();

            for (int slot = 0; slot < slotCount; slot++) {
                if (!dp.isDeleted(slot)) {
                    byte[] record = dp.readRecord(slot);
                    Tuple tuple   = TupleSerializer.deserialize(
                                        schema, record, 0, record.length);
                    results.add(tuple);
                }
            }
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the schema of this heap's table. */
    public Schema schema() {
        return schema;
    }

    /** Returns the number of data pages currently in this heap (excludes the header page). */
    public int pageCount() {
        return dataPages.size();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Allocates a new DATA page via the DiskManager, initialises its slot
     * directory, writes it back, and registers it in the dataPages list.
     *
     * @return the PageId of the new page
     */
    private PageId allocateNewDataPage() throws ForgeDBException {
        PageId newPageId = diskManager.allocatePage();
        Page page = diskManager.readPage(newPageId);
        DataPage.init(page);                       // write slot directory header
        diskManager.writePage(newPageId, page);    // persist the initialised page
        dataPages.add(newPageId);
        return newPageId;
    }
}
