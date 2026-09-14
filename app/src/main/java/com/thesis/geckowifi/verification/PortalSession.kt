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

    private fun evaluate(
        host: String,
        candidates: List<GeoCertificate>, // full list of certificates returned by query
        presentedSpkiHash: String?
    ): VerificationResult {

        // "search across all returned certificates for one that claims this exact domain."
        val primary = candidates.firstOrNull { cert ->
            // cert.domainEntry(host) checks portal.domain list of certificate for exact match
            cert.domainEntry(host)?.let {
                cert.portal?.pinnedSPKIHashes.orEmpty().isEmpty() ||
                        presentedSpkiHash != null &&
                        cert.portal?.pinnedSPKIHashes.orEmpty().contains(presentedSpkiHash)
            } == true
        }
        if (primary != null) {
            anchor = primary
            return VerificationResult(
                VerificationState.VERIFIED, host, host, primary.certificateId,
                "primary domain matched"
            )
        }

        anchor?.let { current ->
            val delegate = current.domainEntry(host)
            if (delegate != null) {
                val keyOk = current.portal?.pinnedSPKIHashes.orEmpty().isEmpty() ||
                        presentedSpkiHash != null &&
                        current.portal?.pinnedSPKIHashes.orEmpty().contains(presentedSpkiHash)
                return if (keyOk) {
                    VerificationResult(
                        VerificationState.VERIFIED, host, host, current.certificateId,
                        "approved delegate of anchor"
                    )
                } else {
                    VerificationResult(
                        VerificationState.CONFLICT, host, host, current.certificateId,
                        "delegate domain presented unexpected key"
                    )
                }
            }
        }

        val nameKnown = candidates.any { it.domainEntry(host) != null }
        return when {
            nameKnown -> VerificationResult(
                VerificationState.CONFLICT, host, host, null,
                "registered domain presented unexpected key"
            )
            anchor != null -> VerificationResult(
                VerificationState.CONFLICT, host, null, anchor?.certificateId,
                "off-anchor domain appeared mid-session"
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