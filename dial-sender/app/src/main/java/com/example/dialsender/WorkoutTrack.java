package com.example.dialsender;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * The GPS trace of a phone-recorded workout.
 *
 * Sessions started from the app used to have no track at all, and the detail
 * screen made one up — a circle around Puerta del Sol with a sine-wave
 * elevation profile. Now the workout screen records the real thing, and when
 * there is no track the detail screen says so instead of inventing one.
 *
 * Stored as one pref per session, "t:lat:lon:alt;..." with t relative to the
 * session start, so it is dropped along with the session.
 */
public final class WorkoutTrack {

    private static final String PREF = "dial_sender_prefs";
    private static final String KEY_PREFIX = "sport_track_";

    /** Fixes vaguer than this are drift, not position — indoors they are the norm. */
    public static final float MAX_HORIZONTAL_ACCURACY_M = 25f;
    /** GPS altitude is far less accurate than position; ignore it unless it is good. */
    public static final float MAX_VERTICAL_ACCURACY_M = 15f;
    /** Below this, consecutive fixes are GPS jitter rather than movement. */
    private static final float MIN_STEP_METRES = 8f;
    /**
     * Cumulative climb ignores anything smaller. Set well above typical GPS
     * vertical noise: at 1.5 m a phone sitting still on a desk accumulated
     * "39 m of climb" over a 76-second session.
     */
    private static final double MIN_CLIMB_METRES = 6.0;

    public static final class Point {
        public final int elapsedSec;
        public final double lat;
        public final double lon;
        /** Metres, or {@link Double#NaN} when the fix carried no altitude. */
        public final double altitude;

        public Point(int elapsedSec, double lat, double lon, double altitude) {
            this.elapsedSec = elapsedSec;
            this.lat = lat;
            this.lon = lon;
            this.altitude = altitude;
        }
    }

    private WorkoutTrack() {
    }

    private static String key(long sessionStartSec) {
        return KEY_PREFIX + sessionStartSec;
    }

    public static void save(Context context, long sessionStartSec, List<Point> points) {
        if (points == null || points.isEmpty())
            return;
        StringBuilder sb = new StringBuilder();
        for (Point p : points) {
            if (sb.length() > 0)
                sb.append(';');
            sb.append(p.elapsedSec).append(':')
                    .append(round6(p.lat)).append(':')
                    .append(round6(p.lon)).append(':')
                    .append(Double.isNaN(p.altitude) ? "" : Math.round(p.altitude * 10) / 10.0);
        }
        prefs(context).edit().putString(key(sessionStartSec), sb.toString()).apply();
    }

    public static List<Point> load(Context context, long sessionStartSec) {
        List<Point> out = new ArrayList<>();
        String raw = prefs(context).getString(key(sessionStartSec), "");
        if (raw.isEmpty())
            return out;
        for (String chunk : raw.split(";")) {
            String[] f = chunk.split(":", -1);
            if (f.length < 3)
                continue;
            try {
                double alt = (f.length >= 4 && !f[3].isEmpty())
                        ? Double.parseDouble(f[3])
                        : Double.NaN;
                out.add(new Point(Integer.parseInt(f[0]),
                        Double.parseDouble(f[1]), Double.parseDouble(f[2]), alt));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static void delete(Context context, long sessionStartSec) {
        prefs(context).edit().remove(key(sessionStartSec)).apply();
    }

    /** Total ground distance in metres. */
    public static double distanceMetres(List<Point> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            float[] r = new float[1];
            Location.distanceBetween(points.get(i - 1).lat, points.get(i - 1).lon,
                    points.get(i).lat, points.get(i).lon, r);
            total += r[0];
        }
        return total;
    }

    /**
     * Cumulative positive climb, or -1 when the track carries no altitude at
     * all — the caller shows "--" rather than a made-up number.
     */
    public static double elevationGainMetres(List<Point> points) {
        double gain = 0;
        double reference = Double.NaN;
        boolean sawAltitude = false;
        for (Point p : points) {
            if (Double.isNaN(p.altitude))
                continue;
            sawAltitude = true;
            if (Double.isNaN(reference)) {
                reference = p.altitude;
                continue;
            }
            double delta = p.altitude - reference;
            if (Math.abs(delta) < MIN_CLIMB_METRES)
                continue; // noise, keep the same reference
            if (delta > 0)
                gain += delta;
            reference = p.altitude;
        }
        return sawAltitude ? gain : -1;
    }

    /**
     * Whether a fix is worth recording: accurate enough to be a position, and
     * far enough from the previous one to be movement rather than drift. The
     * step has to beat the fix's own error, so a vague fix never invents
     * distance.
     */
    public static boolean shouldRecord(Point last, Location location) {
        if (location == null)
            return false;
        if (!location.hasAccuracy() || location.getAccuracy() > MAX_HORIZONTAL_ACCURACY_M)
            return false;
        if (last == null)
            return true;
        float[] r = new float[1];
        Location.distanceBetween(last.lat, last.lon,
                location.getLatitude(), location.getLongitude(), r);
        return r[0] >= Math.max(MIN_STEP_METRES, location.getAccuracy());
    }

    /** Altitude only when the fix says it is trustworthy, otherwise NaN. */
    public static double usableAltitude(Location location) {
        if (location == null || !location.hasAltitude())
            return Double.NaN;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!location.hasVerticalAccuracy()
                    || location.getVerticalAccuracyMeters() > MAX_VERTICAL_ACCURACY_M)
                return Double.NaN;
            return location.getAltitude();
        }
        // Pre-Oreo there is no vertical accuracy to check, so do not guess.
        return Double.NaN;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
