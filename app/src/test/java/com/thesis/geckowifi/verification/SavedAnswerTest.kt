package com.thesis.geckowifi.verification

import com.thesis.geckowifi.ZURICH_AREA
import com.thesis.geckowifi.data.local.SavedLookup
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.PortalDomain
import com.thesis.geckowifi.data.model.PortalDomainRole
import com.thesis.geckowifi.data.model.PortalTLSIdentity
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiIdentity
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.data.remote.GeckoResponse
import com.thesis.geckowifi.discovery.HopSource
import com.thesis.geckowifi.discovery.ObservedHop
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The saved answer standing in for an unreachable map server (D25): when it
 * applies, and that a portal check judges with it and says so.
 */
class SavedAnswerTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private val here = 47.3765 to 8.5482

    private val visitors = GeoCertificate(
        schemaVersion = 3, certificateId = "eth-visitors",
        wifi = WiFiIdentity(ssid = "eth-visitors", authModeNames = listOf("open")),
        portal = PortalTLSIdentity(listOf(PortalDomain("enter-guest-net.ethz.ch", PortalDomainRole.PRIMARY, listOf("keyPortal")))),
        areas = listOf(ZURICH_AREA), notValidAfter = "2031-01-01T00:00:00Z"
    )

    private fun saved(age: Duration = Duration.ofHours(1), radius: Int = 500, at: Pair<Double, Double> = here) = SavedLookup(
        fetchedAt = now.minus(age).toEpochMilli(), lat = at.first, lng = at.second, radiusMeters = radius,
        route = "Wi-Fi", viaSsid = "GeckoTest", certificates = listOf(visitors)
    )

    private fun answer(lookup: SavedLookup?, radius: Int = 50) =
        savedAnswer(lookup, here.first, here.second, radius, now)

    @Test
    fun recentAndCoveringTheQuery_applies_markedAsSaved() {
        val a = answer(saved())!!
        assertEquals(saved().fetchedAt, a.savedAt)
    }

    @Test
    fun tooOld_doesNotApply() {
        assertNull(answer(saved(age = Duration.ofHours(3))))
    }

    /**
     * D26: outside its area the answer may lack registrations, also more of the same SSID, so a
     * certificate for the SSID that reaches the new spot is no longer enough.
     */
    @Test
    fun fetchedForAnotherArea_doesNotApply_evenWithACertificateForTheSsid() {
        val elsewhere = saved(radius = 50, at = 47.3800 to 8.5482) // about 400 m north
        assertTrue(visitors.coversLocation(here.first, here.second, 50))
        assertNull(answer(elsewhere))
    }

    @Test
    fun appliesAt_onlyWhileTheWholeQueryCircleIsInsideTheSavedArea() {
        val north = 47.3765 + 200.0 / 111_195 // about 200 m north of where it was fetched
        val lookup = saved(radius = 253)
        assertTrue(lookup.appliesAt(north, here.second, 50, now))   // 200 + 50 <= 253
        assertFalse(lookup.appliesAt(north, here.second, 60, now))  // 200 + 60 > 253: the fix is too vague for the area left
        assertFalse(lookup.appliesAt(47.3765 + 300.0 / 111_195, here.second, 10, now))
    }

    @Test
    fun isExpired_afterTheMaximumAge() {
        assertFalse(saved(age = Duration.ofMinutes(119)).isExpired(now))
        assertTrue(saved(age = Duration.ofMinutes(121)).isExpired(now))
    }

    @Test
    fun distance_isPlausible() {
        assertEquals(389.0, distanceMeters(47.3765, 8.5482, 47.3800, 8.5482), 5.0)
    }

    private fun hop(index: Int, url: String, status: Int, key: String? = null): ObservedHop {
        val uri = java.net.URI(url)
        return ObservedHop(
            index = index, url = url, scheme = uri.scheme, host = uri.host,
            port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80,
            statusCode = status, presentedSpkiHash = key, source = HopSource.Kind.REDIRECT_PROBE, timestampMillis = 0
        )
    }

    @Test
    fun portalCheck_mapServerUnreachable_judgesWithTheSavedAnswer() = runTest {
        val unreachable: GeckoClient = mockk { coEvery { queryLocation(any(), any(), any()) } returns GeckoResponse.Unreachable("blocked") }
        val engine = VerificationEngine(unreachable, mockk(relaxed = true), DecisionCache(), GeoQueryEncoder()) { now }
        val hops = listOf(
            hop(0, "http://connectivitycheck.gstatic.com/generate_204", 303),
            hop(1, "https://enter-guest-net.ethz.ch/c/s/1", 200, key = "keyPortal")
        )

        val live = engine.verifyPortalHops("net", hops, true, here.first, here.second, null, 50, "eth-visitors")
        assertEquals(VerificationState.UNREACHABLE, live.overall.state)

        engine.savedAnswer = { lat, lng, r, _ -> savedAnswer(saved(), lat, lng, r, now) }
        val fromSaved = engine.verifyPortalHops("net", hops, true, here.first, here.second, null, 50, "eth-visitors")
        assertEquals(VerificationState.VERIFIED, fromSaved.overall.state)
        assertEquals(saved().fetchedAt, fromSaved.overall.savedAt)

        val wrongKey = engine.verifyPortalHops("net", listOf(hops[0], hops[1].copy(presentedSpkiHash = "attacker")), true,
            here.first, here.second, null, 50, "eth-visitors")
        assertEquals(VerificationState.CONFLICT, wrongKey.overall.state)
    }
}
