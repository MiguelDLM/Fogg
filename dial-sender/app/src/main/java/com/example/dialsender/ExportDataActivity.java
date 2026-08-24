package com.example.dialsender;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ExportDataActivity extends AppCompatActivity {
    /** Active theme, resolved once so every builder below can read its tokens. */
    private com.example.dialsender.theme.ThemeManager.AppTheme theme;


    private static final String PREF_NAME = "dial_sender_prefs";

    private TextView txtStatus;
    private ProgressBar progressBar;
    private Button btnExport;
    private LinearLayout layoutSummary;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.example.dialsender.theme.ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);

        theme = com.example.dialsender.theme.ThemeManager.getTheme(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(theme.bgPrimary);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setLayoutParams(lp(-1, dp(48)));

        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_back);
        btnBack.setBackground(null);
        btnBack.setColorFilter(theme.textPrimary);
        btnBack.setLayoutParams(lp(dp(40), dp(40)));
        btnBack.setOnClickListener(v -> finish());
        header.addView(btnBack);

        TextView title = new TextView(this);
        title.setText(getString(R.string.export_title));
        title.setTextColor(theme.textPrimary);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title);
        root.addView(header);

        root.addView(spacer(16));

        layoutSummary = new LinearLayout(this);
        layoutSummary.setOrientation(LinearLayout.VERTICAL);
        layoutSummary.setBackgroundColor(theme.bgCard);
        layoutSummary.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(layoutSummary);

        root.addView(spacer(12));

        TextView infoTxt = new TextView(this);
        infoTxt.setText(getString(R.string.export_info));
        infoTxt.setTextColor(theme.textMuted);
        infoTxt.setTextSize(12);
        infoTxt.setLineSpacing(dp(2), 1f);
        root.addView(infoTxt);

        root.addView(spacer(20));

        btnExport = new Button(this);
        btnExport.setText(getString(R.string.export_btn));
        btnExport.setTextColor(theme.onAccent);
        btnExport.setBackgroundColor(theme.accentPrimary);
        btnExport.setLayoutParams(lp(-1, dp(48)));
        btnExport.setOnClickListener(v -> doExport());
        root.addView(btnExport);

        root.addView(spacer(12));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        txtStatus = new TextView(this);
        txtStatus.setTextColor(theme.textSecondary);
        txtStatus.setTextSize(13);
        txtStatus.setVisibility(View.GONE);
        root.addView(txtStatus);

        populateSummary();
    }

    private void populateSummary() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        layoutSummary.removeAllViews();

        // Counts for each exported file
        String[][] metrics = {
            {"health_steps",          getString(R.string.metric_steps)},
            {"health_heart_rate",     getString(R.string.metric_heart_rate)},
            {"health_sleep",          getString(R.string.metric_sleep)},
            {"health_blood_oxygen",   getString(R.string.metric_spo2)},
            {"health_blood_pressure", getString(R.string.metric_blood_pressure)},
            {"health_stress",         getString(R.string.metric_stress)},
            {"health_hrv",            getString(R.string.metric_hrv)},
            {"health_temperature",    getString(R.string.metric_temperature)},
            {"health_workout",        getString(R.string.metric_workout)},
            {"health_location",       getString(R.string.metric_routes)},
        };

        int totalRows = 0;
        for (String[] m : metrics) {
            String raw = prefs.getString(m[0], "");
            int count = raw.isEmpty() ? 0 : raw.split(",").length;
            if (count == 0) continue;
            totalRows += count;
            addSummaryRow(m[1], count);
        }

        if (totalRows == 0) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.export_no_data));
            empty.setTextColor(theme.textMuted);
            empty.setTextSize(13);
            layoutSummary.addView(empty);
            btnExport.setEnabled(false);
        }
    }

    private void addSummaryRow(String label, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(0xFFCDD5DF);
        lbl.setTextSize(13);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(lbl);

        TextView val = new TextView(this);
        val.setText(count + " " + getString(R.string.export_records));
        val.setTextColor(theme.accentPrimary);
        val.setTextSize(13);
        row.addView(val);

        layoutSummary.addView(row);
    }

    private void doExport() {
        btnExport.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        txtStatus.setVisibility(View.VISIBLE);
        txtStatus.setText(getString(R.string.export_generating));
        txtStatus.setTextColor(theme.textSecondary);

        new Thread(() -> {
            try {
                File zipFile = buildZip();
                runOnUiThread(() -> shareFile(zipFile));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    txtStatus.setText(getString(R.string.error_fmt, e.getMessage()));
                    btnExport.setEnabled(true);
                });
            }
        }).start();
    }

    private File buildZip() throws IOException {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        File dir = new File(getCacheDir(), "exports");
        dir.mkdirs();

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        File zipFile = new File(dir, "health_export_" + stamp + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            exportActivity(zos, prefs);
            exportSimple(zos, prefs, "heart_rate.csv",    "timestamp,bpm",         "health_heart_rate");
            exportSimple(zos, prefs, "blood_oxygen.csv",  "timestamp,spo2_pct",    "health_blood_oxygen");
            exportSimple(zos, prefs, "stress.csv",        "timestamp,stress_0_100","health_stress");
            exportSimple(zos, prefs, "hrv.csv",           "timestamp,hrv_ms",      "health_hrv");
            exportSimple(zos, prefs, "temperature.csv",   "timestamp,temp_x10",    "health_temperature");
            exportBp(zos, prefs);
            exportSleep(zos, prefs);
            exportWorkout(zos, prefs);
            exportLocation(zos, prefs);
            exportReadme(zos, stamp);
        }
        return zipFile;
    }

    private void exportActivity(ZipOutputStream zos, SharedPreferences prefs) throws IOException {
        Map<Long, String[]> map = new TreeMap<>();
        fillMap(map, prefs.getString("health_steps",    ""), 0, 3);
        fillMap(map, prefs.getString("health_calories", ""), 1, 3);
        fillMap(map, prefs.getString("health_distance", ""), 2, 3);
        if (map.isEmpty()) return;
        StringBuilder sb = new StringBuilder("timestamp,steps,calories_kcal,distance_m\n");
        for (Map.Entry<Long, String[]> e : map.entrySet())
            sb.append(e.getKey()).append(",")
              .append(e.getValue()[0] != null ? e.getValue()[0] : "").append(",")
              .append(e.getValue()[1] != null ? e.getValue()[1] : "").append(",")
              .append(e.getValue()[2] != null ? e.getValue()[2] : "").append("\n");
        writeZipEntry(zos, "activity.csv", sb.toString());
    }

    private void fillMap(Map<Long, String[]> map, String raw, int col, int cols) {
        if (raw.isEmpty()) return;
        for (String entry : raw.split(",")) {
            int i = entry.indexOf(':');
            if (i < 1) continue;
            try {
                long ts = Long.parseLong(entry.substring(0, i));
                String val = entry.substring(i + 1);
                String[] row = map.computeIfAbsent(ts, k -> new String[cols]);
                row[col] = val;
            } catch (NumberFormatException ignored) {}
        }
    }

    private void exportSimple(ZipOutputStream zos, SharedPreferences prefs,
            String filename, String header, String key) throws IOException {
        String raw = prefs.getString(key, "");
        if (raw.isEmpty()) return;
        StringBuilder sb = new StringBuilder(header).append("\n");
        for (String entry : raw.split(",")) {
            int i = entry.indexOf(':');
            if (i < 1) continue;
            sb.append(entry.substring(0, i)).append(",").append(entry.substring(i + 1)).append("\n");
        }
        writeZipEntry(zos, filename, sb.toString());
    }

    private void exportBp(ZipOutputStream zos, SharedPreferences prefs) throws IOException {
        String raw = prefs.getString("health_blood_pressure", "");
        if (raw.isEmpty()) return;
        StringBuilder sb = new StringBuilder("timestamp,systolic_mmhg,diastolic_mmhg\n");
        for (String entry : raw.split(",")) {
            int ci = entry.indexOf(':');
            if (ci < 1) continue;
            String[] parts = entry.substring(ci + 1).split("/");
            if (parts.length == 2)
                sb.append(entry.substring(0, ci)).append(",").append(parts[0]).append(",").append(parts[1]).append("\n");
        }
        writeZipEntry(zos, "blood_pressure.csv", sb.toString());
    }

    private void exportSleep(ZipOutputStream zos, SharedPreferences prefs) throws IOException {
        String raw = prefs.getString("health_sleep", "");
        if (raw.isEmpty()) return;
        StringBuilder sb = new StringBuilder("timestamp,mode,soft_sleep_min,deep_sleep_min\n");
        for (String entry : raw.split(",")) {
            String[] p = entry.split(":");
            if (p.length >= 2)
                sb.append(p[0]).append(",").append(p[1]).append(",")
                  .append(p.length > 2 ? p[2] : "").append(",")
                  .append(p.length > 3 ? p[3] : "").append("\n");
        }
        writeZipEntry(zos, "sleep.csv", sb.toString());
    }

    private void exportWorkout(ZipOutputStream zos, SharedPreferences prefs) throws IOException {
        String raw = prefs.getString("health_workout", "");
        if (raw.isEmpty()) return;
        StringBuilder sb = new StringBuilder(
                "start_ts,end_ts,duration_s,altitude_m,air_pressure,spm,mode," +
                "steps,distance_m,calories,speed,pace,avg_bpm,max_bpm\n");
        for (String entry : raw.split(",")) {
            String[] p = entry.split(":");
            if (p.length >= 14)
                sb.append(p[0]).append(",").append(p[1]).append(",").append(p[2]).append(",")
                  .append(p[3]).append(",").append(p[4]).append(",").append(p[5]).append(",")
                  .append(p[6]).append(",").append(p[7]).append(",").append(p[8]).append(",")
                  .append(p[9]).append(",").append(p[10]).append(",").append(p[11]).append(",")
                  .append(p[12]).append(",").append(p[13]).append("\n");
        }
        writeZipEntry(zos, "workout.csv", sb.toString());
    }

    private void exportLocation(ZipOutputStream zos, SharedPreferences prefs) throws IOException {
        String raw = prefs.getString("health_location", "");
        if (raw.isEmpty()) return;
        StringBuilder sb = new StringBuilder("timestamp,mode,altitude_m,longitude,latitude\n");
        for (String entry : raw.split(",")) {
            String[] p = entry.split(":");
            if (p.length >= 5)
                sb.append(p[0]).append(",").append(p[1]).append(",").append(p[2]).append(",")
                  .append(p[3]).append(",").append(p[4]).append("\n");
        }
        writeZipEntry(zos, "location_routes.csv", sb.toString());
    }

    private void exportReadme(ZipOutputStream zos, String stamp) throws IOException {
        String nl = "\n";
        String content =
            "Fogg — Health Data Export" + nl +
            "Generated: " + stamp + nl + nl +
            "Files:" + nl +
            "  activity.csv         — steps, calories (kcal), distance (m)" + nl +
            "  heart_rate.csv       — bpm measurements" + nl +
            "  sleep.csv            — sleep stages (mode: 1=awake,2=light,3=deep,34=REM)" + nl +
            "  blood_oxygen.csv     — SpO2 (%)" + nl +
            "  blood_pressure.csv   — systolic/diastolic (mmHg)" + nl +
            "  stress.csv           — stress level 0-100" + nl +
            "  hrv.csv              — heart rate variability (ms)" + nl +
            "  temperature.csv      — body temp × 10 (divide by 10 for °C)" + nl +
            "  workout.csv          — workout sessions" + nl +
            "  location_routes.csv  — GPS route points (lon/lat/altitude per second)" + nl + nl +
            "All timestamps are Unix epoch in seconds (UTC)." + nl +
            "To convert in spreadsheet: =A1/86400 + DATE(1970,1,1)" + nl;
        writeZipEntry(zos, "README.txt", content);
    }

    private void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        byte[] bytes = content.getBytes("UTF-8");
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }

    private void shareFile(File zipFile) {
        progressBar.setVisibility(View.GONE);
        txtStatus.setText(getString(R.string.export_done, String.valueOf(zipFile.length() / 1024)));
        txtStatus.setTextColor(0xFF4ADE80);
        btnExport.setEnabled(true);

        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", zipFile);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/zip");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, getString(R.string.export_share_title)));
    }

    private View spacer(int dpVal) {
        View v = new View(this);
        v.setLayoutParams(lp(-1, dp(dpVal)));
        return v;
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }
}
