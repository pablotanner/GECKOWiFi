package com.thesis.geckowifi.geopki.bitstring

import kotlin.math.log2

/**
 * Kotlin port of netsec-ethz/geopki `pkg/bitstring/bit_string_pair.go`.
 *
 * Ported: the coordinate <-> bitstring discretization (the client-relevant
 * half of the file). NOT ported: the `Geometry2D` / S2 / GDAL polygon-cover
 * machinery (`PolygonsTo2DBitStrings`, `ApproximateCircle`,
 * `ExtrudedPolygonsToBitStringPairs`, GeoJSON helpers) - that is
 * server-side/registration tooling, out of scope for the client's
 * point+radius query encoding (see instructions.MD step 4 / GeoQueryEncoder).
 *
 * Deviation from the Go source: `Grow2D`/`GrowZ`/`GrowZToLength` mutate the
 * receiver in Go (pointer receiver). Kotlin's data classes are immutable, so
 * here they return a new instance instead.
 */

/** Number of bits used to discretize the `x` (longitude) dimension. */
const val X_BITS: Int = 26

/** Number of bits used to discretize the `y` (latitude) dimension. */
const val Y_BITS: Int = 25

/** Combined length of an (interleaved) xy bit string. */
const val XY_BITS: Int = X_BITS + Y_BITS

/** Number of bits used to discretize the `z` (altitude) dimension. */
const val Z_BITS: Int = 15

/** Maximum value of the discretized `x` coordinate. */
val C_X: UInt = (1u shl X_BITS) - 1u

/** Maximum value of the discretized `y` coordinate. */
val C_Y: UInt = (1u shl Y_BITS) - 1u

/** Maximum value of the discretized `z` coordinate. */
val C_Z: UInt = (1u shl Z_BITS) - 1u

/** Minimum geodetic altitude in meters. */
const val D: Int = -11000

/** Maximum geodetic altitude in meters. */
val H: Int = C_Z.toInt() + D

/** A surface (longitude/latitude) bit string. */
data class XYBitString(
    val xMin: UInt,
    val yMin: UInt,
    val xPrecision: Int,
    val yPrecision: Int
) {
    /** The bit string encoding `xMin`, i.e. its `xPrecision` MSBs. */
    fun xBitString(): String = leftPad(xMin, X_BITS).take(xPrecision)

    /** The bit string encoding `yMin`, i.e. its `yPrecision` MSBs. */
    fun yBitString(): String = leftPad(yMin, Y_BITS).take(yPrecision)

    /** The smallest discretized `x` coordinate no longer in the voxel. */
    fun xMax(): UInt = xMin + (1u shl (X_BITS - xPrecision))

    /** The smallest discretized `y` coordinate no longer in the voxel. */
    fun yMax(): UInt = yMin + (1u shl (Y_BITS - yPrecision))

    /** Interleaved xy bit string: x bits at even indices, y bits at odd. */
    override fun toString(): String {
        val x = xBitString()
        val y = yBitString()
        val out = CharArray(x.length + y.length)
        for (i in x.indices) out[2 * i] = x[i]
        for (i in y.indices) out[2 * i + 1] = y[i]
        return String(out)
    }

    fun geodeticCoordinates(): Pair<Double, Double> =
        undiscretizeX(xMin) to undiscretizeY(yMin)

    fun rawXYBitStringPair(): RawXYBitString {
        val xShifted = xMin shl (32 - X_BITS)
        val yShifted = yMin shl (32 - Y_BITS)

        val xInterleaved = interleaveUint32WithZeros(xShifted) shl 1
        val yInterleaved = interleaveUint32WithZeros(yShifted)

        return RawXYBitString(
            xyBitString = xInterleaved or yInterleaved,
            xyBitStringLen = xPrecision + yPrecision
        )
    }

    /** Grows (does NOT mutate) the voxel by decreasing precision `steps` times. */
    fun grow2D(steps: Int): XYBitString {
        require(xPrecision == yPrecision || xPrecision == yPrecision + 1) {
            "(xPrecision == yPrecision) or (xPrecision == yPrecision + 1) invariant violated"
        }
        require(xPrecision + yPrecision > steps) {
            "cannot grow further in 2D, ${xPrecision + yPrecision} bits left and tried growing by $steps bits"
        }

        val xBitsToClear: Int
        val yBitsToClear: Int
        if (xPrecision == yPrecision) {
            xBitsToClear = steps / 2
            yBitsToClear = (steps + 1) / 2
        } else {
            xBitsToClear = (steps + 1) / 2
            yBitsToClear = steps / 2
        }

        val newXPrecision = xPrecision - xBitsToClear
        val newYPrecision = yPrecision - yBitsToClear

        return XYBitString(
            xMin = xMin and (UInt.MAX_VALUE shl (X_BITS - newXPrecision)),
            yMin = yMin and (UInt.MAX_VALUE shl (Y_BITS - newYPrecision)),
            xPrecision = newXPrecision,
            yPrecision = newYPrecision
        )
    }
}

/** An altitude bit string. */
data class ZBitString(
    val zMin: UInt,
    val zPrecision: Int
) {
    /** The bit string encoding `zMin`, i.e. its `zPrecision` MSBs. */
    override fun toString(): String = leftPad(zMin, Z_BITS).take(zPrecision)

    /** The smallest discretized `z` coordinate no longer in the voxel. */
    fun zMax(): UInt = zMin + (1u shl (Z_BITS - zPrecision))

    fun geodeticCoordinate(): Double = undiscretizeZ(zMin)

    fun rawZBitString(): RawZBitString = RawZBitString(
        zBitString = ((zMin shl (16 - Z_BITS)) and 0xFFFFu).toUShort(),
        zBitStringLen = zPrecision
    )

    /** Grows (does NOT mutate) the voxel by decreasing precision `steps` times. */
    fun growZ(steps: Int): ZBitString {
        require(zPrecision >= steps) { "cannot grow further in the altitude" }
        val newPrecision = zPrecision - steps
        return ZBitString(
            zMin = zMin and ((0xFFFFu.toUInt() shl (Z_BITS - newPrecision)) and 0xFFFFu),
            zPrecision = newPrecision
        )
    }

    /**
     * Grows (does NOT mutate) the voxel by removing z bits until the voxel's
     * altitude would exceed `altitudeMaxRange` if another bit was removed.
     */
    fun growZToLength(altitudeMaxRange: Double): ZBitString {
        val currentAltitudeRange = (zMax() - zMin).toDouble()
        val growSteps = log2(altitudeMaxRange / currentAltitudeRange)

        if (growSteps < 0) return this
        require(growSteps <= Z_BITS) { "something seems off, cannot grow larger than the whole world" }

        return growZ(growSteps.toInt())
    }
}

/** A bit string pair: a surface bit string and an altitude bit string. */
data class BitStringPair(
    val xy: XYBitString,
    val z: ZBitString
) {
    fun bitStringPair(): Pair<String, String> = xy.toString() to z.toString()

    fun geodeticCoordinates(): Triple<Double, Double, Double> {
        val (lng, lat) = xy.geodeticCoordinates()
        return Triple(lng, lat, z.geodeticCoordinate())
    }

    fun rawBitStringPair(): RawBitStringPair = RawBitStringPair(
        rawXY = xy.rawXYBitStringPair(),
        rawZ = z.rawZBitString()
    )
}

/**
 * Creates a new [BitStringPair], validating the given values represent a
 * valid bit string pair. Mirrors Go's `NewBitStringPair`.
 */
fun newBitStringPair(
    xMin: UInt,
    yMin: UInt,
    zMin: UInt,
    xPrecision: Int,
    yPrecision: Int,
    zPrecision: Int
): BitStringPair {
    require(xMin <= C_X) { "xMin ($xMin) is greater than C_X ($C_X)" }
    require(yMin <= C_Y) { "yMin ($yMin) is greater than C_Y ($C_Y)" }
    require(zMin <= C_Z) { "zMin ($zMin) is greater than C_Z ($C_Z)" }

    require(xPrecision in 0..X_BITS) { "xPrecision ($xPrecision) is greater than X_BITS ($X_BITS)" }
    require(yPrecision in 0..Y_BITS) { "yPrecision ($yPrecision) is greater than Y_BITS ($Y_BITS)" }
    require(zPrecision in 0..Z_BITS) { "zPrecision ($zPrecision) is greater than Z_BITS ($Z_BITS)" }

    require(xPrecision == yPrecision || xPrecision == yPrecision + 1) {
        "the x and y precisions must either match or the x precision must be exactly one greater " +
            "to allow a bit interleaving. xPrecision=$xPrecision, yPrecision=$yPrecision given"
    }

    val xMask = UInt.MAX_VALUE shl (X_BITS - xPrecision)
    val yMask = UInt.MAX_VALUE shl (Y_BITS - yPrecision)
    val zMask = (UInt.MAX_VALUE shl (Z_BITS - zPrecision)) and 0xFFFFu

    require(xMin == (xMin and xMask)) {
        "the given precision of $xPrecision bits does not suffice to encode the x coordinate $xMin"
    }
    require(yMin == (yMin and yMask)) {
        "the given precision of $yPrecision bits does not suffice to encode the y coordinate $yMin"
    }
    require(zMin == (zMin and zMask)) {
        "the given precision of $zPrecision bits does not suffice to encode the z coordinate $zMin"
    }

    return BitStringPair(
        xy = XYBitString(xMin, yMin, xPrecision, yPrecision),
        z = ZBitString(zMin, zPrecision)
    )
}

/**
 * Creates a [BitStringPair] from a surface bit string and an altitude bit
 * string, e.g. `bitStringPairFromStringPair("001", "0110")`.
 */
fun bitStringPairFromStringPair(xyBitString: String, zBitString: String): BitStringPair {
    require(xyBitString.length <= XY_BITS) {
        "the given xy bit string has a length of ${xyBitString.length} but should be at most $XY_BITS"
    }
    require(zBitString.length <= Z_BITS) {
        "the given z bit string has a length of ${zBitString.length} but should be at most $Z_BITS"
    }

    val xPrecision = (xyBitString.length + 1) / 2
    val yPrecision = xyBitString.length / 2

    val xChars = CharArray(X_BITS) { '0' }
    val yChars = CharArray(Y_BITS) { '0' }
    for ((i, c) in xyBitString.withIndex()) {
        if (i % 2 == 0) xChars[i / 2] = c else yChars[i / 2] = c
    }

    val x = parseBinary(String(xChars))
    val y = parseBinary(String(yChars))
    val z = parseBinary(zBitString + "0".repeat(Z_BITS - zBitString.length))

    return BitStringPair(
        xy = XYBitString(x, y, xPrecision, yPrecision),
        z = ZBitString(z, zBitString.length)
    )
}

/** Creates an [XYBitString] (full precision) from a geodetic coordinate. */
fun xyBitStringFromGeodeticCoordinates(longitude: Double, latitude: Double): XYBitString {
    require(longitude in -180.0..180.0) { "longitudes must be in the range [-180, 180], $longitude given" }
    require(latitude in -90.0..90.0) { "latitudes must be in the range [-90, 90], $latitude given" }

    val x: UInt = if (longitude == 180.0) {
        C_X
    } else {
        (((longitude + 180) / 360) * (C_X + 1u).toDouble()).toUInt()
    }

    val y: UInt = if (latitude == 90.0) {
        C_Y
    } else {
        (((latitude + 90) / 180) * (C_Y + 1u).toDouble()).toUInt()
    }

    return XYBitString(
        xMin = x and 0x3ffffffu,
        yMin = y and 0x1ffffffu,
        xPrecision = X_BITS,
        yPrecision = Y_BITS
    )
}

/** Creates a [ZBitString] (full precision) from a geodetic altitude. */
fun zBitStringFromGeodeticCoordinate(altitude: Double): ZBitString {
    require(altitude in D.toDouble()..(H + 1).toDouble()) {
        "altitudes must be in the range [$D, ${H + 1}], $altitude given"
    }

    val z: UInt = if (altitude == (H + 1).toDouble()) C_Z else (altitude - D).toUInt()

    return ZBitString(zMin = z, zPrecision = Z_BITS)
}

/** Creates a [BitStringPair] (full precision) from a geodetic coordinate. */
fun bitStringPairFromGeodeticCoordinates(longitude: Double, latitude: Double, altitude: Double): BitStringPair =
    BitStringPair(
        xy = xyBitStringFromGeodeticCoordinates(longitude, latitude),
        z = zBitStringFromGeodeticCoordinate(altitude)
    )

fun undiscretizeX(x: UInt): Double = (x.toULong() * 360uL).toDouble() / (C_X + 1u).toDouble() - 180.0
fun undiscretizeY(y: UInt): Double = (y.toULong() * 180uL).toDouble() / (C_Y + 1u).toDouble() - 90.0
fun undiscretizeZ(z: UInt): Double = (D + z.toInt()).toDouble()

fun undiscretize(x: UInt, y: UInt, z: UInt): Triple<Double, Double, Double> =
    Triple(undiscretizeX(x), undiscretizeY(y), undiscretizeZ(z))

// https://lemire.me/blog/2018/01/08/how-fast-can-you-bit-interleave-32-bit-integers/
// (public domain per that repository's README)
internal fun interleaveUint32WithZeros(input: UInt): ULong {
    var word = input.toULong()
    word = (word xor (word shl 16)) and 0x0000ffff0000ffffuL
    word = (word xor (word shl 8)) and 0x00ff00ff00ff00ffuL
    word = (word xor (word shl 4)) and 0x0f0f0f0f0f0f0f0fuL
    word = (word xor (word shl 2)) and 0x3333333333333333uL
    word = (word xor (word shl 1)) and 0x5555555555555555uL
    return word
}

private fun leftPad(value: UInt, bits: Int): String = value.toString(2).padStart(bits, '0')

private fun parseBinary(bits: String): UInt {
    require(bits.all { it == '0' || it == '1' }) { "invalid bit string: $bits" }
    return bits.toUInt(radix = 2)
}
