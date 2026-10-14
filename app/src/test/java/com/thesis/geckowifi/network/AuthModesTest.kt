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
import org.junit.Test

/** [parseAuthModes] against capability strings in the shapes Android's ScanResult produces. */
class AuthModesTest {

    @Test
    fun open() {
        assertEquals(setOf(OPEN), parseAuthModes("[ESS]"))
        assertEquals(setOf(OPEN), parseAuthModes(""))
        assertEquals(setOf(OPEN), parseAuthModes("[WPS][ESS]"))
    }

    @Test
    fun personal() {
        assertEquals(setOf(WPA2_PERSONAL), parseAuthModes("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals(setOf(WPA3_PERSONAL), parseAuthModes("[RSN-SAE-CCMP][MFPR][MFPC][ESS]"))
        // WPA2/WPA3 transition mode advertises both.
        assertEquals(setOf(WPA2_PERSONAL, WPA3_PERSONAL), parseAuthModes("[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][MFPC][ESS]"))
    }

    @Test
    fun enterprise() {
        assertEquals(setOf(WPA2_ENTERPRISE), parseAuthModes("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"))
        assertEquals(setOf(WPA2_ENTERPRISE), parseAuthModes("[WPA2-EAP-CCMP][ESS]"))
        assertEquals(setOf(WPA2_ENTERPRISE), parseAuthModes("[RSN-FT/EAP+EAP/SHA1-CCMP][ESS]"))
        // WPA3-Enterprise: SHA-256 EAP, or EAP with PMF required.
        assertEquals(setOf(WPA3_ENTERPRISE), parseAuthModes("[RSN-EAP/SHA256-CCMP][MFPR][MFPC][ESS]"))
        assertEquals(setOf(WPA3_ENTERPRISE), parseAuthModes("[RSN-EAP/SHA1-CCMP][MFPR][MFPC][ESS]"))
        assertEquals(setOf(WPA3_ENTERPRISE_192), parseAuthModes("[RSN-EAP_SUITE_B_192-GCMP-256][MFPR][MFPC][ESS]"))
    }

    @Test
    fun oweAndWep() {
        assertEquals(setOf(OWE), parseAuthModes("[RSN-OWE-CCMP][MFPR][MFPC][ESS]"))
        assertEquals(setOf(WEP), parseAuthModes("[WEP][ESS]"))
    }

    @Test
    fun unknownKeyManagement_isSkippedNotOpen() {
        // A secured network with an AKM this parser doesn't know must not look open.
        assertEquals(emptySet<Any>(), parseAuthModes("[RSN-DPP-CCMP][ESS]"))
    }
}
