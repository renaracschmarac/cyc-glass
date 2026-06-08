# cyc-glass

Glass-cockpit Android app for an e-bike: a real-time single-screen view
of the battery (Blu-Battery Daly BMS) and the CYC X1 Pro Gen4 motor,
talking both devices concurrently over Bluetooth Low Energy.

The app is read-only. It does not write motor configuration, does not
pair or bond, and does not change BMS settings. It opens one BLE
connection to each peripheral, subscribes once to the appropriate
notify characteristic, and refreshes telemetry on the existing
connection at 5 Hz per peripheral.

## Display layout

```
+--------------------------------------------------------+
|  Lev   SPD     V    MOT       (4 perimeter cells)      |
|   3    18.4  53.3   74F                                  |
+--------------------------------------------------------+
|                                                         |
|   CURRENT -3.2 A     (color-graded)                     |
|                                                         |
+--------------------------------------------------------+
| CTRL  HumW  MotW  Cap       (4 perimeter cells)        |
|  79F   84W  178W  18.6Ah                                |
+--------------------------------------------------------+
```

- The **CURRENT** band is the single large color-graded value. While the
  motor is connected it uses CYC `reset_avg_input_current`, inverted so
  discharge/riding is negative. When CYC telemetry is unavailable it
  falls back to Daly BMS current, including positive charging current.
  The color scale remains dark green at 0 A, yellow at half scale, and
  bright red at the configured limit.
- The two perimeter rows (8 small cells total) carry the CYC motor
  telemetry (`Lev`, `SPD`, `V`, `MOT`, `CTRL`, `HumW`, `MotW`) plus
  Daly BMS capacity (`Cap`). The perimeter font is sized
  so units fit in their cells without overflow.
- The status line below the bottom row shows the current connection
  state ("Verifying motor telemetry", "Live 4.9 Hz", etc.).

## Two BLE connections, side by side

| Peripheral        | Service                          | Notify                  | Write (host → device) | Request |
|-------------------|----------------------------------|-------------------------|-----------------------|---------|
| Daly BMS (battery)| `0000fff0-…`                    | `0000fff1-…`           | `0000fff2-…`          | `D2 03 00 00 00 3E D7 B9` (Modbus RTU status) |
| CYC motor         | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` (NUS) | `6e400003-…` (handle `0x000f`) | `6e400002-…` (handle `0x000d`) | `02 01 04 40 84 03` (VESC `COMM_GET_VALUES`) |

The BMS client sends its request every 200 ms; the CYC client sends its
request every 200 ms, **staggered by 100 ms** in `MainActivity` so the
two GATTs do not wake the radio at the same instant.

Discovery model mirrors blu-battery: scan → validate the first real
telemetry response → persist the address → reconnect directly on
subsequent launches. A "Re-scan" button in the settings dialog clears
both remembered addresses and re-runs discovery.

## Units and derived values

- **Speed** is displayed in mph (matches blu-battery's imperial convention).
- **Temperatures** are displayed in °F; conversion is `°F = °C × 9/5 + 32`
  on the CYC `temp_motor_filtered` / `temp_fet_filtered` fields.
- **Motor power (W)** is computed as `Input_V × reset_avg_motor_current`
  (electrical input to the controller). This is the same value the CYC
  Ride Control app shows as "Motor Power".
- **Displayed voltage (V)** comes from the CYC `Input_V` field while
  motor telemetry is available and falls back to Daly BMS voltage when
  the motor is off or disconnected.
- **Human power (W)** is a direct field (`Human Power` at offset 76) and
  comes from the torque sensor in the bottom bracket; no derivation.
- **Current sign convention** matches blu-battery: positive for current
  into the pack (charging), negative for current out of the pack
  (discharging / riding). CYC input current is inverted before display
  because the controller reports draw as positive.

## Build

Requires JDK 17, Gradle 8.13.0 or newer (the project was validated with
Gradle 8.14.3), and the Android SDK with Platform 35 and Build Tools
installed. The Android SDK location is read from
`android-app/local.properties` (`sdk.dir=…`).

```bash
cd android-app
gradle --no-daemon assembleDebug
```

The resulting APK is at
`android-app/app/build/outputs/apk/debug/app-debug.apk`.

## Install and run

```bash
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.cycglass.monitor/.MainActivity
```

On first launch, accept the BLUETOOTH_SCAN / BLUETOOTH_CONNECT prompts
(Android 12+). The app then scans for both peripherals.

The remembered addresses are stored in
`com.cycglass.monitor` SharedPreferences (`display_settings`):

- `battery_address`, `battery_label` — the BMS to reconnect to.
- `motor_address`, `motor_label` — the CYC motor to reconnect to.
- `amps_out`, `amps_in` — the current-band color scale limits.

Use `SETTINGS` → `Re-scan for battery` (also re-scans the motor) to
discard the remembered addresses and re-run discovery.

### Diagnostic overrides

For BLE rate testing without recompiling, the poll interval can be
overridden at launch:

```bash
adb shell am force-stop com.cycglass.monitor
adb shell am start -n com.cycglass.monitor/.MainActivity \
    --ei poll_interval_ms 200 \
    --ei motor_poll_interval_ms 200
```

The two extras are independent. Both default to 200 ms (5 Hz per
peripheral). Set them to identical values for a 10 Hz combined refresh,
or stagger them as in production.

## Tests

### Unit tests (no hardware required)

```bash
cd android-app
gradle --no-daemon testDebugUnitTest
```

Three test classes cover the full decode chain:

- `VescFramingTest` — encode → CRC16 → extract round-trip, tolerance to
  junk bytes, CRC-mismatch rejection.
- `CycLayoutTest` — synthetic 87-byte `COMM_GET_VALUES` response is
  framed, extracted, and decoded against the bundled
  `cyc_uart.json`. Every field the app displays (controller temp,
  motor temp, voltage, motor current, speed, human power, assist
  level) is asserted to its expected physical value.
- `BmsFrameTest` — Daly status-frame CRC16 against the canonical
  `D2 03 00 00 00 3E` header, plus valid / invalid-frame rejection.

### Python eval / cross-check

```bash
python3 scripts/eval_decoder.py
```

Mirrors the Java VESC + cyc_uart decoder in pure Python, generates the
same synthetic 87-byte response, and asserts every physical value
matches what the JUnit test expects. The two implementations must
agree; if either drifts, this script catches it without needing
hardware.

### Five-minute dual-connection soak test

```bash
BMS_ADDRESS=50:AA:BB:CC:DD:EE CYCMOTOR_ADDRESS=F0:AA:BB:CC:DD:EE \
    python3 scripts/soak_test.py --seconds 300
```

Runs the blu-battery BMS monitor and the cygnus-bike CYC telemetry
monitor in parallel and verifies that **both** BLE connections stay
open and healthy for the full duration. Every second it polls
`bluetoothctl info` for both addresses and writes a timestamped
line to `data/captures/soak-<timestamp>.log`. The final line is
either:

- `PASS elapsed=…s samples=… disconnected_samples=0` — both
  peripherals stayed connected for the whole test.
- `FAIL elapsed=…s samples=… disconnected_samples=N` — at least one
  peripheral dropped; the log shows when.

This mirrors blu-battery's `scripts/verify_persistent_connection.py`
pattern but extends it to the two-connection case. Run it with the
bike powered and the BMS in range.

## Layout asset

`android-app/app/src/main/assets/cyc_uart.json` is the runtime copy of
the telemetry layout. The build runs a `verifyLayoutAssetConsistency`
preBuild task that refuses to compile if it has drifted from
`data/cyc_uart.json`. To update the layout after a CYC APK change, copy
the new JSON into `data/cyc_uart.json` and re-run the build — the
asset is regenerated automatically.

See `data/SOURCE.md` for the APK version, asset path, and the list of
fields the app actually uses.

## Security

BLE addresses are private. They are never committed. The app stores
remembered addresses in app-private SharedPreferences and refuses to
log them. When the user requests a re-scan, the saved addresses are
cleared from the same store.

## Related projects

- `../blu-battery/` — the Python + Android app this project was
  forked from. Owns the Daly BMS protocol and the current-band color
  scale.
- `../cygnus-bike/` — the reverse-engineering work for the CYC X1 Pro
  Gen4 motor BLE protocol. Owns the live captures, the
  `cyc_telemetry.py` reference reader, and the VESC framing spec.
- `../cyc-glass/` (this repo) — the Android app that combines both.

## License

MIT, same as blu-battery. See `LICENSE`.
