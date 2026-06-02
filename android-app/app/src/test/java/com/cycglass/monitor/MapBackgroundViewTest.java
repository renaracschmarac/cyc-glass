package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the zoom-math inside {@link MapBackgroundView}.
 *
 * <p>The math is pure (no Android dependency) so it can be tested
 * in the JVM unit-test environment. The reference numbers were
 * computed by hand from the spec formula
 *
 * <pre>
 * Z = log2(W * 156543.03392 * cos(φ) / 304.8)
 * </pre>
 *
 * <p>See {@code docs/2026-06-01-map-background-plan.md} §
 * "1000-ft = screen-width math (frozen spec)".
 */
public class MapBackgroundViewTest {

    private static final double EQUATOR_COS = 1.0;
    private static final double DULUTH_COS = 0.685;  // cos(46.78°)
    private static final double TROPIC_COS = 0.866;  // cos(30°)
    private static final double POLAR_COS = 0.05;    // high latitude

    @Test
    public void zoomAtDuluthOn1080PxScreen() {
        // 1080 px, lat 46.78° (Duluth) → ~19
        int z = MapBackgroundView.computeZoomForWidth(1080, 46.78);
        assertEquals("1080 px @ Duluth should snap to zoom 19", 19, z);
    }

    @Test
    public void zoomAtDuluthOn720PxScreen() {
        // 720 px, lat 46.78° → ~18
        int z = MapBackgroundView.computeZoomForWidth(720, 46.78);
        assertEquals("720 px @ Duluth should snap to zoom 18", 18, z);
    }

    @Test
    public void zoomAtDuluthOn1440PxScreen() {
        // 1440 px, lat 46.78° → still 19 (next integer is too far)
        int z = MapBackgroundView.computeZoomForWidth(1440, 46.78);
        assertEquals("1440 px @ Duluth should snap to zoom 19", 19, z);
    }

    @Test
    public void zoomAtEquatorOn1080PxScreen() {
        // 1080 px, equator → about 18.5; rounds to 19.
        int z = MapBackgroundView.computeZoomForWidth(1080, 0.0);
        // The exact value at the equator: log2(1080 * 156543.03 / 304.8)
        // = log2(554743) ≈ 19.08. So 19.
        assertEquals("1080 px @ equator should be ~19", 19, z);
    }

    @Test
    public void zoomIncreasesWithScreenWidth() {
        int z720 = MapBackgroundView.computeZoomForWidth(720, 46.78);
        int z1080 = MapBackgroundView.computeZoomForWidth(1080, 46.78);
        int z1440 = MapBackgroundView.computeZoomForWidth(1440, 46.78);
        int z2160 = MapBackgroundView.computeZoomForWidth(2160, 46.78);
        // Monotonically non-decreasing. Higher resolution, more zoom.
        assertTrue("720 should be <= 1080", z720 <= z1080);
        assertTrue("1080 should be <= 1440", z1080 <= z1440);
        assertTrue("1440 should be <= 2160", z1440 <= z2160);
    }

    @Test
    public void zoomIncreasesWithLatitudeAtFixedWidth() {
        // Z = log2(W * cos(φ) * 156543 / 304.8). As latitude
        // increases, cos(φ) decreases, so the argument shrinks, so
        // Z shrinks. Higher latitude → smaller zoom (the world
        // collapses toward the poles per unit of ground distance,
        // so you need less zoom-in to fit 1000 ft on the screen).
        int zTropic = MapBackgroundView.computeZoomForWidth(1080, 30.0);
        int zDuluth = MapBackgroundView.computeZoomForWidth(1080, 46.78);
        int zHigh = MapBackgroundView.computeZoomForWidth(1080, 60.0);
        // Monotonically non-increasing with latitude.
        assertTrue("higher latitude → smaller or equal zoom (Duluth vs Tropic)",
                zDuluth <= zTropic);
        assertTrue("higher latitude → smaller or equal zoom (60° vs Duluth)",
                zHigh <= zDuluth);
    }

    @Test
    public void zoomAtZeroWidthIsSafe() {
        // Should not throw; the function returns 0 in that case
        // (no usable width to project onto a tile grid).
        int z = MapBackgroundView.computeZoomForWidth(0, 46.78);
        assertEquals("0-px width should yield zoom 0", 0, z);
    }

    @Test
    public void zoomNearPoleIsSafe() {
        // cos(89.9°) is ~0.0017. Z = log2(1080 * 156543 * 0.0017 / 304.8)
        // = log2(943) ≈ 9.88, so 10. The world is so small at the
        // pole that you don't need a high zoom to see 1000 ft.
        int z = MapBackgroundView.computeZoomForWidth(1080, 89.9);
        assertEquals("near-pole zoom should round to 10", 10, z);
        // Also assert the function doesn't crash at lat 90.0 itself.
        // cos(90°) ≈ 6e-17 (not exactly 0). Our safeguard floors at
        // 0.001, giving Z = log2(1080 * 156543 * 0.001 / 304.8)
        // = log2(554) ≈ 9.11 → 9.
        int z90 = MapBackgroundView.computeZoomForWidth(1080, 90.0);
        assertEquals("at lat 90 the safeguard should kick in and give Z≈9",
                9, z90);
    }
}
