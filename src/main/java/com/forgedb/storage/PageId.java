package com.forgedb.storage;

/**
 * Immutable value object representing a logical page address.
 *
 * A PageId is simply a non-negative integer. Pages are laid out sequentially
 * in the database file, so the byte offset of a page is always:
 *
 *     offset = pageId.value() * Constants.PAGE_SIZE
 *
 * PageId is a distinct type (rather than a raw int) so that:
 *   1. The compiler catches cases where we accidentally pass a raw int where
 *      a page address is expected.
 *   2. It can serve as a key in the buffer pool's HashMap (Milestone 3).
 *   3. The validity invariant (non-negative) is enforced in one place.
 *
 * equals() and hashCode() are based solely on the integer value, making
 * PageId safe to use as a Map key.
 */
public final class PageId {

    private final int value;

    /**
     * @param value non-negative page number
     * @throws IllegalArgumentException if value is negative
     */
    public PageId(int value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                "Page ID must be non-negative, got: " + value);
        }
        this.value = value;
    }

    /** Returns the integer page number. */
    public int value() {
        return value;
    }

    /** Returns the byte offset of this page in the database file. */
    public long fileOffset() {
        return (long) value * com.forgedb.common.Constants.PAGE_SIZE;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PageId other)) return false;
        return this.value == other.value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    @Override
    public String toString() {
        return "PageId(" + value + ")";
    }
}
