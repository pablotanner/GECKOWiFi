package com.thesis.geckowifi.eduroam

import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA2_ENTERPRISE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA3_ENTERPRISE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA3_ENTERPRISE_192
import com.thesis.geckowifi.eduroam.EapSecurity.WPA2
import com.thesis.geckowifi.eduroam.EapSecurity.WPA3
import com.thesis.geckowifi.network.Akm
import com.thesis.geckowifi.network.Cipher
import com.thesis.geckowifi.network.RsnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [chooseEnterpriseSecurity]: registered × observed, the 192-bit refusal and the API < 31 fallback. */
class EnterpriseSecurityTest {
    private val s = 31
    private val r = 30

    private val wpa2 = RsnProfile(akms = setOf(Akm.EAP_SHA1), pairwise = setOf(Cipher.CCMP))
    private val transition = RsnProfile(akms = setOf(Akm.EAP_SHA1, Akm.EAP_SHA256), pairwise = setOf(Cipher.CCMP), mfpc = true)
    private val wpa3Akm5 = RsnProfile(akms = setOf(Akm.EAP_SHA256), pairwise = setOf(Cipher.CCMP), mfpc = true, mfpr = true)
    /** WPA3-Enterprise by the WFA definition, but Android lists it as plain EAP. */
    private val wpa3Akm1 = RsnProfile(akms = setOf(Akm.EAP_SHA1), pairwise = setOf(Cipher.CCMP), mfpc = true, mfpr = true)
    private val suiteB = RsnProfile(akms = setOf(Akm.EAP_SUITE_B_192), pairwise = setOf(Cipher.GCMP_256), mfpc = true, mfpr = true)

    private fun use(registered: Set<WiFiAuthMode>?, profile: RsnProfile, sdk: Int = s) =
        chooseEnterpriseSecurity(registered, profile, sdk) as SecurityChoice.Use

    private fun refused(registered: Set<WiFiAuthMode>?, profile: RsnProfile, sdk: Int = s) =
        (chooseEnterpriseSecurity(registered, profile, sdk) as SecurityChoice.Refused).reason

    // --- onboarding: registration not known yet, the beacon decides ---

    @Test
    fun unregistered_strongestObservedMode() {
        assertEquals(WPA2, use(null, wpa2).specifier)
        assertEquals(WPA3, use(null, transition).specifier) // a WPA3 specifier matches a transition AP on API 31+
        assertEquals(WPA3, use(null, wpa3Akm5).specifier)
        assertEquals(setOf(WPA3), use(null, wpa3Akm5).suggestions)
    }

    @Test
    fun akm1WithMfpr_getsTheWpa2Builder_becauseAndroidListsItAsEap() {
        assertEquals(WPA2, use(null, wpa3Akm1).specifier)
        assertEquals(WPA2, use(setOf(WPA3_ENTERPRISE), wpa3Akm1).specifier)
        // Registered WPA3 suggestion, plus WPA2 so this access point stays joinable.
        assertEquals(setOf(WPA3, WPA2), use(setOf(WPA3_ENTERPRISE), wpa3Akm1).suggestions)
    }

    // --- registered × observed ---

    @Test
    fun registeredWpa2_transitionAp_usesWpa2() {
        val c = use(setOf(WPA2_ENTERPRISE), transition)
        assertEquals(WPA2, c.specifier)
        assertEquals(setOf(WPA2), c.suggestions)
    }

    @Test
    fun registeredWpa3_transitionAp_usesWpa3() {
        val c = use(setOf(WPA3_ENTERPRISE), transition)
        assertEquals(WPA3, c.specifier)
        assertEquals(setOf(WPA3), c.suggestions)
    }

    @Test
    fun registeredBoth_installsOneSuggestionPerType() {
        val both = setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE)
        assertEquals(Pair(WPA2, setOf(WPA2, WPA3)), use(both, wpa2).let { it.specifier to it.suggestions })
        assertEquals(Pair(WPA3, setOf(WPA2, WPA3)), use(both, transition).let { it.specifier to it.suggestions })
        assertEquals(Pair(WPA3, setOf(WPA2, WPA3)), use(both, wpa3Akm5).let { it.specifier to it.suggestions })
    }

    @Test
    fun registeredWpa2_wpa3OnlyAp_isAnUpgrade_joinedInItsOwnMode() {
        val c = use(setOf(WPA2_ENTERPRISE), wpa3Akm5)
        assertEquals(WPA3, c.specifier)
        assertEquals(setOf(WPA2, WPA3), c.suggestions)
    }

    @Test
    fun registeredWpa3_wpa2OnlyAp_isRefused() {
        assertTrue(refused(setOf(WPA3_ENTERPRISE), wpa2).contains("wpa3-enterprise is registered"))
    }

    @Test
    fun suiteB192_isRefusedEarly() {
        assertTrue(refused(null, suiteB).contains("192-bit"))
        assertTrue(refused(setOf(WPA3_ENTERPRISE_192), suiteB).contains("192-bit"))
        assertTrue(refused(setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE, WPA3_ENTERPRISE_192), suiteB).contains("192-bit"))
    }

    @Test
    fun registered192_isNeverSuggested() {
        assertEquals(setOf(WPA2), use(setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE_192), wpa2).suggestions)
    }

    @Test
    fun notEnterprise_isRefused() {
        assertTrue(refused(null, RsnProfile()).contains("doesn't offer"))
    }

    // --- API 29-30: no Android security types; MFPR + the deprecated WPA3 builder ---

    @Test
    fun belowApi31_wpa3OnlyWhenTheApRequiresPmf() {
        assertEquals(WPA3, use(null, wpa3Akm5, r).specifier)
        assertEquals(WPA3, use(null, wpa3Akm1, r).specifier) // no Android quirk below 31: any EAP AP matches
        assertEquals(WPA2, use(null, transition, r).specifier)
        assertEquals(setOf(WPA2, WPA3), use(setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE), transition, r).suggestions)
    }
}
