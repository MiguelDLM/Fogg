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
                return 2; // connected, disconnected
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

    public File compile(File outputDir, String filename) throws Exception {
        Log.d(TAG, "Starting compilation...");

        if (!Python.isStarted()) {
            throw new RuntimeException("Python not started. Initialize in Application or Activity.");
        }

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
