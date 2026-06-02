package com.cycglass.monitor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground-only wrapper around {@link SensorManager}'s rotation
 * vector that publishes a smoothed, tilt-gated heading in degrees
 * from magnetic north.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>Uses {@link Sensor#TYPE_ROTATION_VECTOR} (the OS-fused
 *       quaternion from magnetometer + accelerometer + gyroscope
 *       when present).</li>
 *   <li>Roughly 50 Hz updates via
 *       {@link SensorManager#SENSOR_DELAY_GAME}.</li>
 *   <li>Filters samples through {@link HeadingFilter}: tilt gate
 *       (60° pitch, 60° roll) and 100 ms low-pass.</li>
 *   <li>Reports the result via {@link Listener#onHeading(float, long)}.</li>
 *   <li>Lifecycle: pair {@link #start()} with {@link #stop()}; both
 *       are idempotent. If the device has no rotation vector
 *       sensor, both are no-ops and {@link #isAvailable()} returns
 *       false.</li>
 * </ul>
 *
 * <p>No runtime permission is required for sensors on any
 * supported API.
 *
 * <p>See {@code docs/2026-06-02-heading-up-and-speed-sign-plan.md}.
 */
public final class OrientationProvider {

    public interface Listener {
        /**
         * Called on the main looper whenever a new filtered heading
         * is available. The angle is in degrees from magnetic north,
         * normalized to [0, 360). The timestamp is the system time
         * when the underlying sensor sample arrived.
         */
        void onHeading(float degreesFromNorth, long timestampMs);
    }

    private static final String TAG = "OrientationProvider";

    private final Context appContext;
    @Nullable private final SensorManager sensorManager;
    @Nullable private final Sensor rotationVector;
    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();
    private final HeadingFilter filter = new HeadingFilter();

    private final float[] rotationMatrix = new float[9];
    private final float[] adjustedRotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    private boolean started;
    private long lastHeadingMs = 0L;

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            remapForCurrentDisplay();
            SensorManager.getOrientation(adjustedRotationMatrix, orientation);
            float azimuthDeg = (float) Math.toDegrees(orientation[0]);
            float pitchDeg = (float) Math.toDegrees(orientation[1]);
            float rollDeg = (float) Math.toDegrees(orientation[2]);

            long nowMs = System.currentTimeMillis();
            float filtered = filter.processSample(azimuthDeg, pitchDeg, rollDeg, nowMs);
            if (Float.isNaN(filtered)) return;
            lastHeadingMs = nowMs;
            for (Listener l : listeners) l.onHeading(filtered, nowMs);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // No-op for v1. A future change could lower the filter
            // strength (longer time constant) when accuracy is poor
            // (e.g. nearby metal).
        }
    };

    public OrientationProvider(Context context) {
        // Tolerate a null context (e.g. in JVM unit tests) by
        // storing it as-is. The only path that dereferences
        // appContext is remapForCurrentDisplay, which is guarded
        // by a null check. start() also no-ops cleanly.
        this.appContext = context != null
                ? context.getApplicationContext() : null;
        SensorManager sm = (appContext != null)
                ? (SensorManager) appContext.getSystemService(
                        Context.SENSOR_SERVICE)
                : null;
        this.sensorManager = sm;
        Sensor sensor = (sm != null)
                ? sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                : null;
        this.rotationVector = sensor;
    }

    /** True iff the device exposes a rotation vector sensor. */
    public boolean isAvailable() {
        return rotationVector != null;
    }

    /** True iff the sensor listener is currently registered. */
    public boolean isStarted() {
        return started;
    }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** System time (ms) of the most recent published heading, or 0
     * if no heading has been published yet. */
    public long lastHeadingMs() {
        return lastHeadingMs;
    }

    /**
     * Registers the sensor listener. Idempotent. No-op if the
     * device has no rotation vector sensor (callers should check
     * {@link #isAvailable()} first or just call start anyway and
     * rely on the no-op behavior).
     */
    public void start() {
        if (started) return;
        if (sensorManager == null || rotationVector == null) {
            Log.w(TAG, "start: rotation vector sensor unavailable");
            return;
        }
        sensorManager.registerListener(
                sensorListener, rotationVector, SensorManager.SENSOR_DELAY_GAME);
        started = true;
    }

    /**
     * Unregisters the sensor listener. Idempotent. Safe to call
     * from {@code onPause} without first checking {@link #isStarted()}.
     */
    public void stop() {
        if (!started) return;
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorListener);
        }
        started = false;
    }

    /**
     * Remaps the sensor rotation matrix from the device's natural
     * sensor orientation into the current display orientation.
     * The activity is locked to portrait (see
     * {@code AndroidManifest.xml}), so in practice the rotation is
     * always {@link Surface#ROTATION_0} and this resolves to a
     * no-op remap. The remap is kept for robustness on devices
     * whose natural sensor orientation differs from the display's
     * natural orientation.
     */
    private void remapForCurrentDisplay() {
        int rotation = Surface.ROTATION_0;
        if (appContext != null) {
            WindowManager wm = (WindowManager)
                    appContext.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                rotation = wm.getDefaultDisplay().getRotation();
            }
        }
        int axisX, axisY;
        switch (rotation) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            case Surface.ROTATION_0:
            default:
                axisX = SensorManager.AXIS_X;
                axisY = SensorManager.AXIS_Y;
                break;
        }
        SensorManager.remapCoordinateSystem(
                rotationMatrix, axisX, axisY, adjustedRotationMatrix);
    }
}
