package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the pure-Java heading filter that backs
 * {@link OrientationProvider}. No Android instrumentation required.
 */
public class HeadingFilterTest {

    private static final float EPS = 0.5f;

    @Test
    public void firstSampleAdoptedImmediately() {
        HeadingFilter f = new HeadingFilter();
        float result = f.processSample(45.0f, 0.0f, 0.0f, 1000L);
        assertEquals("first sample seeds the filter directly",
                45.0f, result, 0.01f);
    }

    @Test
    public void angleIsNormalizedOnFirstSample() {
        HeadingFilter f = new HeadingFilter();
        float neg = f.processSample(-45.0f, 0.0f, 0.0f, 1000L);
        assertEquals("-45° should normalize to 315°", 315.0f, neg, 0.01f);

        HeadingFilter f2 = new HeadingFilter();
        float wrap = f2.processSample(720.0f, 0.0f, 0.0f, 1000L);
        assertEquals("720° should normalize to 0°", 0.0f, wrap, 0.01f);
    }

    @Test
    public void pitchOverGateIsRejected() {
        HeadingFilter f = new HeadingFilter();
        float result = f.processSample(45.0f, 65.0f, 0.0f, 1000L);
        assertTrue("pitch > 60° must be rejected", Float.isNaN(result));
        // Filter should still be unseeded; next accepted sample seeds.
        float next = f.processSample(50.0f, 0.0f, 0.0f, 2000L);
        assertEquals("next accepted sample seeds directly",
                50.0f, next, 0.01f);
    }

    @Test
    public void rollOverGateIsRejected() {
        HeadingFilter f = new HeadingFilter();
        float result = f.processSample(45.0f, 0.0f, -75.0f, 1000L);
        assertTrue("|roll| > 60° must be rejected", Float.isNaN(result));
    }

    @Test
    public void pitchExactlyAtGateIsAccepted() {
        HeadingFilter f = new HeadingFilter();
        float result = f.processSample(45.0f, 60.0f, 0.0f, 1000L);
        assertEquals("pitch == 60° is on the inclusive boundary",
                45.0f, result, 0.01f);
    }

    @Test
    public void rollExactlyAtGateIsAccepted() {
        HeadingFilter f = new HeadingFilter();
        float result = f.processSample(45.0f, 0.0f, -60.0f, 1000L);
        assertEquals("|roll| == 60° is on the inclusive boundary",
                45.0f, result, 0.01f);
    }

    @Test
    public void tiltGateRejectsDoNotCorruptFilterState() {
        // After a tilt-gate rejection, the filter should keep its
        // last accepted heading and lastSampleMs. The next accepted
        // sample should continue filtering, not re-seed.
        HeadingFilter f = new HeadingFilter();
        f.processSample(0.0f, 0.0f, 0.0f, 0L);
        // Tilt-rejected sample; should not change state.
        f.processSample(90.0f, 80.0f, 0.0f, 50L);
        // Now feed an accepted sample at t=100. The dt is 100 (since
        // the last accepted sample, not 50). Filter should not
        // produce the value it would have if 0 had been corrupted
        // by 90°.
        float result = f.processSample(0.0f, 0.0f, 0.0f, 100L);
        // dt=100, alpha=0.632, lerp(0, 0, alpha) = 0. Just verifying
        // the filter state is intact.
        assertEquals(0.0f, result, 0.01f);
    }

    @Test
    public void lerpAngleTakesShortPathCCW() {
        // 350° → 10° goes via 0° (CCW, 20°), not via 180° (CW, 340°).
        float result = HeadingFilter.lerpAngle(350.0f, 10.0f, 0.5f);
        assertEquals("should cross 0°, not 180°", 0.0f, result, 0.01f);
    }

    @Test
    public void lerpAngleTakesShortPathCW() {
        // 10° → 350° goes via 0° (CW, 20°), not via 180° (CCW, 340°).
        float result = HeadingFilter.lerpAngle(10.0f, 350.0f, 0.5f);
        assertEquals("should cross 0°, not 180°", 0.0f, result, 0.01f);
    }

    @Test
    public void lerpAngleNoWraparound() {
        float result = HeadingFilter.lerpAngle(100.0f, 200.0f, 0.5f);
        assertEquals(150.0f, result, 0.01f);
    }

    @Test
    public void lerpAngleEndpoints() {
        assertEquals(10.0f, HeadingFilter.lerpAngle(10.0f, 350.0f, 0.0f), 0.01f);
        assertEquals(350.0f, HeadingFilter.lerpAngle(10.0f, 350.0f, 1.0f), 0.01f);
    }

    @Test
    public void lowPassConvergesOnSustainedInput() {
        // Seed at 0°, then feed 90° every 20 ms for 1 second.
        // The filter should be very close to 90° after 1 s of
        // sustained input (10× the time constant).
        HeadingFilter f = new HeadingFilter();
        f.processSample(0.0f, 0.0f, 0.0f, 0L);
        for (long t = 20; t <= 1000; t += 20) {
            f.processSample(90.0f, 0.0f, 0.0f, t);
        }
        assertEquals("after 1 s the filter should track 90°",
                90.0f, f.filteredDeg(), 0.5f);
    }

    @Test
    public void lowPassSmoothsStepInputByOneTimeConstant() {
        // Seed at 0°, then a single 90° step at dt=100 ms (1 time
        // constant). The filter should move ~63.2% of the way.
        HeadingFilter f = new HeadingFilter();
        f.processSample(0.0f, 0.0f, 0.0f, 0L);
        float result = f.processSample(90.0f, 0.0f, 0.0f, 100L);
        // alpha = 1 - exp(-1) ≈ 0.6321
        // expected ≈ 90 * 0.6321 ≈ 56.9
        assertEquals(56.9f, result, 1.0f);
    }

    @Test
    public void lowPassSmoothsStepInputByTwoTimeConstants() {
        // dt=200 ms (2 time constants) → ~86.5% of the way.
        HeadingFilter f = new HeadingFilter();
        f.processSample(0.0f, 0.0f, 0.0f, 0L);
        float result = f.processSample(90.0f, 0.0f, 0.0f, 200L);
        // alpha = 1 - exp(-2) ≈ 0.8647
        // expected ≈ 90 * 0.8647 ≈ 77.8
        assertEquals(77.8f, result, 1.0f);
    }

    @Test
    public void lowPassSmoothsStepInputByHalfTimeConstant() {
        // dt=50 ms (½ time constant) → ~39.3% of the way.
        HeadingFilter f = new HeadingFilter();
        f.processSample(0.0f, 0.0f, 0.0f, 0L);
        float result = f.processSample(90.0f, 0.0f, 0.0f, 50L);
        // alpha = 1 - exp(-0.5) ≈ 0.3935
        // expected ≈ 90 * 0.3935 ≈ 35.4
        assertEquals(35.4f, result, 1.0f);
    }

    @Test
    public void zeroDeltaDoesNotBlowUp() {
        HeadingFilter f = new HeadingFilter();
        f.processSample(0.0f, 0.0f, 0.0f, 1000L);
        float result = f.processSample(90.0f, 0.0f, 0.0f, 1000L);
        assertFalse("dt=0 should not produce NaN", Float.isNaN(result));
        // alpha is small (1 - exp(-0.01) ≈ 0.00995), so result ≈ 0.9
        assertEquals("with tiny alpha, result should be near 0",
                0.0f, result, 1.0f);
    }

    @Test
    public void negativeDeltaDoesNotBlowUp() {
        HeadingFilter f = new HeadingFilter();
        f.processSample(0.0f, 0.0f, 0.0f, 1000L);
        float result = f.processSample(90.0f, 0.0f, 0.0f, 999L);
        assertFalse("negative dt should not produce NaN", Float.isNaN(result));
    }

    @Test
    public void resetClearsFilter() {
        HeadingFilter f = new HeadingFilter();
        f.processSample(45.0f, 0.0f, 0.0f, 1000L);
        assertEquals(45.0f, f.filteredDeg(), 0.01f);
        f.reset();
        assertTrue("reset should clear the filter to NaN",
                Float.isNaN(f.filteredDeg()));
        // After reset, the next sample should seed directly.
        float result = f.processSample(120.0f, 0.0f, 0.0f, 2000L);
        assertEquals(120.0f, result, 0.01f);
    }

    @Test
    public void normalizeHandlesAllSigns() {
        assertEquals(0.0f, HeadingFilter.normalizeAngle(0.0f), 0.01f);
        assertEquals(180.0f, HeadingFilter.normalizeAngle(180.0f), 0.01f);
        assertEquals(359.999f, HeadingFilter.normalizeAngle(-0.001f), 0.01f);
        assertEquals(90.0f, HeadingFilter.normalizeAngle(450.0f), 0.01f);
        assertEquals(270.0f, HeadingFilter.normalizeAngle(-90.0f), 0.01f);
    }

    @Test
    public void filterConvergesViaShortPath() {
        // A real-world scenario: the user is heading 350° and turns
        // the phone CCW (CCW = azimuth decreasing, going through 0).
        // The filter should follow via 0°, not swing through 180°.
        HeadingFilter f = new HeadingFilter();
        f.processSample(350.0f, 0.0f, 0.0f, 0L);
        for (int i = 1; i <= 10; i++) {
            // Each step decreases azimuth by 5°, going 350 → 300.
            float a = 350.0f - 5.0f * i;
            f.processSample(a, 0.0f, 0.0f, (long) i * 100L);
        }
        // After 10 steps, input is 300°. The filter has been moving
        // by 5° every 100 ms (½ time constant each), so it should
        // be between 300 and 350 but closer to 300. The key check:
        // it never swings the other way through 180°.
        float result = f.filteredDeg();
        assertTrue("filter should be near 300° (got " + result + ")",
                result > 290.0f && result < 350.0f);
    }
}
