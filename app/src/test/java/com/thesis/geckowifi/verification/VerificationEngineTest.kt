package com.thesis.geckowifi.verification

import com.thesis.geckowifi.ZURICH_AREA
import com.thesis.geckowifi.data.model.AAADomain
import com.thesis.geckowifi.data.model.AuthServer
import com.thesis.geckowifi.data.model.EnterpriseIdentity
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.PortalDomain
import com.thesis.geckowifi.data.model.PortalDomainRole
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
import java.time.Instant

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

    /** A probe whose TLS handshake "presents" [spki]. */
    private fun probePresenting(spki: String): CertProbe = mockk {
        every { fetchCertificate(any()) } returns null
        every { spkiHash(any()) } returns spki
    }

    private fun engine(
        gecko: GeckoClient,
        probe: CertProbe = quietProbe(),
        now: Instant = Instant.parse("2026-10-01T00:00:00Z")
    ) = VerificationEngine(gecko, probe, DecisionCache(), GeoQueryEncoder()) { now }

    private fun pinnedPortal(domain: String, pin: String) =
        PortalTLSIdentity(domains = listOf(PortalDomain(domain, PortalDomainRole.PRIMARY, listOf(pin))))

    private fun sampleCertificate(domain: String, notValidAfter: String = "2099-01-01T00:00:00Z") = GeoCertificate(
        certificateId = "cert-1",
        wifi = WiFiIdentity(authModeNames = listOf("open")),
        portal = pinnedPortal(domain, "expected-hash"),
        areas = listOf(ZURICH_AREA),
        notValidAfter = notValidAfter
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

        val result = engine(gecko, probePresenting("expected-hash")).verify(networkKey, host, lat, lng, null, radius)

        assertEquals(VerificationState.VERIFIED, result.state)
    }

    @Test
    fun verify_ignoresExpiredCertificates() = runTest {
        // Same pinned domain and matching key, but the certificate expired a day
        // before "now": it must behave as if nothing were registered.
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns
            GeckoResponse.Success(listOf(sampleCertificate(host, notValidAfter = "2026-09-30T00:00:00Z")))

        val result = engine(gecko, probePresenting("expected-hash")).verify(networkKey, host, lat, lng, null, radius)

        assertEquals(VerificationState.UNVERIFIED, result.state)
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
            wifi = WiFiIdentity(authModeNames = listOf("open")),
            portal = pinnedPortal(host, "expected-hash"),
            areas = listOf(ZURICH_AREA),
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
            wifi = WiFiIdentity(authModeNames = listOf("open")),
            portal = pinnedPortal(host, "expected-hash"),
            areas = listOf(ZURICH_AREA),
            notValidAfter = "2099-01-01T00:00:00Z"
        )
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.Success(listOf(pinnedCert))

        val result = engine(gecko).verifyPresentedDomain(networkKey, host, "wrong-hash", lat, lng, null, radius)
        assertEquals(VerificationState.CONFLICT, result.state)
    }

    private val genuineServer = ObservedAuthServer("leafKey", listOf("radius.example.org"), listOf("caKey"))

    private fun enterpriseCert(ssid: String = "eduroam", critical: List<String> = emptyList()) = GeoCertificate(
        schemaVersion = 3,
        certificateId = "eduroam-cert",
        critical = critical,
        wifi = WiFiIdentity(
            ssid = ssid,
            authModeNames = listOf("wpa2-enterprise"),
            enterprise = EnterpriseIdentity(
                federation = "eduroam",
                aaa = listOf(AAADomain(
                    realms = listOf("example.org"),
                    servers = listOf(AuthServer(names = listOf("radius.example.org"), caSPKIHashes = listOf("caKey")))
                ))
            )
        ),
        areas = listOf(ZURICH_AREA),
        notValidAfter = "2099-01-01T00:00:00Z"
    )

    private fun geckoReturning(vararg certs: GeoCertificate) = mockk<GeckoClient> {
        coEvery { queryLocation(any(), any(), any()) } returns GeckoResponse.Success(certs.toList())
    }

    @Test
    fun verifyEnterprise_mapsProofFailureToConflict() = runTest {
        val gecko = mockk<GeckoClient>()
        coEvery { gecko.queryLocation(any(), any(), any()) } returns GeckoResponse.ProofFailure("bad signature")

        val result = engine(gecko).verifyEnterprise(networkKey, genuineServer, "example.org", lat, lng, null, radius)

        assertEquals(VerificationState.CONFLICT, result.state)
    }

    @Test
    fun verifyEnterprise_delegatesSuccessToEvaluateEnterpriseNetwork() = runTest {
        val cert = enterpriseCert()

        val result = engine(geckoReturning(cert)).verifyEnterprise(
            networkKey, genuineServer, "example.org", lat, lng, null, radius,
            ssid = "eduroam", observedAuthModes = setOf(WiFiAuthMode.WPA2_ENTERPRISE)
        )

        assertEquals(VerificationState.VERIFIED, result.state)
        assertEquals(cert.certificateId, result.matchedCertificateId)
    }

    @Test
    fun a1_openCloneOfEnterpriseSsid_isConflict() = runTest {
        // An evil twin broadcasting "eduroam" as an open network with a captive
        // portal of its own: nothing about the portal is registered, but the auth
        // mode alone already gives it away.
        val result = engine(geckoReturning(enterpriseCert())).verifyPresentedDomain(
            networkKey, "login.evil.lab", "attackerKey", lat, lng, null, radius,
            ssid = "eduroam", observedAuthModes = setOf(WiFiAuthMode.OPEN)
        )

        assertEquals(VerificationState.CONFLICT, result.state)
        assertEquals("wpa2-enterprise", result.registeredIdentifier)
        assertEquals("open", result.presentedIdentifier)
    }

    @Test
    fun authModeMismatch_isConflictEvenIfThePortalVerifies() = runTest {
        // Genuine portal key, but the AP is registered as WPA2-personal and advertises open.
        val cert = sampleCertificate(host).copy(wifi = WiFiIdentity(ssid = "Cafe", authModeNames = listOf("wpa2-personal")))

        val result = engine(geckoReturning(cert)).verifyPresentedDomain(
            networkKey, host, "expected-hash", lat, lng, null, radius,
            ssid = "Cafe", observedAuthModes = setOf(WiFiAuthMode.OPEN)
        )

        assertEquals(VerificationState.CONFLICT, result.state)
    }

    @Test
    fun certificateWithoutAuthModes_skipsTheAuthModeCheck() = runTest {
        // v2 certificates carry no auth_modes: nothing to compare, the portal decides.
        val cert = sampleCertificate(host).copy(wifi = WiFiIdentity(ssid = "Cafe"))

        val result = engine(geckoReturning(cert)).verifyPresentedDomain(
            networkKey, host, "expected-hash", lat, lng, null, radius,
            ssid = "Cafe", observedAuthModes = setOf(WiFiAuthMode.WPA2_PERSONAL)
        )

        assertEquals(VerificationState.VERIFIED, result.state)
    }

    @Test
    fun onlyUnknownCriticalFeature_isSilentUnverified() = runTest {
        val result = engine(geckoReturning(enterpriseCert(critical = listOf("future-feature")))).verifyEnterprise(
            networkKey, genuineServer, "example.org", lat, lng, null, radius, ssid = "eduroam"
        )

        assertEquals(VerificationState.UNVERIFIED, result.state)
        assertEquals(true, result.reason?.contains("future-feature"))
    }

    @Test
    fun conflictIsWithheldWhileAnotherCertificateForTheSsidIsUnevaluable() = runTest {
        // A rogue server against the evaluable certificate would be CONFLICT, but
        // the second certificate's unknown critical feature might legitimise it.
        val rogue = ObservedAuthServer("rogueKey", listOf("radius.example.org"), listOf("rogueCa"))
        val other = enterpriseCert(critical = listOf("future-feature")).copy(certificateId = "other")

        val result = engine(geckoReturning(enterpriseCert(), other)).verifyEnterprise(
            networkKey, rogue, "example.org", lat, lng, null, radius, ssid = "eduroam"
        )

        assertEquals(VerificationState.UNVERIFIED, result.state)
    }

    @Test
    fun sharedSsid_keepsUnknownNetworkUnrecognized() = runTest {
        val shared = sampleCertificate(host).copy(wifi = WiFiIdentity(ssid = "FreeWiFi", ssidShared = true))

        val result = engine(geckoReturning(shared)).verifyPresentedDomain(
            networkKey, "other-venue.example", "otherKey", lat, lng, null, radius, ssid = "FreeWiFi"
        )

        assertEquals(VerificationState.UNRECOGNIZED, result.state)
    }

    @Test
    fun certificateBeforeNotValidBefore_isIgnored() = runTest {
        val future = sampleCertificate(host).copy(notValidBefore = "2027-01-01T00:00:00Z")

        val result = engine(geckoReturning(future)).verifyPresentedDomain(networkKey, host, "expected-hash", lat, lng, null, radius)

        assertEquals(VerificationState.UNVERIFIED, result.state)
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
