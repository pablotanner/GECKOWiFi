package com.thesis.geckowifi.eduroam

import android.net.Network
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.WiFiAuthMode
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
data class EnterpriseTarget(val ssid: String, val bssid: String?, val authModes: Set<WiFiAuthMode>) {
    val wpa3: Boolean get() = WiFiAuthMode.WPA2_ENTERPRISE !in authModes &&
        (WiFiAuthMode.WPA3_ENTERPRISE in authModes || WiFiAuthMode.WPA3_ENTERPRISE_192 in authModes)
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
    val timings: List<Pair<EnterpriseStep, Long>> = emptyList()
)

data class ConnectOutcome(val install: InstallOutcome, val connected: Boolean?)

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
    private val realmKey: () -> PublicKey = { PinnedRealmKey.publicKey },
    private val clock: () -> Instant = Instant::now,
    private val onboardingTimeoutMs: Int = 60_000,
    private val connectTimeoutMs: Long = 60_000
) {
    /** Whether this prototype may onboard on [ssid] at all (lab guard, D15). */
    fun supports(ssid: String): Boolean = ssid in OnboardingIdentity.LAB_SSIDS

    suspend fun check(
        target: EnterpriseTarget,
        area: QueryArea,
        accounts: List<AccountInfo>,
        onStep: (EnterpriseStep) -> Unit = {}
    ): OnboardingReport {
        val started = System.currentTimeMillis()
        val timings = mutableListOf<Pair<EnterpriseStep, Long>>()
        val step = { s: EnterpriseStep -> timings += s to (System.currentTimeMillis() - started); onStep(s) }

        if (!supports(target.ssid)) return OnboardingReport(
            "this prototype only onboards on the lab network (${OnboardingIdentity.LAB_SSIDS.joinToString()})", null, null, emptyList())

        step(EnterpriseStep.CHECK_NETWORK)
        val session = transport.withOnboardingNetwork(OnboardingTarget(target.ssid, target.bssid, target.wpa3), onboardingTimeoutMs) { network ->
            val siteCheck = engineFor(network).checkAdvertisedSite(
                "enterprise:${target.bssid ?: target.ssid}", target.ssid, target.authModes,
                area.lat, area.lng, null, area.radiusMeters
            )
            val site = EnterpriseSite.from(target.ssid, siteCheck.certificates)
            step(EnterpriseStep.CONFIRM_SERVER)
            val matches = site?.let { matchAccounts(it, accounts) }.orEmpty()
            val client = realmClientFor(network)
            val checks = coroutineScope {
                matches.map { match -> async { pinsFor(match, site!!, client) } }.awaitAll()
            }
            Triple(siteCheck.result, site, checks)
        }
        return when (session) {
            is OnboardingOutcome.Failed -> OnboardingReport(session.reason, null, null, emptyList(), timings)
            is OnboardingOutcome.Done -> session.value.let { (result, site, checks) -> OnboardingReport(null, result, site, checks, timings.toList()) }
        }
    }

    private suspend fun pinsFor(match: AccountMatch, site: EnterpriseSite, client: RealmRecordClient): PinCheck {
        val realm = match.account.realm!!
        return when (match) {
            is AccountMatch.Local -> when (val r = site.localPins(realm)) {
                is PinResult.Ok -> PinCheck.Pinned(match, r.pins)
                // Came with the map proof: incomplete, not forged.
                is PinResult.Rejected -> PinCheck.Rejected(match, r.reason, hostile = false)
            }
            is AccountMatch.Visitor -> when (val f = client.fetch(match.federation, realm)) {
                is RealmFetch.Fetched -> when (val r = AuthenticatedEapPins.fromSignedRealmRecord(f.bytes, match.federation, realm, realmKey(), clock())) {
                    is PinResult.Ok -> PinCheck.Pinned(match, r.pins)
                    is PinResult.Rejected -> PinCheck.Rejected(match, r.reason, hostile = true)
                }
                RealmFetch.NotFound -> PinCheck.Missing(match, "no record for $realm in ${match.federation}")
                is RealmFetch.Unreachable -> PinCheck.Missing(match, "record server not reachable over this network: ${f.cause}")
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
        val install = EnterpriseProfileBuilder.install(pins, account, TargetNetwork(target.ssid, target.wpa3, site.federation), installer)
        if (install !is InstallOutcome.Installed) return ConnectOutcome(install, null)
        return ConnectOutcome(install, installer.awaitConnected(target.ssid, connectTimeoutMs))
    }
}
