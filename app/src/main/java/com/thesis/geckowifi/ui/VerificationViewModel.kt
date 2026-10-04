package com.thesis.geckowifi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesis.geckowifi.BuildConfig
import com.thesis.geckowifi.data.local.HistoryStore
import com.thesis.geckowifi.data.local.TrustPreferenceStore
import com.thesis.geckowifi.data.local.VerificationRecord
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.location.LocationFix
import com.thesis.geckowifi.location.LocationProvider
import com.thesis.geckowifi.network.FakeDemoNetworks
import com.thesis.geckowifi.network.NetworkObserver
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.verification.CertProbe
import com.thesis.geckowifi.verification.DecisionCache
import com.thesis.geckowifi.verification.GeoQueryEncoder
import com.thesis.geckowifi.verification.VerificationEngine
import kotlinx.coroutines.launch

/**
 * The one demo server this build's [com.thesis.geckowifi.data.remote.PinnedServerKey]
 * is pinned to - see README.md's "Trust anchor" section for why the server
 * address isn't a runtime-editable field any more (pinning a key only means
 * something if the server it's pinned to isn't also freely swappable).
 * Chosen per build flavor (`emulator` / `device`), see app/build.gradle.kts.
 */
private const val SERVER_URL = BuildConfig.GEOPKI_URL

/**
 * A completed check bundled with the query context it was made with, for
 * Check Detail / Technical Details to display. Held as plain view state
 * (not persisted) - same lifetime as `MainActivity`'s old ad hoc fields,
 * just centralized.
 */
data class CheckDetail(
    val result: VerificationResult,
    val ssid: String?,
    val bssid: String?,
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float?,
    val radiusMeters: Int,
    val altitude: Double?
)

class VerificationViewModel(
    private val networkObserver: NetworkObserver,
    private val locationProvider: LocationProvider,
    private val certProbe: CertProbe,
    decisionCache: DecisionCache,
    geoQueryEncoder: GeoQueryEncoder,
    private val historyStore: HistoryStore,
    private val trustPreferences: TrustPreferenceStore
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
    var isRefreshing by mutableStateOf(false)
        private set
    var history by mutableStateOf<List<VerificationRecord>>(emptyList())
        private set
    var lastCheck by mutableStateOf<CheckDetail?>(null)
        private set
    /** SSID currently being joined for a check, for the Network Detail button. */
    var connectingTo by mutableStateOf<String?>(null)
        private set

    var strictMode: Boolean
        get() = trustPreferences.strictMode
        set(value) { trustPreferences.strictMode = value }

    var queryRadiusMeters: Int
        get() = trustPreferences.queryRadiusMeters
        set(value) { trustPreferences.queryRadiusMeters = value }

    fun onPermissionResult(granted: Boolean) {
        hasLocationPermission = granted
        if (granted) refresh()
    }

    /** Re-scans nearby networks and re-queries GECKO for what's registered at the current location. */
    fun refresh() {
        if (!hasLocationPermission) return
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
                registeredHere = engine.registeredHere(
                    lat = fix.latitude,
                    lng = fix.longitude,
                    // Not fix.altitude - see the doc comment below on why this
                    // query deliberately never narrows by device altitude.
                    altitude = null,
                    radiusMeters = queryRadiusMeters
                )
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
        engine.gecko = GeckoClient(baseUrl = SERVER_URL, network = networkObserver.cellularNetwork() ?: joined)
        certProbe.network = joined
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
    fun checkFake(fake: ScannedNetwork, onDone: (VerificationResult) -> Unit) {
        val fix = location ?: return
        viewModelScope.launch {
            rebindGeckoClient()
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
            recordAndPublish(fake.ssid, fake.bssid, fake.bssid ?: fake.ssid, result, fix)
            onDone(result)
        }
    }

    /** First primary portal domain registered for [ssid] at the last queried location, if any. */
    fun registeredDomainFor(ssid: String): String? =
        registeredHere.firstOrNull { it.wifi.ssid.equals(ssid, ignoreCase = true) }
            ?.primaryDomains()?.firstOrNull()?.name

    /**
     * Joins [network] itself (the app drives the connection, not Android
     * settings), pinned to its BSSID, then verifies over it.
     *
     * A blank [domain] means "check the domain registered for this SSID" -
     * only knowable after joining, since that's when the server becomes
     * reachable (WiFi-only device). A stand-in for real portal detection,
     * like typing it, but immune to typos.
     */
    fun checkReal(network: ScannedNetwork.Real, domain: String, onDone: (VerificationResult) -> Unit) {
        val fix = location ?: return
        if (connectingTo != null) return
        viewModelScope.launch {
            if (!network.isOpen) {
                onDone(VerificationResult(VerificationState.UNREACHABLE, domain,
                    reason = "only open networks can be joined by this prototype"))
                return@launch
            }
            connectingTo = network.ssid
            val joined = try {
                networkObserver.join(network.ssid, network.bssid)
            } finally {
                connectingTo = null
            }
            if (joined == null) {
                val result = VerificationResult(VerificationState.UNREACHABLE, domain,
                    reason = "could not join ${network.ssid} (${network.bssid})")
                recordAndPublish(network.ssid, network.bssid, network.bssid ?: network.ssid, result, fix)
                onDone(result)
                return@launch
            }
            rebindGeckoClient()
            // The startup query ran before any network was joined; redo it now the server is reachable.
            registeredHere = engine.registeredHere(fix.latitude, fix.longitude, null, queryRadiusMeters)
            val host = domain.trim().ifBlank { registeredDomainFor(network.ssid).orEmpty() }
            if (host.isEmpty()) {
                val result = VerificationResult(VerificationState.UNVERIFIED, "",
                    reason = "no domain to check: nothing registered here lists a portal domain for ${network.ssid}")
                recordAndPublish(network.ssid, network.bssid, network.bssid ?: network.ssid, result, fix)
                onDone(result)
                return@launch
            }
            val result = engine.verify(
                networkKey = network.bssid ?: network.ssid,
                host = host,
                lat = fix.latitude, lng = fix.longitude, altitude = null,
                radiusMeters = queryRadiusMeters, ssid = network.ssid
            )
            recordAndPublish(network.ssid, network.bssid, network.bssid ?: network.ssid, result, fix)
            onDone(result)
        }
    }

    private fun recordAndPublish(
        ssid: String?,
        bssid: String?,
        networkKey: String,
        result: VerificationResult,
        fix: LocationFix
    ) {
        historyStore.record(networkKey, ssid, result, fix.latitude, fix.longitude)
        history = historyStore.recent()
        lastCheck = CheckDetail(
            result = result,
            ssid = ssid,
            bssid = bssid,
            lat = fix.latitude,
            lng = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            radiusMeters = queryRadiusMeters,
            altitude = fix.altitude
        )
    }

    override fun onCleared() {
        networkObserver.leave()
    }

    /** For Network Detail's per-network History section. */
    fun historyFor(networkKey: String): List<VerificationRecord> =
        history.filter { it.networkKey == networkKey }
}
