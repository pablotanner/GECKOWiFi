package com.thesis.geckowifi.network

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
        val capabilities: String
    ) : ScannedNetwork()

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
     * Stands in for the RADIUS/AAA auth server name + CA fingerprint that
     * [com.thesis.geckowifi.capability.SupplicantCertSource] would read from
     * a real EAP-TLS handshake (root-only). No real handshake is involved
     * for this entry.
     */
    data class FakeEduroam(
        override val ssid: String,
        override val bssid: String?,
        val label: String,
        val presumedAuthServerName: String,
        val presumedCaFingerprintBase64: String
    ) : ScannedNetwork()
}
