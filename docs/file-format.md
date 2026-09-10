# ForgeDB — On-Disk File Format

Version: 1  
Page size: 4096 bytes

---

## Overview

A ForgeDB database is a single flat binary file. Its contents are a sequence of fixed-size pages, each exactly `PAGE_SIZE` (4096) bytes long.

```
Byte offset 0        : Page 0  (database header)
Byte offset 4096     : Page 1  (first data page)
Byte offset 8192     : Page 2
...
Byte offset N×4096   : Page N
```

The file has no padding between pages. The byte offset of page N is always `N × PAGE_SIZE`.

---

## Page layout

Every page shares the same 16-byte header, regardless of its type. The remaining 4080 bytes are the payload, whose interpretation depends on the page type.

```
┌────────────────────────────────────────────────────┐  Offset
│  Page ID           (4 bytes, big-endian int)       │   0
│  Page Type         (4 bytes, big-endian int)       │   4
│  CRC32 Checksum    (4 bytes, big-endian int)       │   8
│  LSN               (4 bytes, big-endian int)       │  12
├────────────────────────────────────────────────────┤
│                                                    │  16
│  Payload           (4080 bytes)                    │
│                                                    │
└────────────────────────────────────────────────────┘ 4096
```

### Header fields

| Field | Offset | Size | Description |
|---|---|---|---|
| Page ID | 0 | 4 | The logical page number (0-based). Matches the position of the page in the file. |
| Page Type | 4 | 4 | Identifies what the payload contains. See Page Types below. |
| CRC32 Checksum | 8 | 4 | CRC32 of bytes 12–4095 (LSN + payload). Verified on every read. |
| LSN | 12 | 4 | Log Sequence Number. Reserved for WAL (Milestone 8). Always 0 until then. |

### Checksum coverage

The checksum covers bytes `[12, 4095]` — that is, the LSN field plus the entire payload. The Page ID and Page Type fields at offsets 0–7 are excluded because they are structural metadata written by the DiskManager and do not represent user data.

---

## Page types

| Code | Name | Description |
|---|---|---|
| 0 | `HEADER` | Database-level metadata. Always page 0. |
| 1 | `DATA` | Heap tuple storage (Milestone 2). |
| 2 | `BTREE` | B+ tree index node (Milestone 4). |
| 3 | `FREE` | Deallocated page, available for reuse (future). |

---

## Database header page (page 0)

Page 0 is the database header. Its page-type code is `HEADER (0)`.

The payload layout of page 0:

```
┌─────────────────────────────────────────────────────┐  Payload offset
│  Magic Number      (4 bytes, big-endian int)        │   0
│  Format Version    (4 bytes, big-endian int)        │   4
│  Page Count        (4 bytes, big-endian int)        │   8
│  Reserved          (4 bytes)                        │  12
│  (remaining 4064 bytes unused)                      │  16 …
└─────────────────────────────────────────────────────┘
```

| Field | Payload offset | Value |
|---|---|---|
| Magic Number | 0 | `0x464F5247` — spells "FORG" in ASCII |
| Format Version | 4 | `1` (current) |
| Page Count | 8 | Total pages in file, including page 0 |
| Reserved | 12 | `0` |

### Magic number

`0x464F5247` is the four-byte sequence `F O R G`. It is the first thing the DiskManager checks when opening an existing file. If it does not match, the file is rejected as not a ForgeDB database.

This is the same technique used by:
- SQLite: `0x53514c69` ("SQLi")
- PostgreSQL: `0x2142494E` at the start of each page
- PNG: `0x89504e47` ("PNG")

---

## Byte-order

All multi-byte integer fields are stored in big-endian (network) byte order. This is consistent across all page types and all ForgeDB versions.

---

## File growth

Pages are appended to the end of the file. There is no fragmentation in Milestone 1. When a page is allocated:

1. A blank `DATA` page is written at offset `pageCount × PAGE_SIZE`.
2. The Page Count in the header page (page 0) is incremented and written back.

Both writes happen within the same `allocatePage()` call, using `RandomAccessFile` in `"rwd"` mode (synchronous flush). Crash recovery (WAL) in Milestone 8 will make this atomic.

---

## Example hex dump — header page

Below is an annotated example of the first 32 bytes of a freshly created ForgeDB file.

```
Offset  Hex bytes                          Meaning
------  ---------------------------------  -------
0x0000  00 00 00 00                        Page ID = 0
0x0004  00 00 00 00                        Page Type = HEADER (0)
0x0008  xx xx xx xx                        CRC32 checksum (computed at write time)
0x000C  00 00 00 00                        LSN = 0
0x0010  46 4F 52 47                        Magic number ("FORG")
0x0014  00 00 00 01                        Format version = 1
0x0018  00 00 00 01                        Page count = 1
0x001C  00 00 00 00                        Reserved
```

---

## Milestone 2 additions — Slotted-page record layout

### DataPage payload layout

The 4080-byte page payload is divided into a slot directory (growing from the low end) and a record region (growing from the high end). They grow toward each other; the page is full when they would collide.

```
Payload offset 0:
  [0–1] slotCount    (2-byte unsigned short) — total slot entries
  [2–3] freeSpacePtr (2-byte unsigned short) — payload offset just past last slot entry
  [4–5] endOfRecords (2-byte unsigned short) — payload offset of lowest record byte
  [6–7] reserved (2 bytes, zero)

Payload offset 8 + (slotIndex × 4):
  [0–1] recordOffset (2-byte unsigned short) — payload offset where record starts
                                                0xFFFF = deleted sentinel
  [2–3] recordLength (2-byte unsigned short) — byte length of the record

Records packed from payload offset 4079 downward.
```

**2-byte range check:**
- `PAGE_PAYLOAD_SIZE = 4080` — fits in unsigned short (max 65535) ✓
- `DELETED_SENTINEL = 0xFFFF = 65535 > 4079` — unambiguous, no valid offset can be 65535 ✓

### Tuple binary format

For each column in Schema order:

```
1 byte   null flag: 0x00 = value present, 0x01 = NULL

if 0x00 (present):
  INT      → 4 bytes, big-endian signed int
  LONG     → 8 bytes, big-endian signed long
  BOOLEAN  → 1 byte  (0x00 = false, 0x01 = true)
  DOUBLE   → 8 bytes, big-endian IEEE 754 (via Double.doubleToLongBits)
  TEXT     → 4-byte big-endian int (UTF-8 byte count) + N UTF-8 bytes

if 0x01 (NULL):
  (no further bytes for this column)
```

The format is not self-describing: the same Schema used during serialisation must be supplied for deserialisation.

---

## Version history

| Version | Changes |
|---|---|
| 1 | Initial format. Fixed 4096-byte pages, 16-byte page header, header page at offset 0. |
| 2 | Added slotted-page record layout. Added tuple binary format. |
