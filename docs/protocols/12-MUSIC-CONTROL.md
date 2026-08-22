# 12 — MUSIC_CONTROL (0x0402)

**Sources:**
- `decompiled_apk/sources/com/szabh/smable3/entity/BleMusicControl.java` — `encode()`, `getMLengthToWrite()`
- `decompiled_apk/sources/com/szabh/smable3/entity/MusicEntity.java`, `MusicAttr.java`, `MusicCommand.java`
- `decompiled_apk/sources/com/szabh/smable3/music/MusicController.java` — the phone-side bridge
- `decompiled_apk/sources/com/bestmafen/baseble/data/BleWritable.java` — `writeString`

---

## 0. Shape

`MUSIC_CONTROL` is `BleKey` ordinal 137, value **1026 = 0x0402** → `cmd=0x04`,
`key=0x02`. It carries traffic in both directions with two different bodies.

| Direction | Flag | Body |
|---|---|---|
| phone → watch | `UPDATE` (0x00) | `[entity u8][attr u8][content, UTF-8]` |
| watch → phone | `UPDATE` (0x00) | `[command u8]` |

---

## 1. phone → watch: one attribute per frame

```
AB 01 LL LL CC CC 04 02 00 | entity attr content…
```

`BleMusicControl.encode()`:

```java
writeInt8(mMusicEntity.getMEntity());
writeInt8(mMusicAttr.getMAttr());
BleWritable.writeString$default(this, mContent, null, 2, null);
```

**The string is not NUL-terminated.** `getMLengthToWrite()` returns
`contentBytes.length + 2` — exactly entity + attr + content, with no room for a
terminator — and `writeString` appends the raw `getBytes(charset)` with UTF-8 as
the default. The frame length is what delimits the string.

There is no batching: each attribute is its own frame, so a track change is a
burst of four.

### Entities (byte 0)

| Value | Entity |
|---|---|
| 0 | `PLAYER` |
| 1 | `QUEUE` |
| 2 | `TRACK` |
| -1 | `UNKNOWN` |

### Attributes (byte 1)

Attribute numbering is **namespaced per entity** — it restarts at 0 for each,
so byte 1 is meaningless without byte 0.

| Entity | Value | Attribute | Content sent by the original app |
|---|---|---|---|
| `PLAYER` | 0 | `PLAYER_NAME` | **never sent** (see §1.1) |
| `PLAYER` | 1 | `PLAYER_PLAYBACK_INFO` | `"state,speed,,positionSeconds"` |
| `PLAYER` | 2 | `PLAYER_VOLUME` | `"0.00"`–`"1.00"` |
| `QUEUE` | 0 | `QUEUE_INDEX` | never sent |
| `QUEUE` | 1 | `QUEUE_COUNT` | never sent |
| `QUEUE` | 2 | `QUEUE_SHUFFLE_MODE` | never sent |
| `QUEUE` | 3 | `QUEUE_REPEAT_MODE` | never sent |
| `TRACK` | 0 | `TRACK_ARTIST` | artist, or `" "` |
| `TRACK` | 1 | `TRACK_ALBUM` | album, or `" "` |
| `TRACK` | 2 | `TRACK_TITLE` | title, or `" "` |
| `TRACK` | 3 | `TRACK_DURATION` | seconds as decimal text |

### 1.1 What the original app actually sends

Only six of the twelve attributes are ever written. A grep across the whole APK
finds **no sender for `PLAYER_NAME` or for any `QUEUE` attribute** — they exist
in the enum only. Their firmware-side handling is therefore unverified, and
`dial-sender` does not send them either.

An absent metadata field is sent as a single space `" "`, never as `""`
(`MusicController.updateMetadata`). `""` would be a zero-length body, which the
watch reads as "no change" rather than "clear".

### 1.2 `PLAYER_PLAYBACK_INFO` and its doubled comma

`MusicController.updatePlaybackState` builds a **three**-element list and joins
it with `","`:

```java
List contents = listOf(String.valueOf(state),
                       String.format("%.1f", playbackState.getPlaybackSpeed()),
                       "," + (playbackState.getPosition() / 1000));
```

The third element already begins with a comma, so the wire format has four
fields with the third empty:

```
"1,1.0,,42"     playing, speed 1.0, (empty), 42 s in
```

This is almost certainly a bug in the original app, but the firmware was written
against it. Emitting the tidier `"1,1.0,42"` would shift the position into a
field the watch does not read, so **reproduce the doubled comma.**

Playback states (`com.szabh.smable3.entity.PlaybackState`):

| Value | State |
|---|---|
| 0 | `PAUSED` |
| 1 | `PLAYING` |
| 2 | `REWINDING` |
| 3 | `FAST_FORWARDING` |
| -1 | `UNKNOWN` — suppresses the push entirely |

### 1.3 Idle

When no session is active, the original blanks the player
(`MusicController.autoSwitchActiveController`, else-branch):

```
PLAYER / PLAYBACK_INFO  "0,0.0,,0"
TRACK  / ARTIST         " "
TRACK  / ALBUM          " "
TRACK  / TITLE          " "
TRACK  / DURATION       "0"
```

and resets its cached volume to `-1` so the next state push re-sends it.

### 1.4 Volume

`PLAYER_VOLUME` is `streamVolume / streamMaxVolume` on `STREAM_MUSIC`, formatted
to two decimals. `updateVolume()` rate-limits itself to one push per **150 ms**,
with two exemptions that always get through: the ends of the range
(`vol == 0 || vol == max`) and a level that differs from the last one sent.
Without this, holding a volume key turns every intermediate step into a frame.

---

## 2. watch → phone: transport commands

A single byte, decoded by `MusicCommand.of(byte)`. The dispatcher that reads it
out of the frame is inside the obfuscated part of `BleConnector` that JADX could
not restore, but `of(byte)` fixes the width at one byte.

| Value | Command | `MusicController` action |
|---|---|---|
| 0 | `PLAY` | `KEYCODE_MEDIA_PLAY` (126) |
| 1 | `PAUSE` | `KEYCODE_MEDIA_PAUSE` (127) |
| 2 | `TOGGLE` | `KEYCODE_MEDIA_PLAY_PAUSE` (85) |
| 3 | `NEXT` | `KEYCODE_MEDIA_NEXT` (87) |
| 4 | `PRE` | `KEYCODE_MEDIA_PREVIOUS` (88) |
| 5 | `VOLUME_UP` | `adjustStreamVolume(STREAM_MUSIC, +1, FLAG_SHOW_UI)` |
| 6 | `VOLUME_DOWN` | `adjustStreamVolume(STREAM_MUSIC, -1, FLAG_SHOW_UI)` |
| -1 | `UNKNOWN` | ignored |

Transport commands go out as **media key events**, not through the bound
`MediaController`. That matters: the key is routed by the system to whatever
session it considers active, so the command still lands when the session being
followed is not the one the user means. Volume is the exception — it goes
straight to `AudioManager`.

For codes 0–4 the original also re-runs its active-session election before
dispatching (`autoSwitchActiveController(list, true)`).

---

## 3. Reading the sessions

`MediaSessionManager.getActiveSessions(ComponentName)` requires the caller to be
an **enabled notification listener**; the component passed in must be the app's
own `NotificationListenerService`. Without the grant it throws
`SecurityException` rather than returning empty, so every call site needs a
guard — `dial-sender` reuses `WatchNotificationService` for this.

---

## 4. dial-sender

| Piece | Location |
|---|---|
| Frame + payload | `BleManager.sendMusicControl(int, int, String)` |
| Body builder (tested) | `BleManager.musicControlPayload(...)` |
| Watch → phone handler | `BleManager.processResponse`, `cmd==0x04 && key==0x02 && !isReply` |
| Media session bridge | `WatchMusicController` |
| Lifecycle | `BleForegroundService.onConnectionStateChange` |

Two deliberate deviations from the original, both ours and neither protocol:

- **Content is capped at 128 bytes**, cut on a UTF-8 boundary. The protocol sets
  no limit, but a pathological title would fan out into dozens of MTU chunks and
  starve the write queue behind it.
- **The queue is only kicked when idle.** A metadata burst is four frames;
  kicking a write that is already in flight would race the GATT callback.
