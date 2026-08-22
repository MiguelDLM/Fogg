# 15 — Monitoring windows, find-watch, measure-on-demand

**Sources:**
- `decompiled_apk/sources/com/szabh/smable3/entity/BleHrMonitoringSettings.java`, `BleBloodOxyGenSettings.java`, `BleSleepMonitoringSettings.java`, `BleTimeRange.java`
- `decompiled_apk/sources/com/szabh/smable3/entity/BleRealTimeMeasurement.java`
- `decompiled_apk/sources/com/sma/smartv3/ui/device/FindWatchActivity.java`
- `decompiled_apk/sources/com/sma/smartv3/ui/status/RealTimeMeasurementActivity.java`
- Live capture from a Kronos Thunder (`JL` / `AM05`, `Kronos_Thunder_V006`), 2026-08-22

---

## 0. Why these

The app could already read health history but could not tell the watch **to
collect any of it**. These three keys are what turn sampling on, plus two
actions the watch is otherwise the only place to trigger.

| Key | Value | cmd/key | Purpose |
|---|---|---|---|
| `HR_MONITORING` | 534 | `0x02` / `0x16` | automatic heart-rate sampling |
| `BLOOD_OXYGEN_SET` | 549 | `0x02` / `0x25` | automatic SpO2 sampling |
| `SLEEP_MONITORING` | 576 | `0x02` / `0x40` | sleep tracking window |
| `FIND_WATCH` | 564 | `0x02` / `0x34` | make the watch ring |
| `REALTIME_MEASUREMENT` | 566 | `0x02` / `0x36` | take a reading now |

---

## 1. Monitoring windows

`HR_MONITORING` and `BLOOD_OXYGEN_SET` share one 6-byte body: a `BleTimeRange`
followed by the sampling interval.

| Offset | Field |
|---|---|
| 0 | enabled, 0/1 |
| 1 | start hour |
| 2 | start minute |
| 3 | end hour |
| 4 | end minute |
| 5 | interval, minutes |

`SLEEP_MONITORING` is the same **minus the interval** — 5 bytes, no byte 5.

`BleHrMonitoringSettings.encode()` clamps the interval with
`Math.max(interval, 1)`; `BleBloodOxyGenSettings` does not, so a 0 there reaches
the watch as-is.

### 1.1 Verified on hardware

Writing HR monitoring and reading it back round-tripped exactly:

```
Tx  HR_MONITORING enabled=true 00:00-23:59 every 30min
Rx  key=0x16 raw = 01 00 00 17 3B 1E      → 1, 0:0, 23:59, 30
```

The SpO2 read is the stronger evidence, because it returned a setting **this app
never wrote** — the watch's own pre-existing configuration:

```
Rx  key=0x25 raw = 01 09 00 15 00 3C      → 1, 09:00, 21:00, 60 min
```

That decodes to a plausible daytime SpO2 window, which confirms the field order
independently of anything the phone sent.

### 1.2 SLEEP_MONITORING is write-only here

The Kronos Thunder **ACKs the write** (`Rx ack key=0x40 flag=0x00`, no NACK) but
answers a READ with an **empty body** (`flag=0x10`, LEN=3), both before and after
a successful write. So the setting cannot be read back on this firmware and a UI
showing it is necessarily reporting the phone's copy, not the watch's.

---

## 2. FIND_WATCH (0x0234)

One byte: **1 starts** the ring, **0 stops** it. The mirror of `FIND_PHONE`
(0x0213), which the watch already uses in the other direction.

Verified: `Tx FIND_WATCH start=true` → `AB 11 00 03 00 B7 02 34 00`, a bodyless
ACK.

**The watch never reports that it stopped.** It stops on its own when the user
acknowledges it there, silently. So the phone cannot display live state — it can
only ask. `dial-sender` therefore owns the stop: after 30 seconds it *sends*
stop rather than merely relabelling the row, because letting the label lapse
made the next tap start a second ring instead of ending the first.

---

## 3. REALTIME_MEASUREMENT (0x0236)

One bit-packed byte, MSB first, from `BleRealTimeMeasurement.encode()`:

```
bits 7..6   state    2 = start, 1 = stop
bits 5..4   unused, written as 0
bit  3      stress
bit  2      blood pressure
bit  1      blood oxygen
bit  0      heart rate
```

So "start a heart-rate reading" is `0x81` and "stop" is `0x41`.

The state values come from the original app's two call sites:
`RealTimeMeasurementActivity` begins a measurement with state **2** and sends
state **1** from `onDestroy()`.

### 3.1 The result does not come back on this key

The watch replies here only with progress:

| Reply state | Meaning |
|---|---|
| 0 | done — read the value off the matching health key |
| 1 | failed |

On completion the original app issues a `READ` on `HEART_RATE`,
`BLOOD_PRESSURE` or `BLOOD_OXYGEN` depending on which switch was set. The
reading is never in the 0x0236 frame.

### 3.2 Verified, and not

**Request path verified.** Start and stop were both accepted:

```
Tx REALTIME_MEASUREMENT type=0 start=true  payload=0x81
Rx AB 11 00 03 60 B6 02 36 00              bodyless ACK
Tx REALTIME_MEASUREMENT type=0 start=false payload=0x41
Rx ...                                     bodyless ACK
```

**Completion path NOT verified.** No push on 0x0236 arrived in the 90 seconds
between start and stop. Two explanations are consistent with that and the
capture cannot separate them:

1. the watch was not being worn, so there was nothing to measure and it reported
   nothing;
2. this firmware does not push progress on 0x0236 at all.

Retest with the watch on a wrist before trusting `onMeasurementComplete`. Until
then the phone-side timeout is what ends the measurement, which it does
correctly.

---

## 4. dial-sender

| Piece | Location |
|---|---|
| Monitoring writes | `BleManager.sendHeartRateMonitoring`, `sendBloodOxygenMonitoring`, `sendSleepMonitoring` |
| Monitoring reads | `BleManager.readAllMonitoring`, parsed in `processResponse` |
| Ring the watch | `BleManager.sendFindWatch(boolean)` |
| Measure now | `BleManager.sendRealtimeMeasurement(type, start)` + `MeasurementListener` |
| UI | Device tab rows, `DeviceFragment` |

The monitoring rows render from what the watch reports, not from what was
written — except sleep, which the watch will not report (§1.2).
