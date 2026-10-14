package com.thesis.geckowifi

import android.net.wifi.WifiEnterpriseConfig
import com.thesis.geckowifi.data.model.AAADomain
import com.thesis.geckowifi.data.model.AuthServer
import com.thesis.geckowifi.data.model.EnterpriseIdentity
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.WiFiIdentity
import com.thesis.geckowifi.enterprise.ConfigResult
import com.thesis.geckowifi.enterprise.EnterpriseConfigurator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterpriseConfiguratorTest {

    private val configurator = EnterpriseConfigurator()

    // NOTE: buildFrom()'s success path (constructing a real
    // WifiEnterpriseConfig/WifiNetworkSuggestion) is NOT covered by a plain
    // JVM unit test here - WifiNetworkSuggestion.Builder()'s chained calls
    // return null under the Android SDK stub jar, even with
    // unitTests.isReturnDefaultValues = true. That needs Robolectric or an
    // instrumented test, neither of which is set up. The rejection paths
    // return before touching those classes.

    private fun cert(authModes: List<String>, enterprise: EnterpriseIdentity?) = GeoCertificate(
        certificateId = "c",
        wifi = WiFiIdentity(ssid = "eduroam", authModeNames = authModes, enterprise = enterprise),
        notValidAfter = "2099-01-01T00:00:00Z"
    )

    private val ethz = AAADomain(realms = listOf("ethz.ch"), servers = listOf(AuthServer(names = listOf("radius.ethz.ch"))))
    private val uzh = AAADomain(realms = listOf("uzh.ch"), servers = listOf(AuthServer(names = listOf("radius.uzh.ch"))))

    @Test
    fun `buildFrom rejects non-enterprise auth modes`() {
        val result = configurator.buildFrom(cert(listOf("open"), null), ssid = "cafe")
        assertTrue(result is ConfigResult.Rejected)
    }

    @Test
    fun `buildFrom rejects enterprise certs with no server names`() {
        val noNames = AAADomain(servers = listOf(AuthServer(caSPKIHashes = listOf("ca"))))
        val result = configurator.buildFrom(cert(listOf("wpa2-enterprise"), EnterpriseIdentity(aaa = listOf(noNames))), "eduroam")
        assertEquals("no server names to pin", (result as ConfigResult.Rejected).reason)
    }

    @Test
    fun `buildFrom rejects a federation site without aaa entries`() {
        val result = configurator.buildFrom(cert(listOf("wpa2-enterprise"), EnterpriseIdentity("eduroam")), "eduroam")
        assertEquals("no aaa entry to provision from", (result as ConfigResult.Rejected).reason)
    }

    @Test
    fun `selectGroup picks the user's realm, else the first entry`() {
        assertEquals(uzh, configurator.selectGroup(listOf(ethz, uzh), "uzh.ch"))
        assertEquals(ethz, configurator.selectGroup(listOf(ethz, uzh), "visitor.org"))
        assertEquals(ethz, configurator.selectGroup(listOf(ethz, uzh), null))
    }

    @Test
    fun `eap and phase2 names map to the platform constants`() {
        assertEquals(WifiEnterpriseConfig.Eap.PEAP, configurator.mapEap("peap"))
        assertEquals(WifiEnterpriseConfig.Eap.TTLS, configurator.mapEap("ttls"))
        assertEquals(WifiEnterpriseConfig.Eap.TLS, configurator.mapEap("eap-tls"))
        assertEquals(WifiEnterpriseConfig.Phase2.PAP, configurator.mapPhase2("pap"))
        assertEquals(WifiEnterpriseConfig.Phase2.MSCHAPV2, configurator.mapPhase2(null))
    }
}
