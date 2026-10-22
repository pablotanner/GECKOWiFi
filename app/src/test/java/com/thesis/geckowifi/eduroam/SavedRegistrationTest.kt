package com.thesis.geckowifi.eduroam

import com.thesis.geckowifi.ZURICH_AREA
import com.thesis.geckowifi.data.local.SavedLookup
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.eduroam.EduroamFixtures.now
import com.thesis.geckowifi.eduroam.EduroamFixtures.otherKey
import com.thesis.geckowifi.eduroam.EduroamFixtures.realmKey
import com.thesis.geckowifi.eduroam.EduroamFixtures.record
import com.thesis.geckowifi.eduroam.EduroamFixtures.sign
import com.thesis.geckowifi.eduroam.EduroamFixtures.siteCert
import com.thesis.geckowifi.verification.coversLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * The saved-registration path (docs/eduroam-decisions.md, D21): when a saved
 * answer may replace onboarding, and that the matching on it gives the same
 * decisions as an onboarding session would.
 */
class SavedRegistrationTest {
    private val inZurich = 47.3765 to 8.5482
    private val store = InMemoryAccountStore()
    private val alice = store.save("alice@homeuni.test", "s3cret")
    private val target = EnterpriseTarget("eduroam-lab", null, setOf(WiFiAuthMode.WPA2_ENTERPRISE))
    private val source = DataSource.Saved(now.toEpochMilli(), "Wi-Fi (GeckoTest)")

    private fun cert(federation: String? = "eduroam", realms: List<String> = emptyList()): GeoCertificate =
        siteCert(federation, realms).copy(areas = listOf(ZURICH_AREA))

    private fun saved(vararg certs: GeoCertificate, age: Duration = Duration.ofHours(1)) = SavedLookup(
        fetchedAt = now.minus(age).toEpochMilli(), lat = inZurich.first, lng = inZurich.second, radiusMeters = 50,
        route = "Wi-Fi", viaSsid = "GeckoTest", certificates = certs.toList()
    )

    private fun usable(lookup: SavedLookup?, ssid: String = "eduroam-lab", at: Pair<Double, Double> = inZurich) =
        usableSavedRegistration(lookup, ssid, at.first, at.second, 50, now)

    private fun decideOn(report: OnboardingReport) = decide(report.siteResult, report.site, report.checks, store.accounts(), null)

    @Test
    fun usable_recentEnterpriseRegistrationHere() {
        assertEquals(listOf("site-cert"), usable(saved(cert()))?.map { it.certificateId })
    }

    @Test
    fun notUsable_nothingSaved_tooOld_elsewhere_otherSsid() {
        assertNull(usable(null))
        assertNull(usable(saved(cert(), age = Duration.ofHours(25))))
        assertNull(usable(saved(cert()), at = 46.948 to 7.447)) // Bern
        assertNull(usable(saved(cert()), ssid = "other-net"))
    }

    /** D26: the certificate's own area still covers the new spot, but the saved answer wasn't fetched for it. */
    @Test
    fun notUsable_movedOutOfTheSavedArea_evenThoughTheCertificateCoversTheNewSpot() {
        val fourHundredMetresNorth = 47.3800 to 8.5482
        assertTrue(cert().coversLocation(fourHundredMetresNorth.first, fourHundredMetresNorth.second, 50))
        assertNull(usable(saved(cert()), at = fourHundredMetresNorth))
    }

    @Test
    fun notUsable_expiredCertificate() {
        assertNull(usable(saved(cert().copy(notValidAfter = "2026-01-01T00:00:00Z"))))
    }

    @Test
    fun notUsable_onlyNonEnterpriseRegistration() {
        val open = cert().let { it.copy(wifi = it.wifi.copy(authModeNames = listOf("open"), enterprise = null)) }
        assertNull(usable(saved(open)))
    }

    @Test
    fun usable_keepsEveryApplicableCertForTheSsid_soAMismatchIsSeen() {
        val open = cert().let { it.copy(certificateId = "open-cert", wifi = it.wifi.copy(authModeNames = listOf("open"), enterprise = null)) }
        assertEquals(setOf("site-cert", "open-cert"), usable(saved(cert(), open))!!.map { it.certificateId }.toSet())
    }

    @Test
    fun local_pinsFromTheSavedRegistration_proceed() {
        val report = checkSaved(target, listOf(cert(federation = null, realms = listOf("homeuni.test"))), store.accounts(),
            { _, _ -> null }, source, realmKey.public, now)

        assertEquals(VerificationState.REGISTERED, report.siteResult!!.state)
        assertEquals(source, report.source)
        assertEquals(alice, (decideOn(report) as Decision.Proceed).account)
    }

    @Test
    fun visitor_savedRecord_proceeds() {
        val bytes = sign(record())
        val report = checkSaved(target, listOf(cert()), store.accounts(),
            { f, r -> bytes.takeIf { f == "eduroam" && r == "homeuni.test" } }, source, realmKey.public, now)

        val proceed = decideOn(report) as Decision.Proceed
        assertEquals(listOf("radius.homeuni.test"), proceed.pins.serverNames)
    }

    @Test
    fun visitor_noSavedRecord_missing_soTheCallerCanOnboard() {
        val report = checkSaved(target, listOf(cert()), store.accounts(), { _, _ -> null }, source, realmKey.public, now)

        assertEquals(MissingCause.NOT_SAVED, (report.checks.single() as PinCheck.Missing).cause)
        val ask = decideOn(report) as Decision.NeedAccount
        assertEquals(UnusableAccount.Why.NOT_SAVED, ask.prompt.unusable.single().why)
    }

    @Test
    fun visitor_savedRecordWithBadSignature_hostile() {
        val forged = sign(record(), otherKey)
        val report = checkSaved(target, listOf(cert()), store.accounts(), { _, _ -> forged }, source, realmKey.public, now)

        assertTrue((report.checks.single() as PinCheck.Rejected).hostile)
        assertTrue((decideOn(report) as Decision.Block).hostile)
    }

    @Test
    fun visitor_savedRecordExpiredSinceSaving_notUsed() {
        val stale = sign(record(expiresAt = now.minusSeconds(60)))
        val report = checkSaved(target, listOf(cert()), store.accounts(), { _, _ -> stale }, source, realmKey.public, now)

        assertNotNull(decideOn(report) as? Decision.Block)
    }

    @Test
    fun advertisedSecurityDiffersFromSavedRegistration_conflictAndBlocked() {
        val openAp = target.copy(authModes = setOf(WiFiAuthMode.OPEN))
        val report = checkSaved(openAp, listOf(cert()), store.accounts(), { _, _ -> sign(record()) }, source, realmKey.public, now)

        assertEquals(VerificationState.CONFLICT, report.siteResult!!.state)
        assertTrue(decideOn(report) is Decision.Block)
    }
}
