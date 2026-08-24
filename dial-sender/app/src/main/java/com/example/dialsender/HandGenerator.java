package com.example.dialsender;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

public class HandGenerator {

    public static class HandConfig {
        public int width;
        public int handLength;
        public int tailLength;
        public String tipStyle; // "sword", "arrow", "baton", "needle", "club", "diamond", "leaf", "lollipop"
        public int color;
        public int glowColor;
        public int glowBlur;
        public boolean shadow;
        public boolean outline;
        public int outlineColor;
        public int outlineWidth;
        public boolean rounded;

        public HandConfig(int width, int handLength, int tailLength, String tipStyle, int color,
                          int glowColor, int glowBlur, boolean shadow, boolean outline,
                          int outlineColor, int outlineWidth, boolean rounded) {
            this.width = width;
            this.handLength = handLength;
            this.tailLength = tailLength;
            this.tipStyle = tipStyle;
            this.color = color;
            this.glowColor = glowColor;
            this.glowBlur = glowBlur;
            this.shadow = shadow;
            this.outline = outline;
            this.outlineColor = outlineColor;
            this.outlineWidth = outlineWidth;
            this.rounded = rounded;
        }

        public HandConfig copy() {
            return new HandConfig(width, handLength, tailLength, tipStyle, color,
                    glowColor, glowBlur, shadow, outline, outlineColor, outlineWidth, rounded);
        }

        public static HandConfig getDefault(String type) {
            if ("hour".equals(type)) {
                return new HandConfig(16, 120, 30, "sword", Color.WHITE,
                        Color.TRANSPARENT, 0, true, true, Color.BLACK, 2, true);
            } else if ("minute".equals(type)) {
                return new HandConfig(12, 175, 35, "sword", Color.WHITE,
                        Color.TRANSPARENT, 0, true, true, Color.BLACK, 2, true);
            } else { // second
                return new HandConfig(5, 205, 45, "needle", Color.parseColor("#FF4444"),
                        Color.parseColor("#FF0000"), 6, false, false, Color.BLACK, 1, false);
            }
        }
    }

    public interface OnHandSetAppliedListener {
        void onHandSetApplied(Bitmap hourBmp, int hourCtx, int hourCty, HandConfig hourCfg,
                              Bitmap minBmp, int minCtx, int minCty, HandConfig minCfg,
                              Bitmap secBmp, int secCtx, int secCty, HandConfig secCfg);
    }

    private static int shadeColor(int color, int amount) {
        int r = Math.min(255, Math.max(0, Color.red(color) + amount));
        int g = Math.min(255, Math.max(0, Color.green(color) + amount));
        int b = Math.min(255, Math.max(0, Color.blue(color) + amount));
        return Color.argb(Color.alpha(color), r, g, b);
    }

    public static Bitmap generateHandBitmap(HandConfig cfg, int targetW, int targetH) {
        Bitmap bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        float tipLen = cfg.handLength;
        float tailLen = cfg.tailLength;
        float w = cfg.width;
        float cx = targetW / 2.0f;
        float pivotY = tipLen + Math.max(0, (targetH - tipLen - tailLen) / 2.0f);
        float tipY = pivotY - tipLen;
        float tailY = pivotY + tailLen;
        float hw = w / 2.0f;
        float tailW = Math.max(w * 0.4f, 1.0f);
        float tailHw = tailW / 2.0f;

        // Shadow / Glow
        if (cfg.glowBlur > 0 && cfg.glowColor != Color.TRANSPARENT) {
            Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            glowPaint.setColor(cfg.glowColor);
            glowPaint.setMaskFilter(new BlurMaskFilter(cfg.glowBlur, BlurMaskFilter.Blur.NORMAL));
            drawHandPath(canvas, cfg, cx, tipY, tailY, pivotY, tipLen, hw, tailHw, w, glowPaint);
        }

        if (cfg.shadow) {
            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setColor(Color.argb(130, 0, 0, 0));
            shadowPaint.setMaskFilter(new BlurMaskFilter(3, BlurMaskFilter.Blur.NORMAL));
            canvas.save();
            canvas.translate(1, 1);
            drawHandPath(canvas, cfg, cx, tipY, tailY, pivotY, tipLen, hw, tailHw, w, shadowPaint);
            canvas.restore();
        }

        // Main hand body
        Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mainPaint.setStyle(Paint.Style.FILL);
        Shader grad = new LinearGradient(cx - hw, tipY, cx + hw, tipY,
                new int[]{shadeColor(cfg.color, -25), cfg.color, shadeColor(cfg.color, -25)},
                new float[]{0.0f, 0.5f, 1.0f}, Shader.TileMode.CLAMP);
        mainPaint.setShader(grad);
        drawHandPath(canvas, cfg, cx, tipY, tailY, pivotY, tipLen, hw, tailHw, w, mainPaint);

        // Outline
        if (cfg.outline && cfg.outlineWidth > 0) {
            Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setColor(cfg.outlineColor);
            outlinePaint.setStrokeWidth(cfg.outlineWidth);
            drawHandPath(canvas, cfg, cx, tipY, tailY, pivotY, tipLen, hw, tailHw, w, outlinePaint);
        }

        return bmp;
    }

    private static void drawHandPath(Canvas canvas, HandConfig cfg, float cx, float tipY, float tailY,
                                     float pivotY, float tipLen, float hw, float tailHw, float w, Paint paint) {
        Path path = new Path();
        String style = cfg.tipStyle != null ? cfg.tipStyle : "sword";

        if ("needle".equals(style)) {
            path.moveTo(cx, tipY);
            path.lineTo(cx + 0.5f, tailY);
            path.lineTo(cx - 0.5f, tailY);
            path.close();
            canvas.drawPath(path, paint);
        } else if ("sword".equals(style)) {
            path.moveTo(cx, tipY);
            path.lineTo(cx + hw, pivotY - tipLen * 0.2f);
            path.lineTo(cx + tailHw, pivotY);
            path.lineTo(cx + tailHw, tailY);
            path.lineTo(cx - tailHw, tailY);
            path.lineTo(cx - tailHw, pivotY);
            path.lineTo(cx - hw, pivotY - tipLen * 0.2f);
            path.close();
            canvas.drawPath(path, paint);
        } else if ("arrow".equals(style)) {
            path.moveTo(cx, tipY);
            path.lineTo(cx + hw, pivotY - tipLen * 0.35f);
            path.lineTo(cx + hw * 0.4f, pivotY - tipLen * 0.35f);
            path.lineTo(cx + tailHw, pivotY);
            path.lineTo(cx + tailHw, tailY);
            path.lineTo(cx - tailHw, tailY);
            path.lineTo(cx - tailHw, pivotY);
            path.lineTo(cx - hw * 0.4f, pivotY - tipLen * 0.35f);
            path.lineTo(cx - hw, pivotY - tipLen * 0.35f);
            path.close();
            canvas.drawPath(path, paint);
        } else if ("baton".equals(style)) {
            float r = cfg.rounded ? hw : 0;
            RectF rect = new RectF(cx - hw, tipY, cx + hw, tailY);
            canvas.drawRoundRect(rect, r, r, paint);
        } else if ("club".equals(style)) {
            path.moveTo(cx, tipY);
            path.lineTo(cx + hw, tipY + tipLen * 0.6f);
            path.lineTo(cx + tailHw, pivotY);
            path.lineTo(cx + tailHw, tailY);
            path.lineTo(cx - tailHw, tailY);
            path.lineTo(cx - tailHw, pivotY);
            path.lineTo(cx - hw, tipY + tipLen * 0.6f);
            path.close();
            canvas.drawPath(path, paint);
        } else if ("diamond".equals(style)) {
            path.moveTo(cx, tipY);
            path.lineTo(cx + hw, pivotY - tipLen * 0.5f);
            path.lineTo(cx, pivotY - tipLen * 0.1f);
            path.lineTo(cx - hw, pivotY - tipLen * 0.5f);
            path.close();
            canvas.drawPath(path, paint);
            canvas.drawRect(cx - tailHw, pivotY, cx + tailHw, tailY, paint);
        } else if ("leaf".equals(style)) {
            path.moveTo(cx, tipY);
            path.cubicTo(cx + hw * 1.5f, tipY + tipLen * 0.3f, cx + hw, pivotY - tipLen * 0.15f, cx, pivotY);
            path.cubicTo(cx - hw, pivotY - tipLen * 0.15f, cx - hw * 1.5f, tipY + tipLen * 0.3f, cx, tipY);
            path.close();
            canvas.drawPath(path, paint);
            canvas.drawRect(cx - tailHw * 0.5f, pivotY, cx + tailHw * 0.5f, tailY, paint);
        } else if ("lollipop".equals(style)) {
            canvas.drawCircle(cx, tipY + hw, hw, paint);
            canvas.drawRect(cx - tailHw * 0.6f, tipY + hw * 2, cx + tailHw * 0.6f, tailY, paint);
        }
    }

    public static Bitmap renderDialPreview(HandConfig hourCfg, HandConfig minCfg, HandConfig secCfg, int size) {
        Bitmap dial = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dial);

        float center = size / 2.0f;
        float radius = center - 8;

        // Dial face background
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.parseColor("#111827"));
        canvas.drawCircle(center, center, radius, bgPaint);

        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.parseColor("#334155"));
        ringPaint.setStrokeWidth(2);
        canvas.drawCircle(center, center, radius, ringPaint);

        // Hour markers
        Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        markerPaint.setColor(Color.WHITE);
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(i * 30);
            float x1 = (float) (center + Math.sin(angle) * (radius - 4));
            float y1 = (float) (center - Math.cos(angle) * (radius - 4));
            float len = (i % 3 == 0) ? 14 : 7;
            float x2 = (float) (center + Math.sin(angle) * (radius - 4 - len));
            float y2 = (float) (center - Math.cos(angle) * (radius - 4 - len));
            markerPaint.setStrokeWidth(i % 3 == 0 ? 3 : 1.5f);
            canvas.drawLine(x1, y1, x2, y2, markerPaint);
        }

        // Preview time 10:09:30
        float hAngle = ((10 % 12) * 30 + 9 * 0.5f);
        float mAngle = (9 * 6);
        float sAngle = (30 * 6);

        float dialScale = (radius * 0.9f) / 233.0f;

        drawRotatedHand(canvas, center, center, hAngle, hourCfg, dialScale);
        drawRotatedHand(canvas, center, center, mAngle, minCfg, dialScale);
        drawRotatedHand(canvas, center, center, sAngle, secCfg, dialScale);

        // Center dot
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.WHITE);
        canvas.drawCircle(center, center, 4, dotPaint);

        return dial;
    }

    private static void drawRotatedHand(Canvas canvas, float cx, float cy, float angle, HandConfig cfg, float scale) {
        int w = Math.max(4, Math.round((cfg.width + 10) * scale));
        int h = Math.max(4, Math.round((cfg.handLength + cfg.tailLength + 10) * scale));
        HandConfig scaledCfg = new HandConfig(
                Math.max(1, Math.round(cfg.width * scale)),
                Math.max(1, Math.round(cfg.handLength * scale)),
                Math.max(1, Math.round(cfg.tailLength * scale)),
                cfg.tipStyle, cfg.color, cfg.glowColor, Math.round(cfg.glowBlur * scale),
                cfg.shadow, cfg.outline, cfg.outlineColor, Math.max(1, Math.round(cfg.outlineWidth * scale)),
                cfg.rounded
        );

        Bitmap handBmp = generateHandBitmap(scaledCfg, w, h);
        float pivotOffsetX = w / 2.0f;
        float pivotOffsetY = scaledCfg.handLength + 5; // pivot from top

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(angle);
        canvas.drawBitmap(handBmp, -pivotOffsetX, -pivotOffsetY, null);
        canvas.restore();
    }
}
