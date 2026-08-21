# 11 — DEVICE_INFO (0x023E)

**Sources:**
- `decompiled_apk/sources/com/szabh/smable3/entity/BleDeviceInfo.java` (`decode()`, line 1244) — V1
- `decompiled_apk/sources/com/szabh/smable3/entity/BleDeviceInfo2.java` (`decode()`) — V2
- `com/bestmafen/baseble/data/BleReadable.java` — reader primitives
- Live capture from a Kronos Thunder (`JL` / `AM05`), 2026-08-22

---

## 0. Two payloads answer this one key

`0x023E` has **two incompatible reply shapes** that share no layout. The SDK
models them as separate entities (`BleDeviceInfo` / `BleDeviceInfo2`, with
separate `onReadDeviceInfo` / `onReadDeviceInfo2` callbacks), and **the
dispatcher that chooses between them is not present in the decompiled sources.**

| | V1 — capability block | V2 — identity block |
|---|---|---|
| Opens with | `int32` id, then the supported-key list | BLE MAC as an ASCII string |
| Supported-key list | yes (`mDataKeys`) | **no** |
| Capability flags | ~100 | **none** |
| Version strings | no | firmware / UI / language |
| `mIOBufferSize` | yes | no |

**A real Kronos Thunder replies with V2.** Parsing that payload as V1 does not
fail loudly — it reads the MAC string's ASCII bytes as a key list and yields
six nonsense keys (`0x383A, 0x3131, 0x3A41, …`), a garbage device name, and
`ioBufferSize=0`. Sniff the shape before parsing:

```
V2 if payload[0..16] matches XX:XX:XX:XX:XX:XX (hex digits, colons at 2,5,8,11,14)
   and payload[17] == 0x00
otherwise V1
```

Firmware version is not a usable discriminator here; the wire shape is.

---

## 1. Why this matters

`DEVICE_INFO` is the only message that tells you **what a specific watch can
actually do**. The Jieli/HK89 protocol is shared by 1000+ OEM models with wildly
different feature sets; the same `BleKey` may be fully supported, silently
ignored, or NACK'd depending on the unit.

**On V1**, the block carries two things worth having:

- **`mDataKeys`** — the explicit list of `BleKey` values this watch supports.
  Drives which health keys are worth polling instead of probing all 35.
- **~100 `mSupportXxx` flags** — per-feature booleans for UI gating.

Plus two values that are currently guessed elsewhere:

- **`mIOBufferSize`** — the real stream chunk size for `0x07xx` file transfers.
- **`mSleepAlgorithmType`** — which sleep-staging algorithm the data expects.

**On V2** none of that exists. A V2 watch is telling you who it is, not what it
can do, so feature gating has to fall back to the client's own defaults. Treat
an absent key list as "the watch did not say", never as "supports nothing" —
gating a sync on an empty list syncs zero keys and reports success.

---

## 2. Request

```
BleKey:     DEVICE_INFO  (0x023E)
BleCommand: 0x02   KEY: 0x3E   FLAG: READ (0x10)
Data:       empty

Packet:
  AB 01 00 03 <CRC_H> <CRC_L> 02 3E 10
```

The watch replies with `HEADER` bit4 set (REPLY) and the payload below starting
at **frame offset 9**.

---

## 3. Payload encoding rules

All multi-byte integers are **big-endian** (`BleByteArray` defaults to
`ByteOrder.BIG_ENDIAN`).

| Reader | Meaning |
|--------|---------|
| `readInt8()` / `readUInt8()` | one byte |
| `readUInt16()` | two bytes, big-endian |
| `readInt32()` | four bytes, big-endian |
| `readStringUtil(0)` | bytes up to the next `0x00`, then the `0x00` is consumed |
| `readBytesUtil(0)` | same, but returned raw |

Fields are read **strictly in order** with no length prefixes or tags — a single
misread byte desynchronises everything after it. Firmware that predates a field
simply truncates the payload, so **every read past the end must be treated as
"unsupported" (0), not as an error.**

---

## 4. V2 — identity block field order

| # | Field | Type | Kronos Thunder |
|---|-------|------|----------------|
| 1 | `mBleAddress` | string | `73:08:11:A5:AE:61` |
| 2 | `mClassicAddress` | string | `""` (empty — BLE-only unit) |
| 3 | `mFirmwareVersion` | 3 × uint8 → `a.b.c` | `0.0.6` |
| 4 | `mUiVersion` | 3 × uint8 → `a.b.c` | `0.0.1` |
| 5 | `mLanguageVersion` | 3 × uint8 → `a.b.c` | `0.0.0` |
| 6 | `mLanguageCode` | uint8 | `0` |
| 7 | `mBleName` | string | `Kronos Thunder` |
| 8 | `mPlatform` | string | `JL` (JieLi) |
| 9 | `mPrototype` | string | `AM05` |
| 10 | `mFirmwareFlag` | string | `G6_NEW_Kronos_Thunder` |
| 11 | `mFullVersion` | string | `Kronos_Thunder_V006` |

### 4.0 Worked example — the real reply

94-byte payload, frame body from offset 9:

```
37 33 3A 30 38 3A 31 31 3A 41 35 3A 41 45 3A 36 31 00   "73:08:11:A5:AE:61"
00                                                      mClassicAddress = ""
00 00 06                                                firmware 0.0.6
00 00 01                                                UI       0.0.1
00 00 00                                                language 0.0.0
00                                                      language code 0
4B 72 6F 6E 6F 73 20 54 68 75 6E 64 65 72 00            "Kronos Thunder"
4A 4C 00                                                "JL"
41 4D 30 35 00                                          "AM05"
47 36 5F 4E 45 57 5F 4B 72 6F 6E 6F 73 5F 54 68 75 6E 64 65 72 00   "G6_NEW_Kronos_Thunder"
4B 72 6F 6E 6F 73 5F 54 68 75 6E 64 65 72 5F 56 30 30 36 00         "Kronos_Thunder_V006"
```

Full frame as received:

```
AB 11 00 61 8B 47 02 3E 10 <94 bytes above>
```

---

## 5. V1 — capability block field order

### 5.1 Header

| # | Field | Type | Notes |
|---|-------|------|-------|
| 1 | `mId` | int32 | Device id |
| 2 | `mDataKeys` | uint16[] until `0x00` | **Supported `BleKey` list.** Big-endian pairs, e.g. `05 02 05 03 …` = `ACTIVITY`, `HEART_RATE`, … |
| 3 | `mBleName` | string | Advertised BLE name |
| 4 | `mBleAddress` | string | BLE MAC, upper-cased by the app |
| 5 | `mPlatform` | string | Chipset platform id |
| 6 | `mPrototype` | string | Hardware prototype id |
| 7 | `mFirmwareFlag` | string | May embed a name override — see §5 |
| 8 | `mAGpsType` | int8 | Which AGPS ephemeris format to fetch |
| 9 | `mIOBufferSize` | uint16 | **Stream chunk size for `0x07xx` transfers** |
| 10 | `mWatchFaceType` | int8 | Watch face binary format variant |
| 11 | `mClassicAddress` | string | Classic BT MAC, upper-cased |
| 12 | `mHideDigitalPower` | int8 | UI hint |
| 13 | `mShowAntiLostSwitch` | int8 | UI hint |
| 14 | `mSleepAlgorithmType` | int8 | Sleep staging algorithm selector |

### 5.2 Capability flags, block A

Read as consecutive `int8` values, in this exact order:

```
mSupportDateFormatSet               mSupportReadDeviceInfo
mSupportTemperatureUnitSet          mSupportDrinkWaterSet
mSupportChangeClassicBluetoothState mSupportAppSport
mSupportBloodOxyGenSet              mSupportWashSet
mSupportRequestRealtimeWeather      mSupportHID
mSupportIBeaconSet                  mSupportWatchFaceId
mSupportNewTransportMode            mSupportJLTransport
mSupportFindWatch                   mSupportWorldClock
mSupportStock                       mSupportSMSQuickReply
mSupportNoDisturbSet                mSupportSetWatchPassword
mSupportRealTimeMeasurement         mSupportPowerSaveMode
mSupportLoveTap                     mSupportNewsfeed
mSupportMedicationReminder          mSupportQrcode
mSupportWeather2                    mSupportAlipay
mSupportStandbySet                  mSupport2DAcceleration
mSupportTuyaKey                     mSupportMedicationAlarm
mSupportReadPackageStatus           mSupportContactSize      <- value x 10
mSupportVoice                       mSupportNavigation
mSupportHrWarnSet
```

> `mSupportContactSize` is **multiplied by 10** by the app: the byte holds tens
> of contacts, so `0x0A` means 100 contacts, not 10.

### 5.3 Name resolution (interrupts the flag run)

After `mSupportHrWarnSet` the parser does name fixups, then reads one more
string. See §6 — the string read is **part of the wire format** and must not be
skipped.

| # | Field | Type |
|---|-------|------|
| — | `mBleDefaultName` | string |

### 5.4 Capability flags, block B

Consecutive `int8` again:

```
mSupportMusicTransfer               mSupportNoDisturbSet2
mSupportSOSSet                      mSupportReadLanguages
mSupportGirlCareReminder            mSupportAppPushSwitch
mSupportReceiptCodeSize             mSupportGameTimeReminder
mSupportMyCardCodeSize              mSupportDeviceSportData
mSupportEbookTransfer               mSupportDoubleScreen
mSupportCustomLogo                  mSupportPressureTimingMeasurement
mSupportTimerStandbySet             mSupportSOSSet2
mSupportFallSet                     mSupportWalkAndBike
mSupportConnectReminder             mSupportSDCardInfo
mSupportIncomingCallRing            mSupportNotificationLightScreenSet
mSupportBloodPressureCalibration    mSupportOTAFile
mSupportGPSFirmwareFile             mSupportGoMoreSet
mSupportRingVibrationSet            mSupportNetwork
mSupportContactSort                 mQrcodeSize
mQrcodeContentSize   <- readUInt8   mSupportStringQrcode
mSupportWatchFaceIndex              mSupportSosContact
mSupportGirlCareMonthly             mSupportWearWay
mSupportGestureWake2                mSupportNavImage
mSupportVoiceMaxLength              mSupportAudioBooks
mSupportStudyCards                  mSupportAppStore
mSupportSHSYAlgorithm               mSupportQiblaSet
mSupportMeasurementBloodGlucose     mSupportGameControls
mSupportBatteryUsage                mSupportAITranslation
mSupportSimultaneousTranslation     mSupportTouchSet
mSupportIMEISet                     mSupportQuran
mSupportSyncAGPSInBackground        mSupportRestoreFactory
mSupportRecordNote                  mSupportSleepScore
mSupportWatchface2                  mSupportAICoach
mSupportCrossAppTranslation         mSupportRelaxReminder
mSupportPower2
```

`mSupportPower2` is the last field the SDK reads. Newer firmware may append
more; ignore trailing bytes.

---

## 6. The `mFirmwareFlag` name override (V1)

Between the two flag blocks the app rewrites the device name:

```
if mFirmwareFlag contains "<>":
    candidate = mFirmwareFlag.split("<>")[1]
    if candidate is non-empty and != mBleName:
        mBleCustomName = mBleName      # keep what the user renamed it to
        mBleName       = candidate     # OEM marketing name wins

mBleDefaultName = readStringUtil(0)    # ALWAYS read — consumes wire bytes

if mBleDefaultName is non-empty:
    mBleCustomName = mBleName
    mBleName       = mBleDefaultName
```

The separator constant is `RAW_NAME_SEPARATOR = "<>"` (`BleDeviceInfo.java:410`).

The important part for a reimplementation is that `mBleDefaultName` is read
**unconditionally**. Treating it as optional shifts every field in block B by
the length of that string.

---

## 7. Reading `mDataKeys` safely (V1)

```
keys = []
loop:
    b = next byte
    if b == 0x00: break            # terminator, consumed
    keys.append((b << 8) | next byte)
```

No real key contains a `0x00` byte (`0x01xx`–`0x07xx` high bytes, low bytes
`0x01`–`0xFF`), so scanning for a single `0x00` terminator is unambiguous.

Cross-reference the result against the table in
[02-COMMAND-PROTOCOL.md](./02-COMMAND-PROTOCOL.md) §9.

---

## 8. Suggested consumption order

1. Sniff V1 vs V2 (§0), parse once on connect, cache keyed by MAC.
2. **V1 only:** use `mIOBufferSize` for `0x07xx` chunking instead of deriving it
   from MTU. Sanity-check the value before trusting it.
3. **V1 only:** intersect the client's health-sync key list with `mDataKeys` —
   each unsupported key otherwise costs a full response timeout. Fall back to
   the built-in list when the list is absent or matches nothing.
4. **V1 only:** gate settings UI on the matching `mSupportXxx` flag; hide rather
   than fail.
5. **V2:** use `mPlatform` / `mPrototype` / `mFullVersion` for model
   identification and bug reports; everything else stays on client defaults.
6. Re-read after an OTA — both the shape and the capabilities can change.
