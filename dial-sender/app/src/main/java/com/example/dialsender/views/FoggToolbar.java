package com.example.dialsender.views;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.example.dialsender.R;
import com.example.dialsender.theme.FoggTheme;
import com.example.dialsender.theme.ThemeManager;

/**
 * Wires up the shared header from layout/view_fogg_toolbar.xml.
 *
 * The Fogg themes are all NoActionBar, so screens that called
 * getSupportActionBar().setTitle(…) were silently getting no header. Including
 * the toolbar layout and calling {@link #attach} gives them a real one that
 * follows the active design language.
 */
public final class FoggToolbar {

    private FoggToolbar() {
    }

    /**
     * Builds the same header for screens that assemble their UI in Java rather
     * than inflating a layout. Several of those had hand-rolled headers using a
     * "‹" character as the back button; this gives them the real vector and the
     * theme's title face.
     *
     * @param actionIcon  trailing action drawable, or 0 for none
     */
    public static View build(Activity activity, CharSequence title,
                             @DrawableRes int actionIcon,
                             @Nullable View.OnClickListener actionClick) {
        Context ctx = activity;
        ThemeManager.AppTheme theme = ThemeManager.getTheme(ctx);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(FoggTheme.drawable(ctx, R.attr.foggToolbarBg));
        int side = FoggTheme.dp(ctx, 8);
        bar.setPadding(side, 0, FoggTheme.dp(ctx, 16), 0);
        bar.setMinimumHeight(FoggTheme.dp(ctx, 56));
        bar.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView back = new ImageView(ctx);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(theme.textPrimary);
        int pad = FoggTheme.dp(ctx, 9);
        back.setPadding(pad, pad, pad, pad);
        back.setContentDescription(activity.getString(R.string.fogg_back));
        back.setBackground(FoggTheme.drawable(ctx, android.R.attr.selectableItemBackgroundBorderless));
        back.setOnClickListener(v -> activity.onBackPressed());
        int size = FoggTheme.dp(ctx, 40);
        bar.addView(back, new LinearLayout.LayoutParams(size, size));

        TextView titleView = new TextView(ctx);
        titleView.setTextAppearance(theme.textScreenTitle);
        titleView.setTextColor(theme.textPrimary);
        titleView.setText(title);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(FoggTheme.dp(ctx, 4));
        bar.addView(titleView, titleLp);

        if (actionIcon != 0) {
            ImageView action = new ImageView(ctx);
            action.setImageResource(actionIcon);
            action.setColorFilter(theme.accentPrimary);
            action.setPadding(pad, pad, pad, pad);
            action.setBackground(
                    FoggTheme.drawable(ctx, android.R.attr.selectableItemBackgroundBorderless));
            action.setOnClickListener(actionClick);
            bar.addView(action, new LinearLayout.LayoutParams(size, size));
        }
        return bar;
    }

    public static View build(Activity activity, CharSequence title) {
        return build(activity, title, 0, null);
    }

    /** Title plus a back arrow that finishes the activity. */
    public static void attach(Activity activity, @StringRes int titleRes) {
        attach(activity, activity.getString(titleRes), 0, null);
    }

    public static void attach(Activity activity, CharSequence title) {
        attach(activity, title, 0, null);
    }

    /**
     * Title, back arrow, and one optional action on the trailing edge.
     *
     * @param actionIcon    drawable for the action, or 0 to hide it
     * @param actionClick   listener for the action; ignored when there is none
     */
    public static void attach(Activity activity, CharSequence title,
                              @DrawableRes int actionIcon,
                              @Nullable View.OnClickListener actionClick) {
        TextView titleView = activity.findViewById(R.id.foggToolbarTitle);
        if (titleView != null) {
            titleView.setText(title);
        }

        ImageView back = activity.findViewById(R.id.foggToolbarBack);
        if (back != null) {
            back.setOnClickListener(v -> activity.onBackPressed());
        }

        ImageView action = activity.findViewById(R.id.foggToolbarAction);
        if (action != null) {
            if (actionIcon != 0) {
                action.setImageResource(actionIcon);
                action.setVisibility(View.VISIBLE);
                action.setOnClickListener(actionClick);
            } else {
                action.setVisibility(View.GONE);
            }
        }
    }
}
