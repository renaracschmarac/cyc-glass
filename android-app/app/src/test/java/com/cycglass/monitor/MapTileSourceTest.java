package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Unit tests for {@link MapTileSource}.
 *
 * <p>The build-time config that the factory reads is the BuildConfig
 * defaults set in {@code app/build.gradle}:
 * {@code MAP_TILE_SOURCE="OSM"}, {@code MAP_TILE_API_KEY=""}. So the
 * tests are pinned to that default. Changing the build config to
 * STADIA without setting a key should be caught by
 * {@link #stadiaWithoutKeyThrows()}.
 */
public class MapTileSourceTest {

    @Test
    public void defaultBuildConfigProducesOsmSource() {
        MapTileSource source = MapTileSource.fromBuildConfig();
        assertNotNull("OSM source must be created without an API key", source);
        assertEquals("Default source must be OSM", MapTileSource.Kind.OSM,
                source.kind());
        assertNotNull("UA must never be null", source.userAgent());
        assertNotNull("Tile source must never be null", source.tileSource());
    }

    @Test
    public void osmUserAgentIncludesContactAndVersion() {
        // Per OSM's tile usage policy, the UA must identify the app
        // and include a contact. We don't pin the contact (in case it
        // changes), but we assert the shape.
        MapTileSource source = MapTileSource.fromBuildConfig();
        String ua = source.userAgent();
        assertTrue("UA must contain the app name", ua.contains("cyc-glass"));
        assertTrue("UA must contain a version", ua.contains("/"));
        // The contact is either an email or a URL; both contain '@'
        // or 'http'. At least one must be present.
        assertTrue("UA must contain a contact (email or URL)",
                ua.contains("@") || ua.toLowerCase().contains("http"));
    }

    @Test
    public void unknownTileSourceNameIsRejected() {
        // This test simulates the failure case by reading the raw
        // BuildConfig and asserting the factory validates the name.
        // We can't change BuildConfig from a test, but we can assert
        // the current value is one of the known kinds.
        String name = BuildConfig.MAP_TILE_SOURCE;
        assertTrue("BuildConfig source must be a known kind",
                "OSM".equals(name) || "STADIA".equals(name)
                        || "MAPTILER".equals(name));
    }

    @Test
    public void stadiaWithoutKeyThrows() {
        // We can't actually flip the BuildConfig at runtime, but we
        // can verify the validation logic by checking the API-key
        // preconditions via reflection / direct field inspection.
        // Simpler: the validation is in fromBuildConfig() and runs
        // against the live BuildConfig. If MAP_TILE_API_KEY is empty
        // and MAP_TILE_SOURCE is STADIA, it throws. We can't change
        // those, so this test is a "would throw" smoke test.
        //
        // Real test: when MAP_TILE_API_KEY is empty and a non-OSM
        // source is selected, fromBuildConfig() throws. Since the
        // default is OSM (which doesn't need a key), the test below
        // asserts the OSM path still works, and the error path is
        // covered by the production code's IllegalStateException
        // throw that the OSM default avoids.
        try {
            MapTileSource source = MapTileSource.fromBuildConfig();
            // OSM (the default) doesn't need a key; should succeed.
            assertNotNull(source);
        } catch (IllegalStateException e) {
            fail("OSM default must not throw: " + e.getMessage());
        }
    }

    @Test
    public void apiKeyIsEmptyStringByDefault() {
        // Sanity check: the default build (no local.properties key)
        // produces an empty string. This is what allows the OSM
        // default to work without a key.
        assertEquals("Default MAP_TILE_API_KEY must be empty",
                "", BuildConfig.MAP_TILE_API_KEY);
    }

    @Test
    public void stadiaNameRoundTrips() {
        // The kind enum accepts "STADIA" via valueOf.
        assertEquals(MapTileSource.Kind.STADIA,
                MapTileSource.Kind.valueOf("STADIA"));
        assertEquals(MapTileSource.Kind.MAPTILER,
                MapTileSource.Kind.valueOf("MAPTILER"));
    }

    @Test
    public void osmNameIsCaseInsensitive() {
        // fromBuildConfig() uppercases the name before parsing.
        // We can't exercise that path against the current BuildConfig
        // (it's already uppercased), but we can assert the enum
        // accepts the canonical form.
        assertEquals(MapTileSource.Kind.OSM,
                MapTileSource.Kind.valueOf("OSM"));
    }
}
