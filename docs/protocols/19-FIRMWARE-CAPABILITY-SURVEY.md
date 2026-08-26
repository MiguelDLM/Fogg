# 19 — Firmware capability survey (Kronos Thunder)

**Sources:**
- Exhaustive `READ` sweep of `0x0201`–`0x027F` and `0x0401`–`0x0410` against a
  Kronos Thunder (`JL` / `AM05`, `Kronos_Thunder_V006`), 2026-08-26
- The 86 device-screen items of the vendor app (`com.smart.stf`), enumerated
  from its `DeviceFragment` getters
- `protocols/reference/blekey_map_authoritative.txt` for key names

---

## 0. Why a sweep

This watch answers `DEVICE_INFO` with the **V2 identity** payload — names,
platform, firmware version — and no capability list at all
(see [11-DEVICE-INFO-CAPABILITIES](./11-DEVICE-INFO-CAPABILITIES.md)). Every
`mSupportXxx` gate the vendor app relies on therefore reads as 0 here, and the
only way to learn what the firmware implements is to ask it.

**The test is a `READ`, not a write.** This firmware ACKs a write to *any* key,
implemented or not, with an empty body — so a write proves nothing. A `READ`
separates them:

```
Tx  02 08 10   → Rx  02 08 10  05        implemented
Tx  02 37 10   → Rx  02 37 10  (empty)   not implemented
```

Keys that are pure phone→watch pushes (notifications, weather, camera) return
nothing either way and are not covered by this method.

`SHUTDOWN` (`0x0222`), `FIND_WATCH` (`0x0234`) and `REALTIME_MEASUREMENT`
(`0x0236`) were skipped deliberately — reading them has side effects.

---

## 1. What the firmware implements

"Ours" is whether **this** app drives the key today.

| Key | Name | Len | Watch returns | Ours |
|---|---|---|---|---|
| `0x0202` | TIME_ZONE | 1 | `08` | sí |
| `0x0203` | — | 1 | `28` | sí |
| `0x0204` | FIRMWARE_VERSION | 3 | `00 00 06` | sí |
| `0x0205` | BLE_ADDRESS | 6 | `61 AE A5 11 08 73` | **no** |
| `0x0206` | USER_PROFILE | 11 | `00 01 1E 00 00 2A 43 00 00 8C 42` | sí |
| `0x0207` | STEP_GOAL | 4 | `00 00 27 10` | sí |
| `0x0208` | BACK_LIGHT | 1 | `05` | sí |
| `0x0209` | SEDENTARINESS | 6 | `FF 04 00 16 00 0F` | sí |
| `0x020A` | — | 16 | `00 00 16 00 08 00 00 00 00 00 00 00 …` | sí |
| `0x020B` | VIBRATION | 1 | `01` | **no** |
| `0x020C` | — | 5 | `00 00 00 17 3B` | sí |
| `0x020E` | HOUR_SYSTEM | 1 | `01` | sí |
| `0x020F` | LANGUAGE | 1 | `01` | **no** |
| `0x0211` | UNIT_SET | 1 | `00` | **no** |
| `0x0213` | FIND_PHONE | 1 | `FF` | **no** |
| `0x0214` | NOTIFICATION_REMINDER | 4 | `FD FF 3F 00` | **no** |
| `0x0215` | ANTI_LOST | 1 | `00` | sí |
| `0x0216` | — | 6 | `01 00 00 17 3B 1E` | sí |
| `0x0217` | — | 3 | `00 00 01` | **no** |
| `0x0218` | — | 4 | `D0 D0 D0 00` | **no** |
| `0x0219` | — | 6 | `64 01 28 00 AA 01` | **no** |
| `0x021A` | GIRL_CARE | 10 | `80 08 00 02 03 1A 08 1A 05 1C` | sí |
| `0x021B` | TEMPERATURE_DETECTING | 6 | `01 00 FF 12 00 0C` | **no** |
| `0x021D` | — | 1 | `00` | **no** |
| `0x021E` | — | 1 | `02` | **no** |
| `0x0221` | DRINK_WATER | 6 | `FF 05 00 16 00 0F` | sí |
| `0x0225` | BLOOD_OXYGEN_SET | 6 | `01 09 00 15 00 3C` | sí |
| `0x0226` | WASH_SET | 6 | `7F 08 00 12 00 3C` | **no** |
| `0x0228` | IBEACON_SET | 1 | `FF` | sí |
| `0x0229` | MAC_QRCODE | 1 | `FF` | **no** |
| `0x0235` | SET_WATCH_PASSWORD | 5 | `00 FF FF FF FF` | **no** |
| `0x0239` | CALORIES_GOAL | 4 | `00 04 93 E0` | **no** |
| `0x023A` | DISTANCE_GOAL | 4 | `00 00 0F A0` | **no** |
| `0x023B` | SLEEP_GOAL | 2 | `01 E0` | **no** |
| `0x023E` | DEVICE_INFO | 94 | `37 33 3A 30 38 3A 31 31 3A 41 35 3A …` | sí |
| `0x023F` | HR_WARNING_SET | 4 | `00 96 00 3C` | **no** |
| `0x0241` | STANDBY_SET | 8 | `BE 00 00 00 00 00 00 00` | sí |
| `0x0249` | PACKAGE_STATUS | 8 | `00 00 00 00 00 00 00 00` | **no** |
| `0x024E` | SOS_SET | 20 | `00 00 FF FF FF FF FF FF FF FF FF FF …` | **no** |
| `0x024F` | DEVICE_LANGUAGES | 24 | `01 00 00 00 00 12 01 04 05 10 16 17 …` | **no** |
| `0x0251` | GAME_TIME_REMINDER | 4 | `00 3C 00 00` | **no** |
| `0x025C` | — | 4 | `8C 5D 1D 3D` | **no** |
| `0x0260` | — | 1 | `01` | **no** |
| `0x0263` | — | 1 | `01` | **no** |
| `0x0264` | — | 3 | `00 00 08` | **no** |
| `0x026C` | GIRL_CARE_MONTHLY | 31 | `04 04 00 00 00 00 00 00 00 00 00 00 …` | **no** |

Names shown as `—` are absent from the authoritative `BleKey` map. Four of them
this app already uses: `0x0203` (time), `0x020A` (do-not-disturb), `0x020C`
(raise-to-wake) and `0x0216` (HR monitoring, named in
[02-COMMAND-PROTOCOL](./02-COMMAND-PROTOCOL.md)). The rest — `0x0217`, `0x0218`
(`D0 D0 D0 00`), `0x0219`, `0x021D`, `0x021E`, `0x025C`, `0x0260`, `0x0263`,
`0x0264` — are live on the watch and unnamed anywhere in the decompiled SDK.
Reverse engineering pending.

### 1.1 Content keys (0x04xx)

| Key | Name | Watch |
|---|---|---|
| `0x0402` | `MUSIC_CONTROL` | 7 bytes of playback state |
| `0x0404` / `0x0405` | `WEATHER_REALTIME` / forecast | implemented |
| `0x0407` | `WORLD_CLOCK` | implemented, paged — see [17](./17-WORLD-CLOCK-AND-STOCK.md) |
| `0x0408` | `STOCK` | implemented |
| `0x040C` / `0x040D` | weather variants | implemented |
| `0x0403` `0x0409` `0x040B` `0x040E` `0x040F` | schedule, SMS quick reply, news feed, login days, target completion | **empty — not implemented** |

---

## 2. Gaps against this app

Everything below is implemented by the firmware. The "app" column is the state
after the 2026-08-26 pass.

| Feature | Key | What the watch holds | App |
|---|---|---|---|
| Daily goals: calories, distance, sleep | `0x0239` `0x023A` `0x023B` | 500000, 5000 m, 480 min | **done** |
| Hand-wash reminder | `0x0226` | every day, 08:00–18:00, every 60 min | protocol done, **row disabled** — §2.10 |
| Heart-rate alarm, high/low | `0x023F` | high on at 150, low off at 60 bpm | **done** |
| Vibration repeats | `0x020B` | 1 | **done** |
| Units, metric/imperial | `0x0211` | metric | **done** |
| Watch language + language list | `0x020F` + `0x024F` | code 1, plus the 18 it ships | blocked, §2.7 |
| Per-app notification switches, watch side | `0x0214` | mask `FD FF 3F 00` | blocked, §2.7 |
| Automatic temperature measurement | `0x021B` | on, every 12 min | protocol done, **row disabled** — §2.10 |
| Watch password | `0x0235` | unset | **done** |
| SOS | `0x024E` | stores and reads back a number | protocol done, **row disabled** — §2.10 |
| Game-time reminder | `0x0251` | on, 30 min | **done** |
| Watch QR code | `0x0229` | available | missing |
| Monthly cycle calendar | `0x026C` | see [18-GIRL-CARE](./18-GIRL-CARE.md) | missing |
| Power off | `0x0222` | write-only, never probed | **done** |

### 2.1 Daily goals

All four goals are big-endian integers and all four are writable — verified by
writing and reading back on hardware:

```
Tx  STEP_GOAL: 9000
Tx  GOALS calories=500kcal distance=5km sleep=480min
Rx  goal key=0x07 raw=9000      steps
Rx  goal key=0x39 raw=500000    calories
Rx  goal key=0x3A raw=5000      metres
Rx  goal key=0x3B raw=480       minutes
```

**A missing ACK means the frame never left the phone, not that the key is
unsupported.** `STEP_GOAL` looked pinned at 10000 for a while: every write was
logged, the watch answered every *other* key in the same batch, and a read kept
returning 10000. The key was fine — `syncUserProfileAndGoals()` was kicking the
write queue by hand on top of a flush already in progress, so two
`writeCharacteristic` calls raced and the 0x0207 frame was overwritten before it
went out. When diagnosing a key that "does not stick", check that its ACK came
back at all before concluding anything about the firmware.

| Key | Width | Unit |
|---|---|---|
| `STEP_GOAL` `0x0207` | int32 | steps |
| `CALORIES_GOAL` `0x0239` | int32 | calories — the phone's kcal × 1000 |
| `DISTANCE_GOAL` `0x023A` | int32 | metres |
| `SLEEP_GOAL` `0x023B` | int16 | minutes |

Distance and sleep are pinned by the watch's own defaults (4000 against a 4 km
goal, 480 against 8 h). **The calorie unit is an inference**: the watch shipped
holding 300000 against a plausible 300 kcal default, so the scale is treated as
small calories. It has not been confirmed against the watch's own goal screen.

The watch accepts anything — a 10,000,000 calorie goal written during testing
was stored and read back unchanged — so the phone is the only thing clamping.

Note the original app only ever **reads** calories and distance; writing them is
this app going beyond it, which is why the read-back is wired as the check.

### 2.2 Hand-wash reminder

`BleWashSettings` is the same six bytes as the sedentary and drink-water
reminders — `[enabled<<7 | weekday mask, startH, startM, endH, endM, interval]`
— under key **`0x0226`**. An earlier build wrote it to `0x0228`, which is
`IBEACON_SET`, so it never reached the watch; nothing called it either.

### 2.3 Heart-rate alarm

`BleHrWarningSettings` is four bytes: `[high switch, high bpm, low switch,
low bpm]`. Round-tripped on hardware:

```
Tx  HR_WARNING high=true/150 low=false/60
Rx  HR_WARNING high=true/150 low=false/60      (after a reconnect)
```

### 2.4 Vibration and units

`VIBRATION` `0x020B` is one byte indexing `[off, once, twice, three times]` —
how many times the watch buzzes for a notification. `UNIT_SET` `0x0211` is one
byte, 0 metric and 1 imperial.

### 2.5 Automatic temperature, password, game time, power off

`BleTemperatureDetecting` (`0x021B`) is a `BleTimeRange` plus an interval — the
same six bytes as the HR and SpO2 windows, so it reuses the monitoring row:

```
Rx  monitoring key=0x1B enabled=true 0:255-18:0 every 12min   raw=01 00 FF 12 00 0C
```

Note the start minute of 255: that is what the watch shipped holding, not a
parse error. It is the watch's own uninitialised value and a write replaces it.

`BleSettingWatchPassword` (`0x0235`) is one enabled byte plus a fixed
four-character string; the watch stores `FF` per unset digit, which is why an
unset password reads back as `00 FF FF FF FF`.

`BleGameTimeReminder` (`0x0251`) is an enabled byte plus the minutes after which
the watch nags. Round-tripped on hardware:

```
Tx  GAME_TIME_REMINDER enabled=true after 30min
Rx  GAME_TIME enabled=true after 30min          (after a reconnect)
```

`SHUTDOWN` (`0x0222`) is a single byte and there is no undo from the phone — the
watch comes back only from its own button — so it is deliberately never read
during a sweep and the row confirms before sending.

### 2.6 SOS

`BleSOSSettings` (`0x024E`) is twenty bytes: an enabled flag, the number's
length, then the number in a fixed 18-byte field padded with `0xFF` — which is
exactly how an unconfigured SOS reads back:

```
Rx  02 4E 10  00 00 FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF
```

Written but **not yet round-tripped on hardware**.

### 2.7 Blocked on a lookup table

Two keys are understood structurally but cannot be shipped without a mapping the
decompiled sources do not hand over:

**Watch language.** `BleDeviceLanguages` (`0x024F`) decodes cleanly — one
current-code byte, four reserved, a count, then that many codes:

```
01 | 00 00 00 00 | 12 | 01 04 05 10 16 17 18 1B 1C 1D 1E 24 25 28 29 30 31 00
```

so this watch offers 18 languages and is on code 1. What is missing is
**code → language name**: the app's `language_*` string resources are referenced
only by the AI-translation table, which is a different code space. Shipping a
picker of bare numbers is not worth it.

**Per-app notification switches.** `BleNotificationSettings` (`0x0214`) is a
single little-endian int32 bitmask — this watch reports `FD FF 3F 00`, i.e. 22
switches with one off. What is missing is **bit → app**. The vendor's per-app UI
drives `NOTIFICATION_REMINDER2` (`0x0250`), which this firmware does not
implement, so its switch order is not observable here. Guessing bit positions
would silently mute the wrong app, which is worse than not shipping it.

### 2.8 Open: do goal writes reach the watch's own display?

The four goals store and read back, and the unit switch (`0x0211`) visibly
changes what the watch shows. Whether the **watch's own** goal gauges follow a
write is still unconfirmed. (The *app's* gauge not following was a separate,
phone-side bug: `StatusFragment` re-rendered on `health_*` and `weather_time`
but not on `goal_*`, so the ring kept the old target until the screen was
rebuilt. Fixed.) Either the watch renders them from a copy it
refreshes only at boot, or the calorie/distance scales are wrong in a way the
read-back cannot reveal — a `500000` written and echoed proves storage, not
interpretation. Unresolved; a watch reboot after a write is the cheapest test.

---

### 2.10 Answering a READ is not the same as having the feature

`WASH_SET` (0x0226) and `TEMPERATURE_DETECTING` (0x021B) both answer a READ with
a plausible stored setting, which is why the sweep counted them as implemented.
The Kronos Thunder nonetheless has no hand-wash reminder and no temperature
sensor in its own menus: the firmware keeps the registers because the SDK is
shared across a product family, not because this model does anything with them.

`SOS_SET` (0x024E) is the same story one step further along: the watch stores a
number and reads it back byte for byte, and still has no SOS feature to fire it.

The rows for all three are therefore commented out in `fragment_device.xml` and
`DeviceFragment`, and their keys dropped from `readWatchSettings()` and
`readReminders()`. The senders stay in `BleManager` — the encodings are verified
and correct for a watch that does have the hardware.

Disabled alongside them, for the reasons in §2.9: **find watch** (`0x0234`,
ACKed but never rings) and **shake wrist for photo** (no protocol behind it at
all).

The lesson for the sweep in §1: a non-empty READ proves the **key** exists, not
that the **feature** does. Only the device's own UI settles that.

---

### 2.9 Two app rows with nothing behind them

**"Shake wrist for photo"** (`btnShakeCamera`) has no protocol behind it at all.
Its handler opens `CameraActivity` — the same screen the plain camera row opens.
No key anywhere in the map carries a wrist-shake shutter mode, and the
`mSupportCamera` capability in
[09-SUPPORTED-PLATFORMS](./09-SUPPORTED-PLATFORMS.md) covers only the watch's
shutter button acting as a remote. The row is a duplicate wearing a name the
protocol cannot honour.

**Find watch** (`FIND_WATCH` 0x0234) is real and documented in
[15-MONITORING-AND-ACTIONS](./15-MONITORING-AND-ACTIONS.md), but its "verified"
claim rested on a bodyless ACK, which on this firmware is also what an
unimplemented key returns. The watch has not been observed ringing. That doc has
been corrected.

---

## 3. The vendor app has it, this firmware does not

All of these answer a `READ` with an empty body. Building them for this watch
would repeat the standby-schedule mistake documented in
[16-STANDBY-AND-AOD](./16-STANDBY-AND-AOD.md):

`SCHEDULE` `0x0403` · `SMS_QUICK_REPLY` `0x0409` · `NEWS_FEED` `0x040B` ·
`POWER_SAVE_MODE` `0x0237` · `FALL_SET` `0x0255` · `ECG_SET` `0x0268` ·
`PRESSURE_TIMING_MEASUREMENT` `0x0253` · `MEDICATION_REMINDER` `0x023D` ·
`MEDICATION_ALARM` `0x0246` · `NOTIFICATION_LIGHT_SCREEN_SET` `0x025A` ·
`WEAR_WAY` `0x0270` · `TOUCH_SET` `0x0278` · `SOS_CONTACT` `0x026B` ·
`WATCHFACE_INDEX` `0x026A` · `STANDBY_WATCH_FACE_SET` `0x0254`

Plus the whole branch that does not apply to this hardware at all: Alipay, NFC,
app and game stores, ebooks, audiobooks, voice recording, navigation, Qibla,
custom logo, contact sync.
