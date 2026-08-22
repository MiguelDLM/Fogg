# CO-FIT / SMA Smartwatch Protocol Documentation

## Project Goal

This documentation set captures the reverse-engineered BLE communication protocols used by the CO-FIT / SMA smartwatch ecosystem (app package: `com.szabh.smable3`). The goal is to provide a complete, self-contained reference that allows an open-source, privacy-respecting alternative application to fully replicate all device functionality without reliance on the original vendor APK.

All documentation was derived by static analysis of the decompiled Android APK located in `decompiled_apk/sources/com/szabh/smable3/`.

---

## Document Index

| # | File | Description |
|---|------|-------------|
| 01 | [01-BLE-COMMUNICATION.md](./01-BLE-COMMUNICATION.md) | BLE transport layer: GATT services, packet framing, CRC16, connection lifecycle, DFU address calculation |
| 02 | [02-COMMAND-PROTOCOL.md](./02-COMMAND-PROTOCOL.md) | Command system: `BleKey`, `BleCommand`, `BleKeyFlag` enumerations, packet construction, full command map (all 242 keys, regenerated from the enum) |
| 03 | [03-HEALTH-DATA.md](./03-HEALTH-DATA.md) | Health data entities: heart rate, SpO2, blood pressure, sleep stages, temperature, HRV, ECG, blood glucose |
| 04 | [04-NOTIFICATIONS.md](./04-NOTIFICATIONS.md) | Phone notification forwarding: payload format, app ID mapping, notification lifecycle |
| 05 | [05-WATCHFACE-DIAL.md](./05-WATCHFACE-DIAL.md) | Watch face compilation pipeline, binary format, transfer procedure, widget configuration |
| 06 | [06-OTA-FIRMWARE.md](./06-OTA-FIRMWARE.md) | Over-the-air firmware update: OTA vs XMODEM paths, MTK GATT service, DFU handshake, progress reporting |
| 07 | [07-PRIVACY-ANALYSIS.md](./07-PRIVACY-ANALYSIS.md) | Privacy and tracking analysis: telemetry endpoints, PII collected, cloud sync targets, third-party SDKs |
| 08 | [08-DATA-SYNC.md](./08-DATA-SYNC.md) | Health data synchronisation: sync flow, pagination, cloud API endpoints, data serialisation |
| 09 | [09-SUPPORTED-PLATFORMS.md](./09-SUPPORTED-PLATFORMS.md) | Hardware platforms and device support: JieLi, Nordic, Goodix, MTK chipset variants, feature matrix |
| 11 | [11-DEVICE-INFO-CAPABILITIES.md](./11-DEVICE-INFO-CAPABILITIES.md) | `DEVICE_INFO` (0x023E) capability block: supported-key list, ~100 feature flags, IO buffer size, exact field order |
| 12 | [12-MUSIC-CONTROL.md](./12-MUSIC-CONTROL.md) | `MUSIC_CONTROL` (0x0402) both directions: entity/attribute metadata pushes, the playback-info wire format, the seven transport commands |
| 13 | [13-ALARM.md](./13-ALARM.md) | `ALARM` (0x0210): the 28-byte item, the enabled/repeat bit packing, the Monday-first weekday mask, and the five flag operations |

---

## Architecture Overview

```
+--------------------+        BLE (Nordic UART Service)       +--------------------+
|   Android App      |  =====================================> |   Smartwatch       |
| com.szabh.smable3  |  <=====================================  | CO-FIT / SMA HW    |
+--------------------+                                         +--------------------+
        |
        | GATT Write Characteristic  (TX): 6e400002-...
        | GATT Notify Characteristic (RX): 6e400003-...
        |
        v
+--------------------+
|  BleMessenger      |  Fragments messages, queues writes (LinkedBlockingQueue)
|  BleParser         |  Reassembles fragments, dispatches to handlers
|  MessageFactory    |  Encodes / decodes packet frame (magic, header, CRC16)
+--------------------+
        |
        v
+--------------------+
|  BleKey / BleCommand / BleKeyFlag
|  240+ typed commands organised by category (SET, DATA, PUSH, IO, ...)
+--------------------+
```

---

## Packet Frame (Quick Reference)

```
Byte:  00    01    02-03   04-05   06    07    08    09+
       MAGIC HDR   LEN     CRC16   CMD   KEY   FLAG  DATA...
       0xAB
```

- **MAGIC**: always `0xAB`
- **LEN**: big-endian `uint16`, value = `payload_length + 3`
- **CRC16**: computed over bytes `[06 .. end]` using vendor lookup table
- Full details: [01-BLE-COMMUNICATION.md](./01-BLE-COMMUNICATION.md)

---

## Reading Order

For a new implementor, the recommended reading order is:

1. **01-BLE-COMMUNICATION.md** — understand transport before anything else
2. **02-COMMAND-PROTOCOL.md** — understand how commands are encoded
3. **03-HEALTH-DATA.md** — primary use case (health data retrieval)
4. **08-DATA-SYNC.md** — understand the sync lifecycle end-to-end
5. Remaining docs as needed for specific features

---

## Source File Locations

All source references in this documentation use paths relative to the repository root:

```
decompiled_apk/sources/com/szabh/smable3/
├── BleKey.java
├── BleCommand.java
├── BleKeyFlag.java
└── component/
    ├── BleConnector.java
    ├── BleMessenger.java
    ├── BleParser.java
    ├── BleCache.java
    └── MessageFactory.java
```

---

## Conventions Used in This Documentation

| Notation | Meaning |
|----------|---------|
| `0xAB` | Hexadecimal byte literal |
| `uint8`, `uint16`, `int32` | Unsigned / signed integer widths |
| BE | Big-endian byte order |
| LE | Little-endian byte order |
| `[N]` | Byte at offset N in the packet |
| `[A:B]` | Byte range A inclusive to B exclusive |
| `>>>` | Java unsigned right-shift |
