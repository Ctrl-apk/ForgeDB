package com.forgedb.index;

import com.forgedb.catalog.DataType;
import com.forgedb.common.Constants;
import com.forgedb.common.ForgeDBException;
import com.forgedb.storage.Page;
import com.forgedb.storage.PageId;
import com.forgedb.storage.RecordId;

/**
 * Reads and writes B+ tree node data within a single {@link Page} payload.
 *
 * BTreeNode does NOT touch the DiskManager or BufferPool directly. It only
 * reads from and writes to the in-memory Page object supplied to it. The
 * caller (BTree) is responsible for loading and flushing pages.
 *
 * Page payload layout
 * -------------------
 * All data is stored in the 4080-byte page payload (bytes 16–4095 of the
 * 4096-byte page, addressed via payload-relative offsets 0–4079).
 *
 * Node header (fixed, 16 bytes):
 *   [0..3]   nodeType  : int  — NODE_INTERNAL=0, NODE_LEAF=1
 *   [4..7]   keyCount  : int  — number of keys currently stored
 *   [8..11]  parentId  : int  — PageId.value() of parent, -1 if root
 *   [12..15] nextLeaf  : int  — PageId.value() of right sibling leaf, -1 if none
 *                               (unused / always -1 for internal nodes)
 *
 * Node body layout depends on node type:
 *
 * INTERNAL NODE body (starting at payload offset 16):
 *   child[0] (4 bytes) | key[0] (variable) | child[1] | key[1] | ... | child[keyCount]
 *   Total child count = keyCount + 1.
 *   Layout: child(int pageId), then alternating key bytes and child ints.
 *
 * LEAF NODE body (starting at payload offset 16):
 *   For each i in [0, keyCount):
 *     key[i] bytes | rid.pageId (4 bytes) | rid.slotIndex (4 bytes)
 *   RID = RecordId pointing to the actual tuple in the heap file.
 *   Duplicate keys are stored as adjacent entries.
 *
 * Key serialisation size:
 *   INT     → 4 bytes
 *   LONG    → 8 bytes
 *   DOUBLE  → 8 bytes
 *   TEXT    → 4 bytes (length prefix) + N bytes UTF-8
 *
 * Capacity (order = max keys per node):
 *   Computed by BTree at construction from the key type and PAGE_PAYLOAD_SIZE.
 *   See BTree.computeOrder().
 *
 * Everything is big-endian, consistent with the rest of ForgeDB.
 */
public final class BTreeNode {

    // -------------------------------------------------------------------------
    // Node type constants
    // -------------------------------------------------------------------------

    public static final int NODE_INTERNAL = 0;
    public static final int NODE_LEAF     = 1;

    // -------------------------------------------------------------------------
    // Node header offsets (payload-relative)
    // -------------------------------------------------------------------------

    static final int HDR_NODE_TYPE  = 0;   // int (4 bytes)
    static final int HDR_KEY_COUNT  = 4;   // int (4 bytes)
    static final int HDR_PARENT_ID  = 8;   // int (4 bytes)
    static final int HDR_NEXT_LEAF  = 12;  // int (4 bytes); -1 for internal or last leaf
    static final int HDR_SIZE       = 16;  // total header size

    /** Sentinel value meaning "no page" (no parent, no next sibling). */
    public static final int NO_PAGE = -1;

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Page     page;
    private final DataType keyType;

    // -------------------------------------------------------------------------
    // Construction / initialisation
    // -------------------------------------------------------------------------

    /**
     * Wraps an existing page that already has a valid node header.
     *
     * @param page    a page whose payload starts with a valid BTreeNode header
     * @param keyType the type of keys stored in this node
     */
    public BTreeNode(Page page, DataType keyType) {
        this.page    = page;
        this.keyType = keyType;
    }

    /**
     * Initialises the node header on a blank page and returns the wrapper.
     * Call this exactly once on a freshly allocated page.
     *
     * @param page     a blank page (all payload bytes zero)
     * @param keyType  the type of keys this node will store
     * @param nodeType NODE_INTERNAL or NODE_LEAF
     * @param parentId parent page ID (NO_PAGE = -1 if this is the root)
     */
    public static BTreeNode init(Page page, DataType keyType, int nodeType, int parentId) {
        BTreeNode node = new BTreeNode(page, keyType);
        node.setNodeType(nodeType);
        node.setKeyCount(0);
        node.setParentId(parentId);
        node.setNextLeaf(NO_PAGE);
        return node;
    }

    // -------------------------------------------------------------------------
    // Header accessors
    // -------------------------------------------------------------------------

    public int  getNodeType()           { return page.getInt(HDR_NODE_TYPE); }
    public void setNodeType(int t)      { page.putInt(HDR_NODE_TYPE, t); }
    public int  getKeyCount()           { return page.getInt(HDR_KEY_COUNT); }
    public void setKeyCount(int n)      { page.putInt(HDR_KEY_COUNT, n); }
    public int  getParentId()           { return page.getInt(HDR_PARENT_ID); }
    public void setParentId(int pid)    { page.putInt(HDR_PARENT_ID, pid); }
    public int  getNextLeaf()           { return page.getInt(HDR_NEXT_LEAF); }
    public void setNextLeaf(int next)   { page.putInt(HDR_NEXT_LEAF, next); }
    public boolean isLeaf()             { return getNodeType() == NODE_LEAF; }
    public boolean isInternal()         { return getNodeType() == NODE_INTERNAL; }
    public Page getPage()               { return page; }
    public DataType keyType()           { return keyType; }

    // =========================================================================
    // LEAF NODE operations
    // =========================================================================

    /**
     * Reads the key at position {@code index} in this leaf node.
     * The body starts at payload offset HDR_SIZE.
     */
    public BTreeKey leafGetKey(int index) throws ForgeDBException {
        int offset = leafOffsetOf(index);
        return BTreeKey.deserialize(keyType, pageBytes(), offset);
    }

    /**
     * Reads the RecordId at position {@code index} in this leaf node.
     */
    public RecordId leafGetRid(int index) throws ForgeDBException {
        int keyBytes = leafKeyByteSize(index);
        int offset   = leafOffsetOf(index) + keyBytes;
        int pid   = readIntAt(offset);
        int slot  = readIntAt(offset + 4);
        return new RecordId(new PageId(pid), slot);
    }

    /**
     * Appends a (key, rid) pair at the end of this leaf node.
     * Does NOT enforce sorted order — caller must insert at the right position
     * using {@link #leafInsertAt}.
     */
    public void leafAppend(BTreeKey key, RecordId rid) throws ForgeDBException {
        int index = getKeyCount();
        leafInsertAt(index, key, rid);
    }

    /**
     * Inserts (key, rid) at position {@code index}, shifting later entries right.
     */
    public void leafInsertAt(int insertIdx, BTreeKey key, RecordId rid)
            throws ForgeDBException {
        int count = getKeyCount();
        // Shift entries [insertIdx..count) one position right to make room
        for (int i = count; i > insertIdx; i--) {
            copyLeafEntry(i - 1, i);
        }
        writeLeafEntry(insertIdx, key, rid);
        setKeyCount(count + 1);
    }

    /**
     * Removes the entry at position {@code index}, shifting later entries left.
     */
    public void leafRemoveAt(int removeIdx) throws ForgeDBException {
        int count = getKeyCount();
        for (int i = removeIdx; i < count - 1; i++) {
            copyLeafEntry(i + 1, i);
        }
        // Zero out the last entry slot (not strictly necessary but helps debugging)
        zeroLeafEntry(count - 1);
        setKeyCount(count - 1);
    }

    // =========================================================================
    // INTERNAL NODE operations
    // =========================================================================

    /**
     * Reads the key at position {@code index} in this internal node.
     * Internal node body layout:
     *   child[0] key[0] child[1] key[1] ... key[N-1] child[N]
     * where N = keyCount.
     */
    public BTreeKey internalGetKey(int index) throws ForgeDBException {
        int offset = internalKeyOffset(index);
        return BTreeKey.deserialize(keyType, pageBytes(), offset);
    }

    /**
     * Returns the PageId of child pointer at position {@code childIdx}.
     * childIdx ranges from 0 to keyCount (inclusive).
     */
    public PageId internalGetChild(int childIdx) {
        int offset = internalChildOffset(childIdx);
        return new PageId(readIntAt(offset));
    }

    /**
     * Sets the key at position {@code index}.
     */
    public void internalSetKey(int index, BTreeKey key) throws ForgeDBException {
        int offset = internalKeyOffset(index);
        writeKeyAt(offset, key);
    }

    /**
     * Sets the child pointer at position {@code childIdx}.
     */
    public void internalSetChild(int childIdx, PageId childPageId) {
        int offset = internalChildOffset(childIdx);
        writeIntAt(offset, childPageId.value());
    }

    /**
     * Inserts a (key, rightChild) pair at position {@code insertIdx},
     * shifting existing entries to the right. The left child of the new key
     * is the existing child at insertIdx (already present).
     *
     * After: ..., child[insertIdx], key[insertIdx](new), child[insertIdx+1](rightChild), ...
     */
    public void internalInsertAt(int insertIdx, BTreeKey key, PageId rightChild)
            throws ForgeDBException {
        int count = getKeyCount();
        // Shift keys and right-children from insertIdx rightward
        for (int i = count; i > insertIdx; i--) {
            // Copy key[i-1] → key[i]
            BTreeKey k = internalGetKey(i - 1);
            internalSetKey(i, k);
            // Copy child[i] → child[i+1]
            PageId c = internalGetChild(i);
            internalSetChild(i + 1, c);
        }
        internalSetKey(insertIdx, key);
        internalSetChild(insertIdx + 1, rightChild);
        setKeyCount(count + 1);
    }

    /**
     * Removes the key at {@code removeIdx} along with its right child pointer
     * (child[removeIdx+1]), shifting entries left.
     */
    public void internalRemoveAt(int removeIdx) throws ForgeDBException {
        int count = getKeyCount();
        for (int i = removeIdx; i < count - 1; i++) {
            BTreeKey k = internalGetKey(i + 1);
            internalSetKey(i, k);
            PageId c = internalGetChild(i + 2);
            internalSetChild(i + 1, c);
        }
        setKeyCount(count - 1);
    }

    // =========================================================================
    // Binary search helpers
    // =========================================================================

    /**
     * Finds the index of the first key >= searchKey in this node.
     * Returns keyCount if all keys are < searchKey.
     */
    public int findFirstGE(BTreeKey searchKey) throws ForgeDBException {
        int lo = 0, hi = getKeyCount();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            BTreeKey midKey = isLeaf() ? leafGetKey(mid) : internalGetKey(mid);
            if (midKey.compareTo(searchKey) < 0) lo = mid + 1;
            else                                  hi = mid;
        }
        return lo;
    }

    /**
     * For internal nodes: returns the child index to follow for {@code key}.
     * If key < key[0] → child[0]; if key[i-1] <= key < key[i] → child[i]; etc.
     */
    public int internalFindChildIndex(BTreeKey searchKey) throws ForgeDBException {
        int count = getKeyCount();
        for (int i = 0; i < count; i++) {
            if (searchKey.compareTo(internalGetKey(i)) < 0) return i;
        }
        return count;
    }

    // =========================================================================
    // Offset arithmetic
    // =========================================================================

    /**
     * Returns the payload offset of leaf entry {@code index}.
     * Each leaf entry = key bytes + 4 (rid.pageId) + 4 (rid.slotIndex).
     *
     * For fixed-size key types: every entry is the same size, so this is O(1).
     * For TEXT: we must scan from the beginning, so this is O(index).
     */
    int leafOffsetOf(int index) throws ForgeDBException {
        int offset = HDR_SIZE;
        for (int i = 0; i < index; i++) {
            offset += leafEntrySize(i, offset);
        }
        return offset;
    }

    /** Returns the total byte size of leaf entry {@code index}. */
    private int leafEntrySize(int index, int offsetOfEntry) throws ForgeDBException {
        return leafKeyByteSizeAt(offsetOfEntry) + 8; // key bytes + rid(4+4)
    }

    /** Returns the byte size of the serialised key at payload offset {@code keyOffset}. */
    private int leafKeyByteSizeAt(int keyOffset) throws ForgeDBException {
        return switch (keyType) {
            case INT    -> 4;
            case LONG   -> 8;
            case DOUBLE -> 8;
            case TEXT   -> 4 + readIntAt(keyOffset);   // length prefix + content
            default     -> throw new ForgeDBException("Unsupported key type: " + keyType);
        };
    }

    /** Returns the byte size of the key at position {@code index}. */
    private int leafKeyByteSize(int index) throws ForgeDBException {
        int offset = leafOffsetOf(index);
        return leafKeyByteSizeAt(offset);
    }

    // Internal node layout helpers
    // child[0] | key[0] | child[1] | key[1] | ... | key[N-1] | child[N]
    // Each child = 4 bytes (int pageId), each key = fixed or variable bytes.

    int internalChildOffset(int childIdx) {
        // child[0] is at HDR_SIZE
        // child[i] is at HDR_SIZE + i * (keySize + 4) — only for fixed-size keys
        // For TEXT we must walk the layout
        if (keyType == DataType.TEXT) {
            return internalChildOffsetVariable(childIdx);
        }
        int keySize = BTreeKey.fixedSerializedSize(keyType);
        return HDR_SIZE + childIdx * (keySize + 4);
    }

    int internalKeyOffset(int keyIdx) {
        if (keyType == DataType.TEXT) {
            return internalKeyOffsetVariable(keyIdx);
        }
        int keySize = BTreeKey.fixedSerializedSize(keyType);
        // child[0] | key[0] | child[1] | ...
        // key[i] is after child[i]
        return HDR_SIZE + keyIdx * (keySize + 4) + 4;
    }

    private int internalChildOffsetVariable(int childIdx) {
        // Walk from start: child(4) key(var) child(4) key(var) ... child(4)
        int offset = HDR_SIZE;
        for (int i = 0; i < childIdx; i++) {
            offset += 4; // skip child[i]
            int textLen = readIntAt(offset);  // key length prefix
            offset += 4 + textLen;            // skip key[i]
        }
        return offset;
    }

    private int internalKeyOffsetVariable(int keyIdx) {
        int offset = HDR_SIZE;
        for (int i = 0; i <= keyIdx; i++) {
            offset += 4; // skip child[i]
            if (i == keyIdx) return offset; // key[keyIdx] starts here
            int textLen = readIntAt(offset);
            offset += 4 + textLen;
        }
        return offset; // unreachable
    }

    // =========================================================================
    // Raw read / write helpers
    // =========================================================================

    private byte[] pageBytes() {
        // We need the raw bytes starting from payload offset 0 for BTreeKey.deserialize.
        // Page.getBytes copies payload bytes into a fresh array.
        byte[] buf = new byte[Constants.PAGE_PAYLOAD_SIZE];
        page.getBytes(0, buf, 0, Constants.PAGE_PAYLOAD_SIZE);
        return buf;
    }

    private int readIntAt(int payloadOffset) {
        return page.getInt(payloadOffset);
    }

    private void writeIntAt(int payloadOffset, int value) {
        page.putInt(payloadOffset, value);
    }

    private void writeKeyAt(int payloadOffset, BTreeKey key) throws ForgeDBException {
        byte[] bytes = key.serialize();
        page.putBytes(payloadOffset, bytes, 0, bytes.length);
    }

    // =========================================================================
    // Leaf entry helpers
    // =========================================================================

    private void writeLeafEntry(int index, BTreeKey key, RecordId rid)
            throws ForgeDBException {
        int offset   = leafOffsetOf(index);
        byte[] kbytes = key.serialize();
        page.putBytes(offset, kbytes, 0, kbytes.length);
        offset += kbytes.length;
        writeIntAt(offset,     rid.pageId().value());
        writeIntAt(offset + 4, rid.slotIndex());
    }

    /** Copies leaf entry from {@code src} index to {@code dst} index. */
    private void copyLeafEntry(int src, int dst) throws ForgeDBException {
        // Read from src
        int srcOff  = leafOffsetOf(src);
        int keySize = leafKeyByteSizeAt(srcOff);
        int total   = keySize + 8;

        byte[] tmp = new byte[total];
        page.getBytes(srcOff, tmp, 0, total);

        int dstOff = leafOffsetOf(dst);
        page.putBytes(dstOff, tmp, 0, total);
    }

    /** Zeros out a leaf entry slot (for cleanliness after removal). */
    private void zeroLeafEntry(int index) throws ForgeDBException {
        int offset  = leafOffsetOf(index);
        int keySize = leafKeyByteSizeAt(offset);
        int total   = keySize + 8;
        byte[] zeros = new byte[total];
        page.putBytes(offset, zeros, 0, total);
    }
}
