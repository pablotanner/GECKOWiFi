package com.thesis.geckowifi.eduroam

import com.thesis.geckowifi.data.local.SavedLookup
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.verification.AdvertisedMatch
import com.thesis.geckowifi.verification.coversLocation
import com.thesis.geckowifi.verification.matchAdvertised
import java.security.PublicKey
import java.time.Duration
import java.time.Instant

/**
 * Where the registration and pins of an enterprise check came from
 * (docs/eduroam-decisions.md, D21): GECKO's onboarding session at the network,
 * or a verified answer the app saved earlier over another connection.
 */
sealed interface DataSource {
    data object Onboarding : DataSource

    /** [fetchedAt] epoch millis, [via] the connection the map answer came over. */
    data class Saved(val fetchedAt: Long, val via: String) : DataSource
}

/**
 * How old a saved registration may be and still replace onboarding (D21).
 * Against an active attacker a saved answer is hardly weaker than a fresh one
 * (map-head freshness is unsolved either way); the limit is for honest changes,
 * such as an operator rotating its certificate or a revoked registration.
 */
val SAVED_REGISTRATION_MAX_AGE: Duration = Duration.ofHours(24)

/**
 * The saved registrations that let GECKO skip onboarding for [ssid] here and
 * now, or null: the saved answer must not be older than [maxAge], and among
 * its certificates for [ssid] that are valid now and whose area reaches the
 * device's current position, at least one registers an enterprise network.
 * Returns every applicable certificate for [ssid] (also non-enterprise ones),
 * so the security-type comparison sees the whole registration.
 */
fun usableSavedRegistration(
    saved: SavedLookup?,
    ssid: String,
    lat: Double,
    lng: Double,
    radiusMeters: Int,
    now: Instant,
    maxAge: Duration = SAVED_REGISTRATION_MAX_AGE
): List<GeoCertificate>? {
    if (saved == null) return null
    if (Duration.between(Instant.ofEpochMilli(saved.fetchedAt), now) > maxAge) return null
    val applicable = saved.certificates.filter {
        it.wifi.ssid.equals(ssid, ignoreCase = true) && it.isValidAt(now) && it.isEvaluable() &&
            it.coversLocation(lat, lng, radiusMeters)
    }
    return applicable.takeIf { certs -> certs.any { it.wifi.enterprise != null } }
}

/**
 * Phase 1 without onboarding (D21): the same matching as [EnterpriseOnboarding.check],
 * on a saved registration and saved realm records. [certificates] come from
 * [usableSavedRegistration]; [savedRecord] returns the signed bytes of a saved
 * realm record (re-verified here, with its federation and expiry checked against
 * [now]). A visitor without a saved record ends up [PinCheck.Missing], which the
 * caller can answer with onboarding.
 */
fun checkSaved(
    target: EnterpriseTarget,
    certificates: List<GeoCertificate>,
    accounts: List<AccountInfo>,
    savedRecord: (federation: String, realm: String) -> ByteArray?,
    source: DataSource.Saved,
    realmKey: PublicKey = PinnedRealmKey.publicKey,
    now: Instant = Instant.now()
): OnboardingReport {
    val match = matchAdvertised(target.ssid, target.authModes, certificates)
    val siteResult = if (match is AdvertisedMatch.Mismatch) VerificationResult(
        VerificationState.CONFLICT, target.ssid,
        reason = "network advertises ${match.advertised.joinToString { it.wire }}, but is registered as ${match.registered.joinToString()} (saved registration)"
    ) else VerificationResult(
        VerificationState.REGISTERED, target.ssid,
        matchedCertificateId = certificates.firstOrNull { it.wifi.enterprise != null }?.certificateId,
        reason = "registered here (saved registration)"
    )
    val site = EnterpriseSite.from(target.ssid, certificates)
    val checks = site?.let { matchAccounts(it, accounts) }.orEmpty().map { m ->
        val realm = m.account.realm!!
        when (m) {
            is AccountMatch.Local -> when (val r = site!!.localPins(realm)) {
                is PinResult.Ok -> PinCheck.Pinned(m, r.pins)
                is PinResult.Rejected -> PinCheck.Rejected(m, r.reason, hostile = false)
            }
            is AccountMatch.Visitor -> when (val bytes = savedRecord(m.federation, realm)) {
                null -> PinCheck.Missing(m, "no saved record for $realm in ${m.federation}")
                else -> when (val r = AuthenticatedEapPins.fromSignedRealmRecord(bytes, m.federation, realm, realmKey, now)) {
                    is PinResult.Ok -> PinCheck.Pinned(m, r.pins)
                    is PinResult.Rejected -> PinCheck.Rejected(m, r.reason, hostile = true)
                }
            }
        }
    }
    return OnboardingReport(null, siteResult, site, checks, source = source)
}
