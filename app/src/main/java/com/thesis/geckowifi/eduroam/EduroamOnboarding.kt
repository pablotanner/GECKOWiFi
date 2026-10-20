package com.thesis.geckowifi.eduroam

import android.net.Network
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.verification.SiteCheck
import com.thesis.geckowifi.verification.VerificationEngine
import java.security.PublicKey
import java.time.Instant

/** The steps of [EduroamOnboarding.run], in order. */
enum class EduroamStep { ONBOARDING, SITE, REALM, DECISION, INSTALL, CONNECT }

/** What the realm side of the check produced. */
sealed interface RealmCheck {
    data class Pinned(val pins: AuthenticatedEapPins) : RealmCheck

    /** A record arrived but must not be used (bad signature, expired, wrong realm, ...): evidence of tampering or misconfiguration. */
    data class Rejected(val reason: String) : RealmCheck

    /** Nothing usable arrived (endpoint unreachable, withheld, no record). */
    data class Missing(val reason: String) : RealmCheck
}

sealed interface Decision {
    data class Proceed(val pins: AuthenticatedEapPins, val why: String) : Decision

    /** [hostile]: something contradicted GECKO (a warning), rather than GECKO just not knowing. */
    data class Block(val reason: String, val hostile: Boolean) : Decision
}

/**
 * The gate of the eduroam path (docs/eduroam-decisions.md, D2): credentials go
 * out only with pins from an authenticated source for the user's realm, and
 * never to a network that contradicts its registration. The site's other
 * states don't decide; with authenticated pins the supplicant protects the
 * password on any access point. Prefers the site's own registration (local
 * user, map proof) over a realm record.
 */
fun decide(site: VerificationResult?, local: PinResult?, realm: RealmCheck?): Decision {
    if (site?.state == VerificationState.CONFLICT) {
        return Decision.Block("this network contradicts its GECKO registration: ${site.reason}", hostile = true)
    }
    if (local is PinResult.Ok) {
        val id = (local.pins.source as? PinSource.SiteCertificate)?.certificateId
        return Decision.Proceed(local.pins, "the site's registration ($id) lists the server for realm ${local.pins.realm}")
    }
    return when (realm) {
        is RealmCheck.Pinned -> Decision.Proceed(realm.pins, "signed realm record for ${realm.pins.realm} verified")
        is RealmCheck.Rejected -> Decision.Block("the realm record was rejected: ${realm.reason}", hostile = true)
        is RealmCheck.Missing -> Decision.Block("no verified server identity for your realm (${realm.reason})", hostile = false)
        null -> Decision.Block("no verified server identity for your realm", hostile = false)
    }
}

/** Device location for the site query. */
data class QueryArea(val lat: Double, val lng: Double, val radiusMeters: Int)

/** An access point of the target SSID, from the scan. */
data class EduroamTarget(val ssid: String, val bssid: String?, val authModes: Set<WiFiAuthMode>) {
    val wpa3: Boolean get() = WiFiAuthMode.WPA2_ENTERPRISE !in authModes &&
        (WiFiAuthMode.WPA3_ENTERPRISE in authModes || WiFiAuthMode.WPA3_ENTERPRISE_192 in authModes)
}

data class EduroamOutcome(
    /** Why no onboarding session came up; null if it did. */
    val onboardingFailure: String?,
    val site: VerificationResult?,
    val local: PinResult?,
    val realm: RealmCheck?,
    val decision: Decision,
    val install: InstallOutcome? = null,
    /** Null if nothing was installed. */
    val connected: Boolean? = null,
    /** Milliseconds since the start at which each step began. */
    val timings: List<Pair<EduroamStep, Long>> = emptyList()
)

/**
 * Design A end to end (eduroam-brief.md, section 2): onboarding session with
 * the GECKO identity → site query and realm-record fetch over it → session
 * released → [decide] → pinned profile via [EnterpriseProfileBuilder] → wait
 * for the device to connect. The platform steps are behind [transport] and
 * [installer], so a root implementation can replace them (D1).
 */
class EduroamOnboarding(
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
    private class Fetched(val site: SiteCheck, val realm: RealmFetch)

    suspend fun run(
        target: EduroamTarget,
        area: QueryArea,
        credentials: EduroamCredentials,
        onStep: (EduroamStep) -> Unit = {}
    ): EduroamOutcome {
        val started = System.currentTimeMillis()
        val timings = mutableListOf<Pair<EduroamStep, Long>>()
        val step = { s: EduroamStep -> timings += s to (System.currentTimeMillis() - started); onStep(s) }

        if (target.ssid !in OnboardingIdentity.LAB_SSIDS) return EduroamOutcome(null, null, null, null,
            Decision.Block("this prototype only onboards on the lab network (${OnboardingIdentity.LAB_SSIDS.joinToString()})", hostile = false))
        val realm = credentials.realm ?: return EduroamOutcome(null, null, null, null,
            Decision.Block("your identity ${credentials.identity} has no realm (user@realm)", hostile = false))

        step(EduroamStep.ONBOARDING)
        val session = transport.withOnboardingNetwork(OnboardingTarget(target.ssid, target.bssid, target.wpa3), onboardingTimeoutMs) { network ->
            step(EduroamStep.SITE)
            val site = engineFor(network).checkAdvertisedSite(
                "eduroam:${target.bssid ?: target.ssid}", target.ssid, target.authModes,
                area.lat, area.lng, null, area.radiusMeters
            )
            step(EduroamStep.REALM)
            Fetched(site, realmClientFor(network).fetch(realm))
        }
        val fetched = when (session) {
            is OnboardingOutcome.Failed -> return EduroamOutcome(session.reason, null, null, null,
                Decision.Block("no onboarding session: ${session.reason}", hostile = false), timings = timings)
            is OnboardingOutcome.Done -> session.value
        }

        step(EduroamStep.DECISION)
        val local = fetched.site.certificates.map { AuthenticatedEapPins.fromSiteCertificate(it, realm) }
            .let { results -> results.firstOrNull { it is PinResult.Ok } ?: results.firstOrNull() }
        val realmCheck = when (val f = fetched.realm) {
            is RealmFetch.Fetched -> when (val r = AuthenticatedEapPins.fromSignedRealmRecord(f.bytes, realm, realmKey(), clock())) {
                is PinResult.Ok -> RealmCheck.Pinned(r.pins)
                is PinResult.Rejected -> RealmCheck.Rejected(r.reason)
            }
            RealmFetch.NotFound -> RealmCheck.Missing("the realm server has no record for $realm")
            is RealmFetch.Unreachable -> RealmCheck.Missing("realm server not reachable over this network: ${f.cause}")
        }
        val decision = decide(fetched.site.result, local, realmCheck)
        val base = EduroamOutcome(null, fetched.site.result, local, realmCheck, decision, timings = timings)
        if (decision !is Decision.Proceed) return base

        step(EduroamStep.INSTALL)
        val install = try {
            EnterpriseProfileBuilder.install(decision.pins, credentials, TargetNetwork(target.ssid, target.wpa3), installer)
        } catch (e: CredentialsRefused) {
            return base.copy(decision = Decision.Block(e.message ?: "credentials refused", hostile = false))
        }
        if (install !is InstallOutcome.Installed) return base.copy(install = install)

        step(EduroamStep.CONNECT)
        val connected = installer.awaitConnected(target.ssid, connectTimeoutMs)
        return base.copy(install = install, connected = connected, timings = timings.toList())
    }
}
