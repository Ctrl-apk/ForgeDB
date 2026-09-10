package com.forgedb.execution;

import com.forgedb.catalog.Schema;
import com.forgedb.storage.HeapFile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory registry that maps table names to their Schema and HeapFile.
 *
 * The Catalog is the single source of truth for what tables exist. The
 * Executor creates it at startup and registers each table after CREATE TABLE.
 *
 * This is an intentionally simple, non-persistent catalog. In a real
 * production system (Milestone 9+) the catalog itself would be stored in
 * a dedicated system table. For Milestone 6 the catalog lives only in memory,
 * meaning tables are only visible for the lifetime of the Executor instance.
 * Persistence of the data within each table is provided by HeapFile + DiskManager.
 *
 * Table names are stored and compared case-insensitively so that
 * {@code users}, {@code USERS}, and {@code Users} all refer to the same table,
 * matching standard SQL semantics.
 *
 * Thread safety: Catalog is NOT thread-safe. External synchronisation required
 * (Milestone 7).
 */
public final class Catalog {

    /**
     * Holds the schema and heap file for one table.
     */
    public static final class TableEntry {
        private final Schema   schema;
        private final HeapFile heapFile;

        TableEntry(Schema schema, HeapFile heapFile) {
            this.schema   = schema;
            this.heapFile = heapFile;
        }

        public Schema   schema()   { return schema; }
        public HeapFile heapFile() { return heapFile; }
    }

    /** Table name → entry. Keys are always lower-case for case-insensitive lookup. */
    private final Map<String, TableEntry> tables = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers a table in the catalog.
     *
     * @param tableName the table name (case-insensitive)
     * @param schema    the table's column schema
     * @param heapFile  the HeapFile that stores the table's rows
     * @throws ExecutionException if a table with this name already exists
     */
    public void register(String tableName, Schema schema, HeapFile heapFile)
            throws ExecutionException {
        String key = tableName.toLowerCase();
        if (tables.containsKey(key)) {
            throw new ExecutionException(
                "Table '" + tableName + "' already exists");
        }
        tables.put(key, new TableEntry(schema, heapFile));
    }

    // -------------------------------------------------------------------------
    // Lookup
    // -------------------------------------------------------------------------

    /**
     * Returns the catalog entry for the given table name.
     *
     * @param tableName table name (case-insensitive)
     * @throws ExecutionException if no table with this name exists
     */
    public TableEntry getTable(String tableName) throws ExecutionException {
        TableEntry entry = tables.get(tableName.toLowerCase());
        if (entry == null) {
            throw new ExecutionException(
                "Unknown table: '" + tableName + "'");
        }
        return entry;
    }

    /**
     * Returns true if a table with the given name is registered.
     *
     * @param tableName table name (case-insensitive)
     */
    public boolean hasTable(String tableName) {
        return tables.containsKey(tableName.toLowerCase());
    }

    /**
     * Returns the schema for the given table.
     *
     * @throws ExecutionException if the table does not exist
     */
    public Schema getSchema(String tableName) throws ExecutionException {
        return getTable(tableName).schema();
    }

    /**
     * Returns the HeapFile for the given table.
     *
     * @throws ExecutionException if the table does not exist
     */
    public HeapFile getHeapFile(String tableName) throws ExecutionException {
        return getTable(tableName).heapFile();
    }

    /**
     * Returns an unmodifiable view of all registered table names (lower-case).
     */
    public Set<String> tableNames() {
        return Collections.unmodifiableSet(tables.keySet());
    }
}
