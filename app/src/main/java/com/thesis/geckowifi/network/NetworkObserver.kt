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

    /** Cellular network for GECKO queries, so the tested WiFi never carries them. */
    fun cellularNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }
}