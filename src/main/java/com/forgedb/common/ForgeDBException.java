package com.forgedb.common;

/**
 * Base checked exception for all ForgeDB runtime errors.
 *
 * We use a checked exception (rather than RuntimeException) for storage-layer
 * errors because I/O failures are expected conditions that every caller must
 * consciously handle — they are not programming mistakes. Higher-level layers
 * (query engine, CLI) can catch and translate these into user-facing messages.
 *
 * Subclasses will be added in later milestones for more specific error
 * categories (e.g. ChecksumMismatchException, PageNotFoundException).
 */
public class ForgeDBException extends Exception {

    public ForgeDBException(String message) {
        super(message);
    }

    public ForgeDBException(String message, Throwable cause) {
        super(message, cause);
    }
}
