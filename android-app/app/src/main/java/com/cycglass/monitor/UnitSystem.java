package com.cycglass.monitor;

/**
 * Display unit system chosen by the user in the settings dialog.
 * Persists across app restarts; default is {@link #IMPERIAL} to
 * match the existing behavior (mph, °F).
 *
 * <p>The display layer reads this from {@link DataModel} and routes
 * mph / °F values through {@link Units#displaySpeedMph} and
 * {@link Units#displayTempF} before drawing, and the speed-sign
 * shape (US MUTCD vs EU Vienna) is also picked from this value.
 */
public enum UnitSystem {
    /** mph, °F, US MUTCD rectangle sign. */
    IMPERIAL,
    /** kph, °C, EU Vienna circle sign. */
    METRIC
}
