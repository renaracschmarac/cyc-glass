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
    public void mpsToMphAtZero() {
        assertEquals(0.0, DataModel.mpsToMph(0.0), 0.0001);
    }

    @Test
    public void kphToMphAtZero() {
        assertEquals(0.0, DataModel.kphToMph(0.0), 0.0001);
    }

    @Test
    public void kphToMphAtCommonBikingSpeed() {
        // 25 km/h ≈ 15.53 mph (typical urban cycling / slow ebike)
        assertEquals(15.534, DataModel.kphToMph(25.0), 0.001);
    }

    @Test
    public void kphToMphScalesLinearly() {
        // 2× the kph should give 2× the mph.
        double one = DataModel.kphToMph(15.0);
        double two = DataModel.kphToMph(30.0);
        assertEquals(2.0 * one, two, 0.0001);
    }

    @Test
    public void kphToMphAndMpsToMphAgreeAtCommonSpeed() {
        // Sanity: 1 m/s ≈ 3.6 km/h, so a single physical speed
        // expressed in m/s and km/h should round-trip through both
        // conversions to the same mph.
        double speedMps = 5.0;
        double speedKph = speedMps * 3.6;  // exact
        assertEquals(
                DataModel.mpsToMph(speedMps),
                DataModel.kphToMph(speedKph),
                0.0001);
    }

    @Test
    public void mpsToMphAtOneMps() {
        // 1 m/s = 1 / 0.44704 mph ≈ 2.236936 mph
        assertEquals(2.236936, DataModel.mpsToMph(1.0), 0.0001);
    }

    @Test
    public void mpsToMphAtCommonBikingSpeed() {
        // 5 m/s ≈ 11.18 mph (typical cycling speed)
        assertEquals(11.1847, DataModel.mpsToMph(5.0), 0.001);
    }

    @Test
    public void mpsToMphRoundTripsToZero() {
        // 0 m/s should round-trip to 0 mph, not some tiny float drift.
        assertEquals(0.0, DataModel.mpsToMph(DataModel.mpsToMph(0.0)), 0.0);
    }

    @Test
    public void initialGpsSpeedIsNaN() {
        // The model starts with no GPS speed, which the speed
        // sign renders as "—".
        DataModel m = new DataModel();
        assertTrue("initial gpsSpeedMph must be NaN",
                Double.isNaN(m.gpsSpeedMph()));
    }

    @Test
    public void setGpsSpeedMphRoundTrips() {
        DataModel m = new DataModel();
        m.setGpsSpeedMph(18.4);
        assertEquals(18.4, m.gpsSpeedMph(), 0.0);
    }

    @Test
    public void setGpsSpeedMphAcceptsNaN() {
        // The model should accept NaN as a "no speed known right
        // now" signal (rare but possible when the GPS provider
        // tears down or the fix is stale).
        DataModel m = new DataModel();
        m.setGpsSpeedMph(20.0);
        m.setGpsSpeedMph(Double.NaN);
        assertTrue(Double.isNaN(m.gpsSpeedMph()));
    }

    @Test
    public void mpsToMphScalesLinearly() {
        // 2× the m/s should give 2× the mph.
        double one = DataModel.mpsToMph(5.0);
        double two = DataModel.mpsToMph(10.0);
        assertEquals(2.0 * one, two, 0.0001);
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
