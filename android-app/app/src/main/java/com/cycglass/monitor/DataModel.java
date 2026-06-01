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
