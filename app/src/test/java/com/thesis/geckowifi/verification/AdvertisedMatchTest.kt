package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.WiFiAuthMode.OPEN
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA2_ENTERPRISE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA2_PERSONAL
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA3_ENTERPRISE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA3_PERSONAL
import com.thesis.geckowifi.data.model.WiFiIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [matchAdvertised]: the list's judgement of a registered network before anyone connects. */
class AdvertisedMatchTest {

    private fun cert(
        id: String = "c",
        ssid: String = "eduroam",
        modes: List<String> = listOf("wpa2-enterprise", "wpa3-enterprise"),
        critical: List<String> = emptyList(),
        shared: Boolean = false
    ) = GeoCertificate(
        certificateId = id,
        critical = critical,
        wifi = WiFiIdentity(ssid = ssid, ssidShared = shared, authModeNames = modes),
        notValidAfter = "2099-01-01T00:00:00Z"
    )

    @Test
    fun unregisteredSsid_isNull() {
        assertNull(matchAdvertised("public", setOf(OPEN), listOf(cert())))
    }

    @Test
    fun registeredSecurityType_matches() {
        assertTrue(matchAdvertised("eduroam", setOf(WPA2_ENTERPRISE), listOf(cert())) is AdvertisedMatch.Matches)
        assertTrue(matchAdvertised("EDUROAM", setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE), listOf(cert())) is AdvertisedMatch.Matches)
    }

    @Test
    fun openCloneOfEnterpriseSsid_isMismatch() {
        val m = matchAdvertised("eduroam", setOf(OPEN), listOf(cert())) as AdvertisedMatch.Mismatch
        assertEquals(listOf("wpa2-enterprise", "wpa3-enterprise"), m.registered)
        assertEquals(setOf(OPEN), m.advertised)
    }

    @Test
    fun personalCloneOfEnterpriseSsid_isMismatch() {
        assertTrue(matchAdvertised("eduroam", setOf(WPA2_PERSONAL), listOf(cert())) is AdvertisedMatch.Mismatch)
    }

    @Test
    fun transitionNetwork_offeringBoth_matches() {
        val transition = listOf(cert(ssid = "Cafe", modes = listOf("wpa2-personal", "wpa3-personal")))
        assertTrue(matchAdvertised("Cafe", setOf(WPA2_PERSONAL, WPA3_PERSONAL), transition) is AdvertisedMatch.Matches)
    }

    @Test
    fun transitionNetwork_offeringOnlyWpa2_isMismatch() {
        // The downgrade: devices are pushed onto WPA2 so the handshake can be cracked offline.
        val transition = listOf(cert(ssid = "Cafe", modes = listOf("wpa2-personal", "wpa3-personal")))
        val m = matchAdvertised("Cafe", setOf(WPA2_PERSONAL), transition) as AdvertisedMatch.Mismatch
        assertEquals(setOf(WPA2_PERSONAL), m.advertised)
    }

    @Test
    fun transitionNetwork_offeringOnlyWpa3_matches() {
        // A 6 GHz access point can only offer WPA3.
        val transition = listOf(cert(ssid = "Cafe", modes = listOf("wpa2-personal", "wpa3-personal")))
        assertTrue(matchAdvertised("Cafe", setOf(WPA3_PERSONAL), transition) is AdvertisedMatch.Matches)
    }

    @Test
    fun wpa2OnlyRegistration_offeringWpa2_matches() {
        val wpa2 = listOf(cert(ssid = "Cafe", modes = listOf("wpa2-personal")))
        assertTrue(matchAdvertised("Cafe", setOf(WPA2_PERSONAL), wpa2) is AdvertisedMatch.Matches)
    }

    @Test
    fun enterpriseTransition_offeringOnlyWpa2_stillMatches() {
        // Not held to the WPA3 rule: the pinned server protects the password, and eduroam sites mix access points.
        assertTrue(matchAdvertised("eduroam", setOf(WPA2_ENTERPRISE), listOf(cert())) is AdvertisedMatch.Matches)
    }

    @Test
    fun anyPermittingCertificate_isEnough() {
        // Two registrations for the SSID here (e.g. during a rotation): one fits.
        val certs = listOf(cert("a", modes = listOf("wpa2-enterprise")), cert("b", modes = listOf("open")))
        assertTrue(matchAdvertised("eduroam", setOf(OPEN), certs) is AdvertisedMatch.Matches)
    }

    @Test
    fun noRegisteredSecurityType_isUndecided() {
        // v2 certificates carry no auth_modes: nothing to compare, no warning.
        assertTrue(matchAdvertised("eduroam", setOf(OPEN), listOf(cert(modes = emptyList()))) is AdvertisedMatch.Undecided)
    }

    @Test
    fun unrecognisedAdvertisement_isUndecided() {
        assertTrue(matchAdvertised("eduroam", emptySet(), listOf(cert())) is AdvertisedMatch.Undecided)
    }

    @Test
    fun unknownCriticalFeature_withholdsTheMismatch() {
        val certs = listOf(cert("a"), cert("b", critical = listOf("future-feature")))
        assertTrue(matchAdvertised("eduroam", setOf(OPEN), certs) is AdvertisedMatch.Undecided)
        assertTrue(matchAdvertised("eduroam", setOf(OPEN), listOf(cert(critical = listOf("x")))) is AdvertisedMatch.Undecided)
    }

    @Test
    fun sharedSsid_withholdsTheMismatch() {
        assertTrue(matchAdvertised("eduroam", setOf(OPEN), listOf(cert(shared = true))) is AdvertisedMatch.Undecided)
    }
}
