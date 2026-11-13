package com.thesis.geckowifi.eduroam

import android.net.Network
import android.os.Build
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.AdvertisedSecurity
import com.thesis.geckowifi.network.Association
import com.thesis.geckowifi.network.AssociationSource
import com.thesis.geckowifi.network.RsnProfile
import com.thesis.geckowifi.network.admissibleModes
import com.thesis.geckowifi.network.negotiatedFits
import com.thesis.geckowifi.verification.VerificationEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.PublicKey
import java.time.Instant

/** The steps of an enterprise connection, as the user sees them. */
enum class EnterpriseStep {
    /** Onboarding session with the public GECKO identity and the site query over it. */
    CHECK_NETWORK,
    /** Login-server details for the saved accounts: registration and realm records. */
    CONFIRM_SERVER,
    /** Pinned profile installed, waiting for Android to join with it. */
    CONNECT
}

/** Device location for the site query. */
data class QueryArea(val lat: Double, val lng: Double, val radiusMeters: Int)

/** An access point of the target SSID, from the scan. */
data class EnterpriseTarget(
    val ssid: String,
    val bssid: String?,
    /** What the access point claims in its beacon; decides the 802.1X type to request ([chooseEnterpriseSecurity]). */
    val profile: RsnProfile
) {
    val advertised: AdvertisedSecurity get() = AdvertisedSecurity(profile.admissibleModes(), profile.mfpr)
}

/** What the onboarding session found out (phase 1); no secret was involved. */
data class OnboardingReport(
    /** Why no onboarding session came up; null if it did. */
    val onboardingFailure: String?,
    val siteResult: VerificationResult?,
    /** Null if the site isn't registered as an enterprise network (or couldn't be checked). */
    val site: EnterpriseSite?,
    val checks: List<PinCheck>,
    /** Milliseconds since the start at which each step began. */
    val timings: List<Pair<EnterpriseStep, Long>> = emptyList(),
    /** Onboarding session, or a saved registration (D21). */
    val source: DataSource = DataSource.Onboarding,
    /** Signed realm records fetched over the session, (federation, realm) to bytes, for saving. */
    val fetchedRecords: Map<Pair<String, String>, ByteArray> = emptyMap()
)

data class ConnectOutcome(
    val install: InstallOutcome,
    val connected: Boolean?,
    /**
     * The access point and mode actually negotiated, read after connecting. The pinned
     * profile is a suggestion for the whole SSID, so Android picks the access point itself:
     * it need not be the target. Null if not connected or unknown.
     */
    val joined: Association? = null,
    /** Set when the negotiated mode was weaker than registered; the profile was removed again. */
    val downgrade: String? = null,
    /** Set when the platform reported that signing in with the pinned profile failed (see [ConnectWait.Refused]). */
    val refusedSignIn: String? = null
)

/**
 * Design A for any enterprise network (eduroam-brief.md, section 2;
 * docs/eduroam-decisions.md, D19), in two phases:
 *
 * 1. [check]: onboarding session with the public GECKO identity → site query
 *    → login-server details for every saved account that fits (local: from
 *    the registration; visitor: realm record of the site's federation, fetched
 *    in parallel) → session released. No secret involved.
 * 2. [connect]: for the account [decide] picked (or the user chose), the pinned
 *    profile via [EnterpriseProfileBuilder], then wait for Android to join.
 *
 * The platform steps are behind [transport] and [installer], so a root
 * implementation can replace them (D1).
 */
class EnterpriseOnboarding(
    private val transport: OnboardingTransport,
    /** An engine whose map client is bound to the onboarding network, without fallback. */
    private val engineFor: (Network) -> VerificationEngine,
    private val realmClientFor: (Network) -> RealmRecordClient,
    private val installer: EnterpriseProfileInstaller,
    /** Reads what was negotiated after connecting (root or not, like [transport] and [installer]). */
    private val association: AssociationSource,
    private val realmKey: () -> PublicKey = { PinnedRealmKey.publicKey },
    private val clock: () -> Instant = Instant::now,
    // Lab (2026-11-13, Router A's hostapd log): association to EAP success takes under 1 s;
    // against an impostor Android gives up after 3-4 attempts within ~5 s and then sends
    // nothing more. The rest is Android: scan, the approval dialog (onboarding, non-root),
    // DHCP, and for the suggestion its own join schedule. 30 s leaves room for a user
    // tapping the dialog; a refused sign-in on the saved path ends early (ConnectWait.Refused).
    private val onboardingTimeoutMs: Int = 20_000,
    private val connectTimeoutMs: Long = 20_000,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) {
    /** Phase 1 from a saved registration, without onboarding (D21); see [checkSaved]. */
    fun checkSaved(
        target: EnterpriseTarget,
        certificates: List<com.thesis.geckowifi.data.model.GeoCertificate>,
        accounts: List<AccountInfo>,
        savedRecord: (federation: String, realm: String) -> ByteArray?,
        source: DataSource.Saved
    ): OnboardingReport = checkSaved(target, certificates, accounts, savedRecord, source, realmKey(), clock())

    suspend fun check(
        target: EnterpriseTarget,
        area: QueryArea,
        accounts: List<AccountInfo>,
        onStep: (EnterpriseStep) -> Unit = {}
    ): OnboardingReport {
        val started = System.currentTimeMillis()
        val timings = mutableListOf<Pair<EnterpriseStep, Long>>()
        val step = { s: EnterpriseStep -> timings += s to (System.currentTimeMillis() - started); onStep(s) }

        // Registration not known yet: the onboarding session sends nothing secret, so the beacon decides.
        val security = when (val choice = chooseEnterpriseSecurity(null, target.profile, sdkInt)) {
            is SecurityChoice.Refused -> return OnboardingReport(choice.reason, null, null, emptyList())
            is SecurityChoice.Use -> choice.specifier
        }
        step(EnterpriseStep.CHECK_NETWORK)
        val session = transport.withOnboardingNetwork(OnboardingTarget(target.ssid, target.bssid, security), onboardingTimeoutMs) { network ->
            val siteCheck = engineFor(network).checkAdvertisedSite(
                "enterprise:${target.bssid ?: target.ssid}", target.ssid, target.advertised,
                area.lat, area.lng, null, area.radiusMeters
            )
            val site = EnterpriseSite.from(target.ssid, siteCheck.certificates)
            step(EnterpriseStep.CONFIRM_SERVER)
            val matches = site?.let { matchAccounts(it, accounts) }.orEmpty()
            val client = realmClientFor(network)
            val fetched = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, ByteArray>()
            val checks = coroutineScope {
                matches.map { match -> async { pinsFor(match, site!!, client, fetched) } }.awaitAll()
            }
            OnboardingReport(null, siteCheck.result, site, checks, fetchedRecords = fetched.toMap())
        }
        return when (session) {
            is OnboardingOutcome.Failed -> OnboardingReport(session.reason, null, null, emptyList(), timings)
            is OnboardingOutcome.Done -> session.value.copy(timings = timings.toList())
        }
    }

    private suspend fun pinsFor(
        match: AccountMatch,
        site: EnterpriseSite,
        client: RealmRecordClient,
        fetched: MutableMap<Pair<String, String>, ByteArray>
    ): PinCheck {
        val realm = match.account.realm!!
        return when (match) {
            is AccountMatch.Local -> when (val r = site.localPins(realm)) {
                is PinResult.Ok -> PinCheck.Pinned(match, r.pins)
                // Came with the map proof: incomplete, not forged.
                is PinResult.Rejected -> PinCheck.Rejected(match, r.reason, hostile = false)
            }
            is AccountMatch.Visitor -> when (val f = client.fetch(match.federation, realm)) {
                is RealmFetch.Fetched -> when (val r = AuthenticatedEapPins.fromSignedRealmRecord(f.bytes, match.federation, realm, realmKey(), clock())) {
                    is PinResult.Ok -> PinCheck.Pinned(match, r.pins).also { fetched[match.federation to realm] = f.bytes }
                    is PinResult.Rejected -> PinCheck.Rejected(match, r.reason, hostile = true)
                }
                RealmFetch.NotFound -> PinCheck.Missing(match, "no record for $realm in ${match.federation}", MissingCause.NO_RECORD)
                is RealmFetch.Unreachable -> PinCheck.Missing(match, "record server not reachable over this network: ${f.cause}", MissingCause.UNREACHABLE)
            }
        }
    }

    /** Phase 2. Throws [CredentialsRefused] if [pins] don't belong to [account] or [site]'s federation. */
    suspend fun connect(
        target: EnterpriseTarget,
        site: EnterpriseSite,
        account: EnterpriseAccount,
        pins: AuthenticatedEapPins,
        onStep: (EnterpriseStep) -> Unit = {}
    ): ConnectOutcome {
        onStep(EnterpriseStep.CONNECT)
        val security = when (val choice = chooseEnterpriseSecurity(site.authModes.ifEmpty { null }, target.profile, sdkInt)) {
            is SecurityChoice.Refused -> return ConnectOutcome(InstallOutcome.Failed(choice.reason), null)
            is SecurityChoice.Use -> choice.suggestions
        }
        val install = EnterpriseProfileBuilder.install(pins, account, TargetNetwork(target.ssid, security, site.federation), installer)
        if (install !is InstallOutcome.Installed) return ConnectOutcome(install, null)
        when (val wait = installer.awaitConnected(target.ssid, connectTimeoutMs)) {
            is ConnectWait.Refused -> return ConnectOutcome(install, false, refusedSignIn = wait.reason)
            ConnectWait.TimedOut -> return ConnectOutcome(install, false)
            ConnectWait.Connected -> Unit
        }
        // The beacon was a claim; what was negotiated is the authenticated answer.
        val joined = association.current()
        val mode = joined?.mode
        if (mode != null && !negotiatedFits(site.authModes, mode)) {
            installer.remove(target.ssid)
            val registered = site.authModes.sorted().joinToString { it.wire }
            return ConnectOutcome(install, false, joined,
                downgrade = "negotiated ${mode.wire} with ${joined.bssid ?: "the access point"}, but $registered is registered; GECKO removed the profile")
        }
        return ConnectOutcome(install, true, joined)
    }
}
