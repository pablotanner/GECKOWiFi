package com.thesis.geckowifi

import com.thesis.geckowifi.location.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoofSignalsTest {

    private fun fix(
        lat: Double, lng: Double,
        mock: Boolean = false,
        provider: String = "gps"
    ) = LocationFix(lat, lng, null, 10f, mock, provider, System.currentTimeMillis())

    @Test
    fun `zurich to bern distance is roughly correct`() {
        val meters = SpoofSignals.haversineMeters(47.3769, 8.5417, 46.9480, 7.4474)
        assertTrue("expected ~95km, got ${meters / 1000}km", meters in 90_000.0..100_000.0)
    }

    @Test
    fun `agreeing providers with no flags gives HIGH confidence`() {
        val result = SpoofSignals.assess(SpoofInputs(
            gpsFix = fix(47.3769, 8.5417),
            networkFix = fix(47.3770, 8.5418, provider = "network"),
            gnssCn0Values = listOf(42f, 31f, 27f, 38f, 45f)
        ))
        assertEquals(LocationConfidence.HIGH, result.confidence)
    }

    @Test
    fun `mock provider flag alone drops confidence to LOW`() {
        val result = SpoofSignals.assess(SpoofInputs(
            gpsFix = fix(47.3769, 8.5417, mock = true),
            networkFix = null
        ))
        assertEquals(LocationConfidence.LOW, result.confidence)
    }

    @Test
    fun `provider disagreement is detected`() {
        val result = SpoofSignals.assess(SpoofInputs(
            gpsFix = fix(47.3769, 8.5417),
            networkFix = fix(46.9480, 7.4474, provider = "network")
        ))
        assertEquals(LocationConfidence.MODERATE, result.confidence)
        assertTrue(result.findings.any { it.contains("disagree") })
    }

    @Test
    fun `uniform satellite signal strengths are flagged`() {
        val result = SpoofSignals.assess(SpoofInputs(
            gpsFix = fix(47.3769, 8.5417),
            networkFix = null,
            gnssCn0Values = listOf(40f, 40.1f, 39.9f, 40f, 40.2f)
        ))
        assertTrue(result.findings.any { it.contains("uniform") })
    }

    @Test
    fun `zurich to tokyo in one minute is impossible travel`() {
        val oneMinuteAgo = System.currentTimeMillis() - 60_000
        val impossible = SpoofSignals.impossibleTravel(
            previousLat = 47.3769, previousLng = 8.5417, previousAtMs = oneMinuteAgo,
            currentLat = 35.6762, currentLng = 139.6503, nowMs = System.currentTimeMillis()
        )
        assertTrue(impossible)
    }

    @Test
    fun `zurich to bern in two hours is plausible`() {
        val twoHoursAgo = System.currentTimeMillis() - 2 * 3_600_000
        val impossible = SpoofSignals.impossibleTravel(
            previousLat = 47.3769, previousLng = 8.5417, previousAtMs = twoHoursAgo,
            currentLat = 46.9480, currentLng = 7.4474, nowMs = System.currentTimeMillis()
        )
        assertFalse(impossible)
    }

    @Test
    fun `no previous location means no impossible travel`() {
        assertFalse(SpoofSignals.impossibleTravel(
            null, null, null, 47.3769, 8.5417, System.currentTimeMillis()
        ))
    }
}