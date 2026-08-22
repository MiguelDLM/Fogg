# Fogg — Smartwatch Toolkit

![Version](https://img.shields.io/badge/version-v0.4-22D3EE?style=flat-square)
![Android](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Jieli%20%2F%20HK89-0D1117?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Devices](https://img.shields.io/badge/devices-1013%2B%20supported-FF9800?style=flat-square)
![Languages](https://img.shields.io/badge/languages-ES%20%7C%20EN-7E57C2?style=flat-square)

A reverse-engineered companion toolkit for smartwatches running the **Jieli / HK89** chipset, sold under dozens of OEM brands (SMA, CoFit, X-View, i-Health, and more). Consists of an Android companion app and a cross-platform desktop dial designer.

---

## Components

| Component | Description | Tech |
|-----------|-------------|------|
| [`dial-sender/`](dial-sender/) | Android companion app — BLE sync, health data, dial transfer | Android (Java), Chaquopy |
| [`dial-designer/`](dial-designer/) | Desktop dial editor — visual canvas for `.bin` dial files | Tauri + React + TypeScript |
| [`docs/protocols/`](docs/protocols/) | Reverse-engineered BLE protocol documentation | Markdown |
| [`comp_decomp.py`](comp_decomp.py) | Dial `.bin` compiler / decompiler (shared by both components) | Python |

---

## Android App — `dial-sender`

### Features

- **BLE Connection** — auto-connects to bonded or active GATT devices; supports multi-device picker
- **Dial Transfer** — sends custom `.bin` watchface files over BLE with progress dialog
- **Health Sync** — reads all sensor data from the watch: steps, calories, distance, heart rate, blood pressure, SpO₂, sleep, stress, HRV, GPS routes, workouts
- **Health Charts** — per-metric detail view with day/week/month tabs and sleep stage timeline
- **Sport Tracker** — phone-side stopwatch with 12 activity types, swipe-to-delete history, session detail with GPS route map
- **Weather** — syncs Open-Meteo forecast to the watch; 7-day forecast card with condition labels
- **Export** — ZIP with 10 CSV files (one per data type) + README; compatible with any spreadsheet
- **Import** — BLE log files (no root), SQLite DB (root), CSV or exported ZIP
- **Watch Settings** — backlight duration, raise-to-wake, DND, sedentary reminder, anti-lost, AGPS sync
- **Multilanguage** — English and Spanish; runtime switching in Settings tab

### Requirements

- Android 7.0+ (API 24)
- Bluetooth LE
- Location permission (BLE scan on Android < 12)

### Build

```bash
cd dial-sender
./gradlew assembleDebug -x generateDebugPythonRequirements
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> The `-x generateDebugPythonRequirements` flag skips a Chaquopy pip task that fails on Python 3.14 (pip 20.1 incompatibility on the host).

### Supported Devices

Over **1,013 device models** are known to use the Jieli/HK89 chipset — see [`devices_supported.csv`](devices_supported.csv) for the full list.

---

## Desktop Designer — `dial-designer`

A Tauri + React desktop app for visually composing watchface layers and compiling them to `.bin`.

### Features

- Layer canvas with background image, digital time digits, analog hands, health metrics, battery
- Recolor tool for palette swaps on SVG assets
- Font digit creator for custom digit sprite sheets
- Compiles to the exact `.bin` format the watch expects

### Build

```bash
cd dial-designer
npm install
npm run tauri dev      # development
npm run tauri build    # production bundle
```

---

## Protocol Documentation

The [`docs/protocols/`](docs/protocols/) directory contains the full reverse-engineered BLE protocol:

| Doc | Contents |
|-----|----------|
| [01 — BLE Communication](docs/protocols/01-BLE-COMMUNICATION.md) | GATT service/characteristic layout, connection flow |
| [02 — Command Protocol](docs/protocols/02-COMMAND-PROTOCOL.md) | Packet framing, BleKey codes, flags |
| [03 — Health Data](docs/protocols/03-HEALTH-DATA.md) | Binary entity layouts for all sensor types |
| [04 — Notifications](docs/protocols/04-NOTIFICATIONS.md) | Message forwarding protocol |
| [05 — Watchface / Dial](docs/protocols/05-WATCHFACE-DIAL.md) | `.bin` file format, layer encoding |
| [06 — OTA Firmware](docs/protocols/06-OTA-FIRMWARE.md) | Firmware update flow |
| [07 — Privacy Analysis](docs/protocols/07-PRIVACY-ANALYSIS.md) | Data sent to vendor servers |
| [08 — Data Sync](docs/protocols/08-DATA-SYNC.md) | Full sync flow, AGPS, weather |
| [09 — Supported Platforms](docs/protocols/09-SUPPORTED-PLATFORMS.md) | OEM brand list |

---

## Architecture Notes

### Data Storage

All health data is persisted in **SharedPreferences** (`dial_sender_prefs`) as comma-separated `timestamp:value` strings:

| Key | Format | Notes |
|-----|--------|-------|
| `health_steps` | `ts:steps` | Daily step total |
| `health_heart_rate` | `ts:bpm` | Per-measurement |
| `health_sleep` | `ts:mode:soft_min:deep_min` | Mode codes: 1=deep, 2=light, 3=awake, 34=REM |
| `health_blood_pressure` | `ts:systolic/diastolic` | mmHg |
| `health_blood_oxygen` | `ts:spo2` | % |
| `health_stress` | `ts:level` | 0–100 |
| `health_workout` | `ts:end:dur:alt:...:avgBpm:maxBpm` | 14 fields |
| `health_location` | `ts:mode:altitude:lon:lat` | GPS route points |

### Timestamp Convention

All timestamps stored as **Unix epoch seconds** (UTC). The watch uses a **2000-01-01 epoch** — `BleManager` adds `946684800` during parsing to convert.

### BLE Key Codes (health data)

| Data type | BleKey |
|-----------|--------|
| Activity (steps/cal/dist) | `0x0502` |
| Heart rate | `0x0503` |
| Blood pressure | `0x0504` |
| Sleep | `0x0505` |
| Location (GPS) | `0x0507` |
| Blood oxygen | `0x0509` |
| Stress (BleKey name: PRESSURE) | `0x050D` |
| Workout | `0x050E` |

---

## Contributing

1. Protocol improvements: open a PR against `docs/protocols/`
2. New device support: add to `devices_supported.csv` with model name
3. Translations: add/edit `dial-sender/app/src/main/res/values-<lang>/strings.xml`

---

*Reverse-engineered for interoperability. Not affiliated with Jieli Technology or any OEM brand.*
