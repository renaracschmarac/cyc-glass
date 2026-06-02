package com.cycglass.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
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

    // GPS speed sign state, mirrored from DataModel. NaN means
    // "no speed known" and the sign shows the em-dash placeholder.
    private double gpsSpeedMph = Double.NaN;

    // Visual constants for the speed sign and center arrow.
    // Material Blue 700.
    private static final int CENTER_ARROW_BLUE = 0xFF1976D2;

    // Layout metrics recomputed in onSizeChanged (and again in onDraw
    // for safety) and exposed for overlay positioning. See
    // getBandTopPx / getBandBottomPx.
    private int bandTopPx;
    private int bandBottomPx;
    private float perimeterFontPx;
    private float perimeterLabelPx;
    private float statusFontPx;
    private float perimeterRowHeight;

    // Settings gear icon. The icon is drawn directly in onDraw (the
    // FrameLayout / ImageButton path failed to render the icon content
    // on this device — see the 2026-06-01 memory note). Tap detection
    // is also handled here, via a hit-test against the icon's rect.
    private final Paint gearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gearHolePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF settingsIconRect = new RectF();
    // Speed sign (US MUTCD rectangle below the gear) and center
    // arrow (chevron + dot at the screen center). Layout is
    // recomputed in recomputeBandMetrics.
    private final RectF speedSignRect = new RectF();
    private final Path centerArrowPath = new Path();
    private Runnable onSettingsTapListener;

    public GlassView(Context context, DataModel model, float ampsOut, float ampsIn) {
        super(context);
        this.model = model;
        this.ampsOut = ampsOut;
        this.ampsIn = ampsIn;
        paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        // Settings gear icon paints.
        gearPaint.setColor(Color.WHITE);
        gearPaint.setStyle(Paint.Style.FILL);
        gearHolePaint.setColor(Color.BLACK);
        gearHolePaint.setStyle(Paint.Style.FILL);
        setContentDescription(buildDescription());
    }

    /**
     * Sets the listener invoked when the user taps the settings gear
     * icon drawn in the top-right of the main band. Pass {@code null}
     * to clear.
     */
    public void setOnSettingsTapListener(Runnable listener) {
        this.onSettingsTapListener = listener;
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
     * Test-only hook for {@link CurrentBandTest} to drive
     * {@link #currentColor()} with a known {@code currentValue} without
     * having to rely on field reflection (which has been unreliable in
     * the local unit test environment with mocked double stores).
     */
    void setCurrentValueForTest(double value) {
        this.currentValue = value;
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
        this.gpsSpeedMph = model.gpsSpeedMph();
        setContentDescription(buildDescription());
        postInvalidate();
    }

    private String buildDescription() {
        return String.format(Locale.US,
                "Voltage %s. Current %s. Remaining %s. Speed %s mph. Status %s.",
                formatValue(voltage, "%.1f V"),
                formatValue(current, "%.1f A"),
                formatValue(remaining, "%.1f Ah"),
                Double.isNaN(gpsSpeedMph) ? "\u2014" : String.format("%.0f", gpsSpeedMph),
                status);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeBandMetrics(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        // Recompute the band metrics every draw in case the view was
        // resized (rotation, window-inset change, etc.) without an
        // onSizeChanged callback firing. Cheap; just a few floats.
        recomputeBandMetrics(width, height);
        float bandTop = bandTopPx;
        float bandBottom = bandBottomPx - perimeterRowHeight;
        float mainBandHeight = bandBottom - bandTop;

        float bandFontPx = Math.max(48.0f, width * 0.18f);
        drawPerimeterRow(canvas, 0, perimeterRowHeight, width, perimeterLabelPx, perimeterFontPx, true);
        drawMainBand(canvas, bandTop, mainBandHeight, width, bandFontPx);
        drawPerimeterRow(canvas, bandBottom, perimeterRowHeight, width, perimeterLabelPx, perimeterFontPx, false);
        drawSettingsIcon(canvas, width);
        drawSpeedSign(canvas, width);
        drawCenterArrow(canvas, width, height);

        // Status line in the gutter below the bottom row.
        paint.setTextSize(statusFontPx);
        paint.setColor(Color.LTGRAY);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(status, width / 2.0f, height - dp(4), paint);
    }

    private void recomputeBandMetrics(int width, int height) {
        if (width == 0 || height == 0) return;
        float bandFontPx = Math.max(48.0f, width * 0.18f);
        float perimeterFontPx = Math.max(48.0f, width * 0.17f);
        float perimeterLabelPx = Math.max(16.0f, width * 0.040f);
        float statusFontPx = Math.max(18.0f, width * 0.035f);
        float statusGutterPx = statusFontPx + dp(12);

        this.perimeterFontPx = perimeterFontPx;
        this.perimeterLabelPx = perimeterLabelPx;
        this.statusFontPx = statusFontPx;
        this.perimeterRowHeight = perimeterFontPx + perimeterLabelPx + dp(8);
        this.bandTopPx = (int) this.perimeterRowHeight;
        this.bandBottomPx = (int) (height - statusGutterPx);

        // Settings gear icon: tucked into the top-right corner of the
        // main band, just below the top perimeter row. Tap target is
        // 64dp wide (Android accessibility floor); visual gear is 48dp.
        float tapRadius = dp(32);
        float rightMargin = dp(20);
        float topMargin = dp(12);
        float cx = width - rightMargin - tapRadius;
        float cy = this.bandTopPx + topMargin + tapRadius;
        settingsIconRect.set(cx - tapRadius, cy - tapRadius, cx + tapRadius, cy + tapRadius);

        // Speed sign: 80 dp tall × 64 dp wide, centered on the
        // gear's X, 16 dp below the gear's visual bottom edge. The
        // US MUTCD rectangle is taller than wide.
        float signWidth = dp(64);
        float signHeight = dp(80);
        float signCx = cx;  // same column as the gear center
        float signTop = this.bandTopPx + dp(84);
        speedSignRect.set(signCx - signWidth / 2f, signTop,
                signCx + signWidth / 2f, signTop + signHeight);

        // Center arrow: chevron tip at (width/2, height/2 - 30dp),
        // base at (width/2 ± 16dp, height/2 - 6dp). The dot sits at
        // the exact screen center, just below the chevron's base.
        // The path is built fresh every layout pass because the
        // arrow is small and the cost is negligible.
        float halfWidth = dp(16);
        float tipY = height / 2f - dp(30);
        float baseY = height / 2f - dp(6);
        centerArrowPath.reset();
        centerArrowPath.moveTo(width / 2f, tipY);
        centerArrowPath.lineTo(width / 2f + halfWidth, baseY);
        centerArrowPath.lineTo(width / 2f - halfWidth, baseY);
        centerArrowPath.close();
    }

    /** Y pixel where the main current band starts (bottom of the top
     * perimeter row). Exposed so overlay widgets (settings gear, etc.)
     * can position themselves relative to the actual band top instead
     * of guessing a hardcoded value. */
    public int getBandTopPx() {
        return bandTopPx;
    }

    /** Y pixel where the main current band ends (top of the bottom
     * perimeter row). */
    public int getBandBottomPx() {
        return bandBottomPx;
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
        // center by roughly fontPx/3. Shifted up 340 px (raw pixels, not
        // dp) so the number clears the blue center arrow — the arrow
        // sits at the exact screen center and would otherwise overlap
        // the digits. The band itself and the perimeter rows are
        // unchanged; only this text's Y moves.
        canvas.drawText(formatValue(current, "%.1f A"),
                width / 2.0f, top + bandHeight / 2.0f + fontPx / 3.0f - 340, paint);
    }

    private void drawPerimeterRow(Canvas canvas, float rowTop, float rowHeight, int width,
                                  float labelPx, float valuePx, boolean isTop) {
        paint.setColor(Color.rgb(18, 18, 22));
        canvas.drawRect(0, rowTop, width, rowTop + rowHeight, paint);

        // Four cells: get motor/battery values from the model. Units live
        // in the labels so the value cells stay numeric. All value strings
        // use at most one decimal place ("%.0f" or "%.1f") so the longest
        // expected value is 4 characters (e.g. "55.5", "2000", "18.6"),
        // which gives auto-fit room to land somewhere reasonable per cell.
        String[] labels;
        String[] values;
        if (isTop) {
            labels = new String[] { "Lev", "mph", "V", "MOT\u00b0F" };
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
        float maxValueWidth = cellWidth * 0.95f;  // 5% combined side padding
        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 4; i++) {
            float cx = cellWidth * (i + 0.5f);
            // Auto-fit: shrink the value font for this cell until the text
            // fits within maxValueWidth. The label keeps the shared labelPx
            // because labels are short and consistent across the row.
            float fittedValuePx = fitFontSize(values[i], valuePx, maxValueWidth, paint);
            if (isTop) {
                paint.setColor(Color.LTGRAY);
                paint.setTextSize(labelPx);
                canvas.drawText(labels[i], cx, rowTop + labelPx + dp(2), paint);
                paint.setColor(Color.WHITE);
                paint.setTextSize(fittedValuePx);
                canvas.drawText(values[i], cx, rowTop + labelPx + fittedValuePx + dp(2), paint);
            } else {
                // Bottom row: value above, label below.
                paint.setColor(Color.WHITE);
                paint.setTextSize(fittedValuePx);
                canvas.drawText(values[i], cx, rowTop + fittedValuePx + dp(2), paint);
                paint.setColor(Color.LTGRAY);
                paint.setTextSize(labelPx);
                canvas.drawText(labels[i], cx, rowTop + fittedValuePx + labelPx + dp(6), paint);
            }
        }
    }

    /**
     * Picks the largest font size, in pixels, at which {@code text} fits
     * within {@code maxWidth} on the given {@code paint}. Starts from
     * {@code startSize} and multiplies by 0.9 until the text fits or the
     * size drops below 8 px (the floor below which a value becomes
     * unreadable; in practice the loop converges well before that).
     *
     * <p>Side effect: the caller's {@code paint} is left with the chosen
     * {@code TextSize} set, so callers can use {@code paint.measureText()}
     * or call {@code canvas.drawText(..., paint)} immediately afterwards.
     */
    static float fitFontSize(String text, float startSize, float maxWidth, Paint paint) {
        float size = startSize;
        paint.setTextSize(size);
        while (paint.measureText(text) > maxWidth && size > 8.0f) {
            size *= 0.9f;
            paint.setTextSize(size);
        }
        return size;
    }

    private int currentColor() {
        double limit = currentValue >= 0.0 ? ampsIn : ampsOut;
        float fraction = (float) Math.min(1.0, Math.abs(currentValue) / limit);
        int baseColor;
        if (fraction <= 0.5f) {
            baseColor = interpolateColor(ZERO_CURRENT_COLOR, MID_CURRENT_COLOR, fraction * 2.0f);
        } else {
            baseColor = interpolateColor(MID_CURRENT_COLOR, LIMIT_CURRENT_COLOR, (fraction - 0.5f) * 2.0f);
        }
        // Alpha scales with the same fraction: 0 A is fully transparent (so a
        // map background beneath shows through), the configured current
        // limit is fully opaque red. The map overlay is the planned use
        // case — the band should be subtle at idle and pop on hard draw.
        int alpha = Math.round(255f * fraction);
        return (alpha << 24) | (baseColor & 0x00FFFFFF);
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

    /**
     * Renders the white settings gear at the location computed by
     * {@link #recomputeBandMetrics}. The icon is drawn here, in the
     * view's own onDraw, rather than as a sibling View in the parent
     * FrameLayout — see the 2026-06-01 memory note for why the
     * sibling-View path was unreliable on this device.
     */
    private void drawSettingsIcon(Canvas canvas, int width) {
        float visualRadius = dp(24);
        float cx = (settingsIconRect.left + settingsIconRect.right) / 2f;
        float cy = (settingsIconRect.top + settingsIconRect.bottom) / 2f;
        Path gear = buildGearPath(cx, cy, visualRadius, visualRadius * 0.75f, 8);
        canvas.drawPath(gear, gearPaint);
        // Center hole in the band color so the gear reads as a gear
        // and not a solid disc.
        gearHolePaint.setColor(currentColor());
        canvas.drawCircle(cx, cy, visualRadius * 0.35f, gearHolePaint);
    }

    /**
     * Renders the US MUTCD-style speed sign directly below the
     * settings gear. White interior, 3 dp black border, integer
     * mph in sans-serif bold black, no unit label. The numeral
     * auto-fits the rectangle width — a 3-digit value (100+) is
     * still readable at 64 dp wide.
     */
    private void drawSpeedSign(Canvas canvas, int width) {
        // White interior. We use the shared `paint` for fill /
        // stroke and reset its state when done.
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(speedSignRect, paint);

        // 3 dp black border.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(Color.BLACK);
        canvas.drawRect(speedSignRect, paint);
        paint.setStyle(Paint.Style.FILL);

        // Speed text. Integer mph, no unit label, em-dash when no
        // GPS fix has reported speed yet.
        String text = Double.isNaN(gpsSpeedMph)
                ? "\u2014"
                : Integer.toString((int) Math.round(gpsSpeedMph));
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.CENTER);
        // Start at ~48 dp; auto-fit down if the text overflows.
        float startFontPx = Math.max(36.0f, dp(48));
        float fitted = fitFontSize(text, startFontPx,
                speedSignRect.width() * 0.85f, paint);
        paint.setTextSize(fitted);
        float cx = (speedSignRect.left + speedSignRect.right) / 2f;
        float cy = (speedSignRect.top + speedSignRect.bottom) / 2f;
        // Vertical center adjustment: text baseline is below the
        // geometric center by roughly fontPx/3.
        canvas.drawText(text, cx, cy + fitted / 3f, paint);
    }

    /**
     * Renders the center arrow: a blue chevron pointing straight
     * up (the user's "front" direction) with a solid blue dot at
     * the exact screen center marking the GPS position. The arrow
     * is in screen space and never rotates with the map.
     */
    private void drawCenterArrow(Canvas canvas, int width, int height) {
        // Filled chevron.
        paint.setColor(CENTER_ARROW_BLUE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(centerArrowPath, paint);

        // Thin white outline so the arrow reads on light map
        // tiles. The outline is drawn over the fill.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.WHITE);
        canvas.drawPath(centerArrowPath, paint);
        paint.setStyle(Paint.Style.FILL);

        // Center dot (also blue, drawn over the outline so the
        // arrow reads as a single visual unit).
        paint.setColor(CENTER_ARROW_BLUE);
        canvas.drawCircle(width / 2f, height / 2f, dp(6), paint);
    }

    /**
     * Builds an N-toothed gear {@link Path} centered at {@code (cx, cy)}
     * with outer radius {@code outerR} and inner (gap) radius
     * {@code innerR}. Each tooth spans half of its allotted angular
     * step, with the gap filling the other half.
     */
    private static Path buildGearPath(float cx, float cy, float outerR, float innerR, int teeth) {
        Path path = new Path();
        float stepAngle = (float) (Math.PI * 2.0 / teeth);
        float halfTooth = stepAngle * 0.25f;  // tooth occupies half the step
        for (int i = 0; i < teeth; i++) {
            float center = i * stepAngle;
            float a1 = center - halfTooth;   // outer-left of this tooth
            float a2 = center + halfTooth;   // outer-right of this tooth
            float a3 = center + stepAngle - halfTooth;  // start of next outer (end of this gap)
            float x1 = cx + outerR * (float) Math.cos(a1);
            float y1 = cy + outerR * (float) Math.sin(a1);
            float x2 = cx + outerR * (float) Math.cos(a2);
            float y2 = cy + outerR * (float) Math.sin(a2);
            float x3 = cx + innerR * (float) Math.cos(a2);
            float y3 = cy + innerR * (float) Math.sin(a2);
            float x4 = cx + innerR * (float) Math.cos(a3);
            float y4 = cy + innerR * (float) Math.sin(a3);
            if (i == 0) {
                path.moveTo(x1, y1);
            } else {
                path.lineTo(x1, y1);
            }
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            path.lineTo(x4, y4);
        }
        path.close();
        return path;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Claim the gesture on ACTION_DOWN if the touch lands on the
        // settings icon, otherwise return false so the framework can
        // route the event to other potential consumers. Without the
        // ACTION_DOWN claim, the framework stops delivering
        // subsequent events (including ACTION_UP) to this view, so
        // the tap would never fire.
        boolean inIcon = settingsIconRect.contains(event.getX(), event.getY());
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                return inIcon;
            case MotionEvent.ACTION_UP:
                if (inIcon && onSettingsTapListener != null) {
                    onSettingsTapListener.run();
                }
                return inIcon;
            default:
                return super.onTouchEvent(event);
        }
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
