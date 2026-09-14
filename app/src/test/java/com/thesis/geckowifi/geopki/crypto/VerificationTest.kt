package com.thesis.geckowifi.geopki.crypto

import com.thesis.geckowifi.geopki.bitstring.ROOT_NODE
import com.thesis.geckowifi.geopki.bitstring.RawXYBitString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * End-to-end tests for [verifyResponse] against synthetic (self-signed)
 * `geopki.Response` messages - the Wire-generated types from step 1, built
 * the same way a real server response would be. No captured real server
 * response was available in this repo to test against; these are
 * self-consistency tests, not upstream parity tests.
 */
class VerificationTest {

    private fun generateEcKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    // Matches production: geopki signs the raw TBS bytes with no SHA-256 step.
    private fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("NONEwithECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()

    private fun signedMapHeadProto(rootHash: SHA256Hash, privateKey: PrivateKey): geopki.SignedMapHead {
        val mapHead = MapHead(rootHash = rootHash, timestamp = 1L, coveredCtLogServers = emptyList())
        val signature = sign(privateKey, mapHead.tbsBytes())
        return SignedMapHead(mapHead, signature).toProto()
    }

    private fun rootProtoNode(
        zLeftChildHash: ByteArray? = null,
        certificateHashes: List<ByteArray> = emptyList()
    ) = geopki.Node(
        geopki_XYBitString = 0L,
        XYBitStringLen = 0,
        ZBitString = 0,
        ZBitStringLen = 0,
        ZLeftChildHash = zLeftChildHash?.toByteString(),
        CertificateHashes = certificateHashes.map { it.toByteString() }
    )

    private val fullRangeQuery = GeoPkiQuery(
        xyBitStrings = listOf(RawXYBitString(0uL, 0)),
        minAltitude = 0,
        maxAltitude = 32767
    )

    @Test
    fun verifyResponse_succeedsForEmptyRoot() {
        val keyPair = generateEcKeyPair()
        val rootHash = Node(pair = ROOT_NODE).hash()

        val response = geopki.Response(
            geopki_SignedMapHead = signedMapHeadProto(rootHash, keyPair.private),
            Nodes = listOf(rootProtoNode())
        )

        assertTrue(verifyResponse(response, fullRangeQuery, keyPair.public).isEmpty())
    }

    @Test
    fun verifyResponse_returnsCertificateHashWhenPresentInNodeAndBody() {
        val keyPair = generateEcKeyPair()
        val certBytes = "hello geopki".toByteArray()
        val certHash = SHA256Hash.sha256(certBytes)
        val rootHash = Node(pair = ROOT_NODE, certificateHashes = listOf(certHash)).hash()

        val response = geopki.Response(
            geopki_SignedMapHead = signedMapHeadProto(rootHash, keyPair.private),
            Nodes = listOf(rootProtoNode(certificateHashes = listOf(certHash.bytes))),
            Certificates = listOf(certBytes.toByteString())
        )

        assertEquals(1, verifyResponse(response, fullRangeQuery, keyPair.public).size)
    }

    @Test
    fun verifyResponse_throwsWhenSignatureInvalid() {
        val keyPair = generateEcKeyPair()
        val otherKeyPair = generateEcKeyPair()
        val rootHash = Node(pair = ROOT_NODE).hash()

        val response = geopki.Response(
            geopki_SignedMapHead = signedMapHeadProto(rootHash, keyPair.private),
            Nodes = listOf(rootProtoNode())
        )

        assertThrows(GeoPkiVerificationException::class.java) {
            verifyResponse(response, fullRangeQuery, otherKeyPair.public)
        }
    }

    @Test
    fun verifyResponse_throwsWhenRootHashMismatches() {
        val keyPair = generateEcKeyPair()
        val wrongRootHash = SHA256Hash(ByteArray(32) { 9 })

        val response = geopki.Response(
            geopki_SignedMapHead = signedMapHeadProto(wrongRootHash, keyPair.private),
            Nodes = listOf(rootProtoNode())
        )

        assertThrows(GeoPkiVerificationException::class.java) {
            verifyResponse(response, fullRangeQuery, keyPair.public)
        }
    }

    @Test
    fun verifyResponse_throwsWhenRootNodeMissing() {
        val keyPair = generateEcKeyPair()
        val rootHash = Node(pair = ROOT_NODE).hash()

        val response = geopki.Response(
            geopki_SignedMapHead = signedMapHeadProto(rootHash, keyPair.private),
            Nodes = emptyList()
        )

        assertThrows(GeoPkiVerificationException::class.java) {
            verifyResponse(response, fullRangeQuery, keyPair.public)
        }
    }

    @Test
    fun verifyResponse_throwsWhenCertificateNotContainedInAnyNode() {
        val keyPair = generateEcKeyPair()
        val rootHash = Node(pair = ROOT_NODE).hash()

        val response = geopki.Response(
            geopki_SignedMapHead = signedMapHeadProto(rootHash, keyPair.private),
            Nodes = listOf(rootProtoNode()),
            Certificates = listOf("unrelated".toByteArray().toByteString())
        )

        assertThrows(GeoPkiVerificationException::class.java) {
            verifyResponse(response, fullRangeQuery, keyPair.public)
        }
    }

    @Test
    fun verifyResponse_throwsWhenServerWithholdsRequiredSubtree() {
        val keyPair = generateEcKeyPair()
        // Root has an explicit zLeftChildHash but no matching node was sent:
        // the server is asserting "there's data here" without disclosing it.
        // Must be rejected even though the signature and root hash line up.
        val withheldHash = SHA256Hash(ByteArray(32) { 3 })
        val rootHash = Node(pair = ROOT_NODE, zLeftChildHash = withheldHash).hash()

        val response = geopki.Response(
            geopki_SignedMapHead = signedMapHeadProto(rootHash, keyPair.private),
            Nodes = listOf(rootProtoNode(zLeftChildHash = withheldHash.bytes))
        )

        assertThrows(GeoPkiVerificationException::class.java) {
            verifyResponse(response, fullRangeQuery, keyPair.public)
        }
    }

    @Test
    fun verifyResponse_throwsWhenNodeHasInvalidLowerBits() {
        val keyPair = generateEcKeyPair()
        val rootHash = Node(pair = ROOT_NODE).hash()

        val response = geopki.Response(
            geopki_SignedMapHead = signedMapHeadProto(rootHash, keyPair.private),
            // xyBitStringLen=0 means no bits are "used" - a non-zero raw value
            // here is an inconsistent encoding and must be rejected.
            Nodes = listOf(geopki.Node(geopki_XYBitString = 1L, XYBitStringLen = 0, ZBitString = 0, ZBitStringLen = 0))
        )

        assertThrows(GeoPkiVerificationException::class.java) {
            verifyResponse(response, fullRangeQuery, keyPair.public)
        }
    }
}
