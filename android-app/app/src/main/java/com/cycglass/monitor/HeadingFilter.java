package com.cycglass.monitor;

/**
 * Pure-Java heading filter, extracted from {@link OrientationProvider}
 * for unit-testability. No Android dependency.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>Tilt gate.</b> Reject samples whose pitch or roll exceeds
 *       {@link #TILT_GATE_DEG}. A phone laid face-up on a stem
 *       mount reports a meaningless azimuth, and we want the
 *       provider to publish the last good heading rather than
 *       drift to garbage.</li>
 *   <li><b>Low-pass filter.</b> Time-based exponential filter
 *       with a {@link #FILTER_TIME_CONSTANT_MS} ms time constant.
 *       Smooths compass jitter when the user is stationary.
 *       The filter uses the angular distance, not the raw
 *       azimuth delta, so 359° → 1° goes through 0°, not
 *       through 180°.</li>
 * </ol>
 *
 * <p>See {@code docs/2026-06-02-heading-up-and-speed-sign-plan.md}.
 */
final class HeadingFilter {

    /** Pitch/roll above this magnitude rejects the sample. */
    static final float TILT_GATE_DEG = 60.0f;

    /** Exponential filter time constant. Smaller = more responsive
     * but more jitter; larger = smoother but laggier. 100 ms is
     * the agreed default. */
    static final float FILTER_TIME_CONSTANT_MS = 100.0f;

    private float filteredDeg = Float.NaN;
    private long lastSampleMs = 0L;

    /**
     * Process a single heading sample.
     *
     * @param azimuthDeg heading in degrees, raw (any range;
     *                   will be normalized to [0, 360))
     * @param pitchDeg   pitch in degrees (positive = top of device
     *                   tilted up)
     * @param rollDeg    roll in degrees
     * @param nowMs      system timestamp in ms; monotonic
     *                   non-decreasing expected but not required
     * @return the filtered heading in [0, 360) degrees, or
     *         {@link Float#NaN} if the sample was rejected by
     *         the tilt gate
     */
    float processSample(float azimuthDeg, float pitchDeg, float rollDeg, long nowMs) {
        if (Math.abs(pitchDeg) > TILT_GATE_DEG) return Float.NaN;
        if (Math.abs(rollDeg) > TILT_GATE_DEG) return Float.NaN;

        float a = normalizeAngle(azimuthDeg);
        if (Float.isNaN(filteredDeg)) {
            filteredDeg = a;
        } else {
            long dtMs = nowMs - lastSampleMs;
            // Clamp non-positive dt so a stalled or out-of-order
            // sample doesn't blow up the alpha calculation.
            if (dtMs <= 0) dtMs = 1;
            float alpha = 1.0f - (float) Math.exp(-dtMs / FILTER_TIME_CONSTANT_MS);
            filteredDeg = lerpAngle(filteredDeg, a, alpha);
        }
        lastSampleMs = nowMs;
        return filteredDeg;
    }

    /** Resets the filter so the next sample seeds it directly. */
    void reset() {
        filteredDeg = Float.NaN;
        lastSampleMs = 0L;
    }

    /** Visible for testing — the last accepted (filtered) heading. */
    float filteredDeg() {
        return filteredDeg;
    }

    /**
     * Lerp two angles in [0, 360), handling the 0°/360° wraparound
     * by taking the short way. E.g. {@code lerpAngle(350, 10, 0.5)}
     * returns 0 (going through 0), not 180 (going the long way).
     */
    static float lerpAngle(float from, float to, float alpha) {
        float diff = to - from;
        if (diff > 180.0f) diff -= 360.0f;
        if (diff < -180.0f) diff += 360.0f;
        float result = from + alpha * diff;
        return normalizeAngle(result);
    }

    /** Normalize an angle (in degrees) into [0, 360). */
    static float normalizeAngle(float deg) {
        float a = deg % 360.0f;
        if (a < 0) a += 360.0f;
        return a;
    }
}
