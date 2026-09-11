# ForgeDB M9: Transactions, WAL, and Recovery

## Overview

M9 adds ACID transaction support, write-ahead logging (WAL), and crash recovery to ForgeDB. All implementations follow a **NO-STEAL / NO-FORCE / REDO-ONLY** policy for simplicity and correctness.

---

## Architecture Summary

### Single-Table-Per-File Model

Each database file (`*.fdb`) stores **one table only**. The header page (page 0) contains:

| Offset | Size | Field |
|--------|------|-------|
| 0      | 4    | Magic number (`0x464F5247` = "FORG") |
| 4      | 4    | DB format version (currently 1) |
| 8      | 4    | Total page count |
| 12     | 4    | Reserved |

Data pages start at page 1 and contain slotted-page record layouts.

---

## WAL Record Format

| Offset | Size | Field |
|--------|------|-------|
| 0      | 4    | recordLength (total bytes including header+CRC) |
| 4      | 4    | recordType (WALRecordType.id) |
| 8      | 8    | txnId (long) |
| 16     | 4    | pageId (int, -1 if not page-specific) |
| 20     | 4    | pageLsn (int, -1 if not applicable) |
| 24     | N    | payload |
| 24+N   | 4    | CRC32 over bytes [4, 24+N) |

LSN = byte offset of the record in the WAL file (naturally ordered).

---

## TransactionManager

### Lifecycle

```
begin()    → assigns unique txnId, appends WAL BEGIN record
           → returns Transaction handle

logInsert  → appends HEAP_INSERT WAL record, stamps pageLSN,
           → tracks dirty page for no-steal pin

commit()   → 1. append WAL COMMIT record
           → 2. WAL sync/force (durability point)
           → 3. release no-steal pins

rollback() → 1. unpin each dirty page
           → 2. discard pages (no-write to disk)
           → 3. append WAL ROLLBACK record
           → 4. release remaining pins
```

### NO-STEAL Policy

- A page dirtied by an active transaction is **pinned** and cannot be evicted
- Dirty content never reaches disk before commit
- Rollback discards dirty pages without writing them

### NO-FORCE Policy

- At commit, pages are NOT forced to disk immediately
- WAL sync is the durability point
- Recovery can reconstruct committed changes from WAL

### REDO-ONLY Recovery

- Uncommitted transaction pages never reached disk (no-steal)
- No UNDO records needed
- Recovery replays only committed transactions' WAL records

---

## WAL Integration with Storage

### WAL-Before-Data Ordering

For each mutating operation (insert/delete):

1. Append WAL redo record to the WAL file
2. Apply change to the in-memory page
3. Stamp `page.setLsn(walRecordLsn)` on the page
4. PageLSN enables idempotent redo during recovery

### HEAP_INSERT Payload

```
[4] pageId (int, big-endian)
[4] slotIndex (int, big-endian)
[N] serialized tuple bytes
```

### HEAP_DELETE Payload

```
[4] pageId (int, big-endian)
[4] slotIndex (int, big-endian)
```

---

## RecoveryManager

### Algorithm

1. **Scan WAL** safely using `WALManager.readAll()` — stops at any corruption/truncation
2. **Identify committed transactions** — collect txnIds with COMMIT records
3. **REDO pass** — replay HEAP_INSERT/HEAP_DELETE records for committed txnIds
   - Skip if `page.getLsn() >= walRecordLsn` (already applied)
4. **Ignore uncommitted** — their dirty pages never reached disk
5. **Rebuild indexes** — scan heap and insert into fresh B+ trees

### Idempotent Redo

The pageLSN check ensures recovery can be run multiple times safely:

- First run: `pageLSN=0`, `walLSN=100` → redo applied, `pageLSN` set to 100
- Second run: `pageLSN=100`, `walLSN=100` → skip (already applied)

---

## SQL Transaction Commands

```sql
BEGIN;        -- Start a new transaction
-- ... execute statements ...
COMMIT;       -- Commit and make changes durable
-- or
ROLLBACK;     -- Abort and discard in-memory changes
```

The commands are recognized by the lexer/parser and dispatched to the Executor.
The actual transaction management is handled by `TransactionManager`.

---

## Crash Simulation Testing

To test crash recovery without actual machine crashes, tests simulate:

- WAL file truncation (cut at random offset)
- CRC corruption (flip a byte in a record)
- Uncommitted transaction scenario (commit all but one transaction)
- Repeated recovery runs

The recovery algorithm must:
- Not crash on any of these scenarios
- Produce consistent state after recovery
- Not lose committed changes

---

## Checkpointing (M9 minimal)

Checkpoint records are reserved in `WALRecordType`:

- `CHECKPOINT_BEGIN` (7) — start of checkpoint
- `CHECKPOINT_END` (8) — checkpoint complete, redo can start from this LSN

Future phases will store checkpoint metadata (active transactions, redo-from-LSN) in the payload.

---

## Index Rebuild During Recovery

After recovery, indexes are rebuilt from heap data:

```java
BTree tree = RecoveryManager.rebuildIndex(bufferPool, schema);
```

This scans all DATA pages and inserts live records into the new B+ tree.

This is simpler than logging index changes and correct for REDO-ONLY recovery.

---

## Known Limitations

1. **No SQL CREATE INDEX yet** — indexes must be created via `Executor.createIndex()` in Java
2. **No multi-table catalog** — each `.fdb` file = one table
3. **No UNDO records** — recovery is REDO-only
4. **No concurrent transactions** — TransactionManager is not thread-safe
5. **No rollback savepoints** — entire transaction is atomic

---

## Files Created/Modified

| File | Purpose |
|------|---------|
| `src/main/java/com/forgedb/sql/ast/BeginStatement.java` | BEGIN AST node |
| `src/main/java/com/forgedb/sql/ast/CommitStatement.java` | COMMIT AST node |
| `src/main/java/com/forgedb/sql/ast/RollbackStatement.java` | ROLLBACK AST node |
| `src/main/java/com/forgedb/sql/Lexer.java` | Added BEGIN/COMMIT/ROLLBACK keywords |
| `src/main/java/com/forgedb/sql/Parser.java` | Parse transaction commands |
| `src/main/java/com/forgedb/sql/TokenType.java` | Token enum |
| `src/main/java/com/forgedb/sql/ast/StatementVisitor.java` | Visitor interface |
| `src/main/java/com/forgedb/execution/Executor.java` | Visit methods for SQL commands |
| `src/main/java/com/forgedb/transaction/TransactionManager.java` | Transaction lifecycle |
| `src/main/java/com/forgedb/transaction/WalHeapFile.java` | WAL-integrated HeapFile |
| `src/main/java/com/forgedb/transaction/RecoveryManager.java` | Crash recovery logic |
| `src/main/java/com/forgedb/storage/DataPage.java` | insertRecordAt for redo |

---

## Test Results

All 523 pre-existing tests pass. No existing tests were modified — only new files were added.
