package com.forgedb.storage;

/**
 * Identifies the purpose of a page, stored as a 4-byte integer in the page
 * header at offset PAGE_HEADER_OFFSET_PAGE_TYPE.
 *
 * Storing the type directly in the page header means the DiskManager (or a
 * recovery process) can understand a page's role without consulting any other
 * data structure — important for crash recovery in Milestone 8.
 *
 * Types added in later milestones (BTREE_INTERNAL, BTREE_LEAF, etc.) will
 * extend this enum.
 */
public enum PageType {

    /** Page 0: database-level metadata (magic number, version, page count). */
    HEADER(0),

    /** A page that stores heap tuple data (Milestone 2). */
    DATA(1),

    /** A page that belongs to a B+ tree index (Milestone 4). */
    BTREE(2),

    /** A page that has been freed and is available for reuse. */
    FREE(3);

    private final int code;

    PageType(int code) {
        this.code = code;
    }

    /** The integer code written into the page header. */
    public int code() {
        return code;
    }

    /**
     * Resolves an integer code back to the corresponding PageType.
     *
     * @throws IllegalArgumentException if the code does not match any known type
     */
    public static PageType fromCode(int code) {
        for (PageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown page type code: " + code);
    }
}
