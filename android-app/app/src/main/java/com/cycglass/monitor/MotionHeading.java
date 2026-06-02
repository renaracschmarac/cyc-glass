package com.cycglass.monitor;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Heading source that picks the most appropriate signal for the
 * bike HUD:
 *
 * <ul>
 *   <li>Below the speed threshold (~2 m/s, ~4.5 mph): use the
 *       phone's rotation-vector-derived azimuth. GPS bearing is
 *       too noisy at low speed (small position deltas amplify
 *       jitter).</li>
 *   <li>At or above the threshold: use the GPS bearing between
 *       two recent fixes, averaged over a 1.5 s window. This is
 *       the actual direction of motion, which is what the rider
 *       cares about.</li>
 * </ul>
 *
 * <p>The switch is hard: at the threshold the map snaps from one
 * source to the other. That's acceptable because (a) the rider
 * cares about heading most when moving, and (b) a smooth
 * cross-fade adds complexity for a benefit only seen in the
 * ~2 m/s transition band.
 *
 * <p>Pure Java (no Android imports) so the bearing math, buffer
 * aging, and switch logic are exhaustively unit-testable.
 *
 * <p>See {@code docs/2026-06-03-gps-bearing-heading-plan.md}.
 */
public final class MotionHeading {

    public interface Listener {
        /**
         * Called whenever a new heading is available. {@code
         * degreesFromNorth} is normalized to {@code [0, 360)}.
         */
        void onHeading(float degreesFromNorth, long timestampMs);
    }

    private static final String TAG = "MotionHeading";

    /** Hard switch speed, in m/s. ~4.5 mph. */
    public static final float SWITCH_SPEED_MPS = 2.0f;

    /** How far back the bearing window looks. */
    static final long WINDOW_MS = 1500L;

    /** Minimum span between oldest and newest fix for a bearing
     * to be valid. 500 ms gives enough motion (≥1 m at 2 m/s)
     * for a stable bearing. */
    static final long MIN_BEARING_SPAN_MS = 500L;

    /** Hard cap on the ring buffer. At 1 Hz GPS that's 16 s of
     * history, which is well past the 1.5 s window — but it
     * gives headroom for higher-rate location sources without
     * dropping data inside the window. */
    static final int MAX_BUFFER = 16;

    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();
    // The azimuth path is already low-passed by OrientationProvider,
    // so we only filter the bearing here. The bearing filter is
    // also a low-pass, but it acts on 1 Hz GPS updates — at that
    // rate the time constant (100 ms) is much smaller than the
    // sample period, so it converges within one sample and adds
    // no perceptible lag.
    private final HeadingFilter bearingFilter = new HeadingFilter();

    // Ring buffer of recent fixes.  Indices [0, bufferCount) are
    // valid; the rest are stale.  We shift on eviction rather
    // than using a circular index, so the oldest point is always
    // at index 0 — simpler when slicing by time.
    private final double[] bufferLat = new double[MAX_BUFFER];
    private final double[] bufferLon = new double[MAX_BUFFER];
    private final long[] bufferMs = new long[MAX_BUFFER];
    private int bufferCount = 0;

    private float lastSpeedMps = 0f;
    private boolean usingGps = false;

    public void addListener(@NonNull Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(@NonNull Listener l) {
        listeners.remove(l);
    }

    /**
     * Feeds a phone-heading sample. {@code azimuthDeg} should be
     * the result of {@code SensorManager.getOrientation} on a
     * rotation-vector-derived matrix, converted to degrees and
     * already tilt-gated by the caller.
     *
     * <p>Output is only emitted when speed is below the switch
     * threshold; otherwise the GPS path is the source of truth.
     */
    public void onAzimuth(float azimuthDeg, long timestampMs) {
        float normalized = wrap360(azimuthDeg);
        if (lastSpeedMps < SWITCH_SPEED_MPS) {
            noteSource(false);
            emit(normalized, timestampMs);
        }
    }

    /**
     * Feeds a GPS fix. Stores the speed (used by the switch
     * gate) and the position (used for bearing). Emits a
     * heading only when speed is at or above the threshold and
     * a stable bearing can be computed.
     */
    public void onLocation(double latitudeDeg, double longitudeDeg,
                           long fixMs, float speedMps) {
        this.lastSpeedMps = speedMps;
        appendFix(latitudeDeg, longitudeDeg, fixMs);
        if (lastSpeedMps < SWITCH_SPEED_MPS) return;
        if (bufferCount < 2) return;
        long span = bufferMs[bufferCount - 1] - bufferMs[0];
        if (span < MIN_BEARING_SPAN_MS) return;
        double bearing = computeBearing(
                bufferLat[0], bufferLon[0],
                bufferLat[bufferCount - 1], bufferLon[bufferCount - 1]);
        float bearingDeg = wrap360((float) Math.toDegrees(bearing));
        float filtered = bearingFilter.processSample(
                bearingDeg, /*pitchDeg*/ 0f, /*rollDeg*/ 0f, fixMs);
        if (Float.isNaN(filtered)) return;
        noteSource(true);
        emit(filtered, fixMs);
    }

    /**
     * Logs a one-line diagnostic when the heading source
     * switches between the rotation vector (slow / stopped) and
     * the GPS bearing (moving above 2 m/s). One line per
     * transition, so the cost is negligible even on long rides.
     * Filtered via {@code adb logcat -s MotionHeading:V}.
     */
    private void noteSource(boolean nowUsingGps) {
        if (nowUsingGps == usingGps) return;
        usingGps = nowUsingGps;
        Log.i(TAG, nowUsingGps
                ? "switching to GPS bearing (speed ≥ 2 m/s)"
                : "switching to rotation vector (speed < 2 m/s)");
    }

    /**
     * Drops the ring buffer. The filters are not reset — they
     * keep their last value so the next sample after a clear
     * is smoothed against the most recent known heading. Tests
     * use this to start from a clean state.
     */
    public void reset() {
        bufferCount = 0;
        lastSpeedMps = 0f;
    }

    /**
     * Initial great-circle bearing from {@code (lat1, lon1)} to
     * {@code (lat2, lon2)}, in radians, normalized to
     * {@code (-π, π]}.
     *
     * <p>Standard formula — see e.g.
     * <a href="https://www.movable-type.co.uk/scripts/latlong.html">
     * movabletype.co.uk/scripts/latlong.html</a>.
     */
    public static double computeBearing(double lat1Deg, double lon1Deg,
                                        double lat2Deg, double lon2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return Math.atan2(y, x);
    }

    /** {@code angle} wrapped to {@code [0, 360)}. NaN is
     * preserved. */
    static float wrap360(float angle) {
        if (Float.isNaN(angle)) return Float.NaN;
        float a = angle % 360f;
        if (a < 0f) a += 360f;
        return a;
    }

    private void appendFix(double lat, double lon, long fixMs) {
        // Drop any points older than the window first. The fix
        // we're about to add is the newest, so it can't itself
        // be expired (fixMs is monotonically non-decreasing in
        // practice; we don't guard against clock weirdness here).
        while (bufferCount > 0 && (fixMs - bufferMs[0]) > WINDOW_MS) {
            shiftLeft(0);
        }
        if (bufferCount < MAX_BUFFER) {
            bufferLat[bufferCount] = lat;
            bufferLon[bufferCount] = lon;
            bufferMs[bufferCount] = fixMs;
            bufferCount++;
        } else {
            // Buffer full — evict the oldest by shifting, then
            // write the new fix at the end.
            shiftLeft(0);
            bufferLat[MAX_BUFFER - 1] = lat;
            bufferLon[MAX_BUFFER - 1] = lon;
            bufferMs[MAX_BUFFER - 1] = fixMs;
        }
    }

    private void shiftLeft(int fromIndex) {
        int n = bufferCount - fromIndex - 1;
        if (n <= 0) {
            bufferCount = fromIndex;
            return;
        }
        System.arraycopy(bufferLat, fromIndex + 1, bufferLat, fromIndex, n);
        System.arraycopy(bufferLon, fromIndex + 1, bufferLon, fromIndex, n);
        System.arraycopy(bufferMs, fromIndex + 1, bufferMs, fromIndex, n);
        bufferCount--;
    }

    private void emit(float degreesFromNorth, long timestampMs) {
        for (Listener l : listeners) l.onHeading(degreesFromNorth, timestampMs);
    }
}
