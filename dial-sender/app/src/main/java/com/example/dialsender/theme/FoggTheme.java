package com.example.dialsender.theme;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.StyleRes;
import androidx.appcompat.view.ContextThemeWrapper;

/**
 * Reads Fogg design tokens out of a themed {@link Context}.
 *
 * The tokens themselves live in res/values/themes_fogg.xml — this class is the
 * only bridge between them and the screens that build views in Java, so the XML
 * stays the single source of truth for both halves of the app.
 */
public final class FoggTheme {

    private FoggTheme() {
    }

    /** Context whose theme is {@code styleRes}, for resolving tokens off-screen. */
    public static Context wrap(Context base, @StyleRes int styleRes) {
        return new ContextThemeWrapper(base, styleRes);
    }

    @ColorInt
    public static int color(Context context, @AttrRes int attr) {
        return color(context, attr, 0);
    }

    @ColorInt
    public static int color(Context context, @AttrRes int attr, @ColorInt int fallback) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, fallback);
        } finally {
            a.recycle();
        }
    }

    /** Token dimension in pixels, rounded the way the layout inflater rounds it. */
    public static int dimen(Context context, @AttrRes int attr, int fallbackPx) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getDimensionPixelSize(0, fallbackPx);
        } finally {
            a.recycle();
        }
    }

    /** Token dimension in raw pixels — use when the value feeds a Paint or Canvas. */
    public static float dimenF(Context context, @AttrRes int attr, float fallbackPx) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getDimension(0, fallbackPx);
        } finally {
            a.recycle();
        }
    }

    public static int resId(Context context, @AttrRes int attr, int fallback) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getResourceId(0, fallback);
        } finally {
            a.recycle();
        }
    }

    public static int integer(Context context, @AttrRes int attr, int fallback) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getInt(0, fallback);
        } finally {
            a.recycle();
        }
    }

    /**
     * Drawable behind a token attribute. A fresh instance is returned each call
     * so callers can mutate it (tint, corner tweak) without corrupting the
     * cached constant state shared with every other view on screen.
     */
    public static Drawable drawable(Context context, @AttrRes int attr) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            Drawable d = a.getDrawable(0);
            return d != null ? d.mutate() : null;
        } finally {
            a.recycle();
        }
    }

    public static int dp(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics()));
    }

    /** Same colour at a different opacity, for glows, tracks and pressed states. */
    @ColorInt
    public static int alpha(@ColorInt int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
