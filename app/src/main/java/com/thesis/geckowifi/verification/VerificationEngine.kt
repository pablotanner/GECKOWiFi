package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.*
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.data.remote.GeckoResponse
import com.thesis.geckowifi.discovery.ObservedHop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
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
            is GeckoResponse.Success -> LocationLookup(LookupStatus.OK, response.validCertificates(lat, lng, radiusMeters))
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
        ssid: String? = null,
        observedAuthModes: Set<WiFiAuthMode>? = null
    ): VerificationResult {
        cache.get(networkKey, host)?.let { return it }
        // Blocking TLS handshake - must not run on the caller's (UI) dispatcher.
        val spki = withContext(Dispatchers.IO) { probe.spkiHash(probe.fetchCertificate(host)) }
        return verifyPresentedDomain(networkKey, host, spki, lat, lng, altitude, radiusMeters, ssid, observedAuthModes)
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
     *
     * [observedAuthModes], when given with [ssid], are the modes the access
     * point advertises; see [authModeConflict].
     */
    suspend fun verifyPresentedDomain(
        networkKey: String,
        host: String,
        presentedSpkiHash: String?,
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int,
        ssid: String? = null,
        observedAuthModes: Set<WiFiAuthMode>? = null
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
            is GeckoResponse.Success -> when (val c = candidatesFor(response, lat, lng, radiusMeters, ssid, host)) {
                is Candidates.Done -> return c.result
                is Candidates.Judge -> c.finish(
                    sessionFor(networkKey).observe(host, c.certs, presentedSpkiHash),
                    authModeConflict(c, observedAuthModes, host)
                ).withResponseMetadata(response)
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
        ssid: String? = null,
        observedAuthModes: Set<WiFiAuthMode>? = null
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
        val c = when (val prepared = candidatesFor(success, lat, lng, radiusMeters, ssid, firstHost)) {
            is Candidates.Done -> return unjudged(prepared.result)
            is Candidates.Judge -> prepared
        }

        val session = sessionFor(networkKey).also { it.reset() }
        val verdicts = hops.map { hop ->
            HopVerdict(hop, if (hop in judged) c.finish(session.observe(hop.host, c.certs, hop.presentedSpkiHash)) else null)
        }
        val results = verdicts.mapNotNull { v -> v.result?.let { v.hop to it } }
        val (decidingHop, decisive) = results.firstOrNull { it.second.state == VerificationState.CONFLICT } ?: results.last()
        val fromHops = decisive.copy(reason = "hop ${decidingHop.index} ${decidingHop.scheme}://${decidingHop.host}: ${decisive.reason}")
        val overall = c.finish(fromHops, authModeConflict(c, observedAuthModes, firstHost)).withResponseMetadata(success)
        return PortalCheckResult(overall, verdicts)
    }

    /**
     * Enterprise (802.1X / eduroam) counterpart of [verifyPresentedDomain]:
     * same location query and SSID handling, but the RADIUS server seen in the
     * handshake ([observed]) is judged by [evaluateEnterpriseNetwork], with
     * [userRealm] deciding local user vs. visitor.
     */
    suspend fun verifyEnterprise(
        networkKey: String,
        observed: ObservedAuthServer,
        userRealm: String?,
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int,
        ssid: String? = null,
        observedAuthModes: Set<WiFiAuthMode>? = null
    ): VerificationResult = verifyEnterpriseWith(
        networkKey, "eap:${observed.leafSpkiSha256}:${userRealm.orEmpty()}", observed.displayName,
        { observed }, userRealm, lat, lng, altitude, radiusMeters, ssid, observedAuthModes
    )

    /**
     * [verifyEnterprise] for a real handshake: [chain] is the server's chain as
     * the supplicant reported it (leaf first). Signatures are checked here, and
     * the registered CA certificates of this location's candidates serve as
     * anchors, because servers often don't send their root.
     */
    suspend fun verifyEnterpriseChain(
        networkKey: String,
        chain: List<X509Certificate>,
        userRealm: String?,
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int,
        ssid: String? = null,
        observedAuthModes: Set<WiFiAuthMode>? = null
    ): VerificationResult {
        val unanchored = ObservedAuthServer.fromChain(chain)
            ?: return VerificationResult(VerificationState.UNVERIFIED, "", reason = "no server certificate was presented")
        return verifyEnterpriseWith(
            networkKey, "eap-chain:${unanchored.leafSpkiSha256}:${userRealm.orEmpty()}", unanchored.displayName,
            { certs ->
                val anchors = certs.flatMap { it.wifi.enterprise?.aaa.orEmpty() }.flatMap { it.servers }.flatMap { it.pinnedCaCertificates() }
                ObservedAuthServer.fromChain(chain, anchors) ?: unanchored
            },
            userRealm, lat, lng, altitude, radiusMeters, ssid, observedAuthModes
        )
    }

    private suspend fun verifyEnterpriseWith(
        networkKey: String,
        cacheKey: String,
        host: String,
        observe: (candidates: List<GeoCertificate>) -> ObservedAuthServer,
        userRealm: String?,
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int,
        ssid: String?,
        observedAuthModes: Set<WiFiAuthMode>?
    ): VerificationResult {
        cache.get(networkKey, cacheKey)?.let { return it }

        val bitStrings = encoder.encodeQuery(lat, lng, radiusMeters)
        val (minAlt, maxAlt) = encoder.altitudeBounds(altitude, radiusMeters)
        val response = gecko.queryLocation(bitStrings, minAlt, maxAlt)

        val result = when (response) {
            is GeckoResponse.Unreachable ->
                VerificationResult(VerificationState.UNREACHABLE, host, reason = response.cause)
            is GeckoResponse.ProofFailure ->
                VerificationResult(VerificationState.CONFLICT, host, reason = response.cause)
            is GeckoResponse.Success -> when (val c = candidatesFor(response, lat, lng, radiusMeters, ssid, host)) {
                is Candidates.Done -> return c.result
                is Candidates.Judge -> c.finish(
                    evaluateEnterpriseNetwork(observe(c.certs), userRealm, c.certs),
                    authModeConflict(c, observedAuthModes, host)
                ).withResponseMetadata(response)
            }
        }
        if (result.state != VerificationState.UNREACHABLE) cache.put(networkKey, cacheKey, result)
        return result
    }

    /**
     * Judges a network whose registration lists nothing to verify its
     * identity against (no portal domain, no login server): only its
     * advertised auth modes ([observedAuthModes]) can be compared. Unregistered
     * SSID: UNVERIFIED. Advertised modes not registered: CONFLICT (scenario
     * A1). Otherwise REGISTERED: the registration exists and nothing
     * contradicts it, nothing more. Before joining, the list makes the same
     * comparison from the last lookup ([matchAdvertised]).
     */
    suspend fun checkAdvertised(
        networkKey: String,
        ssid: String,
        observedAuthModes: Set<WiFiAuthMode>,
        lat: Double,
        lng: Double,
        altitude: Double?,
        radiusMeters: Int
    ): VerificationResult {
        val bitStrings = encoder.encodeQuery(lat, lng, radiusMeters)
        val (minAlt, maxAlt) = encoder.altitudeBounds(altitude, radiusMeters)
        return when (val response = gecko.queryLocation(bitStrings, minAlt, maxAlt)) {
            is GeckoResponse.Unreachable ->
                VerificationResult(VerificationState.UNREACHABLE, ssid, reason = response.cause)
            is GeckoResponse.ProofFailure ->
                VerificationResult(VerificationState.CONFLICT, ssid, reason = response.cause)
            is GeckoResponse.Success -> when (val c = candidatesFor(response, lat, lng, radiusMeters, ssid, ssid)) {
                is Candidates.Done -> c.result
                is Candidates.Judge -> {
                    val cert = c.certs.first()
                    val operator = cert.subject?.name?.let { " by $it" }.orEmpty()
                    val registered = VerificationResult(
                        VerificationState.REGISTERED, ssid,
                        matchedCertificateId = cert.certificateId,
                        reason = "registered here$operator; it advertises ${observedAuthModes.joinToString { it.wire }}, " +
                            "as registered; the registration lists nothing else to compare"
                    )
                    c.finish(registered, authModeConflict(c, observedAuthModes, ssid)).withResponseMetadata(response)
                }
            }
        }.also { if (it.state != VerificationState.UNREACHABLE) cache.put(networkKey, "advertised:$ssid", it) }
    }

    /**
     * The certificates from this response that may actually be used to judge
     * the network: dropped if expired (their domains/keys must not anchor or
     * verify anything any more) or if their registered area does not reach the
     * device ([GeoCertificate.coversLocation]). The area check closes the gap
     * where the map server's cell-based query returns a certificate registered
     * for the same SSID at *another* place (see docs/experiments.md, G2/G3):
     * the query cell is coarser than both the area and the search circle, so
     * presence in the response is not by itself proof the registration applies
     * where the device is.
     */
    private fun GeckoResponse.Success.validCertificates(
        lat: Double, lng: Double, radiusMeters: Int
    ): List<GeoCertificate> {
        val now = clock()
        return certificates.filter { it.isValidAt(now) && it.coversLocation(lat, lng, radiusMeters) }
    }

    /** Returns `null` (meaning "stop, nothing to warn about") only when [ssid] was given and nothing matched. */
    private fun filterBySsid(candidates: List<GeoCertificate>, ssid: String?): List<GeoCertificate>? {
        if (ssid == null) return candidates
        val matching = candidates.filter { it.wifi.ssid?.equals(ssid, ignoreCase = true) == true }
        return if (matching.isEmpty()) null else matching
    }

    /** What a successful query leaves to judge: certificates, or already a final result. */
    private sealed interface Candidates {
        data class Done(val result: VerificationResult) : Candidates

        /**
         * [certs]: valid, covering, SSID-matched and evaluable. [skipped]: the
         * SSID's certificates this client can't evaluate (unknown critical
         * feature). [ssid]: the SSID the query was scoped to, if any.
         */
        data class Judge(val certs: List<GeoCertificate>, val skipped: List<GeoCertificate>, val ssid: String?) : Candidates
    }

    private fun candidatesFor(
        response: GeckoResponse.Success, lat: Double, lng: Double, radiusMeters: Int, ssid: String?, host: String
    ): Candidates {
        val bySsid = filterBySsid(response.validCertificates(lat, lng, radiusMeters), ssid)
            ?: return Candidates.Done(noMatchingSsid(host))
        val (evaluable, skipped) = bySsid.partition { it.isEvaluable() }
        if (evaluable.isEmpty()) {
            val features = skipped.flatMap { it.critical }.distinct().joinToString()
            return Candidates.Done(VerificationResult(VerificationState.UNVERIFIED, host,
                reason = "registered here with features this app doesn't support ($features)").withResponseMetadata(response))
        }
        return Candidates.Judge(evaluable, skipped, ssid)
    }

    /**
     * The rules on top of a single verdict: an auth-mode conflict (if any)
     * wins over a non-conflicting verdict, SSID exclusivity escalates
     * UNRECOGNIZED, and a CONFLICT is withheld while some of the SSID's
     * certificates couldn't be evaluated - one of them might register exactly
     * what was seen.
     */
    private fun Candidates.Judge.finish(verdict: VerificationResult, authConflict: VerificationResult? = null): VerificationResult {
        val combined = if (authConflict != null && verdict.state != VerificationState.CONFLICT) authConflict else verdict
        val result = combined.applyExclusivity(ssid, shared = certs.any { it.wifi.ssidShared })
        return if (result.state == VerificationState.CONFLICT && skipped.isNotEmpty())
            result.copy(state = VerificationState.UNVERIFIED,
                reason = "would be a conflict (${result.reason}), but ${skipped.size} certificate(s) for this SSID use features this app doesn't support")
        else result
    }

    /**
     * Scenario A1: every mode the access point advertises ([observed], from the
     * scan) must be registered for the SSID, else this network isn't what was
     * registered (an open or personal clone of an enterprise SSID, a WPA2
     * clone of a WPA3-only one). Only certificates that register auth modes
     * take part (v2 certificates don't), and one permitting certificate is
     * enough. Returns UNRECOGNIZED, which SSID exclusivity escalates.
     */
    private fun authModeConflict(c: Candidates.Judge, observed: Set<WiFiAuthMode>?, host: String): VerificationResult? {
        if (observed.isNullOrEmpty() || c.ssid == null) return null
        if (c.certs.permitAdvertised(observed) != false) return null
        val registering = c.certs.filter { it.wifi.authModeNames.isNotEmpty() }
        val registered = registering.flatMap { it.wifi.authModeNames }.distinct()
        return VerificationResult(
            VerificationState.UNRECOGNIZED, host,
            matchedCertificateId = registering.first().certificateId,
            reason = "network advertises ${observed.joinToString { it.wire }}, but is registered as ${registered.joinToString()}",
            registeredIdentifier = registered.joinToString(),
            presentedIdentifier = observed.joinToString { it.wire }
        )
    }

    /**
     * SSID exclusivity (see docs/design-decisions.md). A query is SSID-scoped
     * when [ssid] was given, which means the candidates were pre-filtered to
     * that SSID - so reaching the judge at all proves the SSID *is* registered
     * at this location. A network on a registered SSID that matches nothing
     * registered there is not merely "unknown": it is presenting a registered
     * SSID it has no claim to, which we treat as impersonation. So
     * UNRECOGNIZED is escalated to CONFLICT in the SSID-scoped path.
     *
     * Not escalated with [ssid] == null (no pre-filter, e.g. a typed-domain
     * lookup that isn't asserting an SSID), or when a registration marks the
     * SSID as [shared][WiFiIdentity.ssidShared] between operators: then an
     * unknown network on it may be legitimate.
     *
     * The matching registration-side rule (the map server must refuse a new
     * GeoCert whose (SSID, area) overlaps one of another subject) lives in
     * geopki, not here; without it the client and the registry would disagree.
     */
    private fun VerificationResult.applyExclusivity(ssid: String?, shared: Boolean): VerificationResult =
        if (ssid != null && !shared && state == VerificationState.UNRECOGNIZED)
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
