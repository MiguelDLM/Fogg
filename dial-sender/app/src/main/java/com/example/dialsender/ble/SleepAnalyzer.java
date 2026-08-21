package com.example.dialsender.ble;

import java.util.ArrayList;
import java.util.List;

public class SleepAnalyzer {

    public static class SleepResult {
        public int totalMinutes;
        public int deepMin;
        public int lightMin;
        public int remMin;
        public int awakeMin;
    }

    /** One complete sleep session (MODE_START → MODE_END). */
    public static class Session {
        public long start;
        public long end;
        public int totalMinutes;
        public int deepMin, lightMin, remMin, awakeMin;
    }

    /**
     * Parses every complete session out of a sleep record string
     * (comma-separated "timestamp:mode:soft:strong"). Duplicate sessions
     * (same start+end, re-appended by repeated syncs) are dropped.
     */
    public static List<Session> parseSessions(String data) {
        List<Session> out = new ArrayList<>();
        if (data == null || data.isEmpty())
            return out;

        String[] records = data.split(",");
        int i = 0;
        while (i < records.length) {
            // find the next MODE_START
            while (i < records.length && parseMode(records[i]) != BleSleep.MODE_START)
                i++;
            if (i >= records.length)
                break;
            int startIdx = i;
            // find the matching MODE_END (a new START before an END restarts the session)
            int endIdx = -1;
            for (int j = startIdx + 1; j < records.length; j++) {
                int m = parseMode(records[j]);
                if (m == BleSleep.MODE_END) { endIdx = j; break; }
                if (m == BleSleep.MODE_START) { startIdx = j; }
            }
            if (endIdx < 0)
                break;

            Session s = new Session();
            s.start = parseTs(records[startIdx]);
            s.end = parseTs(records[endIdx]);
            if (s.end > s.start) {
                s.totalMinutes = (int) ((s.end - s.start) / 60);

                int currentMode = BleSleep.MODE_AWAKE;
                long currentTs = s.start;
                for (int j = startIdx + 1; j <= endIdx; j++) {
                    long ts = parseTs(records[j]);
                    if (ts <= 0 || ts < currentTs) continue;
                    int mode = parseMode(records[j]);
                    int spanMin = (int) ((ts - currentTs) / 60);
                    switch (currentMode) {
                        case BleSleep.MODE_DEEP:      s.deepMin  += spanMin; break;
                        case BleSleep.MODE_LIGHT:     s.lightMin += spanMin; break;
                        case BleSleep.MODE_REM:       s.remMin   += spanMin; break;
                        case BleSleep.MODE_AWAKE:     s.awakeMin += spanMin; break;
                        case BleSleep.MODE_PIECEMEAL: s.lightMin += spanMin; break; // fragmented → light
                    }
                    currentMode = mode;
                    currentTs = ts;
                }

                boolean duplicate = false;
                for (Session prev : out) {
                    if (prev.start == s.start && prev.end == s.end) { duplicate = true; break; }
                }
                if (!duplicate)
                    out.add(s);
            }
            i = endIdx + 1;
        }
        return out;
    }

    /**
     * Analyzes a sleep record string and returns the last complete session.
     */
    public static SleepResult analyze(String data) {
        List<Session> sessions = parseSessions(data);
        SleepResult result = new SleepResult();
        if (sessions.isEmpty())
            return result;
        return toResult(sessions.get(sessions.size() - 1));
    }

    /**
     * Sleep attributed to one calendar day: every session that ENDS within
     * [dayStart, dayStart+24h) — i.e. the night you woke up from that morning.
     */
    public static SleepResult analyzeDay(String data, long dayStart) {
        SleepResult result = new SleepResult();
        for (Session s : parseSessions(data)) {
            if (s.end >= dayStart && s.end < dayStart + 86400L) {
                result.totalMinutes += s.totalMinutes;
                result.deepMin += s.deepMin;
                result.lightMin += s.lightMin;
                result.remMin += s.remMin;
                result.awakeMin += s.awakeMin;
            }
        }
        return result;
    }

    /** Total sleep minutes per day for `days` consecutive days starting at `start`. */
    public static int[] minutesPerDay(String data, long start, int days) {
        int[] out = new int[days];
        for (Session s : parseSessions(data)) {
            int idx = (int) ((s.end - start) / 86400L);
            if (s.end >= start && idx >= 0 && idx < days)
                out[idx] += s.totalMinutes;
        }
        return out;
    }

    private static SleepResult toResult(Session s) {
        SleepResult r = new SleepResult();
        r.totalMinutes = s.totalMinutes;
        r.deepMin = s.deepMin;
        r.lightMin = s.lightMin;
        r.remMin = s.remMin;
        r.awakeMin = s.awakeMin;
        return r;
    }

    private static long parseTs(String record) {
        try { return Long.parseLong(record.split(":")[0]); }
        catch (Exception e) { return 0; }
    }

    private static int parseMode(String record) {
        try { return Integer.parseInt(record.split(":")[1]); }
        catch (Exception e) { return 0; }
    }
}
