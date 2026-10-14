package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.AAADomain
import com.thesis.geckowifi.data.model.AuthServer
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The RADIUS server identity seen in an EAP handshake, reduced to what a
 * GeoCert's [AuthServer] entries are compared with.
 */
data class ObservedAuthServer(
    /** SHA-256 of the leaf certificate's SPKI, base64. */
    val leafSpkiSha256: String?,
    /** The leaf's SAN dNSNames, or its subject CN if it has none. */
    val leafNames: List<String>,
    /**
     * SPKI hashes of the certificates above the leaf, but only as far as each
     * one's signature over the one below it was verified. During an EAP probe
     * the supplicant's own validation is off, so a chain is attacker-supplied:
     * appending the genuine CA certificate without its signature must not count.
     */
    val verifiedIssuerSpkiSha256: List<String>
) {
    val displayName: String get() = leafNames.firstOrNull() ?: "authentication server"

    companion object {
        /** [chain] is leaf first, as wpa_supplicant reports it (depth 0, 1, ...). */
        fun fromChain(chain: List<X509Certificate>): ObservedAuthServer? {
            val leaf = chain.firstOrNull() ?: return null
            val issuers = mutableListOf<String>()
            for (i in 1 until chain.size) {
                val signedByNext = runCatching { chain[i - 1].verify(chain[i].publicKey) }.isSuccess
                if (!signedByNext) break
                issuers += spkiSha256(chain[i])
            }
            return ObservedAuthServer(spkiSha256(leaf), dnsNames(leaf), issuers)
        }

        fun spkiSha256(cert: X509Certificate): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

        private const val SAN_DNS_NAME = 2

        private fun dnsNames(cert: X509Certificate): List<String> {
            val san = cert.subjectAlternativeNames.orEmpty()
                .filter { it[0] == SAN_DNS_NAME }
                .map { it[1] as String }
            if (san.isNotEmpty()) return san
            return cert.subjectX500Principal.name.split(',')
                .map { it.trim() }
                .filter { it.startsWith("CN=") }
                .map { it.removePrefix("CN=") }
        }
    }
}

/** Whether this registered server accepts [observed]. An entry without pins accepts nothing. */
fun AuthServer.accepts(observed: ObservedAuthServer): Boolean {
    if (observed.leafSpkiSha256 != null && observed.leafSpkiSha256 in pinnedSPKIHashes) return true
    // A CA pin alone would accept every server that CA signs, so it needs a name too.
    if (caSPKIHashes.isEmpty() || names.isEmpty()) return false
    val named = observed.leafNames.any { leaf -> names.any { it.equals(leaf, ignoreCase = true) } }
    return named && observed.verifiedIssuerSpkiSha256.any { it in caSPKIHashes }
}

/**
 * Enterprise (802.1X / eduroam) counterpart of [PortalSession]'s domain
 * matching: compares the RADIUS server seen in the handshake with the
 * enterprise identities registered for this SSID and location. Stateless,
 * because an EAP handshake has no chain of hops to anchor.
 *
 * Which servers apply depends on the user's realm ([userRealm], from their
 * `user@realm` identity):
 * - a realm listed in an `aaa` entry: the user is local, and the server must
 *   be one of that entry's servers, else CONFLICT;
 * - in a federation (eduroam) with no matching entry: the user is a visitor
 *   whose EAP is proxied to their home institution. That server can't be
 *   registered here, so the result is UNVERIFIED with a reason, never a warning;
 * - in a closed network (no federation): every user sees the registered
 *   servers, whatever the realm.
 *
 * [candidates] are already filtered to the SSID, the device's location and
 * certificates this client can evaluate. A certificate that registers the
 * SSID without an enterprise section gives UNRECOGNIZED, which the engine's
 * SSID exclusivity turns into CONFLICT. Auth modes are compared by the engine.
 */
fun evaluateEnterpriseNetwork(
    observed: ObservedAuthServer,
    userRealm: String?,
    candidates: List<GeoCertificate>
): VerificationResult {
    val host = observed.displayName
    if (candidates.isEmpty()) {
        return VerificationResult(VerificationState.UNVERIFIED, host, reason = "no certificate registered at this location")
    }
    val enterprise = candidates.filter { it.wifi.enterprise != null }
    if (enterprise.isEmpty()) {
        return VerificationResult(VerificationState.UNRECOGNIZED, host,
            reason = "certificates exist here, but none registers an enterprise network")
    }

    val groups: List<Pair<GeoCertificate, AAADomain>> =
        enterprise.flatMap { cert -> cert.wifi.enterprise!!.aaa.map { cert to it } }
    val federation = enterprise.firstNotNullOfOrNull { cert -> cert.wifi.enterprise!!.federation?.takeIf { it.isNotBlank() } }
    val localGroups = userRealm?.let { realm -> groups.filter { it.second.coversRealm(realm) } }.orEmpty()

    val toCheck = when {
        localGroups.isNotEmpty() -> localGroups
        federation != null && userRealm != null -> return visitor(host, enterprise.first(), federation, userRealm)
        else -> groups
    }

    val match = toCheck.firstNotNullOfOrNull { (cert, group) ->
        group.servers.firstOrNull { it.accepts(observed) }?.let { cert to it }
    }
    if (match != null) {
        val (cert, _) = match
        return VerificationResult(
            VerificationState.VERIFIED, host,
            matchedIdentifier = host,
            matchedCertificateId = cert.certificateId,
            reason = if (localGroups.isNotEmpty()) "authentication server matched the one registered for realm $userRealm"
                     else "authentication server matched"
        )
    }

    if (federation != null && userRealm == null) {
        // Could be a visitor's home server: without the realm there is nothing to compare it with.
        return VerificationResult(VerificationState.UNVERIFIED, host,
            matchedCertificateId = enterprise.first().certificateId,
            reason = "$federation is registered here, but without your realm the authentication server can't be checked")
    }
    val (cert, _) = toCheck.firstOrNull() ?: (enterprise.first() to null)
    return VerificationResult(
        VerificationState.CONFLICT, host,
        matchedCertificateId = cert.certificateId,
        reason = "authentication server is not the one registered here" + (userRealm?.let { " for realm $it" } ?: ""),
        registeredIdentifier = toCheck.flatMap { it.second.servers }.flatMap { it.names }.firstOrNull(),
        presentedIdentifier = observed.leafSpkiSha256
    )
}

private fun visitor(host: String, cert: GeoCertificate, federation: String, realm: String) = VerificationResult(
    VerificationState.UNVERIFIED, host,
    matchedCertificateId = cert.certificateId,
    reason = "$federation site registered here" + (cert.subject?.name?.let { " by $it" } ?: "") +
        "; you are a visitor (realm $realm), so your home institution's server can't be checked here"
)
