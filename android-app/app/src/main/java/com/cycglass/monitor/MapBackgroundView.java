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

/**
 * Full-screen, no-controls osmdroid {@link MapView} that lives behind
 * the {@link GlassView} as the app's dynamic GPS-centered backdrop.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>No zoom buttons, no compass, no MyLocation overlay dot, no
 *       markers, no multi-touch zoom (gestures are swallowed so a
 *       stray touch on the map never pans it).</li>
 *   <li>Default scale: screen width ≈ 1000 ft (304.8 m) — the zoom is
 *       recomputed in {@link #onSizeChanged(int, int, int, int)}.</li>
 *   <li>Initial center is set via {@link #setInitialCenter(GeoPoint)};
 *       until that happens we render a solid black background
 *       (no tiles, no centered text).</li>
 *   <li>Subsequent updates come from {@link #recenterTo(GeoPoint)};
 *       the map smoothly animates to the new center.</li>
 * </ul>
 *
 * <p>This view is intentionally minimal. Pan/zoom/markers are out of
 * scope for v1 — it's a backdrop, not a map app.
 *
 * <p>See {@code docs/2026-06-01-map-background-plan.md}.
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

        // Disable every UI affordance osmdroid offers. This is a
        // backdrop, not an interactive map.
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

        // Swallow touches. The GlassView above us handles taps (gear
        // icon); the map underneath must not steal them.
        setClickable(false);
        setFocusable(false);
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
        recomputeZoomForWidth(w, pendingCenter);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Swallow all touches — the GlassView on top owns the gesture.
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
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
     */
    void recomputeZoomForWidth(int widthPx, @Nullable GeoPoint around) {
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
