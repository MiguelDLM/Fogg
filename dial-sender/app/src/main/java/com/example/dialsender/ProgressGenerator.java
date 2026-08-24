package com.example.dialsender;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Draws the 11-frame BLK_PROGRESS1 / BLK_PROGRESS2 strip.
 *
 * The watch picks a frame by completion, so the strip runs 0 %, 10 % … 100 %
 * in even steps (see docs/DIAL_FORMAT_GUIDE.md §3). Every frame keeps the same
 * canvas size — only the filled portion changes — otherwise the gauge would
 * jump around as the metric advances.
 */
public class ProgressGenerator {

    /** The watch always reads 11 frames from a progress block. */
    public static final int FRAME_COUNT = 11;

    public static class ProgressConfig {
        public int frameWidth  = 120;
        public int frameHeight = 120;
        public int frameCount  = FRAME_COUNT;
        /** "arc", "ring", "bar", "segments", "dots" */
        public String presetStyle = "arc";
        /** "solid" or "gradient" (start colour → end colour across the sweep) */
        public String colorMode = "solid";
        public int fillColor  = Color.parseColor("#38BDF8");
        public int endColor   = Color.parseColor("#A855F7");
        public int trackColor = Color.parseColor("#2A3340");
        public boolean showTrack = true;
        public boolean rounded   = true;
        public int thickness  = 10;
        /** Degrees, 0 = 3 o'clock. -225 puts an open arc's gap at the bottom. */
        public int startAngle = 135;
        public int sweepAngle = 270;
        /** Lit elements for the segmented styles. */
        public int segmentCount = 12;

        public ProgressConfig() {}

        public ProgressConfig copy() {
            ProgressConfig c = new ProgressConfig();
            c.frameWidth = frameWidth;
            c.frameHeight = frameHeight;
            c.frameCount = frameCount;
            c.presetStyle = presetStyle;
            c.colorMode = colorMode;
            c.fillColor = fillColor;
            c.endColor = endColor;
            c.trackColor = trackColor;
            c.showTrack = showTrack;
            c.rounded = rounded;
            c.thickness = thickness;
            c.startAngle = startAngle;
            c.sweepAngle = sweepAngle;
            c.segmentCount = segmentCount;
            return c;
        }
    }

    // ===================== PUBLIC API =====================

    /** @param index 0-based frame; index/(frameCount-1) is the completion it shows. */
    public static Bitmap generateSingleFrame(ProgressConfig cfg, int index, int frameCount) {
        int w = Math.max(8, cfg.frameWidth);
        int h = Math.max(8, cfg.frameHeight);
        int steps = Math.max(1, frameCount - 1);
        float progress = Math.max(0f, Math.min(1f, index / (float) steps));

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        switch (cfg.presetStyle) {
            case "ring":     drawArc(canvas, cfg, w, h, progress, 0, 360); break;
            case "bar":      drawBar(canvas, cfg, w, h, progress); break;
            case "segments": drawSegments(canvas, cfg, w, h, progress); break;
            case "dots":     drawDots(canvas, cfg, w, h, progress); break;
            case "arc":
            default:         drawArc(canvas, cfg, w, h, progress, cfg.startAngle, cfg.sweepAngle); break;
        }
        return bmp;
    }

    public static Bitmap generateVerticalSpriteSheet(ProgressConfig cfg) {
        int w = Math.max(8, cfg.frameWidth);
        int h = Math.max(8, cfg.frameHeight);
        int count = Math.max(1, cfg.frameCount);
        Bitmap sheet = Bitmap.createBitmap(w, h * count, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet);
        for (int i = 0; i < count; i++) {
            Bitmap frame = generateSingleFrame(cfg, i, count);
            canvas.drawBitmap(frame, 0, i * h, null);
            frame.recycle();
        }
        return sheet;
    }

    /** Colour of the filled part at a given completion. */
    public static int colorAt(ProgressConfig cfg, float progress) {
        if (!"gradient".equals(cfg.colorMode)) return cfg.fillColor;
        return interpolate(cfg.fillColor, cfg.endColor, progress);
    }

    // ===================== STYLES =====================

    private static void drawArc(Canvas c, ProgressConfig cfg, int w, int h,
                                float progress, float startAngle, float sweepAngle) {
        float t = thickness(cfg, Math.min(w, h));
        float inset = t / 2f + 1f;
        float size = Math.min(w, h) - inset * 2;
        RectF box = new RectF((w - size) / 2f, (h - size) / 2f, (w + size) / 2f, (h + size) / 2f);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(t);
        if (cfg.rounded) p.setStrokeCap(Paint.Cap.ROUND);

        if (cfg.showTrack) {
            p.setColor(cfg.trackColor);
            c.drawArc(box, startAngle, sweepAngle, false, p);
        }
        if (progress <= 0f) return;

        if ("gradient".equals(cfg.colorMode)) {
            // Stepped gradient: one short arc per slice, coloured by its own
            // position, so the sweep shifts hue as it fills.
            int slices = Math.max(8, Math.round(Math.abs(sweepAngle) / 6f));
            float filled = sweepAngle * progress;
            float step = filled / slices;
            Paint sp = new Paint(p);
            sp.setStrokeCap(Paint.Cap.BUTT);
            for (int i = 0; i < slices; i++) {
                sp.setColor(interpolate(cfg.fillColor, cfg.endColor, (i + 0.5f) / slices * progress));
                // Overlap by a hair so antialiasing does not leave seams
                c.drawArc(box, startAngle + i * step, step + 0.6f, false, sp);
            }
            if (cfg.rounded) {
                // Redraw the two ends with round caps for a clean finish
                Paint cap = new Paint(p);
                cap.setColor(cfg.fillColor);
                c.drawArc(box, startAngle, 0.1f, false, cap);
                cap.setColor(interpolate(cfg.fillColor, cfg.endColor, progress));
                c.drawArc(box, startAngle + filled, 0.1f, false, cap);
            }
        } else {
            p.setColor(cfg.fillColor);
            c.drawArc(box, startAngle, sweepAngle * progress, false, p);
        }
    }

    private static void drawBar(Canvas c, ProgressConfig cfg, int w, int h, float progress) {
        float t = Math.min(h - 2, thickness(cfg, h) * 1.6f);
        float radius = cfg.rounded ? t / 2f : Math.min(4f, t / 4f);
        float top = (h - t) / 2f;
        RectF track = new RectF(1, top, w - 1, top + t);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        if (cfg.showTrack) {
            p.setColor(cfg.trackColor);
            c.drawRoundRect(track, radius, radius, p);
        }
        if (progress <= 0f) return;

        float filledW = (track.width()) * progress;
        // A rounded bar shorter than its own cap renders as a sliver; keep it
        // at least one cap wide so 10 % still reads as a bar.
        filledW = Math.max(filledW, cfg.rounded ? t : 2f);
        RectF fill = new RectF(track.left, track.top, track.left + filledW, track.bottom);
        if ("gradient".equals(cfg.colorMode)) {
            p.setShader(new android.graphics.LinearGradient(track.left, 0, track.right, 0,
                    cfg.fillColor, cfg.endColor, android.graphics.Shader.TileMode.CLAMP));
        }
        p.setColor(cfg.fillColor);
        c.save();
        c.clipRect(fill);
        c.drawRoundRect(track, radius, radius, p);
        c.restore();
        p.setShader(null);
    }

    private static void drawSegments(Canvas c, ProgressConfig cfg, int w, int h, float progress) {
        int total = Math.max(4, cfg.segmentCount);
        int lit = Math.round(total * progress);
        float t = thickness(cfg, Math.min(w, h));
        float inset = t / 2f + 1f;
        float size = Math.min(w, h) - inset * 2;
        RectF box = new RectF((w - size) / 2f, (h - size) / 2f, (w + size) / 2f, (h + size) / 2f);

        float sweep = cfg.sweepAngle == 0 ? 360 : cfg.sweepAngle;
        float slice = Math.abs(sweep) / total;
        // Round caps bulge half a stroke past each end, so a gap measured only
        // as a fraction of the slice disappears on thick gauges — the ring then
        // renders as one solid arc. Widen the gap to clear the caps.
        float radius = size / 2f;
        float capGap = cfg.rounded && radius > 0
                ? (float) Math.toDegrees(t / radius) * 1.15f : 0f;
        float gap = Math.min(slice * 0.6f, Math.max(slice * 0.3f, capGap));
        float seg = Math.max(slice * 0.2f, slice - gap);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(t);
        if (cfg.rounded) p.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < total; i++) {
            float a = cfg.startAngle + (i * slice + gap / 2f) * Math.signum(sweep);
            boolean on = i < lit;
            if (!on && !cfg.showTrack) continue;
            p.setColor(on ? interpolate(cfg.fillColor,
                    "gradient".equals(cfg.colorMode) ? cfg.endColor : cfg.fillColor,
                    total > 1 ? i / (float) (total - 1) : 0f) : cfg.trackColor);
            c.drawArc(box, a, seg * Math.signum(sweep), false, p);
        }
    }

    private static void drawDots(Canvas c, ProgressConfig cfg, int w, int h, float progress) {
        int total = Math.max(3, cfg.segmentCount);
        int lit = Math.round(total * progress);
        float r = Math.min(h / 2f - 1f, (w / (float) total) * 0.35f);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        float step = total > 1 ? (w - 2 * r - 2) / (float) (total - 1) : 0;
        for (int i = 0; i < total; i++) {
            boolean on = i < lit;
            if (!on && !cfg.showTrack) continue;
            p.setColor(on ? interpolate(cfg.fillColor,
                    "gradient".equals(cfg.colorMode) ? cfg.endColor : cfg.fillColor,
                    total > 1 ? i / (float) (total - 1) : 0f) : cfg.trackColor);
            c.drawCircle(r + 1 + i * step, h / 2f, on ? r : r * 0.7f, p);
        }
    }

    // ===================== HELPERS =====================

    /** Thickness scales with the frame so a 40px gauge is not a solid blob. */
    private static float thickness(ProgressConfig cfg, int reference) {
        float t = cfg.thickness * (reference / 120f);
        return Math.max(2f, Math.min(reference / 3f, t));
    }

    private static int interpolate(int c1, int c2, float factor) {
        float f = Math.max(0f, Math.min(1f, factor));
        return Color.argb(
                Math.round(Color.alpha(c1) + f * (Color.alpha(c2) - Color.alpha(c1))),
                Math.round(Color.red(c1)   + f * (Color.red(c2)   - Color.red(c1))),
                Math.round(Color.green(c1) + f * (Color.green(c2) - Color.green(c1))),
                Math.round(Color.blue(c1)  + f * (Color.blue(c2)  - Color.blue(c1))));
    }
}
