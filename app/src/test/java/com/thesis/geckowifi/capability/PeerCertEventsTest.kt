package com.thesis.geckowifi.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * [parsePeerCertChain] against event lines in the shape wpa_supplicant emits
 * on its control interface (`<3>` level prefix, chain reported top-down,
 * `hash=` and `cert=` as separate events). Certificates are the lab chains
 * from test resources, hex-encoded the way wpa_supplicant writes them.
 */
class PeerCertEventsTest {

    private fun pem(name: String): X509Certificate =
        javaClass.getResourceAsStream("/eap-chains/$name.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hash(c: X509Certificate) = hex(MessageDigest.getInstance("SHA-256").digest(c.encoded))

    private fun events(depth: Int, c: X509Certificate, withCert: Boolean = true) = listOfNotNull(
        "<3>CTRL-EVENT-EAP-PEER-CERT depth=$depth subject='${c.subjectX500Principal.name}' hash=${hash(c)}",
        if (withCert) "<3>CTRL-EVENT-EAP-PEER-CERT depth=$depth subject='${c.subjectX500Principal.name}' cert=${hex(c.encoded)}" else null
    )

    private val genuine = listOf("<3>CTRL-EVENT-EAP-STARTED EAP authentication started", "<3>CTRL-EVENT-EAP-METHOD EAP vendor 0 method 25 (PEAP) selected") +
        events(1, pem("genuine-ca")) + events(0, pem("genuine-leaf")) +
        "<3>CTRL-EVENT-EAP-PEER-ALT depth=0 DNS:radius.gecko-a.lab"

    private val rogue = listOf("<3>CTRL-EVENT-EAP-STARTED EAP authentication started") +
        events(1, pem("rogue-ca")) + events(0, pem("rogue-leaf"))

    @Test
    fun fullChain_isLeafFirstAndComplete() {
        val chain = parsePeerCertChain(genuine)!!
        assertTrue(chain.isComplete)
        assertEquals(listOf(pem("genuine-leaf"), pem("genuine-ca")), chain.certificates)
        assertEquals(hash(pem("genuine-leaf")), chain.hashes[0])
        assertEquals(listOf("radius.gecko-a.lab"), chain.leafAltNames)
    }

    @Test
    fun hashOnlyEvents_areIncomplete() {
        // What logcat shows, or the control interface without cert_in_cb.
        val lines = events(1, pem("genuine-ca"), withCert = false) + events(0, pem("genuine-leaf"), withCert = false)
        val chain = parsePeerCertChain(lines)!!
        assertFalse(chain.isComplete)
        assertTrue(chain.certificates.isEmpty())
        assertEquals(2, chain.subjects.size)
        assertEquals(hash(pem("genuine-ca")), chain.hashes[1])
    }

    @Test
    fun lastHandshakeWins() {
        val chain = parsePeerCertChain(genuine + rogue)!!
        assertEquals(pem("rogue-leaf"), chain.certificates.first())
        assertTrue(chain.leafAltNames.isEmpty())
    }

    @Test
    fun handshakesWithoutStartEvents_splitWhereTheDepthGoesUp() {
        val lines = events(1, pem("genuine-ca")) + events(0, pem("genuine-leaf")) + events(1, pem("rogue-ca")) + events(0, pem("rogue-leaf"))
        assertEquals(listOf(pem("rogue-leaf"), pem("rogue-ca")), parsePeerCertChain(lines)!!.certificates)
    }

    @Test
    fun unfinishedHandshake_fallsBackToTheLastFinishedOne() {
        // The newest handshake was cut off before the server's own certificate.
        val lines = genuine + "<3>CTRL-EVENT-EAP-STARTED EAP authentication started" + events(1, pem("rogue-ca"))
        assertEquals(pem("genuine-leaf"), parsePeerCertChain(lines)!!.certificates.first())
    }

    @Test
    fun corruptCertificate_makesTheChainIncomplete() {
        val lines = events(1, pem("genuine-ca")) +
            "<3>CTRL-EVENT-EAP-PEER-CERT depth=0 subject='CN=x' cert=deadbeef"
        assertFalse(parsePeerCertChain(lines)!!.isComplete)
    }

    @Test
    fun noHandshake_isNull() {
        assertNull(parsePeerCertChain(listOf("<3>CTRL-EVENT-CONNECTED - Connection to 94:83:c4:97:c2:3a completed", "")))
    }
}
