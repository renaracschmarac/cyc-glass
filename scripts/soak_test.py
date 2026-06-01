#!/usr/bin/env python3
"""Five-minute dual-connection soak test for cyc-glass.

Runs the blu-battery BMS monitor and the cygnus-bike CYC telemetry
monitor in parallel and verifies that both BLE connections stay open
and healthy for the full duration. This mirrors blu-battery's
``scripts/verify_persistent_connection.py`` pattern but extends it to
the two-connection case cyc-glass needs.

The test must be run with the bike powered, the BMS powered, and the
host machine's Bluetooth adapter enabled. Both devices must be within
range. The script discovers device addresses from the local cyc-glass /
blu-battery settings files if available; otherwise pass them on the
command line.

Usage::

    # Both devices known and remembered locally.
    python3 scripts/soak_test.py --seconds 300

    # Override the addresses (synthetic example shown):
    BMS_ADDRESS=50:AA:BB:CC:DD:EE \
    CYCMOTOR_ADDRESS=F0:AA:BB:CC:DD:EE \
    python3 scripts/soak_test.py --seconds 60

The test writes a timestamped log to ``data/captures/soak-<timestamp>.log``
and prints a final ``PASS`` / ``FAIL`` line that matches blu-battery's
contract.
"""

from __future__ import annotations

import argparse
import os
import re
import signal
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

BLU_BATTERY_DIR = Path(__file__).parent.parent.parent / "blu-battery"
CYGNUS_BIKE_DIR = Path(__file__).parent.parent.parent / "cygnus-bike"
HERE = Path(__file__).parent
CAPTURES = HERE.parent / "data" / "captures"


def bluetooth_connected(address: str) -> str:
    """Return 'yes', 'no', or 'unknown' from bluetoothctl info <addr>."""
    try:
        out = subprocess.run(
            ["bluetoothctl", "info", address],
            capture_output=True, text=True, timeout=10,
        )
    except (subprocess.TimeoutExpired, FileNotFoundError) as e:
        return f"unknown:{e!r}"
    for line in out.stdout.splitlines():
        if line.strip().lower().startswith("connected:"):
            value = line.split(":", 1)[1].strip().lower()
            return "yes" if value == "yes" else "no"
    return "unknown"


def find_bms_address() -> str | None:
    settings = BLU_BATTERY_DIR / ".cygnus-bike.local.json"
    # blu-battery's local settings file: actually it doesn't use a local
    # settings file, it stores the address in Android SharedPreferences which
    # is unreachable from Linux. The user must pass BMS_ADDRESS explicitly,
    # or the test operator can find it via ``bluetoothctl devices`` before
    # running the test.
    return os.environ.get("BMS_ADDRESS")


def find_cyc_address() -> str | None:
    settings = CYGNUS_BIKE_DIR / ".cygnus-bike.local.json"
    if settings.exists():
        import json
        data = json.loads(settings.read_text())
        return data.get("address")
    return os.environ.get("CYCMOTOR_ADDRESS")


def spawn_ble_clients(bms_addr: str, cyc_addr: str) -> list[subprocess.Popen]:
    """Start the blu-battery and cygnus-bike BLE monitors in parallel."""
    procs: list[subprocess.Popen] = []

    # blu-battery monitor (continuous, 1-second refresh).
    blb = BLU_BATTERY_DIR / ".venv" / "bin" / "blu-battery"
    if not blb.exists():
        blb = BLU_BATTERY_DIR / ".venv" / "bin" / "blu-battery"
    if blb.exists():
        procs.append(subprocess.Popen(
            [str(blb), "--address", bms_addr, "--interval", "1"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        ))
    else:
        print(
            f"WARN: blu-battery CLI not found at {blb}; skipping BMS subprocess",
            file=sys.stderr,
        )

    # cygnus-bike CYC monitor.
    cyc_telemetry = CYGNUS_BIKE_DIR / "scripts" / "cyc_telemetry.py"
    if cyc_telemetry.exists():
        procs.append(subprocess.Popen(
            [sys.executable, str(cyc_telemetry),
             "--address", cyc_addr, "--interval", "0.5",
             "--json", "--count", "0"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        ))
    else:
        print(
            f"WARN: cyc_telemetry.py not found at {cyc_telemetry}; "
            f"skipping CYC subprocess",
            file=sys.stderr,
        )

    return procs


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=int, default=300,
                        help="test duration (default 300 = 5 minutes)")
    parser.add_argument("--interval", type=float, default=1.0,
                        help="seconds between checks (default 1.0)")
    parser.add_argument("--log", type=Path, default=None,
                        help="override the log path")
    args = parser.parse_args()

    bms_addr = find_bms_address()
    cyc_addr = find_cyc_address()
    if not bms_addr or not cyc_addr:
        print(
            "ERROR: both BMS_ADDRESS and CYCMOTOR_ADDRESS must be set.\n"
            "  BMS_ADDRESS=<bms-ble-address> CYCMOTOR_ADDRESS=<cyc-ble-address> "
            "python3 scripts/soak_test.py",
            file=sys.stderr,
        )
        return 2

    CAPTURES.mkdir(parents=True, exist_ok=True)
    log_path = args.log or CAPTURES / f"soak-{datetime.now().strftime('%Y%m%d-%H%M%S')}.log"
    print(f"Logging to {log_path}")

    log = log_path.open("w")
    log.write(f"start={datetime.now().isoformat()} bms={bms_addr} cyc={cyc_addr}\n")
    log.flush()

    procs = spawn_ble_clients(bms_addr, cyc_addr)
    if not procs:
        print("ERROR: no BLE client processes started", file=sys.stderr)
        return 2

    print(f"Started {len(procs)} BLE client subprocess(es).")

    # Catch Ctrl-C cleanly: forward SIGINT to children.
    def stop_children(*_):
        for p in procs:
            try:
                p.send_signal(signal.SIGTERM)
            except ProcessLookupError:
                pass
        sys.exit(130)
    signal.signal(signal.SIGINT, stop_children)

    start = time.time()
    deadline = start + args.seconds
    next_check = start
    total_samples = 0
    disconnected_samples = 0

    try:
        while time.time() < deadline:
            now = time.time()
            if now < next_check:
                time.sleep(min(0.5, next_check - now))
                continue
            next_check = now + args.interval

            bms_state = bluetooth_connected(bms_addr)
            cyc_state = bluetooth_connected(cyc_addr)
            elapsed = now - start
            line = (f"t={elapsed:7.1f}s "
                    f"bms={bms_state} cyc={cyc_state}\n")
            log.write(line)
            log.flush()
            total_samples += 1
            if bms_state != "yes" or cyc_state != "yes":
                disconnected_samples += 1

            # Drain subprocess output (non-blocking) so pipes don't fill.
            for p in procs:
                if p.stdout is None:
                    continue
                try:
                    p.stdout.readline()
                except Exception:
                    pass
    finally:
        for p in procs:
            try:
                p.send_signal(signal.SIGTERM)
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()
        log.close()

    elapsed = time.time() - start
    if disconnected_samples == 0:
        verdict = "PASS"
    else:
        verdict = "FAIL"
    summary = (f"{verdict} elapsed={elapsed:.1f}s samples={total_samples} "
               f"disconnected_samples={disconnected_samples}\n")
    with log_path.open("a") as f:
        f.write(summary)
    print(summary.strip())
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
