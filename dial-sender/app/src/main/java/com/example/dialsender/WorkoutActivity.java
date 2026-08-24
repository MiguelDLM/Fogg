package com.example.dialsender;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.dialsender.views.WorkoutRingView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen workout tracker, opened by picking an activity in the Deporte
 * tab. Everything on it belongs to the session in progress: the activity's own
 * colour, a sweeping dial, the elapsed time, and pause/finish.
 *
 * The screen owns the session end to end — it is started when the activity is
 * created and only written to the history when the user finishes it — so there
 * is no half-running stopwatch left behind on the tab underneath.
 */
public class WorkoutActivity extends AppCompatActivity {
    /** Active theme, resolved once so every builder below can read its tokens. */
    private com.example.dialsender.theme.ThemeManager.AppTheme theme;


    public static final String EXTRA_SPORT = "sport_mode";

    private static final String PREF = "dial_sender_prefs";
    private static final String KEY_SESSIONS = "sport_sessions";


    private Sport sport = Sport.RUN;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running = true;
    private long accumulatedMs = 0;
    private long startedAt = 0;
    private final long sessionStart = System.currentTimeMillis();

    private WorkoutRingView ring;
    private TextView timerText, kcalValue, distanceValue, stateLabel;
    private TextView btnPause;

    // Real GPS trace, so the detail screen has something true to show.
    private static final int LOCATION_REQUEST = 42;
    private static final long GPS_INTERVAL_MS = 3000;
    private final List<WorkoutTrack.Point> track = new ArrayList<>();
    private LocationManager locationManager;
    private boolean trackingLocation = false;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (!running || location == null)
                return;
            WorkoutTrack.Point last = track.isEmpty() ? null : track.get(track.size() - 1);
            if (!WorkoutTrack.shouldRecord(last, location))
                return;
            track.add(new WorkoutTrack.Point(elapsedSeconds(),
                    location.getLatitude(), location.getLongitude(),
                    WorkoutTrack.usableAltitude(location)));
            render();
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            if (running)
                handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.example.dialsender.theme.ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);

        theme = com.example.dialsender.theme.ThemeManager.getTheme(this);

        Sport requested = Sport.byMode(getIntent().getIntExtra(EXTRA_SPORT, Sport.RUN.mode));
        if (requested != null)
            sport = requested;

        // A workout screen that blanks mid-session is useless.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(theme.bgPrimary);
        getWindow().setNavigationBarColor(theme.bgPrimary);

        setContentView(buildRoot());

        startedAt = System.currentTimeMillis();
        handler.post(tick);
        requestLocationUpdates();
    }

    // ========== Location ==========

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationUpdates() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
                    LOCATION_REQUEST);
            return;
        }
        startLocationUpdates();
    }

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates() {
        if (trackingLocation)
            return;
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null)
            return;
        try {
            // GPS only: the network provider returns cell-tower fixes that would
            // draw a route the user never ran.
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        GPS_INTERVAL_MS, 0f, locationListener);
                trackingLocation = true;
            }
        } catch (Exception e) {
            trackingLocation = false;
        }
        render();
    }

    private void stopLocationUpdates() {
        if (locationManager == null || !trackingLocation)
            return;
        try {
            locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) {
        }
        trackingLocation = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        } else {
            // Without GPS the session still times, it just has no route.
            render();
        }
    }

    // ========== Layout ==========

    private View buildRoot() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(theme.bgPrimary);

        // Accent glow bleeding down from the top, so the activity's colour is
        // the first thing you see.
        View glow = new View(this);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { withAlpha(sport.accent, 64), withAlpha(sport.accent, 12), 0x00000000 });
        glow.setBackground(g);
        frame.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(26));

        root.addView(buildTopBar());
        root.addView(buildDial());
        root.addView(buildStats());

        // Push the controls to the bottom of the screen.
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(spacer);

        root.addView(buildControls());
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setImageDrawable(tinted(R.drawable.ic_back, theme.textPrimary));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMargins(dp(14), 0, 0, 0);
        titleCol.setLayoutParams(colLp);

        TextView name = new TextView(this);
        name.setText(sport.label(this));
        name.setTextColor(theme.textPrimary);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        name.setTypeface(null, Typeface.BOLD);
        titleCol.addView(name);

        stateLabel = new TextView(this);
        stateLabel.setTextColor(sport.accent);
        stateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        stateLabel.setTypeface(null, Typeface.BOLD);
        stateLabel.setAllCaps(true);
        stateLabel.setLetterSpacing(0.16f);
        titleCol.addView(stateLabel);
        bar.addView(titleCol);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(tinted(sport.iconRes, sport.accent));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        bar.addView(icon);
        return bar;
    }

    private View buildDial() {
        FrameLayout holder = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        lp.setMargins(0, dp(26), 0, 0);
        holder.setLayoutParams(lp);

        ring = new WorkoutRingView(this);
        ring.setAccent(sport.accent);
        holder.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout centre = new LinearLayout(this);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER);
        holder.addView(centre, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        TextView caption = new TextView(this);
        caption.setText(getString(R.string.workout_elapsed));
        caption.setTextColor(theme.textMuted);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        caption.setAllCaps(true);
        caption.setLetterSpacing(0.18f);
        caption.setGravity(Gravity.CENTER);
        centre.addView(caption);

        timerText = new TextView(this);
        timerText.setTextColor(theme.textPrimary);
        timerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        timerText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        timerText.setGravity(Gravity.CENTER);
        timerText.setPadding(0, dp(2), 0, 0);
        centre.addView(timerText);
        return holder;
    }

    private View buildStats() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(26), 0, 0);
        row.setLayoutParams(lp);

        kcalValue = new TextView(this);
        distanceValue = new TextView(this);
        row.addView(statCard(getString(R.string.workout_calories), kcalValue, 0));
        row.addView(statCard(getString(R.string.workout_distance), distanceValue, dp(12)));
        return row;
    }

    private View statCard(String label, TextView value, int startMargin) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(14), dp(16), dp(14), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.bgCard);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(startMargin, 0, 0, 0);
        card.setLayoutParams(lp);

        value.setTextColor(theme.textPrimary);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        value.setTypeface(null, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        card.addView(value);

        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(theme.textMuted);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        caption.setAllCaps(true);
        caption.setLetterSpacing(0.1f);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(4), 0, 0);
        card.addView(caption);
        return card;
    }

    private View buildControls() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(24), 0, 0);
        row.setLayoutParams(lp);

        btnPause = bigButton("", sport.accent, theme.onAccent);
        btnPause.setOnClickListener(v -> togglePause());
        row.addView(btnPause);

        TextView stop = bigButton(getString(R.string.workout_finish), 0x00000000, theme.danger);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(0x00000000);
        outline.setCornerRadius(dp(32));
        outline.setStroke(dp(2), theme.danger);
        stop.setBackground(outline);
        ((LinearLayout.LayoutParams) stop.getLayoutParams()).setMargins(dp(12), 0, 0, 0);
        stop.setOnClickListener(v -> finishSession());
        row.addView(stop);
        return row;
    }

    private TextView bigButton(String text, int bgColor, int textColor) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(null, Typeface.BOLD);
        b.setAllCaps(true);
        b.setLetterSpacing(0.06f);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(32));
        b.setBackground(bg);
        b.setPadding(dp(16), dp(18), dp(16), dp(18));
        b.setClickable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    // ========== Session ==========

    private int elapsedSeconds() {
        long ms = accumulatedMs + (running ? System.currentTimeMillis() - startedAt : 0);
        return (int) (ms / 1000);
    }

    private void render() {
        int s = elapsedSeconds();
        timerText.setText(formatDuration(s));
        ring.setProgress((s % 60) / 60f);
        ring.setDimmed(!running);

        kcalValue.setText(String.valueOf((int) Math.round(sport.kcalPerMinute * s / 60.0)));
        if (track.size() >= 2) {
            distanceValue.setText(String.format(Locale.US, "%.2f",
                    WorkoutTrack.distanceMetres(track) / 1000.0));
        } else {
            // No fix yet, or no GPS at all — say nothing rather than guess.
            distanceValue.setText(trackingLocation ? "--" : "--");
        }

        stateLabel.setText(running ? getString(R.string.workout_in_progress)
                : getString(R.string.workout_paused));
        stateLabel.setTextColor(running ? sport.accent : theme.textSecondary);
        btnPause.setText(running ? getString(R.string.sport_pause)
                : getString(R.string.sport_resume));
    }

    private void togglePause() {
        if (running) {
            accumulatedMs += System.currentTimeMillis() - startedAt;
            running = false;
            handler.removeCallbacks(tick);
        } else {
            startedAt = System.currentTimeMillis();
            running = true;
            handler.post(tick);
        }
        render();
    }

    private void finishSession() {
        int durSec = elapsedSeconds();
        if (durSec < 5) {
            // Too short to be a workout; treat finishing as discarding.
            confirmDiscard(R.string.workout_too_short);
            return;
        }
        running = false;
        handler.removeCallbacks(tick);
        stopLocationUpdates();

        int kcal = (int) Math.round(sport.kcalPerMinute * durSec / 60.0);
        String rec = (sessionStart / 1000) + "|" + sport.label(this) + "|" + durSec + "|" + kcal;
        SharedPreferences prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        String all = prefs.getString(KEY_SESSIONS, "");
        prefs.edit().putString(KEY_SESSIONS, all.isEmpty() ? rec : rec + "," + all).apply();
        WorkoutTrack.save(this, sessionStart / 1000, track);

        Toast.makeText(this, getString(R.string.sport_session_saved), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDiscard(int messageRes) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.workout_discard_title))
                .setMessage(getString(messageRes))
                .setPositiveButton(getString(R.string.workout_discard), (d, w) -> {
                    handler.removeCallbacks(tick);
                    finish();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (elapsedSeconds() >= 5) {
            confirmDiscard(R.string.workout_discard_msg);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(tick);
        stopLocationUpdates();
    }

    private static String formatDuration(int seconds) {
        if (seconds >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d",
                    seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private Drawable tinted(int resId, int color) {
        Drawable d = ContextCompat.getDrawable(this, resId);
        if (d != null) {
            d = d.mutate();
            d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
