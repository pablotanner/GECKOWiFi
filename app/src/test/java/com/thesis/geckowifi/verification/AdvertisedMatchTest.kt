package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.AdvertisedSecurity
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.data.model.WiFiAuthMode.OPEN
import com.thesis.geckowifi.data.model.WiFiAuthMode.OWE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA2_ENTERPRISE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA2_PERSONAL
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA3_ENTERPRISE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA3_PERSONAL
import com.thesis.geckowifi.data.model.WiFiIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [matchAdvertised] and the rule under it ([WiFiIdentity.permits]): the list's
 * judgement of a registered network before anyone connects.
 */
class AdvertisedMatchTest {

    private fun cert(
        id: String = "c",
        ssid: String = "eduroam",
        modes: List<String> = listOf("wpa2-enterprise", "wpa3-enterprise"),
        critical: List<String> = emptyList(),
        shared: Boolean = false,
        pmf: String? = null
    ) = GeoCertificate(
        certificateId = id,
        critical = critical,
        wifi = WiFiIdentity(ssid = ssid, ssidShared = shared, authModeNames = modes, pmf = pmf),
        notValidAfter = "2099-01-01T00:00:00Z"
    )

    private fun adv(vararg modes: WiFiAuthMode, pmfRequired: Boolean? = null) = AdvertisedSecurity(modes.toSet(), pmfRequired)

    private fun matches(ssid: String, advertised: AdvertisedSecurity, certs: List<GeoCertificate>) =
        matchAdvertised(ssid, advertised, certs) is AdvertisedMatch.Matches

    private fun mismatch(ssid: String, advertised: AdvertisedSecurity, certs: List<GeoCertificate>) =
        matchAdvertised(ssid, advertised, certs) is AdvertisedMatch.Mismatch

    private val personalTransition = listOf(cert(ssid = "Cafe", modes = listOf("wpa2-personal", "wpa3-personal")))

    @Test
    fun unregisteredSsid_isNull() {
        assertNull(matchAdvertised("public", adv(OPEN), listOf(cert())))
    }

    @Test
    fun registeredSecurityType_matches() {
        assertTrue(matches("eduroam", adv(WPA2_ENTERPRISE), listOf(cert())))
        assertTrue(matches("EDUROAM", adv(WPA2_ENTERPRISE, WPA3_ENTERPRISE), listOf(cert())))
    }

    @Test
    fun openCloneOfEnterpriseSsid_isMismatch() {
        val m = matchAdvertised("eduroam", adv(OPEN), listOf(cert())) as AdvertisedMatch.Mismatch
        assertEquals(listOf("wpa2-enterprise", "wpa3-enterprise"), m.registered)
        assertEquals(setOf(OPEN), m.advertised)
    }

    @Test
    fun personalCloneOfEnterpriseSsid_isMismatch() {
        assertTrue(mismatch("eduroam", adv(WPA2_PERSONAL), listOf(cert())))
    }

    @Test
    fun enterpriseOnAPersonalRegistration_isMismatch_notAnUpgrade() {
        // Another kind of network altogether, even if "stronger".
        assertTrue(mismatch("Cafe", adv(WPA2_ENTERPRISE), personalTransition))
    }

    // --- floor: weaker than registered ---

    @Test
    fun downgrade_personalTransitionApOnWpa3OnlyRegistration_isMismatch() {
        val wpa3Only = listOf(cert(ssid = "Cafe", modes = listOf("wpa3-personal")))
        assertTrue(mismatch("Cafe", adv(WPA2_PERSONAL, WPA3_PERSONAL), wpa3Only))
    }

    @Test
    fun downgrade_enterpriseTransitionApOnWpa3OnlyRegistration_isMismatch() {
        // The enterprise leniency only covers a missing WPA3, never a weaker floor.
        assertTrue(mismatch("eduroam", adv(WPA2_ENTERPRISE, WPA3_ENTERPRISE), listOf(cert(modes = listOf("wpa3-enterprise")))))
    }

    // --- ceiling: the strongest registered mode stripped ---

    @Test
    fun transitionNetwork_offeringBoth_matches() {
        assertTrue(matches("Cafe", adv(WPA2_PERSONAL, WPA3_PERSONAL), personalTransition))
    }

    @Test
    fun transitionNetwork_offeringOnlyWpa2_isMismatch() {
        // The downgrade: devices are pushed onto WPA2 so the handshake can be cracked offline.
        val m = matchAdvertised("Cafe", adv(WPA2_PERSONAL), personalTransition) as AdvertisedMatch.Mismatch
        assertEquals(setOf(WPA2_PERSONAL), m.advertised)
    }

    @Test
    fun oweTransition_strippedToOpen_isMismatch() {
        val owe = listOf(cert(ssid = "Cafe", modes = listOf("open", "owe")))
        assertTrue(matches("Cafe", adv(OPEN, OWE), owe))
        assertTrue(mismatch("Cafe", adv(OPEN), owe))
    }

    @Test
    fun enterpriseLeniency_wpa2OnlyApOnTransitionRegistration_matches() {
        // Explicitly exempt: the pinned login server protects the password, and eduroam sites mix access points.
        assertTrue(matches("eduroam", adv(WPA2_ENTERPRISE), listOf(cert())))
    }

    // --- upgrade: stronger than registered ---

    @Test
    fun upgrade_isAccepted() {
        assertTrue(matches("Cafe", adv(WPA3_PERSONAL), personalTransition)) // a 6 GHz AP can only offer WPA3
        assertTrue(matches("Cafe", adv(WPA3_PERSONAL), listOf(cert(ssid = "Cafe", modes = listOf("wpa2-personal")))))
        assertTrue(matches("Cafe", adv(WPA2_PERSONAL, WPA3_PERSONAL), listOf(cert(ssid = "Cafe", modes = listOf("wpa2-personal")))))
        assertTrue(matches("eduroam", adv(WPA3_ENTERPRISE), listOf(cert(modes = listOf("wpa2-enterprise")))))
    }

    @Test
    fun wpa2OnlyRegistration_offeringWpa2_matches() {
        assertTrue(matches("Cafe", adv(WPA2_PERSONAL), listOf(cert(ssid = "Cafe", modes = listOf("wpa2-personal")))))
    }

    // --- PMF ---

    @Test
    fun pmfRequired_butApDoesNotRequireIt_isMismatch() {
        val required = listOf(cert(pmf = "required"))
        assertTrue(mismatch("eduroam", adv(WPA2_ENTERPRISE, pmfRequired = false), required))
        assertTrue(matches("eduroam", adv(WPA3_ENTERPRISE, pmfRequired = true), required))
        // Unknown (a demo network): nothing to contradict.
        assertTrue(matches("eduroam", adv(WPA3_ENTERPRISE), required))
        // Only "required" is enforced.
        assertTrue(matches("eduroam", adv(WPA2_ENTERPRISE, pmfRequired = false), listOf(cert(pmf = "optional"))))
    }

    // --- engine rules on top ---

    @Test
    fun anyPermittingCertificate_isEnough() {
        // Two registrations for the SSID here (e.g. during a rotation): one fits.
        val certs = listOf(cert("a", modes = listOf("wpa2-enterprise")), cert("b", modes = listOf("open")))
        assertTrue(matches("eduroam", adv(OPEN), certs))
    }

    @Test
    fun noRegisteredSecurityType_isUndecided() {
        // v2 certificates carry no auth_modes: nothing to compare, no warning.
        assertTrue(matchAdvertised("eduroam", adv(OPEN), listOf(cert(modes = emptyList()))) is AdvertisedMatch.Undecided)
    }

    @Test
    fun unrecognisedAdvertisement_isUndecided() {
        assertTrue(matchAdvertised("eduroam", adv(), listOf(cert())) is AdvertisedMatch.Undecided)
    }

    @Test
    fun unknownCriticalFeature_withholdsTheMismatch() {
        val certs = listOf(cert("a"), cert("b", critical = listOf("future-feature")))
        assertTrue(matchAdvertised("eduroam", adv(OPEN), certs) is AdvertisedMatch.Undecided)
        assertTrue(matchAdvertised("eduroam", adv(OPEN), listOf(cert(critical = listOf("x")))) is AdvertisedMatch.Undecided)
    }

    @Test
    fun sharedSsid_withholdsTheMismatch() {
        assertTrue(matchAdvertised("eduroam", adv(OPEN), listOf(cert(shared = true))) is AdvertisedMatch.Undecided)
    }
}
