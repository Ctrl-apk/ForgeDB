# ForgeDB

A relational database engine built from scratch in Java 17, with no external database libraries.

ForgeDB implements real database internals: raw disk I/O, page management, buffer pool with LRU eviction, B+ tree indexes, a hand-written SQL parser, a query planner, a query execution engine, write-ahead logging (WAL) with CRC32, and crash recovery. Every component is written from first principles and is designed to be readable and explainable.

---

## Status

| Milestone | Description | Status |
|-----------|-------------|--------|
| M1 | Storage foundation — pages, DiskManager, binary file format | ✅ Complete |
| M2 | Schema, tuples, heap file, slotted-page layout, sequential scan | ✅ Complete |
| M3 | Buffer pool — page caching, LRU eviction, pin/unpin protocol | ✅ Complete |
| M4 | B+ tree index — search, insert, split, delete, range scan | ✅ Complete |
| M5 | SQL lexer, parser, typed AST | ✅ Complete |
| M6 | Query execution engine — SELECT / INSERT / UPDATE / DELETE / WHERE | ✅ Complete |
| M7 | Query planner — index-accelerated lookups, EXPLAIN | ✅ Complete |
| M8 | Interactive CLI shell, benchmark harness | ✅ Complete |
| M9 | Transactions (BEGIN/COMMIT/ROLLBACK), WAL, crash recovery | ✅ Complete |

---

## Architecture

```
SQL text
  │
  ▼
Lexer / Parser  ──────────────────────────── sql/
  │
  ▼
AST (typed statement nodes)  ───────────── sql/ast/
  │
  ▼
Query Planner  ──────────────────────────── planner/
  │  (chooses SEQ_SCAN or INDEX_LOOKUP)
  ▼
Executor  ───────────────────────────────── execution/
  │  (evaluates expressions, drives storage)
  ▼
TransactionManager  ─────────────────────── transaction/
  │  (NO-STEAL / NO-FORCE / REDO-ONLY)
  ▼
WALManager  ─────────────────────────────── wal/
  │  (append-only, CRC32, LSN-addressed)
  ▼
BufferPool  ─────────────────────────────── storage/
  │  (LRU eviction, pin counts, discardPage)
  ├──▶ B+ Tree  ────────────────────────── index/
  │    (disk-backed, page-allocated nodes)
  ▼
HeapFile / DataPage  ────────────────────── storage/
  │  (slotted pages, slot directory)
  ▼
DiskManager  ────────────────────────────── storage/
  │  (page-granular I/O, CRC32 checksums)
  ▼
Disk  (.fdb file, one file per table)
```

---

## M9: Transactions, WAL, and Recovery

### Recovery policy

ForgeDB uses a **NO-STEAL / NO-FORCE / REDO-ONLY** policy:

| Property | Meaning |
|----------|---------|
| **NO-STEAL** | A dirty page modified by an active transaction is pinned in the buffer pool and cannot be evicted before commit. Uncommitted data never reaches disk. |
| **NO-FORCE** | Pages are not forced to disk at commit time. WAL sync is the durability point. |
| **REDO-ONLY** | Because uncommitted pages never reach disk, no UNDO records are needed. Recovery only redoes committed changes. |

### Transaction lifecycle

```
BEGIN    →  assigns unique txnId, appends WAL BEGIN record
            returns Transaction handle

[DML]    →  WAL redo record appended before page change
            pageLSN stamped on modified page
            no-steal pin held while transaction is active

COMMIT   →  1. append WAL COMMIT record
            2. WAL sync/force  ← durability point
            3. release all no-steal pins

ROLLBACK →  1. unpin dirty pages
            2. discard dirty pages (BufferPool.discardPage)
            3. append WAL ROLLBACK record
            disk state is unchanged
```

### WAL record format

```
[4]  recordLength   (header + payload + CRC, big-endian)
[4]  recordType     (BEGIN=1, COMMIT=2, ROLLBACK=3,
                     HEAP_INSERT=4, HEAP_DELETE=5,
                     PAGE_IMAGE=6, CHECKPOINT_BEGIN=7,
                     CHECKPOINT_END=8)
[8]  txnId
[4]  pageId         (-1 if not page-specific)
[4]  pageLsn        (-1 if not applicable)
[N]  payload
[4]  CRC32          (over header + payload; length field excluded)
```

LSN = byte offset of the record in the WAL file. No separate counter is persisted.

### pageLSN semantics

Every modified page carries the LSN of the WAL record that last changed it (bytes 12–15 of the page header). During recovery, if `page.pageLSN >= walRecord.LSN`, the redo is skipped — the change was already persisted. This makes recovery **idempotent**: it is safe to run multiple times after a crash.

### Crash recovery algorithm

1. Scan the WAL from offset 0. Stop safely at any truncated, corrupt, or CRC-invalid tail.
2. Collect all txnIds that have a COMMIT record in the valid log.
3. REDO pass: replay `HEAP_INSERT` and `HEAP_DELETE` records for committed transactions only. Skip any record whose target page has `pageLSN >= record.LSN`.
4. Uncommitted transactions are silently ignored (no UNDO needed).
5. Rebuild B+ tree indexes by scanning heap data.
6. Flush recovered pages.

### Index rebuild during recovery

Indexes are not logged — they are rebuilt from heap data after redo completes. `RecoveryManager.rebuildIndex()` scans all data pages and inserts live records into a fresh B+ tree. This is simpler than logging index changes and produces a correct result after any recovery scenario.

---

## SQL reference

```sql
-- DDL
CREATE TABLE users (id INT, name TEXT, age INT);

-- DML
INSERT INTO users VALUES (1, 'Alice', 30);
INSERT INTO users (id, name) VALUES (2, 'Bob');
SELECT * FROM users;
SELECT id, name FROM users WHERE age > 25;
UPDATE users SET age = 31 WHERE id = 1;
DELETE FROM users WHERE id = 2;

-- Transactions
BEGIN;
INSERT INTO users VALUES (3, 'Carol', 27);
COMMIT;

BEGIN;
INSERT INTO users VALUES (4, 'Dave', 22);
ROLLBACK;   -- Dave never persisted; dirty page is discarded

-- Query planning
EXPLAIN SELECT * FROM users WHERE id = 1;
-- Without index: SeqScan [ table=users, filter=(id EQ 1) ]
-- With index:    IndexLookup [ table=users, index=id, op=EQ, key=1, residual=none ]
```

Column types: `INT`, `LONG`, `DOUBLE`, `TEXT`, `BOOLEAN`.

WHERE operators: `=`, `<>`, `<`, `<=`, `>`, `>=`, `AND`, `OR`, `NOT`, `IS NULL`, `IS NOT NULL`.

---

## CLI

```
$ java -jar target/forgedb.jar

ForgeDB> CREATE TABLE products (id INT, name TEXT, price DOUBLE);
Table 'products' created
ForgeDB> INSERT INTO products VALUES (1, 'Widget', 9.99);
1 row(s) inserted
ForgeDB> SELECT * FROM products;
id | name   | price
---+--------+------
 1 | Widget |  9.99
ForgeDB> EXPLAIN SELECT * FROM products WHERE id = 1;
SeqScan [ table=products, filter=(id EQ 1) ]
ForgeDB> exit
```

> Index-accelerated lookups require creating an index via `Executor.createIndex()` in Java code.
> SQL `CREATE INDEX` syntax is not yet implemented (planned for M10).

---

## Building and running

**Requirements:** Java 17, Maven 3.8+

```bash
# Compile
mvn compile

# Run all 523 tests
mvn test

# Build runnable fat jar
mvn package
java -jar target/forgedb.jar
```

---

## Testing

523 tests across 18 test classes, all using JUnit 5 and `@TempDir` for full isolation.

| Test class | Area |
|------------|------|
| `DiskManagerTest` | Page I/O, page allocation, checksums |
| `DataPageTest` | Slotted-page layout, slot directory |
| `HeapFileTest` | Heap insert/read/delete/scan/update |
| `HeapFilePinDisciplineTest` | Pin/unpin correctness under a small pool |
| `TupleSerializerTest` | Binary tuple encoding/decoding |
| `BufferPoolTest` | LRU eviction, pin counts, flush |
| `BufferPoolDiscardPageTest` | `discardPage` — no-write rollback primitive |
| `BTreeTest` | B+ tree insert, search, delete, splits, range scan |
| `SchemaTest` | Schema construction and column lookup |
| `TupleTest` | Typed tuple access and null handling |
| `LexerTest` | SQL tokenisation |
| `ParserTest` | SQL parsing, AST structure, error cases |
| `QueryPlannerTest` | Plan selection (SEQ_SCAN vs INDEX_LOOKUP) |
| `ExecutorTest` | End-to-end SQL execution |
| `ShellTest` | CLI input/output |
| `WALManagerTest` | WAL append, LSN, replay, corruption/truncation |
| `BenchmarkSmokeTest` | Benchmark runner correctness |
| `M8VerificationTest` | Planner/index behaviour verification |

---

## Project layout

```
forgedb/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── cli-usage.md
│   ├── file-format.md
│   └── M9-transactions-wal-recovery.md
└── src/
    ├── main/java/com/forgedb/
    │   ├── benchmark/          BenchmarkRunner, BenchmarkResult
    │   ├── catalog/            Column, DataType, Schema, Tuple
    │   ├── cli/                ForgeDBShell, ResultPrinter
    │   ├── common/             Constants, ForgeDBException
    │   ├── execution/          Executor, Catalog, ExpressionEvaluator, QueryResult
    │   ├── index/              BTree, BTreeKey, BTreeNode
    │   ├── planner/            QueryPlanner, QueryPlan, PredicateInfo
    │   ├── sql/
    │   │   ├── ast/            Statement nodes (Select, Insert, …, Begin, Commit, Rollback)
    │   │   ├── Lexer.java
    │   │   ├── Parser.java
    │   │   └── TokenType.java
    │   ├── storage/            BufferPool, DataPage, DiskManager(Impl), HeapFile,
    │   │                       Page, PageId, PageType, RecordId, TupleSerializer
    │   ├── transaction/        TransactionManager, WalHeapFile, RecoveryManager
    │   └── wal/                WALManager, WALRecord, WALRecordType
    └── test/java/com/forgedb/
        ├── benchmark/          BenchmarkSmokeTest
        ├── catalog/            SchemaTest, TupleTest
        ├── cli/                ShellTest
        ├── execution/          ExecutorTest
        ├── index/              BTreeTest
        ├── planner/            QueryPlannerTest
        ├── sql/                LexerTest, ParserTest
        ├── storage/            BufferPoolTest, BufferPoolDiscardPageTest,
        │                       DataPageTest, DiskManagerTest,
        │                       HeapFileTest, HeapFilePinDisciplineTest,
        │                       TupleSerializerTest
        ├── verify/             M8VerificationTest
        └── wal/                WALManagerTest
```

---

## Tech stack

- **Java 17** — records, pattern matching, switch expressions
- **Maven 3.8+** — build, test, fat-jar packaging
- **JUnit 5** — all tests; `@TempDir` for disk isolation
- **No external database libraries** — every component is implemented from scratch
