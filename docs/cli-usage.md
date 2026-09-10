# ForgeDB CLI Usage

## Starting the shell

```bash
# Interactive mode — creates/opens forgedb.fdb in the current directory
java -jar forgedb.jar

# Interactive mode with a named database file
java -jar forgedb.jar mydb.fdb

# Batch mode — execute SQL from a file, then exit
java -jar forgedb.jar --file script.sql
java -jar forgedb.jar mydb.fdb --file script.sql
```

## Interactive session

```
╔══════════════════════════════════════╗
║         ForgeDB v0.1.0-SNAPSHOT      ║
║  Type SQL statements and press Enter  ║
║  \q or EXIT to quit  |  \? for help   ║
╚══════════════════════════════════════╝
Database: forgedb.fdb
ForgeDB> CREATE TABLE users (id INT, name TEXT, age INT);
Table 'users' created
ForgeDB> INSERT INTO users VALUES (1, 'Alice', 21);
1 row inserted
ForgeDB> INSERT INTO users VALUES (2, 'Bob', 24);
1 row inserted
ForgeDB> SELECT * FROM users;
+----+-------+-----+
| id | name  | age |
+----+-------+-----+
|  1 | Alice |  21 |
|  2 | Bob   |  24 |
+----+-------+-----+
2 rows
ForgeDB> SELECT * FROM users WHERE id = 2;
+----+------+-----+
| id | name | age |
+----+------+-----+
|  2 | Bob  |  24 |
+----+------+-----+
1 row
ForgeDB> UPDATE users SET age = 25 WHERE id = 2;
1 row(s) updated
ForgeDB> DELETE FROM users WHERE id = 1;
1 row(s) deleted
ForgeDB> \q
Bye.
```

## Meta-commands

| Command | Action |
|---|---|
| `\q` or `exit` or `quit` | Exit the shell |
| `\?` or `help` | Print built-in help |
| `\tables` | List all registered tables |

## Supported SQL

```sql
CREATE TABLE users (id INT, name TEXT, age INT);
INSERT INTO users VALUES (1, 'Alice', 21);
INSERT INTO users (id, name) VALUES (1, 'Alice');   -- explicit column list
SELECT * FROM users;
SELECT id, name FROM users WHERE age > 18;
UPDATE users SET age = 25 WHERE id = 2;
DELETE FROM users WHERE id = 1;
```

## Supported types

| Type | Example value |
|---|---|
| `INT` | `42`, `-7` |
| `LONG` | `9999999999` |
| `BOOLEAN` | `TRUE`, `FALSE` |
| `DOUBLE` | `3.14`, `-0.5` |
| `TEXT` | `'Alice'`, `''` |

## EXPLAIN

Prefix any SELECT, DELETE, or UPDATE with `EXPLAIN` to see the query plan without executing it:

```
ForgeDB> EXPLAIN SELECT * FROM users WHERE id = 42;
SeqScan [ table=users, filter=(id = 42) ]
```

After creating an index:

```
ForgeDB> EXPLAIN SELECT * FROM users WHERE id = 42;
IndexLookup [ table=users, index=id, op=EQ, key=42, residual=none ]
```

With an AND residual predicate:

```
ForgeDB> EXPLAIN SELECT * FROM users WHERE id = 5 AND age > 18;
IndexLookup [ table=users, index=id, op=EQ, key=5, residual=(age > 18) ]
```

## When the planner chooses INDEX_LOOKUP vs SEQ_SCAN

| Condition | Plan chosen |
|---|---|
| No WHERE clause | SEQ_SCAN |
| No index on table | SEQ_SCAN |
| Predicate on non-indexed column | SEQ_SCAN |
| `<>` (not-equal) on indexed column | SEQ_SCAN — not worth using index |
| OR predicate | SEQ_SCAN |
| `col = literal` on indexed col | INDEX_LOOKUP (EQ — point lookup) |
| `col < / <= / > / >=` on indexed col | INDEX_LOOKUP (range scan) |
| `col = literal AND other_cond` on indexed col | INDEX_LOOKUP + residual filter |

## Multi-line statements

Keep typing across multiple lines — the statement is submitted when a semicolon is encountered:

```
ForgeDB> CREATE TABLE orders
      ->   (id INT,
      ->    amount DOUBLE,
      ->    status TEXT);
Table 'orders' created
```

A blank line also submits the accumulated buffer if it is non-empty.

## Error handling

Syntax errors and execution errors print an `ERROR:` message but do not terminate the shell:

```
ForgeDB> SELEKT * FROM users;
ERROR: SQL syntax error: Expected a SQL statement (CREATE, INSERT, SELECT, ...) near 'SELEKT' [line 1, col 1]
ForgeDB> SELECT * FROM users;
...  (continues normally)
```

## Building the fat jar

```bash
mvn package
java -jar target/forgedb.jar
```
