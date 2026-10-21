package com.thesis.geckowifi.eduroam

import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.eduroam.EduroamFixtures.pins
import com.thesis.geckowifi.eduroam.EduroamFixtures.record
import com.thesis.geckowifi.eduroam.EduroamFixtures.site
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [matchAccounts] and [decide]: which account goes where (docs/eduroam-decisions.md, D19, D2). */
class EnterpriseMatchingTest {
    private val alice = AccountInfo("a", "alice@homeuni.test")
    private val bob = AccountInfo("b", "bob@otheruni.test")
    private val staff = AccountInfo("s", "carol@site.test")
    private val noRealm = AccountInfo("n", "dave")
    private val registered = VerificationResult(VerificationState.REGISTERED, "eduroam-lab")

    // --- matching ---

    @Test
    fun federatedSite_foreignAccountIsAVisitorOfThatFederation() {
        assertEquals(listOf(AccountMatch.Visitor(alice, "eduroam")), matchAccounts(site("eduroam"), listOf(alice)))
    }

    @Test
    fun federationNameComesFromTheRegistration_notHardcoded() {
        assertEquals(listOf(AccountMatch.Visitor(alice, "govroam")), matchAccounts(site("GovRoam"), listOf(alice)))
    }

    @Test
    fun accountTheSiteListsIsLocal_evenInAFederation() {
        assertEquals(listOf(AccountMatch.Local(staff)), matchAccounts(site("eduroam", listOf("site.test")), listOf(staff)))
    }

    @Test
    fun wildcardRealmsCoverSubrealms() {
        val sub = AccountInfo("x", "eve@staff.site.test")
        assertEquals(listOf(AccountMatch.Local(sub)), matchAccounts(site(null, listOf("*.site.test")), listOf(sub)))
    }

    @Test
    fun closedNetwork_onlyItsOwnAccountsFit() {
        assertEquals(listOf(AccountMatch.Local(staff)), matchAccounts(site(null, listOf("site.test")), listOf(alice, staff, bob)))
    }

    @Test
    fun accountWithoutRealm_neverFits() {
        assertEquals(emptyList<AccountMatch>(), matchAccounts(site("eduroam"), listOf(noRealm)))
    }

    @Test
    fun siteWithoutEnterpriseRegistration_isNoSite() {
        val open = EduroamFixtures.siteCert("eduroam", emptyList()).let { it.copy(wifi = it.wifi.copy(enterprise = null)) }
        assertNull(EnterpriseSite.from("eduroam-lab", listOf(open)))
    }

    @Test
    fun siteTakesOperatorNameAndRealmsFromTheRegistration() {
        val s = site(null, listOf("site.test"))
        assertEquals("Lab Operator", s.operatorName)
        assertEquals(listOf("site.test"), s.localRealms)
        assertNull(s.federation)
    }

    // --- decision ---

    private fun pinned(account: AccountInfo, federation: String = "eduroam") =
        PinCheck.Pinned(AccountMatch.Visitor(account, federation), pins(record(federation = federation, realm = account.realm!!, outer = "anonymous@${account.realm}")))

    @Test
    fun conflictingSite_isBlockedAsHostile() {
        val conflict = VerificationResult(VerificationState.CONFLICT, "eduroam-lab", reason = "advertises open")
        val d = decide(conflict, site("eduroam"), listOf(pinned(alice)), listOf(alice), null)
        assertTrue((d as Decision.Block).hostile)
    }

    @Test
    fun unregisteredSite_blocks_becauseNoFederationIsKnown() {
        val d = decide(VerificationResult(VerificationState.UNVERIFIED, "eduroam-lab"), null, emptyList(), listOf(alice), null) as Decision.Block
        assertTrue(!d.hostile && d.reason.contains("isn't registered"))
    }

    @Test
    fun unreachableMap_blocks_withoutWarning() {
        val d = decide(VerificationResult(VerificationState.UNREACHABLE, "eduroam-lab", reason = "timeout"), null, emptyList(), listOf(alice), null) as Decision.Block
        assertTrue(!d.hostile && d.reason.contains("wasn't reachable"))
    }

    @Test
    fun oneVerifiedAccount_proceeds() {
        val d = decide(registered, site("eduroam"), listOf(pinned(alice)), listOf(alice), null) as Decision.Proceed
        assertEquals(alice, d.account)
    }

    @Test
    fun twoVerifiedAccounts_askWhichOne() {
        val d = decide(registered, site("eduroam"), listOf(pinned(alice), pinned(bob)), listOf(alice, bob), null)
        assertEquals(2, (d as Decision.ChooseAccount).options.size)
    }

    @Test
    fun twoVerifiedAccounts_useTheRememberedChoice() {
        val d = decide(registered, site("eduroam"), listOf(pinned(alice), pinned(bob)), listOf(alice, bob), bob.id) as Decision.Proceed
        assertEquals(bob, d.account)
    }

    @Test
    fun noFittingAccount_asksForOne_inTheRegistrationsWords() {
        val d = decide(registered, site(null, listOf("site.test")), emptyList(), listOf(alice), null) as Decision.NeedAccount
        assertNull(d.prompt.federation)
        assertEquals("Lab Operator", d.prompt.operatorName)
        assertEquals(listOf("site.test"), d.prompt.localRealms)
        assertEquals(listOf(UnusableAccount(alice, UnusableAccount.Why.NOT_ACCEPTED)), d.prompt.unusable)
    }

    @Test
    fun forgedRecord_blocksAsHostile_evenIfAnotherAccountVerified() {
        val forged = PinCheck.Rejected(AccountMatch.Visitor(bob, "eduroam"), "signature doesn't verify", hostile = true)
        val d = decide(registered, site("eduroam"), listOf(pinned(alice), forged), listOf(alice, bob), null)
        assertTrue((d as Decision.Block).hostile)
    }

    @Test
    fun unreachableRecordServer_blocksWithoutWarning() {
        val missing = PinCheck.Missing(AccountMatch.Visitor(alice, "eduroam"), "record server not reachable", MissingCause.UNREACHABLE)
        val d = decide(registered, site("eduroam"), listOf(missing), listOf(alice), null) as Decision.Block
        assertTrue(!d.hostile)
    }

    @Test
    fun noRecordForTheHomeInstitution_asksForAnotherAccount_withTheReason() {
        val missing = PinCheck.Missing(AccountMatch.Visitor(alice, "eduroam"), "no record", MissingCause.NO_RECORD)
        val d = decide(registered, site("eduroam"), listOf(missing), listOf(alice), null) as Decision.NeedAccount
        assertEquals(listOf(UnusableAccount(alice, UnusableAccount.Why.NO_RECORD)), d.prompt.unusable)
    }

    @Test
    fun recordNotSaved_asksForAnotherAccount_withTheReason() {
        val missing = PinCheck.Missing(AccountMatch.Visitor(alice, "eduroam"), "no saved record", MissingCause.NOT_SAVED)
        val d = decide(registered, site("eduroam"), listOf(missing), listOf(alice), null) as Decision.NeedAccount
        assertEquals(UnusableAccount.Why.NOT_SAVED, d.prompt.unusable.single().why)
    }

    @Test
    fun registrationWithoutCaCertificate_asksForAnotherAccount_noWarning() {
        val incomplete = PinCheck.Rejected(AccountMatch.Local(staff), "nothing to pin", hostile = false)
        val d = decide(registered, site(null, listOf("site.test")), listOf(incomplete), listOf(staff), null) as Decision.NeedAccount
        assertEquals(listOf(UnusableAccount(staff, UnusableAccount.Why.INCOMPLETE_REGISTRATION)), d.prompt.unusable)
    }

    @Test
    fun unreachableBeatsNoRecord_soTheUserCanRetry() {
        val gone = PinCheck.Missing(AccountMatch.Visitor(alice, "eduroam"), "no record", MissingCause.NO_RECORD)
        val down = PinCheck.Missing(AccountMatch.Visitor(bob, "eduroam"), "unreachable", MissingCause.UNREACHABLE)
        assertTrue(decide(registered, site("eduroam"), listOf(gone, down), listOf(alice, bob), null) is Decision.Block)
    }

    // --- local pins from the registration ---

    @Test
    fun localAccount_getsPinsFromTheRegistration() {
        val pins = (site("eduroam", listOf("site.test")).localPins("site.test") as PinResult.Ok).pins
        assertEquals(listOf("radius.site.test"), pins.serverNames)
        assertEquals("eduroam", pins.federation)
    }

    @Test
    fun closedNetworkPins_haveNoFederation() {
        assertNull((site(null, listOf("site.test")).localPins("site.test") as PinResult.Ok).pins.federation)
    }
}
