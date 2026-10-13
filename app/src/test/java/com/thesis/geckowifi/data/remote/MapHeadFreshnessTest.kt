package com.thesis.geckowifi.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/** [isMapHeadStale]: the G4 replay guard (see docs/experiments.md). */
class MapHeadFreshnessTest {

    private val maxAge = 24.hours.inWholeNanoseconds
    private val now = 1_800_000_000_000_000_000L // arbitrary "now" in nanoseconds
    private val hourNanos = 3_600_000_000_000L

    @Test
    fun freshMapHead_isNotStale() {
        assertFalse(isMapHeadStale(now - 1 * hourNanos, now, maxAge))
        assertFalse(isMapHeadStale(now - 23 * hourNanos, now, maxAge))
    }

    @Test
    fun oldMapHead_isStale() {
        assertTrue(isMapHeadStale(now - 25 * hourNanos, now, maxAge))
        assertTrue(isMapHeadStale(now - 100 * hourNanos, now, maxAge)) // a replayed old answer (G4)
    }

    @Test
    fun exactlyMaxAge_isNotStale_butJustOverIs() {
        assertFalse(isMapHeadStale(now - maxAge, now, maxAge))
        assertTrue(isMapHeadStale(now - maxAge - 1, now, maxAge))
    }

    @Test
    fun futureTimestamp_fromClockSkew_isNotStale() {
        assertFalse(isMapHeadStale(now + 5 * hourNanos, now, maxAge))
    }
}
