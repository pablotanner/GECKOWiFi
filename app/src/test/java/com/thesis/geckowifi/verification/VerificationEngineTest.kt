package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.EAPMethod
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.PortalTLSIdentity
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.data.model.WiFiIdentity
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.data.remote.GeckoResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests instructions.MD step 5's requirements on [VerificationEngine.verify]:
 * location-driven (lat/lng/alt/radius in), ProofFailure -> CONFLICT, and
 * PortalSession usage left unchanged. [GeckoClient] and [CertProbe] are
 * mocked - the real network/crypto path is already covered by the geopki
 * port's own tests (including against a live server); this class's own job
 * is just state mapping, caching, and session lifecycle, so that's all this
 * exercises. [GeoQueryEncoder] and [DecisionCache] are used for real (pure,
 * deterministic, no I/O).
 */
class VerificationEngineTest {

    private val networkKey = "wifi:AA:BB:CC:DD:EE:FF"
    private val host = "portal.example.com"
    private val lat = 47.3769
    private val lng = 8.5417
    private val radius = 50

    private fun quietProbe(): CertProbe = mockk {
        every { fetchCertificate(any()) } returns null
        every { spkiHash(any()) } returns null
    }

    private fun engine(gecko: GeckoClient, probe: CertProbe = quietProbe()) =
        VerificationEngine(gecko, probe, DecisionCache(), GeoQueryEncoder())

    private fun sampleCertificate(domain: String) = GeoCertificate(
        certificateId = "cert-1",
        wifi = WiFiIdentity(authMode = WiFiAuthMode.OPEN),
        portal = PortalTLSIdentity(domains = listOf(domain)),
        notValidAfter = "2099-01-01T00:00:00Z"
    )

    @Test
    fun verify_mapsUnreachableToUnreachableState() = runTest {
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.Unreachable("no network")

        val result = engine(gecko).verify(networkKey, host, lat, lng, null, radius)

        assertEquals(VerificationState.UNREACHABLE, result.state)
        assertEquals("no network", result.reason)
    }

    @Test
    fun verify_mapsProofFailureToConflict() = runTest {
        // The key requirement of step 5: a failed cryptographic proof is
        // positive evidence of an attack, not absence of data - CONFLICT,
        // never UNVERIFIED/UNREACHABLE.
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.ProofFailure("bad signature")

        val result = engine(gecko).verify(networkKey, host, lat, lng, null, radius)

        assertEquals(VerificationState.CONFLICT, result.state)
        assertEquals("bad signature", result.reason)
    }

    @Test
    fun verify_delegatesSuccessToPortalSession() = runTest {
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns
            GeckoResponse.Success(listOf(sampleCertificate(host)))

        val result = engine(gecko).verify(networkKey, host, lat, lng, null, radius)

        assertEquals(VerificationState.VERIFIED, result.state)
    }

    @Test
    fun verify_passesAltitudeAndLocationThrough() = runTest {
        // "location-driven (lat/lng/alt/radius in)": confirm the altitude
        // parameter actually reaches the query, not just lat/lng/radius.
        val gecko = mockk<GeckoClient>()
        val minAltSlot = io.mockk.slot<Int>()
        val maxAltSlot = io.mockk.slot<Int>()
        coEvery {
            gecko.queryLocation(any(), capture(minAltSlot), capture(maxAltSlot))
        } returns GeckoResponse.Unreachable("n/a")

        engine(gecko).verify(networkKey, host, lat, lng, altitude = 500.0, radiusMeters = radius)

        assertEquals(true, minAltSlot.captured < maxAltSlot.captured)
    }

    @Test
    fun verify_cachesNonUnreachableResults() = runTest {
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.ProofFailure("bad signature")

        val eng = engine(gecko)
        eng.verify(networkKey, host, lat, lng, null, radius)
        eng.verify(networkKey, host, lat, lng, null, radius)

        coVerify(exactly = 1) { gecko.queryLocation(any(), any(), any()) }
    }

    @Test
    fun verify_doesNotCacheUnreachableResults() = runTest {
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.Unreachable("down")

        val eng = engine(gecko)
        eng.verify(networkKey, host, lat, lng, null, radius)
        eng.verify(networkKey, host, lat, lng, null, radius)

        coVerify(exactly = 2) { gecko.queryLocation(any(), any(), any()) }
    }

    @Test
    fun verifyPresentedDomain_usesGivenSpkiHashInsteadOfCertProbe() = runTest {
        // A pinned cert, with a presented hash supplied directly rather than
        // fetched - this is the prototype's fake-network path.
        val pinnedCert = GeoCertificate(
            certificateId = "cert-pinned",
            wifi = WiFiIdentity(authMode = WiFiAuthMode.OPEN),
            portal = PortalTLSIdentity(domains = listOf(host), pinnedSPKIHashes = listOf("expected-hash")),
            notValidAfter = "2099-01-01T00:00:00Z"
        )
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.Success(listOf(pinnedCert))

        val matching = engine(gecko).verifyPresentedDomain(networkKey, host, "expected-hash", lat, lng, null, radius)
        assertEquals(VerificationState.VERIFIED, matching.state)
    }

    @Test
    fun verifyPresentedDomain_mismatchedSpkiHashIsConflict() = runTest {
        val pinnedCert = GeoCertificate(
            certificateId = "cert-pinned",
            wifi = WiFiIdentity(authMode = WiFiAuthMode.OPEN),
            portal = PortalTLSIdentity(domains = listOf(host), pinnedSPKIHashes = listOf("expected-hash")),
            notValidAfter = "2099-01-01T00:00:00Z"
        )
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.Success(listOf(pinnedCert))

        val result = engine(gecko).verifyPresentedDomain(networkKey, host, "wrong-hash", lat, lng, null, radius)
        assertEquals(VerificationState.CONFLICT, result.state)
    }

    @Test
    fun verifyEnterprise_mapsProofFailureToConflict() = runTest {
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.ProofFailure("bad signature")

        val result = engine(gecko).verifyEnterprise(
            networkKey, "radius.example.org", "abc123", lat, lng, null, radius
        )

        assertEquals(VerificationState.CONFLICT, result.state)
    }

    @Test
    fun verifyEnterprise_delegatesSuccessToEvaluateEnterpriseNetwork() = runTest {
        val cert = GeoCertificate(
            certificateId = "eduroam-cert",
            wifi = WiFiIdentity(
                authMode = WiFiAuthMode.WPA2_ENTERPRISE,
                eap = EAPMethod.EAP_TLS,
                authServerNames = listOf("radius.example.org"),
                trustedCAFingerprints = listOf("abc123")
            ),
            notValidAfter = "2099-01-01T00:00:00Z"
        )
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.Success(listOf(cert))

        val result = engine(gecko).verifyEnterprise(
            networkKey, "radius.example.org", "abc123", lat, lng, null, radius
        )

        assertEquals(VerificationState.VERIFIED, result.state)
        assertEquals(cert.certificateId, result.matchedCertificateId)
    }

    @Test
    fun onNetworkChanged_resetsSessionAndCache() = runTest {
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.ProofFailure("bad signature")

        val eng = engine(gecko)
        eng.verify(networkKey, host, lat, lng, null, radius)
        eng.onNetworkChanged(networkKey)
        eng.verify(networkKey, host, lat, lng, null, radius)

        coVerify(exactly = 2) { gecko.queryLocation(any(), any(), any()) }
    }
}
