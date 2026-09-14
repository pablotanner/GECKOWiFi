package com.thesis.geckowifi.geopki.crypto

import com.thesis.geckowifi.geopki.bitstring.ROOT_NODE
import com.thesis.geckowifi.geopki.bitstring.xyLeftChildPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class NodeTest {

    @Test
    fun hash_ofEmptyRoot_isIntermediateHashOfFourDefaultHashes() {
        val root = Node(pair = ROOT_NODE)

        val expected = MessageDigest.getInstance("SHA-256").digest(
            byteArrayOf(0x01) + DEFAULT_HASH.bytes + DEFAULT_HASH.bytes + DEFAULT_HASH.bytes + DEFAULT_HASH.bytes
        )

        assertEquals(SHA256Hash(expected), root.hash())
    }

    @Test
    fun hash_usesAttachedChildHashInsteadOfDefault() {
        val childPair = ROOT_NODE.xyLeftChildPair()
        val child = Node(pair = childPair)
        val root = Node(pair = ROOT_NODE)
        root.setXYLeftChild(child)

        val expected = MessageDigest.getInstance("SHA-256").digest(
            byteArrayOf(0x01) + child.hash().bytes + DEFAULT_HASH.bytes + DEFAULT_HASH.bytes + DEFAULT_HASH.bytes
        )

        assertEquals(SHA256Hash(expected), root.hash())
    }

    @Test
    fun countNodes_countsSelfAndAttachedChildren() {
        val root = Node(pair = ROOT_NODE)
        assertEquals(1, root.countNodes())

        val child = Node(pair = ROOT_NODE.xyLeftChildPair())
        root.setXYLeftChild(child)
        assertEquals(2, root.countNodes())
    }

    @Test
    fun setXYLeftChild_rejectsMismatchedPair() {
        val wrongChild = Node(pair = ROOT_NODE.xyLeftChildPair().xyLeftChildPair())
        val root = Node(pair = ROOT_NODE)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            root.setXYLeftChild(wrongChild)
        }
    }

    // Root's z-children split [0, 32768) into [0, 16384) and [16384, 32768). The
    // intersection check (ported as-is from Go) compares bounds non-strictly, so
    // a query touching exactly 16384 overlaps both halves - keep these ranges
    // clear of that boundary so each test is unambiguous.
    private val leftZRange = 0..10000
    private val rightZRange = 20000..30000

    @Test
    fun isComplete_falseWhenLeftHashGivenWithoutNodeAndQueryOverlapsLeft() {
        val root = Node(pair = ROOT_NODE, zLeftChildHash = SHA256Hash(ByteArray(32) { 1 }))
        assertFalse(root.isComplete(leftZRange.first, leftZRange.last))
    }

    @Test
    fun isComplete_falseWhenRightHashGivenWithoutNodeAndQueryOverlapsRight() {
        // Regression test for the upstream Go transcription bug (see Node.kt
        // class doc): the buggy version always reported this branch complete.
        val root = Node(pair = ROOT_NODE, zRightChildHash = SHA256Hash(ByteArray(32) { 1 }))
        assertFalse(root.isComplete(rightZRange.first, rightZRange.last))
    }

    @Test
    fun isComplete_trueWhenHashOnlyChildDoesNotOverlapQuery() {
        val root = Node(pair = ROOT_NODE, zLeftChildHash = SHA256Hash(ByteArray(32) { 1 }))
        assertTrue(root.isComplete(rightZRange.first, rightZRange.last))
    }

    @Test
    fun isComplete_trueWhenNothingAtAllWasSent() {
        val root = Node(pair = ROOT_NODE)
        assertTrue(root.isComplete(0, 32767))
    }

    @Test
    fun pathIsComplete_descendsIntoAttachedChild() {
        val child = Node(pair = ROOT_NODE.xyLeftChildPair())
        val root = Node(pair = ROOT_NODE)
        root.setXYLeftChild(child)

        assertTrue(root.pathIsComplete("0", 0, 32767))
    }

    @Test
    fun pathIsComplete_falseWhenChildHashGivenWithoutNode() {
        val root = Node(pair = ROOT_NODE, xyLeftChildHash = SHA256Hash(ByteArray(32) { 1 }))
        assertFalse(root.pathIsComplete("0", 0, 32767))
    }
}
