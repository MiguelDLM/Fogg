package com.example.dialsender.theme;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;

import androidx.annotation.ColorInt;
import androidx.annotation.StyleRes;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.dialsender.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Modular theme engine.
 *
 * A theme is assembled from two independent halves, mirroring the three-layer
 * structure in res/values/themes_fogg.xml:
 *
 *   • a {@link Palette} — the colours;
 *   • a {@link Design}  — the design language: shape scale, density scale,
 *                         type scale and component treatment.
 *
 * Both halves are declared in XML and read back here through {@link FoggTheme},
 * so the screens laid out in XML and the screens built in Java can never drift
 * apart. Nothing in this file hardcodes a colour or a dp value.
 */
public class ThemeManager {

    public static final String PREF_NAME = "dial_sender_prefs";
    public static final String KEY_THEME_ID = "app_theme_id";
    public static final String ACTION_THEME_CHANGED = "com.example.dialsender.THEME_CHANGED";

    public static final String THEME_MIDNIGHT = "midnight";
    public static final String THEME_EMERALD = "emerald";
    public static final String THEME_SOLAR = "solar";
    public static final String THEME_OCEAN = "ocean";
    public static final String THEME_ONYX = "onyx";

    /** Design languages. Several palettes may share one. */
    public enum Design {
        /** Compact, angular, condensed type, hairline neon frames. */
        HUD,
        /** Pill-round, airy, calm sentence-case type, no frames. */
        ORGANIC,
        /** Heavy weights, oversized numerals, thick accent frames. */
        BOLD,
        /** Translucent surfaces, light frames, medium-weight type. */
        GLASS,
        /** No cards: content on the page, grouped by full-bleed hairlines. */
        EDITORIAL;

        static Design fromAttr(int value) {
            Design[] all = values();
            return value >= 0 && value < all.length ? all[value] : HUD;
        }

        /** True when the family draws filled card surfaces at all. */
        public boolean hasCards() {
            return this != EDITORIAL;
        }

        /** True when list rows carry a tinted icon badge rather than a bare glyph. */
        public boolean hasIconBadges() {
            return this != EDITORIAL;
        }
    }

    /**
     * A resolved theme: palette + design language + the metadata the picker
     * needs. Field names are the ones the screens already use.
     */
    public static class AppTheme {
        public final String id;
        public final int nameRes;
        public final int descRes;
        @StyleRes
        public final int styleRes;

        public final Design design;

        // Surfaces
        @ColorInt public final int bgPrimary;
        @ColorInt public final int bgSurface;
        @ColorInt public final int bgCard;
        @ColorInt public final int bgCardGradientEnd;
        @ColorInt public final int bgElevated;

        // Accents
        @ColorInt public final int accentPrimary;
        @ColorInt public final int accentPrimaryDark;
        @ColorInt public final int accentSecondary;
        @ColorInt public final int accentGlow;
        @ColorInt public final int onAccent;

        // Per-metric accents
        @ColorInt public final int accentSteps;
        @ColorInt public final int accentHeart;
        @ColorInt public final int accentCalories;
        @ColorInt public final int accentSleep;
        @ColorInt public final int accentDistance;
        @ColorInt public final int accentSpo2;
        @ColorInt public final int accentStress;
        @ColorInt public final int accentBp;

        // Sleep stages
        @ColorInt public final int sleepDeep;
        @ColorInt public final int sleepLight;
        @ColorInt public final int sleepRem;
        @ColorInt public final int sleepAwake;

        // Text, lines, nav
        @ColorInt public final int textPrimary;
        @ColorInt public final int textSecondary;
        @ColorInt public final int textMuted;
        @ColorInt public final int divider;
        @ColorInt public final int cardBorder;
        @ColorInt public final int gaugeTrack;
        @ColorInt public final int navActive;
        @ColorInt public final int navInactive;

        // Semantic states
        @ColorInt public final int success;
        @ColorInt public final int warning;
        @ColorInt public final int danger;

        // Shape scale, in pixels
        public final int radiusCard;
        public final int radiusSmall;
        public final int radiusChip;
        public final int radiusButton;
        public final int radiusBadge;
        public final int stroke;

        // Density scale, in pixels
        public final int screenPadding;
        public final int cardPadding;
        public final int cardGap;
        public final int sectionGap;
        public final int rowPaddingV;
        public final int rowPaddingH;
        public final int iconSize;
        public final int badgeSize;

        // Type scale, as TextAppearance style resources
        @StyleRes public final int textScreenTitle;
        @StyleRes public final int textSectionHeader;
        @StyleRes public final int textCardTitle;
        @StyleRes public final int textMetricValue;
        @StyleRes public final int textMetricUnit;
        @StyleRes public final int textMetricInline;
        @StyleRes public final int textBody;
        @StyleRes public final int textRowLabel;
        @StyleRes public final int textRowValue;
        @StyleRes public final int textCaption;

        /** Swatches for the theme picker: accent, secondary, card, background. */
        public final int[] previewColors;

        AppTheme(Spec spec, Context themed) {
            this.id = spec.id;
            this.nameRes = spec.nameRes;
            this.descRes = spec.descRes;
            this.styleRes = spec.styleRes;

            this.design = Design.fromAttr(
                    FoggTheme.integer(themed, R.attr.foggDesignFamily, 0));

            this.bgPrimary = FoggTheme.color(themed, R.attr.foggBgPrimary);
            this.bgSurface = FoggTheme.color(themed, R.attr.foggBgSurface);
            this.bgCard = FoggTheme.color(themed, R.attr.foggBgCard);
            this.bgCardGradientEnd = FoggTheme.color(themed, R.attr.foggBgCardEnd);
            this.bgElevated = FoggTheme.color(themed, R.attr.foggBgElevated);

            this.accentPrimary = FoggTheme.color(themed, R.attr.foggAccent);
            this.accentPrimaryDark = FoggTheme.color(themed, R.attr.foggAccentDark);
            this.accentSecondary = FoggTheme.color(themed, R.attr.foggAccentSecondary);
            this.accentGlow = FoggTheme.color(themed, R.attr.foggAccentGlow);
            this.onAccent = FoggTheme.color(themed, R.attr.foggOnAccent);

            this.accentSteps = FoggTheme.color(themed, R.attr.foggSteps);
            this.accentHeart = FoggTheme.color(themed, R.attr.foggHeart);
            this.accentCalories = FoggTheme.color(themed, R.attr.foggCalories);
            this.accentSleep = FoggTheme.color(themed, R.attr.foggSleep);
            this.accentDistance = FoggTheme.color(themed, R.attr.foggDistance);
            this.accentSpo2 = FoggTheme.color(themed, R.attr.foggSpo2);
            this.accentStress = FoggTheme.color(themed, R.attr.foggStress);
            this.accentBp = FoggTheme.color(themed, R.attr.foggBp);

            this.sleepDeep = FoggTheme.color(themed, R.attr.foggSleepDeep);
            this.sleepLight = FoggTheme.color(themed, R.attr.foggSleepLight);
            this.sleepRem = FoggTheme.color(themed, R.attr.foggSleepRem);
            this.sleepAwake = FoggTheme.color(themed, R.attr.foggSleepAwake);

            this.textPrimary = FoggTheme.color(themed, R.attr.foggTextPrimary);
            this.textSecondary = FoggTheme.color(themed, R.attr.foggTextSecondary);
            this.textMuted = FoggTheme.color(themed, R.attr.foggTextMuted);
            this.divider = FoggTheme.color(themed, R.attr.foggDivider);
            this.cardBorder = FoggTheme.color(themed, R.attr.foggCardBorder);
            this.gaugeTrack = FoggTheme.color(themed, R.attr.foggGaugeTrack);
            this.navActive = FoggTheme.color(themed, R.attr.foggNavActive);
            this.navInactive = FoggTheme.color(themed, R.attr.foggNavInactive);

            this.success = FoggTheme.color(themed, R.attr.foggSuccess);
            this.warning = FoggTheme.color(themed, R.attr.foggWarning);
            this.danger = FoggTheme.color(themed, R.attr.foggDanger);

            int d = FoggTheme.dp(themed, 1);
            this.radiusCard = FoggTheme.dimen(themed, R.attr.foggRadiusCard, 16 * d);
            this.radiusSmall = FoggTheme.dimen(themed, R.attr.foggRadiusSmall, 10 * d);
            this.radiusChip = FoggTheme.dimen(themed, R.attr.foggRadiusChip, 14 * d);
            this.radiusButton = FoggTheme.dimen(themed, R.attr.foggRadiusButton, 14 * d);
            this.radiusBadge = FoggTheme.dimen(themed, R.attr.foggRadiusBadge, 12 * d);
            this.stroke = FoggTheme.dimen(themed, R.attr.foggStroke, d);

            this.screenPadding = FoggTheme.dimen(themed, R.attr.foggScreenPadding, 20 * d);
            this.cardPadding = FoggTheme.dimen(themed, R.attr.foggCardPadding, 18 * d);
            this.cardGap = FoggTheme.dimen(themed, R.attr.foggCardGap, 12 * d);
            this.sectionGap = FoggTheme.dimen(themed, R.attr.foggSectionGap, 24 * d);
            this.rowPaddingV = FoggTheme.dimen(themed, R.attr.foggRowPaddingV, 16 * d);
            this.rowPaddingH = FoggTheme.dimen(themed, R.attr.foggRowPaddingH, 16 * d);
            this.iconSize = FoggTheme.dimen(themed, R.attr.foggIconSize, 22 * d);
            this.badgeSize = FoggTheme.dimen(themed, R.attr.foggBadgeSize, 40 * d);

            this.textScreenTitle = FoggTheme.resId(themed, R.attr.foggTextScreenTitle, 0);
            this.textSectionHeader = FoggTheme.resId(themed, R.attr.foggTextSectionHeader, 0);
            this.textCardTitle = FoggTheme.resId(themed, R.attr.foggTextCardTitle, 0);
            this.textMetricValue = FoggTheme.resId(themed, R.attr.foggTextMetricValue, 0);
            this.textMetricUnit = FoggTheme.resId(themed, R.attr.foggTextMetricUnit, 0);
            this.textMetricInline = FoggTheme.resId(themed, R.attr.foggTextMetricInline, 0);
            this.textBody = FoggTheme.resId(themed, R.attr.foggTextBody, 0);
            this.textRowLabel = FoggTheme.resId(themed, R.attr.foggTextRowLabel, 0);
            this.textRowValue = FoggTheme.resId(themed, R.attr.foggTextRowValue, 0);
            this.textCaption = FoggTheme.resId(themed, R.attr.foggTextCaption, 0);

            this.previewColors = new int[]{
                    accentPrimary, accentSecondary,
                    design.hasCards() ? bgCard : bgElevated,
                    bgPrimary};
        }

        /** Accent this theme uses for a metric key, falling back to the primary. */
        @ColorInt
        public int accentFor(String metricKey) {
            if (metricKey == null) return accentPrimary;
            switch (metricKey) {
                case "steps":      return accentSteps;
                case "heart_rate": return accentHeart;
                case "calories":   return accentCalories;
                case "sleep":      return accentSleep;
                case "distance":   return accentDistance;
                case "spo2":       return accentSpo2;
                case "stress":     return accentStress;
                case "bp":         return accentBp;
                default:           return accentPrimary;
            }
        }
    }

    /** Immutable registry entry: which style resource and strings a theme uses. */
    private static class Spec {
        final String id;
        final int nameRes;
        final int descRes;
        @StyleRes final int styleRes;

        Spec(String id, int nameRes, int descRes, @StyleRes int styleRes) {
            this.id = id;
            this.nameRes = nameRes;
            this.descRes = descRes;
            this.styleRes = styleRes;
        }
    }

    private static final List<Spec> SPECS = new ArrayList<>();

    static {
        SPECS.add(new Spec(THEME_MIDNIGHT, R.string.theme_midnight_name,
                R.string.theme_midnight_desc, R.style.Theme_Fogg_Midnight));
        SPECS.add(new Spec(THEME_EMERALD, R.string.theme_emerald_name,
                R.string.theme_emerald_desc, R.style.Theme_Fogg_Emerald));
        SPECS.add(new Spec(THEME_SOLAR, R.string.theme_solar_name,
                R.string.theme_solar_desc, R.style.Theme_Fogg_Solar));
        SPECS.add(new Spec(THEME_OCEAN, R.string.theme_ocean_name,
                R.string.theme_ocean_desc, R.style.Theme_Fogg_Ocean));
        SPECS.add(new Spec(THEME_ONYX, R.string.theme_onyx_name,
                R.string.theme_onyx_desc, R.style.Theme_Fogg_Onyx));
    }

    /** Every theme, resolved against {@code context} for its preview swatches. */
    public static List<AppTheme> getAllThemes(Context context) {
        List<AppTheme> out = new ArrayList<>(SPECS.size());
        for (Spec spec : SPECS) {
            out.add(new AppTheme(spec, FoggTheme.wrap(context, spec.styleRes)));
        }
        return Collections.unmodifiableList(out);
    }

    public static String getThemeId(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_THEME_ID, THEME_MIDNIGHT);
    }

    private static Spec specFor(String id) {
        for (Spec spec : SPECS) {
            if (spec.id.equals(id)) {
                return spec;
            }
        }
        return SPECS.get(0);
    }

    @StyleRes
    public static int getStyleRes(Context context) {
        return specFor(getThemeId(context)).styleRes;
    }

    /**
     * The active theme. Tokens are read through a wrapper around the saved
     * style, so the result is correct even when {@code context} has not had
     * {@link #applyTheme} called on it yet.
     */
    public static AppTheme getTheme(Context context) {
        Spec spec = specFor(getThemeId(context));
        return new AppTheme(spec, FoggTheme.wrap(context, spec.styleRes));
    }

    public static void setTheme(Context context, String themeId) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_THEME_ID, themeId).apply();
        LocalBroadcastManager.getInstance(context)
                .sendBroadcast(new Intent(ACTION_THEME_CHANGED));
    }

    public static void applyTheme(Activity activity) {
        activity.setTheme(getStyleRes(activity));
    }

    // ═══════════════════ Drawable builders ═══════════════════
    // Used by the screens that build their views in Java. Each one honours the
    // active design language, so an Onyx card really is a hairline rule rather
    // than a rounded box in a different colour.

    /** Card surface for the active design language. */
    public static Drawable createCardDrawable(AppTheme theme) {
        if (!theme.design.hasCards()) {
            // Editorial draws no box. Grouping comes from a hairline under the
            // block, matching drawable/bg_fogg_card_editorial on the XML side —
            // without it the programmatic screens lost all separation.
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.RECTANGLE);
            fill.setColor(0x00000000);

            GradientDrawable rule = new GradientDrawable();
            rule.setShape(GradientDrawable.RECTANGLE);
            rule.setColor(0x00000000);
            rule.setStroke(1, theme.divider);

            LayerDrawable layers = new LayerDrawable(new Drawable[]{fill, rule});
            // Push the frame's other three edges outside the bounds so only the
            // bottom edge is painted.
            layers.setLayerInset(1, -2, -2, -2, 0);
            return layers;
        }
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{theme.bgCard, theme.bgCardGradientEnd});
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(theme.radiusCard);
        if (theme.stroke > 0) {
            gd.setStroke(theme.stroke, theme.cardBorder);
        }
        return gd;
    }

    /** Hairline used by Editorial in place of a card edge. */
    public static Drawable createRuleDrawable(AppTheme theme) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(theme.divider);
        return gd;
    }

    public static Drawable createChipDrawable(AppTheme theme, @ColorInt int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(theme.radiusChip);
        return gd;
    }

    /**
     * Squircle behind a metric or row icon. Editorial has no badges, so it gets
     * a fully transparent one and the glyph stands on its own.
     */
    public static Drawable createIconBadge(AppTheme theme, @ColorInt int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(theme.radiusBadge);
        if (!theme.design.hasIconBadges()) {
            gd.setColor(0x00000000);
            return gd;
        }
        gd.setColor(FoggTheme.alpha(color, 0x28));
        gd.setStroke(Math.max(theme.stroke, 1), FoggTheme.alpha(color, 0x44));
        return gd;
    }

    public static Drawable createPrimaryButtonDrawable(AppTheme theme) {
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{theme.accentPrimary, theme.accentPrimaryDark});
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(theme.radiusButton);
        return new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), normal, null);
    }

    /** Track/fill pair for a progress bar drawn in code. */
    public static Drawable createTrackDrawable(AppTheme theme) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(theme.gaugeTrack);
        gd.setCornerRadius(theme.radiusChip);
        return gd;
    }

    @ColorInt
    public static int withAlpha(@ColorInt int color, int alpha) {
        return FoggTheme.alpha(color, alpha);
    }
}
