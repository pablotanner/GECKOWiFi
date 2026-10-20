package com.thesis.geckowifi.network

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
        val capabilities: String,
        val frequencyMhz: Int = 0,
        /** Signal strength in dBm (higher = stronger). */
        val rssi: Int = Int.MIN_VALUE
    ) : ScannedNetwork() {
        /** No WPA/WPA2/WPA3/WEP/802.1X in the scan's capability string - nothing to authenticate with. */
        val isOpen: Boolean
            get() = listOf("WPA", "RSN", "SAE", "WEP", "EAP", "OWE").none { capabilities.contains(it) }

        /** What the access point advertises, compared with the registered `auth_modes`. */
        val authModes: Set<WiFiAuthMode>
            get() = parseAuthModes(capabilities)

        /** 802.1X (WPA2/WPA3-Enterprise): joined with an account, e.g. eduroam. */
        val isEnterprise: Boolean
            get() = authModes.any { it.isEnterprise }

        /** Short security label for list rows, instead of the raw capability string. */
        val securityLabel: String
            get() = when {
                isOpen -> "Open"
                "EAP" in capabilities -> "Enterprise"
                "SAE" in capabilities -> "WPA3"
                "WEP" in capabilities -> "WEP"
                else -> "WPA2"
            }

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
