package com.cycglass.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.util.GeoPoint;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity implements BmsClient.Host, CycClient.Host {

    private static final int REQUEST_BLUETOOTH = 100;
    private static final int REQUEST_LOCATION = 101;
    private static final String SETTINGS_NAME = "display_settings";
    private static final String KEY_AMPS_OUT = "amps_out";
    private static final String KEY_AMPS_IN = "amps_in";
    private static final String KEY_LAST_KNOWN_LAT = "last_known_lat";
    private static final String KEY_LAST_KNOWN_LON = "last_known_lon";
    private static final String KEY_LAST_KNOWN_FIX_MS = "last_known_fix_ms";
    private static final float DEFAULT_AMPS_OUT = 100.0f;
    private static final float DEFAULT_AMPS_IN = 20.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DataModel model = new DataModel();
    private final Runnable refreshView = new Runnable() {
        @Override public void run() {
            if (view != null) view.refresh();
            if (mapView != null) composeStatusLine();
            handler.postDelayed(this, 100);  // 10 Hz redraw to stay smooth
        }
    };

    private SharedPreferences preferences;
    private GlassView view;
    private MapBackgroundView mapView;
    private LocationProvider locationProvider;
    private String lastBaseStatus = "Starting";
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BmsClient bmsClient;
    private CycClient cycClient;
    private CycLayout layout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // osmdroid global configuration. The UA must be set BEFORE
        // any MapView is inflated. Per OSM's tile usage policy, the UA
        // identifies the app and includes a contact. The value here is
        // a default that satisfies the policy; the MapTileSource UA is
        // also set (the MapTileSource owns the ITileSource, so its UA
        // is what OSM actually sees in the tile fetch headers, but we
        // still set the global one for osmdroid's internal logging and
        // any auxiliary requests).
        Configuration.getInstance().load(
                getApplicationContext(),
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
        Configuration.getInstance().setUserAgentValue(
                "cyc-glass/" + BuildConfig.VERSION_NAME
                        + " (+https://github.com/renaracschmarac/cyc-glass)");

        // Build the tile source and apply its UA to the global
        // configuration as well, since the same value is what
        // osmdroid's tile fetcher will use.
        MapTileSource tileSource;
        try {
            tileSource = MapTileSource.fromBuildConfig();
            Configuration.getInstance().setUserAgentValue(tileSource.userAgent());
        } catch (IllegalStateException e) {
            // Misconfigured build (e.g. STADIA without a key). Surface
            // the error on the status line and continue with a black
            // background.
            tileSource = null;
            lastBaseStatus = "Map misconfigured: " + e.getMessage();
        }

        preferences = getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE);
        float ampsOut = Math.abs(preferences.getFloat(KEY_AMPS_OUT, DEFAULT_AMPS_OUT));
        float ampsIn = Math.abs(preferences.getFloat(KEY_AMPS_IN, DEFAULT_AMPS_IN));

        try {
            layout = CycClient.loadLayoutFromAssets(this);
        } catch (IOException e) {
            layout = null;
        }

        view = new GlassView(this, model, ampsOut, ampsIn);

        // Map is created in createContentView() and lives BEHIND the
        // GlassView in the FrameLayout. Set its tile source before
        // attaching to the layout.
        mapView = new MapBackgroundView(this);
        if (tileSource != null) {
            mapView.setTileSource(tileSource.tileSource());
        }
        // Reasonable default cap; the plan calls for 600 MB.
        Configuration.getInstance().setTileFileSystemCacheMaxBytes(
                600L * 1024L * 1024L);
        mapView.setHorizontalMapRepetitionEnabled(false);
        mapView.setVerticalMapRepetitionEnabled(false);

        setContentView(createContentView());
        hideSystemUi();

        // Seed the map from the last-known SharedPreferences entry.
        float savedLat = preferences.getFloat(KEY_LAST_KNOWN_LAT, Float.NaN);
        float savedLon = preferences.getFloat(KEY_LAST_KNOWN_LON, Float.NaN);
        long savedFixMs = preferences.getLong(KEY_LAST_KNOWN_FIX_MS, 0L);
        if (!Float.isNaN(savedLat) && !Float.isNaN(savedLon)) {
            model.setLastKnownLocation(savedLat, savedLon, savedFixMs);
            mapView.recenterTo(new GeoPoint(savedLat, savedLon));
        } else {
            model.clearLastKnownLocation();
            // No recenter → mapView stays in the no-center state and
            // renders a solid black background.
        }

        bmsClient = new BmsClient(this, handler, model, preferences,
                Math.max(50, Math.min(10000,
                        getIntent().getIntExtra("poll_interval_ms",
                                BmsClient.DEFAULT_POLL_INTERVAL_MS))));
        // Stagger the motor poll by 100 ms so both GATTs don't wake the
        // radio at exactly the same instant.
        int motorInterval = Math.max(50, Math.min(10000,
                getIntent().getIntExtra("motor_poll_interval_ms",
                        CycClient.DEFAULT_POLL_INTERVAL_MS)));
        if (layout != null) {
            cycClient = new CycClient(this, handler, model, preferences, motorInterval, layout);
        }

        // Location provider. We start it after permissions are
        // granted; see startWhenPermitted.
        locationProvider = new LocationProvider(this);
        locationProvider.addListener(new LocationProvider.Listener() {
            @Override public void onFix(GeoPoint point, long fixMs) {
                model.setLastKnownLocation(point.getLatitude(), point.getLongitude(), fixMs);
                preferences.edit()
                        .putFloat(KEY_LAST_KNOWN_LAT, (float) point.getLatitude())
                        .putFloat(KEY_LAST_KNOWN_LON, (float) point.getLongitude())
                        .putLong(KEY_LAST_KNOWN_FIX_MS, fixMs)
                        .apply();
                if (mapView != null) mapView.recenterTo(point);
                composeStatusLine();
            }
            @Override public void onPermissionResult(boolean granted) {
                composeStatusLine();
            }
        });

        startWhenPermitted();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshView);
        if (locationProvider != null
                && locationProvider.hasFineLocationPermission()) {
            locationProvider.start();
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshView);
        if (locationProvider != null) locationProvider.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshView);
        if (bmsClient != null) bmsClient.stop();
        if (cycClient != null) cycClient.stop();
        if (locationProvider != null) locationProvider.stop();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH) {
            if (allGranted(grantResults)) {
                startBleClients();
            } else {
                view.setStatus("Bluetooth permission required");
            }
        } else if (requestCode == REQUEST_LOCATION) {
            boolean granted = allGranted(grantResults);
            if (locationProvider != null) {
                locationProvider.onPermissionResult(granted);
            }
            if (!granted) {
                // Per the plan, the map stays at the last-known
                // SharedPreferences center. The status line is
                // composed by composeStatusLine().
                composeStatusLine();
            }
        }
    }

    private View createContentView() {
        FrameLayout root = new FrameLayout(this);
        // Map FIRST (drawn first → at the back).
        FrameLayout.LayoutParams mapParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(mapView, mapParams);
        // GlassView SECOND (drawn on top of the map).
        FrameLayout.LayoutParams glassParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(view, glassParams);
        // The settings gear is drawn directly inside the GlassView's
        // own onDraw (see GlassView.drawSettingsIcon), so a sibling
        // ImageButton isn't needed — the previous sibling-View path
        // failed to render the icon content on this device. Tap
        // detection is handled by GlassView.onTouchEvent, which calls
        // back into showCurrentSettings() via the listener set below.
        view.setOnSettingsTapListener(() -> showCurrentSettings());
        return root;
    }

    /**
     * Composes the status line by prefixing the GPS state (if
     * searching) to whatever the BLE clients last set. Implements
     * the Q5 rule: only show "Searching GPS…" while searching, no
     * sat-lock info once we have a fix.
     */
    private void composeStatusLine() {
        if (view == null) return;
        String gpsPart = null;
        if (locationProvider != null) {
            if (!locationProvider.hasFineLocationPermission()) {
                gpsPart = "GPS off (no permission)";
            } else if (locationProvider.isSearching()) {
                gpsPart = "Searching GPS…";
            }
        }
        String composed;
        if (gpsPart == null) {
            composed = lastBaseStatus;
        } else {
            composed = gpsPart + " · " + lastBaseStatus;
        }
        view.setStatus(composed);
    }

    private void showCurrentSettings() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        fields.setPadding(padding, dp(8), padding, 0);

        EditText ampsOut = currentField("Amps OUT (-)", view.getAmpsOut());
        EditText ampsIn = currentField("Amps IN (+)", view.getAmpsIn());
        fields.addView(labeledField("Bright red at Amps OUT (-)", ampsOut));
        fields.addView(labeledField("Bright red at Amps IN (+)", ampsIn));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Current Color Scale")
                .setMessage("The Current band is green at 0 A, yellow at half scale, and red at either limit.")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Re-scan for battery", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(d -> {
            AlertDialog ad = (AlertDialog) d;
            ad.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b -> {
                ad.dismiss();
                rescanAll();
            });
            ad.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                try {
                    float out = Float.parseFloat(ampsOut.getText().toString().trim());
                    float in = Float.parseFloat(ampsIn.getText().toString().trim());
                    if (out <= 0.0f || in <= 0.0f) throw new NumberFormatException();
                    preferences.edit()
                            .putFloat(KEY_AMPS_OUT, out)
                            .putFloat(KEY_AMPS_IN, in)
                            .apply();
                    view.setCurrentScale(out, in);
                    ad.dismiss();
                } catch (NumberFormatException error) {
                    Toast.makeText(this,
                            "Enter positive magnitudes for OUT and IN.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
        dialog.show();
    }

    private void rescanAll() {
        if (bmsClient != null) bmsClient.rescan();
        if (cycClient != null) cycClient.rescan();
    }

    private EditText currentField(String hint, float value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(String.format(Locale.US, "%.1f", value));
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return input;
    }

    private View labeledField(String label, EditText input) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(this);
        text.setText(label);
        group.addView(text);
        group.addView(input);
        return group;
    }

    private void startWhenPermitted() {
        // Step 1: Bluetooth. On API 31+ that's BLUETOOTH_SCAN/CONNECT;
        // on older releases it's ACCESS_FINE_LOCATION (the historical
        // BLE-scan permission).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_BLUETOOTH);
            } else {
                startBleClients();
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Pre-S: a single ACCESS_FINE_LOCATION request covered
                // both BLE scan and location. We ask once and let the
                // user grant both.
                requestPermissions(
                        new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_BLUETOOTH);
            } else {
                startBleClients();
                // Same permission covers map GPS too; the LocationProvider
                // will start via the permission-already-granted branch
                // when onResume fires.
            }
        }

        // Step 2: Location. On API 23+ ACCESS_FINE_LOCATION is
        // runtime; request it for the map regardless of BLE state.
        // On API 31+ it's separate from BLUETOOTH_*; the
        // pre-S single-permission path above already covered it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] {
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        REQUEST_LOCATION);
            } else {
                if (locationProvider != null) locationProvider.onPermissionResult(true);
            }
        } else {
            // Pre-S: ACCESS_FINE_LOCATION was already requested above
            // (under the BLE permission code path). If the user
            // granted it for BLE, we already started the BLE clients;
            // the locationProvider will pick it up on onResume.
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                if (locationProvider != null) locationProvider.onPermissionResult(true);
            }
        }
    }

    private void startBleClients() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            view.setStatus("Turn Bluetooth on");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (bmsClient != null) bmsClient.start();
        if (cycClient != null) cycClient.start();
    }

    // ----- BmsClient.Host + CycClient.Host -----

    @Override public BluetoothAdapter adapter() { return adapter; }

    @Override public BluetoothLeScanner scanner() { return scanner; }

    @Override
    public void setStatus(String status) {
        // Run on UI thread; both clients may invoke this from a callback.
        handler.post(() -> {
            lastBaseStatus = status;
            composeStatusLine();
        });
    }

    @Override
    public void showDevicePicker(List<String> labels, IntConsumer onPick, Runnable onCancel) {
        handler.post(() -> {
            String[] arr = labels.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle("Select Device")
                    .setItems(arr, (d, which) -> onPick.accept(which))
                    .setNegativeButton("Cancel", (d, w) -> onCancel.run())
                    .show();
        });
    }

    @Override
    public void requestBluetoothPermission() {
        startWhenPermitted();
    }

    private boolean allGranted(int[] results) {
        if (results.length == 0) return false;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
