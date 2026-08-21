package com.example.dialsender.views;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The dial on the workout screen: a track ring with an accent arc that sweeps
 * once per minute, so the timer visibly moves even when the seconds digit is
 * the only thing changing. Tick marks around the rim and a soft glow behind the
 * arc give it the look of a sports watch rather than a stopwatch widget.
 */
public class WorkoutRingView extends View {

    private static final int TICKS = 60;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private int accent = 0xFF22D3EE;
    /** 0..1 within the current minute. */
    private float progress = 0f;
    private boolean dimmed = false;

    public WorkoutRingView(Context context) {
        this(context, null);
    }

    public WorkoutRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(0xFF202832);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        capPaint.setStyle(Paint.Style.FILL);
        // The glow is drawn in software; a blur mask is not hardware accelerated.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setAccent(int color) {
        this.accent = color;
        invalidate();
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    /** Paused sessions keep the dial but drain the colour out of it. */
    public void setDimmed(boolean dimmed) {
        this.dimmed = dimmed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0)
            return;

        float stroke = size * 0.055f;
        float inset = stroke * 0.5f + size * 0.075f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = size / 2f - inset;
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        int color = dimmed ? blend(accent, 0xFF0E1116, 0.55f) : accent;

        // Rim ticks, brighter on the swept side.
        tickPaint.setStrokeWidth(size * 0.008f);
        float tickOuter = radius + stroke * 0.5f + size * 0.035f;
        float tickInner = tickOuter - size * 0.028f;
        int passed = (int) Math.floor(progress * TICKS);
        for (int i = 0; i < TICKS; i++) {
            double angle = Math.toRadians(-90 + i * 360.0 / TICKS);
            boolean major = i % 5 == 0;
            boolean lit = i <= passed;
            tickPaint.setColor(lit ? withAlpha(color, major ? 255 : 150)
                    : (major ? 0xFF39434F : 0xFF262E38));
            float outer = major ? tickOuter : tickOuter - size * 0.008f;
            canvas.drawLine(
                    cx + (float) Math.cos(angle) * tickInner,
                    cy + (float) Math.sin(angle) * tickInner,
                    cx + (float) Math.cos(angle) * outer,
                    cy + (float) Math.sin(angle) * outer,
                    tickPaint);
        }

        trackPaint.setStrokeWidth(stroke);
        canvas.drawArc(bounds, 0, 360, false, trackPaint);

        float sweep = progress * 360f;
        if (sweep > 0.5f) {
            glowPaint.setStrokeWidth(stroke * 1.5f);
            glowPaint.setColor(withAlpha(color, dimmed ? 40 : 110));
            glowPaint.setMaskFilter(new BlurMaskFilter(stroke * 0.9f, BlurMaskFilter.Blur.NORMAL));
            canvas.drawArc(bounds, -90, sweep, false, glowPaint);

            arcPaint.setStrokeWidth(stroke);
            arcPaint.setColor(color);
            canvas.drawArc(bounds, -90, sweep, false, arcPaint);

            // Bright head on the leading edge.
            double headAngle = Math.toRadians(-90 + sweep);
            capPaint.setColor(dimmed ? color : Color.WHITE);
            canvas.drawCircle(
                    cx + (float) Math.cos(headAngle) * radius,
                    cy + (float) Math.sin(headAngle) * radius,
                    stroke * 0.28f, capPaint);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int blend(int from, int to, float amount) {
        float inv = 1f - amount;
        return Color.rgb(
                (int) (Color.red(from) * inv + Color.red(to) * amount),
                (int) (Color.green(from) * inv + Color.green(to) * amount),
                (int) (Color.blue(from) * inv + Color.blue(to) * amount));
    }
}
