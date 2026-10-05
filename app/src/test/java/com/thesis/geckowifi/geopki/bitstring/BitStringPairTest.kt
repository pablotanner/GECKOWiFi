package com.thesis.geckowifi.geopki.bitstring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.abs

/**
 * Kotlin translation of netsec-ethz/geopki's `bit_string_pair_test.go`, with
 * the same expected values as the Go tests (the port must match byte for byte).
 */
class BitStringPairTest {

    @Test
    fun newBitStringPair_maxValues_doesNotThrow() {
        newBitStringPair(C_X, C_Y, C_Z, X_BITS, Y_BITS, Z_BITS)
    }

    @Test
    fun newBitStringPair_bigX_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            newBitStringPair(C_X + 1u, C_Y, C_Z, X_BITS, Y_BITS, Z_BITS)
        }
    }

    @Test
    fun newBitStringPair_bigY_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            newBitStringPair(C_X, C_Y + 1u, C_Z, X_BITS, Y_BITS, Z_BITS)
        }
    }

    @Test
    fun newBitStringPair_bigZ_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            newBitStringPair(C_X, C_Y, C_Z + 1u, X_BITS, Y_BITS, Z_BITS)
        }
    }

    @Test
    fun newBitStringPair_bigXPrecision_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            newBitStringPair(C_X, C_Y, C_Z, X_BITS + 1, Y_BITS, Z_BITS)
        }
    }

    @Test
    fun newBitStringPair_bigYPrecision_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            newBitStringPair(C_X, C_Y, C_Z, X_BITS, Y_BITS + 1, Z_BITS)
        }
    }

    @Test
    fun newBitStringPair_bigZPrecision_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            newBitStringPair(C_X, C_Y, C_Z, X_BITS, Y_BITS, Z_BITS + 1)
        }
    }

    @Test
    fun newBitStringPair_invalidXYPrecision_throws() {
        assertThrows(IllegalArgumentException::class.java) { newBitStringPair(0u, 0u, C_Z, 0, 1, Z_BITS) }
        assertThrows(IllegalArgumentException::class.java) { newBitStringPair(0u, 0u, C_Z, 0, 2, Z_BITS) }
        assertThrows(IllegalArgumentException::class.java) { newBitStringPair(0u, 0u, C_Z, 1, 2, Z_BITS) }
    }

    @Test
    fun newBitStringPair_invalidXPrecision_throws() {
        assertThrows(IllegalArgumentException::class.java) { newBitStringPair(32u, 0u, C_Z, 5, 5, Z_BITS) }
    }

    @Test
    fun newBitStringPair_invalidYPrecision_throws() {
        assertThrows(IllegalArgumentException::class.java) { newBitStringPair(0u, 32u, C_Z, 5, 5, Z_BITS) }
    }

    @Test
    fun newBitStringPair_invalidZPrecision_throws() {
        assertThrows(IllegalArgumentException::class.java) { newBitStringPair(C_X, C_Y, 16u, X_BITS, Y_BITS, 4) }
    }

    @Test
    fun bitStringPairFromStringPair_parsesFields() {
        val b = bitStringPairFromStringPair("001", "0110")

        assertEquals(16777216u, b.xy.xMin) // 2^24
        assertEquals(2, b.xy.xPrecision)
        assertEquals(0u, b.xy.yMin)
        assertEquals(1, b.xy.yPrecision)
        assertEquals(12288u, b.z.zMin) // 2^13 + 2^12
        assertEquals(4, b.z.zPrecision)
    }

    @Test
    fun bitStringPairFromStringPair_longXY_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            bitStringPairFromStringPair("001", "1".repeat(Z_BITS + 1))
        }
    }

    @Test
    fun bitStringPairFromStringPair_longZ_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            bitStringPairFromStringPair("1".repeat(XY_BITS + 1), "0110")
        }
    }

    @Test
    fun bitStringPairFromStringPair_invalidX_throws() {
        assertThrows(IllegalArgumentException::class.java) { bitStringPairFromStringPair("a01", "0110") }
    }

    @Test
    fun bitStringPairFromStringPair_invalidY_throws() {
        assertThrows(IllegalArgumentException::class.java) { bitStringPairFromStringPair("0a1", "0110") }
    }

    @Test
    fun bitStringPairFromStringPair_invalidZ_throws() {
        assertThrows(IllegalArgumentException::class.java) { bitStringPairFromStringPair("001", "011a") }
    }

    @Test
    fun xyBitStringFromGeodeticCoordinates_parsesFullPrecision() {
        val b = xyBitStringFromGeodeticCoordinates(5.31972, 60.39047)

        assertEquals(34546099u, b.xMin)
        assertEquals(28034815u, b.yMin)
        assertEquals(X_BITS, b.xPrecision)
        assertEquals(Y_BITS, b.yPrecision)
    }

    @Test
    fun xyBitStringFromGeodeticCoordinates_invalidLongitude_throws() {
        assertThrows(IllegalArgumentException::class.java) { xyBitStringFromGeodeticCoordinates(-181.0, 60.39047) }
        assertThrows(IllegalArgumentException::class.java) { xyBitStringFromGeodeticCoordinates(180.3, 60.39047) }
    }

    @Test
    fun xyBitStringFromGeodeticCoordinates_invalidLatitude_throws() {
        assertThrows(IllegalArgumentException::class.java) { xyBitStringFromGeodeticCoordinates(5.31972, 90.5) }
        assertThrows(IllegalArgumentException::class.java) { xyBitStringFromGeodeticCoordinates(5.31972, -91.0) }
    }

    @Test
    fun zBitStringFromGeodeticCoordinate_parsesFullPrecision() {
        val b = zBitStringFromGeodeticCoordinate(1337.0)

        assertEquals((1337 - D).toUInt(), b.zMin)
        assertEquals(Z_BITS, b.zPrecision)
    }

    @Test
    fun zBitStringFromGeodeticCoordinate_invalidAltitude_throws() {
        assertThrows(IllegalArgumentException::class.java) { zBitStringFromGeodeticCoordinate(-20000.0) }
        assertThrows(IllegalArgumentException::class.java) { zBitStringFromGeodeticCoordinate(50000.0) }
    }

    @Test
    fun bitStringPairFromGeodeticCoordinates_parsesFullPrecision() {
        val b = bitStringPairFromGeodeticCoordinates(5.31972, 60.39047, 1337.0)

        assertEquals(34546099u, b.xy.xMin)
        assertEquals(28034815u, b.xy.yMin)
        assertEquals((1337 - D).toUInt(), b.z.zMin)
        assertEquals(X_BITS, b.xy.xPrecision)
        assertEquals(Y_BITS, b.xy.yPrecision)
        assertEquals(Z_BITS, b.z.zPrecision)
    }

    @Test
    fun xBitString_matchesExpectedBits() {
        val b = bitStringPairFromStringPair("010011010001", "011")
        assertEquals("001000", b.xy.xBitString())
    }

    @Test
    fun yBitString_matchesExpectedBits() {
        val b = bitStringPairFromStringPair("010011010001", "011")
        assertEquals("101101", b.xy.yBitString())
    }

    @Test
    fun zBitString_matchesExpectedBits() {
        val b = bitStringPairFromStringPair("010011010001", "111001")
        assertEquals("111001", b.z.toString())
    }

    @Test
    fun xMax_matchesExpected() {
        val b = bitStringPairFromStringPair("010011010001", "111001")
        assertEquals(9437184u, b.xy.xMax())
    }

    @Test
    fun yMax_matchesExpected() {
        val b = bitStringPairFromStringPair("010011010001", "111001")
        assertEquals(24117248u, b.xy.yMax())
    }

    @Test
    fun zMax_matchesExpected() {
        val b = bitStringPairFromStringPair("010011010001", "111001")
        assertEquals(29696u, b.z.zMax())
    }

    @Test
    fun bitStringPair_roundTripsStrings() {
        val xy = "010011010001"
        val z = "111001"

        val b = bitStringPairFromStringPair(xy, z)
        val (parsedXY, parsedZ) = b.bitStringPair()

        assertEquals(xy, parsedXY)
        assertEquals(z, parsedZ)
    }

    @Test
    fun rawBitStringPair_matchesExpectedPackedValue() {
        val xy = "010011010001"
        val z = "111001"

        val b = bitStringPairFromStringPair(xy, z)
        val p = b.rawBitStringPair()

        assertEquals(xy.length, p.rawXY.xyBitStringLen)
        assertEquals(z.length, p.rawZ.zBitStringLen)

        val paddedXY = xy.padEnd(64, '0')
        val paddedZ = z.padEnd(16, '0')

        assertEquals(java.lang.Long.parseUnsignedLong(paddedXY, 2).toULong(), p.rawXY.xyBitString)
        assertEquals(paddedZ.toUInt(radix = 2).toUShort(), p.rawZ.zBitString)
    }

    private val epsilon = 0.001

    private fun assertEpsilonClose(expected: Double, actual: Double) {
        assert(abs(actual - expected) <= epsilon) { "expected $expected to be close to $actual" }
    }

    @Test
    fun undiscretize_matchesExpectedCorners() {
        var (lng, lat, alt) = undiscretize(0u, 0u, 0u)
        assertEpsilonClose(-180.0, lng)
        assertEpsilonClose(-90.0, lat)
        assertEpsilonClose(D.toDouble(), alt)

        val midX = 1u shl (X_BITS - 1)
        val midY = 1u shl (Y_BITS - 1)
        val midZ = (-D).toUInt()
        val mid = undiscretize(midX, midY, midZ)
        assertEpsilonClose(0.0, mid.first)
        assertEpsilonClose(0.0, mid.second)
        assertEpsilonClose(0.0, mid.third)

        val corner = undiscretize((1u shl X_BITS) - 1u, (1u shl Y_BITS) - 1u, (1u shl Z_BITS) - 1u)
        assertEpsilonClose(180.0, corner.first)
        assertEpsilonClose(90.0, corner.second)
        assertEpsilonClose(H.toDouble(), corner.third)
    }

    @Test
    fun geodeticCoordinates_matchesExpected() {
        val xy = "010011010001"
        val z = "111001"

        val b = bitStringPairFromStringPair(xy, z)
        val (lng, lat, alt) = b.geodeticCoordinates()

        assertEpsilonClose(-135.0, lng)
        assertEpsilonClose(36.5625, lat)

        val paddedZ = z.padEnd(Z_BITS, '0')
        val expectedZMin = paddedZ.toUInt(radix = 2)
        val expectedAltitude = (D + expectedZMin.toInt()).toDouble()

        assertEpsilonClose(expectedAltitude, alt)
    }
}
