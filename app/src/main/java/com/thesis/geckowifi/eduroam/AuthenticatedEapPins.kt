package com.thesis.geckowifi.eduroam

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.verification.pinnedCaCertificates
import gecko.realm.RealmRecord
import gecko.realm.SignedRealmRecord
import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant

/** Outer EAP method. EAP-TLS is not offered: it needs a client certificate, which this demo doesn't provision. */
enum class EapMethod { PEAP, TTLS }

/** Inner (phase 2) method, as Android's `WifiEnterpriseConfig.Phase2` names them. */
enum class Phase2 { PAP, MSCHAP, MSCHAPV2, GTC }

/** Where a set of pins came from, for the UI and the evidence log. */
sealed interface PinSource {
    /** A [SignedRealmRecord] whose signature verified against [PinnedRealmKey]. */
    data class RealmRecord(val issuedAt: Instant, val expiresAt: Instant, val supportUrl: String?) : PinSource

    /** The `aaa` entry of a site GeoCert that arrived with a verified map proof (local user). */
    data class SiteCertificate(val certificateId: String) : PinSource
}

sealed interface PinResult {
    data class Ok(val pins: AuthenticatedEapPins) : PinResult

    /** The source was readable but must not be used; [reason] says why, in plain words. */
    data class Rejected(val reason: String) : PinResult
}

/**
 * The EAP settings of one realm, from an authenticated source: the only thing
 * credentials may ever be attached to (see [EduroamCredentials.EnterpriseProfileBuilder]).
 * The constructor is private; instances come only from the two factories
 * below, which check where the values came from:
 *
 * - [fromSignedRealmRecord]: the realm's signed record (visitor case).
 * - [fromSiteCertificate]: the site GeoCert's `aaa` entry for the realm (local
 *   user). Its input must be a certificate from a verified map answer
 *   ([com.thesis.geckowifi.data.remote.GeckoResponse.Success]); a
 *   [GeoCertificate] built by hand isn't evidence of anything.
 *
 * Android's supplicant then enforces [caCertificates] and [serverNames] inside
 * the handshake, so the inner exchange (and the password) only ever reaches a
 * server that proves this identity (docs/eduroam-decisions.md, D2).
 */
class AuthenticatedEapPins private constructor(
    val realm: String,
    val method: EapMethod,
    val phase2: Phase2,
    /** Sent in the clear before the tunnel is up; never the user's real identity. */
    val outerIdentity: String,
    val caCertificates: List<X509Certificate>,
    /** Exact server names, each used as a `domain_suffix_match` entry. */
    val serverNames: List<String>,
    val source: PinSource
) {
    override fun toString() = "AuthenticatedEapPins($realm, $method/$phase2, servers=$serverNames, source=$source)"

    companion object {
        /** Tolerated device-clock skew for a record's `issued_at`. */
        private val CLOCK_SKEW: Duration = Duration.ofMinutes(5)

        /**
         * Verifies [bytes] (a binary [SignedRealmRecord]) against [key] first
         * and only then decodes the record: nothing unsigned is ever parsed
         * into pins. [expectedRealm] is the realm of the user's identity.
         */
        fun fromSignedRealmRecord(
            bytes: ByteArray,
            expectedRealm: String,
            key: PublicKey = PinnedRealmKey.publicKey,
            now: Instant = Instant.now()
        ): PinResult {
            val signed = runCatching { SignedRealmRecord.ADAPTER.decode(bytes) }.getOrNull()
                ?: return PinResult.Rejected("the realm record is malformed")
            val recordBytes = signed.record.toByteArray()
            val valid = runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(recordBytes)
                    verify(signed.signature.toByteArray())
                }
            }.getOrDefault(false)
            if (!valid) return PinResult.Rejected("the realm record's signature doesn't verify")

            val record = runCatching { RealmRecord.ADAPTER.decode(recordBytes) }.getOrNull()
                ?: return PinResult.Rejected("the signed realm record can't be decoded")
            if (!record.realm.equals(expectedRealm, ignoreCase = true)) {
                return PinResult.Rejected("the record is for realm ${record.realm}, not $expectedRealm")
            }
            val issuedAt = Instant.ofEpochSecond(record.issued_at)
            val expiresAt = Instant.ofEpochSecond(record.expires_at)
            if (record.expires_at <= 0L || !now.isBefore(expiresAt)) {
                return PinResult.Rejected("the realm record expired at $expiresAt")
            }
            if (issuedAt.isAfter(now.plus(CLOCK_SKEW))) {
                return PinResult.Rejected("the realm record is issued in the future ($issuedAt); check the device clock")
            }

            val method = parseMethod(record.eap_method) ?: return PinResult.Rejected("unsupported EAP method \"${record.eap_method}\"")
            val phase2 = parsePhase2(method, record.phase2) ?: return PinResult.Rejected("unsupported inner method \"${record.phase2}\" for $method")
            val outer = outerIdentity(record.outer_identity, record.realm) ?: return PinResult.Rejected(
                "the outer identity ${record.outer_identity} isn't in realm ${record.realm}")
            val cas = record.ca_der.map { der ->
                parseCertificate(der.toByteArray()) ?: return PinResult.Rejected("a CA certificate in the record doesn't parse")
            }
            return build(record.realm.lowercase(), method, phase2, outer, cas, record.server_names,
                PinSource.RealmRecord(issuedAt, expiresAt, record.support_url.ifBlank { null }))
        }

        /**
         * Pins for [userRealm] from [certificate]'s `aaa` entry covering it
         * (a local user). Only servers that come with CA certificates can be
         * pinned: Android pins a CA certificate, not a key hash, so an entry
         * with only `ca_spki_sha256` or leaf pins gives nothing to enforce.
         * Several servers in the entry are pinned together (any registered CA,
         * any registered name); they are one operator's servers for one realm.
         */
        fun fromSiteCertificate(certificate: GeoCertificate, userRealm: String): PinResult {
            val aaa = certificate.wifi.enterprise?.aaa.orEmpty().firstOrNull { it.coversRealm(userRealm) }
                ?: return PinResult.Rejected("${certificate.certificateId} registers no servers for realm $userRealm")
            val (method, phase2) = aaa.eapMethods.firstNotNullOfOrNull { spec ->
                val m = parseMethod(spec.method) ?: return@firstNotNullOfOrNull null
                spec.phase2.firstNotNullOfOrNull { parsePhase2(m, it) }?.let { m to it }
            } ?: return PinResult.Rejected("${certificate.certificateId} lists no EAP method this app supports for $userRealm")
            val pinnable = aaa.servers.filter { it.names.isNotEmpty() }
                .map { it.names to it.pinnedCaCertificates() }
                .filter { (_, cas) -> cas.isNotEmpty() }
            if (pinnable.isEmpty()) {
                return PinResult.Rejected("${certificate.certificateId} registers no CA certificate for $userRealm, so Android has nothing to pin")
            }
            val realm = userRealm.lowercase()
            return build(realm, method, phase2, "anonymous@$realm",
                pinnable.flatMap { it.second }.distinctBy { it.encoded.contentHashCode() },
                pinnable.flatMap { it.first }.distinct(),
                PinSource.SiteCertificate(certificate.certificateId))
        }

        private fun build(
            realm: String, method: EapMethod, phase2: Phase2, outer: String,
            cas: List<X509Certificate>, names: List<String>, source: PinSource
        ): PinResult {
            if (cas.isEmpty()) return PinResult.Rejected("no CA certificate to pin")
            // A leaf certificate pinned as "CA" would also be accepted by the supplicant;
            // insisting on real CA certificates keeps the pin's meaning what it says.
            if (cas.any { it.basicConstraints < 0 }) return PinResult.Rejected("a pinned certificate is not a CA certificate")
            if (names.isEmpty()) return PinResult.Rejected("no server name to pin; a CA pin alone accepts every server that CA signed")
            if (names.any { !SERVER_NAME.matches(it) }) return PinResult.Rejected("invalid server name in ${names.joinToString()}")
            return PinResult.Ok(AuthenticatedEapPins(realm, method, phase2, outer, cas, names.map { it.lowercase() }, source))
        }

        /** DNS name; notably no ';', which separates entries in `domain_suffix_match`. */
        private val SERVER_NAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$""")

        private fun parseMethod(value: String): EapMethod? = when (value.trim().uppercase()) {
            "PEAP" -> EapMethod.PEAP
            "TTLS", "EAP-TTLS" -> EapMethod.TTLS
            else -> null
        }

        private fun parsePhase2(method: EapMethod, value: String): Phase2? = when (value.trim().uppercase()) {
            "MSCHAPV2" -> Phase2.MSCHAPV2
            "GTC" -> Phase2.GTC
            "PAP" -> Phase2.PAP.takeIf { method == EapMethod.TTLS } // PAP inside PEAP isn't a thing
            "MSCHAP" -> Phase2.MSCHAP.takeIf { method == EapMethod.TTLS }
            else -> null
        }

        /**
         * The outer identity is what the federation routes on, so it must be in
         * the record's own realm. Blank means "not given"; then an anonymous
         * one is used rather than leaking the real identity in the clear.
         */
        private fun outerIdentity(value: String, realm: String): String? {
            if (value.isBlank()) return "anonymous@${realm.lowercase()}"
            return value.takeIf { it.substringAfterLast('@', "").equals(realm, ignoreCase = true) }
        }

        private fun parseCertificate(der: ByteArray): X509Certificate? = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.getOrNull()
    }
}
