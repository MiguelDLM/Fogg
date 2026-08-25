package com.example.dialsender;

import android.graphics.Bitmap;

public class DialLayer {
    public static final int TYPE_BACKGROUND = 0;
    public static final int TYPE_ELEMENT = 1;
    public static final int TYPE_ARM = 2;
    public static final int TYPE_PREVIEW = 3;

    public int layerType;
    public Bitmap icon; // Thumbnail or first frame
    public String name;
    public int nativeElementType; // From DialCompiler type constants

    // Transform
    public float scale = 1.0f;
    public float rotation = 0;
    public float posX = 0;
    public float posY = 0;
    public float alpha = 1.0f;

    // Sprite sheet support
    public Bitmap[] frames; // Individual sub-image frames
    public int frameCount = 1;
    public boolean isSpriteSheet = false;
    public boolean locked = false;

    // Time group support
    public String  layerId         = java.util.UUID.randomUUID().toString();
    public String  timeGroupId     = null;   // null = not part of any time group
    public boolean pendingStyle    = false;  // true = placeholder, no style assigned yet
    public boolean isColonSeparator = false; // true = the ":" between HH/MM/SS, not compiled

    // Animation interval (ms per frame, for TYPE_ANIM blocks only)
    public int animIntervalMs = 100;

    // How the animation frames are squeezed to fit a dial (see DialCompiler).
    // Kept on the layer rather than baked into `frames` because scaling the
    // layer resamples the pixels and undoes the palette, so the treatment has
    // to be re-applied at the size the block is actually written at.
    public int animColors = 0;  // shared palette size; 0 = leave at full colour
    public int animBinX   = 1;  // horizontal group width; 1 = leave untouched

    // The frames exactly as the compiler will write them — scaled, cropped to
    // the face and compressed. Held so the editor can preview the real thing:
    // reducing a palette to 24 colours is a visible change, and finding that
    // out only after installing the dial is too late. The key records the
    // transform and plan they were built for, so a stale set is not drawn.
    public Bitmap[] animCompiled;
    public String   animCompiledKey;

    // Analog hands: vertical rotation pivot in source-image px from the BOTTOM
    // (firmware ctx byte). 0 = use the editor default tail.
    public int pivotTail = 0;

    // Full composite bitmap for sprite-sheets loaded from file
    public Bitmap compositeImage;

    // Styling & Builder configs for style copying and re-editing
    public String fontPath;
    public int fontSize = 32;
    public int fontColor = android.graphics.Color.WHITE;
    public boolean fontBold = false;
    public boolean fontItalic = false;
    public int fontStrokeColor = android.graphics.Color.TRANSPARENT;
    public int fontStrokeWidth = 0;
    public int fontShadowColor = android.graphics.Color.TRANSPARENT;
    public int fontShadowRadius = 0;

    public FontStyleConfig fontConfig;
    public HandGenerator.HandConfig handConfig;
    public BatteryGenerator.BatteryConfig batteryConfig;
    public WeatherGenerator.WeatherConfig weatherConfig;
    public ProgressGenerator.ProgressConfig progressConfig;
    public ConnectionGenerator.ConnectConfig connectConfig;

    public DialLayer(int layerType, Bitmap icon, String name) {
        this.layerType = layerType;
        this.icon = icon;
        this.name = name;
        this.nativeElementType = DialCompiler.TYPE_BACKGROUND;
    }

    public DialLayer(int layerType, Bitmap icon, String name, int nativeElementType) {
        this.layerType = layerType;
        this.icon = icon;
        this.name = name;
        this.nativeElementType = nativeElementType;
        this.frameCount = DialCompiler.getDefaultFrameCount(nativeElementType);
        this.isSpriteSheet = frameCount > 1;
    }
}
