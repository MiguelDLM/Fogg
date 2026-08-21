package com.example.dialsender.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.example.dialsender.ble.BleSleep;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SleepTimelineView extends View {

    private static final int COLOR_DEEP    = Color.parseColor("#34D399");
    private static final int COLOR_LIGHT   = Color.parseColor("#22D3EE");
    private static final int COLOR_REM     = Color.parseColor("#BC8CFF");
    private static final int COLOR_AWAKE   = Color.parseColor("#F87171");
    private static final int COLOR_UNKNOWN = Color.parseColor("#1a2332");

    private static class Segment {
        long startTs, endTs;
        int mode;
        Segment(long s, long e, int m) { startTs = s; endTs = e; mode = m; }
    }

    private final List<Segment> segments = new ArrayList<>();
    private long sessionStart, sessionEnd;
    private final Paint segPaint  = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint();

    public SleepTimelineView(Context context) { super(context); }
    public SleepTimelineView(Context context, AttributeSet a) { super(context, a); }
    public SleepTimelineView(Context context, AttributeSet a, int s) { super(context, a, s); }

    public void setSleepData(String rawPrefsString) {
        setSleepData(rawPrefsString, 0);
    }

    /**
     * Renders the sleep session for one calendar day: the last session that
     * ENDS within [dayStart, dayStart+24h). dayStart=0 keeps the old behaviour
     * (last session in the data).
     */
    public void setSleepData(String rawPrefsString, long dayStart) {
        segments.clear();
        sessionStart = 0;
        sessionEnd = 0;
        if (rawPrefsString == null || rawPrefsString.isEmpty()) { invalidate(); return; }

        String[] records = rawPrefsString.split(",");

        // Find the MODE_END for the requested day (or the last one overall)
        int endIdx = -1;
        for (int i = records.length - 1; i >= 0; i--) {
            if (parseMode(records[i]) != BleSleep.MODE_END) continue;
            if (dayStart > 0) {
                long ts = parseTs(records[i]);
                if (ts < dayStart || ts >= dayStart + 86400L) continue;
            }
            endIdx = i;
            break;
        }
        if (endIdx < 0) { invalidate(); return; }

        // Find the MODE_START of THIS session. Stop at the previous MODE_END:
        // if this session's START is missing from the data, walking past it
        // picked up an earlier night's START and painted a timeline hours long.
        int startIdx = -1;
        for (int i = endIdx - 1; i >= 0; i--) {
            int m = parseMode(records[i]);
            if (m == BleSleep.MODE_END) break;
            if (m == BleSleep.MODE_START) { startIdx = i; break; }
        }
        if (startIdx < 0) { invalidate(); return; }

        sessionStart = parseTs(records[startIdx]);
        sessionEnd   = parseTs(records[endIdx]);

        int prevMode = BleSleep.MODE_AWAKE;
        long prevTs  = sessionStart;

        for (int i = startIdx + 1; i <= endIdx; i++) {
            long ts   = parseTs(records[i]);
            int  mode = parseMode(records[i]);
            if (ts <= 0 || ts < prevTs) continue;
            // prevMode is the phase that was active from prevTs to ts
            if (prevMode != BleSleep.MODE_START && prevMode != BleSleep.MODE_END) {
                segments.add(new Segment(prevTs, ts, prevMode));
            }
            prevMode = mode;
            prevTs   = ts;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;

        if (segments.isEmpty() || sessionEnd <= sessionStart) {
            labelPaint.setColor(Color.parseColor("#8B949E"));
            labelPaint.setTextSize(32f);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(getContext().getString(com.example.dialsender.R.string.sleep_no_data_day),
                    getWidth() / 2f, getHeight() / 2f, labelPaint);
            return;
        }

        float w = getWidth(), h = getHeight();
        float barH   = h * 0.55f;
        float barTop = h * 0.05f;
        float duration = sessionEnd - sessionStart;

        for (Segment seg : segments) {
            float x1 = (seg.startTs - sessionStart) / duration * w;
            float x2 = (seg.endTs   - sessionStart) / duration * w;
            segPaint.setColor(colorForMode(seg.mode));
            canvas.drawRect(x1, barTop, x2, barTop + barH, segPaint);
        }

        // Time axis labels
        labelPaint.setColor(Color.parseColor("#484F58"));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(h * 0.13f);
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        int labelCount = 4;
        for (int i = 0; i <= labelCount; i++) {
            long ts  = sessionStart + (long)(duration * i / labelCount);
            float x  = w * i / labelCount;
            if (i == 0) labelPaint.setTextAlign(Paint.Align.LEFT);
            else if (i == labelCount) labelPaint.setTextAlign(Paint.Align.RIGHT);
            else labelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(sdf.format(new Date(ts * 1000)), x, barTop + barH + h * 0.25f, labelPaint);
        }
    }

    private int colorForMode(int mode) {
        switch (mode) {
            case BleSleep.MODE_DEEP:      return COLOR_DEEP;
            case BleSleep.MODE_LIGHT:
            case BleSleep.MODE_PIECEMEAL: return COLOR_LIGHT;
            case BleSleep.MODE_REM:       return COLOR_REM;
            case BleSleep.MODE_AWAKE:     return COLOR_AWAKE;
            default:                      return COLOR_UNKNOWN;
        }
    }

    private long parseTs(String record) {
        try { return Long.parseLong(record.split(":")[0]); }
        catch (Exception e) { return 0; }
    }

    private int parseMode(String record) {
        try { return Integer.parseInt(record.split(":")[1]); }
        catch (Exception e) { return 0; }
    }
}
