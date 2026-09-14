package com.thesis.geckowifi.enterprise

import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiNetworkSuggestion
import com.thesis.geckowifi.data.model.EAPMethod
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.WiFiAuthMode

sealed class ConfigResult {
    data class Success(val suggestion: WifiNetworkSuggestion) : ConfigResult()
    data class Rejected(val reason: String) : ConfigResult()
}

class EnterpriseConfigurator {

    /**
     * FIXED (found while wiring the eduroam demo, see docs/prototype-decisions.md):
     * this used to require `identity.authServerCAs` (a full CA certificate,
     * base64 DER) and reject when it was empty - but the real server's
     * `GeoCertificate.WiFiIdentity` struct has no such field, only
     * `auth_server_names` and `trusted_ca_fingerprints` (a *hash*, not
     * reversible to the certificate bytes `WifiEnterpriseConfig.caCertificate`
     * needs). So this could never succeed against real data.
     *
     * Fixed by not requiring a pinned CA certificate at all: this now relies
     * on `setDomainSuffixMatch` alone, validated against the device's normal
     * trusted-root store. That's a real, meaningful weakening versus true CA
     * pinning (an attacker holding *any* CA-issued certificate for a
     * similar-looking name could still pass), but it's what's actually
     * achievable from GECKO's real (fingerprint-only) data, and it's still a
     * genuine prevention mechanism - Android's own supplicant refuses the
     * handshake natively if the name doesn't match, no root needed. Root
     * (`SupplicantCertSource`) remains for the fingerprint-based EVALUATION
     * path, which only needs a hash-to-hash compare - see
     * [expectedCaFingerprints] and `verification/EnterpriseVerification.kt`.
     */
    fun buildFrom(certificate: GeoCertificate, ssid: String): ConfigResult {
        val identity = certificate.wifi

        if (!isEnterprise(identity.authMode)) {
            return ConfigResult.Rejected("network is not enterprise: ${identity.authMode}")
        }
        if (identity.authServerNames.isEmpty()) {
            return ConfigResult.Rejected("no auth_server_names to pin")
        }

        val enterpriseConfig = WifiEnterpriseConfig().apply {
            eapMethod = mapEap(identity.eap)
            if (identity.eap != EAPMethod.EAP_TLS) {
                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAPV2
            }
            setDomainSuffixMatch(identity.authServerNames.first())
        }

        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        val suggestion = when (identity.authMode) {
            WiFiAuthMode.WPA3_ENTERPRISE ->
                builder.setWpa3Enterprise192BitModeConfig(enterpriseConfig).build()
            else ->
                builder.setWpa2EnterpriseConfig(enterpriseConfig).build()
        }
        return ConfigResult.Success(suggestion)
    }

    /**
     * The certificate fingerprints are a comparison value for the rooted
     * ground-truth path (`SupplicantCertSource` + `evaluateEnterpriseNetwork`).
     * Stock Android cannot expose the presented certificate, so this is used
     * for evaluation rather than enforcement.
     */
    fun expectedCaFingerprints(certificate: GeoCertificate): List<String> =
        certificate.wifi.trustedCAFingerprints

    fun isEnterprise(mode: WiFiAuthMode): Boolean =
        mode == WiFiAuthMode.WPA2_ENTERPRISE || mode == WiFiAuthMode.WPA3_ENTERPRISE

    fun mapEap(method: EAPMethod?): Int = when (method) {
        EAPMethod.EAP_TLS -> WifiEnterpriseConfig.Eap.TLS
        EAPMethod.PEAP -> WifiEnterpriseConfig.Eap.PEAP
        EAPMethod.TTLS -> WifiEnterpriseConfig.Eap.TTLS
        null -> WifiEnterpriseConfig.Eap.TLS
    }
}
