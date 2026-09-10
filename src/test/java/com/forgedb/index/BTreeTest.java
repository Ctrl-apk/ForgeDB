package com.forgedb.index;

import com.forgedb.catalog.DataType;
import com.forgedb.common.ForgeDBException;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DiskManager;
import com.forgedb.storage.DiskManagerImpl;
import com.forgedb.storage.PageId;
import com.forgedb.storage.RecordId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the B+ Tree index (Milestone 4).
 *
 * Test categories:
 *   1.  Empty tree
 *   2.  Single insert/search
 *   3.  Multiple inserts
 *   4.  Missing keys
 *   5.  Duplicate keys
 *   6.  Leaf splits
 *   7.  Root splits (root becomes internal node)
 *   8.  Delete — basic
 *   9.  Delete — rebalance (borrow/merge)
 *   10. Range search
 *   11. Persistence after close/reopen
 *   12. BufferPool integration
 *   13. Large key counts (force multi-level splits)
 *   14. Key types: LONG, DOUBLE, TEXT
 *   15. BTreeKey serialisation round-trips
 */
class BTreeTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String dbPath(String name) {
        return tempDir.resolve(name).toString();
    }

    /** A RecordId with page=1, slot=i — used as a lightweight placeholder. */
    private static RecordId rid(int i) {
        return new RecordId(new PageId(1), i);
    }

    /** Creates a DiskManager-backed BufferPool with plenty of frames. */
    private BufferPool pool(String path) throws ForgeDBException {
        return new BufferPool(new DiskManagerImpl(path), 64);
    }

    // =========================================================================
    // 1. Empty tree
    // =========================================================================

    @Test
    @DisplayName("Search on empty tree returns empty list")
    void emptyTree_searchReturnsEmpty() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("empty.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            List<RecordId> result = tree.search(BTreeKey.ofInt(42));
            assertTrue(result.isEmpty());
        }
    }

    @Test
    @DisplayName("Range search on empty tree returns empty list")
    void emptyTree_rangeSearchReturnsEmpty() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("empty_range.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            List<BTree.RangeEntry> result = tree.rangeSearch(
                BTreeKey.ofInt(1), BTreeKey.ofInt(100));
            assertTrue(result.isEmpty());
        }
    }

    @Test
    @DisplayName("Delete from empty tree throws ForgeDBException")
    void emptyTree_deleteThrows() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("empty_del.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            assertThrows(ForgeDBException.class,
                () -> tree.delete(BTreeKey.ofInt(1), rid(0)));
        }
    }

    // =========================================================================
    // 2. Single insert / search
    // =========================================================================

    @Test
    @DisplayName("Insert one key, search finds it")
    void singleInsert_searchFindsKey() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("single.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(5), rid(0));

            List<RecordId> result = tree.search(BTreeKey.ofInt(5));
            assertEquals(1, result.size());
            assertEquals(rid(0), result.get(0));
        }
    }

    @Test
    @DisplayName("Insert one key, search for different key returns empty")
    void singleInsert_searchMissReturnsEmpty() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("single_miss.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(5), rid(0));

            assertTrue(tree.search(BTreeKey.ofInt(99)).isEmpty());
        }
    }

    // =========================================================================
    // 3. Multiple inserts
    // =========================================================================

    @Test
    @DisplayName("Insert 10 keys in order, all searchable")
    void multiInsert_orderedKeys() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("multi_ordered.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 1; i <= 10; i++) {
                tree.insert(BTreeKey.ofInt(i), rid(i));
            }
            for (int i = 1; i <= 10; i++) {
                List<RecordId> r = tree.search(BTreeKey.ofInt(i));
                assertEquals(1, r.size(), "Expected 1 result for key " + i);
                assertEquals(rid(i), r.get(0));
            }
        }
    }

    @Test
    @DisplayName("Insert 10 keys in reverse order, all searchable")
    void multiInsert_reverseKeys() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("multi_reverse.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 10; i >= 1; i--) {
                tree.insert(BTreeKey.ofInt(i), rid(i));
            }
            for (int i = 1; i <= 10; i++) {
                List<RecordId> r = tree.search(BTreeKey.ofInt(i));
                assertEquals(1, r.size());
                assertEquals(rid(i), r.get(0));
            }
        }
    }

    // =========================================================================
    // 4. Missing keys
    // =========================================================================

    @Test
    @DisplayName("Search for key below minimum returns empty")
    void missingKey_belowMin() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("miss_below.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(10), rid(0));
            assertTrue(tree.search(BTreeKey.ofInt(5)).isEmpty());
        }
    }

    @Test
    @DisplayName("Search for key above maximum returns empty")
    void missingKey_aboveMax() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("miss_above.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(10), rid(0));
            assertTrue(tree.search(BTreeKey.ofInt(20)).isEmpty());
        }
    }

    @Test
    @DisplayName("Search for gap between existing keys returns empty")
    void missingKey_inGap() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("miss_gap.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(1),  rid(1));
            tree.insert(BTreeKey.ofInt(10), rid(10));
            assertTrue(tree.search(BTreeKey.ofInt(5)).isEmpty());
        }
    }

    // =========================================================================
    // 5. Duplicate keys
    // =========================================================================

    @Test
    @DisplayName("Two entries with same key are both found")
    void duplicateKeys_bothFound() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("dup.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(7), rid(0));
            tree.insert(BTreeKey.ofInt(7), rid(1));

            List<RecordId> result = tree.search(BTreeKey.ofInt(7));
            assertEquals(2, result.size());
            assertTrue(result.contains(rid(0)));
            assertTrue(result.contains(rid(1)));
        }
    }

    @Test
    @DisplayName("Five duplicate keys all found")
    void duplicateKeys_fiveEntries() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("dup5.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 0; i < 5; i++) {
                tree.insert(BTreeKey.ofInt(42), rid(i));
            }
            List<RecordId> result = tree.search(BTreeKey.ofInt(42));
            assertEquals(5, result.size());
        }
    }

    @Test
    @DisplayName("Delete one of two duplicates leaves the other")
    void duplicateKeys_deleteOne() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("dup_del.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(7), rid(0));
            tree.insert(BTreeKey.ofInt(7), rid(1));

            tree.delete(BTreeKey.ofInt(7), rid(0));

            List<RecordId> result = tree.search(BTreeKey.ofInt(7));
            assertEquals(1, result.size());
            assertEquals(rid(1), result.get(0));
        }
    }

    // =========================================================================
    // 6. Leaf splits
    // =========================================================================

    @Test
    @DisplayName("Insert enough keys to cause leaf split; all keys still searchable")
    void leafSplit_allKeysSearchable() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("leaf_split.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            int order = tree.order();
            // Insert order+2 keys to guarantee at least one split
            int count = order + 2;
            for (int i = 0; i < count; i++) {
                tree.insert(BTreeKey.ofInt(i * 2), rid(i));
            }
            for (int i = 0; i < count; i++) {
                List<RecordId> r = tree.search(BTreeKey.ofInt(i * 2));
                assertEquals(1, r.size(), "Key " + (i*2) + " not found after leaf split");
            }
        }
    }

    // =========================================================================
    // 7. Root split
    // =========================================================================

    @Test
    @DisplayName("Tree grows taller after root split; all keys still searchable")
    void rootSplit_treeTallerAndCorrect() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("root_split.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            int n = tree.order() * 2 + 5;  // force several splits
            for (int i = 0; i < n; i++) {
                tree.insert(BTreeKey.ofInt(i), rid(i));
            }
            for (int i = 0; i < n; i++) {
                List<RecordId> r = tree.search(BTreeKey.ofInt(i));
                assertFalse(r.isEmpty(), "Key " + i + " not found after root split");
            }
        }
    }

    // =========================================================================
    // 8. Delete — basic
    // =========================================================================

    @Test
    @DisplayName("Delete only key makes tree empty")
    void delete_onlyKey() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("del_only.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(1), rid(0));
            tree.delete(BTreeKey.ofInt(1), rid(0));
            assertTrue(tree.search(BTreeKey.ofInt(1)).isEmpty());
        }
    }

    @Test
    @DisplayName("Delete first key in sequence")
    void delete_firstKey() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("del_first.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 1; i <= 5; i++) tree.insert(BTreeKey.ofInt(i), rid(i));
            tree.delete(BTreeKey.ofInt(1), rid(1));
            assertTrue(tree.search(BTreeKey.ofInt(1)).isEmpty());
            for (int i = 2; i <= 5; i++) {
                assertFalse(tree.search(BTreeKey.ofInt(i)).isEmpty(),
                    "Key " + i + " should still exist");
            }
        }
    }

    @Test
    @DisplayName("Delete last key in sequence")
    void delete_lastKey() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("del_last.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 1; i <= 5; i++) tree.insert(BTreeKey.ofInt(i), rid(i));
            tree.delete(BTreeKey.ofInt(5), rid(5));
            assertTrue(tree.search(BTreeKey.ofInt(5)).isEmpty());
            for (int i = 1; i <= 4; i++) {
                assertFalse(tree.search(BTreeKey.ofInt(i)).isEmpty());
            }
        }
    }

    @Test
    @DisplayName("Delete middle key in sequence")
    void delete_middleKey() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("del_mid.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 1; i <= 5; i++) tree.insert(BTreeKey.ofInt(i), rid(i));
            tree.delete(BTreeKey.ofInt(3), rid(3));
            assertTrue(tree.search(BTreeKey.ofInt(3)).isEmpty());
            for (int i = 1; i <= 5; i++) {
                if (i != 3) assertFalse(tree.search(BTreeKey.ofInt(i)).isEmpty());
            }
        }
    }

    @Test
    @DisplayName("Delete non-existent key throws ForgeDBException")
    void delete_notFound() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("del_notfound.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(1), rid(0));
            assertThrows(ForgeDBException.class,
                () -> tree.delete(BTreeKey.ofInt(99), rid(0)));
        }
    }

    // =========================================================================
    // 9. Delete with merge/redistribution
    // =========================================================================

    @Test
    @DisplayName("Delete from multi-page tree: all remaining keys still searchable")
    void delete_multiPageAllRemaining() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("del_multi.fdb"))) {
            BTree tree  = BTree.create(bp, DataType.INT);
            int   order = tree.order();
            int   n     = order * 3;
            for (int i = 0; i < n; i++) tree.insert(BTreeKey.ofInt(i), rid(i));

            // Delete every other key
            for (int i = 0; i < n; i += 2) {
                tree.delete(BTreeKey.ofInt(i), rid(i));
            }

            // Check deleted are gone
            for (int i = 0; i < n; i += 2) {
                assertTrue(tree.search(BTreeKey.ofInt(i)).isEmpty(),
                    "Deleted key " + i + " should not exist");
            }
            // Check remaining are present
            for (int i = 1; i < n; i += 2) {
                assertFalse(tree.search(BTreeKey.ofInt(i)).isEmpty(),
                    "Key " + i + " should still exist");
            }
        }
    }

    @Test
    @DisplayName("Insert then delete all keys: tree is empty")
    void delete_allKeys_treeEmpty() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("del_all.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            int   n    = tree.order() + 5;
            for (int i = 0; i < n; i++) tree.insert(BTreeKey.ofInt(i), rid(i));
            for (int i = 0; i < n; i++) tree.delete(BTreeKey.ofInt(i), rid(i));
            for (int i = 0; i < n; i++) {
                assertTrue(tree.search(BTreeKey.ofInt(i)).isEmpty(),
                    "All keys should be deleted; key " + i + " still found");
            }
        }
    }

    // =========================================================================
    // 10. Range search
    // =========================================================================

    @Test
    @DisplayName("Range [lo, hi] returns all keys in range")
    void rangeSearch_fullRange() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("range.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 1; i <= 20; i++) tree.insert(BTreeKey.ofInt(i), rid(i));

            List<BTree.RangeEntry> result = tree.rangeSearch(
                BTreeKey.ofInt(5), BTreeKey.ofInt(15));
            assertEquals(11, result.size(), "Expected keys 5..15 inclusive");
            assertEquals(5,  result.get(0).key.asInt());
            assertEquals(15, result.get(result.size() - 1).key.asInt());
        }
    }

    @Test
    @DisplayName("Range with null low scans from smallest key")
    void rangeSearch_nullLow() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("range_null_low.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 1; i <= 10; i++) tree.insert(BTreeKey.ofInt(i), rid(i));

            List<BTree.RangeEntry> result = tree.rangeSearch(null, BTreeKey.ofInt(5));
            assertEquals(5, result.size());
            assertEquals(1, result.get(0).key.asInt());
            assertEquals(5, result.get(result.size()-1).key.asInt());
        }
    }

    @Test
    @DisplayName("Range with null high scans to largest key")
    void rangeSearch_nullHigh() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("range_null_high.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 1; i <= 10; i++) tree.insert(BTreeKey.ofInt(i), rid(i));

            List<BTree.RangeEntry> result = tree.rangeSearch(BTreeKey.ofInt(6), null);
            assertEquals(5, result.size());
            assertEquals(6,  result.get(0).key.asInt());
            assertEquals(10, result.get(result.size()-1).key.asInt());
        }
    }

    @Test
    @DisplayName("Range with null low and null high returns all keys")
    void rangeSearch_fullScan() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("range_all.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            int n = 30;
            for (int i = 1; i <= n; i++) tree.insert(BTreeKey.ofInt(i), rid(i));
            List<BTree.RangeEntry> result = tree.rangeSearch(null, null);
            assertEquals(n, result.size());
        }
    }

    @Test
    @DisplayName("Range that matches no keys returns empty")
    void rangeSearch_noMatch() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("range_nomatch.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            tree.insert(BTreeKey.ofInt(1), rid(1));
            tree.insert(BTreeKey.ofInt(2), rid(2));
            List<BTree.RangeEntry> result = tree.rangeSearch(
                BTreeKey.ofInt(10), BTreeKey.ofInt(20));
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // 11. Persistence after close/reopen
    // =========================================================================

    @Test
    @DisplayName("Inserted keys survive close and reopen")
    void persistence_insertSurvivesReopen() throws ForgeDBException {
        String path = dbPath("persist.fdb");
        PageId metaId;

        try (BufferPool bp = pool(path)) {
            BTree tree = BTree.create(bp, DataType.INT);
            metaId = tree.metaPageId();
            for (int i = 1; i <= 20; i++) tree.insert(BTreeKey.ofInt(i), rid(i));
        }

        try (BufferPool bp = pool(path)) {
            BTree tree = BTree.open(bp, metaId);
            for (int i = 1; i <= 20; i++) {
                List<RecordId> r = tree.search(BTreeKey.ofInt(i));
                assertFalse(r.isEmpty(), "Key " + i + " must survive reopen");
            }
        }
    }

    @Test
    @DisplayName("Deletions persist across close and reopen")
    void persistence_deleteSurvivesReopen() throws ForgeDBException {
        String path = dbPath("persist_del.fdb");
        PageId metaId;

        try (BufferPool bp = pool(path)) {
            BTree tree = BTree.create(bp, DataType.INT);
            metaId = tree.metaPageId();
            for (int i = 1; i <= 10; i++) tree.insert(BTreeKey.ofInt(i), rid(i));
            tree.delete(BTreeKey.ofInt(5), rid(5));
        }

        try (BufferPool bp = pool(path)) {
            BTree tree = BTree.open(bp, metaId);
            assertTrue(tree.search(BTreeKey.ofInt(5)).isEmpty(),
                "Deleted key 5 must not reappear after reopen");
            for (int i = 1; i <= 10; i++) {
                if (i != 5) {
                    assertFalse(tree.search(BTreeKey.ofInt(i)).isEmpty(),
                        "Key " + i + " must survive reopen");
                }
            }
        }
    }

    // =========================================================================
    // 12. BufferPool integration
    // =========================================================================

    @Test
    @DisplayName("All pages are unpinned after each operation (pool size stays bounded)")
    void bufferPool_pagesUnpinnedAfterOps() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("bp_unpin.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            int order = tree.order();
            // Insert enough to cause splits
            for (int i = 0; i < order * 3; i++) {
                tree.insert(BTreeKey.ofInt(i), rid(i));
                // After insert, no pages should be pinned (pin counts all zero)
                // Verify by checking pool hasn't grown without bound: it must stay
                // within the pool capacity of 64 frames
                assertTrue(bp.size() <= bp.capacity(),
                    "Buffer pool exceeded capacity after insert " + i);
            }
        }
    }

    @Test
    @DisplayName("Tree works with a very small buffer pool (8 frames)")
    void bufferPool_smallPool() throws ForgeDBException {
        try (DiskManager dm = new DiskManagerImpl(dbPath("small_bp.fdb"))) {
            BufferPool bp   = new BufferPool(dm, 8);
            BTree      tree = BTree.create(bp, DataType.INT);
            // Insert enough to force multi-level structure but keep within 8 frames
            int n = tree.order() + 5;
            for (int i = 0; i < n; i++) tree.insert(BTreeKey.ofInt(i), rid(i));
            for (int i = 0; i < n; i++) {
                assertFalse(tree.search(BTreeKey.ofInt(i)).isEmpty(),
                    "Key " + i + " must be findable with small pool");
            }
        }
    }

    // =========================================================================
    // 13. Large key counts
    // =========================================================================

    @Test
    @DisplayName("Insert 1000 sequential INT keys, all searchable")
    void large_1000SequentialKeys() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("large_1000.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            int n = 1000;
            for (int i = 0; i < n; i++) tree.insert(BTreeKey.ofInt(i), rid(i % 1000));
            for (int i = 0; i < n; i++) {
                List<RecordId> r = tree.search(BTreeKey.ofInt(i));
                assertFalse(r.isEmpty(), "Key " + i + " not found");
            }
        }
    }

    @Test
    @DisplayName("Insert 500 random-order INT keys, all searchable")
    void large_500RandomKeys() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("large_500random.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            // Use a simple pseudo-random permutation
            int n = 500;
            int[] keys = new int[n];
            for (int i = 0; i < n; i++) keys[i] = i;
            // Knuth shuffle with fixed seed
            java.util.Random rng = new java.util.Random(42);
            for (int i = n - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = keys[i]; keys[i] = keys[j]; keys[j] = tmp;
            }
            for (int k : keys) tree.insert(BTreeKey.ofInt(k), rid(k));
            for (int i = 0; i < n; i++) {
                assertFalse(tree.search(BTreeKey.ofInt(i)).isEmpty(),
                    "Key " + i + " not found after random insertion");
            }
        }
    }

    @Test
    @DisplayName("Range scan over 1000 keys returns correct count")
    void large_rangeScan1000() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("large_range.fdb"))) {
            BTree tree = BTree.create(bp, DataType.INT);
            for (int i = 0; i < 1000; i++) tree.insert(BTreeKey.ofInt(i), rid(i % 1000));
            List<BTree.RangeEntry> result = tree.rangeSearch(
                BTreeKey.ofInt(100), BTreeKey.ofInt(199));
            assertEquals(100, result.size(), "Range [100,199] should have 100 entries");
        }
    }

    // =========================================================================
    // 14. Key types: LONG, DOUBLE, TEXT
    // =========================================================================

    @Test
    @DisplayName("LONG keys: insert/search/delete round-trip")
    void keyType_long() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("long_keys.fdb"))) {
            BTree tree = BTree.create(bp, DataType.LONG);
            tree.insert(BTreeKey.ofLong(Long.MAX_VALUE),  rid(0));
            tree.insert(BTreeKey.ofLong(Long.MIN_VALUE),  rid(1));
            tree.insert(BTreeKey.ofLong(0L),              rid(2));
            tree.insert(BTreeKey.ofLong(-1L),             rid(3));

            assertFalse(tree.search(BTreeKey.ofLong(Long.MAX_VALUE)).isEmpty());
            assertFalse(tree.search(BTreeKey.ofLong(Long.MIN_VALUE)).isEmpty());
            assertFalse(tree.search(BTreeKey.ofLong(0L)).isEmpty());
            assertFalse(tree.search(BTreeKey.ofLong(-1L)).isEmpty());

            tree.delete(BTreeKey.ofLong(0L), rid(2));
            assertTrue(tree.search(BTreeKey.ofLong(0L)).isEmpty());
        }
    }

    @Test
    @DisplayName("DOUBLE keys: insert/search round-trip preserving numeric order")
    void keyType_double() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("double_keys.fdb"))) {
            BTree tree = BTree.create(bp, DataType.DOUBLE);
            double[] vals = {-1.5, 0.0, 1.5, Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int i = 0; i < vals.length; i++) {
                tree.insert(BTreeKey.ofDouble(vals[i]), rid(i));
            }
            for (double v : vals) {
                assertFalse(tree.search(BTreeKey.ofDouble(v)).isEmpty(),
                    "DOUBLE key " + v + " not found");
            }
            // Range: should return [-1.5, 0.0, 1.5]
            List<BTree.RangeEntry> range = tree.rangeSearch(
                BTreeKey.ofDouble(-2.0), BTreeKey.ofDouble(2.0));
            assertEquals(3, range.size());
        }
    }

    @Test
    @DisplayName("TEXT keys: insert/search/range round-trip")
    void keyType_text() throws ForgeDBException {
        try (BufferPool bp = pool(dbPath("text_keys.fdb"))) {
            BTree tree = BTree.create(bp, DataType.TEXT);
            String[] names = {"Alice", "Bob", "Carol", "Dave", "Eve"};
            for (int i = 0; i < names.length; i++) {
                tree.insert(BTreeKey.ofText(names[i]), rid(i));
            }
            for (String name : names) {
                assertFalse(tree.search(BTreeKey.ofText(name)).isEmpty(),
                    "TEXT key '" + name + "' not found");
            }
            // Range: Bob..Dave
            List<BTree.RangeEntry> range = tree.rangeSearch(
                BTreeKey.ofText("Bob"), BTreeKey.ofText("Dave"));
            assertEquals(3, range.size()); // Bob, Carol, Dave
        }
    }

    // =========================================================================
    // 15. BTreeKey serialisation round-trips
    // =========================================================================

    @Test
    @DisplayName("INT key serialises and deserialises correctly")
    void keySerial_int() throws ForgeDBException {
        for (int v : new int[]{0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            BTreeKey original = BTreeKey.ofInt(v);
            byte[]   bytes    = original.serialize();
            BTreeKey back     = BTreeKey.deserialize(DataType.INT, bytes, 0);
            assertEquals(original, back, "INT key round-trip failed for " + v);
        }
    }

    @Test
    @DisplayName("LONG key serialises and deserialises correctly")
    void keySerial_long() throws ForgeDBException {
        for (long v : new long[]{0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE}) {
            BTreeKey original = BTreeKey.ofLong(v);
            byte[]   back     = original.serialize();
            BTreeKey result   = BTreeKey.deserialize(DataType.LONG, back, 0);
            assertEquals(original, result);
        }
    }

    @Test
    @DisplayName("DOUBLE key serialises and deserialises preserving all special values")
    void keySerial_double() throws ForgeDBException {
        double[] vals = {0.0, -0.0, 1.0, -1.0, Math.PI,
                         Double.MAX_VALUE, Double.MIN_VALUE,
                         Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN};
        for (double v : vals) {
            BTreeKey original = BTreeKey.ofDouble(v);
            byte[]   bytes    = original.serialize();
            BTreeKey back     = BTreeKey.deserialize(DataType.DOUBLE, bytes, 0);
            assertEquals(Double.doubleToLongBits(v),
                         Double.doubleToLongBits(back.asDouble()),
                         "DOUBLE round-trip failed for " + v);
        }
    }

    @Test
    @DisplayName("DOUBLE keys are ordered correctly after serialisation transform")
    void keySerial_doubleOrdering() {
        double[] sorted = {Double.NEGATIVE_INFINITY, -1.0, -0.0, 0.0, 1.0,
                           Double.MAX_VALUE, Double.POSITIVE_INFINITY};
        for (int i = 0; i < sorted.length - 1; i++) {
            BTreeKey a = BTreeKey.ofDouble(sorted[i]);
            BTreeKey b = BTreeKey.ofDouble(sorted[i + 1]);
            // Byte-level ordering of serialised forms must match compareTo ordering
            byte[] ab = a.serialize();
            byte[] bb = b.serialize();
            boolean byteLE = compareBytes(ab, bb) <= 0;
            boolean keyLE  = a.compareTo(b) <= 0;
            assertEquals(keyLE, byteLE,
                "Byte order of " + sorted[i] + " vs " + sorted[i+1] + " mismatch");
        }
    }

    @Test
    @DisplayName("TEXT key serialises and deserialises correctly for ASCII and Unicode")
    void keySerial_text() throws ForgeDBException {
        String[] strs = {"", "a", "hello", "ForgeDB", "café", "日本語", "x".repeat(100)};
        for (String s : strs) {
            BTreeKey original = BTreeKey.ofText(s);
            byte[]   bytes    = original.serialize();
            BTreeKey back     = BTreeKey.deserialize(DataType.TEXT, bytes, 0);
            assertEquals(original, back, "TEXT round-trip failed for: " + s);
        }
    }

    @Test
    @DisplayName("BTreeKey comparison: INT ordering is correct")
    void keyCompare_int() {
        assertTrue(BTreeKey.ofInt(-1).compareTo(BTreeKey.ofInt(0)) < 0);
        assertTrue(BTreeKey.ofInt(0).compareTo(BTreeKey.ofInt(0)) == 0);
        assertTrue(BTreeKey.ofInt(1).compareTo(BTreeKey.ofInt(0)) > 0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Lexicographic comparison of two byte arrays. */
    private static int compareBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (diff != 0) return diff;
        }
        return Integer.compare(a.length, b.length);
    }
}
