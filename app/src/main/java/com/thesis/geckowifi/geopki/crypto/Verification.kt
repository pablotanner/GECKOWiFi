package com.thesis.geckowifi.geopki.crypto

import com.thesis.geckowifi.geopki.bitstring.RawBitStringPair
import com.thesis.geckowifi.geopki.bitstring.RawXYBitString
import com.thesis.geckowifi.geopki.bitstring.RawZBitString
import com.thesis.geckowifi.geopki.bitstring.XY_BITS
import com.thesis.geckowifi.geopki.bitstring.Z_BITS
import com.thesis.geckowifi.geopki.bitstring.bitString
import com.thesis.geckowifi.geopki.bitstring.isRoot
import com.thesis.geckowifi.geopki.bitstring.xyLeftChildPair
import com.thesis.geckowifi.geopki.bitstring.xyRightChildPair
import com.thesis.geckowifi.geopki.bitstring.zLeftChildPair
import com.thesis.geckowifi.geopki.bitstring.zRightChildPair
import java.security.PublicKey
import java.util.Base64
import geopki.Node as ProtoNode
import geopki.Request as ProtoRequest
import geopki.Response as ProtoResponse
import geopki.trillian.Proof as ProtoTrillianProof

/**
 * Kotlin port of netsec-ethz/geopki `pkg/crypto/verification.go` - "THE
 * critical one" per instructions.MD. Operates directly on the Wire-generated
 * protobuf types from step 1 (`geopki.Response`, `geopki.Request`), not on
 * any hand-rolled wire format - future client wrapper code (GeckoClient /
 * ProofVerifier, step 4) should call into [verifyResponse] rather than
 * re-parsing the response itself.
 *
 * A parsed-but-unverified response must never be treated as verified: every
 * exit point on a bad response is an exception, never a partial/best-effort
 * success value.
 */
class GeoPkiVerificationException(message: String) : Exception(message)

/** The subset of `comm.Query` (Go) needed to check response completeness. */
data class GeoPkiQuery(
    val xyBitStrings: List<RawXYBitString>,
    val minAltitude: Int,
    val maxAltitude: Int
) {
    companion object {
        fun fromProtoRequest(request: ProtoRequest): GeoPkiQuery = GeoPkiQuery(
            xyBitStrings = request.XYBitStrings.map {
                RawXYBitString(xyBitString = it.geopki_XYBitString.toULong(), xyBitStringLen = it.XYBitStringLen)
            },
            minAltitude = request.MinAltitude,
            maxAltitude = request.MaxAltitude
        )
    }
}

/** Reinterprets a Kotlin [Int] as the unsigned 32-bit value its bit pattern represents. */
private fun Int.toUnsignedLong(): Long = this.toLong() and 0xFFFFFFFFL

/**
 * Verifies a received [response] against [query] and the server's
 * [publicKey], returning the set of all certificate hashes (as unpadded
 * URL-safe base64) it vouches for. Performs no consistency (append-only log)
 * checks - see [ensureConsistency] for that.
 *
 * Throws [GeoPkiVerificationException] on any failure. Never returns a
 * result for a response whose signature, root hash, or completeness could
 * not be established.
 */
fun verifyResponse(response: ProtoResponse, query: GeoPkiQuery, publicKey: PublicKey): Set<String> {
    val protoSmh = response.geopki_SignedMapHead
        ?: throw GeoPkiVerificationException("response does not contain a SMH")
    val smh = SignedMapHead.fromProto(protoSmh)
    if (!smh.verify(publicKey)) throw GeoPkiVerificationException("signature on the SMH is invalid")

    val nodes = ArrayList<Node>(response.Nodes.size)
    val bitStringMap = HashMap<RawBitStringPair, Node>()
    var rootNode: Node? = null

    for (n in response.Nodes) {
        val node = parseNode(n)

        if (bitStringMap.containsKey(node.pair)) {
            throw GeoPkiVerificationException("received two nodes with the same bit string")
        }
        bitStringMap[node.pair] = node
        nodes += node

        if (node.pair.isRoot()) rootNode = node
    }

    val root = rootNode ?: throw GeoPkiVerificationException("response did not contain the root node")

    // Build the tree: for every node with no explicit child hash, look for
    // the actual child among the received nodes and attach it. If neither an
    // explicit hash nor a matching node was sent, the child is implicitly
    // the default (empty-subtree) hash.
    for (node in nodes) {
        if (node.xyLeftChildHashOrNull == null) {
            runCatching { node.pair.xyLeftChildPair() }.getOrNull()?.let { childPair ->
                bitStringMap[childPair]?.let { node.setXYLeftChild(it) }
            }
        }
        if (node.xyRightChildHashOrNull == null) {
            runCatching { node.pair.xyRightChildPair() }.getOrNull()?.let { childPair ->
                bitStringMap[childPair]?.let { node.setXYRightChild(it) }
            }
        }
        if (node.zLeftChildHashOrNull == null) {
            bitStringMap[node.pair.zLeftChildPair()]?.let { node.setZLeftChild(it) }
        }
        if (node.zRightChildHashOrNull == null) {
            bitStringMap[node.pair.zRightChildPair()]?.let { node.setZRightChild(it) }
        }
    }

    if (nodes.size != root.countNodes()) {
        throw GeoPkiVerificationException(
            "received invalid tree, cannot use all nodes in tree. built tree contains " +
                "${root.countNodes()} nodes but received ${nodes.size} nodes"
        )
    }

    val rootHash = root.hash()
    if (!rootHash.bytes.contentEquals(smh.mapHead.rootHash.bytes)) {
        throw GeoPkiVerificationException("computed root hash does not match the SMH")
    }

    for (xyBitString in query.xyBitStrings) {
        val path = xyBitString.bitString().toString()
        if (!root.pathIsComplete(path, query.minAltitude, query.maxAltitude)) {
            throw GeoPkiVerificationException(
                "server did not include all nodes required by the query ($path, ${query.minAltitude}, ${query.maxAltitude})"
            )
        }
    }

    val certificateHashStrings = HashSet<String>()
    for (node in nodes) {
        for (h in node.certificateHashes) {
            certificateHashStrings += base64UrlNoPad(h.bytes)
        }
    }

    for (certificate in response.Certificates) {
        val hash = SHA256Hash.sha256(certificate.toByteArray())
        val hashString = base64UrlNoPad(hash.bytes)
        if (hashString !in certificateHashStrings) {
            throw GeoPkiVerificationException(
                "certificate with hash '$hashString' is part of the response but is not contained in any node"
            )
        }
    }

    return certificateHashStrings
}

/**
 * Verifies the append-only-log consistency evidence: the SCH signature and
 * the SMH's inclusion proof against it. Independent of [verifyResponse].
 * Every piece of this path (reconstructed SCH shape, reconstructed Trillian
 * proof schema, from-scratch RFC 6962 verifier) has been confirmed against a
 * real live geopki server response - see `LiveFixtureTest`.
 */
fun ensureConsistency(response: ProtoResponse, publicKey: PublicKey) {
    val protoSch = response.geopki_SignedConsistencyHead
        ?: throw GeoPkiVerificationException("response does not contain a SCH")
    val sch = SignedConsistencyHead.fromProto(protoSch)
    if (!sch.verify(publicKey)) throw GeoPkiVerificationException("signature on the SCH is invalid")

    val protoSmh = response.geopki_SignedMapHead
        ?: throw GeoPkiVerificationException("response does not contain a SMH")
    val smh = SignedMapHead.fromProto(protoSmh)

    val leaf = leafHash(smh.toProtoBytes())

    val proofBytes = response.InclusionProof?.toByteArray()
        ?: throw GeoPkiVerificationException("response does not contain an inclusion proof")
    val proof: ProtoTrillianProof = try {
        ProtoTrillianProof.ADAPTER.decode(proofBytes)
    } catch (e: Exception) {
        throw GeoPkiVerificationException("failed decoding inclusion proof: ${e.message}")
    }

    val computedRoot = rootFromInclusionProof(
        leafIndex = proof.leaf_index,
        treeSize = sch.size,
        leafHash = leaf,
        proof = proof.hashes.map { it.toByteArray() }
    )

    if (!computedRoot.contentEquals(sch.rootHash.bytes)) {
        throw GeoPkiVerificationException("inclusion proof verification failed")
    }
}

private fun parseNode(n: ProtoNode): Node {
    val xyLen = n.XYBitStringLen
    if (xyLen.toUnsignedLong() > XY_BITS) {
        throw GeoPkiVerificationException("received xyBitStringLen is greater than XY_BITS")
    }

    val zRaw = n.ZBitString
    if (zRaw.toUnsignedLong() > 0xFFFFL) {
        throw GeoPkiVerificationException("received invalid ZBitString")
    }

    val zLen = n.ZBitStringLen
    if (zLen.toUnsignedLong() > Z_BITS) {
        throw GeoPkiVerificationException("received zBitStringLen is greater than Z_BITS")
    }

    val xyRaw = n.geopki_XYBitString.toULong()
    val xyLowMask = if (xyLen >= 64) ULong.MAX_VALUE else (ULong.MAX_VALUE shr xyLen)
    if ((xyRaw and xyLowMask) != 0uL) {
        throw GeoPkiVerificationException("received invalid xy bit string, the lower bits are not all cleared")
    }

    val zBitStringU16 = zRaw.toUInt().toUShort()
    val zLowMask = if (zLen >= 16) 0xFFFFu else ((0xFFFFu shr zLen) and 0xFFFFu)
    if ((zBitStringU16.toUInt() and zLowMask) != 0u) {
        throw GeoPkiVerificationException("received invalid z bit string, the lower bits are not all cleared")
    }

    return Node(
        pair = RawBitStringPair(
            rawXY = RawXYBitString(xyBitString = xyRaw, xyBitStringLen = xyLen),
            rawZ = RawZBitString(zBitString = zBitStringU16, zBitStringLen = zLen)
        ),
        xyLeftChildHash = bytesToHashOrNull(n.XYLeftChildHash?.toByteArray()),
        xyRightChildHash = bytesToHashOrNull(n.XYRightChildHash?.toByteArray()),
        zLeftChildHash = bytesToHashOrNull(n.ZLeftChildHash?.toByteArray()),
        zRightChildHash = bytesToHashOrNull(n.ZRightChildHash?.toByteArray()),
        certificateHashes = n.CertificateHashes.map { bytesToHash(it.toByteArray()) }
    )
}

private fun base64UrlNoPad(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
