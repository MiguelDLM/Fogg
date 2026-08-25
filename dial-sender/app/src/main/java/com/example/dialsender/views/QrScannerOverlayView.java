package com.example.dialsender.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import com.example.dialsender.theme.ThemeManager;

/**
 * Modern HUD-styled viewfinder overlay for scanning smartwatch QR codes.
 * Draws a dark semi-transparent mask around a centered square target area,
 * neon corner brackets, and a scanning laser line animation.
 */
public class QrScannerOverlayView extends View {

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint laserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF frameRect = new RectF();
    private float laserPosition = 0f; // 0.0 to 1.0
    private ValueAnimator laserAnimator;

    private int accentColor;
    private float cornerLength;
    private float cornerRadius;

    public QrScannerOverlayView(Context context) {
        super(context);
        init(context);
    }

    public QrScannerOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public QrScannerOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setLayerType(LAYER_TYPE_HARDWARE, null);

        ThemeManager.AppTheme theme = ThemeManager.getTheme(context);
        accentColor = theme != null ? theme.accentPrimary : Color.parseColor("#00E5FF");

        maskPaint.setColor(Color.parseColor("#99000000")); // 60% black mask
        maskPaint.setStyle(Paint.Style.FILL);

        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        cornerPaint.setColor(accentColor);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(dp(4));
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);

        borderPaint.setColor(accentColor);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setAlpha(60);

        laserPaint.setStyle(Paint.Style.STROKE);
        laserPaint.setStrokeWidth(dp(2.5f));
        laserPaint.setStrokeCap(Paint.Cap.ROUND);

        cornerLength = dp(24);
        cornerRadius = dp(16);

        startLaserAnimation();
    }

    private void startLaserAnimation() {
        laserAnimator = ValueAnimator.ofFloat(0f, 1f);
        laserAnimator.setDuration(2200);
        laserAnimator.setRepeatCount(ValueAnimator.INFINITE);
        laserAnimator.setRepeatMode(ValueAnimator.REVERSE);
        laserAnimator.setInterpolator(new LinearInterpolator());
        laserAnimator.addUpdateListener(anim -> {
            laserPosition = (float) anim.getAnimatedValue();
            invalidate();
        });
        laserAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (laserAnimator != null) {
            laserAnimator.cancel();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h) * 0.70f;
        float left = (w - size) / 2f;
        float top = (h - size) / 2.4f; // slightly above vertical center
        frameRect.set(left, top, left + size, top + size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Draw mask over everything
        canvas.drawRect(0, 0, getWidth(), getHeight(), maskPaint);

        // 2. Clear out the viewfinder window with rounded corners
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, clearPaint);

        // 3. Draw thin bounding frame
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, borderPaint);

        // 4. Draw HUD neon corner brackets
        float l = frameRect.left;
        float t = frameRect.top;
        float r = frameRect.right;
        float b = frameRect.bottom;
        float cl = cornerLength;

        // Top-Left
        canvas.drawLine(l, t + cl, l, t + cornerRadius, cornerPaint);
        canvas.drawArc(l, t, l + cornerRadius * 2, t + cornerRadius * 2, 180, 90, false, cornerPaint);
        canvas.drawLine(l + cornerRadius, t, l + cl, t, cornerPaint);

        // Top-Right
        canvas.drawLine(r - cl, t, r - cornerRadius, t, cornerPaint);
        canvas.drawArc(r - cornerRadius * 2, t, r, t + cornerRadius * 2, 270, 90, false, cornerPaint);
        canvas.drawLine(r, t + cornerRadius, r, t + cl, cornerPaint);

        // Bottom-Left
        canvas.drawLine(l, b - cl, l, b - cornerRadius, cornerPaint);
        canvas.drawArc(l, b - cornerRadius * 2, l + cornerRadius * 2, b, 90, 90, false, cornerPaint);
        canvas.drawLine(l + cornerRadius, b, l + cl, b, cornerPaint);

        // Bottom-Right
        canvas.drawLine(r - cl, b, r - cornerRadius, b, cornerPaint);
        canvas.drawArc(r - cornerRadius * 2, b - cornerRadius * 2, r, b, 0, 90, false, cornerPaint);
        canvas.drawLine(r, b - cornerRadius, r, b - cl, cornerPaint);

        // 5. Draw animated laser line
        float laserY = t + (b - t) * laserPosition;
        float laserInset = dp(8);
        LinearGradient gradient = new LinearGradient(
                l + laserInset, laserY, r - laserInset, laserY,
                new int[]{Color.TRANSPARENT, accentColor, Color.TRANSPARENT},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        );
        laserPaint.setShader(gradient);
        canvas.drawLine(l + laserInset, laserY, r - laserInset, laserY, laserPaint);
    }

    public RectF getFrameRect() {
        return frameRect;
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
