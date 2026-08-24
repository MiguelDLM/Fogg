package com.example.dialsender;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

public class WeatherGenerator {

    public static class WeatherConfig {
        public int frameWidth = 64;
        public int frameHeight = 64;
        public int frameCount = 12;
        public int iconColor = Color.WHITE;
        public int sunColor = Color.parseColor("#F59E0B");
        public int cloudColor = Color.parseColor("#94A3B8");
        public int rainColor = Color.parseColor("#38BDF8");
        public int strokeWidth = 3;

        public WeatherConfig() {}
    }

    public interface OnWeatherGeneratedListener {
        void onWeatherGenerated(Bitmap sheetBmp, int frameWidth, int frameHeight, int frameCount, WeatherConfig config);
    }

    public static Bitmap generateSingleFrame(WeatherConfig cfg, int weatherIndex) {
        int w = cfg.frameWidth;
        int h = cfg.frameHeight;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float r = Math.min(w, h) * 0.35f;

        Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sunPaint.setColor(cfg.sunColor);
        sunPaint.setStyle(Paint.Style.FILL);

        Paint cloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cloudPaint.setColor(cfg.cloudColor);
        cloudPaint.setStyle(Paint.Style.FILL);

        Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rainPaint.setColor(cfg.rainColor);
        rainPaint.setStyle(Paint.Style.STROKE);
        rainPaint.setStrokeWidth(cfg.strokeWidth);
        rainPaint.setStrokeCap(Paint.Cap.ROUND);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(cfg.iconColor);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(cfg.strokeWidth);

        switch (weatherIndex) {
            case 1: // Sunny
                canvas.drawCircle(cx, cy, r * 0.55f, sunPaint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float x1 = (float) (cx + Math.cos(a) * (r * 0.7f));
                    float y1 = (float) (cy + Math.sin(a) * (r * 0.7f));
                    float x2 = (float) (cx + Math.cos(a) * (r * 0.95f));
                    float y2 = (float) (cy + Math.sin(a) * (r * 0.95f));
                    canvas.drawLine(x1, y1, x2, y2, strokePaint);
                }
                break;

            case 2: // Cloudy (Sun + Cloud)
                canvas.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r * 0.4f, sunPaint);
                drawCloud(canvas, cx + r * 0.1f, cy + r * 0.2f, r * 0.8f, cloudPaint);
                break;

            case 3: // Overcast (Cloud)
                drawCloud(canvas, cx, cy, r * 0.9f, cloudPaint);
                break;

            case 4: // Rainy
                drawCloud(canvas, cx, cy - r * 0.2f, r * 0.8f, cloudPaint);
                canvas.drawLine(cx - r * 0.3f, cy + r * 0.3f, cx - r * 0.4f, cy + r * 0.65f, rainPaint);
                canvas.drawLine(cx, cy + r * 0.3f, cx - r * 0.1f, cy + r * 0.65f, rainPaint);
                canvas.drawLine(cx + r * 0.3f, cy + r * 0.3f, cx + r * 0.2f, cy + r * 0.65f, rainPaint);
                break;

            case 5: // Thunder
            case 6: // Thundershower
                drawCloud(canvas, cx, cy - r * 0.2f, r * 0.8f, cloudPaint);
                drawThunderBolt(canvas, cx, cy + r * 0.4f, r * 0.5f, cfg.sunColor);
                break;

            case 8: // Snowy
                drawCloud(canvas, cx, cy - r * 0.2f, r * 0.8f, cloudPaint);
                Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                dotPaint.setColor(Color.WHITE);
                canvas.drawCircle(cx - r * 0.3f, cy + r * 0.45f, 3, dotPaint);
                canvas.drawCircle(cx, cy + r * 0.55f, 3, dotPaint);
                canvas.drawCircle(cx + r * 0.3f, cy + r * 0.45f, 3, dotPaint);
                break;

            case 9: // Foggy
                drawCloud(canvas, cx, cy - r * 0.3f, r * 0.75f, cloudPaint);
                canvas.drawLine(cx - r * 0.6f, cy + r * 0.35f, cx + r * 0.6f, cy + r * 0.35f, strokePaint);
                canvas.drawLine(cx - r * 0.4f, cy + r * 0.55f, cx + r * 0.4f, cy + r * 0.55f, strokePaint);
                break;

            default: // 0, 7, 10, 11 (General / Wind / Other)
                drawCloud(canvas, cx, cy, r * 0.85f, cloudPaint);
                break;
        }

        return bmp;
    }

    private static void drawCloud(Canvas canvas, float cx, float cy, float size, Paint paint) {
        float r = size * 0.35f;
        canvas.drawCircle(cx - r * 0.6f, cy, r * 0.7f, paint);
        canvas.drawCircle(cx + r * 0.6f, cy, r * 0.7f, paint);
        canvas.drawCircle(cx, cy - r * 0.4f, r * 0.9f, paint);
        canvas.drawRect(cx - r * 0.6f, cy - r * 0.1f, cx + r * 0.6f, cy + r * 0.7f, paint);
    }

    private static void drawThunderBolt(Canvas canvas, float cx, float cy, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);

        Path path = new Path();
        path.moveTo(cx + size * 0.15f, cy - size * 0.5f);
        path.lineTo(cx - size * 0.3f, cy);
        path.lineTo(cx + size * 0.05f, cy);
        path.lineTo(cx - size * 0.15f, cy + size * 0.5f);
        path.lineTo(cx + size * 0.3f, cy - size * 0.05f);
        path.lineTo(cx - size * 0.05f, cy - size * 0.05f);
        path.close();
        canvas.drawPath(path, p);
    }

    public static Bitmap generateVerticalSpriteSheet(WeatherConfig cfg) {
        int w = cfg.frameWidth;
        int h = cfg.frameHeight;
        int totalH = h * cfg.frameCount;
        Bitmap fullSheet = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(fullSheet);

        for (int i = 0; i < cfg.frameCount; i++) {
            Bitmap frameBmp = generateSingleFrame(cfg, i);
            canvas.drawBitmap(frameBmp, 0, i * h, null);
            frameBmp.recycle();
        }

        return fullSheet;
    }
}
