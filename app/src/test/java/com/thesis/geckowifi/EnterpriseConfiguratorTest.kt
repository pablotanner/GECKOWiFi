package com.thesis.geckowifi

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.data.model.WiFiIdentity
import com.thesis.geckowifi.enterprise.ConfigResult
import com.thesis.geckowifi.enterprise.EnterpriseConfigurator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterpriseConfiguratorTest {

    private val configurator = EnterpriseConfigurator()

    @Test
    fun `enterprise auth modes are recognised`() {
        assertTrue(configurator.isEnterprise(WiFiAuthMode.WPA2_ENTERPRISE))
        assertTrue(configurator.isEnterprise(WiFiAuthMode.WPA3_ENTERPRISE))
        assertFalse(configurator.isEnterprise(WiFiAuthMode.OPEN))
        assertFalse(configurator.isEnterprise(WiFiAuthMode.WPA2_PERSONAL))
    }

    // NOTE: buildFrom()'s success path (constructing a real
    // WifiEnterpriseConfig/WifiNetworkSuggestion) is NOT covered by a plain
    // JVM unit test here - WifiNetworkSuggestion.Builder()'s chained calls
    // return null under the Android SDK stub jar used by `testDebugUnitTest`
    // (confirmed: NullPointerException at the .setSsid(...) call), even with
    // testOptions.unitTests.isReturnDefaultValues = true. Testing that path
    // for real needs Robolectric or an instrumented (androidTest) test,
    // neither of which is set up in this project. The two rejection-path
    // tests below are safe because they return before ever touching
    // WifiEnterpriseConfig/WifiNetworkSuggestion. The bug this class had
    // (requiring `authServerCAs`, a field that doesn't exist server-side)
    // is still fixed - see the doc comment on EnterpriseConfigurator.buildFrom
    // - just not verifiable by a fast unit test the way the rejection paths are.

    @Test
    fun `buildFrom rejects non-enterprise auth modes`() {
        val cert = GeoCertificate(
            certificateId = "open-cert",
            wifi = WiFiIdentity(authMode = WiFiAuthMode.OPEN),
            notValidAfter = "2099-01-01T00:00:00Z"
        )

        val result = configurator.buildFrom(cert, ssid = "cafe")

        assertTrue(result is ConfigResult.Rejected)
    }

    @Test
    fun `buildFrom rejects enterprise certs with no auth server names`() {
        val cert = GeoCertificate(
            certificateId = "eduroam-cert-incomplete",
            wifi = WiFiIdentity(authMode = WiFiAuthMode.WPA2_ENTERPRISE, authServerNames = emptyList()),
            notValidAfter = "2099-01-01T00:00:00Z"
        )

        val result = configurator.buildFrom(cert, ssid = "eduroam")

        assertTrue(result is ConfigResult.Rejected)
        assertEquals("no auth_server_names to pin", (result as ConfigResult.Rejected).reason)
    }

    @Test
    fun `expectedCaFingerprints reads trusted_ca_fingerprints`() {
        val cert = GeoCertificate(
            certificateId = "eduroam-cert",
            wifi = WiFiIdentity(
                authMode = WiFiAuthMode.WPA2_ENTERPRISE,
                authServerNames = listOf("radius.example.org"),
                trustedCAFingerprints = listOf("abc123")
            ),
            notValidAfter = "2099-01-01T00:00:00Z"
        )

        assertEquals(listOf("abc123"), configurator.expectedCaFingerprints(cert))
    }
}
