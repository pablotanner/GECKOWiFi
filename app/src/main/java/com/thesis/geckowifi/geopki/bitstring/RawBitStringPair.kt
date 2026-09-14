package com.thesis.geckowifi.geopki.bitstring

/**
 * Kotlin port of netsec-ethz/geopki `pkg/bitstring/raw_bit_string_pair.go`.
 *
 * These are the low-level (packed-integer) SMT-node coordinates: an xy bit
 * string packed into the top bits of a [ULong] (up to 51 bits used) and a z
 * bit string packed into the top bits of a [UShort]-range value (up to 15
 * bits used, stored widened to [UInt] because Kotlin's `UShort` has no
 * bitwise shift operators).
 *
 * Deviation from Go: JVM `shl`/`shr` on a 64-bit value only honor the low 6
 * bits of the shift amount (shifting by 64 is a no-op, not zero, unlike Go's
 * unsigned-shift semantics where a shift count >= the operand's width always
 * yields zero). [shl64] below guards against that specific trap; it is only
 * needed for the raw *xy* (64-bit-backed) operations. The raw *z* operations
 * are computed in 32-bit [UInt] space where the largest shift used (16) is
 * safely under the 32-bit native shift width, so no guard is needed there -
 * see the reasoning captured on [ZBitString.rawZBitString] and friends in
 * BitStringPair.kt.
 */

/** A root SMT node instance. */
val ROOT_NODE = RawBitStringPair(
    rawXY = RawXYBitString(xyBitString = 0uL, xyBitStringLen = 0),
    rawZ = RawZBitString(zBitString = 0u, zBitStringLen = 0)
)

/** Low-level representation of a surface bit string. */
data class RawXYBitString(
    /** The xy bit string, occupying the `xyBitStringLen` most significant bits. */
    val xyBitString: ULong,
    /** Number of most-significant bits used. Must be in `[0, 51]`. */
    val xyBitStringLen: Int
)

/** Low-level representation of an altitude bit string. */
data class RawZBitString(
    /** The z bit string, occupying the `zBitStringLen` most significant bits (of 16). */
    val zBitString: UShort,
    /** Number of most-significant bits used. Must be in `[0, 15]`. */
    val zBitStringLen: Int
)

/** Low-level representation of a bit string pair. */
data class RawBitStringPair(
    val rawXY: RawXYBitString,
    val rawZ: RawZBitString
) {
    // Convenience accessors mirroring Go's embedded-field promotion
    // (`pair.XYBitStringLen` etc.), used pervasively by the crypto/ port.
    val xyBitString: ULong get() = rawXY.xyBitString
    val xyBitStringLen: Int get() = rawXY.xyBitStringLen
    val zBitString: UShort get() = rawZ.zBitString
    val zBitStringLen: Int get() = rawZ.zBitStringLen
}

/** Guards against the 64-bit JVM shift-amount-is-mod-64 trap (see file doc). */
private fun shl64(value: ULong, shift: Int): ULong = if (shift >= 64) 0uL else value shl shift

// https://lemire.me/blog/2018/01/08/how-fast-can-you-bit-interleave-32-bit-integers/
internal fun deInterleaveEvenBits(word0: ULong): UInt {
    var word = word0 and 0x5555555555555555uL
    word = (word or (word shr 1)) and 0x3333333333333333uL
    word = (word or (word shr 2)) and 0x0f0f0f0f0f0f0f0fuL
    word = (word or (word shr 4)) and 0x00ff00ff00ff00ffuL
    word = (word or (word shr 8)) and 0x0000ffff0000ffffuL
    word = (word or (word shr 16)) and 0x00000000ffffffffuL
    return word.toUInt()
}

internal fun deInterleaveUint64(input: ULong): Pair<UInt, UInt> =
    deInterleaveEvenBits(input shr 1) to deInterleaveEvenBits(input)

fun RawXYBitString.bitString(): XYBitString {
    val (xMinRaw, yMinRaw) = deInterleaveUint64(xyBitString)
    return XYBitString(
        xMin = xMinRaw shr (32 - X_BITS),
        xPrecision = (xyBitStringLen + 1) / 2,
        yMin = yMinRaw shr (32 - Y_BITS),
        yPrecision = xyBitStringLen / 2
    )
}

fun RawZBitString.bitString(): ZBitString = ZBitString(
    zMin = zBitString.toUInt() shr (16 - Z_BITS),
    zPrecision = zBitStringLen
)

fun RawBitStringPair.bitStringPair(): BitStringPair =
    BitStringPair(xy = rawXY.bitString(), z = rawZ.bitString())

fun RawBitStringPair.isRoot(): Boolean = xyBitStringLen == 0 && zBitStringLen == 0

fun RawXYBitString.ancestor(levels: Int): RawXYBitString {
    if (xyBitStringLen <= levels) return RawXYBitString(0uL, 0)
    val newLen = xyBitStringLen - levels
    return RawXYBitString(
        xyBitString = xyBitString and shl64(ULong.MAX_VALUE, 64 - newLen),
        xyBitStringLen = newLen
    )
}

fun RawZBitString.ancestor(levels: Int): RawZBitString {
    if (zBitStringLen <= levels) return RawZBitString(0u, 0)
    val newLen = zBitStringLen - levels
    val mask = (0xFFFFu shl (16 - newLen)) and 0xFFFFu
    return RawZBitString(
        zBitString = (zBitString.toUInt() and mask).toUShort(),
        zBitStringLen = newLen
    )
}

fun RawBitStringPair.ancestorPair(levels: Int): RawBitStringPair {
    val zLevels = minOf(zBitStringLen, levels)
    val xyLevels = levels - zLevels
    return RawBitStringPair(rawXY.ancestor(xyLevels), rawZ.ancestor(zLevels))
}

fun RawXYBitString.parent(): RawXYBitString = ancestor(1)
fun RawZBitString.parent(): RawZBitString = ancestor(1)
fun RawBitStringPair.parentPair(): RawBitStringPair = ancestorPair(1)

fun RawXYBitString.neighbor(): RawXYBitString = RawXYBitString(
    xyBitString = xyBitString xor shl64(1uL, 64 - xyBitStringLen),
    xyBitStringLen = xyBitStringLen
)

fun RawZBitString.neighbor(): RawZBitString {
    val bit = (1u shl (16 - zBitStringLen)) and 0xFFFFu
    return RawZBitString(
        zBitString = (zBitString.toUInt() xor bit).toUShort(),
        zBitStringLen = zBitStringLen
    )
}

fun RawBitStringPair.neighborPair(): RawBitStringPair =
    if (zBitStringLen == 0) {
        RawBitStringPair(rawXY.neighbor(), rawZ)
    } else {
        RawBitStringPair(rawXY, rawZ.neighbor())
    }

fun RawXYBitString.leftChild(): RawXYBitString = RawXYBitString(
    xyBitString = xyBitString and shl64(ULong.MAX_VALUE, 64 - xyBitStringLen),
    xyBitStringLen = xyBitStringLen + 1
)

fun RawXYBitString.rightChild(): RawXYBitString = RawXYBitString(
    xyBitString = xyBitString or shl64(1uL, 64 - xyBitStringLen - 1),
    xyBitStringLen = xyBitStringLen + 1
)

/** Throws if this pair is not purely-xy (i.e. has any z bits set). */
fun RawBitStringPair.xyLeftChildPair(): RawBitStringPair {
    require(zBitStringLen == 0) { "cannot call xyLeftChildPair() on non 2D bit string" }
    return RawBitStringPair(rawXY.leftChild(), rawZ)
}

/** Throws if this pair is not purely-xy (i.e. has any z bits set). */
fun RawBitStringPair.xyRightChildPair(): RawBitStringPair {
    require(zBitStringLen == 0) { "cannot call xyRightChildPair() on non 2D bit string" }
    return RawBitStringPair(rawXY.rightChild(), rawZ)
}

fun RawZBitString.leftChild(): RawZBitString {
    val mask = (0xFFFFu shl (16 - zBitStringLen)) and 0xFFFFu
    return RawZBitString(
        zBitString = (zBitString.toUInt() and mask).toUShort(),
        zBitStringLen = zBitStringLen + 1
    )
}

fun RawZBitString.rightChild(): RawZBitString {
    val bit = (1u shl (16 - zBitStringLen - 1)) and 0xFFFFu
    return RawZBitString(
        zBitString = (zBitString.toUInt() or bit).toUShort(),
        zBitStringLen = zBitStringLen + 1
    )
}

fun RawBitStringPair.zLeftChildPair(): RawBitStringPair = RawBitStringPair(rawXY, rawZ.leftChild())
fun RawBitStringPair.zRightChildPair(): RawBitStringPair = RawBitStringPair(rawXY, rawZ.rightChild())
