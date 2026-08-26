package com.example.dialsender;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.dialsender.ble.BleManager;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.BarLineChartBase;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen detail for a single health metric, styled after Co-Fit:
 * Día/Semana/Mes tabs, a big current value with icon, a line/bar chart and a
 * máx/media/mín summary, plus share.
 */
public class MetricDetailActivity extends AppCompatActivity {
    /** Active theme, resolved once so every builder below can read its tokens. */
    private com.example.dialsender.theme.ThemeManager.AppTheme theme;


    public static final String EXTRA_METRIC = "metric";

    private static final String PREF = "dial_sender_prefs";
    private static final String P = "health_";
    private static final int DAY = 0, WEEK = 1, MONTH = 2;

    private String metric;
    private int range = DAY;
    private long selDayStart; // start-of-day for the day currently shown
    private SharedPreferences prefs;

    private LinearLayout content;   // value header + chart + stats container
    private TextView[] tabs = new TextView[3];

    // --- metric metadata ---
    private String title, unit, desc;
    private int color, iconRes;
    private boolean cumulative; // steps/calories/distance accumulate during the day
    private boolean isBp;

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    protected void onCreate(@Nullable Bundle savedInstanceState) {
        com.example.dialsender.theme.ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        metric = getIntent().getStringExtra(EXTRA_METRIC);
        if (metric == null)
            metric = "heart_rate";
        selDayStart = todayStart();
        configMeta();

        theme = com.example.dialsender.theme.ThemeManager.getTheme(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(theme.bgPrimary);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(buildHeader());
        root.addView(buildTabs());
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(24));
        root.addView(content);

        scroll.addView(root);
        setContentView(scroll);
        render();
    }

    private void configMeta() {
        theme = com.example.dialsender.theme.ThemeManager.getTheme(this);
        switch (metric) {
            case "steps":
                title = getString(R.string.metric_steps); unit = ""; color = theme.accentSteps; iconRes = R.drawable.ic_metric_steps;
                cumulative = true;
                desc = getString(R.string.metric_desc_steps);
                break;
            case "calories":
                title = getString(R.string.metric_calories); unit = getString(R.string.unit_kcal); color = theme.accentCalories; iconRes = R.drawable.ic_metric_calories;
                cumulative = true;
                desc = getString(R.string.metric_desc_calories);
                break;
            case "distance":
                title = getString(R.string.metric_distance); unit = getString(R.string.unit_km); color = theme.accentDistance; iconRes = R.drawable.ic_metric_distance;
                cumulative = true;
                desc = getString(R.string.metric_desc_distance);
                break;
            case "blood_oxygen":
                title = getString(R.string.metric_spo2); unit = getString(R.string.unit_pct); color = theme.accentSpo2; iconRes = R.drawable.ic_metric_spo2;
                desc = getString(R.string.metric_desc_blood_oxygen);
                break;
            case "stress":
                title = getString(R.string.metric_stress); unit = ""; color = theme.accentStress; iconRes = R.drawable.ic_metric_pulse;
                desc = getString(R.string.metric_desc_stress);
                break;
            case "blood_pressure":
                title = getString(R.string.metric_blood_pressure); unit = getString(R.string.unit_mmhg); color = theme.accentBp; iconRes = R.drawable.ic_metric_pulse;
                isBp = true;
                desc = getString(R.string.metric_desc_blood_pressure);
                break;
            case "sleep":
                title = getString(R.string.metric_sleep); unit = "h"; color = theme.accentSleep; iconRes = R.drawable.ic_metric_sleep;
                desc = getString(R.string.metric_desc_sleep);
                break;
            default:
                title = getString(R.string.metric_heart_rate); unit = getString(R.string.unit_bpm); color = theme.accentHeart;
                iconRes = R.drawable.ic_metric_heart;
                desc = getString(R.string.metric_desc_heart_rate);
                break;
        }
    }

    // ============ Header / tabs ============

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(8), dp(14), dp(12), dp(8));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(theme.textPrimary);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setOnClickListener(v -> finish());
        h.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView t = new TextView(this);
        t.setTextAppearance(theme.textScreenTitle);
        t.setText(title);
        t.setTextColor(theme.textPrimary);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        h.addView(t);

        ImageView cal = new ImageView(this);
        cal.setImageResource(R.drawable.ic_calendar);
        cal.setColorFilter(theme.textPrimary);
        cal.setPadding(dp(12), dp(12), dp(12), dp(12));
        cal.setOnClickListener(v -> pickDate());
        h.addView(cal, new LinearLayout.LayoutParams(dp(48), dp(48)));

        ImageView share = new ImageView(this);
        share.setImageResource(R.drawable.ic_share);
        share.setColorFilter(theme.textPrimary);
        share.setPadding(dp(12), dp(12), dp(12), dp(12));
        share.setOnClickListener(v -> share());
        h.addView(share, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return h;
    }

    private View buildTabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        String[] names = { getString(R.string.tab_day), getString(R.string.tab_week), getString(R.string.tab_month) };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView tab = new TextView(this);
            tab.setText(names[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tab.setPadding(0, dp(12), 0, dp(12));
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tab.setOnClickListener(v -> {
                range = idx;
                updateTabs();
                render();
            });
            tabs[i] = tab;
            row.addView(tab);
        }
        updateTabs();
        return row;
    }

    private void updateTabs() {
        for (int i = 0; i < 3; i++) {
            boolean sel = i == range;
            tabs[i].setTextColor(sel ? theme.accentPrimary : theme.textSecondary);
            tabs[i].setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ============ Render ============

    private void render() {
        content.removeAllViews();

        // Shown in every range: it is the anchor the week/month window ends on,
        // so hiding it left the user unable to see or move it.
        content.addView(buildDayNav());

        if (isBp) {
            renderBp(selDayStart);
            content.addView(descriptionCard());
            content.addView(warningCard(R.string.bp_warning_title, R.string.bp_warning_body));
            return;
        }

        // ─── Special case: sleep ───────────────────────────────────────────
        if ("sleep".equals(metric)) {
            renderSleep();
            content.addView(descriptionCard());
            return;
        }
        // ──────────────────────────────────────────────────────────────────

        if (range == DAY) {
            List<float[]> s = seriesRange(metric, selDayStart, selDayStart + 86400);
            float latest = s.isEmpty() ? 0 : s.get(s.size() - 1)[1];
            float disp = "distance".equals(metric) ? latest / 1000f : latest;
            String when = s.isEmpty() ? getString(R.string.no_data) : new SimpleDateFormat("HH:mm", Locale.US)
                    .format(new Date((long) s.get(s.size() - 1)[0] * 1000L));
            content.addView(valueHeader(fmt(disp), when));
            if (cumulative) {
                content.addView(hourlyBarChart(s, selDayStart));
            } else {
                content.addView(lineChartDay(s, selDayStart));
                content.addView(statsRow(s));
            }
        } else {
            // Anchored on the day chosen in the picker, not always on today —
            // picking a past date and switching to Semana used to snap back.
            int days = range == WEEK ? 7 : 30;
            long start = selDayStart - (days - 1) * 86400L;
            float[][] agg = aggregateByDay(metric, start, days); // [sum/last, avg, count]
            float shown = cumulative ? agg[0][days - 1] : lastNonZeroAvg(agg, days);
            float disp = "distance".equals(metric) ? shown / 1000f : shown;
            content.addView(valueHeader(fmt(disp),
                    range == WEEK ? getString(R.string.last_7_days) : getString(R.string.last_30_days)));
            content.addView(dailyBarChart(agg, days, start));
            if (!cumulative)
                content.addView(statsRowDaily(agg, days));
        }

        content.addView(descriptionCard());
        if ("blood_oxygen".equals(metric))
            content.addView(warningCard(R.string.spo2_warning_title, R.string.spo2_warning_body));
    }

    /** Renders sleep: per-day timeline + legend, or week/month bar charts. */
    private void renderSleep() {
        String sleepRaw = prefs.getString(P + "sleep", "");

        if (range != DAY) {
            renderSleepRange(sleepRaw);
            return;
        }

        // Day view: only the session(s) that ended on the selected day
        com.example.dialsender.ble.SleepAnalyzer.SleepResult sr =
                com.example.dialsender.ble.SleepAnalyzer.analyzeDay(sleepRaw, selDayStart);

        // Big value card
        String totalStr = sr.totalMinutes > 0
                ? getString(R.string.sleep_hours_min, sr.totalMinutes / 60, sr.totalMinutes % 60)
                : getString(R.string.no_data);
        content.addView(valueHeader(totalStr, sr.totalMinutes > 0 ? getString(R.string.sleep_hours_slept) : ""));

        if (sr.totalMinutes > 0) {
            // Sleep timeline chart for the selected day
            com.example.dialsender.views.SleepTimelineView tl =
                    new com.example.dialsender.views.SleepTimelineView(this);
            tl.setSleepData(sleepRaw, selDayStart);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(120));
            tlp.setMargins(0, dp(12), 0, 0);
            tl.setLayoutParams(tlp);
            content.addView(tl);

            // Phase legend
            LinearLayout legend = new LinearLayout(this);
            legend.setOrientation(LinearLayout.HORIZONTAL);
            legend.setPadding(0, dp(12), 0, dp(8));
            addSleepLegendItem(legend, getString(R.string.sleep_deep), theme.sleepDeep, sr.deepMin);
            addSleepLegendItem(legend, getString(R.string.sleep_light), theme.accentPrimary, sr.lightMin);
            addSleepLegendItem(legend, getString(R.string.sleep_rem), theme.sleepRem, sr.remMin);
            addSleepLegendItem(legend, getString(R.string.sleep_awake), theme.textMuted, sr.awakeMin);
            content.addView(legend);
        } else {
            TextView noData = new TextView(this);
            noData.setText(getString(R.string.sleep_no_data_day));
            noData.setTextColor(theme.textMuted);
            noData.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            noData.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(24), 0, 0);
            noData.setLayoutParams(lp);
            content.addView(noData);
        }
    }

    /** Week/month sleep: bar chart of hours per day + max/avg/min stats. */
    private void renderSleepRange(String sleepRaw) {
        int days = range == WEEK ? 7 : 30;
        long start = selDayStart - (days - 1) * 86400L;
        int[] minutes = com.example.dialsender.ble.SleepAnalyzer.minutesPerDay(sleepRaw, start, days);

        // Average over days with data
        int sum = 0, n = 0, max = 0, min = Integer.MAX_VALUE;
        for (int m : minutes) {
            if (m <= 0) continue;
            sum += m; n++;
            max = Math.max(max, m);
            min = Math.min(min, m);
        }
        int avg = n > 0 ? sum / n : 0;

        String header = avg > 0
                ? getString(R.string.sleep_hours_min, avg / 60, avg % 60)
                : getString(R.string.no_data);
        content.addView(valueHeader(header,
                range == WEEK ? getString(R.string.avg_last_7_days) : getString(R.string.avg_last_30_days)));

        // Bar chart in hours
        List<BarEntry> entries = new ArrayList<>();
        for (int i = 0; i < days; i++)
            entries.add(new BarEntry(i, minutes[i] / 60f));
        BarChart chart = new BarChart(this);
        BarDataSet ds = new BarDataSet(entries, "");
        ds.setColor(color);
        ds.setDrawValues(false);
        BarData bd = new BarData(ds);
        bd.setBarWidth(0.6f);
        chart.setData(bd);
        style(chart);
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(days - 0.5f);
        String[] labels = new String[days];
        SimpleDateFormat f = new SimpleDateFormat("dd/MM", Locale.getDefault());
        for (int i = 0; i < days; i++)
            labels[i] = f.format(new Date((start + i * 86400L) * 1000L));
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        chart.getXAxis().setLabelCount(Math.min(days, 6), false);
        setHeight(chart, 260);
        content.addView(chart);

        if (n > 0) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(20), 0, 0);
            row.addView(sleepStat(getString(R.string.stat_max), max));
            row.addView(sleepStat(getString(R.string.stat_avg), avg));
            row.addView(sleepStat(getString(R.string.stat_min), min == Integer.MAX_VALUE ? 0 : min));
            content.addView(row);
        }
    }

    private View sleepStat(String label, int minutes) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView l = new TextView(this);
        l.setText(title + "\n" + label);
        l.setTextColor(theme.textSecondary);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        l.setGravity(Gravity.CENTER);
        c.addView(l);
        TextView v = new TextView(this);
        v.setText(getString(R.string.sleep_hours_min, minutes / 60, minutes % 60));
        v.setTextColor(theme.textPrimary);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(6), 0, 0);
        c.addView(v);
        return c;
    }

    private void addSleepLegendItem(LinearLayout row, String name, int clr, int minutes) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(android.view.Gravity.CENTER_VERTICAL);
        item.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(clr);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(8), dp(8));
        dl.setMargins(0, 0, dp(5), 0);
        dot.setLayoutParams(dl);
        item.addView(dot);
        TextView t = new TextView(this);
        t.setText(name + "\n" + (minutes / 60) + "h " + (minutes % 60) + "m");
        t.setTextColor(theme.textSecondary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        item.addView(t);
        row.addView(item);
    }

    /** Informational card explaining what the metric is (like Co-Fit). */
    /**
     * The honesty card. Two of these metrics are not measured by hardware this
     * watch has: SpO2 is widely reported as synthesised, and blood pressure is
     * derived from the pulse by a fixed equation. Each screen says so plainly
     * rather than letting a confident-looking number stand on its own.
     */
    private View warningCard(int titleRes, int bodyRes) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.bgElevated);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x66F59E0B);
        card.setBackground(bg);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(16), 0, dp(8));
        card.setLayoutParams(lp);

        TextView head = new TextView(this);
        head.setText(titleRes);
        head.setTextColor(0xFFF59E0B);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setTypeface(null, Typeface.BOLD);
        head.setPadding(0, 0, 0, dp(8));
        card.addView(head);

        TextView body = new TextView(this);
        body.setText(bodyRes);
        body.setTextColor(theme.textSecondary);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setLineSpacing(dp(3), 1f);
        card.addView(body);
        return card;
    }

    private View descriptionCard() {
        if (desc == null || desc.isEmpty())
            return new View(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.bgElevated);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(24), 0, 0);
        card.setLayoutParams(lp);

        TextView head = new TextView(this);
        head.setText(getString(R.string.metric_about_fmt, title.toLowerCase(Locale.getDefault())));
        head.setTextColor(theme.accentPrimary);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setTypeface(null, Typeface.BOLD);
        head.setPadding(0, 0, 0, dp(8));
        card.addView(head);

        TextView body = new TextView(this);
        body.setText(desc);
        body.setTextColor(theme.textSecondary);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setLineSpacing(dp(3), 1f);
        card.addView(body);
        return card;
    }

    private View buildDayNav() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(4));

        ImageView prev = navArrow(R.drawable.ic_chevron_left);
        prev.setOnClickListener(v -> {
            selDayStart -= 86400L;
            render();
        });
        row.addView(prev);

        TextView label = new TextView(this);
        boolean isToday = selDayStart == todayStart();
        label.setText(isToday ? getString(R.string.label_today)
                : new SimpleDateFormat("EEE d MMM", Locale.getDefault())
                        .format(new Date(selDayStart * 1000L)));
        label.setTextColor(theme.textSecondary);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setGravity(Gravity.CENTER);
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(theme.bgElevated);
        lb.setCornerRadius(theme.radiusChip);
        label.setBackground(lb);
        label.setPadding(dp(28), dp(8), dp(28), dp(8));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(llp);
        label.setOnClickListener(v -> pickDate());
        row.addView(label);

        ImageView next = navArrow(R.drawable.ic_chevron_right);
        next.setEnabled(!isToday);
        next.setAlpha(isToday ? 0.3f : 1f);
        next.setOnClickListener(v -> {
            if (selDayStart < todayStart()) {
                selDayStart += 86400L;
                render();
            }
        });
        row.addView(next);
        return row;
    }

    /** Day stepper arrow. Was a "‹"/"›" character in a TextView. */
    private ImageView navArrow(int iconRes) {
        ImageView v = new ImageView(this);
        v.setImageResource(iconRes);
        v.setColorFilter(theme.textSecondary);
        v.setPadding(dp(14), dp(10), dp(14), dp(10));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
        return v;
    }

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(selDayStart * 1000L);
        android.app.DatePickerDialog d = new android.app.DatePickerDialog(this,
                (view, y, m, day) -> {
                    Calendar sel = Calendar.getInstance();
                    sel.set(y, m, day, 0, 0, 0);
                    sel.set(Calendar.MILLISECOND, 0);
                    selDayStart = sel.getTimeInMillis() / 1000;
                    range = DAY;
                    updateTabs();
                    render();
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        d.getDatePicker().setMaxDate(System.currentTimeMillis());
        d.show();
    }

    private View valueHeader(String value, String sub) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(4), dp(16), dp(4), dp(16));

        ImageView icon = new ImageView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        icon.setBackground(bg);
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        icon.setImageResource(iconRes);
        icon.setColorFilter(theme.textPrimary);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(44), dp(44));
        il.setMargins(0, 0, dp(16), 0);
        h.addView(icon, il);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout vr = new LinearLayout(this);
        vr.setOrientation(LinearLayout.HORIZONTAL);
        vr.setGravity(Gravity.BOTTOM);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(theme.textPrimary);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        v.setTypeface(null, Typeface.BOLD);
        vr.addView(v);
        if (!unit.isEmpty()) {
            TextView u = new TextView(this);
            u.setText(" " + unit);
            u.setTextColor(theme.textSecondary);
            u.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            u.setPadding(0, 0, 0, dp(8));
            vr.addView(u);
        }
        col.addView(vr);
        if (sub != null && !sub.isEmpty()) {
            TextView st = new TextView(this);
            st.setText(sub);
            st.setTextColor(theme.textMuted);
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            col.addView(st);
        }
        h.addView(col);
        return h;
    }

    private View statsRow(List<float[]> s) {
        float max = 0, min = Float.MAX_VALUE, sum = 0;
        int n = 0;
        for (float[] e : s) {
            if (e[1] <= 0) continue;
            max = Math.max(max, e[1]);
            min = Math.min(min, e[1]);
            sum += e[1]; n++;
        }
        if (n == 0) { min = 0; }
        return threeStats((int) max, n > 0 ? (int) (sum / n) : 0, n > 0 ? (int) min : 0);
    }

    private View statsRowDaily(float[][] agg, int days) {
        float max = 0, min = Float.MAX_VALUE, sum = 0;
        int n = 0;
        for (int i = 0; i < days; i++) {
            if (agg[2][i] <= 0) continue;
            float avg = agg[1][i];
            max = Math.max(max, avg);
            min = Math.min(min, avg);
            sum += avg; n++;
        }
        if (n == 0) min = 0;
        return threeStats((int) max, n > 0 ? (int) (sum / n) : 0, n > 0 ? (int) min : 0);
    }

    private View threeStats(int max, int avg, int min) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(20), 0, 0);
        row.addView(stat(title + "\n" + getString(R.string.stat_max), max));
        row.addView(stat(title + "\n" + getString(R.string.stat_avg), avg));
        row.addView(stat(title + "\n" + getString(R.string.stat_min), min));
        return row;
    }

    private View stat(String label, int value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(theme.textSecondary);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        l.setGravity(Gravity.CENTER);
        c.addView(l);
        TextView v = new TextView(this);
        v.setText(String.valueOf(value));
        v.setTextColor(theme.textPrimary);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(6), 0, 0);
        c.addView(v);
        if (!unit.isEmpty()) {
            TextView u = new TextView(this);
            u.setText(unit);
            u.setTextColor(theme.textMuted);
            u.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            u.setGravity(Gravity.CENTER);
            c.addView(u);
        }
        return c;
    }

    // ============ Charts ============

    private LineChart lineChartDay(List<float[]> s, long dayStart) {
        LineChart chart = new LineChart(this);
        List<Entry> entries = new ArrayList<>();
        for (float[] e : s)
            entries.add(new Entry((e[0] - dayStart) / 3600f, e[1]));
        // sort by X so the line is drawn left-to-right (avoids back-connections)
        java.util.Collections.sort(entries, (a, b) -> Float.compare(a.getX(), b.getX()));
        if (entries.size() < 2 && !entries.isEmpty())
            entries.add(new Entry(entries.get(0).getX() + 0.01f, entries.get(0).getY()));
        LineDataSet ds = new LineDataSet(entries, "");
        ds.setColor(color);
        ds.setLineWidth(2f);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.LINEAR);
        ds.setDrawFilled(true);
        ds.setFillColor(color);
        ds.setFillAlpha(70);
        chart.setData(new LineData(ds));
        style(chart);
        chart.getXAxis().setAxisMinimum(0f);
        chart.getXAxis().setAxisMaximum(24f);
        chart.getXAxis().setLabelCount(7, true);
        setHeight(chart, 260);
        return chart;
    }

    private BarChart hourlyBarChart(List<float[]> s, long dayStart) {
        float[] buckets = new float[24];
        for (float[] e : s) {
            int hr = (int) ((e[0] - dayStart) / 3600L);
            if (hr >= 0 && hr < 24)
                buckets[hr] = Math.max(buckets[hr], e[1]);
        }
        // cumulative metrics -> show per-hour increment
        if (cumulative) {
            float prev = 0;
            for (int i = 0; i < 24; i++) {
                float cur = buckets[i] > 0 ? buckets[i] : prev;
                float inc = Math.max(0, cur - prev);
                if (buckets[i] > 0) prev = buckets[i];
                buckets[i] = inc;
            }
        }
        List<BarEntry> entries = new ArrayList<>();
        for (int i = 0; i < 24; i++)
            entries.add(new BarEntry(i, buckets[i]));
        BarChart chart = new BarChart(this);
        BarDataSet ds = new BarDataSet(entries, "");
        ds.setColor(color);
        ds.setDrawValues(false);
        BarData bd = new BarData(ds);
        bd.setBarWidth(0.5f);
        chart.setData(bd);
        style(chart);
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(23.5f);
        chart.getXAxis().setLabelCount(7, true);
        setHeight(chart, 260);
        return chart;
    }

    private BarChart dailyBarChart(float[][] agg, int days, long start) {
        List<BarEntry> entries = new ArrayList<>();
        for (int i = 0; i < days; i++)
            entries.add(new BarEntry(i, cumulative ? agg[0][i] : agg[1][i]));
        BarChart chart = new BarChart(this);
        BarDataSet ds = new BarDataSet(entries, "");
        ds.setColor(color);
        ds.setDrawValues(false);
        BarData bd = new BarData(ds);
        bd.setBarWidth(0.6f);
        chart.setData(bd);
        style(chart);
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(days - 0.5f);
        // label a few days
        String[] labels = new String[days];
        SimpleDateFormat f = new SimpleDateFormat("dd/MM", Locale.getDefault());
        for (int i = 0; i < days; i++)
            labels[i] = f.format(new Date((start + i * 86400L) * 1000L));
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        chart.getXAxis().setLabelCount(Math.min(days, 6), false);
        setHeight(chart, 260);
        return chart;
    }

    /**
     * Blood pressure: the most recent sys/dia reading INSIDE the selected
     * window. It used to scan the whole history and ignore its argument, so
     * stepping back a day still showed the same (newer) reading.
     */
    private void renderBp(long dayStart) {
        long from, to;
        if (range == DAY) {
            from = dayStart;
            to = dayStart + 86400L;
        } else {
            int days = range == WEEK ? 7 : 30;
            to = dayStart + 86400L;
            from = to - days * 86400L;
        }

        String h = prefs.getString(P + "blood_pressure", "");
        String latest = "—";
        String when = "";
        if (!h.isEmpty()) {
            String[] arr = h.split(",");
            long bestTs = Long.MIN_VALUE;
            for (String rec : arr) {
                String[] p = rec.split(":");
                if (p.length < 2 || !p[1].contains("/"))
                    continue;
                try {
                    long ts = Long.parseLong(p[0]);
                    if (ts < from || ts >= to || ts <= bestTs)
                        continue;
                    bestTs = ts;
                    latest = p[1];
                    when = new SimpleDateFormat(range == DAY ? "HH:mm" : "dd/MM HH:mm", Locale.US)
                            .format(new Date(ts * 1000L));
                } catch (Exception ignored) {
                }
            }
        }
        if ("—".equals(latest))
            when = getString(R.string.no_data);
        content.addView(valueHeader(latest, when));
    }

    private void style(BarLineChartBase<?> chart) {
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setTouchEnabled(false);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextColor(theme.textMuted);
        x.setTextSize(9f);
        chart.getAxisLeft().setTextColor(theme.textMuted);
        chart.getAxisLeft().setTextSize(9f);
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setGridColor(com.example.dialsender.theme.ThemeManager.withAlpha(theme.textMuted, 0x44));
        chart.getAxisRight().setEnabled(false);
        chart.animateY(500);
    }

    private void setHeight(View v, int dp) {
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dp)));
    }

    // ============ Data ============

    private List<float[]> series(String key, long start) {
        return seriesRange(key, start, System.currentTimeMillis() / 1000 + 86400);
    }

    private List<float[]> seriesRange(String key, long start, long end) {
        List<float[]> out = new ArrayList<>();
        String h = prefs.getString(P + key, "");
        if (h.isEmpty())
            return out;
        for (String e : h.split(",")) {
            String[] p = e.split(":");
            if (p.length < 2 || p[1].contains("/"))
                continue;
            try {
                long ts = Long.parseLong(p[0]);
                float val = Float.parseFloat(p[1]);
                if (ts >= start && ts < end)
                    out.add(new float[] { ts, val });
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Returns [0]=max/last per day, [1]=avg per day, [2]=count per day. */
    private float[][] aggregateByDay(String key, long start, int days) {
        float[][] agg = new float[3][days];
        for (float[] e : series(key, start)) {
            int idx = (int) ((e[0] - start) / 86400L);
            if (idx < 0 || idx >= days)
                continue;
            agg[0][idx] = cumulative ? Math.max(agg[0][idx], e[1]) : agg[0][idx] + e[1];
            agg[2][idx] += 1;
        }
        for (int i = 0; i < days; i++)
            agg[1][i] = agg[2][i] > 0 ? (cumulative ? agg[0][i] : agg[0][i] / agg[2][i]) : 0;
        return agg;
    }

    private float lastNonZeroAvg(float[][] agg, int days) {
        for (int i = days - 1; i >= 0; i--)
            if (agg[2][i] > 0)
                return agg[1][i];
        return 0;
    }

    private String fmt(float v) {
        if (v <= 0)
            return "—";
        if ("distance".equals(metric))
            return String.format(Locale.US, "%.2f", v);
        return String.valueOf((int) v);
    }

    private void share() {
        String rangeName = range == DAY ? getString(R.string.share_range_today)
                : range == WEEK ? getString(R.string.share_range_week) : getString(R.string.share_range_month);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_metric_fmt, title, rangeName));
        startActivity(Intent.createChooser(i, getString(R.string.share)));
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
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
