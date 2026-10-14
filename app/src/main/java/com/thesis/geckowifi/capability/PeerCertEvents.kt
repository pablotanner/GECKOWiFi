package com.thesis.geckowifi.capability

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The RADIUS server's certificate chain from one EAP handshake, as reported by
 * wpa_supplicant. [certificates] is leaf first and only filled when every
 * depth came with its certificate (`cert=`, which needs `cert_in_cb`);
 * otherwise only [subjects] and [hashes] are known, which isn't enough to
 * compare key pins.
 */
data class PeerChain(
    val certificates: List<X509Certificate>,
    /** Subject per depth, leaf first. */
    val subjects: List<String>,
    /** SHA-256 of each certificate's DER (hex), leaf first, where reported. */
    val hashes: List<String?>,
    /** DNS names from `CTRL-EVENT-EAP-PEER-ALT depth=0`. */
    val leafAltNames: List<String>
) {
    val isComplete: Boolean get() = certificates.isNotEmpty() && certificates.size == subjects.size
}

private val PEER_CERT = Regex("""CTRL-EVENT-EAP-PEER-CERT depth=(\d+) subject='([^']*)'(.*)""")
private val PEER_ALT = Regex("""CTRL-EVENT-EAP-PEER-ALT depth=(\d+) (\S+)""")
private val HASH = Regex("""\bhash=([0-9a-fA-F]+)""")
private val CERT = Regex("""\bcert=([0-9a-fA-F]+)""")

/**
 * Extracts the **last** handshake's chain from wpa_supplicant output (control
 * interface events or logcat lines; prefixes like `<3>` are ignored).
 *
 * A handshake starts at `CTRL-EVENT-EAP-STARTED`, or wherever the depth goes
 * up again: wpa_supplicant reports a chain from the top down, ending with
 * depth 0 (the server's own certificate), and may report each depth twice
 * (`hash=` and `cert=`). Within a handshake the latest event per depth wins.
 * Null if no handshake reached depth 0.
 */
fun parsePeerCertChain(lines: List<String>): PeerChain? {
    data class Event(val depth: Int, val subject: String, val hash: String?, val certHex: String?)
    class Handshake { val events = mutableListOf<Event>(); val altNames = mutableListOf<String>() }

    val handshakes = mutableListOf(Handshake())
    for (line in lines) {
        if ("CTRL-EVENT-EAP-STARTED" in line) {
            if (handshakes.last().events.isNotEmpty()) handshakes += Handshake()
            continue
        }
        PEER_CERT.find(line)?.let { m ->
            val rest = m.groupValues[3]
            val event = Event(m.groupValues[1].toInt(), m.groupValues[2],
                HASH.find(rest)?.groupValues?.get(1), CERT.find(rest)?.groupValues?.get(1))
            val current = handshakes.last()
            if (current.events.isNotEmpty() && event.depth > current.events.last().depth) handshakes += Handshake()
            handshakes.last().events += event
        }
        PEER_ALT.find(line)?.let { m ->
            if (m.groupValues[1] == "0") handshakes.last().altNames += m.groupValues[2]
        }
    }

    val last = handshakes.lastOrNull { hs -> hs.events.any { it.depth == 0 } } ?: return null
    val byDepth = last.events.groupBy { it.depth }
    val depths = (0..byDepth.keys.max()).map { d -> byDepth[d].orEmpty() }
    val certificates = depths.map { events -> events.lastOrNull { it.certHex != null }?.certHex?.let(::decodeCertificate) }

    return PeerChain(
        certificates = if (certificates.all { it != null }) certificates.filterNotNull() else emptyList(),
        subjects = depths.map { events -> events.lastOrNull()?.subject.orEmpty() },
        hashes = depths.map { events -> events.lastOrNull { it.hash != null }?.hash },
        leafAltNames = last.altNames.filter { it.startsWith("DNS:") }.map { it.removePrefix("DNS:") }.distinct()
    )
}

private fun decodeCertificate(hex: String): X509Certificate? = runCatching {
    val der = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
}.getOrNull()
