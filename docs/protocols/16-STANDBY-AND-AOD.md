# 16 — Standby screen (always-on display)

**Sources:**
- `com/szabh/smable3/entity/BleStandbyWatchFaceSet` and `BleTimeRange`, read out of
  the vendor APK (`com.smart.stf`) with `dexdump`
- `com/sma/smartv3/ui/device/StandbyWatchFaceSettingsActivity` and
  `DeviceFragment$standbySetItem` — the two screens that drive these keys
- `com/sma/smartv3/ble/ProductManager.xxxI()` / `.Ill8()` — the capability gates
- Live capture from a Kronos Thunder (`JL` / `AM05`, `Kronos_Thunder_V006`), 2026-08-26

---

## 0. Two keys, one feature

The always-on display is split across two keys that are **not** interchangeable,
and a watch may implement one without the other.

| Key | Value | cmd/key | Carries | Gated in the vendor app by |
|---|---|---|---|---|
| `STANDBY_SET` | 577 | `0x02` / `0x41` | the master on/off switch | `mSupportStandbySet` |
| `STANDBY_WATCH_FACE_SET` | 596 | `0x02` / `0x54` | the switch **plus** the schedule | `mSupportTimerStandbySet` |

Both capability flags live in the V1 `DEVICE_INFO` payload. A watch that answers
`DEVICE_INFO` with the V2 identity block — the Kronos Thunder does — reports
neither, so support has to be probed on the wire instead. See §4.

---

## 1. `STANDBY_SET` (0x0241) — one byte

```
payload = [ enabled ]      // 0 = off, 1 = on
flag    = UPDATE (0x00)
```

The vendor app writes it with `BleConnector.sendInt8(STANDBY_SET, UPDATE, picked)`
where `picked` comes from a two-entry picker bound to `array/enable`
(`[@string/off, @string/on]`). Its title resource is `string/standby_set`,
"Standby Settings" / 待机设置.

**The key is write-only in practice.** The vendor app never reads it: on connect
it pushes the value straight from `BleCache`, and the settings screen renders
from that cache too. On the Kronos Thunder a `READ` answers with eight bytes that
are *not* the setting — see §4.2.

---

## 2. `STANDBY_WATCH_FACE_SET` (0x0254) — the 8-byte entity

`BleStandbyWatchFaceSet.encode()` writes, in order:

| Offset | Field | Meaning |
|---|---|---|
| 0 | `mStandbyEnable` | master switch, 0/1 |
| 1 | `mEnabled` | **all day**, 0/1 |
| 2 | `mBleTimeRange1.mEnabled` | **scheduled**, 0/1 |
| 3 | `mBleTimeRange1.mStartHour` | |
| 4 | `mBleTimeRange1.mStartMinute` | |
| 5 | `mBleTimeRange1.mEndHour` | |
| 6 | `mBleTimeRange1.mEndMinute` | |
| 7 | `mReserved` | 0 |

`getMLengthToWrite()` returns 8, and `BleTimeRange` contributes its usual five
bytes (enabled + start/end), the same shape documented in
[15-MONITORING-AND-ACTIONS](./15-MONITORING-AND-ACTIONS.md).

**Bytes 1 and 2 are one choice, not two switches.** The vendor's two toggles each
write the other as its negation:

```kotlin
// updateUI$2 — the "all day" toggle
settings.mEnabled            = isChecked
settings.mBleTimeRange1.mEnabled = !isChecked

// updateUI$3 — the "scheduled hours" toggle
settings.mEnabled            = !isChecked
settings.mBleTimeRange1.mEnabled = isChecked
```

`StandbyWatchFaceSettingsActivity` offers exactly three controls — the master
switch, all-day, scheduled — plus the time range. **There is no watch-face style
field anywhere in this key.** A UI offering "digital dial / pointer dial" has
nothing to put those values in; the closest thing is byte 0, where anything above
1 is out of range.

Unlike `STANDBY_SET`, this key **is** readable: the vendor app issues a `READ` on
connect and renders the screen from the reply.

---

## 3. What the watch answers

| Direction | 0x0241 | 0x0254 |
|---|---|---|
| `UPDATE` write | 1-byte body, `00` | empty body |
| `READ` | 8 bytes, constant (§4.2) | empty body |
| `CREATE` / `DELETE` | empty body | empty body |

```
Tx  02 41 00  01                          → Rx  02 41 00  00
Tx  02 54 00  01 00 01 00 00 00 1E 00     → Rx  02 54 00  (empty)
Tx  02 54 10                              → Rx  02 54 10  (empty)
```

### 3.1 Telling "unsupported" from "acknowledged"

This firmware answers **every** key it does not implement with an empty body,
write or read — `0x0233`, `0x0237`, `0x025A` all behave that way. A key it does
implement answers a write with an empty body too, so the ACK alone proves
nothing. The discriminator is the **read**:

```
Tx  02 08 10          → Rx  02 08 10  05        BACK_LIGHT: implemented
Tx  02 37 10          → Rx  02 37 10  (empty)   POWER_SAVE_MODE: not implemented
```

`0x0241` is the exception that gives itself away on the write side: it is the
only settings key observed answering a write with a **one-byte** body. Writing to
an unimplemented key returns nothing at all.

---

## 4. Kronos Thunder (`Kronos_Thunder_V006`) findings

### 4.1 Only the master switch exists

`0x0241` works — writing `01` turns the always-on dial on, confirmed by looking
at the watch. `0x0254` is inert: read and write both come back empty, so the
**schedule cannot be set on this watch**, and the all-day / scheduled pair has
nowhere to land.

### 4.2 The 0x0241 read is a decoy

```
Tx  02 41 10  → Rx  BE 00 00 00 00 00 00 00
```

Those eight bytes are **constant**. They do not change when standby is turned on,
turned off, or written with any payload from 1 to 16 bytes long, and byte 0
(`0xBE`) never matched any state the watch was in. Treat the key as write-only
and keep the phone's own copy, exactly as the vendor app does.

### 4.3 The schedule is not hiding in another key

Every settings key from `0x0201` to `0x027F` was swept with a `READ`
(see [19-FIRMWARE-CAPABILITY-SURVEY](./19-FIRMWARE-CAPABILITY-SURVEY.md)). No key
other than `0x0254` carries a standby time range, and an 8-byte
`BleStandbyWatchFaceSet` written to `0x0241` produces the same one-byte `00` ACK
as a bare on/off byte — the firmware does not look past byte 0.

---

## 5. How the phone should drive this

1. Send **both** keys on every change: `0x0241` with the on/off byte, `0x0254`
   with the full entity. A watch honours whichever it implements.
2. `READ` `0x0254` on connect. A body means the watch owns the state — render
   from it. An empty answer means this firmware has no schedule, and the UI
   should stop offering one.
3. Push `0x0241` from the phone's stored value on connect, the way the vendor app
   does — nothing on the watch will tell you what it currently is.
