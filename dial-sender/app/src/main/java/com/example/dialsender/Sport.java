package com.example.dialsender;

import android.content.Context;

/**
 * Single source of truth for workout types: the watch's mode byte, the display
 * name, the icon and the kcal/minute estimate used by the phone stopwatch.
 *
 * The watch can report any of the twelve modes, so all of them must decode.
 * Only the handful marked {@link #primary} are offered in the picker — a
 * phone-side stopwatch for swimming or rowing is not something anyone uses,
 * and burying the three activities people actually track in a strip of twelve
 * identical chips was the whole problem with the old screen.
 */
public enum Sport {
    WALK(12, R.string.sport_walk, R.drawable.ic_sport_walk, 4, true, 0xFF34D399),
    RUN(7, R.string.sport_run, R.drawable.ic_sport_run, 11, true, 0xFF22D3EE),
    CYCLING(10, R.string.sport_cycling, R.drawable.ic_sport_cycling, 8, true, 0xFFF59E0B),
    HIKE(50, R.string.sport_hike, R.drawable.ic_sport_hike, 6, true, 0xFFA78BFA),
    TREADMILL(8, R.string.sport_treadmill, R.drawable.ic_sport_treadmill, 9, true, 0xFF60A5FA),
    YOGA(14, R.string.sport_yoga, R.drawable.ic_sport_yoga, 3, false, 0xFFF472B6),
    JUMP_ROPE(26, R.string.sport_jump_rope, R.drawable.ic_sport_jump_rope, 12, false, 0xFFFBBF24),
    BASKETBALL(16, R.string.sport_basketball, R.drawable.ic_sport_basketball, 8, false, 0xFFFB923C),
    FOOTBALL(17, R.string.sport_football, R.drawable.ic_sport_football, 9, false, 0xFF4ADE80),
    SWIM(11, R.string.sport_swim, R.drawable.ic_sport_swim, 9, false, 0xFF38BDF8),
    ROW(52, R.string.sport_row, R.drawable.ic_sport_row, 7, false, 0xFF2DD4BF),
    CLIMB(13, R.string.sport_climb, R.drawable.ic_sport_climb, 8, false, 0xFFF87171);

    /**
     * Mode as the watch reports it in a workout record — see {@link WatchSport}
     * for the full 7..162 table these values come from.
     */
    public final int mode;
    public final int nameRes;
    public final int iconRes;
    public final double kcalPerMinute;
    /** Shown as a tile in the picker; the rest live behind "more". */
    public final boolean primary;
    /** Gives each activity its own identity on the tracking screen. */
    public final int accent;

    Sport(int mode, int nameRes, int iconRes, double kcalPerMinute, boolean primary, int accent) {
        this.mode = mode;
        this.nameRes = nameRes;
        this.iconRes = iconRes;
        this.kcalPerMinute = kcalPerMinute;
        this.primary = primary;
        this.accent = accent;
    }

    public String label(Context context) {
        return context.getString(nameRes);
    }

    /** Resolves a stored session name back to a sport, or null. */
    public static Sport byName(Context context, String name) {
        if (name != null) {
            for (Sport s : values()) {
                if (name.equalsIgnoreCase(s.label(context)))
                    return s;
            }
        }
        return null;
    }

    public static Sport byMode(int mode) {
        for (Sport s : values()) {
            if (s.mode == mode)
                return s;
        }
        return null;
    }

    /**
     * Resolves a stored session name back to a sport. History records keep the
     * localised name, so this also has to cope with a name saved in a different
     * app language, hence the mode-number fallback.
     */
    public static int iconForName(Context context, String name) {
        Sport s = byName(context, name);
        if (s != null)
            return s.iconRes;
        return WatchSport.iconForName(context, name);
    }

    public static String nameForMode(Context context, int mode) {
        return WatchSport.name(context, mode);
    }
}
