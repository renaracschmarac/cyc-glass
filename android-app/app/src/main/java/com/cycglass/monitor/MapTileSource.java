package com.cycglass.monitor;

import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;

import androidx.annotation.NonNull;

/**
 * Map tile source selection for the dynamic map background.
 *
 * <p>Configured at build time via the {@code MAP_TILE_SOURCE} and
 * {@code MAP_TILE_API_KEY} build-config fields in
 * {@code app/build.gradle}. The runtime app code is
 * provider-agnostic; the only swap point is which {@link OnlineTileSourceBase}
 * we hand to osmdroid's {@link MapTileProviderBasic}.
 *
 * <p>OSM raster is the v1 default. The escape hatch to a paid provider
 * is a one-line change in {@link #fromBuildConfig()}.
 *
 * <p>See {@code docs/2026-06-01-map-background-plan.md}.
 */
public final class MapTileSource {

    public enum Kind {
        OSM,
        STADIA,
        MAPTILER
    }

    private final Kind kind;
    private final OnlineTileSourceBase tileSource;
    private final String userAgent;

    private MapTileSource(@NonNull Kind kind,
                          @NonNull OnlineTileSourceBase tileSource,
                          @NonNull String userAgent) {
        this.kind = kind;
        this.tileSource = tileSource;
        this.userAgent = userAgent;
    }

    public Kind kind() { return kind; }
    public OnlineTileSourceBase tileSource() { return tileSource; }
    public String userAgent() { return userAgent; }

    /**
     * Build a tile source from the build-config fields. The OSM case
     * never needs an API key. STADIA and MAPTILER do, and a missing key
     * is treated as a build-time error (we throw — this is a programmer
     * mistake, not a runtime condition).
     */
    public static MapTileSource fromBuildConfig() {
        String name = BuildConfig.MAP_TILE_SOURCE == null
                ? "OSM" : BuildConfig.MAP_TILE_SOURCE.trim().toUpperCase();
        String key = BuildConfig.MAP_TILE_API_KEY == null
                ? "" : BuildConfig.MAP_TILE_API_KEY.trim();
        Kind kind;
        try {
            kind = Kind.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Unknown MAP_TILE_SOURCE '" + name
                            + "'. Expected one of OSM, STADIA, MAPTILER.", e);
        }
        switch (kind) {
            case OSM:
                // OpenStreetMap's published Mapnik tile set. osmdroid's
                // built-in MAPNIK source already points at the canonical
                // URL pattern (a.tile.openstreetmap.org / b / c). We
                // override the attribution and UA below per OSM's tile
                // usage policy.
                return new MapTileSource(
                        Kind.OSM,
                        TileSourceFactory.MAPNIK,
                        buildOsmUserAgent());
            case STADIA:
                if (key.isEmpty()) {
                    throw new IllegalStateException(
                            "MAP_TILE_SOURCE=STADIA but MAP_TILE_API_KEY "
                                    + "is empty. Set MAP_TILE_API_KEY in "
                                    + "local.properties or the environment.");
                }
                // Stadia's "alidade_smooth" is a clean, bike-friendly
                // OSM-derived style.
                return new MapTileSource(
                        Kind.STADIA,
                        new XYTileSource(
                                "Stadia Alidade Smooth",
                                0, 20, 256, ".png",
                                new String[] {
                                        "https://tiles.stadiamaps.com/data/alidade_smooth/"
                                },
                                "© Stadia Maps, © OpenStreetMap contributors"),
                        "cyc-glass/" + BuildConfig.VERSION_NAME
                                + " (Stadia key: " + key + ")");
            case MAPTILER:
                if (key.isEmpty()) {
                    throw new IllegalStateException(
                            "MAP_TILE_SOURCE=MAPTILER but MAP_TILE_API_KEY "
                                    + "is empty. Set MAP_TILE_API_KEY in "
                                    + "local.properties or the environment.");
                }
                // MapTiler's "streets" raster style.
                return new MapTileSource(
                        Kind.MAPTILER,
                        new XYTileSource(
                                "MapTiler Streets",
                                0, 22, 256, ".png",
                                new String[] {
                                        "https://api.maptiler.com/maps/streets/"
                                },
                                "© MapTiler, © OpenStreetMap contributors"),
                        "cyc-glass/" + BuildConfig.VERSION_NAME
                                + " (MapTiler key: " + key + ")");
            default:
                throw new IllegalStateException("Unhandled tile source kind: " + kind);
        }
    }

    /**
     * Builds the User-Agent string we send to OSM. Per the OSM tile
     * usage policy, the UA must identify the app and include a contact
     * (URL or email). We use the project page URL as the contact
     * channel — issue tracker / README there. The same UA is also
     * passed to osmdroid's {@code Configuration.getInstance().
     * userAgentValue} so the tile fetcher doesn't use osmdroid's
     * default (which OSM blocks for unknown-reason traffic).
     */
    private static String buildOsmUserAgent() {
        return "cyc-glass/" + BuildConfig.VERSION_NAME
                + " (+https://github.com/renaracschmarac/cyc-glass)";
    }
}
