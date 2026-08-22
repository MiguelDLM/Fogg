# 13 — ALARM (0x0210)

**Sources:**
- `decompiled_apk/sources/com/szabh/smable3/entity/BleAlarm.java` — `encode()` / `decode()`
- `decompiled_apk/sources/com/szabh/smable3/entity/BleRepeat.java` — the weekday mask
- `decompiled_apk/sources/com/bestmafen/baseble/data/BleWritable.java` — `writeIntN`, `writeStringWithFix`
- `decompiled_apk/sources/com/sma/smartv3/ui/device/AlarmsActivity.java`, `AlarmEditActivity.java` — flag usage

---

## 0. Key

`BleKey.ALARM` is ordinal 17, value **528 = 0x0210** → `cmd=0x02`, `key=0x10`.
(JADX prints the value as `BNMapObserver.EventMapView.EVENT_CLICKED_ROUTE_PAN_ITEM`,
an unrelated Baidu constant that happens to be 528 — a constant-substitution
artefact, not a real dependency.)

Alarms live **on the watch**. The phone is a client: it writes, reads back, and
caches. There is no phone-side scheduler involved.

---

## 1. The item — 28 bytes, fixed

`BleAlarm.getMLengthToWrite()` returns 28 and `ITEM_LENGTH = 28`.

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `id` | `u8`; assigned by the watch |
| 1 | 1 bit (0x80) | `enabled` | `writeIntN(x, 1)` |
| 1 | 7 bits (0x7F) | `repeat` | `writeIntN(x, 7)`, weekday mask |
| 2 | 1 | `year - 2000` | |
| 3 | 1 | `month` | 1–12 |
| 4 | 1 | `day` | 1–31 |
| 5 | 1 | `hour` | 0–23 |
| 6 | 1 | `minute` | 0–59 |
| 7 | 21 | `tag` | UTF-8, **zero-padded**, `TAG_LENGTH = 21` |

### 1.1 The shared byte

`enabled` and `repeat` are not two bytes — they share byte 1. `writeIntN` packs
**MSB-first**: it writes the value left-aligned at the current bit position and
advances by the requested width. So `enabled` occupies bit 7 and `repeat` bits
6..0:

```
byte 1:  E R R R R R R R
```

`enabled=1, repeat=WORKDAY(31)` → `0x9F`. Reading these as separate bytes shifts
every field after them by one and produces plausible-looking garbage rather than
a parse failure.

### 1.2 The weekday mask (`BleRepeat`)

**Monday is the low bit** — `WEEKDAYS` is ordered Mon..Sun, *not* the
`Calendar.SUNDAY = 1` convention.

| Bit | Value | Day |
|---|---|---|
| 0 | 1 | Monday |
| 1 | 2 | Tuesday |
| 2 | 4 | Wednesday |
| 3 | 8 | Thursday |
| 4 | 16 | Friday |
| 5 | 32 | Saturday |
| 6 | 64 | Sunday |

Named combinations: `ONCE = 0`, `WORKDAY = 31` (Mon–Fri), `WEEKEND = 96`,
`EVERYDAY = 127`.

`repeat == 0` means a **one-shot**, and only then do the year/month/day fields
carry meaning. A recurring alarm ignores them.

### 1.3 The tag

`writeStringWithFix(tag, 21)` truncates to 21 bytes and zero-pads the remainder.
Note it does so **by byte count, on the raw UTF-8**, so a naive cut can split a
multi-byte sequence — `dial-sender` backs off to the previous lead byte.

An empty tag takes the `skip(21 * 8)` branch, leaving the field zero — the same
bytes padding produces, so the two are indistinguishable on the wire.

---

## 2. Operations

| Operation | Flag | Body |
|---|---|---|
| Read all | `READ` (0x10) | one byte, `0xFF` |
| Create | `CREATE` (0x20) | one 28-byte item; `id` ignored |
| Update | `UPDATE` (0x00) | one 28-byte item, matched by `id` |
| Delete | `DELETE` (0x30) | one byte, the `id` (`0xFF` = all) |
| Replace list | `RESET` (0x40) | `n` × 28 bytes |

From `AlarmEditActivity`: a new alarm is `CREATE`, an edit of an existing one is
`UPDATE`. `AlarmsActivity` deletes with `sendInt8(ALARM, DELETE, id)` and the
sync path replaces the whole list with `sendList(ALARM, RESET, alarms)`.

### 2.1 Reply shape — verified on hardware

The READ reply is a run of 28-byte items with **no count field and no header** —
`n = body.length / 28`. Confirmed on a Kronos Thunder: a read-all holding two
alarms came back as

```
AB 11 00 3B 55 F3 02 10 10 | 00 84 00 00 00 14 24 00*21 | 01 00 1A 08 0F 06 00 00*21
```

`LEN = 0x3B = 59`, so payload = 56 = 2 × 28 exactly.

The first item decodes as id 0, `0x84` → enabled (bit 7) + repeat 4 (Wednesday),
20:36 with no date; the second as id 1, disabled, one-shot 15/08/2026 06:00.
That byte is the direct confirmation of the shared-byte packing in §1.1 and of
the Monday-first mask in §1.2.

### 2.2 Mutations return nothing useful

**`CREATE` and `UPDATE` do not echo the stored item.** They reply with a
bodyless ACK, and `DELETE` does the same:

```
AB 11 00 03 D8 AD 02 10 20      CREATE ack
AB 11 00 03 .. .. 02 10 30      DELETE ack
```

`LEN = 3` means payload = 0. So a mutation tells you nothing about the resulting
list, and there is no way to learn the id of a just-created alarm from the
reply. **Re-read after every change.**

### 2.3 The watch does not allocate ids

A `CREATE` carrying `id = 0` was written into **slot 0**, destroying the alarm
already stored there — the watch takes the id from the payload rather than
picking a free one. The phone has to choose the slot itself, from the list the
watch last reported. (The original app sends a default-constructed `BleAlarm`,
whose id is 0, so it has this hazard too.)

### 2.4 One-shot alarms in the past

Before sending, the original app checks whether a `repeat == 0` alarm is still
in the future and, if not, moves it to today's date **+1 day**
(`AlarmsActivity.Companion.oIX0oI`). It does this by incrementing the
day-of-month directly, so saving a lapsed alarm on the 31st yields day 32.
`dial-sender` rolls through `Calendar` instead — same intent, real dates.

There is also a fallback in `checkAlarmList`: when the watch is unreachable, a
lapsed one-shot is force-disabled rather than left armed.

### 2.5 How many alarms

The ceiling comes from the app's per-product table (`ProductManager`), which is
not in the decompiled sources. `dial-sender` uses 8 as a client-side guard only;
the watch enforces its own limit regardless.

---

## 3. dial-sender

| Piece | Location |
|---|---|
| Item encode/decode (tested) | `BleAlarm` |
| Create / update / delete / read / reset | `BleManager.createAlarm`, `updateAlarm`, `deleteAlarm`, `readAlarms`, `resetAlarms` |
| Reply handler | `BleManager.processResponse`, `cmd==0x02 && key==0x10` |
| Cache | pref `alarms_json`, each item stored as its own wire bytes in hex |
| UI | `AlarmsActivity`, reached from the Device tab |

The watch is treated as the source of truth: the list is redrawn from what comes
back rather than from what was sent, so a write the watch rejects does not leave
a phantom row on the phone.
