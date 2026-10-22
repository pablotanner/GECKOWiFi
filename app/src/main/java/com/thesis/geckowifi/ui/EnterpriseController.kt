package com.thesis.geckowifi.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thesis.geckowifi.eduroam.AccountInfo
import com.thesis.geckowifi.eduroam.AccountStore
import com.thesis.geckowifi.eduroam.ConnectOutcome
import com.thesis.geckowifi.eduroam.CredentialsRefused
import com.thesis.geckowifi.eduroam.Decision
import com.thesis.geckowifi.eduroam.EnterpriseOnboarding
import com.thesis.geckowifi.eduroam.EnterpriseProfileInstaller
import com.thesis.geckowifi.eduroam.EnterpriseSite
import com.thesis.geckowifi.eduroam.EnterpriseStep
import com.thesis.geckowifi.eduroam.EnterpriseTarget
import com.thesis.geckowifi.eduroam.InstallOutcome
import com.thesis.geckowifi.eduroam.OnboardingReport
import com.thesis.geckowifi.eduroam.PinCheck
import com.thesis.geckowifi.eduroam.QueryArea
import com.thesis.geckowifi.eduroam.SignInPrompt
import com.thesis.geckowifi.eduroam.decide
import com.thesis.geckowifi.location.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "EnterpriseFlow"

/** One finished enterprise connection attempt, for the status card, History and the evidence screen. */
data class EnterpriseRun(
    val target: EnterpriseTarget,
    val fix: LocationFix,
    val report: OnboardingReport,
    val decision: Decision,
    val account: AccountInfo? = null,
    val connect: ConnectOutcome? = null,
    /** Set when connecting threw [CredentialsRefused] (a bug guard; should not happen). */
    val refused: String? = null
)

/**
 * A saved registration that lets the flow skip onboarding (docs/eduroam-decisions.md,
 * D21): the applicable certificates, where they came from, and the saved realm records.
 */
data class SavedInput(
    val certificates: List<com.thesis.geckowifi.data.model.GeoCertificate>,
    val source: com.thesis.geckowifi.eduroam.DataSource.Saved,
    val record: (federation: String, realm: String) -> ByteArray?
)

/** The sign-in sheet is open for [target]; its wording comes from [prompt]. */
data class SignInRequest(val target: EnterpriseTarget, val fix: LocationFix, val area: QueryArea, val prompt: SignInPrompt)

/** Several accounts fit [target]; the user picks one. */
data class ChoiceRequest(val run: EnterpriseRun, val options: List<PinCheck.Pinned>)

/**
 * Drives the generic enterprise flow (docs/eduroam-decisions.md, D19, D20)
 * for the network list: tap → onboarding check → [decide] → connect, or ask
 * for an account / a choice in between. Holds the state of the sign-in sheet
 * and the account chooser; reports steps and results to the view model.
 */
class EnterpriseController(
    private val scope: CoroutineScope,
    private val onboarding: EnterpriseOnboarding,
    private val accountStore: AccountStore,
    private val installer: EnterpriseProfileInstaller,
    private val onStep: (EnterpriseTarget, EnterpriseStep?) -> Unit,
    private val onResult: (EnterpriseRun) -> Unit,
    /** A usable saved registration for the target here and now, or null (D21). */
    private val savedFor: (EnterpriseTarget, LocationFix, QueryArea) -> SavedInput? = { _, _, _ -> null },
    /** Realm records fetched over an onboarding session, to be saved for next time. */
    private val onRecordsFetched: (Map<Pair<String, String>, ByteArray>) -> Unit = {}
) {
    var signIn by mutableStateOf<SignInRequest?>(null)
        private set
    var choice by mutableStateOf<ChoiceRequest?>(null)
        private set
    var accounts by mutableStateOf(accountStore.accounts())
        private set

    /** SSIDs with a pinned profile GECKO installed: what the status card and Settings offer to forget. */
    var installedSsids by mutableStateOf(installer.installedSsids())
        private set

    fun isInstalled(ssid: String): Boolean = installedSsids.any { it.equals(ssid, ignoreCase = true) }

    /** Re-reads [installedSsids]; a profile can also go away outside GECKO (e.g. the app's Wi-Fi permission revoked). */
    fun refreshInstalled() {
        installedSsids = installer.installedSsids()
    }

    private var job: Job? = null

    fun supports(ssid: String): Boolean = onboarding.supports(ssid)

    fun start(target: EnterpriseTarget, fix: LocationFix, area: QueryArea) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val (report, decision) = checkAndDecide(target, fix, area)
                Log.i(TAG, "${target.ssid}: $decision")
                when (decision) {
                    is Decision.Proceed -> connect(EnterpriseRun(target, fix, report, decision), decision)
                    is Decision.ChooseAccount -> choice = ChoiceRequest(EnterpriseRun(target, fix, report, decision), decision.options)
                    is Decision.NeedAccount -> signIn = SignInRequest(target, fix, area, decision.prompt)
                    is Decision.Block -> onResult(EnterpriseRun(target, fix, report, decision))
                }
            } finally {
                onStep(target, null)
            }
        }
    }

    /**
     * Phase 1 and the decision (D21): from a saved registration when there is a
     * usable one, so no onboarding is needed; onboarding when there is none, or
     * when the saved data lacks a visitor's realm record and the network
     * supports GECKO onboarding.
     */
    private suspend fun checkAndDecide(target: EnterpriseTarget, fix: LocationFix, area: QueryArea): Pair<OnboardingReport, Decision> {
        fun decideOn(r: OnboardingReport) =
            decide(r.siteResult, r.site, r.checks, accounts, accountStore.preferredAccount(preferenceKey(target, r.site)))

        val saved = savedFor(target, fix, area)
        if (saved != null) {
            onStep(target, EnterpriseStep.CHECK_NETWORK)
            val report = onboarding.checkSaved(target, saved.certificates, accounts, saved.record, saved.source)
            val decision = decideOn(report)
            // A visitor's record wasn't saved: onboarding can fetch it, where the network supports it.
            val missingRecord = (decision is Decision.NeedAccount || decision is Decision.Block && !decision.hostile) &&
                report.checks.any { it is PinCheck.Missing && it.cause == com.thesis.geckowifi.eduroam.MissingCause.NOT_SAVED }
            if (!missingRecord || !onboarding.supports(target.ssid)) return report to decision
        }
        if (!onboarding.supports(target.ssid)) {
            val report = OnboardingReport(null, null, null, emptyList())
            return report to Decision.Block(
                "GECKO has no recent registration for ${target.ssid} here, and this network doesn't support GECKO onboarding. " +
                    "Connect to a network with internet so GECKO can look it up, then try again", hostile = false)
        }
        val report = onboarding.check(target, area, accounts) { onStep(target, it) }
        if (report.fetchedRecords.isNotEmpty()) onRecordsFetched(report.fetchedRecords)
        return report to decideOn(report)
    }

    /** From the sign-in sheet: save the account, then check again (its pins are only fetched over a new onboarding session). */
    fun submitSignIn(identity: String, password: String) {
        val request = signIn ?: return
        signIn = null
        accountStore.save(identity, password)
        accounts = accountStore.accounts()
        start(request.target, request.fix, request.area)
    }

    fun dismissSignIn() {
        signIn = null
    }

    /** From the chooser: remember the choice for this network, then connect with it. */
    fun choose(accountId: String) {
        val request = choice ?: return
        choice = null
        val picked = request.options.firstOrNull { it.match.account.id == accountId } ?: return
        accountStore.setPreferredAccount(preferenceKey(request.run.target, request.run.report.site), accountId)
        val decision = Decision.Proceed(picked.match.account, picked.pins, "you chose ${picked.match.account.identity} for this network")
        job = scope.launch {
            try { connect(request.run.copy(decision = decision), decision) } finally { onStep(request.run.target, null) }
        }
    }

    fun dismissChoice() {
        choice = null
    }

    private suspend fun connect(run: EnterpriseRun, decision: Decision.Proceed) {
        val site: EnterpriseSite = run.report.site ?: return onResult(run)
        val account = accountStore.load(decision.account.id)
            ?: return onResult(run.copy(decision = Decision.Block("the saved password for ${decision.account.identity} can't be read any more; enter it again in Settings", hostile = false)))
        val outcome = try {
            onboarding.connect(run.target, site, account, decision.pins) { onStep(run.target, it) }
        } catch (e: CredentialsRefused) {
            return onResult(run.copy(account = decision.account, refused = e.message))
        }
        refreshInstalled()
        onResult(run.copy(account = decision.account, connect = outcome))
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    // --- account management (Settings) ---

    fun addAccount(identity: String, password: String) {
        accountStore.save(identity, password)
        accounts = accountStore.accounts()
    }

    fun removeAccount(id: String) {
        accountStore.remove(id)
        accounts = accountStore.accounts()
    }

    /** Removes the pinned profile GECKO installed for [ssid] (Android then forgets the network). */
    fun forgetNetwork(ssid: String) {
        installedSsids = installedSsids.filterNot { it.equals(ssid, ignoreCase = true) }.toSet()
        scope.launch {
            installer.remove(ssid)
            refreshInstalled()
        }
    }

    /** A choice is remembered per network and federation: the same SSID in another federation is another network. */
    private fun preferenceKey(target: EnterpriseTarget, site: EnterpriseSite?) = "${target.ssid}|${site?.federation.orEmpty()}"
}

/** Whether [run] ended with the device connected through the pinned profile. */
val EnterpriseRun.connected: Boolean get() = connect?.connected == true

val EnterpriseRun.installFailure: String? get() = (connect?.install as? InstallOutcome.Failed)?.reason
