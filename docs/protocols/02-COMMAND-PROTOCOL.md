# 02 — Command Protocol

**Source files:**
- `decompiled_apk/sources/com/szabh/smable3/BleKey.java`
- `decompiled_apk/sources/com/szabh/smable3/BleCommand.java`
- `decompiled_apk/sources/com/szabh/smable3/BleKeyFlag.java`

---

## Table of Contents

1. [Overview](#1-overview)
2. [BleCommand — Command Categories](#2-blecommand--command-categories)
3. [BleKeyFlag — Operation Types](#3-blekeyflag--operation-types)
4. [BleKey — Encoding](#4-blekey--encoding)
5. [Packet Construction](#5-packet-construction)
6. [Complete Command Reference](#6-complete-command-reference)
   - [6.1 UPDATE (0x01) — OTA / Firmware](#61-update-0x01--ota--firmware)
   - [6.2 SET (0x02) — Device Settings](#62-set-0x02--device-settings)
   - [6.3 PUSH (0x04) — Phone to Watch Data](#63-push-0x04--phone-to-watch-data)
   - [6.4 DATA (0x05) — Health Data Retrieval](#64-data-0x05--health-data-retrieval)
   - [6.5 CONTROL (0x06) — Device Control](#65-control-0x06--device-control)
   - [6.6 IO (0x07) — File I/O](#66-io-0x07--file-io)
7. [Request / Response Patterns](#7-request--response-patterns)
8. [Connection Handshake Commands](#8-connection-handshake-commands)
9. [Quick-Lookup Index](#9-quick-lookup-index)

---

## 1. Overview

Every BLE message sent between the phone and the smartwatch encodes a **command** using three cooperating enumerations:

| Enum | Java Source | Purpose |
|------|-------------|---------|
| `BleCommand` | `BleCommand.java` | High-level category (SET, DATA, IO, …) |
| `BleKey` | `BleKey.java` | Specific operation within a category |
| `BleKeyFlag` | `BleKeyFlag.java` | Operation mode (read, write, create, delete, …) |

These three values are packed into three consecutive bytes of the packet frame:

```
Packet offset 6:  CMD   = BleCommand value  = mKey >>> 8
Packet offset 7:  KEY   = key index         = mKey & 0xFF
Packet offset 8:  FLAG  = BleKeyFlag value
```

`mKey` is a 16-bit integer defined on each `BleKey` enum constant. Its high byte carries the command category and its low byte carries the per-category index.

---

## 2. BleCommand — Command Categories

**Source:** `BleCommand.java`

```
Value   Hex    Name       Description
-----   ---    ----       -----------
  1     0x01   UPDATE     OTA firmware update commands
  2     0x02   SET        Read or write device settings
  3     0x03   CONNECT    Connection management (not widely used in key map)
  4     0x04   PUSH       Phone pushes live data to watch (notifications, weather, music)
  5     0x05   DATA       Retrieve stored health sensor data from watch
  6     0x06   CONTROL    Device control (camera shutter, call handling)
  7     0x07   IO         File transfer (watch faces, AGPS, fonts, contacts)
255     0xFF   NONE       Sentinel / no command
```

### Encoding in Packet

```
BleCommand occupies packet byte [6] (offset 6).

Examples:
  0x01 → UPDATE command group
  0x02 → SET command group
  0x05 → DATA command group
  0x07 → IO command group
```

---

## 3. BleKeyFlag — Operation Types

**Source:** `BleKeyFlag.java`

The flag byte controls how the receiving side interprets the command. It distinguishes between reading a value, writing it, paginating through records, or managing lifecycle (create/delete/reset).

```
Value   Hex    Name            Description
-----   ---    ----            -----------
  0     0x00   UPDATE          Write / update a value on the device
 16     0x10   READ            Request the current value of a field
 17     0x11   READ_CONTINUE   Continue a paginated read operation
 32     0x20   CREATE          Create or bind a new resource (e.g., IDENTITY binding)
 48     0x30   DELETE          Delete a resource (e.g., alarm, contact)
 64     0x40   RESET           Reset a setting to factory defaults
255     0xFF   NONE            No operation / sentinel
```

### Flag Usage Patterns

```
Read a setting:
  Phone sends:   FLAG = READ    (0x10), DATA = empty
  Watch replies: FLAG = READ    (0x10), DATA = current value   (HEADER bit4 set = REPLY)

Write a setting:
  Phone sends:   FLAG = UPDATE  (0x00), DATA = new value
  Watch replies: FLAG = UPDATE  (0x00), DATA = empty or echo   (HEADER bit4 set = REPLY)

Paginated data read:
  Phone sends:   FLAG = READ         (0x10), DATA = query params (e.g., date range)
  Watch replies: FLAG = READ         (0x10), DATA = first page
  Phone sends:   FLAG = READ_CONTINUE(0x11), DATA = continuation token
  Watch replies: FLAG = READ         (0x10), DATA = next page
  ... repeat until watch returns empty DATA or signals end

Create/bind:
  Phone sends:   FLAG = CREATE (0x20), DATA = resource data
  Watch replies: FLAG = CREATE (0x20), DATA = acknowledgement

Delete:
  Phone sends:   FLAG = DELETE (0x30), DATA = resource ID
  Watch replies: FLAG = DELETE (0x30), DATA = acknowledgement
```

---

## 4. BleKey — Encoding

**Source:** `BleKey.java`

Each `BleKey` enum constant stores a single 16-bit integer `mKey`. The two halves map directly to packet bytes:

```
mKey layout:
  Bits 15–8 (high byte):  BleCommand value
  Bits  7–0 (low byte):   per-command index

getMCommandRawValue() = mKey >>> 8    → packet byte [6]
getMKeyRawValue()     = mKey & 0xFF   → packet byte [7]
```

### Encoding Diagram

```
      mKey (uint16)
  +-------+-------+
  | CMD   | INDEX |
  | [15:8]| [7:0] |
  +-------+-------+
      |       |
      |       +---------> Packet byte [7]  (KEY)
      +-----------------> Packet byte [6]  (CMD)

Example: TIME = 0x0201
  High byte: 0x02 → BleCommand.SET
  Low  byte: 0x01 → index 1 within SET group
```

### Namespace Allocation

```
0x01xx  →  UPDATE group    (0x0101 – 0x01FF)
0x02xx  →  SET group       (0x0201 – 0x02FF)
0x03xx  →  CONNECT group   (0x0301 – 0x03FF)
0x04xx  →  PUSH group      (0x0401 – 0x04FF)
0x05xx  →  DATA group      (0x0501 – 0x05FF)
0x06xx  →  CONTROL group   (0x0601 – 0x06FF)
0x07xx  →  IO group        (0x0701 – 0x07FF)
0xFFFF  →  NONE
```

---

## 5. Packet Construction

### 5.1 Frame Reminder

```
 Byte:  [0]    [1]    [2][3]   [4][5]   [6]   [7]   [8]    [9+]
        MAGIC  HDR    LENGTH   CRC16    CMD   KEY   FLAG   DATA...
        0xAB   0x01   BE u16   BE u16
```

Full frame specification is in `01-BLE-COMMUNICATION.md`.

### 5.2 Construction Procedure

```
procedure buildPacket(bleKey, bleKeyFlag, data):
  cmd    = bleKey.mKey >>> 8
  key    = bleKey.mKey & 0xFF
  flag   = bleKeyFlag.value
  length = len(data) + 3          // +3 for cmd+key+flag bytes
  crc_input = [cmd, key, flag] + data
  crc16  = compute_crc16(crc_input)

  packet = [
    0xAB,                         // MAGIC
    0x01,                         // HEADER (VERSION bit set, no REPLY/NACK)
    (length >> 8) & 0xFF,         // LENGTH high byte
    length & 0xFF,                // LENGTH low byte
    (crc16 >> 8) & 0xFF,          // CRC16 high byte
    crc16 & 0xFF,                 // CRC16 low byte
    cmd,                          // CMD
    key,                          // KEY
    flag,                         // FLAG
  ] + data
  return packet
```

### 5.3 Worked Example — SET TIME

> **CORRECTION (verified against live capture):**
> `TIME (0x0201)` does **not** carry a 4-byte Unix timestamp. The real payload
> is a **6-byte local calendar**: `[year-2000, month(1-12), day, hour, minute,
> second]`. The 4-byte-Unix example below is incorrect for this device family
> and is kept only to show packet framing.

```
Goal: Set device time to 2026-02-05 08:49:11 (verified capture value)

BleKey   = TIME      → mKey = 0x0201
BleCommand           → cmd  = 0x0201 >>> 8 = 0x02
Key index            → key  = 0x0201 & 0xFF = 0x01
BleKeyFlag = UPDATE  → flag = 0x00
Data (6 bytes):        1A 02 05 08 31 0B
                       └ year-2000=0x1A(26), month=02, day=05, hh=08, mm=49, ss=11

CRC input: [02 01 00 1A 02 05 08 31 0B]
LENGTH:    6 + 3 = 9 = 0x0009

Final packet (from capture):
  AB 01 00 09 0F 76 02 01 00 1A 02 05 08 31 0B
```

### 5.4 Worked Example — READ FIRMWARE VERSION

```
Goal: Query firmware version string from device

BleKey   = FIRMWARE_VERSION → mKey = 0x0204
cmd = 0x02,  key = 0x04
BleKeyFlag = READ            → flag = 0x10
Data: empty (0 bytes)

LENGTH: 0 + 3 = 3 = 0x0003
CRC input: [02 04 10]

Final packet:
  AB 01 00 03 <CRC_H> <CRC_L> 02 04 10
```

### 5.5 Worked Example — READ HEART RATE DATA

```
Goal: Retrieve stored heart rate records (typically with date range payload)

BleKey   = HEART_RATE → mKey = 0x0503
cmd = 0x05,  key = 0x03
BleKeyFlag = READ      → flag = 0x10
Data: date range bytes (format defined in 03-HEALTH-DATA.md)

Final packet header:
  AB 01 00 <LEN_H> <LEN_L> <CRC_H> <CRC_L> 05 03 10 [date range...]
```

---

## 6. Complete Command Reference

> **REGENERATED 2026-08-22 from `decompiled_apk/sources/com/szabh/smable3/BleKey.java`.**
>
> The previous version of this section was **wrong from `0x0211` onward**: it
> listed a plausible-looking but invented key map that drifted out of alignment
> with the real enum. Among other things it placed `IDENTITY` at `0x0215` (the
> real value there is `ANTI_LOST`; `IDENTITY` is `0x0301`), `ANTI_LOST` at
> `0x0232`, and `ECG` at `0x050D` (the real value there is `PRESSURE`).
> That error propagated into `dial-sender`'s `BleKey.java`.
>
> The tables below are extracted mechanically from the 242 enum constants in
> the decompiled SDK. Where JADX replaced a numeric literal with a symbolic
> constant from an unrelated library, the value is reconstructed from its
> neighbours in the declaration order and marked _(value inferred)_. Every
> inferred value that `dial-sender` exercises in practice (`0x020A`, `0x020C`)
> is confirmed working against real hardware.
>
> The **dial-sender** column tracks what the companion app implements today:
> `send` = phone writes it, `read` = phone requests it, `recv` = watch-initiated
> and handled, `⚠️ partial` = frame recognised but payload discarded, `—` = not
> implemented.

### Key Namespace Allocation

| Group | Range | Count | Purpose |
|-------|-------|-------|---------|
| UPDATE | `0x0101`–`0x0102` | 2 | OTA firmware |
| SET | `0x0201`–`0x02FF` | 131 | Device settings + diagnostics streams |
| CONNECT | `0x0301`–`0x0302` | 2 | Binding / session |
| PUSH | `0x0401`–`0x0413` | 18 | Phone → watch live data |
| DATA | `0x0501`–`0x05FF` | 35 | Health record retrieval |
| CONTROL | `0x0601`–`0x0622` | 33 | Real-time control (often watch-initiated) |
| IO | `0x0701`–`0x0713` | 19 | Chunked file transfer |
| — | `0xFFFF` | 1 | `NONE` sentinel |

> **Not documented here: per-key allowed flags.** `BleKey.getBleKeyFlags()`
> (line 1243 of the decompiled enum) switches on a JADX-synthesised
> `$SwitchMap` array (`II0xO0.f26446oIX0oI`) whose contents were **not emitted
> by the decompiler**. Case labels are assigned in source-appearance order, not
> by ordinal, so the mapping cannot be recovered from these sources alone —
> assuming `case N` ↔ `ordinal N-1` yields `TIME` as READ-only, which
> contradicts the app writing `TIME` successfully. Determine allowed flags
> empirically per key.

### UPDATE (0x01) — OTA / Firmware

Initiate and transfer over-the-air firmware updates. See `06-OTA-FIRMWARE.md`.

| Key Name | mKey | dec | KEY byte | dial-sender | Description |
|----------|------|-----|----------|-------------|-------------|
| `OTA` | `0x0101` | 257 | `0x01` | — | Initiate standard OTA firmware update |
| `XMODEM` | `0x0102` | 258 | `0x02` | — | XMODEM-based firmware transfer |

### SET (0x02) — Device Settings

The largest group. Read with `FLAG=READ`, write with `FLAG=UPDATE`. Most keys are bidirectional. The `0x02F5`–`0x02FF` tail is a separate diagnostics/raw-stream block, not ordinary settings.

| Key Name | mKey | dec | KEY byte | dial-sender | Description |
|----------|------|-----|----------|-------------|-------------|
| `TIME` | `0x0201` | 513 | `0x01` | ✅ send | Clock sync - 6-byte calendar [yy-2000,MM,dd,hh,mm,ss], NOT Unix (see doc 10) |
| `TIME_ZONE` | `0x0202` | 514 | `0x02` | ✅ send | UTC offset |
| `POWER` | `0x0203` | 515 | `0x03` | ✅ read | Battery level / power state |
| `FIRMWARE_VERSION` | `0x0204` | 516 | `0x04` | ✅ read | Firmware version string |
| `BLE_ADDRESS` | `0x0205` | 517 | `0x05` | — | Device BLE MAC address |
| `USER_PROFILE` | `0x0206` | 518 | `0x06` | — | User biometrics (sex, age/birth, height, weight) |
| `STEP_GOAL` | `0x0207` | 519 | `0x07` | — | Daily step goal |
| `BACK_LIGHT` | `0x0208` | 520 | `0x08` | ✅ send | Backlight duration / brightness |
| `SEDENTARINESS` | `0x0209` | 521 | `0x09` | ✅ send | Sedentary reminder |
| `NO_DISTURB_RANGE` | `0x020A` | 522 | `0x0A` | ✅ send | Do-not-disturb time range _(value inferred — see note)_ |
| `VIBRATION` | `0x020B` | 523 | `0x0B` | — | Vibration strength / pattern |
| `GESTURE_WAKE` | `0x020C` | 524 | `0x0C` | ✅ send | Raise-to-wake gesture _(value inferred — see note)_ |
| `HR_ASSIST_SLEEP` | `0x020D` | 525 | `0x0D` | — | Use HR sensor to assist sleep staging |
| `HOUR_SYSTEM` | `0x020E` | 526 | `0x0E` | ✅ send | 12h vs 24h clock |
| `LANGUAGE` | `0x020F` | 527 | `0x0F` | — | Display language |
| `ALARM` | `0x0210` | 528 | `0x10` | — | Alarm clock entries (CREATE / UPDATE / DELETE) _(value inferred — see note)_ |
| `UNIT_SET` | `0x0211` | 529 | `0x11` | — | Measurement unit system (metric / imperial) _(value inferred — see note)_ |
| `COACHING` | `0x0212` | 530 | `0x12` | — | Coaching / training plan settings |
| `FIND_PHONE` | `0x0213` | 531 | `0x13` | ✅ recv | Find-phone trigger (watch -> phone) _(value inferred — see note)_ |
| `NOTIFICATION_REMINDER` | `0x0214` | 532 | `0x14` | — | Per-app notification reminder switches (v1) |
| `ANTI_LOST` | `0x0215` | 533 | `0x15` | ✅ send | Anti-lost alert |
| `HR_MONITORING` | `0x0216` | 534 | `0x16` | — | Continuous heart-rate monitoring settings _(value inferred — see note)_ |
| `UI_PACK_VERSION` | `0x0217` | 535 | `0x17` | — | Installed UI resource pack version _(value inferred — see note)_ |
| `LANGUAGE_PACK_VERSION` | `0x0218` | 536 | `0x18` | — | Installed language pack version _(value inferred — see note)_ |
| `SLEEP_QUALITY` | `0x0219` | 537 | `0x19` | — | Sleep quality feedback _(value inferred — see note)_ |
| `GIRL_CARE` | `0x021A` | 538 | `0x1A` | — | Menstrual cycle tracking settings |
| `TEMPERATURE_DETECTING` | `0x021B` | 539 | `0x1B` | — | Continuous body-temperature monitoring |
| `AEROBIC_EXERCISE` | `0x021C` | 540 | `0x1C` | — | Aerobic exercise settings |
| `TEMPERATURE_UNIT` | `0x021D` | 541 | `0x1D` | — | Celsius vs Fahrenheit _(value inferred — see note)_ |
| `DATE_FORMAT` | `0x021E` | 542 | `0x1E` | — | Date display format _(value inferred — see note)_ |
| `WATCH_FACE_SWITCH` | `0x021F` | 543 | `0x1F` | — | Active watch face switch _(value inferred — see note)_ |
| `AGPS_PREREQUISITE` | `0x0220` | 544 | `0x20` | ✅ send | AGPS prerequisite / handshake before AGPS_FILE transfer |
| `DRINK_WATER` | `0x0221` | 545 | `0x21` | — | Drink-water reminder |
| `SHUTDOWN` | `0x0222` | 546 | `0x22` | — | Power off the device |
| `APP_SPORT_DATA` | `0x0223` | 547 | `0x23` | — | App-driven sport session data pushed to watch |
| `REAL_TIME_HEART_RATE` | `0x0224` | 548 | `0x24` | — | Start / stop real-time HR streaming _(value inferred — see note)_ |
| `BLOOD_OXYGEN_SET` | `0x0225` | 549 | `0x25` | — | SpO2 monitoring settings |
| `WASH_SET` | `0x0226` | 550 | `0x26` | — | Hand-wash reminder |
| `WATCHFACE_ID` | `0x0227` | 551 | `0x27` | ✅ send | Watch face ID (sent before WATCH_FACE transfer) _(value inferred — see note)_ |
| `IBEACON_SET` | `0x0228` | 552 | `0x28` | — | iBeacon settings |
| `MAC_QRCODE` | `0x0229` | 553 | `0x29` | — | MAC address QR code |
| `REAL_TIME_TEMPERATURE` | `0x0230` | 560 | `0x30` | — | Real-time temperature streaming |
| `REAL_TIME_BLOOD_PRESSURE` | `0x0231` | 561 | `0x31` | — | Real-time blood-pressure streaming |
| `TEMPERATURE_VALUE` | `0x0232` | 562 | `0x32` | — | Temperature value _(value inferred — see note)_ |
| `GAME_SET` | `0x0233` | 563 | `0x33` | — | Watch game settings |
| `FIND_WATCH` | `0x0234` | 564 | `0x34` | — | Find-watch trigger (phone -> watch) |
| `SET_WATCH_PASSWORD` | `0x0235` | 565 | `0x35` | — | Watch screen-lock password |
| `REALTIME_MEASUREMENT` | `0x0236` | 566 | `0x36` | — | On-demand measurement trigger (HR / SpO2 / BP / stress) |
| `POWER_SAVE_MODE` | `0x0237` | 567 | `0x37` | — | Battery power-save mode |
| `BAC_SET` | `0x0238` | 568 | `0x38` | — | Blood alcohol content settings |
| `CALORIES_GOAL` | `0x0239` | 569 | `0x39` | — | Daily calorie goal |
| `DISTANCE_GOAL` | `0x023A` | 570 | `0x3A` | — | Daily distance goal |
| `SLEEP_GOAL` | `0x023B` | 571 | `0x3B` | — | Sleep duration goal |
| `LOVE_TAP_USER` | `0x023C` | 572 | `0x3C` | — | Love-tap paired user config |
| `MEDICATION_REMINDER` | `0x023D` | 573 | `0x3D` | — | Medication reminder |
| `DEVICE_INFO` | `0x023E` | 574 | `0x3E` | ⚠️ partial | Device capability block - see 11-DEVICE-INFO-CAPABILITIES.md |
| `HR_WARNING_SET` | `0x023F` | 575 | `0x3F` | — | Heart-rate high/low alert thresholds |
| `SLEEP_MONITORING` | `0x0240` | 576 | `0x40` | — | Sleep monitoring settings |
| `STANDBY_SET` | `0x0241` | 577 | `0x41` | — | Always-on / standby display settings |
| `BT_NAME` | `0x0242` | 578 | `0x42` | — | Bluetooth device name |
| `TUYA_KEY_SET` | `0x0243` | 579 | `0x43` | — | Tuya IoT key provisioning |
| `BAC_RESULT` | `0x0244` | 580 | `0x44` | — | BAC measurement result |
| `BAC_RESULT_SET` | `0x0245` | 581 | `0x45` | — | BAC result settings |
| `MEDICATION_ALARM` | `0x0246` | 582 | `0x46` | — | Medication alarm entries |
| `MATCH_SET` | `0x0247` | 583 | `0x47` | — | Sports match settings |
| `PACKAGE_STATUS` | `0x0249` | 585 | `0x49` | — | Installed resource package status |
| `ALIPAY_SET` | `0x024A` | 586 | `0x4A` | — | Alipay provisioning |
| `RECORD_PACKET` | `0x024B` | 587 | `0x4B` | — | Recording packet control |
| `BLE_ADDRESS_SET` | `0x024C` | 588 | `0x4C` | — | Write BLE MAC address (factory) |
| `NAVI_INFO` | `0x024D` | 589 | `0x4D` | — | Turn-by-turn navigation info |
| `SOS_SET` | `0x024E` | 590 | `0x4E` | — | SOS emergency settings |
| `DEVICE_LANGUAGES` | `0x024F` | 591 | `0x4F` | — | Languages available on device |
| `NOTIFICATION_REMINDER2` | `0x0250` | 592 | `0x50` | — | Per-app notification reminder switches (v2) |
| `GAME_TIME_REMINDER` | `0x0251` | 593 | `0x51` | — | Game time reminder |
| `DEVICE_SPORT_DATA` | `0x0252` | 594 | `0x52` | — | Device-side sport session data |
| `PRESSURE_TIMING_MEASUREMENT` | `0x0253` | 595 | `0x53` | — | Scheduled automatic stress measurement |
| `STANDBY_WATCH_FACE_SET` | `0x0254` | 596 | `0x54` | — | Standby (always-on) watch face selection |
| `FALL_SET` | `0x0255` | 597 | `0x55` | — | Fall detection settings |
| `BW_NAVI_INFO` | `0x0256` | 598 | `0x56` | — | BW navigation info variant _(value inferred — see note)_ |
| `CONNECT_REMINDER` | `0x0257` | 599 | `0x57` | — | Disconnect / reconnect reminder |
| `SDCARD_INFO` | `0x0258` | 600 | `0x58` | — | SD card capacity info |
| `ACTIVITY_DETAIL` | `0x0259` | 601 | `0x59` | — | Per-interval activity detail records |
| `NOTIFICATION_LIGHT_SCREEN_SET` | `0x025A` | 602 | `0x5A` | — | Wake screen on notification |
| `BLOOD_PRESSURE_CALIBRATION` | `0x025B` | 603 | `0x5B` | — | Blood-pressure calibration reference |
| `AIR_PRESSURE_CALIBRATION` | `0x025C` | 604 | `0x5C` | — | Barometric pressure calibration _(value inferred — see note)_ |
| `EARPHONE_POWER` | `0x025D` | 605 | `0x5D` | — | Earphone battery level _(value inferred — see note)_ |
| `EARPHONE_ANC_SET` | `0x025E` | 606 | `0x5E` | — | Earphone ANC mode _(value inferred — see note)_ |
| `EARPHONE_SOUND_EFFECTS_SET` | `0x025F` | 607 | `0x5F` | — | Earphone EQ / sound effects _(value inferred — see note)_ |
| `SCREEN_BRIGHTNESS_SET` | `0x0260` | 608 | `0x60` | — | Screen brightness _(value inferred — see note)_ |
| `EARPHONE_INFO` | `0x0261` | 609 | `0x61` | — | Earphone device info _(value inferred — see note)_ |
| `EARPHONE_STATE` | `0x0262` | 610 | `0x62` | — | Earphone connection state |
| `EARPHONE_CALL` | `0x0263` | 611 | `0x63` | — | Earphone call state |
| `GPS_FIRMWARE_VERSION` | `0x0264` | 612 | `0x64` | — | GPS chip firmware version |
| `GOMORE_SET` | `0x0265` | 613 | `0x65` | — | GoMore algorithm licence / settings |
| `RING_VIBRATION_SET` | `0x0266` | 614 | `0x66` | — | Ring + vibration mode |
| `NETWORK_FIRMWARE_VERSION` | `0x0267` | 615 | `0x67` | — | Network module firmware version |
| `ECG_SET` | `0x0268` | 616 | `0x68` | — | ECG measurement settings |
| `SPORT_DURATION_GOAL` | `0x0269` | 617 | `0x69` | — | Daily exercise duration goal |
| `WATCHFACE_INDEX` | `0x026A` | 618 | `0x6A` | — | Index of installed watch faces |
| `SOS_CONTACT` | `0x026B` | 619 | `0x6B` | — | SOS emergency contacts |
| `GIRL_CARE_MONTHLY` | `0x026C` | 620 | `0x6C` | — | Menstrual cycle monthly data |
| `GIRL_CARE_MENSTRUATION_UPDATE` | `0x026D` | 621 | `0x6D` | — | Menstrual period update from watch |
| `HEALTH_INDEX` | `0x026E` | 622 | `0x6E` | — | Composite health index |
| `CHECK_IN_EVERY_DAY` | `0x026F` | 623 | `0x6F` | — | Daily check-in |
| `WEAR_WAY` | `0x0270` | 624 | `0x70` | — | Worn on left / right wrist |
| `GESTURE_WAKE2` | `0x0271` | 625 | `0x71` | — | Raise-to-wake gesture (v2, with time window) |
| `EARPHONE_KEY` | `0x0272` | 626 | `0x72` | — | Earphone button mapping |
| `FIND_EARPHONE` | `0x0273` | 627 | `0x73` | — | Find-earphone trigger |
| `HANBAO_SET` | `0x0274` | 628 | `0x74` | — | HanBao vendor settings |
| `QIBLA_SET` | `0x0275` | 629 | `0x75` | — | Qibla direction settings |
| `CALIBRATION_DATA` | `0x0276` | 630 | `0x76` | — | Sensor calibration data |
| `BATTERY_USAGE` | `0x0277` | 631 | `0x77` | — | Per-feature battery usage breakdown |
| `TOUCH_SET` | `0x0278` | 632 | `0x78` | — | Touch panel settings |
| `DEVICE_UNIQUE_DATA_SET` | `0x0279` | 633 | `0x79` | — | Device unique data (factory) |
| `IPC_VERSION` | `0x027C` | 636 | `0x7C` | — | IPC camera module version |
| `IPC_STORES_INFO` | `0x027D` | 637 | `0x7D` | — | IPC stored media info |
| `SLEEP_QUALITY_SCORE` | `0x027F` | 639 | `0x7F` | — | Sleep quality score |
| `RELAX_REMINDER` | `0x0280` | 640 | `0x80` | — | Relax / breathe reminder _(value inferred — see note)_ |
| `CP_USER_INFO` | `0x0281` | 641 | `0x81` | — | CP user info |
| `TEST_INFO_SET` | `0x0282` | 642 | `0x82` | — | Factory test info |
| `POWER2` | `0x0283` | 643 | `0x83` | — | Extended power/battery report (v2) |
| `PPG_SHSY` | `0x02F5` | 757 | `0xF5` | — | SHSY PPG raw stream (diagnostics) |
| `GSENSOR_SHSY` | `0x02F6` | 758 | `0xF6` | — | SHSY G-sensor raw stream (diagnostics) |
| `LOCATION_GSV` | `0x02F7` | 759 | `0xF7` | — | GNSS GSV sentences (diagnostics) |
| `HR_RAW` | `0x02F8` | 760 | `0xF8` | — | Raw HR sensor stream (diagnostics) |
| `REALTIME_LOG` | `0x02F9` | 761 | `0xF9` | — | Real-time device log (diagnostics) _(value inferred — see note)_ |
| `GSENSOR_OUTPUT` | `0x02FA` | 762 | `0xFA` | — | G-sensor processed output (diagnostics) |
| `GSENSOR_RAW` | `0x02FB` | 763 | `0xFB` | — | G-sensor raw output (diagnostics) |
| `MOTION_DETECT` | `0x02FC` | 764 | `0xFC` | — | Motion detection events (diagnostics) |
| `LOCATION_GGA` | `0x02FD` | 765 | `0xFD` | — | GNSS GGA sentences (diagnostics) |
| `RAW_SLEEP` | `0x02FE` | 766 | `0xFE` | — | Raw sleep sensor data (diagnostics) _(value inferred — see note)_ |
| `NO_DISTURB_GLOBAL` | `0x02FF` | 767 | `0xFF` | — | Global do-not-disturb toggle _(value inferred — see note)_ |

### CONNECT (0x03) — Session Management

Binding and session establishment. See section 8.

| Key Name | mKey | dec | KEY byte | dial-sender | Description |
|----------|------|-----|----------|-------------|-------------|
| `IDENTITY` | `0x0301` | 769 | `0x01` | — | Device binding identity (CREATE on first pair) |
| `SESSION` | `0x0302` | 770 | `0x02` | ✅ send | Session establishment - the real handshake opener (see doc 10) |

### PUSH (0x04) — Phone to Watch Data

One-way push from phone to watch. Displayed immediately; not persisted as health records.

| Key Name | mKey | dec | KEY byte | dial-sender | Description |
|----------|------|-----|----------|-------------|-------------|
| `NOTIFICATION` | `0x0401` | 1025 | `0x01` | ✅ send | Incoming notification (SMS, call, app alert) - v1 |
| `MUSIC_CONTROL` | `0x0402` | 1026 | `0x02` | — | Now-playing metadata + playback state |
| `SCHEDULE` | `0x0403` | 1027 | `0x03` | — | Calendar schedule entry |
| `WEATHER_REALTIME` | `0x0404` | 1028 | `0x04` | ✅ send | Current weather conditions - v1 |
| `WEATHER_FORECAST` | `0x0405` | 1029 | `0x05` | ✅ send | Multi-day weather forecast - v1 |
| `HID` | `0x0406` | 1030 | `0x06` | — | HID remote-control mode |
| `WORLD_CLOCK` | `0x0407` | 1031 | `0x07` | — | World clock city list _(value inferred — see note)_ |
| `STOCK` | `0x0408` | 1032 | `0x08` | — | Stock ticker list |
| `SMS_QUICK_REPLY_CONTENT` | `0x0409` | 1033 | `0x09` | — | SMS quick-reply canned messages |
| `NOTIFICATION2` | `0x040A` | 1034 | `0x0A` | — | Incoming notification - v2 (longer text / emoji) |
| `NEWS_FEED` | `0x040B` | 1035 | `0x0B` | — | News feed headlines |
| `WEATHER_REALTIME2` | `0x040C` | 1036 | `0x0C` | ✅ send | Current weather - v2 _(value inferred — see note)_ |
| `WEATHER_FORECAST2` | `0x040D` | 1037 | `0x0D` | — | Weather forecast - v2 |
| `LOGIN_DAYS` | `0x040E` | 1038 | `0x0E` | — | Consecutive login days |
| `TARGET_COMPLETION` | `0x040F` | 1039 | `0x0F` | — | Goal completion notification |
| `AUDIO_TEXT` | `0x0410` | 1040 | `0x10` | — | Audio-to-text payload _(value inferred — see note)_ |
| `WEATHER_REALTIME3` | `0x0412` | 1042 | `0x12` | — | Current weather - v3 |
| `WEATHER_FORECAST3` | `0x0413` | 1043 | `0x13` | — | Weather forecast - v3 |

### DATA (0x05) — Health Data Retrieval

Pull stored sensor readings. Flow: `READ` -> page -> `READ_CONTINUE` -> page -> ... -> empty payload ends the run.

| Key Name | mKey | dec | KEY byte | dial-sender | Description |
|----------|------|-----|----------|-------------|-------------|
| `ACTIVITY_REALTIME` | `0x0501` | 1281 | `0x01` | — | Live steps / calories / distance stream (no sync needed) |
| `ACTIVITY` | `0x0502` | 1282 | `0x02` | ✅ read | Stored activity records - steps, calories, distance |
| `HEART_RATE` | `0x0503` | 1283 | `0x03` | ✅ read | Heart rate measurements |
| `BLOOD_PRESSURE` | `0x0504` | 1284 | `0x04` | ✅ read | Systolic / diastolic blood pressure |
| `SLEEP` | `0x0505` | 1285 | `0x05` | ✅ read | Sleep stage records |
| `WORKOUT` | `0x0506` | 1286 | `0x06` | ✅ read | Workout session records - v1 |
| `LOCATION` | `0x0507` | 1287 | `0x07` | ✅ read | GPS track points |
| `TEMPERATURE` | `0x0508` | 1288 | `0x08` | ✅ read | Body temperature readings |
| `BLOOD_OXYGEN` | `0x0509` | 1289 | `0x09` | ✅ read | SpO2 (blood oxygen) readings |
| `HRV` | `0x050A` | 1290 | `0x0A` | ✅ read | Heart rate variability - v1 |
| `LOG` | `0x050B` | 1291 | `0x0B` | — | Device diagnostic log text |
| `SLEEP_RAW_DATA` | `0x050C` | 1292 | `0x0C` | — | Raw sleep sensor records |
| `PRESSURE` | `0x050D` | 1293 | `0x0D` | ✅ read | Mental stress index |
| `WORKOUT2` | `0x050E` | 1294 | `0x0E` | ✅ read | Workout session records - v2 (richer fields) |
| `MATCH_RECORD` | `0x050F` | 1295 | `0x0F` | — | Sports match record - v1 |
| `BLOOD_GLUCOSE` | `0x0510` | 1296 | `0x10` | — | Blood glucose readings |
| `BODY_STATUS` | `0x0511` | 1297 | `0x11` | — | Body status index |
| `MIND_STATUS` | `0x0512` | 1298 | `0x12` | — | Mind / mood status index |
| `CALORIE_INTAKE` | `0x0513` | 1299 | `0x13` | — | Calorie intake log |
| `FOOD_BALANCE` | `0x0514` | 1300 | `0x14` | — | Food balance log _(value inferred — see note)_ |
| `BAC` | `0x0515` | 1301 | `0x15` | — | Blood alcohol content readings |
| `MATCH_RECORD2` | `0x0516` | 1302 | `0x16` | — | Sports match record - v2 |
| `AVG_HEART_RATE` | `0x0517` | 1303 | `0x17` | — | Daily average heart rate |
| `ALIPAY_BIND_INFO` | `0x0518` | 1304 | `0x18` | — | Alipay binding info |
| `RRI` | `0x0519` | 1305 | `0x19` | — | R-R intervals (raw basis for HRV) |
| `ECG` | `0x0520` | 1312 | `0x20` | — | ECG waveform data |
| `HANBAO_VIBRATION` | `0x0521` | 1313 | `0x21` | — | HanBao vibration records |
| `SOS_CALL_LOG` | `0x0522` | 1314 | `0x22` | — | SOS call log |
| `WORKOUT3` | `0x0523` | 1315 | `0x23` | ✅ read | Workout session records - v3 (with GPS polyline) |
| `DAILY_POWER` | `0x0525` | 1317 | `0x25` | — | Daily energy / body battery |
| `TRAINING_STATUS` | `0x0526` | 1318 | `0x26` | — | Training status (GoMore) |
| `VITALITY` | `0x0527` | 1319 | `0x27` | — | Vitality score (GoMore) |
| `HRV2` | `0x0528` | 1320 | `0x28` | — | Heart rate variability - v2 |
| `RESPIRATORY_RATE` | `0x0529` | 1321 | `0x29` | — | Respiratory rate |
| `DATA_ALL` | `0x05FF` | 1535 | `0xFF` | — | Bulk request - all health data types at once |

### CONTROL (0x06) — Device Control

Real-time control. Many of these are **watch-initiated** — the watch sends them to the phone unsolicited.

| Key Name | mKey | dec | KEY byte | dial-sender | Description |
|----------|------|-----|----------|-------------|-------------|
| `CAMERA` | `0x0601` | 1537 | `0x01` | ✅ send | Remote camera shutter |
| `REQUEST_LOCATION` | `0x0602` | 1538 | `0x02` | — | Watch requests current location from phone _(value inferred — see note)_ |
| `INCOMING_CALL` | `0x0603` | 1539 | `0x03` | — | Incoming call state - accept / reject from watch _(value inferred — see note)_ |
| `APP_SPORT_STATE` | `0x0604` | 1540 | `0x04` | — | App-driven sport session state (start/pause/stop) |
| `CLASSIC_BLUETOOTH_STATE` | `0x0605` | 1541 | `0x05` | — | Classic Bluetooth (A2DP/HFP) state |
| `DEVICE_SMS_QUICK_REPLY` | `0x0607` | 1543 | `0x07` | — | Watch sends a quick-reply SMS request |
| `LOVE_TAP` | `0x0608` | 1544 | `0x08` | — | Love-tap event |
| `FACTORY_TEST` | `0x0609` | 1545 | `0x09` | — | Factory test |
| `DOUBLE_SCREEN` | `0x060A` | 1546 | `0x0A` | — | Dual-screen control |
| `BAC_CALIBRATION` | `0x060B` | 1547 | `0x0B` | — | BAC calibration |
| `INCOMING_CALL_RING` | `0x060C` | 1548 | `0x0C` | — | Incoming call ringtone control |
| `SPORT_END_NOTIFY` | `0x060D` | 1549 | `0x0D` | — | Watch signals workout ended |
| `FILE_PATH` | `0x060E` | 1550 | `0x0E` | — | File path exchange |
| `APP_OP` | `0x060F` | 1551 | `0x0F` | — | App operation request from watch _(value inferred — see note)_ |
| `HANBAO_VIBRATION_STATE` | `0x0610` | 1552 | `0x10` | — | HanBao vibration state |
| `MEASUREMENT` | `0x0611` | 1553 | `0x11` | — | On-demand measurement result |
| `GAME_CONTROL` | `0x0612` | 1554 | `0x12` | — | Watch game control input |
| `APP_API_VERSION` | `0x0613` | 1555 | `0x13` | — | App API version negotiation _(value inferred — see note)_ |
| `DEVICE_OP` | `0x0614` | 1556 | `0x14` | — | Generic device operation |
| `RECEIVE_DEVICE_FILE` | `0x0615` | 1557 | `0x15` | — | Receive a file initiated by the device |
| `AI_COMMAND` | `0x0616` | 1558 | `0x16` | — | AI assistant command |
| `SG_RGB_SET` | `0x0617` | 1559 | `0x17` | — | SG RGB lighting |
| `SG_SLEEP_SET` | `0x0618` | 1560 | `0x18` | — | SG sleep settings |
| `SG_EQ_SET` | `0x0619` | 1561 | `0x19` | — | SG equaliser _(value inferred — see note)_ |
| `SG_KEY_SET` | `0x061A` | 1562 | `0x1A` | — | SG key mapping |
| `SG_CAMERA_SET` | `0x061B` | 1563 | `0x1B` | — | SG camera settings |
| `IPC_WIFI_STATE` | `0x061C` | 1564 | `0x1C` | — | IPC Wi-Fi state |
| `SG_KEYWORD_AWAKE` | `0x061D` | 1565 | `0x1D` | — | SG keyword wake |
| `IPC_WIFI_INFO` | `0x061E` | 1566 | `0x1E` | — | IPC Wi-Fi credentials |
| `AI_COACH_PLAN` | `0x061F` | 1567 | `0x1F` | — | AI coaching plan _(value inferred — see note)_ |
| `WEAR_STATE` | `0x0620` | 1568 | `0x20` | — | Wear detection state |
| `SG_TEST` | `0x0621` | 1569 | `0x21` | — | SG test mode |
| `LACTATE_THRESHOLD` | `0x0622` | 1570 | `0x22` | — | Lactate threshold _(value inferred — see note)_ |

### IO (0x07) — File I/O

Chunked binary transfer. `FLAG=CREATE` opens the stream, `FLAG=UPDATE` carries chunks.

| Key Name | mKey | dec | KEY byte | dial-sender | Description |
|----------|------|-----|----------|-------------|-------------|
| `WATCH_FACE` | `0x0701` | 1793 | `0x01` | ✅ send | Compiled watch face binary (.bin) |
| `AGPS_FILE` | `0x0702` | 1794 | `0x02` | ✅ send | Assisted-GPS ephemeris data |
| `FONT_FILE` | `0x0703` | 1795 | `0x03` | — | Custom font file |
| `CONTACT` | `0x0704` | 1796 | `0x04` | — | Contact list entry |
| `UI_FILE` | `0x0705` | 1797 | `0x05` | — | UI resource pack |
| `DEVICE_FILE` | `0x0706` | 1798 | `0x06` | — | Generic device file |
| `LANGUAGE_FILE` | `0x0707` | 1799 | `0x07` | — | Language / localisation pack |
| `BRAND_INFO_FILE` | `0x0708` | 1800 | `0x08` | — | OEM brand information pack _(value inferred — see note)_ |
| `QRCODE` | `0x0709` | 1801 | `0x09` | — | QR code image - v1 |
| `THIRD_PARTY_DATA` | `0x070A` | 1802 | `0x0A` | — | Third-party data blob _(value inferred — see note)_ |
| `QRCODE2` | `0x070B` | 1803 | `0x0B` | — | QR code image - v2 _(value inferred — see note)_ |
| `LOGO_FILE` | `0x070C` | 1804 | `0x0C` | — | Boot logo image _(value inferred — see note)_ |
| `OTA_FILE` | `0x070D` | 1805 | `0x0D` | — | OTA firmware image _(value inferred — see note)_ |
| `GPS_FIRMWARE_FILE` | `0x070E` | 1806 | `0x0E` | — | GPS chip firmware image _(value inferred — see note)_ |
| `CONTACT_SORT` | `0x070F` | 1807 | `0x0F` | — | Contact sort order _(value inferred — see note)_ |
| `NAVI_IMAGE` | `0x0710` | 1808 | `0x10` | — | Navigation turn icon image _(value inferred — see note)_ |
| `APP_FILE` | `0x0711` | 1809 | `0x11` | — | Watch app package _(value inferred — see note)_ |
| `FAT_BIN_FILE` | `0x0712` | 1810 | `0x12` | — | FAT filesystem image _(value inferred — see note)_ |
| `AGPS_FILE2` | `0x0713` | 1811 | `0x13` | — | Assisted-GPS ephemeris data - v2 _(value inferred — see note)_ |

---

## 7. Request / Response Patterns

### 7.1 Simple Read / Write

```
Host  →  Watch:  [MAGIC][HDR=0x01][LEN][CRC][CMD][KEY][FLAG=0x10][query...]
Watch →  Host:   [MAGIC][HDR=0x11][LEN][CRC][CMD][KEY][FLAG=0x10][value...]
                                         ^
                                         HEADER bit4 set = REPLY
```

### 7.2 Paginated Data Read

```
Host  →  Watch:  CMD=0x05  KEY=0x03  FLAG=0x10  DATA=[date range]
                 (HEART_RATE READ)

Watch →  Host:   CMD=0x05  KEY=0x03  FLAG=0x10  DATA=[records page 1]

Host  →  Watch:  CMD=0x05  KEY=0x03  FLAG=0x11  DATA=[]
                 (READ_CONTINUE)

Watch →  Host:   CMD=0x05  KEY=0x03  FLAG=0x10  DATA=[records page 2]

... repeat ...

Watch →  Host:   CMD=0x05  KEY=0x03  FLAG=0x10  DATA=[]
                 (empty DATA = end of records)
```

### 7.3 File Transfer Write

```
Host  →  Watch:  CMD=0x07  KEY=0x01  FLAG=0x20  DATA=[file_size, file_id, ...]
                 (WATCH_FACE CREATE — initiate transfer)

Watch →  Host:   CMD=0x07  KEY=0x01  FLAG=0x20  DATA=[ack]

Host  →  Watch:  CMD=0x07  KEY=0x01  FLAG=0x00  DATA=[chunk_1]
                 (WATCH_FACE UPDATE — first chunk)

Host  →  Watch:  CMD=0x07  KEY=0x01  FLAG=0x00  DATA=[chunk_2]
... repeat for all chunks ...

Watch →  Host:   CMD=0x07  KEY=0x01  FLAG=0x00  DATA=[ack / crc_check]
                 (transfer complete)
```

### 7.4 NACK Handling

```
If the device cannot process a command it replies with HEADER bit5 set:

Watch →  Host:   [MAGIC][HDR=0x31][LEN][CRC][CMD][KEY][FLAG][error_code?]
                          ^
                          0x31 = REPLY(0x10) | NACK(0x20) | VERSION(0x01)

Host should:
  1. Log the error (with CMD/KEY for identification)
  2. Optionally retry once
  3. Surface error to application layer if retry fails
```

---

## 8. Connection Handshake Commands

Verified against a live capture.
The `0x03` CONNECT group holds only two keys:

| Key | mKey | Role |
|-----|------|------|
| `IDENTITY` | `0x0301` | Device binding identity. **Not used by the real app's opener.** |
| `SESSION`  | `0x0302` | Session establishment — this is what actually opens the connection. |

> Earlier revisions of this document claimed the handshake opened with
> `IDENTITY (0x0215, CREATE)` plus a random nonce. Both halves were wrong:
> `0x0215` is `ANTI_LOST`, and no bind/login step exists at all.

### Step 1 — SESSION (CREATE)

```
BleKey:     SESSION  (0x0302)
BleKeyFlag: CREATE   (0x20)
Data:       4 bytes — fixed FF FF FF FF (not a nonce; the value never varies)

Purpose:    Opens the session. The watch replies immediately; there is no
            challenge/response and no stored secret.

Packet (byte-for-byte from capture, CRC is constant because the payload is):
  AB 01 00 07 B1 B2 03 02 20 FF FF FF FF
```

### Step 2 — TIME_ZONE (UPDATE)

```
BleKey:     TIME_ZONE  (0x0202)
BleKeyFlag: UPDATE     (0x00)

Purpose:    Sent before TIME. The watch keeps a local-time clock, so the
            offset must land first.
```

### Step 3 — TIME (UPDATE)

```
BleKey:     TIME    (0x0201)
BleKeyFlag: UPDATE  (0x00)
Data:       6 bytes — [year-2000, month(1-12), day, hour, minute, second]
                      LOCAL time, not UTC, and not a Unix timestamp.

Example:    2026-02-05 08:49:11  ->  1A 02 05 08 31 0B
```

### Step 4 — HOUR_SYSTEM (UPDATE)

```
BleKey:     HOUR_SYSTEM  (0x020E)
BleKeyFlag: UPDATE       (0x00)

Purpose:    12h/24h display preference.
```

### Step 5 — Read device state

```
FIRMWARE_VERSION (0x0204, READ)  -> version string
POWER            (0x0203, READ)  -> battery percentage
DEVICE_INFO      (0x023E, READ)  -> full capability block
                                    see 11-DEVICE-INFO-CAPABILITIES.md
```

`DEVICE_INFO` is the one that matters for feature gating: it carries the list of
`BleKey` values the specific watch actually supports, plus ~100 capability
flags. Everything after the handshake should be driven from it rather than
assumed.

---

## 9. Quick-Lookup Index

All 242 `BleKey` constants sorted by value, with current `dial-sender` coverage.

```
dec     hex     name                            dial-sender
  257   0x0101  OTA                             —
  258   0x0102  XMODEM                          —
  513   0x0201  TIME                            send
  514   0x0202  TIME_ZONE                       send
  515   0x0203  POWER                           read
  516   0x0204  FIRMWARE_VERSION                read
  517   0x0205  BLE_ADDRESS                     —
  518   0x0206  USER_PROFILE                    —
  519   0x0207  STEP_GOAL                       —
  520   0x0208  BACK_LIGHT                      send
  521   0x0209  SEDENTARINESS                   send
  522   0x020A  NO_DISTURB_RANGE                send
  523   0x020B  VIBRATION                       —
  524   0x020C  GESTURE_WAKE                    send
  525   0x020D  HR_ASSIST_SLEEP                 —
  526   0x020E  HOUR_SYSTEM                     send
  527   0x020F  LANGUAGE                        —
  528   0x0210  ALARM                           —
  529   0x0211  UNIT_SET                        —
  530   0x0212  COACHING                        —
  531   0x0213  FIND_PHONE                      recv
  532   0x0214  NOTIFICATION_REMINDER           —
  533   0x0215  ANTI_LOST                       send
  534   0x0216  HR_MONITORING                   —
  535   0x0217  UI_PACK_VERSION                 —
  536   0x0218  LANGUAGE_PACK_VERSION           —
  537   0x0219  SLEEP_QUALITY                   —
  538   0x021A  GIRL_CARE                       —
  539   0x021B  TEMPERATURE_DETECTING           —
  540   0x021C  AEROBIC_EXERCISE                —
  541   0x021D  TEMPERATURE_UNIT                —
  542   0x021E  DATE_FORMAT                     —
  543   0x021F  WATCH_FACE_SWITCH               —
  544   0x0220  AGPS_PREREQUISITE               send
  545   0x0221  DRINK_WATER                     —
  546   0x0222  SHUTDOWN                        —
  547   0x0223  APP_SPORT_DATA                  —
  548   0x0224  REAL_TIME_HEART_RATE            —
  549   0x0225  BLOOD_OXYGEN_SET                —
  550   0x0226  WASH_SET                        —
  551   0x0227  WATCHFACE_ID                    send
  552   0x0228  IBEACON_SET                     —
  553   0x0229  MAC_QRCODE                      —
  560   0x0230  REAL_TIME_TEMPERATURE           —
  561   0x0231  REAL_TIME_BLOOD_PRESSURE        —
  562   0x0232  TEMPERATURE_VALUE               —
  563   0x0233  GAME_SET                        —
  564   0x0234  FIND_WATCH                      —
  565   0x0235  SET_WATCH_PASSWORD              —
  566   0x0236  REALTIME_MEASUREMENT            —
  567   0x0237  POWER_SAVE_MODE                 —
  568   0x0238  BAC_SET                         —
  569   0x0239  CALORIES_GOAL                   —
  570   0x023A  DISTANCE_GOAL                   —
  571   0x023B  SLEEP_GOAL                      —
  572   0x023C  LOVE_TAP_USER                   —
  573   0x023D  MEDICATION_REMINDER             —
  574   0x023E  DEVICE_INFO                     ~partial
  575   0x023F  HR_WARNING_SET                  —
  576   0x0240  SLEEP_MONITORING                —
  577   0x0241  STANDBY_SET                     —
  578   0x0242  BT_NAME                         —
  579   0x0243  TUYA_KEY_SET                    —
  580   0x0244  BAC_RESULT                      —
  581   0x0245  BAC_RESULT_SET                  —
  582   0x0246  MEDICATION_ALARM                —
  583   0x0247  MATCH_SET                       —
  585   0x0249  PACKAGE_STATUS                  —
  586   0x024A  ALIPAY_SET                      —
  587   0x024B  RECORD_PACKET                   —
  588   0x024C  BLE_ADDRESS_SET                 —
  589   0x024D  NAVI_INFO                       —
  590   0x024E  SOS_SET                         —
  591   0x024F  DEVICE_LANGUAGES                —
  592   0x0250  NOTIFICATION_REMINDER2          —
  593   0x0251  GAME_TIME_REMINDER              —
  594   0x0252  DEVICE_SPORT_DATA               —
  595   0x0253  PRESSURE_TIMING_MEASUREMENT     —
  596   0x0254  STANDBY_WATCH_FACE_SET          —
  597   0x0255  FALL_SET                        —
  598   0x0256  BW_NAVI_INFO                    —
  599   0x0257  CONNECT_REMINDER                —
  600   0x0258  SDCARD_INFO                     —
  601   0x0259  ACTIVITY_DETAIL                 —
  602   0x025A  NOTIFICATION_LIGHT_SCREEN_SET   —
  603   0x025B  BLOOD_PRESSURE_CALIBRATION      —
  604   0x025C  AIR_PRESSURE_CALIBRATION        —
  605   0x025D  EARPHONE_POWER                  —
  606   0x025E  EARPHONE_ANC_SET                —
  607   0x025F  EARPHONE_SOUND_EFFECTS_SET      —
  608   0x0260  SCREEN_BRIGHTNESS_SET           —
  609   0x0261  EARPHONE_INFO                   —
  610   0x0262  EARPHONE_STATE                  —
  611   0x0263  EARPHONE_CALL                   —
  612   0x0264  GPS_FIRMWARE_VERSION            —
  613   0x0265  GOMORE_SET                      —
  614   0x0266  RING_VIBRATION_SET              —
  615   0x0267  NETWORK_FIRMWARE_VERSION        —
  616   0x0268  ECG_SET                         —
  617   0x0269  SPORT_DURATION_GOAL             —
  618   0x026A  WATCHFACE_INDEX                 —
  619   0x026B  SOS_CONTACT                     —
  620   0x026C  GIRL_CARE_MONTHLY               —
  621   0x026D  GIRL_CARE_MENSTRUATION_UPDATE   —
  622   0x026E  HEALTH_INDEX                    —
  623   0x026F  CHECK_IN_EVERY_DAY              —
  624   0x0270  WEAR_WAY                        —
  625   0x0271  GESTURE_WAKE2                   —
  626   0x0272  EARPHONE_KEY                    —
  627   0x0273  FIND_EARPHONE                   —
  628   0x0274  HANBAO_SET                      —
  629   0x0275  QIBLA_SET                       —
  630   0x0276  CALIBRATION_DATA                —
  631   0x0277  BATTERY_USAGE                   —
  632   0x0278  TOUCH_SET                       —
  633   0x0279  DEVICE_UNIQUE_DATA_SET          —
  636   0x027C  IPC_VERSION                     —
  637   0x027D  IPC_STORES_INFO                 —
  639   0x027F  SLEEP_QUALITY_SCORE             —
  640   0x0280  RELAX_REMINDER                  —
  641   0x0281  CP_USER_INFO                    —
  642   0x0282  TEST_INFO_SET                   —
  643   0x0283  POWER2                          —
  757   0x02F5  PPG_SHSY                        —
  758   0x02F6  GSENSOR_SHSY                    —
  759   0x02F7  LOCATION_GSV                    —
  760   0x02F8  HR_RAW                          —
  761   0x02F9  REALTIME_LOG                    —
  762   0x02FA  GSENSOR_OUTPUT                  —
  763   0x02FB  GSENSOR_RAW                     —
  764   0x02FC  MOTION_DETECT                   —
  765   0x02FD  LOCATION_GGA                    —
  766   0x02FE  RAW_SLEEP                       —
  767   0x02FF  NO_DISTURB_GLOBAL               —
  769   0x0301  IDENTITY                        —
  770   0x0302  SESSION                         send
 1025   0x0401  NOTIFICATION                    send
 1026   0x0402  MUSIC_CONTROL                   —
 1027   0x0403  SCHEDULE                        —
 1028   0x0404  WEATHER_REALTIME                send
 1029   0x0405  WEATHER_FORECAST                send
 1030   0x0406  HID                             —
 1031   0x0407  WORLD_CLOCK                     —
 1032   0x0408  STOCK                           —
 1033   0x0409  SMS_QUICK_REPLY_CONTENT         —
 1034   0x040A  NOTIFICATION2                   —
 1035   0x040B  NEWS_FEED                       —
 1036   0x040C  WEATHER_REALTIME2               send
 1037   0x040D  WEATHER_FORECAST2               —
 1038   0x040E  LOGIN_DAYS                      —
 1039   0x040F  TARGET_COMPLETION               —
 1040   0x0410  AUDIO_TEXT                      —
 1042   0x0412  WEATHER_REALTIME3               —
 1043   0x0413  WEATHER_FORECAST3               —
 1281   0x0501  ACTIVITY_REALTIME               —
 1282   0x0502  ACTIVITY                        read
 1283   0x0503  HEART_RATE                      read
 1284   0x0504  BLOOD_PRESSURE                  read
 1285   0x0505  SLEEP                           read
 1286   0x0506  WORKOUT                         read
 1287   0x0507  LOCATION                        read
 1288   0x0508  TEMPERATURE                     read
 1289   0x0509  BLOOD_OXYGEN                    read
 1290   0x050A  HRV                             read
 1291   0x050B  LOG                             —
 1292   0x050C  SLEEP_RAW_DATA                  —
 1293   0x050D  PRESSURE                        read
 1294   0x050E  WORKOUT2                        read
 1295   0x050F  MATCH_RECORD                    —
 1296   0x0510  BLOOD_GLUCOSE                   —
 1297   0x0511  BODY_STATUS                     —
 1298   0x0512  MIND_STATUS                     —
 1299   0x0513  CALORIE_INTAKE                  —
 1300   0x0514  FOOD_BALANCE                    —
 1301   0x0515  BAC                             —
 1302   0x0516  MATCH_RECORD2                   —
 1303   0x0517  AVG_HEART_RATE                  —
 1304   0x0518  ALIPAY_BIND_INFO                —
 1305   0x0519  RRI                             —
 1312   0x0520  ECG                             —
 1313   0x0521  HANBAO_VIBRATION                —
 1314   0x0522  SOS_CALL_LOG                    —
 1315   0x0523  WORKOUT3                        read
 1317   0x0525  DAILY_POWER                     —
 1318   0x0526  TRAINING_STATUS                 —
 1319   0x0527  VITALITY                        —
 1320   0x0528  HRV2                            —
 1321   0x0529  RESPIRATORY_RATE                —
 1535   0x05FF  DATA_ALL                        —
 1537   0x0601  CAMERA                          send
 1538   0x0602  REQUEST_LOCATION                —
 1539   0x0603  INCOMING_CALL                   —
 1540   0x0604  APP_SPORT_STATE                 —
 1541   0x0605  CLASSIC_BLUETOOTH_STATE         —
 1543   0x0607  DEVICE_SMS_QUICK_REPLY          —
 1544   0x0608  LOVE_TAP                        —
 1545   0x0609  FACTORY_TEST                    —
 1546   0x060A  DOUBLE_SCREEN                   —
 1547   0x060B  BAC_CALIBRATION                 —
 1548   0x060C  INCOMING_CALL_RING              —
 1549   0x060D  SPORT_END_NOTIFY                —
 1550   0x060E  FILE_PATH                       —
 1551   0x060F  APP_OP                          —
 1552   0x0610  HANBAO_VIBRATION_STATE          —
 1553   0x0611  MEASUREMENT                     —
 1554   0x0612  GAME_CONTROL                    —
 1555   0x0613  APP_API_VERSION                 —
 1556   0x0614  DEVICE_OP                       —
 1557   0x0615  RECEIVE_DEVICE_FILE             —
 1558   0x0616  AI_COMMAND                      —
 1559   0x0617  SG_RGB_SET                      —
 1560   0x0618  SG_SLEEP_SET                    —
 1561   0x0619  SG_EQ_SET                       —
 1562   0x061A  SG_KEY_SET                      —
 1563   0x061B  SG_CAMERA_SET                   —
 1564   0x061C  IPC_WIFI_STATE                  —
 1565   0x061D  SG_KEYWORD_AWAKE                —
 1566   0x061E  IPC_WIFI_INFO                   —
 1567   0x061F  AI_COACH_PLAN                   —
 1568   0x0620  WEAR_STATE                      —
 1569   0x0621  SG_TEST                         —
 1570   0x0622  LACTATE_THRESHOLD               —
 1793   0x0701  WATCH_FACE                      send
 1794   0x0702  AGPS_FILE                       send
 1795   0x0703  FONT_FILE                       —
 1796   0x0704  CONTACT                         —
 1797   0x0705  UI_FILE                         —
 1798   0x0706  DEVICE_FILE                     —
 1799   0x0707  LANGUAGE_FILE                   —
 1800   0x0708  BRAND_INFO_FILE                 —
 1801   0x0709  QRCODE                          —
 1802   0x070A  THIRD_PARTY_DATA                —
 1803   0x070B  QRCODE2                         —
 1804   0x070C  LOGO_FILE                       —
 1805   0x070D  OTA_FILE                        —
 1806   0x070E  GPS_FIRMWARE_FILE               —
 1807   0x070F  CONTACT_SORT                    —
 1808   0x0710  NAVI_IMAGE                      —
 1809   0x0711  APP_FILE                        —
 1810   0x0712  FAT_BIN_FILE                    —
 1811   0x0713  AGPS_FILE2                      —
65535   0xFFFF  NONE                            —
```
