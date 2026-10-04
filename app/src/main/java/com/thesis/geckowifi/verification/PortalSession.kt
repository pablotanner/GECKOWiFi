package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.*

class PortalSession(val networkKey: String) {

    private var anchor: GeoCertificate? = null
    private val hops = mutableListOf<VerificationResult>()

    val anchorCertificateId: String? get() = anchor?.certificateId
    fun history(): List<VerificationResult> = hops.toList()

    fun reset() {
        anchor = null
        hops.clear()
    }

    fun observe(
        host: String,
        candidates: List<GeoCertificate>,
        presentedSpkiHash: String?
    ): VerificationResult = record(evaluate(host, candidates, presentedSpkiHash))

    /**
     * Schema-v2 rules (per-domain roles and pins):
     * - Only a PRIMARY domain whose own pins accept the presented key can
     *   start (or move) the session anchor.
     * - Any other domain of the anchored certificate (delegate, api, or its
     *   primary again) is checked against that domain's own pins - a
     *   delegate's key never vouches for the primary or vice versa.
     * - A domain with no pins never verifies (pins are the trust anchor; the
     *   app does no Web PKI validation). The server rejects such
     *   certificates on insert; this fails closed if one slips through.
     */
    private fun evaluate(
        host: String,
        candidates: List<GeoCertificate>, // full list of certificates returned by query
        presentedSpkiHash: String?
    ): VerificationResult {

        val primary = candidates.firstOrNull { cert ->
            cert.domainEntry(host)?.let { it.role == PortalDomainRole.PRIMARY && it.accepts(presentedSpkiHash) } == true
        }
        if (primary != null) {
            anchor = primary
            return VerificationResult(
                VerificationState.VERIFIED, host, host, primary.certificateId,
                "primary domain matched"
            )
        }

        anchor?.let { current ->
            val entry = current.domainEntry(host)
            if (entry != null) {
                return if (entry.accepts(presentedSpkiHash)) {
                    VerificationResult(
                        VerificationState.VERIFIED, host, host, current.certificateId,
                        "${entry.role.name.lowercase()} domain of anchor matched"
                    )
                } else {
                    VerificationResult(
                        VerificationState.CONFLICT, host, host, current.certificateId,
                        "${entry.role.name.lowercase()} domain of anchor presented unexpected key",
                        registeredIdentifier = entry.pinnedSPKIHashes.firstOrNull(),
                        presentedIdentifier = presentedSpkiHash
                    )
                }
            }
        }

        val registered = candidates.firstNotNullOfOrNull { cert -> cert.domainEntry(host)?.let { cert to it } }
        if (registered != null) {
            val (cert, entry) = registered
            // Genuine key, but a delegate/api domain is not a valid way into a portal
            // session: not evidence of an attack, not a verified entry point either.
            return if (entry.role != PortalDomainRole.PRIMARY && entry.accepts(presentedSpkiHash)) {
                VerificationResult(
                    VerificationState.UNRECOGNIZED, host, host, cert.certificateId,
                    "${entry.role.name.lowercase()} domain seen before its portal's primary domain"
                )
            } else {
                VerificationResult(
                    VerificationState.CONFLICT, host, host, cert.certificateId,
                    "registered domain presented unexpected key",
                    registeredIdentifier = entry.pinnedSPKIHashes.firstOrNull(),
                    presentedIdentifier = presentedSpkiHash
                )
            }
        }

        return when {
            anchor != null -> VerificationResult(
                VerificationState.CONFLICT, host, null, anchor?.certificateId,
                "off-anchor domain appeared mid-session",
                registeredIdentifier = anchor?.primaryDomains()?.firstOrNull()?.name,
                presentedIdentifier = host
            )
            candidates.isNotEmpty() -> VerificationResult(
                VerificationState.UNRECOGNIZED, host, null, null,
                "certificates exist here but none names this domain"
            )
            else -> VerificationResult(
                VerificationState.UNVERIFIED, host, null, null,
                "no certificate registered"
            )
        }
    }

    private fun record(result: VerificationResult) = result.also { hops.add(it) }
}