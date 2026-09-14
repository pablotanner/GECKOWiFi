package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState

/**
 * Enterprise (802.1X/eduroam-style) equivalent of [PortalSession]'s domain
 * matching - compares an observed RADIUS/AAA auth server name + CA
 * fingerprint against what GECKO has registered for this location.
 *
 * Deliberately a separate, stateless function rather than an addition to
 * [PortalSession]: there is no "browsing session" to anchor across for an
 * EAP handshake (you're either connected with a matching cert or you're
 * not - no sequence of domains within one session the way a captive portal
 * login can redirect through several), so PortalSession's anchor/delegate
 * semantics don't map onto this case. PortalSession's own comparison logic
 * must not be touched per project rules, so this lives entirely on its own.
 *
 * PROTOTYPE NOTE: [observedAuthServerName]/[observedCaFingerprint] stand in
 * for what [com.thesis.geckowifi.capability.SupplicantCertSource] would
 * report from a real EAP-TLS handshake (root-only, reads wpa_supplicant's
 * `CTRL-EVENT-EAP-PEER-CERT`). No real handshake is involved when this is
 * called from the prototype's fake network entries - see
 * docs/prototype-decisions.md.
 */
fun evaluateEnterpriseNetwork(
    observedAuthServerName: String,
    observedCaFingerprint: String,
    candidates: List<GeoCertificate>
): VerificationResult {
    val matching = candidates.firstOrNull { cert ->
        cert.wifi.authServerNames.any { it.equals(observedAuthServerName, ignoreCase = true) }
    }

    return when {
        matching == null && candidates.isEmpty() ->
            VerificationResult(
                VerificationState.UNVERIFIED,
                observedAuthServerName,
                reason = "no certificate registered at this location"
            )
        matching == null ->
            VerificationResult(
                VerificationState.UNRECOGNIZED,
                observedAuthServerName,
                reason = "certificates exist here but none names this auth server"
            )
        matching.wifi.trustedCAFingerprints.isEmpty() ||
            matching.wifi.trustedCAFingerprints.contains(observedCaFingerprint) ->
            VerificationResult(
                VerificationState.VERIFIED,
                observedAuthServerName,
                matchedIdentifier = observedAuthServerName,
                matchedCertificateId = matching.certificateId,
                reason = "auth server name matched"
            )
        else ->
            VerificationResult(
                VerificationState.CONFLICT,
                observedAuthServerName,
                matchedCertificateId = matching.certificateId,
                reason = "registered auth server presented an unexpected CA fingerprint"
            )
    }
}
