# Plan: Dynamic GPS-centered map background for cyc-glass

**Status:** Draft, working through open questions with Owen.
**Created:** 2026-06-01
**Target project:** `~/.openclaw/workspace/projects/cyc-glass/`

---

## Path check (RESOLVED)

The user originally pointed at `projects/cygnus-bike` (the Python
read-only BLE tooling for the CYC motor), but the description
("foreground items," "middle section," "transparency obscure the map
as AMP increases") matches `projects/cyc-glass` exactly — the
Android app with the perimeter cells, the color-graded CURRENT band,
and the alpha gradient commit `e402f21`.

**Status:** RESOLVED 2026-06-01 — Owen confirmed `cyc-glass` is the
target. Plan locked to the Android app.

---

## Goal

Add a full-screen map backdrop to the cyc-glass Android app:

1. Map always centered on the device's GPS location.
2. Default scale: screen width ≈ 1000 ft (304.8 m).
3. All existing foreground items (perimeter rows, main CURRENT band,
   status line, settings gear) stay on top, unchanged.
4. The main CURRENT band fades in over the map as the current value
   rises from 0 A to the configured `Amps OUT` / `Amps IN` limit.
   (The alpha gradient is already implemented in
   `GlassView.currentColor()` — see `GlassView.java:318-326`.)
5. No pan / zoom / marker / control UI on the map — it's a backdrop.

---

## Map provider — RESOLVED

**Provider:** OpenStreetMap raster tiles, no API key.

| Option | Pros | Cons |
|---|---|---|
| **osmdroid + OSM raster** ✅ | Single dep, mature, OSM data is best for non-US, no API key | OSM Foundation policy disallows "heavy use" without coordination |
| ~~osmdroid + Stadia Maps~~ | 200k free reqs/mo, designed for app use | Needs API key, paid after free tier |
| ~~osmdroid + MapTiler~~ | 100k free reqs/mo, vector available | Needs API key, paid after free tier |
| ~~MapLibre Native + vector~~ | Sharp at any zoom, offline-capable | Heavier dep, more setup |
| ~~Google Maps SDK~~ | Familiar | Hard-couples to Google Play Services; Static Images API is wrong shape for a backdrop |
| ~~Hand-rolled tile fetcher~~ | Minimal deps, full control | We already have osmdroid doing this; reinventing it adds risk |

**OSM UA history note (re-confirmed):** `MEMORY.md` mentions a
2026-05-03 fix for OSM tile blocking against the cygnus-bike Python
crawler. The fix is the same at runtime: set
`Configuration.getInstance().userAgentValue` to a real-app-identifier
UA per OSM's tile policy. Concretely:

```java
Configuration.getInstance().userAgentValue =
    "cyc-glass/" + BuildConfig.VERSION_NAME
    + " (+https://github.com/renaracschmarac/cyc-glass)";
```

OSM's published tile usage policy says the UA must include a contact
(URL or email). The project page URL satisfies that and avoids
embedding a direct email in every tile request.

**OSM tile policy acceptance:** We accept the "no heavy use" constraint
and keep our tile-load rate low. Refresh strategy (below) is throttled
to only fetch on real GPS displacement. If the user reports blocks in
the wild, the fallback is to point `MapTileSource` at Stadia/MapTiler
via a one-line config change — that escape hatch stays in the code.

**Status:** RESOLVED 2026-06-01 — Owen chose OSM raster, no API key.

---

## GPS — provider (RESOLVED)

`FusedLocationProviderClient` from `play-services-location`, requesting
**`ACCESS_FINE_LOCATION` only (no `ACCESS_BACKGROUND_LOCATION`)**:

- `Priority.PRIORITY_HIGH_ACCURACY`
- `interval = 1000 ms`, `smallestDisplacement = 5 m`
- `setFastestInterval(500 ms)` for intermediate samples
- Stop updates in `onPause`, resume in `onResume`

Permission requested once at `MainActivity.startWhenPermitted()`,
alongside the existing Bluetooth pair. No "Allow all the time"
prompt.

**Status:** RESOLVED 2026-06-01 — Owen chose foreground-only.

---

## Refresh / update strategy

- **GPS thread** (~1 Hz, Play Services): updates model lat/lon.
- **GlassView refresh** (existing 10 Hz in `MainActivity.refreshView`):
  unchanged.
- **Map refresh** (new, throttled):
  - < 0.25 tile widths from current center (~32 m at zoom 18):
    just translate the existing bitmap, no network.
  - 0.25 – 0.5 tile widths: translate + prefetch the new edge tile.
  - \> 0.5 tile widths: snap to new center, fetch a fresh 3×3 mosaic.

Two independent redraw timelines, no coupling.

## Tile cache — RESOLVED

**Mode:** Online-only. No bundled tile pack, no side-loadable pack.
Every tile fetched live from OSM and saved to the device's tile
cache directory.

**Cache cap:** 600 MB. Set via
`Configuration.getInstance().tileFileSystemCacheMaxBytes = 600L * 1024 * 1024`
in `MainActivity.onCreate` before the `MapView` is inflated.

osmdroid evicts least-recently-used tiles when the cap is reached.
600 MB at the default tile size (256×256) holds roughly 600 MB ÷
~10 KB per tile ≈ 60k tiles, which covers a wide area at zoom 18
(full Duluth metro, all of Minneapolis–St. Paul, and most of the
North Shore). The cap is global; the working set on a typical ride
will be a few hundred MB.

**Trade-off acknowledged:** 600 MB is generous. If a phone is shared
with other osmdroid-using apps (none in this stack, but possible)
they share the cache. We'll log the current cache size in the
status line only if it grows past a soft-warning threshold (configurable,
default 80% of cap).

**Status:** RESOLVED 2026-06-01 — Owen chose online-only, 600 MB cap.

---

## Status-line GPS info — RESOLVED

**Behavior:** Status line only mentions GPS when we're searching for
a fix. Once a fix is acquired, the status line reverts to the
existing BMS/CYC status with no satellite count, accuracy, or fix
quality. No sat-lock info anywhere in v1.

State machine (composed with the existing status line):

| State | Status line text | Notes |
|---|---|---|
| No GPS, no permission yet | `"Searching GPS… · <existing>"` | Permission prompt is up |
| Permission granted, no fix | `"Searching GPS… · <existing>"` | Play Services callback hasn't fired yet |
| Fix acquired | `<existing>` only | No GPS text |
| Permission denied | `"GPS off (no permission) · <existing>"` | Map stays at last-known center (see Question 6) |

Implementation note: `LocationProvider` exposes two methods —
`isSearching()` and `hasFix()` — and `MainActivity` polls them at
the existing 10 Hz `refreshView` rate. When the state changes,
`view.setStatus(...)` is called with the composed string. The
`<existing>` part is whatever `bmsClient` / `cycClient` set
independently; we just `String.join(" · ", gpsPart, existingPart)`
and pass that through.

**Status:** RESOLVED 2026-06-01 — Owen chose: only show GPS status
when searching, no sat-lock info.

---

## Default map state on cold start — RESOLVED

**Rule:** Try last-known location first. If none, render a black
background. The existing bottom status line still does the
"Searching GPS…" notification per the Q5 rule — no big centered
overlay is drawn on the map area itself.

Three-state machine:

| State | Map renders | Status line |
|---|---|---|
| We have a last-known `SharedPreferences` value | Map centered on that lat/lon at the computed zoom | `"Searching GPS… · <existing>"` (or `<existing>` if fix is already in hand) |
| No last-known (first run, or wiped `SharedPreferences`) | Solid black fill of the `MapBackgroundView` (no tiles, no centered text) | `"Searching GPS… · <existing>"` |
| First real fix arrives | Smooth recenter to the fix (osmdroid handles the animation); `SharedPreferences` is updated | `<existing>` only (Q5 rule) |

`SharedPreferences` schema (added to the existing
`display_settings` prefs):

- `last_known_lat` (float)
- `last_known_lon` (float)
- `last_known_fix_ms` (long) — for a future "stale?" warning if
  desired; not used in v1

`MainActivity.onCreate` reads the saved lat/lon (if present) and
passes it to `MapBackgroundView.setInitialCenter(LatLng)`. The
`LocationProvider` writes the new lat/lon on every fix and calls
`SharedPreferences.Editor.apply()`. The `MapBackgroundView`
subscribes to the provider and recenters when the new fix is more
than the throttled-recenter threshold (0.5 tile widths) from the
current center, or the first fix if there's no current center.

**Black background implementation:** `MapBackgroundView` extends
osmdroid's `MapView`. In the no-last-known branch, we override
`onDraw` *before* calling `super.onDraw` and fill the canvas with
`Color.BLACK`; then we don't call `super.onDraw` (so osmdroid
doesn't try to draw tiles). Once a center is set, we call
`super.onDraw` normally. This keeps the no-map state visually
honest without showing a "no map data" message.

**Status:** RESOLVED 2026-06-01 — Owen chose: last-known from
`SharedPreferences`, black background fallback, no centered
overlay on the map area.

---

## Architecture

```
projects/cyc-glass/android-app/app/src/main/java/com/cycglass/monitor/
├── MainActivity.java          (modify: add FusedLocationProviderClient,
│                                request ACCESS_FINE_LOCATION, place
│                                MapBackgroundView behind GlassView,
│                                lifecycle)
├── GlassView.java             (untouched; existing alpha gradient
│                                already works)
├── MapBackgroundView.java     (NEW — thin osmdroid wrapper; no
│                                controls, centered on provided LatLng,
│                                tile source from BuildConfig)
├── MapTileSource.java         (NEW — enum + factory: OSM, STADIA,
│                                MAPTILER; reads API key from BuildConfig)
├── LocationProvider.java      (NEW — wraps FusedLocationProviderClient;
│                                posts LatLng to MapBackgroundView;
│                                lifecycle-aware)
├── DataModel.java             (modify: add last-known lat/lon +
│                                timestamp)
├── BmsClient.java, CycClient.java, CycLayout.java, VescFraming.java
│                              (untouched)
```

---

## 1000-ft = screen-width math (frozen spec)

At lat φ, zoom Z, screen width W pixels:

```
metersPerPixel = 156543.03392 * cos(φ) / 2^Z
W * metersPerPixel = 304.8      # 1000 ft
Z = log2(W * 156543.03392 * cos(φ) / 304.8)
```

Pinned at Duluth (φ ≈ 46.78°, cos φ ≈ 0.685):

| Screen W (px) | Zoom | Actual width |
|---|---|---|
| 720 | 18 | ~900 ft |
| 1080 | 19 | ~1000 ft |
| 1440 | 19 | ~1100 ft |

Snap to integer zoom; ±10% on the spec is well within "approximately
1000 ft."

On `onSizeChanged` (rotation), recompute.

---

## Build order (proposed)

1. Scaffolding — add `play-services-location` + `osmdroid` to
   `app/build.gradle`. Add `INTERNET` + `ACCESS_FINE_LOCATION` (+)
   `ACCESS_COARSE_LOCATION` to manifest. Confirm Gradle builds.
2. `MapTileSource` + config — enum, factory, `BuildConfig` fields
   (`MAP_TILE_SOURCE`, `MAP_TILE_API_KEY`), `local.properties` plumbing.
3. `MapBackgroundView` — osmdroid `MapView` subclass with: no
   controls, no zoom buttons, no compass, no MyLocation overlay dot,
   no markers. Set tile source from `BuildConfig`. Override
   `onSizeChanged` to compute the right zoom from width + last-known-lat.
4. `LocationProvider` — `FusedLocationProviderClient` wrapper,
   lifecycle-aware, posts `LatLng` to the `MapBackgroundView`.
5. `MainActivity` wiring — request `ACCESS_FINE_LOCATION`, place
   `MapBackgroundView` first in the `FrameLayout` (so the existing
   `GlassView` stays on top), set initial center to last-known
   location (or fallback — see Question 6).
6. Foreground check — boot the app, screenshot → map visible
   everywhere the main band is transparent. Boot with simulated
   high current → map disappears under the band, perimeter + status
   unaffected.
7. JVM tests — zoom-math unit tests, BuildConfig key redaction test.
8. Live field test — walk/ride the bike around the block, confirm
   center follows, tiles prefetch, no jank. Verify on the connected
   Android test device.
9. Polish — tile-disk-cache size cap, graceful behavior when offline
   (cached tiles show, no spinner), status-line addition (Question 5).

---

## Open questions

1. ~~**Path confirmation** — is the target `cyc-glass` (the Android
   app) or `cygnus-bike` (the Python tooling)?~~ **RESOLVED** →
   `cyc-glass`.
2. ~~**Map provider** — OSM raster, Stadia Maps, or MapTiler?~~
   **RESOLVED** → OSM raster, no API key.
3. ~~**Offline tile pack** for v1, or online-only with disk cache?~~
   **RESOLVED** → online-only, 600 MB cache cap.
4. ~~**`ACCESS_FINE_LOCATION` only, or also background-location?~~
   **RESOLVED** → foreground-only, no
   `ACCESS_BACKGROUND_LOCATION`.
5. ~~**Status-line GPS info** — append "GPS: 12 sat, ±4 m" to the
   existing status line, or put it elsewhere?~~ **RESOLVED** →
   only "Searching GPS…" while searching, nothing once locked, no
   sat-lock info anywhere.
6. ~~**Default lat/lon when there's no GPS fix** — downtown Duluth,
   last-known stored in SharedPreferences, or "Searching GPS…"
   black background?~~ **RESOLVED** → last-known from
   `SharedPreferences` if present, otherwise solid black
   background. No centered overlay on the map area.

---

## Changelog

- 2026-06-01 — Initial draft. Plan written to file. Awaiting
  answers to open questions.
- 2026-06-01 — Q1 RESOLVED. Target is `cyc-glass` (Android app).
  Plan locked to the Android app.
- 2026-06-01 — Q2 RESOLVED. Map provider is OpenStreetMap raster
  tiles (no API key). UA configured per OSM policy.
- 2026-06-01 — Q3 RESOLVED. Online-only tiles with 600 MB disk
  cache cap. No bundled or side-loadable pack in v1.
- 2026-06-01 — Q4 RESOLVED. Foreground-only GPS. No
  `ACCESS_BACKGROUND_LOCATION` requested.
- 2026-06-01 — Q5 RESOLVED. Status line only shows GPS when
  searching ("Searching GPS…"). No sat-lock info once a fix is
  acquired.
- 2026-06-01 — Q6 RESOLVED. Default map state on cold start:
  last-known from `SharedPreferences` if present, otherwise solid
  black background. No centered overlay on the map area.
- 2026-06-01 — All open questions resolved. Plan is final. Ready
  for Owen's approval to start implementation.
