package com.thesis.geckowifi.enterprise

import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiNetworkSuggestion
import com.thesis.geckowifi.data.model.AAADomain
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.verification.pinnedCaCertificates

sealed class ConfigResult {
    data class Success(val suggestion: WifiNetworkSuggestion) : ConfigResult()
    data class Rejected(val reason: String) : ConfigResult()
}

/**
 * Builds the connection profile for an enterprise network from its
 * GeoCertificate, so the OS supplicant itself refuses a server that doesn't
 * match (enforcement, not just detection). Not wired into a check yet.
 *
 * No root needed: the profile pins the server name (`setDomainSuffixMatch`)
 * and, when the GeoCert carries them (`ca_certificates`), the registered CA
 * certificates, so the server must chain to the operator's own CA. Without
 * them, the name is checked against the device's trusted roots, which a
 * private CA like ETH's isn't in.
 */
class EnterpriseConfigurator {

    /** [userRealm] picks the `aaa` entry; null or a visitor realm falls back to the first entry. */
    fun buildFrom(certificate: GeoCertificate, ssid: String, userRealm: String? = null): ConfigResult {
        val identity = certificate.wifi
        val enterprise = identity.enterprise
        if (enterprise == null || identity.authModes.none { it.isEnterprise }) {
            return ConfigResult.Rejected("network is not enterprise: ${identity.authModeNames}")
        }
        val group = selectGroup(enterprise.aaa, userRealm)
            ?: return ConfigResult.Rejected("no aaa entry to provision from")
        val names = group.servers.flatMap { it.names }.distinct()
        if (names.isEmpty()) {
            return ConfigResult.Rejected("no server names to pin")
        }

        val method = group.eapMethods.firstOrNull()
        val enterpriseConfig = WifiEnterpriseConfig().apply {
            eapMethod = mapEap(method?.method)
            if (eapMethod != WifiEnterpriseConfig.Eap.TLS) {
                phase2Method = mapPhase2(method?.phase2?.firstOrNull())
            }
            // Android accepts a ';'-separated list; each entry also matches its subdomains.
            setDomainSuffixMatch(names.joinToString(";"))
            val cas = group.servers.flatMap { it.pinnedCaCertificates() }
            if (cas.isNotEmpty()) caCertificates = cas.toTypedArray()
        }

        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        val suggestion = if (WiFiAuthMode.WPA3_ENTERPRISE_192 in identity.authModes)
            builder.setWpa3Enterprise192BitModeConfig(enterpriseConfig).build()
        else
            builder.setWpa2EnterpriseConfig(enterpriseConfig).build()
        return ConfigResult.Success(suggestion)
    }

    fun selectGroup(aaa: List<AAADomain>, userRealm: String?): AAADomain? =
        userRealm?.let { realm -> aaa.firstOrNull { it.coversRealm(realm) } } ?: aaa.firstOrNull()

    fun mapEap(method: String?): Int = when (method) {
        "peap" -> WifiEnterpriseConfig.Eap.PEAP
        "ttls" -> WifiEnterpriseConfig.Eap.TTLS
        else -> WifiEnterpriseConfig.Eap.TLS
    }

    fun mapPhase2(method: String?): Int = when (method) {
        "pap" -> WifiEnterpriseConfig.Phase2.PAP
        "gtc" -> WifiEnterpriseConfig.Phase2.GTC
        "mschap" -> WifiEnterpriseConfig.Phase2.MSCHAP
        else -> WifiEnterpriseConfig.Phase2.MSCHAPV2
    }
}
