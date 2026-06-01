package com.cycglass.monitor;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.ParcelUuid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * GATT client for the CYC X1 Pro Gen4 motor controller over Nordic UART
 * Service. The transport is the standard NUS pattern: write to
 * {@code 6e400002-…} (handle {@code 0x000d}) and receive notifications from
 * {@code 6e400003-…} (handle {@code 0x000f}). The application payload is a
 * VESC short packet carrying {@code COMM_GET_VALUES} (command {@code 0x04});
 * the response is a 87-byte VESC short packet whose 86-byte payload (after
 * stripping the leading VESC command byte) matches the {@code cyc_uart.json}
 * layout bundled in the app's assets.
 *
 * <p>Validation: the client waits for the first response that is a valid VESC
 * short packet with a 87-byte payload whose first byte is {@code 0x04} before
 * remembering the device address. The remembered address is used to skip
 * discovery on subsequent launches.
 */
public final class CycClient {

    public interface Host {
        BluetoothAdapter adapter();
        BluetoothLeScanner scanner();
        void setStatus(String status);
        void showDevicePicker(List<String> labels, IntConsumer onPick, Runnable onCancel);
    }

    private static final String PREFS = "display_settings";
    private static final String KEY_MOTOR_ADDRESS = "motor_address";
    private static final String KEY_MOTOR_LABEL = "motor_label";
    private static final String DEVICE_NAME = "CYCMOTOR";
    private static final int SCAN_SELECTION_DELAY_MS = 3000;
    private static final int VALIDATION_TIMEOUT_MS = 4000;
    public static final int DEFAULT_POLL_INTERVAL_MS = 200;

    public static final UUID SERVICE_UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID TX_UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID RX_UUID =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final byte COMM_GET_VALUES = 0x04;

    private final Host host;
    private final Handler handler;
    private final DataModel model;
    private final SharedPreferences preferences;
    private final int pollIntervalMs;
    private final CycLayout layout;
    private final byte[] getValuesPacket;

    private final Map<String, DiscoveredMotor> found = new LinkedHashMap<>();
    private final ByteArrayOutputStream rx = new ByteArrayOutputStream();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txCharacteristic;
    private boolean tryingRememberedDevice;
    private boolean selectingDevice;
    private boolean validated;
    private boolean scanning;

    private final Runnable resolveScan = this::chooseDevice;
    private final Runnable rejectTimeout = () -> {
        if (!validated && gatt != null) reject("No valid CYC telemetry");
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (gatt != null && txCharacteristic != null) {
                txCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                txCharacteristic.setValue(getValuesPacket);
                if (!gatt.writeCharacteristic(txCharacteristic)) {
                    host.setStatus("Motor request failed");
                }
                handler.postDelayed(this, pollIntervalMs);
            }
        }
    };

    public CycClient(Host host, Handler handler, DataModel model,
                     SharedPreferences preferences, int pollIntervalMs, CycLayout layout) {
        this.host = host;
        this.handler = handler;
        this.model = model;
        this.preferences = preferences;
        this.pollIntervalMs = pollIntervalMs;
        this.layout = layout;
        this.getValuesPacket = VescFraming.encodeShort(new byte[] { COMM_GET_VALUES });
    }

    public int pollIntervalMs() {
        return pollIntervalMs;
    }

    /** Convenience loader for the asset-shipped {@code cyc_uart.json}. */
    public static CycLayout loadLayoutFromAssets(Context context) throws IOException {
        try (InputStream in = context.getAssets().open("cyc_uart.json")) {
            return CycLayout.fromJson(in);
        }
    }

    @SuppressWarnings("MissingPermission")
    public void start() {
        BluetoothAdapter adapter = host.adapter();
        if (adapter == null || !adapter.isEnabled()) {
            host.setStatus("Turn Bluetooth on");
            return;
        }
        String saved = preferences.getString(KEY_MOTOR_ADDRESS, null);
        if (saved != null) {
            try {
                tryingRememberedDevice = true;
                BluetoothDevice device = adapter.getRemoteDevice(saved);
                String label = preferences.getString(KEY_MOTOR_LABEL, DEVICE_NAME);
                host.setStatus("Connecting to " + label);
                connectDevice(device);
                return;
            } catch (IllegalArgumentException error) {
                preferences.edit().remove(KEY_MOTOR_ADDRESS).remove(KEY_MOTOR_LABEL).apply();
            }
        }
        beginScan();
    }

    @SuppressWarnings("MissingPermission")
    public void stop() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(resolveScan);
        handler.removeCallbacks(rejectTimeout);
        if (scanning && host.scanner() != null) host.scanner().stopScan(scanCallback);
        scanning = false;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        txCharacteristic = null;
    }

    @SuppressWarnings("MissingPermission")
    public void rescan() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(resolveScan);
        handler.removeCallbacks(rejectTimeout);
        if (scanning && host.scanner() != null) host.scanner().stopScan(scanCallback);
        scanning = false;
        tryingRememberedDevice = false;
        preferences.edit().remove(KEY_MOTOR_ADDRESS).remove(KEY_MOTOR_LABEL).apply();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        txCharacteristic = null;
        beginScan();
    }

    @SuppressWarnings("MissingPermission")
    private void beginScan() {
        BluetoothLeScanner scanner = host.scanner();
        if (scanner == null) {
            host.setStatus("BLE scanner unavailable");
            return;
        }
        found.clear();
        selectingDevice = false;
        handler.removeCallbacks(resolveScan);
        host.setStatus("Scanning for motor");
        scanning = true;
        scanner.startScan(scanCallback);
    }

    @SuppressWarnings("MissingPermission")
    private void connectDevice(BluetoothDevice device) {
        if (scanning && host.scanner() != null) host.scanner().stopScan(scanCallback);
        scanning = false;
        selectingDevice = false;
        validated = false;
        rx.reset();
        handler.removeCallbacks(rejectTimeout);
        host.setStatus("Connecting to motor");
        gatt = device.connectGatt((Context) host, false, gattCallback,
                BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressWarnings("MissingPermission")
    private void chooseDevice() {
        if (found.isEmpty() || selectingDevice) return;
        if (found.size() == 1) {
            connectDevice(found.values().iterator().next().device);
            return;
        }
        selectingDevice = true;
        if (scanning && host.scanner() != null) host.scanner().stopScan(scanCallback);
        scanning = false;
        List<DiscoveredMotor> candidates = new ArrayList<>(found.values());
        List<String> labels = new ArrayList<>(candidates.size());
        for (DiscoveredMotor d : candidates) labels.add(d.label());
        final List<DiscoveredMotor> finalCandidates = candidates;
        host.showDevicePicker(labels, (which) -> {
            selectingDevice = false;
            if (which >= 0 && which < finalCandidates.size()) {
                connectDevice(finalCandidates.get(which).device);
            }
        }, () -> { selectingDevice = false; beginScan(); });
    }

    @SuppressWarnings("MissingPermission")
    private void rememberDevice(BluetoothDevice device) {
        DiscoveredMotor cand = found.get(device.getAddress());
        String label = cand == null ? device.getName() : cand.name;
        if (label == null) label = DEVICE_NAME;
        preferences.edit()
                .putString(KEY_MOTOR_ADDRESS, device.getAddress())
                .putString(KEY_MOTOR_LABEL, label)
                .apply();
    }

    @SuppressWarnings("MissingPermission")
    private void reject(String reason) {
        if (tryingRememberedDevice) {
            preferences.edit().remove(KEY_MOTOR_ADDRESS).remove(KEY_MOTOR_LABEL).apply();
            tryingRememberedDevice = false;
        }
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
        }
        gatt = null;
        txCharacteristic = null;
        handler.removeCallbacks(rejectTimeout);
        host.setStatus(reason + " - scanning");
        handler.postDelayed(this::beginScan, 1000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();
            String name = record == null ? null : record.getDeviceName();
            if (name == null) name = device.getName();
            if (name == null || !name.equals(DEVICE_NAME)) return;
            if (!found.containsKey(device.getAddress())) {
                found.put(device.getAddress(), new DiscoveredMotor(device, name, result.getRssi()));
                host.setStatus("Found " + found.size() + " motor candidate(s)");
                handler.removeCallbacks(resolveScan);
                handler.postDelayed(resolveScan, SCAN_SELECTION_DELAY_MS);
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (g != gatt) { g.close(); return; }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                host.setStatus("Motor connected");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(poll);
                handler.removeCallbacks(rejectTimeout);
                txCharacteristic = null;
                g.close();
                gatt = null;
                if (tryingRememberedDevice) tryingRememberedDevice = false;
                host.setStatus("Motor disconnected - searching");
                handler.postDelayed(() -> beginScan(), 1000);
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt) return;
            if (status != BluetoothGatt.GATT_SUCCESS) { reject("Motor service discovery failed"); return; }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) { reject("Not a CYC motor"); return; }
            BluetoothGattCharacteristic notify = service.getCharacteristic(RX_UUID);
            txCharacteristic = service.getCharacteristic(TX_UUID);
            BluetoothGattDescriptor cccd = notify == null ? null : notify.getDescriptor(CCCD_UUID);
            if (notify == null || txCharacteristic == null || cccd == null) {
                reject("Not a CYC motor");
                return;
            }
            g.setCharacteristicNotification(notify, true);
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(cccd);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (g != gatt) return;
            if (d.getUuid().equals(CCCD_UUID) && status == BluetoothGatt.GATT_SUCCESS) {
                host.setStatus("Verifying motor telemetry");
                handler.removeCallbacks(poll);
                handler.removeCallbacks(rejectTimeout);
                handler.postDelayed(rejectTimeout, VALIDATION_TIMEOUT_MS);
                handler.post(poll);
            } else {
                host.setStatus("Motor notification setup failed");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (g != gatt) return;
            if (c.getUuid().equals(RX_UUID)) acceptChunk(c.getValue());
        }
    };

    private void acceptChunk(byte[] chunk) {
        rx.write(chunk, 0, chunk.length);
        byte[] data = rx.toByteArray();
        byte[] payload = VescFraming.extractPayload(data);
        if (payload == null) return;
        rx.reset();
        if (payload.length < 1 || (payload[0] & 0xFF) != COMM_GET_VALUES) {
            host.setStatus("Unexpected motor response");
            return;
        }
        if (!validated && gatt != null) {
            validated = true;
            handler.removeCallbacks(rejectTimeout);
            rememberDevice(gatt.getDevice());
            tryingRememberedDevice = false;
        }
        // Strip the VESC command byte; the rest is the cyc_uart.json payload.
        byte[] telemetry = new byte[payload.length - 1];
        System.arraycopy(payload, 1, telemetry, 0, telemetry.length);
        decodeAndEmit(telemetry);
    }

    private void decodeAndEmit(byte[] telemetry) {
        if (telemetry.length < layout.totalBytes()) {
            host.setStatus("Short motor telemetry");
            return;
        }
        double motorTempC = layout.field("temp_motor_filtered").decode(telemetry);
        double controllerTempC = layout.field("temp_fet_filtered").decode(telemetry);
        double voltage = layout.field("Input_V").decode(telemetry);
        double motorCurrent = layout.field("reset_avg_motor_current").decode(telemetry);
        double speed = layout.field("Speed").decode(telemetry);
        double humanPower = layout.field("Human Power").decode(telemetry);
        double assistLevel = layout.field("Assist Level").decode(telemetry);
        // CYC returns Speed scaled by 100; cyc_uart.json says "scale": 100 and
        // cygnus-bike notes the units are mph once the app is in imperial mode.
        // (See the cyc-protocol-notes for the ODO/speed unit decision.)
        double speedMph = speed;
        double motorTempF = celsiusToFahrenheit(motorTempC);
        double controllerTempF = celsiusToFahrenheit(controllerTempC);
        // Electrical input power: V * A. scale of voltage = 10, scale of
        // current = 100, so product is in (V*100)*(A*100)/10000 = W.
        double motorPowerW = voltage * motorCurrent;
        int assist = (int) Math.round(assistLevel);
        model.setMotorMetrics(assist, speedMph, motorTempF, controllerTempF, humanPower, motorPowerW);
    }

    private static double celsiusToFahrenheit(double celsius) {
        return celsius * 9.0 / 5.0 + 32.0;
    }

    private static final class DiscoveredMotor {
        final BluetoothDevice device;
        final String name;
        final int rssi;
        DiscoveredMotor(BluetoothDevice device, String name, int rssi) {
            this.device = device; this.name = name; this.rssi = rssi;
        }
        String label() {
            return String.format(Locale.US, "%s  %s  (%d dBm)", name, device.getAddress(), rssi);
        }
    }
}
