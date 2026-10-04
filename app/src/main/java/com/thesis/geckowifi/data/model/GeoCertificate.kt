package com.thesis.geckowifi.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

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
    /**
     * Null = missing or unrecognised on the server. Legacy (pre-schema-v2)
     * certificates were stored without a `wifi` section and are served with
     * `"auth_mode": ""`; GeckoClient's `coerceInputValues` maps that to this
     * default instead of dropping the whole certificate as unparsable.
     */
    @SerialName("auth_mode") val authMode: WiFiAuthMode? = null,
    val eap: EAPMethod? = null,
    @SerialName("auth_server_names") val authServerNames: List<String> = emptyList(),
    @SerialName("trusted_ca_fingerprints") val trustedCAFingerprints: List<String> = emptyList(),
    @SerialName("auth_server_cas") val authServerCAs: List<String> = emptyList()
)

/** What a domain may be in a captive-portal flow (schema v2). */
@Serializable
enum class PortalDomainRole {
    /** Entry point: the only role that can start (anchor) a portal session. */
    @SerialName("primary") PRIMARY,
    /** Only valid after a primary domain of the same certificate was seen (e.g. a payment page). */
    @SerialName("delegate") DELEGATE,
    /** Host of the RFC 8908 Captive Portal API (DHCP option 114). */
    @SerialName("api") API
}

/**
 * One HTTPS host of a captive portal with its own pins - per domain, so a
 * third-party delegate's key can't be presented for the certificate's other
 * domains. Mirrors the server's Go `PortalDomain` (schema v2).
 */
@Serializable
data class PortalDomain(
    val name: String,
    val role: PortalDomainRole,
    /** SPKI SHA-256 (base64). The trust anchor: an empty list never verifies. */
    @SerialName("pinned_spki_sha256") val pinnedSPKIHashes: List<String> = emptyList(),
    @SerialName("certificate_sha256") val certificateFingerprints: List<String> = emptyList()
) {
    fun accepts(presentedSpkiHash: String?): Boolean =
        presentedSpkiHash != null && presentedSpkiHash in pinnedSPKIHashes
}

@Serializable
data class PortalTLSIdentity(
    val domains: List<PortalDomain> = emptyList()
)

@Serializable
data class GeoCertArea(
    val type: String = "MultiPolygon",
    val coordinates: List<List<List<List<Double>>>> = emptyList()
)

@Serializable
data class GeoCertificate(
    /** 2 = per-domain roles and pins; 0 = legacy certificate without the field. */
    @SerialName("schema_version") val schemaVersion: Int = 0,
    @SerialName("certificate_id") val certificateId: String,
    val wifi: WiFiIdentity,
    val portal: PortalTLSIdentity? = null,
    val areas: List<GeoCertArea> = emptyList(),
    @SerialName("areas_altitude") val areasAltitude: List<List<Double>> = emptyList(),
    @SerialName("not_valid_after") val notValidAfter: String
) {
    fun portalDomains(): List<PortalDomain> = portal?.domains.orEmpty()

    fun domainEntry(host: String): PortalDomain? =
        portalDomains().firstOrNull { it.name.equals(host, ignoreCase = true) }

    fun primaryDomains(): List<PortalDomain> =
        portalDomains().filter { it.role == PortalDomainRole.PRIMARY }

    /** False once [notValidAfter] has passed - or if it can't be parsed (fail closed). */
    fun isValidAt(now: Instant): Boolean =
        // OffsetDateTime, not Instant.parse: RFC 3339 allows offsets like +02:00, not just Z.
        runCatching { now.isBefore(OffsetDateTime.parse(notValidAfter).toInstant()) }.getOrDefault(false)
}