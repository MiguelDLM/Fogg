package com.example.dialsender;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.dialsender.ble.WorldClockManager;
import com.example.dialsender.theme.ThemeManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class WorldClockActivity extends AppCompatActivity {

    private TextView txtLocalCityName;
    private TextView txtLocalTime;
    private TextView txtLocalDate;
    private LinearLayout containerWorldClocks;
    private TextView txtEmptyWorldClocks;

    private List<WorldClockManager.WorldClockItem> clockList;
    private ThemeManager.AppTheme theme;
    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeTicker = new Runnable() {
        @Override
        public void run() {
            updateClocksTime();
            timeHandler.postDelayed(this, 1000);
        }
    };

    private static final String[][] PRESET_CITIES = {
            {"London", "Europe/London"},
            {"Paris", "Europe/Paris"},
            {"Madrid", "Europe/Madrid"},
            {"New York", "America/New_York"},
            {"Los Angeles", "America/Los_Angeles"},
            {"Chicago", "America/Chicago"},
            {"Tokyo", "Asia/Tokyo"},
            {"Beijing", "Asia/Shanghai"},
            {"Hong Kong", "Asia/Hong_Kong"},
            {"Singapore", "Asia/Singapore"},
            {"Dubai", "Asia/Dubai"},
            {"Sydney", "Australia/Sydney"},
            {"Cairo", "Africa/Cairo"},
            {"Buenos Aires", "America/Argentina/Buenos_Aires"},
            {"São Paulo", "America/Sao_Paulo"},
            {"Mexico City", "America/Mexico_City"},
            {"Toronto", "America/Toronto"},
            {"Seoul", "Asia/Seoul"},
            {"Rome", "Europe/Rome"},
            {"Berlin", "Europe/Berlin"}
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_world_clock);

        theme = ThemeManager.getTheme(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddClock).setOnClickListener(v -> showAddCityDialog());

        txtLocalCityName = findViewById(R.id.txtLocalCityName);
        txtLocalTime = findViewById(R.id.txtLocalTime);
        txtLocalDate = findViewById(R.id.txtLocalDate);
        containerWorldClocks = findViewById(R.id.containerWorldClocks);
        txtEmptyWorldClocks = findViewById(R.id.txtEmptyWorldClocks);

        TimeZone localTz = TimeZone.getDefault();
        String localName = localTz.getID().replace("_", " ");
        if (localName.contains("/")) {
            localName = localName.substring(localName.lastIndexOf('/') + 1);
        }
        txtLocalCityName.setText(getString(R.string.world_clock_local) + " · " + localName);

        loadClocks();
    }

    private final WorldClockManager.WorldClockChangeListener worldClockListener = () -> {
        runOnUiThread(this::loadClocks);
    };

    @Override
    protected void onResume() {
        super.onResume();
        WorldClockManager.addListener(worldClockListener);
        timeHandler.post(timeTicker);
        loadClocks();
        com.example.dialsender.ble.BleManager ble = com.example.dialsender.ble.BleManager.getInstance(this);
        if (ble.isSessionReady()) {
            ble.readWorldClocks();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WorldClockManager.removeListener(worldClockListener);
        timeHandler.removeCallbacks(timeTicker);
    }

    private void loadClocks() {
        clockList = WorldClockManager.getSavedClocks(this);
        renderList();
    }

    private void renderList() {
        containerWorldClocks.removeAllViews();

        if (clockList == null || clockList.isEmpty()) {
            txtEmptyWorldClocks.setVisibility(View.VISIBLE);
            return;
        }
        txtEmptyWorldClocks.setVisibility(View.GONE);

        float density = getResources().getDisplayMetrics().density;
        int padH = (int) (16 * density);
        int padV = (int) (14 * density);

        for (int i = 0; i < clockList.size(); i++) {
            final int index = i;
            final WorldClockManager.WorldClockItem item = clockList.get(i);

            if (i > 0) {
                View div = new View(this);
                div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                div.setBackgroundColor(theme.divider);
                div.setPadding(padH, 0, 0, 0);
                containerWorldClocks.addView(div);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(padH, padV, padH, padV);

            // Left: City name + Time diff
            LinearLayout leftCol = new LinearLayout(this);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvCity = new TextView(this);
            tvCity.setText(item.cityName);
            tvCity.setTextColor(theme.textPrimary);
            tvCity.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvCity.setTypeface(Typeface.DEFAULT_BOLD);
            leftCol.addView(tvCity);

            TextView tvDiff = new TextView(this);
            tvDiff.setTag("diff_" + item.id);
            tvDiff.setTextColor(theme.textMuted);
            tvDiff.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            leftCol.addView(tvDiff);

            row.addView(leftCol);

            // Center/Right: Time in that timezone
            TextView tvTime = new TextView(this);
            tvTime.setTag("time_" + item.id);
            tvTime.setTextColor(theme.textPrimary);
            tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            tvTime.setTypeface(Typeface.DEFAULT_BOLD);
            tvTime.setPadding(0, 0, (int) (16 * density), 0);
            row.addView(tvTime);

            // Delete icon
            ImageView btnDel = new ImageView(this);
            btnDel.setImageResource(R.drawable.ic_delete);
            btnDel.setColorFilter(theme.textMuted);
            btnDel.setPadding((int) (6 * density), (int) (6 * density), (int) (6 * density), (int) (6 * density));
            btnDel.setLayoutParams(new LinearLayout.LayoutParams((int) (32 * density), (int) (32 * density)));
            btnDel.setOnClickListener(v -> {
                clockList.remove(index);
                WorldClockManager.saveClocks(this, clockList);
                WorldClockManager.syncToWatch(this);
                renderList();
            });
            row.addView(btnDel);

            containerWorldClocks.addView(row);
        }

        updateClocksTime();
    }

    private void updateClocksTime() {
        Calendar localNow = Calendar.getInstance();
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, d MMM", Locale.getDefault());

        txtLocalTime.setText(timeFmt.format(localNow.getTime()));
        txtLocalDate.setText(dateFmt.format(localNow.getTime()));

        if (clockList == null) return;

        long localOffsetMs = TimeZone.getDefault().getOffset(localNow.getTimeInMillis());

        for (WorldClockManager.WorldClockItem item : clockList) {
            TimeZone tz = TimeZone.getTimeZone(item.timeZoneId != null ? item.timeZoneId : "UTC");
            Calendar tzCal = Calendar.getInstance(tz);

            SimpleDateFormat cityTimeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
            cityTimeFmt.setTimeZone(tz);

            TextView tvTime = containerWorldClocks.findViewWithTag("time_" + item.id);
            if (tvTime != null) {
                tvTime.setText(cityTimeFmt.format(tzCal.getTime()));
            }

            long targetOffsetMs = tz.getOffset(localNow.getTimeInMillis());
            long diffHours = (targetOffsetMs - localOffsetMs) / (1000 * 60 * 60);

            String diffStr;
            if (diffHours == 0) {
                diffStr = getString(R.string.world_clock_local);
            } else if (diffHours > 0) {
                diffStr = "+" + diffHours + "h";
            } else {
                diffStr = diffHours + "h";
            }

            TextView tvDiff = containerWorldClocks.findViewWithTag("diff_" + item.id);
            if (tvDiff != null) {
                tvDiff.setText(diffStr);
            }
        }
    }

    private void showAddCityDialog() {
        if (clockList != null && clockList.size() >= WorldClockManager.MAX_CLOCKS) {
            Toast.makeText(this, R.string.world_clock_max_reached, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] cityNames = new String[PRESET_CITIES.length];
        for (int i = 0; i < PRESET_CITIES.length; i++) {
            cityNames[i] = PRESET_CITIES[i][0];
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.world_clock_add)
                .setItems(cityNames, (dialog, which) -> {
                    String name = PRESET_CITIES[which][0];
                    String tzId = PRESET_CITIES[which][1];

                    // Check if already added
                    for (WorldClockManager.WorldClockItem existing : clockList) {
                        if (existing.cityName.equalsIgnoreCase(name)) {
                            return;
                        }
                    }

                    int newId = WorldClockManager.getNextAvailableId(clockList);
                    clockList.add(new WorldClockManager.WorldClockItem(newId, name, tzId, false));
                    WorldClockManager.saveClocks(this, clockList);
                    WorldClockManager.syncToWatch(this);
                    renderList();
                    Toast.makeText(this, R.string.world_clock_synced, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
