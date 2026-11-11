package com.thesis.geckowifi.network

import com.thesis.geckowifi.data.model.WiFiAuthMode.OPEN
import com.thesis.geckowifi.data.model.WiFiAuthMode.OWE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WEP
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA2_ENTERPRISE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA2_PERSONAL
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA3_ENTERPRISE
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA3_ENTERPRISE_192
import com.thesis.geckowifi.data.model.WiFiAuthMode.WPA3_PERSONAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RsnProfile] and [admissibleModes] on raw element bytes as they appear in a
 * beacon: `EID LEN body`, little-endian counts. RSN layout: version, group
 * cipher, pairwise count + list, AKM count + list, capabilities (MFPR 0x40,
 * MFPC 0x80), PMKID count, group management cipher.
 */
class SecurityProfileTest {

    /** `"30 14 01 00 ..."` → (48, body); the length byte must match. */
    private fun ie(hex: String): Pair<Int, ByteArray> {
        val bytes = hex.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }
        assertEquals("length byte of $hex", bytes[1].toInt() and 0xFF, bytes.size - 2)
        return (bytes[0].toInt() and 0xFF) to bytes.drop(2).toByteArray()
    }

    private fun modes(vararg ies: String, wep: Boolean = false) = RsnProfile.fromIes(ies.map(::ie), wep).admissibleModes()

    @Test
    fun wpa2Eap_noPmf() {
        val p = RsnProfile.fromIes(listOf(ie("30 14 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 00 0F AC 01 00 00")))
        assertEquals(setOf(Akm.EAP_SHA1), p.akms)
        assertEquals(setOf(Cipher.CCMP), p.pairwise)
        assertEquals(Cipher.CCMP, p.group)
        assertFalse(p.mfpc || p.mfpr)
        assertEquals(setOf(WPA2_ENTERPRISE), p.admissibleModes())
    }

    @Test
    fun wpa2Eap_pmfOptional_staysWpa2() {
        assertEquals(setOf(WPA2_ENTERPRISE), modes("30 14 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 00 0F AC 01 80 00"))
    }

    @Test
    fun akm5Only_withoutMfpr_isWpa2() {
        // A client without PMF can still join: that is WPA2-Enterprise, whatever the AKM.
        assertEquals(setOf(WPA2_ENTERPRISE), modes("30 14 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 00 0F AC 05 80 00"))
    }

    @Test
    fun transition_akm1And5_mfpc() {
        assertEquals(setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE),
            modes("30 18 01 00 00 0F AC 04 01 00 00 0F AC 04 02 00 00 0F AC 01 00 0F AC 05 80 00"))
    }

    @Test
    fun akm1_mfpr_isWpa3Only() {
        assertEquals(setOf(WPA3_ENTERPRISE), modes("30 14 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 00 0F AC 01 C0 00"))
    }

    @Test
    fun akm5_mfpr_isWpa3Only_withGroupMgmtCipher() {
        val p = RsnProfile.fromIes(listOf(ie("30 1A 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 00 0F AC 05 C0 00 00 00 00 0F AC 06")))
        assertTrue(p.mfpc && p.mfpr)
        assertEquals(6, p.groupMgmt)
        assertEquals(setOf(WPA3_ENTERPRISE), p.admissibleModes())
    }

    @Test
    fun suiteB192() {
        assertEquals(setOf(WPA3_ENTERPRISE_192),
            modes("30 1A 01 00 00 0F AC 09 01 00 00 0F AC 09 01 00 00 0F AC 0C C0 00 00 00 00 0F AC 0C"))
    }

    @Test
    fun suiteB_withoutGcmp256_isNotReported() {
        assertEquals(emptySet<Any>(), modes("30 14 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 00 0F AC 0C C0 00"))
    }

    @Test
    fun fils_isSkipped_notOpen() {
        val p = RsnProfile.fromIes(listOf(ie("30 14 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 00 0F AC 0E 80 00")))
        assertTrue(p.secured)
        assertEquals(emptySet<Any>(), p.admissibleModes())
    }

    @Test
    fun dpp_otherVendorsAkm_isSkipped() {
        assertEquals(emptySet<Any>(), modes("30 14 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 50 6F 9A 02 80 00"))
    }

    @Test
    fun wpa1AndWpa2_mixed() {
        val p = RsnProfile.fromIes(listOf(
            ie("30 18 01 00 00 0F AC 02 02 00 00 0F AC 04 00 0F AC 02 01 00 00 0F AC 02 00 00"),
            ie("DD 16 00 50 F2 01 01 00 00 50 F2 02 01 00 00 50 F2 02 01 00 00 50 F2 02")
        ))
        assertTrue(p.wpa1)
        assertEquals(setOf(Cipher.CCMP, Cipher.TKIP), p.pairwise)
        assertEquals(Cipher.TKIP, p.group)
        assertEquals(setOf(WPA2_PERSONAL), p.admissibleModes())
    }

    @Test
    fun sae() {
        assertEquals(setOf(WPA3_PERSONAL), modes("30 14 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 00 0F AC 08 C0 00"))
    }

    @Test
    fun pskSaeTransition() {
        assertEquals(setOf(WPA2_PERSONAL, WPA3_PERSONAL),
            modes("30 18 01 00 00 0F AC 04 01 00 00 0F AC 04 02 00 00 0F AC 02 00 0F AC 08 80 00"))
    }

    @Test
    fun sae_withoutPmf_admitsOnlyPsk() {
        // SAE needs PMF; without MFPC only the PSK side is reachable.
        assertEquals(setOf(WPA2_PERSONAL),
            modes("30 18 01 00 00 0F AC 04 01 00 00 0F AC 04 02 00 00 0F AC 02 00 0F AC 08 00 00"))
    }

    @Test
    fun owe() {
        assertEquals(setOf(OWE), modes("30 14 01 00 00 0F AC 04 01 00 00 0F AC 04 01 00 00 0F AC 12 C0 00"))
    }

    @Test
    fun oweTransition_openSide() {
        // Vendor element: OUI 50:6F:9A type 0x1C, then the OWE twin's BSSID and SSID.
        assertEquals(setOf(OPEN, OWE), modes("DD 0E 50 6F 9A 1C 02 11 22 33 44 55 03 6F 77 65"))
    }

    @Test
    fun wep() {
        assertEquals(setOf(WEP), modes(wep = true))
    }

    @Test
    fun open() {
        assertEquals(setOf(OPEN), modes())
        assertEquals(setOf(OPEN), modes("DD 05 00 50 F2 04 10")) // a WPS element is not security
    }

    @Test
    fun truncatedRsn_takesTheStandardsDefaults() {
        // Only the version: group and pairwise CCMP, AKM 802.1X.
        assertEquals(setOf(WPA2_ENTERPRISE), modes("30 02 01 00"))
    }

    @Test
    fun malformedCount_stopsWithoutCrashing() {
        // Pairwise count claims 5 suites, one present: falls back to the defaults.
        assertEquals(setOf(WPA2_ENTERPRISE), modes("30 0C 01 00 00 0F AC 04 05 00 00 0F AC 04"))
    }

    // --- capability-string fallback ---

    @Test
    fun capabilities_shapesAndroidProduces() {
        fun caps(s: String) = RsnProfile.fromCapabilities(s).admissibleModes()
        assertEquals(setOf(OPEN), caps("[ESS]"))
        assertEquals(setOf(OPEN), caps(""))
        assertEquals(setOf(WPA2_PERSONAL), caps("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals(setOf(WPA2_PERSONAL, WPA3_PERSONAL), caps("[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][MFPC][ESS]"))
        assertEquals(setOf(WPA2_PERSONAL), caps("[RSN-PSK-SHA256-CCMP][ESS]"))
        assertEquals(setOf(WPA2_ENTERPRISE), caps("[WPA2-EAP-CCMP][ESS]"))
        assertEquals(setOf(WPA2_ENTERPRISE), caps("[RSN-FT/EAP+EAP/SHA1-CCMP][ESS]"))
        assertEquals(setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE), caps("[RSN-EAP/SHA1+EAP/SHA256-CCMP][MFPC][ESS]"))
        assertEquals(setOf(WPA3_ENTERPRISE), caps("[RSN-EAP/SHA256-CCMP][MFPR][MFPC][ESS]"))
        assertEquals(setOf(WPA3_ENTERPRISE_192), caps("[RSN-EAP_SUITE_B_192-GCMP-256][MFPR][MFPC][ESS]"))
        assertEquals(setOf(OWE), caps("[RSN-OWE-CCMP][MFPR][MFPC][ESS]"))
        assertEquals(setOf(OPEN, OWE), caps("[RSN-OWE_TRANSITION-CCMP][ESS]"))
        assertEquals(setOf(WEP), caps("[WEP][ESS]"))
        // FILS is not EAP/SHA1 even though its name starts with "EAP".
        assertEquals(emptySet<Any>(), caps("[RSN-EAP-FILS-SHA256-CCMP][ESS]"))
        assertEquals(emptySet<Any>(), caps("[RSN-DPP-CCMP][ESS]"))
    }

    @Test
    fun capabilities_wpa1AndCiphers() {
        val p = RsnProfile.fromCapabilities("[WPA-PSK-TKIP+CCMP][WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]")
        assertTrue(p.wpa1)
        assertEquals(setOf(Cipher.TKIP, Cipher.CCMP), p.pairwise)
    }

    // --- floor / ceiling ---

    @Test
    fun floorAndCeiling() {
        val transition = setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE)
        assertEquals(WPA2_ENTERPRISE, transition.floor)
        assertEquals(WPA3_ENTERPRISE, transition.ceiling())
        assertEquals(WPA2_ENTERPRISE, transition.ceiling(setOf(WPA2_ENTERPRISE)))
        assertEquals(OPEN, setOf(OPEN, OWE).floor)
        assertNull(emptySet<Nothing>().floor)
        assertNull(setOf(WPA3_ENTERPRISE_192).ceiling(transition))
    }
}
