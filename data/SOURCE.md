# Telemetry layout source

The bundled `cyc_uart.json` is the same byte map shipped in the CYC Ride
Control Android APK and used by the read-only `cyc_telemetry.py` script in
the `cygnus-bike` project. The fields and offsets are stable across
firmware versions; the only field that has ever been added to the layout
since the CYC X1 Pro Gen4 launch is `xSeries_uart.json`, which extends
the block from 86 to 156 bytes for newer hardware revisions.

**Source APK:** `com.cyc.CYCRideControl` v2.5.44
**Asset path in APK:** `assets/flutter_assets/assets/json/cyc_uart.json`
**Live response (X1 Pro Gen4 / X12 controller):** 87-byte VESC short
packet: `02 57 04 <86 bytes> <crc16> 03` — leading `0x04` is the VESC
`COMM_GET_VALUES` command echo; the 86 bytes that follow match this
layout exactly. The 156-byte `xSeries_uart.json` is **not** returned by
the tested X1 Pro Gen4 hardware — see `cygnus-bike/docs/cyc-protocol-notes.md`
for the live captures that established this.

## Field list (relevant subset)

| Key                 | Offset | Length | Scale | Type | Notes                                |
|---------------------|-------:|-------:|------:|------|--------------------------------------|
| `temp_fet_filtered` |      0 |      2 |    10 |  int | Controller (FET) temperature, °C     |
| `temp_motor_filtered`|     2 |      2 |    10 |  int | Motor temperature, °C                |
| `reset_avg_motor_current`|  4 |      4 |   100 |  int | Motor current, A                     |
| `Input_V`           |     26 |      2 |    10 |  int | Pack voltage, V                      |
| `Human Power`       |     76 |      4 |     1 |  int | Rider power, W (from torque sensor)  |
| `Speed`             |     80 |      4 |   100 |  int | Speed, mph once app is in imperial   |
| `Assist Level`      |     85 |      1 |     1 |  int | 0..N depending on assist level cap   |

## Derived values

- **Motor power (W):** `Input_V * reset_avg_motor_current` — electrical
  input to the controller; matches the CYC app's "Motor Power" display.
- **Controller / motor temperature in °F:** `°F = °C × 9/5 + 32`.
- **Speed unit:** the CYC app shows the `Speed` field in mph once the
  display is set to imperial (matches the blu-battery convention); the
  X1 Pro Gen4 controller emits the same wire bytes regardless of app
  unit setting, so the conversion happens on the app side.

## Maintenance

If a new APK release adds fields or shifts offsets:

1. Pull the new APK: `adb pull` from the test phone, or download from
   apkmirror.
2. Unzip and copy `assets/flutter_assets/assets/json/cyc_uart.json` into
   `data/`. (Also into `android-app/app/src/main/assets/cyc_uart.json`
   so the runtime asset is in sync.)
3. Run the JUnit tests in `android-app/app/src/test/java/com/cycglass/monitor/CycLayoutTest.java`
   and `VescFramingTest.java`; both will fail loudly if the layout
   changes break the expected decode values.
4. Run `python3 scripts/eval_decoder.py` — the cross-check prints every
   expected value and must agree with the Java tests.
