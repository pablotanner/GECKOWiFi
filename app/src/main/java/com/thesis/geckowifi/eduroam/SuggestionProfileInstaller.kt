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

    /** What this process added, by SSID, for [remove] and [installedSsids] on API 29 (no [WifiManager.getNetworkSuggestions]). */
    private val added = mutableMapOf<String, List<WifiNetworkSuggestion>>()

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
        // One per security type, same pins: see chooseEnterpriseSecurity.
        val suggestions = try {
            plan.security.map { security ->
                WifiNetworkSuggestion.Builder()
                    .setSsid(plan.ssid)
                    .apply { setEnterpriseConfig(security, config) }
                    .build()
            }
        } catch (e: IllegalArgumentException) {
            // Android rejects enterprise configs without CA + domain (API 30+), among others.
            return InstallOutcome.Failed("Android rejected the profile: ${e.message}")
        }

        remove(plan.ssid)
        val status = wifi.addNetworkSuggestions(suggestions)
        Log.i(TAG, "addNetworkSuggestions(${plan.ssid}, ${plan.security}) = $status")
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> {
                added[plan.ssid] = suggestions
                InstallOutcome.Installed("suggested to Android; the first time, allow the app's suggested networks in the notification")
            }
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                InstallOutcome.Failed("suggestions from this app are disallowed; allow them in Settings > Apps > special access > Wi-Fi control")
            else -> InstallOutcome.Failed("Android refused the suggestion (status $status)")
        }
    }

    override suspend fun remove(ssid: String) {
        val ours = buildList {
            added.keys.filter { it.equals(ssid, ignoreCase = true) }.forEach { added.remove(it)?.let(::addAll) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                addAll(wifi.networkSuggestions.filter { it.ssid?.removeSurrounding("\"").equals(ssid, ignoreCase = true) })
            }
        }.distinct()
        if (ours.isNotEmpty()) Log.i(TAG, "removeNetworkSuggestions($ssid) = ${wifi.removeNetworkSuggestions(ours)}")
    }

    override fun installedSsids(): Set<String> =
        // Android keeps an app's suggestions across restarts and returns them from API 30.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifi.networkSuggestions.mapNotNull { it.ssid?.removeSurrounding("\"") }.toSet()
        // API 29 can't read them back: only what this process added (lost track of after a restart).
        else added.keys.toSet()

    /**
     * Waits for the connection, and on API 30+ also listens for Android's report that
     * signing in with one of our suggestions failed (`SuggestionConnectionStatusListener`).
     * Against an impostor Android gives up after a few attempts within seconds and then
     * stays quiet, so without that report the wait always ran into the timeout.
     */
    @SuppressLint("MissingPermission")
    override suspend fun awaitConnected(ssid: String, timeoutMs: Long): ConnectWait {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val started = System.currentTimeMillis()
        val result: ConnectWait = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<ConnectWait> { cont ->
                var statusListener: Any? = null
                lateinit var callback: ConnectivityManager.NetworkCallback
                fun finish(wait: ConnectWait) {
                    if (!cont.isActive) return
                    runCatching { connectivity.unregisterNetworkCallback(callback) }
                    removeStatusListener(statusListener)
                    cont.resume(wait)
                }
                val onCaps = { caps: NetworkCapabilities ->
                    if (connectedSsid(caps).equals(ssid, ignoreCase = true)) finish(ConnectWait.Connected)
                }
                // FLAG_INCLUDE_LOCATION_INFO: without it, WifiInfo.ssid is redacted on API 31+.
                callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(caps)
                    }
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onCaps(caps)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val listener = WifiManager.SuggestionConnectionStatusListener { suggestion, reason ->
                        val forUs = suggestion.ssid?.removeSurrounding("\"").equals(ssid, ignoreCase = true)
                        Log.i(TAG, "suggestion status for ${suggestion.ssid}: reason $reason")
                        // Only an authentication failure ends the wait: association and IP failures
                        // are often transient, and Android keeps trying those by itself.
                        if (forUs && reason == WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_AUTHENTICATION) {
                            finish(ConnectWait.Refused("Android reported that signing in to $ssid failed"))
                        }
                    }
                    runCatching { wifi.addSuggestionConnectionStatusListener(Runnable::run, listener) }
                        .onSuccess { statusListener = listener }
                        .onFailure { Log.w(TAG, "can't listen for suggestion connection status", it) }
                }
                cont.invokeOnCancellation {
                    runCatching { connectivity.unregisterNetworkCallback(callback) }
                    removeStatusListener(statusListener)
                }
                connectivity.registerNetworkCallback(request, callback)
            }
        } ?: ConnectWait.TimedOut
        Log.i(TAG, "awaitConnected($ssid) = $result after ${System.currentTimeMillis() - started} ms")
        return result
    }

    private fun removeStatusListener(listener: Any?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && listener is WifiManager.SuggestionConnectionStatusListener) {
            runCatching { wifi.removeSuggestionConnectionStatusListener(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private fun connectedSsid(caps: NetworkCapabilities): String? =
        ((caps.transportInfo as? WifiInfo)?.ssid ?: wifi.connectionInfo?.ssid)?.removeSurrounding("\"")
}
