package com.thesis.geckowifi.geopki.bitstring

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kotlin translation of netsec-ethz/geopki's `raw_bit_string_pair_test.go`. */
class RawBitStringPairTest {

    @Test
    fun bitString_roundTripsInterleavedString() {
        val xyBitString = "010011010001"
        val padded = xyBitString.padEnd(64, '0')

        val raw = RawXYBitString(
            xyBitString = java.lang.Long.parseUnsignedLong(padded, 2).toULong(),
            xyBitStringLen = xyBitString.length
        )

        assertEquals(xyBitString, raw.bitString().toString())
    }

    @Test
    fun leftChild_ofRoot_isZero() {
        val left = ROOT_NODE.rawXY.leftChild()
        assertEquals(0uL, left.xyBitString)
        assertEquals(1, left.xyBitStringLen)
    }

    @Test
    fun rightChild_ofRoot_hasTopBitSet() {
        val right = ROOT_NODE.rawXY.rightChild()
        assertEquals(1uL shl 63, right.xyBitString)
        assertEquals(1, right.xyBitStringLen)
    }

    @Test
    fun leftChild_thenParent_roundTrips() {
        val child = ROOT_NODE.rawXY.leftChild()
        assertEquals(ROOT_NODE.rawXY, child.parent())
    }

    @Test
    fun rightChild_thenParent_roundTrips() {
        val child = ROOT_NODE.rawXY.rightChild()
        assertEquals(ROOT_NODE.rawXY, child.parent())
    }

    @Test
    fun neighbor_ofLeftChild_isRightChild() {
        val left = ROOT_NODE.rawXY.leftChild()
        val right = ROOT_NODE.rawXY.rightChild()
        assertEquals(right, left.neighbor())
        assertEquals(left, right.neighbor())
    }

    @Test
    fun zLeftChild_ofRoot_isZero() {
        val left = ROOT_NODE.rawZ.leftChild()
        assertEquals(0.toUShort(), left.zBitString)
        assertEquals(1, left.zBitStringLen)
    }

    @Test
    fun zRightChild_ofRoot_hasTopBitSet() {
        val right = ROOT_NODE.rawZ.rightChild()
        assertEquals((1 shl 15).toUShort(), right.zBitString)
        assertEquals(1, right.zBitStringLen)
    }

    @Test
    fun isRoot_trueOnlyForRoot() {
        org.junit.Assert.assertTrue(ROOT_NODE.isRoot())
        org.junit.Assert.assertFalse(ROOT_NODE.rawXY.leftChild().let { RawBitStringPair(it, ROOT_NODE.rawZ) }.isRoot())
    }

    @Test
    fun xyLeftChildPair_throwsOnNon2DPair() {
        val withZ = RawBitStringPair(ROOT_NODE.rawXY, ROOT_NODE.rawZ.leftChild())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { withZ.xyLeftChildPair() }
    }
}
