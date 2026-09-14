package com.thesis.geckowifi.geopki.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class HashTest {

    @Test
    fun defaultHash_isSha256OfZeroByte() {
        val expected = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0x00))
        assertEquals(SHA256Hash(expected), DEFAULT_HASH)
    }

    @Test
    fun bytesToHash_padsShortInput() {
        val h = bytesToHash(byteArrayOf(1, 2, 3))
        assertEquals(32, h.bytes.size)
        assertEquals(1, h.bytes[0].toInt())
        assertEquals(2, h.bytes[1].toInt())
        assertEquals(3, h.bytes[2].toInt())
        assertEquals(0, h.bytes[3].toInt())
    }

    @Test
    fun bytesToHash_truncatesLongInput() {
        val input = ByteArray(40) { it.toByte() }
        val h = bytesToHash(input)
        assertEquals(32, h.bytes.size)
        assertEquals(input.copyOf(32).toList(), h.bytes.toList())
    }

    @Test
    fun bytesToHashOrNull_nullForEmpty() {
        assertNull(bytesToHashOrNull(null))
        assertNull(bytesToHashOrNull(ByteArray(0)))
    }

    @Test
    fun sha256Hash_equalityIsContentBased() {
        val a = SHA256Hash(ByteArray(32) { 7 })
        val b = SHA256Hash(ByteArray(32) { 7 })
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun unsignedByteArrayComparator_ordersHighBytesAfterLow() {
        val low = byteArrayOf(0x01)
        val high = byteArrayOf(0xFF.toByte())
        assert(UNSIGNED_BYTE_ARRAY_COMPARATOR.compare(low, high) < 0) {
            "expected 0x01 to sort before 0xFF under unsigned comparison"
        }
    }
}
