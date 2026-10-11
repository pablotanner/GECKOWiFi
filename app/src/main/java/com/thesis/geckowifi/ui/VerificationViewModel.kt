package com.thesis.geckowifi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesis.geckowifi.BuildConfig
import com.thesis.geckowifi.data.local.HistoryStore
import com.thesis.geckowifi.data.local.PortalCheckLog
import com.thesis.geckowifi.data.local.TrustPreferenceStore
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.discovery.Discovery
import com.thesis.geckowifi.discovery.HopSource
import com.thesis.geckowifi.discovery.RedirectProbeSource
import com.thesis.geckowifi.location.LocationFix
import com.thesis.geckowifi.location.LocationProvider
import com.thesis.geckowifi.network.FakeDemoNetworks
import com.thesis.geckowifi.network.NetworkObserver
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.verification.CertProbe
import com.thesis.geckowifi.verification.DecisionCache
import com.thesis.geckowifi.verification.GeoQueryEncoder
import com.thesis.geckowifi.verification.HopVerdict
import com.thesis.geckowifi.verification.LookupStatus
import com.thesis.geckowifi.verification.VerificationEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The one demo server this build's [com.thesis.geckowifi.data.remote.PinnedServerKey]
 * is pinned to - see README.md's "Trust anchor" section for why the server
 * address isn't a runtime-editable field any more (pinning a key only means
 * something if the server it's pinned to isn't also freely swappable).
 * Chosen per build flavor (`emulator` / `device`), see app/build.gradle.kts.
 */
const val SERVER_URL = BuildConfig.GEOPKI_URL

/**
 * Which network the map-server query actually went out on. [MOBILE_DATA] and
 * [JOINED_WIFI] are the network GeckoClient was bound to (cellular when there
 * is any, else the WiFi being checked); [DEFAULT_ROUTE] is the system default,
 * used when nothing was bound or the bound network failed and GeckoClient fell back.
 */
enum class QueryRoute { MOBILE_DATA, JOINED_WIFI, DEFAULT_ROUTE }

/** Why a check ended without a verdict from the map server, so the UI can say it in plain words. */
enum class CheckFailure { NOT_OPEN, JOIN_FAILED, PORTAL_FAILED }

/**
 * A completed check bundled with the query context it was made with. Kept for
 * the whole session (newest first in [VerificationViewModel.checks]) so every
 * History row opens its own evidence, not just the last one.
 */
data class CheckDetail(
    val id: String = UUID.randomUUID().toString(),
    val result: VerificationResult,
    val ssid: String?,
    val bssid: String?,
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float?,
    val radiusMeters: Int,
    val altitude: Double?,
    /** Portal hops in order (empty when no portal was seen). */
    val hops: List<HopVerdict> = emptyList(),
    /** How the check was made: portal discovery, or the registered domain when no portal appeared. */
    val modeDescription: String? = null,
    /** The app holds a connection to this network (false for demo entries and failed joins). */
    val joined: Boolean = false,
    val failure: CheckFailure? = null,
    /** Simulated demo entry (emulator flavor): nothing was joined or probed. */
    val isDemo: Boolean = false,
    /** 802.1X check (login server compared) rather than a captive portal check. */
    val isEnterprise: Boolean = false,
    /** Null when no map-server answer was used (e.g. the query never got through). */
    val queryRoute: QueryRoute? = null
)

/** The steps of a check, in the order the app runs them (README "How a check works"). */
enum class CheckStep { JOIN, LOOKUP, PORTAL, COMPARE }

/** A check that is still running: which steps it has and which one is in progress. */
data class ActiveCheck(val ssid: String, val bssid: String?, val steps: List<CheckStep>, val current: CheckStep)

class VerificationViewModel(
    private val networkObserver: NetworkObserver,
    private val locationProvider: LocationProvider,
    private val certProbe: CertProbe,
    decisionCache: DecisionCache,
    geoQueryEncoder: GeoQueryEncoder,
    private val historyStore: HistoryStore,
    private val trustPreferences: TrustPreferenceStore,
    private val checkLog: PortalCheckLog? = null,
    /** Where portal hops come from; the redirect probe until root traffic observation exists. */
    private val hopSource: HopSource = RedirectProbeSource()
) : ViewModel() {

    /**
     * One long-lived engine for this ViewModel's lifetime, so [VerificationEngine.sessionFor]'s
     * `PortalSession`s actually persist across checks of the same network -
     * see the doc comment on [VerificationEngine.gecko] for the bug this
     * fixes. Only `gecko` is rebuilt per check (fresh cellular `Network`
     * lookup); the engine instance, and its session state, never is.
     */
    private val engine = VerificationEngine(
        gecko = GeckoClient(baseUrl = SERVER_URL),
        probe = certProbe,
        cache = decisionCache,
        encoder = geoQueryEncoder
    )

    var hasLocationPermission by mutableStateOf(false)
        private set
    var location by mutableStateOf<LocationFix?>(null)
        private set
    var scannedNetworks by mutableStateOf<List<ScannedNetwork>>(emptyList())
        private set
    var registeredHere by mutableStateOf<List<GeoCertificate>>(emptyList())
        private set
    /**
     * Whether [registeredHere] is actually known. `null` = not queried yet;
     * otherwise it says whether the list is trustworthy or the server couldn't
     * be reached - so an empty [registeredHere] isn't mistaken for "nothing here".
     */
    var certLookup by mutableStateOf<LookupStatus?>(null)
        private set
    var isRefreshing by mutableStateOf(false)
        private set

    /** Every check this session, newest first. */
    var checks by mutableStateOf<List<CheckDetail>>(emptyList())
        private set
    val lastCheck: CheckDetail? get() = checks.firstOrNull()

    /** The check that is running right now, if any. */
    var activeCheck by mutableStateOf<ActiveCheck?>(null)
        private set
    private var checkJob: Job? = null

    /** The network the status card is about: the last check's network, until the user disconnects. */
    var current by mutableStateOf<CheckDetail?>(null)
        private set

    /** A CONFLICT the user hasn't seen the full-screen warning for yet; the UI shows it and calls [warningShown]. */
    var pendingWarning by mutableStateOf<String?>(null)
        private set

    var strictMode: Boolean
        get() = trustPreferences.strictMode
        set(value) { trustPreferences.strictMode = value }

    var queryRadiusMeters by mutableStateOf(trustPreferences.queryRadiusMeters)
        private set

    fun updateQueryRadius(meters: Int) {
        queryRadiusMeters = meters
        trustPreferences.queryRadiusMeters = meters
    }

    var labMode by mutableStateOf(trustPreferences.labMode)
        private set

    fun updateLabMode(enabled: Boolean) {
        labMode = enabled
        trustPreferences.labMode = enabled
    }

    fun onPermissionResult(granted: Boolean) {
        hasLocationPermission = granted
        if (granted) refresh()
    }

    /** Re-scans nearby networks and re-queries GECKO for what's registered at the current location. */
    fun refresh() {
        if (!hasLocationPermission || isRefreshing) return
        scannedNetworks = currentScanList()
        networkObserver.requestScan { scannedNetworks = currentScanList() }
        viewModelScope.launch {
            isRefreshing = true
            // lastKnown() alone returns null until some component has actively
            // requested a location update at least once - a real gap on a cold
            // emulator/device even after `adb emu geo fix`, confirmed via
            // `adb shell dumpsys location` showing every provider's
            // "last location=null". awaitFix() actively requests one instead
            // of only reading the (possibly never-populated) passive cache.
            val fix = locationProvider.lastKnown() ?: locationProvider.awaitFix()
            location = fix
            if (fix != null) {
                rebindGeckoClient()
                val lookup = engine.registeredHere(
                    lat = fix.latitude,
                    lng = fix.longitude,
                    // Not fix.altitude - see the doc comment below on why this
                    // query deliberately never narrows by device altitude.
                    altitude = null,
                    radiusMeters = queryRadiusMeters
                )
                registeredHere = lookup.certificates
                certLookup = lookup.status
            }
            isRefreshing = false
        }
    }

    private fun currentScanList(): List<ScannedNetwork> =
        networkObserver.scanResults() + if (BuildConfig.SHOW_DEMO_NETWORKS) FakeDemoNetworks.all else emptyList()

    /**
     * Prefers cellular so the WiFi under evaluation can't interfere with its
     * own check - see README.md. Without cellular (the WiFi-only lab tablet)
     * it falls back to the WiFi the app joined, which is then the only route
     * to the server: that AP *can* block the query, which surfaces as
     * UNREACHABLE and never as a warning. A known limitation, not a bug.
     * The TLS probe always goes over the joined WiFi - it has to see what
     * that network presents.
     */
    private fun rebindGeckoClient() {
        val joined = networkObserver.joinedNetwork
        val cellular = networkObserver.cellularNetwork()
        boundRoute = when {
            cellular != null -> QueryRoute.MOBILE_DATA
            joined != null -> QueryRoute.JOINED_WIFI
            else -> QueryRoute.DEFAULT_ROUTE
        }
        engine.gecko = GeckoClient(baseUrl = SERVER_URL, network = cellular ?: joined)
        certProbe.network = joined
    }

    /** What [rebindGeckoClient] bound the map-server client to; read back when a check is published. */
    private var boundRoute = QueryRoute.DEFAULT_ROUTE

    /** The one entry point for the UI: joins and checks [network]. */
    fun startCheck(network: ScannedNetwork) {
        if (activeCheck != null) return
        checkJob = when (network) {
            is ScannedNetwork.Real -> checkReal(network)
            else -> checkFake(network)
        }
    }

    /** Stops a running check (e.g. a join stuck waiting for the system dialog) and drops the connection. */
    fun cancelCheck() {
        checkJob?.cancel()
        checkJob = null
        activeCheck = null
        networkObserver.leave()
    }

    /** Leaves the network the app joined for the current check; the status card goes back to idle. */
    fun disconnect() {
        networkObserver.leave()
        current = null
        pendingWarning = null
    }

    /** The user has seen the full-screen warning for [pendingWarning]; it isn't shown again for this check. */
    fun warningShown() {
        pendingWarning = null
    }

    /** The status card's network was dismissed without disconnecting (nothing joined to drop, e.g. a demo entry). */
    fun dismissCurrent() {
        current = null
    }

    fun checkById(id: String): CheckDetail? = checks.firstOrNull { it.id == id }

    /** Most recent check of the access point [networkKey] (BSSID, or SSID if unknown), for the list badge. */
    fun latestCheckFor(networkKey: String): CheckDetail? =
        checks.firstOrNull { (it.bssid ?: it.ssid) == networkKey }

    private fun step(network: ScannedNetwork, steps: List<CheckStep>, current: CheckStep) {
        activeCheck = ActiveCheck(network.ssid, network.bssid, steps, current)
    }

    /**
     * Every query below passes `altitude = null` (the encoder's "full
     * vertical range" case, see `GeoQueryEncoder.altitudeBounds`) rather
     * than `fix.altitude`. Found and fixed 2026-09-17: an emulator's mock
     * GPS fix reports a real but misleading `altitude` (e.g. `0.0`), which -
     * once actually wired into the query, unlike the old pre-Compose UI,
     * which never used altitude at all - narrowed the search to a sea-level
     * slice and silently excluded every cert registered at a real building's
     * altitude (350-450m for the demo certs), with no error, just an empty
     * result. GPS/network altitude is generally too imprecise to safely
     * narrow a security-relevant query with; `fix.altitude` is still
     * recorded on [CheckDetail] for display, just never fed into a query.
     */
    private fun checkFake(fake: ScannedNetwork): Job? {
        val fix = location ?: return null
        val steps = listOf(CheckStep.LOOKUP, CheckStep.COMPARE)
        step(fake, steps, CheckStep.LOOKUP)
        return viewModelScope.launch {
            try {
                rebindGeckoClient()
                step(fake, steps, CheckStep.COMPARE)
                val result = when (fake) {
                    is ScannedNetwork.FakeCaptivePortal -> engine.verifyPresentedDomain(
                        networkKey = "fake:${fake.ssid}:${fake.label}",
                        host = fake.presumedDomain,
                        presentedSpkiHash = fake.presumedSpkiHashBase64,
                        lat = fix.latitude, lng = fix.longitude, altitude = null,
                        radiusMeters = queryRadiusMeters, ssid = fake.ssid
                    )
                    is ScannedNetwork.FakeEduroam -> engine.verifyEnterprise(
                        networkKey = "fake:${fake.ssid}:${fake.label}",
                        observedAuthServerName = fake.presumedAuthServerName,
                        observedCaFingerprint = fake.presumedCaFingerprintBase64,
                        lat = fix.latitude, lng = fix.longitude, altitude = null,
                        radiusMeters = queryRadiusMeters, ssid = fake.ssid
                    )
                    is ScannedNetwork.Real -> return@launch
                }
                val mode = when (fake) {
                    is ScannedNetwork.FakeEduroam -> "Demo entry: login server compared"
                    else -> "Demo entry: login page compared"
                }
                publish(fake.ssid, fake.bssid, fake.bssid ?: fake.ssid, result, fix,
                    modeDescription = mode, joined = false, isDemo = true,
                    isEnterprise = fake is ScannedNetwork.FakeEduroam)
            } finally {
                activeCheck = null
            }
        }
    }

    /** First primary portal domain registered for [ssid] at the last queried location, if any. */
    fun registeredDomainFor(ssid: String): String? =
        registeredHere.firstOrNull { it.wifi.ssid.equals(ssid, ignoreCase = true) }
            ?.primaryDomains()?.firstOrNull()?.name

    /**
     * Joins [network] itself (the app drives the connection, not Android
     * settings), pinned to its BSSID, then verifies over it: [hopSource]
     * follows the network's redirects from a probe URL and every hop is judged
     * ([VerificationEngine.verifyPortalHops]). If the probe sees no portal
     * (HTTP 204: open network, or already logged in), the domain registered
     * for the SSID is probed instead, so a logged-in network is still checked.
     *
     * Every check is appended to [checkLog] (JSON lines, see [PortalCheckLog]).
     */
    private fun checkReal(network: ScannedNetwork.Real): Job? {
        val fix = location ?: return null
        val networkKey = network.bssid ?: network.ssid
        val steps = CheckStep.entries.toList()

        if (!network.isOpen) {
            val result = VerificationResult(VerificationState.UNREACHABLE, "",
                reason = "only open networks can be joined by this prototype")
            publish(network.ssid, network.bssid, networkKey, result, fix, failure = CheckFailure.NOT_OPEN)
            return null
        }

        step(network, steps, CheckStep.JOIN)
        return viewModelScope.launch {
            try {
                val joined = networkObserver.join(network.ssid, network.bssid)
                if (joined == null) {
                    val result = VerificationResult(VerificationState.UNREACHABLE, "",
                        reason = "could not join ${network.ssid} (${network.bssid})")
                    finishCheck(network, networkKey, fix, result, mode = "join", failure = CheckFailure.JOIN_FAILED)
                    return@launch
                }
                step(network, steps, CheckStep.LOOKUP)
                rebindGeckoClient()
                // The startup query ran before any network was joined; redo it now the server is reachable.
                engine.registeredHere(fix.latitude, fix.longitude, null, queryRadiusMeters).let {
                    registeredHere = it.certificates
                    certLookup = it.status
                }

                step(network, steps, CheckStep.PORTAL)
                val discovery = hopSource.discover(joined)
                step(network, steps, CheckStep.COMPARE)
                when (val outcome = discovery.outcome) {
                    Discovery.Outcome.PortalReached -> {
                        val check = engine.verifyPortalHops(
                            networkKey, discovery.hops, judgeFinalPlainHop = true,
                            fix.latitude, fix.longitude, null, queryRadiusMeters, network.ssid
                        )
                        finishCheck(network, networkKey, fix, check.overall, "discovery", outcome, check.hops)
                    }
                    Discovery.Outcome.NoPortal -> {
                        // No portal in the way: check the network's registered portal domain directly.
                        val registered = registeredDomainFor(network.ssid)
                        val result = if (registered == null) {
                            VerificationResult(VerificationState.UNVERIFIED, "",
                                reason = "no captive portal seen, and nothing registered here lists a portal domain for ${network.ssid}")
                        } else {
                            verifyDomain(network, networkKey, registered, fix).let {
                                it.copy(reason = "no captive portal seen (already logged in?) - checked registered $registered: ${it.reason}")
                            }
                        }
                        finishCheck(network, networkKey, fix, result, "discovery", outcome,
                            discovery.hops.map { HopVerdict(it, null) })
                    }
                    is Discovery.Outcome.Failed -> {
                        // Judge what was seen: a key mismatch before the failure is still a CONFLICT.
                        val partial = engine.verifyPortalHops(
                            networkKey, discovery.hops, judgeFinalPlainHop = false,
                            fix.latitude, fix.longitude, null, queryRadiusMeters, network.ssid
                        )
                        val conflict = partial.overall.state == VerificationState.CONFLICT
                        val result = if (conflict) partial.overall
                        else VerificationResult(VerificationState.UNREACHABLE, discovery.hops.lastOrNull()?.host.orEmpty(),
                            reason = "portal discovery failed: ${outcome.reason}")
                        finishCheck(network, networkKey, fix, result, "discovery", outcome, partial.hops,
                            failure = if (conflict) null else CheckFailure.PORTAL_FAILED)
                    }
                }
            } finally {
                activeCheck = null
            }
        }
    }

    private suspend fun verifyDomain(
        network: ScannedNetwork.Real, networkKey: String, host: String, fix: LocationFix
    ): VerificationResult = engine.verify(
        networkKey = networkKey,
        host = host,
        lat = fix.latitude, lng = fix.longitude, altitude = null,
        radiusMeters = queryRadiusMeters, ssid = network.ssid
    )

    private fun finishCheck(
        network: ScannedNetwork.Real,
        networkKey: String,
        fix: LocationFix,
        result: VerificationResult,
        mode: String,
        outcome: Discovery.Outcome? = null,
        hops: List<HopVerdict> = emptyList(),
        failure: CheckFailure? = null
    ) {
        checkLog?.append(
            PortalCheckLog.CheckEntry(
                runId = UUID.randomUUID().toString(),
                time = PortalCheckLog.isoTime(result.timestamp),
                ssid = network.ssid, bssid = network.bssid, networkKey = networkKey,
                mode = mode,
                outcome = when (outcome) {
                    null -> null
                    is Discovery.Outcome.Failed -> "Failed: ${outcome.reason}"
                    else -> outcome::class.simpleName
                },
                state = result.state.name, reason = result.reason,
                lat = fix.latitude, lng = fix.longitude, accuracyMeters = fix.accuracyMeters,
                hops = PortalCheckLog.hopEntries(hops)
            )
        )
        val modeDescription = when (mode) {
            "discovery" -> if (outcome == Discovery.Outcome.NoPortal) "No login page appeared; the registered one was checked directly"
            else "Login page found by following the network's redirects"
            else -> null
        }
        publish(network.ssid, network.bssid, networkKey, result, fix, hops, modeDescription,
            joined = failure != CheckFailure.JOIN_FAILED, failure = failure)
    }

    private fun publish(
        ssid: String?,
        bssid: String?,
        networkKey: String,
        result: VerificationResult,
        fix: LocationFix,
        hops: List<HopVerdict> = emptyList(),
        modeDescription: String? = null,
        joined: Boolean = false,
        failure: CheckFailure? = null,
        isDemo: Boolean = false,
        isEnterprise: Boolean = false
    ) {
        historyStore.record(networkKey, ssid, result, fix.latitude, fix.longitude)
        val detail = CheckDetail(
            result = result,
            ssid = ssid,
            bssid = bssid,
            lat = fix.latitude,
            lng = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            radiusMeters = queryRadiusMeters,
            altitude = fix.altitude,
            hops = hops,
            modeDescription = modeDescription,
            joined = joined,
            failure = failure,
            isDemo = isDemo,
            isEnterprise = isEnterprise,
            // usedPreferredNetwork = "the bound network served it", not "cellular".
            queryRoute = when (result.usedPreferredNetwork) {
                true -> boundRoute
                false -> QueryRoute.DEFAULT_ROUTE
                null -> null
            }
        )
        checks = listOf(detail) + checks
        current = detail
        if (result.state == VerificationState.CONFLICT) pendingWarning = detail.id
    }

    override fun onCleared() {
        networkObserver.leave()
    }
}
