package com.thesis.geckowifi.eduroam

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import com.thesis.geckowifi.R
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.coroutines.resume

private const val TAG = "OnboardingSession"

/**
 * Phase 1 of Design A (eduroam-brief.md): a session on the target SSID with
 * a public, anonymous GECKO identity that the SP's RADIUS server handles
 * locally and puts into a walled garden that only reaches the map server.
 * Nothing secret is sent, so the session itself needn't be trusted: the GECKO
 * answers fetched over it are self-authenticating.
 *
 * DEMO SHORTCUT (docs/eduroam-decisions.md, D8): these are constants. A real
 * deployment would need a federation-wide convention and an OS-bundled
 * onboarding CA, since a device can't look anything up before joining.
 */
object OnboardingIdentity {
    const val IDENTITY = "fetch@gecko.ch"
    const val PASSWORD = "gecko" // public on purpose
    /**
     * Android (API 30+) refuses enterprise configs without server validation,
     * so the onboarding server must present a certificate for this name from
     * the bundled CA. Its validation exists only for that reason, not for
     * security: an attacker may hold such a certificate too (scene 3).
     */
    const val SERVER_NAME = "onboard.gecko.ch"

    /**
     * Lab guard: SSIDs the prototype may onboard on. Real eduroam is all around
     * the lab (ETH campus); the demo must never send even the public GECKO
     * identity to production RADIUS servers (eduroam-brief.md, 4.1).
     */
    val LAB_SSIDS: Set<String> = setOf("eduroam-lab")
}

/** The access point to onboard through: the SSID, and the BSSID from the scan so the test hits the intended AP. */
data class OnboardingTarget(val ssid: String, val bssid: String?, val wpa3: Boolean = false)

sealed interface OnboardingOutcome<out T> {
    /** The session came up; [value] is what the block returned. The session is already released. */
    data class Done<T>(val value: T) : OnboardingOutcome<T>

    /** No session: declined, out of range, or the onboarding server's certificate didn't validate. */
    data class Failed(val reason: String) : OnboardingOutcome<Nothing>
}

/**
 * Brings up the onboarding session, runs a block over it, and always tears it
 * down again. Non-root: [SpecifierOnboardingSession]. A root/system
 * implementation would associate through wpa_supplicant directly and skip the
 * approval dialog (docs/eduroam-decisions.md, D1).
 */
interface OnboardingTransport {
    /** [block] gets the onboarding [Network]; everything it sends must be bound to it. */
    suspend fun <T> withOnboardingNetwork(target: OnboardingTarget, timeoutMs: Int, block: suspend (Network) -> T): OnboardingOutcome<T>
}

/**
 * `WifiNetworkSpecifier` with the onboarding identity. The network is
 * app-only and never the device's default (no INTERNET capability). Android
 * shows an approval dialog for every request; a prototype cost.
 */
class SpecifierOnboardingSession(private val context: Context) : OnboardingTransport {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val onboardingCa: X509Certificate by lazy {
        context.resources.openRawResource(R.raw.gecko_onboarding_ca).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    override suspend fun <T> withOnboardingNetwork(
        target: OnboardingTarget,
        timeoutMs: Int,
        block: suspend (Network) -> T
    ): OnboardingOutcome<T> {
        val enterprise = WifiEnterpriseConfig().apply {
            eapMethod = WifiEnterpriseConfig.Eap.PEAP
            phase2Method = WifiEnterpriseConfig.Phase2.MSCHAPV2
            identity = OnboardingIdentity.IDENTITY
            // Outer = inner: the server only accepts the GECKO identity when both are it.
            anonymousIdentity = OnboardingIdentity.IDENTITY
            password = OnboardingIdentity.PASSWORD
            caCertificate = onboardingCa
            domainSuffixMatch = OnboardingIdentity.SERVER_NAME
        }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(target.ssid)
            .apply { target.bssid?.let { setBssid(MacAddress.fromString(it)) } }
            .apply { if (target.wpa3) setWpa3EnterpriseConfig(enterprise) else setWpa2EnterpriseConfig(enterprise) }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val network = suspendCancellableCoroutine<Network?> { cont ->
                val created = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "onboarding session up on ${target.ssid} (${target.bssid}) as $network")
                        if (cont.isActive) cont.resume(network)
                    }
                    override fun onUnavailable() {
                        Log.w(TAG, "no onboarding session on ${target.ssid} (${target.bssid})")
                        if (cont.isActive) cont.resume(null)
                    }
                }
                callback = created
                connectivity.requestNetwork(request, created, timeoutMs)
            } ?: return OnboardingOutcome.Failed(
                "couldn't join ${target.ssid} with the GECKO identity: the request was declined, the access point " +
                    "wasn't found, or its server's certificate isn't valid for ${OnboardingIdentity.SERVER_NAME}"
            )
            return OnboardingOutcome.Done(block(network))
        } finally {
            // Always released, also on errors, timeouts and cancellation.
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            Log.i(TAG, "onboarding session released")
        }
    }
}
