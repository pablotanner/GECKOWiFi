package com.thesis.geckowifi.network

import android.net.wifi.WifiInfo
import com.thesis.geckowifi.data.model.WiFiAuthMode.OPEN
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

/** The post-association comparator and the two readers' parsing. */
class AssociationTest {

    @Test
    fun negotiated_belowRegistered_doesNotFit() {
        assertFalse(negotiatedFits(setOf(WPA3_ENTERPRISE), WPA2_ENTERPRISE))
        assertFalse(negotiatedFits(setOf(WPA2_PERSONAL, WPA3_PERSONAL), OPEN)) // another kind altogether
        assertFalse(negotiatedFits(setOf(WPA2_ENTERPRISE), WPA3_PERSONAL))
    }

    @Test
    fun negotiated_asRegisteredOrStronger_fits() {
        assertTrue(negotiatedFits(setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE), WPA2_ENTERPRISE))
        assertTrue(negotiatedFits(setOf(WPA2_ENTERPRISE, WPA3_ENTERPRISE), WPA3_ENTERPRISE))
        assertTrue(negotiatedFits(setOf(WPA2_ENTERPRISE), WPA3_ENTERPRISE))
        assertTrue(negotiatedFits(emptySet(), OPEN)) // v2 certificate: nothing registered to compare
    }

    @Test
    fun wifiInfo_eapCountsAsWpa3_onlyWhenTheApRequiresPmf() {
        assertEquals(WPA2_ENTERPRISE, modeFromWifiInfo(WifiInfo.SECURITY_TYPE_EAP, apRequiresPmf = false))
        assertEquals(WPA3_ENTERPRISE, modeFromWifiInfo(WifiInfo.SECURITY_TYPE_EAP, apRequiresPmf = true))
        assertEquals(WPA3_ENTERPRISE, modeFromWifiInfo(WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE, apRequiresPmf = false))
        assertEquals(WPA3_ENTERPRISE_192, modeFromWifiInfo(WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT, false))
        assertEquals(WPA3_PERSONAL, modeFromWifiInfo(WifiInfo.SECURITY_TYPE_SAE, false))
        assertNull(modeFromWifiInfo(WifiInfo.SECURITY_TYPE_UNKNOWN, false))
    }

    @Test
    fun supplicantStatus_wpa2Eap() {
        val a = parseSupplicantStatus(
            listOf(
                "bssid=96:83:C4:97:C2:3A", "ssid=eduroam-lab", "pairwise_cipher=CCMP", "group_cipher=CCMP",
                "key_mgmt=WPA2/IEEE 802.1X/EAP", "wpa_state=COMPLETED"
            ).joinToString("\n")
        )
        assertEquals(Association("96:83:c4:97:c2:3a", WPA2_ENTERPRISE), a)
    }

    @Test
    fun supplicantStatus_pmfMakesEapWpa3() {
        val a = parseSupplicantStatus("bssid=96:83:c4:97:c2:47\nkey_mgmt=WPA2-EAP-SHA256\npmf=2\nmgmt_group_cipher=BIP\nwpa_state=COMPLETED")
        assertEquals(WPA3_ENTERPRISE, a.mode)
        assertEquals(WPA3_PERSONAL, parseSupplicantStatus("key_mgmt=SAE\npmf=2\nwpa_state=COMPLETED").mode)
        assertEquals(OPEN, parseSupplicantStatus("key_mgmt=NONE\nwpa_state=COMPLETED").mode)
    }

    @Test
    fun supplicantStatus_notCompleted_hasNoMode() {
        val a = parseSupplicantStatus("bssid=96:83:c4:97:c2:47\nkey_mgmt=WPA2/IEEE 802.1X/EAP\nwpa_state=4WAY_HANDSHAKE")
        assertNull(a.mode)
        assertEquals("96:83:c4:97:c2:47", a.bssid)
    }
}
