package com.example.dialsender.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.dialsender.R;
import com.example.dialsender.ble.BleManager;
import com.example.dialsender.ble.SleepAnalyzer;
import com.example.dialsender.views.GaugeView;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Estado tab — health dashboard styled after Co-Fit.
 * Always shows the day view. Pull down to sync health data from the watch.
 */
public class StatusFragment extends Fragment {
    private static final String PREF_NAME = "dial_sender_prefs";
    private static final String P = "health_";

    private LinearLayout healthContainer;
    private SwipeRefreshLayout swipeRefreshHealth;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_status, container, false);
        prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        healthContainer = view.findViewById(R.id.healthContainer);
        swipeRefreshHealth = view.findViewById(R.id.swipeRefreshHealth);

        View btnBack = view.findViewById(R.id.btnBackHealth);
        if (btnBack != null)
            btnBack.setVisibility(View.GONE);

        // Pull-down gesture replaces the old sync button
        swipeRefreshHealth.setColorSchemeColors(0xFF22D3EE);
        swipeRefreshHealth.setProgressBackgroundColorSchemeColor(0xFF1A2027);
        swipeRefreshHealth.setOnRefreshListener(() -> {
            render();
            BleManager ble = BleManager.getInstance(requireContext());
            // Weather refreshes regardless of the watch connection
            com.example.dialsender.ble.WeatherSync.syncIfPossible(requireContext(), ble);
            if (ble.isSessionReady()) {
                ble.syncHealth();
                toast(getString(R.string.status_syncing));
            } else {
                long lastSync = prefs.getLong("last_sync_time", 0);
                if (lastSync > 0) {
                    String when = new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(lastSync * 1000L));
                    toast(getString(R.string.status_not_connected_since, when));
                } else {
                    toast(getString(R.string.status_not_connected));
                }
            }
            swipeRefreshHealth.postDelayed(() -> swipeRefreshHealth.setRefreshing(false), 1200);
        });

        return view;
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key == null) return;
            if (key.equals("weather_time") || key.equals("last_sync_time") || key.startsWith("health_")) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> render());
                }
            }
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
    }

    private void render() {
        if (healthContainer == null || !isAdded())
            return;
        healthContainer.removeAllViews();
        renderDay();
    }

    // ===================== Day dashboard =====================

    private void renderDay() {
        long todayStart = todayStart();

        // --- Last sync time banner ---
        long lastSync = prefs.getLong("last_sync_time", 0);
        TextView syncBanner = new TextView(requireContext());
        if (lastSync > 0) {
            String when = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(lastSync * 1000L));
            syncBanner.setText(getString(R.string.status_last_sync, when));
        } else {
            syncBanner.setText(getString(R.string.status_no_sync));
        }
        syncBanner.setTextColor(0xFF6B7280);
        syncBanner.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        syncBanner.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerLp.setMargins(0, 0, 0, dp(10));
        syncBanner.setLayoutParams(bannerLp);
        healthContainer.addView(syncBanner);

        // --- Steps gauge with weather chip pinned top-right (Co-Fit layout) ---
        android.widget.FrameLayout gaugeWrap = new android.widget.FrameLayout(requireContext());
        LinearLayout.LayoutParams gwlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
        gwlp.setMargins(0, 0, 0, dp(14));
        gaugeWrap.setLayoutParams(gwlp);

        int steps = (int) latest(P + "steps", todayStart);
        int stepGoal = prefs.getInt("goal_steps", 10000);
        GaugeView gauge = new GaugeView(requireContext());
        gauge.setGaugeStyle(prefs.getString("gauge_style", GaugeView.STYLE_B));
        gauge.setArcColor(0xFFFF9800);
        gauge.setValue(stepGoal > 0 ? steps / (float) stepGoal : 0f);
        gauge.setValueText(String.valueOf(steps));
        gauge.setLabel(getString(R.string.metric_steps));
        gauge.setSubText(getString(R.string.status_goal, stepGoal));
        gauge.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gauge.setClickable(true);
        gauge.setOnClickListener(v -> openDetail("steps"));
        gaugeWrap.addView(gauge);

        // Always show the weather chip
        TextView chip = new TextView(requireContext());
        long wTime = prefs.getLong("weather_time", 0);
        if (wTime > 0) {
            int temp = prefs.getInt("weather_temp", 0);
            String city = prefs.getString("weather_city", "");
            chip.setText("🌤️ " + temp + "°C" + (city.isEmpty() ? "" : "  " + city));
        } else {
            chip.setText("🌤️ --°C");
        }
        chip.setTextColor(0xFFC9D1D9);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setColor(0x33FFFFFF);
        cbg.setCornerRadius(dp(20));
        chip.setBackground(cbg);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        chip.setClickable(true);
        chip.setForeground(rippleForeground());
        chip.setOnClickListener(v -> startActivity(new android.content.Intent(
                requireContext(), com.example.dialsender.WeatherDetailActivity.class)));
        android.widget.FrameLayout.LayoutParams clp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.END | Gravity.TOP;
        chip.setLayoutParams(clp);
        gaugeWrap.addView(chip);

        healthContainer.addView(gaugeWrap);

        // --- Calories + Distance row (with sparklines, like Co-Fit) ---
        int cal = (int) latest(P + "calories", todayStart);
        float dist = latest(P + "distance", todayStart);
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(matchWrapMargin(dp(12)));
        row.addView(miniCard(getString(R.string.metric_calories), cal > 0 ? String.valueOf(cal) : "—",
                getString(R.string.unit_kcal), 0xFFE5552E,
                R.drawable.ic_metric_calories, "calories", series(P + "calories", todayStart), todayStart));
        row.addView(spacer());
        row.addView(miniCard(getString(R.string.metric_distance),
                dist > 0 ? String.format(java.util.Locale.US, "%.2f", dist / 1000f) : "—",
                getString(R.string.unit_km), 0xFF34C759, R.drawable.ic_metric_distance, "distance",
                series(P + "distance", todayStart), todayStart));
        healthContainer.addView(row);

        // --- Heart rate line chart (only with enough points) ---
        List<float[]> hr = series(P + "heart_rate", todayStart);
        int hrLatest = hr.isEmpty() ? 0 : (int) hr.get(hr.size() - 1)[1];
        addCard(getString(R.string.metric_heart_rate),
                hrLatest > 0 ? hrLatest + " " + getString(R.string.unit_bpm) : "—", 0xFFE5552E,
                R.drawable.ic_metric_heart, "heart_rate", lastTime(hr),
                hr.size() >= 2 ? lineChart(hr, 0xFFE5552E, todayStart) : null);

        // --- Blood pressure ---
        int[] bp = latestBp(todayStart);
        addCard(getString(R.string.metric_blood_pressure),
                bp != null ? bp[0] + "/" + bp[1] + " " + getString(R.string.unit_mmhg) : "—",
                0xFFEF5350, R.drawable.ic_metric_pulse, "blood_pressure", null, null);

        // --- SpO2 ---
        List<float[]> spo2s = series(P + "blood_oxygen", todayStart);
        int spo2 = spo2s.isEmpty() ? 0 : (int) spo2s.get(spo2s.size() - 1)[1];
        addCard(getString(R.string.metric_spo2),
                spo2 > 0 ? spo2 + " " + getString(R.string.unit_pct) : "—", 0xFF42A5F5,
                R.drawable.ic_metric_spo2, "blood_oxygen", lastTime(spo2s), null);

        // --- HRV ---
        List<float[]> hrvSeries = series(P + "hrv", todayStart);
        int hrv = hrvSeries.isEmpty() ? 0 : (int) hrvSeries.get(hrvSeries.size() - 1)[1];
        if (hrv > 0) {
            addCard(getString(R.string.metric_hrv),
                    hrv + " " + getString(R.string.unit_ms), 0xFF06B6D4,
                    R.drawable.ic_metric_heart, "hrv", lastTime(hrvSeries),
                    hrvSeries.size() >= 2 ? lineChart(hrvSeries, 0xFF06B6D4, todayStart) : null);
        }

        // --- Temperature ---
        List<float[]> tempSeries = series(P + "temperature", todayStart);
        if (!tempSeries.isEmpty()) {
            float tempRaw = tempSeries.get(tempSeries.size() - 1)[1];
            String tempStr = String.format(java.util.Locale.US, "%.1f °C", tempRaw / 10.0f);
            addCard(getString(R.string.metric_temperature), tempStr, 0xFFFF7043,
                    R.drawable.ic_metric_heart, "temperature", lastTime(tempSeries), null);
        }

        // --- Sleep timeline by stages ---
        String sleepRaw = prefs.getString(P + "sleep", "");
        // Only the session(s) you woke up from on this day — analyze() would
        // return the last session in the whole history and paint a stale night
        // as if it were today's.
        SleepAnalyzer.SleepResult sr = SleepAnalyzer.analyzeDay(sleepRaw, todayStart);
        if (sr.totalMinutes > 0) {
            LinearLayout sleepContent = new LinearLayout(requireContext());
            sleepContent.setOrientation(LinearLayout.VERTICAL);
            com.example.dialsender.views.SleepTimelineView tl =
                    new com.example.dialsender.views.SleepTimelineView(requireContext());
            tl.setSleepData(sleepRaw, todayStart);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(90));
            tl.setLayoutParams(tlp);
            sleepContent.addView(tl);
            sleepContent.addView(sleepLegend(sr));
            addCard(getString(R.string.metric_sleep),
                    getString(R.string.sleep_hours_min, sr.totalMinutes / 60, sr.totalMinutes % 60),
                    0xFF7E57C2, R.drawable.ic_metric_sleep, "sleep", null, sleepContent);
        } else {
            addCard(getString(R.string.metric_sleep), "—", 0xFF7E57C2,
                    R.drawable.ic_metric_sleep, "sleep", null, null);
        }

        // --- Stress (hourly bars) ---
        List<float[]> stressSeries = series(P + "stress", todayStart);
        int stress = stressSeries.isEmpty() ? 0 : (int) stressSeries.get(stressSeries.size() - 1)[1];
        addCard(getString(R.string.metric_stress), stress > 0 ? String.valueOf(stress) : "—",
                0xFF34C759, R.drawable.ic_metric_pulse, "stress", lastTime(stressSeries),
                stressSeries.size() >= 2 ? hourlyBars(stressSeries, todayStart, 0xFF34C759) : null);
    }

    /** Small color-keyed legend with per-stage minutes for the sleep card. */
    private View sleepLegend(SleepAnalyzer.SleepResult sr) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        addLegendItem(row, getString(R.string.sleep_deep), 0xFF3F51B5, sr.deepMin);
        addLegendItem(row, getString(R.string.sleep_light), 0xFF22D3EE, sr.lightMin);
        addLegendItem(row, getString(R.string.sleep_rem), 0xFF9C27B0, sr.remMin);
        addLegendItem(row, getString(R.string.sleep_awake), 0xFF6B7280, sr.awakeMin);
        return row;
    }

    private void addLegendItem(LinearLayout row, String name, int color, int minutes) {
        LinearLayout item = new LinearLayout(requireContext());
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View dot = new View(requireContext());
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(8), dp(8));
        dl.setMargins(0, 0, dp(5), 0);
        dot.setLayoutParams(dl);
        item.addView(dot);
        TextView t = new TextView(requireContext());
        t.setText(name + "\n" + (minutes / 60) + "h " + (minutes % 60) + "m");
        t.setTextColor(0xFF9AA4B2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        item.addView(t);
        row.addView(item);
    }

    private void openDetail(String metricKey) {
        android.content.Intent i = new android.content.Intent(requireContext(),
                com.example.dialsender.MetricDetailActivity.class);
        i.putExtra(com.example.dialsender.MetricDetailActivity.EXTRA_METRIC, metricKey);
        startActivity(i);
    }

    private android.graphics.drawable.Drawable rippleForeground() {
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        return ContextCompat.getDrawable(requireContext(), tv.resourceId);
    }

    /** "HH:mm" of the most recent sample, or "" if none. */
    private String lastTime(List<float[]> s) {
        if (s == null || s.isEmpty())
            return "";
        long ts = (long) s.get(s.size() - 1)[0];
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(ts * 1000L));
    }

    // ===================== Card / chart builders =====================

    private void addCard(String title, String value, int color, int iconRes, @Nullable String metricKey,
            @Nullable String subtitle, @Nullable View chart) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1A2027);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setLayoutParams(matchWrapMargin(dp(12)));
        if (metricKey != null) {
            card.setClickable(true);
            card.setForeground(rippleForeground());
            card.setOnClickListener(v -> openDetail(metricKey));
        }

        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        // Leading icon on a colored circle (Co-Fit style)
        android.widget.ImageView icon = new android.widget.ImageView(requireContext());
        android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
        iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        iconBg.setColor(color);
        icon.setBackground(iconBg);
        int pad = dp(6);
        icon.setPadding(pad, pad, pad, pad);
        if (iconRes != 0)
            icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        iconLp.setMargins(0, 0, dp(12), 0);
        icon.setLayoutParams(iconLp);
        header.addView(icon);

        LinearLayout titleCol = new LinearLayout(requireContext());
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setTextColor(0xFFC9D1D9);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleCol.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView st = new TextView(requireContext());
            st.setText(subtitle);
            st.setTextColor(0xFF6B7280);
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            titleCol.addView(st);
        }
        header.addView(titleCol);

        TextView v = new TextView(requireContext());
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        v.setTypeface(null, Typeface.BOLD);
        header.addView(v);
        card.addView(header);

        if (chart != null) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
            cp.setMargins(0, dp(8), 0, 0);
            chart.setLayoutParams(cp);
            card.addView(chart);
        }
        healthContainer.addView(card);
    }

    private LinearLayout miniCard(String title, String value, String unit, int color,
            int iconRes, String metricKey, List<float[]> spark, long dayStart) {
        LinearLayout c = new LinearLayout(requireContext());
        c.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1A2027);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c.setLayoutParams(lp);
        if (metricKey != null) {
            c.setClickable(true);
            c.setForeground(rippleForeground());
            c.setOnClickListener(v -> openDetail(metricKey));
        }

        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView ic = new android.widget.ImageView(requireContext());
        android.graphics.drawable.GradientDrawable icBg = new android.graphics.drawable.GradientDrawable();
        icBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        icBg.setColor(color);
        ic.setBackground(icBg);
        int ip = dp(5);
        ic.setPadding(ip, ip, ip, ip);
        if (iconRes != 0)
            ic.setImageResource(iconRes);
        ic.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(26), dp(26));
        icLp.setMargins(0, 0, dp(8), 0);
        ic.setLayoutParams(icLp);
        titleRow.addView(ic);
        TextView tt = new TextView(requireContext());
        tt.setText(title);
        tt.setTextColor(0xFF9AA4B2);
        tt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleRow.addView(tt);
        c.addView(titleRow);

        LinearLayout vrow = new LinearLayout(requireContext());
        vrow.setOrientation(LinearLayout.HORIZONTAL);
        vrow.setGravity(Gravity.BOTTOM);
        vrow.setPadding(0, dp(4), 0, 0);
        TextView vv = new TextView(requireContext());
        vv.setText(value);
        vv.setTextColor(color);
        vv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        vv.setTypeface(null, Typeface.BOLD);
        vrow.addView(vv);
        TextView uu = new TextView(requireContext());
        uu.setText(" " + unit);
        uu.setTextColor(0xFF6B7280);
        uu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        uu.setPadding(0, 0, 0, dp(3));
        vrow.addView(uu);
        c.addView(vrow);

        if (spark != null && spark.size() >= 2) {
            LineChart sl = lineChart(spark, color, dayStart);
            sl.getXAxis().setEnabled(false);
            sl.getAxisLeft().setEnabled(false);
            sl.setViewPortOffsets(0, dp(4), 0, 0);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            slp.setMargins(0, dp(6), 0, 0);
            sl.setLayoutParams(slp);
            c.addView(sl);
        }
        return c;
    }

    /** Vertical bars per hour of the day for a series (e.g. stress). */
    private BarChart hourlyBars(List<float[]> data, long dayStart, int color) {
        float[] buckets = new float[24];
        for (float[] e : data) {
            int hr = (int) ((e[0] - dayStart) / 3600L);
            if (hr >= 0 && hr < 24)
                buckets[hr] = Math.max(buckets[hr], e[1]);
        }
        List<BarEntry> entries = new ArrayList<>();
        for (int i = 0; i < 24; i++)
            entries.add(new BarEntry(i, buckets[i]));
        BarChart chart = new BarChart(requireContext());
        BarDataSet ds = new BarDataSet(entries, "");
        ds.setColor(color);
        ds.setDrawValues(false);
        BarData bd = new BarData(ds);
        bd.setBarWidth(0.5f);
        chart.setData(bd);
        styleChart(chart);
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(23.5f);
        return chart;
    }

    /**
     * Line chart for a day series.
     * Entries are sorted by X (hours since midnight) so MPAndroidChart never
     * draws a line backwards to an earlier timestamp — that was the "crossing"
     * bug. We also use LINEAR mode (not CUBIC_BEZIER) to avoid overshoot loops.
     */
    private LineChart lineChart(List<float[]> data, int color, long dayStart) {
        LineChart chart = new LineChart(requireContext());
        List<Entry> entries = new ArrayList<>();
        for (float[] e : data) {
            float hour = (e[0] - dayStart) / 3600f;
            entries.add(new Entry(hour, e[1]));
        }
        // Sort by X — critical: MPAndroidChart connects points in list order,
        // so unsorted data causes the last segment to jump back to an old X.
        java.util.Collections.sort(entries, (a, b) -> Float.compare(a.getX(), b.getX()));
        if (entries.isEmpty())
            entries.add(new Entry(0, 0));
        LineDataSet ds = new LineDataSet(entries, "");
        ds.setColor(color);
        ds.setDrawCircles(false);
        ds.setLineWidth(2f);
        ds.setDrawValues(false);
        // LINEAR avoids the Bezier overshoot that can look like a crossing line
        ds.setMode(LineDataSet.Mode.LINEAR);
        ds.setDrawFilled(true);
        ds.setFillColor(color);
        ds.setFillAlpha(60);
        chart.setData(new LineData(ds));
        styleChart(chart);
        chart.getXAxis().setAxisMinimum(0f);
        chart.getXAxis().setAxisMaximum(24f);
        return chart;
    }

    private void styleChart(com.github.mikephil.charting.charts.BarLineChartBase<?> chart) {
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setTouchEnabled(false);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextColor(0xFF6B7280);
        x.setTextSize(9f);
        chart.getAxisLeft().setTextColor(0xFF6B7280);
        chart.getAxisLeft().setTextSize(9f);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setDrawGridLines(true);
        chart.getAxisLeft().setGridColor(0x22FFFFFF);
        chart.getAxisRight().setEnabled(false);
        chart.animateY(600);
    }

    // ===================== Data parsing =====================

    /** Parse "ts:val,ts:val" within [start, now+1d] -> list of {ts, val}. */
    private List<float[]> series(String key, long start) {
        List<float[]> out = new ArrayList<>();
        String h = prefs.getString(key, "");
        if (h.isEmpty())
            return out;
        long now = System.currentTimeMillis() / 1000 + 86400;
        for (String e : h.split(",")) {
            String[] parts = e.split(":");
            if (parts.length < 2)
                continue;
            try {
                long ts = Long.parseLong(parts[0]);
                float val = Float.parseFloat(parts[1]);
                if (ts >= start && ts <= now)
                    out.add(new float[] { ts, val });
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private float latest(String key, long start) {
        List<float[]> s = series(key, start);
        float max = 0;
        for (float[] e : s)
            max = Math.max(max, e[1]); // step/cal/dist are cumulative -> max = today's total
        // For non-cumulative metrics the last value is more meaningful:
        if (key.endsWith("heart_rate") || key.endsWith("blood_oxygen") || key.endsWith("stress")) {
            return s.isEmpty() ? 0 : s.get(s.size() - 1)[1];
        }
        return max;
    }

    private int[] latestBp(long start) {
        String h = prefs.getString(P + "blood_pressure", "");
        if (h.isEmpty())
            return null;
        String[] arr = h.split(",");
        for (int i = arr.length - 1; i >= 0; i--) {
            String[] parts = arr[i].split(":");
            if (parts.length >= 2 && parts[1].contains("/")) {
                try {
                    long ts = Long.parseLong(parts[0]);
                    if (ts < start)
                        continue;
                    String[] sd = parts[1].split("/");
                    return new int[] { Integer.parseInt(sd[0]), Integer.parseInt(sd[1]) };
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    // ===================== Helpers =====================

    private LinearLayout.LayoutParams matchWrapMargin(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, bottom);
        return lp;
    }

    private View spacer() {
        View v = new View(requireContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 1));
        return v;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private long todayStart() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
