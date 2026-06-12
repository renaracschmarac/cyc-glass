package com.cycglass.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;

/**
 * Full-screen osmdroid {@link MapView} that lives behind the {@link GlassView}
 * as the app's dynamic GPS-centered backdrop.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>Zoom level (scale) is user-controllable via on-screen +/-
 *       buttons (styled like classic transparent OSM/Google map
 *       controls) and simple gestures (pinch-to-zoom + double-tap).
 *       See GlassView and MainActivity.</li>
 *   <li>The map is *always* kept centered on the current GPS location,
 *       no matter what zoom the user chooses. Any attempt to shift the
 *       center (via gesture, scroll, etc.) is immediately corrected.
 *       Single-touch drags are swallowed to block panning. This is the
 *       core "keep centered on the GPS location, regardless of zoom"
 *       requirement.</li>
 *   <li>Default/initial scale: screen width ≈ 1000 ft (304.8 m). This
 *       is applied only until the user manually zooms; after that the
 *       user's chosen zoom level is preserved (including across
 *       rotations) while the center continues to follow GPS.</li>
 *   <li>Initial center is set via {@link #setInitialCenter(GeoPoint)};
 *       until that happens we render a solid black background
 *       (no tiles, no centered text).</li>
 *   <li>Subsequent GPS updates come from {@link #recenterTo(GeoPoint)};
 *       the map smoothly animates the center to the new GPS fix while
 *       preserving whatever zoom level the user has chosen.</li>
 * </ul>
 *
 * <p>See {@code docs/2026-06-01-map-background-plan.md} (original
 * backdrop design) and the zoom-controls feature addition.
 */
public final class MapBackgroundView extends MapView {

    /** Tile size in pixels (OSM's default is 256×256). */
    private static final int TILE_SIZE_PX = 256;

    /**
     * WGS-84 equatorial circumference (meters) divided by the tile
     * size, then by 2π. The standard OSM zoom-level formula. The
     * latitude term is applied per-call.
     */
    private static final double METERS_PER_PX_AT_EQUATOR_AT_ZOOM_0 =
            156543.03392;

    /** Target horizontal field of view in meters: 1000 ft. */
    private static final double TARGET_WIDTH_M = 304.8;

    private final Paint blackFill = new Paint();
    @Nullable private GeoPoint pendingCenter;
    private int lastZoomLevel = -1;
    private boolean userHasZoomed = false;

    /** The last GPS location we were told to center on. We keep the map
     *  strictly centered here at all times, even when the user changes
     *  the zoom level via buttons or pinch. This enforces the core
     *  "keep centered on the GPS location" requirement. */
    @Nullable private GeoPoint currentGpsCenter;

    public MapBackgroundView(Context context) {
        super(context);
        configure(context);
    }

    public MapBackgroundView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        configure(context);
    }

    public MapBackgroundView(Context context,
                             @Nullable AttributeSet attrs,
                             int defStyleAttr) {
        // osmdroid's 3-arg MapView constructor takes
        // (Context, MapTileProviderBase, AttributeSet) — not
        // (Context, AttributeSet, int). Bypass it and use the
        // 2-arg path; the defStyleAttr is not honored, but the
        // view is created programmatically in MainActivity, so
        // style attrs from XML are not in play.
        super(context, attrs);
        configure(context);
    }

    private void configure(Context context) {
        blackFill.setColor(Color.BLACK);
        blackFill.setStyle(Paint.Style.FILL);

        // Multi-touch (pinch) is enabled so forwarded gestures from
        // User-controlled zoom (buttons + pinch handled in GlassView) is
        // supported, but we keep multi-touch controls *off* on the map
        // itself so that the internal gesture cannot shift the center
        // away from GPS. All zoom is applied explicitly, followed by an
        // immediate setCenter(currentGpsCenter). Single-touch is swallowed
        // to block panning. This strictly protects "always centered on
        // the GPS location, regardless of zoom level".
        setMultiTouchControls(false);
        setBuiltInZoomControls(false);
        setHorizontalMapRepetitionEnabled(false);
        setVerticalMapRepetitionEnabled(false);
        setTilesScaledToDpi(false);
        setFlingEnabled(false);

        // We provide our own black background, so make the map's own
        // background transparent. Without this osmdroid paints its
        // own background and the black-fill branch never shows.
        setBackgroundColor(Color.TRANSPARENT);

        // Single-touch drags are swallowed (no user pan). The clickable/
        // focusable flags keep the view from stealing focus from the HUD.
        setClickable(false);
        setFocusable(false);

        // Listen for zoom/scroll so we can (a) remember the user's zoom
        // choice and (b) *immediately* snap the center back to the last
        // known GPS location. This is the key protection for the
        // "keep centered on GPS regardless of zoom" requirement.
        addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent event) {
                if (currentGpsCenter != null) {
                    getController().setCenter(currentGpsCenter);
                }
                return false;
            }
            @Override public boolean onZoom(ZoomEvent event) {
                lastZoomLevel = (int) Math.round(event.getZoomLevel());
                userHasZoomed = true;
                if (currentGpsCenter != null) {
                    getController().setCenter(currentGpsCenter);
                }
                return false;
            }
        });
    }

    /**
     * Sets the heading of the map. The map is rotated around the
     * screen center (which is the GPS location) by {@code -degrees}
     * — osmdroid's rotation is clockwise-positive, so we negate to
     * keep the user's forward direction at the top of the screen.
     *
     * <p>Callers (the {@code OrientationProvider} listener) are
     * expected to invoke this on the main thread.
     *
     * @param degrees heading in degrees from magnetic north,
     *               normalized to [0, 360)
     */
    public void setHeading(float degrees) {
        setMapOrientation(-degrees);
    }

    /**
     * Zoom in one level. After the zoom we *immediately* force the center
     * back to the last known GPS location (if any). This, together with
     * the MapListener and GlassView's explicit handling, guarantees the
     * map stays centered on GPS no matter what the user does with zoom.
     */
    public void zoomIn() {
        getController().zoomIn();
        if (currentGpsCenter != null) {
            getController().setCenter(currentGpsCenter);
        }
        lastZoomLevel = (int) Math.round(getZoomLevelDouble());
        userHasZoomed = true;
    }

    /**
     * Zoom out one level, then force center back to GPS (see zoomIn).
     */
    public void zoomOut() {
        getController().zoomOut();
        if (currentGpsCenter != null) {
            getController().setCenter(currentGpsCenter);
        }
        lastZoomLevel = (int) Math.round(getZoomLevelDouble());
        userHasZoomed = true;
    }

    /**
     * Sets an arbitrary zoom level and *immediately* forces the center
     * back to the last known GPS location. Used by the pinch gesture
     * handler in GlassView so that pinch always scales the map *around*
     * the GPS position on screen (the blue marker stays fixed in the
     * middle of the view). This is the core of the "keep centered on
     * GPS location, regardless of zoom" guarantee.
     */
    public void setZoomLevel(double zoomLevel) {
        getController().setZoom(zoomLevel);
        lastZoomLevel = (int) Math.round(zoomLevel);
        userHasZoomed = true;
        if (currentGpsCenter != null) {
            getController().setCenter(currentGpsCenter);
        }
    }

    /**
     * Sets the initial center if we don't yet have one, OR animates to
     * the given center if we already do. Safe to call multiple times.
     *
     * <p>Animation is done on the UI thread via osmdroid's
     * {@link #getController()} which queues to the looper.
     */
    public void recenterTo(GeoPoint point) {
        if (point == null) return;
        currentGpsCenter = point;   // always remember the authoritative GPS center
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            if (pendingCenter == null) {
                pendingCenter = point;
                getController().setCenter(point);
                if (lastZoomLevel > 0) {
                    getController().setZoom(lastZoomLevel);
                }
                invalidate();
            } else {
                // animateTo uses osmdroid's internal animation; if
                // called too often in quick succession it short-
                // circuits, which is the behavior we want.
                getController().animateTo(point);
            }
        });
    }

    /**
     * Test-only hook to inject a center without going through the
     * looper. Used by {@code MapBackgroundViewTest} for the zoom-math
     * checks (no Android instrumentation required).
     */
    void setInitialCenterForTest(GeoPoint point) {
        this.pendingCenter = point;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Only auto-apply the 1000-ft default scale while the user has
        // never manually zoomed. After the first zoomIn/zoomOut/pinch
        // we preserve whatever logical zoom level the user chose
        // (including across rotations).
        if (!userHasZoomed) {
            recomputeZoomForWidth(w, pendingCenter);
        } else if (lastZoomLevel > 0) {
            // Keep the user's zoom level explicitly (rotation can
            // sometimes reset internal state in the view).
            getController().setZoom(lastZoomLevel);
            if (currentGpsCenter != null) {
                getController().setCenter(currentGpsCenter);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Allow multi-touch events (pinch zoom) to be processed by the
        // superclass / osmdroid (when multiTouchControls is enabled).
        // Single-touch is still swallowed to prevent any user panning
        // of the GPS-centered backdrop.
        if (event.getPointerCount() > 1) {
            return super.onTouchEvent(event);
        }
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1) {
            return super.dispatchTouchEvent(event);
        }
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Before we have a center, paint the canvas black ourselves
        // and skip osmdroid's tile-drawing code (which would otherwise
        // try to fetch tiles at lat 0, lon 0). Once we have a center,
        // fall through to the normal draw.
        if (pendingCenter == null) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), blackFill);
            return;
        }
        super.dispatchDraw(canvas);
    }

    /**
     * Computes the integer OSM zoom level such that the screen width
     * shows roughly {@link #TARGET_WIDTH_M} meters at the current
     * latitude, and applies it.
     *
     * <p>Formula: {@code Z = log2(W * 156543.03392 * cos(φ) / 304.8)}
     * — see the plan. We clamp Z to the range osmdroid supports
     * (0..MAX_ZOOM_LEVEL, default 22).
     *
     * <p>After the user has manually zoomed (via buttons or gesture)
     * this method becomes a no-op; we preserve the user's chosen
     * logical zoom level.
     */
    void recomputeZoomForWidth(int widthPx, @Nullable GeoPoint around) {
        if (userHasZoomed) {
            return; // user controls zoom now; keep their level
        }
        if (widthPx <= 0) return;
        double lat = (around != null) ? around.getLatitude() : 0.0;
        double cosLat = Math.cos(Math.toRadians(lat));
        // Near the poles, cos(φ) collapses toward zero and
        // log2(tiny) → -∞, which would clamp to a wildly negative Z.
        // Floor cosLat at 0.001 (lat ≈ 89.4°); for any sane
        // mid-latitude use case (Duluth is 46.78°), this is a no-op.
        if (cosLat < 0.001) cosLat = 0.001;
        double exactZ = Math.log(
                widthPx * METERS_PER_PX_AT_EQUATOR_AT_ZOOM_0 * cosLat
                        / TARGET_WIDTH_M) / Math.log(2.0);
        int z = (int) Math.round(exactZ);
        // osmdroid 6.1.x exposes getMinZoomLevel() / getMaxZoomLevel()
        // as double-typed accessor pairs; clamp with explicit casts so
        // we don't get a "lossy conversion" error in any of them.
        int minZ = (int) Math.floor(getMinZoomLevel());
        int maxZ = (int) Math.floor(getMaxZoomLevel());
        if (z < minZ) z = minZ;
        if (z > maxZ) z = maxZ;
        lastZoomLevel = z;
        getController().setZoom(z);
    }

    /** Visible for testing — exposes the computed zoom level. */
    int lastZoomLevelForTest() { return lastZoomLevel; }

    /** Visible for testing — exposes whether we have a center yet. */
    boolean hasCenterForTest() { return pendingCenter != null; }

    /**
     * Computes the OSM zoom level for a given screen width and
     * latitude, with no Android dependency. Package-private so the
     * unit test can call it directly.
     */
    static int computeZoomForWidth(int widthPx, double latitudeDeg) {
        if (widthPx <= 0) return 0;
        double cosLat = Math.cos(Math.toRadians(latitudeDeg));
        if (cosLat < 0.001) cosLat = 0.001;
        double exactZ = Math.log(
                widthPx * METERS_PER_PX_AT_EQUATOR_AT_ZOOM_0 * cosLat
                        / TARGET_WIDTH_M) / Math.log(2.0);
        return (int) Math.round(exactZ);
    }
}
