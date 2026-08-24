package com.example.dialsender;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

public class BatteryGenerator {

    public static class BatteryConfig {
        public int frameWidth = 84;
        public int frameHeight = 75;
        public int frameCount = 6;
        public String presetStyle = "horizontal_capsule"; // "horizontal_capsule", "vertical_capsule", "circular_ring", "gauge_arc", "pill_dots"
        public String colorMode = "dynamic"; // "dynamic", "solid", "gradient"
        public int solidColor = Color.WHITE;
        public int lowColor = Color.parseColor("#FF4D4D");
        public int midColor = Color.parseColor("#FFC83B");
        public int highColor = Color.parseColor("#2ECC71");
        public int strokeWidth = 3;
        public int borderRadius = 6;
        public boolean showLightning = true;

        public BatteryConfig() {}

        public BatteryConfig copy() {
            BatteryConfig c = new BatteryConfig();
            c.frameWidth = frameWidth;
            c.frameHeight = frameHeight;
            c.frameCount = frameCount;
            c.presetStyle = presetStyle;
            c.colorMode = colorMode;
            c.solidColor = solidColor;
            c.lowColor = lowColor;
            c.midColor = midColor;
            c.highColor = highColor;
            c.strokeWidth = strokeWidth;
            c.borderRadius = borderRadius;
            c.showLightning = showLightning;
            return c;
        }
    }

    public interface OnBatteryGeneratedListener {
        void onBatteryGenerated(Bitmap sheetBmp, int frameWidth, int frameHeight, int frameCount, BatteryConfig config);
    }

    private static int interpolateColor(int c1, int c2, float factor) {
        float f = Math.max(0.0f, Math.min(1.0f, factor));
        int r = Math.round(Color.red(c1) + f * (Color.red(c2) - Color.red(c1)));
        int g = Math.round(Color.green(c1) + f * (Color.green(c2) - Color.green(c1)));
        int b = Math.round(Color.blue(c1) + f * (Color.blue(c2) - Color.blue(c1)));
        return Color.rgb(r, g, b);
    }

    public static int getColorForPercent(BatteryConfig cfg, int percent) {
        if ("solid".equals(cfg.colorMode)) return cfg.solidColor;
        if ("dynamic".equals(cfg.colorMode)) {
            if (percent <= 20) return cfg.lowColor;
            if (percent <= 60) return cfg.midColor;
            return cfg.highColor;
        }
        // Gradient mode
        if (percent <= 50) {
            return interpolateColor(cfg.lowColor, cfg.midColor, percent / 50.0f);
        } else {
            return interpolateColor(cfg.midColor, cfg.highColor, (percent - 50) / 50.0f);
        }
    }

    public static Bitmap generateSingleFrame(BatteryConfig cfg, int frameIdx, int frameCount) {
        int w = cfg.frameWidth;
        int h = cfg.frameHeight;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        int percent = Math.round((frameIdx / (float) (frameCount - 1)) * 100);
        int color = getColorForPercent(cfg, percent);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(cfg.strokeWidth);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(color);

        float pad = 10.0f;
        float cx = w / 2.0f;
        float cy = h / 2.0f;

        if ("horizontal_capsule".equals(cfg.presetStyle)) {
            float bw = w - pad * 2 - 8;
            float bh = h * 0.45f;
            float bx = pad;
            float by = cy - bh / 2.0f;

            // Outer body
            RectF body = new RectF(bx, by, bx + bw, by + bh);
            canvas.drawRoundRect(body, cfg.borderRadius, cfg.borderRadius, strokePaint);

            // Nipple on the right
            float nw = 5;
            float nh = bh * 0.45f;
            RectF nipple = new RectF(bx + bw + 2, cy - nh / 2.0f, bx + bw + 2 + nw, cy + nh / 2.0f);
            canvas.drawRoundRect(nipple, 2, 2, fillPaint);

            // Inner fill
            float innerPad = cfg.strokeWidth + 2;
            float maxFillW = bw - innerPad * 2;
            float curFillW = maxFillW * (percent / 100.0f);
            if (curFillW > 2) {
                RectF fillRect = new RectF(bx + innerPad, by + innerPad, bx + innerPad + curFillW, by + bh - innerPad);
                canvas.drawRoundRect(fillRect, Math.max(2, cfg.borderRadius - 2), Math.max(2, cfg.borderRadius - 2), fillPaint);
            }

            if (cfg.showLightning && frameIdx == frameCount - 1) {
                drawBolt(canvas, cx, cy, bh * 0.7f, Color.WHITE);
            }
        } else if ("vertical_capsule".equals(cfg.presetStyle)) {
            float bw = w * 0.45f;
            float bh = h - pad * 2 - 8;
            float bx = cx - bw / 2.0f;
            float by = pad + 6;

            // Nipple on top
            float nw = bw * 0.45f;
            float nh = 5;
            RectF nipple = new RectF(cx - nw / 2.0f, by - nh - 2, cx + nw / 2.0f, by - 2);
            canvas.drawRoundRect(nipple, 2, 2, fillPaint);

            // Outer body
            RectF body = new RectF(bx, by, bx + bw, by + bh);
            canvas.drawRoundRect(body, cfg.borderRadius, cfg.borderRadius, strokePaint);

            // Inner fill from bottom to top
            float innerPad = cfg.strokeWidth + 2;
            float maxFillH = bh - innerPad * 2;
            float curFillH = maxFillH * (percent / 100.0f);
            if (curFillH > 2) {
                RectF fillRect = new RectF(bx + innerPad, by + bh - innerPad - curFillH, bx + bw - innerPad, by + bh - innerPad);
                canvas.drawRoundRect(fillRect, Math.max(2, cfg.borderRadius - 2), Math.max(2, cfg.borderRadius - 2), fillPaint);
            }
        } else if ("circular_ring".equals(cfg.presetStyle)) {
            float r = Math.min(w, h) / 2.0f - pad;
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);

            // Background ring track
            Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setColor(Color.argb(50, 255, 255, 255));
            trackPaint.setStrokeWidth(cfg.strokeWidth);
            canvas.drawArc(oval, 0, 360, false, trackPaint);

            // Fill arc
            float sweep = 360 * (percent / 100.0f);
            if (sweep > 0) {
                canvas.drawArc(oval, -90, sweep, false, strokePaint);
            }

            if (cfg.showLightning && frameIdx == frameCount - 1) {
                drawBolt(canvas, cx, cy, r * 0.8f, color);
            }
        } else { // pill_dots or gauge_arc
            int totalDots = 5;
            float dotW = (w - pad * 2) / (float) totalDots - 4;
            float dotH = h * 0.35f;
            int activeDots = Math.round((percent / 100.0f) * totalDots);

            for (int i = 0; i < totalDots; i++) {
                float dx = pad + i * (dotW + 4);
                float dy = cy - dotH / 2.0f;
                RectF dot = new RectF(dx, dy, dx + dotW, dy + dotH);
                if (i < activeDots) {
                    canvas.drawRoundRect(dot, 3, 3, fillPaint);
                } else {
                    Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    dimPaint.setStyle(Paint.Style.STROKE);
                    dimPaint.setColor(Color.argb(60, 255, 255, 255));
                    dimPaint.setStrokeWidth(1.5f);
                    canvas.drawRoundRect(dot, 3, 3, dimPaint);
                }
            }
        }

        return bmp;
    }

    private static void drawBolt(Canvas canvas, float cx, float cy, float size, int color) {
        Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boltPaint.setColor(color);
        boltPaint.setStyle(Paint.Style.FILL);

        Path p = new Path();
        p.moveTo(cx + size * 0.12f, cy - size * 0.45f);
        p.lineTo(cx - size * 0.28f, cy + size * 0.05f);
        p.lineTo(cx + size * 0.04f, cy + size * 0.05f);
        p.lineTo(cx - size * 0.12f, cy + size * 0.45f);
        p.lineTo(cx + size * 0.28f, cy - size * 0.05f);
        p.lineTo(cx - size * 0.04f, cy - size * 0.05f);
        p.close();
        canvas.drawPath(p, boltPaint);
    }

    public static Bitmap generateVerticalSpriteSheet(BatteryConfig cfg) {
        int w = cfg.frameWidth;
        int h = cfg.frameHeight;
        int totalH = h * cfg.frameCount;
        Bitmap fullSheet = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(fullSheet);

        for (int i = 0; i < cfg.frameCount; i++) {
            Bitmap frameBmp = generateSingleFrame(cfg, i, cfg.frameCount);
            canvas.drawBitmap(frameBmp, 0, i * h, null);
            frameBmp.recycle();
        }

        return fullSheet;
    }
}
