package com.example.dialsender.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.example.dialsender.R;
import com.example.dialsender.theme.ThemeManager;

public class GaugeView extends View {

    public static final String STYLE_A = "A"; // semi-circle, 180°
    public static final String STYLE_B = "B"; // donut, 270°
    public static final String STYLE_C = "C"; // thin ring, 270°

    private Paint trackPaint;
    private Paint arcPaint;
    private Paint textPaint;
    private Paint labelPaint;
    private Paint subPaint;

    private float value = 0f; // 0.0–1.0
    private String label = "";
    private String valueText = "";
    private String subText = "";
    private int arcColor = Color.parseColor("#22D3EE");
    private String gaugeStyle = STYLE_A;
    private float valueTextSizeMultiplier = 1.0f;

    private final RectF oval = new RectF();
    private static final float STROKE_WIDTH_NORMAL = 18f;
    private static final float STROKE_WIDTH_THIN = 8f;

    public GaugeView(Context context) {
        super(context);
        init(context, null);
    }

    public GaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public GaugeView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        ThemeManager.AppTheme initialTheme = ThemeManager.getTheme(context);
        arcColor = initialTheme.accentPrimary;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GaugeView);
            value = a.getFloat(R.styleable.GaugeView_gv_value, 0f);
            String lbl = a.getString(R.styleable.GaugeView_gv_label);
            label = lbl != null ? lbl : "";
            String vt = a.getString(R.styleable.GaugeView_gv_valueText);
            valueText = vt != null ? vt : "";
            String st = a.getString(R.styleable.GaugeView_gv_subText);
            subText = st != null ? st : "";
            arcColor = a.getColor(R.styleable.GaugeView_gv_color, arcColor);
            a.recycle();
        }

        // Track and text colours come from the active theme; they used to be
        // three fixed hex values, so the ring stayed navy-on-cyan whichever
        // theme was picked.
        ThemeManager.AppTheme theme = initialTheme;

        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(theme.gaugeTrack);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setColor(arcColor);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(theme.textPrimary);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(numeralTypeface(theme));

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(arcColor);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subPaint.setColor(theme.textMuted);
        subPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Numeral face for the active design language, matching FoggText.*.MetricValue. */
    private static Typeface numeralTypeface(ThemeManager.AppTheme theme) {
        switch (theme.design) {
            case ORGANIC:   return Typeface.create("sans-serif", Typeface.BOLD);
            case BOLD:      return Typeface.create("sans-serif-black", Typeface.NORMAL);
            case GLASS:     return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            case EDITORIAL: return Typeface.create("sans-serif-light", Typeface.NORMAL);
            case HUD:
            default:        return Typeface.create("sans-serif-condensed", Typeface.BOLD);
        }
    }

    public void setValue(float value) {
        this.value = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    public void setValueText(String text) {
        this.valueText = text != null ? text : "";
        invalidate();
    }

    public void setLabel(String text) {
        this.label = text != null ? text : "";
        invalidate();
    }

    public void setSubText(String text) {
        this.subText = text != null ? text : "";
        invalidate();
    }

    public void setArcColor(int color) {
        this.arcColor = color;
        arcPaint.setColor(color);
        labelPaint.setColor(color);
        invalidate();
    }

    public void setGaugeStyle(String style) {
        this.gaugeStyle = style != null ? style : STYLE_A;
        invalidate();
    }

    public void setValueTextSizeMultiplier(float multiplier) {
        this.valueTextSizeMultiplier = multiplier;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0)
            return;

        float stroke = STYLE_C.equals(gaugeStyle) ? STROKE_WIDTH_THIN : STROKE_WIDTH_NORMAL;
        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);

        float margin = stroke / 2f + 2f;
        float textAreaHeight = h * 0.35f;

        boolean isSemi = STYLE_A.equals(gaugeStyle);
        float startAngle = isSemi ? 180f : 135f;
        float sweepTotal = isSemi ? 180f : 270f;

        float arcDiameter;
        float left, top;
        if (isSemi) {
            arcDiameter = Math.min(w - 2 * margin, (h - textAreaHeight) * 2);
            left = (w - arcDiameter) / 2f;
            top = h - textAreaHeight - arcDiameter / 2f;
        } else {
            arcDiameter = Math.min(w - 2 * margin, h - 2 * margin);
            left = (w - arcDiameter) / 2f;
            top = (h - arcDiameter) / 2f;
        }

        oval.set(left + margin, top + margin, left + arcDiameter - margin, top + arcDiameter - margin);

        canvas.drawArc(oval, startAngle, sweepTotal, false, trackPaint);

        float sweep = sweepTotal * Math.max(0f, Math.min(1f, value));
        if (sweep > 0) {
            canvas.drawArc(oval, startAngle, sweep, false, arcPaint);
        }

        // Text is sized off the arc diameter for both styles. The semicircle
        // used to size off the view width, so on a wide card the value grew
        // large enough to push the label and goal line out of the bottom of
        // the view — that is what clipped "Pasos" under the step count.
        float valueSp = arcDiameter * 0.20f;
        float labelSp = arcDiameter * 0.085f;
        float subSp = arcDiameter * 0.07f;
        float textY;
        if (isSemi) {
            textY = top + arcDiameter / 2f + stroke;
        } else {
            textY = oval.centerY() - (w * 0.06f);
        }

        // Second guard: shrink the whole stack to whatever room is actually
        // left below textY, so no combination of card width and height clips it.
        float stackHeight = valueSp * 0.8f + labelSp * 1.2f + subSp * 1.3f;
        float available = h - textY - margin;
        if (stackHeight > available && available > 0) {
            float scale = available / stackHeight;
            valueSp *= scale;
            labelSp *= scale;
            subSp *= scale;
        }

        textPaint.setTextSize(valueSp);
        canvas.drawText(valueText, w / 2f, textY + valueSp * 0.8f, textPaint);

        labelPaint.setTextSize(labelSp);
        canvas.drawText(label, w / 2f, textY + valueSp * 0.8f + labelSp * 1.2f, labelPaint);

        subPaint.setTextSize(subSp);
        canvas.drawText(subText, w / 2f, textY + valueSp * 0.8f + labelSp * 1.2f + subSp * 1.3f, subPaint);
    }
}
