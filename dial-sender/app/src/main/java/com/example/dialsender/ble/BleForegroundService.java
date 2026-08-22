package com.example.dialsender.ble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.example.dialsender.MainActivity;
import com.example.dialsender.R;

/**
 * Keeps the BLE link alive while the app is in the background.
 *
 * It does NOT poll. The retry loop lives in {@link BleManager} and only runs
 * while the link is actually down (5s → 40s back-off, alternating direct
 * connect / short address-filtered scan, exactly like the original CO-FIT app).
 * This service just owns the foreground notification, arms the loop once, and
 * updates the notification text from connection events.
 */
public class BleForegroundService extends Service implements BleManager.ConnectionObserver {

    public static final String ACTION_DISCONNECT = "com.example.dialsender.ACTION_DISCONNECT";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "ble_connection";

    /**
     * Safety net only: BleManager's own loop is event-driven, this just makes
     * sure the loop is armed again after e.g. a process restart where no GATT
     * callback will ever fire. 15 minutes costs nothing on the battery.
     */
    private static final long WATCHDOG_INTERVAL_MS = 15 * 60_000L;

    private Handler handler;
    private BleManager bleManager;

    /** Notifications follow the language chosen in the app, not the system one. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.example.dialsender.LocaleHelper.wrap(base));
    }

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (bleManager != null
                    && !bleManager.isConnected()
                    && !bleManager.isReconnecting()
                    && bleManager.isAutoConnectEnabled()) {
                String addr = bleManager.getVerifiedDeviceAddress();
                if (addr != null)
                    bleManager.reconnect(addr);
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    /** Starts the service on the right API-appropriate entry point. */
    public static void start(android.content.Context context) {
        Intent i = new Intent(context, BleForegroundService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        bleManager = BleManager.getInstance(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            bleManager.disconnect();
            bleManager.removeConnectionObserver(this);
            handler.removeCallbacks(watchdogRunnable);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                stopForeground(STOP_FOREGROUND_REMOVE);
            else
                stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification(bleManager.isConnected()));
        bleManager.addConnectionObserver(this);

        if (bleManager.isAutoConnectEnabled()) {
            String addr = bleManager.getVerifiedDeviceAddress();
            if (addr != null)
                bleManager.reconnect(addr);
        }

        handler.removeCallbacks(watchdogRunnable);
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bleManager != null) {
            bleManager.removeConnectionObserver(this);
            bleManager.getMusicController().stop();
        }
        handler.removeCallbacks(watchdogRunnable);
    }

    @Override
    public void onConnectionStateChange(boolean connected, boolean sessionReady) {
        updateNotification(connected);

        // Follow the phone's media session only while the watch can actually
        // receive the pushes; otherwise every track change would build frames
        // that sendMusicControl drops anyway.
        WatchMusicController music = bleManager.getMusicController();
        if (sessionReady) {
            music.start();
            // The watch shows an empty player until the first push, so re-send
            // everything once the session is up rather than waiting for the
            // next metadata change.
            music.pushAll();
        } else if (!connected) {
            music.stop();
        }
    }

    /**
     * minSdk is 24 and NotificationChannel is API 26 — without this guard the
     * service crashed on create on Android 7.x.
     */
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O)
            return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_ble_name), NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.notif_channel_ble_desc));
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(boolean connected) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE);

        Intent disconnectIntent = new Intent(this, BleForegroundService.class);
        disconnectIntent.setAction(ACTION_DISCONNECT);
        PendingIntent disconnectPi = PendingIntent.getService(this, 1, disconnectIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(connected
                        ? R.string.notif_ble_connected : R.string.notif_ble_searching))
                .setContentIntent(openPi)
                .addAction(0, getString(R.string.notif_ble_disconnect), disconnectPi)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    public void updateNotification(boolean connected) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(connected));
    }
}
