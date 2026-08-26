# 18 — Girl care / period tracking

**Sources:**
- `com/szabh/smable3/entity/BleGirlCareSettings`, `BleGirlCareMonthly` (vendor APK)
- Live capture from a Kronos Thunder (`JL` / `AM05`, `Kronos_Thunder_V006`), 2026-08-26

---

## 0. Two keys

| Key | Value | cmd/key | Purpose | On this watch |
|---|---|---|---|---|
| `GIRL_CARE` | 538 | `0x02` / `0x1A` | cycle parameters + reminder | implemented |
| `GIRL_CARE_MONTHLY` | 620 | `0x02` / `0x6C` | per-day calendar the watch draws | implemented, **not yet used by this app** |

---

## 1. `GIRL_CARE` (0x021A) — 10 bytes

| Offset | Field | Notes |
|---|---|---|
| 0 | `[bit 7: reminder on] [bit 0: feature on]` | bits 1-6 unused |
| 1 | reminder hour | |
| 2 | reminder minute | |
| 3 | menstruation reminder, days in advance | 1-3 |
| 4 | ovulation reminder, days in advance | 1-3 |
| 5 | year of last period − 2000 | |
| 6 | month of last period | 1-12 |
| 7 | day of last period | 1-31 |
| 8 | menstruation duration, days | clamped 2-15 |
| 9 | cycle length, days | clamped 20-45 |

The watch derives the phase itself from the last-period date, the duration and
the cycle length; the phone does not send a phase.

### 1.1 Verified on hardware

```
Tx  02 1A 00  80 08 00 02 03 1A 08 1A 05 1C
Rx  02 1A 10  80 08 00 02 03 1A 08 1A 05 1C
```

Reads back byte for byte: reminder on at 08:00, 2 and 3 days' notice, last period
2026-08-26, 5-day duration, 28-day cycle.

---

### 1.2 The start date must not be in the future

The watch derives the day-in-cycle from bytes 5-7 and **treats the day
difference as unsigned**. A start date three days ahead of today does not come
back as "not started yet"; it wraps:

```
diff = -3
-3 as uint32 = 4294967293
4294967293 mod 28 = 9      → the watch displays day 9 of 28
```

The phone's own `((diff % cycle) + cycle) % cycle + 1` wraps the same input to
day 26. Both numbers look plausible and neither means anything, which is what
made this hard to spot: the two sides disagreed while each was internally
consistent.

`PeriodTrackerManager` therefore clamps the stored start date to today before
computing anything or sending it, and `logPeriodStart()` refuses a future date
outright — the calendar grid and both date pickers could all offer one.

---

### 1.3 The watch counts the phase, the phone counts the cycle

The two screens quote different numbers from the same data, and neither is
wrong. Verified with start = 2026-08-20, cycle 28, duration 5, on 2026-08-26:

| | Shows | Meaning |
|---|---|---|
| Phone | "Day 7 of cycle" | day since the period started, 1-based |
| Watch | "day 03, safe period" | day within the **current phase** |
| Phone | "ovulation in 7 days" | to the ovulation day (day 14) |
| Watch | "2 days enter period of ovulation" | to the **fertile window** opening (day 9) |

Both agree on the substance: safe phase now, fertile window in two days. The
watch's phase-day sits one ahead of what the phone's boundaries imply — its safe
phase appears to start on the last day of menstruation rather than the day
after — which is a firmware display convention, not a payload difference. The
0x021A read confirms the watch stored exactly what was sent:

```
Rx reminder key=0x1A raw = 01 08 00 02 03 1A 08 14 05 1C
                                          └ 2026-08-20 ┘
```

Because day 20 differs from the year offset 26, this capture also settles the
byte order — the watch reads bytes 5-7 as year, month, day, the same way the
phone writes them.

`CycleStatus` now carries `daysUntilFertileWindow` alongside
`daysUntilOvulation` so the app states both milestones and the two screens can
be read against each other.

---

## 2. `GIRL_CARE_MONTHLY` (0x026C) — 31 bytes

One byte per day of the month. The Kronos answers a read with:

```
04 04 00 00 00 00 00 00 00 00 00 00 01 02 02 02 03 00 00 00 00 04 04 04 04 04 05 04 04 04 00
```

The first two bytes are a header and the remaining 29 are day codes, with small
enumerated values (0-5) marking the phase the watch paints on its calendar.
The exact code table has **not** been confirmed against the vendor entity yet —
this app writes only `0x021A` and leaves the calendar to the watch's own
derivation. Documented here so the field is not mistaken for free space.
