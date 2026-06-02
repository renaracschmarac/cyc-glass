package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the Imperial/Metric conversion + formatting
 * helpers in {@link Units}. Pure Java so these run on the JVM
 * without an Android device.
 */
public class UnitsTest {

    // --- conversions ----------------------------------------------------

    @Test
    public void mphToKph_zero() {
        assertEquals(0.0, Units.mphToKph(0.0), 1e-9);
    }

    @Test
    public void mphToKph_knownValues() {
        // 60 mph ≈ 96.5606 kph (NIST: 1 mph = 1.609344 kph exactly)
        assertEquals(96.56064, Units.mphToKph(60.0), 1e-4);
        // 1 mph = 1.609344 kph
        assertEquals(1.609344, Units.mphToKph(1.0), 1e-9);
        // 100 mph = 160.9344 kph
        assertEquals(160.9344, Units.mphToKph(100.0), 1e-4);
    }

    @Test
    public void kphToMph_knownValues() {
        assertEquals(60.0, Units.kphToMph(96.56064), 1e-4);
        assertEquals(0.0, Units.kphToMph(0.0), 1e-9);
    }

    @Test
    public void mphToKph_roundTripStable() {
        // mph → kph → mph should be stable to many digits
        for (double mph : new double[] { 0, 5, 12.5, 30, 60, 100, 187.3 }) {
            double back = Units.kphToMph(Units.mphToKph(mph));
            assertEquals("round-trip mph→kph→mph at " + mph,
                    mph, back, 1e-9);
        }
    }

    @Test
    public void fToC_knownAnchors() {
        // Water freezes: 32 °F = 0 °C
        assertEquals(0.0, Units.fToC(32.0), 1e-9);
        // Water boils: 212 °F = 100 °C
        assertEquals(100.0, Units.fToC(212.0), 1e-9);
        // -40 is the only place F and C agree
        assertEquals(-40.0, Units.fToC(-40.0), 1e-9);
    }

    @Test
    public void cToF_knownAnchors() {
        assertEquals(32.0, Units.cToF(0.0), 1e-9);
        assertEquals(212.0, Units.cToF(100.0), 1e-9);
        assertEquals(-40.0, Units.cToF(-40.0), 1e-9);
    }

    @Test
    public void fToC_roundTripStable() {
        for (double f : new double[] { 0, 32, 70, 80, 100, 150, -10 }) {
            double back = Units.cToF(Units.fToC(f));
            assertEquals("round-trip F→C→F at " + f, f, back, 1e-9);
        }
    }

    // --- sign shape ----------------------------------------------------

    @Test
    public void signShape_imperialIsMutcd() {
        assertEquals(Units.SpeedSignShape.MUTCD,
                Units.signShape(UnitSystem.IMPERIAL));
    }

    @Test
    public void signShape_metricIsVienna() {
        assertEquals(Units.SpeedSignShape.VIENNA,
                Units.signShape(UnitSystem.METRIC));
    }

    @Test
    public void signWidthDp_imperial64() {
        assertEquals(64f, Units.signWidthDp(UnitSystem.IMPERIAL), 0.0f);
    }

    @Test
    public void signWidthDp_metric72() {
        assertEquals(72f, Units.signWidthDp(UnitSystem.METRIC), 0.0f);
    }

    @Test
    public void signHeightDp_imperial80() {
        // US MUTCD is taller than wide
        assertEquals(80f, Units.signHeightDp(UnitSystem.IMPERIAL), 0.0f);
    }

    @Test
    public void signHeightDp_metric72() {
        // EU Vienna is square
        assertEquals(72f, Units.signHeightDp(UnitSystem.METRIC), 0.0f);
    }

    // --- display unit selection (NaN passthrough) ---------------------

    @Test
    public void displaySpeedMph_imperialIsIdentity() {
        assertEquals(15.0, Units.displaySpeedMph(15.0, UnitSystem.IMPERIAL), 1e-9);
    }

    @Test
    public void displaySpeedMph_metricConverts() {
        assertEquals(Units.mphToKph(15.0),
                Units.displaySpeedMph(15.0, UnitSystem.METRIC), 1e-9);
    }

    @Test
    public void displaySpeedMph_nanPassthrough() {
        assertTrue(Double.isNaN(Units.displaySpeedMph(Double.NaN, UnitSystem.IMPERIAL)));
        assertTrue(Double.isNaN(Units.displaySpeedMph(Double.NaN, UnitSystem.METRIC)));
    }

    @Test
    public void displayTempF_imperialIsIdentity() {
        assertEquals(80.0, Units.displayTempF(80.0, UnitSystem.IMPERIAL), 1e-9);
    }

    @Test
    public void displayTempF_metricConverts() {
        // 80 °F ≈ 26.67 °C
        assertEquals(26.66666, Units.displayTempF(80.0, UnitSystem.METRIC), 1e-4);
    }

    @Test
    public void displayTempF_nanPassthrough() {
        assertTrue(Double.isNaN(Units.displayTempF(Double.NaN, UnitSystem.IMPERIAL)));
        assertTrue(Double.isNaN(Units.displayTempF(Double.NaN, UnitSystem.METRIC)));
    }

    // --- labels --------------------------------------------------------

    @Test
    public void speedLabel_imperialIsMph() {
        assertEquals("mph", Units.speedLabel(UnitSystem.IMPERIAL));
    }

    @Test
    public void speedLabel_metricIsKph() {
        assertEquals("kph", Units.speedLabel(UnitSystem.METRIC));
    }

    @Test
    public void tempLabel_imperial() {
        assertEquals("MOT\u00b0F", Units.tempLabel(UnitSystem.IMPERIAL, "MOT"));
        assertEquals("CTRL\u00b0F", Units.tempLabel(UnitSystem.IMPERIAL, "CTRL"));
    }

    @Test
    public void tempLabel_metric() {
        assertEquals("MOT\u00b0C", Units.tempLabel(UnitSystem.METRIC, "MOT"));
        assertEquals("CTRL\u00b0C", Units.tempLabel(UnitSystem.METRIC, "CTRL"));
    }

    // --- formatting ---------------------------------------------------

    @Test
    public void formatSpeedInteger_imperial() {
        assertEquals("0", Units.formatSpeedInteger(0.0, UnitSystem.IMPERIAL));
        assertEquals("15", Units.formatSpeedInteger(15.4, UnitSystem.IMPERIAL));
        assertEquals("16", Units.formatSpeedInteger(15.5, UnitSystem.IMPERIAL));  // banker's not used; Math.round rounds 0.5 up
        assertEquals("60", Units.formatSpeedInteger(60.0, UnitSystem.IMPERIAL));
    }

    @Test
    public void formatSpeedInteger_metric() {
        // 60 mph ≈ 96.56 kph, rounds to "97"
        assertEquals("97", Units.formatSpeedInteger(60.0, UnitSystem.METRIC));
        // 0 mph = 0 kph
        assertEquals("0", Units.formatSpeedInteger(0.0, UnitSystem.METRIC));
        // 1 mph ≈ 1.6 kph, rounds to "2"
        assertEquals("2", Units.formatSpeedInteger(1.0, UnitSystem.METRIC));
    }

    @Test
    public void formatSpeedInteger_nanBecomesEmDash() {
        assertEquals("\u2014", Units.formatSpeedInteger(Double.NaN, UnitSystem.IMPERIAL));
        assertEquals("\u2014", Units.formatSpeedInteger(Double.NaN, UnitSystem.METRIC));
    }

    @Test
    public void formatSpeedValue_imperial() {
        assertEquals("15.0", Units.formatSpeedValue(15.0, UnitSystem.IMPERIAL));
        assertEquals("0.0", Units.formatSpeedValue(0.0, UnitSystem.IMPERIAL));
    }

    @Test
    public void formatSpeedValue_metric() {
        // 15 mph ≈ 24.1 kph
        assertEquals("24.1", Units.formatSpeedValue(15.0, UnitSystem.METRIC));
    }

    @Test
    public void formatSpeedWithUnit_imperial() {
        assertEquals("0 mph", Units.formatSpeedWithUnit(0.0, UnitSystem.IMPERIAL));
        assertEquals("15 mph", Units.formatSpeedWithUnit(15.4, UnitSystem.IMPERIAL));
    }

    @Test
    public void formatSpeedWithUnit_metric() {
        assertEquals("0 kph", Units.formatSpeedWithUnit(0.0, UnitSystem.METRIC));
        // 15 mph ≈ 24 kph (rounded to integer in TalkBack-friendly form)
        assertEquals("24 kph", Units.formatSpeedWithUnit(15.0, UnitSystem.METRIC));
    }

    @Test
    public void formatTempValue_imperial() {
        assertEquals("80", Units.formatTempValue(80.0, UnitSystem.IMPERIAL));
        assertEquals("32", Units.formatTempValue(32.0, UnitSystem.IMPERIAL));
    }

    @Test
    public void formatTempValue_metric() {
        // 80 °F ≈ 27 °C
        assertEquals("27", Units.formatTempValue(80.0, UnitSystem.METRIC));
        // 32 °F = 0 °C
        assertEquals("0", Units.formatTempValue(32.0, UnitSystem.METRIC));
    }

    @Test
    public void formatTempValue_nanBecomesEmDash() {
        assertEquals("\u2014", Units.formatTempValue(Double.NaN, UnitSystem.IMPERIAL));
        assertEquals("\u2014", Units.formatTempValue(Double.NaN, UnitSystem.METRIC));
    }
}
