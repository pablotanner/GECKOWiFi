package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.local.SavedLookup
import com.thesis.geckowifi.eduroam.SAVED_REGISTRATION_MAX_AGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** When GECKO looks the place up again (D26): on need, not on a timer. */
class SavedAnswerUpkeepTest {
    private val now = Instant.parse("2026-10-27T12:00:00Z")
    private val here = 47.3765 to 8.5482
    private val checkRadius = 100

    private fun saved(age: Duration = Duration.ofMinutes(5), confirmedAgo: Duration? = null, radius: Int = 253) = SavedLookup(
        fetchedAt = now.minus(age).toEpochMilli(), lat = here.first, lng = here.second, radiusMeters = radius,
        route = "Wi-Fi", mapHeadMillis = 1_000L, confirmedAt = confirmedAgo?.let { now.minus(it).toEpochMilli() },
        certificates = emptyList()
    )

    private fun upkeep(lookup: SavedLookup?, metresNorth: Double = 0.0) =
        savedAnswerUpkeep(lookup, here.first + metresNorth / 111_195, here.second, checkRadius, now)

    @Test
    fun noSavedAnswer_fetches() {
        assertEquals(SavedAnswerUpkeep.FETCH, upkeep(null))
    }

    @Test
    fun recentAndCovering_needsNothing() {
        // the app is open at the same place: no download, unlike the old 10-minute timer
        assertEquals(SavedAnswerUpkeep.NONE, upkeep(saved()))
        assertEquals(SavedAnswerUpkeep.NONE, upkeep(saved(), metresNorth = 150.0)) // 150 + 100 <= 253
    }

    @Test
    fun leavingTheArea_fetches_whileAMaximalCheckStillFits() {
        // replaced once a 100 m check no longer fits, i.e. before the device leaves the area
        assertEquals(SavedAnswerUpkeep.FETCH, upkeep(saved(), metresNorth = 160.0))
    }

    @Test
    fun gettingOld_confirmsInsteadOfDownloading() {
        val halfLife = SAVED_REGISTRATION_MAX_AGE.dividedBy(2)
        assertEquals(SavedAnswerUpkeep.NONE, upkeep(saved(age = halfLife.minusMinutes(1))))
        assertEquals(SavedAnswerUpkeep.CONFIRM, upkeep(saved(age = halfLife)))
    }

    @Test
    fun expired_fetches() {
        assertEquals(SavedAnswerUpkeep.FETCH, upkeep(saved(age = SAVED_REGISTRATION_MAX_AGE.plusMinutes(1))))
    }

    @Test
    fun confirmedUnchanged_countsAsCurrent() {
        // fetched long ago, but the map head was the same 10 minutes ago: still exactly current
        val old = saved(age = SAVED_REGISTRATION_MAX_AGE.multipliedBy(3), confirmedAgo = Duration.ofMinutes(10))
        assertFalse(old.isExpired(now))
        assertTrue(old.appliesAt(here.first, here.second, checkRadius, now))
        assertEquals(SavedAnswerUpkeep.NONE, upkeep(old))
        assertEquals(now.minus(Duration.ofMinutes(10)).toEpochMilli(), old.currentAt)
    }

    @Test
    fun oldConfirmation_expiresLikeAnOldFetch() {
        val stale = saved(age = Duration.ofDays(1), confirmedAgo = SAVED_REGISTRATION_MAX_AGE.plusMinutes(1))
        assertTrue(stale.isExpired(now))
        assertEquals(SavedAnswerUpkeep.FETCH, upkeep(stale))
    }
}
