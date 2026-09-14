package com.thesis.geckowifi.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.annotation.RequiresPermission

data class NetworkIdentity(
    val ssid: String?,
    val bssid: String?,
    val isWifi: Boolean,
    val isCellular: Boolean
) {
    /**
     * Local cache key only. SSID and BSSID are unauthenticated broadcast values
     * and are never used as trust anchors.
     */
    val key: String get() = bssid ?: ssid ?: if (isCellular) "cellular" else "unknown"
}

class NetworkObserver(private val context: Context) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(onNetworkChanged: (NetworkIdentity) -> Unit) {
        stop()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        val created = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkChanged(identify(network))
            }
            override fun onCapabilitiesChanged(
                network: Network, capabilities: NetworkCapabilities
            ) {
                onNetworkChanged(identify(network, capabilities))
            }
        }
        callback = created
        connectivity.registerNetworkCallback(request, created)
    }

    fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }

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
                capabilities = it.capabilities ?: ""
            )
        }

    /** Cellular network for GECKO queries, so the tested WiFi never carries them. */
    fun cellularNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun currentIdentity(): NetworkIdentity {
        val active = connectivity.activeNetwork ?: return NetworkIdentity(null, null, false, false)
        return identify(active)
    }

    @SuppressLint("MissingPermission")
    private fun identify(
        network: Network,
        capabilities: NetworkCapabilities? = null
    ): NetworkIdentity {
        val caps = capabilities ?: connectivity.getNetworkCapabilities(network)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        if (!isWifi) return NetworkIdentity(null, null, isWifi, isCellular)

        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" }
        return NetworkIdentity(ssid, info?.bssid, isWifi = true, isCellular = false)
    }
}