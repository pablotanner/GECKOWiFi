package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.PortalDomain
import com.thesis.geckowifi.data.model.PortalDomainRole
import com.thesis.geckowifi.data.model.PortalTLSIdentity
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.data.model.WiFiIdentity
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.data.remote.GeckoResponse
import com.thesis.geckowifi.discovery.HopSource
import com.thesis.geckowifi.discovery.ObservedHop
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * [VerificationEngine.verifyPortalHops] against hop chains shaped like the
 * lab's openNDS flow and the planned attacker scenarios.
 */
class PortalHopsVerificationTest {

    private val now = Instant.parse("2026-10-01T00:00:00Z")

    private val mangoA = GeoCertificate(
        schemaVersion = 2,
        certificateId = "mango-a",
        wifi = WiFiIdentity(ssid = "GeckoTest", authMode = WiFiAuthMode.OPEN),
        portal = PortalTLSIdentity(listOf(
            PortalDomain("portal.gecko-a.lab", PortalDomainRole.PRIMARY, listOf("keyPortal")),
            PortalDomain("pay.gecko-pay.lab", PortalDomainRole.DELEGATE, listOf("keyPay"))
        )),
        notValidAfter = "2031-01-01T00:00:00Z"
    )

    private fun engine(gecko: GeckoClient) =
        VerificationEngine(gecko, mockk(relaxed = true), DecisionCache(), GeoQueryEncoder()) { now }

    private fun geckoReturning(vararg certs: GeoCertificate): GeckoClient = mockk {
        coEvery { queryLocation(any(), any(), any()) } returns GeckoResponse.Success(certs.toList())
    }

    private fun hop(index: Int, url: String, status: Int, key: String? = null): ObservedHop {
        val uri = java.net.URI(url)
        return ObservedHop(
            index = index, url = url, scheme = uri.scheme, host = uri.host,
            port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80,
            statusCode = status, presentedSpkiHash = key,
            source = HopSource.Kind.REDIRECT_PROBE, timestampMillis = 0
        )
    }

    /** The genuine openNDS → portal chain on router A. */
    private val genuineChain = listOf(
        hop(0, "http://connectivitycheck.gstatic.com/generate_204", 302),
        hop(1, "http://portal.gecko-a.lab/?tok=1", 302),
        hop(2, "https://portal.gecko-a.lab/?tok=1", 200, key = "keyPortal")
    )

    private suspend fun check(gecko: GeckoClient, hops: List<ObservedHop>, judgeFinal: Boolean = true) =
        engine(gecko).verifyPortalHops("net", hops, judgeFinal, 47.3777, 8.5484, null, 50, "GeckoTest")

    @Test
    fun genuineChain_isVerified_andOnlyHttpsHopIsJudged() = runTest {
        val result = check(geckoReturning(mangoA), genuineChain)

        assertEquals(VerificationState.VERIFIED, result.overall.state)
        assertNull(result.hops[0].result)
        assertNull(result.hops[1].result)
        assertEquals(VerificationState.VERIFIED, result.hops[2].result?.state)
    }

    @Test
    fun s1_clonedPortalWithAttackerKey_isConflict() = runTest {
        val clone = genuineChain.dropLast(1) + hop(2, "https://portal.gecko-a.lab/?tok=1", 200, key = "keyAttacker")

        assertEquals(VerificationState.CONFLICT, check(geckoReturning(mangoA), clone).overall.state)
    }

    @Test
    fun s2_lookalikeDomainOnRegisteredSsid_isConflictUnderExclusivity() = runTest {
        // GeckoTest is registered here (mangoA), so a network on it serving a
        // domain that matches nothing registered is impersonation, not merely
        // "unknown": SSID exclusivity escalates UNRECOGNIZED to CONFLICT.
        val lookalike = listOf(
            hop(0, "http://connectivitycheck.gstatic.com/generate_204", 302),
            hop(1, "https://gecko-a-login.lab/", 200, key = "keyAttacker")
        )

        val result = check(geckoReturning(mangoA), lookalike).overall
        assertEquals(VerificationState.CONFLICT, result.state)
        assertEquals(true, result.reason?.contains("SSID exclusive"))
    }

    @Test
    fun unregisteredDomain_withoutSsidScope_staysUnrecognized() = runTest {
        // The additive fallback: with no SSID asserted (ssid = null), certs may
        // exist here for other SSIDs without claiming this domain, so an unknown
        // domain is UNRECOGNIZED, not CONFLICT.
        val lookalike = listOf(
            hop(0, "http://connectivitycheck.gstatic.com/generate_204", 302),
            hop(1, "https://gecko-a-login.lab/", 200, key = "keyAttacker")
        )

        val result = engine(geckoReturning(mangoA))
            .verifyPortalHops("net", lookalike, true, 47.3777, 8.5484, null, 50, ssid = null)
        assertEquals(VerificationState.UNRECOGNIZED, result.overall.state)
    }

    @Test
    fun s3_relayToGenuineThenSwitch_isConflictAtTheSwitch() = runTest {
        val relayThenSwitch = genuineChain + hop(3, "https://gecko-a-login.lab/continue", 200, key = "keyAttacker")

        val result = check(geckoReturning(mangoA), relayThenSwitch)

        assertEquals(VerificationState.CONFLICT, result.overall.state)
        assertEquals(VerificationState.VERIFIED, result.hops[2].result?.state)
        assertEquals(VerificationState.CONFLICT, result.hops[3].result?.state)
        assertEquals(true, result.overall.reason?.startsWith("hop 3 https://gecko-a-login.lab"))
    }

    @Test
    fun s4_delegateWithAttackerKey_isConflict() = runTest {
        val chain = genuineChain + hop(3, "https://pay.gecko-pay.lab/checkout", 200, key = "keyAttacker")

        assertEquals(VerificationState.CONFLICT, check(geckoReturning(mangoA), chain).overall.state)
    }

    @Test
    fun genuineDelegateAfterPrimary_isVerified() = runTest {
        val chain = genuineChain + hop(3, "https://pay.gecko-pay.lab/checkout", 200, key = "keyPay")

        assertEquals(VerificationState.VERIFIED, check(geckoReturning(mangoA), chain).overall.state)
    }

    @Test
    fun s6_registeredPortalServedOverPlainHttp_isConflict() = runTest {
        val downgrade = listOf(
            hop(0, "http://connectivitycheck.gstatic.com/generate_204", 302),
            hop(1, "http://portal.gecko-a.lab/?tok=1", 200)
        )

        assertEquals(VerificationState.CONFLICT, check(geckoReturning(mangoA), downgrade).overall.state)
    }

    @Test
    fun finalPlainHopNotJudgedWhenAsked_noJudgedHops_isUnverifiedWithoutQuery() = runTest {
        val gecko = geckoReturning(mangoA)
        val onlyHttp = listOf(hop(0, "http://connectivitycheck.gstatic.com/generate_204", 302))

        val result = check(gecko, onlyHttp, judgeFinal = false)

        assertEquals(VerificationState.UNVERIFIED, result.overall.state)
        coVerify(exactly = 0) { gecko.queryLocation(any(), any(), any()) }
    }

    @Test
    fun unreachableServer_isUnreachableAndNothingJudged() = runTest {
        val gecko = mockk<GeckoClient> {
            coEvery { queryLocation(any(), any(), any()) } returns GeckoResponse.Unreachable("down")
        }

        val result = check(gecko, genuineChain)

        assertEquals(VerificationState.UNREACHABLE, result.overall.state)
        assertEquals(true, result.hops.all { it.result == null })
    }

    @Test
    fun unregisteredSsid_isUnverified() = runTest {
        val otherSsid = mangoA.copy(wifi = mangoA.wifi.copy(ssid = "OtherNet"))

        assertEquals(VerificationState.UNVERIFIED, check(geckoReturning(otherSsid), genuineChain).overall.state)
    }

    @Test
    fun r1_proofFailure_isConflictAndNothingJudged() = runTest {
        val gecko = mockk<GeckoClient> {
            coEvery { queryLocation(any(), any(), any()) } returns GeckoResponse.ProofFailure("bad signature")
        }

        val result = check(gecko, genuineChain)

        assertEquals(VerificationState.CONFLICT, result.overall.state)
        assertEquals(true, result.hops.all { it.result == null })
    }

    @Test
    fun p2_redirectToUnlistedPaymentProcessor_isConflict() = runTest {
        // Benign in reality, but the processor isn't in the certificate: a false positive
        // until the operator lists it as a delegate.
        val chain = genuineChain + hop(3, "https://checkout.payments.example/pay", 200, key = "keyProcessor")

        val result = check(geckoReturning(mangoA), chain)

        assertEquals(VerificationState.CONFLICT, result.overall.state)
        assertEquals(VerificationState.CONFLICT, result.hops[3].result?.state)
    }

    @Test
    fun s9_domainWithoutPins_neverVerifies() = runTest {
        val unpinned = mangoA.copy(portal = PortalTLSIdentity(listOf(
            PortalDomain("portal.gecko-a.lab", PortalDomainRole.PRIMARY, emptyList())
        )))

        assertEquals(VerificationState.CONFLICT, check(geckoReturning(unpinned), genuineChain).overall.state)
    }

    @Test
    fun f1_attackerWithOwnCertificateForSameSsid_isVerified() = runTest {
        // Known limitation: GECKO can't tell two registered operators of the same SSID apart.
        val attacker = attackerCert()
        val chain = listOf(
            hop(0, "http://connectivitycheck.gstatic.com/generate_204", 302),
            hop(1, "https://login.attacker.lab/", 200, key = "keyAttacker")
        )

        val result = check(geckoReturning(mangoA, attacker), chain)

        assertEquals(VerificationState.VERIFIED, result.overall.state)
        assertEquals("attacker", result.overall.matchedCertificateId)
    }

    @Test
    fun f1s_switchToAnotherRegisteredPrimaryMidChain_reanchorsAndIsVerified() = runTest {
        // Known limitation: a second certificate's primary domain moves the anchor
        // instead of counting as a switch.
        val chain = genuineChain + hop(3, "https://login.attacker.lab/", 200, key = "keyAttacker")

        val result = check(geckoReturning(mangoA, attackerCert()), chain)

        assertEquals(VerificationState.VERIFIED, result.hops[2].result?.state)
        assertEquals(VerificationState.VERIFIED, result.hops[3].result?.state)
        assertEquals(VerificationState.VERIFIED, result.overall.state)
    }

    @Test
    fun x1_secondCertificateForSameSsid_doesNotBreakGenuineChain() = runTest {
        // The genuine primary still matches its own certificate, so no false positive.
        assertEquals(VerificationState.VERIFIED,
            check(geckoReturning(attackerCert(), mangoA), genuineChain).overall.state)
    }

    @Test
    fun failedTlsOnRegisteredPortalDomain_isConflict() = runTest {
        // The probe stopped at the registered domain without seeing a key (timeout,
        // refused, TLS error). Judged like "no key presented" -> CONFLICT.
        val chain = genuineChain.dropLast(1) + failedHop(2, "https://portal.gecko-a.lab/?tok=1")

        val result = check(geckoReturning(mangoA), chain, judgeFinal = false)

        assertEquals(VerificationState.CONFLICT, result.overall.state)
    }

    @Test
    fun failedTlsOnUnregisteredDomainOnRegisteredSsid_isConflict() = runTest {
        // On a registered SSID, a hop to an unregistered domain is impersonation
        // regardless of whether its TLS succeeded: exclusivity makes it CONFLICT.
        val chain = listOf(
            hop(0, "http://connectivitycheck.gstatic.com/generate_204", 302),
            failedHop(1, "https://unrelated.example/")
        )

        assertEquals(VerificationState.CONFLICT,
            check(geckoReturning(mangoA), chain, judgeFinal = false).overall.state)
    }

    private fun attackerCert() = GeoCertificate(
        schemaVersion = 2,
        certificateId = "attacker",
        wifi = WiFiIdentity(ssid = "GeckoTest", authMode = WiFiAuthMode.OPEN),
        portal = PortalTLSIdentity(listOf(
            PortalDomain("login.attacker.lab", PortalDomainRole.PRIMARY, listOf("keyAttacker"))
        )),
        notValidAfter = "2031-01-01T00:00:00Z"
    )

    private fun failedHop(index: Int, url: String): ObservedHop {
        val uri = java.net.URI(url)
        return ObservedHop(
            index = index, url = url, scheme = uri.scheme, host = uri.host, port = 443,
            statusCode = null, source = HopSource.Kind.REDIRECT_PROBE, timestampMillis = 0,
            error = "timeout"
        )
    }

    @Test
    fun repeatedChecks_startFreshSessions() = runTest {
        // A previous relay-then-switch must not leave an anchor that changes the next verdict.
        val eng = engine(geckoReturning(mangoA))
        eng.verifyPortalHops("net", genuineChain, true, 47.3777, 8.5484, null, 50, "GeckoTest")

        val lookalike = listOf(hop(0, "https://gecko-a-login.lab/", 200, key = "keyAttacker"))
        val second = eng.verifyPortalHops("net", lookalike, true, 47.3777, 8.5484, null, 50, "GeckoTest")

        // Under exclusivity both a fresh and a stale session would be CONFLICT,
        // so the reason is the discriminator: a fresh session escalates via SSID
        // exclusivity ("SSID exclusive"); a leaked anchor would instead report an
        // "off-anchor" domain. Seeing the former proves the session reset.
        assertEquals(VerificationState.CONFLICT, second.overall.state)
        assertEquals(true, second.overall.reason?.contains("SSID exclusive"))
        assertEquals(false, second.overall.reason?.contains("off-anchor"))
    }
}
