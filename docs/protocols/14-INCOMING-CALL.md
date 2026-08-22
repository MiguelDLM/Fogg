# 14 — INCOMING_CALL (0x0603)

**Sources:**
- `decompiled_apk/sources/com/sma/smartv3/initializer/BleInitializer.java` — the `PhoneStateListener` (line 1418), `Ioxxx(int)` (the sender), `onIncomingCallStatus(int)` (line 2858), `XIo0oOXIx()` (dismissal)
- `decompiled_apk/sources/com/szabh/smable3/BleKey.java` — key value
- `decompiled_apk/sources/com/szabh/smable3/entity/BleNotification.java` — `CATEGORY_INCOMING_CALL`

---

## 0. Key

`BleKey.INCOMING_CALL` is value **1539 = 0x0603** → `cmd=0x06`, `key=0x03`.
(JADX prints it as `SubBinId.Bbpro.DSP_SCENARIO2`, an unrelated Realtek DFU
constant that happens to equal 1539 — constant substitution, not a dependency.)

Body is **one byte** in both directions, but the byte means different things
each way.

---

## 1. It is not the "a call is arriving" push

This is the trap. `INCOMING_CALL` is a **call-in-progress flag**, and the
original app never sends it while the phone is ringing.
`BleInitializer`'s `PhoneStateListener.onCallStateChanged` does this:

| Android state | What is sent |
|---|---|
| `CALL_STATE_RINGING` (1) | **nothing on this key** — a `NOTIFICATION` with category 1 instead |
| `CALL_STATE_OFFHOOK` (2) | `INCOMING_CALL = 0` |
| `CALL_STATE_IDLE` (0) | `INCOMING_CALL = 1`, plus a `NOTIFICATION` DELETE |

So on the wire:

| Value | Meaning |
|---|---|
| 0 | a call is in progress |
| 1 | no call in progress |

The caller screen the user sees on the watch — name, number, answer and reject
buttons — is a **notification**, not this key.

### 1.1 The ringing notification

`BleInitializer.XX0xXo` ("handleIncomingCall2") sends a `NOTIFICATION2` with
category **1** (`BleNotification.CATEGORY_INCOMING_CALL`), the resolved contact
name as the title and the number as the body. Devices without the NOTIFICATION2
capability get the plain `NOTIFICATION` (0x0401) equivalent.

### 1.2 Dismissing it

`XIo0oOXIx()` ("handleEnd") sends `BleNotification(1, 0L, null, null, null)`
with the **DELETE** flag: a full-size notification body whose category is 1 and
whose every other field is zero. Without it the watch keeps the caller on screen
after the call ends.

### 1.3 Missed calls

A separate notification, not this key: category **127** with package
`BleNotification.PACKAGE_MISSED_CALL`, sent when a ringing call goes to idle
without ever reaching off-hook.

---

## 2. watch → phone: what the user pressed

`onIncomingCallStatus(int)`:

| Value | Action |
|---|---|
| 0 | **answer** — `TelecomManager.acceptRingingCall()` |
| anything else | **hang up** — `TelecomManager.endCall()` |

The original's branch is `if (i == 0) { accept } else { … endCall }`, so every
non-zero value falls through to hanging up; there is no distinct "reject" code.

Its pre-API-28 fallbacks (dispatching `KEYCODE_HEADSETHOOK`, shelling out to
`input keyevent 79`, and reflecting into `com.android.internal.telephony.ITelephony`)
are legacy workarounds, not protocol.

Both actions need `ANSWER_PHONE_CALLS`. `acceptRingingCall()` is API 26+ and
`endCall()` is API 28+.

---

## 3. dial-sender

| Piece | Location |
|---|---|
| State flag | `BleManager.sendCallState(int)` |
| Dismissal | `BleManager.dismissCallNotification()` |
| Watch → phone handler | `BleManager.processResponse`, `cmd==0x06 && key==0x03 && !isReply` |
| Call-state bridge | `WatchCallController` |
| Lifecycle | `BleForegroundService.onConnectionStateChange` |
| Toggle | Device tab, pref `call_control_enabled`, **off by default** |

The feature is off until the user turns it on, and turning it on is what
triggers the `READ_PHONE_STATE` / `ANSWER_PHONE_CALLS` request — the row only
flips to "on" once both are actually granted, so it never claims a capability
the watch does not have.

**The ringing screen is not pushed from here.** `WatchNotificationService`
already forwards the dialer's own notification with category 1, so pushing a
second one would double the screen on the watch. It would also need the caller's
number, which `READ_PHONE_STATE` alone no longer yields on Android 10+ (and
`TelephonyCallback` on API 31+ never carries it at all). The consequence worth
knowing: **the dialer must be enabled in the notification filter** for the
caller screen to appear — this key only handles the in-call flag, the dismissal
and the answer/hang-up.
