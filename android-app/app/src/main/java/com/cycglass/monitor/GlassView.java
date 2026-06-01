package com.cycglass.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/**
 * Custom view that renders the cyc-glass layout: a single large
 * color-graded CURRENT band in the center with two perimeter rows above and
 * below it carrying the CYC motor telemetry and the two secondary battery
 * values (Voltage, Capacity remaining).
 *
 * <p>Layout:
 * <pre>
 *  +--------------------------------------------------------+
 *  |  Lev   SPD     V    MOT       (4 perimeter cells)      |
 *  |   3    18.4  53.3   74F                                  |
 *  +--------------------------------------------------------+
 *  |                                                         |
 *  |   CURRENT -3.2 A     (color-graded)                     |
 *  |                                                         |
 *  +--------------------------------------------------------+
 *  | CTRL  HumW  MotW  Cap       (4 perimeter cells)        |
 *  |  79F   84W  178W  18.6Ah                                |
 *  +--------------------------------------------------------+
 * </pre>
 *
 * <p>Perimeter font is {@code 2/3} of the band-value font (blu-battery's
 * band-value font is {@code width * 0.18f}, so this is {@code width * 0.12f}).
 */
public final class GlassView extends View {

    private static final int ZERO_CURRENT_COLOR = Color.rgb(10, 52, 48);
    private static final int MID_CURRENT_COLOR = Color.rgb(246, 190, 0);
    private static final int LIMIT_CURRENT_COLOR = Color.rgb(238, 26, 26);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DataModel model;

    // BMS display state (mirrors the DataModel at draw time).
    private double voltage = Double.NaN;
    private double current = Double.NaN;
    private double remaining = Double.NaN;
    private double currentValue;
    private float ampsOut;
    private float ampsIn;
    private String status = "Starting";

    public GlassView(Context context, DataModel model, float ampsOut, float ampsIn) {
        super(context);
        this.model = model;
        this.ampsOut = ampsOut;
        this.ampsIn = ampsIn;
        paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        setContentDescription(buildDescription());
    }

    public void setStatus(String value) {
        this.status = value;
        setContentDescription(buildDescription());
        postInvalidate();
    }

    public float getAmpsOut() { return ampsOut; }
    public float getAmpsIn() { return ampsIn; }

    public void setCurrentScale(float ampsOut, float ampsIn) {
        this.ampsOut = ampsOut;
        this.ampsIn = ampsIn;
        postInvalidate();
    }

    /**
     * Pulls a fresh snapshot from the data model and repaints. Cheap enough
     * to call from a {@code postDelayed} poll at the desired display rate.
     */
    public void refresh() {
        this.voltage = model.bmsVoltage();
        this.current = model.bmsCurrent();
        this.remaining = model.bmsRemaining();
        this.currentValue = current;
        setContentDescription(buildDescription());
        postInvalidate();
    }

    private String buildDescription() {
        return String.format(Locale.US,
                "Voltage %s. Current %s. Remaining %s. Status %s.",
                formatValue(voltage, "%.1f V"),
                formatValue(current, "%.1f A"),
                formatValue(remaining, "%.1f Ah"),
                status);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        float bandFontPx = Math.max(48.0f, width * 0.18f);
        // Perimeter value font: doubled from 0.085 to 0.17 of the width.
        // Note: at this size on a phone, the unit-suffixed values like
        // "178W" and "18.6Ah" will visually overflow into the adjacent
        // cell. The label font is left at 0.040 so the visual hierarchy
        // (large value, small label) still reads.
        float perimeterFontPx = Math.max(48.0f, width * 0.17f);
        float perimeterLabelPx = Math.max(16.0f, width * 0.040f);
        float statusFontPx = Math.max(18.0f, width * 0.035f);
        float statusGutterPx = statusFontPx + dp(12);

        float perimeterRowHeight = perimeterFontPx + perimeterLabelPx + dp(8);
        float bandTop = perimeterRowHeight;
        float bandBottom = height - statusGutterPx - perimeterRowHeight;
        float mainBandHeight = bandBottom - bandTop;
        drawPerimeterRow(canvas, 0, perimeterRowHeight, width, perimeterLabelPx, perimeterFontPx, true);
        drawMainBand(canvas, bandTop, mainBandHeight, width, bandFontPx);
        drawPerimeterRow(canvas, bandBottom, perimeterRowHeight, width, perimeterLabelPx, perimeterFontPx, false);

        // Status line in the gutter below the bottom row.
        paint.setTextSize(statusFontPx);
        paint.setColor(Color.LTGRAY);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(status, width / 2.0f, height - dp(4), paint);
    }

    private void drawMainBand(Canvas canvas, float top, float bandHeight, int width, float fontPx) {
        paint.setColor(currentColor());
        canvas.drawRect(0, top, width, top + bandHeight, paint);
        // Simple middle section: just the number and unit, vertically
        // centered. No label above.
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        paint.setTextSize(fontPx);
        // Vertical center adjustment: text baseline is below the geometric
        // center by roughly fontPx/3.
        canvas.drawText(formatValue(current, "%.1f A"),
                width / 2.0f, top + bandHeight / 2.0f + fontPx / 3.0f, paint);
    }

    private void drawPerimeterRow(Canvas canvas, float rowTop, float rowHeight, int width,
                                  float labelPx, float valuePx, boolean isTop) {
        paint.setColor(Color.rgb(18, 18, 22));
        canvas.drawRect(0, rowTop, width, rowTop + rowHeight, paint);

        // Four cells: get motor/battery values from the model. Units live
        // in the labels so the value cells stay numeric and don't fight
        // the cell width at the doubled (0.17) value font.
        String[] labels;
        String[] values;
        if (isTop) {
            labels = new String[] { "Lev", "SPDmph", "V", "MOT\u00b0F" };
            values = new String[] {
                    formatInt(model.assistLevel()),
                    formatValue(model.speedMph(), "%.1f"),
                    formatValue(model.bmsVoltage(), "%.1f"),
                    formatValue(model.motorTempF(), "%.0f")
            };
        } else {
            labels = new String[] { "CTRL\u00b0F", "HumW", "MotW", "CapAh" };
            values = new String[] {
                    formatValue(model.controllerTempF(), "%.0f"),
                    formatValue(model.humanPowerW(), "%.0f"),
                    formatValue(model.motorPowerW(), "%.0f"),
                    formatValue(model.bmsRemaining(), "%.1f")
            };
        }

        float cellWidth = width / 4.0f;
        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 4; i++) {
            float cx = cellWidth * (i + 0.5f);
            paint.setColor(Color.LTGRAY);
            paint.setTextSize(labelPx);
            if (isTop) {
                canvas.drawText(labels[i], cx, rowTop + labelPx + dp(2), paint);
                paint.setColor(Color.WHITE);
                paint.setTextSize(valuePx);
                canvas.drawText(values[i], cx, rowTop + labelPx + valuePx + dp(2), paint);
            } else {
                // Bottom row: draw label and value stacked, value just above
                // the label, so the band hugs the top of the row.
                paint.setColor(Color.WHITE);
                paint.setTextSize(valuePx);
                canvas.drawText(values[i], cx, rowTop + valuePx + dp(2), paint);
                paint.setColor(Color.LTGRAY);
                paint.setTextSize(labelPx);
                canvas.drawText(labels[i], cx, rowTop + valuePx + labelPx + dp(6), paint);
            }
        }
    }

    private int currentColor() {
        double limit = currentValue >= 0.0 ? ampsIn : ampsOut;
        float fraction = (float) Math.min(1.0, Math.abs(currentValue) / limit);
        if (fraction <= 0.5f) {
            return interpolateColor(ZERO_CURRENT_COLOR, MID_CURRENT_COLOR, fraction * 2.0f);
        }
        return interpolateColor(MID_CURRENT_COLOR, LIMIT_CURRENT_COLOR, (fraction - 0.5f) * 2.0f);
    }

    private static int interpolateColor(int start, int end, float fraction) {
        return Color.rgb(
                blend(Color.red(start), Color.red(end), fraction),
                blend(Color.green(start), Color.green(end), fraction),
                blend(Color.blue(start), Color.blue(end), fraction));
    }

    private static int blend(int start, int end, float fraction) {
        return Math.round(start + (end - start) * fraction);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatValue(double v, String fmt) {
        if (Double.isNaN(v)) return "—";
        return String.format(Locale.US, fmt, v);
    }

    private static String formatInt(int v) {
        if (v == Integer.MIN_VALUE) return "—";
        return Integer.toString(v);
    }
}
