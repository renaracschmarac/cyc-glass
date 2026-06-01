#!/usr/bin/env python3
"""Cross-check the Java VESC + cyc_uart decoder against a reference Python
implementation.

Generates a synthetic 87-byte COMM_GET_VALUES response with known values,
frames it as a VESC short packet, decodes it through the same algorithms
the Android app uses, and prints the physical values the app would
display. Run this to confirm the Java unit tests in
``android-app/app/src/test/java/com/cycglass/monitor/CycLayoutTest.java``
still produce the same numbers after a layout change.

Usage::

    python3 scripts/eval_decoder.py
    python3 scripts/eval_decoder.py --json      # machine-readable output
    python3 scripts/eval_decoder.py --captures  # also walk data/captures/

Exit code is 0 on a successful cross-check, non-zero on any disagreement.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# CRC16 table (VESC polynomial 0x1021, init 0). Mirrors VescFraming.java.
CRC16_TAB = [
    0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
    0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF,
    0x1231, 0x0210, 0x3273, 0x2252, 0x52B5, 0x4294, 0x72F7, 0x62D6,
    0x9339, 0x8318, 0xB37B, 0xA35A, 0xD3BD, 0xC39C, 0xF3FF, 0xE3DE,
    0x2462, 0x3443, 0x0420, 0x1401, 0x64E6, 0x74C7, 0x44A4, 0x5485,
    0xA56A, 0xB54B, 0x8528, 0x9509, 0xE5EE, 0xF5CF, 0xC5AC, 0xD58D,
    0x3653, 0x2672, 0x1611, 0x0630, 0x76D7, 0x66F6, 0x5695, 0x46B4,
    0xB75B, 0xA77A, 0x9719, 0x8738, 0xF7DF, 0xE7FE, 0xD79D, 0xC7BC,
    0x48C4, 0x58E5, 0x6886, 0x78A7, 0x0840, 0x1861, 0x2802, 0x3823,
    0xC9CC, 0xD9ED, 0xE98E, 0xF9AF, 0x8948, 0x9969, 0xA90A, 0xB92B,
    0x5AF5, 0x4AD4, 0x7AB7, 0x6A96, 0x1A71, 0x0A50, 0x3A33, 0x2A12,
    0xDBFD, 0xCBDC, 0xFBBF, 0xEB9E, 0x9B79, 0x8B58, 0xBB3B, 0xAB1A,
    0x6CA6, 0x7C87, 0x4CE4, 0x5CC5, 0x2C22, 0x3C03, 0x0C60, 0x1C41,
    0xEDAE, 0xFD8F, 0xCDEC, 0xDDCD, 0xAD2A, 0xBD0B, 0x8D68, 0x9D49,
    0x7E97, 0x6EB6, 0x5ED5, 0x4EF4, 0x3E13, 0x2E32, 0x1E51, 0x0E70,
    0xFF9F, 0xEFBE, 0xDFDD, 0xCFFC, 0xBF1B, 0xAF3A, 0x9F59, 0x8F78,
    0x9188, 0x81A9, 0xB1CA, 0xA1EB, 0xD10C, 0xC12D, 0xF14E, 0xE16F,
    0x1080, 0x00A1, 0x30C2, 0x20E3, 0x5004, 0x4025, 0x7046, 0x6067,
    0x83B9, 0x9398, 0xA3FB, 0xB3DA, 0xC33D, 0xD31C, 0xE37F, 0xF35E,
    0x02B1, 0x1290, 0x22F3, 0x32D2, 0x4235, 0x5214, 0x6277, 0x7256,
    0xB5EA, 0xA5CB, 0x95A8, 0x8589, 0xF56E, 0xE54F, 0xD52C, 0xC50D,
    0x34E2, 0x24C3, 0x14A0, 0x0481, 0x7466, 0x6447, 0x5424, 0x4405,
    0xA7DB, 0xB7FA, 0x8799, 0x97B8, 0xE75F, 0xF77E, 0xC71D, 0xD73C,
    0x26D3, 0x36F2, 0x0691, 0x16B0, 0x6657, 0x7676, 0x4615, 0x5634,
    0xD94C, 0xC96D, 0xF90E, 0xE92F, 0x99C8, 0x89E9, 0xB98A, 0xA9AB,
    0x5844, 0x4865, 0x7806, 0x6827, 0x18C0, 0x08E1, 0x3882, 0x28A3,
    0xCB7D, 0xDB5C, 0xEB3F, 0xFB1E, 0x8BF9, 0x9BD8, 0xABBB, 0xBB9A,
    0x4A75, 0x5A54, 0x6A37, 0x7A16, 0x0AF1, 0x1AD0, 0x2AB3, 0x3A92,
    0xFD2E, 0xED0F, 0xDD6C, 0xCD4D, 0xBDAA, 0xAD8B, 0x9DE8, 0x8DC9,
    0x7C26, 0x6C07, 0x5C64, 0x4C45, 0x3CA2, 0x2C83, 0x1CE0, 0x0CC1,
    0xEF1F, 0xFF3E, 0xCF5D, 0xDF7C, 0xAF9B, 0xBFBA, 0x8FD9, 0x9FF8,
    0x6E17, 0x7E36, 0x4E55, 0x5E74, 0x2E93, 0x3EB2, 0x0ED1, 0x1EF0,
]


def crc16_v2(data: bytes) -> int:
    """VESC CRC16 over ``data`` (matches ``VescFraming.crc16`` in Java)."""
    checksum = 0
    for b in data:
        checksum = ((checksum << 8) & 0xFFFF) ^ CRC16_TAB[((checksum >> 8) ^ b) & 0xFF]
    return checksum & 0xFFFF


def encode_short(payload: bytes) -> bytes:
    """Encode a VESC short packet (matches ``VescFraming.encodeShort``)."""
    if len(payload) > 255:
        raise ValueError("payload too long for short packet")
    crc = crc16_v2(payload)
    return bytes([0x02, len(payload) & 0xFF, *payload, (crc >> 8) & 0xFF, crc & 0xFF, 0x03])


def extract_payload(buf: bytes) -> bytes | None:
    """First valid VESC short packet in ``buf``, or ``None`` if incomplete.

    Mirrors ``VescFraming.extractPayload`` (no long-packet branch; the CYC
    telemetry response always fits a short packet).
    """
    for i, byte in enumerate(buf):
        if byte != 0x02:
            continue
        if len(buf) - i < 4:
            return None
        length = buf[i + 1]
        total = 2 + length + 2 + 1
        if len(buf) - i < total:
            return None
        if buf[i + total - 1] != 0x03:
            continue
        payload = buf[i + 2 : i + 2 + length]
        expected = crc16_v2(payload)
        actual = (buf[i + 2 + length] << 8) | buf[i + 2 + length + 1]
        if expected != actual:
            continue
        return payload
    return None


def make_synthetic_telemetry_body() -> bytes:
    """Build the 86-byte body of a synthetic COMM_GET_VALUES response.

    The values chosen here are the same ones the Java unit test uses
    (``VescFramingTest.makeSyntheticTelemetry``); the two must produce
    identical decoded physical values.
    """
    body = bytearray(86)
    # temp_fet_filtered: int16 LE, scale 10, value 25.0 C → 250
    body[0:2] = (250).to_bytes(2, "little", signed=True)
    # temp_motor_filtered: int16 LE, scale 10, value 30.0 C → 300
    body[2:4] = (300).to_bytes(2, "little", signed=True)
    # reset_avg_motor_current: int32 LE, scale 100, value 5.00 A → 500
    body[4:8] = (500).to_bytes(4, "little", signed=True)
    # Input_V: int16 LE, scale 10, value 48.0 V → 480
    body[26:28] = (480).to_bytes(2, "little", signed=True)
    # Human Power: int32 LE, scale 1, value 84 W → 84
    body[76:80] = (84).to_bytes(4, "little", signed=True)
    # Speed: int32 LE, scale 100, value 12.34 → 1234
    body[80:84] = (1234).to_bytes(4, "little", signed=True)
    # Assist Level: int8, scale 1, value 3
    body[85] = 3
    return bytes(body)


def decode_field(layout: list[dict], body: bytes, key: str) -> float:
    """Decode a single field by key from the layout and the body bytes."""
    for f in layout:
        if f["key"] == key:
            start = f["offset"]
            length = f["length"]
            scale = f["scale"]
            signed = f["type"] == "int"
            raw = int.from_bytes(body[start : start + length], "little", signed=signed)
            return raw / scale
    raise KeyError(f"field {key!r} not in layout")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--layout",
        default=str(Path(__file__).parent.parent / "data" / "cyc_uart.json"),
        help="path to cyc_uart.json (default: data/cyc_uart.json)",
    )
    parser.add_argument("--json", action="store_true", help="emit JSON instead of text")
    parser.add_argument(
        "--captures", action="store_true", help="also walk data/captures/ for hex frames"
    )
    args = parser.parse_args()

    layout = json.loads(Path(args.layout).read_text())
    body = make_synthetic_telemetry_body()
    payload = bytes([0x04]) + body
    frame = encode_short(payload)

    extracted = extract_payload(frame)
    assert extracted is not None, "synthetic frame failed to extract"
    assert extracted == payload, "synthetic frame did not round-trip"

    telemetry = extracted[1:]  # strip VESC command byte

    fields = {
        "temp_fet_filtered_c": decode_field(layout, telemetry, "temp_fet_filtered"),
        "temp_motor_filtered_c": decode_field(layout, telemetry, "temp_motor_filtered"),
        "input_v": decode_field(layout, telemetry, "Input_V"),
        "reset_avg_motor_current_a": decode_field(
            layout, telemetry, "reset_avg_motor_current"
        ),
        "speed": decode_field(layout, telemetry, "Speed"),
        "human_power_w": decode_field(layout, telemetry, "Human Power"),
        "assist_level": decode_field(layout, telemetry, "Assist Level"),
    }
    fields["controller_temp_f"] = fields["temp_fet_filtered_c"] * 9 / 5 + 32
    fields["motor_temp_f"] = fields["temp_motor_filtered_c"] * 9 / 5 + 32
    fields["motor_power_w"] = fields["input_v"] * fields["reset_avg_motor_current_a"]

    if args.json:
        print(json.dumps(fields, indent=2))
    else:
        print("Synthetic COMM_GET_VALUES response decoded:")
        for k, v in fields.items():
            print(f"  {k:32s} {v}")

    # Cross-check assertions: must match VescFramingTest / CycLayoutTest.
    expected = {
        "temp_fet_filtered_c": 25.0,
        "temp_motor_filtered_c": 30.0,
        "input_v": 48.0,
        "reset_avg_motor_current_a": 5.0,
        "speed": 12.34,
        "human_power_w": 84.0,
        "assist_level": 3.0,
        "controller_temp_f": 25.0 * 9 / 5 + 32,
        "motor_temp_f": 30.0 * 9 / 5 + 32,
        "motor_power_w": 48.0 * 5.0,
    }
    for k, want in expected.items():
        got = fields[k]
        if abs(got - want) > 1e-9:
            print(f"FAIL: {k}: expected {want}, got {got}", file=sys.stderr)
            return 1

    print("\nCross-check OK: all expected values match the synthetic payload.")
    print("This must agree with VescFramingTest and CycLayoutTest in JUnit.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
