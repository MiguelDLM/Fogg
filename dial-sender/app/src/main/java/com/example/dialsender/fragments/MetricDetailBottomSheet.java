package com.example.dialsender.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.dialsender.R;
import com.example.dialsender.ble.BleManager;
import com.example.dialsender.ble.SleepAnalyzer;
import com.example.dialsender.views.SleepTimelineView;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MetricDetailBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_METRIC = "metric_key";
    private static final String PREF_NAME = "dial_sender_prefs";
    private static final String PREF_HEALTH_PREFIX = "health_";

    private static final int RANGE_DAY = 0, RANGE_WEEK = 1, RANGE_MONTH = 2, RANGE_ALL = 3;
    private int currentRange = RANGE_DAY;

    private String metricKey;
    private SharedPreferences prefs;
    private FrameLayout chartContainer;
    private TextView txtTitle, txtValue;

    public static MetricDetailBottomSheet newInstance(String metricKey) {
        MetricDetailBottomSheet sheet = new MetricDetailBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_METRIC, metricKey);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_metric_detail, container, false);

        metricKey = getArguments() != null ? getArguments().getString(ARG_METRIC, "steps") : "steps";
        prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        txtTitle = view.findViewById(R.id.txtDetailTitle);
        txtValue = view.findViewById(R.id.txtDetailValue);
        chartContainer = view.findViewById(R.id.detailChartContainer);

        txtTitle.setText(metricKey.replace("_", " ").toUpperCase(Locale.US));

        MaterialButtonToggleGroup toggle = view.findViewById(R.id.toggleDetailRange);
        toggle.check(R.id.btnDetailDay);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.btnDetailDay)
                    currentRange = RANGE_DAY;
                else if (checkedId == R.id.btnDetailWeek)
                    currentRange = RANGE_WEEK;
                else if (checkedId == R.id.btnDetailMonth)
                    currentRange = RANGE_MONTH;
                else if (checkedId == R.id.btnDetailAll)
                    currentRange = RANGE_ALL;
                renderChart();
            }
        });

        Button btnSync = view.findViewById(R.id.btnSyncDetail);
        btnSync.setOnClickListener(v -> {
            BleManager ble = BleManager.getInstance(requireContext());
            if (ble.isSessionReady()) {
                ble.syncHealth();
                Toast.makeText(requireContext(), R.string.syncing_short, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), R.string.status_not_connected, Toast.LENGTH_SHORT).show();
            }
        });

        View btnShare = view.findViewById(R.id.btnShareDetail);
        if (btnShare != null)
            btnShare.setOnClickListener(v -> shareMetric());

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        renderChart();
    }

    private void renderChart() {
        if (chartContainer == null || !isAdded())
            return;
        chartContainer.removeAllViews();
        String history = prefs.getString(PREF_HEALTH_PREFIX + metricKey, "");

        // Sleep is not a bucketed series, it gets its own renderer. Everything
        // below used to sit INSIDE this branch (the `else` was missing), so
        // every other metric drew nothing at all and sleep drew a bar chart of
        // raw mode codes on top of its own timeline.
        if ("sleep".equals(metricKey)) {
            renderSleep(history);
            return;
        }

        long now = System.currentTimeMillis() / 1000;
        long todayStart = getTodayStart();
        long rangeStart;
        int numBuckets, bucketSize;
        if (currentRange == RANGE_DAY) {
            rangeStart = todayStart;
            numBuckets = 24;
            bucketSize = 3600;
        } else if (currentRange == RANGE_WEEK) {
            rangeStart = todayStart - 6L * 86400;
            numBuckets = 7;
            bucketSize = 86400;
        } else if (currentRange == RANGE_MONTH) {
            rangeStart = todayStart - 29L * 86400;
            numBuckets = 30;
            bucketSize = 86400;
        } else {
            rangeStart = findEarliestTimestamp();
            numBuckets = (int) ((now - rangeStart) / 86400) + 1;
            if (numBuckets <= 0)
                numBuckets = 1;
            bucketSize = 86400;
        }

        float[] buckets = new float[numBuckets];
        int latestVal = 0;
        long latestTs = Long.MIN_VALUE;
        for (String entry : history.split(",")) {
            if (entry.trim().isEmpty())
                continue;
            long ts = 0;
            int val = 0;
            String[] p = entry.split(":");
            try {
                if (p.length >= 2) {
                    ts = Long.parseLong(p[0].trim());
                    val = Integer.parseInt(p[1].trim());
                } else {
                    val = Integer.parseInt(entry.trim());
                    ts = todayStart + 3600;
                }
            } catch (Exception ignored) {
                continue;
            }
            if (ts >= rangeStart && ts <= now + 86400) {
                int idx = (int) ((ts - rangeStart) / bucketSize);
                if (idx >= 0 && idx < numBuckets && val > buckets[idx])
                    buckets[idx] = val;
            }
            // The headline is the most recent reading of TODAY, by timestamp —
            // records arrive out of order, so "last in the string" was wrong.
            if (ts >= todayStart && ts <= now && ts > latestTs) {
                latestTs = ts;
                latestVal = val;
            }
        }

        txtValue.setText(latestVal > 0 ? String.valueOf(latestVal) : "—");

        List<BarEntry> entries = new ArrayList<>();
        for (int i = 0; i < numBuckets; i++)
            entries.add(new BarEntry(i, buckets[i]));

        BarDataSet ds = new BarDataSet(entries, "Data");
        ds.setColor(ContextCompat.getColor(requireContext(), R.color.accent_primary));
        ds.setDrawValues(false);
        chartContainer.addView(buildBarChart(ds), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /**
     * Day: the stage timeline for the night you woke up from today.
     * Week/month/all: hours slept per day.
     */
    private void renderSleep(String history) {
        long todayStart = getTodayStart();

        if (currentRange == RANGE_DAY) {
            SleepAnalyzer.SleepResult sr = SleepAnalyzer.analyzeDay(history, todayStart);
            txtValue.setText(sr.totalMinutes > 0
                    ? (sr.totalMinutes / 60) + "h " + (sr.totalMinutes % 60) + "m"
                    : "—");
            SleepTimelineView stv = new SleepTimelineView(requireContext());
            stv.setSleepData(history, todayStart);
            chartContainer.addView(stv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            return;
        }

        int days;
        if (currentRange == RANGE_WEEK) {
            days = 7;
        } else if (currentRange == RANGE_MONTH) {
            days = 30;
        } else {
            days = (int) ((todayStart - findEarliestTimestamp()) / 86400L) + 1;
            if (days < 1)
                days = 1;
        }
        long start = todayStart - (days - 1L) * 86400L;
        int[] minutes = SleepAnalyzer.minutesPerDay(history, start, days);

        List<BarEntry> entries = new ArrayList<>();
        int sum = 0, n = 0;
        for (int i = 0; i < days; i++) {
            entries.add(new BarEntry(i, minutes[i] / 60f));
            if (minutes[i] > 0) {
                sum += minutes[i];
                n++;
            }
        }
        int avg = n > 0 ? sum / n : 0;
        txtValue.setText(avg > 0 ? (avg / 60) + "h " + (avg % 60) + "m" : "—");

        BarDataSet ds = new BarDataSet(entries, "Data");
        ds.setColor(ContextCompat.getColor(requireContext(), R.color.accent_purple));
        ds.setDrawValues(false);
        chartContainer.addView(buildBarChart(ds), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private BarChart buildBarChart(BarDataSet ds) {
        BarData data = new BarData(ds);
        data.setBarWidth(0.7f);

        BarChart chart = new BarChart(requireContext());
        chart.setData(data);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.getXAxis().setDrawGridLines(false);
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        chart.getAxisLeft().setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisRight().setEnabled(false);
        chart.setTouchEnabled(false);
        chart.animateY(600);
        return chart;
    }

    private void shareMetric() {
        String label = metricKey.replace("_", " ");
        String range = getString(currentRange == RANGE_DAY ? R.string.share_range_today
                : currentRange == RANGE_WEEK ? R.string.share_range_week : R.string.share_range_month);
        String val = txtValue.getText() != null ? txtValue.getText().toString() : "—";
        String text = getString(R.string.share_metric_fmt, label + ": " + val, range);
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(android.content.Intent.EXTRA_TEXT, text);
        startActivity(android.content.Intent.createChooser(i, getString(R.string.share)));
    }

    private long getTodayStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis() / 1000;
    }

    private long findEarliestTimestamp() {
        long earliest = System.currentTimeMillis() / 1000;
        String history = prefs.getString(PREF_HEALTH_PREFIX + metricKey, "");
        if (!history.isEmpty()) {
            String firstEntry = history.split(",")[0];
            if (firstEntry.contains(":")) {
                try {
                    earliest = Long.parseLong(firstEntry.split(":")[0]);
                } catch (Exception ignored) {
                }
            }
        }
        return earliest;
    }
}
