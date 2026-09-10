package com.forgedb.storage;

import java.util.Objects;

/**
 * Uniquely identifies a single tuple within a HeapFile.
 *
 * A RecordId is a (pageId, slotIndex) pair:
 *   - pageId    — which page in the database file contains this record
 *   - slotIndex — which slot in that page's slot directory points to the record
 *
 * RecordIds are stable across updates to other records on the same page and
 * across deletions of other records. A RecordId becomes invalid only when the
 * record at that slot is itself deleted.
 *
 * RecordId is immutable and safe to use as a Map key (equals/hashCode based
 * entirely on the two fields).
 */
public final class RecordId {

    private final PageId pageId;
    private final int    slotIndex;

    /**
     * @param pageId    the page containing the record; must not be null
     * @param slotIndex zero-based slot index within the page; must be >= 0
     * @throws NullPointerException     if pageId is null
     * @throws IllegalArgumentException if slotIndex is negative
     */
    public RecordId(PageId pageId, int slotIndex) {
        this.pageId    = Objects.requireNonNull(pageId, "pageId must not be null");
        if (slotIndex < 0) {
            throw new IllegalArgumentException(
                "slotIndex must be >= 0, got: " + slotIndex);
        }
        this.slotIndex = slotIndex;
    }

    /** Returns the page that contains this record. */
    public PageId pageId() {
        return pageId;
    }

    /** Returns the zero-based slot index within the page. */
    public int slotIndex() {
        return slotIndex;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RecordId other)) return false;
        return slotIndex == other.slotIndex && pageId.equals(other.pageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, slotIndex);
    }

    @Override
    public String toString() {
        return "RecordId(" + pageId + ", slot=" + slotIndex + ")";
    }
}
