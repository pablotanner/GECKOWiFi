package com.thesis.geckowifi.geopki.crypto

import java.security.MessageDigest

/**
 * RFC 6962 (Certificate Transparency) Merkle tree hashing + inclusion-proof
 * verification.
 *
 * NOT copied from netsec-ethz/geopki - `crypto/verification.go`'s
 * `EnsureConsistency` instead delegates to `transparency-dev/merkle/rfc6962`
 * and `transparency-dev/merkle/proof`, neither of which are Kotlin/JVM
 * libraries available to an Android client. This is a from-scratch
 * reimplementation of the standard, publicly-specified RFC 6962 algorithm
 * (leaf hash = SHA256(0x00 || data), node hash = SHA256(0x01 || left ||
 * right), and the usual "climb from leaf to root, consuming one proof
 * element per level except at an unpaired node" inclusion-proof check).
 *
 * Exercised in tests against a small tree built with the same [nodeHash]/
 * [leafHash] functions (internal self-consistency), AND separately CONFIRMED
 * (2026-09-11) to correctly reconstruct the real signed consistency root
 * from a live geopki server's actual inclusion proof - see
 * `LiveFixtureTest.ensureConsistency_succeedsAgainstCapturedQueryResponse`.
 */

fun leafHash(data: ByteArray): ByteArray {
    val out = ByteArray(data.size + 1)
    out[0] = 0x00
    System.arraycopy(data, 0, out, 1, data.size)
    return MessageDigest.getInstance("SHA-256").digest(out)
}

fun nodeHash(left: ByteArray, right: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(0x01)
    digest.update(left)
    digest.update(right)
    return digest.digest()
}

/**
 * Recomputes the tree root from a leaf hash, its 0-indexed position, the
 * total tree size, and its (bottom-up) audit path, throwing
 * [GeoPkiVerificationException] if the proof is malformed.
 */
fun rootFromInclusionProof(
    leafIndex: Long,
    treeSize: Long,
    leafHash: ByteArray,
    proof: List<ByteArray>
): ByteArray {
    if (leafIndex < 0 || leafIndex >= treeSize) {
        throw GeoPkiVerificationException("leaf index $leafIndex out of range for tree size $treeSize")
    }

    var node = leafIndex
    var lastNode = treeSize - 1
    var hash = leafHash
    var i = 0

    while (lastNode > 0) {
        if (i >= proof.size) throw GeoPkiVerificationException("inclusion proof is too short")

        hash = when {
            node % 2 == 1L -> nodeHash(proof[i], hash).also { i++ }
            node < lastNode -> nodeHash(hash, proof[i]).also { i++ }
            // node == lastNode and even: an unpaired node promoted without combination.
            else -> hash
        }

        node /= 2
        lastNode /= 2
    }

    if (i != proof.size) throw GeoPkiVerificationException("inclusion proof has unused elements")
    return hash
}
