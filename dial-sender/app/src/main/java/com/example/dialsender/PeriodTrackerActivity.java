package com.example.dialsender;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.dialsender.ble.BleManager;
import com.example.dialsender.ble.PeriodTrackerManager;
import com.example.dialsender.theme.ThemeManager;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class PeriodTrackerActivity extends AppCompatActivity {

    private TextView badgePhase;
    private TextView txtDayInCycle;
    private TextView txtCycleSummary;
    private TextView txtNextPeriodDays;
    private TextView txtNextOvulationDays;
    private TextView txtMonthYear;
    private LinearLayout layoutWeekdaysHeader;
    private GridLayout gridCalendar;
    private Button btnLogPeriodToday;
    private Button btnLogPeriodDate;

    private Calendar displayedMonth;
    private ThemeManager.AppTheme theme;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_period_tracker);

        theme = ThemeManager.getTheme(this);
        displayedMonth = Calendar.getInstance();
        displayedMonth.set(Calendar.DAY_OF_MONTH, 1);

        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        ImageView btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        badgePhase = findViewById(R.id.badgePhase);
        txtDayInCycle = findViewById(R.id.txtDayInCycle);
        txtCycleSummary = findViewById(R.id.txtCycleSummary);
        txtNextPeriodDays = findViewById(R.id.txtNextPeriodDays);
        txtNextOvulationDays = findViewById(R.id.txtNextOvulationDays);
        txtMonthYear = findViewById(R.id.txtMonthYear);
        layoutWeekdaysHeader = findViewById(R.id.layoutWeekdaysHeader);
        gridCalendar = findViewById(R.id.gridCalendar);
        btnLogPeriodToday = findViewById(R.id.btnLogPeriodToday);
        btnLogPeriodDate = findViewById(R.id.btnLogPeriodDate);

        findViewById(R.id.btnPrevMonth).setOnClickListener(v -> {
            displayedMonth.add(Calendar.MONTH, -1);
            renderCalendar();
        });

        findViewById(R.id.btnNextMonth).setOnClickListener(v -> {
            displayedMonth.add(Calendar.MONTH, 1);
            renderCalendar();
        });

        btnLogPeriodToday.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            PeriodTrackerManager.logPeriodStart(this, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
            Toast.makeText(this, R.string.period_sync_success, Toast.LENGTH_SHORT).show();
            render();
        });

        btnLogPeriodDate.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                PeriodTrackerManager.logPeriodStart(this, year, month + 1, dayOfMonth);
                Toast.makeText(this, R.string.period_sync_success, Toast.LENGTH_SHORT).show();
                render();
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
            picker.getDatePicker().setMaxDate(System.currentTimeMillis());
            picker.show();
        });

        buildWeekdaysHeader();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        PeriodTrackerManager.CycleStatus status = PeriodTrackerManager.getCycleStatus(this);

        if (!status.enabled) {
            badgePhase.setText(R.string.state_off);
            badgePhase.setBackground(createBadgeDrawable(Color.parseColor("#475569")));
            txtDayInCycle.setText("—");
            txtCycleSummary.setText(R.string.period_disabled_hint);
            txtNextPeriodDays.setText("—");
            txtNextOvulationDays.setText("—");
        } else {
            txtDayInCycle.setText(getString(R.string.period_day_in_cycle, status.currentDayInCycle));
            txtCycleSummary.setText(getString(R.string.period_cycle_summary, status.totalCycleDays, status.durationDays));

            int phaseColor;
            int phaseTextRes;
            switch (status.currentPhase) {
                case MENSTRUATION:
                    phaseColor = Color.parseColor("#F43F5E"); // Rose / Red
                    phaseTextRes = R.string.period_phase_menstruation;
                    break;
                case FERTILE_WINDOW:
                    phaseColor = Color.parseColor("#A855F7"); // Purple
                    phaseTextRes = R.string.period_phase_fertile;
                    break;
                case OVULATION_DAY:
                    phaseColor = Color.parseColor("#EC4899"); // Pink/Magenta
                    phaseTextRes = R.string.period_phase_ovulation;
                    break;
                case LUTEAL_SAFE:
                    phaseColor = Color.parseColor("#38BDF8"); // Light blue
                    phaseTextRes = R.string.period_phase_luteal;
                    break;
                case FOLLICULAR_SAFE:
                default:
                    phaseColor = Color.parseColor("#22D3EE"); // Cyan
                    phaseTextRes = R.string.period_phase_follicular;
                    break;
            }

            badgePhase.setText(phaseTextRes);
            badgePhase.setBackground(createBadgeDrawable(phaseColor));

            if (status.daysUntilNextPeriod == 0) {
                txtNextPeriodDays.setText(R.string.period_next_today);
            } else {
                txtNextPeriodDays.setText(getString(R.string.period_next_in, status.daysUntilNextPeriod));
            }

            if (status.daysUntilOvulation == 0) {
                txtNextOvulationDays.setText(R.string.period_ovulation_today);
            } else if (status.daysUntilFertileWindow > 0) {
                // Both milestones, because the watch quotes the fertile-window
                // one and this screen used to quote only the ovulation day.
                txtNextOvulationDays.setText(getString(R.string.period_ovulation_in, status.daysUntilOvulation)
                        + " · " + getString(R.string.period_fertile_in, status.daysUntilFertileWindow));
            } else {
                txtNextOvulationDays.setText(getString(R.string.period_ovulation_in, status.daysUntilOvulation));
            }
        }

        renderCalendar();
    }

    private void buildWeekdaysHeader() {
        layoutWeekdaysHeader.removeAllViews();
        String[] days = {"L", "M", "M", "J", "V", "S", "D"};
        if (Locale.getDefault().getLanguage().startsWith("en")) {
            days = new String[]{"M", "T", "W", "T", "F", "S", "S"};
        }
        for (String day : days) {
            TextView tv = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            tv.setGravity(Gravity.CENTER);
            tv.setText(day);
            tv.setTextColor(theme.textMuted);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            layoutWeekdaysHeader.addView(tv);
        }
    }

    private void renderCalendar() {
        SimpleDateFormat monthFmt = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
        String monthStr = monthFmt.format(displayedMonth.getTime());
        txtMonthYear.setText(monthStr.substring(0, 1).toUpperCase() + monthStr.substring(1));

        gridCalendar.removeAllViews();

        Calendar cal = (Calendar) displayedMonth.clone();
        cal.set(Calendar.DAY_OF_MONTH, 1);

        // Day of week: Sunday=1, Monday=2 ... Saturday=7
        int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        int emptySlots = (firstDayOfWeek == Calendar.SUNDAY) ? 6 : (firstDayOfWeek - Calendar.MONDAY);

        int maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        Calendar today = Calendar.getInstance();
        boolean isCurrentMonth = (today.get(Calendar.YEAR) == displayedMonth.get(Calendar.YEAR)
                && today.get(Calendar.MONTH) == displayedMonth.get(Calendar.MONTH));
        int currentDay = today.get(Calendar.DAY_OF_MONTH);

        int year = displayedMonth.get(Calendar.YEAR);
        int month = displayedMonth.get(Calendar.MONTH) + 1;

        // Empty cells before start
        for (int i = 0; i < emptySlots; i++) {
            View empty = new View(this);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = (int) (38 * getResources().getDisplayMetrics().density);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            empty.setLayoutParams(lp);
            gridCalendar.addView(empty);
        }

        // Days of month
        boolean isCycleEnabled = PeriodTrackerManager.isEnabled(this);

        for (int day = 1; day <= maxDays; day++) {
            TextView dayView = new TextView(this);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = (int) (38 * getResources().getDisplayMetrics().density);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(2, 2, 2, 2);
            dayView.setLayoutParams(lp);
            dayView.setGravity(Gravity.CENTER);
            dayView.setText(String.valueOf(day));
            dayView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);

            if (isCycleEnabled) {
                PeriodTrackerManager.Phase phase = PeriodTrackerManager.getPhaseForDate(this, year, month, day);
                int bgCol;
                switch (phase) {
                    case MENSTRUATION:
                        bgCol = Color.parseColor("#4DF43F5E"); // translucent red
                        dayView.setTextColor(Color.parseColor("#FFF43F5E"));
                        break;
                    case FERTILE_WINDOW:
                    case OVULATION_DAY:
                        bgCol = Color.parseColor("#4DA855F7"); // translucent purple
                        dayView.setTextColor(Color.parseColor("#FFA855F7"));
                        break;
                    case LUTEAL_SAFE:
                    case FOLLICULAR_SAFE:
                    default:
                        bgCol = Color.parseColor("#2638BDF8"); // translucent blue
                        dayView.setTextColor(theme.textPrimary);
                        break;
                }

                GradientDrawable shape = new GradientDrawable();
                shape.setShape(GradientDrawable.OVAL);
                shape.setColor(bgCol);

                if (isCurrentMonth && day == currentDay) {
                    shape.setStroke((int) (2 * getResources().getDisplayMetrics().density), theme.accentPrimary);
                    dayView.setTypeface(Typeface.DEFAULT_BOLD);
                }

                dayView.setBackground(shape);
            } else {
                dayView.setTextColor(theme.textPrimary);
                if (isCurrentMonth && day == currentDay) {
                    GradientDrawable shape = new GradientDrawable();
                    shape.setShape(GradientDrawable.OVAL);
                    shape.setStroke((int) (2 * getResources().getDisplayMetrics().density), theme.accentPrimary);
                    dayView.setBackground(shape);
                    dayView.setTypeface(Typeface.DEFAULT_BOLD);
                }
            }

            final int selectedDay = day;
            dayView.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.period_mark_start_date))
                        .setMessage(selectedDay + "/" + month + "/" + year)
                        .setPositiveButton(R.string.save, (d, w) -> {
                            PeriodTrackerManager.logPeriodStart(this, year, month, selectedDay);
                            render();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });

            gridCalendar.addView(dayView);
        }
    }

    private void showSettingsDialog() {
        SharedPreferences sp = PeriodTrackerManager.getPrefs(this);
        boolean enabled = sp.getBoolean(PeriodTrackerManager.PREF_GIRLCARE_ENABLED, false);
        boolean reminderEn = sp.getBoolean(PeriodTrackerManager.PREF_GIRLCARE_REMINDER_ENABLED, true);
        int remH = sp.getInt(PeriodTrackerManager.PREF_GIRLCARE_REMINDER_HOUR, PeriodTrackerManager.DEFAULT_REMINDER_HOUR);
        int remM = sp.getInt(PeriodTrackerManager.PREF_GIRLCARE_REMINDER_MINUTE, PeriodTrackerManager.DEFAULT_REMINDER_MINUTE);
        int pAdv = sp.getInt(PeriodTrackerManager.PREF_GIRLCARE_PERIOD_ADVANCE, PeriodTrackerManager.DEFAULT_PERIOD_ADVANCE);
        int oAdv = sp.getInt(PeriodTrackerManager.PREF_GIRLCARE_OVULATION_ADVANCE, PeriodTrackerManager.DEFAULT_OVULATION_ADVANCE);
        int duration = sp.getInt(PeriodTrackerManager.PREF_GIRLCARE_DURATION, PeriodTrackerManager.DEFAULT_DURATION);
        int cycle = sp.getInt(PeriodTrackerManager.PREF_GIRLCARE_CYCLE, PeriodTrackerManager.DEFAULT_CYCLE);

        Calendar now = Calendar.getInstance();
        int lastY = sp.getInt(PeriodTrackerManager.PREF_GIRLCARE_LAST_YEAR, now.get(Calendar.YEAR));
        int lastM = sp.getInt(PeriodTrackerManager.PREF_GIRLCARE_LAST_MONTH, now.get(Calendar.MONTH) + 1);
        int lastD = sp.getInt(PeriodTrackerManager.PREF_GIRLCARE_LAST_DAY, now.get(Calendar.DAY_OF_MONTH));

        final int[] dateHolder = {lastY, lastM, lastD};
        final int[] timeHolder = {remH, remM};

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad);

        // Switch enabled
        SwitchMaterial swEnabled = new SwitchMaterial(this);
        swEnabled.setText(R.string.period_tracker_title);
        swEnabled.setChecked(enabled);
        layout.addView(swEnabled);

        // Last period date picker button
        Button btnPickDate = new Button(this);
        btnPickDate.setText(getString(R.string.period_last_period_date) + ": " + dateHolder[2] + "/" + dateHolder[1] + "/" + dateHolder[0]);
        btnPickDate.setOnClickListener(v -> {
            DatePickerDialog picker = new DatePickerDialog(this, (view, y, m, d) -> {
                dateHolder[0] = y;
                dateHolder[1] = m + 1;
                dateHolder[2] = d;
                btnPickDate.setText(getString(R.string.period_last_period_date) + ": " + d + "/" + (m + 1) + "/" + y);
            }, dateHolder[0], dateHolder[1] - 1, dateHolder[2]);
            // A start date in the future is not a cycle the phone or the watch
            // can reason about: both wrap it, to different wrong answers.
            picker.getDatePicker().setMaxDate(System.currentTimeMillis());
            picker.show();
        });
        layout.addView(btnPickDate);

        // Period duration
        TextView lblDur = new TextView(this);
        lblDur.setText(R.string.period_duration_label);
        lblDur.setTextColor(theme.textSecondary);
        lblDur.setPadding(0, pad / 2, 0, 4);
        layout.addView(lblDur);

        EditText edtDuration = new EditText(this);
        edtDuration.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        edtDuration.setText(String.valueOf(duration));
        layout.addView(edtDuration);

        // Cycle length
        TextView lblCycle = new TextView(this);
        lblCycle.setText(R.string.period_cycle_length_label);
        lblCycle.setTextColor(theme.textSecondary);
        lblCycle.setPadding(0, pad / 2, 0, 4);
        layout.addView(lblCycle);

        EditText edtCycle = new EditText(this);
        edtCycle.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        edtCycle.setText(String.valueOf(cycle));
        layout.addView(edtCycle);

        // Reminder Switch
        SwitchMaterial swReminder = new SwitchMaterial(this);
        swReminder.setText(R.string.period_reminders_label);
        swReminder.setChecked(reminderEn);
        layout.addView(swReminder);

        // Reminder Time button
        Button btnPickTime = new Button(this);
        btnPickTime.setText(getString(R.string.period_reminder_time_label) + ": " + String.format(Locale.US, "%02d:%02d", timeHolder[0], timeHolder[1]));
        btnPickTime.setOnClickListener(v -> {
            new TimePickerDialog(this, (view, h, m) -> {
                timeHolder[0] = h;
                timeHolder[1] = m;
                btnPickTime.setText(getString(R.string.period_reminder_time_label) + ": " + String.format(Locale.US, "%02d:%02d", h, m));
            }, timeHolder[0], timeHolder[1], true).show();
        });
        layout.addView(btnPickTime);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);

        new AlertDialog.Builder(this)
                .setTitle(R.string.period_settings)
                .setView(scroll)
                .setPositiveButton(R.string.save, (d, w) -> {
                    int dVal = 5;
                    int cVal = 28;
                    try {
                        dVal = Math.max(2, Math.min(15, Integer.parseInt(edtDuration.getText().toString().trim())));
                        cVal = Math.max(20, Math.min(45, Integer.parseInt(edtCycle.getText().toString().trim())));
                    } catch (Exception ignored) {}

                    sp.edit()
                            .putBoolean(PeriodTrackerManager.PREF_GIRLCARE_ENABLED, swEnabled.isChecked())
                            .putBoolean(PeriodTrackerManager.PREF_GIRLCARE_REMINDER_ENABLED, swReminder.isChecked())
                            .putInt(PeriodTrackerManager.PREF_GIRLCARE_REMINDER_HOUR, timeHolder[0])
                            .putInt(PeriodTrackerManager.PREF_GIRLCARE_REMINDER_MINUTE, timeHolder[1])
                            .putInt(PeriodTrackerManager.PREF_GIRLCARE_LAST_YEAR, dateHolder[0])
                            .putInt(PeriodTrackerManager.PREF_GIRLCARE_LAST_MONTH, dateHolder[1])
                            .putInt(PeriodTrackerManager.PREF_GIRLCARE_LAST_DAY, dateHolder[2])
                            .putInt(PeriodTrackerManager.PREF_GIRLCARE_DURATION, dVal)
                            .putInt(PeriodTrackerManager.PREF_GIRLCARE_CYCLE, cVal)
                            .apply();

                    PeriodTrackerManager.syncToWatch(this);
                    render();
                    Toast.makeText(this, R.string.period_sync_success, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private GradientDrawable createBadgeDrawable(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(20 * getResources().getDisplayMetrics().density);
        shape.setColor(color);
        return shape;
    }
}
