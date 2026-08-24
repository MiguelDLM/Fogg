package com.example.dialsender;

import android.graphics.Color;

/**
 * Everything the digit font creator needs to rebuild a set of glyphs, so an
 * element made from a font can be re-opened and tweaked instead of deleted and
 * recreated from scratch.
 */
public class FontStyleConfig {
    /** Display name of the typeface as listed in the creator's spinner. */
    public String fontName;
    public int size        = 48;
    public int color       = Color.WHITE;
    public int borderColor = Color.BLACK;
    public int glow        = 0;
    public int border      = 0;
    /** Extra tracking, may be negative. */
    public int spacing     = 0;
    public String lang     = "en";
    public String customText = "";

    public FontStyleConfig copy() {
        FontStyleConfig c = new FontStyleConfig();
        c.fontName = fontName;
        c.size = size;
        c.color = color;
        c.borderColor = borderColor;
        c.glow = glow;
        c.border = border;
        c.spacing = spacing;
        c.lang = lang;
        c.customText = customText;
        return c;
    }
}
