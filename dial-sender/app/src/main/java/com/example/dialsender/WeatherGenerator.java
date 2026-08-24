package com.example.dialsender;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Draws the 12-frame BLK_WEATHER strip the firmware expects.
 *
 * Frame order is fixed by the watch (see docs/DIAL_FORMAT_GUIDE.md §6):
 * 0 sunny · 1 partly cloudy · 2 cloudy · 3 overcast · 4 light rain · 5 rain ·
 * 6 heavy rain · 7 thunderstorm · 8 snow · 9 fog · 10 hail · 11 windy.
 * Every frame must be visually distinct — the watch picks one by condition
 * code, so two identical frames mean two conditions the user cannot tell apart.
 */
public class WeatherGenerator {

    /** The watch always reads 12 frames from a weather block. */
    public static final int FRAME_COUNT = 12;

    public static final int SUNNY        = 0;
    public static final int PARTLY_CLOUDY= 1;
    public static final int CLOUDY       = 2;
    public static final int OVERCAST     = 3;
    public static final int LIGHT_RAIN   = 4;
    public static final int RAIN         = 5;
    public static final int HEAVY_RAIN   = 6;
    public static final int THUNDERSTORM = 7;
    public static final int SNOW         = 8;
    public static final int FOG          = 9;
    public static final int HAIL         = 10;
    public static final int WINDY        = 11;

    public static class WeatherConfig {
        public int frameWidth  = 64;
        public int frameHeight = 64;
        public int frameCount  = FRAME_COUNT;
        /** Line-art instead of solid shapes. */
        public boolean outline = false;
        public int sunColor   = Color.parseColor("#F59E0B");
        public int cloudColor = Color.parseColor("#CBD5E1");
        public int rainColor  = Color.parseColor("#38BDF8");
        public int boltColor  = Color.parseColor("#FACC15");
        public int snowColor  = Color.WHITE;
        public int strokeWidth = 3;
        /** Fraction of the frame the artwork fills. */
        public float iconScale = 0.92f;

        public WeatherConfig() {}

        public WeatherConfig copy() {
            WeatherConfig c = new WeatherConfig();
            c.frameWidth = frameWidth;
            c.frameHeight = frameHeight;
            c.frameCount = frameCount;
            c.outline = outline;
            c.sunColor = sunColor;
            c.cloudColor = cloudColor;
            c.rainColor = rainColor;
            c.boltColor = boltColor;
            c.snowColor = snowColor;
            c.strokeWidth = strokeWidth;
            c.iconScale = iconScale;
            return c;
        }
    }

    public interface OnWeatherGeneratedListener {
        void onWeatherGenerated(Bitmap sheetBmp, int frameWidth, int frameHeight, int frameCount, WeatherConfig config);
    }

    // ===================== PUBLIC API =====================

    public static Bitmap generateSingleFrame(WeatherConfig cfg, int weatherIndex) {
        int w = Math.max(8, cfg.frameWidth);
        int h = Math.max(8, cfg.frameHeight);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Everything is laid out inside a centred square so icons keep their
        // proportions on non-square frames.
        float s = Math.min(w, h) * clamp(cfg.iconScale, 0.4f, 1.0f);
        RectF box = new RectF((w - s) / 2f, (h - s) / 2f, (w + s) / 2f, (h + s) / 2f);
        float stroke = Math.max(1f, cfg.strokeWidth * s / 64f);

        switch (weatherIndex) {
            case SUNNY:         drawSun(canvas, cfg, box, 1.0f, stroke); break;
            case PARTLY_CLOUDY: drawPartlyCloudy(canvas, cfg, box, stroke); break;
            case CLOUDY:        drawDoubleCloud(canvas, cfg, box, stroke); break;
            case OVERCAST:      drawOvercast(canvas, cfg, box, stroke); break;
            case LIGHT_RAIN:    drawRain(canvas, cfg, box, stroke, 2, 0.16f); break;
            case RAIN:          drawRain(canvas, cfg, box, stroke, 3, 0.22f); break;
            case HEAVY_RAIN:    drawRain(canvas, cfg, box, stroke, 4, 0.30f); break;
            case THUNDERSTORM:  drawThunder(canvas, cfg, box, stroke); break;
            case SNOW:          drawSnow(canvas, cfg, box, stroke); break;
            case FOG:           drawFog(canvas, cfg, box, stroke); break;
            case HAIL:          drawHail(canvas, cfg, box, stroke); break;
            case WINDY:         drawWindy(canvas, cfg, box, stroke); break;
            default:            drawOvercast(canvas, cfg, box, stroke); break;
        }
        return bmp;
    }

    public static Bitmap generateVerticalSpriteSheet(WeatherConfig cfg) {
        int w = Math.max(8, cfg.frameWidth);
        int h = Math.max(8, cfg.frameHeight);
        int count = Math.max(1, cfg.frameCount);
        Bitmap fullSheet = Bitmap.createBitmap(w, h * count, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(fullSheet);
        for (int i = 0; i < count; i++) {
            Bitmap frameBmp = generateSingleFrame(cfg, i);
            canvas.drawBitmap(frameBmp, 0, i * h, null);
            frameBmp.recycle();
        }
        return fullSheet;
    }

    // ===================== ICONS =====================

    /** @param radiusFactor 1.0 = full-size sun, smaller values tuck it behind a cloud. */
    private static void drawSun(Canvas c, WeatherConfig cfg, RectF box, float radiusFactor, float stroke) {
        float s = box.width();
        float cx = box.centerX(), cy = box.centerY();
        if (radiusFactor < 1.0f) {
            // Peeking sun sits up and to the left of the cloud it pairs with.
            cx = box.left + s * 0.34f;
            cy = box.top  + s * 0.30f;
        }
        float r = s * 0.21f * radiusFactor;
        float rayIn  = r * 1.42f;
        float rayOut = r * 2.05f;

        Paint disc = paint(cfg.sunColor, cfg.outline, stroke);
        c.drawCircle(cx, cy, cfg.outline ? r - stroke / 2f : r, disc);

        Paint ray = new Paint(Paint.ANTI_ALIAS_FLAG);
        ray.setColor(cfg.sunColor);
        ray.setStyle(Paint.Style.STROKE);
        ray.setStrokeWidth(stroke);
        ray.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45 + 22.5);
            c.drawLine((float) (cx + Math.cos(a) * rayIn),  (float) (cy + Math.sin(a) * rayIn),
                       (float) (cx + Math.cos(a) * rayOut), (float) (cy + Math.sin(a) * rayOut), ray);
        }
    }

    private static void drawPartlyCloudy(Canvas c, WeatherConfig cfg, RectF box, float stroke) {
        float s = box.width();
        drawSun(c, cfg, box, 0.72f, stroke);
        RectF cloud = new RectF(box.left + s * 0.06f, box.top + s * 0.40f,
                                box.left + s * 0.94f, box.top + s * 0.86f);
        drawCloud(c, cfg, cloud, cfg.cloudColor, stroke);
    }

    private static void drawDoubleCloud(Canvas c, WeatherConfig cfg, RectF box, float stroke) {
        float s = box.width();
        // Back cloud, dimmed, so "cloudy" reads differently from "overcast".
        RectF back = new RectF(box.left + s * 0.26f, box.top + s * 0.16f,
                               box.left + s * 0.98f, box.top + s * 0.54f);
        drawCloud(c, cfg, back, dim(cfg.cloudColor, 0.60f), stroke);
        RectF front = new RectF(box.left + s * 0.02f, box.top + s * 0.42f,
                                box.left + s * 0.82f, box.top + s * 0.88f);
        drawCloud(c, cfg, front, cfg.cloudColor, stroke);
    }

    private static void drawOvercast(Canvas c, WeatherConfig cfg, RectF box, float stroke) {
        float s = box.width();
        RectF cloud = new RectF(box.left + s * 0.03f, box.top + s * 0.26f,
                                box.left + s * 0.97f, box.top + s * 0.80f);
        drawCloud(c, cfg, cloud, cfg.cloudColor, stroke);
    }

    private static void drawRain(Canvas c, WeatherConfig cfg, RectF box, float stroke,
                                 int drops, float dropLen) {
        float s = box.width();
        RectF cloud = new RectF(box.left + s * 0.06f, box.top + s * 0.12f,
                                box.left + s * 0.94f, box.top + s * 0.58f);
        drawCloud(c, cfg, cloud, cfg.cloudColor, stroke);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(cfg.rainColor);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        float top = box.top + s * 0.62f;
        float len = s * dropLen;
        float span = s * 0.56f;
        float x0 = box.centerX() - span / 2f;
        float step = drops > 1 ? span / (drops - 1) : 0;
        for (int i = 0; i < drops; i++) {
            float x = drops > 1 ? x0 + i * step : box.centerX();
            float y = top + (i % 2 == 0 ? 0 : s * 0.06f);
            c.drawLine(x, y, x - s * 0.06f, y + len, p);
        }
    }

    private static void drawThunder(Canvas c, WeatherConfig cfg, RectF box, float stroke) {
        float s = box.width();
        RectF cloud = new RectF(box.left + s * 0.06f, box.top + s * 0.10f,
                                box.left + s * 0.94f, box.top + s * 0.56f);
        drawCloud(c, cfg, cloud, cfg.cloudColor, stroke);

        Path bolt = new Path();
        float bx = box.centerX(), by = box.top + s * 0.58f;
        bolt.moveTo(bx + s * 0.10f, by);
        bolt.lineTo(bx - s * 0.14f, by + s * 0.20f);
        bolt.lineTo(bx + s * 0.01f, by + s * 0.20f);
        bolt.lineTo(bx - s * 0.09f, by + s * 0.40f);
        bolt.lineTo(bx + s * 0.16f, by + s * 0.14f);
        bolt.lineTo(bx + s * 0.01f, by + s * 0.14f);
        bolt.close();
        c.drawPath(bolt, paint(cfg.boltColor, cfg.outline, stroke));
    }

    private static void drawSnow(Canvas c, WeatherConfig cfg, RectF box, float stroke) {
        float s = box.width();
        RectF cloud = new RectF(box.left + s * 0.06f, box.top + s * 0.12f,
                                box.left + s * 0.94f, box.top + s * 0.58f);
        drawCloud(c, cfg, cloud, cfg.cloudColor, stroke);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(cfg.snowColor);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, stroke * 0.8f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float r = s * 0.075f;
        float[][] centres = {
                { box.centerX() - s * 0.24f, box.top + s * 0.72f },
                { box.centerX(),             box.top + s * 0.84f },
                { box.centerX() + s * 0.24f, box.top + s * 0.72f }
        };
        for (float[] q : centres) {
            for (int i = 0; i < 3; i++) {
                double a = Math.toRadians(i * 60);
                float dx = (float) (Math.cos(a) * r), dy = (float) (Math.sin(a) * r);
                c.drawLine(q[0] - dx, q[1] - dy, q[0] + dx, q[1] + dy, p);
            }
        }
    }

    private static void drawFog(Canvas c, WeatherConfig cfg, RectF box, float stroke) {
        float s = box.width();
        RectF cloud = new RectF(box.left + s * 0.06f, box.top + s * 0.08f,
                                box.left + s * 0.94f, box.top + s * 0.52f);
        drawCloud(c, cfg, cloud, cfg.cloudColor, stroke);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(dim(cfg.cloudColor, 0.75f));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke * 1.15f);
        p.setStrokeCap(Paint.Cap.ROUND);
        float[][] lines = {
                { 0.10f, 0.66f, 0.86f },
                { 0.22f, 0.78f, 0.72f },
                { 0.14f, 0.90f, 0.80f }
        };
        for (float[] l : lines) {
            float y = box.top + s * l[1];
            c.drawLine(box.left + s * l[0], y, box.left + s * l[2], y, p);
        }
    }

    private static void drawHail(Canvas c, WeatherConfig cfg, RectF box, float stroke) {
        float s = box.width();
        RectF cloud = new RectF(box.left + s * 0.06f, box.top + s * 0.12f,
                                box.left + s * 0.94f, box.top + s * 0.58f);
        drawCloud(c, cfg, cloud, cfg.cloudColor, stroke);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(cfg.rainColor);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(stroke);
        line.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(box.centerX() - s * 0.18f, box.top + s * 0.64f,
                   box.centerX() - s * 0.24f, box.top + s * 0.84f, line);
        c.drawLine(box.centerX() + s * 0.24f, box.top + s * 0.64f,
                   box.centerX() + s * 0.18f, box.top + s * 0.84f, line);

        // Pellets tell hail apart from plain rain.
        Paint pellet = paint(cfg.snowColor, cfg.outline, Math.max(1f, stroke * 0.8f));
        float r = s * 0.055f;
        c.drawCircle(box.centerX() - s * 0.02f, box.top + s * 0.70f, r, pellet);
        c.drawCircle(box.centerX() + s * 0.06f, box.top + s * 0.86f, r, pellet);
    }

    private static void drawWindy(Canvas c, WeatherConfig cfg, RectF box, float stroke) {
        float s = box.width();
        RectF cloud = new RectF(box.left + s * 0.10f, box.top + s * 0.12f,
                                box.left + s * 0.86f, box.top + s * 0.50f);
        drawCloud(c, cfg, cloud, dim(cfg.cloudColor, 0.85f), stroke);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(cfg.rainColor);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        // Three gusts, each ending in a curl.
        float[][] gusts = {
                { 0.08f, 0.62f, 0.60f, 0.10f },
                { 0.16f, 0.76f, 0.74f, 0.12f },
                { 0.10f, 0.90f, 0.52f, 0.09f }
        };
        for (float[] g : gusts) {
            float y  = box.top + s * g[1];
            float x0 = box.left + s * g[0];
            float x1 = box.left + s * g[2];
            float curl = s * g[3];
            Path path = new Path();
            path.moveTo(x0, y);
            path.lineTo(x1, y);
            path.cubicTo(x1 + curl, y, x1 + curl, y - curl, x1 + curl * 0.35f, y - curl * 0.55f);
            c.drawPath(path, p);
        }
    }

    // ===================== PRIMITIVES =====================

    /**
     * A cloud built as a single unioned path, so outline mode strokes the
     * silhouette instead of the seams between the bumps it is made of.
     */
    private static void drawCloud(Canvas c, WeatherConfig cfg, RectF box, int color, float stroke) {
        float w = box.width(), h = box.height();
        float x = box.left, y = box.top;

        Path path = new Path();
        Path piece = new Path();
        piece.addCircle(x + w * 0.28f, y + h * 0.62f, h * 0.38f, Path.Direction.CW);
        path.op(piece, Path.Op.UNION);
        piece.reset();
        piece.addCircle(x + w * 0.52f, y + h * 0.44f, h * 0.44f, Path.Direction.CW);
        path.op(piece, Path.Op.UNION);
        piece.reset();
        piece.addCircle(x + w * 0.76f, y + h * 0.60f, h * 0.40f, Path.Direction.CW);
        path.op(piece, Path.Op.UNION);
        piece.reset();
        piece.addRect(x + w * 0.28f, y + h * 0.55f, x + w * 0.76f, y + h, Path.Direction.CW);
        path.op(piece, Path.Op.UNION);

        c.drawPath(path, paint(color, cfg.outline, stroke));
    }

    private static Paint paint(int color, boolean outline, float stroke) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        if (outline) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeJoin(Paint.Join.ROUND);
        } else {
            p.setStyle(Paint.Style.FILL);
        }
        return p;
    }

    private static int dim(int color, float factor) {
        return Color.argb(Color.alpha(color),
                Math.round(Color.red(color) * factor),
                Math.round(Color.green(color) * factor),
                Math.round(Color.blue(color) * factor));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
