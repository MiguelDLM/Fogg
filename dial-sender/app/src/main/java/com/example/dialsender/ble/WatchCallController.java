package com.example.dialsender.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Bridges the phone's call state to the watch (INCOMING_CALL 0x0603).
 *
 * On a call:
 *   ringing  nothing from here — the answer/reject screen on the watch comes
 *            from the dialer's own notification, which
 *            {@link WatchNotificationService} already forwards with category 1
 *            (so the dialer has to be enabled in the notification filter)
 *   off-hook {@link BleManager#CALL_ACTIVE}, so the watch knows a call is up
 *   idle     {@link BleManager#CALL_IDLE} plus a NOTIFICATION DELETE, without
 *            which the watch keeps showing the caller after the call ends
 *
 * The other direction is the watch pressing answer or reject, which arrives as
 * {@link BleManager.CallListener} and is applied through TelecomManager.
 *
 * Everything here is gated on {@link #PREF_CALL_CONTROL}, off by default: it
 * needs READ_PHONE_STATE and ANSWER_PHONE_CALLS, which the app has no business
 * holding unless the user asked for this feature.
 *
 * Protocol: docs/protocols/14-INCOMING-CALL.md
 */
public class WatchCallController implements BleManager.CallListener {

    private static final String TAG = "WatchCall";

    public static final String PREF_CALL_CONTROL = "call_control_enabled";

    private final Context context;
    private final BleManager ble;
    private final SharedPreferences prefs;

    private TelephonyManager telephony;
    private PhoneStateListener legacyListener;
    private Object modernCallback;   // TelephonyCallback, API 31+
    private boolean started;

    /** Last state seen, so a repeated broadcast does not re-push. */
    private int lastState = TelephonyManager.CALL_STATE_IDLE;

    public WatchCallController(Context context, BleManager ble) {
        this.context = context.getApplicationContext();
        this.ble = ble;
        this.prefs = this.context.getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(PREF_CALL_CONTROL, false);
    }

    /** True once the permissions this needs are actually granted. */
    public boolean hasPermissions() {
        return granted(Manifest.permission.READ_PHONE_STATE)
                && granted(Manifest.permission.ANSWER_PHONE_CALLS);
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (started || !isEnabled())
            return;
        if (!granted(Manifest.permission.READ_PHONE_STATE)) {
            Log.w(TAG, "READ_PHONE_STATE not granted, call control disabled");
            return;
        }
        telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephony == null)
            return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallback = ModernCallback.register(context, telephony, this::onCallState);
            } else {
                legacyListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String number) {
                        onCallState(state, number);
                    }
                };
                telephony.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
            ble.setCallListener(this);
            started = true;
            Log.d(TAG, "started");
        } catch (Exception e) {
            // A vendor ROM refusing the listener must not take the BLE service
            // down with it.
            Log.w(TAG, "could not register call listener: " + e.getMessage());
        }
    }

    public void stop() {
        if (!started)
            return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modernCallback != null)
                ModernCallback.unregister(telephony, modernCallback);
            else if (legacyListener != null)
                telephony.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
        } catch (Exception ignored) {
            // Already gone; nothing to unwind.
        }
        ble.setCallListener(null);
        modernCallback = null;
        legacyListener = null;
        started = false;
    }

    /** Pick up a settings change without restarting the service. */
    public void refresh() {
        if (isEnabled())
            start();
        else
            stop();
    }

    // ---- phone -> watch ----

    private void onCallState(int state, String number) {
        if (state == lastState)
            return;
        lastState = state;

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                // Nothing to send. The original app does not touch
                // INCOMING_CALL while ringing either — the watch's caller
                // screen comes from the dialer's own notification, which
                // WatchNotificationService already forwards with category 1.
                //
                // Pushing a second notification from here would need the
                // caller's number, which READ_PHONE_STATE alone no longer
                // yields on Android 10+, and would double the screen on the
                // watch when the dialer is whitelisted.
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                ble.sendCallState(BleManager.CALL_ACTIVE);
                break;

            case TelephonyManager.CALL_STATE_IDLE:
            default:
                ble.sendCallState(BleManager.CALL_IDLE);
                ble.dismissCallNotification();
                break;
        }
    }

    // ---- watch -> phone ----

    @Override
    @SuppressLint("MissingPermission")
    public void onCallAction(int action) {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            Log.w(TAG, "ANSWER_PHONE_CALLS not granted, ignoring watch action");
            return;
        }
        TelecomManager telecom =
                (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        if (telecom == null)
            return;
        try {
            if (action == BleManager.CALL_ANSWER) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    telecom.acceptRingingCall();
            } else {
                // endCall() covers both rejecting a ringing call and hanging up
                // an active one, which is what the watch's reject button means
                // in either state.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    telecom.endCall();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "call action refused: " + e.getMessage());
        }
    }

    /**
     * TelephonyCallback lives in API 31+, so it is kept out of the main class
     * body — verifying WatchCallController on an older device would otherwise
     * try to resolve it.
     */
    private interface StateSink {
        void onState(int state, String number);
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private static class ModernCallback extends android.telephony.TelephonyCallback
            implements android.telephony.TelephonyCallback.CallStateListener {

        private final StateSink sink;

        ModernCallback(StateSink sink) {
            this.sink = sink;
        }

        @Override
        public void onCallStateChanged(int state) {
            // TelephonyCallback never carries the number; the caller is read
            // from the notification the dialer posts instead.
            sink.onState(state, null);
        }

        static Object register(Context context, TelephonyManager tm, StateSink sink) {
            ModernCallback cb = new ModernCallback(sink);
            tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb);
            return cb;
        }

        static void unregister(TelephonyManager tm, Object cb) {
            tm.unregisterTelephonyCallback((android.telephony.TelephonyCallback) cb);
        }
    }
}
