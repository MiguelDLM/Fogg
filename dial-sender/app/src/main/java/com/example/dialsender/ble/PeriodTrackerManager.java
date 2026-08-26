package com.example.dialsender.ble;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Period Tracker / Female Health Manager.
 * Handles menstrual cycle predictions, phase calculations, persistent settings,
 * and BLE sync with the smartwatch (BleKey.GIRL_CARE 0x021A).
 */
public class PeriodTrackerManager {

    private static final String PREF_NAME = "dial_sender_prefs";

    public static final String PREF_GIRLCARE_ENABLED = "girlcare_enabled";
    public static final String PREF_GIRLCARE_REMINDER_ENABLED = "girlcare_reminder_enabled";
    public static final String PREF_GIRLCARE_REMINDER_HOUR = "girlcare_reminder_hour";
    public static final String PREF_GIRLCARE_REMINDER_MINUTE = "girlcare_reminder_minute";
    public static final String PREF_GIRLCARE_PERIOD_ADVANCE = "girlcare_period_advance";
    public static final String PREF_GIRLCARE_OVULATION_ADVANCE = "girlcare_ovulation_advance";
    public static final String PREF_GIRLCARE_LAST_YEAR = "girlcare_last_year";
    public static final String PREF_GIRLCARE_LAST_MONTH = "girlcare_last_month"; // 1-12
    public static final String PREF_GIRLCARE_LAST_DAY = "girlcare_last_day";     // 1-31
    public static final String PREF_GIRLCARE_DURATION = "girlcare_duration";     // default 5 days
    public static final String PREF_GIRLCARE_CYCLE = "girlcare_cycle";           // default 28 days

    public static final int DEFAULT_DURATION = 5;
    public static final int DEFAULT_CYCLE = 28;
    public static final int DEFAULT_REMINDER_HOUR = 8;
    public static final int DEFAULT_REMINDER_MINUTE = 0;
    public static final int DEFAULT_PERIOD_ADVANCE = 2;
    public static final int DEFAULT_OVULATION_ADVANCE = 3;

    public enum Phase {
        MENSTRUATION,
        FOLLICULAR_SAFE,
        FERTILE_WINDOW,
        OVULATION_DAY,
        LUTEAL_SAFE
    }

    public static class CycleStatus {
        public boolean enabled;
        public int currentDayInCycle; // 1..cycle
        public int totalCycleDays;
        public int durationDays;
        public Phase currentPhase;
        public int daysUntilNextPeriod;
        public int daysUntilOvulation;
        public Calendar nextPeriodDate;
        public Calendar nextOvulationDate;
    }

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_GIRLCARE_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_GIRLCARE_ENABLED, enabled).apply();
    }

    /**
     * The stored start date, never in the future.
     *
     * A date ahead of today makes both sides of this feature lie, in different
     * ways. The phone computes {@code ((diff % cycle) + cycle) % cycle + 1},
     * so a start three days out reads as day 26 of 28. The watch treats the
     * day difference as unsigned, so the same -3 wraps to 4294967293 and
     * {@code mod 28} lands on day 9. Neither number means anything; the input
     * was impossible. The picker now refuses future dates, and this clamp keeps
     * data stored before that guard existed from producing either result.
     */
    private static Calendar lastPeriodStart(SharedPreferences sp) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        Calendar start = (Calendar) today.clone();
        start.set(Calendar.YEAR, sp.getInt(PREF_GIRLCARE_LAST_YEAR, today.get(Calendar.YEAR)));
        start.set(Calendar.MONTH, sp.getInt(PREF_GIRLCARE_LAST_MONTH, today.get(Calendar.MONTH) + 1) - 1);
        start.set(Calendar.DAY_OF_MONTH, sp.getInt(PREF_GIRLCARE_LAST_DAY, today.get(Calendar.DAY_OF_MONTH)));

        return start.after(today) ? today : start;
    }

    public static CycleStatus getCycleStatus(Context context) {
        SharedPreferences sp = getPrefs(context);
        CycleStatus status = new CycleStatus();
        status.enabled = sp.getBoolean(PREF_GIRLCARE_ENABLED, false);
        status.durationDays = sp.getInt(PREF_GIRLCARE_DURATION, DEFAULT_DURATION);
        status.totalCycleDays = sp.getInt(PREF_GIRLCARE_CYCLE, DEFAULT_CYCLE);

        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        Calendar lastPeriod = lastPeriodStart(sp);

        long diffMillis = today.getTimeInMillis() - lastPeriod.getTimeInMillis();
        long diffDays = Math.round((double) diffMillis / (1000.0 * 60 * 60 * 24));

        int cycle = Math.max(status.totalCycleDays, 20);
        int duration = Math.max(status.durationDays, 2);

        int dayInCycle = (int) (((diffDays % cycle) + cycle) % cycle) + 1; // 1-based
        status.currentDayInCycle = dayInCycle;

        status.currentPhase = calculatePhase(dayInCycle, duration, cycle);

        // Next period calculation
        int daysToNext = (cycle - dayInCycle) + 1;
        if (daysToNext > cycle) daysToNext = cycle;
        status.daysUntilNextPeriod = daysToNext;

        Calendar nextPeriod = (Calendar) today.clone();
        nextPeriod.add(Calendar.DAY_OF_YEAR, daysToNext);
        status.nextPeriodDate = nextPeriod;

        // Next ovulation calculation (day cycle - 14)
        int ovulationDayInCycle = Math.max(duration + 1, cycle - 14);
        int daysToOvulation;
        if (dayInCycle <= ovulationDayInCycle) {
            daysToOvulation = ovulationDayInCycle - dayInCycle;
        } else {
            daysToOvulation = (cycle - dayInCycle) + ovulationDayInCycle;
        }
        status.daysUntilOvulation = daysToOvulation;

        Calendar nextOvu = (Calendar) today.clone();
        nextOvu.add(Calendar.DAY_OF_YEAR, daysToOvulation);
        status.nextOvulationDate = nextOvu;

        return status;
    }

    public static Phase calculatePhase(int dayInCycle, int duration, int cycle) {
        if (dayInCycle <= duration) {
            return Phase.MENSTRUATION;
        }
        int ovulationDay = Math.max(duration + 1, cycle - 14);
        int fertileStart = Math.max(duration + 1, ovulationDay - 5);
        int fertileEnd = Math.min(cycle, ovulationDay + 4);

        if (dayInCycle == ovulationDay) {
            return Phase.OVULATION_DAY;
        } else if (dayInCycle >= fertileStart && dayInCycle <= fertileEnd) {
            return Phase.FERTILE_WINDOW;
        } else if (dayInCycle < fertileStart) {
            return Phase.FOLLICULAR_SAFE;
        } else {
            return Phase.LUTEAL_SAFE;
        }
    }

    public static Phase getPhaseForDate(Context context, int year, int month, int day) {
        SharedPreferences sp = getPrefs(context);
        int duration = sp.getInt(PREF_GIRLCARE_DURATION, DEFAULT_DURATION);
        int cycle = sp.getInt(PREF_GIRLCARE_CYCLE, DEFAULT_CYCLE);

        Calendar lastPeriod = lastPeriodStart(sp);

        Calendar target = Calendar.getInstance();
        target.set(year, month - 1, day, 0, 0, 0);
        target.set(Calendar.MILLISECOND, 0);

        long diffMillis = target.getTimeInMillis() - lastPeriod.getTimeInMillis();
        long diffDays = Math.round((double) diffMillis / (1000.0 * 60 * 60 * 24));

        int dayInCycle = (int) (((diffDays % cycle) + cycle) % cycle) + 1;
        return calculatePhase(dayInCycle, duration, cycle);
    }

    /**
     * Record the day a period started. A future date is refused rather than
     * stored: the calendar grid and the date pickers can all offer one, and
     * both the phone's and the watch's day-in-cycle maths wrap it into a
     * plausible-looking but meaningless number — see {@link #lastPeriodStart}.
     */
    public static void logPeriodStart(Context context, int year, int month, int day) {
        Calendar picked = Calendar.getInstance();
        picked.set(year, month - 1, day, 0, 0, 0);
        picked.set(Calendar.MILLISECOND, 0);

        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        if (picked.after(today))
            return;

        getPrefs(context).edit()
                .putInt(PREF_GIRLCARE_LAST_YEAR, year)
                .putInt(PREF_GIRLCARE_LAST_MONTH, month)
                .putInt(PREF_GIRLCARE_LAST_DAY, day)
                .apply();
        syncToWatch(context);
    }

    public static void syncToWatch(Context context) {
        BleManager ble = BleManager.getInstance(context);
        if (!ble.isSessionReady()) return;

        SharedPreferences sp = getPrefs(context);
        boolean enabled = sp.getBoolean(PREF_GIRLCARE_ENABLED, false);
        boolean reminderEn = sp.getBoolean(PREF_GIRLCARE_REMINDER_ENABLED, true);
        int reminderH = sp.getInt(PREF_GIRLCARE_REMINDER_HOUR, DEFAULT_REMINDER_HOUR);
        int reminderM = sp.getInt(PREF_GIRLCARE_REMINDER_MINUTE, DEFAULT_REMINDER_MINUTE);
        int pAdvance = sp.getInt(PREF_GIRLCARE_PERIOD_ADVANCE, DEFAULT_PERIOD_ADVANCE);
        int oAdvance = sp.getInt(PREF_GIRLCARE_OVULATION_ADVANCE, DEFAULT_OVULATION_ADVANCE);

        // Clamped, so the watch cannot be handed a start date it would read as
        // an unsigned day count — see lastPeriodStart().
        Calendar last = lastPeriodStart(sp);
        int lastY = last.get(Calendar.YEAR);
        int lastM = last.get(Calendar.MONTH) + 1;
        int lastD = last.get(Calendar.DAY_OF_MONTH);
        int duration = sp.getInt(PREF_GIRLCARE_DURATION, DEFAULT_DURATION);
        int cycle = sp.getInt(PREF_GIRLCARE_CYCLE, DEFAULT_CYCLE);

        ble.sendGirlCare(enabled, reminderEn, reminderH, reminderM, pAdvance, oAdvance,
                lastY, lastM, lastD, duration, cycle);
    }
}
