# ForgeDB

A production-quality educational relational database engine built from scratch in Java 21.

ForgeDB is not a toy CRUD wrapper. It implements real database internals — from raw disk I/O and page management up through a buffer pool, B+ tree indexes, a SQL parser, a query execution engine, transactions, and write-ahead logging — with no external database libraries.

The goal is to be able to read every component, understand how it works, and explain it in an interview.

---

## Status

| Milestone | Description | Status |
|---|---|---|
| 1 | Storage foundation — pages, DiskManager, persistence | ✅ Complete |
| 2 | Schema, tuples, heap file, sequential scan | ✅ Complete |
| 3 | Buffer pool, page caching, LRU eviction | ✅ Complete |
| 4 | B+ Tree index — search, insert, split, delete, merge | ✅ Complete |
| 5 | SQL lexer, parser, AST | ✅ Complete |
| 6 | Query execution engine — SELECT/INSERT/UPDATE/DELETE/WHERE | ✅ Complete |
| 7 | Query planner — index-accelerated SELECT/UPDATE/DELETE, EXPLAIN | ✅ Complete |
| 8 | CLI shell, EXPLAIN command, benchmark harness | ✅ Complete |
| 3 | Buffer pool, page caching, LRU eviction | ⬜ Planned |
| 4 | B+ tree index — search, insert, split, delete | ⬜ Planned |
| 5 | SQL lexer, parser, AST | ⬜ Planned |
| 6 | Query execution engine, planner | ⬜ Planned |
| 7 | Transactions, locking, concurrency control | ⬜ Planned |
| 8 | Write-ahead logging, crash recovery | ⬜ Planned |
| 9 | Interactive SQL shell (CLI) | ⬜ Planned |
| 10 | Benchmarks — scan vs index, throughput, cache hit rate | ⬜ Planned |

---

## Architecture

```
SQL
 ↓
Lexer / Parser          (Milestone 5)
 ↓
AST                     (Milestone 5)
 ↓
Query Planner           (Milestone 6)
 ↓
Execution Engine        (Milestone 6)
 ↓
Transactions            (Milestone 7)
 ↓
Buffer Pool             (Milestone 3)
 ↓
Access Methods / B+ Tree (Milestone 4)
 ↓
Storage Engine          (Milestone 2)
 ↓
Disk Manager            (Milestone 1) ← current
 ↓
Disk
```

See [docs/architecture.md](docs/architecture.md) for a full description of each layer.

---

## Building

**Prerequisites:** Java 21, Maven 3.8+

```bash
# Compile
mvn compile

# Run all tests
mvn test

# Build fat jar (available from Milestone 9)
mvn package
```

---

## Running tests

```bash
mvn test
```

All tests use JUnit 5 and `@TempDir` for isolation — no test data is left behind on disk.

---

## Target demo (Milestone 9+)

```
$ java -jar forgedb.jar

ForgeDB> CREATE TABLE users (id INT, name TEXT, age INT);
ForgeDB> INSERT INTO users VALUES (1, 'Alice', 21);
ForgeDB> INSERT INTO users VALUES (2, 'Bob', 24);
ForgeDB> SELECT * FROM users;
ForgeDB> SELECT * FROM users WHERE id = 2;
ForgeDB> BEGIN;
ForgeDB> UPDATE users SET age = 25 WHERE id = 2;
ForgeDB> ROLLBACK;
```

---

## Tech stack

- **Java 21** — records, pattern matching, sealed types where appropriate
- **Maven** — build, dependency management, test execution
- **JUnit 5** — unit and integration tests
- **No external database libraries** — every component is implemented from scratch

---

## Documentation

| Document | Description |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Layer-by-layer architecture description |
| [docs/file-format.md](docs/file-format.md) | On-disk binary file format specification |

---

## Project layout

```
forgedb/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   └── file-format.md
└── src/
    ├── main/java/com/forgedb/
    │   ├── common/
    │   │   ├── Constants.java
    │   │   └── ForgeDBException.java
    │   └── storage/
    │       ├── Page.java
    │       ├── PageId.java
    │       ├── PageType.java
    │       ├── DiskManager.java
    │       └── DiskManagerImpl.java
    └── test/java/com/forgedb/
        └── storage/
            └── DiskManagerTest.java
```
