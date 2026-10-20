package com.thesis.geckowifi.eduroam

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "EduroamInstaller"

/**
 * Non-root [EnterpriseProfileInstaller]: installs the pinned profile as a
 * `WifiNetworkSuggestion`, as an app replacing eduroam CAT would.
 *
 * Prototype costs (a system component wouldn't have them): Android asks once
 * whether the app may suggest networks (a notification), joins suggestions on
 * its own schedule, and prefers a network the user saved by hand for the same
 * SSID (remove the manual profile of scene 4 before scene 1).
 */
class SuggestionProfileInstaller(context: Context) : EnterpriseProfileInstaller {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** What this process added, for [remove] on API 29 (no [WifiManager.getNetworkSuggestions]). */
    private val added = mutableMapOf<String, WifiNetworkSuggestion>()

    override suspend fun install(plan: ProfilePlan, password: String): InstallOutcome {
        val config = WifiEnterpriseConfig().apply {
            eapMethod = when (plan.method) {
                EapMethod.PEAP -> WifiEnterpriseConfig.Eap.PEAP
                EapMethod.TTLS -> WifiEnterpriseConfig.Eap.TTLS
            }
            phase2Method = when (plan.phase2) {
                Phase2.PAP -> WifiEnterpriseConfig.Phase2.PAP
                Phase2.MSCHAP -> WifiEnterpriseConfig.Phase2.MSCHAP
                Phase2.MSCHAPV2 -> WifiEnterpriseConfig.Phase2.MSCHAPV2
                Phase2.GTC -> WifiEnterpriseConfig.Phase2.GTC
            }
            identity = plan.identity
            anonymousIdentity = plan.outerIdentity
            this.password = password
            caCertificates = plan.caCertificates.toTypedArray()
            // wpa_supplicant's domain_suffix_match takes a ';'-separated list.
            domainSuffixMatch = plan.serverNames.joinToString(";")
        }
        val suggestion = try {
            WifiNetworkSuggestion.Builder()
                .setSsid(plan.ssid)
                .apply { if (plan.wpa3) setWpa3EnterpriseConfig(config) else setWpa2EnterpriseConfig(config) }
                .build()
        } catch (e: IllegalArgumentException) {
            // Android rejects enterprise configs without CA + domain (API 30+), among others.
            return InstallOutcome.Failed("Android rejected the profile: ${e.message}")
        }

        remove(plan.ssid)
        val status = wifi.addNetworkSuggestions(listOf(suggestion))
        Log.i(TAG, "addNetworkSuggestions(${plan.ssid}) = $status")
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> {
                added[plan.ssid.lowercase()] = suggestion
                InstallOutcome.Installed("suggested to Android; the first time, allow the app's suggested networks in the notification")
            }
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                InstallOutcome.Failed("suggestions from this app are disallowed; allow them in Settings > Apps > special access > Wi-Fi control")
            else -> InstallOutcome.Failed("Android refused the suggestion (status $status)")
        }
    }

    override suspend fun remove(ssid: String) {
        val ours = buildList {
            added.remove(ssid.lowercase())?.let(::add)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                addAll(wifi.networkSuggestions.filter { it.ssid?.removeSurrounding("\"").equals(ssid, ignoreCase = true) })
            }
        }.distinct()
        if (ours.isNotEmpty()) Log.i(TAG, "removeNetworkSuggestions($ssid) = ${wifi.removeNetworkSuggestions(ours)}")
    }

    @SuppressLint("MissingPermission")
    override suspend fun awaitConnected(ssid: String, timeoutMs: Long): Boolean {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val onCaps = { callback: ConnectivityManager.NetworkCallback, caps: NetworkCapabilities ->
                    if (connectedSsid(caps).equals(ssid, ignoreCase = true) && cont.isActive) {
                        runCatching { connectivity.unregisterNetworkCallback(callback) }
                        cont.resume(true)
                    }
                }
                // FLAG_INCLUDE_LOCATION_INFO: without it, WifiInfo.ssid is redacted on API 31+.
                val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(this, caps)
                    }
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(this, caps)
                    }
                }
                cont.invokeOnCancellation { runCatching { connectivity.unregisterNetworkCallback(callback) } }
                connectivity.registerNetworkCallback(request, callback)
            }
        } ?: false
    }

    @Suppress("DEPRECATION")
    private fun connectedSsid(caps: NetworkCapabilities): String? =
        ((caps.transportInfo as? WifiInfo)?.ssid ?: wifi.connectionInfo?.ssid)?.removeSurrounding("\"")
}
