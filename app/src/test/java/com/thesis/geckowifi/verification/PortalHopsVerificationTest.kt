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
    fun s2_lookalikeDomain_isUnrecognized() = runTest {
        val lookalike = listOf(
            hop(0, "http://connectivitycheck.gstatic.com/generate_204", 302),
            hop(1, "https://gecko-a-login.lab/", 200, key = "keyAttacker")
        )

        assertEquals(VerificationState.UNRECOGNIZED, check(geckoReturning(mangoA), lookalike).overall.state)
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
    fun repeatedChecks_startFreshSessions() = runTest {
        // A previous relay-then-switch must not leave an anchor that changes the next verdict.
        val eng = engine(geckoReturning(mangoA))
        eng.verifyPortalHops("net", genuineChain, true, 47.3777, 8.5484, null, 50, "GeckoTest")

        val lookalike = listOf(hop(0, "https://gecko-a-login.lab/", 200, key = "keyAttacker"))
        val second = eng.verifyPortalHops("net", lookalike, true, 47.3777, 8.5484, null, 50, "GeckoTest")

        assertEquals(VerificationState.UNRECOGNIZED, second.overall.state)
    }
}
