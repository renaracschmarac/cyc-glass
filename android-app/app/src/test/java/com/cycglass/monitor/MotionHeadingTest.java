package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link MotionHeading}. The class is pure Java,
 * so these run in the standard JVM test source set (no
 * instrumentation needed).
 */
public class MotionHeadingTest {

    /** Helper that records every onHeading callback. */
    private static final class Recorder implements MotionHeading.Listener {
        final List<Float> headings = new ArrayList<>();
        final List<Long> timestamps = new ArrayList<>();
        @Override public void onHeading(float degreesFromNorth, long timestampMs) {
            headings.add(degreesFromNorth);
            timestamps.add(timestampMs);
        }
    }

    private static final float DELTA = 0.5f;  // ±0.5° is fine for trig

    // ------------------------------------------------------------------
    // computeBearing
    // ------------------------------------------------------------------

    @Test
    public void computeBearing_dueEast() {
        // Same lat, increasing lon → due east → 90°.
        double bearing = MotionHeading.computeBearing(
                46.8, -92.0, 46.8, -91.9);
        assertEquals(90.0, Math.toDegrees(bearing), DELTA);
    }

    @Test
    public void computeBearing_dueWest() {
        // Same lat, decreasing lon → due west → -90° (= 270°).
        double bearing = MotionHeading.computeBearing(
                46.8, -92.0, 46.8, -92.1);
        assertEquals(-90.0, Math.toDegrees(bearing), DELTA);
    }

    @Test
    public void computeBearing_dueNorth() {
        // Increasing lat, same lon → due north → 0°.
        double bearing = MotionHeading.computeBearing(
                46.8, -92.0, 46.9, -92.0);
        assertEquals(0.0, Math.toDegrees(bearing), DELTA);
    }

    @Test
    public void computeBearing_dueSouth() {
        // Decreasing lat, same lon → due south → 180°.
        double bearing = MotionHeading.computeBearing(
                46.8, -92.0, 46.7, -92.0);
        assertEquals(180.0, Math.toDegrees(bearing), DELTA);
    }

    @Test
    public void computeBearing_northeast45() {
        // 1° lat north + 1° lon east at mid-latitudes → roughly NE
        // (the exact angle is 90° in pure-lat / pure-lon terms,
        // but at lat=45° the lon-corrected angle is about 45°).
        // Use smaller deltas so the curvature error is small.
        double bearing = MotionHeading.computeBearing(
                46.8, -92.0, 46.81, -91.99);
        // 1 deg lat ≈ 111 km, 1 deg lon at 46.8° lat ≈ 76 km
        // → atan2(76, 111) ≈ 34.4° east of north. Allow a wide
        // margin for this check.
        assertTrue("bearing should be in the NE quadrant: "
                + Math.toDegrees(bearing),
                Math.toDegrees(bearing) > 25.0
                        && Math.toDegrees(bearing) < 45.0);
    }

    @Test
    public void computeBearing_samePoint() {
        // Two identical points → atan2(0, 0) = 0.
        double bearing = MotionHeading.computeBearing(
                46.8, -92.0, 46.8, -92.0);
        assertEquals(0.0, Math.toDegrees(bearing), 0.0);
    }

    @Test
    public void computeBearing_wraparoundAtAntimeridian() {
        // Move east across the antimeridian: lon goes from +179.9
        // to -179.9. The short-path bearing should be due east
        // (~90°), not the long way around the globe.
        double bearing = MotionHeading.computeBearing(
                46.8, 179.9, 46.8, -179.9);
        // The "short way" bearing is +90°; the formula naturally
        // gives the short way because we don't normalize dLon.
        assertEquals(90.0, Math.toDegrees(bearing), DELTA);
    }

    // ------------------------------------------------------------------
    // wrap360
    // ------------------------------------------------------------------

    @Test
    public void wrap360_inRange() {
        assertEquals(45.0f, MotionHeading.wrap360(45.0f), 0.0f);
        assertEquals(359.0f, MotionHeading.wrap360(359.0f), 0.0f);
    }

    @Test
    public void wrap360_negative() {
        assertEquals(350.0f, MotionHeading.wrap360(-10.0f), 0.0f);
        assertEquals(180.0f, MotionHeading.wrap360(-180.0f), 0.0f);
    }

    @Test
    public void wrap360_overflow() {
        assertEquals(10.0f, MotionHeading.wrap360(370.0f), 0.0f);
        assertEquals(0.0f, MotionHeading.wrap360(720.0f), 0.0f);
    }

    @Test
    public void wrap360_nanIsNan() {
        // NaN must round-trip — we don't want a stray 0.0f leaking
        // into the filter chain.
        float result = MotionHeading.wrap360(Float.NaN);
        assertTrue("NaN should pass through, got " + result,
                Float.isNaN(result));
    }

    // ------------------------------------------------------------------
    // Switch behavior
    // ------------------------------------------------------------------

    @Test
    public void onAzimuth_emitsBelowThreshold() {
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        // Default speed is 0, which is below the threshold.
        mh.onAzimuth(45.0f, 1_000L);
        assertEquals(1, r.headings.size());
        assertEquals(45.0f, r.headings.get(0), 0.0f);
        assertEquals(1_000L, (long) r.timestamps.get(0));
    }

    @Test
    public void onAzimuth_suppressedAtOrAboveThreshold() {
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        // First push speed over the threshold, then deliver an
        // azimuth. The azimuth must NOT be emitted.
        mh.onLocation(46.8, -92.0, 1_000L, /*speedMps*/ 2.0f);
        mh.onLocation(46.8, -91.999, 1_500L, /*speedMps*/ 2.0f);
        r.headings.clear();
        mh.onAzimuth(45.0f, 1_600L);
        assertEquals("azimuth should be suppressed at speed", 0, r.headings.size());
    }

    @Test
    public void onLocation_emitsBearingAboveThreshold() {
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        // Two fixes 1 s apart, 0.01° east at lat 46.8 (~1 km/h? no:
        // 0.01° lat ≈ 1.1 km, 0.01° lon ≈ 0.76 km; the bike
        // isn't actually moving that fast but the bearing math
        // doesn't care about scale, only direction).
        // Use a more realistic distance: 0.0001° lat north →
        // 11 m north, 0 m east → due-north bearing.
        mh.onLocation(46.8000, -92.0000, 1_000L, /*speedMps*/ 3.0f);
        mh.onLocation(46.8001, -92.0000, 2_000L, /*speedMps*/ 3.0f);
        // The second call should have triggered an emit with a
        // near-zero bearing.
        assertFalse("expected a bearing event", r.headings.isEmpty());
        float last = r.headings.get(r.headings.size() - 1);
        assertTrue("bearing should be near 0 (north), got " + last,
                last < 1.0f || last > 359.0f);
    }

    @Test
    public void onLocation_suppressedBelowThreshold() {
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        mh.onLocation(46.8000, -92.0000, 1_000L, /*speedMps*/ 1.0f);
        mh.onLocation(46.8001, -92.0000, 2_000L, /*speedMps*/ 1.0f);
        assertEquals("GPS bearing should be suppressed at low speed",
                0, r.headings.size());
    }

    @Test
    public void onLocation_noBearingWithSinglePoint() {
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        mh.onLocation(46.8, -92.0, 1_000L, 3.0f);
        assertEquals("no bearing with a single fix", 0, r.headings.size());
    }

    @Test
    public void onLocation_noBearingWhenPointsTooClose() {
        // Two fixes only 200 ms apart — below the 500 ms minimum
        // span, so the bearing is rejected.
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        mh.onLocation(46.8000, -92.0000, 1_000L, 3.0f);
        mh.onLocation(46.8001, -92.0000, 1_200L, 3.0f);
        assertEquals("no bearing when fixes are < 500 ms apart",
                0, r.headings.size());
    }

    @Test
    public void hardSwitchFromAzimuthToGps() {
        // Standing still, then start moving. The last azimuth
        // value should be the only one we see, until the first
        // bearing event comes through.
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        mh.onAzimuth(120.0f, 1_000L);  // 120° (ESE)
        mh.onAzimuth(122.0f, 1_050L);
        // Now we start moving. Two fixes, 1 s apart.
        mh.onLocation(46.8000, -92.0000, 2_000L, 3.0f);
        // First fix alone — no bearing yet.
        assertEquals(2, r.headings.size());
        mh.onLocation(46.8001, -92.0000, 3_000L, 3.0f);
        // Second fix triggers a bearing event.
        assertEquals(3, r.headings.size());
        // The new bearing is due north (0°), which is NOT 120° —
        // that's the hard switch.
        float switched = r.headings.get(2);
        assertTrue("expected hard switch to bearing, got " + switched,
                switched < 1.0f || switched > 359.0f);
    }

    @Test
    public void hardSwitchFromGpsToAzimuth() {
        // Moving, then stop. After the stop, azimuth should drive
        // the output again.
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        mh.onLocation(46.8000, -92.0000, 1_000L, 3.0f);
        mh.onLocation(46.8001, -92.0000, 2_000L, 3.0f);
        int afterMoving = r.headings.size();
        // Now slow down below threshold and deliver an azimuth.
        mh.onLocation(46.8002, -92.0000, 3_000L, 0.0f);
        r.headings.clear();
        mh.onAzimuth(45.0f, 3_100L);
        assertEquals("azimuth should resume at low speed", 1, r.headings.size());
        assertEquals(45.0f, r.headings.get(0), 0.0f);
        // The buffer should still have the old fast fixes, but
        // they don't matter because we don't emit below threshold.
    }

    // ------------------------------------------------------------------
    // Buffer aging
    // ------------------------------------------------------------------

    @Test
    public void bufferDropsPointsOlderThanWindow() {
        // Push 5 fixes 600 ms apart, total span 3000 ms. The
        // window is 1500 ms, so only the last ~3 should remain
        // in the buffer when the 5th arrives. The bearing
        // computed at that point should use the oldest point
        // still inside the window, not the very first one.
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        mh.onLocation(46.8000, -92.0000, 1_000L, 3.0f);
        mh.onLocation(46.8001, -92.0000, 1_600L, 3.0f);
        mh.onLocation(46.8002, -92.0000, 2_200L, 3.0f);
        mh.onLocation(46.8003, -92.0000, 2_800L, 3.0f);
        mh.onLocation(46.8004, -92.0000, 3_400L, 3.0f);
        // The 5th fix at 3400 should evict the first (1000) and
        // the second (1600) because they're >1500 ms old. The
        // remaining buffer: [2200, 2800, 3400]. Bearing between
        // 2200 and 3400 is still due north.
        // At 1 Hz GPS in real life, this scenario is unusual, but
        // we want to verify the eviction logic works.
        assertFalse("expected at least one bearing event",
                r.headings.isEmpty());
    }

    @Test
    public void bufferEvictionAtMaxBuffer() {
        // Push MAX_BUFFER + 2 fixes, all 1 ms apart. The first
        // two should be evicted. We verify this by checking
        // that the bearing is still computed (no exceptions)
        // and that emissions keep happening.
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        long t = 0L;
        for (int i = 0; i < MotionHeading.MAX_BUFFER + 2; i++) {
            // Drift east each step so the bearing is non-zero.
            mh.onLocation(46.8, -92.0 + i * 0.0001, t, 3.0f);
            t += 600L;  // 600 ms apart
        }
        assertFalse("expected emissions even with full buffer",
                r.headings.isEmpty());
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    @Test
    public void multipleListenersAllNotified() {
        MotionHeading mh = new MotionHeading();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        mh.addListener(a);
        mh.addListener(b);
        mh.onAzimuth(45.0f, 1_000L);
        assertEquals(1, a.headings.size());
        assertEquals(1, b.headings.size());
    }

    @Test
    public void removeListenerStopsDelivery() {
        MotionHeading mh = new MotionHeading();
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        mh.addListener(a);
        mh.addListener(b);
        mh.onAzimuth(45.0f, 1_000L);
        mh.removeListener(a);
        mh.onAzimuth(50.0f, 2_000L);
        assertEquals("listener a should be silent", 1, a.headings.size());
        assertEquals("listener b should still hear all", 2, b.headings.size());
    }

    @Test
    public void addListenerIsIdempotent() {
        MotionHeading mh = new MotionHeading();
        Recorder a = new Recorder();
        mh.addListener(a);
        mh.addListener(a);
        mh.onAzimuth(45.0f, 1_000L);
        assertEquals("duplicate add should not double-deliver",
                1, a.headings.size());
    }

    // ------------------------------------------------------------------
    // reset
    // ------------------------------------------------------------------

    @Test
    public void resetClearsBufferAndSpeedState() {
        MotionHeading mh = new MotionHeading();
        Recorder r = new Recorder();
        mh.addListener(r);
        // Build up some state.
        mh.onLocation(46.8, -92.0, 1_000L, 3.0f);
        mh.onLocation(46.8001, -92.0, 2_000L, 3.0f);
        r.headings.clear();
        mh.reset();
        // After reset, lastSpeedMps is 0 — first azimuth should
        // emit, and a single fix alone should not produce a
        // bearing event.
        mh.onAzimuth(45.0f, 5_000L);
        assertEquals(1, r.headings.size());
        mh.onLocation(46.81, -92.0, 6_000L, 3.0f);
        assertEquals("post-reset, single fix should not bearing",
                1, r.headings.size());
    }

    // ------------------------------------------------------------------
    // Constants are stable
    // ------------------------------------------------------------------

    @Test
    public void constantsHaveDocumentedValues() {
        // Pin the public constants. If these change accidentally
        // the test will catch it before a bike ride does.
        assertEquals(2.0f, MotionHeading.SWITCH_SPEED_MPS, 0.0f);
        assertEquals(1500L, MotionHeading.WINDOW_MS);
        assertEquals(500L, MotionHeading.MIN_BEARING_SPAN_MS);
    }

    @Test
    public void newInstanceHasNoInternalNpe() {
        // Sanity: a brand-new MotionHeading should not throw
        // when listeners are absent. Used to be a problem when
        // the constructor called the filter chain.
        MotionHeading mh = new MotionHeading();
        mh.onAzimuth(45.0f, 1_000L);
        mh.onLocation(46.8, -92.0, 1_000L, 3.0f);
        assertNotNull(mh);
    }
}
