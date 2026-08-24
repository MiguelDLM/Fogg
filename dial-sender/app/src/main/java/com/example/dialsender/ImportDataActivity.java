package com.example.dialsender;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ImportDataActivity extends AppCompatActivity {
    /** Active theme, resolved once so every builder below can read its tokens. */
    private com.example.dialsender.theme.ThemeManager.AppTheme theme;


    private static final String PREF_NAME = "dial_sender_prefs";
    private static final long WATCH_EPOCH_OFFSET = 946684800L; // watch → Unix epoch (seconds)

    private TextView txtStatus;
    private ProgressBar progressBar;
    private LinearLayout layoutResults;
    private Button btnSelectFile;

    private final ActivityResultLauncher<String[]> pickFile =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importFromUri(uri);
        });

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

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

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_back);
        btnBack.setBackground(null);
        btnBack.setColorFilter(theme.textPrimary);
        btnBack.setLayoutParams(lp(dp(40), dp(40)));
        btnBack.setOnClickListener(v -> finish());
        header.addView(btnBack);

        TextView title = new TextView(this);
        title.setText(getString(R.string.import_title));
        title.setTextColor(theme.textPrimary);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title);
        root.addView(header);

        root.addView(spacer(16));

        // Option 1: BLE log (no root)
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        root.addView(infoCard(
            getString(R.string.import_option1_title),
            "STF, CoFit, Smawatch, WearFit…\n\n" +
            "  adb shell cp \\\n" +
            "    /sdcard/Android/data/com.smart.stf/logs/ble_logs/" + today + ".txt \\\n" +
            "    /sdcard/Download/"
        ));

        root.addView(spacer(8));

        // Option 2: SQLite DB (root)
        root.addView(infoCard(
            getString(R.string.import_option2_title),
            "  adb shell su -c \\\n" +
            "    'cp /data/data/com.smart.stf/databases/smartv3.db /sdcard/Download/'"
        ));

        root.addView(spacer(8));

        // Option 3: CSV / ZIP
        root.addView(infoCard(
            getString(R.string.import_option3_title),
            getString(R.string.import_option3_body)
        ));

        root.addView(spacer(20));

        // Select file button
        btnSelectFile = new Button(this);
        btnSelectFile.setText(getString(R.string.import_select_file));
        btnSelectFile.setTextColor(theme.onAccent);
        btnSelectFile.setBackgroundColor(theme.accentPrimary);
        LinearLayout.LayoutParams btnLp = lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        btnSelectFile.setLayoutParams(btnLp);
        btnSelectFile.setOnClickListener(v -> pickFile.launch(new String[]{"*/*"}));
        root.addView(btnSelectFile);

        root.addView(spacer(16));

        // Progress
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        txtStatus = new TextView(this);
        txtStatus.setTextColor(theme.textSecondary);
        txtStatus.setTextSize(13);
        txtStatus.setVisibility(View.GONE);
        root.addView(txtStatus);

        root.addView(spacer(8));

        layoutResults = new LinearLayout(this);
        layoutResults.setOrientation(LinearLayout.VERTICAL);
        root.addView(layoutResults);
    }

    private void importFromUri(Uri uri) {
        btnSelectFile.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        txtStatus.setVisibility(View.VISIBLE);
        txtStatus.setText(R.string.import_copying);
        layoutResults.removeAllViews();

        new Thread(() -> {
            try {
                File tmp = copyToTemp(uri);
                FileType type = detectType(tmp);
                final String statusMsg;
                switch (type) {
                    case BLE_LOG: statusMsg = getString(R.string.import_processing_ble); break;
                    case ZIP:     statusMsg = getString(R.string.import_reading_zip); break;
                    case CSV:     statusMsg = getString(R.string.import_reading_csv); break;
                    default:      statusMsg = getString(R.string.import_reading_db);
                }
                runOnUiThread(() -> txtStatus.setText(statusMsg));
                ImportResult result;
                switch (type) {
                    case BLE_LOG: result = parseBleLog(tmp); break;
                    case ZIP:     result = parseZip(tmp); break;
                    case CSV:     result = parseCsv(tmp); break;
                    default:      result = parseDb(tmp);
                }
                tmp.delete();
                runOnUiThread(() -> showResults(result));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    txtStatus.setText(getString(R.string.error_fmt, e.getMessage()));
                    btnSelectFile.setEnabled(true);
                });
            }
        }).start();
    }

    private File copyToTemp(Uri uri) throws Exception {
        File tmp = new File(getCacheDir(), "import_tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return tmp;
    }

    private enum FileType { SQLITE, BLE_LOG, CSV, ZIP }

    private FileType detectType(File file) throws Exception {
        byte[] header = new byte[16];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(header);
        }
        // ZIP: PK magic
        if (header[0] == 0x50 && header[1] == 0x4B) return FileType.ZIP;
        // SQLite
        String h = new String(header, "UTF-8");
        if (h.startsWith("SQLite format")) return FileType.SQLITE;
        // BLE log: lines start with HH:MM:SS.mmm
        if (header[2] == ':' && header[5] == ':' && header[8] == '.') return FileType.BLE_LOG;
        // Assume CSV (text file)
        return FileType.CSV;
    }

    // ── ZIP parser ───────────────────────────────────────────────────────────

    private ImportResult parseZip(File zipFile) throws Exception {
        ImportResult total = new ImportResult();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.US);
                if (!name.endsWith(".csv")) { zis.closeEntry(); continue; }
                File tmp = new File(getCacheDir(), "zip_entry.csv");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                }
                ImportResult r = parseCsv(tmp);
                total.activity   += r.activity;
                total.heartRate  += r.heartRate;
                total.sleep      += r.sleep;
                total.bloodOxygen+= r.bloodOxygen;
                total.bloodPressure += r.bloodPressure;
                total.stress     += r.stress;
                total.hrv        += r.hrv;
                total.location   += r.location;
                total.workout    += r.workout;
                zis.closeEntry();
            }
        }
        return total;
    }

    // ── CSV parser ───────────────────────────────────────────────────────────

    private ImportResult parseCsv(File csvFile) throws Exception {
        ImportResult r = new ImportResult();
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(csvFile), "UTF-8"))) {
            String header = br.readLine();
            if (header == null) return r;
            header = header.trim();

            if (header.startsWith("timestamp,steps")) {
                // activity.csv
                StringBuilder steps = new StringBuilder(prefs.getString("health_steps", ""));
                StringBuilder cals  = new StringBuilder(prefs.getString("health_calories", ""));
                StringBuilder dists = new StringBuilder(prefs.getString("health_distance", ""));
                Set<Long> seen = new HashSet<>();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(",", -1);
                    if (p.length < 4) continue;
                    try {
                        long ts = Long.parseLong(p[0].trim());
                        if (!seen.add(ts)) continue;
                        append(steps, ts + ":" + p[1].trim());
                        append(cals,  ts + ":" + p[2].trim());
                        append(dists, ts + ":" + p[3].trim());
                        r.activity++;
                    } catch (NumberFormatException ignored) {}
                }
                ed.putString("health_steps",    steps.toString());
                ed.putString("health_calories", cals.toString());
                ed.putString("health_distance", dists.toString());

            } else if (header.startsWith("timestamp,bpm")) {
                r.heartRate = importSimpleCsv(br, prefs, ed, "health_heart_rate");
            } else if (header.startsWith("timestamp,spo2")) {
                r.bloodOxygen = importSimpleCsv(br, prefs, ed, "health_blood_oxygen");
            } else if (header.startsWith("timestamp,stress")) {
                r.stress = importSimpleCsv(br, prefs, ed, "health_stress");
            } else if (header.startsWith("timestamp,hrv")) {
                r.hrv = importSimpleCsv(br, prefs, ed, "health_hrv");
            } else if (header.startsWith("timestamp,temp")) {
                importSimpleCsv(br, prefs, ed, "health_temperature");
            } else if (header.startsWith("timestamp,systolic")) {
                // blood_pressure.csv: ts,sys,dia
                StringBuilder sb = new StringBuilder(prefs.getString("health_blood_pressure", ""));
                Set<Long> seen = new HashSet<>();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(",", -1);
                    if (p.length < 3) continue;
                    try {
                        long ts = Long.parseLong(p[0].trim());
                        if (!seen.add(ts)) continue;
                        append(sb, ts + ":" + p[1].trim() + "/" + p[2].trim());
                        r.bloodPressure++;
                    } catch (NumberFormatException ignored) {}
                }
                ed.putString("health_blood_pressure", sb.toString());
            } else if (header.startsWith("timestamp,mode")) {
                // sleep.csv: ts,mode,soft,deep
                StringBuilder sb = new StringBuilder(prefs.getString("health_sleep", ""));
                Set<Long> seen = new HashSet<>();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(",", -1);
                    if (p.length < 4) continue;
                    try {
                        long ts = Long.parseLong(p[0].trim());
                        if (!seen.add(ts)) continue;
                        append(sb, ts + ":" + p[1].trim() + ":" + p[2].trim() + ":" + p[3].trim());
                        r.sleep++;
                    } catch (NumberFormatException ignored) {}
                }
                ed.putString("health_sleep", sb.toString());
            } else if (header.startsWith("start_ts")) {
                // workout.csv
                StringBuilder sb = new StringBuilder(prefs.getString("health_workout", ""));
                Set<Long> seen = new HashSet<>();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(",", -1);
                    if (p.length < 14) continue;
                    try {
                        long ts = Long.parseLong(p[0].trim());
                        if (!seen.add(ts)) continue;
                        append(sb, p[0].trim() + ":" + p[1].trim() + ":" + p[2].trim() + ":"
                                + p[3].trim() + ":" + p[4].trim() + ":" + p[5].trim() + ":"
                                + p[6].trim() + ":" + p[7].trim() + ":" + p[8].trim() + ":"
                                + p[9].trim() + ":" + p[10].trim() + ":" + p[11].trim() + ":"
                                + p[12].trim() + ":" + p[13].trim());
                        r.workout++;
                    } catch (NumberFormatException ignored) {}
                }
                ed.putString("health_workout", sb.toString());
            } else if (header.startsWith("timestamp,mode,altitude")) {
                // location.csv: ts,mode,altitude,longitude,latitude
                StringBuilder sb = new StringBuilder(prefs.getString("health_location", ""));
                Set<Long> seen = new HashSet<>();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(",", -1);
                    if (p.length < 5) continue;
                    try {
                        long ts = Long.parseLong(p[0].trim());
                        if (!seen.add(ts)) continue;
                        append(sb, ts + ":" + p[1].trim() + ":" + p[2].trim()
                                + ":" + p[3].trim() + ":" + p[4].trim());
                        r.location++;
                    } catch (NumberFormatException ignored) {}
                }
                ed.putString("health_location", sb.toString());
            }
        }
        ed.apply();
        return r;
    }

    private int importSimpleCsv(BufferedReader br, SharedPreferences prefs,
            SharedPreferences.Editor ed, String key) throws Exception {
        StringBuilder sb = new StringBuilder(prefs.getString(key, ""));
        Set<Long> seen = new HashSet<>();
        int count = 0;
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = line.split(",", -1);
            if (p.length < 2) continue;
            try {
                long ts = Long.parseLong(p[0].trim());
                if (!seen.add(ts)) continue;
                append(sb, ts + ":" + p[1].trim());
                count++;
            } catch (NumberFormatException ignored) {}
        }
        ed.putString(key, sb.toString());
        return count;
    }

    // ── BLE log parser ───────────────────────────────────────────────────────

    private ImportResult parseBleLog(File logFile) throws Exception {
        // Strip per-line timestamp prefix and join lines so mid-token wraps are healed.
        StringBuilder raw = new StringBuilder((int) logFile.length());
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFile), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Each line starts with "HH:MM:SS.mmm L " (15 chars)
                if (line.length() > 15 && line.charAt(2) == ':' && line.charAt(5) == ':'
                        && line.charAt(8) == '.')
                    raw.append(line.substring(15));
                else
                    raw.append(line);
            }
        }

        ImportResult r = new ImportResult();
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        // Clear all BLE-sourced keys before writing so re-imports always replace stale values.
        ed.remove("health_steps").remove("health_calories").remove("health_distance")
          .remove("health_heart_rate").remove("health_sleep")
          .remove("health_blood_oxygen").remove("health_blood_pressure")
          .remove("health_stress");

        // Activity → steps / calories / distance
        // mCalorie and mDistance are raw BLE wire units; divide by 10000 (mirrors BleManager).
        {
            StringBuilder steps = new StringBuilder();
            StringBuilder cals  = new StringBuilder();
            StringBuilder dists = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            Matcher m = Pattern.compile(
                    "BleActivity\\(mTime=(\\d+), mMode=\\d+, mState=\\d+," +
                    " mStep=(\\d+), mCalorie=(\\d+), mDistance=(\\d+)\\)").matcher(raw);
            while (m.find()) {
                long t = Long.parseLong(m.group(1)) + WATCH_EPOCH_OFFSET;
                if (!seen.add(t)) continue;
                append(steps, t + ":" + Integer.parseInt(m.group(2)));
                append(cals,  t + ":" + Integer.parseInt(m.group(3)) / 10000);
                append(dists, t + ":" + Integer.parseInt(m.group(4)) / 10000);
                r.activity++;
            }
            ed.putString("health_steps",   steps.toString());
            ed.putString("health_calories", cals.toString());
            ed.putString("health_distance", dists.toString());
        }

        // HeartRate
        {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            Matcher m = Pattern.compile(
                    "BleHeartRate\\(mTime=(\\d+), mBpm=(\\d+), mType=\\d+\\)").matcher(raw);
            while (m.find()) {
                long t = Long.parseLong(m.group(1)) + WATCH_EPOCH_OFFSET;
                if (!seen.add(t)) continue;
                append(sb, t + ":" + m.group(2));
                r.heartRate++;
            }
            ed.putString("health_heart_rate", sb.toString());
        }

        // Sleep — mLocalTime is already Unix epoch in milliseconds
        {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            Matcher m = Pattern.compile(
                    "BleSleep\\(mTime=\\d+, mMode=(\\d+), mSoft=(\\d+), mStrong=(\\d+)," +
                    " mLocalTime=(\\d+)\\(").matcher(raw);
            while (m.find()) {
                long t = Long.parseLong(m.group(4)) / 1000L; // ms → s
                if (!seen.add(t)) continue;
                append(sb, t + ":" + m.group(1) + ":" + m.group(2) + ":" + m.group(3));
                r.sleep++;
            }
            ed.putString("health_sleep", sb.toString());
        }

        // BloodOxygen (SpO2)
        {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            Matcher m = Pattern.compile(
                    "BleBloodOxygen\\(mTime=(\\d+), mValue=(\\d+)").matcher(raw);
            while (m.find()) {
                long t = Long.parseLong(m.group(1)) + WATCH_EPOCH_OFFSET;
                if (!seen.add(t)) continue;
                append(sb, t + ":" + m.group(2));
                r.bloodOxygen++;
            }
            ed.putString("health_blood_oxygen", sb.toString());
        }

        // BloodPressure
        {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            Matcher m = Pattern.compile(
                    "BleBloodPressure\\(mTime=(\\d+), mSystolic=(\\d+), mDiastolic=(\\d+)")
                    .matcher(raw);
            while (m.find()) {
                long t = Long.parseLong(m.group(1)) + WATCH_EPOCH_OFFSET;
                if (!seen.add(t)) continue;
                append(sb, t + ":" + m.group(2) + "/" + m.group(3));
                r.bloodPressure++;
            }
            ed.putString("health_blood_pressure", sb.toString());
        }

        // BlePressure → stress level 0–100
        {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            Matcher m = Pattern.compile(
                    "BlePressure\\(mTime=(\\d+), mValue=(\\d+)\\)").matcher(raw);
            while (m.find()) {
                long t = Long.parseLong(m.group(1)) + WATCH_EPOCH_OFFSET;
                if (!seen.add(t)) continue;
                append(sb, t + ":" + m.group(2));
                r.stress++;
            }
            ed.putString("health_stress", sb.toString());
        }

        ed.apply();
        return r;
    }

    // ── SQLite DB parser ─────────────────────────────────────────────────────

    private ImportResult parseDb(File dbFile) {
        ImportResult r = new ImportResult();
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // Clear all health keys so re-imports always produce correct values.
        prefs.edit()
             .remove("health_steps").remove("health_calories").remove("health_distance")
             .remove("health_heart_rate").remove("health_sleep")
             .remove("health_blood_oxygen").remove("health_blood_pressure")
             .remove("health_stress").remove("health_hrv").remove("health_location")
             .remove("health_workout").remove("sport_sessions")
             .commit();

        SharedPreferences.Editor ed = prefs.edit();
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

        // Activity → steps / calories / distance
        // mTime is Unix ms → /1000; mCalorie and mDistance are BLE wire units → /10000.
        if (tableExists(db, "Activity")) {
            StringBuilder steps = new StringBuilder();
            StringBuilder cals  = new StringBuilder();
            StringBuilder dists = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            try (Cursor c = db.rawQuery(
                    "SELECT mTime, mStep, mCalorie, mDistance FROM Activity WHERE mIsDelete=0",
                    null)) {
                while (c.moveToNext()) {
                    long t = c.getLong(0) / 1000L;
                    if (!seen.add(t)) continue;
                    append(steps, t + ":" + c.getInt(1));
                    append(cals,  t + ":" + c.getInt(2) / 10000);
                    append(dists, t + ":" + c.getInt(3) / 10000);
                    r.activity++;
                }
            }
            ed.putString("health_steps",    steps.toString());
            ed.putString("health_calories",  cals.toString());
            ed.putString("health_distance",  dists.toString());
        }

        // HeartRate
        if (tableExists(db, "HeartRate")) {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            try (Cursor c = db.rawQuery("SELECT mTime, mBpm FROM HeartRate", null)) {
                while (c.moveToNext()) {
                    long t = c.getLong(0) / 1000L;
                    if (!seen.add(t)) continue;
                    append(sb, t + ":" + c.getInt(1));
                    r.heartRate++;
                }
            }
            ed.putString("health_heart_rate", sb.toString());
        }

        // Sleep
        if (tableExists(db, "Sleep")) {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            try (Cursor c = db.rawQuery(
                    "SELECT mTime, mMode, mSoft, mStrong FROM Sleep", null)) {
                while (c.moveToNext()) {
                    long t = c.getLong(0) / 1000L;
                    if (!seen.add(t)) continue;
                    append(sb, t + ":" + c.getInt(1) + ":" + c.getInt(2) + ":" + c.getInt(3));
                    r.sleep++;
                }
            }
            ed.putString("health_sleep", sb.toString());
        }

        // BloodOxygen (SpO2)
        if (tableExists(db, "BloodOxygen")) {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            try (Cursor c = db.rawQuery("SELECT mTime, mValue FROM BloodOxygen", null)) {
                while (c.moveToNext()) {
                    long t = c.getLong(0) / 1000L;
                    if (!seen.add(t)) continue;
                    append(sb, t + ":" + c.getInt(1));
                    r.bloodOxygen++;
                }
            }
            ed.putString("health_blood_oxygen", sb.toString());
        }

        // BloodPressure
        if (tableExists(db, "BloodPressure")) {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            try (Cursor c = db.rawQuery(
                    "SELECT mTime, mSystolic, mDiastolic FROM BloodPressure", null)) {
                while (c.moveToNext()) {
                    long t = c.getLong(0) / 1000L;
                    if (!seen.add(t)) continue;
                    append(sb, t + ":" + c.getInt(1) + "/" + c.getInt(2));
                    r.bloodPressure++;
                }
            }
            ed.putString("health_blood_pressure", sb.toString());
        }

        // Pressure → stress level 0–100
        if (tableExists(db, "Pressure")) {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            try (Cursor c = db.rawQuery("SELECT mTime, mValue FROM Pressure", null)) {
                while (c.moveToNext()) {
                    long t = c.getLong(0) / 1000L;
                    if (!seen.add(t)) continue;
                    append(sb, t + ":" + c.getInt(1));
                    r.stress++;
                }
            }
            ed.putString("health_stress", sb.toString());
        }

        // HRV
        if (tableExists(db, "Hrv")) {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            String col = columnExists(db, "Hrv", "mAvgValue") ? "mAvgValue" : "mValue";
            try (Cursor c = db.rawQuery("SELECT mTime, " + col + " FROM Hrv", null)) {
                while (c.moveToNext()) {
                    long t = c.getLong(0) / 1000L;
                    if (!seen.add(t)) continue;
                    append(sb, t + ":" + c.getInt(1));
                    r.hrv++;
                }
            }
            ed.putString("health_hrv", sb.toString());
        }

        // Location (GPS tracks)
        if (tableExists(db, "Location")) {
            StringBuilder sb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            try (Cursor c = db.rawQuery(
                    "SELECT mTime, mActivityMode, mAltitude, mLongitude, mLatitude FROM Location",
                    null)) {
                while (c.moveToNext()) {
                    long t = c.getLong(0) / 1000L;
                    if (!seen.add(t)) continue;
                    append(sb, t + ":" + c.getInt(1) + ":" + c.getInt(2)
                            + ":" + c.getFloat(3) + ":" + c.getFloat(4));
                    r.location++;
                }
            }
            ed.putString("health_location", sb.toString());
        }

        // Workout (calories/distance already in final units in BLE Workout protocol)
        if (tableExists(db, "Workout")) {
            StringBuilder wSb = new StringBuilder();
            StringBuilder sSb = new StringBuilder();
            Set<Long> seenW = new HashSet<>();
            Set<String> sessions = new HashSet<>();

            String query = "SELECT mStart, mEnd, mDuration, mAltitude, mAirPressure, mSpm," +
                    " mMode, mStep, mDistance, mCalorie, mSpeed, mPace, mAvgBpm, mMaxBpm" +
                    " FROM Workout WHERE mIsDelete=0 OR mIsDelete IS NULL";
            try (Cursor c = db.rawQuery(query, null)) {
                while (c.moveToNext()) {
                    long start = c.getLong(0) / 1000L;
                    if (!seenW.add(start)) continue;

                    long end  = c.getLong(1) / 1000L;
                    int duration = c.getInt(2), altitude = c.getInt(3), pressure = c.getInt(4);
                    int spm = c.getInt(5), mode = c.getInt(6), step = c.getInt(7);
                    int dist = c.getInt(8), calorie = c.getInt(9), speed = c.getInt(10);
                    int pace = c.getInt(11), avgBpm = c.getInt(12), maxBpm = c.getInt(13);

                    append(wSb, start + ":" + end + ":" + duration + ":" + altitude + ":"
                            + pressure + ":" + spm + ":" + mode + ":" + step + ":" + dist
                            + ":" + calorie + ":" + speed + ":" + pace + ":" + avgBpm
                            + ":" + maxBpm);

                    String key = String.valueOf(start);
                    if (sessions.add(key)) {
                        String rec = start + "|" + getSportName(mode) + "|" + duration + "|" + calorie;
                        if (sSb.length() > 0) sSb.insert(0, rec + ",");
                        else sSb.append(rec);
                    }
                    r.workout++;
                }
            }
            ed.putString("health_workout",  wSb.toString());
            ed.putString("sport_sessions",   sSb.toString());
        }

        db.close();
        ed.apply();
        return r;
    }

    private void showResults(ImportResult r) {
        progressBar.setVisibility(View.GONE);
        btnSelectFile.setEnabled(true);

        if (r.total() == 0) {
            txtStatus.setText(getString(R.string.import_empty));
            return;
        }

        txtStatus.setText(getString(R.string.import_done));
        txtStatus.setTextColor(0xFF4ADE80);

        String[][] rows = {
            {getString(R.string.import_row_activity),  String.valueOf(r.activity)},
            {getString(R.string.import_row_hr),         String.valueOf(r.heartRate)},
            {getString(R.string.import_row_sleep),      String.valueOf(r.sleep)},
            {getString(R.string.import_row_spo2),       String.valueOf(r.bloodOxygen)},
            {getString(R.string.import_row_bp),         String.valueOf(r.bloodPressure)},
            {getString(R.string.import_row_stress),     String.valueOf(r.stress)},
            {getString(R.string.import_row_hrv),        String.valueOf(r.hrv)},
            {getString(R.string.import_row_location),   String.valueOf(r.location)},
            {getString(R.string.import_row_workout),    String.valueOf(r.workout)},
        };

        for (String[] row : rows) {
            int count = Integer.parseInt(row[1]);
            if (count == 0) continue;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            item.setPadding(0, dp(6), 0, dp(6));

            TextView label = new TextView(this);
            label.setText(row[0]);
            label.setTextColor(0xFFCDD5DF);
            label.setTextSize(14);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            item.addView(label);

            TextView val = new TextView(this);
            val.setText("+" + row[1]);
            val.setTextColor(0xFF4ADE80);
            val.setTextSize(14);
            val.setTypeface(null, android.graphics.Typeface.BOLD);
            item.addView(val);

            layoutResults.addView(item);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean tableExists(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{table})) {
            return c.moveToFirst();
        }
    }

    private static boolean columnExists(SQLiteDatabase db, String table, String column) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIdx = c.getColumnIndex("name");
            while (c.moveToNext())
                if (column.equals(c.getString(nameIdx))) return true;
        }
        return false;
    }

    private static Set<Long> tsSet(String prefs) {
        Set<Long> s = new HashSet<>();
        if (prefs == null || prefs.isEmpty()) return s;
        for (String e : prefs.split(",")) {
            int i = e.indexOf(':');
            if (i > 0) {
                try { s.add(Long.parseLong(e.substring(0, i))); } catch (NumberFormatException ignored) {}
            }
        }
        return s;
    }

    private static Set<String> sessionSet(String prefs) {
        Set<String> s = new HashSet<>();
        if (prefs == null || prefs.isEmpty()) return s;
        for (String e : prefs.split(",")) {
            int i = e.indexOf('|');
            if (i > 0) s.add(e.substring(0, i));
        }
        return s;
    }

    private static void append(StringBuilder sb, String entry) {
        if (sb.length() > 0) sb.append(",");
        sb.append(entry);
    }

    private String getSportName(int mode) {
        switch (mode) {
            case 1:  return getString(R.string.sport_walk);
            case 2:  return getString(R.string.sport_run);
            case 3:  return getString(R.string.sport_cycling);
            case 4:  return getString(R.string.sport_swim);
            case 5:  return getString(R.string.sport_yoga);
            case 6:  return getString(R.string.sport_row);
            case 7:  return getString(R.string.sport_climb);
            case 8:  return getString(R.string.sport_hike);
            case 9:  return getString(R.string.sport_treadmill);
            case 10: return getString(R.string.sport_elliptical);
            case 11: return getString(R.string.sport_jump_rope);
            case 12: return getString(R.string.sport_basketball);
            case 13: return getString(R.string.sport_football);
            case 14: return getString(R.string.sport_badminton);
            case 15: return getString(R.string.sport_tennis);
            default: return getString(R.string.sport_other);
        }
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(dp)));
        return v;
    }

    private View infoCard(String titleText, String bodyText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(theme.bgCard);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = lp(LinearLayout.LayoutParams.MATCH_PARENT, -2);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(theme.accentPrimary);
        t.setTextSize(13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(8));
        card.addView(t);

        TextView b = new TextView(this);
        b.setText(bodyText);
        b.setTextColor(theme.textSecondary);
        b.setTextSize(12);
        b.setLineSpacing(dp(2), 1f);
        card.addView(b);

        return card;
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private static class ImportResult {
        int activity, heartRate, sleep, bloodOxygen, bloodPressure, stress, hrv, location, workout;
        int total() {
            return activity + heartRate + sleep + bloodOxygen + bloodPressure + stress + hrv
                    + location + workout;
        }
    }
}
