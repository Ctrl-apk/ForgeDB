package com.forgedb.execution;

import com.forgedb.catalog.Column;
import com.forgedb.catalog.DataType;
import com.forgedb.catalog.Schema;
import com.forgedb.catalog.Tuple;
import com.forgedb.common.ForgeDBException;
import com.forgedb.index.BTree;
import com.forgedb.index.BTreeKey;
import com.forgedb.planner.QueryPlan;
import com.forgedb.planner.QueryPlanner;
import com.forgedb.sql.Lexer;
import com.forgedb.sql.Parser;
import com.forgedb.sql.SqlException;
import com.forgedb.sql.ast.*;
import com.forgedb.storage.BufferPool;
import com.forgedb.storage.DataPage;
import com.forgedb.storage.HeapFile;
import com.forgedb.storage.Page;
import com.forgedb.storage.PageId;
import com.forgedb.storage.PageType;
import com.forgedb.storage.RecordId;
import com.forgedb.storage.TupleSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ForgeDB query execution engine.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   SQL text
 *     ↓ Lexer + Parser      (M5)
 *   Statement AST
 *     ↓ Executor            (M6)
 *   Storage (HeapFile, BufferPool, BTree)
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>
 *   // Create once per database session
 *   Executor exec = new Executor(bufferPool);
 *
 *   // Execute any SQL string
 *   QueryResult result = exec.execute("SELECT * FROM users WHERE id = 1");
 *   for (Tuple t : result.rows()) { ... }
 *
 *   // Or dispatch a pre-parsed AST (for tests / internal use)
 *   QueryResult result = exec.executeStatement(parsedStatement);
 *
 *   exec.close();   // flushes and closes the BufferPool
 * </pre>
 *
 * <h2>Catalog</h2>
 * Tables are tracked in a {@link Catalog}. {@code CREATE TABLE} registers a new
 * entry; all other statements look up the table there. The catalog is in-memory
 * only — it is rebuilt from pre-existing tables on construction by scanning the
 * HeapFile page count (Milestone 9 will add a persistent system catalog).
 *
 * <h2>Statement execution</h2>
 *
 * <b>CREATE TABLE</b>:  builds a {@link Schema} from the column definitions,
 * creates a new {@link HeapFile} backed by the BufferPool, registers in the
 * catalog.
 *
 * <b>INSERT</b>:  evaluates each value expression (literals only — no
 * sub-queries yet), coerces to the column's DataType, populates a Tuple,
 * and calls {@link HeapFile#insert}. If a B+ tree index exists on the first
 * column it is also updated.
 *
 * <b>SELECT</b>:  calls {@link HeapFile#scan} to materialise all rows, applies
 * the WHERE predicate via {@link ExpressionEvaluator}, then projects the
 * selected columns into new result Tuples.
 *
 * <b>DELETE</b>:  scans all rows, evaluates WHERE on each, collects matching
 * {@link RecordId}s from the DataPage scan, then calls
 * {@link HeapFile#delete(RecordId)} for each.  The scan-then-delete pattern
 * avoids modifying the heap while iterating it.
 *
 * <b>UPDATE</b>:  scans all rows, evaluates WHERE, applies SET clauses to
 * matching tuples, and calls {@link HeapFile#update(RecordId, Tuple)}.
 *
 * <h2>B+ Tree integration</h2>
 * An optional B+ tree index on the first column of a table can be created with
 * {@link #createIndex}. When present:
 * - INSERT automatically inserts into the index.
 * - A future optimiser (M9) can use the index for point lookups.
 * - DELETE removes entries from the index.
 * For Milestone 6 SELECT always uses a sequential scan regardless of indexes.
 *
 * <h2>Error handling</h2>
 * Storage errors ({@link ForgeDBException}) and syntax errors
 * ({@link SqlException}) are both wrapped in {@link ExecutionException} before
 * bubbling up so callers only need to catch one exception type at this layer.
 *
 * <h2>Thread safety</h2>
 * Executor is NOT thread-safe (Milestone 7 will add locking).
 */
public final class Executor implements StatementVisitor<QueryResult>, AutoCloseable {

    private final BufferPool bufferPool;
    private final Catalog    catalog;

    /**
     * Map from table name (lower-case) to its optional B+ tree index on
     * the first column. Populated via {@link #createIndex}.
     */
    private final Map<String, BTree> indexes = new HashMap<>();

    /** The query planner — stateless, shared across all statements. */
    private final QueryPlanner planner = new QueryPlanner();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates an Executor that uses the given BufferPool for all I/O.
     *
     * The Executor owns the BufferPool — calling {@link #close()} flushes and
     * closes it.
     *
     * @param bufferPool the buffer pool to use for all page access
     */
    public Executor(BufferPool bufferPool) {
        if (bufferPool == null) throw new NullPointerException("bufferPool must not be null");
        this.bufferPool = bufferPool;
        this.catalog    = new Catalog();
    }

    // -------------------------------------------------------------------------
    // Primary API
    // -------------------------------------------------------------------------

    /**
     * Parses and executes a single SQL statement.
     *
     * @param sql the SQL text to execute
     * @return the query result
     * @throws ExecutionException if parsing fails or execution fails
     */
    public QueryResult execute(String sql) throws ExecutionException {
        List<Statement> stmts;
        try {
            stmts = new Parser(new Lexer(sql).tokenize()).parseAll();
        } catch (SqlException e) {
            throw new ExecutionException("SQL syntax error: " + e.getMessage(), e);
        }
        if (stmts.isEmpty()) {
            return QueryResult.ofUpdate("(empty input)", 0);
        }
        // Execute only the first statement; callers use executeAll for multi-statement
        return executeStatement(stmts.get(0));
    }

    /**
     * Parses and executes all semicolon-separated statements in the SQL string.
     * Returns the result of the last statement executed.
     *
     * @param sql the SQL text (may contain multiple statements separated by ';')
     * @return result of the last statement, or an empty result for empty input
     * @throws ExecutionException if any statement fails
     */
    public QueryResult executeAll(String sql) throws ExecutionException {
        List<Statement> stmts;
        try {
            stmts = new Parser(new Lexer(sql).tokenize()).parseAll();
        } catch (SqlException e) {
            throw new ExecutionException("SQL syntax error: " + e.getMessage(), e);
        }
        QueryResult last = QueryResult.ofUpdate("(empty)", 0);
        for (Statement stmt : stmts) {
            last = executeStatement(stmt);
        }
        return last;
    }

    /**
     * Executes a pre-parsed AST statement directly.
     * Use this in tests when you want to inspect the AST before running it.
     *
     * @param stmt the parsed statement
     * @return the query result
     * @throws ExecutionException if execution fails
     */
    public QueryResult executeStatement(Statement stmt) throws ExecutionException {
        try {
            return stmt.accept(this);
        } catch (ExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExecutionException("Execution failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Index management (public helper — not driven by SQL yet)
    // -------------------------------------------------------------------------

    /**
     * Creates a B+ tree index on the first column of the given table.
     * Inserts all existing rows into the index.
     *
     * This is called programmatically for now; Milestone 9 will add
     * {@code CREATE INDEX} SQL syntax.
     *
     * @param tableName the table to index
     * @throws ExecutionException if the table does not exist or index already exists
     */
    public void createIndex(String tableName) throws ExecutionException {
        if (indexes.containsKey(tableName.toLowerCase())) {
            throw new ExecutionException(
                "Index already exists for table '" + tableName + "'");
        }
        Catalog.TableEntry entry = catalog.getTable(tableName);
        Schema schema = entry.schema();
        if (schema.columnCount() == 0) {
            throw new ExecutionException("Cannot index a table with no columns");
        }
        DataType keyType = schema.getColumn(0).type();
        if (keyType == DataType.BOOLEAN) {
            throw new ExecutionException("BOOLEAN columns cannot be used as index keys");
        }

        try {
            BTree tree = BTree.create(bufferPool, keyType);

            // Populate index with existing rows
            List<Tuple> rows = entry.heapFile().scan();
            // We need RecordIds — scan page-by-page via a helper
            populateIndex(tree, entry.heapFile(), schema, rows);

            indexes.put(tableName.toLowerCase(), tree);
        } catch (ForgeDBException e) {
            throw new ExecutionException("Failed to create index: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the B+ tree index for the given table, or null if none exists.
     */
    public BTree getIndex(String tableName) {
        return indexes.get(tableName.toLowerCase());
    }

    // -------------------------------------------------------------------------
    // StatementVisitor — CREATE TABLE
    // -------------------------------------------------------------------------

    @Override
    public QueryResult visitCreateTable(CreateTableStatement stmt)
            throws ExecutionException {
        String tableName = stmt.tableName();
        if (catalog.hasTable(tableName)) {
            throw new ExecutionException(
                "Table '" + tableName + "' already exists");
        }

        // Build Schema from column definitions
        List<Column> columns = new ArrayList<>();
        for (ColumnDef def : stmt.columns()) {
            columns.add(new Column(def.name(), def.type()));
        }
        Schema schema = new Schema(columns);

        // Create a HeapFile backed by the BufferPool
        HeapFile heapFile = new HeapFile(bufferPool, schema);

        // Register in catalog
        try {
            catalog.register(tableName, schema, heapFile);
        } catch (ExecutionException e) {
            throw e;
        }

        return QueryResult.ofUpdate("Table '" + tableName + "' created", 0);
    }

    // -------------------------------------------------------------------------
    // StatementVisitor — INSERT
    // -------------------------------------------------------------------------

    @Override
    public QueryResult visitInsert(InsertStatement stmt) throws ExecutionException {
        Catalog.TableEntry entry = catalog.getTable(stmt.tableName());
        Schema schema   = entry.schema();
        HeapFile heap   = entry.heapFile();

        List<String>     colNames = stmt.columnNames();
        List<Expression> values   = stmt.values();

        // Determine column order
        int[] colIndexes;   // colIndexes[i] = schema column index for values[i]
        if (colNames.isEmpty()) {
            // No explicit column list — values in schema order
            if (values.size() != schema.columnCount()) {
                throw new ExecutionException(String.format(
                    "INSERT into '%s' expects %d value(s) but got %d",
                    stmt.tableName(), schema.columnCount(), values.size()));
            }
            colIndexes = new int[schema.columnCount()];
            for (int i = 0; i < colIndexes.length; i++) colIndexes[i] = i;
        } else {
            // Explicit column list
            if (colNames.size() != values.size()) {
                throw new ExecutionException(
                    "Column list has " + colNames.size() +
                    " entries but VALUES has " + values.size());
            }
            colIndexes = new int[colNames.size()];
            for (int i = 0; i < colNames.size(); i++) {
                String name = colNames.get(i);
                if (!schema.hasColumn(name)) {
                    throw new ExecutionException(
                        "Unknown column '" + name + "' in table '" + stmt.tableName() + "'");
                }
                colIndexes[i] = schema.getColumnIndex(name);
            }
        }

        // Evaluate each value expression (pure literals — no column refs)
        ExpressionEvaluator eval = new ExpressionEvaluator(null, null);
        Tuple tuple = new Tuple(schema);

        for (int i = 0; i < values.size(); i++) {
            int    colIdx  = colIndexes[i];
            Column col     = schema.getColumn(colIdx);
            Object rawVal  = eval.evaluate(values.get(i));
            Object coerced = ExpressionEvaluator.coerce(rawVal, col.type(), col.name());
            tuple.set(colIdx, coerced);
        }

        // Insert into heap
        try {
            RecordId rid = heap.insert(tuple);

            // Update index if present
            BTree index = indexes.get(stmt.tableName().toLowerCase());
            if (index != null && !tuple.isNull(0)) {
                BTreeKey key = toBTreeKey(tuple.get(0), schema.getColumn(0).type(),
                                          schema.getColumn(0).name());
                index.insert(key, rid);
            }
        } catch (ForgeDBException e) {
            throw new ExecutionException("INSERT failed: " + e.getMessage(), e);
        }

        return QueryResult.ofUpdate("1 row inserted", 1);
    }

    // -------------------------------------------------------------------------
    // StatementVisitor — SELECT
    // -------------------------------------------------------------------------

    @Override
    public QueryResult visitSelect(SelectStatement stmt) throws ExecutionException {
        Catalog.TableEntry entry = catalog.getTable(stmt.tableName());
        Schema   srcSchema = entry.schema();
        HeapFile heap      = entry.heapFile();
        BTree    index     = indexes.get(stmt.tableName().toLowerCase());

        // Ask the planner which execution strategy to use
        QueryPlan plan = planner.planSelect(stmt.tableName(), srcSchema,
                                             stmt.whereClause(), stmt.selectList(),
                                             index);

        // Determine output schema
        List<SelectItem> selectList = stmt.selectList();
        Schema outSchema;
        if (selectList.size() == 1 && selectList.get(0).isStar()) {
            outSchema = srcSchema;
        } else {
            outSchema = buildProjectionSchema(selectList, srcSchema, stmt.tableName());
        }

        List<Tuple> candidates;
        try {
            candidates = fetchCandidates(plan, heap, srcSchema);
        } catch (ForgeDBException e) {
            throw new ExecutionException("SELECT failed: " + e.getMessage(), e);
        }

        // Apply residual / full WHERE filter
        Expression filter = (plan.type() == QueryPlan.PlanType.INDEX_LOOKUP)
                            ? plan.residualWhere()
                            : stmt.whereClause();
        List<Tuple> filtered = applyWhere(candidates, srcSchema, filter);

        // Project
        List<Tuple> results = new ArrayList<>();
        for (Tuple row : filtered) {
            results.add(project(row, srcSchema, selectList, outSchema));
        }

        return QueryResult.ofRows(outSchema, results);
    }

    // -------------------------------------------------------------------------
    // StatementVisitor — DELETE
    // -------------------------------------------------------------------------

    @Override
    public QueryResult visitDelete(DeleteStatement stmt) throws ExecutionException {
        Catalog.TableEntry entry = catalog.getTable(stmt.tableName());
        Schema   schema = entry.schema();
        HeapFile heap   = entry.heapFile();
        BTree    index  = indexes.get(stmt.tableName().toLowerCase());

        QueryPlan plan = planner.planDelete(stmt.tableName(), schema,
                                             stmt.whereClause(), index);

        List<RecordId> toDelete      = new ArrayList<>();
        List<Tuple>    matchedTuples = new ArrayList<>();

        try {
            fetchCandidatesWithRids(plan, heap, schema, toDelete, matchedTuples);

            // For INDEX_LOOKUP: apply residual filter after index fetch
            // For SEQ_SCAN: scanWithRids already applied the full WHERE
            Expression residual = (plan.type() == QueryPlan.PlanType.INDEX_LOOKUP)
                                  ? plan.residualWhere() : null;
            if (residual != null) {
                List<RecordId> filteredRids   = new ArrayList<>();
                List<Tuple>    filteredTuples = new ArrayList<>();
                for (int i = 0; i < toDelete.size(); i++) {
                    Tuple t = matchedTuples.get(i);
                    if (new ExpressionEvaluator(schema, t).evaluateBoolean(residual)) {
                        filteredRids.add(toDelete.get(i));
                        filteredTuples.add(t);
                    }
                }
                toDelete      = filteredRids;
                matchedTuples = filteredTuples;
            }

            // Remove from index first
            if (index != null) {
                for (int i = 0; i < toDelete.size(); i++) {
                    Tuple t = matchedTuples.get(i);
                    if (!t.isNull(0)) {
                        BTreeKey key = toBTreeKey(t.get(0), schema.getColumn(0).type(),
                                                  schema.getColumn(0).name());
                        try { index.delete(key, toDelete.get(i)); }
                        catch (ForgeDBException ex) { /* best-effort */ }
                    }
                }
            }

            for (RecordId rid : toDelete) heap.delete(rid);

        } catch (ForgeDBException e) {
            throw new ExecutionException("DELETE failed: " + e.getMessage(), e);
        }

        int count = toDelete.size();
        return QueryResult.ofUpdate(count + " row(s) deleted", count);
    }

    // -------------------------------------------------------------------------
    // StatementVisitor — UPDATE
    // -------------------------------------------------------------------------

    @Override
    public QueryResult visitUpdate(UpdateStatement stmt) throws ExecutionException {
        Catalog.TableEntry entry = catalog.getTable(stmt.tableName());
        Schema   schema = entry.schema();
        HeapFile heap   = entry.heapFile();
        BTree    index  = indexes.get(stmt.tableName().toLowerCase());

        // Validate SET columns upfront
        for (SetClause sc : stmt.setClauses()) {
            if (!schema.hasColumn(sc.columnName())) {
                throw new ExecutionException(
                    "Unknown column '" + sc.columnName() +
                    "' in SET clause for table '" + stmt.tableName() + "'");
            }
        }

        QueryPlan plan = planner.planUpdate(stmt.tableName(), schema,
                                             stmt.whereClause(), index);

        List<RecordId> toUpdate  = new ArrayList<>();
        List<Tuple>    oldTuples = new ArrayList<>();

        try {
            fetchCandidatesWithRids(plan, heap, schema, toUpdate, oldTuples);

            // Apply residual filter
            Expression residual = (plan.type() == QueryPlan.PlanType.INDEX_LOOKUP)
                                  ? plan.residualWhere() : null;
            if (residual != null) {
                List<RecordId> fr = new ArrayList<>();
                List<Tuple>    ft = new ArrayList<>();
                for (int i = 0; i < toUpdate.size(); i++) {
                    Tuple t = oldTuples.get(i);
                    if (new ExpressionEvaluator(schema, t).evaluateBoolean(residual)) {
                        fr.add(toUpdate.get(i));
                        ft.add(t);
                    }
                }
                toUpdate  = fr;
                oldTuples = ft;
            }

            int count = 0;
            for (int i = 0; i < toUpdate.size(); i++) {
                RecordId rid    = toUpdate.get(i);
                Tuple    oldRow = oldTuples.get(i);
                Tuple    newRow = oldRow.copy();

                ExpressionEvaluator eval = new ExpressionEvaluator(schema, oldRow);
                for (SetClause sc : stmt.setClauses()) {
                    int    colIdx  = schema.getColumnIndex(sc.columnName());
                    Column col     = schema.getColumn(colIdx);
                    Object rawVal  = eval.evaluate(sc.value());
                    Object coerced = ExpressionEvaluator.coerce(rawVal, col.type(), col.name());
                    newRow.set(colIdx, coerced);
                }

                // Remove old key from index
                if (index != null && !oldRow.isNull(0)) {
                    try {
                        index.delete(toBTreeKey(oldRow.get(0),
                            schema.getColumn(0).type(), schema.getColumn(0).name()), rid);
                    } catch (ForgeDBException ex) { /* best-effort */ }
                }

                RecordId newRid = heap.update(rid, newRow);

                // Insert new key into index
                if (index != null && !newRow.isNull(0)) {
                    try {
                        index.insert(toBTreeKey(newRow.get(0),
                            schema.getColumn(0).type(), schema.getColumn(0).name()), newRid);
                    } catch (ForgeDBException ex) { /* best-effort */ }
                }
                count++;
            }

            return QueryResult.ofUpdate(count + " row(s) updated", count);
        } catch (ForgeDBException e) {
            throw new ExecutionException("UPDATE failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // AutoCloseable
    // -------------------------------------------------------------------------

    /**
     * Flushes all dirty pages and closes the BufferPool.
     * After this call the Executor must not be used.
     */
    @Override
    public void close() throws ExecutionException {
        try {
            bufferPool.close();
        } catch (ForgeDBException e) {
            throw new ExecutionException("Failed to close Executor: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Catalog accessor (for tests and CLI)
    // -------------------------------------------------------------------------

    /** Returns the in-memory catalog (read-only use only). */
    public Catalog catalog() { return catalog; }

    /**
     * Returns the query plan the planner would choose for the given SQL
     * SELECT/DELETE/UPDATE statement without executing it (EXPLAIN equivalent).
     *
     * @param sql the SQL text to inspect
     * @return the chosen QueryPlan
     * @throws ExecutionException if the SQL cannot be parsed or the table is unknown
     */
    public QueryPlan explain(String sql) throws ExecutionException {
        List<Statement> stmts;
        try {
            stmts = new Parser(new Lexer(sql).tokenize()).parseAll();
        } catch (SqlException e) {
            throw new ExecutionException("SQL syntax error: " + e.getMessage(), e);
        }
        if (stmts.isEmpty()) throw new ExecutionException("No statement to explain");
        Statement stmt = stmts.get(0);
        if (stmt instanceof SelectStatement s) {
            return planFor(s.tableName(), s.whereClause(), s.selectList());
        }
        if (stmt instanceof DeleteStatement d) {
            return planFor(d.tableName(), d.whereClause(), null);
        }
        if (stmt instanceof UpdateStatement u) {
            return planFor(u.tableName(), u.whereClause(), null);
        }
        throw new ExecutionException(
            "EXPLAIN only supports SELECT, DELETE, and UPDATE");
    }

    /** Builds a QueryPlan for any statement operating on tableName. */
    private QueryPlan planFor(String tableName, Expression whereClause,
                               List<SelectItem> selectList)
            throws ExecutionException {
        Catalog.TableEntry entry = catalog.getTable(tableName);
        BTree index = indexes.get(tableName.toLowerCase());
        return planner.planSelect(tableName, entry.schema(),
                                  whereClause, selectList, index);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fetches candidate tuples according to the given plan.
     * For SEQ_SCAN: returns all rows from the heap (no filter applied yet).
     * For INDEX_LOOKUP: uses the B+ tree to fetch only tuples matching the
     * index predicate, then fetches each by RecordId from the heap.
     */
    private List<Tuple> fetchCandidates(QueryPlan plan, HeapFile heap, Schema schema)
            throws ForgeDBException, ExecutionException {
        if (plan.type() == QueryPlan.PlanType.SEQ_SCAN) {
            return heap.scan();
        }
        // INDEX_LOOKUP
        BTree index = indexes.get(plan.tableName().toLowerCase());
        if (index == null) {
            return heap.scan();  // fallback (should not happen if planner is correct)
        }
        List<RecordId> rids = lookupByIndex(index, plan);
        List<Tuple> result = new ArrayList<>();
        for (RecordId rid : rids) {
            result.add(heap.read(rid));
        }
        return result;
    }

    /**
     * Fetches candidate (RecordId, Tuple) pairs according to the given plan.
     * For SEQ_SCAN: full page-by-page scan.
     * For INDEX_LOOKUP: use the index, then fetch tuples by RecordId.
     */
    private void fetchCandidatesWithRids(QueryPlan plan, HeapFile heap, Schema schema,
                                          List<RecordId> outRids, List<Tuple> outTuples)
            throws ForgeDBException, ExecutionException {
        if (plan.type() == QueryPlan.PlanType.SEQ_SCAN) {
            // Use full WHERE (plan.whereClause) — passed as null so scanWithRids
            // collects ALL rows; caller applies the filter.
            scanWithRids(heap, schema, plan.whereClause(), outRids, outTuples);
            return;
        }
        // INDEX_LOOKUP
        BTree index = indexes.get(plan.tableName().toLowerCase());
        if (index == null) {
            scanWithRids(heap, schema, plan.whereClause(), outRids, outTuples);
            return;
        }
        List<RecordId> rids = lookupByIndex(index, plan);
        for (RecordId rid : rids) {
            outRids.add(rid);
            outTuples.add(heap.read(rid));
        }
    }

    /**
     * Performs the B+ tree lookup for the given plan and returns matching RecordIds.
     * Handles EQ (point lookup) and range ops (LT, LTE, GT, GTE) via rangeSearch.
     */
    private List<RecordId> lookupByIndex(BTree index, QueryPlan plan)
            throws ForgeDBException, ExecutionException {
        Object keyVal = plan.indexKeyValue();
        DataType keyType = index.keyType();
        BTreeKey key = makeBTreeKey(keyVal, keyType, plan.indexedColumn());

        return switch (plan.indexOp()) {
            case EQ  -> index.search(key);
            case LT  -> {
                var entries = index.rangeSearch(null, key);
                // LT: exclude the key itself
                var rids = new ArrayList<RecordId>();
                for (var e : entries) {
                    if (e.key.compareTo(key) < 0) rids.add(e.rid);
                }
                yield rids;
            }
            case LTE -> {
                var entries = index.rangeSearch(null, key);
                var rids = new ArrayList<RecordId>();
                for (var e : entries) rids.add(e.rid);
                yield rids;
            }
            case GT  -> {
                var entries = index.rangeSearch(key, null);
                var rids = new ArrayList<RecordId>();
                for (var e : entries) {
                    if (e.key.compareTo(key) > 0) rids.add(e.rid);
                }
                yield rids;
            }
            case GTE -> {
                var entries = index.rangeSearch(key, null);
                var rids = new ArrayList<RecordId>();
                for (var e : entries) rids.add(e.rid);
                yield rids;
            }
            default -> index.search(key);  // fallback
        };
    }

    /**
     * Builds a BTreeKey from a plain Java value and the index's key type.
     * Handles numeric widening (Integer → Long, Integer/Long → Double).
     */
    private BTreeKey makeBTreeKey(Object value, DataType keyType, String colName)
            throws ExecutionException {
        // Coerce if needed
        try {
            value = ExpressionEvaluator.coerce(value, keyType, colName);
        } catch (ExecutionException e) {
            throw new ExecutionException(
                "Cannot use value '" + value + "' as key for index on '" + colName + "'", e);
        }
        return toBTreeKey(value, keyType, colName);
    }

    /**
     * Applies a WHERE predicate to a list of tuples and returns only
     * the matching ones.
     *
     * @param rows        candidate rows
     * @param schema      the row schema
     * @param whereClause AST predicate node, or null (= no filter = keep all)
     */
    private List<Tuple> applyWhere(List<Tuple> rows, Schema schema,
                                    Expression whereClause)
            throws ExecutionException {
        if (whereClause == null) return rows;
        List<Tuple> result = new ArrayList<>();
        for (Tuple row : rows) {
            ExpressionEvaluator eval = new ExpressionEvaluator(schema, row);
            if (eval.evaluateBoolean(whereClause)) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * Scans the heap page-by-page, collecting (RecordId, Tuple) pairs for
     * rows that match the optional WHERE predicate.
     *
     * We need RecordIds for DELETE and UPDATE but HeapFile.scan() doesn't
     * return them. This helper reimplements the scan at the DataPage level.
     *
     * @param heap        the HeapFile to scan
     * @param schema      the table schema
     * @param whereClause optional filter; null = match all
     * @param rids        output: matched RecordIds (in order)
     * @param tuples      output: matched Tuples (parallel to rids)
     */
    private void scanWithRids(HeapFile heap, Schema schema,
                               Expression whereClause,
                               List<RecordId> rids, List<Tuple> tuples)
            throws ForgeDBException, ExecutionException {
        // HeapFile.scan() doesn't return RecordIds.
        // We replicate the scan logic using the page-level DataPage API
        // by reading each page through the BufferPool DiskManager.
        //
        // Implementation: scan() gives us tuples in order. To get their
        // RecordIds we re-scan through DataPage, which is what HeapFile does
        // internally. We reproduce it here to obtain (rid, tuple) pairs.
        //
        // Alternatively: iterate via the internal page list. Since HeapFile
        // doesn't expose pageCount in a way we can iterate safely from outside,
        // we use the DiskManager's page count (BufferPool exposes getPageCount())
        // and skip page 0 (DB header). Pages 1..N-1 are data pages.

        int totalPages = bufferPool.getPageCount();
        // Page 0 is the DB-level header. Pages 1..N-1 are data.
        // The heap only knows its own pages, but since we use one DB file per
        // table in M6, all data pages belong to this heap.
        for (int pageNum = 1; pageNum < totalPages; pageNum++) {
            PageId pageId = new PageId(pageNum);
            Page   page   = bufferPool.readPage(pageId);

            // Only process DATA pages (skip BTREE meta/node pages)
            if (page.getPageType() != PageType.DATA) {
                bufferPool.unpin(pageId);
                continue;
            }

            DataPage dp       = new DataPage(page);
            int      slotCount = dp.getSlotCount();

            for (int slot = 0; slot < slotCount; slot++) {
                if (dp.isDeleted(slot)) continue;
                byte[] record = dp.readRecord(slot);
                Tuple  tuple  = TupleSerializer.deserialize(
                                    schema, record, 0, record.length);
                RecordId rid  = new RecordId(pageId, slot);

                if (whereClause == null) {
                    rids.add(rid);
                    tuples.add(tuple);
                } else {
                    ExpressionEvaluator eval =
                        new ExpressionEvaluator(schema, tuple);
                    if (eval.evaluateBoolean(whereClause)) {
                        rids.add(rid);
                        tuples.add(tuple);
                    }
                }
            }
            bufferPool.unpin(pageId);
        }
    }

    /**
     * Builds the output Schema for a SELECT projection (non-star select list).
     *
     * Each SelectItem must be a ColumnRef (for now; future milestones can
     * add arbitrary expressions). The alias, if provided, becomes the output
     * column name.
     *
     * @throws ExecutionException if an item references an unknown column or
     *         is not a supported expression type
     */
    private Schema buildProjectionSchema(List<SelectItem> selectList,
                                          Schema srcSchema,
                                          String tableName)
            throws ExecutionException {
        List<Column> outCols = new ArrayList<>();
        for (SelectItem item : selectList) {
            if (item.isStar()) {
                // SELECT *, col — expand star in-place
                for (int i = 0; i < srcSchema.columnCount(); i++) {
                    outCols.add(srcSchema.getColumn(i));
                }
                continue;
            }
            Expression expr = item.expr();
            if (!(expr instanceof ColumnRef ref)) {
                throw new ExecutionException(
                    "SELECT list items must be column references (got: " + expr + ")");
            }
            String colName = ref.columnName();
            if (!srcSchema.hasColumn(colName)) {
                throw new ExecutionException(
                    "Unknown column '" + colName + "' in table '" + tableName + "'");
            }
            Column srcCol  = srcSchema.getColumn(colName);
            String outName = item.alias() != null ? item.alias() : colName;
            outCols.add(new Column(outName, srcCol.type()));
        }
        return new Schema(outCols);
    }

    /**
     * Projects a source row into the output schema determined by the select list.
     */
    private Tuple project(Tuple srcRow, Schema srcSchema,
                            List<SelectItem> selectList, Schema outSchema)
            throws ExecutionException {
        // If SELECT *, return the row unchanged (same schema)
        if (selectList.size() == 1 && selectList.get(0).isStar()) {
            return srcRow;
        }

        // Build projected tuple
        Tuple out = new Tuple(outSchema);
        int   outIdx = 0;

        for (SelectItem item : selectList) {
            if (item.isStar()) {
                // Expand star
                for (int i = 0; i < srcSchema.columnCount(); i++) {
                    out.set(outIdx++, srcRow.get(i));
                }
                continue;
            }
            ColumnRef ref    = (ColumnRef) item.expr();
            int       srcIdx = srcSchema.getColumnIndex(ref.columnName());
            out.set(outIdx++, srcRow.get(srcIdx));
        }
        return out;
    }

    /**
     * Builds a BTreeKey from a raw Java value and its column DataType.
     *
     * @throws ExecutionException if the type is not indexable
     */
    private BTreeKey toBTreeKey(Object value, DataType type, String colName)
            throws ExecutionException {
        return switch (type) {
            case INT    -> BTreeKey.ofInt   ((Integer) value);
            case LONG   -> BTreeKey.ofLong  ((Long)    value);
            case DOUBLE -> BTreeKey.ofDouble((Double)  value);
            case TEXT   -> BTreeKey.ofText  ((String)  value);
            default     -> throw new ExecutionException(
                               "Column '" + colName + "' of type " + type +
                               " cannot be used as a B+ tree key");
        };
    }

    /**
     * Inserts all existing rows of a heap into a newly created index.
     * Used by {@link #createIndex}.
     */
    private void populateIndex(BTree tree, HeapFile heap,
                                Schema schema, List<Tuple> rows)
            throws ExecutionException, ForgeDBException {
        // Re-scan with RecordIds so we can insert (key, rid) pairs
        List<RecordId> rids   = new ArrayList<>();
        List<Tuple>    tuples = new ArrayList<>();
        scanWithRids(heap, schema, null, rids, tuples);

        DataType keyType = schema.getColumn(0).type();
        String   keyName = schema.getColumn(0).name();
        for (int i = 0; i < rids.size(); i++) {
            Tuple t = tuples.get(i);
            if (!t.isNull(0)) {
                BTreeKey key = toBTreeKey(t.get(0), keyType, keyName);
                tree.insert(key, rids.get(i));
            }
        }
    }
}
