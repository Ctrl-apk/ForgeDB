package com.forgedb.execution;

import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;

import java.util.List;

/**
 * The result of executing a SQL statement.
 *
 * Every statement produces a QueryResult, but the content differs by type:
 *
 *   CREATE TABLE  → status message, rowsAffected = 0, no rows
 *   INSERT        → status message, rowsAffected = 1, no rows
 *   SELECT        → column schema + rows, rowsAffected = row count
 *   DELETE        → status message, rowsAffected = deleted count, no rows
 *   UPDATE        → status message, rowsAffected = updated count, no rows
 *
 * For non-SELECT statements, {@link #rows()} returns an empty list and
 * {@link #schema()} returns null. Callers check {@link #isResultSet()} to
 * know whether rows are present.
 */
public final class QueryResult {

    private final boolean    isResultSet;
    private final Schema     schema;        // null for non-SELECT
    private final List<Tuple> rows;         // empty for non-SELECT
    private final int        rowsAffected;
    private final String     message;

    // -------------------------------------------------------------------------
    // Private constructor — use factories below
    // -------------------------------------------------------------------------

    private QueryResult(boolean isResultSet, Schema schema,
                        List<Tuple> rows, int rowsAffected, String message) {
        this.isResultSet  = isResultSet;
        this.schema       = schema;
        this.rows         = rows == null ? List.of() : List.copyOf(rows);
        this.rowsAffected = rowsAffected;
        this.message      = message;
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    /**
     * Creates a result for a non-SELECT statement (DDL or DML without rows).
     *
     * @param message      human-readable status, e.g. "Table 'users' created"
     * @param rowsAffected number of rows inserted / deleted / updated
     */
    public static QueryResult ofUpdate(String message, int rowsAffected) {
        return new QueryResult(false, null, null, rowsAffected, message);
    }

    /**
     * Creates a result for a SELECT statement that returned rows.
     *
     * @param schema the projected output schema (column names and types)
     * @param rows   the result tuples
     */
    public static QueryResult ofRows(Schema schema, List<Tuple> rows) {
        int count = rows == null ? 0 : rows.size();
        return new QueryResult(true, schema, rows, count,
                               count + " row" + (count == 1 ? "" : "s"));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns true if this result contains a row set (i.e. came from SELECT).
     * Returns false for INSERT / DELETE / UPDATE / CREATE TABLE.
     */
    public boolean isResultSet() { return isResultSet; }

    /**
     * The output schema for a SELECT result, or null for non-SELECT statements.
     */
    public Schema schema() { return schema; }

    /**
     * The result rows for a SELECT, or an empty list for other statements.
     */
    public List<Tuple> rows() { return rows; }

    /**
     * Number of rows returned (SELECT) or affected (INSERT/DELETE/UPDATE).
     * Always 0 for CREATE TABLE.
     */
    public int rowsAffected() { return rowsAffected; }

    /**
     * Human-readable status message suitable for display in a SQL shell.
     */
    public String message() { return message; }

    @Override
    public String toString() {
        if (isResultSet) {
            return "QueryResult[SELECT, " + rowsAffected + " row(s)]";
        }
        return "QueryResult[" + message + "]";
    }
}
