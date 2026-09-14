package com.thesis.geckowifi.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WiFiAuthMode {
    @SerialName("open") OPEN,
    @SerialName("wpa2-personal") WPA2_PERSONAL,
    @SerialName("wpa3-personal") WPA3_PERSONAL,
    @SerialName("wpa2-enterprise") WPA2_ENTERPRISE,
    @SerialName("wpa3-enterprise") WPA3_ENTERPRISE
}

@Serializable
enum class EAPMethod {
    @SerialName("eap-tls") EAP_TLS,
    @SerialName("peap") PEAP,
    @SerialName("ttls") TTLS
}

@Serializable
data class WiFiIdentity(
    val ssid: String? = null,
    @SerialName("auth_mode") val authMode: WiFiAuthMode,
    val eap: EAPMethod? = null,
    @SerialName("auth_server_names") val authServerNames: List<String> = emptyList(),
    @SerialName("trusted_ca_fingerprints") val trustedCAFingerprints: List<String> = emptyList(),
    @SerialName("auth_server_cas") val authServerCAs: List<String> = emptyList()
)

@Serializable
data class PortalTLSIdentity(
    val domains: List<String> = emptyList(),
    @SerialName("pinned_spki_sha256") val pinnedSPKIHashes: List<String> = emptyList(),
    @SerialName("certificate_sha256") val certificateFingerprints: List<String> = emptyList()
)

@Serializable
data class GeoCertArea(
    val type: String = "MultiPolygon",
    val coordinates: List<List<List<List<Double>>>> = emptyList()
)

@Serializable
data class GeoCertificate(
    @SerialName("certificate_id") val certificateId: String,
    val wifi: WiFiIdentity,
    val portal: PortalTLSIdentity? = null,
    val areas: List<GeoCertArea> = emptyList(),
    @SerialName("areas_altitude") val areasAltitude: List<List<Double>> = emptyList(),
    @SerialName("not_valid_after") val notValidAfter: String
) {
    fun portalDomains(): List<String> = portal?.domains.orEmpty()
    fun domainEntry(host: String): String? =
        portalDomains().firstOrNull { it.equals(host, ignoreCase = true) }
}