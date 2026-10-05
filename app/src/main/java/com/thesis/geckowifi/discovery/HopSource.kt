package com.thesis.geckowifi.discovery

import android.net.Network

/**
 * One request/response observed on the way to (and through) a captive portal.
 * The unit everything downstream works on: [com.thesis.geckowifi.verification.VerificationEngine.verifyPortalHops]
 * judges hops, the check log records them.
 *
 * Deliberately source-agnostic: the redirect probe fills every field from its
 * own requests; a future traffic observer (root, pcap/SNI) would only know
 * [host]/[scheme] from the wire and get [presentedSpkiHash] by probing the
 * host itself - see docs/portal-detection.md.
 */
data class ObservedHop(
    val index: Int,
    val url: String,
    val scheme: String,
    val host: String,
    val port: Int,
    /** HTTP status; null if the request failed (see [error]). */
    val statusCode: Int? = null,
    /** Where this response sent the client next (Location header or `<meta refresh>`), if anywhere. */
    val next: String? = null,
    /** SPKI SHA-256 (base64) of the certificate presented on this hop; HTTPS only. */
    val presentedSpkiHash: String? = null,
    val certificateSubject: String? = null,
    val source: HopSource.Kind,
    val timestampMillis: Long,
    val error: String? = null
) {
    val isHttps: Boolean get() = scheme.equals("https", ignoreCase = true)
}

/**
 * Where hops come from. Verification ([com.thesis.geckowifi.verification.PortalSession])
 * doesn't care: every source produces [ObservedHop]s that are judged the same
 * way, so adding a source doesn't touch the trust logic.
 */
interface HopSource {

    enum class Kind {
        /** The app requests a plain-HTTP probe URL and follows the redirects itself (no root). */
        REDIRECT_PROBE,
        /** Fallback: the app connects to the domain registered for the SSID (no portal observed). */
        REGISTERED_DOMAIN_PROBE,
        /** Future (root): hosts seen in the device's real traffic, keys probed separately. */
        TRAFFIC_OBSERVATION
    }

    val kind: Kind

    /** Runs discovery over [network] (the WiFi being checked - never the default network). */
    suspend fun discover(network: Network): Discovery
}

/** Result of one discovery run: every hop seen, plus how the run ended. */
data class Discovery(val outcome: Outcome, val hops: List<ObservedHop>) {

    sealed class Outcome {
        /** The probe got its expected "no portal" answer (HTTP 204): open network or already logged in. */
        data object NoPortal : Outcome()
        /** Redirects ended on a page (the portal's login page): the last hop. */
        data object PortalReached : Outcome()
        /** Network error, timeout or redirect loop - nothing to judge reliably. */
        data class Failed(val reason: String) : Outcome()
    }
}
