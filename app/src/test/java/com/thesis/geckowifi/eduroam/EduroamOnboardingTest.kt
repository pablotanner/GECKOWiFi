package com.thesis.geckowifi.eduroam

import android.net.Network
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.eduroam.EduroamFixtures.now
import com.thesis.geckowifi.eduroam.EduroamFixtures.otherKey
import com.thesis.geckowifi.eduroam.EduroamFixtures.realmKey
import com.thesis.geckowifi.eduroam.EduroamFixtures.record
import com.thesis.geckowifi.eduroam.EduroamFixtures.sign
import com.thesis.geckowifi.verification.SiteCheck
import com.thesis.geckowifi.verification.VerificationEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EduroamOnboarding] and [decide] on the demo scenes (eduroam-brief.md,
 * section 3), with the platform steps faked. The point of every blocking case:
 * the installer, the only place a password goes, is never called.
 */
class EduroamOnboardingTest {
    private val alice = EduroamCredentials("alice@homeuni.test", "s3cret")
    private val target = EduroamTarget("eduroam-lab", "94:83:c4:97:c2:3a", setOf(WiFiAuthMode.WPA2_ENTERPRISE))
    private val area = QueryArea(47.3765, 8.5482, 50)

    private val registered = VerificationResult(VerificationState.REGISTERED, "eduroam-lab", matchedCertificateId = "mango-a-eduroam-lab")
    private val unreachable = VerificationResult(VerificationState.UNREACHABLE, "eduroam-lab", reason = "timeout")
    private val conflict = VerificationResult(VerificationState.CONFLICT, "eduroam-lab", reason = "advertises open")

    /** Onboarding transport that either comes up (and runs the block) or doesn't. */
    private class FakeTransport(private val comesUp: Boolean) : OnboardingTransport {
        var ran = false
        override suspend fun <T> withOnboardingNetwork(target: OnboardingTarget, timeoutMs: Int, block: suspend (Network) -> T): OnboardingOutcome<T> {
            ran = true
            return if (comesUp) OnboardingOutcome.Done(block(mockk())) else OnboardingOutcome.Failed("certificate not valid")
        }
    }

    private fun flow(transport: OnboardingTransport, site: VerificationResult, realm: RealmFetch, installer: EnterpriseProfileInstaller): EduroamOnboarding {
        val engine = mockk<VerificationEngine>()
        coEvery { engine.checkAdvertisedSite(any(), any(), any(), any(), any(), any(), any()) } returns SiteCheck(site)
        val realmClient = mockk<RealmRecordClient>()
        coEvery { realmClient.fetch(any()) } returns realm
        return EduroamOnboarding(transport, { engine }, { realmClient }, installer, realmKey = { realmKey.public }, clock = { now })
    }

    @Test
    fun scene1_happyPath_installsPinnedProfileAndConnects() = runTest {
        val installer = RecordingInstaller()
        val outcome = flow(FakeTransport(true), registered, RealmFetch.Fetched(sign(record())), installer).run(target, area, alice)

        assertTrue(outcome.decision is Decision.Proceed)
        assertEquals(true, outcome.connected)
        val (plan, password) = installer.installed.single()
        assertEquals("s3cret", password)
        assertEquals(listOf("radius.homeuni.test"), plan.serverNames)
        assertEquals(EduroamStep.entries, outcome.timings.map { it.first })
    }

    @Test
    fun scene2_rogueCertificate_noOnboardingSession_nothingSent() = runTest {
        val installer = RecordingInstaller()
        val outcome = flow(FakeTransport(false), registered, RealmFetch.Fetched(sign(record())), installer).run(target, area, alice)

        assertTrue(outcome.decision is Decision.Block)
        assertEquals("certificate not valid", outcome.onboardingFailure)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun scene3_withholding_mapAndRealmUnreachable_nothingSent() = runTest {
        val installer = RecordingInstaller()
        val outcome = flow(FakeTransport(true), unreachable, RealmFetch.Unreachable("connect timed out"), installer).run(target, area, alice)

        val block = outcome.decision as Decision.Block
        assertFalse(block.hostile)
        assertTrue(installer.installed.isEmpty())
        assertNull(outcome.connected)
    }

    @Test
    fun forgedRealmRecord_isBlockedAsHostile() = runTest {
        val installer = RecordingInstaller()
        val outcome = flow(FakeTransport(true), registered, RealmFetch.Fetched(sign(record(), otherKey)), installer).run(target, area, alice)

        assertTrue((outcome.decision as Decision.Block).hostile)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun conflictingSite_isBlockedEvenWithValidRecord() = runTest {
        val installer = RecordingInstaller()
        val outcome = flow(FakeTransport(true), conflict, RealmFetch.Fetched(sign(record())), installer).run(target, area, alice)

        assertTrue((outcome.decision as Decision.Block).hostile)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun unregisteredSiteWithVerifiedRecord_proceeds_likeCatAtAnyEduroamSite() = runTest {
        val unverified = VerificationResult(VerificationState.UNVERIFIED, "eduroam-lab")
        val installer = RecordingInstaller()
        val outcome = flow(FakeTransport(true), unverified, RealmFetch.Fetched(sign(record())), installer).run(target, area, alice)

        assertTrue(outcome.decision is Decision.Proceed)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun noRecordForTheRealm_isBlocked() = runTest {
        val installer = RecordingInstaller()
        val outcome = flow(FakeTransport(true), registered, RealmFetch.NotFound, installer).run(target, area, alice)

        assertTrue(outcome.decision is Decision.Block)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun identityWithoutRealm_neverStartsOnboarding() = runTest {
        val transport = FakeTransport(true)
        val outcome = flow(transport, registered, RealmFetch.NotFound, RecordingInstaller()).run(target, area, EduroamCredentials("alice", "s3cret"))

        assertTrue(outcome.decision is Decision.Block)
        assertFalse(transport.ran)
    }

    @Test
    fun realEduroam_isNeverOnboarded() = runTest {
        val transport = FakeTransport(true)
        val outcome = flow(transport, registered, RealmFetch.Fetched(sign(record())), RecordingInstaller())
            .run(target.copy(ssid = "eduroam"), area, alice)

        assertTrue(outcome.decision is Decision.Block)
        assertFalse(transport.ran)
    }

    @Test
    fun suggestionRefusedByAndroid_isReportedNotConnected() = runTest {
        val installer = RecordingInstaller(InstallOutcome.Failed("disallowed"))
        val outcome = flow(FakeTransport(true), registered, RealmFetch.Fetched(sign(record())), installer).run(target, area, alice)

        assertEquals(InstallOutcome.Failed("disallowed"), outcome.install)
        assertNull(outcome.connected)
    }

    @Test
    fun decide_prefersSiteRegistrationOverRealmRecord() {
        val site = EduroamFixtures.pins()
        val other = EduroamFixtures.pins(record(names = listOf("radius2.homeuni.test")))
        val decision = decide(registered, PinResult.Ok(site), RealmCheck.Pinned(other)) as Decision.Proceed
        assertEquals(site, decision.pins)
    }
}
