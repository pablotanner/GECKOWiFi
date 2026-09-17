package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.*
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.data.remote.GeckoResponse
import java.util.concurrent.ConcurrentHashMap

class VerificationEngine(
    /**
     * Mutable so a caller (e.g. a long-lived ViewModel) can rebind the
     * network/server connection between checks - e.g. to pick up a freshly
     * looked-up cellular [android.net.Network] - without losing this
     * engine's [sessions] state. Rebuilding a whole new `VerificationEngine`
     * per check (the previous approach) silently discarded every
     * [PortalSession], defeating its anchor/delegate bait-and-switch
     * detection across checks of the same network - see README.md.
     */
    var gecko: GeckoClient,
    private val probe: CertProbe,
    private val cache: DecisionCache,
    private val encoder: GeoQueryEncoder
) {
    private val sessions = ConcurrentHashMap<String, PortalSession>()

    fun sessionFor(networkKey: String): PortalSession =
        sessions.getOrPut(networkKey) { PortalSession(networkKey) }

    fun onNetworkChanged(networkKey: String) {
        sessions.remove(networkKey)
        cache.invalidateAll()
    }

    /**
     * Every GeoCertificate registered at this location, unfiltered by SSID -
     * for the Networks screen's "what does GECKO know about here" list, not
     * a trust decision. Returns an empty list on any failure (unreachable
     * server, proof failure) rather than throwing - the Networks screen
     * treats "couldn't tell" the same as "nothing registered" for display
     * purposes, same as everywhere else in this app that a query can fail.
     */
    suspend fun registeredHere(
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int
    ): List<GeoCertificate> {
        val bitStrings = encoder.encodeQuery(lat, lng, radiusMeters)
        val (minAlt, maxAlt) = encoder.altitudeBounds(altitude, radiusMeters)
        return when (val response = gecko.queryLocation(bitStrings, minAlt, maxAlt)) {
            is GeckoResponse.Success -> response.certificates
            is GeckoResponse.Unreachable, is GeckoResponse.ProofFailure -> emptyList()
        }
    }

    suspend fun verify(
        networkKey: String,
        host: String,
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int,
        ssid: String? = null
    ): VerificationResult {
        cache.get(networkKey, host)?.let { return it }
        val spki = probe.spkiHash(probe.fetchCertificate(host))
        return verifyPresentedDomain(networkKey, host, spki, lat, lng, altitude, radiusMeters, ssid)
    }

    /**
     * Same as [verify], but takes the presented SPKI hash directly instead
     * of fetching it via [CertProbe]. [verify] is a thin wrapper over this
     * for the normal (real TLS probe) path; this overload exists so a
     * caller that already has (or, for the prototype's fake network
     * entries, is standing in for) a presented identity can skip the real
     * network probe entirely - see docs/prototype-decisions.md.
     *
     * [ssid], when given, applies the SSID pre-filter described in
     * docs/app-design.md: candidates are narrowed to only those whose
     * `wifi.ssid` matches [ssid] *before* [PortalSession] ever sees them.
     * If nothing matches, this returns UNVERIFIED without calling
     * PortalSession at all - per the advisor's rule, an unregistered SSID
     * must never surface a warning, regardless of what else GECKO returned
     * for this location. [PortalSession]'s own comparison logic is
     * unchanged either way - this is a pure pre-filter layered in front of
     * it. Passing `ssid = null` (the default) skips filtering entirely,
     * preserving the original behavior for existing callers.
     */
    suspend fun verifyPresentedDomain(
        networkKey: String,
        host: String,
        presentedSpkiHash: String?,
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int,
        ssid: String? = null
    ): VerificationResult {
        cache.get(networkKey, host)?.let { return it }

        val bitStrings = encoder.encodeQuery(lat, lng, radiusMeters)
        val (minAlt, maxAlt) = encoder.altitudeBounds(altitude, radiusMeters)
        val response = gecko.queryLocation(bitStrings, minAlt, maxAlt)

        val result = when (response) {
            is GeckoResponse.Unreachable ->
                VerificationResult(VerificationState.UNREACHABLE, host, reason = response.cause)
            is GeckoResponse.ProofFailure ->
                VerificationResult(VerificationState.CONFLICT, host, reason = response.cause) // failed proof = hostile, not absent
            is GeckoResponse.Success -> {
                val candidates = filterBySsid(response.certificates, ssid) ?: return noMatchingSsid(host)
                sessionFor(networkKey).observe(host, candidates, presentedSpkiHash).withResponseMetadata(response)
            }
        }
        if (result.state != VerificationState.UNREACHABLE) cache.put(networkKey, host, result)
        return result
    }

    /**
     * Enterprise (802.1X/eduroam-style) equivalent of [verify] - same
     * location query, but compares against [evaluateEnterpriseNetwork]
     * instead of [PortalSession] (no browsing-session anchor concept
     * applies to an EAP handshake). See docs/prototype-decisions.md for what
     * [observedAuthServerName]/[observedCaFingerprint] stand in for today,
     * and docs/app-design.md for the [ssid] pre-filter (same semantics as
     * [verifyPresentedDomain]'s).
     */
    suspend fun verifyEnterprise(
        networkKey: String,
        observedAuthServerName: String,
        observedCaFingerprint: String,
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int,
        ssid: String? = null
    ): VerificationResult {
        cache.get(networkKey, observedAuthServerName)?.let { return it }

        val bitStrings = encoder.encodeQuery(lat, lng, radiusMeters)
        val (minAlt, maxAlt) = encoder.altitudeBounds(altitude, radiusMeters)
        val response = gecko.queryLocation(bitStrings, minAlt, maxAlt)

        val result = when (response) {
            is GeckoResponse.Unreachable ->
                VerificationResult(VerificationState.UNREACHABLE, observedAuthServerName, reason = response.cause)
            is GeckoResponse.ProofFailure ->
                VerificationResult(VerificationState.CONFLICT, observedAuthServerName, reason = response.cause)
            is GeckoResponse.Success -> {
                val candidates = filterBySsid(response.certificates, ssid) ?: return noMatchingSsid(observedAuthServerName)
                evaluateEnterpriseNetwork(observedAuthServerName, observedCaFingerprint, candidates).withResponseMetadata(response)
            }
        }
        if (result.state != VerificationState.UNREACHABLE) cache.put(networkKey, observedAuthServerName, result)
        return result
    }

    /** Returns `null` (meaning "stop, nothing to warn about") only when [ssid] was given and nothing matched. */
    private fun filterBySsid(candidates: List<GeoCertificate>, ssid: String?): List<GeoCertificate>? {
        if (ssid == null) return candidates
        val matching = candidates.filter { it.wifi.ssid?.equals(ssid, ignoreCase = true) == true }
        return if (matching.isEmpty()) null else matching
    }

    private fun noMatchingSsid(host: String) = VerificationResult(
        VerificationState.UNVERIFIED, host,
        reason = "no certificate registered for this SSID at this location"
    )

    /** Attaches display-only response metadata (see [VerificationResult]'s doc comment) after the decision is already made. */
    private fun VerificationResult.withResponseMetadata(response: GeckoResponse.Success) = copy(
        certificateCount = response.certificates.size,
        unparsedCount = response.unparsedCount,
        usedPreferredNetwork = response.usedPreferredNetwork
    )
}
