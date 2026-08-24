package com.example.dialsender;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.example.dialsender.ble.BleForegroundService;
import com.example.dialsender.ble.BleManager;
import com.example.dialsender.fragments.DeviceFragment;
import com.example.dialsender.fragments.SettingsFragment;
import com.example.dialsender.fragments.SportFragment;
import com.example.dialsender.fragments.StatusFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;
    private BleManager.BleStateListener autoSyncListener;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.example.dialsender.theme.ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .registerReceiver(new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(android.content.Context context, Intent intent) {
                        recreate();
                    }
                }, new android.content.IntentFilter(com.example.dialsender.theme.ThemeManager.ACTION_THEME_CHANGED));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 200);
            }
        }

        BleManager ble = BleManager.getInstance(this);
        if (ble.getVerifiedDeviceAddress() != null && ble.isAutoConnectEnabled()) {
            BleForegroundService.start(this);
            requestBatteryOptimizationExemptionOnce();
        }

        // Auto-sync when the session becomes ready. Kept for the whole life of
        // the activity — it used to be dropped as soon as any fragment
        // registered its own listener.
        autoSyncListener = new BleManager.BleStateListener() {
            @Override
            public void onConnectionStateChange(boolean connected, boolean sessionReady) {
                if (sessionReady) {
                    ble.syncTime();
                    ble.readBattery();
                    // onSessionReady() already has device info, firmware, battery
                    // and the time/settings push queued for the next second. A
                    // health READ fired into that burst goes unanswered, so let
                    // the watch finish the post-connect routine first.
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(ble::syncHealth, 4000);
                }
            }

            @Override
            public void onHealthDataReceived(String k, byte[] p) {
            }

            @Override
            public void onHealthSyncComplete() {
            }

            @Override
            public void onTransferProgress(int pct, long done, long tot) {
            }

            @Override
            public void onTransferComplete() {
            }

            @Override
            public void onLogUpdated() {
            }

            @Override
            public void onFindPhoneRequest() {
                runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.findphone_dialog_title)
                        .setMessage(R.string.findphone_title)
                        .setPositiveButton(R.string.findphone_stop_ringing, (d, w) -> ble.stopFindPhoneAlert())
                        .setCancelable(false)
                        .show());
            }
        };
        ble.addListener(autoSyncListener);

        bottomNav = findViewById(R.id.bottomNav);
        com.example.dialsender.theme.ThemeManager.AppTheme currentTheme = com.example.dialsender.theme.ThemeManager.getTheme(this);
        int[][] navStates = new int[][] {
            new int[] { android.R.attr.state_checked },
            new int[] { -android.R.attr.state_checked }
        };
        int[] navColors = new int[] {
            currentTheme.navActive,
            currentTheme.navInactive
        };
        android.content.res.ColorStateList navColorStateList = new android.content.res.ColorStateList(navStates, navColors);
        bottomNav.setItemIconTintList(navColorStateList);
        bottomNav.setItemTextColor(navColorStateList);
        bottomNav.setBackgroundColor(currentTheme.bgSurface);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_status) {
                loadFragment(new StatusFragment());
            } else if (id == R.id.nav_sport) {
                loadFragment(new SportFragment());
            } else if (id == R.id.nav_device) {
                loadFragment(new DeviceFragment());
            } else if (id == R.id.nav_me) {
                loadFragment(new SettingsFragment());
            } else {
                return false;
            }
            return true;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_status);
        }
    }

    /**
     * Ask once to be exempted from Doze/battery optimisation. Without it the
     * OS freezes the process during deep Doze and the retry loop simply never
     * runs — this is what the original app requests too
     * (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). It does not itself consume
     * battery; it only stops Android from suspending an already idle loop.
     */
    private void requestBatteryOptimizationExemptionOnce() {
        android.content.SharedPreferences sp = getSharedPreferences("dial_sender_prefs", MODE_PRIVATE);
        if (sp.getBoolean("battery_opt_asked", false))
            return;
        try {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()))
                return;
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
            sp.edit().putBoolean("battery_opt_asked", true).apply();
        } catch (Exception ignored) {
            // Some OEM ROMs do not expose the settings screen.
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (autoSyncListener != null)
            BleManager.getInstance(this).removeListener(autoSyncListener);
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }

    /**
     * Show a fragment in the main container WITHOUT changing the bottom tab —
     * used e.g. to open the watch-face library from inside the Device tab.
     */
    public void showFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .addToBackStack(null)
                .commit();
    }
}
