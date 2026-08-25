package com.example.dialsender;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DialCompiler {
    private static final String TAG = "DialCompiler";

    // ======== Block Types (matching comp_decomp.py BlockType enum) ========
    // Preview/Background
    public static final int TYPE_PREVIEW = 0x01;
    public static final int TYPE_BACKGROUND = 0x02;
    // Analog clock hands
    public static final int TYPE_ARM_HOUR = 0x03;
    public static final int TYPE_ARM_MIN = 0x04;
    public static final int TYPE_ARM_SEC = 0x05;
    // Date/Time
    public static final int TYPE_YEAR = 0x06;
    public static final int TYPE_MONTH = 0x07;
    public static final int TYPE_DAY = 0x08;
    public static final int TYPE_DIGITAL_HOUR = 0x09;
    public static final int TYPE_DIGITAL_MIN = 0x0A;
    public static final int TYPE_SECONDS = 0x0B;
    public static final int TYPE_AMPM = 0x0C;
    public static final int TYPE_WEEKDAY = 0x0D;
    // Health/Activity
    public static final int TYPE_STEPS = 0x0E;
    public static final int TYPE_HEART = 0x0F;
    public static final int TYPE_CALORIE = 0x10;
    public static final int TYPE_DISTANCE = 0x11;
    // Status
    public static final int TYPE_BATTERY = 0x12;
    public static final int TYPE_CONNECT = 0x13;
    // Decoration
    public static final int TYPE_BERRY = 0x16;
    public static final int TYPE_ANIM = 0x17;
    public static final int TYPE_BATT_STRIP = 0x18;
    public static final int TYPE_WEATHER = 0x19;
    public static final int TYPE_TEMP = 0x1A;
    // Progress bars
    public static final int TYPE_PROGRESS2 = 0x1E;
    public static final int TYPE_PROGRESS1 = 0x20;
    // Labels
    public static final int TYPE_LABEL = 0x25;
    // Digit splits
    public static final int TYPE_HOUR_LO = 0x27;
    public static final int TYPE_HOUR_HI = 0x28;
    public static final int TYPE_MIN_HI = 0x29;
    public static final int TYPE_MIN_LO = 0x2A;

    /**
     * Returns the BLK_xxx type string for a given block type code.
     * Used to populate dial_desc.json for comp_decomp.py.
     */
    public static String blockTypeToString(int type) {
        switch (type) {
            case TYPE_PREVIEW:
                return "BLK_PREV";
            case TYPE_BACKGROUND:
                return "BLK_BGIMG";
            case TYPE_ARM_HOUR:
                return "BLK_ARMH";
            case TYPE_ARM_MIN:
                return "BLK_ARMM";
            case TYPE_ARM_SEC:
                return "BLK_ARMS";
            case TYPE_YEAR:
                return "BLK_YEAR";
            case TYPE_MONTH:
                return "BLK_MONTH";
            case TYPE_DAY:
                return "BLK_DAY";
            case TYPE_DIGITAL_HOUR:
                return "BLK_HOUR";
            case TYPE_DIGITAL_MIN:
                return "BLK_MIN";
            case TYPE_SECONDS:
                return "BLK_SEC";
            case TYPE_AMPM:
                return "BLK_AMPM";
            case TYPE_WEEKDAY:
                return "BLK_WEEKD";
            case TYPE_STEPS:
                return "BLK_STEPS";
            case TYPE_HEART:
                return "BLK_PULSE";
            case TYPE_CALORIE:
                return "BLK_CALOR";
            case TYPE_DISTANCE:
                return "BLK_DIST";
            case TYPE_BATTERY:
                return "BLK_BATTN";
            case TYPE_CONNECT:
                return "BLK_CONN";
            case TYPE_BERRY:
                return "BLK_BERRY";
            case TYPE_ANIM:
                return "BLK_ANIMPART";
            case TYPE_BATT_STRIP:
                return "BLK_BATTS";
            case TYPE_WEATHER:
                return "BLK_WEAT";
            case TYPE_TEMP:
                return "BLK_TEMP";
            case TYPE_PROGRESS2:
                return "BLK_PROG2";
            case TYPE_PROGRESS1:
                return "BLK_PROG1";
            case TYPE_LABEL:
                return "BLK_LABEL";
            case TYPE_HOUR_LO:
                return "BLK_HOURL";
            case TYPE_HOUR_HI:
                return "BLK_HOURH";
            case TYPE_MIN_HI:
                return "BLK_MINH";
            case TYPE_MIN_LO:
                return "BLK_MINL";
            default:
                return "BLK_UNKNOWN";
        }
    }

    public static int getTypeFromString(String typeStr) {
        switch (typeStr) {
            case "BLK_PREV":
            case "BLK_PREVI":
                return TYPE_PREVIEW;
            case "BLK_BGIMG":
            case "BLK_BACKGROUND":
            case "BLK_BACKGROUND2":
                return TYPE_BACKGROUND;
            case "BLK_ARMH":
            case "BLK_ARM_HOUR":
                return TYPE_ARM_HOUR;
            case "BLK_ARMM":
            case "BLK_ARM_MINUTE":
                return TYPE_ARM_MIN;
            case "BLK_ARMS":
            case "BLK_ARM_SECOND":
                return TYPE_ARM_SEC;
            case "BLK_YEAR":
                return TYPE_YEAR;
            case "BLK_MONTH":
                return TYPE_MONTH;
            case "BLK_DAY":
                return TYPE_DAY;
            case "BLK_HOUR":
            case "BLK_HOURS":
                return TYPE_DIGITAL_HOUR;
            case "BLK_MIN":
            case "BLK_MINUTES":
                return TYPE_DIGITAL_MIN;
            case "BLK_SEC":
            case "BLK_SECONDS":
                return TYPE_SECONDS;
            case "BLK_AMPM":
                return TYPE_AMPM;
            case "BLK_WEEKD":
                return TYPE_WEEKDAY;
            case "BLK_STEPS":
                return TYPE_STEPS;
            case "BLK_PULSE":
            case "BLK_PULS":
                return TYPE_HEART;
            case "BLK_CALOR":
                return TYPE_CALORIE;
            case "BLK_DIST":
                return TYPE_DISTANCE;
            case "BLK_BATTN":
            case "BLK_BATTERY":
                return TYPE_BATTERY;
            case "BLK_CONN":
            case "BLK_CONNECT":
                return TYPE_CONNECT;
            case "BLK_BERRY":
            case "BLK_BIGYO":
                return TYPE_BERRY;
            case "BLK_ANIM":
            case "BLK_ANIMPART":
                return TYPE_ANIM;
            case "BLK_BATTS":
            case "BLK_BATTERY_STRIP":
                return TYPE_BATT_STRIP;
            case "BLK_WEAT":
            case "BLK_WEATHER":
                return TYPE_WEATHER;
            case "BLK_TEMP":
                return TYPE_TEMP;
            case "BLK_PROG2":
            case "BLK_PROGRESS2":
                return TYPE_PROGRESS2;
            case "BLK_PROG1":
            case "BLK_PROGRESS1":
                return TYPE_PROGRESS1;
            case "BLK_LABEL":
                return TYPE_LABEL;
            case "BLK_HOURL":
            case "BLK_HOUR_LO":
                return TYPE_HOUR_LO;
            case "BLK_HOURH":
            case "BLK_HOUR_HI":
                return TYPE_HOUR_HI;
            case "BLK_MINH":
            case "BLK_MINUTE_HI":
                return TYPE_MIN_HI;
            case "BLK_MINL":
            case "BLK_MINUTE_LO":
                return TYPE_MIN_LO;
            default:
                return 0;
        }
    }

    /**
     * Returns the expected frame count for a given block type.
     * This is how many sub-images a vertical sprite-sheet should contain.
     */
    public static int getDefaultFrameCount(int type) {
        switch (type) {
            case TYPE_DISTANCE:
                return 11; // digits 0-9 + '.'
            case TYPE_DIGITAL_HOUR:
            case TYPE_DIGITAL_MIN:
            case TYPE_SECONDS:
            case TYPE_STEPS:
            case TYPE_HEART:
            case TYPE_CALORIE:
            case TYPE_DAY:
            case TYPE_YEAR:
            case TYPE_HOUR_LO:
            case TYPE_HOUR_HI:
            case TYPE_MIN_HI:
            case TYPE_MIN_LO:
            case TYPE_TEMP:
                return 10; // digits 0-9
            case TYPE_WEEKDAY:
                return 7; // Mon-Sun
            case TYPE_MONTH:
                return 12; // Jan-Dec
            case TYPE_AMPM:
                return 2; // AM, PM
            case TYPE_BATTERY:
                return 10; // digits 0-9 (battery percentage)
            case TYPE_BATT_STRIP:
                return 6; // battery strip levels (0-5%, 6-20, 21-40, 41-60, 61-80, 81-100)
            case TYPE_WEATHER:
                return WeatherGenerator.FRAME_COUNT; // 12 weather condition icons
            case TYPE_CONNECT:
                return ConnectionGenerator.FRAME_COUNT; // 0 = disconnected, 1 = connected
            case TYPE_PROGRESS1:
            case TYPE_PROGRESS2:
                return ProgressGenerator.FRAME_COUNT; // 0 %, 10 % ... 100 %
            default:
                return 1; // single image
        }
    }

    /**
     * Returns a user-friendly label for a block type.
     */
    public static String blockTypeLabel(int type) {
        switch (type) {
            case TYPE_PREVIEW:
                return "Vista previa";
            case TYPE_BACKGROUND:
                return "Fondo";
            case TYPE_ARM_HOUR:
                return "Manecilla Hora";
            case TYPE_ARM_MIN:
                return "Manecilla Minuto";
            case TYPE_ARM_SEC:
                return "Manecilla Segundo";
            case TYPE_YEAR:
                return "Año";
            case TYPE_MONTH:
                return "Mes";
            case TYPE_DAY:
                return "Día";
            case TYPE_DIGITAL_HOUR:
                return "Hora (digital)";
            case TYPE_DIGITAL_MIN:
                return "Minuto (digital)";
            case TYPE_SECONDS:
                return "Segundos";
            case TYPE_AMPM:
                return "AM/PM";
            case TYPE_WEEKDAY:
                return "Día de semana";
            case TYPE_STEPS:
                return "Pasos";
            case TYPE_HEART:
                return "Pulso";
            case TYPE_CALORIE:
                return "Calorías";
            case TYPE_DISTANCE:
                return "Distancia";
            case TYPE_BATTERY:
                return "Batería (número)";
            case TYPE_CONNECT:
                return "Conexión";
            case TYPE_BERRY:
                return "Decoración";
            case TYPE_ANIM:
                return "Animación";
            case TYPE_BATT_STRIP:
                return "Batería (barra)";
            case TYPE_WEATHER:
                return "Clima";
            case TYPE_TEMP:
                return "Temperatura";
            case TYPE_PROGRESS2:
                return "Barra progreso 2";
            case TYPE_PROGRESS1:
                return "Barra progreso 1";
            case TYPE_LABEL:
                return "Etiqueta";
            case TYPE_HOUR_LO:
                return "Hora dígito bajo";
            case TYPE_HOUR_HI:
                return "Hora dígito alto";
            case TYPE_MIN_HI:
                return "Minuto dígito alto";
            case TYPE_MIN_LO:
                return "Minuto dígito bajo";
            default:
                return "Desconocido";
        }
    }

    /**
     * Returns the string resource ID for a given block type,
     * for use with getString() in activities.
     */
    public static int blockTypeLabelRes(int type) {
        switch (type) {
            case TYPE_PREVIEW:
                return R.string.blk_preview;
            case TYPE_BACKGROUND:
                return R.string.blk_background;
            case TYPE_ARM_HOUR:
                return R.string.blk_arm_hour;
            case TYPE_ARM_MIN:
                return R.string.blk_arm_min;
            case TYPE_ARM_SEC:
                return R.string.blk_arm_sec;
            case TYPE_YEAR:
                return R.string.blk_year;
            case TYPE_MONTH:
                return R.string.blk_month;
            case TYPE_DAY:
                return R.string.blk_day;
            case TYPE_DIGITAL_HOUR:
                return R.string.blk_hour;
            case TYPE_DIGITAL_MIN:
                return R.string.blk_min;
            case TYPE_SECONDS:
                return R.string.blk_sec;
            case TYPE_AMPM:
                return R.string.blk_ampm;
            case TYPE_WEEKDAY:
                return R.string.blk_weekday;
            case TYPE_STEPS:
                return R.string.blk_steps;
            case TYPE_HEART:
                return R.string.blk_heart;
            case TYPE_CALORIE:
                return R.string.blk_calorie;
            case TYPE_DISTANCE:
                return R.string.blk_distance;
            case TYPE_BATTERY:
                return R.string.blk_battery;
            case TYPE_CONNECT:
                return R.string.blk_connect;
            case TYPE_BERRY:
                return R.string.blk_berry;
            case TYPE_ANIM:
                return R.string.blk_anim;
            case TYPE_BATT_STRIP:
                return R.string.blk_batt_strip;
            case TYPE_WEATHER:
                return R.string.blk_weather;
            case TYPE_TEMP:
                return R.string.blk_temp;
            case TYPE_PROGRESS2:
                return R.string.blk_prog2;
            case TYPE_PROGRESS1:
                return R.string.blk_prog1;
            case TYPE_LABEL:
                return R.string.blk_label;
            case TYPE_HOUR_LO:
                return R.string.blk_hour_lo;
            case TYPE_HOUR_HI:
                return R.string.blk_hour_hi;
            case TYPE_MIN_HI:
                return R.string.blk_min_hi;
            case TYPE_MIN_LO:
                return R.string.blk_min_lo;
            default:
                return R.string.blk_unknown;
        }
    }

    public static class DialBlock {
        public int type;
        public int x;
        public int y;
        public int width;
        public int height;
        public int frames = 1;
        public int picIdx;
        public Bitmap[] images;
        public boolean hasAlpha = false;
        public int compress = 4; // Default to RLE (4=RLE in comp_decomp)
        public int animIntervalMs = 0; // For TYPE_ANIM: frame duration in ms (stored as centX)
        public int pivotTail = 0; // For hands: vertical rotation pivot in px from the image BOTTOM (already scaled)

        /**
         * Returns the color space string for the dial_desc.json.
         * Backgrounds and previews use RGB; overlays use RGBA.
         */
        public String getColorSpace() {
            return hasAlpha ? "RGBA" : "RGB";
        }
    }

    private List<DialBlock> blocks = new ArrayList<>();
    private int deviceWidth = 466;
    private int deviceHeight = 466;

    public DialCompiler(int width, int height) {
        this.deviceWidth = width;
        this.deviceHeight = height;
    }

    public void addBlock(DialBlock block) {
        blocks.add(block);
    }

    /**
     * Bytes a block occupies in the .bin when stored uncompressed.
     *
     * Frames are RGB565 (2 bytes/px) or RGBA5658 (3 bytes/px) with every row
     * padded to a 4-byte boundary. This is the ceiling: RLE never does worse
     * than a couple of percent above it, so it stays useful as a quick bound
     * for still blocks, where simulating the encoder would be overkill.
     */
    public static long estimateBlockBytes(int width, int height, int frames, boolean hasAlpha) {
        int bytesPerPixel = hasAlpha ? 3 : 2;
        long rowBytes = (long) Math.max(0, width) * bytesPerPixel;
        long alignedRow = (rowBytes + 3) & ~3L;
        long perFrame = alignedRow * Math.max(0, height);
        // +2% for the RLE run headers photographic content adds.
        return (long) (perFrame * Math.max(0, frames) * 1.02);
    }

    // ── Animation compression ──────────────────────────────────────────
    //
    // The container knows two codecs, raw and RLE, and the firmware would not
    // understand anything else, so the payload has to be shaped to suit the
    // RLE it does have: three or more identical RGB565 pixels in a row cost 3
    // bytes, anything else costs 1 byte plus 2 per pixel. Photographic video
    // has almost no such runs and lands near raw size, which is how a
    // full-screen animation reaches several megabytes.
    //
    // The factory dials show what the format is actually meant to hold. Their
    // animation frames carry 139-251 distinct RGB565 colours each — they are
    // palettised, not merely truncated to 16 bit — and they cover a fraction of
    // the face (300x269, 391x442, 200x270) rather than all of it, with whole
    // files landing at 757-944 KB. Matching that is what makes user footage
    // fit, so the primary lever here is a shared-palette median cut, with
    // horizontal binning as the secondary one.

    /**
     * Treatments in order of increasing harshness: {binX, palette size}, where
     * a palette of 0 means "leave the colours alone".
     *
     * Ordered rather than searched as a grid because compressed size falls
     * monotonically along it, which lets {@link #planAnimation} bisect and pay
     * for four measurements instead of fourteen. Colours go first and binning
     * only comes in once the palette is exhausted: dropping colours is close to
     * invisible on the footage people import, while binning visibly softens the
     * image horizontally.
     */
    private static final int[][] ANIM_LADDER = {
        { 1, 0 }, { 1, 256 }, { 1, 192 }, { 1, 128 }, { 1, 96 }, { 1, 64 },
        { 1, 48 }, { 1, 32 }, { 3, 64 }, { 3, 48 }, { 3, 32 }, { 3, 24 },
        { 4, 24 }, { 4, 16 },
    };

    /** Result of fitting an animation to a byte budget. */
    public static final class AnimPlan {
        /** Palette size applied, or 0 for "left at full colour". */
        public final int colors;
        /** Horizontal group width applied, 1 for "untouched". */
        public final int binX;
        /** Exact compressed size of the frames once the plan is applied. */
        public final long bytes;

        public AnimPlan(int colors, int binX, long bytes) {
            this.colors = colors;
            this.binX = Math.max(1, binX);
            this.bytes = bytes;
        }

        public boolean isLossless() { return colors == 0 && binX <= 1; }
    }

    /**
     * Finds the mildest rung of {@link #ANIM_LADDER} that brings {@code frames}
     * under {@code budgetBytes}, and returns it with the exact size it lands at.
     *
     * If even the bottom rung is too big the bottom rung is returned anyway —
     * the caller still needs a number to show, and whether to go ahead is the
     * user's call.
     */
    public static AnimPlan planAnimation(Bitmap[] frames, long budgetBytes) {
        if (frames == null || frames.length == 0 || frames[0] == null) {
            return new AnimPlan(0, 1, 0);
        }
        int w = frames[0].getWidth(), h = frames[0].getHeight();

        int[][] source = new int[frames.length][];
        for (int i = 0; i < frames.length; i++) {
            Bitmap f = frames[i] != null ? frames[i] : frames[0];
            int[] px = new int[w * h];
            f.getPixels(px, 0, w, 0, 0, w, h);
            source[i] = px;
        }

        // Binning is the expensive half and changes only three times along the
        // ladder, so the binned pixels are kept and reused across rungs.
        int[] cachedBin = { -1 };
        int[][][] cached = { null };

        int lo = 0, hi = ANIM_LADDER.length - 1;
        int found = -1;
        long foundBytes = 0;
        int lastRung = -1;
        long lastBytes = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long bytes = ladderBytes(source, w, h, mid, cachedBin, cached);
            lastRung = mid;
            lastBytes = bytes;
            if (bytes <= budgetBytes) {
                found = mid;
                foundBytes = bytes;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        if (found >= 0) {
            return new AnimPlan(ANIM_LADDER[found][1], ANIM_LADDER[found][0], foundBytes);
        }
        // Nothing on the ladder fits; hand back the bottom rung and its real size.
        int hardest = ANIM_LADDER.length - 1;
        long bytes = hardest == lastRung
                ? lastBytes
                : ladderBytes(source, w, h, hardest, cachedBin, cached);
        return new AnimPlan(ANIM_LADDER[hardest][1], ANIM_LADDER[hardest][0], bytes);
    }

    /** Compressed size of the frames at one rung, without building bitmaps. */
    private static long ladderBytes(int[][] source, int w, int h, int rung,
                                    int[] cachedBin, int[][][] cached) {
        int binX = ANIM_LADDER[rung][0];
        int colors = ANIM_LADDER[rung][1];

        if (cachedBin[0] != binX) {
            cached[0] = binX <= 1 ? source : binRows(source, w, h, binX);
            cachedBin[0] = binX;
        }
        int[][] work = cached[0];
        int[] map = colors > 0 ? paletteMap(work, colors) : null;

        long total = 0;
        for (int[] frame : work) total += rleSize(frame, w, h, map);
        return total;
    }

    /** Applies a plan produced by {@link #planAnimation}. */
    public static Bitmap[] applyPlan(Bitmap[] frames, AnimPlan plan) {
        if (frames == null || plan == null || plan.isLossless()) return frames;
        Bitmap[] out = plan.binX > 1 ? binHorizontally(frames, plan.binX) : frames;
        if (plan.colors > 0) out = quantizeShared(out, plan.colors);
        return out;
    }

    /**
     * Averages every {@code binX} pixels horizontally and repeats the result,
     * which guarantees each group reaches the encoder's 3-pixel run threshold.
     *
     * The gain is a step rather than a curve — measured on a 466x466, 12-frame
     * animation: 1978 KB untouched, 1827 KB at binX=2, 1077 KB at binX=3 —
     * since only at 3 does every group become a run. That is why the ladder
     * skips 2 entirely.
     */
    public static Bitmap[] binHorizontally(Bitmap[] frames, int binX) {
        if (frames == null || binX <= 1) return frames;
        Bitmap[] out = new Bitmap[frames.length];
        for (int i = 0; i < frames.length; i++) {
            Bitmap src = frames[i];
            if (src == null) continue;
            int w = src.getWidth(), h = src.getHeight();
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);
            binRow(px, w, h, binX);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.setPixels(px, 0, w, 0, 0, w, h);
            out[i] = b;
        }
        return out;
    }

    private static int[][] binRows(int[][] frames, int w, int h, int binX) {
        int[][] out = new int[frames.length][];
        for (int i = 0; i < frames.length; i++) {
            out[i] = frames[i].clone();
            binRow(out[i], w, h, binX);
        }
        return out;
    }

    private static void binRow(int[] px, int w, int h, int binX) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x0 = 0; x0 < w; x0 += binX) {
                int n = Math.min(binX, w - x0);
                int a = 0, r = 0, g = 0, b = 0;
                for (int k = 0; k < n; k++) {
                    int c = px[row + x0 + k];
                    a += (c >>> 24) & 0xFF;
                    r += (c >>> 16) & 0xFF;
                    g += (c >>> 8) & 0xFF;
                    b += c & 0xFF;
                }
                int packed = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
                for (int k = 0; k < n; k++) px[row + x0 + k] = packed;
            }
        }
    }

    /**
     * Reduces every frame to a single palette of at most {@code maxColors}.
     *
     * One palette for the whole animation rather than one per frame: a colour
     * that survives in frame 3 and not in frame 4 shows up as flicker, and a
     * shared palette also makes neighbouring pixels agree more often, which is
     * what the run encoder rewards. Measured on a 466x466, 12-frame clip:
     * 1978 KB at full colour, 1741 KB at 256, 1404 KB at 64, 1183 KB at 32.
     *
     * Deliberately undithered. Dithering would hide the banding but scatter
     * every flat area into noise, and noise is exactly what this format cannot
     * store — the factory dials are undithered for the same reason.
     */
    public static Bitmap[] quantizeShared(Bitmap[] frames, int maxColors) {
        if (frames == null || frames.length == 0 || maxColors <= 0) return frames;

        int w = -1, h = -1;
        for (Bitmap f : frames) {
            if (f == null) continue;
            w = f.getWidth(); h = f.getHeight();
            break;
        }
        if (w <= 0) return frames;

        int[][] px = new int[frames.length][];
        for (int i = 0; i < frames.length; i++) {
            if (frames[i] == null) continue;
            px[i] = new int[w * h];
            frames[i].getPixels(px[i], 0, w, 0, 0, w, h);
        }
        int[] map = paletteMap(px, maxColors);
        if (map == null) return frames;

        Bitmap[] out = new Bitmap[frames.length];
        for (int i = 0; i < frames.length; i++) {
            if (px[i] == null) continue;
            for (int j = 0; j < px[i].length; j++) {
                px[i][j] = (px[i][j] & 0xFF000000) | from565(map[to565(px[i][j])]);
            }
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.setPixels(px[i], 0, w, 0, 0, w, h);
            out[i] = b;
        }
        return out;
    }

    /**
     * Median cut over the colours present across every frame, returning a
     * 65536-entry table that maps each RGB565 value to its bucket's
     * representative, or null if there was nothing to reduce.
     *
     * Buckets are chosen for splitting by the squared error they carry — the
     * sum over their pixels of the distance to the bucket's own average — and
     * split at the pixel-weighted median of their widest channel. Splitting by
     * pixel count instead spends the palette subdividing a large flat sky;
     * splitting by colour-space volume spends it on a handful of stray
     * highlights and flattens that sky to one tone. Squared error is what
     * actually balances the two.
     */
    private static int[] paletteMap(int[][] frames, int maxColors) {
        int[] hist = new int[65536];
        for (int[] frame : frames) {
            if (frame == null) continue;
            for (int p : frame) hist[to565(p)]++;
        }

        int present = 0;
        for (int i = 0; i < 65536; i++) if (hist[i] != 0) present++;
        if (present <= maxColors) return null;

        int[] palette = new int[present];
        int k = 0;
        for (int i = 0; i < 65536; i++) if (hist[i] != 0) palette[k++] = i;

        int[] start = new int[maxColors];
        int[] end = new int[maxColors];
        double[] error = new double[maxColors];
        start[0] = 0;
        end[0] = present;
        error[0] = boxError(palette, hist, 0, present);
        int boxes = 1;

        int[] scratch = new int[present];

        while (boxes < maxColors) {
            int pick = -1;
            double worst = -1;
            for (int i = 0; i < boxes; i++) {
                if (end[i] - start[i] < 2) continue;
                if (error[i] > worst) { worst = error[i]; pick = i; }
            }
            if (pick < 0) break;

            int s = start[pick], e = end[pick];
            int rMin = 255, rMax = 0, gMin = 255, gMax = 0, bMin = 255, bMax = 0;
            long weight = 0;
            for (int i = s; i < e; i++) {
                int c = palette[i];
                weight += hist[c];
                int r = red565(c), g = green565(c), b = blue565(c);
                if (r < rMin) rMin = r;
                if (r > rMax) rMax = r;
                if (g < gMin) gMin = g;
                if (g > gMax) gMax = g;
                if (b < bMin) bMin = b;
                if (b > bMax) bMax = b;
            }
            int rSpan = rMax - rMin, gSpan = gMax - gMin, bSpan = bMax - bMin;
            int channel = (rSpan >= gSpan && rSpan >= bSpan) ? 0 : (gSpan >= bSpan ? 1 : 2);

            sortByChannel(palette, scratch, s, e, channel);

            long half = weight / 2;
            long acc = 0;
            int split = s + 1;
            for (int i = s; i < e - 1; i++) {
                acc += hist[palette[i]];
                if (acc >= half) { split = i + 1; break; }
                split = i + 2;
            }
            if (split <= s) split = s + 1;
            if (split >= e) split = e - 1;

            end[pick] = split;
            error[pick] = boxError(palette, hist, s, split);
            start[boxes] = split;
            end[boxes] = e;
            error[boxes] = boxError(palette, hist, split, e);
            boxes++;
        }

        int[] map = new int[65536];
        for (int i = 0; i < boxes; i++) {
            int rep = boxAverage(palette, hist, start[i], end[i]);
            for (int j = start[i]; j < end[i]; j++) map[palette[j]] = rep;
        }
        return map;
    }

    /** Pixel-weighted sum of squared distance to the bucket's average colour. */
    private static double boxError(int[] palette, int[] hist, int s, int e) {
        long n = 0, sr = 0, sg = 0, sb = 0, qr = 0, qg = 0, qb = 0;
        for (int i = s; i < e; i++) {
            int c = palette[i];
            long w = hist[c];
            int r = red565(c), g = green565(c), b = blue565(c);
            n += w;
            sr += w * r; sg += w * g; sb += w * b;
            qr += w * r * r; qg += w * g * g; qb += w * b * b;
        }
        if (n == 0) return 0;
        return (qr - (double) sr * sr / n)
             + (qg - (double) sg * sg / n)
             + (qb - (double) sb * sb / n);
    }

    /** The bucket's pixel-weighted average, packed back to RGB565. */
    private static int boxAverage(int[] palette, int[] hist, int s, int e) {
        long n = 0, sr = 0, sg = 0, sb = 0;
        for (int i = s; i < e; i++) {
            int c = palette[i];
            long w = hist[c];
            n += w;
            sr += w * red565(c); sg += w * green565(c); sb += w * blue565(c);
        }
        if (n == 0) n = 1;
        int r = (int) (sr / n), g = (int) (sg / n), b = (int) (sb / n);
        return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
    }

    /** Counting sort of palette[s,e) on one 565 channel; stable, no allocation. */
    private static void sortByChannel(int[] palette, int[] scratch, int s, int e, int channel) {
        int buckets = channel == 1 ? 64 : 32;
        int shift = channel == 0 ? 11 : (channel == 1 ? 5 : 0);
        int mask = buckets - 1;
        int[] counts = new int[buckets + 1];
        for (int i = s; i < e; i++) counts[((palette[i] >>> shift) & mask) + 1]++;
        for (int i = 0; i < buckets; i++) counts[i + 1] += counts[i];
        for (int i = s; i < e; i++) scratch[counts[(palette[i] >>> shift) & mask]++] = palette[i];
        System.arraycopy(scratch, 0, palette, s, e - s);
    }

    /** Exact compressed size of a whole RGB animation block. */
    public static long animationBytes(Bitmap[] frames) {
        if (frames == null) return 0;
        long total = 0;
        for (Bitmap f : frames) total += rleSizeRgb565(f);
        return total;
    }

    /**
     * Exact compressed size of one RGB frame, mirroring the RLE encoder in
     * hkcomposer.py: a run of three or more identical RGB565 pixels costs 3
     * bytes, anything else costs 1 byte plus 2 per pixel, runs and literal
     * blocks both cap at 127 pixels, and each frame carries a four-byte row
     * lookup entry per scanline before being padded to a 4-byte boundary.
     *
     * Simulated rather than approximated because the whole point of the size
     * readout is to tell the user, before a multi-minute transfer, whether the
     * dial will fit. Verified against the factory dials byte for byte.
     */
    public static long rleSizeRgb565(Bitmap frame) {
        if (frame == null) return 0;
        int w = frame.getWidth();
        int h = frame.getHeight();
        int[] px = new int[w * h];
        frame.getPixels(px, 0, w, 0, 0, w, h);
        return rleSize(px, w, h, null);
    }

    /**
     * @param map optional RGB565 -> RGB565 palette table, applied on the fly so
     *            the planner can price a palette without building the bitmaps
     */
    private static long rleSize(int[] px, int w, int h, int[] map) {
        long total = 4L * h; // per-scanline lookup table + skip offset
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int x = 0;
            while (x < w) {
                int cur = mapped(px[row + x], map);
                int run = 1;
                int maxRun = Math.min(127, w - x);
                while (run < maxRun && mapped(px[row + x + run], map) == cur) run++;

                if (run >= 3) {
                    total += 3;
                    x += run;
                } else {
                    // Literal block, ended by the next run of 3 or the row edge.
                    int lit = 0;
                    int maxLit = Math.min(127, w - x);
                    while (lit < maxLit) {
                        int c = mapped(px[row + x + lit], map);
                        int ahead = 1;
                        while (ahead < 3 && x + lit + ahead < w
                                && mapped(px[row + x + lit + ahead], map) == c) ahead++;
                        if (ahead >= 3) break;
                        lit++;
                    }
                    if (lit == 0) lit = 1;
                    total += 1 + 2L * lit;
                    x += lit;
                }
            }
        }
        return (total + 3) & ~3L;
    }

    private static int mapped(int argb, int[] map) {
        int c = to565(argb);
        return map == null ? c : map[c];
    }

    private static int to565(int argb) {
        int r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
        return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
    }

    private static int red565(int c)   { return (c >>> 11) << 3; }
    private static int green565(int c) { return ((c >>> 5) & 0x3F) << 2; }
    private static int blue565(int c)  { return (c & 0x1F) << 3; }

    private static int from565(int c) {
        return (red565(c) << 16) | (green565(c) << 8) | blue565(c);
    }

    /** True for the three analogue hands, which anchor by their pivot. */
    private static boolean isHandType(int type) {
        return type == TYPE_ARM_HOUR || type == TYPE_ARM_MIN || type == TYPE_ARM_SEC;
    }

    /**
     * Crops every block back inside the watch face.
     *
     * A block's position is stored in the binary as an unsigned 16-bit pair, so a
     * layer dragged past the top or left edge cannot be encoded at all: the
     * packer rejects the negative value and the whole compile dies with
     * "ushort format requires 0 <= number <= 65535", which says nothing about
     * which layer is at fault.
     *
     * The editor already previews such a layer clipped to the canvas, so the
     * compiled dial is made to match that preview: the pixels that fall outside
     * are cropped away and the origin moves to the edge. Overflow past the right
     * and bottom edges is cropped too — that never crashed, but it shipped a
     * block running off the screen.
     *
     * Hands are exempt: they anchor by their rotation pivot at the watch centre
     * and are meant to sweep beyond their nominal box.
     *
     * @throws IllegalStateException if a block ends up with nothing left on screen
     */
    private void clampBlocksToCanvas() {
        for (DialBlock block : blocks) {
            if (block.images == null || block.images.length == 0) continue;
            if (isHandType(block.type)) continue;
            if (block.type == TYPE_PREVIEW) continue;

            int cropLeft = block.x < 0 ? -block.x : 0;
            int cropTop = block.y < 0 ? -block.y : 0;
            int right = block.x + block.width;
            int bottom = block.y + block.height;
            int cropRight = right > deviceWidth ? right - deviceWidth : 0;
            int cropBottom = bottom > deviceHeight ? bottom - deviceHeight : 0;

            if (cropLeft == 0 && cropTop == 0 && cropRight == 0 && cropBottom == 0) {
                continue;
            }

            int newW = block.width - cropLeft - cropRight;
            int newH = block.height - cropTop - cropBottom;
            if (newW <= 0 || newH <= 0) {
                throw new IllegalStateException(OFF_CANVAS_PREFIX + block.type);
            }

            // Every frame is cropped with the same rect so a sprite sheet stays aligned.
            Bitmap[] cropped = new Bitmap[block.images.length];
            for (int i = 0; i < block.images.length; i++) {
                Bitmap src = block.images[i];
                if (src == null) {
                    cropped[i] = null;
                    continue;
                }
                int w = Math.min(newW, src.getWidth() - cropLeft);
                int h = Math.min(newH, src.getHeight() - cropTop);
                if (w <= 0 || h <= 0) {
                    throw new IllegalStateException(OFF_CANVAS_PREFIX + block.type);
                }
                cropped[i] = Bitmap.createBitmap(src, cropLeft, cropTop, w, h);
            }

            Log.d(TAG, "Cropped " + blockTypeToString(block.type)
                    + " from " + block.width + "x" + block.height + "@(" + block.x + "," + block.y + ")"
                    + " to " + newW + "x" + newH + "@(" + Math.max(0, block.x) + "," + Math.max(0, block.y) + ")");

            block.images = cropped;
            block.width = newW;
            block.height = newH;
            block.x = Math.max(0, block.x);
            block.y = Math.max(0, block.y);
        }
    }

    /** Marker + block type, which the editor turns into a localised message. */
    public static final String OFF_CANVAS_PREFIX = "FOGG_OFF_CANVAS:";

    public File compile(File outputDir, String filename) throws Exception {
        Log.d(TAG, "Starting compilation...");

        if (!Python.isStarted()) {
            throw new RuntimeException("Python not started. Initialize in Application or Activity.");
        }

        // Bring every block inside the face before anything is serialised: the
        // format cannot represent a negative position.
        clampBlocksToCanvas();

        File tempDir = new File(outputDir, "temp_compile_" + System.currentTimeMillis());
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Failed to create temp directory");
        }

        JSONObject root = new JSONObject();
        root.put("dial_name", filename);
        JSONArray jsonBlocks = new JSONArray();
        int blockIndex = 0;
        for (DialBlock block : blocks) {
            if (block.images == null || block.images.length == 0)
                continue;
            JSONObject jsonBlock = new JSONObject();
            String imgFilename = "block_" + blockIndex + ".png";
            jsonBlock.put("fname", imgFilename);
            jsonBlock.put("type", blockTypeToString(block.type));
            jsonBlock.put("colsp", block.getColorSpace());
            jsonBlock.put("comp", block.compress == 0 ? 0 : 4);
            jsonBlock.put("frms", block.frames);
            jsonBlock.put("posx", block.x);
            jsonBlock.put("posy", block.y);
            jsonBlock.put("w", block.width);
            // Alignment semantics verified against original dials (same convention
            // the desktop dial-designer uses): hands and animations require align=9;
            // every other block uses align=0 and anchors by its TOP-LEFT corner at
            // (posx, posy). align=10 + centre-anchoring mispositions elements.
            boolean isHand = block.type == TYPE_ARM_HOUR || block.type == TYPE_ARM_MIN
                    || block.type == TYPE_ARM_SEC;
            jsonBlock.put("alnx", (isHand || block.type == TYPE_ANIM) ? 9 : 0);
            if (block.type == TYPE_ANIM) {
                // centX = frame interval in 10ms units; the watch needs it nonzero
                jsonBlock.put("ctx", Math.max(1, block.animIntervalMs / 10));
                jsonBlock.put("cty", 0);
            } else if (isHand) {
                // Hands anchor by their rotation pivot: posx/posy place that pivot at
                // the watch centre. Byte order verified against original dials:
                //   ctx = VERTICAL pivot measured from the image BOTTOM
                //   cty = HORIZONTAL pivot from the left (= width / 2)
                // Swapping them makes the hand orbit around the wrong point.
                jsonBlock.put("posx", deviceWidth / 2);
                jsonBlock.put("posy", deviceHeight / 2);
                jsonBlock.put("ctx", block.pivotTail > 0 ? block.pivotTail : 24);
                jsonBlock.put("cty", block.width / 2);
            } else {
                jsonBlock.put("ctx", 0);
                jsonBlock.put("cty", 0);
            }
            Bitmap imageToSave = combineBitmapsVertically(block.images);
            File imgFile = new File(tempDir, imgFilename);
            try (FileOutputStream fos = new FileOutputStream(imgFile)) {
                imageToSave.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            Log.d(TAG, "Block " + blockIndex + ": type=" + blockTypeToString(block.type)
                    + ", colsp=" + block.getColorSpace()
                    + ", size=" + block.width + "x" + block.height
                    + ", hasAlpha=" + block.hasAlpha);
            jsonBlocks.put(jsonBlock);
            blockIndex++;
        }
        root.put("blocks", jsonBlocks);

        File jsonFile = new File(tempDir, "dial_desc.json");
        try (FileOutputStream fos = new FileOutputStream(jsonFile)) {
            fos.write(root.toString(2).getBytes());
        }
        Log.d(TAG, "Wrote dial_desc.json to " + jsonFile.getAbsolutePath());

        Python py = Python.getInstance();
        PyObject composerModule = py.getModule("comp_decomp");
        File outFile = new File(outputDir, filename);

        try {
            PyObject result = composerModule.callAttr("compile_dial",
                    tempDir.getAbsolutePath(),
                    outFile.getAbsolutePath());
            if (result != null && result.toBoolean()) {
                Log.d(TAG, "Compilation success: " + outFile.length() + " bytes");
                deleteRecursive(tempDir);
                return outFile;
            } else {
                throw new Exception("Python compilation returned false");
            }
        } catch (Exception e) {
            throw new Exception("Python compilation failed: " + e.getMessage());
        }
    }

    private String getBlockTypeString(int type) {
        switch (type) {
            case TYPE_PREVIEW:
                return "BLK_PREV";
            case TYPE_BACKGROUND:
                return "BLK_BGIMG";
            case TYPE_DIGITAL_HOUR:
                return "BLK_HOUR";
            case TYPE_DIGITAL_MIN:
                return "BLK_MIN";
            case TYPE_STEPS:
                return "BLK_STEPS";
            case TYPE_HEART:
                return "BLK_PULS";
            case TYPE_CALORIE:
                return "BLK_CALOR";
            default:
                return "BLK_PREV";
        }
    }

    /**
     * Normalize any input image for the watch firmware.
     * <p>
     * This method performs the following transformations:
     * <ul>
     * <li>Center-crops to square aspect ratio (if needed)</li>
     * <li>Scales to exact target dimensions</li>
     * <li>Quantizes colors to RGB565 palette (5-bit R, 6-bit G, 5-bit B)</li>
     * <li>Flattens alpha (composites onto black background)</li>
     * </ul>
     * <p>
     * This ensures images from any source (internet, photos, screenshots)
     * produce the same limited color palette as factory dial backgrounds,
     * resulting in efficient and firmware-compatible RLE compression.
     *
     * @param src     Source bitmap (any format, any size)
     * @param targetW Target width (e.g. 466 for backgrounds, 280 for previews)
     * @param targetH Target height
     * @return Processed ARGB_8888 bitmap with RGB565-quantized colors
     */
    public static Bitmap normalizeForWatch(Bitmap src, int targetW, int targetH) {
        if (src == null)
            return null;

        // Step 1: Center-crop to target aspect ratio
        float targetAspect = (float) targetW / targetH;
        float srcAspect = (float) src.getWidth() / src.getHeight();

        int cropW, cropH, cropX, cropY;
        if (srcAspect > targetAspect) {
            // Source is wider — crop sides
            cropH = src.getHeight();
            cropW = (int) (cropH * targetAspect);
            cropX = (src.getWidth() - cropW) / 2;
            cropY = 0;
        } else {
            // Source is taller — crop top/bottom
            cropW = src.getWidth();
            cropH = (int) (cropW / targetAspect);
            cropX = 0;
            cropY = (src.getHeight() - cropH) / 2;
        }

        // Step 2: Scale to exact target dimensions
        Bitmap result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        // Draw solid black first to flatten any alpha
        canvas.drawColor(Color.BLACK);
        // Draw the cropped source scaled to fill the target
        Rect srcRect = new Rect(cropX, cropY, cropX + cropW, cropY + cropH);
        Rect dstRect = new Rect(0, 0, targetW, targetH);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(src, srcRect, dstRect, paint);

        // Step 3: Quantize to RGB565 palette
        // This reduces the color depth so the RLE compressor produces
        // efficient, firmware-compatible data regardless of input complexity.
        int[] pixels = new int[targetW * targetH];
        result.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (Color.red(c) >> 3) << 3; // 5-bit -> 8-bit
            int g = (Color.green(c) >> 2) << 2; // 6-bit -> 8-bit
            int b = (Color.blue(c) >> 3) << 3; // 5-bit -> 8-bit
            pixels[i] = Color.argb(255, r, g, b);
        }
        result.setPixels(pixels, 0, targetW, 0, 0, targetW, targetH);

        return result;
    }

    private Bitmap combineBitmapsVertically(Bitmap[] bitmaps) {
        if (bitmaps.length == 1)
            return bitmaps[0];
        int w = bitmaps[0].getWidth();
        int h = 0;
        for (Bitmap b : bitmaps)
            h += b.getHeight();

        // Preserve the input config (RGB_565 for backgrounds, ARGB_8888 for RGBA)
        Bitmap.Config config = bitmaps[0].getConfig();
        if (config == null)
            config = Bitmap.Config.ARGB_8888;
        Bitmap combined = Bitmap.createBitmap(w, h, config);
        Canvas c = new Canvas(combined);
        if (config == Bitmap.Config.RGB_565) {
            // Fill black for RGB (no alpha) to avoid uninitialized pixels
            c.drawColor(android.graphics.Color.BLACK);
        }
        int y = 0;
        for (Bitmap b : bitmaps) {
            c.drawBitmap(b, 0, y, null);
            y += b.getHeight();
        }
        return combined;
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }
}
