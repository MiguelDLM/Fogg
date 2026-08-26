package com.example.dialsender.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.dialsender.MetricDetailActivity;
import com.example.dialsender.R;
import com.example.dialsender.WeatherDetailActivity;
import com.example.dialsender.ble.BleManager;
import com.example.dialsender.ble.SleepAnalyzer;
import com.example.dialsender.ble.WeatherSync;
import com.example.dialsender.theme.ThemeManager;
import com.example.dialsender.views.GaugeView;
import com.example.dialsender.views.SleepTimelineView;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Health Dashboard Tab (Estado) — Modular themed companion experience.
 * Displays daily activity hero card, health telemetry, and sleep stages.
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

        ThemeManager.AppTheme theme = ThemeManager.getTheme(requireContext());
        swipeRefreshHealth.setColorSchemeColors(theme.accentPrimary);
        swipeRefreshHealth.setProgressBackgroundColorSchemeColor(theme.bgCard);

        swipeRefreshHealth.setOnRefreshListener(() -> {
            render();
            BleManager ble = BleManager.getInstance(requireContext());
            WeatherSync.syncIfPossible(requireContext(), ble);
            if (ble.isSessionReady()) {
                ble.syncHealth();
                toast(getString(R.string.status_syncing));
            } else {
                long lastSync = prefs.getLong("last_sync_time", 0);
                if (lastSync > 0) {
                    String when = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                            .format(new Date(lastSync * 1000L));
                    toast(getString(R.string.status_not_connected_since, when));
                } else {
                    toast(getString(R.string.status_not_connected));
                }
            }
            swipeRefreshHealth.postDelayed(() -> {
                if (swipeRefreshHealth != null)
                    swipeRefreshHealth.setRefreshing(false);
            }, 1200);
        });

        return view;
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (key == null) return;
                // "goal_" matters as much as the samples do: the gauge draws
                // steps against the target, and the target moves both when the
                // user edits it and when the watch reports its own on connect.
                // Leaving it out was why the ring kept the old goal until the
                // screen was rebuilt.
                if (key.equals("weather_time") || key.equals("last_sync_time")
                        || key.startsWith("health_") || key.startsWith("girlcare_")
                        || key.startsWith("goal_")) {
                    if (isAdded()) {
                        requireActivity().runOnUiThread(this::render);
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

    // ===================== Day Dashboard =====================

    private void renderDay() {
        ThemeManager.AppTheme theme = ThemeManager.getTheme(requireContext());
        float density = getResources().getDisplayMetrics().density;
        long todayStart = todayStart();

        // 1. Top Greeting & Date Banner
        healthContainer.addView(buildGreetingHeader(theme, density));

        // 2. Activity Hero Card (Steps + Gauge + Weather)
        healthContainer.addView(buildHeroActivityCard(theme, density, todayStart));

        // 3. Mini Cards: Calories & Distance
        int cal = (int) latest(P + "calories", todayStart);
        float dist = latest(P + "distance", todayStart);
        LinearLayout calDistRow = new LinearLayout(requireContext());
        calDistRow.setOrientation(LinearLayout.HORIZONTAL);
        calDistRow.setLayoutParams(matchWrapMargin(dp(12)));

        calDistRow.addView(miniCard(
                getString(R.string.metric_calories),
                cal > 0 ? String.valueOf(cal) : "—",
                getString(R.string.unit_kcal),
                theme.accentCalories,
                R.drawable.ic_metric_calories,
                "calories",
                series(P + "calories", todayStart),
                todayStart,
                theme,
                density
        ));
        calDistRow.addView(spacer(dp(12)));
        calDistRow.addView(miniCard(
                getString(R.string.metric_distance),
                dist > 0 ? String.format(Locale.US, "%.2f", dist / 1000f) : "—",
                getString(R.string.unit_km),
                theme.accentDistance,
                R.drawable.ic_metric_distance,
                "distance",
                series(P + "distance", todayStart),
                todayStart,
                theme,
                density
        ));
        healthContainer.addView(calDistRow);

        // 4. Heart Rate Card (Line Chart)
        List<float[]> hr = series(P + "heart_rate", todayStart);
        int hrLatest = hr.isEmpty() ? 0 : (int) hr.get(hr.size() - 1)[1];
        addCard(
                getString(R.string.metric_heart_rate),
                hrLatest > 0 ? hrLatest + " " + getString(R.string.unit_bpm) : "—",
                theme.accentHeart,
                R.drawable.ic_metric_heart,
                "heart_rate",
                lastTime(hr),
                hr.size() >= 2 ? lineChart(hr, theme.accentHeart, todayStart, theme) : null,
                theme,
                density
        );

        // 5. Blood Pressure Card
        //
        // The reading is a series like the others, so the card shows when it
        // was taken and how it moved. Without those it looked frozen next to
        // the heart-rate card and read as "not syncing" even while the watch
        // was reporting fresh values.
        int[] bp = latestBp(todayStart);
        List<float[]> systolic = bpSeries(todayStart);
        addCard(
                getString(R.string.metric_blood_pressure),
                bp != null ? bp[0] + "/" + bp[1] + " " + getString(R.string.unit_mmhg) : "—",
                theme.accentBp,
                R.drawable.ic_metric_pulse,
                "blood_pressure",
                lastTime(systolic),
                systolic.size() >= 2 ? lineChart(systolic, theme.accentBp, todayStart, theme) : null,
                theme,
                density
        );

        // 6. SpO2 Card
        List<float[]> spo2s = series(P + "blood_oxygen", todayStart);
        int spo2 = spo2s.isEmpty() ? 0 : (int) spo2s.get(spo2s.size() - 1)[1];
        addCard(
                getString(R.string.metric_spo2),
                spo2 > 0 ? spo2 + " " + getString(R.string.unit_pct) : "—",
                theme.accentSpo2,
                R.drawable.ic_metric_spo2,
                "blood_oxygen",
                lastTime(spo2s),
                spo2s.size() >= 2 ? lineChart(spo2s, theme.accentSpo2, todayStart, theme) : null,
                theme,
                density
        );

        // 7. HRV Card (if data available)
        List<float[]> hrvSeries = series(P + "hrv", todayStart);
        int hrv = hrvSeries.isEmpty() ? 0 : (int) hrvSeries.get(hrvSeries.size() - 1)[1];
        if (hrv > 0) {
            addCard(
                    getString(R.string.metric_hrv),
                    hrv + " " + getString(R.string.unit_ms),
                    theme.accentPrimary,
                    R.drawable.ic_metric_heart,
                    "hrv",
                    lastTime(hrvSeries),
                    hrvSeries.size() >= 2 ? lineChart(hrvSeries, theme.accentPrimary, todayStart, theme) : null,
                    theme,
                    density
            );
        }

        // 8. Temperature Card (if data available)
        List<float[]> tempSeries = series(P + "temperature", todayStart);
        if (!tempSeries.isEmpty()) {
            float tempRaw = tempSeries.get(tempSeries.size() - 1)[1];
            String tempStr = String.format(Locale.US, "%.1f °C", tempRaw / 10.0f);
            addCard(
                    getString(R.string.metric_temperature),
                    tempStr,
                    theme.accentCalories,
                    R.drawable.ic_metric_heart,
                    "temperature",
                    lastTime(tempSeries),
                    null,
                    theme,
                    density
            );
        }

        // 9. Sleep Timeline Card
        String sleepRaw = prefs.getString(P + "sleep", "");
        SleepAnalyzer.SleepResult sr = SleepAnalyzer.analyzeDay(sleepRaw, todayStart);
        if (sr.totalMinutes > 0) {
            LinearLayout sleepContent = new LinearLayout(requireContext());
            sleepContent.setOrientation(LinearLayout.VERTICAL);

            SleepTimelineView tl = new SleepTimelineView(requireContext());
            tl.setSleepData(sleepRaw, todayStart);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(90));
            tl.setLayoutParams(tlp);
            sleepContent.addView(tl);
            sleepContent.addView(sleepLegend(sr, theme, density));

            addCard(
                    getString(R.string.metric_sleep),
                    getString(R.string.sleep_hours_min, sr.totalMinutes / 60, sr.totalMinutes % 60),
                    theme.accentSleep,
                    R.drawable.ic_metric_sleep,
                    "sleep",
                    null,
                    sleepContent,
                    theme,
                    density
            );
        } else {
            addCard(
                    getString(R.string.metric_sleep),
                    "—",
                    theme.accentSleep,
                    R.drawable.ic_metric_sleep,
                    "sleep",
                    null,
                    null,
                    theme,
                    density
            );
        }

        // 10. Stress Card (Hourly Bars)
        List<float[]> stressSeries = series(P + "stress", todayStart);
        int stress = stressSeries.isEmpty() ? 0 : (int) stressSeries.get(stressSeries.size() - 1)[1];
        addCard(
                getString(R.string.metric_stress),
                stress > 0 ? String.valueOf(stress) : "—",
                theme.accentStress,
                R.drawable.ic_metric_pulse,
                "stress",
                lastTime(stressSeries),
                stressSeries.size() >= 2 ? hourlyBars(stressSeries, todayStart, theme.accentStress, theme) : null,
                theme,
                density
        );

        // 11. Period Tracker Card (Female Health)
        boolean ptEnabled = com.example.dialsender.ble.PeriodTrackerManager.isEnabled(requireContext());
        com.example.dialsender.ble.PeriodTrackerManager.CycleStatus cs =
                com.example.dialsender.ble.PeriodTrackerManager.getCycleStatus(requireContext());
        String ptVal;
        String ptSub;
        if (ptEnabled) {
            int phaseRes;
            switch (cs.currentPhase) {
                case MENSTRUATION:
                    phaseRes = R.string.period_phase_menstruation;
                    break;
                case FERTILE_WINDOW:
                    phaseRes = R.string.period_phase_fertile;
                    break;
                case OVULATION_DAY:
                    phaseRes = R.string.period_phase_ovulation;
                    break;
                case LUTEAL_SAFE:
                    phaseRes = R.string.period_phase_luteal;
                    break;
                case FOLLICULAR_SAFE:
                default:
                    phaseRes = R.string.period_phase_follicular;
                    break;
            }
            ptVal = getString(R.string.period_day_in_cycle, cs.currentDayInCycle);
            ptSub = getString(phaseRes) + " · " + (cs.daysUntilNextPeriod == 0 ? getString(R.string.period_next_today) : getString(R.string.period_next_in, cs.daysUntilNextPeriod));
        } else {
            ptVal = getString(R.string.state_off);
            ptSub = getString(R.string.period_disabled_hint);
        }
        addCard(
                getString(R.string.period_card_title),
                ptVal,
                Color.parseColor("#F43F5E"),
                R.drawable.ic_female_care,
                "period_tracker",
                ptSub,
                null,
                theme,
                density
        );
    }

    // ===================== Header & Hero Builders =====================

    private View buildGreetingHeader(ThemeManager.AppTheme theme, float density) {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, (int) (14 * density));
        header.setLayoutParams(lp);

        // Greeting and formatted Date
        LinearLayout titleCol = new LinearLayout(requireContext());
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        String greeting = (hour >= 6 && hour < 12) ? getString(R.string.greeting_morning)
                : (hour >= 12 && hour < 20) ? getString(R.string.greeting_afternoon)
                : getString(R.string.greeting_evening);

        TextView txtGreeting = new TextView(requireContext());
        txtGreeting.setText(greeting);
        txtGreeting.setTextAppearance(theme.textCaption);
        txtGreeting.setTextColor(theme.textSecondary);
        titleCol.addView(txtGreeting);

        TextView txtDate = new TextView(requireContext());
        String dateFmt = getString(R.string.date_format_status);
        String dateStr = new SimpleDateFormat(dateFmt, Locale.getDefault()).format(new Date());
        // Capitalize first letter
        if (dateStr.length() > 0) {
            dateStr = Character.toUpperCase(dateStr.charAt(0)) + dateStr.substring(1);
        }
        txtDate.setText(dateStr);
        txtDate.setTextAppearance(theme.textScreenTitle);
        txtDate.setTextColor(theme.textPrimary);
        txtDate.setPadding(0, (int) (2 * density), 0, 0);
        titleCol.addView(txtDate);
        header.addView(titleCol);

        // Sync & Connection Status Pill
        long lastSync = prefs.getLong("last_sync_time", 0);
        BleManager ble = BleManager.getInstance(requireContext());
        boolean isConnected = ble.isSessionReady();

        TextView syncPill = new TextView(requireContext());
        syncPill.setTextAppearance(theme.textCaption);
        if (isConnected) {
            syncPill.setText(getString(R.string.status_pill_connected));
            syncPill.setTextColor(theme.success);
        } else if (lastSync > 0) {
            String when = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(lastSync * 1000L));
            syncPill.setText("⏱ " + when);
            syncPill.setTextColor(theme.textSecondary);
        } else {
            syncPill.setText(getString(R.string.status_pill_disconnected));
            syncPill.setTextColor(theme.textMuted);
        }
        syncPill.setPadding((int) (10 * density), (int) (5 * density), (int) (10 * density), (int) (5 * density));

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE);
        pillBg.setColor(ThemeManager.withAlpha(theme.bgCard, 220));
        pillBg.setCornerRadius(theme.radiusChip);
        pillBg.setStroke(Math.max(theme.stroke, 1), ThemeManager.withAlpha(theme.accentPrimary, 40));
        syncPill.setBackground(pillBg);

        header.addView(syncPill);
        return header;
    }

    private View buildHeroActivityCard(ThemeManager.AppTheme theme, float density, long todayStart) {
        FrameLayout heroCard = new FrameLayout(requireContext());
        heroCard.setLayoutParams(matchWrapMargin(dp(16)));

        // Elevated Gradient Card Background
        // Surface, radius and frame all follow the active design language, so
        // Onyx really loses its card here instead of just changing colour.
        android.graphics.drawable.Drawable bg = ThemeManager.createCardDrawable(theme);
        heroCard.setBackground(bg);
        heroCard.setPadding(theme.cardPadding, theme.cardPadding, theme.cardPadding, theme.cardPadding);

        LinearLayout contentCol = new LinearLayout(requireContext());
        contentCol.setOrientation(LinearLayout.VERTICAL);
        contentCol.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Gauge Container
        int steps = (int) latest(P + "steps", todayStart);
        int stepGoal = prefs.getInt("goal_steps", 10000);

        GaugeView gauge = new GaugeView(requireContext());
        gauge.setGaugeStyle(prefs.getString("gauge_style", GaugeView.STYLE_B));
        gauge.setArcColor(theme.accentSteps);
        gauge.setValue(stepGoal > 0 ? steps / (float) stepGoal : 0f);
        gauge.setValueText(String.valueOf(steps));
        gauge.setLabel(getString(R.string.metric_steps));
        gauge.setSubText(getString(R.string.status_goal, stepGoal));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (220 * density));
        gauge.setLayoutParams(glp);
        gauge.setClickable(true);
        gauge.setOnClickListener(v -> openDetail("steps"));
        contentCol.addView(gauge);

        // Progress percentage pill below gauge
        int pct = stepGoal > 0 ? (int) Math.min(999, (steps * 100f / stepGoal)) : 0;
        TextView progressPill = new TextView(requireContext());
        progressPill.setText(getString(R.string.status_progress_completed, pct));
        progressPill.setTextColor(theme.accentPrimary);
        progressPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        progressPill.setTypeface(null, Typeface.BOLD);
        progressPill.setGravity(Gravity.CENTER);
        progressPill.setPadding(0, (int) (4 * density), 0, (int) (6 * density));
        contentCol.addView(progressPill);

        heroCard.addView(contentCol);

        // Weather chip pinned top-right
        TextView weatherChip = new TextView(requireContext());
        long wTime = prefs.getLong("weather_time", 0);
        if (wTime > 0) {
            int temp = prefs.getInt("weather_temp", 0);
            String city = prefs.getString("weather_city", "");
            weatherChip.setText(temp + "°C" + (city.isEmpty() ? "" : " · " + city));
        } else {
            weatherChip.setText("--°C");
        }
        // Was a 🌤️ emoji, which rendered at a different size and colour on
        // every device font. A tinted vector matches the rest of the chips.
        android.graphics.drawable.Drawable wIcon = androidx.core.content.ContextCompat
                .getDrawable(requireContext(), R.drawable.ic_weather_cloud);
        if (wIcon != null) {
            wIcon = wIcon.mutate();
            wIcon.setBounds(0, 0, dp(16), dp(16));
            androidx.core.graphics.drawable.DrawableCompat.setTint(wIcon, theme.accentPrimary);
            weatherChip.setCompoundDrawablesRelative(wIcon, null, null, null);
            weatherChip.setCompoundDrawablePadding(dp(6));
        }
        weatherChip.setTextColor(theme.textPrimary);
        weatherChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(ThemeManager.withAlpha(theme.accentPrimary, 30));
        cbg.setCornerRadius(theme.radiusChip);
        cbg.setStroke(Math.max(theme.stroke, 1), ThemeManager.withAlpha(theme.accentPrimary, 60));
        weatherChip.setBackground(cbg);
        weatherChip.setPadding((int) (12 * density), (int) (6 * density), (int) (12 * density), (int) (6 * density));
        weatherChip.setClickable(true);
        weatherChip.setForeground(rippleForeground());
        weatherChip.setOnClickListener(v -> startActivity(new Intent(requireContext(), WeatherDetailActivity.class)));

        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.END | Gravity.TOP;
        weatherChip.setLayoutParams(wlp);
        heroCard.addView(weatherChip);

        return heroCard;
    }

    // ===================== Metric Cards =====================

    private void addCard(String title, String value, int color, int iconRes, @Nullable String metricKey,
                         @Nullable String subtitle, @Nullable View chart, ThemeManager.AppTheme theme, float density) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);

        android.graphics.drawable.Drawable bg = ThemeManager.createCardDrawable(theme);
        card.setBackground(bg);
        card.setPadding(theme.cardPadding, theme.cardPadding, theme.cardPadding, theme.cardPadding);
        card.setLayoutParams(matchWrapMargin(dp(12)));

        if (metricKey != null) {
            card.setClickable(true);
            card.setForeground(rippleForeground());
            card.setOnClickListener(v -> openDetail(metricKey));
        }

        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        // Icon inside squircle badge with soft alpha glow
        ImageView icon = new ImageView(requireContext());
        icon.setBackground(ThemeManager.createIconBadge(theme, color));
        int pad = dp(8);
        icon.setPadding(pad, pad, pad, pad);
        if (iconRes != 0)
            icon.setImageResource(iconRes);
        icon.setColorFilter(color);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams((int) (38 * density), (int) (38 * density));
        iconLp.setMargins(0, 0, (int) (14 * density), 0);
        icon.setLayoutParams(iconLp);
        header.addView(icon);

        LinearLayout titleCol = new LinearLayout(requireContext());
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setTextAppearance(theme.textCardTitle);
        t.setTextColor(theme.textPrimary);
        titleCol.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView st = new TextView(requireContext());
            st.setText(subtitle);
            st.setTextAppearance(theme.textCaption);
            st.setTextColor(theme.textSecondary);
            st.setPadding(0, (int) (2 * density), 0, 0);
            titleCol.addView(st);
        }
        header.addView(titleCol);

        TextView v = new TextView(requireContext());
        v.setText(value);
        v.setTextAppearance(theme.textMetricInline);
        v.setTextColor(theme.textPrimary);
        v.setMaxLines(1);
        header.addView(v);

        card.addView(header);

        if (chart != null) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (150 * density));
            cp.setMargins(0, (int) (10 * density), 0, 0);
            chart.setLayoutParams(cp);
            card.addView(chart);
        }
        healthContainer.addView(card);
    }

    private LinearLayout miniCard(String title, String value, String unit, int color,
                                  int iconRes, String metricKey, List<float[]> spark, long dayStart,
                                  ThemeManager.AppTheme theme, float density) {
        LinearLayout c = new LinearLayout(requireContext());
        c.setOrientation(LinearLayout.VERTICAL);

        android.graphics.drawable.Drawable bg = ThemeManager.createCardDrawable(theme);
        c.setBackground(bg);
        c.setPadding(theme.cardPadding, theme.cardPadding, theme.cardPadding, theme.cardPadding);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c.setLayoutParams(lp);

        if (metricKey != null) {
            c.setClickable(true);
            c.setForeground(rippleForeground());
            c.setOnClickListener(v -> openDetail(metricKey));
        }

        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView ic = new ImageView(requireContext());
        ic.setBackground(ThemeManager.createIconBadge(theme, color));
        int ip = dp(6);
        ic.setPadding(ip, ip, ip, ip);
        if (iconRes != 0)
            ic.setImageResource(iconRes);
        ic.setColorFilter(color);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams((int) (30 * density), (int) (30 * density));
        icLp.setMargins(0, 0, (int) (10 * density), 0);
        ic.setLayoutParams(icLp);
        titleRow.addView(ic);

        TextView tt = new TextView(requireContext());
        tt.setText(title);
        tt.setTextAppearance(theme.textCardTitle);
        tt.setTextColor(theme.textSecondary);
        titleRow.addView(tt);
        c.addView(titleRow);

        LinearLayout vrow = new LinearLayout(requireContext());
        vrow.setOrientation(LinearLayout.HORIZONTAL);
        vrow.setGravity(Gravity.BOTTOM);
        vrow.setPadding(0, (int) (6 * density), 0, 0);

        TextView vv = new TextView(requireContext());
        vv.setText(value);
        vv.setTextAppearance(theme.textMetricValue);
        vv.setTextColor(theme.textPrimary);
        vrow.addView(vv);

        TextView uu = new TextView(requireContext());
        uu.setText(" " + unit);
        uu.setTextAppearance(theme.textMetricUnit);
        uu.setTextColor(theme.textSecondary);
        uu.setPadding(0, 0, 0, (int) (3 * density));
        vrow.addView(uu);
        c.addView(vrow);

        if (spark != null && spark.size() >= 2) {
            LineChart sl = lineChart(spark, color, dayStart, theme);
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

    private View sleepLegend(SleepAnalyzer.SleepResult sr, ThemeManager.AppTheme theme, float density) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        addLegendItem(row, getString(R.string.sleep_deep), 0xFF3F51B5, sr.deepMin, theme, density);
        addLegendItem(row, getString(R.string.sleep_light), theme.accentPrimary, sr.lightMin, theme, density);
        addLegendItem(row, getString(R.string.sleep_rem), 0xFF9C27B0, sr.remMin, theme, density);
        addLegendItem(row, getString(R.string.sleep_awake), theme.textMuted, sr.awakeMin, theme, density);
        return row;
    }

    private void addLegendItem(LinearLayout row, String name, int color, int minutes,
                               ThemeManager.AppTheme theme, float density) {
        LinearLayout item = new LinearLayout(requireContext());
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View dot = new View(requireContext());
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) (8 * density), (int) (8 * density));
        dl.setMargins(0, 0, (int) (6 * density), 0);
        dot.setLayoutParams(dl);
        item.addView(dot);

        TextView t = new TextView(requireContext());
        t.setText(name + "\n" + (minutes / 60) + "h " + (minutes % 60) + "m");
        t.setTextColor(theme.textSecondary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        item.addView(t);
        row.addView(item);
    }

    // ===================== Chart Generators =====================

    private LineChart lineChart(List<float[]> data, int color, long dayStart, ThemeManager.AppTheme theme) {
        LineChart chart = new LineChart(requireContext());
        List<Entry> entries = new ArrayList<>();
        for (float[] e : data) {
            float hour = (e[0] - dayStart) / 3600f;
            entries.add(new Entry(hour, e[1]));
        }
        Collections.sort(entries, (a, b) -> Float.compare(a.getX(), b.getX()));
        if (entries.isEmpty())
            entries.add(new Entry(0, 0));

        LineDataSet ds = new LineDataSet(entries, "");
        ds.setColor(color);
        ds.setDrawCircles(false);
        ds.setLineWidth(2.2f);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.LINEAR);
        ds.setDrawFilled(true);
        ds.setFillColor(color);
        ds.setFillAlpha(45);
        chart.setData(new LineData(ds));
        styleChart(chart, theme);
        chart.getXAxis().setAxisMinimum(0f);
        chart.getXAxis().setAxisMaximum(24f);
        return chart;
    }

    private BarChart hourlyBars(List<float[]> data, long dayStart, int color, ThemeManager.AppTheme theme) {
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
        bd.setBarWidth(0.55f);
        chart.setData(bd);
        styleChart(chart, theme);
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(23.5f);
        return chart;
    }

    private void styleChart(com.github.mikephil.charting.charts.BarLineChartBase<?> chart, ThemeManager.AppTheme theme) {
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setTouchEnabled(false);

        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextColor(theme.textSecondary);
        x.setTextSize(9f);

        chart.getAxisLeft().setTextColor(theme.textSecondary);
        chart.getAxisLeft().setTextSize(9f);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setDrawGridLines(true);
        chart.getAxisLeft().setGridColor(0x18FFFFFF);
        chart.getAxisRight().setEnabled(false);
        chart.animateY(500);
    }

    // ===================== Navigation & Data Parsing =====================

    private void openDetail(String metricKey) {
        if ("period_tracker".equals(metricKey)) {
            startActivity(new Intent(requireContext(), com.example.dialsender.PeriodTrackerActivity.class));
            return;
        }
        Intent i = new Intent(requireContext(), MetricDetailActivity.class);
        i.putExtra(MetricDetailActivity.EXTRA_METRIC, metricKey);
        startActivity(i);
    }

    private android.graphics.drawable.Drawable rippleForeground() {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        return ContextCompat.getDrawable(requireContext(), tv.resourceId);
    }

    private String lastTime(List<float[]> s) {
        if (s == null || s.isEmpty())
            return "";
        long ts = (long) s.get(s.size() - 1)[0];
        return new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(ts * 1000L));
    }

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
                    out.add(new float[]{ts, val});
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private float latest(String key, long start) {
        List<float[]> s = series(key, start);
        float max = 0;
        for (float[] e : s)
            max = Math.max(max, e[1]);
        if (key.endsWith("heart_rate") || key.endsWith("blood_oxygen") || key.endsWith("stress")) {
            return s.isEmpty() ? 0 : s.get(s.size() - 1)[1];
        }
        return max;
    }

    /** Systolic readings as a plain series, for the card's time and trend. */
    private List<float[]> bpSeries(long start) {
        List<float[]> out = new ArrayList<>();
        String h = prefs.getString(P + "blood_pressure", "");
        if (h.isEmpty())
            return out;
        long now = System.currentTimeMillis() / 1000 + 86400;
        for (String e : h.split(",")) {
            String[] parts = e.split(":");
            if (parts.length < 2 || !parts[1].contains("/"))
                continue;
            try {
                long ts = Long.parseLong(parts[0]);
                float sys = Float.parseFloat(parts[1].split("/")[0]);
                if (ts >= start && ts <= now)
                    out.add(new float[]{ts, sys});
            } catch (Exception ignored) {
            }
        }
        return out;
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
                    return new int[]{Integer.parseInt(sd[0]), Integer.parseInt(sd[1])};
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private LinearLayout.LayoutParams matchWrapMargin(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, bottom);
        return lp;
    }

    private View spacer(int widthDp) {
        View v = new View(requireContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(widthDp, 1));
        return v;
    }

    private void toast(String s) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }

    private long todayStart() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
