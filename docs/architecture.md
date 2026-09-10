# ForgeDB — Architecture

## Overview

ForgeDB is a relational database engine built from scratch in Java 21. Its purpose is to demonstrate and explain the internals of a production-quality database at every layer, from raw disk I/O up to SQL parsing and transaction management.

The system is structured as a strict layered stack. Each layer depends only on the layer directly below it. No layer skips levels.

```
┌─────────────────────────────────────┐
│              SQL Client             │  (user / application)
├─────────────────────────────────────┤
│           SQL Lexer / Parser        │  Milestone 5
├─────────────────────────────────────┤
│         Abstract Syntax Tree        │  Milestone 5
├─────────────────────────────────────┤
│           Query Planner             │  Milestone 6
├─────────────────────────────────────┤
│         Execution Engine            │  Milestone 6
├─────────────────────────────────────┤
│        Transaction Manager          │  Milestone 7
├─────────────────────────────────────┤
│   Write-Ahead Log (WAL) Manager     │  Milestone 8
├─────────────────────────────────────┤
│            Buffer Pool              │  Milestone 3
├─────────────────────────────────────┤
│     Access Methods (B+ Tree)        │  Milestone 4
├─────────────────────────────────────┤
│     Heap File / Tuple Store         │  Milestone 2
├─────────────────────────────────────┤
│          Disk Manager               │  Milestone 1  ← current
├─────────────────────────────────────┤
│           Storage (Disk)            │
└─────────────────────────────────────┘
```

---

## Milestone 1 — Storage Foundation

### Responsibility

Provide reliable, page-granular read and write access to a database file on disk. This is the only layer that touches the filesystem.

### Key concepts

**Page**
The smallest unit of I/O. Every read and write transfers exactly `PAGE_SIZE` bytes (4096 by default). This matches the OS virtual memory page size, which keeps I/O aligned and minimises read amplification.

**Page ID**
A non-negative integer. The byte offset of page N in the database file is `N × PAGE_SIZE`. This makes random access O(1) with no lookup tables.

**Page Header**
The first 16 bytes of every page contain structural metadata: page ID, page type, CRC32 checksum, and a reserved LSN field for WAL (Milestone 8). The remaining 4080 bytes are payload.

**Database Header Page (page 0)**
The very first page in the file stores database-level metadata: a magic number to identify ForgeDB files, a format version number, and the total page count. It is written on creation and updated on every page allocation.

**DiskManager**
A single interface that owns all file I/O. Nothing above this layer calls `RandomAccessFile`, `FileChannel`, or any filesystem API. This isolation makes the storage backend replaceable and the upper layers testable with mocks.

**Checksum (CRC32)**
Every page carries a CRC32 checksum over its payload. It is computed before every write and verified after every read. Mismatches indicate storage corruption or incomplete writes (torn pages) and surface as `ForgeDBException`.

### Classes

| Class | Location | Role |
|---|---|---|
| `Constants` | `common/` | PAGE_SIZE, magic number, header offsets |
| `ForgeDBException` | `common/` | Base checked exception for all I/O errors |
| `PageId` | `storage/` | Immutable value object wrapping a page number |
| `PageType` | `storage/` | Enum: HEADER, DATA, BTREE, FREE |
| `Page` | `storage/` | Fixed-size byte buffer with typed header accessors |
| `DiskManager` | `storage/` | Interface: readPage / writePage / allocatePage |
| `DiskManagerImpl` | `storage/` | RandomAccessFile-backed implementation |

### Design decisions

**Why `RandomAccessFile` in `"rwd"` mode?**
The `"d"` flag causes the OS to flush each write to the physical storage device before the call returns (equivalent to `fsync`). This prevents data loss on a process crash. The cost is higher write latency. In Milestone 8 we will switch to buffered writes with explicit `fsync` only at transaction commit boundaries — the WAL pattern used by PostgreSQL and SQLite.

**Why CRC32 and not MD5/SHA?**
CRC32 is fast, simple, and sufficient for detecting accidental corruption (bit rot, partial writes). It is not a cryptographic hash and does not protect against malicious tampering, which is not a requirement for a local embedded database.

**Why big-endian byte order?**
Big-endian is the network byte order and the choice made by most database file formats (PostgreSQL, MySQL). It makes hex dumps easier to read and avoids endian-specific behaviour when copying database files between machines.

---

---

## Milestone 8 — CLI, EXPLAIN, and Benchmark Harness

### Responsibility

Provide a human-usable interface to the database engine, expose the query planner's decisions via EXPLAIN, and measure the performance difference between sequential scan and index-backed lookup.

### CLI (`com.forgedb.cli`)

**`ForgeDBShell`** — interactive REPL. Reads SQL from stdin (or a `--file` script), dispatches each statement through `Executor.execute()`, and formats the result with `ResultPrinter`. Key features:

- Multi-line accumulation: lines buffered until `;` or blank line
- Meta-commands: `\q` / `exit`, `\?` / `help`, `\tables`
- EXPLAIN intercepted at the shell level — calls `Executor.explain()` instead of `execute()`
- Error handling: prints `ERROR: <message>`, does not terminate

**`ResultPrinter`** — stateless formatter. SELECT results render as a bordered ASCII table with dynamic column widths. Numbers are right-aligned; text is left-aligned; NULL is shown literally. Non-SELECT results print the message string.

### EXPLAIN

`Executor.explain(sql)` parses the SQL, builds a `QueryPlan` for it, and returns the plan without executing anything. `ForgeDBShell` calls this when the input starts with `EXPLAIN`.

Output examples:
```
SeqScan [ table=users, filter=(id = 42) ]
IndexLookup [ table=users, index=id, op=EQ, key=42, residual=none ]
IndexLookup [ table=users, index=id, op=EQ, key=5, residual=(age > 18) ]
```

### Benchmark harness (`com.forgedb.benchmark`)

**`BenchmarkResult`** — immutable result with Builder. Produces both CSV (machine-readable) and human-readable output. Metrics: `elapsedMs`, `avgUs/op`, `opsPerSec`, `rowsExamined`, `planType`.

**`BenchmarkRunner`** — four scenarios run against a temporary database file:

| Scenario | What it measures |
|---|---|
| `insert` | Heap insert throughput (rows/sec) |
| `seq_scan` | Full table scan, equality predicate, no index |
| `index_lookup_eq` | B+ tree point lookup, equality predicate |
| `index_lookup_range` | B+ tree range scan (~1% of table) |

Uses a fixed random seed (42) for reproducibility. Cleans up temp files. Writes `benchmark-results.csv` and `benchmark-results.txt`. Prints a speedup comparison table when both `seq_scan` and `index_lookup_eq` results are available.

**Running the benchmark:**
```bash
java -cp target/forgedb.jar com.forgedb.benchmark.BenchmarkRunner 10000 100
java -cp target/forgedb.jar com.forgedb.benchmark.BenchmarkRunner --suite
```

### Methodology and limitations

- Timings use `System.nanoTime()` (wall-clock, not CPU time).
- No JVM warm-up: first run includes JIT compilation overhead.
- Buffer pool: 256 pages — large tables will produce realistic I/O patterns.
- Results vary by hardware, JVM version, and OS scheduler.
- Smoke tests run with 100 rows to keep the Maven test suite fast. Real benchmarks use 10K–100K rows.

---

## Milestone 7 — Query Planner and Index-Accelerated Execution

### Responsibility

Inspect SELECT/UPDATE/DELETE WHERE predicates and choose the optimal execution strategy — either a B+ tree index lookup or a sequential heap scan — before handing off to the executor. Produce an EXPLAIN-style plan representation for debugging.

### Architecture

```
SQL text
    ↓ Lexer + Parser      (M5)
Statement AST
    ↓ QueryPlanner        (M7 — new)
QueryPlan
    ↓ Executor            (M6 — updated)
    ├─ INDEX_LOOKUP: BTree.search/rangeSearch → RecordId list → HeapFile.read per rid
    └─ SEQ_SCAN: HeapFile.scan / scanWithRids → full table read
Storage
```

### Classes

| Class | Package | Role |
|---|---|---|
| `QueryPlan` | `planner` | Immutable plan descriptor: type (SEQ_SCAN / INDEX_LOOKUP), table, index column, op, key value, residual predicate. Provides `explain()`. |
| `QueryPlanner` | `planner` | Stateless rule-based planner. `planSelect/planDelete/planUpdate` inspect the WHERE AST and return a QueryPlan. |
| `PredicateInfo` | `planner` (package-private) | Result of predicate analysis: is it indexable? Which column, op, literal value, residual? |

`Executor` was updated to: import `QueryPlanner`; add `planner` field; add `explain(sql)` public method; route `visitSelect`/`visitDelete`/`visitUpdate` through `planFor()` which asks the planner; use `fetchCandidates` / `fetchCandidatesWithRids` that dispatch on `PlanType`.

### How the planner chooses index vs sequential scan

```
1. No WHERE clause?           → SEQ_SCAN
2. No index on this table?    → SEQ_SCAN
3. Inspect WHERE expression:

   Simple comparison (col op literal) or (literal op col):
     - col is column 0 (the indexed column)?     Yes → candidate
     - op is one of EQ LT LTE GT GTE?            Yes → INDEX_LOOKUP
     - op is NEQ?                                → SEQ_SCAN (not worth it)

   AND conjunction (A AND B):
     - Recursively check each side for a simple index predicate
     - First indexable side → index pred; other side → residual predicate
     - Residual is evaluated tuple-by-tuple after index fetch

   OR, NOT, complex nested expressions → SEQ_SCAN

4. Literal on left side (e.g. 5 = id or 10 > id):
     Operator is flipped: 5 = id → id = 5, 10 > id → id < 10
```

**Priority:** equality (EQ) gets the tightest point lookup (`BTree.search`). Range operators use `BTree.rangeSearch` with one or both bounds.

### B+ Tree and RecordId integration

| Plan | Execution path |
|---|---|
| `EQ` | `BTree.search(key)` → `List<RecordId>` → `heap.read(rid)` per RecordId |
| `LT` | `BTree.rangeSearch(null, key)`, then filter `key < bound` |
| `LTE` | `BTree.rangeSearch(null, key)` — all entries up to and including key |
| `GT` | `BTree.rangeSearch(key, null)`, then filter `key > bound` |
| `GTE` | `BTree.rangeSearch(key, null)` — all entries at and above key |

The residual WHERE (if any, from an AND conjunction) is applied after the index delivers its tuples, so it never touches the index itself.

---

## Milestone 6 — Query Execution Engine

### Responsibility

Bridge the SQL AST (Milestone 5) to the storage layer (M1–M4). Take a parsed `Statement` tree, execute it against the HeapFile and BufferPool, and return a `QueryResult`.

### Architecture

```
SQL text (String)
    ↓ Lexer + Parser    (M5 — unchanged)
Statement AST
    ↓ Executor          (M6)
    ├─ ExpressionEvaluator  — evaluates WHERE / SET / VALUES expressions
    ├─ Catalog              — in-memory table registry (name → Schema + HeapFile)
    ├─ HeapFile             (M2 — unchanged)
    ├─ BufferPool           (M3 — unchanged)
    └─ BTree                (M4 — unchanged, used by createIndex)
QueryResult
```

The executor **never** imports the Lexer, Parser, or AST directly in the storage path — it receives a `Statement` interface and dispatches via the `StatementVisitor` pattern.

### Classes

| Class | Package | Role |
|---|---|---|
| `ExecutionException` | `execution` | Checked exception for semantic errors (unknown table, type mismatch, etc.) |
| `QueryResult` | `execution` | Result of any statement: rows+schema for SELECT, rowsAffected+message for DML/DDL |
| `Catalog` | `execution` | In-memory map of table name → `TableEntry(Schema, HeapFile)`. Case-insensitive. |
| `ExpressionEvaluator` | `execution` | Implements `ExpressionVisitor<Object>`, evaluates expressions against a row context |
| `Executor` | `execution` | Implements `StatementVisitor<QueryResult>`, the central execution entry point |

### How AST nodes map to execution

| AST node | Execution |
|---|---|
| `CreateTableStatement` | Build `Schema` from `ColumnDef` list → new `HeapFile(bufferPool, schema)` → `catalog.register()` |
| `InsertStatement` | Evaluate each value `Expression` → `ExpressionEvaluator.coerce()` to column type → `HeapFile.insert()` → optionally `BTree.insert()` |
| `SelectStatement` | `HeapFile.scan()` → apply WHERE via `evaluateBoolean()` → project columns → `QueryResult.ofRows()` |
| `DeleteStatement` | `scanWithRids()` to get `(RecordId, Tuple)` pairs → filter WHERE → `BTree.delete()` + `HeapFile.delete()` per rid |
| `UpdateStatement` | `scanWithRids()` → filter WHERE → apply `SetClause` expressions → `HeapFile.update()` → update BTree index |

### scanWithRids: the key design decision

`HeapFile.scan()` returns `List<Tuple>` but not their `RecordId`s. DELETE and UPDATE need RecordIds. The executor reimplements the scan at the `DataPage` level (iterating `bufferPool.getPageCount()` pages, skipping non-DATA pages) to produce `(RecordId, Tuple)` pairs without modifying `HeapFile`.

### ExpressionEvaluator

Implements `ExpressionVisitor<Object>`. Returns boxed Java values (Integer, Long, Double, Boolean, String, null). Key behaviours:

- **Column ref**: looks up `schema.getColumnIndex(name)`, returns `tuple.get(idx)`
- **Numeric widening**: when comparing Integer vs Double (or Long vs Double), promotes both to Double
- **Short-circuit AND/OR**: FALSE AND x = FALSE without evaluating x; TRUE OR x = TRUE
- **NULL propagation**: any comparison involving NULL returns null, treated as false by `evaluateBoolean()`
- **`coerce(value, DataType, colName)`**: static helper that widens INT→LONG, INT/LONG→DOUBLE for INSERT/UPDATE

### B+ Tree integration

`createIndex(tableName)` creates a `BTree` on the first column. INSERT/DELETE/UPDATE maintain it automatically when an index exists. SELECT still uses full table scan in M6 — index-accelerated lookup arrives in the query planner (M9).

### Error hierarchy

```
Exception
  ├── ForgeDBException    (M1 — storage I/O errors)
  ├── SqlException        (M5 — syntax errors)
  └── ExecutionException  (M6 — semantic runtime errors)
```

`Executor.execute()` wraps `SqlException` and `ForgeDBException` inside `ExecutionException` so callers need only one catch at the application layer.

---

## Milestone 5 — SQL Lexer, Parser, and AST

### Responsibility

Convert raw SQL text into a structured Abstract Syntax Tree (AST) that the query execution engine (Milestone 6) can walk and execute. This layer is purely syntactic — it never touches storage, the buffer pool, or the B+ tree.

### Three-layer architecture

```
SQL text (String)
    ↓
Lexer           tokenize()  →  List<Token>
    ↓
Parser          parse()     →  Statement (AST root)
    ↓
AST             (data only — no execution logic)
```

Each layer is a separate class in a separate package. The Parser depends only on the token stream; it never imports anything from `storage`, `catalog` (except `DataType`), `index`, or `common`.

### Lexer (`com.forgedb.sql.Lexer`)

Single-pass character scanner. Produces a `List<Token>`, always ending with `EOF`.

**Token types** (`TokenType` enum, 47 values):
- SQL keywords: `CREATE TABLE INSERT INTO VALUES SELECT FROM WHERE DELETE UPDATE SET AND OR NOT NULL TRUE FALSE AS BY ORDER IS DROP`
- Type keywords: `INT LONG BOOLEAN DOUBLE TEXT`
- Literals: `INTEGER_LITERAL DOUBLE_LITERAL STRING_LITERAL`
- Operators: `EQ NEQ LT LTE GT GTE PLUS MINUS`
- Punctuation: `COMMA SEMICOLON LPAREN RPAREN STAR DOT`
- `IDENT EOF`

**Key rules:**
- Keywords matched case-insensitively via a `HashMap<String, TokenType>` keyed on lowercased word
- `'-'` immediately followed by a digit → scanned as part of the number literal; `'- '5'` (with space) → separate `MINUS` + `INTEGER_LITERAL`
- A decimal point in a number → `DOUBLE_LITERAL`
- Single-quoted strings: surrounding quotes stripped, `''` unescaped to `'`
- `--` single-line comments skipped to end of line
- Every token carries `line` and `col` (1-based) for error reporting

### Parser (`com.forgedb.sql.Parser`)

Hand-written recursive-descent parser. No parser-generator libraries.

**Entry points:**
- `parse()` — reads exactly one statement (optional trailing `;`)
- `parseAll()` — reads all semicolon-separated statements until EOF

**Grammar (informal):**
```
statement  ::= createTable | insert | select | delete | update

createTable ::= CREATE TABLE ident '(' colDef (',' colDef)* ')' ';'?
colDef      ::= ident (INT|LONG|BOOLEAN|DOUBLE|TEXT)

insert      ::= INSERT INTO ident ['(' identList ')'] VALUES '(' exprList ')' ';'?

select      ::= SELECT ('*' | selectItem (',' selectItem)*) FROM ident ['WHERE' expr] ';'?
selectItem  ::= expr ['AS' ident]

delete      ::= DELETE FROM ident ['WHERE' expr] ';'?

update      ::= UPDATE ident SET setClause (',' setClause)* ['WHERE' expr] ';'?
setClause   ::= ident '=' expr

expr        ::= orExpr
orExpr      ::= andExpr  ('OR'  andExpr)*
andExpr     ::= notExpr  ('AND' notExpr)*
notExpr     ::= 'NOT' notExpr | cmpExpr
cmpExpr     ::= primary (('='|'<>'|'<'|'<='|'>'|'>=') primary)?
primary     ::= literal | columnRef | '(' expr ')'
```

**Expression precedence** (low → high): `OR → AND → NOT → comparison → primary`

**Error handling:** every `consume()` call produces a `SqlException` with the token's position (`line`, `col`) and the offending token value. Example: `Expected FROM near 'users' [line 1, col 8]`.

**Integer auto-promotion:** an `INTEGER_LITERAL` that fits in `int` → `IntLiteral`; one that exceeds `Integer.MAX_VALUE` → `LongLiteral`.

### AST (`com.forgedb.sql.ast`)

Pure data tree — no execution logic anywhere.

| Node | Description |
|---|---|
| `Statement` | Marker interface; all statement nodes implement it |
| `StatementVisitor<T>` | Visitor for the query engine (Milestone 6) |
| `Expression` | Base interface for all expression nodes |
| `ExpressionVisitor<T>` | Visitor for expression evaluation |
| `CreateTableStatement` | `tableName`, `List<ColumnDef>` |
| `InsertStatement` | `tableName`, `columnNames`, `List<Expression> values` |
| `SelectStatement` | `List<SelectItem>`, `tableName`, `whereClause` |
| `DeleteStatement` | `tableName`, `whereClause` |
| `UpdateStatement` | `tableName`, `List<SetClause>`, `whereClause` |
| `ColumnDef` | `name`, `DataType` |
| `SelectItem` | `isStar`, `expr`, `alias` |
| `SetClause` | `columnName`, `value` |
| `IntLiteral` | `int value` |
| `LongLiteral` | `long value` |
| `DoubleLiteral` | `double value` |
| `StringLiteral` | `String value` (quotes stripped) |
| `BoolLiteral` | `boolean value` |
| `NullLiteral` | singleton |
| `ColumnRef` | `tableName` (nullable), `columnName` |
| `BinaryExpression` | `left`, `Op` (EQ/NEQ/LT/LTE/GT/GTE/AND/OR), `right` |
| `UnaryExpression` | `Op` (NOT), `operand` |

Both `StatementVisitor` and `ExpressionVisitor` use generics and declare `throws Exception` so Milestone 6 can return any result type and propagate `ForgeDBException`.

### Design decision: `SqlException` separate from `ForgeDBException`

`SqlException` is a distinct checked exception rather than a subclass of `ForgeDBException`. This keeps the SQL frontend decoupled from the storage layer — the parser doesn't import anything from `common` except indirectly through `DataType`. The query engine (Milestone 6) will bridge between the two exception hierarchies.

---

## Milestone 4 — B+ Tree Index

### Responsibility

Provide a disk-backed B+ Tree index that maps typed keys to `RecordId` values (pointers into a HeapFile). Supports point search, duplicate keys, deletion with rebalancing, and range scans via leaf linked-list traversal. All I/O goes through the BufferPool.

### Key concepts

**B+ Tree structure**
All data lives in leaf nodes. Internal nodes only store separator keys and child pointers. Leaf nodes form a doubly-linked list (actually singly-linked via `nextLeaf`) enabling range scans without traversing the tree again after finding the start leaf.

**Metadata page**
Each tree allocates one metadata page (payload: rootPageId, keyType ordinal, order, height). The `metaPageId` is the handle needed to reopen a persisted tree.

**Splits**
When a leaf reaches `order + 1` entries it splits: left keeps the first `ceil((order+1)/2)`, right gets the rest. The right leaf's first key is pushed up to the parent. Internal nodes split symmetrically with the middle key pushed up. Root splits create a new root and increment the tree height.

**Deletion and rebalancing**
After removing an entry, if a leaf has fewer than `ceil(order/2)` keys, the tree tries to borrow from a sibling (redistribution), then falls back to merging two siblings. Merge pulls the separator key down from the parent and removes it, which may propagate underflow upward to the root.

**Duplicate keys**
Multiple `(key, RecordId)` pairs with the same key are stored as consecutive leaf entries. Search collects all matches, including those spanning into the next leaf via the linked list.

**Order and capacity**
`order = floor((PAGE_PAYLOAD_SIZE - HDR_SIZE) / entrySize) - 1`. The `-1` is critical: the page must hold `order + 1` entries to trigger a split without overflowing, so one slot must remain vacant at the point of split.

For INT keys: `order = floor(4064 / 12) - 1 = 337`.

### Classes

| Class | Package | Role |
|---|---|---|
| `BTreeKey` | `index` | Immutable comparable key: INT/LONG/DOUBLE/TEXT with serialize/deserialize |
| `BTreeNode` | `index` | Page-level node: reads/writes leaf and internal node entries via Page API |
| `BTree` | `index` | Tree logic: create/open, insert, search, delete, range scan |

### Page layout

**All B+ tree pages** use `PageType.BTREE` (code 2, already defined in M1).

Node header (16 bytes at payload offset 0):
```
[0..3]  nodeType  — 0=INTERNAL, 1=LEAF
[4..7]  keyCount  — number of keys stored
[8..11] parentId  — -1 if this node is the root
[12..15] nextLeaf — -1 if internal or last leaf; else PageId of right sibling
```

Leaf body (payload offset 16 onwards):
```
entry[i] = key_bytes | rid.pageId(4) | rid.slot(4)
```

Internal body (payload offset 16 onwards):
```
child(4) | key_bytes | child(4) | key_bytes | ... | child(4)
child count = keyCount + 1
```

### Key serialisation

| Type | Bytes | Notes |
|---|---|---|
| INT | 4 | big-endian signed int |
| LONG | 8 | big-endian signed long |
| DOUBLE | 8 | IEEE 754 with sign-magnitude transform for correct sort order |
| TEXT | 4 + N | 4-byte length prefix + N bytes UTF-8 |

DOUBLE transform: `stored = (bits < 0) ? ~bits : (bits ^ Long.MIN_VALUE)`. This makes the unsigned byte ordering of the 8-byte value match the numeric ordering of the original double, including negative values, ±0, ±∞, and NaN.

### Design decisions

**Why order-1 in capacity formula?** The page must hold `order + 1` entries momentarily (before the split code fires). Computing `order = capacity` means the page overflows on the `order+1`-th insert. Subtracting 1 reserves that slot.

**Why implement `BTree.open(pool, metaPageId)`?** The metadata page stores everything needed to reconstruct the in-memory state: root, key type, order, height. This is the minimal persistent handle — no separate catalog table needed in Milestone 4.

**Why not use PageType.BTREE_INTERNAL / BTREE_LEAF?** `PageType.BTREE(2)` was already defined in M1 for exactly this purpose. The node type (internal vs leaf) is stored in the first 4 bytes of the node header, which is more flexible than using separate `PageType` codes.

---

## Milestone 3 — Buffer Pool

### Responsibility

Cache database pages in memory so that repeated access to the same page does not require disk I/O. The buffer pool sits between all upper-layer code (HeapFile, and later the query engine) and the DiskManager.

### Key concepts

**Frame**
A single slot in the buffer pool. Each frame holds one `Page`, the `PageId` it maps to, and a pin count. When a caller pins a page (via `readPage`), the pin count increments. When done, the caller calls `unpin`. The pool never evicts a pinned frame.

**Pin / Unpin protocol**
Every `readPage` call increments the pin count. Every caller must call `unpin` when finished. Failing to unpin eventually exhausts all frames, causing a `ForgeDBException("all frames are pinned")`. This models how real databases (PostgreSQL, MySQL) manage buffer frames.

**Dirty flag**
A page is dirty when its in-memory content differs from the last version written to disk. `Page.isDirty()` is set automatically by any payload mutator (`putByte`, `putInt`, etc.). On eviction, dirty frames are flushed to disk before the frame is reused. `flushAll()` is called by `close()` to ensure no dirty data is lost.

**LRU eviction via `LinkedHashMap`**
`LinkedHashMap(capacity, 0.75f, accessOrder=true)` maintains insertion order that changes on every access: the most-recently-used entry moves to the tail. When eviction is needed, we iterate from the head (oldest) and select the first unpinned frame. This gives O(1) move-to-tail and O(evicted frames) scan for the victim — identical in principle to the Clock algorithm used by PostgreSQL.

**Write-through on `writePage`**
`BufferPool.writePage` forwards immediately to `DiskManager.writePage`, which stamps the checksum and clears the dirty flag. If the page is also in the cache its frame is updated. This avoids a stale cache problem where the on-disk page differs from the in-memory frame.

**Implements `DiskManager`**
`BufferPool` implements the `DiskManager` interface, so it can be passed as-is to `HeapFile(DiskManager, Schema)` without changing any existing code. `allocatePage` and `getPageCount` are delegated to the underlying `DiskManagerImpl`.

### Classes

| Class | Package | Role |
|---|---|---|
| `BufferPool` | `storage` | LRU page cache; implements `DiskManager` |
| `BufferPool.Frame` | `storage` (static inner) | One pool slot: Page + PageId + pinCount |

### Design decisions

**Why `LinkedHashMap` instead of a manual doubly-linked list?**
A manual LRU list is the textbook approach and gives the most explicit control. `LinkedHashMap(accessOrder=true)` provides the same O(1) move-to-tail on access with less code to maintain. Both are equally correct; we chose `LinkedHashMap` for readability.

**Why implement `DiskManager` rather than wrapping it with a separate interface?**
`HeapFile` already holds a `DiskManager` reference. Making `BufferPool` implement that interface means zero changes to `HeapFile` or any other existing component. If we had introduced a new `PageCache` interface, every existing caller would need updating.

**Why write-through instead of write-back on `writePage`?**
Write-back (defer writes until eviction) is more efficient but requires the WAL (Milestone 8) to be correct — you must not flush a dirty page before its log record is on disk. Write-through is safer before WAL exists and matches the Milestone 1 design where `DiskManagerImpl` uses `"rwd"` mode (synchronous flush per write).

---

## Milestone 2 — Schema, Tuple, and Heap File

### Responsibility

Provide a typed record layer above raw pages. Define what data means (Schema), how a row looks in memory (Tuple), how it is encoded on disk (TupleSerializer), how multiple records are packed into a page (DataPage), and how a logical table spans multiple pages (HeapFile).

### Key concepts

**Schema / Column / DataType**
A Schema is an ordered list of named, typed Columns. Column types are `INT` (4 bytes), `LONG` (8 bytes), `BOOLEAN` (1 byte), `DOUBLE` (8 bytes), and `TEXT` (variable, UTF-8). Schema lives in the `catalog` package because it describes the logical meaning of data, not how it is stored.

**Tuple**
An in-memory row of `Object` values bound to a Schema. Type-checked at set/get time. Null represents SQL NULL. Mutable so the execution engine can build rows incrementally.

**TupleSerializer**
Converts a Tuple to/from a compact binary format using `ByteBuffer` (big-endian). No `ObjectOutputStream`. No reflection. Format: for each column in Schema order — one null-flag byte, then the typed value bytes (absent for NULL). TEXT is encoded as a 4-byte length prefix plus UTF-8 bytes.

**RecordId**
An immutable (pageId, slotIndex) pair that uniquely identifies a tuple within a HeapFile. Stable across deletions of other records; only invalidated when the record itself is deleted.

**DataPage — Slotted Page Layout**
Wraps a raw Page and interprets its 4080-byte payload as a slotted page:

```
Payload offset 0–7:   Slot directory header
  [0–1] slotCount    — total slots (including deleted)
  [2–3] freeSpacePtr — payload offset of first free byte (just past last slot entry)
  [4–5] endOfRecords — payload offset of the first (lowest) record byte
  [6–7] reserved

Payload offset 8 + i*4:  Slot entry i (4 bytes)
  [0–1] recordOffset — payload offset of record (0xFFFF = deleted)
  [2–3] recordLength — byte size of record

Records packed from payload offset 4079 downward (high end → low end).
Slot entries grow upward from offset 8.
Free space is the gap between freeSpacePtr and endOfRecords.
```

Deleted records set `recordOffset = 0xFFFF`. The slot index is never reused, preserving all existing RecordIds.

**HeapFile**
A logical table stored as a sequence of DATA pages. On insert, scans existing pages for free space; if none, allocates a new page via DiskManager. Supports insert, read, delete, update (delete+insert), and full sequential scan. No buffer pool yet — every operation goes directly to disk.

### Classes

| Class | Package | Role |
|---|---|---|
| `DataType` | `catalog` | Enum of column types with fixed/variable-size info |
| `Column` | `catalog` | Immutable (name, type) pair |
| `Schema` | `catalog` | Ordered, named column list; O(1) lookup by name |
| `Tuple` | `catalog` | Mutable, typed row of values bound to a Schema |
| `TupleSerializer` | `storage` | Binary serialise/deserialise using ByteBuffer |
| `RecordId` | `storage` | Immutable (PageId, slotIndex) tuple address |
| `DataPage` | `storage` | Slotted-page layout over a raw Page |
| `HeapFile` | `storage` | Multi-page table: insert/read/delete/update/scan |

### Design decisions

**Why a slotted page and not fixed-size slots?**
TEXT fields are variable-length. Fixed slots waste space on short strings and can't hold long ones. Slotted pages are the industry standard (used by PostgreSQL, SQLite, Oracle) because they support variable-length records with O(1) access and stable slot addresses.

**Why store `endOfRecords` in the page header?**
Without persisting it, we would need to scan all slot entries on every page load to recompute the lowest record offset. Storing it as a 2-byte field in the directory header makes it O(1) — the same approach PostgreSQL uses with `pd_lower`/`pd_upper`.

**Why delete = set sentinel rather than reclaim space?**
Space reclamation (compaction/vacuum) requires moving records and updating all slot offsets. That is safe to implement only after the buffer pool and transaction support exist. The sentinel approach is simple, correct, and preserves RecordId stability.

---

## Future Milestones (planned)

| Milestone | Layer added |
|---|---|
| 3 | Buffer Pool with LRU eviction |
| 4 | B+ Tree index (search, insert, split, delete, merge) |
| 5 | SQL Lexer, Parser, AST |
| 6 | Query Execution Engine, Planner |
| 7 | Transactions, locking, MVCC |
| 8 | Write-Ahead Logging, crash recovery |
| 9 | Interactive SQL shell (CLI) |
| 10 | Benchmarks and performance analysis |
