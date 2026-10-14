package com.thesis.geckowifi.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/**
 * How a client authenticates to the network (schema v3 `auth_modes`). Parsed
 * leniently from the wire name: a value this client doesn't know is dropped
 * rather than failing the whole certificate, and so never matches anything
 * (see docs/geocert-schema.md). [WEP] is never registered; it exists so an
 * observed WEP network can be told apart from an open one.
 */
enum class WiFiAuthMode(val wire: String) {
    OPEN("open"),
    OWE("owe"),
    WEP("wep"),
    WPA2_PERSONAL("wpa2-personal"),
    WPA3_PERSONAL("wpa3-personal"),
    WPA2_ENTERPRISE("wpa2-enterprise"),
    WPA3_ENTERPRISE("wpa3-enterprise"),
    WPA3_ENTERPRISE_192("wpa3-enterprise-192");

    val isEnterprise: Boolean
        get() = this == WPA2_ENTERPRISE || this == WPA3_ENTERPRISE || this == WPA3_ENTERPRISE_192

    companion object {
        fun fromWire(value: String): WiFiAuthMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Who the registration authority bound this certificate to (design decision 2). */
@Serializable
data class Subject(
    /** Stable registrant id, e.g. a verified domain. Certificates with the same id belong to one operator. */
    val id: String = "",
    /** Display only, never used for matching. */
    val name: String? = null
)

@Serializable
data class WiFiIdentity(
    val ssid: String? = null,
    /**
     * Opt-out of SSID exclusivity (design decision 1). False (the default, and
     * the Go zero value) means a network on this SSID that matches nothing
     * registered here is impersonation.
     */
    @SerialName("ssid_shared") val ssidShared: Boolean = false,
    /** Wire names as registered; see [authModes]. Empty for v2 certificates, which had a single `auth_mode`. */
    @SerialName("auth_modes") val authModeNames: List<String> = emptyList(),
    /** "optional" / "required"; null = unspecified. Parsed, not yet compared (scan results don't reliably report PMF). */
    val pmf: String? = null,
    val enterprise: EnterpriseIdentity? = null
) {
    /** The registered modes this client understands. */
    val authModes: Set<WiFiAuthMode>
        get() = authModeNames.mapNotNull(WiFiAuthMode::fromWire).toSet()

    /** Every mode the network advertises must be registered. Unknown registered names never match. */
    fun permits(observed: Set<WiFiAuthMode>): Boolean = observed.all { it in authModes }
}

/** 802.1X identity of an enterprise network (schema v3). */
@Serializable
data class EnterpriseIdentity(
    /** Roaming federation such as "eduroam"; null/blank = a closed network where every user sees [aaa]. */
    val federation: String? = null,
    /** May be empty only for a federation site without local users (everyone is a visitor). */
    val aaa: List<AAADomain> = emptyList()
) {
    val isFederated: Boolean get() = !federation.isNullOrBlank()
}

/** One set of users (by NAI realm) and the servers that authenticate them. */
@Serializable
data class AAADomain(
    /** "ethz.ch" matches exactly, "*.ethz.ch" matches subrealms only; case-insensitive. */
    val realms: List<String> = emptyList(),
    @SerialName("eap_methods") val eapMethods: List<EAPMethodSpec> = emptyList(),
    val servers: List<AuthServer> = emptyList()
) {
    fun coversRealm(realm: String): Boolean = realms.any { entry ->
        if (entry.startsWith("*.")) realm.endsWith(entry.substring(1), ignoreCase = true)
        else realm.equals(entry, ignoreCase = true)
    }
}

/** EAP method and inner (phase 2) methods. Open-ended strings: "eap-tls", "peap", "ttls"; "mschapv2", "pap", ... */
@Serializable
data class EAPMethodSpec(
    val method: String = "",
    val phase2: List<String> = emptyList()
)

/**
 * One RADIUS server identity. A server is accepted if its leaf key is in
 * [pinnedSPKIHashes], or if a CA in its (signature-checked) chain is in
 * [caSPKIHashes] and the leaf names one of [names]. An entry without pins
 * never accepts anything. Hashes are SHA-256 over the SPKI, base64.
 */
@Serializable
data class AuthServer(
    /** SAN dNSName, falling back to the subject CN. Required for a CA pin to mean anything. */
    val names: List<String> = emptyList(),
    @SerialName("pinned_spki_sha256") val pinnedSPKIHashes: List<String> = emptyList(),
    /** CA key; the CA certificate must be part of the chain the server sends. */
    @SerialName("ca_spki_sha256") val caSPKIHashes: List<String> = emptyList()
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
    /** Null if the server sent a role this client doesn't know: such an entry never anchors a session. */
    val role: PortalDomainRole? = null,
    /** SPKI SHA-256 (base64). The trust anchor: an empty list never verifies. */
    @SerialName("pinned_spki_sha256") val pinnedSPKIHashes: List<String> = emptyList(),
    @SerialName("certificate_sha256") val certificateFingerprints: List<String> = emptyList()
) {
    fun accepts(presentedSpkiHash: String?): Boolean =
        presentedSpkiHash != null && presentedSpkiHash in pinnedSPKIHashes

    val roleLabel: String get() = role?.name?.lowercase() ?: "unknown-role"
}

@Serializable
data class PortalTLSIdentity(
    val domains: List<PortalDomain> = emptyList()
)

/**
 * Despite `type`, not a GeoJSON MultiPolygon: geopki's `coordinates` is
 * `[exteriorRings, interiorRings]`, each ring a closed list of `[lng, lat]`.
 * Every exterior ring is a separate polygon; interior rings are holes.
 */
@Serializable
data class GeoCertArea(
    val type: String = "MultiPolygon",
    val coordinates: List<List<List<List<Double>>>> = emptyList()
) {
    val exteriorRings: List<List<List<Double>>> get() = coordinates.getOrNull(0).orEmpty()
    val interiorRings: List<List<List<Double>>> get() = coordinates.getOrNull(1).orEmpty()
}

/** Critical features this client implements; see [GeoCertificate.isEvaluable]. None defined yet. */
val SUPPORTED_CRITICAL_FEATURES: Set<String> = emptySet()

/** The client-side model of a GeoCertificate; the schema is specified in docs/geocert-schema.md. */
@Serializable
data class GeoCertificate(
    /** 3 = current; 2 = per-domain portal pins, single `auth_mode`; 0 = legacy certificate without the field. */
    @SerialName("schema_version") val schemaVersion: Int = 0,
    @SerialName("certificate_id") val certificateId: String,
    val subject: Subject? = null,
    /** Features this certificate can't be judged without; see [isEvaluable]. */
    val critical: List<String> = emptyList(),
    val wifi: WiFiIdentity,
    val portal: PortalTLSIdentity? = null,
    val areas: List<GeoCertArea> = emptyList(),
    @SerialName("areas_altitude") val areasAltitude: List<List<Double>> = emptyList(),
    /** RFC 3339; null = valid from issuance. Lets an operator register a new key ahead of a rotation. */
    @SerialName("not_valid_before") val notValidBefore: String? = null,
    @SerialName("not_valid_after") val notValidAfter: String
) {
    fun portalDomains(): List<PortalDomain> = portal?.domains.orEmpty()

    fun domainEntry(host: String): PortalDomain? =
        portalDomains().firstOrNull { it.name.equals(host, ignoreCase = true) }

    fun primaryDomains(): List<PortalDomain> =
        portalDomains().filter { it.role == PortalDomainRole.PRIMARY }

    /**
     * True inside [notValidBefore, notValidAfter). False if either bound
     * can't be parsed (fail closed).
     */
    fun isValidAt(now: Instant): Boolean = runCatching {
        // OffsetDateTime, not Instant.parse: RFC 3339 allows offsets like +02:00, not just Z.
        val started = notValidBefore?.let { !now.isBefore(OffsetDateTime.parse(it).toInstant()) } ?: true
        started && now.isBefore(OffsetDateTime.parse(notValidAfter).toInstant())
    }.getOrDefault(false)

    /**
     * False if the certificate lists a critical feature this client doesn't
     * implement: its meaning then can't be judged, so it must produce neither
     * VERIFIED nor CONFLICT (see VerificationEngine).
     */
    fun isEvaluable(): Boolean = critical.all { it in SUPPORTED_CRITICAL_FEATURES }
}
