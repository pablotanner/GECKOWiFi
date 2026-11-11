package com.thesis.geckowifi.network

import android.net.wifi.WifiInfo
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.verification.ObservedAuthServer

/**
 * A network to show in the prototype's "nearby networks" list - either a
 * real WifiManager scan result, or a hardcoded stand-in for a scenario that
 * can't be physically set up for testing (a genuine evil-twin AP, a real
 * eduroam RADIUS server). See docs/app-design.md / docs/prototype-decisions.md.
 *
 * [ssid] is the actual identifier used for the SSID pre-filter
 * (docs/app-design.md) - for a fake benign/evil-twin pair it is deliberately
 * the SAME value for both (that's the literal definition of an evil twin:
 * identical visible network identity, different backend). [label] is purely
 * for telling the two apart in this demo UI; it plays no role in matching.
 */
sealed class ScannedNetwork {
    abstract val ssid: String
    abstract val bssid: String?

    /** A real result from [NetworkObserver.scanResults] - no presumed domain/cert; that's still typed in manually. */
    data class Real(
        override val ssid: String,
        override val bssid: String?,
        /** What the access point claims in its beacon (unauthenticated); see [RsnProfile]. */
        val profile: RsnProfile,
        val frequencyMhz: Int = 0,
        /** Signal strength in dBm (higher = stronger). */
        val rssi: Int = Int.MIN_VALUE,
        /**
         * Android's own classification of this AP ([android.net.wifi.ScanResult.getSecurityTypes],
         * `WifiInfo.SECURITY_TYPE_*`). Empty on API < 31, where that call is unavailable. Used only to
         * pick the Wi-Fi security type for the connect specifier/suggestion ([requiresWpa3EnterpriseSpecifier]);
         * [authModes] remains the source for the verdict.
         */
        val securityTypes: List<Int> = emptyList()
    ) : ScannedNetwork() {
        /** Every mode a client could end up in with this AP ([admissibleModes]), compared with the registered `auth_modes`. */
        val authModes: Set<WiFiAuthMode> = profile.admissibleModes()

        /** Nothing to authenticate with (an OWE transition AP's open side included). */
        val isOpen: Boolean
            get() = WiFiAuthMode.OPEN in authModes

        /** 802.1X (WPA2/WPA3-Enterprise): joined with an account, e.g. eduroam. */
        val isEnterprise: Boolean
            get() = authModes.any { it.isEnterprise }

        /**
         * Whether the connect specifier/suggestion must request WPA3-Enterprise rather than
         * WPA2-Enterprise, decided from Android's own [securityTypes] (not the capability string). A
         * WPA2/WPA3-Enterprise transition AP reports `SECURITY_TYPE_EAP` and is matched only by a
         * WPA2-Enterprise specifier, so WPA3 is requested solely when Android reports WPA3-Enterprise
         * without plain EAP. Empty security types (API < 31) fall back to WPA2-Enterprise.
         */
        val requiresWpa3EnterpriseSpecifier: Boolean
            get() = WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE in securityTypes &&
                WifiInfo.SECURITY_TYPE_EAP !in securityTypes

        /** IEEE channel number derived from [frequencyMhz], or null if unknown. */
        val channel: Int?
            get() = when (frequencyMhz) {
                2484 -> 14
                in 2412..2472 -> (frequencyMhz - 2407) / 5
                in 5160..5885 -> (frequencyMhz - 5000) / 5
                in 5955..7115 -> (frequencyMhz - 5950) / 5
                else -> null
            }

        val band: String?
            get() = when (frequencyMhz) {
                in 2400..2500 -> "2.4 GHz"
                in 5000..5900 -> "5 GHz"
                in 5925..7125 -> "6 GHz"
                else -> null
            }
    }

    /**
     * Stands in for a captive-portal login domain + presented SPKI hash that
     * [com.thesis.geckowifi.capability.PcapSniCapture]/[com.thesis.geckowifi.capability.RootPacketCapture]
     * would observe from real traffic, once wired up. Skips detection
     * entirely to exercise only the verification logic downstream of it.
     */
    data class FakeCaptivePortal(
        override val ssid: String,
        override val bssid: String?,
        val label: String,
        val presumedDomain: String,
        val presumedSpkiHashBase64: String
    ) : ScannedNetwork()

    /**
     * Stands in for what an EAP probe would observe from a real 802.1X
     * handshake (root-only, see [com.thesis.geckowifi.capability.SupplicantCertSource]):
     * the RADIUS server's certificate chain, plus the realm of the user's own
     * identity. No real handshake is involved for this entry.
     */
    data class FakeEduroam(
        override val ssid: String,
        override val bssid: String?,
        val label: String,
        val observed: ObservedAuthServer,
        /** Realm of the user's eduroam identity (`user@realm`); decides local user vs. visitor. */
        val userRealm: String?,
        val authModes: Set<WiFiAuthMode> = setOf(WiFiAuthMode.WPA2_ENTERPRISE)
    ) : ScannedNetwork()
}
