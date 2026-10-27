package com.thesis.geckowifi.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "NetworkObserver"

class NetworkObserver(private val context: Context) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Held for as long as the app keeps the joined WiFi up - unregistering it drops the connection. */
    private var joinCallback: ConnectivityManager.NetworkCallback? = null

    /** The WiFi network the app itself joined via [join], while it is still up. */
    @Volatile var joinedNetwork: Network? = null
        private set

    /**
     * The Wi-Fi network the device itself is on (see [watchDeviceWifi]), or null. Binding to it reaches
     * the network right after joining, while Android is still evaluating it (e.g. a captive portal)
     * and hasn't made it the default route yet.
     */
    @Volatile var deviceWifiNetwork: Network? = null
        private set

    /**
     * The last cached WiFi scan (not a fresh one - reading cached results
     * avoids Android's per-app throttling on requesting new scans, which
     * only gates `startScan()`, not reading what's already there).
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun scanResults(): List<ScannedNetwork.Real> =
        runCatching { wifi.scanResults }.getOrDefault(emptyList()).map {
            ScannedNetwork.Real(
                ssid = it.SSID?.takeIf { s -> s.isNotBlank() } ?: "(hidden)",
                bssid = it.BSSID,
                capabilities = it.capabilities ?: "",
                frequencyMhz = it.frequency,
                rssi = it.level
            )
        }

    /**
     * Asks for a fresh scan and calls [onResults] (main thread) once it
     * finishes - or immediately if the request was refused, e.g. by
     * Android's foreground throttling (4 scans / 2 min), in which case
     * [scanResults] just returns the cached list again.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // startScan() is deprecated but still the only way to ask for a scan.
    fun requestScan(onResults: () -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                runCatching { context.unregisterReceiver(this) }
                onResults()
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val started = runCatching { wifi.startScan() }.getOrDefault(false)
        if (!started) {
            Log.w(TAG, "startScan() refused (throttled?), using cached results")
            runCatching { context.unregisterReceiver(receiver) }
            onResults()
        }
    }

    /**
     * Joins [ssid] (open networks only - the lab APs have no passphrase) via
     * `WifiNetworkSpecifier`, pinned to [bssid] when given so an A-vs-B test
     * hits the intended AP rather than whichever has the stronger signal.
     * Android shows a system approval dialog for each request.
     *
     * The returned [Network] has no INTERNET capability and is never the
     * default network: anything meant to go over it (GECKO queries, the TLS
     * probe) must be explicitly bound to it. Returns `null` if the user
     * declined, the AP wasn't found, or [timeoutMs] passed. The connection
     * stays up until [leave] or the next [join].
     */
    suspend fun join(ssid: String, bssid: String?, timeoutMs: Int = 60_000): Network? {
        leave()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply { if (bssid != null) setBssid(MacAddress.fromString(bssid)) }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        return suspendCancellableCoroutine { cont ->
            val created = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "joined $ssid ($bssid) as $network")
                    joinedNetwork = network
                    if (cont.isActive) cont.resume(network)
                }
                override fun onUnavailable() {
                    Log.w(TAG, "could not join $ssid ($bssid)")
                    joinCallback = null
                    if (cont.isActive) cont.resume(null)
                }
                override fun onLost(network: Network) {
                    Log.i(TAG, "lost $network")
                    if (joinedNetwork == network) joinedNetwork = null
                }
            }
            joinCallback = created
            connectivity.requestNetwork(request, created, timeoutMs)
            cont.invokeOnCancellation { leave() }
        }
    }

    /** Releases the network joined by [join], if any. */
    fun leave() {
        joinCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        joinCallback = null
        joinedNetwork = null
    }

    /** Held while [watchDeviceWifi] is active. */
    private var deviceWifiCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Calls [onChange] (any thread) with the SSID of the Wi-Fi the device
     * itself is connected to, or null, now and on every change. Only networks
     * with the INTERNET capability count: the app's own joins (onboarding
     * session, portal check) are requested without it, so they don't show up
     * as "the device's connection".
     */
    @SuppressLint("MissingPermission")
    fun watchDeviceWifi(onChange: (String?) -> Unit) {
        if (deviceWifiCallback != null) return
        val bySsid = java.util.concurrent.ConcurrentHashMap<Network, String>()
        val report = {
            deviceWifiNetwork = bySsid.keys.firstOrNull()
            onChange(bySsid.values.firstOrNull())
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val onCaps = { network: Network, caps: NetworkCapabilities ->
            // Android 12+: the SSID comes with the capabilities (if the callback asked for
            // location info); before that only WifiManager knows it.
            @Suppress("DEPRECATION")
            val raw = (caps.transportInfo as? android.net.wifi.WifiInfo)?.ssid ?: wifi.connectionInfo?.ssid
            val ssid = raw?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            if (ssid == null) bySsid.remove(network) else bySsid[network] = ssid
            report()
        }
        val onLost = { network: Network -> bySsid.remove(network); report() }
        val callback = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(network, caps)
                override fun onLost(network: Network) { onLost(network) }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(network, caps)
                override fun onLost(network: Network) { onLost(network) }
            }
        }
        deviceWifiCallback = callback
        runCatching { connectivity.registerNetworkCallback(request, callback) }.onFailure { Log.w(TAG, "can't watch Wi-Fi: $it") }
    }

    /** Held while [watchInternet] is active. */
    private var internetCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Calls [onOnline] (any thread) whenever the device's default network gains
     * working internet: Android validated it, which on a captive portal happens
     * only after the login. That's when a WiFi-only device can reach the map
     * server again (D26).
     */
    fun watchInternet(onOnline: () -> Unit) {
        if (internetCallback != null) return
        val validated = java.util.concurrent.ConcurrentHashMap<Network, Boolean>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (now && validated.put(network, true) != true) onOnline()
                if (!now) validated[network] = false
            }
            override fun onLost(network: Network) { validated.remove(network) }
        }
        internetCallback = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }.onFailure { Log.w(TAG, "can't watch internet: $it") }
    }

    /** Whether Wi-Fi is switched on; with it off, every join fails, so callers ask the user to turn it on first. */
    fun isWifiEnabled(): Boolean = runCatching { wifi.isWifiEnabled }.getOrDefault(true)

    /** SSID of the network the device itself is connected to (not the app's own join), or null. */
    @Suppress("DEPRECATION")
    fun connectedSsid(): String? =
        wifi.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

    /** BSSID of the access point the device itself is connected to, or null (also without location permission). */
    @Suppress("DEPRECATION")
    fun connectedBssid(): String? =
        wifi.connectionInfo?.bssid?.lowercase()?.takeIf { it != "02:00:00:00:00:00" }

    fun cellularNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }
}