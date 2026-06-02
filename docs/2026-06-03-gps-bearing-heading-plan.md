# GPS-bearing heading for cyc-glass

**Date:** 2026-06-03
**Project:** `projects/cyc-glass`
**Status:** implemented, 2026-06-03

## Problem

The v0.3.0 heading-up rotation uses the phone's rotation vector
sensor (compass + accel + gyro fused). On a moving bike this drifts
from the actual direction of motion because:

- The phone mount is rarely aligned with the bike's frame.
- The phone can wobble/tilt while riding.
- The magnetic compass is unreliable near the motor, battery, and
  metal frame.

Owen tested v0.3.0 on the bike and the map's "up" did not match
the direction of travel. The fix is to use **GPS bearing** (the
direction between two recent fixes) as the heading source when
the bike is actually moving, and fall back to the rotation vector
when stopped.

## Goal

When moving above ~4.5 mph (2 m/s), the map's "up" (0° heading)
points in the direction of motion. When stopped, the map uses
the phone's heading.

## RESOLVED decisions

- **Speed threshold:** 2 m/s (~4.5 mph). Below this, GPS bearing
  is too noisy (small position deltas amplify noise). Above it,
  the bearing is reliable.
- **Switch behavior:** hard switch (no cross-fade). Snap at the
  threshold is acceptable for the bike use case — a rider notices
  a slow drift more than a one-time snap. Cross-fade is a v2 if
  needed.
- **Bearing window:** last 1.5 s of fixes, computed from the
  oldest to the newest point in the window. Requires at least
  500 ms of separation (otherwise the bearing is unstable).
- **Bearing filter:** reuse `HeadingFilter` (100 ms low-pass)
  for the same smoothing behavior as the rotation vector path.
- **Calibration angle:** not needed. The mount offset between
  the phone and the bike is implicit — when moving, GPS bearing
  is the source of truth, so the offset doesn't matter.

## Architecture

```
            ┌────────────────────────────┐
            │   SensorManager            │
            │   (TYPE_ROTATION_VECTOR)   │
            └─────────────┬──────────────┘
                          │ rotation-vector
                          ▼
            ┌────────────────────────────┐
            │  OrientationProvider       │
            │  • 50 Hz                   │
            │  • tilt gate 60°/60°       │
            │  • HeadingFilter           │
            │  • azimuth in degrees      │
            └─────────────┬──────────────┘
                          │ azimuth(deg, ms)
                          ▼
            ┌────────────────────────────┐
            │  MotionHeading (new)       │     ┌─────────────────────┐
            │  • speed gate 2 m/s        │◀────│ LocationProvider    │
            │  • ring buffer of fixes    │     │ onFix(...)          │
            │  • GPS bearing in degrees  │     └─────────────────────┘
            │  • HeadingFilter           │
            └─────────────┬──────────────┘
                          │ heading(deg, ms)
                          ▼
            ┌────────────────────────────┐
            │  MapBackgroundView         │
            │  setHeading(deg)           │
            └────────────────────────────┘
```

`MotionHeading` is a pure-Java class. Both inputs (azimuth, GPS
fix) are pre-computed by their providers, so the math
(bearing, filtering, switching) is exhaustively unit-testable
without Android instrumentation.

## Build order

1. **`MotionHeading.java`** — pure Java, ring buffer of fixes,
   bearing math, hard switch at 2 m/s.
2. **`MotionHeadingTest.java`** — bearing math (cardinals,
   wraparound, same-point), buffer aging, speed-gate behavior,
   listener fan-out.
3. **`MainActivity.java`** — instantiate `MotionHeading`, wire
   its listener to `mapView.setHeading`, feed
   `orientationProvider`'s listener output into
   `motionHeading.onAzimuth`, and feed
   `locationProvider.onFix` into `motionHeading.onLocation`.
4. **Build + install + test on `ZY22KR8XLR`.**

`OrientationProvider`, `MapBackgroundView`, `GlassView`, and
`LocationProvider` are unchanged.

## Files affected

| File | Change |
|---|---|
| `MotionHeading.java` | NEW — pure Java, switch logic |
| `MotionHeadingTest.java` | NEW — JUnit tests |
| `MainActivity.java` | wire MotionHeading between OrientationProvider + LocationProvider and mapView |
| (other) | unchanged |

## Lifecycle

- `MotionHeading` is created in `onCreate`, has no start/stop
  methods (it's stateless in the lifecycle sense — its internal
  state is just the most recent filter values and buffer).
- Its inputs come from `OrientationProvider` (which has its own
  start/stop in onResume/onPause) and `LocationProvider`
  (likewise). When the providers are stopped, no inputs arrive
  and no outputs are emitted.
- No explicit teardown needed; the object becomes unreachable
  when `MainActivity` is destroyed.

## Tradeoffs

- **Snap on transition:** when speed crosses 2 m/s, the map
  snaps from rotation-vector heading to GPS bearing (or vice
  versa). At ~2 m/s, the bike is at very low speed or just
  starting/stopping. The snap is at most a few degrees (the
  difference between phone heading and motion direction).
- **GPS jitter at low speed:** below 2 m/s, we use the
  rotation vector. The rotation vector is jittery when the
  phone is held loosely, but at low speed the rider doesn't
  care about precise heading.
- **No automatic mount-offset calibration.** A future
  improvement could average the (rotation-vector − GPS-bearing)
  offset over a long ride and apply it as a calibration. Not
  needed for v1 — the GPS bearing is the source of truth when
  moving, and offset doesn't matter then.

## What "verified" means for v0.3.1

- All 74+ existing tests still pass.
- New tests for `MotionHeading` cover: bearing math
  (cardinals, 45°, wraparound, same-point), buffer aging
  (oldest dropped after 1.5 s), hard switch (azimuth under
  threshold, GPS over), listener fan-out, no-bearing when
  buffer has < 2 points or < 500 ms separation.
- APK on `ZY22KR8XLR`:
  - Map shows the new rotation-source behavior.
  - At rest: rotation vector drives the map (same as v0.3.0).
  - In motion (verified by phone shake or a short walk): the
    map's "up" tracks the direction of travel, not the phone's
    magnetic heading.
  - No crashes during the speed-gate transitions.

## Out of scope

- Cross-fade between sources (v2).
- Auto-calibration of the mount offset (v2, only useful if
  the rotation vector is to be trusted at speed).
- Compass figure-8 calibration prompt (not needed; the
  rotation vector is only a fallback).
- Speedometer / odometer on the speed sign (separate feature).
