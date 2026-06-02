# Imperial / Metric unit toggle for cyc-glass

**Date:** 2026-06-04 (or whenever Owen confirms)
**Project:** `projects/cyc-glass`
**Status:** proposed, awaiting approval

## Goal

A new option in the existing gear-icon settings panel to
toggle the app between **Imperial** (mph, °F — the current
default) and **Metric** (kph, °C). The choice persists
across app restarts via SharedPreferences. Every numeric
display that has a unit changes to match.

## Scope (what flips when the toggle is flipped)

| Display | Imperial | Metric |
|---|---|---|
| Speed sign (EU Vienna red-ringed circle, just the number) | mph | kph |
| Top perimeter "mph" cell value | mph | kph |
| Top perimeter "mph" cell label | "mph" | "kph" |
| Top perimeter "MOT°F" cell value | °F | °C |
| Top perimeter "MOT°F" cell label | "MOT°F" | "MOT°C" |
| Bottom perimeter "CTRL°F" cell value | °F | °C |
| Bottom perimeter "CTRL°F" cell label | "CTRL°F" | "CTRL°C" |
| Voice / content description (accessibility) | "…speed 18 mph, motor 80 °F…" | "…speed 29 kph, motor 27 °C…" |

**Stays the same** regardless of toggle:

- Amps (A), voltage (V), capacity (Ah), motor / human power
  (W) — already SI, no conversion needed.
- CYC "Speed" wire field: still parsed as km/h (per the
  v0.3.1 fix); the conversion to display units happens at
  render time.
- Map, center arrow, gear icon, status line — no units.

## RESOLVED design questions

- **Storage:** keep the existing canonical units. Speeds
  are stored in mph in `DataModel`; temperatures in °F.
  Conversion to kph/°C happens at render time. Rationale:
  smallest possible diff, no risk to the live
  `gpsSpeedMph` / `speedMph` / motor-temp fields that the
  model currently exposes.
- **Speed sign unit label:** none. The Vienna Convention
  speed-limit sign is iconically unlabeled (matches the
  MUTCD convention on this point); the rider has just
  toggled the setting, so they know which unit they
  picked. Keeps the sign visually clean.
- **Default:** Imperial (current behavior, no migration).
- **Persistence key:** `units_system` in the existing
  `display_settings` SharedPreferences file, alongside
  `amps_out` and `amps_in`.

## Architecture

A new pure-Java class `Units.java` holds the conversion +
formatting math. Pure Java so all the conversion and
formatting is unit-testable without instrumentation.

```
            ┌──────────────────────────┐
            │ SharedPreferences        │
            │ "display_settings"       │
            │   key="units_system"     │
            │   value: IMPERIAL|METRIC │
            └────────────┬─────────────┘
                         │ read in onCreate
                         ▼
            ┌──────────────────────────┐
            │ DataModel                │
            │ unitSystem: UnitSystem   │
            └────────────┬─────────────┘
                         │ passed to view via refresh()
                         ▼
            ┌──────────────────────────┐
            │ GlassView                │
            │ onDraw: use Units.* to   │
            │ format speed/temp cells  │
            │ based on current system  │
            └──────────────────────────┘
```

The setting dialog gets a new `RadioGroup` (Imperial /
Metric). On Save, the value is written to
SharedPreferences and the view is refreshed immediately.

## Build order

1. **`Units.java`** — pure Java, conversion + formatting
   helpers, `UnitSystem` enum.
2. **`UnitsTest.java`** — conversion math (mph↔kph, °F↔°C),
   formatting (with/without unit label, integer rounding,
   edge cases at 0 / negative / boundary speeds).
3. **`DataModel.java`** — `unitSystem` field (default
   `IMPERIAL`), getter/setter, `synchronized` like the
   other accessors.
4. **`MainActivity.java`** — read `units_system` from
   `display_settings` in `onCreate`, pass to the model. In
   `showCurrentSettings`, add a `RadioGroup` above the
   amps fields. On Save, persist and call
   `view.refresh()` (which will re-read the model).
5. **`GlassView.java`** — `refresh()` pulls `unitSystem`
   into a local field. `onDraw` and `drawPerimeterRow`
   use `Units.formatSpeedMph(...)`, `Units.formatTempF(...)`,
   `Units.speedLabel(unitSystem)`, `Units.tempLabel(unitSystem)`
   for the four affected cells. The speed-sign number uses
   `Units.formatSpeedInteger(mph, unitSystem)` so the
   integer is in the right unit but the unit is implicit
   (no label, per the design decision above).
6. **Build + install + verify on `ZY22KR8XLR`.**
7. **Toggle test:** open the app, tap the gear, switch to
   Metric, save. Confirm: speed sign shows kph, perimeter
   row labels are "kph" and "MOT°C" / "CTRL°C", all
   values are converted. Switch back to Imperial and
   confirm everything reverts. (Static screenshot is
   fine for this; the actual on-bike ride can stay on
   Imperial.)

## Files affected

| File | Change |
|---|---|
| `Units.java` | NEW — pure Java helpers + `UnitSystem` |
| `UnitsTest.java` | NEW — JUnit tests |
| `DataModel.java` | `unitSystem` field, getter/setter |
| `MainActivity.java` | read/write `units_system`, add radio group to settings |
| `GlassView.java` | use `Units.*` in perimeter + speed-sign draws |

`MapBackgroundView`, `MotionHeading`, `LocationProvider`,
`OrientationProvider`, `BmsClient`, `CycClient`, all
unchanged.

## Tests

New `UnitsTest` covering:
- `mphToKph(0) == 0`, `mphToKph(60) ≈ 96.561`
- `kphToMph(0) == 0`, `kphToMph(100) ≈ 62.137`
- Round-trip stability (`mphToKph(kphToMph(x)) ≈ x`)
- `fToC(32) == 0` (water freezes)
- `fToC(212) == 100` (water boils)
- `cToF(0) == 32`, `cToF(100) == 212`
- `formatSpeedInteger(0, IMPERIAL) == "0"`, `formatSpeedInteger(60, METRIC) == "97"`
- `formatSpeedWithUnit(0, IMPERIAL) == "0 mph"`, `formatSpeedWithUnit(0, METRIC) == "0 kph"`
- `speedLabel(IMPERIAL) == "mph"`, `speedLabel(METRIC) == "kph"`
- `formatTempF(32, IMPERIAL) == "32°F"`, `formatTempF(0, METRIC) == "0°C"`
- `tempLabel(IMPERIAL) == "MOT°F"`, `tempLabel(METRIC) == "MOT°C"` (and same for CTRL)
- NaN passthrough: `formatSpeed(Double.NaN, …)` → "—"

Plus the existing 105 tests must still pass.

## UI mock (settings dialog)

```
┌─ Current Color Scale ─────────────────────┐
│ The Current band is green at 0 A, …       │
│                                           │
│ Units  ( ) Imperial (mph, °F)              │
│        (●) Metric (kph, °C)               │
│                                           │
│ Bright red at Amps OUT (-)                │
│ ┌──────────────┐                          │
│ │ 100.0        │                          │
│ └──────────────┘                          │
│ Bright red at Amps IN (+)                 │
│ ┌──────────────┐                          │
│ │ 20.0         │                          │
│ └──────────────┘                          │
│                                           │
│ [ Re-scan for battery ]  [Cancel]  [Save] │
└───────────────────────────────────────────┘
```

(Imperial is the default; if the user has never opened
settings, Imperial is selected. If they've previously
saved Metric, Metric is the pre-selected radio.)

## Out of scope (v1)

- Localization of the labels themselves (e.g., translating
  "mph" to "mph" in other languages) — `mph` / `kph` / `°F`
  / `°C` are universal.
- Per-display overrides (e.g., metric speeds but imperial
  temps). One toggle controls both.
- Distance / odometer units (no odometer display yet).
- Speedometer unit auto-switch based on locale.
