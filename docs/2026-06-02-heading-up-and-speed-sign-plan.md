# Plan: Heading-up map rotation + GPS speed sign for cyc-glass

**Status:** Approved 2026-06-02. All open questions resolved.
**Created:** 2026-06-02
**Target project:** `~/.openclaw/workspace/projects/cyc-glass/`
**Depends on:** v0.2.0 dynamic GPS-centered map background
(`docs/2026-06-01-map-background-plan.md`)

---

## Goal

Build a HUD-style "heading-up" display on top of the existing
GPS-centered map background:

1. **Map rotation.** The map's "up" follows the direction the top
   edge of the phone is pointing (i.e. the user's facing direction).
   Fused from `Sensor.TYPE_ROTATION_VECTOR` (compass + accelerometer
   + gyroscope when present). Always on.
2. **Center indicator.** A small blue arrow at the geometric center
   of the screen, always pointing up in screen space, with the GPS
   location at the center of the arrow. The map rotates around this
   point — the arrow itself is screen-space, it never rotates with
   the map.
3. **GPS speed sign.** A traffic-sign-styled indicator placed
   directly below the settings gear, displaying the GPS-derived
   speed in mph with no unit label. Readable from the handlebars.

All existing behavior (BLE telemetry, current band, perimeter rows,
status line, settings gear) stays intact. The new elements are
additive overlays drawn on top of the map.

---

## Sensor source — RESOLVED 2026-06-02

**`Sensor.TYPE_ROTATION_VECTOR`** via `SensorManager`, registered
in `MainActivity.onResume`, unregistered in `onPause`. No
runtime permission required for sensors on any supported API.

| Sensor                 | Pros                                          | Cons                                   |
|------------------------|-----------------------------------------------|----------------------------------------|
| **`TYPE_ROTATION_VECTOR`** ✅ | OS-fused; uses magnetometer + accelerometer + gyroscope; smooth at low speeds; quaternions avoid gimbal lock | None for our use case                  |
| `TYPE_MAGNETIC_FIELD` + `TYPE_ACCELEROMETER` (manual fusion) | No deps | We re-implement what `getRotationMatrix` does; more drift, more code |
| `TYPE_GAME_ROTATION_VECTOR` (no compass) | No tilt compensation issues with nearby metal | Drifts over minutes; unusable for navigation |
| GPS bearing only       | No sensor permission UX                       | Undefined when stationary; slow to update; bad in tunnels |

**Lifecycle / rates:**

- Register in `onResume`, unregister in `onPause` (standard pattern).
- `SENSOR_DELAY_GAME` (~20 ms / 50 Hz). Smooth enough for handlebar
  use without burning the CPU.
- 100 ms low-pass filter on the azimuth to kill compass jitter when
  stationary. Filter is per-axis; no-op for first sample.

**Tilt gate (in the provider, before publishing):**

Ignore heading samples when the device is too far from its natural
orientation. Without this, a phone laid face-up on a stem mount
reports a meaningless azimuth. The gate:

```
ACCEPT  if  |pitch| < 60°  AND  |roll| < 60°
REJECT  otherwise
```

The provider publishes the last good heading while the device is
tilted past the gate. The map doesn't snap back to north during a
brief tilt (e.g. phone in a pocket), it just freezes.

**Status:** RESOLVED 2026-06-02. Owen approved the proposal; v1
default. If field testing shows problems, the gate and filter
constants move to `BuildConfig`.

---

## Map rotation

osmdroid's `MapView.setMapOrientation(float degrees)` rotates the
canvas around the screen center, leaving the center pixel on the
center pixel. This is exactly what we want: the GPS location is
the center, the arrow is the center, and the map spins underneath
both.

**Wiring:**

```java
// MapBackgroundView.setHeading(float degreesFromNorth)
public void setHeading(float degrees) {
    getController().setRotation(-degrees);  // osmdroid is clockwise-positive
}
```

`LocationProvider.onFix` continues to recenter the map (the bike
moves), and a new `OrientationProvider` posts heading changes to
`MapBackgroundView.setHeading`. The two are independent on
different timelines: GPS at 1 Hz, heading at ~50 Hz.

**No conflict with existing `recenterTo`:** the osmdroid
`MapController` keeps the rotation independent of the center; the
center and the orientation are two separate properties. We
verified this in the v0.2.0 read-through.

---

## Center arrow

Drawn in `GlassView.onDraw` (the same path the settings gear uses —
that's how we know it actually renders on this device, per the
2026-06-01 memory note). Composition:

- A small filled blue dot at the exact screen center, ~6 px
  radius. Marks the GPS position.
- A blue chevron / upward triangle just above the dot, ~32 px
  wide × 24 px tall. Filled `#1976D2` (Material Blue 700) with a
  thin white outline so it reads on light map tiles.
- Arrow points straight up in screen space, always. It does not
  rotate when the map does.

**Why on the `GlassView` and not a sibling `View`:** we have
hard-won evidence in the memory note (2026-06-01) that a sibling
`ImageView` did not render on this device. The gear is drawn
directly in `onDraw` and works. We follow that pattern.

**Why a chevron + dot, not a full arrow body:** a thin chevron is
the most common HUD convention (Google Maps blue dot, Apple Maps
user-location puck). It reads as "you are here, this way you're
facing" without dominating the screen. The user said "small" —
keep it small.

**The center is *not* interactive.** It is a status indicator
only. Taps pass through (the map underneath is the same — we
already swallow touches on `MapBackgroundView`).

---

## Speed sign

### Shape — RESOLVED 2026-06-02 → MUTCD white rectangle

Two real-world conventions:

| Style | Shape | Color |
|---|---|---|
| **US MUTCD speed limit** ✅ | Vertical rectangle, slightly taller than wide | White interior, black border, black numerals |
| ~~Vienna Convention (EU/UK/most of world)~~ | ~~Circle~~ | ~~Red border, white interior, black numerals~~ |

Owen's call: US MUTCD rectangle. Matches mph + Duluth MN. The
Vienna circle is the right answer for a non-US rider; if we ever
ship abroad we revisit.

### Layout

- **Position:** directly below the settings gear. The gear is
  at `bandTopPx + 12dp + 32dp` from the top of the main band
  (see `GlassView.recomputeBandMetrics`). The speed sign sits
  at the same X (top-right column), ~16dp below the gear's
  bottom edge.
- **Size:** 80 dp tall × 64 dp wide (rectangle) — large enough
  to read at a glance while riding, small enough to leave the
  current band uncluttered. The numeral is ~48 dp font
  (`width * 0.10f` on a 1080-wide screen = 108 px ≈ 36 dp at
  xxhdpi, scaled up to 48 dp for a glance-readable value).
  Auto-fit inside the rectangle if a 3-digit speed (`100+`)
  comes in.
- **Content:** GPS speed in mph, no unit label, integer value
  (`Math.round(mph)`). 0 mph is shown as `0`, not blank.
  Below 0.5 mph the sign is still drawn — useful when stopped
  at a light.
- **Color:** white interior, 3dp black border, black numerals.
  Matches the MUTCD spec. Font is **sans-serif bold** (RESOLVED
  2026-06-02), the same typeface `GlassView` already uses.

### Data flow

```
Location.getSpeed()  (m/s, can be NaN at startup)
   └─ LocationProvider  ──→  onSpeed(metersPerSecond)
                                └─ DataModel.setGpsSpeedMph(mph)
                                     └─ GlassView.refresh()
                                          └─ read in onDraw
```

`Location.getSpeed()` requires the provider to be able to
compute speed. `FusedLocationProviderClient` does, and reports
it in m/s. Conversion: `mph = mps × 2.236936`. If speed is
`Location.SPEED_UNSET` (older devices, or no Doppler), we show
`—` instead of a number — same convention the rest of the
perimeter values use.

**Status:** RESOLVED 2026-06-02. Owen accepted the proposal as the
v1 default.

### INTERNET — NOT NEEDED for the speed sign

The sign reads from `DataModel`, not from a network. The
`FusedLocationProviderClient` already gets the speed from GPS.

---

## Status-line changes — RESOLVED 2026-06-02

The existing status line composition is the Q5 rule from the
previous plan: "Searching GPS…" prefix only while searching.
For this feature:

- No new GPS status text. The current rule still applies.
- No heading-related status text. The map is always heading-up;
  the user can see the arrow + map rotation directly.
- No speed-related status text. The speed sign is the indicator.

**Status:** RESOLVED 2026-06-02. No change vs. v0.2.0.

---

## Architecture

```
projects/cyc-glass/android-app/app/src/main/java/com/cycglass/monitor/
├── MainActivity.java          (modify: add OrientationProvider,
│                                post heading to MapBackgroundView;
│                                lifecycle in onResume/onPause)
├── GlassView.java             (modify: draw center arrow in onDraw;
│                                draw speed sign below gear; read
│                                DataModel.gpsSpeedMph())
├── MapBackgroundView.java     (modify: add setHeading(float) which
│                                delegates to controller.setRotation)
├── OrientationProvider.java   (NEW — SensorManager wrapper, rotation
│                                vector → azimuth, tilt-gate, low-pass
│                                filter, lifecycle-aware)
├── LocationProvider.java      (modify: also report speed in onFix)
├── DataModel.java             (modify: add gpsSpeedMph + getter/setter)
├── MapTileSource.java, BmsClient.java, CycClient.java, CycLayout.java,
│   VescFraming.java
│                              (untouched)
```

**No new dependencies.** All work is in the existing Android SDK
+ osmdroid + play-services-location. No new Gradle deps.

**No new permissions.** Sensors do not require a runtime
permission on any supported API. The existing
`ACCESS_FINE_LOCATION` already covers the GPS-speed read.

---

## Rotation math (frozen spec)

`SensorManager.getRotationMatrixFromVector(R, event.values)` →
`SensorManager.getOrientation(R, orientation)` →
`orientation[0]` is azimuth, in radians, where 0 = magnetic
north, π/2 = east, increasing clockwise.

For a phone held in portrait with the screen facing the user:

- The "top edge of the phone" points in the same direction as
  the user's "forward."
- `orientation[0]` is exactly the bearing of the top edge from
  magnetic north.
- We convert to degrees (`× 180/π`) and pass the negative to
  osmdroid (`setRotation(-bearingDeg)`) because osmdroid's
  rotation is clockwise-positive and we want north at the top
  of the map when the user is heading north.

**Compass variance:** the `getRotationMatrixFromVector` math
inherits the magnetometer's noise. The 100 ms low-pass filter
on `orientation[0]` (and the tilt gate above) keep this from
showing as jitter on the map.

**Status:** RESOLVED 2026-06-02. Spec locked.

---

## Lifecycle integration

`MainActivity.onResume`:
- Existing: `handler.post(refreshView)`, `locationProvider.start()`.
- **New:** `orientationProvider.start()` (registers rotation vector listener).

`MainActivity.onPause`:
- Existing: `handler.removeCallbacks(refreshView)`, `locationProvider.stop()`.
- **New:** `orientationProvider.stop()` (unregisters listener).

`MainActivity.onDestroy`:
- Add `orientationProvider.stop()` (idempotent; safe).

**Both providers are independent on independent timelines:**

| Provider | Rate | Posts to |
|---|---|---|
| `LocationProvider` | 1 Hz, 5 m displacement | `MapBackgroundView.recenterTo`, `DataModel.setLastKnownLocation`, status line |
| `OrientationProvider` | ~50 Hz, post-tilt-gate, post-filter | `MapBackgroundView.setHeading` |

**Decision (RESOLVED 2026-06-02):** subscribe `MapBackgroundView`
directly to the orientation provider (no `DataModel.setHeadingDeg`
for v1). `DataModel.setGpsSpeedMph` IS added, because the speed
sign reads through `GlassView.refresh()` which reads from
`DataModel`, same as the BMS voltage etc.

---

## Open questions

All resolved 2026-06-02. See the inline **RESOLVED** markers
above:

1. Speed sign shape → MUTCD white rectangle
2. Heading filter time constant → 100 ms low-pass
3. Tilt gate threshold → 60° pitch / 60° roll
4. Speed sign tap behavior → visual only (gear keeps its 64dp tap rect)
5. Speed sign font → sans-serif bold

---

## Build order

1. **OrientationProvider** — `SensorManager` wrapper, rotation
   vector → azimuth, tilt gate, 100 ms low-pass, listener
   interface, lifecycle. Unit test the filter and the gate
   without an Android instrumentation harness (pure-Java
   helper extracted for testability).
2. **MapBackgroundView.setHeading** — one-line wrapper around
   `getController().setRotation(-deg)`. No test (delegates
   to osmdroid).
3. **MainActivity wiring** — instantiate the provider, post
   to `MapBackgroundView.setHeading`, lifecycle in
   onResume/onPause.
4. **Field test 1** — boot the app, rotate the phone on a
   table, confirm the map rotates smoothly. No GPS needed.
5. **DataModel + LocationProvider speed** — add
   `gpsSpeedMph`, post from `LocationCallback.onLocationResult`.
   Unit test the m/s → mph conversion.
6. **GlassView speed sign** — draw the sign in `onDraw`
   below the gear, read from `DataModel.gpsSpeedMph()`.
7. **GlassView center arrow** — draw the chevron + dot at
   the screen center in `onDraw`. Static (doesn't depend on
   heading — it points up in screen space).
8. **Field test 2** — full ride: speed sign updates, arrow
   stays at the center, map rotates with the phone. Capture
   a few screenshots for the changelog.
9. **Polish** — auto-fit the speed-sign font for 3-digit
   speeds (`100+`), no-rotation fallback if the rotation
   vector sensor is missing, status-line additions if needed.

---

## Changelog

- 2026-06-02 — Initial draft. Heading-up rotation + GPS speed
  sign proposed on top of the v0.2.0 map background. Open
  questions Q1 (sign shape) and Q5 (sign font) flagged for
  Owen.
- 2026-06-02 — All questions resolved. Owen confirmed: US
  MUTCD speed sign, sans-serif bold font, all other defaults
  (100 ms filter, 60°/60° tilt gate, visual-only sign, integer
  mph, no unit label, 80 dp × 64 dp sign, ~48 dp font, auto-
  fit for 3-digit speeds). Plan approved.
- 2026-06-02 — Step 1 implemented: `OrientationProvider.java`,
  `HeadingFilter.java`, and unit tests
  (`HeadingFilterTest`, `OrientationProviderTest`). Heading
  filter math is pure Java and exhaustively unit-tested; the
  sensor wiring is covered by field tests in step 4.
