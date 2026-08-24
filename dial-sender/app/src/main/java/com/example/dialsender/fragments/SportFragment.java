package com.example.dialsender.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.dialsender.R;
import com.example.dialsender.Sport;
import com.example.dialsender.SportDetailActivity;
import com.example.dialsender.WorkoutActivity;
import com.example.dialsender.WorkoutTrack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deporte tab: choose an activity, then review past ones.
 *
 * Picking a tile hands the session over to {@link WorkoutActivity}, a
 * full-screen tracker — so this screen is only ever a launcher and a history,
 * and nothing here keeps a stopwatch running in the background.
 *
 * The grid shows only the activities worth timing on a phone; the rest live
 * behind "more". The history sits at the bottom, one icon per activity, and is
 * deleted through an explicit selection mode (long-press a row, or the "select"
 * action) rather than the old hidden swipe gesture, which fought the
 * surrounding ScrollView and left rows that could not be removed.
 */
public class SportFragment extends Fragment {

    private static final String PREF = "dial_sender_prefs";
    private static final String KEY_SESSIONS = "sport_sessions"; // "start|name|durSec|kcal,..."
    private static final String KEY_HIDDEN = "sport_hidden_starts";

    /**
     * Palette and shape come from the active theme. These used to be six fixed
     * literals, so this tab stayed Midnight-cyan whichever theme was picked.
     */
    private com.example.dialsender.theme.ThemeManager.AppTheme cachedTheme;

    private com.example.dialsender.theme.ThemeManager.AppTheme theme() {
        if (cachedTheme == null) {
            cachedTheme = com.example.dialsender.theme.ThemeManager.getTheme(requireContext());
        }
        return cachedTheme;
    }

    private SharedPreferences prefs;

    private TextView histAction, histCount;
    private LinearLayout historyContainer, tileGrid;

    /** Selection mode state, keyed by the session start timestamp. */
    private boolean selectionMode = false;
    private final Set<String> selected = new LinkedHashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        prefs = requireContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        migrateWatchTimestamps();
        dedupeStoredWorkouts();
        migrateWatchSessions();


        ScrollView scroll = new ScrollView(requireContext());
        scroll.setBackgroundColor(theme().bgPrimary);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));

        TextView title = new TextView(requireContext());
        title.setText(getString(R.string.nav_deporte));
        title.setTextColor(theme().textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView prompt = new TextView(requireContext());
        prompt.setText(getString(R.string.sport_pick_prompt));
        prompt.setTextColor(theme().textSecondary);
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        prompt.setPadding(0, dp(4), 0, dp(20));
        root.addView(prompt);

        root.addView(buildTileGrid());

        root.addView(buildHistoryHeader());
        historyContainer = new LinearLayout(requireContext());
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(historyContainer);

        renderTiles();
        renderHistory();

        scroll.addView(root);
        return scroll;
    }

    // ========== Activity picker ==========

    private View buildTileGrid() {
        tileGrid = new LinearLayout(requireContext());
        tileGrid.setOrientation(LinearLayout.VERTICAL);
        return tileGrid;
    }

    private void renderTiles() {
        tileGrid.removeAllViews();
        List<Sport> primary = new ArrayList<>();
        for (Sport sport : Sport.values()) {
            if (sport.primary)
                primary.add(sport);
        }

        final int columns = 2;
        List<View> cells = new ArrayList<>();
        for (Sport sport : primary)
            cells.add(sportTile(sport));
        cells.add(moreTile());

        for (int i = 0; i < cells.size(); i += columns) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, i == 0 ? 0 : dp(12), 0, 0);
            row.setLayoutParams(rowLp);
            for (int c = 0; c < columns; c++) {
                int idx = i + c;
                View cell = idx < cells.size()
                        ? cells.get(idx)
                        : new View(requireContext()); // keeps the grid aligned
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(c == 0 ? 0 : dp(12), 0, 0, 0);
                cell.setLayoutParams(lp);
                row.addView(cell);
            }
            tileGrid.addView(row);
        }
    }

    /**
     * A tile is a start button, not a radio button — tapping one opens the
     * tracker straight away. Each carries its activity's colour so the grid
     * reads as a set of sports rather than a list of settings.
     */
    private View sportTile(final Sport sport) {
        LinearLayout tile = tileShell();

        android.widget.FrameLayout badge = new android.widget.FrameLayout(requireContext());
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(withAlpha(sport.accent, 38));
        badge.setBackground(badgeBg);
        badge.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));

        ImageView icon = new ImageView(requireContext());
        icon.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                dp(30), dp(30), Gravity.CENTER));
        icon.setImageDrawable(tinted(sport.iconRes, sport.accent));
        badge.addView(icon);
        tile.addView(badge);

        TextView label = new TextView(requireContext());
        label.setText(sport.label(requireContext()));
        label.setTextColor(theme().textPrimary);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(null, Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(12), 0, 0);
        label.setMaxLines(1);
        tile.addView(label);

        TextView go = new TextView(requireContext());
        go.setText(getString(R.string.sport_start));
        go.setTextColor(sport.accent);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        go.setAllCaps(true);
        go.setLetterSpacing(0.12f);
        go.setGravity(Gravity.CENTER);
        go.setPadding(0, dp(3), 0, 0);
        tile.addView(go);

        tile.setOnClickListener(v -> selectSport(sport));
        return tile;
    }

    private View moreTile() {
        LinearLayout tile = tileShell();

        android.widget.FrameLayout badge = new android.widget.FrameLayout(requireContext());
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(0xFF232B34);
        badge.setBackground(badgeBg);
        badge.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView dots = new TextView(requireContext());
        dots.setText("•••");
        dots.setTextColor(0xFFC9D1D9);
        dots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        dots.setGravity(Gravity.CENTER);
        dots.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        badge.addView(dots);
        tile.addView(badge);

        TextView label = new TextView(requireContext());
        label.setText(getString(R.string.sport_more));
        label.setTextColor(theme().textPrimary);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(null, Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(12), 0, 0);
        tile.addView(label);

        TextView go = new TextView(requireContext());
        go.setText(getString(R.string.sport_more_count, Sport.values().length - 5));
        go.setTextColor(theme().textSecondary);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        go.setAllCaps(true);
        go.setLetterSpacing(0.12f);
        go.setGravity(Gravity.CENTER);
        go.setPadding(0, dp(3), 0, 0);
        tile.addView(go);

        tile.setOnClickListener(v -> showMoreSports());
        return tile;
    }

    private LinearLayout tileShell() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout tile = new LinearLayout(requireContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(10), dp(22), dp(10), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme().bgCard);
        bg.setCornerRadius(20 * density);
        bg.setStroke((int) (1 * density), theme().cardBorder);
        tile.setBackground(bg);
        tile.setClickable(true);
        return tile;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private void showMoreSports() {
        final List<Sport> rest = new ArrayList<>();
        for (Sport s : Sport.values()) {
            if (!s.primary)
                rest.add(s);
        }
        String[] labels = new String[rest.size()];
        for (int i = 0; i < rest.size(); i++)
            labels[i] = rest.get(i).label(requireContext());

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sport_more_title)
                .setItems(labels, (d, which) -> selectSport(rest.get(which)))
                .show();
    }

    private void selectSport(Sport sport) {
        Intent intent = new Intent(requireContext(), WorkoutActivity.class);
        intent.putExtra(WorkoutActivity.EXTRA_SPORT, sport.mode);
        startActivity(intent);
    }

    private static String formatDuration(int seconds) {
        if (seconds >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d",
                    seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    // ========== History ==========

    private View buildHistoryHeader() {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(28), 0, dp(10));
        header.setLayoutParams(lp);

        histCount = new TextView(requireContext());
        histCount.setTextColor(theme().textSecondary);
        histCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        histCount.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(histCount);

        histAction = new TextView(requireContext());
        histAction.setTextColor(theme().accentPrimary);
        histAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        histAction.setTypeface(null, Typeface.BOLD);
        histAction.setPadding(dp(10), dp(6), dp(4), dp(6));
        histAction.setOnClickListener(v -> {
            if (selectionMode)
                exitSelectionMode();
            else
                enterSelectionMode(null);
        });
        header.addView(histAction);
        return header;
    }

    /**
     * Unified history: phone-recorded sessions merged with watch workouts,
     * deduplicated by start timestamp, newest first. Records are the standard
     * "start|name|durSec|kcal" that SportDetailActivity expects.
     */
    private List<String> collectSessions() {
        java.util.LinkedHashMap<Long, String> byStart = new java.util.LinkedHashMap<>();
        Set<String> hidden = hiddenStarts();

        String all = prefs.getString(KEY_SESSIONS, "");
        if (!all.isEmpty()) {
            for (String rec : all.split(",")) {
                String[] p = rec.split("\\|");
                if (p.length < 4)
                    continue;
                try {
                    long start = Long.parseLong(p[0].trim());
                    if (!isPlausibleStart(start))
                        continue;
                    if (hidden.contains(String.valueOf(start)) || byStart.containsKey(start))
                        continue;
                    byStart.put(start, start + "|" + p[1] + "|" + p[2] + "|" + p[3]);
                } catch (Exception ignored) {
                }
            }
        }

        // Watch workouts:
        // start:end:duration:alt:air:spm:mode:step:distance:calorie:...
        String workouts = prefs.getString("health_workout", "");
        if (!workouts.isEmpty()) {
            for (String w : workouts.split(",")) {
                String[] f = w.split(":");
                if (f.length < 10)
                    continue;
                try {
                    long start = Long.parseLong(f[0].trim());
                    int durSec = Integer.parseInt(f[2].trim());
                    if (!isPlausibleStart(start) || durSec <= 0)
                        continue;
                    if (hidden.contains(String.valueOf(start)) || byStart.containsKey(start))
                        continue;
                    int mode = Integer.parseInt(f[6].trim());
                    byStart.put(start, start + "|" + Sport.nameForMode(requireContext(), mode)
                            + "|" + durSec + "|" + kcalFromRaw(f[9]));
                } catch (Exception ignored) {
                }
            }
        }

        List<String> out = new ArrayList<>(byStart.values());
        java.util.Collections.sort(out, (a, b) -> Long.compare(startOf(b), startOf(a)));
        return out;
    }

    /**
     * The watch reports workout calories in calories, not kilocalories — a
     * 1h54 climb came back as 254813, which the old screen printed verbatim as
     * "254813 kcal". See docs/protocols/03-HEALTH-DATA.md.
     */
    private static int kcalFromRaw(String raw) {
        try {
            long cal = Long.parseLong(raw.trim());
            if (cal < 0)
                return 0;
            return (int) Math.round(cal / 1000.0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Rejects corrupt records. A partially-parsed frame once stored a workout
     * starting at epoch -853976960 (1942), which then sat in the history as an
     * undeletable "Sport 0" row.
     */
    private static boolean isPlausibleStart(long start) {
        long now = System.currentTimeMillis() / 1000L;
        return start > 1577836800L /* 2020-01-01 */ && start < now + 86400L;
    }

    private static long startOf(String record) {
        try {
            return Long.parseLong(record.split("\\|")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** True if health_workout still carries this start, i.e. a sync can re-add it. */
    private boolean isWatchWorkout(String start) {
        for (String w : prefs.getString("health_workout", "").split(",")) {
            String[] f = w.split(":");
            if (f.length >= 10 && f[0].trim().equals(start))
                return true;
        }
        return false;
    }

    private Set<String> hiddenStarts() {
        Set<String> out = new HashSet<>();
        for (String s : prefs.getString(KEY_HIDDEN, "").split(",")) {
            String t = s.trim();
            if (!t.isEmpty())
                out.add(t);
        }
        return out;
    }

    private void renderHistory() {
        if (historyContainer == null)
            return;
        historyContainer.removeAllViews();
        final List<String> sessions = collectSessions();

        // Drop selections that no longer exist.
        selected.retainAll(startsOf(sessions));
        if (selectionMode && sessions.isEmpty())
            selectionMode = false;
        updateHistoryHeader(sessions.size());

        if (sessions.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(getString(R.string.sport_no_sessions));
            empty.setTextColor(theme().textMuted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            historyContainer.addView(empty);
            return;
        }

        if (selectionMode)
            historyContainer.addView(deleteBar());

        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM · HH:mm", Locale.getDefault());
        for (final String rec : sessions) {
            String[] p = rec.split("\\|");
            if (p.length < 4)
                continue;
            historyContainer.addView(historyRow(rec, p, fmt));
        }
    }

    private View historyRow(final String rec, String[] p, SimpleDateFormat fmt) {
        final String start = p[0];
        final boolean isSelected = selected.contains(start);
        float density = getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(16), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme().bgCard);
        bg.setCornerRadius(16 * density);
        if (isSelected) {
            bg.setStroke((int) (2 * density), theme().accentPrimary);
        } else {
            bg.setStroke((int) (1 * density), theme().cardBorder);
        }
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        row.setLayoutParams(lp);

        // Activity icon, in a tinted round badge.
        android.widget.FrameLayout badge = new android.widget.FrameLayout(requireContext());
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(isSelected ? theme().accentPrimary : 0x22FFFFFF);
        badge.setBackground(badgeBg);
        badge.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));

        ImageView icon = new ImageView(requireContext());
        android.widget.FrameLayout.LayoutParams iconLp = new android.widget.FrameLayout.LayoutParams(
                dp(24), dp(24), Gravity.CENTER);
        icon.setLayoutParams(iconLp);
        Sport rowSport = Sport.byName(requireContext(), p[1]);
        int rowAccent = rowSport != null ? rowSport.accent : theme().accentPrimary;
        icon.setImageDrawable(tinted(Sport.iconForName(requireContext(), p[1]),
                isSelected ? 0xFF06121A : rowAccent));
        badge.addView(icon);
        row.addView(badge);

        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMargins(dp(13), 0, dp(8), 0);
        col.setLayoutParams(colLp);

        TextView name = new TextView(requireContext());
        name.setText(p[1]);
        name.setTextColor(theme().textPrimary);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(1);
        col.addView(name);

        TextView when = new TextView(requireContext());
        try {
            when.setText(fmt.format(new Date(Long.parseLong(start) * 1000L)));
        } catch (Exception e) {
            when.setText("");
        }
        when.setTextColor(theme().textSecondary);
        when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        col.addView(when);
        row.addView(col);

        LinearLayout stats = new LinearLayout(requireContext());
        stats.setOrientation(LinearLayout.VERTICAL);
        stats.setGravity(Gravity.END);

        TextView dur = new TextView(requireContext());
        dur.setText(formatDuration(parseInt(p[2])));
        dur.setTextColor(theme().accentPrimary);
        dur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dur.setTypeface(null, Typeface.BOLD);
        dur.setGravity(Gravity.END);
        stats.addView(dur);

        TextView kcal = new TextView(requireContext());
        kcal.setText(p[3] + " kcal");
        kcal.setTextColor(theme().textSecondary);
        kcal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        kcal.setGravity(Gravity.END);
        stats.addView(kcal);
        row.addView(stats);

        row.setOnClickListener(v -> {
            if (selectionMode) {
                toggleSelected(start);
            } else {
                Intent intent = new Intent(requireContext(), SportDetailActivity.class);
                intent.putExtra("session_record", rec);
                startActivity(intent);
            }
        });
        row.setOnLongClickListener(v -> {
            enterSelectionMode(start);
            return true;
        });
        return row;
    }

    private View deleteBar() {
        TextView btn = new TextView(requireContext());
        btn.setText(getString(R.string.sport_delete_selected, selected.size()));
        btn.setTextColor(theme().textPrimary);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected.isEmpty() ? 0xFF3A2226 : 0xFFEF4444);
        bg.setCornerRadius(dp(14));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        btn.setLayoutParams(lp);
        btn.setEnabled(!selected.isEmpty());
        btn.setOnClickListener(v -> confirmDeleteSelected());
        return btn;
    }

    private Set<String> startsOf(List<String> sessions) {
        Set<String> out = new HashSet<>();
        for (String s : sessions)
            out.add(s.split("\\|")[0]);
        return out;
    }

    private void updateHistoryHeader(int total) {
        if (selectionMode) {
            histCount.setText(getString(R.string.sport_n_selected, selected.size()));
            histAction.setText(getString(R.string.cancel));
        } else {
            histCount.setText(getString(R.string.sport_history));
            histAction.setText(total > 0 ? getString(R.string.sport_select) : "");
        }
    }

    private void enterSelectionMode(@Nullable String initial) {
        selectionMode = true;
        selected.clear();
        if (initial != null)
            selected.add(initial);
        renderHistory();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selected.clear();
        renderHistory();
    }

    private void toggleSelected(String start) {
        if (!selected.remove(start))
            selected.add(start);
        renderHistory();
    }

    private void confirmDeleteSelected() {
        final int n = selected.size();
        if (n == 0)
            return;
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.sport_delete_title))
                .setMessage(getResources().getQuantityString(R.plurals.sport_delete_msg, n, n))
                .setPositiveButton(getString(R.string.delete), (d, w) -> {
                    deleteSessions(new ArrayList<>(selected));
                    exitSelectionMode();
                    Toast.makeText(requireContext(), getString(R.string.sport_workout_deleted),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    /**
     * Deletes by start timestamp, not by matching the whole record string: the
     * rendered record is rebuilt from the watch data and never matched the raw
     * stored line, which is why entries kept coming back.
     */
    private void deleteSessions(List<String> starts) {
        Set<String> kill = new HashSet<>(starts);

        StringBuilder kept = new StringBuilder();
        for (String s : prefs.getString(KEY_SESSIONS, "").split(",")) {
            if (s.trim().isEmpty())
                continue;
            String start = s.split("\\|")[0].trim();
            if (kill.contains(start))
                continue;
            if (kept.length() > 0)
                kept.append(",");
            kept.append(s);
        }

        // Watch workouts live in health_workout and are re-added on every sync,
        // so they are suppressed by start timestamp instead of being removed.
        // Phone-recorded sessions are gone for good once dropped above, so they
        // are not added — otherwise the suppression list would grow forever.
        Set<String> hidden = hiddenStarts();
        for (String start : kill) {
            if (isWatchWorkout(start))
                hidden.add(start);
        }

        prefs.edit()
                .putString(KEY_SESSIONS, kept.toString())
                .putString(KEY_HIDDEN, join(hidden))
                .apply();

        // The GPS trace belongs to the session; drop it too.
        for (String start : kill) {
            try {
                WorkoutTrack.delete(requireContext(), Long.parseLong(start));
            } catch (Exception ignored) {
            }
        }
        renderHistory();
    }

    /**
     * One-time cleanup of history written by earlier versions: workouts synced
     * from the watch were copied into sport_sessions with raw calorie counts
     * and, when a frame was mis-parsed, impossible timestamps. They are derived
     * from health_workout now, so the copies are dropped.
     */
    private void migrateWatchSessions() {
        if (prefs.getBoolean("sport_sessions_migrated", false)) {
            pruneOrphanTracks();
            return;
        }

        Set<String> watchStarts = new HashSet<>();
        for (String w : prefs.getString("health_workout", "").split(",")) {
            String[] f = w.split(":");
            if (f.length >= 10)
                watchStarts.add(f[0].trim());
        }

        StringBuilder kept = new StringBuilder();
        for (String s : prefs.getString(KEY_SESSIONS, "").split(",")) {
            if (s.trim().isEmpty())
                continue;
            String[] p = s.split("\\|");
            if (p.length < 4)
                continue;
            String start = p[0].trim();
            if (watchStarts.contains(start))
                continue;
            try {
                if (!isPlausibleStart(Long.parseLong(start)))
                    continue;
            } catch (Exception e) {
                continue;
            }
            if (kept.length() > 0)
                kept.append(",");
            kept.append(s);
        }

        prefs.edit()
                .putString(KEY_SESSIONS, kept.toString())
                .putBoolean("sport_sessions_migrated", true)
                .apply();
        pruneOrphanTracks();
    }

    /**
     * A GPS trace only ever belongs to a phone-recorded session, so any trace
     * whose session is gone is dead weight in the prefs file.
     */
    /**
     * Watch timestamps used to be stored as local-wall-clock seconds treated as
     * UTC, so every synced record reads one zone offset late. Shift the stored
     * ones once, rather than losing history the watch may no longer hold.
     */
    private void migrateWatchTimestamps() {
        if (prefs.getBoolean("watch_time_offset_fixed", false))
            return;

        int offsetSec = java.util.TimeZone.getDefault()
                .getOffset(System.currentTimeMillis()) / 1000;
        if (offsetSec == 0) {
            prefs.edit().putBoolean("watch_time_offset_fixed", true).apply();
            return;
        }

        SharedPreferences.Editor editor = prefs.edit();
        String workouts = prefs.getString("health_workout", "");
        if (!workouts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String w : workouts.split(",")) {
                String[] f = w.split(":");
                if (f.length < 10)
                    continue;
                try {
                    long start = Long.parseLong(f[0].trim());
                    long end = Long.parseLong(f[1].trim());
                    if (!isPlausibleStart(start))
                        continue; // corrupt record, drop it while we are here
                    f[0] = String.valueOf(start - offsetSec);
                    f[1] = String.valueOf(end - offsetSec);
                } catch (Exception e) {
                    continue;
                }
                if (sb.length() > 0)
                    sb.append(",");
                sb.append(String.join(":", f));
            }
            editor.putString("health_workout", sb.toString());
        }

        // Suppressed workouts are keyed by start, so they move with them.
        Set<String> shifted = new HashSet<>();
        for (String h : hiddenStarts()) {
            try {
                shifted.add(String.valueOf(Long.parseLong(h) - offsetSec));
            } catch (Exception ignored) {
            }
        }
        editor.putString(KEY_HIDDEN, join(shifted));
        editor.putBoolean("watch_time_offset_fixed", true).apply();
    }

    /**
     * Collapses duplicate workout records. The watch reports the same session
     * under WORKOUT (0x06) and WORKOUT2 (0x0E), which arrive as separate
     * packets and each appended their own copy, so health_workout accumulated
     * a second set on every sync. Deduping on write cannot repair what is
     * already stored — once the watch has handed a record over it returns an
     * empty page — so it is also done here, on load.
     */
    private void dedupeStoredWorkouts() {
        String stored = prefs.getString("health_workout", "");
        if (stored.isEmpty())
            return;
        java.util.LinkedHashMap<String, String> byStart = new java.util.LinkedHashMap<>();
        for (String rec : stored.split(",")) {
            int colon = rec.indexOf(':');
            if (colon <= 0)
                continue;
            String start = rec.substring(0, colon).trim();
            if (!byStart.containsKey(start))
                byStart.put(start, rec);
        }
        StringBuilder sb = new StringBuilder();
        for (String rec : byStart.values()) {
            if (sb.length() > 0)
                sb.append(",");
            sb.append(rec);
        }
        if (!sb.toString().equals(stored))
            prefs.edit().putString("health_workout", sb.toString()).apply();
    }

    private void pruneOrphanTracks() {
        Set<String> live = new HashSet<>();
        for (String rec : prefs.getString(KEY_SESSIONS, "").split(",")) {
            String[] p = rec.split("\\|");
            if (p.length >= 1 && !p[0].trim().isEmpty())
                live.add(p[0].trim());
        }
        SharedPreferences.Editor editor = null;
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("sport_track_"))
                continue;
            if (live.contains(key.substring("sport_track_".length())))
                continue;
            if (editor == null)
                editor = prefs.edit();
            editor.remove(key);
        }
        if (editor != null)
            editor.apply();
    }

    private static String join(Set<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (v == null || v.trim().isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append(",");
            sb.append(v.trim());
        }
        return sb.toString();
    }

    // ========== Small helpers ==========

    private Drawable tinted(int resId, int color) {
        Drawable d = ContextCompat.getDrawable(requireContext(), resId);
        if (d != null) {
            d = d.mutate();
            d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        return d;
    }

    private TextView sectionLabel(String text, int topMargin) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextColor(theme().textSecondary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), topMargin, 0, dp(10));
        tv.setLayoutParams(lp);
        return tv;
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private TextView pill(String text, int bgColor, int textColor) {
        TextView b = new TextView(requireContext());
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(26));
        b.setBackground(bg);
        b.setPadding(dp(28), dp(14), dp(28), dp(14));
        b.setClickable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private LinearLayout card() {
        LinearLayout ll = new LinearLayout(requireContext());
        ll.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme().bgCard);
        bg.setCornerRadius(theme().radiusCard);
        ll.setBackground(bg);
        ll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return ll;
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (key == null)
            return;
        if (key.equals("health_workout") || key.equals(KEY_SESSIONS)) {
            if (isAdded() && historyContainer != null)
                requireActivity().runOnUiThread(this::renderHistory);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        renderHistory();
    }

    @Override
    public void onPause() {
        super.onPause();
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
