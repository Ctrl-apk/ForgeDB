package com.forgedb.wal;

/**
 * The type of a WAL record. Identifies what the record's payload means and
 * how a future recovery pass must treat it.
 *
 * <h2>Phase 1 scope</h2>
 * Only the {@link #MIN_TYPE_ID} / {@link #MAX_TYPE_ID} validity envelope and
 * the enum constants' stable IDs matter now. Nothing interprets payloads yet:
 * no transaction manager, executor, or storage component writes or replays
 * WAL records in Phase 1.
 *
 * <h2>Planned types (reserved for later M9 phases — not yet written)</h2>
 * <ul>
 *   <li>{@code BEGIN (1)}            — transaction start; payload: empty</li>
 *   <li>{@code COMMIT (2)}           — commit decision point; payload: empty</li>
 *   <li>{@code ROLLBACK (3)}         — abort marker; payload: empty</li>
 *   <li>{@code HEAP_INSERT (4)}      — redo: insert record bytes at a slot;
 *                                      payload: slot + serialized tuple</li>
 *   <li>{@code HEAP_DELETE (5)}      — redo: mark slot deleted;
 *                                      payload: slot</li>
 *   <li>{@code PAGE_IMAGE (6)}       — full 4096-byte after-image of one page
 *                                      (fallback for coarse redo)</li>
 *   <li>{@code CHECKPOINT_BEGIN (7)} — start of a checkpoint</li>
 *   <li>{@code CHECKPOINT_END (8)}   — checkpoint complete; recovery may
 *                                      truncate/ignore older records</li>
 * </ul>
 *
 * Unknown or out-of-envelope type IDs make a record invalid — the scan stops
 * there rather than guessing. Keeping IDs stable is a compatibility promise
 * to recovery code written in later phases.
 */
public enum WALRecordType {

    /** Transaction started. No payload. */
    BEGIN(1),

    /** Transaction committed. No payload. The commit decision point. */
    COMMIT(2),

    /** Transaction rolled back. No payload. */
    ROLLBACK(3),

    /** Heap record inserted. Payload: slot id + serialized tuple bytes. */
    HEAP_INSERT(4),

    /** Heap record deleted. Payload: slot id. */
    HEAP_DELETE(5),

    /** Full-page after-image. Payload: one complete page image. */
    PAGE_IMAGE(6),

    /** Checkpoint started. Payload: none (future: active-txn table). */
    CHECKPOINT_BEGIN(7),

    /** Checkpoint completed. Payload: none (future: checkpoint details). */
    CHECKPOINT_END(8);

    /** The lowest valid type ID (any record below this is corrupt). */
    public static final int MIN_TYPE_ID = 1;

    /** The highest valid type ID (any record above this is corrupt). */
    public static final int MAX_TYPE_ID = 8;

    private final int id;

    WALRecordType(int id) {
        this.id = id;
    }

    /** The stable on-disk ID for this record type. */
    public int id() {
        return id;
    }

    /**
     * Maps an on-disk type ID to its enum constant.
     *
     * @return the matching constant, or {@code null} if the ID is outside the
     *         valid envelope (the caller must treat the record as corrupt)
     */
    public static WALRecordType fromId(int id) {
        if (id < MIN_TYPE_ID || id > MAX_TYPE_ID) {
            return null;
        }
        return values()[id - 1];
    }
}
