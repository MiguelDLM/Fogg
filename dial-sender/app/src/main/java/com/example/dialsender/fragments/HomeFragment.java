package com.example.dialsender.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.dialsender.R;
import com.example.dialsender.ble.BleManager;
import com.example.dialsender.ble.SleepAnalyzer;
import com.example.dialsender.views.GaugeView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HomeFragment extends Fragment implements BleManager.BleStateListener {

    private static final String PREF_NAME = "dial_sender_prefs";
    private static final String PREF_HEALTH_PREFIX = "health_";

    private TextView txtGreeting, txtDate, homeStatusText;
    private View homeStatusDot;
    private GaugeView gaugeSteps, gaugeHeart, gaugeCalories, gaugeSpo2, gaugeSleep;
    private TextView txtSleepPhases;
    private BroadcastReceiver gaugeStyleReceiver;

    private SharedPreferences prefs;
    private BleManager bleManager;

    private SwipeRefreshLayout swipeRefresh;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(() -> {
            if (bleManager.isSessionReady()) {
                bleManager.syncHealth();
                // Safety timeout to hide spinner if sync callback never arrives
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (swipeRefresh.isRefreshing()) {
                        swipeRefresh.setRefreshing(false);
                    }
                }, 12000);
            } else {
                swipeRefresh.setRefreshing(false);
            }
        });

        txtGreeting = view.findViewById(R.id.txtGreeting);
        txtDate = view.findViewById(R.id.txtDate);
        homeStatusText = view.findViewById(R.id.homeStatusText);
        homeStatusDot = view.findViewById(R.id.homeStatusDot);

        gaugeSteps = view.findViewById(R.id.gaugeSteps);
        gaugeHeart = view.findViewById(R.id.gaugeHeart);
        gaugeCalories = view.findViewById(R.id.gaugeCalories);
        gaugeSpo2 = view.findViewById(R.id.gaugeSpo2);
        gaugeSleep = view.findViewById(R.id.gaugeSleep);
        txtSleepPhases = view.findViewById(R.id.txtSleepPhases);

        prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        bleManager = BleManager.getInstance(requireContext());

        view.findViewById(R.id.gaugeCardSteps).setOnClickListener(v -> MetricDetailBottomSheet.newInstance("steps")
                .show(getChildFragmentManager(), "detail"));

        view.findViewById(R.id.gaugeCardHeart).setOnClickListener(v -> MetricDetailBottomSheet.newInstance("heart_rate")
                .show(getChildFragmentManager(), "detail"));

        view.findViewById(R.id.gaugeCardCalories)
                .setOnClickListener(v -> MetricDetailBottomSheet.newInstance("calories")
                        .show(getChildFragmentManager(), "detail"));

        view.findViewById(R.id.gaugeCardSpo2)
                .setOnClickListener(v -> MetricDetailBottomSheet.newInstance("blood_oxygen")
                        .show(getChildFragmentManager(), "detail"));

        view.findViewById(R.id.gaugeCardSleep).setOnClickListener(v -> MetricDetailBottomSheet.newInstance("sleep")
                .show(getChildFragmentManager(), "detail"));

        updateDate();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        bleManager.addListener(this);
        refreshMetrics();
        updateConnectionState(bleManager.isSessionReady());

        gaugeStyleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyGaugeStyle();
            }
        };
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                gaugeStyleReceiver,
                new IntentFilter(SettingsFragment.ACTION_GAUGE_STYLE_CHANGED));
        applyGaugeStyle();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (gaugeStyleReceiver != null) {
            LocalBroadcastManager.getInstance(requireContext())
                    .unregisterReceiver(gaugeStyleReceiver);
            gaugeStyleReceiver = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bleManager != null) {
            bleManager.removeListener(this);
        }
        gaugeSteps = null;
        gaugeHeart = null;
        gaugeCalories = null;
        gaugeSpo2 = null;
        gaugeSleep = null;
        txtSleepPhases = null;
    }

    private void applyGaugeStyle() {
        if (!isAdded() || prefs == null)
            return;
        String style = prefs.getString("gauge_style", GaugeView.STYLE_B);
        GaugeView[] gauges = { gaugeSteps, gaugeHeart, gaugeCalories, gaugeSpo2, gaugeSleep };
        for (GaugeView g : gauges) {
            if (g != null)
                g.setGaugeStyle(style);
        }
        // Apply specific text size multiplier for sleep gauge if it exists
        if (gaugeSleep != null) {
            gaugeSleep.setValueTextSizeMultiplier(0.7f);
        }
    }

    private void updateDate() {
        if (txtDate == null)
            return;
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE, d MMM", Locale.getDefault());
        txtDate.setText(sdf.format(new Date()));
    }

    private void refreshMetrics() {
        // gauge* are nulled in onDestroyView, but a health-sync callback can
        // still arrive while the fragment is technically added.
        if (!isAdded() || prefs == null || gaugeSteps == null || gaugeSleep == null)
            return;

        int goalSteps = prefs.getInt("goal_steps", 10000);
        int goalCal = prefs.getInt("goal_calories", 500);
        int goalSleep = prefs.getInt("goal_sleep_min", 480);

        // Steps
        int steps = getLatestMetricValue("steps");
        gaugeSteps.setValue(steps > 0 ? (float) steps / goalSteps : 0f);
        gaugeSteps.setValueText(steps > 0 ? formatSteps(steps) : "—");
        gaugeSteps.setSubText(getString(R.string.goal_short_fmt, formatSteps(goalSteps)));

        // Heart rate
        int hr = getLatestMetricValue("heart_rate");
        gaugeHeart.setValue(hr > 0 ? (float) hr / 200f : 0f);
        gaugeHeart.setValueText(hr > 0 ? String.valueOf(hr) : "—");
        gaugeHeart.setSubText(getString(hr > 0 ? R.string.normal_label : R.string.no_data));

        // Calories
        int cal = getLatestMetricValue("calories");
        gaugeCalories.setValue(cal > 0 ? (float) cal / goalCal : 0f);
        gaugeCalories.setValueText(cal > 0 ? String.valueOf(cal) : "—");
        gaugeCalories.setSubText(getString(R.string.goal_short_fmt, String.valueOf(goalCal)));

        // SpO2
        int spo2 = getLatestMetricValue("blood_oxygen");
        gaugeSpo2.setValue(spo2 > 0 ? (float) spo2 / 100f : 0f);
        gaugeSpo2.setValueText(spo2 > 0 ? spo2 + "%" : "—");
        gaugeSpo2.setSubText(spo2 > 0 ? getString(R.string.normal_label) : getString(R.string.no_data));

        // Sleep
        String sleepData = prefs.getString(PREF_HEALTH_PREFIX + "sleep", "");
        // Today's sleep (the session you woke up from this morning), not just
        // whatever the last synced session happens to be.
        SleepAnalyzer.SleepResult sr = SleepAnalyzer.analyzeDay(sleepData, todayStart());
        int sleepMin = sr.totalMinutes;
        gaugeSleep.setValue(sleepMin > 0 ? (float) sleepMin / goalSleep : 0f);
        if (sleepMin > 0) {
            int h = sleepMin / 60, m = sleepMin % 60;
            gaugeSleep.setValueText(h > 0 ? h + "h" + m + "m" : m + "m");
            gaugeSleep.setValueTextSizeMultiplier(0.7f); // Reduce sleep text size

            StringBuilder phases = new StringBuilder();
            if (sr.deepMin > 0)
                phases.append(getString(R.string.sleep_deep_abbr)).append(" ").append(formatMins(sr.deepMin)).append("  ");
            if (sr.lightMin > 0)
                phases.append(getString(R.string.sleep_light_abbr)).append(" ").append(formatMins(sr.lightMin)).append("  ");
            if (sr.remMin > 0)
                phases.append("REM: ").append(formatMins(sr.remMin));
            String phasesStr = phases.toString().trim();
            txtSleepPhases.setText(phasesStr.isEmpty() ? getString(R.string.sleep_no_breakdown) : phasesStr);
        } else {
            gaugeSleep.setValueText("—");
            txtSleepPhases.setText(R.string.no_data);
        }
    }

    private String formatSteps(int steps) {
        if (steps >= 1000) {
            return (steps / 1000) + "." + ((steps % 1000) / 100) + "K";
        }
        return String.valueOf(steps);
    }

    private String formatMins(int mins) {
        int h = mins / 60, m = mins % 60;
        return h > 0 ? h + "h" + m + "m" : m + "m";
    }

    /**
     * Latest value recorded TODAY, or 0. Taking the last entry of the whole
     * history — as this used to — surfaced a value from days ago as if it had
     * just been measured, because a re-sync appends old records at the end.
     */
    private int getLatestMetricValue(String metric) {
        String history = prefs.getString(PREF_HEALTH_PREFIX + metric, "");
        if (history.isEmpty())
            return 0;
        long dayStart = todayStart();
        long dayEnd = dayStart + 86400L;
        long bestTs = Long.MIN_VALUE;
        int best = 0;
        for (String entry : history.split(",")) {
            String[] p = entry.trim().split(":");
            if (p.length < 2)
                continue;
            try {
                long ts = Long.parseLong(p[0]);
                if (ts < dayStart || ts >= dayEnd || ts <= bestTs)
                    continue;
                bestTs = ts;
                best = Integer.parseInt(p[1]);
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private static long todayStart() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000;
    }

    private void updateConnectionState(boolean connected) {
        if (!isAdded())
            return;
        if (connected) {
            homeStatusDot.setBackgroundResource(R.drawable.indicator_connected);
            homeStatusText.setText(R.string.connected);
            homeStatusText.setTextColor(requireContext().getColor(R.color.status_connected));
        } else {
            homeStatusDot.setBackgroundResource(R.drawable.indicator_disconnected);
            homeStatusText.setText(R.string.disconnected);
            homeStatusText.setTextColor(requireContext().getColor(R.color.status_disconnected));
        }
    }

    // BleStateListener
    @Override
    public void onConnectionStateChange(boolean connected, boolean sessionReady) {
        if (!isAdded())
            return;
        requireActivity().runOnUiThread(() -> updateConnectionState(sessionReady));
    }

    @Override
    public void onHealthDataReceived(String keyName, byte[] payload) {
    }

    @Override
    public void onHealthSyncComplete() {
        if (!isAdded())
            return;
        requireActivity().runOnUiThread(() -> {
            refreshMetrics();
            if (swipeRefresh != null)
                swipeRefresh.setRefreshing(false);
        });
    }

    @Override
    public void onTransferProgress(int percent, long bytesTransferred, long totalBytes) {
    }

    @Override
    public void onTransferComplete() {
    }

    @Override
    public void onLogUpdated() {
    }
}
