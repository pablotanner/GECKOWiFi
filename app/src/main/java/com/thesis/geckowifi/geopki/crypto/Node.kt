package com.thesis.geckowifi.geopki.crypto

import com.thesis.geckowifi.geopki.bitstring.RawBitStringPair
import com.thesis.geckowifi.geopki.bitstring.XY_BITS
import com.thesis.geckowifi.geopki.bitstring.Z_BITS
import com.thesis.geckowifi.geopki.bitstring.bitString
import com.thesis.geckowifi.geopki.bitstring.leftChild
import com.thesis.geckowifi.geopki.bitstring.rightChild
import com.thesis.geckowifi.geopki.bitstring.xyLeftChildPair
import com.thesis.geckowifi.geopki.bitstring.xyRightChildPair
import com.thesis.geckowifi.geopki.bitstring.zLeftChildPair
import com.thesis.geckowifi.geopki.bitstring.zRightChildPair
import java.io.ByteArrayOutputStream

/**
 * Kotlin port of netsec-ethz/geopki `pkg/crypto/node.go` - a sparse Merkle
 * tree node.
 *
 * ## Deviations from the Go source (both in [isZComplete]/[isComplete])
 *
 * The ported Go file computes its *right* z-child bounds via
 * `node.RawZBitString.LeftChild()` (copy/paste of the left-child line - the
 * right-child branch never actually calls `RightChild()`), and separately,
 * `IsZComplete`'s right-child "was this omitted subtree legitimately empty"
 * check tests `node.zRightChild == nil` - which is always true inside the
 * `if node.zRightChild == nil { ... }` branch it appears in, i.e. a no-op
 * that can never flag incompleteness. Both look like transcription bugs
 * rather than intentional server-specific behavior: they only make the
 * *right* subtree's completeness check strictly weaker than the left one,
 * silently accepting a response where the server omitted required right-hand
 * z-subtree data. Given the hard rule that proof verification is
 * non-optional, this port uses the corrected logic (calling `RightChild()`,
 * and checking the *hash* field, matching the left-child branch's pattern)
 * instead of reproducing the bug. Flagged for confirmation against upstream.
 */
class Node(
    val pair: RawBitStringPair,
    xyLeftChildHash: SHA256Hash? = null,
    xyRightChildHash: SHA256Hash? = null,
    zLeftChildHash: SHA256Hash? = null,
    zRightChildHash: SHA256Hash? = null,
    val certificateHashes: List<SHA256Hash> = emptyList()
) {
    var xyLeftChild: Node? = null
        private set
    var xyRightChild: Node? = null
        private set
    var zLeftChild: Node? = null
        private set
    var zRightChild: Node? = null
        private set

    private var xyLeftChildHashField: SHA256Hash? = xyLeftChildHash
    private var xyRightChildHashField: SHA256Hash? = xyRightChildHash
    private var zLeftChildHashField: SHA256Hash? = zLeftChildHash
    private var zRightChildHashField: SHA256Hash? = zRightChildHash

    /** Explicit hash the server attached to this child (if any), regardless of [useDefault]. */
    val xyLeftChildHashOrNull: SHA256Hash? get() = xyLeftChildHashField
    val xyRightChildHashOrNull: SHA256Hash? get() = xyRightChildHashField
    val zLeftChildHashOrNull: SHA256Hash? get() = zLeftChildHashField
    val zRightChildHashOrNull: SHA256Hash? get() = zRightChildHashField

    /** If [useDefault], falls back to [DEFAULT_HASH] when no child/hash is known. */
    fun xyLeftChildHash(useDefault: Boolean): SHA256Hash? {
        xyLeftChild?.let { return it.hash() }
        if (!useDefault || xyLeftChildHashField != null) return xyLeftChildHashField
        return DEFAULT_HASH
    }

    fun xyRightChildHash(useDefault: Boolean): SHA256Hash? {
        xyRightChild?.let { return it.hash() }
        if (!useDefault || xyRightChildHashField != null) return xyRightChildHashField
        return DEFAULT_HASH
    }

    fun zLeftChildHash(useDefault: Boolean): SHA256Hash? {
        zLeftChild?.let { return it.hash() }
        if (!useDefault || zLeftChildHashField != null) return zLeftChildHashField
        return DEFAULT_HASH
    }

    fun zRightChildHash(useDefault: Boolean): SHA256Hash? {
        zRightChild?.let { return it.hash() }
        if (!useDefault || zRightChildHashField != null) return zRightChildHashField
        return DEFAULT_HASH
    }

    fun setXYLeftChild(child: Node) {
        val expected = pair.xyLeftChildPair()
        require(expected == child.pair) { "tried setting invalid xy left child" }
        check(xyLeftChildHashField == null) { "tried setting xyLeftChildHash on node with non-nil xyLeftChild hash" }
        xyLeftChild = child
    }

    fun setXYRightChild(child: Node) {
        val expected = pair.xyRightChildPair()
        require(expected == child.pair) { "tried setting invalid xy right child" }
        check(xyRightChildHashField == null) { "tried setting xyRightChild on node with non-nil xyRightChild hash" }
        xyRightChild = child
    }

    fun setZLeftChild(child: Node) {
        require(pair.zLeftChildPair() == child.pair) { "tried setting invalid z left child" }
        check(zLeftChildHashField == null) { "tried setting zLeftChild on node with non-nil zLeftChild hash" }
        zLeftChild = child
    }

    fun setZRightChild(child: Node) {
        require(pair.zRightChildPair() == child.pair) { "tried setting invalid z right child" }
        check(zRightChildHashField == null) { "tried setting zRightChild on node with non-nil zRightChild hash" }
        zRightChild = child
    }

    fun sortedCertificateHashes(): List<SHA256Hash> =
        certificateHashes.sortedWith(compareBy(UNSIGNED_BYTE_ARRAY_COMPARATOR) { it.bytes })

    fun concatenatedCertificateHashes(): ByteArray {
        val out = ByteArrayOutputStream()
        sortedCertificateHashes().forEach { out.write(it.bytes) }
        return out.toByteArray()
    }

    /** Computes this node's hash: a SHA-256 leaf hash, or an intermediate-node hash over its children. */
    fun hash(): SHA256Hash {
        check(pair.xyBitStringLen <= XY_BITS && pair.zBitStringLen <= Z_BITS) {
            "invalid bit string pair with sizes (${pair.xyBitStringLen}, ${pair.zBitStringLen})"
        }

        if (pair.xyBitStringLen == XY_BITS && pair.zBitStringLen == Z_BITS) {
            val out = ByteArrayOutputStream()
            out.write(0x00)
            out.write(concatenatedCertificateHashes())
            return SHA256Hash.sha256(out.toByteArray())
        }

        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(xyLeftChildHash(true)!!.bytes)
        out.write(xyRightChildHash(true)!!.bytes)
        out.write(zLeftChildHash(true)!!.bytes)
        out.write(zRightChildHash(true)!!.bytes)

        return if (certificateHashes.isEmpty()) {
            SHA256Hash.sha256(out.toByteArray())
        } else {
            val certHash = SHA256Hash.sha256(concatenatedCertificateHashes())
            out.write(certHash.bytes)
            SHA256Hash.sha256(out.toByteArray())
        }
    }

    /** Counts the nodes in the subtree rooted at this node (including itself). */
    fun countNodes(): Int {
        var c = 1
        xyLeftChild?.let { c += it.countNodes() }
        xyRightChild?.let { c += it.countNodes() }
        zLeftChild?.let { c += it.countNodes() }
        zRightChild?.let { c += it.countNodes() }
        return c
    }

    /**
     * Walks [xyPath]/[zPath] (each a string of '0'/'1') until either the path
     * is exhausted or the next child along it is missing. Returns the node
     * reached and the unconsumed remainder of each path.
     */
    fun walk(xyPath: String, zPath: String): Triple<Node, String, String> {
        if (xyPath.isEmpty()) {
            if (zPath.isEmpty()) return Triple(this, xyPath, zPath)

            return if (zPath[0] == '0') {
                zLeftChild?.walk(xyPath, zPath.substring(1)) ?: Triple(this, xyPath, zPath)
            } else {
                zRightChild?.walk(xyPath, zPath.substring(1)) ?: Triple(this, xyPath, zPath)
            }
        }

        return if (xyPath[0] == '0') {
            xyLeftChild?.walk(xyPath.substring(1), zPath) ?: Triple(this, xyPath, zPath)
        } else {
            xyRightChild?.walk(xyPath.substring(1), zPath) ?: Triple(this, xyPath, zPath)
        }
    }

    /** Whether the z-subtree rooted here is complete w.r.t. [altitudeMin]/[altitudeMax]. See class doc. */
    fun isZComplete(altitudeMin: Int, altitudeMax: Int): Boolean {
        var complete = true

        val leftBits = pair.rawZ.leftChild().bitString()
        val leftMin = leftBits.zMin.toInt()
        val leftMax = leftBits.zMax().toInt()

        val rightBits = pair.rawZ.rightChild().bitString()
        val rightMin = rightBits.zMin.toInt()
        val rightMax = rightBits.zMax().toInt()

        if (altitudeMin <= leftMax && altitudeMax >= leftMin) {
            complete = complete && if (zLeftChild == null) {
                zLeftChildHashField == null
            } else {
                zLeftChild!!.isZComplete(altitudeMin, altitudeMax)
            }
        }

        if (altitudeMin <= rightMax && altitudeMax >= rightMin) {
            complete = complete && if (zRightChild == null) {
                zRightChildHashField == null
            } else {
                zRightChild!!.isZComplete(altitudeMin, altitudeMax)
            }
        }

        return complete
    }

    /** Whether the whole subtree rooted here is complete w.r.t. [minAltitude]/[maxAltitude]. See class doc. */
    fun isComplete(minAltitude: Int, maxAltitude: Int): Boolean {
        var complete = true

        complete = complete && if (xyLeftChild == null) {
            xyLeftChildHashField == null
        } else {
            xyLeftChild!!.isComplete(minAltitude, maxAltitude)
        }

        complete = complete && if (xyRightChild == null) {
            xyRightChildHashField == null
        } else {
            xyRightChild!!.isComplete(minAltitude, maxAltitude)
        }

        if (zLeftChild == null) {
            val leftBits = pair.rawZ.leftChild().bitString()
            val noIntersection = minAltitude > leftBits.zMax().toInt() || maxAltitude < leftBits.zMin.toInt()
            complete = complete && (zLeftChildHashField == null || noIntersection)
        } else {
            complete = complete && zLeftChild!!.isZComplete(minAltitude, maxAltitude)
        }

        if (zRightChild == null) {
            val rightBits = pair.rawZ.rightChild().bitString()
            val noIntersection = minAltitude > rightBits.zMax().toInt() || maxAltitude < rightBits.zMin.toInt()
            complete = complete && (zRightChildHashField == null || noIntersection)
        } else {
            complete = complete && zRightChild!!.isZComplete(minAltitude, maxAltitude)
        }

        return complete
    }

    /**
     * Whether the z-subtrees of every node along [xyPath] are complete w.r.t.
     * [minAltitude]/[maxAltitude], AND the full subtree rooted at the node at
     * the end of the path is complete w.r.t. them.
     */
    fun pathIsComplete(xyPath: String, minAltitude: Int, maxAltitude: Int): Boolean {
        if (xyPath.isEmpty()) return isComplete(minAltitude, maxAltitude)

        if (!isZComplete(minAltitude, maxAltitude)) return false

        return if (xyPath[0] == '0') {
            xyLeftChild?.pathIsComplete(xyPath.substring(1), minAltitude, maxAltitude) ?: (xyLeftChildHashField == null)
        } else {
            xyRightChild?.pathIsComplete(xyPath.substring(1), minAltitude, maxAltitude) ?: (xyRightChildHashField == null)
        }
    }
}
