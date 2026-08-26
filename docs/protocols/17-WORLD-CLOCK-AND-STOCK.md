# 17 — World clock and stock market

**Sources:**
- `com/szabh/smable3/entity/BleWorldClock`, `BleStock` (vendor APK `com.smart.stf`)
- Live capture from a Kronos Thunder (`JL` / `AM05`, `Kronos_Thunder_V006`), 2026-08-24/26

---

## 0. Two list keys

| Key | Value | cmd/key | Item size | Max items |
|---|---|---|---|---|
| `WORLD_CLOCK` | 1031 | `0x04` / `0x07` | 68 bytes | 5 |
| `STOCK` | 1032 | `0x04` / `0x08` | 84 bytes | 5 |

Both are **collections**, not settings, so they use the full flag vocabulary
rather than plain `UPDATE`:

| Flag | Meaning |
|---|---|
| `CREATE` (0x20) | add or replace the item with this id |
| `DELETE` (0x30) with a 1-byte id | remove that item |
| `DELETE` (0x30) with `0xFF` | clear the whole list |
| `READ` (0x10) / `READ_CONTINUE` (0x11) | page the list back, see §1.2 |

Text fields in both entities are **UTF-16LE**, not UTF-8 — a Latin name
therefore occupies two bytes per character and the field caps at 62 bytes.

---

## 1. `WORLD_CLOCK` (0x0407)

### 1.1 The 68-byte item

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `[bit 7: is local] [bits 0-6: id]` |
| 1 | 1 | timezone offset in **quarter hours**, signed |
| 2..3 | 2 | reserved |
| 4..67 | 64 | city name, UTF-16LE, NUL-padded |

The offset unit is quarter-hours, so UTC+5:45 (Kathmandu) is 23 and UTC-3:30 is
-14. One item in the list carries the local-clock bit; it is the watch's own
timezone and is not a user entry.

### 1.2 Reading the list is paged

A `READ` does **not** return the whole list. The watch answers with a single
68-byte item — the local clock — and hands over the rest one frame at a time in
response to `READ_CONTINUE`, ending with an empty body.

```
Tx  04 07 10 FF   → Rx  one item: "Berlin", local bit set
Tx  04 07 11 FF   → Rx  one item: "Sydney"
Tx  04 07 11 FF   → Rx  (empty)   ← end of list
```

Reading the first frame as the complete list is what made an earlier build
conclude the watch held no cities and overwrite the phone's list with an empty
one. `BleManager` now accumulates pages, bounded by `MAX_CLOCKS + 2` and a 3 s
per-page timeout, and only adopts the result once the empty frame arrives.

### 1.3 The watch pushes deletions

Removing a city on the watch sends an **unsolicited** `DELETE` (not a reply) with
a one-byte body holding the id. Three different things arrive under this key and
they must be told apart by direction, not by flag alone:

| Direction | Flag | Body | Meaning |
|---|---|---|---|
| watch → phone | `DELETE` | 1 byte | the user deleted that city |
| reply | `DELETE` / `CREATE` | empty | plain ACK for our own writes |
| reply | `READ` / `READ_CONTINUE` | 68 bytes or empty | one page of the list |

Treating the bodyless ACK as "the watch deleted id = -1" filled the log with
bogus delete attempts in an earlier build.

---

## 2. `STOCK` (0x0408)

### 2.1 The 84-byte item

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | id |
| 1 | 1 | colour type — 0 or 1, which way round green/red are drawn |
| 2 | 1 | `[bits 4-7: price decimals] [bits 0-3: change-point decimals]` |
| 3 | 1 | `[bits 4-7: change-percent decimals]` |
| 4..67 | 64 | ticker, UTF-16LE, NUL-padded |
| 68..71 | 4 | share price, IEEE-754 float, **little-endian** |
| 72..75 | 4 | net change, points |
| 76..79 | 4 | net change, percent |
| 80..83 | 4 | market cap |

The decimal-place nibbles tell the watch how many digits to render for each
float; they are not part of the value. `BleManager.getDecimalPlaces()` derives
them from the value being sent.

### 2.2 Verified on hardware

```
Tx  04 08 20  id=0 code=GOOGL price=... 
Rx  04 08 10  02 00 12 20 47 00 4F 00 4F 00 47 00 4C 00 ...
                          └ "GOOGL" in UTF-16LE
```

The watch stores and returns the list, and pushes a `DELETE` when a stock is
removed on the device — same shape as the world clock.

---

## 3. Support

Neither key is gated on a `DEVICE_INFO` flag we can read on a V2 watch. Both are
live on the Kronos Thunder: `0x0408` reads back the pushed list, and `0x0407`
answers the paged read. Note the plain `READ` of `0x0407` with **no** id byte
answers empty — always send the `0xFF` selector.
