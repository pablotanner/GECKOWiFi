package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.data.remote.GeckoResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant

/**
 * lab/certs/ethz-eduroam.json, built from ETH's eduroam CAT profile, read
 * exactly as the app reads it from the map server. Keeps the registered file
 * and the client's interpretation of it from drifting apart.
 */
class EthzEduroamCertificateTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val cert: GeoCertificate = json.decodeFromString(
        ListSerializer(GeoCertificate.serializer()),
        File("../lab/certs/ethz-eduroam.json").readText()
    ).single()

    private val server = cert.wifi.enterprise!!.aaa.single().servers.single()

    private fun der(name: String): X509Certificate =
        javaClass.getResourceAsStream("/eap-chains/$name.der")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    @Test
    fun registersEthEduroamAsAFederationSite() {
        assertEquals("eduroam", cert.wifi.ssid)
        assertEquals(setOf(WiFiAuthMode.WPA2_ENTERPRISE, WiFiAuthMode.WPA3_ENTERPRISE), cert.wifi.authModes)
        assertTrue(cert.wifi.enterprise!!.isFederated)
        assertTrue(cert.wifi.enterprise!!.aaa.single().coversRealm("ethz.ch"))
        assertTrue(cert.wifi.enterprise!!.aaa.single().coversRealm("student.ethz.ch"))
        assertEquals(listOf("radius-service.ethz.ch"), server.names)
        assertTrue(cert.isEvaluable())
        assertTrue(cert.isValidAt(Instant.parse("2026-10-14T00:00:00Z")))
    }

    @Test
    fun caCertificatesAreTheProfilesCasAndMatchTheirPins() {
        val pinned = server.pinnedCaCertificates()
        assertEquals(2, pinned.size)
        assertEquals(listOf(der("ethz-root-ca-2020"), der("ethz-issuing-ca-2020")), pinned)
        assertEquals(server.caSPKIHashes, pinned.map { ObservedAuthServer.spkiSha256(it) })
    }

    @Test
    fun issuingCaChainsToTheRoot() {
        // Signature check, not just names: what fromChain relies on for a real server chain.
        val observed = ObservedAuthServer.fromChain(listOf(der("ethz-issuing-ca-2020")), listOf(der("ethz-root-ca-2020")))!!
        assertEquals(listOf(server.caSPKIHashes[0]), observed.verifiedIssuerSpkiSha256)
    }

    @Test
    fun areasCoverBothCampusesButNotTheStation() {
        assertTrue(cert.coversLocation(47.37650, 8.54732, 20))   // ETH main building (the profile's location)
        assertTrue(cert.coversLocation(47.4081, 8.5076, 20))     // Hönggerberg
        assertFalse(cert.coversLocation(47.3779, 8.5403, 20))    // Zürich HB
    }

    // --- checked without joining, as the app does for a secured network ---

    private fun engine() = VerificationEngine(
        mockk<GeckoClient> { coEvery { queryLocation(any(), any(), any()) } returns GeckoResponse.Success(listOf(cert)) },
        mockk(relaxed = true), DecisionCache(), GeoQueryEncoder()
    ) { Instant.parse("2026-10-14T00:00:00Z") }

    private suspend fun advertised(modes: Set<WiFiAuthMode>, ssid: String = "eduroam") =
        engine().checkAdvertised("net", ssid, modes, 47.37650, 8.54732, null, 20)

    @Test
    fun genuineEduroamAdvertisement_isRegistered() = runTest {
        val result = advertised(setOf(WiFiAuthMode.WPA2_ENTERPRISE))
        assertEquals(VerificationState.REGISTERED, result.state)
        assertTrue(result.reason!!.contains("ETH Zürich"))
    }

    @Test
    fun wpa3TransitionAdvertisement_isRegistered() = runTest {
        assertEquals(VerificationState.REGISTERED, advertised(setOf(WiFiAuthMode.WPA2_ENTERPRISE, WiFiAuthMode.WPA3_ENTERPRISE)).state)
    }

    @Test
    fun openEduroam_isConflict() = runTest {
        assertEquals(VerificationState.CONFLICT, advertised(setOf(WiFiAuthMode.OPEN)).state)
    }

    @Test
    fun passwordProtectedEduroam_isConflict() = runTest {
        assertEquals(VerificationState.CONFLICT, advertised(setOf(WiFiAuthMode.WPA2_PERSONAL)).state)
    }

    @Test
    fun otherSsid_isUnverified() = runTest {
        assertEquals(VerificationState.UNVERIFIED, advertised(setOf(WiFiAuthMode.WPA2_ENTERPRISE), ssid = "public").state)
    }
}
