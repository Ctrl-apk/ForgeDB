package com.forgedb.wal;

import com.forgedb.common.ForgeDBException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Phase 1 WAL primitive: append/LSN correctness, sync,
 * reopen/replay, CRC validation, and safe handling of truncated or corrupt
 * tails.
 */
class WALManagerTest {

    @TempDir
    Path tempDir;

    private Path walPath(String name) {
        return tempDir.resolve(name);
    }

    // =========================================================================
    // Empty WAL
    // =========================================================================

    @Test
    @DisplayName("a freshly created WAL is empty; scan is clean with zero records")
    void emptyWal() throws Exception {
        try (WALManager wal = new WALManager(walPath("empty.wal"))) {
            WALManager.ScanResult scan = wal.readAll();
            assertTrue(scan.records().isEmpty());
            assertTrue(scan.isClean(), "empty file must scan clean");
            assertNull(scan.stopReason());
            assertEquals(0, scan.validEndOffset());
            assertEquals(0, wal.nextLsn(), "next LSN in an empty log is 0");
        }
    }

    // =========================================================================
    // Append + read back
    // =========================================================================

    @Test
    @DisplayName("append then readAll returns the identical record")
    void appendAndRead() throws Exception {
        try (WALManager wal = new WALManager(walPath("one.wal"))) {
            byte[] payload = "hello wal".getBytes("UTF-8");
            long lsn = wal.append(WALRecordType.BEGIN, 7, payload);

            assertEquals(0, lsn, "first record lands at offset 0");

            WALManager.ScanResult scan = wal.readAll();
            assertTrue(scan.isClean());
            assertEquals(1, scan.records().size());

            WALRecord r = scan.records().get(0);
            assertEquals(WALRecordType.BEGIN, r.type());
            assertEquals(7, r.txnId());
            assertEquals(-1, r.pageId());
            assertEquals(-1, r.pageLsn());
            assertArrayEquals(payload, r.payload());
            assertEquals(0, r.lsn());
        }
    }

    @Test
    @DisplayName("page-specific fields round-trip (pageId, pageLsn)")
    void pageFieldsRoundTrip() throws Exception {
        try (WALManager wal = new WALManager(walPath("page.wal"))) {
            wal.append(WALRecordType.HEAP_INSERT, 42, 3, 100, new byte[]{1, 2, 3});

            WALRecord r = wal.readAll().records().get(0);
            assertEquals(WALRecordType.HEAP_INSERT, r.type());
            assertEquals(42, r.txnId());
            assertEquals(3, r.pageId());
            assertEquals(100, r.pageLsn());
            assertArrayEquals(new byte[]{1, 2, 3}, r.payload());
        }
    }

    @Test
    @DisplayName("multiple records of different types replay in order")
    void multipleRecordsInOrder() throws Exception {
        try (WALManager wal = new WALManager(walPath("multi.wal"))) {
            wal.append(WALRecordType.BEGIN, 1, null);
            wal.append(WALRecordType.HEAP_INSERT, 1, 5, 16, new byte[]{9});
            wal.append(WALRecordType.COMMIT, 1, null);
            wal.append(WALRecordType.BEGIN, 2, null);
            wal.append(WALRecordType.HEAP_DELETE, 2, 5, 40, null);
            wal.append(WALRecordType.ROLLBACK, 2, null);

            WALManager.ScanResult scan = wal.readAll();
            assertTrue(scan.isClean());
            assertEquals(6, scan.records().size());

            List<WALRecord> rs = scan.records();
            assertEquals(WALRecordType.BEGIN,       rs.get(0).type());
            assertEquals(WALRecordType.HEAP_INSERT, rs.get(1).type());
            assertEquals(WALRecordType.COMMIT,      rs.get(2).type());
            assertEquals(WALRecordType.BEGIN,       rs.get(3).type());
            assertEquals(WALRecordType.HEAP_DELETE, rs.get(4).type());
            assertEquals(WALRecordType.ROLLBACK,    rs.get(5).type());

            // txn ids preserved per record
            assertEquals(1, rs.get(0).txnId());
            assertEquals(1, rs.get(1).txnId());
            assertEquals(1, rs.get(2).txnId());
            assertEquals(2, rs.get(3).txnId());
            assertEquals(2, rs.get(4).txnId());
            assertEquals(2, rs.get(5).txnId());
        }
    }

    // =========================================================================
    // LSN / file-offset correctness
    // =========================================================================

    @Test
    @DisplayName("LSNs are the byte offsets of their records; nextLsn tracks the end")
    void lsnEqualsFileOffset() throws Exception {
        try (WALManager wal = new WALManager(walPath("lsn.wal"))) {
            long l0 = wal.append(WALRecordType.BEGIN, 1, null);
            long l1 = wal.append(WALRecordType.COMMIT, 1, null);

            assertEquals(0, l0);

            // Record size for a BEGIN with empty payload:
            // 4 (length) + 20 (header) + 0 (payload) + 4 (CRC) = 28
            assertEquals(4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE, l1,
                "second record must start exactly after the first");
            // nextLsn is the position AFTER the second record (28 + 28 = 56).
            assertEquals(l1 + 4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE,
                wal.nextLsn());

            // A scan must agree: each record's lsn() is its byte offset and
            // validEndOffset is the end of the file.
            WALManager.ScanResult scan = wal.readAll();
            assertEquals(l0, scan.records().get(0).lsn());
            assertEquals(l1, scan.records().get(1).lsn());
            assertEquals(wal.nextLsn(), scan.validEndOffset());

            // Appending continues the sequence after the scan.
            long l2 = wal.append(WALRecordType.BEGIN, 2, null);
            assertTrue(l2 > l1);
        }
    }

    @Test
    @DisplayName("LSN spacing accounts for payload size")
    void lsnSpacingIncludesPayload() throws Exception {
        try (WALManager wal = new WALManager(walPath("spacing.wal"))) {
            byte[] small = new byte[10];
            byte[] big   = new byte[1000];

            long a = wal.append(WALRecordType.HEAP_INSERT, 1, 1, 0, small);
            long b = wal.append(WALRecordType.HEAP_INSERT, 1, 1, 0, big);
            long c = wal.append(WALRecordType.COMMIT, 1, null);

            assertEquals(0, a);
            assertEquals(a + 4 + WALManager.RECORD_HEADER_SIZE + small.length + WALManager.CRC_FIELD_SIZE, b);
            assertEquals(b + 4 + WALManager.RECORD_HEADER_SIZE + big.length + WALManager.CRC_FIELD_SIZE, c);
        }
    }

    // =========================================================================
    // Sync
    // =========================================================================

    @Test
    @DisplayName("sync/force complete without error and preserve the log")
    void syncWorks() throws Exception {
        try (WALManager wal = new WALManager(walPath("sync.wal"))) {
            wal.append(WALRecordType.BEGIN, 1, null);
            wal.sync();
            wal.append(WALRecordType.COMMIT, 1, null);
            wal.force();   // alias

            WALManager.ScanResult scan = wal.readAll();
            assertTrue(scan.isClean());
            assertEquals(2, scan.records().size());
        }
    }

    // =========================================================================
    // Reopen and replay
    // =========================================================================

    @Test
    @DisplayName("close and reopen: records replay and appends continue the LSN sequence")
    void reopenAndContinue() throws Exception {
        Path p = walPath("reopen.wal");

        long lastLsn;
        try (WALManager wal = new WALManager(p)) {
            wal.append(WALRecordType.BEGIN, 1, null);
            wal.append(WALRecordType.HEAP_INSERT, 1, 2, 30, new byte[]{5, 5, 5});
            wal.sync();
            lastLsn = wal.append(WALRecordType.COMMIT, 1, null);
        }

        try (WALManager wal2 = new WALManager(p)) {
            WALManager.ScanResult scan = wal2.readAll();
            assertTrue(scan.isClean());
            assertEquals(3, scan.records().size());
            assertEquals(WALRecordType.COMMIT, scan.records().get(2).type());

            // nextLsn recovered from the existing file — appends continue.
            assertEquals(lastLsn + 4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE,
                wal2.nextLsn());

            long newLsn = wal2.append(WALRecordType.BEGIN, 2, null);
            assertEquals(wal2.nextLsn(), newLsn + 4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE);

            assertEquals(4, wal2.readAll().records().size());
        }
    }

    // =========================================================================
    // Corruption detection
    // =========================================================================

    @Test
    @DisplayName("flipped payload byte: scan returns the clean prefix and reports corruption")
    void corruptedPayloadStopsScan() throws Exception {
        Path p = walPath("badpayload.wal");
        try (WALManager wal = new WALManager(p)) {
            wal.append(WALRecordType.BEGIN, 1, null);
            wal.append(WALRecordType.HEAP_INSERT, 1, 1, 0, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            wal.append(WALRecordType.COMMIT, 1, null);
        }

        // Flip one payload byte of the second record.
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            long secondStart = 4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE; // after record 1
            f.seek(secondStart + 4 + WALManager.RECORD_HEADER_SIZE);   // first payload byte
            int v = f.read();
            f.seek(secondStart + 4 + WALManager.RECORD_HEADER_SIZE);
            f.write(v ^ 0xFF);
        }

        try (WALManager wal = new WALManager(p)) {
            WALManager.ScanResult scan = wal.readAll();
            assertFalse(scan.isClean(), "scan must not silently accept corruption");
            assertEquals(1, scan.records().size(), "only the record before the damaged one replays");
            assertTrue(scan.stopReason().contains("CRC"));
            assertEquals(scan.records().get(0).lsn() + 4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE,
                scan.validEndOffset());
        }
    }

    @Test
    @DisplayName("corrupted header byte: CRC fails, no fabricated record is produced")
    void corruptedHeaderStopsScan() throws Exception {
        Path p = walPath("badheader.wal");
        try (WALManager wal = new WALManager(p)) {
            wal.append(WALRecordType.BEGIN, 1, null);
            wal.append(WALRecordType.COMMIT, 1, null);
        }

        // Overwrite the type field of the second record (offset 4 of it).
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            long secondStart = 4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE;
            f.seek(secondStart + 4);
            f.writeInt(0xDEADBEEF);
        }

        try (WALManager wal = new WALManager(p)) {
            WALManager.ScanResult scan = wal.readAll();
            assertFalse(scan.isClean());
            assertEquals(1, scan.records().size());
            String reason = scan.stopReason();
            assertTrue(reason.contains("CRC") || reason.contains("unknown record type"),
                "must fail on CRC or type, got: " + reason);
        }
    }

    @Test
    @DisplayName("unknown record type id stops the scan even if the CRC would pass")
    void unknownTypeIdStopsScan() throws Exception {
        // Hand-craft a record whose type id is outside the envelope but whose
        // CRC is correct: corruption must still never yield a record.
        Path p = walPath("badtype.wal");
        try (WALManager wal = new WALManager(p)) {
            wal.append(WALRecordType.BEGIN, 1, null);
        }

        int headerOnly = 4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE;
        byte[] fake = new byte[headerOnly];
        ByteBuffer buf = ByteBuffer.wrap(fake).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(headerOnly);          // length
        buf.putInt(999);                 // invalid type id
        buf.putLong(1);                  // txnId
        buf.putInt(-1);                  // pageId
        buf.putInt(-1);                  // pageLsn
        CRC32 crc = new CRC32();
        crc.update(fake, 4, headerOnly - 4 - WALManager.CRC_FIELD_SIZE);
        buf.putInt((int) crc.getValue());

        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE + 4); // end of record 1
            f.write(fake);
        }

        try (WALManager wal = new WALManager(p)) {
            WALManager.ScanResult scan = wal.readAll();
            assertFalse(scan.isClean());
            assertEquals(1, scan.records().size());
            assertTrue(scan.stopReason().contains("unknown record type"),
                "got: " + scan.stopReason());
        }
    }

    // =========================================================================
    // Truncation
    // =========================================================================

    @Test
    @DisplayName("torn tail (record cut in half) replays only the valid prefix")
    void tornTailStopsSafely() throws Exception {
        Path p = walPath("torn.wal");
        try (WALManager wal = new WALManager(p)) {
            wal.append(WALRecordType.BEGIN, 1, null);
            wal.append(WALRecordType.HEAP_INSERT, 1, 1, 0, new byte[32]);
            wal.sync();
        }

        // Cut the file in the middle of the second record.
        long fullSize;
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            fullSize = f.length();
        }
        long secondStart = 4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE;
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(secondStart + 10);   // 10 bytes of the second record survive
        }
        assertTrue(fullSize > secondStart + 10);

        try (WALManager wal = new WALManager(p)) {
            WALManager.ScanResult scan = wal.readAll();
            assertEquals(1, scan.records().size());
            assertFalse(scan.isClean());
            assertTrue(scan.stopReason().contains("incomplete") || scan.stopReason().contains("truncated"),
                "got: " + scan.stopReason());
            assertEquals(secondStart, scan.validEndOffset(),
                "valid end must be the boundary of the last complete record");

            // The log remains appendable; appends continue from the valid end.
            long lsn = wal.append(WALRecordType.COMMIT, 1, null);
            assertEquals(secondStart, lsn);
        }
    }

    @Test
    @DisplayName("length field alone survives (fewer than 4 bytes remain is handled)")
    void tinyTruncatedTailStopsSafely() throws Exception {
        Path p = walPath("tiny.wal");
        try (WALManager wal = new WALManager(p)) {
            wal.append(WALRecordType.BEGIN, 1, null);
        }

        // Leave 2 stray bytes past the last valid record.
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            long end = f.length();
            f.seek(end);
            f.write(0x12);
            f.write(0x34);
        }

        try (WALManager wal = new WALManager(p)) {
            WALManager.ScanResult scan = wal.readAll();
            assertEquals(1, scan.records().size());
            assertFalse(scan.isClean());
            assertTrue(scan.stopReason().contains("4 bytes"),
                "got: " + scan.stopReason());
        }
    }

    @Test
    @DisplayName("garbage length field (impossible size) stops the scan")
    void garbageLengthStopsSafely() throws Exception {
        Path p = walPath("garbage.wal");
        try (WALManager wal = new WALManager(p)) {
            wal.append(WALRecordType.BEGIN, 1, null);
        }

        // Overwrite the length field of a second, valid record with nonsense.
        try (WALManager wal = new WALManager(p)) {
            wal.append(WALRecordType.COMMIT, 1, null);
        }
        long secondStart = 4 + WALManager.RECORD_HEADER_SIZE + WALManager.CRC_FIELD_SIZE;
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(secondStart);
            f.writeInt(0x7FFFFFF0);   // absurd length, larger than the file
        }

        try (WALManager wal = new WALManager(p)) {
            WALManager.ScanResult scan = wal.readAll();
            assertEquals(1, scan.records().size());
            assertFalse(scan.isClean());
            assertTrue(scan.stopReason().contains("length"),
                "got: " + scan.stopReason());
        }
    }

    @Test
    @DisplayName("corrupt tail then re-append: only valid records ever replay")
    void malformedTailNeverYieldsRecords() throws Exception {
        Path p = walPath("malformed.wal");
        try (WALManager wal = new WALManager(p)) {
            wal.append(WALRecordType.BEGIN, 1, null);
        }

        // Append 40 bytes of pure garbage directly to the file.
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(f.length());
            byte[] junk = new byte[40];
            for (int i = 0; i < junk.length; i++) junk[i] = (byte) (0x30 + (i % 10));
            f.write(junk);
        }

        try (WALManager wal = new WALManager(p)) {
            WALManager.ScanResult scan = wal.readAll();
            assertEquals(1, scan.records().size(), "garbage must never surface as records");
            assertFalse(scan.isClean());

            // Recovery action: truncate to the valid end and continue.
            long validEnd = scan.validEndOffset();
            try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
                f.setLength(validEnd);
            }

            // Fresh manager over the truncated file: clean, appendable.
            try (WALManager wal2 = new WALManager(p)) {
                assertTrue(wal2.readAll().isClean());
                assertEquals(1, wal2.readAll().records().size());
                wal2.append(WALRecordType.COMMIT, 1, null);
                assertEquals(2, wal2.readAll().records().size());
            }
        }
    }

    // =========================================================================
    // Large payloads
    // =========================================================================

    @Test
    @DisplayName("a large payload (1 MiB) round-trips byte-exact")
    void largePayloadRoundTrip() throws Exception {
        byte[] big = new byte[1024 * 1024];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);

        try (WALManager wal = new WALManager(walPath("big.wal"))) {
            long lsn = wal.append(WALRecordType.PAGE_IMAGE, 9, 77, 1234, big);
            assertEquals(0, lsn);

            WALRecord r = wal.readAll().records().get(0);
            assertEquals(WALRecordType.PAGE_IMAGE, r.type());
            assertArrayEquals(big, r.payload());
            assertEquals(1_048_576, r.payloadLength());
        }
    }

    @Test
    @DisplayName("append rejects a payload whose record would exceed MAX_RECORD_SIZE")
    void oversizePayloadRejected() throws Exception {
        try (WALManager wal = new WALManager(walPath("huge.wal"))) {
            byte[] tooBig = new byte[WALManager.MAX_RECORD_SIZE]; // > max after framing
            assertThrows(ForgeDBException.class,
                () -> wal.append(WALRecordType.PAGE_IMAGE, 1, -1, -1, tooBig));

            // The log is untouched by the failed append.
            assertEquals(0, wal.readAll().records().size());
            assertEquals(0, wal.nextLsn());
        }
    }

    // =========================================================================
    // Resource management
    // =========================================================================

    @Test
    @DisplayName("operations after close fail; double close is harmless")
    void closeSemantics() throws Exception {
        WALManager wal = new WALManager(walPath("closed.wal"));
        wal.append(WALRecordType.BEGIN, 1, null);
        wal.close();
        assertDoesNotThrow(wal::close);          // idempotent

        assertThrows(ForgeDBException.class, () -> wal.append(WALRecordType.BEGIN, 2, null));
        assertThrows(ForgeDBException.class, wal::readAll);
        assertThrows(ForgeDBException.class, wal::sync);
    }

    @Test
    @DisplayName("null path is rejected")
    void nullPathRejected() {
        assertThrows(NullPointerException.class, () -> new WALManager(null));
    }
}
