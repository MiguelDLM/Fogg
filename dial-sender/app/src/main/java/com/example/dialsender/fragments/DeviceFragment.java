package com.example.dialsender.fragments;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TimePicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.dialsender.ble.WatchCallController;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.dialsender.R;
import com.example.dialsender.ble.BleManager;
import com.example.dialsender.ble.WatchFilter;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DeviceFragment extends Fragment implements BleManager.BleStateListener {

    private View statusIndicator;
    private TextView txtStatus;
    private Button btnConnect;
    private android.widget.ImageView imgWatch;

    private TextView txtBattery;
    private TextView txtRssi;
    private TextView txtDeviceName;
    private TextView txtDeviceMac;
    private View statsRow;

    private TextView txtBacklight;
    private TextView txtRaiseToWake;
    private TextView txtFirmware;
    private TextView txtFirmwareRowValue;

    private BleManager bleManager;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isAgpsTransferActive = false;
    private AlertDialog activeProgressDialog;
    private android.widget.ProgressBar activeProgressBar;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_device, container, false);

        // Connection UI
        imgWatch = view.findViewById(R.id.imgWatch);
        statusIndicator = view.findViewById(R.id.statusIndicator);
        txtStatus = view.findViewById(R.id.txtStatus);
        btnConnect = view.findViewById(R.id.btnConnect);

        txtBattery = view.findViewById(R.id.txtBattery);
        txtRssi = view.findViewById(R.id.txtRssi);
        txtDeviceName = view.findViewById(R.id.txtDeviceName);
        txtDeviceMac = view.findViewById(R.id.txtDeviceMac);
        statsRow = view.findViewById(R.id.statsRow);

        txtBacklight = view.findViewById(R.id.txtBacklight);
        txtRaiseToWake = view.findViewById(R.id.txtRaiseToWake);
        txtFirmware = view.findViewById(R.id.txtFirmware);
        txtFirmwareRowValue = view.findViewById(R.id.txtFirmwareRowValue);

        bluetoothManager = (BluetoothManager) requireContext()
                .getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        bleManager = BleManager.getInstance(requireContext());
        bleManager.addListener(this);

        btnConnect.setOnClickListener(v -> handleConnect());

        View btnQrPair = view.findViewById(R.id.btnQrPair);
        if (btnQrPair != null) {
            btnQrPair.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), com.example.dialsender.QrDeviceScannerActivity.class)));
        }

        // Device function entries
        View btnWatchFaces = view.findViewById(R.id.btnWatchFaces);
        if (btnWatchFaces != null) {
            btnWatchFaces.setOnClickListener(v -> {
                if (getActivity() instanceof com.example.dialsender.MainActivity) {
                    ((com.example.dialsender.MainActivity) getActivity())
                            .showFragment(new DialsFragment());
                }
            });
        }
        View rowNotifications = view.findViewById(R.id.rowNotifications);
        if (rowNotifications != null) {
            rowNotifications.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), com.example.dialsender.NotificationSettingsActivity.class)));
        }
        View rowAlarms = view.findViewById(R.id.rowAlarms);
        if (rowAlarms != null) {
            rowAlarms.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), com.example.dialsender.AlarmsActivity.class)));
        }
        setupCallControl(view);
        setupFindWatch(view);
        setupMonitoring(view);

        View btnCamera = view.findViewById(R.id.btnCamera);
        if (btnCamera != null) {
            btnCamera.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), com.example.dialsender.CameraActivity.class)));
        }

        View btnAgps = view.findViewById(R.id.btnAgps);
        if (btnAgps != null) {
            btnAgps.setOnClickListener(v -> startAgpsSync());
        }

        // Anti-pérdida toggle (verified: SET ANTI_LOST 0x0215, int8 0/1)
        TextView txtAntiLost = view.findViewById(R.id.txtAntiLost);
        View rowAntiLost = view.findViewById(R.id.rowAntiLost);
        if (txtAntiLost != null && rowAntiLost != null) {
            android.content.SharedPreferences sp = requireContext()
                    .getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE);
            boolean[] state = { sp.getBoolean("set_antilost", false) };
            txtAntiLost.setText(state[0] ? R.string.state_on : R.string.state_off);
            rowAntiLost.setOnClickListener(v -> {
                state[0] = !state[0];
                txtAntiLost.setText(state[0] ? R.string.state_on : R.string.state_off);
                sp.edit().putBoolean("set_antilost", state[0]).apply();
                if (bleManager.isSessionReady()) {
                    bleManager.sendSetting(0x15, new byte[] { (byte) (state[0] ? 1 : 0) });
                } else {
                    Toast.makeText(requireContext(), getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show();
                }
            });
        }

        android.content.SharedPreferences sp2 = requireContext()
                .getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE);

        // No molestar (verified: NO_DISTURB_RANGE 0x020A = enabled + 3x BleTimeRange[en,sh,sm,eh,em])
        TextView txtDnd = view.findViewById(R.id.txtDnd);
        View rowDnd = view.findViewById(R.id.rowDnd);
        if (txtDnd != null && rowDnd != null) {
            boolean[] on = { sp2.getBoolean("set_dnd", false) };
            txtDnd.setText(on[0] ? R.string.state_on : R.string.state_off);
            rowDnd.setOnClickListener(v -> {
                on[0] = !on[0];
                txtDnd.setText(on[0] ? R.string.state_on : R.string.state_off);
                sp2.edit().putBoolean("set_dnd", on[0]).apply();
                if (bleManager.isSessionReady()) {
                    byte en = (byte) (on[0] ? 1 : 0);
                    byte[] p = new byte[16];
                    p[0] = en;                 // global enabled
                    p[1] = en; p[2] = 22; p[3] = 0; p[4] = 8; p[5] = 0; // range1 22:00–08:00
                    bleManager.sendSetting(0x0A, p);
                } else {
                    Toast.makeText(requireContext(), getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Recordatorio sedentario (SEDENTARINESS 0x0209, 6B: bitfield[en|repeat], sh,sm,eh,em,interval)
        TextView txtSed = view.findViewById(R.id.txtSedentary);
        View rowSed = view.findViewById(R.id.rowSedentary);
        if (txtSed != null && rowSed != null) {
            boolean[] on = { sp2.getBoolean("set_sedentary", false) };
            txtSed.setText(on[0] ? R.string.state_on : R.string.state_off);
            rowSed.setOnClickListener(v -> {
                on[0] = !on[0];
                txtSed.setText(on[0] ? R.string.state_on : R.string.state_off);
                sp2.edit().putBoolean("set_sedentary", on[0]).apply();
                if (bleManager.isSessionReady()) {
                    byte b0 = (byte) ((on[0] ? 0x80 : 0x00) | 0x7F); // enabled bit + all weekdays
                    byte[] p = new byte[] { b0, 8, 0, 22, 0, 60 };   // 08:00–22:00, cada 60 min
                    bleManager.sendSetting(0x09, p);
                } else {
                    Toast.makeText(requireContext(), getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show();
                }
            });
        }
        // Luz de fondo (Backlight)
        View rowBacklight = view.findViewById(R.id.rowBacklight);
        if (rowBacklight != null && txtBacklight != null) {
            rowBacklight.setOnClickListener(v -> {
                String[] options = {getString(R.string.device_backlight_5s), getString(R.string.device_backlight_10s), getString(R.string.device_backlight_15s), getString(R.string.device_backlight_20s)};
                int[] values = {5, 10, 15, 20};
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.device_backlight_title))
                    .setItems(options, (dialog, which) -> {
                        int seconds = values[which];
                        txtBacklight.setText(seconds + "s");
                        sp2.edit().putInt("set_backlight", seconds).apply();
                        if (bleManager.isSessionReady()) {
                            bleManager.sendSetting(0x08, new byte[] { (byte) seconds });
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            });
        }

        // Activar al levantar (Raise to Wake)
        View rowRaiseToWake = view.findViewById(R.id.rowRaiseToWake);
        if (rowRaiseToWake != null && txtRaiseToWake != null) {
            boolean[] on = { sp2.getBoolean("set_raise_to_wake", false) };
            txtRaiseToWake.setText(on[0] ? R.string.state_on : R.string.state_off);
            rowRaiseToWake.setOnClickListener(v -> {
                on[0] = !on[0];
                txtRaiseToWake.setText(on[0] ? R.string.state_on : R.string.state_off);
                sp2.edit().putBoolean("set_raise_to_wake", on[0]).apply();
                if (bleManager.isSessionReady()) {
                    byte[] p = new byte[] { (byte) (on[0] ? 1 : 0), 0, 0, 23, 59 };
                    bleManager.sendSetting(0x0C, p);
                } else {
                    Toast.makeText(requireContext(), getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Girar muñeca para foto (Shake Camera)
        View btnShakeCamera = view.findViewById(R.id.btnShakeCamera);
        if (btnShakeCamera != null) {
            btnShakeCamera.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), com.example.dialsender.CameraActivity.class)));
        }

        // Actualizar firmware (Firmware Upgrade Check)
        View btnFirmware = view.findViewById(R.id.btnFirmware);
        if (btnFirmware != null) {
            btnFirmware.setOnClickListener(v -> {
                if (!bleManager.isSessionReady()) {
                    Toast.makeText(requireContext(), getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show();
                    return;
                }
                bleManager.readFirmwareVersion();
                android.app.ProgressDialog pd = new android.app.ProgressDialog(requireContext());
                pd.setMessage(getString(R.string.device_fw_checking));
                pd.setCancelable(true);
                pd.show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (pd.isShowing()) {
                        pd.dismiss();
                        String currentVer = sp2.getString("firmware_version", "");
                        if (currentVer.isEmpty() || currentVer.equals("v") || currentVer.equals("—")) {
                            String fwVer = sp2.getString("device_info_firmware_version", "");
                            String fullVer = sp2.getString("device_info_full_version", "");
                            if (!fwVer.isEmpty() && !fwVer.equals("0.0.0")) {
                                currentVer = "v" + fwVer;
                            } else if (!fullVer.isEmpty()) {
                                currentVer = (fullVer.startsWith("v") || fullVer.startsWith("V")) ? fullVer : "v" + fullVer;
                            } else {
                                currentVer = "v1.0.0";
                            }
                        }
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle(getString(R.string.device_fw_update_title))
                                .setMessage(getString(R.string.device_fw_uptodate_msg, currentVer))
                                .setPositiveButton(getString(R.string.accept), null)
                                .show();
                    }
                }, 1500);
            });
        }

        // Desconectar
        View btnDisconnect = view.findViewById(R.id.btnDisconnect);
        if (btnDisconnect != null) {
            btnDisconnect.setOnClickListener(v -> {
                bleManager.disconnect();
                Toast.makeText(requireContext(), getString(R.string.device_disconnecting), Toast.LENGTH_SHORT).show();
            });
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        bleManager.addListener(this);
        syncConnectionUi();
        checkNotificationListenerAccess();
    }

    private void syncConnectionUi() {
        boolean sessionReady = bleManager.isSessionReady();
        boolean connected = bleManager.isConnected();

        // Device identity (name + MAC) from the last bound device
        String name = bleManager.getLastDeviceName();
        String addr = bleManager.getLastDeviceAddress();
        if (txtDeviceName != null)
            txtDeviceName.setText(name != null && !name.isEmpty() ? name : getString(R.string.device_default_name));
        loadWatchImage(name);
        if (txtDeviceMac != null)
            txtDeviceMac.setText(addr != null ? addr : getString(R.string.device_unbound));

        if (sessionReady) {
            statusIndicator.setBackgroundResource(R.drawable.indicator_connected);
            txtStatus.setText(R.string.connected);
            txtStatus.setTextColor(com.example.dialsender.theme.ThemeManager.getTheme(requireContext()).success);
            btnConnect.setText(R.string.reconnect);

            android.content.SharedPreferences sp = requireContext()
                    .getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE);
            int batt = sp.getInt("battery_level", 0);
            // Battery shown inline next to device name
            if (txtBattery != null)
                // The layout already shows a battery vector next to this label.
                txtBattery.setText(batt > 0 ? batt + "%" : "");

            // Firmware version shown in the statsRow chip
            if (statsRow != null)
                statsRow.setVisibility(View.VISIBLE);
            if (txtRssi != null)
                txtRssi.setText("FW:");
            String version = sp.getString("firmware_version", "");
            if (version.isEmpty() || version.equals("v") || version.equals("v0.0.0")) {
                String fwVer = sp.getString("device_info_firmware_version", "");
                String fullVer = sp.getString("device_info_full_version", "");
                if (!fwVer.isEmpty() && !fwVer.equals("0.0.0")) {
                    version = "v" + fwVer;
                } else if (!fullVer.isEmpty()) {
                    version = (fullVer.startsWith("v") || fullVer.startsWith("V")) ? fullVer : "v" + fullVer;
                } else {
                    version = "—";
                }
            }
            if (txtFirmware != null)
                txtFirmware.setText(version);
            if (txtFirmwareRowValue != null)
                txtFirmwareRowValue.setText(version);
        } else if (connected) {
            txtStatus.setText(R.string.connecting);
            txtStatus.setTextColor(com.example.dialsender.theme.ThemeManager.getTheme(requireContext()).warning);
            if (txtBattery != null) txtBattery.setText("");
            if (statsRow != null)
                statsRow.setVisibility(View.GONE);
            if (txtFirmwareRowValue != null)
                txtFirmwareRowValue.setText("—");
        } else {
            statusIndicator.setBackgroundResource(R.drawable.indicator_disconnected);
            txtStatus.setText(R.string.disconnected);
            txtStatus.setTextColor(com.example.dialsender.theme.ThemeManager.getTheme(requireContext()).danger);
            btnConnect.setText(R.string.scan_connect);
            if (txtBattery != null) txtBattery.setText("");
            if (statsRow != null)
                statsRow.setVisibility(View.GONE);
            if (txtFirmwareRowValue != null)
                txtFirmwareRowValue.setText("—");
        }

        android.content.SharedPreferences sp = requireContext()
                .getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE);
        if (txtBacklight != null) {
            int sec = sp.getInt("set_backlight", 5);
            txtBacklight.setText(sec + "s");
        }
        if (txtRaiseToWake != null) {
            boolean rt = sp.getBoolean("set_raise_to_wake", false);
            txtRaiseToWake.setText(rt ? R.string.state_on : R.string.state_off);
        }
    }

    private void checkNotificationListenerAccess() {
        androidx.core.app.NotificationManagerCompat nmc = androidx.core.app.NotificationManagerCompat.from(requireContext());
        boolean granted = nmc.getEnabledListenerPackages(requireContext()).contains(requireContext().getPackageName());
        if (!granted) {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.device_notif_disabled_title))
                    .setMessage(getString(R.string.device_notif_disabled_msg))
                    .setPositiveButton(getString(R.string.go_to_settings), (d, w) ->
                            startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                    .setNegativeButton(getString(R.string.ignore), null)
                    .show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stay subscribed while paused so connection callbacks still land; the
        // subscription is dropped in onDestroyView.
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bleManager.removeListener(this);
    }

    /**
     * handleConnect() returns early to ask for permissions; without this the
     * user had to find and press "Conectar" a second time, with no hint why.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_CALL_PERMISSIONS) {
            WatchCallController calls = bleManager.getCallController();
            if (calls.hasPermissions()) {
                requireContext().getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean(WatchCallController.PREF_CALL_CONTROL, true).apply();
                calls.refresh();
            } else {
                Toast.makeText(requireContext(), R.string.call_control_needs_permission,
                        Toast.LENGTH_LONG).show();
            }
            renderCallControl();
            return;
        }

        if (requestCode != 1)
            return;
        boolean granted = grantResults.length > 0;
        for (int r : grantResults)
            granted &= r == PackageManager.PERMISSION_GRANTED;
        if (granted)
            handleConnect();
        else
            Toast.makeText(requireContext(), R.string.enable_bt, Toast.LENGTH_SHORT).show();
    }

    // ========== BleStateListener callbacks ==========

    @Override
    public void onConnectionStateChange(boolean connected, boolean sessionReady) {
        if (!isAdded())
            return;
        requireActivity().runOnUiThread(this::syncConnectionUi);
    }

    @Override
    public void onHealthDataReceived(String keyName, byte[] payload) {
        // Health data will be handled elsewhere or logged
    }

    @Override
    public void onHealthSyncComplete() {
        // Health sync completion handled in StatusFragment
    }

    @Override
    public void onTransferProgress(int percent, long bytesTransferred, long totalBytes) {
        if (isAgpsTransferActive && activeProgressDialog != null && activeProgressBar != null) {
            activeProgressBar.setProgress(percent);
            activeProgressDialog.setMessage(getString(R.string.device_agps_progress, percent, bytesTransferred / 1024, totalBytes / 1024));
        }
    }

    @Override
    public void onTransferComplete() {
        if (isAgpsTransferActive) {
            isAgpsTransferActive = false;
            if (activeProgressDialog != null) {
                activeProgressDialog.dismiss();
            }
            Toast.makeText(requireContext(), getString(R.string.device_agps_ok), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onTransferFailed(String reason) {
        if (isAgpsTransferActive) {
            isAgpsTransferActive = false;
            if (activeProgressDialog != null) {
                activeProgressDialog.dismiss();
            }
            Toast.makeText(requireContext(), getString(R.string.device_agps_transfer_error, reason), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The peer cannot speak the watch protocol. Say so explicitly and point at
     * the GATT dump BleManager just wrote: the old behaviour was to drop
     * silently back to "disconnected", which reads as a bug in the app rather
     * than as unsupported hardware.
     */
    @Override
    public void onDeviceIncompatible(String deviceName, String reason) {
        if (!isAdded())
            return;
        int bodyRes;
        if (BleManager.REASON_NO_CHARACTERISTICS.equals(reason))
            bodyRes = R.string.incompatible_no_characteristics;
        else if (BleManager.REASON_NO_CCCD.equals(reason))
            bodyRes = R.string.incompatible_no_cccd;
        else
            bodyRes = R.string.incompatible_no_service;

        String body = getString(bodyRes, deviceName)
                + "\n\n" + getString(R.string.incompatible_dump_hint);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.incompatible_title)
                .setMessage(body)
                .setPositiveButton(R.string.incompatible_open_devtools, (d, w) ->
                        startActivity(new android.content.Intent(requireContext(),
                                com.example.dialsender.DeveloperToolsActivity.class)))
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public void onLogUpdated() {
        // BLE log now lives in the hidden developer tools (Yo → version taps)
    }

    // ========== Connection ==========

    private void handleConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(requireContext(), R.string.enable_bt, Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }, 1);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 1);
                return;
            }
        }

        // Devices the phone already knows about: an active GATT link first, then
        // the bonded list (the watch stops advertising once it is bonded, so a
        // scan alone would find nothing).
        //
        // Both lists are full of things that are not watches — headphones,
        // speakers, car kits — so every candidate goes through WatchFilter,
        // exactly like the original app checks its product-name list before
        // offering a device for binding. Without this, a pair of BLE headphones
        // with an open GATT link was picked as the single candidate and the app
        // tried to talk the watch protocol to them.
        if (bluetoothManager != null && hasConnectPermission()) {
            // Only a watch we have actually completed a session with skips the
            // picker. Without the "verified" flag a single bad tap would bind
            // the app to the wrong device forever.
            String lastAddress = bleManager.getVerifiedDeviceAddress();
            if (lastAddress != null) {
                for (BluetoothDevice d : bluetoothAdapter.getBondedDevices()) {
                    if (lastAddress.equalsIgnoreCase(d.getAddress())) {
                        txtStatus.setText(R.string.connecting);
                        bleManager.connect(d);
                        return;
                    }
                }
            }

            List<BluetoothDevice> candidates = new ArrayList<>();

            for (BluetoothDevice d : bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)) {
                if (!WatchFilter.isExcluded(d) && !containsDevice(candidates, d)) {
                    candidates.add(d);
                }
            }

            if (candidates.isEmpty()) {
                for (BluetoothDevice d : bluetoothAdapter.getBondedDevices()) {
                    if (!WatchFilter.isExcluded(d) && !containsDevice(candidates, d)) {
                        candidates.add(d);
                    }
                }
            }

            rankKnownFirst(candidates);

            if (candidates.size() == 1) {
                txtStatus.setText(R.string.connecting);
                bleManager.connect(candidates.get(0));
                return;
            } else if (candidates.size() > 1) {
                showDevicePicker(candidates);
                return;
            }
        }

        startWatchScan();
    }

    /** Recognised watches float to the top of the picker. */
    private void rankKnownFirst(List<BluetoothDevice> devices) {
        final Context ctx = requireContext();
        java.util.Collections.sort(devices, (a, b) -> {
            boolean ka = WatchFilter.isKnownWatch(ctx, a);
            boolean kb = WatchFilter.isKnownWatch(ctx, b);
            if (ka == kb)
                return 0;
            return ka ? -1 : 1;
        });
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean containsDevice(List<BluetoothDevice> list, BluetoothDevice device) {
        for (BluetoothDevice d : list) {
            if (d.getAddress().equals(device.getAddress()))
                return true;
        }
        return false;
    }

    private String describeDevice(BluetoothDevice d) {
        String fallback = getString(R.string.unknown_device);
        String name = hasConnectPermission() ? WatchFilter.displayName(d, fallback) : fallback;
        String label = name + " (" + d.getAddress() + ")";
        if (WatchFilter.isKnownWatch(requireContext(), d))
            label = "⌚ " + label;
        return label;
    }

    private void showDevicePicker(List<BluetoothDevice> devices) {
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            names[i] = describeDevice(devices.get(i));
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.select_watch)
                .setItems(names, (dialog, which) -> {
                    txtStatus.setText(R.string.connecting);
                    bleManager.connect(devices.get(which));
                })
                .show();
    }

    /**
     * Scans for advertising watches. Results are split into compatible devices
     * (shown by default) and everything else, which is only offered behind an
     * explicit "show all" so an unlisted model is still reachable.
     */
    private void startWatchScan() {
        final android.bluetooth.le.BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Toast.makeText(requireContext(), R.string.no_ble_found, Toast.LENGTH_SHORT).show();
            return;
        }

        btnConnect.setText(R.string.scanning);
        btnConnect.setEnabled(false);

        final List<BluetoothDevice> compatible = new ArrayList<>();
        final List<BluetoothDevice> others = new ArrayList<>();

        // A bonded watch stops advertising, so it can never show up in the scan.
        // Seed the fallback list with the bonded devices we filtered out, so an
        // unlisted model is still reachable through "show all".
        if (hasConnectPermission()) {
            for (BluetoothDevice d : bluetoothAdapter.getBondedDevices()) {
                if (!WatchFilter.isExcluded(d) && !containsDevice(compatible, d))
                    compatible.add(d);
            }
        }

        final android.bluetooth.le.ScanCallback scanCallback = new android.bluetooth.le.ScanCallback() {
            @Override
            public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                if (result == null || result.getDevice() == null || !isAdded())
                    return;
                BluetoothDevice device = result.getDevice();
                if (WatchFilter.isCandidate(requireContext(), result)) {
                    if (!containsDevice(compatible, device))
                        compatible.add(device);
                } else if (!containsDevice(others, device)) {
                    others.add(device);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                if (!isAdded())
                    return;
                Toast.makeText(requireContext(),
                        getString(R.string.scan_failed, errorCode), Toast.LENGTH_SHORT).show();
            }
        };

        try {
            android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCallback);
        } catch (Exception e) {
            btnConnect.setText(R.string.scan_connect);
            btnConnect.setEnabled(true);
            Toast.makeText(requireContext(), R.string.no_ble_found, Toast.LENGTH_SHORT).show();
            return;
        }

        // Same 10s window the original app uses for its bind screen.
        handler.postDelayed(() -> {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
            if (!isAdded())
                return;
            btnConnect.setText(R.string.scan_connect);
            btnConnect.setEnabled(true);

            rankKnownFirst(compatible);

            if (!compatible.isEmpty()) {
                if (compatible.size() == 1) {
                    txtStatus.setText(R.string.connecting);
                    bleManager.connect(compatible.get(0));
                } else {
                    showDevicePicker(compatible);
                }
            } else if (!others.isEmpty()) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.no_compatible_found_title)
                        .setMessage(getString(R.string.no_compatible_found_msg, others.size()))
                        .setPositiveButton(R.string.show_all_devices, (d, w) -> showDevicePicker(others))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            } else {
                Toast.makeText(requireContext(), R.string.no_ble_found, Toast.LENGTH_SHORT).show();
            }
        }, 10000);
    }

    private void startAgpsSync() {
        if (!bleManager.isSessionReady()) {
            Toast.makeText(requireContext(), getString(R.string.device_not_connected), Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.dev_agps));
        builder.setMessage(getString(R.string.device_agps_downloading));
        builder.setCancelable(false);
        
        android.widget.ProgressBar progressBar = new android.widget.ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        progressBar.setPadding(padding, padding, padding, padding);
        builder.setView(progressBar);
        
        AlertDialog progressDialog = builder.create();
        progressDialog.show();

        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("https://api.smawatch.cn/epo/ble_epo_offline.bin");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.connect();
                if (conn.getResponseCode() != java.net.HttpURLConnection.HTTP_OK) {
                    throw new Exception("HTTP status error: " + conn.getResponseCode());
                }
                int length = conn.getContentLength();
                java.io.InputStream is = conn.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                int totalRead = 0;
                while ((read = is.read(buffer)) != -1) {
                    bos.write(buffer, 0, read);
                    totalRead += read;
                    if (length > 0) {
                        final int percent = (int) ((totalRead * 100L) / length);
                        handler.post(() -> {
                            progressDialog.setMessage(getString(R.string.device_agps_downloading_pct, percent));
                            progressBar.setIndeterminate(false);
                            progressBar.setMax(100);
                            progressBar.setProgress(percent);
                        });
                    }
                }
                is.close();
                byte[] epoBytes = bos.toByteArray();

                handler.post(() -> {
                    progressDialog.setMessage(getString(R.string.device_agps_flashing));
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(100);
                    progressBar.setProgress(0);
                    
                    isAgpsTransferActive = true;
                    activeProgressDialog = progressDialog;
                    activeProgressBar = progressBar;
                    
                    bleManager.startFileTransfer(epoBytes, (byte) 0x02);
                });
            } catch (Exception e) {
                e.printStackTrace();
                handler.post(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    Toast.makeText(requireContext(), getString(R.string.device_agps_error, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void loadWatchImage(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) {
            return;
        }
        final String finalDeviceName = deviceName;
        final String cacheFileName = "watch_image_" + deviceName.replaceAll("[^a-zA-Z0-9]", "_") + ".png";
        final File cacheFile = new File(requireContext().getCacheDir(), cacheFileName);

        if (cacheFile.exists()) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
            if (bmp != null && imgWatch != null) {
                imgWatch.setImageBitmap(bmp);
                return;
            }
        }

        new Thread(() -> {
            try {
                String imageUrl = "https://api-oss.iot-solution.net/device/1719481068100_" + android.net.Uri.encode(finalDeviceName) + ".png";
                java.net.URL url = new java.net.URL(imageUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.connect();
                if (conn.getResponseCode() == java.net.HttpURLConnection.HTTP_OK) {
                    java.io.InputStream is = conn.getInputStream();
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                    if (bmp != null) {
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile)) {
                            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                        }
                        if (isAdded()) {
                            requireActivity().runOnUiThread(() -> {
                                if (imgWatch != null) {
                                    imgWatch.setImageBitmap(bmp);
                                }
                            });
                        }
                    }
                    is.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ===== Call control (INCOMING_CALL 0x0603) =====

    private static final int REQ_CALL_PERMISSIONS = 4301;

    private TextView txtCallControl;

    /**
     * Answer/hang up from the watch, off by default.
     *
     * It needs READ_PHONE_STATE and ANSWER_PHONE_CALLS, so the switch only
     * turns on once those are actually granted — flipping the label first and
     * discovering the denial later would leave the row lying about what the
     * watch can do.
     */
    private void setupCallControl(View view) {
        View row = view.findViewById(R.id.rowCallControl);
        txtCallControl = view.findViewById(R.id.txtCallControl);
        if (row == null || txtCallControl == null)
            return;

        renderCallControl();
        row.setOnClickListener(v -> {
            WatchCallController calls = bleManager.getCallController();
            android.content.SharedPreferences sp = requireContext()
                    .getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE);

            if (calls.isEnabled()) {
                sp.edit().putBoolean(WatchCallController.PREF_CALL_CONTROL, false).apply();
                calls.refresh();
                renderCallControl();
                return;
            }

            if (!calls.hasPermissions()) {
                requestPermissions(new String[] {
                        android.Manifest.permission.READ_PHONE_STATE,
                        android.Manifest.permission.ANSWER_PHONE_CALLS
                }, REQ_CALL_PERMISSIONS);
                return;
            }
            sp.edit().putBoolean(WatchCallController.PREF_CALL_CONTROL, true).apply();
            calls.refresh();
            renderCallControl();
        });
    }

    private void renderCallControl() {
        if (txtCallControl == null)
            return;
        WatchCallController calls = bleManager.getCallController();
        boolean on = calls.isEnabled() && calls.hasPermissions();
        txtCallControl.setText(on ? R.string.state_on : R.string.state_off);
    }


    // ===== Find the watch (FIND_WATCH 0x0234) =====

    /**
     * The watch stops ringing on its own once the user acknowledges it there
     * and tells us nothing when it does, so the row cannot show live state. It
     * shows "Ringing…" for a few seconds as feedback that the frame went out,
     * then goes back to offering the action.
     */
    /** Long enough to walk to the next room, short enough not to annoy. */
    private static final long FIND_WATCH_TIMEOUT_MS = 30_000;

    private void setupFindWatch(View view) {
        View row = view.findViewById(R.id.rowFindWatch);
        TextView value = view.findViewById(R.id.txtFindWatch);
        if (row == null || value == null)
            return;

        final boolean[] ringing = { false };
        row.setOnClickListener(v -> {
            if (!bleManager.isSessionReady()) {
                Toast.makeText(requireContext(), R.string.device_not_connected,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            ringing[0] = !ringing[0];
            bleManager.sendFindWatch(ringing[0]);
            value.setText(ringing[0] ? R.string.find_watch_ringing : R.string.find_watch_ring);
            if (ringing[0]) {
                // The watch never reports that it stopped, so the phone owns
                // the stop: after a while, send it rather than just relabelling
                // the row. Letting the label lapse on its own made the next tap
                // start a second ring instead of ending the first.
                value.postDelayed(() -> {
                    if (!ringing[0])
                        return;
                    ringing[0] = false;
                    bleManager.sendFindWatch(false);
                    value.setText(R.string.find_watch_ring);
                }, FIND_WATCH_TIMEOUT_MS);
            }
        });
    }

    // ===== Health monitoring windows =====

    private static final String PREF_HR_MON = "hr_monitoring";
    private static final String PREF_SPO2_MON = "spo2_monitoring";
    private static final String PREF_SLEEP_MON = "sleep_monitoring";

    private final java.util.List<Runnable> monitoringRepaint = new ArrayList<>();

    private void setupMonitoring(View view) {
        monitoringRepaint.clear();
        setupMonitoringRow(view, R.id.rowHrMonitoring, R.id.txtHrMonitoring,
                PREF_HR_MON, R.string.dev_hr_monitoring, true, 0, 0, 23, 59, 30);
        setupMonitoringRow(view, R.id.rowSpo2Monitoring, R.id.txtSpo2Monitoring,
                PREF_SPO2_MON, R.string.dev_spo2_monitoring, true, 0, 0, 23, 59, 60);
        setupMonitoringRow(view, R.id.rowSleepMonitoring, R.id.txtSleepMonitoring,
                PREF_SLEEP_MON, R.string.dev_sleep_monitoring, false, 21, 0, 10, 0, 0);

        // The watch holds the real setting; ask for it and repaint when it
        // answers, so the rows never show a phone-side guess.
        bleManager.setMonitoringListener(() -> {
            if (isAdded())
                for (Runnable r : monitoringRepaint)
                    r.run();
        });
        if (bleManager.isSessionReady())
            bleManager.readAllMonitoring();
    }

    /**
     * One monitoring window.
     *
     * The watch is not asked for its current setting: these keys are stored
     * phone-side and pushed, because a READ that the firmware does not answer
     * would leave the row stuck on a placeholder with no way to tell that from
     * "disabled". Pushing on every change keeps the two in step in the
     * direction that matters.
     *
     * @param hasInterval false for sleep, whose payload is five bytes with no
     *                    sampling interval
     */
    private void setupMonitoringRow(View view, int rowId, int valueId, final String pref,
                                    final int titleRes, final boolean hasInterval,
                                    final int defStartH, final int defStartM,
                                    final int defEndH, final int defEndM,
                                    final int defInterval) {
        final View row = view.findViewById(rowId);
        final TextView value = view.findViewById(valueId);
        if (row == null || value == null)
            return;

        final android.content.SharedPreferences sp = requireContext()
                .getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE);

        final int[] cfg = {
                sp.getBoolean(pref + "_on", false) ? 1 : 0,
                sp.getInt(pref + "_sh", defStartH), sp.getInt(pref + "_sm", defStartM),
                sp.getInt(pref + "_eh", defEndH), sp.getInt(pref + "_em", defEndM),
                sp.getInt(pref + "_interval", defInterval)
        };
        renderMonitoring(value, cfg, hasInterval);

        monitoringRepaint.add(() -> {
            cfg[0] = sp.getBoolean(pref + "_on", false) ? 1 : 0;
            cfg[1] = sp.getInt(pref + "_sh", defStartH);
            cfg[2] = sp.getInt(pref + "_sm", defStartM);
            cfg[3] = sp.getInt(pref + "_eh", defEndH);
            cfg[4] = sp.getInt(pref + "_em", defEndM);
            cfg[5] = sp.getInt(pref + "_interval", defInterval);
            renderMonitoring(value, cfg, hasInterval);
        });

        row.setOnClickListener(v -> showMonitoringDialog(pref, titleRes, hasInterval, cfg,
                () -> {
                    sp.edit()
                            .putBoolean(pref + "_on", cfg[0] == 1)
                            .putInt(pref + "_sh", cfg[1]).putInt(pref + "_sm", cfg[2])
                            .putInt(pref + "_eh", cfg[3]).putInt(pref + "_em", cfg[4])
                            .putInt(pref + "_interval", cfg[5])
                            .apply();
                    renderMonitoring(value, cfg, hasInterval);
                    pushMonitoring(pref, cfg, hasInterval);
                }));
    }

    private void renderMonitoring(TextView value, int[] cfg, boolean hasInterval) {
        if (cfg[0] == 0) {
            value.setText(R.string.state_off);
            return;
        }
        String window = getString(R.string.monitoring_window, cfg[1], cfg[2], cfg[3], cfg[4]);
        value.setText(hasInterval
                ? window + " · " + getString(R.string.monitoring_every, cfg[5])
                : window);
    }

    private void pushMonitoring(String pref, int[] cfg, boolean hasInterval) {
        if (!bleManager.isSessionReady()) {
            Toast.makeText(requireContext(), R.string.device_not_connected,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = cfg[0] == 1;
        if (PREF_HR_MON.equals(pref))
            bleManager.sendHeartRateMonitoring(on, cfg[1], cfg[2], cfg[3], cfg[4], cfg[5]);
        else if (PREF_SPO2_MON.equals(pref))
            bleManager.sendBloodOxygenMonitoring(on, cfg[1], cfg[2], cfg[3], cfg[4], cfg[5]);
        else
            bleManager.sendSleepMonitoring(on, cfg[1], cfg[2], cfg[3], cfg[4]);
    }

    private void showMonitoringDialog(String pref, int titleRes, boolean hasInterval,
                                      int[] cfg, Runnable onSave) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final com.google.android.material.switchmaterial.SwitchMaterial toggle =
                new com.google.android.material.switchmaterial.SwitchMaterial(requireContext());
        toggle.setText(R.string.monitoring_enabled);
        toggle.setChecked(cfg[0] == 1);
        box.addView(toggle);

        final TimePicker from = new TimePicker(requireContext());
        final TimePicker to = new TimePicker(requireContext());
        for (TimePicker p : new TimePicker[] { from, to })
            p.setIs24HourView(true);
        setPickerTime(from, cfg[1], cfg[2]);
        setPickerTime(to, cfg[3], cfg[4]);
        box.addView(labelledPicker(getString(R.string.monitoring_window, cfg[1], cfg[2], cfg[3], cfg[4]), from));
        box.addView(to);

        final EditText interval = new EditText(requireContext());
        if (hasInterval) {
            TextView lbl = new TextView(requireContext());
            lbl.setText(R.string.monitoring_interval);
            box.addView(lbl);
            interval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            interval.setText(String.valueOf(cfg[5]));
            box.addView(interval);
        }

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(box);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(titleRes)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    cfg[0] = toggle.isChecked() ? 1 : 0;
                    cfg[1] = pickerHour(from);
                    cfg[2] = pickerMinute(from);
                    cfg[3] = pickerHour(to);
                    cfg[4] = pickerMinute(to);
                    if (hasInterval) {
                        try {
                            cfg[5] = Math.max(1, Math.min(255,
                                    Integer.parseInt(interval.getText().toString().trim())));
                        } catch (NumberFormatException e) {
                            // Keep the previous interval rather than sending 0,
                            // which the watch reads as "never sample".
                        }
                    }
                    onSave.run();
                })
                .show();
    }

    private View labelledPicker(String text, View picker) {
        LinearLayout wrap = new LinearLayout(requireContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(requireContext());
        t.setText(text);
        wrap.addView(t);
        wrap.addView(picker);
        return wrap;
    }

    @SuppressWarnings("deprecation")
    private static void setPickerTime(TimePicker p, int h, int m) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            p.setHour(h);
            p.setMinute(m);
        } else {
            p.setCurrentHour(h);
            p.setCurrentMinute(m);
        }
    }

    @SuppressWarnings("deprecation")
    private static int pickerHour(TimePicker p) {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                ? p.getHour() : p.getCurrentHour();
    }

    @SuppressWarnings("deprecation")
    private static int pickerMinute(TimePicker p) {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                ? p.getMinute() : p.getCurrentMinute();
    }
}
