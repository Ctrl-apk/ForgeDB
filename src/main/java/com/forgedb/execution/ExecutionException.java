package com.forgedb.execution;

/**
 * Thrown by the query execution engine when a statement cannot be executed.
 *
 * Distinct from {@link com.forgedb.common.ForgeDBException} (storage I/O errors)
 * and {@link com.forgedb.sql.SqlException} (syntax errors). ExecutionException
 * represents semantic errors detected at runtime:
 *
 *   - unknown table name
 *   - unknown column name
 *   - type mismatch between a literal and a column's declared type
 *   - duplicate table name on CREATE TABLE
 *   - wrong number of values in INSERT
 *   - WHERE expression evaluated to a non-boolean result
 *
 * ExecutionException is checked so callers must handle or propagate it.
 */
public class ExecutionException extends Exception {

    public ExecutionException(String message) {
        super(message);
    }

    public ExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
