package com.thesis.geckowifi.eduroam

import android.net.Network
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.network.Association
import com.thesis.geckowifi.network.AssociationSource
import com.thesis.geckowifi.network.RsnProfile
import com.thesis.geckowifi.eduroam.EduroamFixtures.now
import com.thesis.geckowifi.eduroam.EduroamFixtures.realmKey
import com.thesis.geckowifi.eduroam.EduroamFixtures.record
import com.thesis.geckowifi.eduroam.EduroamFixtures.sign
import com.thesis.geckowifi.eduroam.EduroamFixtures.siteCert
import com.thesis.geckowifi.verification.SiteCheck
import com.thesis.geckowifi.verification.VerificationEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EnterpriseOnboarding] on the demo scenes and the generic cases (D19), with
 * the platform steps faked. In every blocking case the installer, the only
 * place a password goes, is never reached.
 */
class EnterpriseOnboardingTest {
    private val store = InMemoryAccountStore()
    private val alice = store.save("alice@homeuni.test", "s3cret")
    private val target = EnterpriseTarget("eduroam-lab", "96:83:c4:97:c2:3a", RsnProfile.fromCapabilities("[RSN-EAP/SHA1-CCMP][ESS]"))
    private val area = QueryArea(47.3765, 8.5482, 50)
    private val registered = VerificationResult(VerificationState.REGISTERED, "eduroam-lab", matchedCertificateId = "site-cert")

    private class FakeTransport(private val comesUp: Boolean) : OnboardingTransport {
        var ran = false
        override suspend fun <T> withOnboardingNetwork(target: OnboardingTarget, timeoutMs: Int, block: suspend (Network) -> T): OnboardingOutcome<T> {
            ran = true
            return if (comesUp) OnboardingOutcome.Done(block(mockk())) else OnboardingOutcome.Failed("certificate not valid")
        }
    }

    private val realmClient = mockk<RealmRecordClient>()

    private fun flow(
        transport: OnboardingTransport,
        site: SiteCheck,
        records: Map<Pair<String, String>, RealmFetch> = emptyMap(),
        installer: EnterpriseProfileInstaller = RecordingInstaller(),
        joined: Association? = null
    ): EnterpriseOnboarding {
        val engine = mockk<VerificationEngine>()
        coEvery { engine.checkAdvertisedSite(any(), any(), any(), any(), any(), any(), any()) } returns site
        coEvery { realmClient.fetch(any(), any()) } answers { records[firstArg<String>() to secondArg<String>()] ?: RealmFetch.NotFound }
        val association = object : AssociationSource { override suspend fun current() = joined }
        return EnterpriseOnboarding(transport, { engine }, { realmClient }, installer, association, realmKey = { realmKey.public }, clock = { now })
    }

    private fun eduroamSite(vararg extra: GeoCertificate) = SiteCheck(registered, listOf(siteCert("eduroam", emptyList())) + extra)

    private suspend fun EnterpriseOnboarding.run(accounts: List<AccountInfo> = store.accounts()): Pair<OnboardingReport, Decision> {
        val report = check(target, area, accounts)
        return report to decide(report.siteResult, report.site, report.checks, accounts, null)
    }

    @Test
    fun scene1_visitor_recordOfTheSitesFederation_proceedsAndConnects() = runTest {
        val installer = RecordingInstaller()
        val f = flow(FakeTransport(true), eduroamSite(), mapOf(("eduroam" to "homeuni.test") to RealmFetch.Fetched(sign(record()))), installer)
        val (report, decision) = f.run()

        val proceed = decision as Decision.Proceed
        assertEquals(alice, proceed.account)
        coVerify { realmClient.fetch("eduroam", "homeuni.test") }

        val outcome = f.connect(target, report.site!!, store.load(alice.id)!!, proceed.pins)
        assertEquals(true, outcome.connected)
        val (plan, password) = installer.installed.single()
        assertEquals("s3cret", password)
        assertEquals(listOf("radius.homeuni.test"), plan.serverNames)
        assertEquals(EnterpriseStep.entries.take(2), report.timings.map { it.first })
    }

    @Test
    fun recordOfAnotherFederation_isTreatedAsTampering() = runTest {
        // The server answers the eduroam path with the govroam record of the same realm.
        val f = flow(FakeTransport(true), eduroamSite(), mapOf(("eduroam" to "homeuni.test") to RealmFetch.Fetched(sign(record(federation = "govroam")))))
        val (_, decision) = f.run()
        assertTrue((decision as Decision.Block).hostile)
    }

    @Test
    fun govroamSite_fetchesTheGovroamRecord() = runTest {
        val site = SiteCheck(registered, listOf(siteCert("govroam", emptyList())))
        val f = flow(FakeTransport(true), site, mapOf(("govroam" to "homeuni.test") to RealmFetch.Fetched(sign(record(federation = "govroam")))))
        val (_, decision) = f.run()
        assertEquals("govroam", (decision as Decision.Proceed).pins.federation)
        coVerify(exactly = 0) { realmClient.fetch("eduroam", any()) }
    }

    @Test
    fun twoAccountsWithRecords_askWhichOne() = runTest {
        val bob = store.save("bob@otheruni.test", "pw2")
        val f = flow(FakeTransport(true), eduroamSite(), mapOf(
            ("eduroam" to "homeuni.test") to RealmFetch.Fetched(sign(record())),
            ("eduroam" to "otheruni.test") to RealmFetch.Fetched(sign(record(realm = "otheruni.test", outer = "anonymous@otheruni.test")))
        ))
        val (_, decision) = f.run()
        assertEquals(setOf(alice, bob), (decision as Decision.ChooseAccount).options.map { it.match.account }.toSet())
    }

    @Test
    fun localAccount_takesPinsFromTheRegistration_withoutFetchingARecord() = runTest {
        val staff = store.save("carol@site.test", "pw3")
        val f = flow(FakeTransport(true), SiteCheck(registered, listOf(siteCert(null, listOf("site.test")))))
        val (_, decision) = f.run(listOf(staff))
        val proceed = decision as Decision.Proceed
        assertEquals(staff, proceed.account)
        assertTrue(proceed.pins.source is PinSource.SiteCertificate)
        coVerify(exactly = 0) { realmClient.fetch(any(), any()) }
    }

    @Test
    fun noAccounts_asksForOne_afterLearningTheRegistration() = runTest {
        val f = flow(FakeTransport(true), eduroamSite())
        val (_, decision) = f.run(emptyList())
        assertEquals("eduroam", (decision as Decision.NeedAccount).prompt.federation)
    }

    @Test
    fun scene2_rogueCertificate_noOnboardingSession_nothingSent() = runTest {
        val f = flow(FakeTransport(false), eduroamSite())
        val (report, decision) = f.run()
        assertEquals("certificate not valid", report.onboardingFailure)
        assertTrue(decision is Decision.Block)
    }

    @Test
    fun scene3_withholding_mapUnreachable_nothingFetched() = runTest {
        val f = flow(FakeTransport(true), SiteCheck(VerificationResult(VerificationState.UNREACHABLE, "eduroam-lab", reason = "timeout")))
        val (report, decision) = f.run()
        assertNull(report.site)
        assertFalse((decision as Decision.Block).hostile)
        coVerify(exactly = 0) { realmClient.fetch(any(), any()) }
    }

    @Test
    fun anySsid_isOnboarded_noNameAllowlist() = runTest {
        // Network without GECKO onboarding (e.g. production eduroam): the session simply fails, nothing secret is sent.
        val transport = FakeTransport(false)
        val report = flow(transport, eduroamSite()).check(target.copy(ssid = "eduroam"), area, store.accounts())
        assertTrue(transport.ran)
        assertEquals("certificate not valid", report.onboardingFailure)
    }

    /** The site registered WPA3-Enterprise only; the tapped AP is a transition AP, so connecting is allowed. */
    private fun siteRegistering(vararg modes: String) = EnterpriseSite.from("eduroam-lab",
        listOf(siteCert("eduroam", emptyList()).let { it.copy(wifi = it.wifi.copy(authModeNames = modes.toList())) }))!!
    private val transitionAp = target.copy(profile = RsnProfile.fromCapabilities("[RSN-EAP/SHA1+EAP/SHA256-CCMP][MFPC][ESS]"))

    @Test
    fun connect_reportsTheAccessPointAndModeActuallyNegotiated() = runTest {
        // Android picked another access point of the SSID than the tapped one: the outcome says which.
        val joined = Association("96:83:c4:97:c2:47", WiFiAuthMode.WPA3_ENTERPRISE)
        val installer = RecordingInstaller()
        val outcome = flow(FakeTransport(true), eduroamSite(), installer = installer, joined = joined)
            .connect(transitionAp, siteRegistering("wpa3-enterprise"), store.load(alice.id)!!, EduroamFixtures.pins(record()))
        assertEquals(true, outcome.connected)
        assertEquals(joined, outcome.joined)
        assertNull(outcome.downgrade)
        assertTrue(installer.removed.isEmpty())
    }

    @Test
    fun connect_negotiatedBelowRegistered_removesTheProfile() = runTest {
        val installer = RecordingInstaller()
        val outcome = flow(FakeTransport(true), eduroamSite(), installer = installer,
            joined = Association("96:83:c4:97:c2:3a", WiFiAuthMode.WPA2_ENTERPRISE))
            .connect(transitionAp, siteRegistering("wpa3-enterprise"), store.load(alice.id)!!, EduroamFixtures.pins(record()))
        assertEquals(false, outcome.connected)
        assertTrue(outcome.downgrade!!.contains("negotiated wpa2-enterprise"))
        assertEquals(listOf("eduroam-lab"), installer.removed)
    }

    @Test
    fun connect_192BitOnlyAp_isRefusedBeforeInstalling() = runTest {
        val installer = RecordingInstaller()
        val suiteB = target.copy(profile = RsnProfile.fromCapabilities("[RSN-EAP_SUITE_B_192-GCMP-256][MFPR][MFPC][ESS]"))
        val outcome = flow(FakeTransport(true), eduroamSite(), installer = installer)
            .connect(suiteB, siteRegistering("wpa3-enterprise-192"), store.load(alice.id)!!, EduroamFixtures.pins(record()))
        assertTrue((outcome.install as InstallOutcome.Failed).reason.contains("192-bit"))
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun connect_refusesPinsOfAnotherFederation() = runTest {
        val installer = RecordingInstaller()
        val f = flow(FakeTransport(true), eduroamSite(), installer = installer)
        val govroamPins = EduroamFixtures.pins(record(federation = "govroam"))
        val thrown = runCatching { f.connect(target, EduroamFixtures.site("eduroam"), store.load(alice.id)!!, govroamPins) }.exceptionOrNull()
        assertTrue(thrown is CredentialsRefused)
        assertTrue(installer.installed.isEmpty())
    }
}

/** [InMemoryAccountStore], the reference for [KeystoreAccountStore]'s contract. */
class AccountStoreTest {
    @Test
    fun savingTheSameIdentityAgain_replacesThePassword() {
        val store = InMemoryAccountStore()
        val first = store.save("alice@homeuni.test", "old")
        val second = store.save("Alice@HomeUni.test ", "new")
        assertEquals(first.id, second.id)
        assertEquals(1, store.accounts().size)
        assertTrue(store.load(first.id)!!.identity.equals("alice@homeuni.test", ignoreCase = true))
    }

    @Test
    fun removingAnAccount_forgetsItAsPreferredChoice() {
        val store = InMemoryAccountStore()
        val a = store.save("alice@homeuni.test", "pw")
        store.setPreferredAccount("eduroam-lab", a.id)
        store.remove(a.id)
        assertNull(store.preferredAccount("eduroam-lab"))
        assertNull(store.load(a.id))
    }

    @Test
    fun realmIsDerivedFromTheIdentity() {
        assertEquals("homeuni.test", AccountInfo("x", "alice@HomeUni.test").realm)
        assertNull(AccountInfo("x", "alice").realm)
    }
}
