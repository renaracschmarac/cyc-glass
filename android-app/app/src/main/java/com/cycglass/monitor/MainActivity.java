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
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity implements BmsClient.Host, CycClient.Host {

    private static final int REQUEST_BLUETOOTH = 100;
    private static final String SETTINGS_NAME = "display_settings";
    private static final String KEY_AMPS_OUT = "amps_out";
    private static final String KEY_AMPS_IN = "amps_in";
    private static final float DEFAULT_AMPS_OUT = 100.0f;
    private static final float DEFAULT_AMPS_IN = 20.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DataModel model = new DataModel();
    private final Runnable refreshView = new Runnable() {
        @Override public void run() {
            if (view != null) view.refresh();
            handler.postDelayed(this, 100);  // 10 Hz redraw to stay smooth
        }
    };

    private SharedPreferences preferences;
    private GlassView view;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BmsClient bmsClient;
    private CycClient cycClient;
    private CycLayout layout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE);
        float ampsOut = Math.abs(preferences.getFloat(KEY_AMPS_OUT, DEFAULT_AMPS_OUT));
        float ampsIn = Math.abs(preferences.getFloat(KEY_AMPS_IN, DEFAULT_AMPS_IN));

        try {
            layout = CycClient.loadLayoutFromAssets(this);
        } catch (IOException e) {
            layout = null;
        }

        view = new GlassView(this, model, ampsOut, ampsIn);
        setContentView(createContentView());
        hideSystemUi();

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

        startWhenPermitted();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshView);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshView);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshView);
        if (bmsClient != null) bmsClient.stop();
        if (cycClient != null) cycClient.stop();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH && allGranted(grantResults)) {
            startClients();
        } else if (requestCode == REQUEST_BLUETOOTH) {
            view.setStatus("Bluetooth permission required");
        }
    }

    private View createContentView() {
        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // SETTINGS lives in the band's top-right corner, well clear of the
        // perimeter rows. Its background is semi-transparent so the
        // underlying band color shows through.
        Button settings = new Button(this);
        settings.setText("SETTINGS");
        settings.setTextColor(Color.WHITE);
        settings.setBackgroundColor(Color.argb(0x88, 0x00, 0x00, 0x00));
        settings.setOnClickListener(v -> showCurrentSettings());
        int bandTopPx = dp(70);  // sit just below the top perimeter row
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.setMargins(dp(12), bandTopPx, dp(12), 0);
        root.addView(settings, params);
        return root;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_BLUETOOTH);
                return;
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLUETOOTH);
            return;
        }
        startClients();
    }

    private void startClients() {
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
            if (view != null) view.setStatus(status);
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
