package com.cycglass.monitor;

import android.Manifest;
import android.app.Activity;
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
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * GATT client for the Daly-family BMS used by blu-battery. Ported from
 * {@code blu-battery}'s {@code MainActivity} into its own class so the new
 * {@code MainActivity} can run a BMS client and a CYC client side by side
 * without sharing a single {@code BluetoothGattCallback}.
 *
 * <p>Behavior is unchanged from blu-battery:
 * <ul>
 *   <li>Discovers by service {@code FFF0} or observed advertisement
 *       manufacturer-data id {@code 0x0104}.</li>
 *   <li>Validates the first Daly status response ({@code D2 03 ...}) and only
 *       then remembers the address in {@link SharedPreferences}.</li>
 *   <li>Subscribes once to {@code FFF1}, sends the fixed 8-byte status
 *       request on {@code FFF2} every {@code pollIntervalMs}.</li>
 *   <li>Emits metrics to {@link DataModel#setBmsMetrics}.</li>
 *   <li>Does not pair, bond, or write any BMS configuration.</li>
 * </ul>
 */
public final class BmsClient {

    public interface Host {
        BluetoothAdapter adapter();
        BluetoothLeScanner scanner();
        void setStatus(String status);
        void requestBluetoothPermission();
        void showDevicePicker(List<String> labels, IntConsumer onPick, Runnable onCancel);
    }

    private static final String PREFS = "display_settings";
    private static final String KEY_ADDRESS = "battery_address";
    private static final String KEY_LABEL = "battery_label";
    private static final int OBSERVED_BMS_ADVERTISEMENT_ID = 0x0104;
    private static final int SCAN_SELECTION_DELAY_MS = 3000;
    private static final int VALIDATION_TIMEOUT_MS = 4000;

    public static final int DEFAULT_POLL_INTERVAL_MS = 200;
    public static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    public static final UUID NOTIFY_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    public static final UUID COMMAND_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final byte[] STATUS_REQUEST =
            new byte[] {(byte) 0xD2, 0x03, 0x00, 0x00, 0x00, 0x3E, (byte) 0xD7, (byte) 0xB9};

    private final Host host;
    private final Handler handler;
    private final DataModel model;
    private final SharedPreferences preferences;
    private final Map<String, DiscoveredBms> found = new LinkedHashMap<>();
    private final ByteArrayOutputStream rx = new ByteArrayOutputStream();
    private final int pollIntervalMs;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private boolean tryingRememberedDevice;
    private boolean selectingDevice;
    private boolean validated;
    private boolean scanning;

    /** True if this scan session was started because there was no cached address (new install or after clear).
     *  Used to give the first seen candidate a shorter selection delay so the "scan then cache MAC" flow
     *  succeeds more reliably on first launch without the user having to manually rescan. */
    private boolean isInitialDiscovery = false;

    private final Runnable resolveScan = this::chooseDevice;
    private final Runnable rejectTimeout = () -> {
        if (!validated && gatt != null) {
            reject("No valid BMS telemetry");
        }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (gatt != null && commandCharacteristic != null) {
                commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                commandCharacteristic.setValue(STATUS_REQUEST);
                if (!gatt.writeCharacteristic(commandCharacteristic)) {
                    host.setStatus("Telemetry request failed");
                }
                handler.postDelayed(this, pollIntervalMs);
            }
        }
    };

    public BmsClient(Host host, Handler handler, DataModel model,
                     SharedPreferences preferences, int pollIntervalMs) {
        this.host = host;
        this.handler = handler;
        this.model = model;
        this.preferences = preferences;
        this.pollIntervalMs = pollIntervalMs;
    }

    public int pollIntervalMs() {
        return pollIntervalMs;
    }

    @SuppressWarnings("MissingPermission")
    public void start() {
        BluetoothAdapter adapter = host.adapter();
        if (adapter == null || !adapter.isEnabled()) {
            host.setStatus("Turn Bluetooth on");
            return;
        }
        String saved = preferences.getString(KEY_ADDRESS, null);
        if (saved != null) {
            try {
                tryingRememberedDevice = true;
                BluetoothDevice device = adapter.getRemoteDevice(saved);
                String label = preferences.getString(KEY_LABEL, saved);
                host.setStatus("Connecting to " + label);
                connectDevice(device);
                return;
            } catch (IllegalArgumentException error) {
                preferences.edit().remove(KEY_ADDRESS).remove(KEY_LABEL).apply();
            }
        }
        beginScan();
    }

    @SuppressWarnings("MissingPermission")
    public void stop() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(resolveScan);
        handler.removeCallbacks(rejectTimeout);
        if (scanning && host.scanner() != null) {
            host.scanner().stopScan(scanCallback);
        }
        scanning = false;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        commandCharacteristic = null;
    }

    @SuppressWarnings("MissingPermission")
    public void rescan() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(resolveScan);
        handler.removeCallbacks(rejectTimeout);
        if (scanning && host.scanner() != null) {
            host.scanner().stopScan(scanCallback);
        }
        scanning = false;
        tryingRememberedDevice = false;
        preferences.edit().remove(KEY_ADDRESS).remove(KEY_LABEL).apply();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        commandCharacteristic = null;
        beginScan();
    }

    @SuppressWarnings("MissingPermission")
    private void beginScan() {
        BluetoothLeScanner scanner = host.scanner();
        if (scanner == null) {
            host.setStatus("BLE scanner unavailable");
            return;
        }
        if (scanning) {
            scanner.stopScan(scanCallback);
            scanning = false;
        }
        found.clear();
        selectingDevice = false;
        handler.removeCallbacks(resolveScan);
        isInitialDiscovery = (preferences.getString(KEY_ADDRESS, null) == null);
        if (isInitialDiscovery) {
            host.setStatus("Scanning for battery — power on the bike if not already");
        } else {
            host.setStatus("Scanning for BMS");
        }
        scanning = true;
        scanner.startScan(scanCallback);
    }

    @SuppressWarnings("MissingPermission")
    private void tryAggressiveReconnect() {
        // Aggressive persistent reconnect logic (user requirement for riding: keep trying without dying or user action).
        // Prefer direct connect to saved/remembered device (bypass scan for speed). Short backoff if needed.
        // Only fall back to scan if no saved address. Will keep retrying on future disconnects.
        String saved = preferences.getString(KEY_ADDRESS, null);
        if (saved != null) {
            try {
                BluetoothDevice device = host.adapter().getRemoteDevice(saved);
                host.setStatus("Reconnecting directly (aggressive)");
                connectDevice(device);
                return;
            } catch (IllegalArgumentException e) {
                // Bad saved addr, clear and scan
                preferences.edit().remove(KEY_ADDRESS).remove(KEY_LABEL).apply();
            }
        }
        // No saved or bad: fall back to scan (will remember on success)
        handler.postDelayed(this::beginScan, 500);  // short delay for aggressive but not instant spam
    }

    @SuppressWarnings("MissingPermission")
    private void connectDevice(BluetoothDevice device) {
        if (scanning && host.scanner() != null) {
            host.scanner().stopScan(scanCallback);
        }
        scanning = false;
        selectingDevice = false;
        validated = false;
        rx.reset();
        handler.removeCallbacks(rejectTimeout);
        host.setStatus("Connecting to BMS");
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
        if (scanning && host.scanner() != null) {
            host.scanner().stopScan(scanCallback);
        }
        scanning = false;
        List<DiscoveredBms> candidates = new ArrayList<>(found.values());
        List<String> labels = new ArrayList<>(candidates.size());
        for (DiscoveredBms d : candidates) labels.add(d.label());
        final List<DiscoveredBms> finalCandidates = candidates;
        host.showDevicePicker(labels, (which) -> {
            selectingDevice = false;
            if (which >= 0 && which < finalCandidates.size()) {
                connectDevice(finalCandidates.get(which).device);
            }
        }, () -> { selectingDevice = false; beginScan(); });
    }

    @SuppressWarnings("MissingPermission")
    private void rememberDevice(BluetoothDevice device) {
        DiscoveredBms cand = found.get(device.getAddress());
        String label = cand == null ? device.getName() : cand.name;
        if (label == null) label = "Battery BMS";
        // MAC addresses are runtime BluetoothDevice.getAddress() values only (never hardcoded in source or APK).
        // Stored in device-local SharedPreferences (MODE_PRIVATE) -- acceptable per user (on phone, not in GH/APK).
        // Cleared on rescan or bad addr.
        preferences.edit()
                .putString(KEY_ADDRESS, device.getAddress())
                .putString(KEY_LABEL, label)
                .apply();
        isInitialDiscovery = false;  // We successfully locked in and cached during this discovery session.
    }

    @SuppressWarnings("MissingPermission")
    private void reject(String reason) {
        if (tryingRememberedDevice) {
            preferences.edit().remove(KEY_ADDRESS).remove(KEY_LABEL).apply();
            tryingRememberedDevice = false;
        }
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
        }
        gatt = null;
        commandCharacteristic = null;
        handler.removeCallbacks(rejectTimeout);
        host.setStatus(reason + " - scanning");
        handler.postDelayed(this::beginScan, 1000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            if (!isCandidate(result)) return;
            BluetoothDevice device = result.getDevice();
            String name = advertisedName(result);
            if (!found.containsKey(device.getAddress())) {
                found.put(device.getAddress(), new DiscoveredBms(device, name, result.getRssi()));
                host.setStatus("Found " + found.size() + " BMS candidate(s)");
                handler.removeCallbacks(resolveScan);
                long delay = SCAN_SELECTION_DELAY_MS;
                if (isInitialDiscovery && found.size() == 1) {
                    // On a brand new install (or after rescan cleared the cached MAC), as soon as we see the
                    // first candidate via the manufacturer mask (0x0104), use a shorter delay before attempting
                    // to connect + validate. This makes the "scan first, then cache the MAC" flow succeed
                    // more reliably and quickly on first launch without the user needing to manually hit Rescan.
                    delay = 1000;
                }
                handler.postDelayed(resolveScan, delay);
            }
        }
    };

    @SuppressWarnings("MissingPermission")
    private boolean isCandidate(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return false;
        List<ParcelUuid> services = record.getServiceUuids();
        if (services != null && services.contains(new ParcelUuid(SERVICE_UUID))) return true;
        return record.getManufacturerSpecificData(OBSERVED_BMS_ADVERTISEMENT_ID) != null;
    }

    @SuppressWarnings("MissingPermission")
    private String advertisedName(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        if (name == null) name = result.getDevice().getName();
        return name == null ? "Unnamed BMS" : name;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (g != gatt) {
                g.close();
                return;
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                host.setStatus("Connected");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(poll);
                handler.removeCallbacks(rejectTimeout);
                commandCharacteristic = null;
                g.close();
                gatt = null;
                if (tryingRememberedDevice) tryingRememberedDevice = false;
                host.setStatus("Disconnected - retrying aggressively");
                // Aggressive reconnect for riding use case (per user): prefer direct reconnect to saved device
                // without full scan/delay if we have a remembered address. Keep trying without user interaction.
                // Falls back to scan only if no saved address. Short/no delay to stay connected.
                handler.post(BmsClient.this::tryAggressiveReconnect);
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt) return;
            if (status != BluetoothGatt.GATT_SUCCESS) { reject("Service discovery failed"); return; }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) { reject("Candidate is not a compatible BMS"); return; }
            BluetoothGattCharacteristic notify = service.getCharacteristic(NOTIFY_UUID);
            commandCharacteristic = service.getCharacteristic(COMMAND_UUID);
            BluetoothGattDescriptor cccd = notify == null ? null : notify.getDescriptor(CCCD_UUID);
            if (notify == null || commandCharacteristic == null || cccd == null) {
                reject("Candidate is not a compatible BMS");
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
                host.setStatus("Verifying BMS telemetry");
                handler.removeCallbacks(poll);
                handler.removeCallbacks(rejectTimeout);
                handler.postDelayed(rejectTimeout, VALIDATION_TIMEOUT_MS);
                handler.post(poll);
            } else {
                host.setStatus("Notification setup failed");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (g != gatt) return;
            if (c.getUuid().equals(NOTIFY_UUID)) acceptChunk(c.getValue());
        }
    };

    private void acceptChunk(byte[] chunk) {
        rx.write(chunk, 0, chunk.length);
        byte[] data = rx.toByteArray();
        int start = findStart(data);
        if (start < 0) { rx.reset(); return; }
        if (data.length - start < 3) return;
        int size = 3 + (data[start + 2] & 0xFF) + 2;
        if (data.length - start < size) return;
        byte[] frame = new byte[size];
        System.arraycopy(data, start, frame, 0, size);
        rx.reset();
        if (!validStatusFrame(frame)) {
            host.setStatus("Invalid response");
            return;
        }
        if (!validated && gatt != null) {
            validated = true;
            handler.removeCallbacks(rejectTimeout);
            rememberDevice(gatt.getDevice());
            tryingRememberedDevice = false;
        }
        double voltage = unsigned16(frame, 83) * 0.1;
        double current = (unsigned16(frame, 85) - 30000) * 0.1;
        double remaining = unsigned16(frame, 99) * 0.1;
        model.setBmsMetrics(voltage, current, remaining);
    }

    private static boolean validStatusFrame(byte[] frame) {
        if (frame.length < 101 || (frame[0] & 0xFF) != 0xD2 || (frame[1] & 0xFF) != 0x03) {
            return false;
        }
        int expected = crc16Modbus(frame, frame.length - 2);
        int actual = (frame[frame.length - 2] & 0xFF) | ((frame[frame.length - 1] & 0xFF) << 8);
        return expected == actual;
    }

    private static int findStart(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if ((data[i] & 0xFF) == 0xD2) return i;
        }
        return -1;
    }

    private static int unsigned16(byte[] value, int offset) {
        return ((value[offset] & 0xFF) << 8) | (value[offset + 1] & 0xFF);
    }

    private static int crc16Modbus(byte[] value, int length) {
        int checksum = 0xFFFF;
        for (int i = 0; i < length; i++) {
            checksum ^= value[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                checksum = (checksum & 1) != 0 ? (checksum >> 1) ^ 0xA001 : checksum >> 1;
            }
        }
        return checksum;
    }

    private static final class DiscoveredBms {
        final BluetoothDevice device;
        final String name;
        final int rssi;
        DiscoveredBms(BluetoothDevice device, String name, int rssi) {
            this.device = device; this.name = name; this.rssi = rssi;
        }
        String label() {
            return String.format(Locale.US, "%s  %s  (%d dBm)", name, device.getAddress(), rssi);
        }
    }
}
