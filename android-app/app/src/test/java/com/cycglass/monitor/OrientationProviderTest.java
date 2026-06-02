package com.cycglass.monitor;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * Smoke tests for {@link OrientationProvider}. The bulk of the
 * math is covered by {@link HeadingFilterTest}; these tests pin
 * down the lifecycle contract (idempotent start/stop, safe with
 * a null context) so the wiring in {@code MainActivity} doesn't
 * regress. The actual sensor path is verified in the field.
 */
public class OrientationProviderTest {

    @Test
    public void nullContextReportsUnavailable() {
        // In the JVM unit-test environment, SensorManager is
        // mocked (returns null from getSystemService), so even with
        // a non-null Context, the provider reports unavailable.
        // With a null Context, appContext is null and the
        // constructor must not throw.
        OrientationProvider p = new OrientationProvider(null);
        assertFalse("null context should yield unavailable", p.isAvailable());
    }

    @Test
    public void startIsNoOpWithoutSensor() {
        // The provider must not throw when start() is called and
        // there's no rotation vector sensor (e.g. on a device that
        // lacks one, or in the JVM unit-test environment).
        OrientationProvider p = new OrientationProvider(null);
        p.start();
        p.start();  // second start is a no-op
        p.stop();
        p.stop();  // second stop is a no-op
    }

    @Test
    public void stopBeforeStartIsSafe() {
        OrientationProvider p = new OrientationProvider(null);
        p.stop();  // should not throw
    }

    @Test
    public void addRemoveListenerDoesNotThrow() {
        OrientationProvider p = new OrientationProvider(null);
        OrientationProvider.Listener l = (deg, ms) -> {};
        p.addListener(l);
        p.removeListener(l);
        // Adding twice should be a no-op (we use CopyOnWriteArrayList
        // with addIfAbsent, so verify it stays safe).
        p.addListener(l);
        p.addListener(l);
        p.removeListener(l);
    }

    @Test
    public void lastHeadingMsStartsAtZero() {
        OrientationProvider p = new OrientationProvider(null);
        org.junit.Assert.assertEquals(0L, p.lastHeadingMs());
    }
}
