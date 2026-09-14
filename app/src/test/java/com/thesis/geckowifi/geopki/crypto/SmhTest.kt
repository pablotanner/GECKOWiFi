package com.thesis.geckowifi.geopki.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class SmhTest {

    private fun generateEcKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    // Matches production: geopki signs the raw TBS bytes with no SHA-256 step
    // (see Smh.kt's verifyEcdsaAsn1 doc) - "NONEwithECDSA", not "SHA256withECDSA".
    private fun sign(privateKey: java.security.PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("NONEwithECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()

    @Test
    fun signedMapHead_verifiesGenuineSignature() {
        val keyPair = generateEcKeyPair()
        val mapHead = MapHead(
            rootHash = SHA256Hash(ByteArray(32) { it.toByte() }),
            timestamp = 1_700_000_000L,
            coveredCtLogServers = listOf(CtLogServer(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)))
        )
        val signature = sign(keyPair.private, mapHead.tbsBytes())
        val smh = SignedMapHead(mapHead, signature)

        assertTrue(smh.verify(keyPair.public))
    }

    @Test
    fun signedMapHead_rejectsTamperedRootHash() {
        val keyPair = generateEcKeyPair()
        val mapHead = MapHead(
            rootHash = SHA256Hash(ByteArray(32) { it.toByte() }),
            timestamp = 1_700_000_000L,
            coveredCtLogServers = emptyList()
        )
        val signature = sign(keyPair.private, mapHead.tbsBytes())

        val tampered = mapHead.copy(rootHash = SHA256Hash(ByteArray(32) { (it + 1).toByte() }))
        val smh = SignedMapHead(tampered, signature)

        assertFalse(smh.verify(keyPair.public))
    }

    @Test
    fun signedMapHead_rejectsWrongKey() {
        val keyPair = generateEcKeyPair()
        val otherKeyPair = generateEcKeyPair()
        val mapHead = MapHead(SHA256Hash(ByteArray(32) { it.toByte() }), 1L, emptyList())
        val signature = sign(keyPair.private, mapHead.tbsBytes())

        assertFalse(SignedMapHead(mapHead, signature).verify(otherKeyPair.public))
    }

    @Test
    fun signedConsistencyHead_verifiesGenuineSignature() {
        val keyPair = generateEcKeyPair()
        val sch = SignedConsistencyHead(
            rootHash = SHA256Hash(ByteArray(32) { it.toByte() }),
            timestamp = 1_700_000_000L,
            size = 42L,
            signature = ByteArray(0)
        )
        val signature = sign(keyPair.private, sch.tbsBytes())

        assertTrue(sch.copy(signature = signature).verify(keyPair.public))
    }

    @Test
    fun signedMapHead_toProtoBytes_roundTripsThroughWire() {
        val mapHead = MapHead(
            rootHash = SHA256Hash(ByteArray(32) { it.toByte() }),
            timestamp = 123L,
            coveredCtLogServers = listOf(CtLogServer(byteArrayOf(9, 8), byteArrayOf(7, 6)))
        )
        val smh = SignedMapHead(mapHead, signature = byteArrayOf(1, 2, 3))

        val decoded = geopki.SignedMapHead.ADAPTER.decode(smh.toProtoBytes())
        val roundTripped = SignedMapHead.fromProto(decoded)

        assertTrue(roundTripped.mapHead.rootHash.bytes.contentEquals(mapHead.rootHash.bytes))
        assertTrue(roundTripped.signature.contentEquals(smh.signature))
    }
}
