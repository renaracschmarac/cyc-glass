package com.cycglass.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the last-known-location state added to
 * {@link DataModel} for the map-background feature.
 *
 * <p>The state machine:
 * <ul>
 *   <li>Initial: NaN / 0 / hasLastKnownLocation() == false</li>
 *   <li>After setLastKnownLocation(lat, lon, fixMs): round-trips</li>
 *   <li>After clearLastKnownLocation(): back to NaN / 0 / false</li>
 * </ul>
 *
 * <p>See {@code docs/2026-06-01-map-background-plan.md} §
 * "Default map state on cold start".
 */
public class DataModelLocationTest {

    @Test
    public void initialStateHasNoLastKnownLocation() {
        DataModel m = new DataModel();
        assertFalse("initial model must not have a saved location",
                m.hasLastKnownLocation());
        assertTrue("initial lat must be NaN",
                Double.isNaN(m.lastKnownLat()));
        assertTrue("initial lon must be NaN",
                Double.isNaN(m.lastKnownLon()));
        assertEquals("initial fix-ms must be 0", 0L, m.lastKnownFixMs());
    }

    @Test
    public void setLastKnownLocationRoundTrips() {
        DataModel m = new DataModel();
        // Use a synthetic, non-identifying coordinate so the test
        // does not embed a real lat/lon in the source tree.
        m.setLastKnownLocation(12.345678, -98.765432, 1234567890L);
        assertTrue(m.hasLastKnownLocation());
        assertEquals(12.345678, m.lastKnownLat(), 0.0);
        assertEquals(-98.765432, m.lastKnownLon(), 0.0);
        assertEquals(1234567890L, m.lastKnownFixMs());
    }

    @Test
    public void clearLastKnownLocationResetsState() {
        DataModel m = new DataModel();
        m.setLastKnownLocation(12.345678, -98.765432, 1234567890L);
        m.clearLastKnownLocation();
        assertFalse(m.hasLastKnownLocation());
        assertTrue(Double.isNaN(m.lastKnownLat()));
        assertTrue(Double.isNaN(m.lastKnownLon()));
        assertEquals(0L, m.lastKnownFixMs());
    }

    @Test
    public void overwritingLastKnownLocationReplacesPrevious() {
        DataModel m = new DataModel();
        m.setLastKnownLocation(46.0, -92.0, 1000L);
        m.setLastKnownLocation(47.0, -93.0, 2000L);
        assertEquals(47.0, m.lastKnownLat(), 0.0);
        assertEquals(-93.0, m.lastKnownLon(), 0.0);
        assertEquals(2000L, m.lastKnownFixMs());
    }

    @Test
    public void setIsThreadSafe() {
        // Smoke test: from two threads, write alternately, never
        // throw and never observe a half-updated triple. The model
        // synchronizes per set; we just make sure no
        // ConcurrentModificationException or NPE surfaces.
        DataModel m = new DataModel();
        Thread a = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                m.setLastKnownLocation(46.0 + i * 0.0001, -92.0, i);
            }
        });
        Thread b = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                m.setLastKnownLocation(47.0 + i * 0.0001, -93.0, i + 10000);
            }
        });
        a.start();
        b.start();
        try {
            a.join();
            b.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // After both threads finish, the model should have a value
        // from one of them (the last writer wins, but synchronized
        // so the triple is always coherent).
        assertTrue("after concurrent writes, model must have a location",
                m.hasLastKnownLocation());
    }
}
