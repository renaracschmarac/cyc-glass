package com.cycglass.monitor;

/**
 * Thread-safe state container shared by the BLE clients and the view.
 *
 * <p>Two threads are producers: the BMS GATT callback (writes pack metrics)
 * and the CYC GATT callback (writes motor metrics). The view is the consumer
 * and reads on the UI thread inside {@code onDraw}.
 *
 * <p>Each setter is synchronized on the field triple so that the three
 * related numbers update atomically. This avoids the visual artifact of, for
 * example, a new {@code amps} value being drawn against a stale
 * {@code voltage} value when the UI thread snapshots them in two steps.
 */
public final class DataModel {

    // Battery metrics from the Daly BMS. Defaults render as "—".
    private double bmsVoltage = Double.NaN;
    private double bmsCurrent = Double.NaN;
    private double bmsRemaining = Double.NaN;

    // Motor metrics decoded from the CYC VESC COMM_GET_VALUES response.
    // Layout fields are pre-resolved at startup so the per-frame cost is just
    // a few array reads.
    private int assistLevel = Integer.MIN_VALUE;
    private double speedMph = Double.NaN;
    private double motorTempF = Double.NaN;
    private double controllerTempF = Double.NaN;
    private double humanPowerW = Double.NaN;
    private double motorPowerW = Double.NaN;

    // Last GPS fix used to seed the map on cold start. NaN means
    // "no fix ever recorded" — the map renders a solid black
    // background in that case. See
    // docs/2026-06-01-map-background-plan.md.
    private double lastKnownLat = Double.NaN;
    private double lastKnownLon = Double.NaN;
    private long lastKnownFixMs = 0L;

    // Latest GPS-derived ground speed in mph. NaN means "no fix
    // has reported speed yet" — the speed-sign overlay shows
    // "—" in that case. See
    // docs/2026-06-02-heading-up-and-speed-sign-plan.md.
    private double gpsSpeedMph = Double.NaN;

    // Display unit system chosen in the settings dialog. Default
    // Imperial preserves the existing mph/°F behavior. The model
    // itself stores speeds in mph and temperatures in °F
    // (canonical); the display layer (GlassView) routes every
    // rendered value through Units.displaySpeedMph() and
    // Units.displayTempF() before drawing.
    private UnitSystem unitSystem = UnitSystem.IMPERIAL;

    /** Meters per second to miles per hour. Exact constant from
     * NIST: 1 m/s = 1/0.44704 mph. */
    public static double mpsToMph(double metersPerSecond) {
        return metersPerSecond * 2.2369362920544;
    }

    /** Kilometers per hour to miles per hour. Exact constant
     * from NIST: 1 km/h = 1/1.609344 mph. */
    public static double kphToMph(double kilometersPerHour) {
        return kilometersPerHour * 0.62137119223733;
    }

    public synchronized void setBmsMetrics(double voltage, double current, double remaining) {
        this.bmsVoltage = voltage;
        this.bmsCurrent = current;
        this.bmsRemaining = remaining;
    }

    public synchronized void setMotorMetrics(int assistLevel, double speedMph,
                                             double motorTempF, double controllerTempF,
                                             double humanPowerW, double motorPowerW) {
        this.assistLevel = assistLevel;
        this.speedMph = speedMph;
        this.motorTempF = motorTempF;
        this.controllerTempF = controllerTempF;
        this.humanPowerW = humanPowerW;
        this.motorPowerW = motorPowerW;
    }

    /**
     * Records a new GPS fix. Called from {@link LocationProvider} on
     * every Fused Location callback. Always overwrites — we only
     * keep the latest.
     */
    public synchronized void setLastKnownLocation(double lat, double lon, long fixMs) {
        this.lastKnownLat = lat;
        this.lastKnownLon = lon;
        this.lastKnownFixMs = fixMs;
    }

    /** Clears the last-known location (e.g. after a re-scan). */
    public synchronized void clearLastKnownLocation() {
        this.lastKnownLat = Double.NaN;
        this.lastKnownLon = Double.NaN;
        this.lastKnownFixMs = 0L;
    }

    public synchronized double lastKnownLat() { return lastKnownLat; }
    public synchronized double lastKnownLon() { return lastKnownLon; }
    public synchronized long lastKnownFixMs() { return lastKnownFixMs; }
    public synchronized boolean hasLastKnownLocation() {
        return !Double.isNaN(lastKnownLat) && !Double.isNaN(lastKnownLon);
    }

    /** Records the latest GPS-derived ground speed in mph. NaN is
     * a valid input (e.g. the first fix had no Doppler speed, or
     * the provider is being torn down) and means "no speed known
     * right now — show the dash placeholder." */
    public synchronized void setGpsSpeedMph(double mph) {
        this.gpsSpeedMph = mph;
    }

    /** Latest GPS speed in mph, or NaN if no fix has reported it. */
    public synchronized double gpsSpeedMph() { return gpsSpeedMph; }

    /**
     * Returns the display unit system chosen in the settings dialog.
     * The model itself stores canonical units (mph, °F); the display
     * layer converts to this system's unit before drawing.
     */
    public synchronized UnitSystem unitSystem() { return unitSystem; }

    /**
     * Updates the display unit system. The change takes effect on the
     * next {@link GlassView#refresh()} — callers should call
     * {@code view.refresh()} (or {@code view.setUnitSystem(...)}
     * which wraps it) immediately after to redraw with the new unit.
     */
    public synchronized void setUnitSystem(UnitSystem system) {
        this.unitSystem = system;
    }

    public synchronized double bmsVoltage() { return bmsVoltage; }
    public synchronized double bmsCurrent() { return bmsCurrent; }
    public synchronized double bmsRemaining() { return bmsRemaining; }
    public synchronized int assistLevel() { return assistLevel; }
    public synchronized double speedMph() { return speedMph; }
    public synchronized double motorTempF() { return motorTempF; }
    public synchronized double controllerTempF() { return controllerTempF; }
    public synchronized double humanPowerW() { return humanPowerW; }
    public synchronized double motorPowerW() { return motorPowerW; }
}
