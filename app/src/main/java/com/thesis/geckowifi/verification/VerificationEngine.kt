package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.*
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.data.remote.GeckoResponse
import com.thesis.geckowifi.discovery.ObservedHop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
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
    private val encoder: GeoQueryEncoder,
    /** Injectable for tests; used to drop certificates past their `not_valid_after`. */
    private val clock: () -> Instant = Instant::now
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
    ): LocationLookup {
        val bitStrings = encoder.encodeQuery(lat, lng, radiusMeters)
        val (minAlt, maxAlt) = encoder.altitudeBounds(altitude, radiusMeters)
        return when (val response = gecko.queryLocation(bitStrings, minAlt, maxAlt)) {
            is GeckoResponse.Success -> LocationLookup(LookupStatus.OK, response.validCertificates())
            // The caller must tell "nothing registered here" apart from "couldn't
            // get a trustworthy answer" - an empty list alone can't. On a WiFi-only
            // device this query is usually unreachable until a network is joined.
            is GeckoResponse.Unreachable -> LocationLookup(LookupStatus.UNREACHABLE, emptyList())
            is GeckoResponse.ProofFailure -> LocationLookup(LookupStatus.UNTRUSTED, emptyList())
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
        // Blocking TLS handshake - must not run on the caller's (UI) dispatcher.
        val spki = withContext(Dispatchers.IO) { probe.spkiHash(probe.fetchCertificate(host)) }
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
                val candidates = filterBySsid(response.validCertificates(), ssid) ?: return noMatchingSsid(host)
                sessionFor(networkKey).observe(host, candidates, presentedSpkiHash).applyExclusivity(ssid).withResponseMetadata(response)
            }
        }
        if (result.state != VerificationState.UNREACHABLE) cache.put(networkKey, host, result)
        return result
    }

    /**
     * Judges a captive-portal hop chain (from any [com.thesis.geckowifi.discovery.HopSource])
     * with ONE location query and a fresh [PortalSession], hop by hop in order -
     * so a chain that starts on the genuine primary and then moves to an
     * unregistered or wrongly-keyed domain (relay-then-switch) is a CONFLICT
     * at the hop where it switches.
     *
     * Judged hops: every HTTPS hop (its presented key, or none if the TLS
     * connection failed), plus - if [judgeFinalPlainHop] - the last hop when
     * it is plain HTTP: the page the user actually sees, served without TLS
     * (a registered portal domain there = downgrade = CONFLICT). Plain-HTTP
     * hops that only redirect onwards (the probe URL, openNDS, http→https
     * upgrades) are transit hops and not judged.
     *
     * Overall: the first CONFLICT if any hop conflicts, else the last judged
     * hop's verdict (the page the user ends up on). Bypasses [cache] and
     * resets the network's session: each call is a new check of the network.
     */
    suspend fun verifyPortalHops(
        networkKey: String,
        hops: List<ObservedHop>,
        judgeFinalPlainHop: Boolean,
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int,
        ssid: String? = null
    ): PortalCheckResult {
        val judged = hops.filter { it.isHttps || (judgeFinalPlainHop && it === hops.lastOrNull()) }
        val unjudged = { overall: VerificationResult -> PortalCheckResult(overall, hops.map { HopVerdict(it, null) }) }
        if (judged.isEmpty()) {
            return unjudged(VerificationResult(VerificationState.UNVERIFIED, hops.lastOrNull()?.host.orEmpty(),
                reason = "no portal page to verify"))
        }

        val bitStrings = encoder.encodeQuery(lat, lng, radiusMeters)
        val (minAlt, maxAlt) = encoder.altitudeBounds(altitude, radiusMeters)
        val response = gecko.queryLocation(bitStrings, minAlt, maxAlt)
        val firstHost = judged.first().host
        val success = when (response) {
            is GeckoResponse.Unreachable ->
                return unjudged(VerificationResult(VerificationState.UNREACHABLE, firstHost, reason = response.cause))
            is GeckoResponse.ProofFailure ->
                return unjudged(VerificationResult(VerificationState.CONFLICT, firstHost, reason = response.cause))
            is GeckoResponse.Success -> response
        }
        val candidates = filterBySsid(success.validCertificates(), ssid) ?: return unjudged(noMatchingSsid(firstHost))

        val session = sessionFor(networkKey).also { it.reset() }
        val verdicts = hops.map { hop ->
            HopVerdict(hop, if (hop in judged) session.observe(hop.host, candidates, hop.presentedSpkiHash).applyExclusivity(ssid) else null)
        }
        val results = verdicts.mapNotNull { v -> v.result?.let { v.hop to it } }
        val (decidingHop, decisive) = results.firstOrNull { it.second.state == VerificationState.CONFLICT } ?: results.last()
        val overall = decisive.copy(
            reason = "hop ${decidingHop.index} ${decidingHop.scheme}://${decidingHop.host}: ${decisive.reason}"
        ).withResponseMetadata(success)
        return PortalCheckResult(overall, verdicts)
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
                val candidates = filterBySsid(response.validCertificates(), ssid) ?: return noMatchingSsid(observedAuthServerName)
                evaluateEnterpriseNetwork(observedAuthServerName, observedCaFingerprint, candidates).applyExclusivity(ssid).withResponseMetadata(response)
            }
        }
        if (result.state != VerificationState.UNREACHABLE) cache.put(networkKey, observedAuthServerName, result)
        return result
    }

    /**
     * Expired certificates are treated as if they weren't registered at all -
     * their domains/keys must not anchor or verify anything any more.
     */
    private fun GeckoResponse.Success.validCertificates(): List<GeoCertificate> {
        val now = clock()
        return certificates.filter { it.isValidAt(now) }
    }

    /** Returns `null` (meaning "stop, nothing to warn about") only when [ssid] was given and nothing matched. */
    private fun filterBySsid(candidates: List<GeoCertificate>, ssid: String?): List<GeoCertificate>? {
        if (ssid == null) return candidates
        val matching = candidates.filter { it.wifi.ssid?.equals(ssid, ignoreCase = true) == true }
        return if (matching.isEmpty()) null else matching
    }

    /**
     * SSID exclusivity (see docs/design-decisions.md). A query is SSID-scoped
     * when [ssid] was given, which means the candidates were pre-filtered to
     * that SSID - so reaching [PortalSession] at all proves the SSID *is*
     * registered at this location. A network on a registered SSID that matches
     * nothing registered there is not merely "unknown": it is presenting a
     * registered SSID it has no claim to, which we treat as impersonation.
     * So UNRECOGNIZED is escalated to CONFLICT in the SSID-scoped path.
     *
     * With [ssid] == null (no pre-filter, e.g. a typed-domain lookup that isn't
     * asserting an SSID) the additive UNRECOGNIZED semantics are kept: certs
     * may exist here for *other* SSIDs without claiming this domain's network.
     *
     * The matching registration-side rule (the map server must refuse a new
     * GeoCert whose (SSID, area) overlaps an existing one) lives in geopki, not
     * here; without it the client and the registry would disagree.
     */
    private fun VerificationResult.applyExclusivity(ssid: String?): VerificationResult =
        if (ssid != null && state == VerificationState.UNRECOGNIZED)
            copy(
                state = VerificationState.CONFLICT,
                reason = "unregistered identity on \"$ssid\", which is registered here (SSID exclusive): $reason"
            )
        else this

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
