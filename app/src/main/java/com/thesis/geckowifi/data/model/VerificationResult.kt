package com.thesis.geckowifi.data.model

data class VerificationResult(
    val state: VerificationState,
    val host: String,
    val matchedIdentifier: String? = null,
    val matchedCertificateId: String? = null,
    val reason: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * The identifier (domain, or auth-server-name+fingerprint) that was
     * actually registered, and what was actually presented - populated only
     * on [VerificationState.CONFLICT], only where the underlying value is
     * already known to the code producing this result. Purely descriptive:
     * never used to derive [state] itself.
     */
    val registeredIdentifier: String? = null,
    val presentedIdentifier: String? = null,
    /**
     * Query/response metadata from the [com.thesis.geckowifi.data.remote.GeckoResponse.Success]
     * this result was computed from - attached by [com.thesis.geckowifi.verification.VerificationEngine]
     * after the trust decision was already made, purely for detail-view display
     * (Check Detail / Technical Details). Null when there was no successful
     * response to attach (e.g. [VerificationState.UNREACHABLE]).
     */
    val certificateCount: Int? = null,
    val unparsedCount: Int? = null,
    val usedPreferredNetwork: Boolean? = null
)