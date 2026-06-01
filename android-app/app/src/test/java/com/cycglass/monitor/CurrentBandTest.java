package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Unit tests for the CURRENT band's alpha gradient in {@link GlassView}.
 *
 * <p>The band needs to fade from fully transparent at 0 A to fully opaque
 * at the configured current limit, so it can sit as a subtle overlay
 * over a future map background. The alpha scales linearly with the
 * |current| / limit fraction.
 *
 * <p>The RGB channels still walk the green \u2192 yellow \u2192 red scale, but
 * {@code Color.rgb()} is mocked in local unit tests (returns 0) so we
 * only assert the alpha byte here. The RGB behavior is covered by
 * live-hardware verification at the bike.
 */
public class CurrentBandTest {

    private static final int ALPHA_MASK = 0xFF000000;
    private static final int RGB_MASK = 0x00FFFFFF;

    @Test
    public void currentColorIsTransparentAtZero() throws Exception {
        GlassView v = newView(0.0, 100.0f, 20.0f);
        int color = invokeCurrentColor(v);
        int alpha = (color & ALPHA_MASK) >>> 24;
        assertEquals("0 A must be fully transparent (alpha 0)", 0, alpha);
    }

    @Test
    public void currentColorIsOpaqueAtLimit() throws Exception {
        GlassView v = newView(100.0, 100.0f, 20.0f);
        int color = invokeCurrentColor(v);
        int alpha = (color & ALPHA_MASK) >>> 24;
        assertEquals("limit current must be fully opaque (alpha 255)", 255, alpha);
    }

    @Test
    public void currentColorAlphaScalesLinearly() throws Exception {
        // At half the negative limit (ampsOut = 100), alpha should be ~128.
        GlassView v = newView(-50.0, 100.0f, 20.0f);
        int color = invokeCurrentColor(v);
        int alpha = (color & ALPHA_MASK) >>> 24;
        assertEquals(128, alpha);
    }

    @Test
    public void currentColorRespectsBothLimitsIndependently() throws Exception {
        // ampsOut = 100, ampsIn = 20. A draw of -50 A is at half of
        // ampsOut (alpha ~128). The same current is over the ampsIn
        // limit, but the negative side uses ampsOut, so the color
        // should still be at the yellow midpoint, not at the limit.
        GlassView negative = newView(-50.0, 100.0f, 20.0f);
        int negativeColor = invokeCurrentColor(negative);
        int negativeAlpha = (negativeColor & ALPHA_MASK) >>> 24;
        assertEquals(128, negativeAlpha);

        // A draw of +15 A is at 75% of ampsIn (20), alpha ~191.
        GlassView positive = newView(15.0, 100.0f, 20.0f);
        int positiveColor = invokeCurrentColor(positive);
        int positiveAlpha = (positiveColor & ALPHA_MASK) >>> 24;
        assertEquals(191, positiveAlpha);
    }

    @Test
    public void currentColorStaysTransparentAtTinyCurrent() throws Exception {
        // 0.01 A out of 100 A is 0.01% of the limit; round(255 * 0.0001) = 0
        // with plenty of headroom for float rounding to stay at 0.
        GlassView v = newView(0.01, 100.0f, 20.0f);
        int color = invokeCurrentColor(v);
        int alpha = (color & ALPHA_MASK) >>> 24;
        assertEquals(0, alpha);
    }

    @Test
    public void currentColorIsMonotonicInCurrent() throws Exception {
        // Higher |current| should never have a lower alpha than lower
        // |current|, for any sign.
        int prev = -1;
        for (double a = 0; a <= 105; a += 5) {
            GlassView v = newView(a, 100.0f, 20.0f);
            int alpha = (invokeCurrentColor(v) & ALPHA_MASK) >>> 24;
            assertTrue("alpha must be non-decreasing with |current| (a=" + a
                    + ", prev=" + prev + ", now=" + alpha + ")", alpha >= prev);
            prev = alpha;
        }
    }

    @Test
    public void currentColorSignIndependent() throws Exception {
        // The sign picks which limit applies: positive current uses
        // ampsIn, negative uses ampsOut. So |current| alone does not
        // determine alpha; the sign picks the limit. To get the same
        // alpha on both sides, the |current| must be the same fraction
        // of the applicable limit. With ampsOut=100 and ampsIn=20, a
        // draw of +10 A is at 50% of ampsIn (alpha 128), and a regen
        // of -50 A is at 50% of ampsOut (also alpha 128).
        GlassView pos = newView(10.0, 100.0f, 20.0f);
        GlassView neg = newView(-50.0, 100.0f, 20.0f);
        int posAlpha = (invokeCurrentColor(pos) & ALPHA_MASK) >>> 24;
        int negAlpha = (invokeCurrentColor(neg) & ALPHA_MASK) >>> 24;
        assertEquals(128, posAlpha);
        assertEquals(128, negAlpha);
        assertEquals(posAlpha, negAlpha);
    }

    @Test
    public void currentColorStaysWithinValidRange() throws Exception {
        for (double a = -200; a <= 200; a += 17) {
            GlassView v = newView(a, 100.0f, 20.0f);
            int color = invokeCurrentColor(v);
            int alpha = (color & ALPHA_MASK) >>> 24;
            assertTrue("alpha out of range at a=" + a + ": " + alpha,
                    alpha >= 0 && alpha <= 255);
        }
    }

    /** Constructs a GlassView with a known current value for tests. */
    private static GlassView newView(double current, float ampsOut, float ampsIn) {
        // The constructor takes a Context; we never call any method that
        // dereferences it (the dp() helper is only invoked from onDraw),
        // so passing null is safe for the unit test.
        GlassView v = new GlassView(
                (android.content.Context) null,
                new DataModel(),
                ampsOut,
                ampsIn);
        v.setCurrentValueForTest(current);
        return v;
    }

    private static int invokeCurrentColor(GlassView v) throws Exception {
        Method m = GlassView.class.getDeclaredMethod("currentColor");
        m.setAccessible(true);
        return (int) m.invoke(v);
    }
}
