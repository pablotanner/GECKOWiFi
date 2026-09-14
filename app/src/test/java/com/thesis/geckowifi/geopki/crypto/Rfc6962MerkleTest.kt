package com.thesis.geckowifi.geopki.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [rootFromInclusionProof] against a small Merkle tree built with
 * the same [leafHash]/[nodeHash] primitives (RFC 6962's split-at-largest-
 * power-of-two-below rule). This proves internal consistency between the
 * proof generator and verifier here, NOT parity with a real captured geopki
 * server response or google/trillian's implementation - see the caveats on
 * Rfc6962Merkle.kt.
 */
class Rfc6962MerkleTest {

    private fun mth(leaves: List<ByteArray>): ByteArray {
        if (leaves.size == 1) return leafHash(leaves[0])
        val k = largestPowerOfTwoBelow(leaves.size)
        return nodeHash(mth(leaves.subList(0, k)), mth(leaves.subList(k, leaves.size)))
    }

    private fun auditPath(leaves: List<ByteArray>, index: Int): List<ByteArray> {
        if (leaves.size == 1) return emptyList()
        val k = largestPowerOfTwoBelow(leaves.size)
        return if (index < k) {
            auditPath(leaves.subList(0, k), index) + mth(leaves.subList(k, leaves.size))
        } else {
            auditPath(leaves.subList(k, leaves.size), index - k) + mth(leaves.subList(0, k))
        }
    }

    private fun largestPowerOfTwoBelow(n: Int): Int {
        var k = 1
        while (k * 2 < n) k *= 2
        return k
    }

    private fun leaves(n: Int): List<ByteArray> = (0 until n).map { byteArrayOf(it.toByte()) }

    @Test
    fun fourLeafTree_verifiesLeftLeaf() {
        val data = leaves(4)
        val root = mth(data)
        val proof = auditPath(data, 0)

        val recomputed = rootFromInclusionProof(0L, 4L, leafHash(data[0]), proof)
        assertTrue(recomputed.contentEquals(root))
    }

    @Test
    fun fourLeafTree_verifiesRightLeaf() {
        val data = leaves(4)
        val root = mth(data)
        val proof = auditPath(data, 3)

        val recomputed = rootFromInclusionProof(3L, 4L, leafHash(data[3]), proof)
        assertTrue(recomputed.contentEquals(root))
    }

    @Test
    fun fiveLeafTree_verifiesUnbalancedLoneLeaf() {
        val data = leaves(5)
        val root = mth(data)
        val proof = auditPath(data, 4)

        val recomputed = rootFromInclusionProof(4L, 5L, leafHash(data[4]), proof)
        assertTrue(recomputed.contentEquals(root))
    }

    @Test
    fun tamperedProofElement_producesWrongRoot() {
        val data = leaves(4)
        val root = mth(data)
        val proof = auditPath(data, 0).toMutableList()
        proof[0] = ByteArray(32) { 0x42 }

        val recomputed = rootFromInclusionProof(0L, 4L, leafHash(data[0]), proof)
        assertFalse(recomputed.contentEquals(root))
    }

    @Test
    fun outOfRangeLeafIndex_throws() {
        org.junit.Assert.assertThrows(GeoPkiVerificationException::class.java) {
            rootFromInclusionProof(5L, 4L, leafHash(byteArrayOf(0)), emptyList())
        }
    }
}
