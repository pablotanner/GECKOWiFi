package com.thesis.geckowifi.data.remote

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

    /** geopki signs the raw to-be-signed bytes (ecdsa.SignASN1, no hashing step). */
    private fun signed(head: MapHead): ByteArray {
        val signer = Signature.getInstance("NONEwithECDSA").apply { initSign(keys.private); update(head.tbsBytes()) }
        return SignedMapHead(head, signer.sign()).toProtoBytes()
    }

    private val head = MapHead(SHA256Hash.sha256("root".toByteArray()), signedAt, emptyList())

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
     * geopki signs root hash | timestamp | ... raw with ECDSA (ecdsa.SignASN1),
     * which uses only the first 32 bytes of its input on P-256: the root hash.
     * The timestamp is not covered, so a saved answer is compared by root hash.
     */
    @Test
    fun alteredTimestamp_stillVerifies_soOnlyTheRootHashIsSigned() {
        val proto = geopki.SignedMapHead.ADAPTER.decode(signed(head))
        val forged = geopki.SignedMapHead.ADAPTER.encode(proto.copy(Timestamp = signedAt + 3_600_000_000_000L))
        val answer = GeckoClient.mapHeadFrom(forged, keys.public)
        assertTrue(answer is MapHeadAnswer.Current)
        assertEquals(head.rootHash.bytes.toList(), (answer as MapHeadAnswer.Current).rootHash.toList())
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
