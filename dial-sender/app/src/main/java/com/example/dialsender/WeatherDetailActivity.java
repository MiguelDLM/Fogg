package com.example.dialsender;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.dialsender.ble.BleManager;
import com.example.dialsender.ble.WeatherSync;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Weather detail screen opened from the weather chip in Estado. Shows the
 * current conditions (temp, condition, humidity, wind, UV, rain chance) plus a
 * short multi-day forecast, all from the cached Open-Meteo data.
 */
public class WeatherDetailActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dial_sender_prefs", Context.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0E1116);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(Color.WHITE);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setPadding(0, 0, dp(14), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = new TextView(this);
        title.setText(getString(R.string.weather_title));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        header.addView(spacer);

        TextView refresh = new TextView(this);
        refresh.setText(getString(R.string.weather_refresh));
        refresh.setTextColor(0xFF22D3EE);
        refresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        refresh.setTypeface(null, Typeface.BOLD);
        refresh.setPadding(dp(12), dp(6), dp(12), dp(6));
        refresh.setClickable(true);
        refresh.setOnClickListener(v -> {
            // Fetches with a fresh location fix and refreshes the cached forecast;
            // the watch is updated too when connected.
            android.widget.Toast.makeText(this, getString(R.string.weather_refreshing), android.widget.Toast.LENGTH_SHORT).show();
            WeatherSync.syncIfPossible(this, BleManager.getInstance(this));
        });
        header.addView(refresh);

        root.addView(header);

        if (prefs.getLong("weather_time", 0) <= 0) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.weather_no_data));
            empty.setTextColor(0xFF9AA4B2);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            root.addView(empty);
            scroll.addView(root);
            setContentView(scroll);
            return;
        }

        int temp = prefs.getInt("weather_temp", 0);
        int code = prefs.getInt("weather_code", 0);
        int hi = prefs.getInt("weather_hi", 0);
        int lo = prefs.getInt("weather_lo", 0);
        int hum = prefs.getInt("weather_humidity", 0);
        int wind = prefs.getInt("weather_wind", 0);
        int uv = prefs.getInt("weather_uv", 0);
        int pop = prefs.getInt("weather_pop", 0);
        String city = prefs.getString("weather_city", "");
        long when = prefs.getLong("weather_time", 0);

        // --- Big current-conditions hero ---
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, dp(24), 0, dp(16));

        TextView emoji = new TextView(this);
        emoji.setText(emojiFor(code));
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        emoji.setGravity(Gravity.CENTER);
        hero.addView(emoji);

        TextView tempView = new TextView(this);
        tempView.setText(temp + "°");
        tempView.setTextColor(Color.WHITE);
        tempView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        tempView.setTypeface(null, Typeface.BOLD);
        tempView.setGravity(Gravity.CENTER);
        hero.addView(tempView);

        TextView cond = new TextView(this);
        cond.setText(labelFor(code));
        cond.setTextColor(0xFF22D3EE);
        cond.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        cond.setGravity(Gravity.CENTER);
        hero.addView(cond);

        TextView sub = new TextView(this);
        String place = city.isEmpty() ? "" : city + " · ";
        sub.setText(place + getString(R.string.weather_hi_lo, hi, lo));
        sub.setTextColor(0xFF9AA4B2);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, 0);
        hero.addView(sub);
        root.addView(hero);

        // --- Detail metric grid ---
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.addView(metricRow(getString(R.string.weather_humidity), hum + " %", getString(R.string.weather_wind), wind + " km/h"));
        grid.addView(metricRow(getString(R.string.weather_uv), String.valueOf(uv), getString(R.string.weather_rain), pop + " %"));
        root.addView(grid);

        // --- Forecast ---
        String fc = prefs.getString("weather_forecast", "");
        if (!fc.isEmpty()) {
            TextView fcHead = new TextView(this);
            fcHead.setText(getString(R.string.weather_forecast_header));
            fcHead.setTextColor(0xFF22D3EE);
            fcHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            fcHead.setTypeface(null, Typeface.BOLD);
            fcHead.setPadding(0, dp(24), 0, dp(8));
            root.addView(fcHead);

            LinearLayout fcCard = new LinearLayout(this);
            fcCard.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1A2027);
            bg.setCornerRadius(dp(14));
            fcCard.setBackground(bg);
            fcCard.setPadding(dp(16), dp(8), dp(16), dp(8));

            String[] days = fc.split(";");
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(when);
            SimpleDateFormat df = new SimpleDateFormat("EEE d", Locale.getDefault());
            for (int i = 0; i < days.length; i++) {
                String[] p = days[i].split("\\|");
                if (p.length < 3)
                    continue;
                int dCode = parse(p[0]);
                int dHi = parse(p[1]);
                int dLo = parse(p[2]);
                int dPop = p.length > 3 ? parse(p[3]) : 0;
                String dayLabel = i == 0 ? getString(R.string.label_today) : df.format(cal.getTime());
                fcCard.addView(forecastRow(dayLabel, dCode, dHi, dLo, dPop));
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
            root.addView(fcCard);
        }

        scroll.addView(root);
        setContentView(scroll);
    }

    private View metricRow(String l1, String v1, String l2, String v2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(lp);
        row.addView(metricCell(l1, v1));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
        row.addView(sp);
        row.addView(metricCell(l2, v2));
        return row;
    }

    private View metricCell(String label, String value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A2027);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFF9AA4B2);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        c.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        v.setTypeface(null, Typeface.BOLD);
        v.setPadding(0, dp(4), 0, 0);
        c.addView(v);
        return c;
    }

    private View forecastRow(String day, int code, int hi, int lo, int pop) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView d = new TextView(this);
        d.setText(day);
        d.setTextColor(0xFFC9D1D9);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        d.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        row.addView(d);

        TextView e = new TextView(this);
        e.setText(emojiFor(code) + (pop > 0 ? "  " + pop + "%" : ""));
        e.setTextColor(0xFF6FC3FF);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        row.addView(e);

        TextView t = new TextView(this);
        t.setText(hi + "° / " + lo + "°");
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setGravity(Gravity.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        return row;
    }

    private int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String labelFor(int code) {
        String[] conds = getResources().getStringArray(R.array.weather_conditions);
        return (code >= 0 && code < conds.length) ? conds[code] : conds[0];
    }

    private String emojiFor(int code) {
        switch (code) {
            case 1: return "☀️";
            case 2: return "⛅";
            case 3: return "☁️";
            case 4: return "🌧️";
            case 5: return "⛈️";
            case 6: return "⛈️";
            case 7: return "💨";
            case 8: return "❄️";
            case 9: return "🌫️";
            case 10: return "🌪️";
            case 11: return "🌁";
            default: return "🌡️";
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if ("weather_time".equals(key)) {
                runOnUiThread(() -> recreate());
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
