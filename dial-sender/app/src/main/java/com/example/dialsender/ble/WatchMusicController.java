package com.example.dialsender.ble;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;
import java.util.Locale;

/**
 * Bridges Android's media session to the watch over MUSIC_CONTROL (0x0402).
 *
 * Two directions:
 *   phone -> watch  now-playing metadata and playback state, pushed as the
 *                   entity/attribute pairs the watch expects
 *   watch -> phone  transport commands, replayed as media key events
 *
 * Reading media sessions requires notification-listener access, which the app
 * already holds for {@link WatchNotificationService}. Without it
 * getActiveSessions throws SecurityException, so every call is guarded: the
 * feature degrades to silence rather than taking the BLE service down.
 *
 * Protocol: docs/protocols/04-NOTIFICATIONS.md
 */
public class WatchMusicController {

    private static final String TAG = "WatchMusic";

    // Entities (payload byte 0)
    static final int ENTITY_PLAYER = 0;
    static final int ENTITY_QUEUE = 1;
    static final int ENTITY_TRACK = 2;

    // Attributes (payload byte 1) — namespaced per entity, so the numbering
    // restarts at 0 for each of them.
    // PLAYER_NAME (attr 0) exists in the protocol but the original app never
    // sends it, so we do not either — the firmware's handling of it is unknown.
    static final int PLAYER_PLAYBACK_INFO = 1;
    static final int PLAYER_VOLUME = 2;
    static final int TRACK_ARTIST = 0;
    static final int TRACK_ALBUM = 1;
    static final int TRACK_TITLE = 2;
    static final int TRACK_DURATION = 3;

    // Playback states as the watch understands them.
    private static final int STATE_PAUSED = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_REWINDING = 2;
    private static final int STATE_FAST_FORWARDING = 3;
    private static final int STATE_UNKNOWN = -1;

    // Commands the watch sends (single payload byte).
    public static final int CMD_PLAY = 0;
    public static final int CMD_PAUSE = 1;
    public static final int CMD_TOGGLE = 2;
    public static final int CMD_NEXT = 3;
    public static final int CMD_PREVIOUS = 4;
    public static final int CMD_VOLUME_UP = 5;
    public static final int CMD_VOLUME_DOWN = 6;

    /** Placeholder the original app sends for an empty field; "" blanks the row. */
    private static final String EMPTY = " ";

    private final Context context;
    private final BleManager ble;
    private final AudioManager audio;
    private final MediaSessionManager sessions;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaController active;
    private boolean started;
    private int lastVolume = -1;
    private long lastVolumeAt;

    public WatchMusicController(Context context, BleManager ble) {
        this.context = context.getApplicationContext();
        this.ble = ble;
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.sessions = (MediaSessionManager)
                this.context.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            pushTrack(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            pushPlaybackState(state);
        }

        @Override
        public void onSessionDestroyed() {
            handler.post(() -> refreshActiveSession());
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> bindTo(pickController(controllers));

    /** Start following the active media session. Safe to call more than once. */
    public void start() {
        if (started || sessions == null)
            return;
        ComponentName listener = new ComponentName(context, WatchNotificationService.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listener);
            bindTo(pickController(sessions.getActiveSessions(listener)));
            started = true;
            Log.d(TAG, "started");
        } catch (SecurityException e) {
            // Notification access not granted (or revoked). Nothing to do until
            // the user grants it; the caller can simply try again later.
            Log.w(TAG, "no notification listener access, music control disabled");
        }
    }

    public void stop() {
        if (!started)
            return;
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Exception ignored) {
            // Listener was never registered, or the service is already gone.
        }
        bindTo(null);
        started = false;
    }

    /**
     * Prefer a session that is actually playing; otherwise take the first one.
     * The system orders the list by recency, so the head is the most plausible
     * fallback when everything is paused.
     */
    private MediaController pickController(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty())
            return null;
        for (MediaController c : controllers) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING)
                return c;
        }
        return controllers.get(0);
    }

    private void bindTo(MediaController controller) {
        if (active == controller)
            return;
        if (active != null)
            active.unregisterCallback(controllerCallback);
        active = controller;
        if (active == null) {
            pushIdle();
            return;
        }
        active.registerCallback(controllerCallback);
        pushTrack(active.getMetadata());
        pushPlaybackState(active.getPlaybackState());
        pushVolume();
    }

    private void refreshActiveSession() {
        if (sessions == null)
            return;
        try {
            bindTo(pickController(sessions.getActiveSessions(
                    new ComponentName(context, WatchNotificationService.class))));
        } catch (SecurityException e) {
            bindTo(null);
        }
    }

    // ---- phone -> watch ----

    /** The watch clears a row on "", so an absent field is sent as a space. */
    private static String orEmpty(String s) {
        return (s == null || s.isEmpty()) ? EMPTY : s;
    }

    private void pushTrack(MediaMetadata m) {
        if (m == null) {
            ble.sendMusicControl(ENTITY_TRACK, TRACK_ARTIST, EMPTY);
            ble.sendMusicControl(ENTITY_TRACK, TRACK_ALBUM, EMPTY);
            ble.sendMusicControl(ENTITY_TRACK, TRACK_TITLE, EMPTY);
            ble.sendMusicControl(ENTITY_TRACK, TRACK_DURATION, "0");
            return;
        }
        ble.sendMusicControl(ENTITY_TRACK, TRACK_ARTIST,
                orEmpty(m.getString(MediaMetadata.METADATA_KEY_ARTIST)));
        ble.sendMusicControl(ENTITY_TRACK, TRACK_ALBUM,
                orEmpty(m.getString(MediaMetadata.METADATA_KEY_ALBUM)));
        ble.sendMusicControl(ENTITY_TRACK, TRACK_TITLE,
                orEmpty(m.getString(MediaMetadata.METADATA_KEY_TITLE)));
        ble.sendMusicControl(ENTITY_TRACK, TRACK_DURATION,
                String.valueOf(m.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000));
    }

    private void pushPlaybackState(PlaybackState state) {
        if (state == null)
            return;
        int watchState = toWatchState(state.getState());
        if (watchState == STATE_UNKNOWN)
            return;
        ble.sendMusicControl(ENTITY_PLAYER, PLAYER_PLAYBACK_INFO,
                playbackInfo(watchState, state.getPlaybackSpeed(), state.getPosition() / 1000));
        if (lastVolume == -1)
            pushVolume();
    }

    /**
     * "state,speed,,positionSeconds".
     *
     * The doubled comma is deliberate: the original app builds this from a
     * three-element list joined with "," whose last element already starts with
     * one, so the watch firmware is parsing four fields with the third empty.
     * Emitting the tidier three-field form would not match what the firmware
     * was written against.
     */
    private static String playbackInfo(int state, float speed, long positionSeconds) {
        return state + "," + String.format(Locale.US, "%.1f", speed) + ",," + positionSeconds;
    }

    private static int toWatchState(int androidState) {
        switch (androidState) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
                return STATE_PLAYING;
            case PlaybackState.STATE_PAUSED:
            case PlaybackState.STATE_STOPPED:
                return STATE_PAUSED;
            case PlaybackState.STATE_REWINDING:
                return STATE_REWINDING;
            case PlaybackState.STATE_FAST_FORWARDING:
                return STATE_FAST_FORWARDING;
            default:
                return STATE_UNKNOWN;
        }
    }

    /**
     * Current media volume as a 0.00–1.00 fraction.
     *
     * Rate-limited to one push per 150 ms, as the original app does: holding a
     * volume key fires a stream of changes and every one of them would other-
     * wise become its own BLE frame. The ends of the range and a genuinely new
     * level are let through regardless, so the watch never sticks at a stale
     * value when the user stops at min or max.
     */
    public void pushVolume() {
        if (audio == null)
            return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0)
            return;
        int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        long now = System.currentTimeMillis();
        if (now - lastVolumeAt <= 150
                && ((vol != 0 && vol != max) || vol == lastVolume))
            return;
        lastVolumeAt = now;
        lastVolume = vol;
        ble.sendMusicControl(ENTITY_PLAYER, PLAYER_VOLUME,
                String.format(Locale.US, "%.2f", vol / (float) max));
    }

    /** Blank the watch's player when nothing is playing. */
    private void pushIdle() {
        ble.sendMusicControl(ENTITY_PLAYER, PLAYER_PLAYBACK_INFO, playbackInfo(STATE_PAUSED, 0f, 0));
        ble.sendMusicControl(ENTITY_TRACK, TRACK_ARTIST, EMPTY);
        ble.sendMusicControl(ENTITY_TRACK, TRACK_ALBUM, EMPTY);
        ble.sendMusicControl(ENTITY_TRACK, TRACK_TITLE, EMPTY);
        ble.sendMusicControl(ENTITY_TRACK, TRACK_DURATION, "0");
        lastVolume = -1;
    }

    /** Re-send everything, e.g. after the watch reconnects. */
    public void pushAll() {
        if (active == null) {
            refreshActiveSession();
            return;
        }
        pushTrack(active.getMetadata());
        pushPlaybackState(active.getPlaybackState());
        pushVolume();
    }

    // ---- watch -> phone ----

    /**
     * Apply a transport command from the watch.
     *
     * Volume goes through AudioManager; everything else is replayed as a media
     * key event rather than driven through the bound MediaController, so it
     * still lands when the session we are following is not the one the user
     * means — the system routes the key to whichever session it considers
     * active, which is the behaviour the original app relies on.
     */
    public void onWatchCommand(int command) {
        switch (command) {
            case CMD_PLAY:
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            case CMD_PAUSE:
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
                break;
            case CMD_TOGGLE:
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            case CMD_NEXT:
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case CMD_PREVIOUS:
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case CMD_VOLUME_UP:
                adjustVolume(AudioManager.ADJUST_RAISE);
                break;
            case CMD_VOLUME_DOWN:
                adjustVolume(AudioManager.ADJUST_LOWER);
                break;
            default:
                Log.w(TAG, "unknown music command " + command);
                return;
        }
        // The session reports the new state asynchronously; nudge the active
        // one so a command the watch issued is reflected there promptly.
        handler.postDelayed(() -> {
            if (active != null)
                pushPlaybackState(active.getPlaybackState());
        }, 300);
    }

    private void sendMediaKey(int keyCode) {
        if (audio == null)
            return;
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private void adjustVolume(int direction) {
        if (audio == null)
            return;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        pushVolume();
    }
}
