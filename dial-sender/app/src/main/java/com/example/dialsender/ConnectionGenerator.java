package com.example.dialsender;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Draws the 2-frame BLK_CONNECT strip: frame 0 disconnected, frame 1 connected
 * (see docs/DIAL_FORMAT_GUIDE.md §3). The two frames must read differently at a
 * glance — same glyph in a dim colour is easy to miss on a watch face, so the
 * off state can also carry a slash.
 */
public class ConnectionGenerator {

    /** The watch always reads 2 frames from a connection block. */
    public static final int FRAME_COUNT = 2;

    public static final int DISCONNECTED = 0;
    public static final int CONNECTED    = 1;

    public static class ConnectConfig {
        public int frameWidth  = 48;
        public int frameHeight = 48;
        public int frameCount  = FRAME_COUNT;
        /** "bluetooth", "waves", "link" */
        public String presetStyle = "bluetooth";
        public int connectedColor    = Color.parseColor("#38BDF8");
        public int disconnectedColor = Color.parseColor("#64748B");
        public int strokeWidth = 4;
        /** Draw the glyph inside a filled rounded badge. */
        public boolean badge = false;
        public int badgeColor = Color.parseColor("#1E293B");
        /** Cross out the glyph on the disconnected frame. */
        public boolean slashWhenOff = true;

        public ConnectConfig() {}

        public ConnectConfig copy() {
            ConnectConfig c = new ConnectConfig();
            c.frameWidth = frameWidth;
            c.frameHeight = frameHeight;
            c.frameCount = frameCount;
            c.presetStyle = presetStyle;
            c.connectedColor = connectedColor;
            c.disconnectedColor = disconnectedColor;
            c.strokeWidth = strokeWidth;
            c.badge = badge;
            c.badgeColor = badgeColor;
            c.slashWhenOff = slashWhenOff;
            return c;
        }
    }

    // ===================== PUBLIC API =====================

    public static Bitmap generateSingleFrame(ConnectConfig cfg, int index) {
        int w = Math.max(8, cfg.frameWidth);
        int h = Math.max(8, cfg.frameHeight);
        boolean on = index == CONNECTED;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        float s = Math.min(w, h);
        float stroke = Math.max(1.5f, cfg.strokeWidth * s / 48f);
        int color = on ? cfg.connectedColor : cfg.disconnectedColor;

        if (cfg.badge) {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(cfg.badgeColor);
            bg.setStyle(Paint.Style.FILL);
            RectF r = new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f);
            c.drawRoundRect(r, s * 0.28f, s * 0.28f, bg);
        }

        // Glyph box: leave room for the badge edge and the slash overshoot
        float g = s * (cfg.badge ? 0.52f : 0.66f);
        RectF box = new RectF((w - g) / 2f, (h - g) / 2f, (w + g) / 2f, (h + g) / 2f);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        switch (cfg.presetStyle) {
            case "waves": drawWaves(c, box, p, stroke, on); break;
            case "link":  drawLink(c, box, p, stroke, on); break;
            case "bluetooth":
            default:      drawBluetooth(c, box, p); break;
        }

        if (!on && cfg.slashWhenOff) {
            Paint slash = new Paint(p);
            slash.setStrokeWidth(stroke * 1.05f);
            float pad = s * (cfg.badge ? 0.16f : 0.06f);
            c.drawLine(pad, pad, w - pad, h - pad, slash);
        }
        return bmp;
    }

    public static Bitmap generateVerticalSpriteSheet(ConnectConfig cfg) {
        int w = Math.max(8, cfg.frameWidth);
        int h = Math.max(8, cfg.frameHeight);
        int count = Math.max(1, cfg.frameCount);
        Bitmap sheet = Bitmap.createBitmap(w, h * count, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet);
        for (int i = 0; i < count; i++) {
            Bitmap frame = generateSingleFrame(cfg, i);
            canvas.drawBitmap(frame, 0, i * h, null);
            frame.recycle();
        }
        return sheet;
    }

    // ===================== GLYPHS =====================

    /** The Bluetooth rune, drawn as one continuous stroke. */
    private static void drawBluetooth(Canvas c, RectF box, Paint p) {
        float w = box.width(), h = box.height();
        float x = box.left, y = box.top;
        float cx = x + w * 0.5f;

        Path path = new Path();
        path.moveTo(x + w * 0.16f, y + h * 0.70f);
        path.lineTo(x + w * 0.84f, y + h * 0.30f);
        path.lineTo(cx,            y + h * 0.04f);
        path.lineTo(cx,            y + h * 0.96f);
        path.lineTo(x + w * 0.84f, y + h * 0.70f);
        path.lineTo(x + w * 0.16f, y + h * 0.30f);
        c.drawPath(path, p);
    }

    /** Signal arcs radiating from a dot; the off state loses the outer arcs. */
    private static void drawWaves(Canvas c, RectF box, Paint p, float stroke, boolean on) {
        float cx = box.centerX();
        float cy = box.bottom;
        float w = box.width();

        Paint dot = new Paint(p);
        dot.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, stroke * 0.9f, dot);

        int arcs = on ? 3 : 1;
        for (int i = 1; i <= arcs; i++) {
            float r = w * 0.18f * i + stroke;
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(oval, 210, 120, false, p);
        }
    }

    /** Two interlocking links; broken apart when disconnected. */
    private static void drawLink(Canvas c, RectF box, Paint p, float stroke, boolean on) {
        float w = box.width(), h = box.height();
        float x = box.left, y = box.top;
        float linkW = w * 0.46f, linkH = h * 0.34f;
        float gap = on ? 0 : w * 0.10f;

        RectF left = new RectF(x - gap, y + (h - linkH) / 2f,
                               x + linkW - gap, y + (h + linkH) / 2f);
        RectF right = new RectF(x + w - linkW + gap, left.top, x + w + gap, left.bottom);
        float r = linkH / 2f;
        c.drawRoundRect(left, r, r, p);
        c.drawRoundRect(right, r, r, p);
        if (on) {
            // The bar that makes the two links read as one chain
            c.drawLine(x + w * 0.38f, box.centerY(), x + w * 0.62f, box.centerY(), p);
        }
    }
}
