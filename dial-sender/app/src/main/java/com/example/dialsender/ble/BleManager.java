package com.example.dialsender.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import com.example.dialsender.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * BLE Manager — ported directly from the working omo-version MainActivity.
 * Uses raw processResponse() instead of BleParser to guarantee compatibility.
 * Includes comprehensive BLE protocol logging for debugging and refinement.
 */
public class BleManager {
    private static final String TAG = "BleManager";
    private static BleManager instance;
    private final Context context;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeChar;

    // The protocol's GATT addresses live in BleUuids, not as literals here: the
    // scan filter needs the same service UUID and the two copies were free to
    // drift apart.

    // Protocol State — exactly as in omo version
    private enum ConnectionState {
        DISCONNECTED,
        CONNECTED,
        HANDSHAKE_SENT,
        HANDSHAKE_OK,
        BIND_SENT,
        BIND_OK,
        LOGIN_SENT,
        SESSION_READY,
        WATCHFACE_ID_SENT,
        PRE_TRANSFER,
        SETUP1_SENT,
        SETUP2_SENT,
        TRANSFERRING
    }

    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private final SharedPreferences prefs;
    private static final String PREF_NAME = "dial_sender_prefs";

    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Every interested screen, not one slot. This used to be a single
     * reference and whichever fragment resumed last silently unsubscribed
     * everyone else — MainActivity's auto-sync-on-connect died the moment you
     * opened the Reloj tab.
     */
    private final java.util.concurrent.CopyOnWriteArrayList<BleStateListener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean isConnected = false;

    private final ConcurrentLinkedQueue<byte[]> commandQueue = new ConcurrentLinkedQueue<>();
    private boolean isSending = false;
    private int currentMtu = 23;
    private int ioBufferSize = 480;
    private volatile BleDeviceInfo deviceInfo = null;
    private int packetsSent = 0;
    private byte[] lastChunkSent = null;

    // Reassembly of a logical frame split across several notifications.
    // The header's length field (bytes 2..3) counts cmd+key+flag+payload, so a
    // full frame is that value + 6. A health page is 1024 payload bytes, which
    // arrives as three notifications at MTU 512 — without reassembly the tail
    // two were dropped and only the first ~500 bytes were ever parsed.
    private byte[] rxAssembly = null;
    private int rxFilled = 0;
    private static final long RX_ASSEMBLY_TIMEOUT_MS = 5000;
    private final Runnable rxAssemblyTimeout = () -> {
        if (rxAssembly != null) {
            log("Rx reassembly timed out with " + rxFilled + "/" + rxAssembly.length + " bytes — discarding");
            rxAssembly = null;
            rxFilled = 0;
        }
    };
    // The watch answers SESSION CREATE in well under a second on every model we
    // have logs for. Without a deadline, a peer that exposes the right service
    // but never replies leaves the app pinned on "Connecting…" forever with a
    // live GATT link — there was no timeout on this state at all.
    private static final long HANDSHAKE_TIMEOUT_MS = 6000;
    private final Runnable handshakeTimeoutRunnable = this::handleHandshakeTimeout;

    private int writeRetryCount = 0;
    private static final int MAX_WRITE_RETRIES = 3;
    private static final long WRITE_TIMEOUT_MS = 5000;
    private final Runnable writeWatchdogRunnable = this::handleWriteTimeout;

    // File transfer
    private byte[] fileBytesToSend;
    private long fileTotalSize;
    private boolean isFileTransferActive = false;
    private byte transferKey = 0x01; // default to watchface (0x01)

    // Pre-transfer / Setup
    private int preTransferIndex = 0;
    private int setupStep = 0;
    private Runnable transferTimeoutRunnable;
    private int transferRetryCount = 0;
    private static final int MAX_TRANSFER_RETRIES = 3;
    private static final long TRANSFER_TIMEOUT_MS = 3000;
    private long lastTransferOffset = -1;
    private Runnable preTransferTimeoutRunnable;
    private static final long PRE_TRANSFER_TIMEOUT_MS = 1500;
    private Runnable setupTimeoutRunnable;
    private static final long SETUP_TIMEOUT_MS = 2000;

    // Handshake Magic Bytes — exactly from omo version
    private static final byte[] HANDSHAKE_CMD = new byte[] {
            (byte) 0xAB, 0x01, 0x00, 0x07,
            (byte) 0xB1, (byte) 0xB2, 0x03, 0x02,
            0x20, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
    };

    // CRC16-Modbus Table — exactly from omo version
    private static final int[] CRC16_TABLE = {
            0, 49345, 49537, 320, 49921, 960, 640, 49729, 50689, 1728, 1920, 51009, 1280, 50625, 50305, 1088, 52225,
            3264, 3456, 52545, 3840, 53185, 52865, 3648, 2560, 51905, 52097, 2880, 51457, 2496, 2176, 51265, 55297,
            6336, 6528, 55617, 6912, 56257, 55937, 6720, 7680, 57025, 57217, 8000, 56577, 7616, 7296, 56385, 5120,
            54465, 54657, 5440, 55041, 6080, 5760, 54849, 53761, 4800, 4992, 54081, 4352, 53697, 53377, 4160, 61441,
            12480, 12672, 61761, 13056, 62401, 62081, 12864, 13824, 63169, 63361, 14144, 62721, 13760, 13440, 62529,
            15360, 64705, 64897, 15680, 65281, 16320, 16000, 65089, 64001, 15040, 15232, 64321, 14592, 63937, 63617,
            14400, 10240, 59585, 59777, 10560, 60161, 11200, 10880, 59969, 60929, 11968, 12160, 61249, 11520, 60865,
            60545, 11328, 58369, 9408, 9600, 58689, 9984, 59329, 59009, 9792, 8704, 58049, 58241, 9024, 57601, 8640,
            8320, 57409, 40961, 24768, 24960, 41281, 25344, 41921, 41601, 25152, 26112, 42689, 42881, 26432, 42241,
            26048, 25728, 42049, 27648, 44225, 44417, 27968, 44801, 28608, 28288, 44609, 43521, 27328, 27520, 43841,
            26880, 43457, 43137, 26688, 30720, 47297, 47489, 31040, 47873, 31680, 31360, 47681, 48641, 32448, 32640,
            48961, 32000, 48577, 48257, 31808, 46081, 29888, 30080, 46401, 30464, 47041, 46721, 30272, 29184, 45761,
            45953, 29504, 45313, 29120, 28800, 45121, 20480, 37057, 37249, 20800, 37633, 21440, 21120, 37441, 38401,
            22208, 22400, 38721, 21760, 38337, 38017, 21568, 39937, 23744, 23936, 40257, 24320, 40897, 40577, 24128,
            23040, 39617, 39809, 23360, 39169, 22976, 22656, 38977, 34817, 18624, 18816, 35137, 19200, 35777, 35457,
            19008, 19968, 36545, 36737, 20288, 36097, 19904, 19584, 35905, 17408, 33985, 34177, 17728, 34561, 18368,
            18048, 34369, 33281, 17088, 17280, 33601, 16640, 33217, 32897, 16448
    };

    // ========== BLE LOG ==========
    private static final List<String> bleLog = new ArrayList<>();
    private static final int MAX_LOG_LINES = 2000;
    private static final SimpleDateFormat LOG_TIME_FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public interface BleStateListener {
        void onConnectionStateChange(boolean connected, boolean sessionReady);

        void onHealthDataReceived(String keyName, byte[] payload);

        void onHealthSyncComplete();

        void onTransferProgress(int percent, long bytesTransferred, long totalBytes);

        void onTransferComplete();

        void onLogUpdated();

        default void onFindPhoneRequest() {}

        default void onTransferFailed(String reason) {}

        /**
         * The peer we connected to cannot speak the watch protocol. {@code reason}
         * is one of the {@code BleManager.REASON_*} constants.
         */
        default void onDeviceIncompatible(String deviceName, String reason) {}
    }

    // Health data key codes from protocol doc 03-HEALTH-DATA.md
    public static final int HEALTH_KEY_ACTIVITY = 0x02; // 16 bytes/record
    public static final int HEALTH_KEY_HEART_RATE = 0x03; // 6 bytes/record
    public static final int HEALTH_KEY_BLOOD_PRESSURE = 0x04; // 6 bytes/record
    public static final int HEALTH_KEY_SLEEP = 0x05; // 7 bytes/record
    public static final int HEALTH_KEY_WORKOUT = 0x06; // 48 bytes/record
    public static final int HEALTH_KEY_WORKOUT2 = 0x0E; // 128 bytes/record
    public static final int HEALTH_KEY_TEMPERATURE = 0x08; // 6 bytes/record
    public static final int HEALTH_KEY_BLOOD_OXYGEN = 0x09; // 6 bytes/record
    public static final int HEALTH_KEY_HRV = 0x0A; // 6 bytes/record
    // Corrected against the decompiled BleKey enum (protocols/reference):
    public static final int HEALTH_KEY_PRESSURE = 0x0D; // stress (was wrongly 0x0E)
    public static final int HEALTH_KEY_ECG = 0x20; // ECG (was wrongly 0x0D)
    public static final int HEALTH_KEY_BLOOD_GLUCOSE = 0x10; // blood glucose

    // ========== Constructor ==========

    private BleManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized BleManager getInstance(Context context) {
        if (instance == null) {
            instance = new BleManager(context);
        }
        return instance;
    }

    /** Subscribe. Idempotent; the new listener gets the current state at once. */
    public void addListener(BleStateListener listener) {
        if (listener == null || listeners.contains(listener))
            return;
        listeners.add(listener);
        listener.onConnectionStateChange(isConnected, connectionState == ConnectionState.SESSION_READY);
    }

    /** Unsubscribe. Every addListener needs a matching call, or the owner leaks. */
    public void removeListener(BleStateListener listener) {
        listeners.remove(listener);
    }

    /** Fan a callback out to every subscriber, on the main thread. */
    private void forEachListener(java.util.function.Consumer<BleStateListener> call) {
        if (listeners.isEmpty())
            return;
        handler.post(() -> {
            for (BleStateListener l : listeners)
                call.accept(l);
        });
    }

    /**
     * Lightweight connection observer, independent of the single UI
     * {@link BleStateListener} slot that fragments keep overwriting. Used by
     * BleForegroundService so its notification tracks the real link state
     * without polling.
     */
    public interface ConnectionObserver {
        void onConnectionStateChange(boolean connected, boolean sessionReady);
    }

    private final java.util.concurrent.CopyOnWriteArrayList<ConnectionObserver> observers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addConnectionObserver(ConnectionObserver observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
            observer.onConnectionStateChange(isConnected, isSessionReady());
        }
    }

    public void removeConnectionObserver(ConnectionObserver observer) {
        observers.remove(observer);
    }

    private void notifyConnectionState(boolean connected, boolean sessionReady) {
        handler.post(() -> {
            for (BleStateListener l : listeners) {
                l.onConnectionStateChange(connected, sessionReady);
            }
            for (ConnectionObserver o : observers) {
                o.onConnectionStateChange(connected, sessionReady);
            }
        });
    }

    // ========== Logging ==========

    private void log(String msg) {
        String timestamp = LOG_TIME_FMT.format(new Date());
        String line = "[" + timestamp + "] " + msg;
        Log.d(TAG, msg);
        synchronized (bleLog) {
            bleLog.add(line);
            while (bleLog.size() > MAX_LOG_LINES) {
                bleLog.remove(0);
            }
        }
        forEachListener(BleStateListener::onLogUpdated);
    }

    public static List<String> getLogLines() {
        synchronized (bleLog) {
            return new ArrayList<>(bleLog);
        }
    }

    public static String getLogText() {
        synchronized (bleLog) {
            StringBuilder sb = new StringBuilder();
            for (String line : bleLog) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    public static void clearLog() {
        synchronized (bleLog) {
            bleLog.clear();
        }
    }

    // ========== Connection ==========

    /**
     * Auto-reconnection, replicating the original CO-FIT/SMA app
     * (com.bestmafen.baseble.connector.AbsBleConnector):
     *
     * - a single retry loop with a linear back-off: 5s, 10s, 15s ... 40s, then
     *   it wraps back to 5s (mRetry * mReconnectBasePeriod, capped by
     *   mReconnectMaxPeriod);
     * - every other attempt alternates between a *direct* connectGatt() on the
     *   remembered MAC and a short *scan* (BALANCED mode, at most 12s and never
     *   more than 75% of the current back-off window) — the watch stops
     *   advertising while it thinks it is bonded, so neither strategy alone is
     *   reliable;
     * - the loop only exists while the link is down. As soon as GATT reports
     *   CONNECTED it is cancelled, so a healthy connection costs nothing;
     * - it stops completely when Bluetooth is off or the user unbound the
     *   watch, and restarts on ACTION_STATE_CHANGED -> STATE_ON.
     */
    private static final int RECONNECT_BASE_PERIOD_S = 5;
    private static final int RECONNECT_MAX_PERIOD_S = 40;
    private static final int SCAN_MAX_DURATION_S = 12;

    private static final String PREF_AUTO_CONNECT = "auto_connect_enabled";
    private static final String PREF_DEVICE_VERIFIED = "device_verified";

    private volatile String targetAddress;
    private boolean autoReconnect = true;
    private boolean isReconnecting = false;
    private boolean connectDirectly = true;
    private int retry = 0;
    private boolean receiverRegistered = false;

    private android.bluetooth.le.ScanCallback scanCallback;
    private final Runnable stopScanRunnable = this::stopReconnectScan;

    /** One tick of the back-off loop — mirrors AbsBleConnector.mReconnection. */
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (isConnected) {
                // Won the race against onConnectionStateChange(CONNECTED).
                isReconnecting = false;
                stopReconnectScan();
                return;
            }
            closeConnection(false);

            if (!shouldReconnect()) {
                isReconnecting = false;
                log("Auto-reconnect paused (bluetooth off or no bound device)");
                return;
            }

            retry++;
            if (retry < 1) {
                retry = 1;
            }
            int periodS = retry * RECONNECT_BASE_PERIOD_S;
            if (periodS > RECONNECT_MAX_PERIOD_S) {
                retry = 1;
                periodS = RECONNECT_BASE_PERIOD_S;
            }

            if (connectDirectly) {
                log("Auto-reconnect: direct connect (next retry in " + periodS + "s)");
                connectGattToTarget();
            } else {
                int scanSeconds = (int) (periodS * 0.75f);
                if (scanSeconds > SCAN_MAX_DURATION_S) {
                    scanSeconds = SCAN_MAX_DURATION_S;
                }
                if (scanSeconds < 1) {
                    scanSeconds = 1;
                }
                log("Auto-reconnect: scan " + scanSeconds + "s (next retry in " + periodS + "s)");
                startReconnectScan(scanSeconds);
            }

            connectDirectly = !connectDirectly;
            handler.postDelayed(this, periodS * 1000L);
        }
    };

    /** Bluetooth adapter on/off, as in AbsBleConnector.mReceiver. */
    private final android.content.BroadcastReceiver adapterStateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()))
                return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_OFF) {
                log("Bluetooth turned OFF");
                closeConnection(false);
                setReconnecting(false);
                if (isConnected) {
                    isConnected = false;
                    connectionState = ConnectionState.DISCONNECTED;
                    notifyConnectionState(false, false);
                }
            } else if (state == BluetoothAdapter.STATE_ON) {
                log("Bluetooth turned ON");
                if (autoReconnect && shouldReconnect()) {
                    setReconnecting(true);
                }
            }
        }
    };

    private void registerAdapterStateReceiver() {
        if (receiverRegistered)
            return;
        try {
            androidx.core.content.ContextCompat.registerReceiver(context, adapterStateReceiver,
                    new android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        } catch (Exception e) {
            log("registerReceiver failed: " + e.getMessage());
        }
    }

    private boolean shouldReconnect() {
        return autoReconnect
                && bluetoothAdapter != null
                && bluetoothAdapter.isEnabled()
                && targetAddress != null
                && !targetAddress.isEmpty()
                && hasConnectPermission();
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            return true;
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            return true;
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Starts/stops the retry loop. Idempotent, exactly like
     * AbsBleConnector.connect(boolean): calling it with the state it is already
     * in is a no-op, which is what keeps a single loop running.
     */
    private synchronized void setReconnecting(boolean reconnecting) {
        if (isReconnecting == reconnecting)
            return;
        isReconnecting = reconnecting;
        retry = 0;
        if (reconnecting) {
            log("Auto-reconnect started");
            handler.post(reconnectRunnable);
        } else {
            log("Auto-reconnect stopped");
            stopReconnectScan();
            handler.removeCallbacks(reconnectRunnable);
        }
    }

    public boolean isReconnecting() {
        return isReconnecting;
    }

    public void setAutoReconnect(boolean enabled) {
        autoReconnect = enabled;
        if (!enabled) {
            setReconnecting(false);
        }
    }

    /**
     * Tears the GATT client down properly. The previous implementation only
     * dropped the flags, leaving bluetoothGatt non-null forever: that both
     * leaked an app-level GATT client per disconnect (Android allows ~32) and
     * made the old reconnect() guard bail out permanently, which is why the
     * connection never came back without a manual tap.
     */
    @SuppressLint("MissingPermission")
    private synchronized void closeConnection(boolean stopReconnecting) {
        commandQueue.clear();
        isSending = false;
        isFileTransferActive = false;
        handler.removeCallbacks(writeWatchdogRunnable);
        handler.removeCallbacks(handshakeTimeoutRunnable);

        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
            } catch (Exception ignored) {
            }
            try {
                bluetoothGatt.close();
            } catch (Exception ignored) {
            }
            bluetoothGatt = null;
        }
        writeChar = null;

        if (stopReconnecting) {
            targetAddress = null;
            setReconnecting(false);
        }
    }

    @SuppressLint("MissingPermission")
    private void connectGattToTarget() {
        String address = targetAddress;
        if (address == null || bluetoothAdapter == null || !hasConnectPermission())
            return;
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            openGatt(device);
        } catch (Exception e) {
            log("connectGatt failed: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void openGatt(BluetoothDevice device) {
        if (bluetoothGatt != null)
            return;
        int transport = BluetoothDevice.TRANSPORT_LE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, transport);
        } else {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
        }
    }

    /** Address-filtered BALANCED scan, bounded in time — same as the original. */
    @SuppressLint("MissingPermission")
    private void startReconnectScan(int seconds) {
        final String address = targetAddress;
        if (address == null || bluetoothAdapter == null || !hasScanPermission()) {
            connectGattToTarget();
            return;
        }
        android.bluetooth.le.BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            connectGattToTarget();
            return;
        }
        stopReconnectScan();

        scanCallback = new android.bluetooth.le.ScanCallback() {
            @Override
            public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                if (result == null || result.getDevice() == null)
                    return;
                if (!address.equalsIgnoreCase(result.getDevice().getAddress()))
                    return;
                log("Auto-reconnect: device found by scan, connecting");
                stopReconnectScan();
                openGatt(result.getDevice());
            }

            @Override
            public void onScanFailed(int errorCode) {
                log("Auto-reconnect: scan failed (" + errorCode + "), falling back to direct connect");
                stopReconnectScan();
                connectGattToTarget();
            }
        };

        try {
            List<android.bluetooth.le.ScanFilter> filters = new ArrayList<>();
            filters.add(new android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(address).build());
            android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
                    .build();
            scanner.startScan(filters, settings, scanCallback);
            handler.postDelayed(stopScanRunnable, seconds * 1000L);
        } catch (Exception e) {
            log("startScan failed: " + e.getMessage());
            scanCallback = null;
            connectGattToTarget();
        }
    }

    @SuppressLint("MissingPermission")
    private void stopReconnectScan() {
        handler.removeCallbacks(stopScanRunnable);
        android.bluetooth.le.ScanCallback cb = scanCallback;
        scanCallback = null;
        if (cb == null || bluetoothAdapter == null)
            return;
        try {
            android.bluetooth.le.BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner != null)
                scanner.stopScan(cb);
        } catch (Exception ignored) {
        }
    }

    /** User-initiated connect (device picker / "Reconnect" button). */
    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            log("ERROR: Missing BLUETOOTH_CONNECT permission!");
            return;
        }

        registerAdapterStateReceiver();
        closeConnection(false);
        isConnected = false;
        connectionState = ConnectionState.DISCONNECTED;

        targetAddress = device.getAddress();
        autoReconnect = true;
        // NOT persisted yet: a device only becomes "the watch" once service
        // discovery proves it speaks our protocol (see onServicesDiscovered).
        // Persisting on the attempt is how a mis-tap used to bind the app to a
        // pair of smart glasses permanently.
        prefs.edit().putBoolean(PREF_AUTO_CONNECT, true).apply();

        String deviceName = device.getName() != null ? device.getName() : device.getAddress();
        log("Connecting to " + deviceName + " (" + device.getAddress() + ")");

        // Keep the link alive in the background from the very first pairing,
        // instead of waiting for the next app start.
        try {
            BleForegroundService.start(context);
        } catch (Exception e) {
            log("Could not start foreground service: " + e.getMessage());
        }

        // Go through the retry loop so a failed first attempt is retried
        // automatically instead of leaving the user on a dead "Connecting…".
        connectDirectly = true;
        setReconnecting(false);
        setReconnecting(true);
    }

    /**
     * User-initiated disconnect: stops the retry loop and forgets the target,
     * so the watch stays disconnected until the user reconnects.
     */
    public void disconnect() {
        log("Disconnecting (user request)...");
        autoReconnect = false;
        prefs.edit().putBoolean(PREF_AUTO_CONNECT, false).apply();
        boolean wasConnected = isConnected;
        isConnected = false;
        connectionState = ConnectionState.DISCONNECTED;
        closeConnection(true);
        autoReconnect = true; // re-armed for the next explicit connect
        if (wasConnected) {
            notifyConnectionState(false, false);
        }
        // Drop the foreground notification too — nothing is being kept alive.
        try {
            context.stopService(new Intent(context, BleForegroundService.class));
        } catch (Exception ignored) {
        }
    }

    public boolean isSessionReady() {
        return isConnected && connectionState == ConnectionState.SESSION_READY;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getLastDeviceAddress() {
        return prefs.getString("last_device_address", null);
    }

    public String getLastDeviceName() {
        return prefs.getString("last_device_name", null);
    }

    public boolean isAutoConnectEnabled() {
        return prefs.getBoolean(PREF_AUTO_CONNECT, true);
    }

    /**
     * The remembered watch, but only once a GATT session has proven it speaks
     * our protocol. Callers that would skip the device picker must use this,
     * never {@link #getLastDeviceAddress()}.
     */
    public String getVerifiedDeviceAddress() {
        if (!prefs.getBoolean(PREF_DEVICE_VERIFIED, false))
            return null;
        return prefs.getString("last_device_address", null);
    }

    @SuppressLint("MissingPermission")
    private void rememberVerifiedDevice(BluetoothDevice device) {
        if (device == null)
            return;
        String name = null;
        try {
            name = device.getName();
        } catch (Exception ignored) {
        }
        prefs.edit()
                .putString("last_device_address", device.getAddress())
                .putString("last_device_name", name != null ? name : "")
                .putBoolean(PREF_DEVICE_VERIFIED, true)
                .apply();
        log("Device verified and remembered: " + (name != null ? name : device.getAddress()));
    }

    /**
     * Print every service and characteristic the peer exposes.
     *
     * This runs when discovery fails to find the watch protocol, and it is the
     * whole point of that failure path: "device may not be compatible" told a
     * user nothing they could act on, and told us nothing we could diagnose.
     * The dump lands in the same BLE log the developer tools already copy and
     * save, so an incompatibility report arrives with the peer's real GATT
     * table attached instead of requiring the reporter to install nRF Connect.
     */
    @SuppressLint("MissingPermission")
    private void dumpGattTable(BluetoothGatt gatt) {
        if (gatt == null)
            return;
        List<BluetoothGattService> services;
        try {
            services = gatt.getServices();
        } catch (Exception e) {
            log("GATT dump unavailable: " + e.getMessage());
            return;
        }
        if (services == null || services.isEmpty()) {
            log("GATT dump: the peer exposed no services at all");
            return;
        }
        log("--- GATT dump (" + services.size() + " services) ---");
        for (BluetoothGattService service : services) {
            log("  service " + BleUuids.describe(service.getUuid()));
            List<BluetoothGattCharacteristic> chars = service.getCharacteristics();
            if (chars == null || chars.isEmpty()) {
                log("    (no characteristics)");
                continue;
            }
            for (BluetoothGattCharacteristic c : chars) {
                log("    char " + BleUuids.describe(c.getUuid())
                        + " props=" + BleUuids.describeProperties(c.getProperties())
                        + (c.getDescriptor(BleUuids.CCCD) != null ? " +CCCD" : ""));
            }
        }
        log("--- end GATT dump ---");
    }

    /** Why a peer was rejected. Surfaced to the UI so it can say something useful. */
    public static final String REASON_NO_SERVICE = "no_service";
    public static final String REASON_NO_CHARACTERISTICS = "no_characteristics";
    public static final String REASON_NO_CCCD = "no_cccd";

    /**
     * The device we connected to does not speak the watch protocol. Retrying it
     * on the back-off loop would burn battery forever against hardware that can
     * never answer, so forget it and stop — but tell the UI why, because the
     * user otherwise just sees the connection drop back to "disconnected".
     */
    @SuppressLint("MissingPermission")
    private void handleIncompatibleDevice(BluetoothDevice device, String reason) {
        String name = device != null ? device.getAddress() : "device";
        try {
            if (device != null && device.getName() != null)
                name = device.getName();
        } catch (Exception ignored) {
        }
        log("Not a compatible watch: " + name + " - forgetting it");

        String address = device != null ? device.getAddress() : null;
        String remembered = prefs.getString("last_device_address", null);
        if (address != null && address.equalsIgnoreCase(remembered)) {
            prefs.edit()
                    .remove("last_device_address")
                    .remove("last_device_name")
                    .putBoolean(PREF_DEVICE_VERIFIED, false)
                    .apply();
        }

        isConnected = false;
        connectionState = ConnectionState.DISCONNECTED;
        autoReconnect = false;
        closeConnection(true);
        autoReconnect = true;
        notifyConnectionState(false, false);

        final String shownName = name;
        forEachListener(l -> l.onDeviceIncompatible(shownName, reason));
    }

    /**
     * Entry point for the foreground service / boot: arm the retry loop for the
     * remembered watch. Cheap and idempotent — if the link is already up
     * nothing happens.
     */
    @SuppressLint("MissingPermission")
    public void reconnect(String address) {
        if (address == null || bluetoothAdapter == null)
            return;
        if (!isAutoConnectEnabled())
            return;
        registerAdapterStateReceiver();
        targetAddress = address;
        autoReconnect = true;
        if (isConnected || isReconnecting)
            return;
        setReconnecting(true);
    }

    // ========== GATT Callback — ported from omo version ==========

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                // The link is up: cancel the retry loop, it costs nothing while idle.
                setReconnecting(false);
                synchronized (BleManager.this) {
                    if (bluetoothGatt == null) {
                        bluetoothGatt = gatt;
                    }
                }
                targetAddress = gatt.getDevice().getAddress();
                log("Connected (status=" + status + "). Discovering services...");
                notifyConnectionState(true, false);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasConnected = isConnected;
                isConnected = false;
                connectionState = ConnectionState.DISCONNECTED;
                log("Disconnected (status=" + status + ") - Resetting state");
                commandQueue.clear();
                isSending = false;
                isFileTransferActive = false;
                handler.removeCallbacks(rxAssemblyTimeout);
                handler.removeCallbacks(handshakeTimeoutRunnable);
                rxAssembly = null;
                rxFilled = 0;
                handler.removeCallbacks(healthTimeoutRunnable);
                healthKeyIndex = -1;
                healthPageCount = 0;
                lastHealthPageFingerprint = 0;
                if (wasConnected || !isReconnecting) {
                    notifyConnectionState(false, false);
                }
                if (autoReconnect && shouldReconnect()) {
                    // Restarts the back-off from 5s; no-op if already looping.
                    handler.post(() -> setReconnecting(true));
                } else {
                    closeConnection(false);
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(BleUuids.SERVICE);
                if (service != null) {
                    writeChar = service.getCharacteristic(BleUuids.WRITE_CHAR);
                    BluetoothGattCharacteristic notifyChar = service.getCharacteristic(BleUuids.NOTIFY_CHAR);

                    if (writeChar != null && notifyChar != null) {
                        log("STF Services Found!");
                        gatt.setCharacteristicNotification(notifyChar, true);
                        BluetoothGattDescriptor descriptor = notifyChar.getDescriptor(BleUuids.CCCD);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                            log("Enabling notifications...");
                        } else {
                            log("ERROR: CCCD descriptor not found!");
                            dumpGattTable(gatt);
                            handleIncompatibleDevice(gatt.getDevice(), REASON_NO_CCCD);
                        }
                    } else {
                        log("ERROR: " + BleUuids.describe(BleUuids.SERVICE)
                                + " is present but its read/write characteristics are not");
                        dumpGattTable(gatt);
                        handleIncompatibleDevice(gatt.getDevice(), REASON_NO_CHARACTERISTICS);
                    }
                } else {
                    log("ERROR: this device does not expose "
                            + BleUuids.describe(BleUuids.SERVICE)
                            + ", which is the only channel the watch protocol runs on");
                    dumpGattTable(gatt);
                    handleIncompatibleDevice(gatt.getDevice(), REASON_NO_SERVICE);
                }
            } else {
                log("ERROR: Service discovery failed, status=" + status);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Notifications enabled. Requesting MTU...");
                gatt.requestMtu(512);
            } else {
                log("ERROR: Descriptor write failed, status=" + status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu - 3;
                // Only a guess. DEVICE_INFO overrides it later in the handshake
                // when the watch reports its real buffer; MTU is renegotiated
                // before that on every reconnect, so this resets cleanly.
                ioBufferSize = 2 * currentMtu;
                log("MTU changed to " + mtu + " (payload=" + currentMtu + ", chunkSize=" + ioBufferSize + ")");
                sendHandshake();
            } else {
                log("ERROR: MTU change failed, status=" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data != null) {
                log("Rx [" + data.length + "]: " + bytesToHex(data));
                onRxChunk(data);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            handler.removeCallbacks(writeWatchdogRunnable);
            writeRetryCount = 0;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendNextChunk();
            } else {
                log("Write failed: status=" + status);
            }
        }
    };

    // ========== Protocol — ported EXACTLY from omo version ==========

    private void sendHandshake() {
        log("Sending Handshake...");
        connectionState = ConnectionState.HANDSHAKE_SENT;
        if (!isFileTransferActive) {
            commandQueue.clear();
        }
        packetsSent = 0;
        enqueueLogicalFrame(HANDSHAKE_CMD);
        isSending = true;
        sendNextChunk();
        handler.removeCallbacks(handshakeTimeoutRunnable);
        handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS);
    }

    /**
     * No SESSION CREATE reply. Drop the link so the existing back-off loop
     * retries from a clean state instead of sitting on a connection that will
     * never produce a session. Deliberately not treated as incompatibility: a
     * missed reply is far more often a flaky link than the wrong hardware, and
     * forgetting the watch over one silent handshake would be worse than the
     * hang it replaces.
     */
    private void handleHandshakeTimeout() {
        if (connectionState != ConnectionState.HANDSHAKE_SENT)
            return;
        log("ERROR: no SESSION CREATE reply after " + HANDSHAKE_TIMEOUT_MS
                + "ms — dropping the link so the retry loop can start over");
        connectionState = ConnectionState.DISCONNECTED;
        isConnected = false;
        closeConnection(false);
        notifyConnectionState(false, false);
        if (autoReconnect && shouldReconnect())
            setReconnecting(true);
    }

    /**
     * Called when the watch acknowledges the SESSION CREATE handshake.
     * Marks the session ready and replicates the CO-FIT app's post-connect
     * routine: synchronise the clock and basic device settings, then notify the
     * UI. This mirrors the verified capture, where the phone pushes TIME_ZONE +
     * TIME + HOUR_SYSTEM immediately after the handshake reply.
     */
    private void onSessionReady() {
        connectionState = ConnectionState.SESSION_READY;
        handler.removeCallbacks(handshakeTimeoutRunnable);
        log("=== SESSION READY ===");
        // Verification means "answered our protocol", not "exposed a service
        // with the right UUID". Marking the device at discovery time let any
        // peer carrying a Nordic UART service inherit the picker-skipping
        // shortcut without ever proving it speaks to us.
        BluetoothGatt gatt = bluetoothGatt;
        if (gatt != null)
            rememberVerifiedDevice(gatt.getDevice());
        notifyConnectionState(true, true);
        handler.postDelayed(this::readDeviceInfo, 200);
        handler.postDelayed(this::readFirmwareVersion, 350);
        handler.postDelayed(this::readBattery, 500);
        // Push time + settings shortly after the session is up.
        handler.postDelayed(this::syncTimeAndSettings, 800);
        // Then push weather (network fetch, off the main thread) once things settle.
        handler.postDelayed(() -> WeatherSync.syncIfPossible(context, this), 2500);
    }

    /**
     * Feed one BLE notification into the frame reassembler.
     *
     * A logical frame is AB | hdr | len(2) | crc(2) | cmd | key | flag | payload,
     * where len = payload + 3, so the whole frame is len + 6 bytes. Anything
     * longer than the MTU arrives split over several notifications, and only the
     * first one carries the 0xAB header. Health pages are 1024 payload bytes and
     * were losing their last two notifications entirely, which is why the app
     * stopped seeing anything newer than the middle of the first page.
     */
    private void onRxChunk(byte[] data) {
        if (rxAssembly != null) {
            int need = rxAssembly.length - rxFilled;
            int take = Math.min(need, data.length);
            System.arraycopy(data, 0, rxAssembly, rxFilled, take);
            rxFilled += take;
            if (rxFilled < rxAssembly.length)
                return;
            byte[] complete = rxAssembly;
            rxAssembly = null;
            rxFilled = 0;
            handler.removeCallbacks(rxAssemblyTimeout);
            log("Rx frame reassembled: " + complete.length + " bytes");
            processResponse(complete);
            if (take < data.length)
                onRxChunk(Arrays.copyOfRange(data, take, data.length));
            return;
        }

        if (data.length == 0)
            return;
        if (data[0] != (byte) 0xAB) {
            log("Rx orphan chunk (" + data.length + " bytes, no frame in progress) — discarding");
            return;
        }
        if (data.length < 6) {
            processResponse(data); // too short to hold a length; let the parser reject it
            return;
        }

        int frameLen = (((data[2] & 0xFF) << 8) | (data[3] & 0xFF)) + 6;
        if (data.length >= frameLen) {
            processResponse(Arrays.copyOfRange(data, 0, frameLen));
            if (data.length > frameLen)
                onRxChunk(Arrays.copyOfRange(data, frameLen, data.length));
            return;
        }

        rxAssembly = new byte[frameLen];
        System.arraycopy(data, 0, rxAssembly, 0, data.length);
        rxFilled = data.length;
        handler.removeCallbacks(rxAssemblyTimeout);
        handler.postDelayed(rxAssemblyTimeout, RX_ASSEMBLY_TIMEOUT_MS);
    }

    /**
     * Process raw response — ported EXACTLY from omo version's processResponse().
     * NO BleParser intermediary — direct byte inspection.
     */
    private void processResponse(byte[] data) {
        if (data.length == 0 || data[0] != (byte) 0xAB)
            return;

        int header = data[1] & 0xFF;
        boolean isReply = (header & 0x10) != 0;

        if (data.length < 9) {
            log("Short frame (" + data.length + " bytes), ignoring");
            return;
        }
        byte cmd = data[6];
        byte key = data[7];
        byte flag = data[8];

        log("Parse: Hdr=0x" + String.format("%02X", header) + " Cmd=0x" + String.format("%02X", cmd)
                + " Key=0x" + String.format("%02X", key) + " Flag=0x" + String.format("%02X", flag)
                + " Reply=" + isReply + " State=" + connectionState);

        // Handle Identity Info Request from watch -> implies Session Ready.
        // Kept as a fallback for firmware variants that solicit identity before
        // we observe the SESSION CREATE reply.
        if ((header & 0x10) == 0 && header == 0x01 && cmd == 0x03 && key == 0x01) {
            log("Received Identity Request - Sending ACK");
            sendAck(cmd, key, flag);
            if (connectionState != ConnectionState.SESSION_READY) {
                onSessionReady();
            }
            return;
        }

        // Handshake / SESSION CREATE ACK.
        // The real CO-FIT app (verified against full_capture.log) does NOT send a
        // separate bind/login with random ints — it sends the fixed SESSION CREATE
        // (cmd=0x03 key=0x02 flag=0x20, data=FFFFFFFF) and proceeds straight into
        // its config sequence as soon as the watch replies. So the reply to the
        // handshake *is* the session being established.
        if (isReply && connectionState == ConnectionState.HANDSHAKE_SENT
                && cmd == 0x03 && key == 0x02) {
            log("SESSION CREATE OK");
            onSessionReady();
            return;
        }

        // Watch requests time sync.
        // Must be a genuine request: the watch also ACKs our own TIME write with
        // the same cmd/key but isReply set, and matching that ACK here made the
        // two sides ping-pong time frames ~11x/second forever, which pinned the
        // radio on and drained both batteries.
        if (!isReply && cmd == 0x02 && key == 0x01 && connectionState == ConnectionState.SESSION_READY
                && !isFileTransferActive) {
            log("Watch requested time sync — sending time");
            syncTime();
            return;
        }

        // Device Info response (Cmd=0x02, Key=0x3E)
        if (isReply && cmd == 0x02 && (key & 0xFF) == 0x3E) {
            onDeviceInfo(data.length > 9 ? Arrays.copyOfRange(data, 9, data.length) : new byte[0]);
            return;
        }

        // Empty UPDATE acks for the settings we write. Recognised so they stop
        // showing up as "Unhandled response" noise in the log.
        if (isReply && cmd == 0x02 && (key == 0x06 || key == 0x07)
                && flag == BleKeyFlag.UPDATE.getValue()) {
            log((key == 0x06 ? "USER_PROFILE" : "STEP_GOAL") + " ack");
            return;
        }

        // Daily goals (STEP 0x0207, CALORIES 0x0239, DISTANCE 0x023A, SLEEP
        // 0x023B). The watch keeps its own copies and the original app reads
        // them on every connect, so a READ reply is the authority — the phone's
        // prefs follow it rather than the other way round.
        if (isReply && cmd == 0x02 && flag == (byte) BleKeyFlag.READ.getValue()
                && (key == 0x07 || key == 0x39 || key == 0x3A || key == 0x3B)) {
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (body.length >= 2) {
                int value = 0;
                for (byte b : body)
                    value = (value << 8) | (b & 0xFF);
                storeGoal(key & 0xFF, value);
            }
            return;
        }

        // Heart-rate alarm (HR_WARNING_SET 0x023F) — 4 bytes.
        if (isReply && cmd == 0x02 && (key & 0xFF) == 0x3F) {
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (body.length >= 4) {
                log("Rx HR_WARNING high=" + (body[0] != 0) + "/" + (body[1] & 0xFF)
                        + " low=" + (body[2] != 0) + "/" + (body[3] & 0xFF));
                prefs.edit()
                        .putBoolean("hr_warn_high_on", body[0] != 0)
                        .putInt("hr_warn_high", body[1] & 0xFF)
                        .putBoolean("hr_warn_low_on", body[2] != 0)
                        .putInt("hr_warn_low", body[3] & 0xFF)
                        .apply();
                notifyWatchSettings();
            }
            return;
        }

        // Hand-wash reminder (WASH_SET 0x0226) — same 6-byte shape as the
        // sedentary and drink-water reminders.
        if (isReply && cmd == 0x02 && (key & 0xFF) == 0x26) {
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (body.length >= 6) {
                log("Rx WASH raw=" + bytesToHex(body));
                storeReminder(0x26, body);
                notifyWatchSettings();
            }
            return;
        }

        // Watch password (0x0235): enabled byte + four characters.
        if (isReply && cmd == 0x02 && (key & 0xFF) == 0x35) {
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (body.length >= 5) {
                StringBuilder pw = new StringBuilder();
                for (int i = 1; i < 5; i++)
                    if ((body[i] & 0xFF) != 0xFF) pw.append((char) (body[i] & 0xFF));
                log("Rx WATCH_PASSWORD enabled=" + (body[0] != 0) + " len=" + pw.length());
                prefs.edit()
                        .putBoolean("watch_password_on", body[0] != 0)
                        .putString("watch_password", pw.toString())
                        .apply();
                notifyWatchSettings();
            }
            return;
        }

        // SOS (0x024E): enabled byte, number length, then the fixed field.
        if (isReply && cmd == 0x02 && (key & 0xFF) == 0x4E) {
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (body.length >= 2) {
                int len = Math.min(body[1] & 0xFF, Math.max(0, body.length - 2));
                StringBuilder phone = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    int c = body[2 + i] & 0xFF;
                    if (c != 0xFF && c != 0) phone.append((char) c);
                }
                log("Rx SOS enabled=" + (body[0] != 0) + " phone=" + phone.length() + " digits");
                prefs.edit()
                        .putBoolean("sos_on", body[0] != 0)
                        .putString("sos_phone", phone.toString())
                        .apply();
                notifyWatchSettings();
            }
            return;
        }

        // Game-time reminder (0x0251): enabled byte + minutes.
        if (isReply && cmd == 0x02 && (key & 0xFF) == 0x51) {
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (body.length >= 2) {
                log("Rx GAME_TIME enabled=" + (body[0] != 0) + " after " + (body[1] & 0xFF) + "min");
                prefs.edit()
                        .putBoolean("game_time_on", body[0] != 0)
                        .putInt("game_time_min", body[1] & 0xFF)
                        .apply();
                notifyWatchSettings();
            }
            return;
        }

        // Vibration repeats (0x020B, 0-3) and unit system (0x0211, 0 metric).
        if (isReply && cmd == 0x02 && ((key & 0xFF) == 0x0B || (key & 0xFF) == 0x11)) {
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (body.length >= 1) {
                int v = body[0] & 0xFF;
                log("Rx " + ((key & 0xFF) == 0x0B ? "VIBRATION" : "UNIT_SET") + "=" + v);
                prefs.edit().putInt((key & 0xFF) == 0x0B ? "watch_vibration" : "watch_units", v).apply();
                notifyWatchSettings();
            }
            return;
        }

        // User profile READ reply (Cmd=0x02, Key=0x06, FLAG=READ).
        if (isReply && cmd == 0x02 && key == 0x06 && flag == BleKeyFlag.READ.getValue()
                && data.length >= 20) {
            ByteBuffer bb = ByteBuffer.wrap(data, 9, 11).order(ByteOrder.LITTLE_ENDIAN);
            int unit = bb.get() & 0xFF;
            int gender = bb.get() & 0xFF;
            int age = bb.get() & 0xFF;
            float height = bb.getFloat();
            float weight = bb.getFloat();
            log("Rx USER_PROFILE: unit=" + unit + " gender=" + gender + " age=" + age
                    + " height=" + height + "cm weight=" + weight + "kg");
            return;
        }

        // Battery response (Cmd=0x02, Key=0x03)
        if (isReply && cmd == 0x02 && key == 0x03) {
            int battery = (data.length > 9) ? (data[9] & 0xFF) : 0;
            log("Battery Level: " + battery + "%");
            prefs.edit().putInt("battery_level", battery).apply();
            return;
        }

        // Firmware Version response (Cmd=0x02, Key=0x04)
        if (isReply && cmd == 0x02 && key == 0x04) {
            String version = parseFirmwareVersionPayload(data, 9);
            if (!version.isEmpty()) {
                String formatted = (version.startsWith("v") || version.startsWith("V")) ? version : "v" + version;
                log("Firmware Version: " + formatted);
                prefs.edit().putString("firmware_version", formatted).apply();
                notifyConnectionState(true, true);
            }
            return;
        }

        // Watchface ID ACK
        if (isReply && cmd == 0x02 && key == 0x27 && connectionState == ConnectionState.WATCHFACE_ID_SENT) {
            log("Watchface ID ACK");
            handler.postDelayed(this::startPreTransferSequence, 50);
            return;
        }

        // Pre-Transfer ACK
        if (isReply && connectionState == ConnectionState.PRE_TRANSFER) {
            if (preTransferTimeoutRunnable != null)
                handler.removeCallbacks(preTransferTimeoutRunnable);
            log("Pre-Transfer ACK");
            preTransferIndex++;
            handler.postDelayed(this::sendNextPreTransferCommand, 50);
            return;
        }

        // Setup1 ACK
        if (isReply && cmd == 0x02 && key == 0x20 && connectionState == ConnectionState.SETUP1_SENT) {
            if (setupTimeoutRunnable != null)
                handler.removeCallbacks(setupTimeoutRunnable);
            log("Setup1 ACK");
            setupStep = 2;
            handler.postDelayed(this::sendSetupStep2, 50);
            return;
        }

        // Setup2 ACK
        if (isReply && cmd == 0x04 && key == 0x0C && connectionState == ConnectionState.SETUP2_SENT) {
            if (setupTimeoutRunnable != null)
                handler.removeCallbacks(setupTimeoutRunnable);
            log("Setup2 ACK");
            handler.postDelayed(this::startStreamTransfer, 50);
            return;
        }

        // Progress / Completion
        if (cmd == 0x07 && (key == 0x01 || key == 0x02) && data.length >= 18) {
            if (transferTimeoutRunnable != null)
                handler.removeCallbacks(transferTimeoutRunnable);
            transferRetryCount = 0;
            byte[] payload = Arrays.copyOfRange(data, 9, data.length);
            int statusByte = payload[0] & 0xFF;
            int transferStatus = (statusByte >> 4) & 0x0F;
            int error = statusByte & 0x0F;

            ByteBuffer bb = ByteBuffer.wrap(payload);
            bb.order(ByteOrder.BIG_ENDIAN);
            bb.position(1);
            long total = bb.getInt() & 0xFFFFFFFFL;
            long completed = bb.getInt() & 0xFFFFFFFFL;
            int percent = (total > 0) ? (int) ((completed * 100) / total) : 0;
            log("Progress: " + completed + "/" + total + " (" + percent + "%) Err=" + error);

            forEachListener(l -> l.onTransferProgress(percent, completed, total));

            if (transferStatus == 0) {
                if (isFileTransferActive && completed < total) {
                    // Guard: only send next chunk if the offset actually advanced.
                    // This prevents an infinite loop when the watch echoes back
                    // completed=0 (or a stale offset) before the transfer truly begins.
                    if (completed > lastTransferOffset || lastTransferOffset < 0) {
                        lastTransferOffset = completed;
                        sendStreamChunk(completed);
                    } else {
                        log("Progress stalled at " + completed + " (lastOffset=" + lastTransferOffset
                                + ") — waiting for watch to advance");
                    }
                } else if (isFileTransferActive && completed >= total) {
                    log("=== Transfer Complete! ===");
                    isFileTransferActive = false;
                    connectionState = ConnectionState.SESSION_READY;
                    commandQueue.clear();
                    isSending = false;
                    packetsSent = 0;
                    lastTransferOffset = -1;
                    forEachListener(BleStateListener::onTransferComplete);
                }
            }
            return;
        }

        // Health data responses (Cmd=0x05) — parse per protocol doc 03-HEALTH-DATA.md
        if (cmd == 0x05) {
            byte[] healthPayload = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            String keyName = getHealthKeyName(key & 0xFF);
            log("Health [" + keyName + "] payload=" + healthPayload.length + "B: " + bytesToHex(healthPayload));

            // Parse health records and store to SharedPreferences
            boolean stored = parseAndStoreHealthData(key & 0xFF, healthPayload);

            forEachListener(l -> l.onHealthDataReceived(keyName, healthPayload));

            // ACK unsolicited pushes (watch -> phone) before driving the sync state
            // machine, otherwise advance the sequential paging.
            if (!isReply) {
                sendAck(cmd, key, flag);
            } else {
                onHealthPage(key & 0xFF, flag & 0xFF, healthPayload, stored);
            }
            return;
        }

        // Find phone (SET FIND_PHONE 0x0213, watch -> phone): the watch asks the
        // phone to ring. The phone keeps ringing until the user stops it (or the
        // watch sends a stop, payload[0]==0). Verified against the original app's
        // BleHandleCallback.onFindPhone(boolean).
        if (cmd == 0x02 && key == 0x13 && !isReply) {
            boolean start = (data.length <= 9) || (data[9] != 0);
            log("Find phone: start=" + start);
            sendAck(cmd, key, flag);
            if (start)
                startFindPhoneAlert();
            else
                stopFindPhoneAlert();
            return;
        }

        // Alarms (ALARM 0x0210).
        //
        // Only READ and RESET replies carry the list, as a run of 28-byte items
        // with no count field — verified on a Kronos Thunder, whose reply to a
        // read-all was LEN=0x3B (56 bytes) for two alarms.
        //
        // CREATE and UPDATE come back as a bodyless ACK ("AB 11 00 03 .. 02 10
        // 20") and DELETE draws no reply at all, so none of them say anything
        // about the resulting list. sendAlarm/deleteAlarm schedule a re-read
        // instead of trying to infer it.
        if (cmd == 0x02 && key == 0x10) {
            byte[] body = Arrays.copyOfRange(data, 9, data.length);
            int f = flag & 0xFF;

            if ((f == BleKeyFlag.READ.getValue() || f == BleKeyFlag.RESET.getValue())
                    && body.length >= BleAlarm.ITEM_LENGTH) {
                List<BleAlarm> items = BleAlarm.decodeList(body);
                log("Rx ALARM list: " + items.size() + " alarm(s)");
                storeAlarms(items);
                notifyAlarms(items);
            } else if (f == BleKeyFlag.READ.getValue() || f == BleKeyFlag.RESET.getValue()) {
                // An empty body is a real answer: the watch holds no alarms.
                log("Rx ALARM list: empty");
                List<BleAlarm> none = new ArrayList<>();
                storeAlarms(none);
                notifyAlarms(none);
            } else {
                log("Rx ALARM ack flag=0x" + String.format("%02X", f));
            }
            if (!isReply)
                sendAck(cmd, key, flag);
            return;
        }

        // The watch answered or rejected the ringing call (INCOMING_CALL
        // 0x0603, watch -> phone). Payload is one byte: 0 answers, anything
        // else hangs up — the original app's onIncomingCallStatus branches the
        // same way, with every non-zero value falling through to endCall().
        if (cmd == 0x06 && key == 0x03 && !isReply) {
            sendAck(cmd, key, flag);
            int action = data.length > 9 ? (data[9] & 0xFF) : CALL_HANG_UP;
            log("Call action from watch: " + (action == CALL_ANSWER ? "answer" : "hang up"));
            CallListener l = callListener;
            if (l != null)
                handler.post(() -> l.onCallAction(action == CALL_ANSWER ? CALL_ANSWER : CALL_HANG_UP));
            return;
        }

        // Monitoring windows (HR 0x0216, SpO2 0x0225, sleep 0x0240) and
        // FIND_WATCH (0x0234) —
        // a write is answered with a bodyless ACK, while a READ comes back with
        // the stored setting, which is what the rows render from (the watch is
        // the source of truth, as it is for alarms).
        if (isReply && cmd == 0x02
                && (key == 0x34 || key == 0x16 || key == 0x25 || key == 0x40 || key == 0x1B)) {
            byte[] body = Arrays.copyOfRange(data, 9, data.length);
            if (body.length >= 5 && key != 0x34) {
                boolean on = body[0] != 0;
                int sh = body[1] & 0xFF, sm = body[2] & 0xFF;
                int eh = body[3] & 0xFF, em = body[4] & 0xFF;
                // Sleep has no interval byte; the other two do.
                int interval = body.length >= 6 ? body[5] & 0xFF : 0;
                log("Rx monitoring key=0x" + String.format("%02X", key)
                        + " enabled=" + on + " " + sh + ":" + sm + "-" + eh + ":" + em
                        + (body.length >= 6 ? " every " + interval + "min" : "")
                        + "  raw=" + bytesToHex(body));
                storeMonitoring(key & 0xFF, on, sh, sm, eh, em, interval);
                MonitoringListener ml = monitoringListener;
                if (ml != null)
                    handler.post(ml::onMonitoringChanged);
            } else {
                log("Rx ack key=0x" + String.format("%02X", key)
                        + " flag=0x" + String.format("%02X", flag));
            }
            return;
        }

        // Camera shutter pushed from the watch (CONTROL 0x0601, watch -> phone)
        if (cmd == 0x06 && key == 0x01 && !isReply) {
            log("Camera shutter from watch");
            sendAck(cmd, key, flag);
            if (cameraListener != null) {
                handler.post(() -> cameraListener.onShutter());
            }
            return;
        }

        // Standby watch face (STANDBY_WATCH_FACE_SET 0x0254). A READ comes back
        // with the eight bytes the watch has stored, which is what the row
        // renders from; a write is answered with a bodyless ACK. Firmware that
        // does not implement the key answers everything with an empty body,
        // and that is the case we have to tell apart from a real ACK — see
        // sendStandby() for why the master switch travels separately.
        if (cmd == 0x02 && (key & 0xFF) == 0x54) {
            byte[] body = data.length > 9 ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (body.length >= 7) {
                standbyScheduleSupported = true;
                boolean on = body[0] != 0;
                boolean allDay = body[1] != 0;
                int sh = body[3] & 0xFF, sm = body[4] & 0xFF;
                int eh = body[5] & 0xFF, em = body[6] & 0xFF;
                log("Rx STANDBY_WATCH_FACE enabled=" + on + " allDay=" + allDay
                        + " " + sh + ":" + sm + "-" + eh + ":" + em
                        + "  raw=" + bytesToHex(body));
                prefs.edit()
                        .putBoolean("standby_enabled", on)
                        .putBoolean("standby_allday", allDay)
                        .putInt("standby_sh", sh).putInt("standby_sm", sm)
                        .putInt("standby_eh", eh).putInt("standby_em", em)
                        .apply();
                MonitoringListener ml = monitoringListener;
                if (ml != null)
                    handler.post(ml::onMonitoringChanged);
            } else if ((flag & 0xFF) == BleKeyFlag.READ.getValue() && isReply) {
                // An empty answer to a READ is this firmware saying it has no
                // such setting; the schedule half of the row stays local-only.
                standbyScheduleSupported = false;
                log("Rx STANDBY_WATCH_FACE: empty — key not implemented by this firmware");
            } else {
                log("Rx STANDBY_WATCH_FACE ack flag=0x" + String.format("%02X", flag));
            }
            if (!isReply) sendAck(cmd, key, flag);
            return;
        }

        // Reminders / Health Settings (Sedentary 0x0209, Drink Water 0x0221, Wash 0x0228,
        // Girl Care 0x021A, Standby Set 0x0241 — 0x0254 is handled above).
        //
        // A write comes back as a bodyless ACK; a READ comes back with what the
        // watch actually stored, which is the only way to tell an accepted frame
        // from an understood one — the watch ACKs 0x09/0x21 either way.
        if (cmd == 0x02 && (key == 0x09 || key == 0x21 || key == 0x28 || key == 0x1A || (key & 0xFF) == 0x41)) {
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (isReply && flag == (byte) BleKeyFlag.READ.getValue() && body.length > 0) {
                log("Rx reminder key=0x" + String.format("%02X", key)
                        + " len=" + body.length + " raw=" + bytesToHex(body));
                if ((key == 0x09 || key == 0x21) && body.length >= 6) {
                    storeReminder(key & 0xFF, body);
                    MonitoringListener ml = monitoringListener;
                    if (ml != null)
                        handler.post(ml::onMonitoringChanged);
                }
            } else {
                log("Rx settings ack key=0x" + String.format("%02X", key) + " flag=0x" + String.format("%02X", flag));
            }
            if (!isReply) sendAck(cmd, key, flag);
            return;
        }

        // World Clock (0x0407)
        //
        // Three different things arrive under this key and they have to be told
        // apart by direction, not by flag alone:
        //   * watch -> phone DELETE with a one-byte body: the user removed a
        //     city on the watch.
        //   * reply DELETE/CREATE with no body: the plain ACK for the reset and
        //     the pushes syncToWatch() just sent. Treating those as "the watch
        //     deleted id=-1" is what filled the log with bogus delete attempts.
        //   * reply READ/READ_CONTINUE: one page of the list, see
        //     onWorldClockPage().
        if (cmd == 0x04 && key == 0x07) {
            int f = flag & 0xFF;
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            log("Rx WORLD_CLOCK flag=0x" + String.format("%02X", f) + " isReply=" + isReply
                    + " body=" + body.length + "B");
            if (!isReply && f == BleKeyFlag.DELETE.getValue() && body.length >= 1) {
                int id = body[0] & 0xFF;
                log("Watch deleted WORLD_CLOCK id=" + id);
                WorldClockManager.deleteClockById(context, id);
                sendAck(cmd, key, flag);
                return;
            }
            if (isReply && (f == BleKeyFlag.READ.getValue() || f == BleKeyFlag.READ_CONTINUE.getValue())) {
                onWorldClockPage(body);
                return;
            }
            if (!isReply) sendAck(cmd, key, flag);
            return;
        }

        // Stock Market (0x0408)
        if (cmd == 0x04 && key == 0x08) {
            int f = flag & 0xFF;
            log("Rx STOCK flag=0x" + String.format("%02X", f) + " isReply=" + isReply);
            if (f == BleKeyFlag.DELETE.getValue()) {
                int id = (data.length > 9) ? (data[9] & 0xFF) : -1;
                log("Watch deleted STOCK id=" + id);
                StockMarketManager.deleteStockById(context, id);
            }
            if (!isReply) sendAck(cmd, key, flag);
            return;
        }

        // Music transport buttons on the watch (MUSIC_CONTROL 0x0402, watch ->
        // phone). One byte: 0 play, 1 pause, 2 toggle, 3 next, 4 previous,
        // 5/6 volume — see docs/protocols/12-MUSIC-CONTROL.md §2.
        //
        // This branch did not exist: WatchMusicController.onWatchCommand() was
        // written and never called, so every button press fell through to the
        // "Unhandled response" line below. Next and previous appeared to work
        // because the watch is also an AVRCP device over classic Bluetooth and
        // that path skips tracks on its own; play/pause had nothing to fall
        // back on and did nothing.
        if (cmd == 0x04 && key == 0x02) {
            byte[] body = (data.length > 9) ? Arrays.copyOfRange(data, 9, data.length) : new byte[0];
            if (!isReply && body.length >= 1) {
                int command = body[0] & 0xFF;
                log("Rx MUSIC command from watch: " + command + " raw=" + bytesToHex(body));
                WatchMusicController mc = getMusicController();
                handler.post(() -> mc.onWatchCommand(command));
                sendAck(cmd, key, flag);
            } else {
                log("Rx MUSIC ack flag=0x" + String.format("%02X", flag)
                        + " body=" + body.length + "B");
            }
            return;
        }

        // Unknown response
        log("Unhandled response: Cmd=0x" + String.format("%02X", cmd) + " Key=0x" + String.format("%02X", key));
    }

    // ========== File Transfer — ported from omo version ==========

    public void startFileTransfer(byte[] fileData) {
        startFileTransfer(fileData, (byte) 0x01);
    }

    public void startFileTransfer(byte[] fileData, byte key) {
        if (connectionState != ConnectionState.SESSION_READY) {
            log("Session not ready! Current state: " + connectionState);
            return;
        }
        if (fileData == null || fileData.length == 0) {
            log("No file data to send");
            return;
        }

        this.fileBytesToSend = fileData;
        this.fileTotalSize = fileData.length;
        this.transferKey = key;
        commandQueue.clear();
        isSending = false;
        packetsSent = 0;
        preTransferIndex = 0;
        setupStep = 0;
        isFileTransferActive = true;
        lastTransferOffset = -1;

        log("Starting file transfer (" + fileTotalSize + " bytes) for key 0x" + String.format("%02X", key));
        if (key == 0x02) {
            // AGPS: start streaming directly!
            startStreamTransfer();
        } else {
            // Watchface: do the full setup sequence
            startPreTransferSequence();
        }
    }

    public void cancelTransfer() {
        log("Transfer cancelled");
        isFileTransferActive = false;
        commandQueue.clear();
        isSending = false;
        connectionState = ConnectionState.SESSION_READY;
    }

    private static class PreTransferCommand {
        byte cmd, key, flag, header;
        byte[] payload;

        PreTransferCommand(int cmd, int key, int flag, byte[] payload) {
            this.cmd = (byte) cmd;
            this.key = (byte) key;
            this.flag = (byte) flag;
            this.header = 0x01;
            this.payload = payload;
        }

        PreTransferCommand(int cmd, int key, int flag, byte[] payload, int header) {
            this.cmd = (byte) cmd;
            this.key = (byte) key;
            this.flag = (byte) flag;
            this.header = (byte) header;
            this.payload = payload;
        }
    }

    private List<PreTransferCommand> getPreTransferCommands() {
        List<PreTransferCommand> cmds = new ArrayList<>();
        cmds.add(new PreTransferCommand(0x02, 0x1a, 0x00,
                new byte[] { 0x00, 0x0a, 0x00, 0x02, 0x03, 0x30, 0x00, 0x00, 0x05, 0x1c }, 0x01));
        cmds.add(new PreTransferCommand(0x02, 0x1a, 0x00,
                new byte[] { 0x00, 0x0a, 0x00, 0x02, 0x03, 0x30, 0x00, 0x00, 0x05, 0x1c }, 0x00));
        cmds.add(new PreTransferCommand(0x02, 0x1a, 0x00,
                new byte[] { 0x00, 0x0a, 0x00, 0x02, 0x03, 0x30, 0x00, 0x00, 0x05, 0x1c }, 0x03));
        cmds.add(new PreTransferCommand(0x02, 0x03, 0x10, null));
        cmds.add(new PreTransferCommand(0x02, 0x02, 0x00, new byte[] { 0x04 }));
        cmds.add(new PreTransferCommand(0x02, 0x15, 0x00, new byte[] { 0x00 }));
        cmds.add(new PreTransferCommand(0x05, 0x02, 0x10, null));
        cmds.add(new PreTransferCommand(0x02, 0x03, 0x10, null));
        return cmds;
    }

    private void startPreTransferSequence() {
        log("Starting Pre-Transfer...");
        connectionState = ConnectionState.PRE_TRANSFER;
        preTransferIndex = 0;
        sendNextPreTransferCommand();
    }

    private void sendNextPreTransferCommand() {
        List<PreTransferCommand> cmds = getPreTransferCommands();
        if (preTransferIndex >= cmds.size()) {
            log("Pre-Transfer Done");
            startSetupSequence();
            return;
        }
        PreTransferCommand c = cmds.get(preTransferIndex);
        byte[] msg = createMessageWithHeader(c.header, c.cmd, c.key, c.flag, c.payload);
        connectionState = ConnectionState.PRE_TRANSFER;
        log("Pre-Transfer step " + (preTransferIndex + 1) + "/" + cmds.size() + ": " + bytesToHex(msg));
        enqueueLogicalFrame(msg);
        isSending = true;
        sendNextChunk();
        schedulePreTransferTimeout();
    }

    private void startSetupSequence() {
        log("Starting Setup...");
        sendSetupStep1();
    }

    private void sendSetupStep1() {
        byte[] payload = new byte[] {
                (byte) 0xC3, 0x25, (byte) 0xB3, (byte) 0xC2, (byte) 0x9F, (byte) 0xA2, (byte) 0xA7, 0x41,
                0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };

        log("Setup1: Sending Session/Auth Token");
        byte[] msg = createMessage((byte) 0x02, (byte) 0x20, (byte) 0x00, payload);
        connectionState = ConnectionState.SETUP1_SENT;
        enqueueLogicalFrame(msg);
        isSending = true;
        sendNextChunk();
        scheduleSetupTimeout("SETUP1");
    }

    private void sendSetupStep2() {
        String name = "Custom Dial";
        byte[] nameBytes = name.getBytes();

        byte[] payload = new byte[101];
        Arrays.fill(payload, (byte) 0);

        payload[0] = 0x00;
        payload[1] = 0x1A;
        payload[2] = 0x02;
        payload[3] = 0x05;
        payload[4] = 0x08;
        payload[5] = 0x31;
        payload[6] = (byte) nameBytes.length;

        System.arraycopy(nameBytes, 0, payload, 7, Math.min(nameBytes.length, 60));

        byte[] tail = new byte[] {
                0x10, 0x00, 0x00, 0x01, 0x00, 0x03, 0x51, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x21, 0x2D, 0x00, 0x33,
                0x04, 0x02, 0x00, 0x00
        };

        int tailPos = payload.length - tail.length;
        System.arraycopy(tail, 0, payload, tailPos, tail.length);

        log("Setup2: Sending dial metadata");
        byte[] msg = createMessage((byte) 0x04, (byte) 0x0C, (byte) 0x00, payload);
        connectionState = ConnectionState.SETUP2_SENT;
        enqueueLogicalFrame(msg);
        isSending = true;
        sendNextChunk();
        scheduleSetupTimeout("SETUP2");
    }

    private void startStreamTransfer() {
        log("Starting Stream Transfer...");
        connectionState = ConnectionState.TRANSFERRING;
        sendStreamChunk(0);
    }

    private void sendStreamChunk(long offset) {
        if (!isFileTransferActive)
            return;

        long remaining = fileTotalSize - offset;
        if (remaining <= 0)
            return;

        int chunkSize = (int) Math.min(remaining, 1018);
        byte[] chunk = new byte[chunkSize];
        System.arraycopy(fileBytesToSend, (int) offset, chunk, 0, chunkSize);

        byte[] id = new byte[4];
        id[0] = (byte) ((fileTotalSize >> 24) & 0xFF);
        id[1] = (byte) ((fileTotalSize >> 16) & 0xFF);
        id[2] = (byte) ((fileTotalSize >> 8) & 0xFF);
        id[3] = (byte) (fileTotalSize & 0xFF);

        ByteBuffer bb = ByteBuffer.allocate(9 + chunkSize);
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) 0);
        bb.put(id);
        bb.putInt((int) offset);
        bb.put(chunk);

        byte[] message = createMessage((byte) 0x07, transferKey, (byte) 0x00, bb.array());
        enqueueLogicalFrame(message);
        isSending = true;
        sendNextChunk();
        scheduleTransferTimeout(offset);
    }

    // ========== Health Sync ==========

    public static final int HEALTH_KEY_LOCATION = 0x07; // GPS location coordinate records
    /**
     * Rich workout record, 2048 bytes, carrying the GPS polyline plus HR/pace/
     * speed/cadence/altitude series (BleWorkout3 in the original app). We never
     * asked for it, which is why watch workouts arrived without a route.
     */
    public static final int HEALTH_KEY_WORKOUT3 = 0x23;

    /**
     * Request all health data from the watch.
     * Sends READ (0x10) requests for each health data BleKey in the 0x05xx range.
     * Per protocol doc 03-HEALTH-DATA.md, the watch responds with packed binary
     * records.
     */
    // Keys queried during a full health sync, in order.
    private static final int[] HEALTH_SYNC_KEYS = {
            HEALTH_KEY_ACTIVITY, // 0x02 - Steps/Calories/Distance
            HEALTH_KEY_HEART_RATE, // 0x03 - Heart rate BPM
            HEALTH_KEY_BLOOD_PRESSURE, // 0x04 - Systolic/Diastolic
            HEALTH_KEY_SLEEP, // 0x05 - Sleep stages
            HEALTH_KEY_WORKOUT, // 0x06 - Workout session data
            HEALTH_KEY_WORKOUT2, // 0x0E - Rich workout session data
            HEALTH_KEY_WORKOUT3, // 0x23 - Workout with GPS polyline
            HEALTH_KEY_LOCATION, // 0x07 - GPS coordinate records
            HEALTH_KEY_TEMPERATURE, // 0x08 - Body temperature
            HEALTH_KEY_BLOOD_OXYGEN, // 0x09 - SpO2
            HEALTH_KEY_HRV, // 0x0A - Heart rate variability
            HEALTH_KEY_PRESSURE, // 0x0D - Stress level
    };
    // The keys this sync run will actually walk. Normally HEALTH_SYNC_KEYS,
    // narrowed to what DEVICE_INFO said the watch supports when it told us.
    private int[] activeHealthKeys = HEALTH_SYNC_KEYS;
    private int healthKeyIndex = -1;
    private int healthPageCount = 0;
    private int lastHealthPageFingerprint = 0;
    // A health request the watch never answers used to wedge the sync forever:
    // healthKeyIndex stayed >= 0 and syncHealth() refused every later attempt
    // until the app was restarted. This moves the walk on instead.
    private static final long HEALTH_RESPONSE_TIMEOUT_MS = 8000;
    private final Runnable healthTimeoutRunnable = this::onHealthTimeout;
    // Safety cap against infinite paging. Each page is real progress now that
    // DELETE confirms it, so this only bounds how much of a long backlog one
    // sync drains; the rest comes down on the next one.
    private static final int MAX_HEALTH_PAGES = 200;

    /**
     * Request all health data from the watch, one key at a time.
     * For each key we send a READ; the watch replies with a page of packed
     * records. While the page is non-empty we keep paging with READ_CONTINUE;
     * an empty page means that key is exhausted and we advance to the next.
     */
    public void syncHealth() {
        if (connectionState != ConnectionState.SESSION_READY) {
            log("Cannot sync health: session not ready (state=" + connectionState + ")");
            return;
        }
        if (healthKeyIndex >= 0) {
            // Already walking the key list. Restarting would reset the cursor to
            // key 0 mid-flight and re-request pages the watch has already
            // handed over — it returns them empty, so those records are lost.
            log("Health sync already in progress (key index " + healthKeyIndex + ") — ignoring");
            return;
        }
        activeHealthKeys = resolveHealthKeys();
        log("=== Syncing Health Data (" + activeHealthKeys.length + " keys) ===");
        healthKeyIndex = 0;
        healthPageCount = 0;
        requestHealthKey(activeHealthKeys[0], BleKeyFlag.READ.getValue());
    }

    /**
     * Narrow the health sync to the keys this watch reported in DEVICE_INFO.
     *
     * Each unsupported key costs a full 8s timeout, so a model that only has
     * four sensors used to spend a minute and a half waiting on silence. The
     * watch reporting nothing means "I did not tell you", not "I support
     * nothing" — that falls back to the built-in list rather than syncing zero
     * keys. A watch that reports keys we have no parser for is likewise not our
     * problem: the intersection only ever shrinks the built-in list.
     */
    private int[] resolveHealthKeys() {
        BleDeviceInfo info = deviceInfo;
        if (info == null || !info.hasDataKeys())
            return HEALTH_SYNC_KEYS;

        int[] filtered = new int[HEALTH_SYNC_KEYS.length];
        int n = 0;
        StringBuilder skipped = new StringBuilder();
        for (int key : HEALTH_SYNC_KEYS) {
            if (info.supportsKey(0x0500 | key)) {
                filtered[n++] = key;
            } else {
                if (skipped.length() > 0)
                    skipped.append(", ");
                skipped.append(getHealthKeyName(key));
            }
        }
        if (n == 0) {
            // The watch listed keys, but none we handle. Trust our list over an
            // empty walk that would report "sync complete" having done nothing.
            log("Health sync: DEVICE_INFO matched none of our keys — using built-in list");
            return HEALTH_SYNC_KEYS;
        }
        if (skipped.length() > 0)
            log("Health sync: skipping unsupported keys [" + skipped + "]");
        return Arrays.copyOf(filtered, n);
    }

    private void requestHealthKey(int key, int flag) {
        // Verified against the live CO-FIT capture: data reads carry NO payload
        // (the request is just CMD=05, KEY, FLAG=READ with an empty body). An
        // empty reply from the watch means "no more records" for that key.
        byte[] msg = createMessage((byte) 0x05, (byte) key, (byte) flag, null);
        log("Health READ key=0x" + String.format("%02X", key) + " flag=0x" + String.format("%02X", flag));
        handler.removeCallbacks(healthTimeoutRunnable);
        handler.postDelayed(healthTimeoutRunnable, HEALTH_RESPONSE_TIMEOUT_MS);
        enqueueLogicalFrame(msg);
        flushQueue();
    }

    /**
     * Advance the sequential health sync after a key's page was processed.
     *
     * The watch keeps a read cursor per key and only moves it when the phone
     * confirms the page with DELETE — the health keys accept exactly READ and
     * DELETE, nothing else (established empirically against the watch; the
     * SDK's own getBleKeyFlags() table cannot be recovered from the decompile,
     * see 02-COMMAND-PROTOCOL.md §6). Without that confirmation the watch replays its
     * oldest unconfirmed block forever, which is why the app had been stuck on
     * mid-August data while the watch held six more days of it.
     *
     * DELETE is only sent once the page is safely committed to prefs, so a
     * failed write costs a retry next sync instead of losing the records.
     */
    private void onHealthPage(int key, int flag, byte[] payload, boolean stored) {
        if (healthKeyIndex < 0)
            return; // not in a sync session (e.g. unsolicited push)
        handler.removeCallbacks(healthTimeoutRunnable);

        if (flag == BleKeyFlag.DELETE.getValue()) {
            // The watch dropped the page it just gave us; ask for the next one.
            healthPageCount++;
            if (healthPageCount < MAX_HEALTH_PAGES) {
                requestHealthKey(key, BleKeyFlag.READ.getValue());
                return;
            }
            log("Health [" + getHealthKeyName(key) + "]: page cap reached, continuing next sync");
            nextHealthKey();
            return;
        }

        int payloadLen = (payload != null) ? payload.length : 0;
        if (payloadLen == 0) {
            nextHealthKey(); // key exhausted
            return;
        }
        if (!stored) {
            log("Health [" + getHealthKeyName(key) + "]: page not stored — leaving it on the watch");
            nextHealthKey();
            return;
        }
        // Safety net: if a firmware variant ignores the DELETE, we would ask for
        // the same block forever. Stop as soon as a page repeats.
        int fingerprint = Arrays.hashCode(payload);
        if (healthPageCount > 0 && fingerprint == lastHealthPageFingerprint) {
            log("Health [" + getHealthKeyName(key) + "]: page repeated — cursor is not advancing, stopping");
            nextHealthKey();
            return;
        }
        lastHealthPageFingerprint = fingerprint;
        requestHealthKey(key, BleKeyFlag.DELETE.getValue());
    }

    /** The watch did not answer a health request — move on rather than wedge. */
    private void onHealthTimeout() {
        if (healthKeyIndex < 0)
            return;
        int key = activeHealthKeys[Math.min(healthKeyIndex, activeHealthKeys.length - 1)];
        log("Health [" + getHealthKeyName(key) + "]: no reply in " + HEALTH_RESPONSE_TIMEOUT_MS + "ms — skipping key");
        nextHealthKey();
    }

    /** Move the sync on to the next key, or finish it. */
    private void nextHealthKey() {
        healthKeyIndex++;
        healthPageCount = 0;
        lastHealthPageFingerprint = 0;
        if (healthKeyIndex < activeHealthKeys.length) {
            requestHealthKey(activeHealthKeys[healthKeyIndex], BleKeyFlag.READ.getValue());
        } else {
            healthKeyIndex = -1;
            handler.removeCallbacks(healthTimeoutRunnable);
            log("=== Health Sync Complete ===");
            prefs.edit().putLong("last_sync_time", System.currentTimeMillis() / 1000L).apply();
            forEachListener(BleStateListener::onHealthSyncComplete);
        }
    }

    /** Per-key retention for the health series stored in SharedPreferences. */
    private static final int HEALTH_MAX_RECORDS = 20000;

    /**
     * One record per timestamp, latest value wins, ordered by time.
     *
     * The watch replays a page until it is told to drop it, and a page that
     * arrives twice used to be appended twice: steps grew from 1751 to 2199
     * stored records in one night without a single new measurement.
     */
    private static String dedupeSeries(String list) {
        if (list == null || list.isEmpty())
            return "";
        java.util.TreeMap<Long, String> byTime = new java.util.TreeMap<>();
        for (String rec : list.split(",")) {
            int colon = rec.indexOf(':');
            if (colon <= 0)
                continue;
            try {
                byTime.put(Long.parseLong(rec.substring(0, colon).trim()), rec);
            } catch (NumberFormatException ignored) {
                // not a record we wrote — drop it
            }
        }
        // Bound the series. Dedupe alone stops a re-sync from inflating it, but
        // a year of heart-rate samples still ends up as one multi-hundred-KB
        // SharedPreferences string that every render() reparses. Keep the most
        // recent HEALTH_MAX_RECORDS; raise it if you import older history.
        while (byTime.size() > HEALTH_MAX_RECORDS)
            byTime.pollFirstEntry();
        StringBuilder sb = new StringBuilder();
        for (String rec : byTime.values()) {
            if (sb.length() > 0)
                sb.append(",");
            sb.append(rec);
        }
        return sb.toString();
    }

    /**
     * Drop exact duplicate records, keeping insertion order. Used for GPS
     * fixes, where two points can legitimately share a timestamp and keying on
     * time alone would thin out the route.
     */
    private static String dedupeRecords(String list) {
        if (list == null || list.isEmpty())
            return "";
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String rec : list.split(",")) {
            if (!rec.isEmpty())
                seen.add(rec);
        }
        StringBuilder sb = new StringBuilder();
        for (String rec : seen) {
            if (sb.length() > 0)
                sb.append(",");
            sb.append(rec);
        }
        return sb.toString();
    }

    /** Whether the accumulated workout list already holds a record for this start. */
    private static boolean containsWorkoutStart(CharSequence stored, int start) {
        String prefix = start + ":";
        String all = stored.toString();
        if (all.startsWith(prefix))
            return true;
        return all.contains("," + prefix);
    }

    /** One record per start timestamp, first one wins. */
    private static String dedupeWorkoutList(String list) {
        if (list == null || list.isEmpty())
            return "";
        java.util.LinkedHashMap<String, String> byStart = new java.util.LinkedHashMap<>();
        for (String rec : list.split(",")) {
            int colon = rec.indexOf(':');
            if (colon <= 0)
                continue;
            String start = rec.substring(0, colon).trim();
            if (!byStart.containsKey(start))
                byStart.put(start, rec);
        }
        StringBuilder sb = new StringBuilder();
        for (String rec : byStart.values()) {
            if (sb.length() > 0)
                sb.append(",");
            sb.append(rec);
        }
        return sb.toString();
    }

    /**
     * Converts a watch timestamp to a real Unix epoch.
     *
     * The watch counts seconds from 2000-01-01, but its clock is the *local*
     * wall clock — syncTime() sends local hours, not UTC. Adding the epoch
     * alone therefore yields a value that reads two hours late here, which is
     * why a workout finished at 23:29 appeared in the history as 01:29 the
     * next day. Subtracting the zone offset puts it back on real time.
     */
    private static int watchTimeToEpoch(int raw) {
        long asLocal = (raw & 0xFFFFFFFFL) + 946684800L;
        int offsetSec = TimeZone.getDefault().getOffset(asLocal * 1000L) / 1000;
        return (int) (asLocal - offsetSec);
    }

    private String getHealthKeyName(int key) {
        switch (key) {
            case HEALTH_KEY_ACTIVITY:
                return "activity";
            case HEALTH_KEY_HEART_RATE:
                return "heart_rate";
            case HEALTH_KEY_BLOOD_PRESSURE:
                return "blood_pressure";
            case HEALTH_KEY_SLEEP:
                return "sleep";
            case HEALTH_KEY_WORKOUT:
                return "workout";
            case HEALTH_KEY_WORKOUT3:
                return "workout3";
            case HEALTH_KEY_WORKOUT2:
                return "workout2";
            case HEALTH_KEY_LOCATION:
                return "location";
            case HEALTH_KEY_TEMPERATURE:
                return "temperature";
            case HEALTH_KEY_BLOOD_OXYGEN:
                return "blood_oxygen";
            case HEALTH_KEY_HRV:
                return "hrv";
            case HEALTH_KEY_ECG:
                return "ecg";
            case HEALTH_KEY_PRESSURE:
                return "stress";
            case HEALTH_KEY_BLOOD_GLUCOSE:
                return "blood_glucose";
            default:
                return "unknown_" + String.format("%02X", key);
        }
    }

    /**
     * Parse binary health records per protocol doc 03-HEALTH-DATA.md and store to
     * SharedPreferences.
     * Each entity type has a fixed ITEM_LENGTH. Records are big-endian packed.
     */
    private boolean parseAndStoreHealthData(int key, byte[] payload) {
        if (payload == null || payload.length == 0) {
            log("Health [" + getHealthKeyName(key) + "]: empty payload");
            return false;
        }

        SharedPreferences.Editor editor = prefs.edit();
        String prefix = "health_";

        try {
            ByteBuffer bb = ByteBuffer.wrap(payload);
            bb.order(ByteOrder.BIG_ENDIAN);

            switch (key) {
                case HEALTH_KEY_ACTIVITY: {
                    // ITEM_LENGTH=16: time(4) packed(1) step(3) calorie(4) distance(4)
                    int itemLen = 16;
                    StringBuilder steps = new StringBuilder(prefs.getString(prefix + "steps", ""));
                    StringBuilder calories = new StringBuilder(prefs.getString(prefix + "calories", ""));
                    StringBuilder distance = new StringBuilder(prefs.getString(prefix + "distance", ""));
                    while (bb.remaining() >= itemLen) {
                        int time = watchTimeToEpoch(bb.getInt());
                        int packed = bb.get() & 0xFF;
                        int b0 = bb.get() & 0xFF;
                        int b1 = bb.get() & 0xFF;
                        int b2 = bb.get() & 0xFF;
                        int step = (b0 << 16) | (b1 << 8) | b2;
                        int calorie = bb.getInt() / 10000;
                        int dist = bb.getInt() / 10000;
                        if (steps.length() > 0)
                            steps.append(",");
                        steps.append(time).append(":").append(step);
                        if (calories.length() > 0)
                            calories.append(",");
                        calories.append(time).append(":").append(calorie);
                        if (distance.length() > 0)
                            distance.append(",");
                        distance.append(time).append(":").append(dist);
                        log("  Activity: t=" + time + " steps=" + step + " cal=" + calorie + " dist=" + dist);
                    }
                    editor.putString(prefix + "steps", dedupeSeries(steps.toString()));
                    editor.putString(prefix + "calories", dedupeSeries(calories.toString()));
                    editor.putString(prefix + "distance", dedupeSeries(distance.toString()));
                    break;
                }
                case HEALTH_KEY_HEART_RATE: {
                    // ITEM_LENGTH=6: time(4) bpm(1) type(1)
                    int itemLen = 6;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "heart_rate", ""));
                    while (bb.remaining() >= itemLen) {
                        int time = watchTimeToEpoch(bb.getInt());
                        int bpm = bb.get() & 0xFF;
                        int type = bb.get() & 0xFF;
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(time).append(":").append(bpm);
                        log("  HeartRate: t=" + time + " bpm=" + bpm + " type=" + type);
                    }
                    editor.putString(prefix + "heart_rate", dedupeSeries(sb.toString()));
                    break;
                }
                case HEALTH_KEY_BLOOD_PRESSURE: {
                    // ITEM_LENGTH=6: time(4) systolic(1) diastolic(1)
                    int itemLen = 6;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "blood_pressure", ""));
                    while (bb.remaining() >= itemLen) {
                        int time = watchTimeToEpoch(bb.getInt());
                        int sys = bb.get() & 0xFF;
                        int dia = bb.get() & 0xFF;
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(time).append(":").append(sys).append("/").append(dia);
                        log("  BP: t=" + time + " sys=" + sys + " dia=" + dia);
                    }
                    editor.putString(prefix + "blood_pressure", dedupeSeries(sb.toString()));
                    break;
                }
                case HEALTH_KEY_SLEEP: {
                    // ITEM_LENGTH=7: time(4) mode(1) soft(1) strong(1)
                    int itemLen = 7;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "sleep", ""));
                    while (bb.remaining() >= itemLen) {
                        int time = watchTimeToEpoch(bb.getInt());
                        int mode = bb.get() & 0xFF;
                        int soft = bb.get() & 0xFF;
                        int strong = bb.get() & 0xFF;
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(time).append(":").append(mode).append(":").append(soft).append(":").append(strong);
                        log("  Sleep: t=" + time + " mode=" + mode + " light=" + soft + " deep=" + strong);
                    }
                    editor.putString(prefix + "sleep", dedupeSeries(sb.toString()));
                    break;
                }
                case HEALTH_KEY_TEMPERATURE: {
                    // ITEM_LENGTH=6: time(4) temperature(2) — value *10
                    int itemLen = 6;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "temperature", ""));
                    while (bb.remaining() >= itemLen) {
                        int time = watchTimeToEpoch(bb.getInt());
                        int temp = bb.getShort();
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(time).append(":").append(temp);
                        log("  Temp: t=" + time + " temp=" + (temp / 10.0) + "°C");
                    }
                    editor.putString(prefix + "temperature", dedupeSeries(sb.toString()));
                    break;
                }
                case HEALTH_KEY_BLOOD_OXYGEN: {
                    // ITEM_LENGTH=6: time(4) value(1) padding(1)
                    int itemLen = 6;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "blood_oxygen", ""));
                    while (bb.remaining() >= itemLen) {
                        int time = watchTimeToEpoch(bb.getInt());
                        int spo2 = bb.get() & 0xFF;
                        bb.get(); // padding
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(time).append(":").append(spo2);
                        log("  SpO2: t=" + time + " value=" + spo2 + "%");
                    }
                    editor.putString(prefix + "blood_oxygen", dedupeSeries(sb.toString()));
                    break;
                }
                case HEALTH_KEY_HRV: {
                    // ITEM_LENGTH=6: time(4) value(1) avg(1)
                    int itemLen = 6;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "hrv", ""));
                    while (bb.remaining() >= itemLen) {
                        int time = watchTimeToEpoch(bb.getInt());
                        int val = bb.get(); // signed
                        bb.get(); // avg
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(time).append(":").append(val);
                    }
                    editor.putString(prefix + "hrv", dedupeSeries(sb.toString()));
                    break;
                }
                case HEALTH_KEY_PRESSURE: {
                    // ITEM_LENGTH=6: time(4) value(1) padding(1) — stress 0..100
                    int itemLen = 6;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "stress", ""));
                    while (bb.remaining() >= itemLen) {
                        int time = watchTimeToEpoch(bb.getInt());
                        int val = bb.get() & 0xFF;
                        bb.get(); // padding
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(time).append(":").append(val);
                    }
                    editor.putString(prefix + "stress", dedupeSeries(sb.toString()));
                    break;
                }
                case HEALTH_KEY_WORKOUT: {
                    // ITEM_LENGTH=48: start(4) end(4) duration(2) altitude(2) airPressure(2) spm(1) mode(1) step(4) distance(4) calorie(4) speed(4) pace(4) avgBpm(1) maxBpm(1) padding(10)
                    int itemLen = 48;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "workout", ""));
                    while (bb.remaining() >= itemLen) {
                        int start = watchTimeToEpoch(bb.getInt());
                        int end = watchTimeToEpoch(bb.getInt());
                        int duration = bb.getShort() & 0xFFFF;
                        int altitude = bb.getShort();
                        int airPressure = bb.getShort() & 0xFFFF;
                        int spm = bb.get() & 0xFF;
                        int mode = bb.get() & 0xFF;
                        int step = bb.getInt();
                        int distance = bb.getInt();
                        int calorie = bb.getInt();
                        int speed = bb.getInt();
                        int pace = bb.getInt();
                        int avgBpm = bb.get() & 0xFF;
                        int maxBpm = bb.get() & 0xFF;
                        // Skip remaining 10 bytes padding
                        for (int i = 0; i < 10; i++) {
                            bb.get();
                        }
                        if (!isPlausibleWorkout(start, end, duration)) {
                            log("  Skipping corrupt workout record: start=" + start
                                    + " end=" + end + " duration=" + duration);
                            continue;
                        }
                        if (containsWorkoutStart(sb, start)) {
                            continue; // already stored, from a previous sync or from WORKOUT2
                        }
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(start).append(":")
                          .append(end).append(":")
                          .append(duration).append(":")
                          .append(altitude).append(":")
                          .append(airPressure).append(":")
                          .append(spm).append(":")
                          .append(mode).append(":")
                          .append(step).append(":")
                          .append(distance).append(":")
                          .append(calorie).append(":")
                          .append(speed).append(":")
                          .append(pace).append(":")
                          .append(avgBpm).append(":")
                          .append(maxBpm);
                        log("  Workout: start=" + start + " end=" + end + " mode=" + mode + " steps=" + step + " dist=" + distance + " kcal=" + calorie);

                    }
                    editor.putString(prefix + "workout", dedupeWorkoutList(sb.toString()));
                    break;
                }
                case HEALTH_KEY_WORKOUT3: {
                    // Layout not decoded yet — log enough to see whether this
                    // watch sends it, and what the path header looks like.
                    log("  Workout3 payload " + bb.remaining() + " bytes");
                    byte[] head = new byte[Math.min(bb.remaining(), 160)];
                    bb.get(head);
                    log("  Workout3 head: " + bytesToHex(head));
                    break;
                }
                case HEALTH_KEY_WORKOUT2: {
                    // ITEM_LENGTH=128: start(4) end(4) duration(2) altitude(2) airPressure(2) spm(1) mode(1) step(4) distance(4) calorie(4) speed(4) pace(4) avgBpm(1) maxBpm(1) minBpm(1) undefined(1) maxSpm(2) minSpm(2) maxPace(4) minPace(4) maxAltitude(2) minAltitude(2) minStress(1) maxStress(1) avgStress(1) maxSpeed(4) minSpeed(4) restDuration(4) padding(59)
                    int itemLen = 128;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "workout", ""));
                    while (bb.remaining() >= itemLen) {
                        int start = watchTimeToEpoch(bb.getInt());
                        int end = watchTimeToEpoch(bb.getInt());
                        int duration = bb.getShort() & 0xFFFF;
                        int altitude = bb.getShort();
                        int airPressure = bb.getShort() & 0xFFFF;
                        int spm = bb.get() & 0xFF;
                        int mode = bb.get() & 0xFF;
                        int step = bb.getInt();
                        int distance = bb.getInt();
                        int calorie = bb.getInt();
                        int speed = bb.getInt();
                        int pace = bb.getInt();
                        int avgBpm = bb.get() & 0xFF;
                        int maxBpm = bb.get() & 0xFF;
                        // Skip remaining 90 bytes of the 128-byte item
                        for (int i = 0; i < 90; i++) {
                            bb.get();
                        }
                        if (!isPlausibleWorkout(start, end, duration)) {
                            log("  Skipping corrupt workout record: start=" + start
                                    + " end=" + end + " duration=" + duration);
                            continue;
                        }
                        if (containsWorkoutStart(sb, start)) {
                            continue; // already stored, from a previous sync or from WORKOUT2
                        }
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(start).append(":")
                          .append(end).append(":")
                          .append(duration).append(":")
                          .append(altitude).append(":")
                          .append(airPressure).append(":")
                          .append(spm).append(":")
                          .append(mode).append(":")
                          .append(step).append(":")
                          .append(distance).append(":")
                          .append(calorie).append(":")
                          .append(speed).append(":")
                          .append(pace).append(":")
                          .append(avgBpm).append(":")
                          .append(maxBpm);
                        log("  Workout2: start=" + start + " end=" + end + " mode=" + mode + " steps=" + step + " dist=" + distance + " kcal=" + calorie);

                    }
                    editor.putString(prefix + "workout", dedupeWorkoutList(sb.toString()));
                    break;
                }
                case HEALTH_KEY_LOCATION: {
                    // ITEM_LENGTH=16: time(4) mode(1) padding(1) altitude(2) longitude(4) latitude(4)
                    int itemLen = 16;
                    StringBuilder sb = new StringBuilder(prefs.getString(prefix + "location", ""));
                    while (bb.remaining() >= itemLen) {
                        int time = watchTimeToEpoch(bb.getInt());
                        int mode = bb.get() & 0xFF;
                        bb.get(); // padding (1 byte)
                        int altitude = bb.getShort();
                        float longitude = bb.getFloat();
                        float latitude = bb.getFloat();
                        if (sb.length() > 0)
                            sb.append(",");
                        sb.append(time).append(":").append(mode).append(":").append(altitude).append(":").append(longitude).append(":").append(latitude);
                        log("  Location: t=" + time + " mode=" + mode + " alt=" + altitude + " lon=" + longitude + " lat=" + latitude);
                    }
                    editor.putString(prefix + "location", dedupeRecords(sb.toString()));
                    break;
                }
                default:
                    log("  Unhandled health key 0x" + String.format("%02X", key) + " (" + payload.length + " bytes)");
                    break;
            }
            // commit(), not apply(): the caller only tells the watch it may drop
            // this page once the write has actually landed on disk.
            return editor.commit();
        } catch (Exception e) {
            log("Health parse error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Workout records only make sense with a real timestamp and a real
     * duration. Anything else is a mis-parsed frame, and storing it means a row
     * the user cannot get rid of.
     */
    private static boolean isPlausibleWorkout(int start, int end, int duration) {
        long now = System.currentTimeMillis() / 1000L;
        return start > 1577836800L /* 2020-01-01 */
                && start < now + 86400L
                && end >= start
                && duration > 0;
    }

    // ========== Helpers — ported from omo version ==========

    private byte[] createMessage(byte cmd, byte key, byte flag, byte[] payload) {
        return createMessageWithHeader((byte) 0x01, cmd, key, flag, payload);
    }

    private byte[] createMessageWithHeader(byte header, byte cmd, byte key, byte flag, byte[] payload) {
        int payloadLen = (payload != null) ? payload.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(payloadLen + 9);
        buffer.put((byte) 0xAB);
        buffer.put(header);
        buffer.putShort((short) (payloadLen + 3));
        buffer.putShort((short) 0);
        buffer.put(cmd);
        buffer.put(key);
        buffer.put(flag);
        if (payload != null)
            buffer.put(payload);

        byte[] arr = buffer.array();
        int crc = calculateCrc16(arr, 6);
        arr[4] = (byte) ((crc >> 8) & 0xFF);
        arr[5] = (byte) (crc & 0xFF);
        return arr;
    }

    private void sendAck(byte cmd, byte key, byte flag) {
        byte[] msg = createMessageWithHeader((byte) 0x11, cmd, key, flag, new byte[] { 0x00 });
        log("Tx ACK: " + bytesToHex(msg));
        enqueueLogicalFrame(msg);
        flushQueue();
    }

    private int calculateCrc16(byte[] data, int offset) {
        int crc = 0;
        for (int i = offset; i < data.length; i++) {
            crc = (CRC16_TABLE[(crc ^ (data[i] & 0xFF)) & 0xFF] ^ (crc >>> 8)) & 0xFFFF;
        }
        return crc;
    }

    private void enqueueLogicalFrame(byte[] frame) {
        int offset = 0;
        while (offset < frame.length) {
            int remaining = frame.length - offset;
            int size = Math.min(remaining, currentMtu);
            byte[] chunk = new byte[size];
            System.arraycopy(frame, offset, chunk, 0, size);
            commandQueue.add(chunk);
            offset += size;
        }
    }

    /**
     * Start draining the queue if nothing is in flight.
     *
     * The guard matters for the keys that enqueue several frames at once:
     * kicking a write that is already outstanding would race the GATT write
     * callback, which is what advances the queue.
     */
    /**
     * Start draining the queue if nothing is in flight.
     *
     * Every sender goes through here. Kicking {@link #sendNextChunk()} by hand
     * while a write is already outstanding starts a second
     * {@code writeCharacteristic} on the same characteristic, and the two race:
     * one logical frame is written over and never reaches the watch, while the
     * GATT callback still advances the queue once. That is what silently ate
     * the STEP_GOAL frame in syncUserProfileAndGoals(), which enqueues the
     * profile and four goals and then kicked the queue a second time — the
     * watch ACKed 0x06, 0x39, 0x3A and 0x3B, and 0x07 simply vanished.
     */
    private void flushQueue() {
        if (!isSending) {
            isSending = true;
            sendNextChunk();
        }
    }

    @SuppressLint("MissingPermission")
    private void sendNextChunk() {
        if (commandQueue.isEmpty()) {
            isSending = false;
            return;
        }
        byte[] chunk = commandQueue.poll();
        if (chunk != null && writeChar != null && bluetoothGatt != null) {
            lastChunkSent = chunk;
            writeChar.setValue(chunk);
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            bluetoothGatt.writeCharacteristic(writeChar);
            // Start watchdog
            handler.removeCallbacks(writeWatchdogRunnable);
            handler.postDelayed(writeWatchdogRunnable, WRITE_TIMEOUT_MS);
        }
    }

    @SuppressLint("MissingPermission")
    private void handleWriteTimeout() {
        if (!isSending && commandQueue.isEmpty())
            return;
        writeRetryCount++;
        if (writeRetryCount > MAX_WRITE_RETRIES) {
            log("Write watchdog: max retries exceeded, aborting");
            isFileTransferActive = false;
            commandQueue.clear();
            isSending = false;
            connectionState = ConnectionState.SESSION_READY;
            return;
        }
        log("Write watchdog: retrying chunk (attempt " + writeRetryCount + ")");
        if (lastChunkSent != null && writeChar != null && bluetoothGatt != null) {
            writeChar.setValue(lastChunkSent);
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            bluetoothGatt.writeCharacteristic(writeChar);
            handler.postDelayed(writeWatchdogRunnable, WRITE_TIMEOUT_MS);
        }
    }

    // ========== Timeout / Retry — ported from omo version ==========

    private void scheduleTransferTimeout(long offset) {
        if (transferTimeoutRunnable != null)
            handler.removeCallbacks(transferTimeoutRunnable);
        lastTransferOffset = offset;
        transferTimeoutRunnable = () -> {
            if (isFileTransferActive && lastTransferOffset == offset) {
                transferRetryCount++;
                if (transferRetryCount <= MAX_TRANSFER_RETRIES) {
                    log("Transfer stalled at offset " + offset + ", retry " + transferRetryCount);
                    sendStreamChunk(offset);
                } else {
                    log("Transfer failed after " + MAX_TRANSFER_RETRIES + " retries");
                    isFileTransferActive = false;
                    forEachListener(l -> l.onTransferFailed(
                            "Transfer stalled after " + MAX_TRANSFER_RETRIES + " retries"));
                }
            }
        };
        handler.postDelayed(transferTimeoutRunnable, TRANSFER_TIMEOUT_MS);
    }

    private void schedulePreTransferTimeout() {
        if (preTransferTimeoutRunnable != null)
            handler.removeCallbacks(preTransferTimeoutRunnable);
        preTransferTimeoutRunnable = () -> {
            List<PreTransferCommand> commands = getPreTransferCommands();
            log("Pre-Transfer timeout at step " + (preTransferIndex + 1));
            if (preTransferIndex < commands.size() - 1) {
                preTransferIndex++;
                log("Skipping to next step " + (preTransferIndex + 1));
                sendNextPreTransferCommand();
            } else {
                log("Pre-Transfer exhausted, continuing to setup");
                startSetupSequence();
            }
        };
        handler.postDelayed(preTransferTimeoutRunnable, PRE_TRANSFER_TIMEOUT_MS);
    }

    private void scheduleSetupTimeout(String stage) {
        if (setupTimeoutRunnable != null)
            handler.removeCallbacks(setupTimeoutRunnable);
        setupTimeoutRunnable = () -> {
            log("Setup timeout on " + stage);
            handleSetupTimeout();
        };
        handler.postDelayed(setupTimeoutRunnable, SETUP_TIMEOUT_MS);
    }

    private void handleSetupTimeout() {
        log("Setup failed, attempting direct stream transfer");
        startStreamTransfer();
    }

    // ========== Time / Date / Settings Sync ==========

    public void syncTime() {
        if (!isSessionReady()) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        byte[] timePayload = new byte[] {
            (byte) (cal.get(java.util.Calendar.YEAR) - 2000),
            (byte) (cal.get(java.util.Calendar.MONTH) + 1),
            (byte)  cal.get(java.util.Calendar.DAY_OF_MONTH),
            (byte)  cal.get(java.util.Calendar.HOUR_OF_DAY),
            (byte)  cal.get(java.util.Calendar.MINUTE),
            (byte)  cal.get(java.util.Calendar.SECOND)
        };
        log("Syncing time: " + cal.getTime());
        byte[] timeMsg = createMessage((byte) 0x02, (byte) 0x01, (byte) 0x00, timePayload);
        enqueueLogicalFrame(timeMsg);
        flushQueue();
    }

    public void readBattery() {
        if (!isSessionReady()) return;
        log("Reading battery level...");
        byte[] msg = createMessage((byte) 0x02, (byte) 0x03, (byte) 0x10, null);
        enqueueLogicalFrame(msg);
        flushQueue();
    }

    public void readDeviceInfo() {
        if (!isSessionReady()) return;
        log("Reading device info (0x023E)...");
        byte[] msg = createMessage((byte) 0x02, (byte) 0x3E, (byte) 0x10, null);
        enqueueLogicalFrame(msg);
        flushQueue();
    }

    /** Capability block from the last DEVICE_INFO reply, or null before one arrives. */
    public BleDeviceInfo getDeviceInfo() {
        return deviceInfo;
    }

    /**
     * Handle the DEVICE_INFO (0x023E) reply.
     *
     * This block is what makes feature gating possible: it carries the list of
     * BleKeys this particular watch supports plus ~100 capability flags. It was
     * previously logged and thrown away, so every request the app made was a
     * guess. See docs/protocols/11-DEVICE-INFO-CAPABILITIES.md.
     */
    private void onDeviceInfo(byte[] payload) {
        BleDeviceInfo info = BleDeviceInfo.parse(payload);
        deviceInfo = info;
        log("Device Info: " + info);
        if (info.hasDataKeys())
            log("Device Info supported keys: " + info.dataKeysHex());
        else
            log("Device Info carries no key list (" + info.variant
                    + ") — keeping the built-in sync list");

        // The watch's own buffer size beats the MTU-derived guess for 0x07xx
        // streaming. Ignore an absurd value rather than wedging transfers.
        if (info.ioBufferSize >= 32 && info.ioBufferSize <= 4096) {
            if (info.ioBufferSize != ioBufferSize)
                log("Device Info: chunkSize " + ioBufferSize + " -> " + info.ioBufferSize);
            ioBufferSize = info.ioBufferSize;
        } else if (info.ioBufferSize != 0) {
            log("Device Info: ignoring implausible ioBufferSize=" + info.ioBufferSize
                    + ", keeping " + ioBufferSize);
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putString("device_info_variant", info.variant.name())
                .putString("device_info_platform", info.platform)
                .putString("device_info_prototype", info.prototype)
                .putString("device_info_name", info.bleName)
                .putString("device_info_firmware_flag", info.firmwareFlag)
                .putString("device_info_full_version", info.fullVersion)
                .putString("device_info_firmware_version", info.firmwareVersion)
                .putString("device_info_ui_version", info.uiVersion)
                .putString("device_info_language_version", info.languageVersion)
                .putInt("device_info_agps_type", info.agpsType)
                .putInt("device_info_watchface_type", info.watchFaceType)
                .putInt("device_info_sleep_algorithm", info.sleepAlgorithmType)
                .putInt("device_info_io_buffer", info.ioBufferSize)
                .putString("device_info_data_keys", info.dataKeysHex())
                .putString("device_info_flags", info.flagsCompact());

        if (info.firmwareVersion != null && !info.firmwareVersion.isEmpty() && !info.firmwareVersion.equals("0.0.0")) {
            editor.putString("firmware_version", "v" + info.firmwareVersion);
        } else if (info.fullVersion != null && !info.fullVersion.isEmpty()) {
            String fv = (info.fullVersion.startsWith("v") || info.fullVersion.startsWith("V"))
                    ? info.fullVersion : "v" + info.fullVersion;
            editor.putString("firmware_version", fv);
        }
        editor.apply();
        notifyConnectionState(true, true);
    }

    /**
     * Parses firmware version from raw packet payload (offset 9 onward).
     * Handles ASCII text strings (with optional NUL terminator) and binary version bytes
     * (e.g. [0x00, 0x00, 0x06] -> "0.0.6").
     */
    public static String parseFirmwareVersionPayload(byte[] data, int offset) {
        if (data == null || offset >= data.length) return "";

        int end = data.length;
        for (int i = offset; i < data.length; i++) {
            if (data[i] == 0) {
                end = i;
                break;
            }
        }

        boolean allPrintable = (end > offset);
        for (int i = offset; i < end; i++) {
            int b = data[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                allPrintable = false;
                break;
            }
        }

        if (allPrintable) {
            String str = new String(data, offset, end - offset, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!str.isEmpty()) {
                return str;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < data.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(data[i] & 0xFF);
        }
        return sb.toString();
    }

    public void readFirmwareVersion() {
        if (!isSessionReady()) return;
        log("Reading firmware version (0x0204)...");
        byte[] msg = createMessage((byte) 0x02, (byte) 0x04, (byte) 0x10, null);
        enqueueLogicalFrame(msg);
        flushQueue();
    }

    /**
     * Push current time + basic clock settings to the watch.
     * Order mirrors the verified CO-FIT capture: TIME_ZONE, TIME, HOUR_SYSTEM.
     */
    public void syncTimeAndSettings() {
        if (!isSessionReady()) {
            log("syncTimeAndSettings: session not ready");
            return;
        }
        log("=== Syncing time & settings ===");
        sendTimeZone();
        sendTime();
        sendHourSystem();
        sendUserProfile();
        // The step goal rides along with the other three in syncReminders() ->
        // sendGoals(); sending it here as well just doubled the frame.
        syncReminders();
        flushQueue();
        // Read the profile back so a firmware that accepts the frame but
        // decodes it differently shows up in the log instead of silently
        // skewing every calorie figure the watch reports afterwards.
        handler.postDelayed(this::readUserProfile, 600);
    }

    // Gender codes on the wire, from UserInfoActivity in the original app:
    // it renders R.string.female for 0 and R.string.male for anything else.
    public static final int GENDER_FEMALE = 0;
    public static final int GENDER_MALE = 1;
    // 0 = metric, 1 = imperial (MeasureUnitSettingsActivity maps picker index
    // 0 to 0 and everything else to 1).
    private static final int UNIT_METRIC = 0;
    private static final int UNIT_IMPERIAL = 1;

    /**
     * SET USER_PROFILE (BleKey 0x0206, UPDATE) — 11 bytes.
     *
     * The watch computes calories and stride-based distance on-device, so
     * without this it uses firmware defaults and every derived figure the app
     * later reads back is wrong. The app had never sent it.
     *
     *   [0] unit    uint8   0 = metric, 1 = imperial
     *   [1] gender  uint8   0 = female, 1 = male
     *   [2] age     uint8   years
     *   [3] height  float32 LITTLE-endian, centimetres
     *   [7] weight  float32 LITTLE-endian, kilograms
     *
     * Note the endianness: the two floats are little-endian while the rest of
     * this protocol is big-endian. That is not a transcription slip — the SDK
     * passes ByteOrder.LITTLE_ENDIAN explicitly for both, against a writer
     * whose default is big-endian.
     */
    public void sendUserProfile() {
        if (!isSessionReady()) return;

        int gender = prefs.getInt("profile_gender", GENDER_MALE);
        int age = prefs.getInt("profile_age", 30);
        float heightCm = prefs.getFloat("profile_height_cm", 170f);
        float weightKg = prefs.getFloat("profile_weight_kg", 70f);
        int unit = "lb".equals(prefs.getString("unit_weight", "kg")) ? UNIT_IMPERIAL : UNIT_METRIC;

        // Keep the watch out of arithmetic that would divide by zero or
        // overflow the age byte; fall back to the defaults instead.
        if (age < 1 || age > 120) age = 30;
        if (heightCm < 50f || heightCm > 250f) heightCm = 170f;
        if (weightKg < 10f || weightKg > 300f) weightKg = 70f;

        ByteBuffer bb = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) unit);
        bb.put((byte) (gender == GENDER_FEMALE ? GENDER_FEMALE : GENDER_MALE));
        bb.put((byte) age);
        bb.putFloat(heightCm);
        bb.putFloat(weightKg);

        log("Tx USER_PROFILE: unit=" + unit + " gender=" + gender + " age=" + age
                + " height=" + heightCm + "cm weight=" + weightKg + "kg");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x06, (byte) 0x00, bb.array()));
    }

    /**
     * SET STEP_GOAL (BleKey 0x0207, UPDATE) — int32, big-endian.
     *
     * The goal already existed as a phone-side pref driving the Status ring; it
     * just never reached the watch, so the two showed different targets.
     */
    public void sendStepGoal() {
        if (!isSessionReady()) return;

        int goal = prefs.getInt("goal_steps", 10000);
        if (goal < 1 || goal > 200000) goal = 10000;

        byte[] payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(goal).array();
        log("Tx STEP_GOAL: " + goal);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x07, (byte) 0x00, payload));
    }

    // ===== Daily goals =====
    //
    // Four keys, all big-endian integers, all owned by the watch: the original
    // app reads calories and distance on every connect and never writes them.
    // The units are fixed by what a Kronos returns for its own defaults —
    // 10000 steps, 4000 (4 km), 480 (8 h) — so distance is metres and sleep is
    // minutes. Calories reads 300000 against a 300 kcal default, i.e. small
    // calories, which is why the phone's kcal figure is scaled by 1000.

    private static final int CAL_PER_KCAL = 1000;

    private void storeGoal(int key, int value) {
        SharedPreferences.Editor e = prefs.edit();
        switch (key) {
            case 0x07: e.putInt("goal_steps", value); break;
            case 0x39: e.putInt("goal_calories", Math.max(1, value / CAL_PER_KCAL)); break;
            case 0x3A: e.putInt("goal_distance", Math.max(1, value / 1000)); break;
            case 0x3B: e.putInt("goal_sleep_min", value); break;
            default: return;
        }
        log("Rx goal key=0x" + String.format("%02X", key) + " raw=" + value);
        e.apply();
        notifyWatchSettings();
    }

    /** Push the three goals the step goal used to travel without. */
    public void sendGoals() {
        if (!isSessionReady()) return;
        sendStepGoal();

        int kcal = clamp(prefs.getInt("goal_calories", 500), 1, 20000);
        int km = clamp(prefs.getInt("goal_distance", 5), 1, 500);
        int sleepMin = clamp(prefs.getInt("goal_sleep_min", 480), 1, 1440);

        log("Tx GOALS calories=" + kcal + "kcal distance=" + km + "km sleep=" + sleepMin + "min");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x39, (byte) 0x00,
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(kcal * CAL_PER_KCAL).array()));
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x3A, (byte) 0x00,
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(km * 1000).array()));
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x3B, (byte) 0x00,
                ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort((short) sleepMin).array()));
        flushQueue();
    }

    /**
     * Read the goals back after pushing them.
     *
     * All four round-trip, the step goal included: `Tx STEP_GOAL: 8000` comes
     * back as `raw=8000`. It looked pinned at 10000 for a while, but that was
     * this app losing the frame — syncUserProfileAndGoals() used to kick the
     * write queue by hand on top of a flush already in progress, and 0x0207 was
     * the frame that got overwritten. See flushQueue().
     */
    public void readGoals() {
        if (!isSessionReady()) return;
        for (int key : new int[] { 0x07, 0x39, 0x3A, 0x3B })
            enqueueLogicalFrame(createMessage((byte) 0x02, (byte) key,
                    (byte) BleKeyFlag.READ.getValue(), new byte[0]));
        flushQueue();
    }

    // ===== Watch-side settings the app did not expose =====

    /**
     * Heart-rate alarm (HR_WARNING_SET 0x023F) — `BleHrWarningSettings` is four
     * bytes: high switch, high bpm, low switch, low bpm.
     */
    public void sendHrWarning(boolean highOn, int highBpm, boolean lowOn, int lowBpm) {
        if (!isSessionReady()) return;
        byte[] payload = {
                (byte) (highOn ? 1 : 0), (byte) clamp(highBpm, 80, 220),
                (byte) (lowOn ? 1 : 0), (byte) clamp(lowBpm, 30, 100)
        };
        log("Tx HR_WARNING high=" + highOn + "/" + highBpm + " low=" + lowOn + "/" + lowBpm);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x3F, (byte) 0x00, payload));
        flushQueue();
    }

    /** How many times the watch buzzes for a notification: 0 = off, 1-3 times. */
    public void sendVibration(int times) {
        if (!isSessionReady()) return;
        log("Tx VIBRATION=" + times);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x0B, (byte) 0x00,
                new byte[] { (byte) clamp(times, 0, 3) }));
        flushQueue();
    }

    /** Unit system shown on the watch: 0 = metric, 1 = imperial. */
    public void sendUnits(int unit) {
        if (!isSessionReady()) return;
        log("Tx UNIT_SET=" + unit);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x11, (byte) 0x00,
                new byte[] { (byte) clamp(unit, 0, 1) }));
        flushQueue();
    }

    /**
     * Watch password (SET_WATCH_PASSWORD 0x0235). `BleSettingWatchPassword` is
     * one enabled byte followed by a fixed four-character string, which is what
     * the watch returns as `00 FF FF FF FF` when no password is set.
     */
    public void sendWatchPassword(boolean enabled, String password) {
        if (!isSessionReady()) return;
        byte[] payload = new byte[5];
        payload[0] = (byte) (enabled ? 1 : 0);
        // 0xFF is what the watch itself stores for an unset digit.
        Arrays.fill(payload, 1, 5, (byte) 0xFF);
        if (password != null) {
            byte[] digits = password.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(digits, 0, payload, 1, Math.min(digits.length, 4));
        }
        log("Tx SET_WATCH_PASSWORD enabled=" + enabled);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x35, (byte) 0x00, payload));
        flushQueue();
    }

    /**
     * Game-time reminder (GAME_TIME_REMINDER 0x0251) — `BleGameTimeReminder` is
     * an enabled byte plus the minutes after which the watch nags.
     */
    public void sendGameTimeReminder(boolean enabled, int minutes) {
        if (!isSessionReady()) return;
        byte[] payload = { (byte) (enabled ? 1 : 0), (byte) clamp(minutes, 1, 240) };
        log("Tx GAME_TIME_REMINDER enabled=" + enabled + " after " + minutes + "min");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x51, (byte) 0x00, payload));
        flushQueue();
    }

    /**
     * Power the watch off (SHUTDOWN 0x0222). One byte; there is no undo from
     * the phone, so the caller is expected to have confirmed with the user.
     */
    public void sendShutdown() {
        if (!isSessionReady()) return;
        log("Tx SHUTDOWN");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x22, (byte) 0x00, new byte[] { 1 }));
        flushQueue();
    }

    /**
     * SOS (SOS_SET 0x024E). `BleSOSSettings` is an enabled byte, the phone
     * number's length, then the number itself in a fixed 18-byte field. The
     * watch pads unused bytes with 0xFF, which is how an unconfigured SOS reads
     * back as `00 00` followed by eighteen 0xFF.
     */
    public void sendSos(boolean enabled, String phone) {
        if (!isSessionReady()) return;
        String number = (phone == null) ? "" : phone.trim();
        byte[] digits = number.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int len = Math.min(digits.length, SOS_PHONE_LENGTH);

        byte[] payload = new byte[2 + SOS_PHONE_LENGTH];
        payload[0] = (byte) (enabled ? 1 : 0);
        payload[1] = (byte) len;
        Arrays.fill(payload, 2, payload.length, (byte) 0xFF);
        System.arraycopy(digits, 0, payload, 2, len);

        log("Tx SOS_SET enabled=" + enabled + " len=" + len);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x4E, (byte) 0x00, payload));
        flushQueue();
    }

    private static final int SOS_PHONE_LENGTH = 18;

    public void readWatchSettings() {
        if (!isSessionReady()) return;
        for (int key : new int[] { 0x0B, 0x11, 0x1B, 0x26, 0x35, 0x3F, 0x4E, 0x51 })
            enqueueLogicalFrame(createMessage((byte) 0x02, (byte) key,
                    (byte) BleKeyFlag.READ.getValue(), new byte[0]));
        flushQueue();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The rows render from prefs; this is what tells them the watch spoke. */
    private void notifyWatchSettings() {
        MonitoringListener ml = monitoringListener;
        if (ml != null)
            handler.post(ml::onMonitoringChanged);
    }

    /**
     * READ USER_PROFILE (0x0206). The watch echoes the same 11-byte layout, so
     * this is what confirms the little-endian floats were understood rather
     * than merely acknowledged.
     */
    public void readUserProfile() {
        if (!isSessionReady()) return;
        log("Reading user profile (0x0206)...");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x06, (byte) 0x10, null));
        flushQueue();
    }

    /**
     * Push profile and goal after the user edits them, without waiting for the
     * next connection.
     */
    public void syncUserProfileAndGoals() {
        if (!isSessionReady()) return;
        sendUserProfile();
        sendGoals();
        flushQueue();
        handler.postDelayed(this::readUserProfile, 600);
        // Read the goals back: the watch is the authority, so if it clamps or
        // ignores one of them the phone's fields correct themselves.
        handler.postDelayed(this::readGoals, 1200);
    }

    /**
     * SET TIME (BleKey 0x0201, UPDATE).
     *
     * IMPORTANT: the device clock is NOT a 4-byte Unix timestamp (as previously
     * documented). The verified capture shows a 6-byte calendar structure:
     *     [year-2000, month(1-12), day, hour(0-23), minute, second]
     * e.g. 1A 02 05 08 31 0B == 2026-02-05 08:49:11.
     */
    public void sendTime() {
        Calendar c = Calendar.getInstance();
        byte[] payload = new byte[] {
                (byte) (c.get(Calendar.YEAR) - 2000),
                (byte) (c.get(Calendar.MONTH) + 1), // Calendar.MONTH is 0-based
                (byte) c.get(Calendar.DAY_OF_MONTH),
                (byte) c.get(Calendar.HOUR_OF_DAY),
                (byte) c.get(Calendar.MINUTE),
                (byte) c.get(Calendar.SECOND)
        };
        byte[] msg = createMessage((byte) 0x02, (byte) 0x01, (byte) 0x00, payload);
        log("Tx TIME: " + bytesToHex(payload));
        enqueueLogicalFrame(msg);
    }

    /**
     * SET TIME_ZONE (BleKey 0x0202, UPDATE) — single signed byte.
     * Verified against the original app's BleTimeZone entity: the value is the
     * UTC offset expressed in 15-minute units, i.e. offsetMillis/1000/60/15.
     * (e.g. UTC+2 -> 8, UTC-6 -> -24.)
     */
    public void sendTimeZone() {
        int offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis());
        int quarters = offsetMs / 1000 / 60 / 15;
        byte[] payload = new byte[] { (byte) quarters };
        byte[] msg = createMessage((byte) 0x02, (byte) 0x02, (byte) 0x00, payload);
        log("Tx TIME_ZONE: " + bytesToHex(payload) + " (" + quarters + " x15min)");
        enqueueLogicalFrame(msg);
    }

    /**
     * SET HOUR_SYSTEM (BleKey 0x020E, UPDATE) — 0x00 = 12h, 0x01 = 24h.
     * Defaults to the phone's locale setting.
     */
    public void sendHourSystem() {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(context);
        byte[] payload = new byte[] { (byte) (is24 ? 0x01 : 0x00) };
        byte[] msg = createMessage((byte) 0x02, (byte) 0x0E, (byte) 0x00, payload);
        log("Tx HOUR_SYSTEM: " + bytesToHex(payload));
        enqueueLogicalFrame(msg);
    }

    // ========== Weather Push ==========
    //
    // Byte layout verified against the original app's decompiled entities
    // (com.szabh.smable3.entity.BleWeather / BleWeatherRealtime / BleWeatherForecast):
    //
    //   BleWeather (10 bytes): currentTemp(i8) maxTemp(i8) minTemp(i8) code(i8)
    //                          windSpeed(i8) humidity(i8) visibility(i8)
    //                          uvIndex(i8) precipitation(i16 LITTLE-ENDIAN)
    //   WEATHER_REALTIME (0x0404, UPDATE): BleTime(6) + BleWeather          = 16 B
    //   WEATHER_FORECAST (0x0405, UPDATE): BleTime(6) + 3 x BleWeather      = 36 B
    //
    // Weather codes: 0=other 1=sunny 2=cloudy 3=overcast 4=rainy 5=thunder
    //   6=thundershower 7=high-wind 8=snowy 9=foggy 10=sandstorm 11=haze.

    public static final int WEATHER_OTHER = 0, WEATHER_SUNNY = 1, WEATHER_CLOUDY = 2,
            WEATHER_OVERCAST = 3, WEATHER_RAINY = 4, WEATHER_THUNDER = 5,
            WEATHER_THUNDERSHOWER = 6, WEATHER_HIGH_WIND = 7, WEATHER_SNOWY = 8,
            WEATHER_FOGGY = 9, WEATHER_SANDSTORM = 10, WEATHER_HAZE = 11,
            WEATHER_WIND = 12, WEATHER_DRIZZLE = 13, WEATHER_HEAVY_RAIN = 14,
            WEATHER_LIGHTNING = 15, WEATHER_LIGHT_SNOW = 16, WEATHER_HEAVY_SNOW = 17,
            WEATHER_SLEET = 18, WEATHER_TORNADO = 19, WEATHER_SNOWSTORM = 20;

    /** One BleWeather record. All temps in °C. */
    public static class WeatherDay {
        public int conditionCode; // 0..20 (see WEATHER_* constants)
        public int tempCurrent;
        public int tempHigh;
        public int tempLow;
        public int windSpeed;     // km/h
        public int humidity;      // %
        public int visibility;    // km
        public int uvIndex;
        public int precipitation; // mm of rain (for watch wire protocol)
        public int popProbability; // % probability of precipitation (for app UI)
        public int sunriseH, sunriseM, sunriseS;
        public int sunsetH, sunsetM, sunsetS;
        public int aqi;

        public WeatherDay(int conditionCode, int tempCurrent, int tempHigh, int tempLow) {
            this(conditionCode, tempCurrent, tempHigh, tempLow, 0, 0, 0, 0, 0, 0, 6, 0, 0, 19, 0, 0, 0);
        }

        public WeatherDay(int conditionCode, int tempCurrent, int tempHigh, int tempLow,
                int windSpeed, int humidity, int visibility, int uvIndex, int precipitation) {
            this(conditionCode, tempCurrent, tempHigh, tempLow, windSpeed, humidity, visibility, uvIndex, precipitation, precipitation, 6, 0, 0, 19, 0, 0, 0);
        }

        public WeatherDay(int conditionCode, int tempCurrent, int tempHigh, int tempLow,
                int windSpeed, int humidity, int visibility, int uvIndex, int precipitation, int popProbability,
                int sunriseH, int sunriseM, int sunriseS, int sunsetH, int sunsetM, int sunsetS, int aqi) {
            this.conditionCode = conditionCode;
            this.tempCurrent = tempCurrent;
            this.tempHigh = tempHigh;
            this.tempLow = tempLow;
            this.windSpeed = windSpeed;
            this.humidity = humidity;
            this.visibility = visibility;
            this.uvIndex = uvIndex;
            this.precipitation = precipitation;
            this.popProbability = popProbability;
            this.sunriseH = sunriseH;
            this.sunriseM = sunriseM;
            this.sunriseS = sunriseS;
            this.sunsetH = sunsetH;
            this.sunsetM = sunsetM;
            this.sunsetS = sunsetS;
            this.aqi = aqi;
        }
    }

    /** Appends a 10-byte BleWeather record (V1). */
    private void putBleWeather(ByteBuffer buf, WeatherDay d, boolean isRealtime) {
        buf.put((byte) (isRealtime ? d.tempCurrent : 0));
        buf.put((byte) d.tempHigh);
        buf.put((byte) d.tempLow);
        buf.put((byte) (d.conditionCode & 0xFF));
        buf.put((byte) d.windSpeed);
        buf.put((byte) d.humidity);
        buf.put((byte) d.visibility);
        buf.put((byte) d.uvIndex);
        buf.put((byte) (d.precipitation & 0xFF));        // LE low byte
        buf.put((byte) ((d.precipitation >> 8) & 0xFF)); // LE high byte
    }

    /** Appends a 20-byte BleWeather2 record (V2). */
    private void putBleWeather2(ByteBuffer buf, WeatherDay d, boolean isRealtime) {
        buf.put((byte) (isRealtime ? d.tempCurrent : 0));
        buf.put((byte) d.tempHigh);
        buf.put((byte) d.tempLow);
        // mWeatherCode (int16 Little-Endian)
        buf.put((byte) (d.conditionCode & 0xFF));
        buf.put((byte) ((d.conditionCode >> 8) & 0xFF));
        buf.put((byte) d.windSpeed);
        buf.put((byte) d.humidity);
        buf.put((byte) d.visibility);
        buf.put((byte) d.uvIndex);
        // mPrecipitation (int16 Little-Endian)
        buf.put((byte) (d.precipitation & 0xFF));
        buf.put((byte) ((d.precipitation >> 8) & 0xFF));
        buf.put((byte) d.sunriseH);
        buf.put((byte) d.sunriseM);
        buf.put((byte) d.sunriseS);
        buf.put((byte) d.sunsetH);
        buf.put((byte) d.sunsetM);
        buf.put((byte) d.sunsetS);
        // mAQI / Altitude (int16 Little-Endian)
        buf.put((byte) (d.aqi & 0xFF));
        buf.put((byte) ((d.aqi >> 8) & 0xFF));
        // Padding
        buf.put((byte) 0x00);
    }

    private void putCityName66(ByteBuffer buf, String city) {
        byte[] raw = (city != null) ? city.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int len = Math.min(raw.length, 66);
        buf.put(raw, 0, len);
        for (int i = len; i < 66; i++) {
            buf.put((byte) 0);
        }
    }

    /**
     * Push current weather (today) + 7-day forecast to the watch using both V2 (0x040C/0x040D)
     * and V1 (0x0404/0x0405) protocols.
     *
     * @param days today first, then the next days (forecast uses up to 7)
     * @param city City name (encoded in V2 packets)
     */
    public void sendWeather(List<WeatherDay> days, String city) {
        if (!isSessionReady() || days == null || days.isEmpty()) {
            log("sendWeather: not ready or no data");
            return;
        }
        Calendar now = Calendar.getInstance();

        // --- 1. WEATHER_REALTIME2 (0x040C, UPDATE 0x00): BleTime(6) + City(66) + BleWeather2(20) = 92 B ---
        ByteBuffer rt2 = ByteBuffer.allocate(92);
        rt2.order(ByteOrder.BIG_ENDIAN);
        putBleTime(rt2, now);
        putCityName66(rt2, city);
        putBleWeather2(rt2, days.get(0), true);
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x0C, (byte) 0x00, rt2.array()));
        log("Tx WEATHER_REALTIME2 city='" + city + "' cur=" + days.get(0).tempCurrent + "°C hi=" + days.get(0).tempHigh + "°C lo=" + days.get(0).tempLow + "°C");

        // --- 2. WEATHER_FORECAST2 (0x040D, UPDATE 0x00): BleTime(6) + City(66) + 7 x BleWeather2(20) = 212 B ---
        ByteBuffer fc2 = ByteBuffer.allocate(212);
        fc2.order(ByteOrder.BIG_ENDIAN);
        putBleTime(fc2, now);
        putCityName66(fc2, city);
        for (int i = 0; i < 7; i++) {
            WeatherDay d = days.get(Math.min(i, days.size() - 1));
            putBleWeather2(fc2, d, i == 0);
        }
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x0D, (byte) 0x00, fc2.array()));
        log("Tx WEATHER_FORECAST2 (" + Math.min(days.size(), 7) + " days)");

        // --- 3. V1 fallback: WEATHER_REALTIME (0x0404, 16 B) & WEATHER_FORECAST (0x0405, 36 B) ---
        ByteBuffer rt = ByteBuffer.allocate(16);
        rt.order(ByteOrder.BIG_ENDIAN);
        putBleTime(rt, now);
        putBleWeather(rt, days.get(0), true);
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x04, (byte) 0x00, rt.array()));

        ByteBuffer fc = ByteBuffer.allocate(36);
        fc.order(ByteOrder.BIG_ENDIAN);
        putBleTime(fc, now);
        for (int i = 0; i < 3; i++) {
            WeatherDay d = days.get(Math.min(i, days.size() - 1));
            putBleWeather(fc, d, i == 0);
        }
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x05, (byte) 0x00, fc.array()));

        flushQueue();
    }

    /** Convenience hook for the UI: fetch current weather and push it to the watch. */
    public void syncWeather() {
        WeatherSync.syncIfPossible(context, this);
    }

    /**
     * Writes a 6-byte BleTime [year-2000, month, day, hour, min, sec].
     * Verified against the original app's decompiled BleTime entity
     * (ITEM_LENGTH = 6, no weekday byte).
     */
    private void putBleTime(ByteBuffer buf, Calendar c) {
        buf.put((byte) (c.get(Calendar.YEAR) - 2000));
        buf.put((byte) (c.get(Calendar.MONTH) + 1));
        buf.put((byte) c.get(Calendar.DAY_OF_MONTH));
        buf.put((byte) c.get(Calendar.HOUR_OF_DAY));
        buf.put((byte) c.get(Calendar.MINUTE));
        buf.put((byte) c.get(Calendar.SECOND));
    }

    // ========== Device Control & Settings ==========

    /** Optional callback for remote-camera events from the watch. */
    public interface CameraListener {
        void onShutter();
    }

    private CameraListener cameraListener;

    public void setCameraListener(CameraListener l) {
        this.cameraListener = l;
    }

    // CAMERA (0x0601) control states observed from the original app.
    public static final int CAMERA_ENTER = 0x01; // phone entered camera screen
    public static final int CAMERA_EXIT = 0x00; // phone left camera screen

    /**
     * Tell the watch the phone's camera screen is open/closed (CAMERA 0x0601).
     * While open, pressing the watch's shutter makes the watch send a 0x0601
     * push which we surface via {@link CameraListener#onShutter()}.
     */
    public void sendCamera(int state) {
        if (!isSessionReady())
            return;
        byte[] msg = createMessage((byte) 0x06, (byte) 0x01, (byte) 0x00, new byte[] { (byte) state });
        log("Tx CAMERA state=" + state);
        enqueueLogicalFrame(msg);
        flushQueue();
    }

    // ===== Incoming calls (INCOMING_CALL 0x0603) =====

    /** The watch is told a call is in progress. */
    public static final int CALL_ACTIVE = 0;
    /** The watch is told no call is in progress. */
    public static final int CALL_IDLE = 1;

    /** What the watch asked the phone to do with the ringing call. */
    public static final int CALL_ANSWER = 0;
    public static final int CALL_HANG_UP = 1;

    /** Categories the watch uses for notifications; a call gets its own. */
    public static final int NOTIF_CATEGORY_CALL = 1;

    /**
     * SET INCOMING_CALL (0x0603, UPDATE) — one byte.
     *
     * This is a call-in-progress flag, not the "a call is arriving" push: the
     * original app sends {@link #CALL_ACTIVE} when the phone goes off-hook and
     * {@link #CALL_IDLE} when it returns to idle, and never sends anything here
     * while the phone is merely ringing. The ringing screen on the watch comes
     * from a NOTIFICATION with category 1 instead.
     */
    public void sendCallState(int state) {
        if (!isSessionReady())
            return;
        log("Tx INCOMING_CALL state=" + state);
        enqueueLogicalFrame(createMessage((byte) 0x06, (byte) 0x03, (byte) 0x00,
                new byte[] { (byte) state }));
        flushQueue();
    }

    /**
     * Clear the call screen on the watch (NOTIFICATION 0x0401, DELETE).
     *
     * The body is a full-size notification with category 1 and every other
     * field empty — matching the original's handleEnd(), which builds
     * {@code BleNotification(1, 0L, null, null, null)} and sends it with DELETE.
     * Without this the watch keeps showing the caller after the call ends.
     */
    public void dismissCallNotification() {
        if (!isSessionReady())
            return;
        ByteBuffer buf = ByteBuffer.allocate(71).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) NOTIF_CATEGORY_CALL);
        buf.put(new byte[6]);           // mTime, zeroed as in the original
        buf.put(new byte[32]);          // mPackage
        buf.put(new byte[32]);          // mTitle
        log("Tx NOTIFICATION delete (call)");
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x01,
                (byte) BleKeyFlag.DELETE.getValue(), buf.array()));
        flushQueue();
    }

    private WatchCallController callController;

    /**
     * The call bridge, created on first use.
     *
     * Like the music bridge, BleManager owns it because the watch -> phone half
     * lands here on the GATT callback.
     */
    public synchronized WatchCallController getCallController() {
        if (callController == null)
            callController = new WatchCallController(context, this);
        return callController;
    }

    /** Notified when the watch answers or rejects a ringing call. */
    public interface CallListener {
        void onCallAction(int action);
    }

    private CallListener callListener;

    public void setCallListener(CallListener l) {
        this.callListener = l;
    }

    /** Pref prefix for a monitoring key, shared with the Device tab. */
    public static String monitoringPref(int key) {
        switch (key) {
            case 0x16: return "hr_monitoring";
            case 0x1B: return "temp_monitoring";
            case 0x25: return "spo2_monitoring";
            case 0x40: return "sleep_monitoring";
            default:   return "monitoring_" + key;
        }
    }

    private void storeMonitoring(int key, boolean on, int sh, int sm, int eh, int em, int interval) {
        String p = monitoringPref(key);
        SharedPreferences.Editor e = prefs.edit()
                .putBoolean(p + "_on", on)
                .putInt(p + "_sh", sh).putInt(p + "_sm", sm)
                .putInt(p + "_eh", eh).putInt(p + "_em", em);
        if (interval > 0)
            e.putInt(p + "_interval", interval);
        e.apply();
    }

    /** Fired when the watch reports a monitoring window. */
    public interface MonitoringListener {
        void onMonitoringChanged();
    }

    private MonitoringListener monitoringListener;

    public void setMonitoringListener(MonitoringListener l) {
        this.monitoringListener = l;
    }

    /** Ask the watch for all three monitoring windows. */
    public void readAllMonitoring() {
        readMonitoring(0x16);
        readMonitoring(0x25);
        readMonitoring(0x40);
    }

    // ===== Find the watch (FIND_WATCH 0x0234) =====

    /**
     * Make the watch ring and vibrate, or stop it (FIND_WATCH 0x0234, UPDATE).
     *
     * One byte: 1 starts, 0 stops. The mirror image of FIND_PHONE (0x0213),
     * which the watch already uses to ring this phone.
     *
     * The watch also stops on its own once the user acknowledges it there, and
     * it sends nothing back when it does — so the phone cannot know whether it
     * is still ringing, only ask it to start or stop.
     */
    public void sendFindWatch(boolean start) {
        if (!isSessionReady())
            return;
        log("Tx FIND_WATCH start=" + start);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x34, (byte) 0x00,
                new byte[] { (byte) (start ? 1 : 0) }));
        flushQueue();
    }

    // ===== Health monitoring windows =====

    /**
     * A daily window plus a sampling interval, the shape three of the
     * monitoring keys share: {@code [enabled, startH, startM, endH, endM]}
     * (a BleTimeRange) followed by the interval in minutes.
     *
     * HR_MONITORING clamps the interval to at least 1 in the original's
     * encode(); BLOOD_OXYGEN_SET does not, so a 0 there is passed through as
     * the watch's own idea of "off".
     */
    private void sendMonitoringWindow(int key, String name, boolean enabled,
                                      int startHour, int startMinute,
                                      int endHour, int endMinute, int intervalMinutes) {
        if (!isSessionReady())
            return;
        byte[] payload = {
                (byte) (enabled ? 1 : 0),
                (byte) startHour, (byte) startMinute,
                (byte) endHour, (byte) endMinute,
                (byte) intervalMinutes
        };
        log("Tx " + name + " enabled=" + enabled
                + " " + startHour + ":" + startMinute + "-" + endHour + ":" + endMinute
                + " every " + intervalMinutes + "min");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) key, (byte) 0x00, payload));
        flushQueue();
    }

    /** Automatic heart-rate sampling (HR_MONITORING 0x0216). */
    /**
     * Automatic temperature measurement (TEMPERATURE_DETECTING 0x021B).
     * `BleTemperatureDetecting` is a BleTimeRange plus the interval — the same
     * six bytes as the HR and SpO2 windows.
     */
    public void sendTemperatureMonitoring(boolean enabled, int startHour, int startMinute,
                                          int endHour, int endMinute, int intervalMinutes) {
        sendMonitoringWindow(0x1B, "TEMPERATURE_DETECTING", enabled,
                startHour, startMinute, endHour, endMinute, intervalMinutes);
    }

    public void sendHeartRateMonitoring(boolean enabled, int startHour, int startMinute,
                                        int endHour, int endMinute, int intervalMinutes) {
        sendMonitoringWindow(0x16, "HR_MONITORING", enabled, startHour, startMinute,
                endHour, endMinute, Math.max(intervalMinutes, 1));
    }

    /** Automatic SpO2 sampling (BLOOD_OXYGEN_SET 0x0225). */
    public void sendBloodOxygenMonitoring(boolean enabled, int startHour, int startMinute,
                                          int endHour, int endMinute, int intervalMinutes) {
        sendMonitoringWindow(0x25, "BLOOD_OXYGEN_SET", enabled, startHour, startMinute,
                endHour, endMinute, intervalMinutes);
    }

    /**
     * Sleep tracking window (SLEEP_MONITORING 0x0240).
     *
     * Five bytes, not six: this key has no interval.
     */
    public void sendSleepMonitoring(boolean enabled, int startHour, int startMinute,
                                    int endHour, int endMinute) {
        if (!isSessionReady())
            return;
        byte[] payload = {
                (byte) (enabled ? 1 : 0),
                (byte) startHour, (byte) startMinute,
                (byte) endHour, (byte) endMinute
        };
        log("Tx SLEEP_MONITORING enabled=" + enabled
                + " " + startHour + ":" + startMinute + "-" + endHour + ":" + endMinute);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x40, (byte) 0x00, payload));
        flushQueue();
    }

    /** Ask the watch for a monitoring key's current setting. */
    public void readMonitoring(int key) {
        if (!isSessionReady())
            return;
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) key,
                (byte) BleKeyFlag.READ.getValue(), new byte[0]));
        flushQueue();
    }

    // ===== Health Reminders & Girl Care =====

    /** Pref prefix the reminder rows in DeviceFragment read from. */
    private static String reminderPref(int key) {
        switch (key) {
            case 0x09: return "set_sedentary";
            case 0x26: return "wash";
            default:   return "drink_water";
        }
    }

    /**
     * Adopt what the watch reports for a reminder key, in the same shape the
     * phone writes it: [enabled<<7 | weekday mask, startH, startM, endH, endM,
     * interval].
     */
    private void storeReminder(int key, byte[] body) {
        String p = reminderPref(key);
        int b0 = body[0] & 0xFF;
        boolean on = (b0 & 0x80) != 0;
        int repeat = b0 & 0x7F;
        int interval = body[5] & 0xFF;
        log("Rx reminder [" + p + "] enabled=" + on + " repeat=0x" + Integer.toHexString(repeat)
                + " " + (body[1] & 0xFF) + ":" + (body[2] & 0xFF)
                + "-" + (body[3] & 0xFF) + ":" + (body[4] & 0xFF)
                + " every " + interval + "min");
        SharedPreferences.Editor e = prefs.edit()
                .putBoolean(p + "_on", on)
                .putInt(p + "_sh", body[1] & 0xFF).putInt(p + "_sm", body[2] & 0xFF)
                .putInt(p + "_eh", body[3] & 0xFF).putInt(p + "_em", body[4] & 0xFF);
        if (repeat != 0)
            e.putInt(p + "_repeat", repeat);
        if (interval > 0)
            e.putInt(p + "_interval", interval);
        if (key == 0x09)
            e.putBoolean("set_sedentary", on); // legacy key the row still falls back to
        e.apply();
    }

    /** Ask the watch for the reminder settings it is actually running. */
    public void readReminders() {
        if (!isSessionReady())
            return;
        log("Tx reminder READ 0x09/0x21/0x26/0x1A");
        for (int key : new int[] { 0x09, 0x21, 0x26, 0x1A }) {
            enqueueLogicalFrame(createMessage((byte) 0x02, (byte) key,
                    (byte) BleKeyFlag.READ.getValue(), new byte[0]));
        }
        flushQueue();
    }

    /**
     * Sedentary Reminder (SEDENTARINESS 0x0209) — 6 bytes.
     * Byte 0: [bit 7: enabled (1), bits 0-6: repeat mask (0x7F = everyday)]
     * Byte 1: startHour
     * Byte 2: startMinute
     * Byte 3: endHour
     * Byte 4: endMinute
     * Byte 5: interval (minutes)
     */
    public void sendSedentariness(boolean enabled, int repeat, int startHour, int startMinute,
                                 int endHour, int endMinute, int intervalMinutes) {
        if (!isSessionReady()) return;
        byte b0 = (byte) ((enabled ? 0x80 : 0x00) | (repeat & 0x7F));
        byte[] payload = {
                b0,
                (byte) startHour, (byte) startMinute,
                (byte) endHour, (byte) endMinute,
                (byte) Math.max(intervalMinutes, 15)
        };
        log("Tx SEDENTARINESS enabled=" + enabled + " repeat=0x" + Integer.toHexString(repeat)
                + " " + startHour + ":" + startMinute + "-" + endHour + ":" + endMinute
                + " every " + intervalMinutes + "min");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x09, (byte) 0x00, payload));
        flushQueue();
    }

    /**
     * Drink Water Reminder (DRINK_WATER 0x0221) — 6 bytes.
     * Byte 0: [bit 7: enabled (1), bits 0-6: repeat mask (0x7F = everyday)]
     * Byte 1: startHour
     * Byte 2: startMinute
     * Byte 3: endHour
     * Byte 4: endMinute
     * Byte 5: interval (minutes)
     */
    public void sendDrinkWater(boolean enabled, int repeat, int startHour, int startMinute,
                               int endHour, int endMinute, int intervalMinutes) {
        if (!isSessionReady()) return;
        byte b0 = (byte) ((enabled ? 0x80 : 0x00) | (repeat & 0x7F));
        byte[] payload = {
                b0,
                (byte) startHour, (byte) startMinute,
                (byte) endHour, (byte) endMinute,
                (byte) Math.max(intervalMinutes, 15)
        };
        log("Tx DRINK_WATER enabled=" + enabled + " repeat=0x" + Integer.toHexString(repeat)
                + " " + startHour + ":" + startMinute + "-" + endHour + ":" + endMinute
                + " every " + intervalMinutes + "min");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x21, (byte) 0x00, payload));
        flushQueue();
    }

    /**
     * Hand Wash Reminder (WASH_SET 0x0226) — 6 bytes, the `BleWashSettings`
     * shape: [enabled<<7 | weekday mask, startH, startM, endH, endM, interval].
     * 0x0228 is IBEACON_SET; an earlier build wrote this reminder there, which
     * is why it never reached the watch.
     */
    public void sendWash(boolean enabled, int repeat, int startHour, int startMinute,
                         int endHour, int endMinute, int intervalMinutes) {
        if (!isSessionReady()) return;
        byte b0 = (byte) ((enabled ? 0x80 : 0x00) | (repeat & 0x7F));
        byte[] payload = {
                b0,
                (byte) startHour, (byte) startMinute,
                (byte) endHour, (byte) endMinute,
                (byte) Math.max(intervalMinutes, 15)
        };
        log("Tx WASH enabled=" + enabled + " repeat=0x" + Integer.toHexString(repeat)
                + " " + startHour + ":" + startMinute + "-" + endHour + ":" + endMinute
                + " every " + intervalMinutes + "min");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x26, (byte) 0x00, payload));
        flushQueue();
    }

    /**
     * Girl Care / Period Tracker (GIRL_CARE 0x021A) — 10 bytes.
     * Byte 0: [bit 7: reminderEnabled, bits 1-6: 0, bit 0: enabled]
     * Byte 1: reminderHour
     * Byte 2: reminderMinute
     * Byte 3: menstruationReminderAdvance (days in advance, 1-3)
     * Byte 4: ovulationReminderAdvance (days in advance, 1-3)
     * Byte 5: latestYear - 2000
     * Byte 6: latestMonth (1-12)
     * Byte 7: latestDay (1-31)
     * Byte 8: menstruationDuration (days, default 5)
     * Byte 9: menstruationPeriod (cycle days, default 28)
     */
    public void sendGirlCare(boolean enabled, boolean reminderEnabled, int reminderHour, int reminderMinute,
                             int periodAdvance, int ovulationAdvance, int lastYear, int lastMonth, int lastDay,
                             int duration, int cycle) {
        if (!isSessionReady()) return;
        byte b0 = (byte) (((reminderEnabled ? 1 : 0) << 7) | (enabled ? 1 : 0));
        int yearOffset = (lastYear >= 2000) ? (lastYear - 2000) : lastYear;
        byte[] payload = {
                b0,
                (byte) reminderHour,
                (byte) reminderMinute,
                (byte) Math.max(1, Math.min(3, periodAdvance)),
                (byte) Math.max(1, Math.min(3, ovulationAdvance)),
                (byte) yearOffset,
                (byte) lastMonth,
                (byte) lastDay,
                (byte) Math.max(2, Math.min(15, duration)),
                (byte) Math.max(20, Math.min(45, cycle))
        };
        log("Tx GIRL_CARE enabled=" + enabled + " reminder=" + reminderEnabled
                + " time=" + reminderHour + ":" + reminderMinute + " last=" + lastYear + "-" + lastMonth + "-" + lastDay
                + " dur=" + duration + " cycle=" + cycle);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x1A, (byte) 0x00, payload));
        flushQueue();
    }

    /**
     * Push all saved reminders to the watch if enabled.
     */
    public void syncReminders() {
        if (!isSessionReady()) return;

        // Sedentary and drink water are settings the watch owns: it has its own
        // menu for them, so re-pushing the phone's copy on every connect (and
        // the app reconnects often) quietly undid whatever the user had set
        // there. Alarms and the monitoring windows already treat the watch as
        // the source of truth; these two now do the same — readReminders()
        // below pulls the live values in.
        //
        // The one thing worth pushing is an edit made in the app while the
        // phone was disconnected, which never reached the watch. The row marks
        // it pending; send that, then let the read confirm it.
        if (prefs.getBoolean("set_sedentary_pending", false)) {
            sendSedentariness(
                    prefs.getBoolean("set_sedentary_on", prefs.getBoolean("set_sedentary", false)),
                    prefs.getInt("set_sedentary_repeat", 0x7F),
                    prefs.getInt("set_sedentary_sh", 8), prefs.getInt("set_sedentary_sm", 0),
                    prefs.getInt("set_sedentary_eh", 22), prefs.getInt("set_sedentary_em", 0),
                    prefs.getInt("set_sedentary_interval", 60));
            prefs.edit().remove("set_sedentary_pending").apply();
        }
        if (prefs.getBoolean("drink_water_pending", false)) {
            sendDrinkWater(
                    prefs.getBoolean("drink_water_on", false),
                    prefs.getInt("drink_water_repeat", 0x7F),
                    prefs.getInt("drink_water_sh", 8), prefs.getInt("drink_water_sm", 0),
                    prefs.getInt("drink_water_eh", 22), prefs.getInt("drink_water_em", 0),
                    prefs.getInt("drink_water_interval", 60));
            prefs.edit().remove("drink_water_pending").apply();
        }

        if (prefs.getBoolean("wash_pending", false)) {
            sendWash(
                    prefs.getBoolean("wash_on", false),
                    prefs.getInt("wash_repeat", 0x7F),
                    prefs.getInt("wash_sh", 8), prefs.getInt("wash_sm", 0),
                    prefs.getInt("wash_eh", 18), prefs.getInt("wash_em", 0),
                    prefs.getInt("wash_interval", 60));
            prefs.edit().remove("wash_pending").apply();
        }

        // Girl Care
        PeriodTrackerManager.syncToWatch(context);

        // Read the reminders back: the watch ACKs 0x09/0x21 whether or not it
        // understood the frame, so the reply to a READ is the only proof that
        // what it stored is what was sent.
        readReminders();

        // Standby
        boolean allDay = prefs.getBoolean("standby_allday", true);
        int sh = prefs.getInt("standby_sh", 8);
        int sm = prefs.getInt("standby_sm", 0);
        int eh = prefs.getInt("standby_eh", 22);
        int em = prefs.getInt("standby_em", 0);
        sendStandby(prefs.getBoolean("standby_enabled", false), allDay, sh, sm, eh, em);
        // ...then ask the watch what it kept, the way the row renders it.
        readStandby();

        // World Clock: read current clocks from watch so phone reflects watch state
        readWorldClocks();

        // Stock Market: managed from app
        StockMarketManager.syncToWatch(context);

        // Goals: push what the user set, then read it back. The watch stores
        // all four, so the read is a confirmation rather than a guess — and it
        // corrects the phone if the watch clamped anything.
        sendGoals();
        readGoals();
        readWatchSettings();
    }

    // ===== Standby / Always-On Display =====
    //
    // Two keys carry this one feature and they are not interchangeable:
    //
    //   STANDBY_SET (0x0241) is the master switch — a single byte, 0 or 1.
    //   The original app pushes it from its own cache on every connect and
    //   never reads it back; a READ on the Kronos Thunder answers with an
    //   unrelated eight-byte block, so treat the key as write-only.
    //
    //   STANDBY_WATCH_FACE_SET (0x0254) is the BleStandbyWatchFaceSet entity,
    //   eight bytes: enable, all-day, then a BleTimeRange (its own enabled
    //   flag plus start/end) and one reserved byte. All-day and scheduled are
    //   the two halves of one choice — the original app writes each as the
    //   negation of the other. This firmware answers 0x0254 with an empty
    //   body, read or write, the shape it uses for keys it does not
    //   implement, so only the master switch actually lands on this watch.

    /** True once the watch has answered a 0x0254 READ with a real body. */
    private boolean standbyScheduleSupported;

    public boolean isStandbyScheduleSupported() {
        return standbyScheduleSupported;
    }

    public void sendStandby(boolean enabled, boolean allDay, int startH, int startM, int endH, int endM) {
        if (!isSessionReady()) return;

        log("Tx STANDBY enabled=" + enabled + " allDay=" + allDay
                + " " + startH + ":" + startM + "-" + endH + ":" + endM);

        // Master switch first: on a watch that only implements 0x0241 this is
        // the frame that does the work.
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x41,
                (byte) BleKeyFlag.UPDATE.getValue(), new byte[] { (byte) (enabled ? 1 : 0) }));

        byte[] wfPayload = {
                (byte) (enabled ? 1 : 0),
                (byte) (allDay ? 1 : 0),
                (byte) (allDay ? 0 : 1), // BleTimeRange.mEnabled — the scheduled half
                (byte) startH, (byte) startM,
                (byte) endH, (byte) endM,
                0 // reserved
        };
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x54,
                (byte) BleKeyFlag.UPDATE.getValue(), wfPayload));
        flushQueue();
    }

    /** Ask the watch for its stored standby schedule (0x0241 has no readable state). */
    public void readStandby() {
        if (!isSessionReady()) return;
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x54, (byte) BleKeyFlag.READ.getValue(), new byte[0]));
        flushQueue();
    }

    // ===== World Clock (WORLD_CLOCK 0x0407) =====

    public void sendWorldClock(int id, boolean isLocal, int timeZoneOffsetQuarterHours, String cityName) {
        if (!isSessionReady()) return;
        byte[] payload = new byte[68];
        payload[0] = (byte) (((isLocal ? 1 : 0) << 7) | (id & 0x7F));
        payload[1] = (byte) timeZoneOffsetQuarterHours;
        // payload[2..3] = 0 (reversed)
        if (cityName != null) {
            byte[] cityBytes = cityName.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            int len = Math.min(cityBytes.length, 62);
            System.arraycopy(cityBytes, 0, payload, 4, len);
        }
        log("Tx WORLD_CLOCK id=" + id + " local=" + isLocal + " offsetQ=" + timeZoneOffsetQuarterHours + " city=" + cityName);
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x07, (byte) BleKeyFlag.CREATE.getValue(), payload));
        flushQueue();
    }

    public void deleteWorldClock(int id) {
        if (!isSessionReady()) return;
        log("Tx WORLD_CLOCK delete id=" + id);
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x07, (byte) BleKeyFlag.DELETE.getValue(), new byte[] { (byte) id }));
        flushQueue();
    }

    public void resetWorldClocks() {
        if (!isSessionReady()) return;
        log("Tx WORLD_CLOCK reset all");
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x07, (byte) BleKeyFlag.DELETE.getValue(), new byte[] { (byte) 0xFF }));
        flushQueue();
    }

    // Reading the list back is paged: the watch answers a READ with a single
    // 68-byte item — the local clock — and hands over the rest one frame at a
    // time in response to READ_CONTINUE, ending with an empty body. Verified on
    // a Kronos: a read-all issued seconds after pushing "Hong Kong" came back as
    // one 77-byte frame carrying just "Berlin", the local clock. The old code
    // took that single frame for the whole list, concluded the watch held no
    // world clocks, and overwrote the phone's list with an empty one.
    private static final int WORLD_CLOCK_MAX_PAGES = WorldClockManager.MAX_CLOCKS + 2;
    private static final long WORLD_CLOCK_PAGE_TIMEOUT_MS = 3000;
    /** Items gathered so far, or null when no read is in flight. */
    private List<WorldClockManager.WorldClockItem> worldClockPages = null;
    private int worldClockPageCount = 0;
    private final Runnable worldClockPageTimeout = () -> {
        if (worldClockPages == null) return;
        log("World clock read: no answer after " + worldClockPageCount
                + " page(s) — keeping the phone's list");
        worldClockPages = null;
    };

    public void readWorldClocks() {
        if (!isSessionReady()) return;
        log("Tx WORLD_CLOCK read all");
        worldClockPages = new ArrayList<>();
        worldClockPageCount = 0;
        requestWorldClockPage(BleKeyFlag.READ.getValue());
    }

    private void requestWorldClockPage(int pageFlag) {
        handler.removeCallbacks(worldClockPageTimeout);
        handler.postDelayed(worldClockPageTimeout, WORLD_CLOCK_PAGE_TIMEOUT_MS);
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x07, (byte) pageFlag, new byte[] { (byte) 0xFF }));
        flushQueue();
    }

    /**
     * One page of the world clock list. Keep asking until the watch answers
     * with an empty body, then adopt what it reported.
     */
    private void onWorldClockPage(byte[] body) {
        handler.removeCallbacks(worldClockPageTimeout);
        if (worldClockPages == null) {
            log("Rx WORLD_CLOCK page outside a read — ignoring");
            return;
        }
        List<WorldClockManager.WorldClockItem> page = WorldClockManager.parseItems(body);
        if (!page.isEmpty()) {
            WorldClockManager.WorldClockItem first = page.get(0);
            if (!worldClockPages.isEmpty()) {
                WorldClockManager.WorldClockItem last = worldClockPages.get(worldClockPages.size() - 1);
                // A firmware that ignores READ_CONTINUE just repeats the first
                // item. Stop rather than spin, and leave the phone's list alone.
                if (last.id == first.id && last.isLocal == first.isLocal
                        && String.valueOf(last.cityName).equalsIgnoreCase(String.valueOf(first.cityName))) {
                    log("World clock read: page repeated — cursor is not advancing, keeping the phone's list");
                    worldClockPages = null;
                    return;
                }
            }
            for (WorldClockManager.WorldClockItem item : page) {
                log("Rx WORLD_CLOCK item id=" + item.id + " local=" + item.isLocal + " city=" + item.cityName);
            }
            worldClockPages.addAll(page);
            worldClockPageCount++;
            if (worldClockPageCount < WORLD_CLOCK_MAX_PAGES) {
                requestWorldClockPage(BleKeyFlag.READ_CONTINUE.getValue());
                return;
            }
            log("World clock read: page cap reached");
        }

        List<WorldClockManager.WorldClockItem> full = worldClockPages;
        worldClockPages = null;
        log("Rx WORLD_CLOCK list complete: " + full.size() + " item(s) over "
                + worldClockPageCount + " page(s)");
        WorldClockManager.applyWatchList(context, full);
    }

    // ===== Stock Market (STOCK 0x0408) =====

    public void sendStock(int id, int colorType, String stockCode, float sharePrice,
                          float netChangePoint, float netChangePercent, float marketCap) {
        if (!isSessionReady()) return;
        byte[] payload = new byte[84];
        payload[0] = (byte) id;
        payload[1] = (byte) (colorType & 1);

        int decPt = getDecimalPlaces(netChangePoint);
        int decPrice = getDecimalPlaces(sharePrice);
        int decPct = getDecimalPlaces(netChangePercent);

        payload[2] = (byte) ((decPt & 0x0F) | ((decPrice & 0x0F) << 4));
        payload[3] = (byte) ((decPct & 0x0F) << 4);

        if (stockCode != null) {
            byte[] codeBytes = stockCode.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            int len = Math.min(codeBytes.length, 62);
            System.arraycopy(codeBytes, 0, payload, 4, len);
        }

        // Float values in Little-Endian
        putFloatLe(payload, 68, sharePrice);
        putFloatLe(payload, 72, netChangePoint);
        putFloatLe(payload, 76, netChangePercent);
        putFloatLe(payload, 80, marketCap);

        log("Tx STOCK id=" + id + " code=" + stockCode + " price=" + sharePrice + " change=" + netChangePoint + " (" + netChangePercent + "%)");
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x08, (byte) BleKeyFlag.CREATE.getValue(), payload));
        flushQueue();
    }

    public void deleteStock(int id) {
        if (!isSessionReady()) return;
        log("Tx STOCK delete id=" + id);
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x08, (byte) BleKeyFlag.DELETE.getValue(), new byte[] { (byte) id }));
        flushQueue();
    }

    public void resetStocks() {
        if (!isSessionReady()) return;
        log("Tx STOCK reset all");
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x08, (byte) BleKeyFlag.DELETE.getValue(), new byte[] { (byte) 0xFF }));
        flushQueue();
    }

    public void readStocks() {
        if (!isSessionReady()) return;
        log("Tx STOCK read all");
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x08, (byte) BleKeyFlag.READ.getValue(), new byte[] { (byte) 0xFF }));
        flushQueue();
    }

    private static int getDecimalPlaces(float f) {
        String s = String.valueOf(f);
        int idx = s.indexOf('.');
        return (idx < 0) ? 0 : Math.min(4, s.length() - 1 - idx);
    }

    private static void putFloatLe(byte[] target, int offset, float value) {
        int bits = Float.floatToIntBits(value);
        target[offset] = (byte) (bits & 0xFF);
        target[offset + 1] = (byte) ((bits >> 8) & 0xFF);
        target[offset + 2] = (byte) ((bits >> 16) & 0xFF);
        target[offset + 3] = (byte) ((bits >> 24) & 0xFF);
    }

    // ===== Alarms (ALARM 0x0210) =====

    public static final String PREF_ALARMS = "alarms_json";

    /**
     * Watch-side alarm slots.
     *
     * The original app reads this ceiling from its per-product table, which is
     * not in the decompiled sources. Eight is what the stock UI offers on this
     * class of device; the watch rejects a CREATE past its own limit anyway, so
     * this only keeps the phone from queueing writes that cannot land.
     */
    public static final int MAX_ALARMS = 8;

    /** Ask the watch for every alarm it holds (READ, id 0xFF = all). */
    public void readAlarms() {
        if (!isSessionReady())
            return;
        log("Tx ALARM read all");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x10,
                (byte) BleKeyFlag.READ.getValue(), new byte[] { (byte) 0xFF }));
        flushQueue();
    }

    /**
     * Add a new alarm (CREATE).
     *
     * The watch does NOT allocate the id — verified on a Kronos Thunder, which
     * wrote a CREATE carrying id 0 straight into slot 0 and destroyed the alarm
     * already there. So the free slot is chosen here, from the list the watch
     * last reported.
     */
    public void createAlarm(BleAlarm alarm) {
        if (alarm != null)
            alarm.id = nextFreeAlarmId();
        sendAlarm(alarm, BleKeyFlag.CREATE.getValue(), "create");
    }

    /** Lowest slot the watch is not already using. */
    private int nextFreeAlarmId() {
        List<BleAlarm> known = getCachedAlarms();
        for (int id = 0; id < MAX_ALARMS; id++) {
            boolean taken = false;
            for (BleAlarm a : known) {
                if (a.id == id) {
                    taken = true;
                    break;
                }
            }
            if (!taken)
                return id;
        }
        return MAX_ALARMS - 1;   // full; the watch rejects it either way
    }

    /** Overwrite the alarm with this id (UPDATE). */
    public void updateAlarm(BleAlarm alarm) {
        sendAlarm(alarm, BleKeyFlag.UPDATE.getValue(), "update");
    }

    private void sendAlarm(BleAlarm alarm, int flag, String what) {
        if (!isSessionReady() || alarm == null)
            return;
        // A one-shot whose time has passed would be dropped by the watch, so
        // move it to the next occurrence before sending — the same fix-up the
        // original app applies on save.
        alarm.rollToFuture();
        log("Tx ALARM " + what + ": " + alarm);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x10, (byte) flag, alarm.encode()));
        flushQueue();
        scheduleAlarmReread();
    }

    /**
     * Re-read the list shortly after changing it.
     *
     * CREATE and UPDATE come back as a bodyless ACK and DELETE draws no reply
     * at all on this firmware, so a mutation carries no information about the
     * resulting list. Without this the screen keeps showing the pre-edit state
     * until the user leaves and comes back.
     */
    private void scheduleAlarmReread() {
        handler.removeCallbacks(alarmRereadRunnable);
        handler.postDelayed(alarmRereadRunnable, 700);
    }

    private final Runnable alarmRereadRunnable = this::readAlarms;

    /** Delete one alarm by id, or every alarm with {@code id == 0xFF}. */
    public void deleteAlarm(int id) {
        if (!isSessionReady())
            return;
        log("Tx ALARM delete id=" + id);
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x10,
                (byte) BleKeyFlag.DELETE.getValue(), new byte[] { (byte) id }));
        flushQueue();
        scheduleAlarmReread();
    }

    /** Replace the watch's whole list in one frame (RESET). */
    public void resetAlarms(List<BleAlarm> alarms) {
        if (!isSessionReady() || alarms == null)
            return;
        int n = Math.min(alarms.size(), MAX_ALARMS);
        byte[] body = new byte[n * BleAlarm.ITEM_LENGTH];
        for (int i = 0; i < n; i++) {
            BleAlarm a = alarms.get(i);
            a.rollToFuture();
            System.arraycopy(a.encode(), 0, body, i * BleAlarm.ITEM_LENGTH, BleAlarm.ITEM_LENGTH);
        }
        log("Tx ALARM reset: " + n + " alarm(s)");
        enqueueLogicalFrame(createMessage((byte) 0x02, (byte) 0x10,
                (byte) BleKeyFlag.RESET.getValue(), body));
        flushQueue();
        scheduleAlarmReread();
    }

    /** Alarms as last seen on the watch, cached so the list opens instantly. */
    public List<BleAlarm> getCachedAlarms() {
        return decodeAlarmCache(prefs.getString(PREF_ALARMS, ""));
    }

    private void storeAlarms(List<BleAlarm> alarms) {
        StringBuilder sb = new StringBuilder();
        for (BleAlarm a : alarms) {
            if (sb.length() > 0)
                sb.append(';');
            sb.append(bytesToHex(a.encode()).replace(" ", ""));
        }
        prefs.edit().putString(PREF_ALARMS, sb.toString()).apply();
    }

    /**
     * The cache stores each alarm as its own wire bytes in hex, so the cached
     * form cannot drift from what the watch actually holds.
     */
    private static List<BleAlarm> decodeAlarmCache(String stored) {
        List<BleAlarm> out = new ArrayList<>();
        if (stored == null || stored.isEmpty())
            return out;
        for (String part : stored.split(";")) {
            if (part.length() != BleAlarm.ITEM_LENGTH * 2)
                continue;
            byte[] item = new byte[BleAlarm.ITEM_LENGTH];
            for (int i = 0; i < item.length; i++)
                item[i] = (byte) Integer.parseInt(part.substring(i * 2, i * 2 + 2), 16);
            out.add(BleAlarm.decode(item, 0));
        }
        return out;
    }

    /** Notified when the watch reports its alarm list. */
    public interface AlarmListener {
        void onAlarmsChanged(List<BleAlarm> alarms);
    }

    private AlarmListener alarmListener;

    public void setAlarmListener(AlarmListener l) {
        this.alarmListener = l;
    }

    private void notifyAlarms(List<BleAlarm> alarms) {
        AlarmListener l = alarmListener;
        if (l != null)
            handler.post(() -> l.onAlarmsChanged(alarms));
    }

    // ===== Music control (MUSIC_CONTROL 0x0402) =====

    /**
     * Longest content string we will put in one MUSIC_CONTROL frame.
     *
     * The protocol itself sets no limit — the original app sends whatever the
     * media session reports. This cap is ours: a pathological track title would
     * otherwise fan out into dozens of MTU chunks and starve the write queue
     * behind it. 128 bytes comfortably fits any title a watch face can show.
     */
    private static final int MUSIC_CONTENT_MAX = 128;

    private WatchMusicController musicController;

    /**
     * The media bridge, created on first use.
     *
     * BleManager owns it because the watch -> phone half arrives here, on the
     * GATT callback; the caller ({@link BleForegroundService}) drives its
     * start/stop from the connection state.
     */
    public synchronized WatchMusicController getMusicController() {
        if (musicController == null)
            musicController = new WatchMusicController(context, this);
        return musicController;
    }

    /**
     * SET MUSIC_CONTROL (BleKey 0x0402, UPDATE) — one attribute of one entity.
     *
     * Payload is [entity u8][attr u8][content, UTF-8, NOT NUL-terminated]; the
     * frame length delimits the string. Verified against BleMusicControl.encode
     * in the decompiled SDK, whose getMLengthToWrite() is contentBytes + 2 —
     * i.e. no room for a terminator.
     */
    public void sendMusicControl(int entity, int attr, String content) {
        if (!isSessionReady())
            return;
        log("Tx MUSIC entity=" + entity + " attr=" + attr + " content='" + content + "'");
        enqueueLogicalFrame(createMessage((byte) 0x04, (byte) 0x02, (byte) 0x00,
                musicControlPayload(entity, attr, content)));
        // Metadata arrives as a burst of four or five attributes; flushQueue
        // keeps them serialised behind the write callback.
        flushQueue();
    }

    /** Build the MUSIC_CONTROL body. Package-private so it can be unit tested. */
    static byte[] musicControlPayload(int entity, int attr, String content) {
        byte[] text = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (text.length > MUSIC_CONTENT_MAX)
            text = truncateUtf8(text, MUSIC_CONTENT_MAX);

        byte[] payload = new byte[text.length + 2];
        payload[0] = (byte) entity;
        payload[1] = (byte) attr;
        System.arraycopy(text, 0, payload, 2, text.length);
        return payload;
    }

    /** Cut to at most {@code max} bytes without splitting a UTF-8 sequence. */
    static byte[] truncateUtf8(byte[] bytes, int max) {
        int end = max;
        // Continuation bytes are 10xxxxxx; walk back off them to the lead byte.
        while (end > 0 && (bytes[end] & 0xC0) == 0x80)
            end--;
        byte[] out = new byte[end];
        System.arraycopy(bytes, 0, out, 0, end);
        return out;
    }

    // ===== Find phone (watch rings the phone) =====
    private MediaPlayer findPhonePlayer;
    private static final String FIND_PHONE_CHANNEL = "find_phone";
    private static final int FIND_PHONE_NOTIF_ID = 4101;
    public static final String ACTION_FIND_PHONE_STOP = "com.example.dialsender.FIND_PHONE_STOP";

    /** Start ringing + vibrating the phone, with a notification + screen to stop. */
    public void startFindPhoneAlert() {
        handler.post(() -> {
            try {
                if (findPhonePlayer == null) {
                    Uri uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE);
                    if (uri == null)
                        uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                    findPhonePlayer = new MediaPlayer();
                    findPhonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                    findPhonePlayer.setDataSource(context, uri);
                    findPhonePlayer.setLooping(true);
                    findPhonePlayer.prepare();
                    findPhonePlayer.start();
                }
                try {
                    Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null && v.hasVibrator()) {
                        v.vibrate(VibrationEffect.createWaveform(new long[] { 0, 600, 400 }, 0));
                    }
                } catch (Exception ve) {
                    log("Find phone vibrate skipped: " + ve.getMessage());
                }
                postFindPhoneNotification();
                log("Find phone: ringing");
                forEachListener(BleStateListener::onFindPhoneRequest);
            } catch (Exception e) {
                log("Find phone ring error: " + e.getMessage());
            }
        });
    }

    /** Stop the find-phone alert. */
    public void stopFindPhoneAlert() {
        handler.post(() -> {
            if (findPhonePlayer != null) {
                try {
                    findPhonePlayer.stop();
                    findPhonePlayer.release();
                } catch (Exception ignored) {
                }
                findPhonePlayer = null;
            }
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null)
                v.cancel();
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null)
                nm.cancel(FIND_PHONE_NOTIF_ID);
            log("Find phone: stopped");
        });
    }

    public boolean isFindPhoneActive() {
        return findPhonePlayer != null;
    }

    @SuppressLint("MissingPermission")
    private void postFindPhoneNotification() {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null)
            return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(FIND_PHONE_CHANNEL,
                    "Buscar teléfono", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null); // we ring via MediaPlayer
            nm.createNotificationChannel(ch);
        }
        Intent stopIntent = new Intent(ACTION_FIND_PHONE_STOP).setPackage(context.getPackageName());
        PendingIntent stopPi = PendingIntent.getBroadcast(context, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent full = new Intent(context, com.example.dialsender.FindPhoneActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent fullPi = PendingIntent.getActivity(context, 1, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, FIND_PHONE_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.findphone_notif_title))
                .setContentText(context.getString(R.string.findphone_notif_desc))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setFullScreenIntent(fullPi, true)
                .addAction(0, context.getString(R.string.findphone_stop), stopPi);
        try {
            nm.notify(FIND_PHONE_NOTIF_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    /**
     * Generic settings writer for SET (0x02) keys. Use the authoritative key
     * values in protocols/reference/blekey_map_authoritative.txt.
     */
    public void sendSetting(int key, byte[] data) {
        if (!isSessionReady())
            return;
        byte[] msg = createMessage((byte) 0x02, (byte) key, (byte) 0x00, data);
        log("Tx SET key=0x" + String.format("%02X", key) + " " + bytesToHex(data));
        enqueueLogicalFrame(msg);
        flushQueue();
    }

    // ========== Notification Forwarding ==========

    public void sendNotification(int category, String title, String content, String packageName) {
        handler.post(() -> {
            if (!isSessionReady())
                return;

            // BleNotification (0x0401, UPDATE) — layout verified against the
            // original app's decompiled BleNotification entity:
            //   [0]       mCategory (int8)
            //   [1..6]    mTime (BleTime, 6 bytes: yy,MM,dd,hh,mm,ss)
            //   [7..38]   mPackage (32 bytes fixed, UTF-8, null-padded)
            //   [39..70]  mTitle   (32 bytes fixed, UTF-8, null-padded)
            //   [71..]    mContent (actual UTF-8 bytes, truncated to 250, NOT padded)
            // Total = 71 + contentBytes (variable length).
            byte[] contentBytes = truncatedUtf8(content != null ? content : "", 250);
            ByteBuffer buf = ByteBuffer.allocate(71 + contentBytes.length);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.put((byte) category);
            putBleTime(buf, Calendar.getInstance());
            buf.put(fixedBytes(packageName != null ? packageName : "", 32));
            buf.put(fixedBytes(title != null ? title : "", 32));
            buf.put(contentBytes);

            byte[] frame = createMessage((byte) 0x04, (byte) 0x01, (byte) 0x00, buf.array());
            log("Tx NOTIFICATION cat=" + category + " pkg=" + packageName + " title=" + title);
            enqueueLogicalFrame(frame);
            if (!isSending) {
                flushQueue();
            }
        });
    }

    /**
     * Returns a null-padded byte array of exactly {@code len} bytes from {@code s}
     * (UTF-8).
     */
    private byte[] fixedBytes(String s, int len) {
        byte[] src = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] dst = new byte[len];
        System.arraycopy(src, 0, dst, 0, Math.min(src.length, len));
        return dst;
    }

    /** UTF-8 bytes of {@code s}, truncated to at most {@code maxLen} bytes. */
    private byte[] truncatedUtf8(String s, int maxLen) {
        byte[] src = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (src.length <= maxLen)
            return src;
        return Arrays.copyOf(src, maxLen);
    }

    // ========== Utility ==========

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
            sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
}
