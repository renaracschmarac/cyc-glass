package com.cycglass.monitor;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * A custom two-color slide switch. The track is split into a red
 * left half (Metric / "commie") and a green right half (Imperial /
 * "Correct"). A white circular thumb slides between the two halves
 * to indicate the active selection.
 *
 * <p>This is a one-off View for the cyc-glass settings dialog —
 * it replaces the stock {@code RadioGroup} we used to have, with
 * a control that visually reads as a traffic-light toggle.
 *
 * <p>State mapping:
 * <ul>
 *   <li>{@code isChecked() == false}: thumb on the red (left)
 *       side, value is {@link UnitSystem#METRIC}.</li>
 *   <li>{@code isChecked() == true}: thumb on the green (right)
 *       side, value is {@link UnitSystem#IMPERIAL}.</li>
 * </ul>
 *
 * <p>Interaction:
 * <ul>
 *   <li>Tap on the left half → set to unchecked (Metric).
 *   <li>Tap on the right half → set to checked (Imperial).
 *   <li>Drag the thumb → follows the finger; on release, snaps
 *       to the nearest side and fires the listener.
 * </ul>
 *
 * <p>The thumb animates between positions on state change (150 ms
 * slide). On first layout, the thumb appears at the current
 * state's position with no animation.
 */
public final class UnitSwitch extends View {

    /** Callback fired after a state change (programmatic or
     *  user-initiated). */
    public interface OnCheckedChangeListener {
        void onCheckedChanged(UnitSwitch view, boolean isChecked);
    }

    // Material Red 600 — traffic-light red.
    private static final int COLOR_RED = 0xFFE53935;
    // Material Green 600 — traffic-light green.
    private static final int COLOR_GREEN = 0xFF43A047;
    // White thumb. High contrast on both halves.
    private static final int COLOR_THUMB = Color.WHITE;
    // Slide animation duration in milliseconds.
    private static final long ANIM_DURATION_MS = 150L;

    private boolean checked = false;
    private OnCheckedChangeListener listener;

    private final Paint redPaint;
    private final Paint greenPaint;
    private final Paint thumbPaint;
    private final Path trackPath = new Path();
    private final RectF trackRect = new RectF();

    /** Current thumb center X. Updated by animation or touch. */
    private float thumbX;

    public UnitSwitch(Context context) {
        super(context);
        redPaint = fillPaint(COLOR_RED);
        greenPaint = fillPaint(COLOR_GREEN);
        thumbPaint = fillPaint(COLOR_THUMB);
        // Required for the view to receive touch events
        // consistently — without this, parent containers
        // can swallow ACTION_DOWN before the gesture
        // reaches us.
        setClickable(true);
        setFocusable(true);
    }

    public UnitSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        redPaint = fillPaint(COLOR_RED);
        greenPaint = fillPaint(COLOR_GREEN);
        thumbPaint = fillPaint(COLOR_THUMB);
        setClickable(true);
        setFocusable(true);
    }

    private static Paint fillPaint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int desiredWidth = dp(200);
        int desiredHeight = dp(40);

        int width = (widthMode == MeasureSpec.EXACTLY) ? widthSize
                : Math.min(desiredWidth, widthSize);
        int height = (heightMode == MeasureSpec.EXACTLY) ? heightSize
                : Math.min(desiredHeight, heightSize);

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        trackRect.set(0, 0, w, h);
        // Snap the thumb to the active half on first layout
        // (no animation — we want it to be in place on first
        // paint, not slide in from the wrong side).
        thumbX = thumbCenterX(checked);
    }

    /**
     * Computes the thumb's center X for a given target state. The
     * thumb sits at the geometric center of whichever half of the
     * track the state maps to.
     */
    private float thumbCenterX(boolean targetChecked) {
        float halfWidth = trackRect.width() / 2f;
        return targetChecked ? halfWidth * 1.5f : halfWidth * 0.5f;
    }

    /** Current state. {@code true} = Imperial (right, green). */
    public boolean isChecked() {
        return checked;
    }

    /**
     * Sets the checked state and animates the thumb to the new
     * position. The {@link OnCheckedChangeListener} is invoked
     * after the state changes.
     */
    public void setChecked(boolean value) {
        if (this.checked == value) return;
        this.checked = value;
        if (trackRect.width() > 0) {
            animateThumbTo(thumbCenterX(value));
        } else {
            // Pre-layout: just remember the position for the
            // first draw.
            thumbX = thumbCenterX(value);
            invalidate();
        }
        if (listener != null) listener.onCheckedChanged(this, value);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    private void animateThumbTo(float targetX) {
        ValueAnimator anim = ValueAnimator.ofFloat(thumbX, targetX);
        anim.setDuration(ANIM_DURATION_MS);
        anim.addUpdateListener(a -> {
            thumbX = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = (int) trackRect.width();
        int h = (int) trackRect.height();
        if (w == 0 || h == 0) return;

        float midX = w / 2f;
        float cornerRadius = h / 2f;

        // Track: clip a rounded rect, fill the left half red and
        // the right half green. Clipping keeps the corners
        // rounded while letting the inside be a hard split.
        trackPath.reset();
        trackPath.addRoundRect(trackRect, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(trackPath);
        canvas.drawRect(0, 0, midX, h, redPaint);
        canvas.drawRect(midX, 0, w, h, greenPaint);
        canvas.restore();

        // Thumb: a white circle in the active half. Radius is
        // 35% of the track height so the thumb sits clearly
        // inside the track with a small colored ring visible
        // around it on every position.
        float thumbRadius = h * 0.35f;
        canvas.drawCircle(thumbX, h / 2f, thumbRadius, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Don't let the parent scroll container steal
                // the gesture if the user is dragging the thumb.
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                // Follow the finger, clamped to the track edges
                // (with a small inset for the thumb radius).
                float thumbRadius = trackRect.height() * 0.35f;
                float clamped = Math.max(thumbRadius,
                        Math.min(event.getX(), trackRect.width() - thumbRadius));
                thumbX = clamped;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                // Snap to whichever side the touch ended on. The
                // user can also tap a side directly to set that
                // state without dragging.
                boolean newChecked = event.getX() >= trackRect.width() / 2f;
                setChecked(newChecked);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                // Snap back to the current state's position on
                // cancel so the thumb doesn't get stuck mid-drag.
                animateThumbTo(thumbCenterX(checked));
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
