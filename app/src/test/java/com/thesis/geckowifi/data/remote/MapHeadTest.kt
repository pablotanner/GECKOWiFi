package com.thesis.geckowifi.data.remote

import com.thesis.geckowifi.geopki.crypto.MAP_HEAD_VERSION
import com.thesis.geckowifi.geopki.crypto.MapHead
import com.thesis.geckowifi.geopki.crypto.SHA256Hash
import com.thesis.geckowifi.geopki.crypto.SignedMapHead
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** The map-head check behind confirming a saved answer (D26). */
class MapHeadTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val signedAt = 1_793_000_000_123_456_789L // nanoseconds, as geopki signs

    /** geopki signs the SHA-256 of the to-be-signed bytes (map version 2). */
    private fun signed(head: MapHead): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(head.tbsBytes()) }
        return SignedMapHead(head, signer.sign()).toProtoBytes()
    }

    private val head = MapHead(MAP_HEAD_VERSION, SHA256Hash.sha256("root".toByteArray()), signedAt, emptyList())

    @Test
    fun validSignature_givesTheRootHash() {
        val answer = GeckoClient.mapHeadFrom(signed(head), keys.public) as MapHeadAnswer.Current
        assertEquals(head.rootHash.bytes.toList(), answer.rootHash.toList())
    }

    @Test
    fun alteredRootHash_isAProofFailure() {
        val proto = geopki.SignedMapHead.ADAPTER.decode(signed(head))
        val forged = geopki.SignedMapHead.ADAPTER.encode(
            proto.copy(RootHash = SHA256Hash.sha256("other".toByteArray()).bytes.toByteString()))
        assertTrue(GeckoClient.mapHeadFrom(forged, keys.public) is MapHeadAnswer.ProofFailure)
    }

    /**
     * Map version 1 signed the raw bytes, of which ECDSA on P-256 used only the
     * root hash, so a changed timestamp still verified. Version 2 signs their
     * SHA-256: every field is covered.
     */
    @Test
    fun alteredTimestamp_isAProofFailure() {
        val proto = geopki.SignedMapHead.ADAPTER.decode(signed(head))
        val forged = geopki.SignedMapHead.ADAPTER.encode(proto.copy(Timestamp = signedAt + 3_600_000_000_000L))
        assertTrue(GeckoClient.mapHeadFrom(forged, keys.public) is MapHeadAnswer.ProofFailure)
    }

    @Test
    fun mapHeadOfAnotherVersion_isAProofFailure() {
        assertTrue(GeckoClient.mapHeadFrom(signed(head.copy(version = 1)), keys.public) is MapHeadAnswer.ProofFailure)
    }

    @Test
    fun anotherServersKey_isAProofFailure() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertTrue(GeckoClient.mapHeadFrom(signed(head), other.public) is MapHeadAnswer.ProofFailure)
    }

    @Test
    fun garbage_isUnreachableNotAFailedProof() {
        assertTrue(GeckoClient.mapHeadFrom(byteArrayOf(0x0a, 0x7f, 0x01), keys.public) is MapHeadAnswer.Unreachable)
    }
}
