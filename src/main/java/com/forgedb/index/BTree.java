package com.forgedb.index;

import com.forgedb.catalog.DataType;
import com.forgedb.common.Constants;
import com.forgedb.common.ForgeDBException;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.Page;
import com.forgedb.storage.PageId;
import com.forgedb.storage.PageType;
import com.forgedb.storage.RecordId;

import java.util.ArrayList;
import java.util.List;

/**
 * Disk-backed B+ Tree index stored on top of the {@link BufferPool}.
 *
 * <h2>Structure</h2>
 * Every node of the tree lives in one database page (4096 bytes, 4080-byte
 * payload). The tree itself does not own the database file — it allocates
 * pages through the BufferPool and stores its root page ID persistently in
 * a dedicated <em>metadata page</em> (the first page allocated for this index).
 *
 * <pre>
 * Metadata page payload (payload offsets):
 *   [0..3]  rootPageId  (int)  — PageId.value() of the current root node
 *   [4..7]  keyType     (int)  — DataType ordinal of the key type
 *   [8..11] order       (int)  — tree order (max keys per leaf node)
 *   [12..15] height     (int)  — current tree height (1 = root is also leaf)
 * </pre>
 *
 * <h2>Node layout (handled by BTreeNode)</h2>
 * Each tree node page payload starts with a 16-byte header:
 * <pre>
 *   [0..3]  nodeType  — 0=INTERNAL, 1=LEAF
 *   [4..7]  keyCount
 *   [8..11] parentId  — -1 if root
 *   [12..15] nextLeaf — -1 if not leaf or last leaf
 * </pre>
 *
 * Leaf entries:  key_bytes | rid.pageId(4) | rid.slot(4)
 * Internal entries: child(4) | key_bytes | child(4) | key_bytes | ... | child(4)
 *
 * <h2>Order and capacity</h2>
 * The tree order {@code ORDER} is the maximum number of keys per leaf node.
 * Internal nodes also store at most ORDER keys (and ORDER+1 child pointers).
 * When a node reaches ORDER+1 keys it splits. The minimum keys per non-root
 * node is ceil(ORDER/2).
 *
 * For INT keys (4 bytes each):
 * <pre>
 *   Leaf:     header(16) + ORDER * (4+8) = 4080 → ORDER = floor(4064/12) = 338
 *   Internal: header(16) + ORDER*(4+4) + 4 = 4080 → ORDER = floor(4060/8) = 507
 * </pre>
 * We use the leaf order (smaller of the two) as the global ORDER, so the
 * same value works for both node types.
 *
 * <h2>Duplicate keys</h2>
 * Duplicate keys are fully supported. Multiple (key, RecordId) pairs with the
 * same key are stored as consecutive entries in leaf nodes. Search and delete
 * handle duplicate ranges correctly.
 *
 * <h2>Pin protocol</h2>
 * Every {@code bufferPool.readPage(id)} increments the pin count. Each helper
 * method in BTree unpins every page it pins before returning, keeping the
 * buffer pool stable. Failure paths also unpin.
 *
 * <h2>Thread safety</h2>
 * BTree is NOT thread-safe. External synchronisation required (Milestone 7).
 */
public class BTree {

    // -------------------------------------------------------------------------
    // Metadata page layout (payload offsets within the metadata page)
    // -------------------------------------------------------------------------

    private static final int META_ROOT_PAGE_ID = 0;   // int
    private static final int META_KEY_TYPE     = 4;   // int (DataType ordinal)
    private static final int META_ORDER        = 8;   // int
    private static final int META_HEIGHT       = 12;  // int

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final BufferPool bufferPool;
    private final DataType   keyType;
    private final int        order;       // max keys per node
    private final int        minKeys;     // ceil(order / 2) — minimum for non-root nodes
    private final PageId     metaPageId;  // page holding rootPageId and tree metadata

    private int rootPageId;   // cached; authoritative copy is in metaPageId
    private int height;       // cached tree height

    // -------------------------------------------------------------------------
    // Construction: create a new tree
    // -------------------------------------------------------------------------

    /**
     * Creates a new, empty B+ tree index.
     *
     * Allocates a metadata page and a root leaf page. The root is initially
     * an empty leaf node. Both pages are persisted immediately.
     *
     * @param bufferPool the buffer pool to use for all I/O
     * @param keyType    the type of keys this tree indexes
     * @return a new empty BTree
     * @throws ForgeDBException if page allocation or I/O fails
     */
    public static BTree create(BufferPool bufferPool, DataType keyType)
            throws ForgeDBException {
        int order = computeOrder(keyType);

        // Allocate and initialise the metadata page
        PageId metaId   = bufferPool.allocatePage();
        Page   metaPage = bufferPool.readPage(metaId);
        metaPage.setPageType(PageType.BTREE);

        // Allocate and initialise the root leaf page
        PageId rootId   = bufferPool.allocatePage();
        Page   rootPage = bufferPool.readPage(rootId);
        rootPage.setPageType(PageType.BTREE);
        BTreeNode.init(rootPage, keyType, BTreeNode.NODE_LEAF, BTreeNode.NO_PAGE);

        // Write metadata
        metaPage.putInt(META_ROOT_PAGE_ID, rootId.value());
        metaPage.putInt(META_KEY_TYPE,     keyType.ordinal());
        metaPage.putInt(META_ORDER,        order);
        metaPage.putInt(META_HEIGHT,       1);

        // Flush both pages
        bufferPool.writePage(rootId,  rootPage);
        bufferPool.writePage(metaId,  metaPage);
        bufferPool.unpin(rootId);
        bufferPool.unpin(metaId);

        return new BTree(bufferPool, keyType, order, metaId,
                         rootId.value(), 1);
    }

    /**
     * Opens an existing B+ tree from a previously allocated metadata page.
     *
     * @param bufferPool the buffer pool backing the database
     * @param metaPageId the page ID of the tree's metadata page
     * @return the loaded BTree
     * @throws ForgeDBException if the metadata page cannot be read or is corrupt
     */
    public static BTree open(BufferPool bufferPool, PageId metaPageId)
            throws ForgeDBException {
        Page metaPage = bufferPool.readPage(metaPageId);
        int rootPageId = metaPage.getInt(META_ROOT_PAGE_ID);
        int typeOrdinal = metaPage.getInt(META_KEY_TYPE);
        int order      = metaPage.getInt(META_ORDER);
        int height     = metaPage.getInt(META_HEIGHT);
        bufferPool.unpin(metaPageId);

        DataType keyType = DataType.values()[typeOrdinal];
        return new BTree(bufferPool, keyType, order, metaPageId, rootPageId, height);
    }

    private BTree(BufferPool bufferPool, DataType keyType, int order,
                  PageId metaPageId, int rootPageId, int height) {
        this.bufferPool  = bufferPool;
        this.keyType     = keyType;
        this.order       = order;
        this.minKeys     = (order + 1) / 2;
        this.metaPageId  = metaPageId;
        this.rootPageId  = rootPageId;
        this.height      = height;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns the PageId of the metadata page (needed to reopen the tree). */
    public PageId metaPageId() { return metaPageId; }

    /** Returns the key type of this index. */
    public DataType keyType()  { return keyType; }

    /** Returns the tree order (max keys per node). */
    public int order()         { return order; }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /**
     * Returns all RecordIds associated with {@code key}.
     * Returns an empty list if the key is not found.
     * Supports duplicate keys: all matches are returned in insertion order.
     */
    public List<RecordId> search(BTreeKey key) throws ForgeDBException {
        List<RecordId> results = new ArrayList<>();
        PageId leafId = findLeaf(key);

        Page      leafPage = bufferPool.readPage(leafId);
        BTreeNode leaf     = new BTreeNode(leafPage, keyType);

        int count = leaf.getKeyCount();
        // Find first entry >= key
        int start = leaf.findFirstGE(key);
        // Collect all matching entries (may span multiple leaf pages)
        // First: collect from this leaf
        for (int i = start; i < count; i++) {
            BTreeKey k = leaf.leafGetKey(i);
            if (k.compareTo(key) != 0) break;
            results.add(leaf.leafGetRid(i));
        }

        // Follow next-leaf chain if the last key matched
        int nextLeafId = leaf.getNextLeaf();
        bufferPool.unpin(leafId);

        while (nextLeafId != BTreeNode.NO_PAGE && !results.isEmpty()) {
            // Check if last key on current leaf matched — we already added those;
            // now check next leaf for continued duplicates
            PageId    nextId   = new PageId(nextLeafId);
            Page      nextPage = bufferPool.readPage(nextId);
            BTreeNode nextLeaf = new BTreeNode(nextPage, keyType);
            int       nc       = nextLeaf.getKeyCount();
            boolean   found    = false;
            for (int i = 0; i < nc; i++) {
                BTreeKey k = nextLeaf.leafGetKey(i);
                int cmp = k.compareTo(key);
                if (cmp == 0) { results.add(nextLeaf.leafGetRid(i)); found = true; }
                else if (cmp > 0) break;
            }
            nextLeafId = nextLeaf.getNextLeaf();
            bufferPool.unpin(nextId);
            if (!found) break;
        }

        return results;
    }

    // -------------------------------------------------------------------------
    // Range search
    // -------------------------------------------------------------------------

    /**
     * Returns all (key, RecordId) entries where {@code low <= key <= high}.
     * Pass null for {@code low} to scan from the smallest key.
     * Pass null for {@code high} to scan to the largest key.
     *
     * Uses the leaf linked-list for efficient traversal.
     */
    public List<RangeEntry> rangeSearch(BTreeKey low, BTreeKey high)
            throws ForgeDBException {
        List<RangeEntry> results = new ArrayList<>();

        // Find the leftmost leaf
        PageId startLeafId;
        if (low == null) {
            startLeafId = leftmostLeaf();
        } else {
            startLeafId = findLeaf(low);
        }

        PageId curId = startLeafId;
        while (curId != null) {
            Page      curPage = bufferPool.readPage(curId);
            BTreeNode cur     = new BTreeNode(curPage, keyType);
            int       count   = cur.getKeyCount();
            boolean   done    = false;

            for (int i = 0; i < count; i++) {
                BTreeKey k = cur.leafGetKey(i);
                // Skip keys below low
                if (low != null && k.compareTo(low) < 0) continue;
                // Stop at keys above high
                if (high != null && k.compareTo(high) > 0) { done = true; break; }
                results.add(new RangeEntry(k, cur.leafGetRid(i)));
            }

            int nextId = cur.getNextLeaf();
            bufferPool.unpin(curId);

            if (done || nextId == BTreeNode.NO_PAGE) break;
            curId = new PageId(nextId);
        }

        return results;
    }

    /** A (key, RecordId) pair returned by range search. */
    public static final class RangeEntry {
        public final BTreeKey key;
        public final RecordId rid;
        RangeEntry(BTreeKey key, RecordId rid) { this.key = key; this.rid = rid; }
    }

    // -------------------------------------------------------------------------
    // Insert
    // -------------------------------------------------------------------------

    /**
     * Inserts a (key, RecordId) pair into the index.
     * Duplicate keys are supported: each (key, rid) pair is stored separately.
     */
    public void insert(BTreeKey key, RecordId rid) throws ForgeDBException {
        PageId leafId = findLeaf(key);
        insertIntoLeaf(leafId, key, rid);
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Deletes the entry with the given key and RecordId from the index.
     * If the key has duplicates, only the entry with the matching RecordId
     * is removed.
     *
     * @throws ForgeDBException if the (key, rid) pair is not found
     */
    public void delete(BTreeKey key, RecordId rid) throws ForgeDBException {
        PageId    leafId   = findLeaf(key);
        Page      leafPage = bufferPool.readPage(leafId);
        BTreeNode leaf     = new BTreeNode(leafPage, keyType);

        // Find the exact entry to delete
        int count    = leaf.getKeyCount();
        int delIndex = -1;
        int start    = leaf.findFirstGE(key);
        for (int i = start; i < count; i++) {
            BTreeKey k = leaf.leafGetKey(i);
            if (k.compareTo(key) != 0) break;
            RecordId r = leaf.leafGetRid(i);
            if (r.equals(rid)) { delIndex = i; break; }
        }

        if (delIndex == -1) {
            bufferPool.unpin(leafId);
            throw new ForgeDBException(
                "Delete: entry (" + key + ", " + rid + ") not found in index");
        }

        leaf.leafRemoveAt(delIndex);
        bufferPool.writePage(leafId, leafPage);
        bufferPool.unpin(leafId);

        // Fix under-full leaf
        fixLeafUnderflow(leafId, leafPage);
    }

    // =========================================================================
    // Internal: tree traversal
    // =========================================================================

    /** Traverses the tree to find the leaf node where {@code key} belongs. */
    private PageId findLeaf(BTreeKey key) throws ForgeDBException {
        PageId curId = new PageId(rootPageId);

        while (true) {
            Page      curPage = bufferPool.readPage(curId);
            BTreeNode cur     = new BTreeNode(curPage, keyType);

            if (cur.isLeaf()) {
                bufferPool.unpin(curId);
                return curId;
            }

            // Follow appropriate child
            int childIdx = cur.internalFindChildIndex(key);
            PageId childId = cur.internalGetChild(childIdx);
            bufferPool.unpin(curId);
            curId = childId;
        }
    }

    /** Returns the PageId of the leftmost leaf in the tree. */
    private PageId leftmostLeaf() throws ForgeDBException {
        PageId curId = new PageId(rootPageId);
        while (true) {
            Page      curPage = bufferPool.readPage(curId);
            BTreeNode cur     = new BTreeNode(curPage, keyType);
            if (cur.isLeaf()) {
                bufferPool.unpin(curId);
                return curId;
            }
            PageId childId = cur.internalGetChild(0);
            bufferPool.unpin(curId);
            curId = childId;
        }
    }

    // =========================================================================
    // Internal: insertion
    // =========================================================================

    private void insertIntoLeaf(PageId leafId, BTreeKey key, RecordId rid)
            throws ForgeDBException {
        Page      leafPage = bufferPool.readPage(leafId);
        BTreeNode leaf     = new BTreeNode(leafPage, keyType);

        // Find insertion point (maintain sorted order; allow duplicates)
        int insertIdx = leaf.findFirstGE(key);
        // For duplicate keys, insert after existing same keys to preserve insertion order
        int count = leaf.getKeyCount();
        while (insertIdx < count && leaf.leafGetKey(insertIdx).compareTo(key) == 0) {
            insertIdx++;
        }

        leaf.leafInsertAt(insertIdx, key, rid);

        if (leaf.getKeyCount() <= order) {
            // No split needed
            bufferPool.writePage(leafId, leafPage);
            bufferPool.unpin(leafId);
            return;
        }

        // Split the leaf
        splitLeaf(leafId, leafPage, leaf);
    }

    /**
     * Splits a leaf that has exceeded its capacity (keyCount == order + 1).
     *
     * The left leaf keeps the first ceil((order+1)/2) entries.
     * The right leaf gets the remaining entries.
     * The first key of the right leaf is pushed up to the parent.
     */
    private void splitLeaf(PageId leftId, Page leftPage, BTreeNode leftLeaf)
            throws ForgeDBException {
        int totalKeys = leftLeaf.getKeyCount();  // = order + 1
        int leftCount = (totalKeys + 1) / 2;     // ceil((order+1)/2)
        int rightCount = totalKeys - leftCount;

        // Allocate new right leaf
        PageId rightId   = bufferPool.allocatePage();
        Page   rightPage = bufferPool.readPage(rightId);
        rightPage.setPageType(PageType.BTREE);
        BTreeNode rightLeaf = BTreeNode.init(rightPage, keyType,
                                             BTreeNode.NODE_LEAF,
                                             leftLeaf.getParentId());

        // Copy right half of leftLeaf into rightLeaf
        for (int i = 0; i < rightCount; i++) {
            BTreeKey srcKey = leftLeaf.leafGetKey(leftCount + i);
            RecordId srcRid = leftLeaf.leafGetRid(leftCount + i);
            rightLeaf.leafInsertAt(i, srcKey, srcRid);
        }

        // Truncate leftLeaf to leftCount entries
        for (int i = totalKeys - 1; i >= leftCount; i--) {
            leftLeaf.leafRemoveAt(i);
        }

        // Link: rightLeaf.next = leftLeaf.next; leftLeaf.next = rightLeaf
        rightLeaf.setNextLeaf(leftLeaf.getNextLeaf());
        leftLeaf.setNextLeaf(rightId.value());

        // The key that goes up is the first key of the right leaf
        BTreeKey pushUpKey = rightLeaf.leafGetKey(0);

        // Persist both leaves
        bufferPool.writePage(leftId,  leftPage);
        bufferPool.writePage(rightId, rightPage);
        bufferPool.unpin(leftId);
        bufferPool.unpin(rightId);

        // Push the key up to the parent
        int parentId = leftLeaf.getParentId();
        if (parentId == BTreeNode.NO_PAGE) {
            // Left was the root — create a new root
            createNewRoot(pushUpKey, leftId, rightId);
        } else {
            insertIntoInternal(new PageId(parentId), pushUpKey, rightId, leftId);
        }
    }

    /**
     * Inserts a key and new right child into an internal node after a split.
     *
     * @param parentId   the internal node to insert into
     * @param key        the key to insert
     * @param rightChild the new right child page
     * @param leftChild  the left child (used to identify correct position)
     */
    private void insertIntoInternal(PageId parentId, BTreeKey key,
                                     PageId rightChild, PageId leftChild)
            throws ForgeDBException {
        Page      parentPage = bufferPool.readPage(parentId);
        BTreeNode parent     = new BTreeNode(parentPage, keyType);

        // Find position: the slot right after leftChild's position
        int count    = parent.getKeyCount();
        int insertAt = 0;
        for (int i = 0; i <= count; i++) {
            if (parent.internalGetChild(i).equals(leftChild)) {
                insertAt = i;
                break;
            }
        }

        parent.internalInsertAt(insertAt, key, rightChild);

        if (parent.getKeyCount() <= order) {
            bufferPool.writePage(parentId, parentPage);
            bufferPool.unpin(parentId);
            // Update the right child's parentId
            updateParentId(rightChild, parentId.value());
            return;
        }

        // Parent also overflowed — split it
        splitInternal(parentId, parentPage, parent, rightChild);
    }

    /**
     * Splits an internal node that has exceeded capacity.
     *
     * The middle key is pushed up to the parent. The left node keeps keys
     * [0..mid-1], the right node gets keys [mid+1..order].
     */
    private void splitInternal(PageId leftId, Page leftPage, BTreeNode leftNode,
                                 PageId lastInsertedChild)
            throws ForgeDBException {
        int totalKeys = leftNode.getKeyCount();  // = order + 1
        int mid       = totalKeys / 2;           // index of key that goes up

        BTreeKey pushUpKey = leftNode.internalGetKey(mid);

        // Allocate new right internal node
        PageId rightId   = bufferPool.allocatePage();
        Page   rightPage = bufferPool.readPage(rightId);
        rightPage.setPageType(PageType.BTREE);
        BTreeNode rightNode = BTreeNode.init(rightPage, keyType,
                                             BTreeNode.NODE_INTERNAL,
                                             leftNode.getParentId());

        // Right node gets keys [mid+1..totalKeys-1] and children [mid+1..totalKeys]
        int rightKeyCount = totalKeys - mid - 1;
        // First child of rightNode = child[mid+1] of leftNode
        PageId firstRightChild = leftNode.internalGetChild(mid + 1);
        rightNode.internalSetChild(0, firstRightChild);
        for (int i = 0; i < rightKeyCount; i++) {
            BTreeKey k = leftNode.internalGetKey(mid + 1 + i);
            PageId   c = leftNode.internalGetChild(mid + 2 + i);
            rightNode.internalInsertAt(i, k, c);
        }

        // Truncate leftNode: keep keys [0..mid-1] and children [0..mid]
        int newLeftCount = mid;
        // Remove keys and children from the right end
        for (int i = totalKeys - 1; i >= mid; i--) {
            leftNode.internalRemoveAt(i);
        }

        // Update parent pointers for all children moved to rightNode
        int rightKC = rightNode.getKeyCount();
        for (int i = 0; i <= rightKC; i++) {
            updateParentId(rightNode.internalGetChild(i), rightId.value());
        }

        bufferPool.writePage(leftId,  leftPage);
        bufferPool.writePage(rightId, rightPage);
        bufferPool.unpin(leftId);
        bufferPool.unpin(rightId);

        int parentId = leftNode.getParentId();
        if (parentId == BTreeNode.NO_PAGE) {
            createNewRoot(pushUpKey, leftId, rightId);
        } else {
            insertIntoInternal(new PageId(parentId), pushUpKey, rightId, leftId);
        }
    }

    /**
     * Creates a new root containing a single key and two child pointers.
     * Updates the metadata page.
     */
    private void createNewRoot(BTreeKey key, PageId leftChild, PageId rightChild)
            throws ForgeDBException {
        PageId newRootId   = bufferPool.allocatePage();
        Page   newRootPage = bufferPool.readPage(newRootId);
        newRootPage.setPageType(PageType.BTREE);
        BTreeNode newRoot = BTreeNode.init(newRootPage, keyType,
                                           BTreeNode.NODE_INTERNAL, BTreeNode.NO_PAGE);
        newRoot.internalSetChild(0, leftChild);
        newRoot.internalInsertAt(0, key, rightChild);

        bufferPool.writePage(newRootId, newRootPage);
        bufferPool.unpin(newRootId);

        // Update parent pointers on the two children
        updateParentId(leftChild,  newRootId.value());
        updateParentId(rightChild, newRootId.value());

        // Update metadata
        rootPageId = newRootId.value();
        height++;
        persistMetadata();
    }

    // =========================================================================
    // Internal: deletion and rebalancing
    // =========================================================================

    /**
     * After a leaf deletion, fixes any underflow (keyCount < minKeys).
     * Tries to borrow from a sibling; if that fails, merges.
     */
    private void fixLeafUnderflow(PageId leafId, Page leafPage)
            throws ForgeDBException {
        Page      page = bufferPool.readPage(leafId);
        BTreeNode node = new BTreeNode(page, keyType);
        int       kc   = node.getKeyCount();
        int       pid  = node.getParentId();
        bufferPool.unpin(leafId);

        // Root can be under minKeys (it may even be empty)
        if (pid == BTreeNode.NO_PAGE) return;
        // No underflow
        if (kc >= minKeys) return;

        // Find our position in parent
        PageId    parentId   = new PageId(pid);
        Page      parentPage = bufferPool.readPage(parentId);
        BTreeNode parent     = new BTreeNode(parentPage, keyType);
        int       myIdx      = findChildIndex(parent, leafId);
        bufferPool.unpin(parentId);

        // Try borrow from left sibling
        if (myIdx > 0) {
            PageId leftSibId = parent.internalGetChild(myIdx - 1);
            // Re-read parent (it was unpinned above)
            parentPage = bufferPool.readPage(parentId);
            parent     = new BTreeNode(parentPage, keyType);
            leftSibId  = parent.internalGetChild(myIdx - 1);
            bufferPool.unpin(parentId);

            Page      lsPage = bufferPool.readPage(leftSibId);
            BTreeNode lsNode = new BTreeNode(lsPage, keyType);
            if (lsNode.getKeyCount() > minKeys) {
                borrowFromLeftLeaf(leafId, leftSibId, parentId, myIdx);
                bufferPool.unpin(leftSibId);
                return;
            }
            bufferPool.unpin(leftSibId);
        }

        // Try borrow from right sibling
        parentPage = bufferPool.readPage(parentId);
        parent     = new BTreeNode(parentPage, keyType);
        int parentKC = parent.getKeyCount();
        bufferPool.unpin(parentId);

        if (myIdx < parentKC) {
            parentPage = bufferPool.readPage(parentId);
            parent     = new BTreeNode(parentPage, keyType);
            PageId rightSibId = parent.internalGetChild(myIdx + 1);
            bufferPool.unpin(parentId);

            Page      rsPage = bufferPool.readPage(rightSibId);
            BTreeNode rsNode = new BTreeNode(rsPage, keyType);
            if (rsNode.getKeyCount() > minKeys) {
                borrowFromRightLeaf(leafId, rightSibId, parentId, myIdx);
                bufferPool.unpin(rightSibId);
                return;
            }
            bufferPool.unpin(rightSibId);
        }

        // Neither sibling can lend — merge
        if (myIdx > 0) {
            // Merge left sibling into this leaf
            parentPage = bufferPool.readPage(parentId);
            parent     = new BTreeNode(parentPage, keyType);
            PageId leftSibId = parent.internalGetChild(myIdx - 1);
            bufferPool.unpin(parentId);
            mergeLeaves(leftSibId, leafId, parentId, myIdx - 1);
        } else {
            // Merge this leaf into right sibling
            parentPage = bufferPool.readPage(parentId);
            parent     = new BTreeNode(parentPage, keyType);
            PageId rightSibId = parent.internalGetChild(myIdx + 1);
            bufferPool.unpin(parentId);
            mergeLeaves(leafId, rightSibId, parentId, myIdx);
        }
    }

    /** Borrows the last entry from the left sibling leaf. */
    private void borrowFromLeftLeaf(PageId rightId, PageId leftId,
                                     PageId parentId, int rightIdxInParent)
            throws ForgeDBException {
        Page      rightPage = bufferPool.readPage(rightId);
        BTreeNode rightNode = new BTreeNode(rightPage, keyType);
        Page      leftPage  = bufferPool.readPage(leftId);
        BTreeNode leftNode  = new BTreeNode(leftPage, keyType);

        // Move last entry of left to front of right
        int      lastIdx = leftNode.getKeyCount() - 1;
        BTreeKey movedKey = leftNode.leafGetKey(lastIdx);
        RecordId movedRid = leftNode.leafGetRid(lastIdx);
        leftNode.leafRemoveAt(lastIdx);
        rightNode.leafInsertAt(0, movedKey, movedRid);

        // Update parent separator: parent key[rightIdxInParent - 1] = first key of right
        Page      parentPage = bufferPool.readPage(parentId);
        BTreeNode parent     = new BTreeNode(parentPage, keyType);
        parent.internalSetKey(rightIdxInParent - 1, rightNode.leafGetKey(0));

        bufferPool.writePage(rightId,  rightPage);
        bufferPool.writePage(leftId,   leftPage);
        bufferPool.writePage(parentId, parentPage);
        bufferPool.unpin(rightId);
        bufferPool.unpin(leftId);
        bufferPool.unpin(parentId);
    }

    /** Borrows the first entry from the right sibling leaf. */
    private void borrowFromRightLeaf(PageId leftId, PageId rightId,
                                      PageId parentId, int leftIdxInParent)
            throws ForgeDBException {
        Page      leftPage  = bufferPool.readPage(leftId);
        BTreeNode leftNode  = new BTreeNode(leftPage, keyType);
        Page      rightPage = bufferPool.readPage(rightId);
        BTreeNode rightNode = new BTreeNode(rightPage, keyType);

        // Move first entry of right to end of left
        BTreeKey movedKey = rightNode.leafGetKey(0);
        RecordId movedRid = rightNode.leafGetRid(0);
        rightNode.leafRemoveAt(0);
        leftNode.leafInsertAt(leftNode.getKeyCount(), movedKey, movedRid);

        // Update parent separator: parent key[leftIdxInParent] = first key of right
        Page      parentPage = bufferPool.readPage(parentId);
        BTreeNode parent     = new BTreeNode(parentPage, keyType);
        parent.internalSetKey(leftIdxInParent, rightNode.leafGetKey(0));

        bufferPool.writePage(leftId,   leftPage);
        bufferPool.writePage(rightId,  rightPage);
        bufferPool.writePage(parentId, parentPage);
        bufferPool.unpin(leftId);
        bufferPool.unpin(rightId);
        bufferPool.unpin(parentId);
    }

    /**
     * Merges two adjacent leaf siblings. After merge, the left leaf contains
     * all entries; the right leaf is effectively abandoned (set to type FREE).
     * The separator key is removed from the parent.
     *
     * @param leftId          the left leaf
     * @param rightId         the right leaf
     * @param parentId        the parent of both
     * @param leftKeyIdxInParent  the index of the separator key in the parent
     *                            (parent.key[leftKeyIdxInParent] separates left/right)
     */
    private void mergeLeaves(PageId leftId, PageId rightId,
                               PageId parentId, int leftKeyIdxInParent)
            throws ForgeDBException {
        Page      leftPage  = bufferPool.readPage(leftId);
        BTreeNode leftNode  = new BTreeNode(leftPage, keyType);
        Page      rightPage = bufferPool.readPage(rightId);
        BTreeNode rightNode = new BTreeNode(rightPage, keyType);

        // Append all right entries to left
        int rightKC = rightNode.getKeyCount();
        int leftKC  = leftNode.getKeyCount();
        for (int i = 0; i < rightKC; i++) {
            BTreeKey k = rightNode.leafGetKey(i);
            RecordId r = rightNode.leafGetRid(i);
            leftNode.leafInsertAt(leftKC + i, k, r);
        }

        // Relink: left.next = right.next
        leftNode.setNextLeaf(rightNode.getNextLeaf());

        // Mark right leaf as free
        rightPage.setPageType(PageType.FREE);

        bufferPool.writePage(leftId,  leftPage);
        bufferPool.writePage(rightId, rightPage);
        bufferPool.unpin(leftId);
        bufferPool.unpin(rightId);

        // Remove the separator key from parent
        Page      parentPage = bufferPool.readPage(parentId);
        BTreeNode parent     = new BTreeNode(parentPage, keyType);
        parent.internalRemoveAt(leftKeyIdxInParent);
        bufferPool.writePage(parentId, parentPage);
        bufferPool.unpin(parentId);

        fixInternalUnderflow(parentId, parentPage);
    }

    /**
     * After removing a key from an internal node, fix any underflow.
     */
    private void fixInternalUnderflow(PageId nodeId, Page nodePage)
            throws ForgeDBException {
        Page      page = bufferPool.readPage(nodeId);
        BTreeNode node = new BTreeNode(page, keyType);
        int       kc   = node.getKeyCount();
        int       pid  = node.getParentId();
        bufferPool.unpin(nodeId);

        // Root: if empty and has one child, shrink the tree
        if (pid == BTreeNode.NO_PAGE) {
            if (kc == 0) {
                // The root is empty — its only child becomes the new root
                page = bufferPool.readPage(nodeId);
                node = new BTreeNode(page, keyType);
                if (node.isInternal() && node.getKeyCount() == 0) {
                    PageId newRootId = node.internalGetChild(0);
                    bufferPool.unpin(nodeId);
                    updateParentId(newRootId, BTreeNode.NO_PAGE);
                    rootPageId = newRootId.value();
                    height--;
                    persistMetadata();
                    // Mark old root as free
                    page = bufferPool.readPage(nodeId);
                    page.setPageType(PageType.FREE);
                    bufferPool.writePage(nodeId, page);
                    bufferPool.unpin(nodeId);
                } else {
                    bufferPool.unpin(nodeId);
                }
            }
            return;
        }

        if (kc >= minKeys) return;

        // Find our position in parent
        PageId    parentId   = new PageId(pid);
        Page      parentPage = bufferPool.readPage(parentId);
        BTreeNode parent     = new BTreeNode(parentPage, keyType);
        int       myIdx      = findChildIndex(parent, nodeId);
        bufferPool.unpin(parentId);

        // Try borrow from left sibling
        if (myIdx > 0) {
            parentPage = bufferPool.readPage(parentId);
            parent     = new BTreeNode(parentPage, keyType);
            PageId leftSibId = parent.internalGetChild(myIdx - 1);
            bufferPool.unpin(parentId);

            Page      lsPage = bufferPool.readPage(leftSibId);
            BTreeNode lsNode = new BTreeNode(lsPage, keyType);
            if (lsNode.getKeyCount() > minKeys) {
                borrowFromLeftInternal(nodeId, leftSibId, parentId, myIdx);
                bufferPool.unpin(leftSibId);
                return;
            }
            bufferPool.unpin(leftSibId);
        }

        // Try borrow from right sibling
        parentPage = bufferPool.readPage(parentId);
        parent     = new BTreeNode(parentPage, keyType);
        int parentKC = parent.getKeyCount();
        bufferPool.unpin(parentId);

        if (myIdx < parentKC) {
            parentPage = bufferPool.readPage(parentId);
            parent     = new BTreeNode(parentPage, keyType);
            PageId rightSibId = parent.internalGetChild(myIdx + 1);
            bufferPool.unpin(parentId);

            Page      rsPage = bufferPool.readPage(rightSibId);
            BTreeNode rsNode = new BTreeNode(rsPage, keyType);
            if (rsNode.getKeyCount() > minKeys) {
                borrowFromRightInternal(nodeId, rightSibId, parentId, myIdx);
                bufferPool.unpin(rightSibId);
                return;
            }
            bufferPool.unpin(rightSibId);
        }

        // Merge
        if (myIdx > 0) {
            parentPage = bufferPool.readPage(parentId);
            parent     = new BTreeNode(parentPage, keyType);
            PageId leftSibId = parent.internalGetChild(myIdx - 1);
            bufferPool.unpin(parentId);
            mergeInternal(leftSibId, nodeId, parentId, myIdx - 1);
        } else {
            parentPage = bufferPool.readPage(parentId);
            parent     = new BTreeNode(parentPage, keyType);
            PageId rightSibId = parent.internalGetChild(myIdx + 1);
            bufferPool.unpin(parentId);
            mergeInternal(nodeId, rightSibId, parentId, myIdx);
        }
    }

    /** Rotates from left internal sibling: pulls down parent key, pushes up rightmost left key. */
    private void borrowFromLeftInternal(PageId rightId, PageId leftId,
                                          PageId parentId, int rightIdxInParent)
            throws ForgeDBException {
        Page      rightPage = bufferPool.readPage(rightId);
        BTreeNode rightNode = new BTreeNode(rightPage, keyType);
        Page      leftPage  = bufferPool.readPage(leftId);
        BTreeNode leftNode  = new BTreeNode(leftPage, keyType);
        Page      parentPage = bufferPool.readPage(parentId);
        BTreeNode parent    = new BTreeNode(parentPage, keyType);

        BTreeKey parentKey    = parent.internalGetKey(rightIdxInParent - 1);
        int      leftLastKC   = leftNode.getKeyCount() - 1;
        BTreeKey leftLastKey  = leftNode.internalGetKey(leftLastKC);
        PageId   movedChild   = leftNode.internalGetChild(leftLastKC + 1);

        // Insert parentKey at front of rightNode, with movedChild as left-most new child
        // Shift right node: child[0] becomes child[1], etc.
        rightNode.internalSetChild(rightNode.getKeyCount() + 1,
            rightNode.internalGetChild(rightNode.getKeyCount()));
        for (int i = rightNode.getKeyCount(); i > 0; i--) {
            rightNode.internalSetKey(i, rightNode.internalGetKey(i - 1));
            rightNode.internalSetChild(i, rightNode.internalGetChild(i - 1));
        }
        rightNode.internalSetKey(0, parentKey);
        rightNode.internalSetChild(0, movedChild);
        rightNode.setKeyCount(rightNode.getKeyCount() + 1);
        updateParentId(movedChild, rightId.value());

        // Update parent separator
        parent.internalSetKey(rightIdxInParent - 1, leftLastKey);

        // Remove last key+child from left
        leftNode.internalRemoveAt(leftLastKC);

        bufferPool.writePage(rightId,  rightPage);
        bufferPool.writePage(leftId,   leftPage);
        bufferPool.writePage(parentId, parentPage);
        bufferPool.unpin(rightId);
        bufferPool.unpin(leftId);
        bufferPool.unpin(parentId);
    }

    /** Rotates from right internal sibling. */
    private void borrowFromRightInternal(PageId leftId, PageId rightId,
                                           PageId parentId, int leftIdxInParent)
            throws ForgeDBException {
        Page      leftPage   = bufferPool.readPage(leftId);
        BTreeNode leftNode   = new BTreeNode(leftPage, keyType);
        Page      rightPage  = bufferPool.readPage(rightId);
        BTreeNode rightNode  = new BTreeNode(rightPage, keyType);
        Page      parentPage = bufferPool.readPage(parentId);
        BTreeNode parent     = new BTreeNode(parentPage, keyType);

        BTreeKey parentKey  = parent.internalGetKey(leftIdxInParent);
        BTreeKey rightFirst = rightNode.internalGetKey(0);
        PageId   movedChild = rightNode.internalGetChild(0);

        // Append parentKey + movedChild to left
        int leftKC = leftNode.getKeyCount();
        leftNode.internalInsertAt(leftKC, parentKey, movedChild);
        updateParentId(movedChild, leftId.value());

        // Update parent separator
        parent.internalSetKey(leftIdxInParent, rightFirst);

        // Remove first key+child from right
        rightNode.internalRemoveAt(0);
        // Also shift child[0] out
        int rightKC = rightNode.getKeyCount();
        for (int i = 0; i <= rightKC; i++) {
            rightNode.internalSetChild(i, rightNode.internalGetChild(i + 1));
        }

        bufferPool.writePage(leftId,   leftPage);
        bufferPool.writePage(rightId,  rightPage);
        bufferPool.writePage(parentId, parentPage);
        bufferPool.unpin(leftId);
        bufferPool.unpin(rightId);
        bufferPool.unpin(parentId);
    }

    /** Merges two adjacent internal siblings, pulling down the separator from parent. */
    private void mergeInternal(PageId leftId, PageId rightId,
                                 PageId parentId, int leftKeyIdxInParent)
            throws ForgeDBException {
        Page      leftPage   = bufferPool.readPage(leftId);
        BTreeNode leftNode   = new BTreeNode(leftPage, keyType);
        Page      rightPage  = bufferPool.readPage(rightId);
        BTreeNode rightNode  = new BTreeNode(rightPage, keyType);
        Page      parentPage = bufferPool.readPage(parentId);
        BTreeNode parent     = new BTreeNode(parentPage, keyType);

        BTreeKey separator = parent.internalGetKey(leftKeyIdxInParent);

        // Pull down separator + all right children into left
        int leftKC  = leftNode.getKeyCount();
        int rightKC = rightNode.getKeyCount();

        // First child of right becomes child after last key of left
        PageId rightFirstChild = rightNode.internalGetChild(0);
        leftNode.internalInsertAt(leftKC, separator, rightFirstChild);
        updateParentId(rightFirstChild, leftId.value());

        for (int i = 0; i < rightKC; i++) {
            BTreeKey k = rightNode.internalGetKey(i);
            PageId   c = rightNode.internalGetChild(i + 1);
            int      p = leftNode.getKeyCount();
            leftNode.internalInsertAt(p, k, c);
            updateParentId(c, leftId.value());
        }

        // Mark right as free
        rightPage.setPageType(PageType.FREE);

        bufferPool.writePage(leftId,  leftPage);
        bufferPool.writePage(rightId, rightPage);
        bufferPool.unpin(leftId);
        bufferPool.unpin(rightId);

        // Remove separator from parent
        parent.internalRemoveAt(leftKeyIdxInParent);
        bufferPool.writePage(parentId, parentPage);
        bufferPool.unpin(parentId);

        fixInternalUnderflow(parentId, parentPage);
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    /** Finds the index of {@code childId} among the children of {@code parent}. */
    private int findChildIndex(BTreeNode parent, PageId childId)
            throws ForgeDBException {
        int count = parent.getKeyCount();
        for (int i = 0; i <= count; i++) {
            if (parent.internalGetChild(i).equals(childId)) return i;
        }
        throw new ForgeDBException("Child " + childId + " not found in parent");
    }

    /** Updates the parentId field of the node at {@code childId}. */
    private void updateParentId(PageId childId, int newParentId)
            throws ForgeDBException {
        Page      childPage = bufferPool.readPage(childId);
        BTreeNode childNode = new BTreeNode(childPage, keyType);
        childNode.setParentId(newParentId);
        bufferPool.writePage(childId, childPage);
        bufferPool.unpin(childId);
    }

    /** Persists rootPageId and height to the metadata page. */
    private void persistMetadata() throws ForgeDBException {
        Page metaPage = bufferPool.readPage(metaPageId);
        metaPage.putInt(META_ROOT_PAGE_ID, rootPageId);
        metaPage.putInt(META_HEIGHT,       height);
        bufferPool.writePage(metaPageId, metaPage);
        bufferPool.unpin(metaPageId);
    }

    // =========================================================================
    // Capacity computation
    // =========================================================================

    /**
     * Computes the B+ tree order (maximum keys per leaf node) for a given key type.
     *
     * Leaf node capacity (payload = 4080 bytes):
     *   header = 16 bytes
     *   each leaf entry = keySize + 8 (rid.pageId + rid.slot)
     *   order = floor((4080 - 16) / entrySize) = floor(4064 / entrySize)
     *
     * For TEXT keys we use a default maximum assumed key length of 64 bytes
     * (plus 4-byte length prefix), giving entrySize = 68 + 8 = 76.
     * This ensures the tree has a consistent, calculable order even for
     * variable-length keys. The actual page can hold fewer entries if keys
     * are larger, which will cause splits at the right time.
     *
     * We cap the order at 4 as a minimum so tests with small orders work.
     */
    public static int computeOrder(DataType keyType) {
        int headerSize   = BTreeNode.HDR_SIZE;            // 16
        int usable       = Constants.PAGE_PAYLOAD_SIZE - headerSize;  // 4064
        int keySize      = switch (keyType) {
            case INT    -> 4;
            case LONG   -> 8;
            case DOUBLE -> 8;
            case TEXT   -> 68;  // 4-byte length prefix + 64-byte typical key
            default     -> throw new UnsupportedOperationException("Unsupported: " + keyType);
        };
        int entrySize    = keySize + 8;  // key + rid(8)
        // Subtract 1: the page must hold ORDER+1 entries before triggering a split,
        // so ORDER = floor(usable / entrySize) - 1.
        // Minimum 4 to keep the tree structure valid in tests.
        return Math.max(4, usable / entrySize - 1);
    }
}
