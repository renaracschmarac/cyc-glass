package com.cycglass.monitor;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.osmdroid.util.GeoPoint;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground-only wrapper around {@link FusedLocationProviderClient}
 * that drives the {@link MapBackgroundView}.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>1 Hz updates with a 5 m displacement threshold. Bikes move
 *       slowly; a 1 m wakeup would be wasteful.</li>
 *   <li>Start in {@link #start()}, stop in {@link #stop()}. Always
 *       pair the two. {@link #stop()} is idempotent.</li>
 *   <li>Reports three states via {@link Listener}: searching, fix
 *       acquired, permission denied. The MainActivity composes the
 *       status line from these states per the plan's Q5 rule.</li>
 *   <li>Saves each fix to {@code SharedPreferences} so the next
 *       cold start has a center to use.</li>
 * </ul>
 *
 * <p>See {@code docs/2026-06-01-map-background-plan.md}.
 */
public final class LocationProvider {

    public interface Listener {
        /**
         * Called on the main looper whenever a new fix arrives.
         * {@code point} is never null.
         *
         * @param point            the new position
         * @param fixMs            system time of the fix
         * @param metersPerSecond  the fix's ground speed in m/s, or
         *                         0 if the location has no speed
         *                         (callers should check the
         *                         underlying Location.hasSpeed() to
         *                         decide whether to trust it)
         */
        void onFix(GeoPoint point, long fixMs, float metersPerSecond);

        /**
         * Called on the main looper when the permission state is known.
         * @param granted true if we hold ACCESS_FINE_LOCATION
         */
        void onPermissionResult(boolean granted);
    }

    private static final String TAG = "LocationProvider";

    /** 1 Hz, 5 m displacement. */
    private static final long INTERVAL_MS = 1000L;
    private static final long FASTEST_INTERVAL_MS = 500L;
    private static final float DISPLACEMENT_M = 5.0f;

    private final Context appContext;
    private final FusedLocationProviderClient fused;
    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();

    @Nullable private GeoPoint lastFix;
    private long lastFixMs;
    private boolean started;

    private final LocationCallback callback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult result) {
            Location loc = result.getLastLocation();
            if (loc == null) return;
            GeoPoint point = new GeoPoint(loc.getLatitude(), loc.getLongitude());
            long ms = loc.getTime();
            if (ms <= 0) ms = System.currentTimeMillis();
            float mps = loc.hasSpeed() ? loc.getSpeed() : 0.0f;
            lastFix = point;
            lastFixMs = ms;
            Log.d(TAG, "fix lat=" + loc.getLatitude()
                    + " lon=" + loc.getLongitude()
                    + " acc=" + loc.getAccuracy() + "m"
                    + " speed=" + mps + "m/s");
            for (Listener l : listeners) l.onFix(point, ms, mps);
        }
    };

    public LocationProvider(Context context) {
        this.appContext = context.getApplicationContext();
        this.fused = LocationServices.getFusedLocationProviderClient(appContext);
    }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public boolean isSearching() {
        return lastFix == null;
    }

    public boolean hasFix() {
        return lastFix != null;
    }

    @Nullable
    public GeoPoint lastFix() {
        return lastFix;
    }

    public long lastFixMs() {
        return lastFixMs;
    }

    public boolean hasFineLocationPermission() {
        return ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Starts 1 Hz location updates. Idempotent. No-op if the
     * permission hasn't been granted yet (callers should check
     * {@link #hasFineLocationPermission()} first or use
     * {@link #onPermissionResult(boolean)}).
     */
    public void start() {
        if (started) return;
        if (!hasFineLocationPermission()) {
            // The caller is expected to request permission and call
            // start() again from the permission callback. Don't
            // notify permission-denied here — the caller knows.
            return;
        }
        LocationRequest req = new LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
                .setMinUpdateDistanceMeters(DISPLACEMENT_M)
                .setWaitForAccurateLocation(false)
                .build();
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
            started = true;
        } catch (SecurityException e) {
            // Race: permission revoked between our check and the call.
            Log.w(TAG, "start: SecurityException, treating as denied", e);
            for (Listener l : listeners) l.onPermissionResult(false);
        }
    }

    public void stop() {
        if (!started) return;
        try {
            fused.removeLocationUpdates(callback);
        } catch (SecurityException e) {
            Log.w(TAG, "stop: SecurityException (ignored)", e);
        }
        started = false;
    }

    /**
     * Should be called from the activity's
     * {@code onRequestPermissionsResult}. Re-starts the provider if
     * permission is now granted; notifies listeners either way.
     */
    public void onPermissionResult(boolean granted) {
        for (Listener l : listeners) l.onPermissionResult(granted);
        if (granted) start();
    }
}
