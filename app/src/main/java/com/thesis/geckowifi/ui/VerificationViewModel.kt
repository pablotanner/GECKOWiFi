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
import com.thesis.geckowifi.data.local.RegistrationCache
import com.thesis.geckowifi.data.local.SavedLookup
import com.thesis.geckowifi.data.local.SavedRealmRecord
import com.thesis.geckowifi.data.local.StoredVerdict
import com.thesis.geckowifi.eduroam.DataSource
import com.thesis.geckowifi.eduroam.usableSavedRegistration
import com.thesis.geckowifi.verification.appliesAt
import com.thesis.geckowifi.verification.coversLocation
import com.thesis.geckowifi.verification.distanceMeters
import com.thesis.geckowifi.verification.isExpired
import com.thesis.geckowifi.verification.savedAnswer
import com.thesis.geckowifi.verification.SavedAnswerUpkeep
import com.thesis.geckowifi.verification.savedAnswerUpkeep
import com.thesis.geckowifi.data.remote.MapHeadAnswer
import com.thesis.geckowifi.data.local.VerdictStore
import com.thesis.geckowifi.eduroam.AccountStore
import com.thesis.geckowifi.eduroam.Decision
import com.thesis.geckowifi.eduroam.EnterpriseOnboarding
import com.thesis.geckowifi.eduroam.EnterpriseProfileInstaller
import com.thesis.geckowifi.eduroam.EnterpriseStep
import com.thesis.geckowifi.eduroam.EnterpriseTarget
import com.thesis.geckowifi.eduroam.PinCheck
import com.thesis.geckowifi.eduroam.PinSource
import com.thesis.geckowifi.eduroam.QueryArea
import com.thesis.geckowifi.discovery.HopSource
import com.thesis.geckowifi.discovery.RedirectProbeSource
import com.thesis.geckowifi.location.LocationFix
import com.thesis.geckowifi.location.LocationProvider
import com.thesis.geckowifi.network.FakeDemoNetworks
import com.thesis.geckowifi.network.NetworkObserver
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.verification.AdvertisedMatch
import com.thesis.geckowifi.verification.CertProbe
import com.thesis.geckowifi.verification.DecisionCache
import com.thesis.geckowifi.verification.GeoQueryEncoder
import com.thesis.geckowifi.verification.HopVerdict
import com.thesis.geckowifi.verification.LocationLookup
import com.thesis.geckowifi.verification.LookupStatus
import com.thesis.geckowifi.verification.VerificationEngine
import com.thesis.geckowifi.verification.matchAdvertised
import com.thesis.geckowifi.capability.Capabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.ceil

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
enum class CheckFailure {
    SERVER_UNREADABLE, JOIN_FAILED, PORTAL_FAILED, LOW_ACCURACY,
    /** Enterprise: GECKO couldn't confirm the login server for any fitting account, so no password went out. */
    SERVER_NOT_CONFIRMED,
    /** Enterprise: the GECKO onboarding session didn't come up (declined, out of range, server certificate). */
    ONBOARDING_FAILED,
    /**
     * Enterprise: the pinned profile is installed, but the device stayed on another Wi-Fi network; Android
     * doesn't switch from a working network to a suggested one by itself (docs/eduroam-decisions.md, D27).
     */
    ANDROID_STAYED
}

/**
 * What an enterprise connection was based on (docs/eduroam-decisions.md, D19),
 * for the evidence screen: who logged in, where the pins came from, and what
 * happened to each saved account that fitted.
 */
data class EnterpriseEvidence(
    val account: String? = null,
    val federation: String? = null,
    val operatorName: String? = null,
    /** "Signed record of Home University in eduroam" / "This network's registration (site-cert)". */
    val pinsFrom: String? = null,
    val serverNames: List<String> = emptyList(),
    val caSubjects: List<String> = emptyList(),
    val onboardingFailure: String? = null,
    /** One line per fitting account: "alice@homeuni.test: server confirmed (eduroam record)". */
    val accounts: List<String> = emptyList(),
    /** Step name to milliseconds since the start. */
    val timings: List<Pair<String, Long>> = emptyList()
)

/** The map query's hard radius cap (see [com.thesis.geckowifi.verification.GeoQueryEncoder]). */
const val MAX_QUERY_RADIUS_METERS = 253

/**
 * Android's accuracy is a 68 % radius: the true position is outside it one time
 * in three. A query of 1.6 x the accuracy contains it about 95 % of the time
 * (normally distributed error), so a registration nearby isn't silently missed.
 */
const val ACCURACY_TO_RADIUS = 1.6

/**
 * The widest check (lab/density/README.md): it covers nearly all indoor fixes,
 * and the answer grows with the radius squared. A fix that would need more
 * (accuracy worse than [MAX_CHECK_ACCURACY_METERS]) is refused rather than
 * queried narrower, which risks a silent miss (docs/experiments.md, L3), or
 * wider, which costs megabytes in a dense area.
 */
const val MAX_CHECK_RADIUS_METERS = 100

/** The worst reported accuracy a check accepts: [MAX_CHECK_RADIUS_METERS] / [ACCURACY_TO_RADIUS]. */
const val MAX_CHECK_ACCURACY_METERS = MAX_CHECK_RADIUS_METERS / ACCURACY_TO_RADIUS

/**
 * Radius of a check: [settingMeters] (the lab's search radius), but never less
 * than [ACCURACY_TO_RADIUS] x [accuracyMeters], and at most [MAX_CHECK_RADIUS_METERS].
 */
fun checkRadius(settingMeters: Int, accuracyMeters: Float): Int =
    maxOf(settingMeters, ceil(accuracyMeters * ACCURACY_TO_RADIUS).toInt())
        .coerceAtMost(MAX_CHECK_RADIUS_METERS)

/**
 * Radius of the area the home list's lookup asks for and the saved answer then
 * covers (docs/eduroam-decisions.md, D26): the widest query the encoder allows,
 * so the saved answer still applies after a walk across the building, not only
 * within one GPS fix. The list itself only shows what reaches the device.
 */
const val SAVED_AREA_RADIUS_METERS = MAX_QUERY_RADIUS_METERS

/**
 * How often, while the app is visible, GECKO checks whether the saved answer
 * still covers the device and is recent (D26). Only the check is periodic: it
 * looks the place up only when [savedAnswerUpkeep] says so.
 */
const val UPKEEP_CHECK_INTERVAL_MS = 5 * 60_000L

/** After the device joins a Wi-Fi network: waits before each attempt, until the saved answer is current (D26). */
private val JOIN_LOOKUP_DELAYS_MS = listOf(3_000L, 10_000L, 30_000L)

/** How long the login-server check waits for the user to reconnect and the EAP handshake to run. */
const val HANDSHAKE_CAPTURE_SECONDS = 60

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
    val queryRoute: QueryRoute? = null,
    /** Enterprise connection through GECKO's onboarding (null for every other check). */
    val enterprise: EnterpriseEvidence? = null,
    /** Restored from [VerdictStore] after a restart: a summary only, no hops. */
    val restored: Boolean = false,
    /** Where the registration data behind this verdict came from and when, in words (D21). */
    val dataSource: String? = null
)

/**
 * The steps of a check, in the order the app runs them (README "How a check works").
 * Enterprise networks use [ONBOARD], [CONFIRM], [CONNECT] (docs/eduroam-decisions.md, D20).
 */
enum class CheckStep { JOIN, LOOKUP, PORTAL, SERVER, COMPARE, ONBOARD, CONFIRM, CONNECT }

/** A check that is still running: which steps it has and which one is in progress. */
data class ActiveCheck(val ssid: String, val bssid: String?, val steps: List<CheckStep>, val current: CheckStep)

class VerificationViewModel(
    private val networkObserver: NetworkObserver,
    private val locationProvider: LocationProvider,
    private val certProbe: CertProbe,
    private val decisionCache: DecisionCache,
    geoQueryEncoder: GeoQueryEncoder,
    private val historyStore: HistoryStore,
    private val trustPreferences: TrustPreferenceStore,
    private val checkLog: PortalCheckLog? = null,
    /** Where portal hops come from; the redirect probe until root traffic observation exists. */
    private val hopSource: HopSource = RedirectProbeSource(),
    enterpriseOnboarding: EnterpriseOnboarding? = null,
    accountStore: AccountStore? = null,
    profileInstaller: EnterpriseProfileInstaller? = null,
    private val verdictStore: VerdictStore? = null,
    private val registrationCache: RegistrationCache? = null
) : ViewModel() {

    /**
     * The enterprise flow (accounts, sign-in sheet, account choice); null in
     * tests that don't wire it. Its steps show in the status card like any check.
     */
    val enterprise: EnterpriseController? =
        if (enterpriseOnboarding != null && accountStore != null && profileInstaller != null) EnterpriseController(
            viewModelScope, enterpriseOnboarding, accountStore, profileInstaller,
            onStep = { target, step -> enterpriseStep(target, step) },
            onResult = { recordEnterprise(it) },
            savedFor = { target, fix, area -> savedInputFor(target.ssid, fix, area.radiusMeters) },
            onRecordsFetched = { saveRealmRecords(it) }
        ) else null

    /**
     * The last verified map answer, kept across restarts (D21): what the
     * "Saved GECKO data" screen shows, and what lets an enterprise network be
     * joined without onboarding.
     */
    var savedLookup by mutableStateOf(registrationCache?.lookup())
        private set
    var savedRealmRecords by mutableStateOf(registrationCache?.realmRecords().orEmpty())
        private set

    /** Non-null while [registeredHere] shows the saved answer (no live one): when it was fetched. */
    var registrationsSavedAt by mutableStateOf<Long?>(null)
        private set

    /** SSID of the Wi-Fi the device itself is on (not the app's own temporary joins), or null. */
    var deviceSsid by mutableStateOf<String?>(null)
        private set

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
    ).also { e ->
        // A portal network blocks the map server until login: judge with the saved answer then (D25).
        e.savedAnswer = { lat, lng, radius, _ -> savedAnswer(savedLookup, lat, lng, radius, java.time.Instant.now()) }
    }

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

    /**
     * Radius actually used for a query: the user's setting, but never smaller
     * than [ACCURACY_TO_RADIUS] x the reported accuracy, capped at
     * [MAX_CHECK_RADIUS_METERS]. A radius narrower than the real uncertainty
     * can miss the device's true position, so the registration isn't returned
     * and the check goes silently UNVERIFIED (see docs/experiments.md, L3).
     * Checks refuse a fix that isn't [preciseEnough], so the cap only ever
     * applies to the setting.
     */
    private fun effectiveRadius(fix: LocationFix): Int =
        checkRadius(queryRadiusMeters, fix.accuracyMeters)

    /** Whether [fix] is precise enough for a check (see [MAX_CHECK_RADIUS_METERS]). */
    private fun preciseEnough(fix: LocationFix): Boolean = fix.accuracyMeters <= MAX_CHECK_ACCURACY_METERS

    var labMode by mutableStateOf(trustPreferences.labMode)
        private set

    fun updateLabMode(enabled: Boolean) {
        labMode = enabled
        trustPreferences.labMode = enabled
    }

    /** Whether the root-only login-server check is available (GECKO's system-level stand-in). */
    var rootAvailable by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) { rootAvailable = Capabilities.hasRoot }
        // Verdicts from earlier sessions: History and the status card know them after a restart.
        checks = verdictStore?.all().orEmpty().map { it.toCheckDetail() }
        dropExpiredLookup(java.time.Instant.now())
        runCatching {
            networkObserver.watchDeviceWifi { ssid ->
                viewModelScope.launch(Dispatchers.Main) {
                    val joined = ssid != null && !ssid.equals(deviceSsid, ignoreCase = true)
                    deviceSsid = ssid
                    enterprise?.refreshInstalled()
                    // Before the lookups below: a portal page may already be on its way to the user.
                    if (joined) checkDeviceJoin(ssid)
                    // A WiFi-only device can usually reach the map server once it is on a network (D26).
                    if (joined) {
                        // A new network may need a moment (DHCP, the AP's own portal setup): a few tries, until the
                        // saved answer is current here or the device moves on. Nothing is fetched if it already is.
                        for (wait in JOIN_LOOKUP_DELAYS_MS) {
                            delay(wait)
                            if (!ssid.equals(deviceSsid, ignoreCase = true)) break
                            if (savedAnswerIsCurrent()) break
                            ensureFresh()
                        }
                    }
                }
            }
        }
        // Also when the internet comes back otherwise, e.g. after a captive portal's login (D26).
        runCatching {
            networkObserver.watchInternet { viewModelScope.launch(Dispatchers.Main) { ensureFresh() } }
        }
    }

    /** The newest verdict GECKO has for [ssid], from this session or an earlier one. */
    fun latestVerdictFor(ssid: String): CheckDetail? =
        checks.firstOrNull { it.ssid.equals(ssid, ignoreCase = true) && !it.isDemo }

    /** Whether the device itself is connected to [ssid] (via its own settings, with the user's account). */
    fun isConnectedTo(ssid: String): Boolean = networkObserver.connectedSsid().equals(ssid, ignoreCase = true)

    /**
     * Root: checks the login server of the enterprise network the device is
     * connected to. Captures the next EAP handshake (the user reconnects while
     * this runs), then judges the server's chain against the registration
     * ([VerificationEngine.verifyEnterpriseChain]), with the realm of the
     * account the connection uses (from the supplicant, as the OS would know
     * it) deciding local user vs. visitor. Read-only: it observes the device's
     * own handshake and reports what the server presented; it doesn't change
     * or stop the connection.
     */
    fun checkLoginServer(network: ScannedNetwork.Real) {
        if (activeCheck != null) return
        val fix = location ?: return
        val networkKey = network.bssid ?: network.ssid
        val steps = listOf(CheckStep.SERVER, CheckStep.LOOKUP, CheckStep.COMPARE)
        step(network, steps, CheckStep.SERVER)
        checkJob = viewModelScope.launch {
            try {
                val source = Capabilities.certificateSource()
                val chain = source.captureHandshake(HANDSHAKE_CAPTURE_SECONDS)
                if (chain == null || !chain.isComplete) {
                    val reason = if (chain == null) "no EAP handshake seen within $HANDSHAKE_CAPTURE_SECONDS s"
                        else "the supplicant reported the server's chain without certificates (cert_in_cb not applied?)"
                    publish(network.ssid, network.bssid, networkKey,
                        VerificationResult(VerificationState.UNREACHABLE, chain?.subjects?.firstOrNull().orEmpty(), reason = reason),
                        fix, modeDescription = "Your connection: login server", failure = CheckFailure.SERVER_UNREADABLE,
                        isEnterprise = true)
                    return@launch
                }
                step(network, steps, CheckStep.LOOKUP)
                rebindGeckoClient()
                step(network, steps, CheckStep.COMPARE)
                val result = engine.verifyEnterpriseChain(
                    networkKey, chain.certificates, source.accountRealm(),
                    fix.latitude, fix.longitude, null, effectiveRadius(fix),
                    ssid = network.ssid, observedAuthModes = network.authModes
                )
                publish(network.ssid, network.bssid, networkKey, result, fix,
                    modeDescription = "Your connection: login server compared", isEnterprise = true)
            } finally {
                activeCheck = null
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        hasLocationPermission = granted
        if (granted) ensureFresh()
    }

    /**
     * An update asked for while one ran; it runs when that one ends, so e.g. a
     * join isn't lost. True if any of them was a [refresh] (a lookup in any case).
     */
    private var queuedForce: Boolean? = null

    /**
     * Re-scans nearby networks and looks the place up again, whatever the saved
     * answer says: the refresh button. [userInitiated]: the user asked, so a
     * lookup that fails is said, not only shown in the list.
     */
    fun refresh(userInitiated: Boolean = false) = update(force = true, userInitiated = userInitiated)

    /**
     * Re-scans nearby networks and keeps the saved answer useful (D26): looks
     * the place up only if [savedAnswerUpkeep] says so, confirms an aging answer
     * against the server's map head, and otherwise shows the saved answer. What
     * every automatic update calls: opening the app, the periodic check,
     * joining a network, regaining internet.
     */
    fun ensureFresh() = update(force = false, userInitiated = false)

    /**
     * Not while a check runs: a lookup rebinds the map client to the network the
     * check holds.
     */
    private fun update(force: Boolean, userInitiated: Boolean) {
        if (!hasLocationPermission || activeCheck != null) return
        if (isRefreshing) {
            queuedForce = force || queuedForce == true
            return
        }
        isRefreshing = true
        scannedNetworks = currentScanList()
        networkObserver.requestScan { scannedNetworks = currentScanList() }
        viewModelScope.launch {
            try {
                // lastKnown() alone returns null until some component has actively
                // requested a location update at least once - a real gap on a cold
                // emulator/device even after `adb emu geo fix`, confirmed via
                // `adb shell dumpsys location` showing every provider's
                // "last location=null". awaitFix() actively requests one instead
                // of only reading the (possibly never-populated) passive cache.
                val fix = locationProvider.lastKnown() ?: locationProvider.awaitFix()
                location = fix
                if (fix != null) {
                    val now = java.time.Instant.now()
                    dropExpiredLookup(now)
                    val upkeep = if (force) SavedAnswerUpkeep.FETCH
                        else savedAnswerUpkeep(savedLookup, fix.latitude, fix.longitude, MAX_CHECK_RADIUS_METERS, now)
                    android.util.Log.i("GeckoLookup", "upkeep ${if (force) "FETCH (asked)" else upkeep}")
                    when (upkeep) {
                        SavedAnswerUpkeep.FETCH -> lookUpSavedArea(fix, userInitiated)
                        SavedAnswerUpkeep.CONFIRM -> confirmSavedAnswer(fix)
                        SavedAnswerUpkeep.NONE -> showSaved(fix)
                    }
                } else if (userInitiated) {
                    showNotice("No location yet, so GECKO can't look up what's registered here.")
                }
            } finally {
                isRefreshing = false
                queuedForce?.let { queued ->
                    queuedForce = null
                    update(force = queued, userInitiated = false)
                }
            }
        }
    }

    /** Looks up the whole saved area around [fix] and saves the answer (D26). */
    private suspend fun lookUpSavedArea(fix: LocationFix, userInitiated: Boolean) {
        rebindGeckoClient()
        val lookup = engine.registeredHere(
            lat = fix.latitude,
            lng = fix.longitude,
            // Not fix.altitude - see the doc comment below on why this
            // query deliberately never narrows by device altitude.
            altitude = null,
            // The whole saved area (D26); applyLookup narrows the list to the device.
            radiusMeters = SAVED_AREA_RADIUS_METERS
        )
        applyLookup(lookup, fix, viaSsid = deviceSsid, radiusMeters = SAVED_AREA_RADIUS_METERS)
        if (userInitiated) lookupFailureNotice(lookup.status)?.let(::showNotice)
    }

    /**
     * Asks the server for its signed map head (a few hundred bytes): unchanged
     * means the saved answer is still exactly current, so it is marked confirmed
     * instead of downloaded again; changed means a new lookup. Unreachable: the
     * saved answer stays as it is, still usable until it expires.
     */
    private suspend fun confirmSavedAnswer(fix: LocationFix) {
        val saved = savedLookup ?: return lookUpSavedArea(fix, userInitiated = false)
        rebindGeckoClient()
        when (val head = engine.gecko.currentMapHead()) {
            // The root hash, not the timestamp: only the root hash is covered by the signature.
            is MapHeadAnswer.Current -> if (saved.mapRootHash != null && head.rootHashBase64 == saved.mapRootHash) {
                val confirmed = saved.copy(confirmedAt = System.currentTimeMillis())
                registrationCache?.saveLookup(confirmed)
                savedLookup = confirmed
                android.util.Log.i("GeckoLookup", "map unchanged (root ${saved.mapRootHash}): saved answer confirmed")
                showSaved(fix)
            } else {
                lookUpSavedArea(fix, userInitiated = false)
            }
            is MapHeadAnswer.Unreachable -> showSaved(fix)
            is MapHeadAnswer.ProofFailure -> {
                android.util.Log.w("GeckoLookup", "map head: ${head.reason}")
                showSaved(fix)
            }
        }
    }

    /** Whether the saved answer covers a check here and needs nothing (see [savedAnswerUpkeep]). */
    private fun savedAnswerIsCurrent(): Boolean {
        val fix = location ?: return false
        return savedAnswerUpkeep(savedLookup, fix.latitude, fix.longitude, MAX_CHECK_RADIUS_METERS,
            java.time.Instant.now()) == SavedAnswerUpkeep.NONE
    }

    /**
     * Keeps the saved answer useful while the app is visible (D26): checks every
     * [UPKEEP_CHECK_INTERVAL_MS] whether it still covers the device and is
     * recent, and looks up only when it doesn't. Runs until cancelled; the UI
     * ties it to the started lifecycle, so nothing runs (and no location is
     * asked for) in the background.
     */
    suspend fun keepRegistrationsFresh() {
        while (true) {
            ensureFresh()
            delay(UPKEEP_CHECK_INTERVAL_MS)
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
     * that network presents; [probeOver] is that network when the device joined
     * it itself ([checkDeviceJoin]).
     */
    private fun rebindGeckoClient(probeOver: android.net.Network? = networkObserver.joinedNetwork) {
        val joined = networkObserver.joinedNetwork
        val cellular = networkObserver.cellularNetwork()
        // Without a join of its own, the device's Wi-Fi: bound, it works right after joining,
        // before Android makes a network with a captive portal the default route.
        val wifi = joined ?: networkObserver.deviceWifiNetwork
        boundRoute = when {
            cellular != null -> QueryRoute.MOBILE_DATA
            wifi != null -> QueryRoute.JOINED_WIFI
            else -> QueryRoute.DEFAULT_ROUTE
        }
        engine.gecko = GeckoClient(baseUrl = SERVER_URL, network = cellular ?: wifi)
        certProbe.network = probeOver
    }

    /** What [rebindGeckoClient] bound the map-server client to; read back when a check is published. */
    private var boundRoute = QueryRoute.DEFAULT_ROUTE

    /**
     * What tapping a network does (docs/eduroam-decisions.md, D20): open
     * networks are joined and checked, enterprise networks GECKO can onboard on
     * go through the enterprise flow, everything else is Android's to join.
     */
    enum class TapAction { CHECK, ENTERPRISE, ANDROID_SETTINGS }

    fun tapActionFor(network: ScannedNetwork): TapAction = when {
        network !is ScannedNetwork.Real -> TapAction.CHECK
        network.isOpen -> TapAction.CHECK
        network.isEnterprise && enterprise != null &&
            enterpriseAccessFor(network) in setOf(EnterpriseAccess.SIGN_IN, EnterpriseAccess.NEEDS_LOOKUP) -> TapAction.ENTERPRISE
        else -> TapAction.ANDROID_SETTINGS
    }

    /**
     * Why GECKO can or can't sign in to an enterprise network here, for the
     * row's second line and the tap: [SIGN_IN] where it can onboard or holds a
     * usable saved registration (D21); [NEEDS_LOOKUP] when the SSID is
     * registered here but the saved answer can't be used, so a new lookup
     * would help; [NOT_REGISTERED] when the map's answer has nothing for it;
     * [UNKNOWN] when there is no answer to go by.
     */
    enum class EnterpriseAccess { SIGN_IN, NEEDS_LOOKUP, NOT_REGISTERED, UNKNOWN }

    fun enterpriseAccessFor(network: ScannedNetwork.Real): EnterpriseAccess = when {
        enterprise?.supports(network.ssid) == true || hasUsableSavedRegistration(network.ssid) -> EnterpriseAccess.SIGN_IN
        registeredHere.any { it.wifi.ssid.equals(network.ssid, ignoreCase = true) && it.wifi.enterprise != null } ->
            EnterpriseAccess.NEEDS_LOOKUP
        certLookup == LookupStatus.OK -> EnterpriseAccess.NOT_REGISTERED
        else -> EnterpriseAccess.UNKNOWN
    }

    /** Whether a fresh saved registration lets GECKO sign in to [ssid] here without onboarding (D21). */
    fun hasUsableSavedRegistration(ssid: String): Boolean {
        val fix = location ?: return false
        return savedInputFor(ssid, fix, effectiveRadius(fix)) != null
    }

    private fun savedInputFor(ssid: String, fix: LocationFix, radiusMeters: Int): SavedInput? {
        val saved = savedLookup ?: return null
        val certificates = usableSavedRegistration(saved, ssid, fix.latitude, fix.longitude, radiusMeters, java.time.Instant.now())
            ?: return null
        val records = savedRealmRecords
        return SavedInput(certificates, DataSource.Saved(saved.fetchedAt, saved.viaDescription())) { federation, realm ->
            records.firstOrNull { it.federation.equals(federation, true) && it.realm.equals(realm, true) }
                ?.let { java.util.Base64.getDecoder().decode(it.signedBase64) }
        }
    }

    private fun saveRealmRecords(records: Map<Pair<String, String>, ByteArray>) {
        val cache = registrationCache ?: return
        val now = System.currentTimeMillis()
        records.forEach { (key, bytes) ->
            cache.saveRealmRecord(SavedRealmRecord(key.first, key.second, now,
                java.util.Base64.getEncoder().encodeToString(bytes), via = "GECKO onboarding"))
        }
        savedRealmRecords = cache.realmRecords()
    }

    /**
     * Shows [lookup] and saves it when it is a verified answer. When the map
     * server isn't reachable (common on a WiFi-only device before joining), the
     * saved answer stands in, marked as saved, if it is recent and covers this
     * place. A failed proof is never papered over with saved data.
     *
     * [radiusMeters] must be the radius [lookup] was made with: the saved answer
     * is only used where that circle covers the check (appliesAt). Recording a
     * wider one would let a 50 m answer stand in 200 m away, where it lacks the
     * registrations, and the check would go silently UNVERIFIED.
     */
    private fun applyLookup(lookup: LocationLookup, fix: LocationFix, viaSsid: String?, radiusMeters: Int) {
        android.util.Log.i("GeckoLookup", "${lookup.status}, ${lookup.certificates.size} certificates, route $boundRoute, via $viaSsid${lookup.unreachableCause?.let { ": $it" }.orEmpty()}")
        if (lookup.status == LookupStatus.OK) {
            // The answer covers the whole queried area; the list shows what reaches the device.
            registeredHere = lookup.certificates.filter { it.coversLocation(fix.latitude, fix.longitude, effectiveRadius(fix)) }
            certLookup = LookupStatus.OK
            registrationsSavedAt = null
            val saved = SavedLookup(
                fetchedAt = System.currentTimeMillis(), lat = fix.latitude, lng = fix.longitude,
                radiusMeters = radiusMeters, route = boundRoute.label(),
                viaSsid = viaSsid.takeIf { boundRoute == QueryRoute.JOINED_WIFI || boundRoute == QueryRoute.DEFAULT_ROUTE },
                mapHeadMillis = lookup.mapHeadMillis, mapRootHash = lookup.mapRootHash, certificates = lookup.certificates
            )
            registrationCache?.saveLookup(saved)
            savedLookup = saved
            return
        }
        val now = java.time.Instant.now()
        dropExpiredLookup(now)
        val saved = savedLookup
        // Only where the saved answer is complete (D26): elsewhere an empty list would claim "nothing registered".
        if (lookup.status == LookupStatus.UNREACHABLE && saved != null && showSaved(fix)) return
        registeredHere = lookup.certificates
        certLookup = lookup.status
        registrationsSavedAt = null
    }

    /**
     * Shows the saved answer in the network list, marked as saved, if it
     * [applies][appliesAt] here; false (and nothing changed) if it doesn't.
     */
    private fun showSaved(fix: LocationFix): Boolean {
        val now = java.time.Instant.now()
        val saved = savedLookup?.takeIf { it.appliesAt(fix.latitude, fix.longitude, effectiveRadius(fix), now) } ?: return false
        registeredHere = saved.certificates.filter { it.isValidAt(now) && it.coversLocation(fix.latitude, fix.longitude, effectiveRadius(fix)) }
        certLookup = LookupStatus.OK
        registrationsSavedAt = saved.fetchedAt
        return true
    }

    /** What to tell the user after a refresh they asked for didn't get a live answer; null if it did. */
    private fun lookupFailureNotice(status: LookupStatus): String? = when {
        status == LookupStatus.OK -> null
        status == LookupStatus.UNTRUSTED -> "The map server's answer failed verification. GECKO ignored it."
        registrationsSavedAt != null -> "Couldn't reach the map server. Showing saved registrations from ${timeText(registrationsSavedAt!!)}."
        else -> "Couldn't reach the map server, so GECKO doesn't know what's registered here."
    }

    /** Deletes the saved answer once it is too old to be used anywhere (D26); realm records expire on their own. */
    private fun dropExpiredLookup(now: java.time.Instant) {
        if (savedLookup?.isExpired(now) == true) {
            registrationCache?.clearLookup()
            savedLookup = null
        }
    }

    /** Metres between the device and where the saved answer was fetched, or null without either. */
    fun savedLookupDistanceMeters(): Double? {
        val saved = savedLookup ?: return null
        val fix = location ?: return null
        return distanceMeters(saved.lat, saved.lng, fix.latitude, fix.longitude)
    }

    /** Whether the saved answer [applies][appliesAt] where the device is now; null without a location. */
    fun savedLookupAppliesHere(): Boolean? {
        val saved = savedLookup ?: return false
        val fix = location ?: return null
        return saved.appliesAt(fix.latitude, fix.longitude, effectiveRadius(fix), java.time.Instant.now())
    }

    /**
     * Forgets everything GECKO saved (D21): registrations, realm records,
     * verdicts and History. Accounts and the Wi-Fi profiles GECKO installed stay.
     */
    fun clearSavedData() {
        registrationCache?.clear()
        verdictStore?.clear()
        historyStore.clear()
        decisionCache.invalidateAll()
        savedLookup = null
        savedRealmRecords = emptyList()
        registrationsSavedAt = null
        registeredHere = emptyList()
        certLookup = null
        checks = emptyList()
        current = null
    }

    /** The one entry point for the UI: joins and checks [network]. */
    fun startCheck(network: ScannedNetwork) {
        if (activeCheck != null) return
        // Joining can't work with Wi-Fi off: say so instead of a "Couldn't join" result. Demo entries don't join.
        if (network is ScannedNetwork.Real && !isWifiEnabled()) {
            showNotice("Wi-Fi is off. Turn it on, then tap ${network.ssid} again.")
            return
        }
        checkJob = when {
            network is ScannedNetwork.Real && tapActionFor(network) == TapAction.ENTERPRISE -> { startEnterprise(network); null }
            network is ScannedNetwork.Real -> checkReal(network)
            else -> checkFake(network)
        }
    }

    /** Stops a running check (e.g. a join stuck waiting for the system dialog) and drops the connection. */
    fun cancelCheck() {
        checkJob?.cancel()
        checkJob = null
        enterprise?.cancel()
        activeCheck = null
        networkObserver.leave()
    }

    private fun startEnterprise(network: ScannedNetwork.Real) {
        val controller = enterprise ?: return
        val fix = location
        if (fix == null) {
            pendingNotice = "Waiting for your location. GECKO needs it to look up the network's registration."
            return
        }
        if (!preciseEnough(fix)) {
            publish(network.ssid, network.bssid, network.bssid ?: network.ssid,
                VerificationResult(VerificationState.UNREACHABLE, network.ssid,
                    reason = "location too imprecise (±${fix.accuracyMeters.toInt()} m)"),
                fix, failure = CheckFailure.LOW_ACCURACY, isEnterprise = true)
            return
        }
        // Registered, but nothing usable to sign in with: say how to get it instead of recording a failed check.
        if (enterpriseAccessFor(network) == EnterpriseAccess.NEEDS_LOOKUP) {
            showNotice("GECKO has no recent registration for ${network.ssid} here. " +
                "Join a network with internet so GECKO can look it up, then tap ${network.ssid} again.")
            return
        }
        // Android serves one app-requested network at a time; a portal check may still hold one.
        networkObserver.leave()
        controller.start(EnterpriseTarget(network.ssid, network.bssid, network.authModes), fix,
            QueryArea(fix.latitude, fix.longitude, effectiveRadius(fix)))
    }

    private fun enterpriseStep(target: EnterpriseTarget, step: EnterpriseStep?) {
        activeCheck = step?.let {
            ActiveCheck(target.ssid, target.bssid, listOf(CheckStep.ONBOARD, CheckStep.CONFIRM, CheckStep.CONNECT), when (it) {
                EnterpriseStep.CHECK_NETWORK -> CheckStep.ONBOARD
                EnterpriseStep.CONFIRM_SERVER -> CheckStep.CONFIRM
                EnterpriseStep.CONNECT -> CheckStep.CONNECT
            })
        }
    }

    /** A one-line message for the home screen (e.g. why a tap didn't start anything); the UI clears it. */
    var pendingNotice by mutableStateOf<String?>(null)
        private set

    fun showNotice(text: String) {
        pendingNotice = text
    }

    fun noticeShown() {
        pendingNotice = null
    }

    fun isWifiEnabled(): Boolean = networkObserver.isWifiEnabled()

    /** Whether GECKO installed a pinned profile for [ssid] that is still there. */
    fun isSetUpByGecko(ssid: String): Boolean = enterprise?.isInstalled(ssid) == true

    /** Removes the pinned profile GECKO installed for [ssid]; the status card goes back to idle. */
    fun forgetEnterpriseNetwork(ssid: String) {
        enterprise?.forgetNetwork(ssid)
        if (current?.ssid.equals(ssid, ignoreCase = true)) current = null
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

    /** "Try again" on a check that failed for a technical reason: the same network, if it is still in range. */
    fun retry(check: CheckDetail) {
        val real = scannedNetworks.filterIsInstance<ScannedNetwork.Real>().filter { it.ssid == check.ssid }
        val network = real.firstOrNull { it.bssid == check.bssid } ?: real.firstOrNull()
        if (network == null) {
            showNotice("${check.ssid ?: "The network"} is out of range")
            return
        }
        current = null
        startCheck(network)
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
                        radiusMeters = effectiveRadius(fix), ssid = fake.ssid
                    )
                    is ScannedNetwork.FakeEduroam -> engine.verifyEnterprise(
                        networkKey = "fake:${fake.ssid}:${fake.label}",
                        observed = fake.observed,
                        userRealm = fake.userRealm,
                        lat = fix.latitude, lng = fix.longitude, altitude = null,
                        radiusMeters = effectiveRadius(fix), ssid = fake.ssid,
                        observedAuthModes = fake.authModes
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

    /**
     * How [network]'s advertised security type fits its registration, from the
     * last lookup (proof-checked when it was fetched; a check queries again).
     * Null when the SSID isn't registered here or the lookup isn't known.
     */
    fun advertisedMatch(network: ScannedNetwork.Real): AdvertisedMatch? =
        if (certLookup == LookupStatus.OK) matchAdvertised(network.ssid, network.authModes, registeredHere) else null

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
        val steps = listOf(CheckStep.JOIN, CheckStep.LOOKUP, CheckStep.PORTAL, CheckStep.COMPARE)

        if (!preciseEnough(fix)) {
            val result = VerificationResult(VerificationState.UNREACHABLE, "",
                reason = "location too imprecise (±${fix.accuracyMeters.toInt()} m); a reliable check needs it within ${MAX_CHECK_ACCURACY_METERS.toInt()} m")
            publish(network.ssid, network.bssid, networkKey, result, fix, failure = CheckFailure.LOW_ACCURACY)
            return null
        }

        // A secured network can't be joined without credentials: the list judges it from its scan
        // ([advertisedMatch]), and the portal is checked once the device joins it ([checkDeviceJoin]).
        if (!network.isOpen) return null

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
                // A cached verdict must never survive a (re)join: the BSSID a key is
                // keyed on can be spoofed, so an attacker taking over that BSSID would
                // otherwise inherit an earlier VERIFIED (see docs/acceptance-criteria.md, AC22).
                engine.onNetworkChanged(networkKey)
                step(network, steps, CheckStep.LOOKUP)
                rebindGeckoClient()
                // The startup query ran before any network was joined; redo it now the server is reachable.
                val radius = effectiveRadius(fix)
                engine.registeredHere(fix.latitude, fix.longitude, null, radius).let {
                    applyLookup(it, fix, viaSsid = network.ssid, radiusMeters = radius)
                }
                judgeJoined(joined, network, networkKey, fix, steps, ownJoin = true)
            } finally {
                activeCheck = null
            }
        }
    }

    /**
     * Checks a password network the device joined itself, through Android
     * (GECKO can't join it without the password): whether it shows a captive
     * portal, and whether that portal is the registered one. A fake network
     * that knows the password steals data through its login page, so that is
     * what is compared; the security type was already judged in the list.
     *
     * Only for SSIDs registered here (live or saved answer), so an
     * unregistered network such as a travel router is left alone. Runs right
     * after the join, without root: Android's own sign-in notification for
     * the portal can still appear before the verdict.
     */
    private fun checkDeviceJoin(ssid: String) {
        if (activeCheck != null) return
        val over = networkObserver.deviceWifiNetwork ?: return
        val fix = location?.takeIf { preciseEnough(it) } ?: return
        val bssid = networkObserver.connectedBssid()
        val candidates = scannedNetworks.filterIsInstance<ScannedNetwork.Real>().filter { it.ssid.equals(ssid, ignoreCase = true) }
        val network = candidates.firstOrNull { it.bssid.equals(bssid, ignoreCase = true) } ?: candidates.firstOrNull() ?: return
        // Open networks are joined and checked by GECKO itself; enterprise networks have their own flow.
        if (network.isOpen || network.isEnterprise) return
        if (registeredHere.none { it.wifi.ssid.equals(ssid, ignoreCase = true) }) return

        val target = network.copy(bssid = bssid ?: network.bssid)
        val steps = listOf(CheckStep.PORTAL, CheckStep.COMPARE)
        step(target, steps, CheckStep.PORTAL)
        checkJob = viewModelScope.launch {
            try {
                engine.onNetworkChanged(target.bssid ?: ssid)
                rebindGeckoClient(probeOver = over)
                judgeJoined(over, target, target.bssid ?: ssid, fix, steps, ownJoin = false)
            } finally {
                certProbe.network = networkObserver.joinedNetwork
                activeCheck = null
            }
        }
    }

    /**
     * Follows [over]'s redirects and judges them against the registration
     * ([VerificationEngine.verifyPortalHops]). If no portal appears (HTTP 204:
     * no portal, or already logged in), the domain registered for the SSID is
     * probed instead, so a logged-in network is still checked. [ownJoin]: the
     * app joined [over] itself and holds the connection; false when the device
     * did ([checkDeviceJoin]).
     */
    private suspend fun judgeJoined(
        over: android.net.Network, network: ScannedNetwork.Real, networkKey: String, fix: LocationFix,
        steps: List<CheckStep>, ownJoin: Boolean
    ) {
        step(network, steps, CheckStep.PORTAL)
        val discovery = hopSource.discover(over)
        step(network, steps, CheckStep.COMPARE)
        when (val outcome = discovery.outcome) {
            Discovery.Outcome.PortalReached -> {
                val check = engine.verifyPortalHops(
                    networkKey, discovery.hops, judgeFinalPlainHop = true,
                    fix.latitude, fix.longitude, null, effectiveRadius(fix), network.ssid,
                    observedAuthModes = network.authModes
                )
                finishCheck(network, networkKey, fix, check.overall, "discovery", outcome, check.hops, ownJoin = ownJoin)
            }
            Discovery.Outcome.NoPortal -> {
                // No portal in the way: check the network's registered portal domain directly.
                val registered = registeredDomainFor(network.ssid)
                val ssidRegistered = registeredHere.any { it.wifi.ssid.equals(network.ssid, ignoreCase = true) }
                val result = if (registered == null && ssidRegistered) {
                    // Registered without a login page: only the security type can be compared.
                    engine.checkAdvertised(networkKey, network.ssid, network.authModes,
                        fix.latitude, fix.longitude, null, effectiveRadius(fix))
                } else if (registered == null) {
                    VerificationResult(VerificationState.UNVERIFIED, "",
                        reason = "no captive portal seen, and nothing registered here lists a portal domain for ${network.ssid}")
                } else {
                    verifyDomain(network, networkKey, registered, fix).let {
                        it.copy(reason = "no captive portal seen (already logged in?) - checked registered $registered: ${it.reason}")
                    }
                }
                finishCheck(network, networkKey, fix, result, "discovery", outcome,
                    discovery.hops.map { HopVerdict(it, null) }, ownJoin = ownJoin)
            }
            is Discovery.Outcome.Failed -> {
                // Judge what was seen: a key mismatch before the failure is still a CONFLICT.
                val partial = engine.verifyPortalHops(
                    networkKey, discovery.hops, judgeFinalPlainHop = false,
                    fix.latitude, fix.longitude, null, effectiveRadius(fix), network.ssid,
                    observedAuthModes = network.authModes
                )
                val conflict = partial.overall.state == VerificationState.CONFLICT
                val result = if (conflict) partial.overall
                else VerificationResult(VerificationState.UNREACHABLE, discovery.hops.lastOrNull()?.host.orEmpty(),
                    reason = "portal discovery failed: ${outcome.reason}")
                finishCheck(network, networkKey, fix, result, "discovery", outcome, partial.hops,
                    failure = if (conflict) null else CheckFailure.PORTAL_FAILED, ownJoin = ownJoin)
            }
        }
    }

    private suspend fun verifyDomain(
        network: ScannedNetwork.Real, networkKey: String, host: String, fix: LocationFix
    ): VerificationResult = engine.verify(
        networkKey = networkKey,
        host = host,
        lat = fix.latitude, lng = fix.longitude, altitude = null,
        radiusMeters = effectiveRadius(fix), ssid = network.ssid,
        observedAuthModes = network.authModes
    )

    private fun finishCheck(
        network: ScannedNetwork.Real,
        networkKey: String,
        fix: LocationFix,
        result: VerificationResult,
        mode: String,
        outcome: Discovery.Outcome? = null,
        hops: List<HopVerdict> = emptyList(),
        failure: CheckFailure? = null,
        ownJoin: Boolean = true
    ) {
        checkLog?.append(
            PortalCheckLog.CheckEntry(
                runId = UUID.randomUUID().toString(),
                time = PortalCheckLog.isoTime(result.timestamp),
                ssid = network.ssid, bssid = network.bssid, networkKey = networkKey,
                mode = if (ownJoin) mode else "$mode-device-join",
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
        }?.let { if (ownJoin) it else "Your connection: ${it.replaceFirstChar(Char::lowercase)}" }
        publish(network.ssid, network.bssid, networkKey, result, fix, hops, modeDescription,
            joined = ownJoin && failure != CheckFailure.JOIN_FAILED, failure = failure)
    }

    /**
     * Records an enterprise connection attempt (docs/eduroam-decisions.md, D19,
     * D20) like any other check: status card, History, warning on attack signs.
     * The connection is the device's own (a suggestion), so `joined = false`.
     */
    private fun recordEnterprise(run: EnterpriseRun) {
        val decision = run.decision
        val pins = (decision as? Decision.Proceed)?.pins
        val site = run.report.siteResult
        val (state, failure, reason) = when {
            decision is Decision.Block && decision.hostile -> Triple(VerificationState.CONFLICT, null, decision.reason)
            decision is Decision.Block -> Triple(VerificationState.UNREACHABLE,
                if (run.report.onboardingFailure != null) CheckFailure.ONBOARDING_FAILED else CheckFailure.SERVER_NOT_CONFIRMED,
                decision.reason)
            run.refused != null -> Triple(VerificationState.UNREACHABLE, CheckFailure.SERVER_NOT_CONFIRMED, run.refused)
            run.connected -> Triple(VerificationState.VERIFIED, null,
                "connected as ${run.account?.identity}; the login server proved it is ${pins?.serverNames?.joinToString()} " +
                    "before the password was sent (${(decision as Decision.Proceed).why})")
            run.installFailure != null -> Triple(VerificationState.REGISTERED, null, run.installFailure)
            else -> {
                // Android doesn't leave a working network for a suggestion by itself (D27).
                val stayedOn = deviceSsid?.takeUnless { it.equals(run.target.ssid, ignoreCase = true) }
                if (stayedOn != null) Triple(VerificationState.REGISTERED, CheckFailure.ANDROID_STAYED,
                    "pinned profile installed; Android stayed on $stayedOn")
                else Triple(VerificationState.REGISTERED, null, "pinned profile installed; Android hasn't joined it yet")
            }
        }
        val result = VerificationResult(
            state, run.target.ssid,
            matchedIdentifier = pins?.serverNames?.firstOrNull(),
            matchedCertificateId = site?.matchedCertificateId,
            reason = reason,
            certificateCount = site?.certificateCount,
            unparsedCount = site?.unparsedCount,
            usedPreferredNetwork = site?.usedPreferredNetwork
        )
        val evidence = EnterpriseEvidence(
            account = run.account?.identity,
            federation = run.report.site?.federation,
            operatorName = run.report.site?.operatorName,
            pinsFrom = when (val source = pins?.source) {
                is PinSource.RealmRecord -> "Signed record of ${source.displayName ?: pins.realm} in ${pins.federation}"
                is PinSource.SiteCertificate -> "This network's registration (${source.certificateId})"
                null -> null
            },
            serverNames = pins?.serverNames.orEmpty(),
            caSubjects = pins?.caCertificates.orEmpty().map { it.subjectX500Principal.name },
            onboardingFailure = run.report.onboardingFailure,
            accounts = run.report.checks.map {
                "${it.match.account.identity}: " + when (it) {
                    is PinCheck.Pinned -> "server confirmed (" + (if (it.pins.source is PinSource.RealmRecord) "${it.pins.federation} record" else "registration") + ")"
                    is PinCheck.Rejected -> "rejected, ${it.reason}"
                    is PinCheck.Missing -> "not confirmed, ${it.reason}"
                }
            },
            timings = run.report.timings.map { it.first.name to it.second }
        )
        val source = when (val src = run.report.source) {
            DataSource.Onboarding -> "Fetched live over GECKO's check connection to ${run.target.ssid}"
            is DataSource.Saved -> "Saved registration from ${timeText(src.fetchedAt)}, fetched over ${src.via}"
        }
        publish(run.target.ssid, run.target.bssid, run.target.bssid ?: run.target.ssid, result, run.fix,
            modeDescription = when (run.report.source) {
                DataSource.Onboarding -> "Enterprise network: GECKO onboarding, then a profile pinned to the verified login server"
                is DataSource.Saved -> "Enterprise network: saved registration (no onboarding), then a profile pinned to the verified login server"
            },
            failure = failure, isEnterprise = true, enterprise = evidence,
            queryRoute = if (site?.certificateCount != null) QueryRoute.JOINED_WIFI else null,
            dataSource = source)
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
        isEnterprise: Boolean = false,
        enterprise: EnterpriseEvidence? = null,
        queryRoute: QueryRoute? = null,
        dataSource: String? = null
    ) {
        historyStore.record(networkKey, ssid, result, fix.latitude, fix.longitude)
        val detail = CheckDetail(
            result = result,
            ssid = ssid,
            bssid = bssid,
            lat = fix.latitude,
            lng = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            radiusMeters = effectiveRadius(fix),
            altitude = fix.altitude,
            hops = hops,
            modeDescription = modeDescription,
            joined = joined,
            failure = failure,
            isDemo = isDemo,
            isEnterprise = isEnterprise,
            // usedPreferredNetwork = "the bound network served it", not "cellular".
            queryRoute = queryRoute ?: when (result.usedPreferredNetwork) {
                true -> boundRoute
                false -> QueryRoute.DEFAULT_ROUTE
                null -> null
            },
            enterprise = enterprise,
            dataSource = dataSource ?: result.savedAt?.let {
                "Saved registration from ${timeText(it)}" + (savedLookup?.let { s -> ", fetched over ${s.viaDescription()}" } ?: "") +
                    ". The map server wasn't reachable over this network"
            } ?: result.certificateCount?.let {
                "Fetched live at ${timeText(result.timestamp)} over ${boundRoute.label()}" +
                    (ssid?.takeIf { boundRoute == QueryRoute.JOINED_WIFI }?.let { " ($it)" } ?: "")
            }
        )
        if (ssid != null && !isDemo) verdictStore?.put(detail.toStoredVerdict())
        checks = listOf(detail) + checks
        current = detail
        if (result.state == VerificationState.CONFLICT) pendingWarning = detail.id
    }

    override fun onCleared() {
        networkObserver.leave()
    }
}

private fun CheckDetail.toStoredVerdict() = StoredVerdict(
    ssid = ssid.orEmpty(), bssid = bssid, state = result.state.name, reason = result.reason,
    timestamp = result.timestamp, lat = lat, lng = lng, isEnterprise = isEnterprise,
    modeDescription = modeDescription, failure = failure?.name,
    account = enterprise?.account, serverNames = enterprise?.serverNames.orEmpty()
)

private fun StoredVerdict.toCheckDetail() = CheckDetail(
    result = VerificationResult(
        runCatching { VerificationState.valueOf(state) }.getOrDefault(VerificationState.UNVERIFIED),
        ssid, reason = reason, timestamp = timestamp
    ),
    ssid = ssid, bssid = bssid, lat = lat, lng = lng, accuracyMeters = null, radiusMeters = 0, altitude = null,
    modeDescription = modeDescription,
    failure = failure?.let { runCatching { CheckFailure.valueOf(it) }.getOrNull() },
    isEnterprise = isEnterprise,
    enterprise = if (isEnterprise) EnterpriseEvidence(account = account, serverNames = serverNames) else null,
    restored = true,
    dataSource = "Checked ${timeText(timestamp)} (saved verdict from an earlier session)"
)

/** How a query went out, in words. */
fun QueryRoute.label(): String = when (this) {
    QueryRoute.MOBILE_DATA -> "Mobile data"
    QueryRoute.JOINED_WIFI -> "Wi-Fi"
    QueryRoute.DEFAULT_ROUTE -> "Default connection"
}

/** "Wi-Fi (GeckoTest)" / "Mobile data". */
fun SavedLookup.viaDescription(): String = route + (viaSsid?.let { " ($it)" } ?: "")

/** Local time of day, with the date when it isn't today. */
fun timeText(epochMillis: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val pattern = if (at.toLocalDate() == today) "HH:mm" else "d MMM, HH:mm"
    return at.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}
