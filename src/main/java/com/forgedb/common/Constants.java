package com.forgedb.common;

/**
 * Global constants shared across all ForgeDB components.
 *
 * PAGE_SIZE is the single most important constant in the system. Every read
 * and write to disk is exactly this many bytes. Changing it requires
 * reformatting the database file, so it is intentionally not runtime-
 * configurable in this milestone.
 *
 * MAGIC_NUMBER ("FORG" in ASCII) is written to the header page so we can
 * identify and reject files that are not ForgeDB databases, similar to how
 * SQLite uses 0x53514c69 ("SQLi") and PNG uses 0x89504e47.
 */
public final class Constants {

    private Constants() {
        // Utility class — no instances
    }

    // -------------------------------------------------------------------------
    // Storage
    // -------------------------------------------------------------------------

    /** Size of every page in bytes. Must be a power of two. */
    public static final int PAGE_SIZE = 4096;

    /**
     * Magic number written at byte 0 of the database file header page.
     * Spells "FORG" in ASCII: 0x46=F, 0x4F=O, 0x52=R, 0x47=G.
     */
    public static final int MAGIC_NUMBER = 0x464F5247;

    /** Database file format version. Increment when the on-disk format changes. */
    public static final int DB_VERSION = 1;

    // -------------------------------------------------------------------------
    // Page header layout (byte offsets within a page)
    // Every page starts with a 16-byte header, leaving PAGE_SIZE - 16 bytes
    // of usable payload for data.
    //
    //  Offset  Size  Field
    //  ------  ----  -----
    //   0       4    Page ID
    //   4       4    Page type  (see PageType enum)
    //   8       4    CRC32 checksum of bytes [12 .. PAGE_SIZE)
    //  12       4    LSN (Log Sequence Number) — reserved for WAL (Milestone 8)
    // -------------------------------------------------------------------------

    /** Byte offset of the page-ID field inside a page. */
    public static final int PAGE_HEADER_OFFSET_PAGE_ID   = 0;

    /** Byte offset of the page-type field inside a page. */
    public static final int PAGE_HEADER_OFFSET_PAGE_TYPE = 4;

    /** Byte offset of the CRC32 checksum field inside a page. */
    public static final int PAGE_HEADER_OFFSET_CHECKSUM  = 8;

    /** Byte offset of the LSN field inside a page (reserved for WAL). */
    public static final int PAGE_HEADER_OFFSET_LSN       = 12;

    /** Total size of the fixed page header in bytes. */
    public static final int PAGE_HEADER_SIZE = 16;

    /** Number of usable payload bytes per page (after the header). */
    public static final int PAGE_PAYLOAD_SIZE = PAGE_SIZE - PAGE_HEADER_SIZE;

    // -------------------------------------------------------------------------
    // Database file header page (page 0) payload layout
    // The payload region of page 0 stores database-level metadata.
    //
    //  Payload offset  Size  Field
    //  --------------  ----  -----
    //   0               4    Magic number (MAGIC_NUMBER)
    //   4               4    DB format version (DB_VERSION)
    //   8               4    Total page count (includes header page itself)
    //  12               4    Reserved
    // -------------------------------------------------------------------------

    /** Payload offset of the magic number inside the header page. */
    public static final int DB_HEADER_OFFSET_MAGIC      = 0;

    /** Payload offset of the format version inside the header page. */
    public static final int DB_HEADER_OFFSET_VERSION    = 4;

    /** Payload offset of the page count inside the header page. */
    public static final int DB_HEADER_OFFSET_PAGE_COUNT = 8;

    /** Page ID of the database file header page. Always 0. */
    public static final int HEADER_PAGE_ID = 0;
}
