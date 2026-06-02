package com.cycglass.monitor;

import java.util.Locale;

/**
 * Pure-Java unit conversion and formatting helpers used to switch
 * the app between Imperial (mph, °F) and Metric (kph, °C) display.
 *
 * <p>Pure Java so the conversion and formatting logic is unit-testable
 * without an Android device. The display layer ({@link GlassView},
 * {@link MainActivity}) reads the chosen {@link UnitSystem} from
 * {@link DataModel} and routes every mph / °F value through these
 * helpers before drawing or speaking it.
 *
 * <p>Conversions are exact within the limits of {@code double}:
 * 1 mph = 1.609344 kph (NIST, defined); F to C is affine.
 */
public final class Units {

    /** Exact NIST conversion factor: 1 mph = 1.609344 kph. */
    public static final double KPH_PER_MPH = 1.609344;

    /** Speed sign geometry, picked by the unit system:
     *  Imperial = US MUTCD rectangle, Metric = EU Vienna circle. */
    public enum SpeedSignShape { MUTCD, VIENNA }

    private Units() { /* utility class */ }

    // --- sign shape (visual cue mirrors the unit system) ------------------

    /** Picks the speed-sign shape that matches the unit system:
     *  Imperial → US MUTCD, Metric → EU Vienna. */
    public static SpeedSignShape signShape(UnitSystem system) {
        return system == UnitSystem.IMPERIAL
                ? SpeedSignShape.MUTCD
                : SpeedSignShape.VIENNA;
    }

    /** Width of the sign's bounding box in dp for the given unit
     *  system. The two shapes have intentionally different aspect
     *  ratios (US: 64×80 tall, EU: 72×72 square). */
    public static float signWidthDp(UnitSystem system) {
        return system == UnitSystem.IMPERIAL ? 64f : 72f;
    }

    /** Height of the sign's bounding box in dp for the given unit
     *  system. */
    public static float signHeightDp(UnitSystem system) {
        return system == UnitSystem.IMPERIAL ? 80f : 72f;
    }

    // --- canonical unit (mph, °F) ↔ display unit -------------------------

    /** {@code mph} in the canonical storage unit, converted to the
     *  display unit the user picked. */
    public static double displaySpeedMph(double mph, UnitSystem system) {
        if (Double.isNaN(mph)) return Double.NaN;
        return system == UnitSystem.METRIC ? mphToKph(mph) : mph;
    }

    /** {@code fahrenheit} in the canonical storage unit, converted
     *  to the display unit the user picked. */
    public static double displayTempF(double f, UnitSystem system) {
        if (Double.isNaN(f)) return Double.NaN;
        return system == UnitSystem.METRIC ? fToC(f) : f;
    }

    // --- conversions (round-trip stable) --------------------------------

    /** mph to kph. */
    public static double mphToKph(double mph) {
        return mph * KPH_PER_MPH;
    }

    /** kph to mph. */
    public static double kphToMph(double kph) {
        return kph / KPH_PER_MPH;
    }

    /** Fahrenheit to Celsius. */
    public static double fToC(double f) {
        return (f - 32.0) * 5.0 / 9.0;
    }

    /** Celsius to Fahrenheit. */
    public static double cToF(double c) {
        return c * 9.0 / 5.0 + 32.0;
    }

    // --- labels (perimeter cells) ---------------------------------------

    /** "mph" or "kph" depending on the unit system. */
    public static String speedLabel(UnitSystem system) {
        return system == UnitSystem.METRIC ? "kph" : "mph";
    }

    /** "MOT°F" / "MOT°C" or "CTRL°F" / "CTRL°C" — the per-row
     *  temperature cell label for the given cell prefix. */
    public static String tempLabel(UnitSystem system, String prefix) {
        return prefix + (system == UnitSystem.METRIC ? "\u00b0C" : "\u00b0F");
    }

    // --- formatting ----------------------------------------------------

    /** Integer in the display unit, no unit label. Used for the
     *  speed sign (US MUTCD / EU Vienna conventions both omit the
     *  unit on the sign). */
    public static String formatSpeedInteger(double mph, UnitSystem system) {
        if (Double.isNaN(mph)) return "\u2014";
        return Integer.toString((int) Math.round(displaySpeedMph(mph, system)));
    }

    /** Numeric speed value (no unit) for the perimeter cell.
     *  One decimal place — matches the "%.1f" pattern the cell
     *  already uses for the Imperial mph value. */
    public static String formatSpeedValue(double mph, UnitSystem system) {
        if (Double.isNaN(mph)) return "\u2014";
        return String.format(Locale.US, "%.1f", displaySpeedMph(mph, system));
    }

    /** Speed value with unit, e.g. "0 mph" or "0 kph" — for the
     *  accessibility (TalkBack) content description. */
    public static String formatSpeedWithUnit(double mph, UnitSystem system) {
        if (Double.isNaN(mph)) return "\u2014";
        return String.format(Locale.US, "%.0f %s",
                displaySpeedMph(mph, system), speedLabel(system));
    }

    /** Integer temperature in the display unit, no unit label. */
    public static String formatTempValue(double f, UnitSystem system) {
        if (Double.isNaN(f)) return "\u2014";
        return String.format(Locale.US, "%.0f", displayTempF(f, system));
    }
}
