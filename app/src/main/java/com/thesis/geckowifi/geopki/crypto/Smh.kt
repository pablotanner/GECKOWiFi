package com.thesis.geckowifi.geopki.crypto

import java.io.ByteArrayOutputStream
import java.security.PublicKey
import java.security.Signature
import okio.ByteString.Companion.toByteString
import geopki.CTLogServer as ProtoCTLogServer
import geopki.SignedConsistencyHead as ProtoSignedConsistencyHead
import geopki.SignedMapHead as ProtoSignedMapHead

/**
 * Kotlin port of netsec-ethz/geopki `pkg/crypto/smh.go`.
 *
 * Only `Verify` is ported (not `Sign`) - the Android client never signs a map
 * head, only verifies the server's signature.
 */

data class CtLogServer(val logId: ByteArray, val signedTreeHead: ByteArray)

/** The map format this client understands (geopki `crypto.MapHeadVersion`). */
const val MAP_HEAD_VERSION = 2

data class MapHead(
    val version: Int,
    val rootHash: SHA256Hash,
    val timestamp: Long,
    val coveredCtLogServers: List<CtLogServer>
) {
    /** The bytes the signature is computed over: version (BE32) | root | timestamp (BE64) | logs. */
    fun tbsBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bigEndianIntBytes(version))
        out.write(rootHash.bytes)
        out.write(bigEndianLongBytes(timestamp))
        coveredCtLogServers.forEach {
            out.write(it.logId)
            out.write(it.signedTreeHead)
        }
        return out.toByteArray()
    }
}

class SignedMapHead(val mapHead: MapHead, val signature: ByteArray) {

    /** Whether the signature is valid and the map is of [MAP_HEAD_VERSION]. */
    fun verify(publicKey: PublicKey): Boolean =
        mapHead.version == MAP_HEAD_VERSION && verifyEcdsaAsn1(publicKey, mapHead.tbsBytes(), signature)

    fun toProto(): ProtoSignedMapHead = ProtoSignedMapHead(
        RootHash = mapHead.rootHash.bytes.toByteString(),
        Timestamp = mapHead.timestamp,
        CoveredCTLogServers = mapHead.coveredCtLogServers.map {
            ProtoCTLogServer(LogId = it.logId.toByteString(), SignedTreeHead = it.signedTreeHead.toByteString())
        },
        Signature = signature.toByteString(),
        Version = mapHead.version
    )

    /** Re-serializes this SMH (including the signature). Mirrors Go's `SignedMapHead.Marshal`. */
    fun toProtoBytes(): ByteArray = ProtoSignedMapHead.ADAPTER.encode(toProto())

    companion object {
        fun fromProto(proto: ProtoSignedMapHead): SignedMapHead = SignedMapHead(
            mapHead = MapHead(
                version = proto.Version,
                rootHash = bytesToHash(proto.RootHash.toByteArray()),
                timestamp = proto.Timestamp,
                coveredCtLogServers = proto.CoveredCTLogServers.map {
                    CtLogServer(it.LogId.toByteArray(), it.SignedTreeHead.toByteArray())
                }
            ),
            signature = proto.Signature.toByteArray()
        )
    }
}

/**
 * Signed Consistency Head.
 *
 * Not a direct port: geopki's `crypto/verification.go` uses
 * `NewSCHFromCommSCH`/`sch.Verify`/`sch.Size`/`sch.RootHash`, but the type is
 * defined elsewhere upstream. Reconstructed here from
 * `response.proto`'s `SignedConsistencyHead` message shape and from
 * [MapHead.tbsBytes]'s pattern (rootHash || timestamp(BE64) || size(BE64)).
 * CONFIRMED against a real signature from a live geopki server (2026-09-11,
 * see `LiveFixtureTest`) - this TBS layout is correct.
 */
data class SignedConsistencyHead(
    val rootHash: SHA256Hash,
    val timestamp: Long,
    val size: Long,
    val signature: ByteArray
) {
    fun tbsBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(rootHash.bytes)
        out.write(bigEndianLongBytes(timestamp))
        out.write(bigEndianLongBytes(size))
        return out.toByteArray()
    }

    fun verify(publicKey: PublicKey): Boolean = verifyEcdsaAsn1(publicKey, tbsBytes(), signature)

    companion object {
        fun fromProto(proto: ProtoSignedConsistencyHead): SignedConsistencyHead = SignedConsistencyHead(
            rootHash = bytesToHash(proto.RootHash.toByteArray()),
            timestamp = proto.Timestamp,
            size = proto.Size,
            signature = proto.Signature.toByteArray()
        )
    }
}

/**
 * Verifies an ASN.1/X9.62-DER ECDSA signature over the SHA-256 of [data] -
 * matches Go's `ecdsa.VerifyASN1(pub, sha256(tbsBytes), sig)` since map
 * version 2.
 *
 * Version 1 signed the raw TBS bytes (`NONEwithECDSA` here), and ECDSA on
 * P-256 only uses the first 32 bytes of its input: only the root hash was
 * signed, the timestamp, logs and SCH size were not (see
 * `evidence/findings/geopki-signature-covers-only-root-hash.md`).
 */
internal fun verifyEcdsaAsn1(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(publicKey)
    verifier.update(data)
    return verifier.verify(signature)
}

internal fun bigEndianIntBytes(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte()
)

internal fun bigEndianLongBytes(value: Long): ByteArray = byteArrayOf(
    (value ushr 56).toByte(),
    (value ushr 48).toByte(),
    (value ushr 40).toByte(),
    (value ushr 32).toByte(),
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte()
)
