package com.thesis.geckowifi.verification

import com.google.common.geometry.S2LatLng
import com.google.common.geometry.S2Loop
import com.google.common.geometry.S2Point
import com.thesis.geckowifi.geopki.bitstring.C_Z
import com.thesis.geckowifi.geopki.bitstring.D
import com.thesis.geckowifi.geopki.bitstring.H
import com.thesis.geckowifi.geopki.bitstring.X_BITS
import com.thesis.geckowifi.geopki.bitstring.Y_BITS
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin
import com.thesis.geckowifi.geopki.bitstring.XYBitString as GeoXYBitString

/**
 * My own wrapper for a surface bit string ready to send to the map server -
 * kept distinct from [com.thesis.geckowifi.geopki.bitstring.XYBitString]
 * so [GeckoClient][com.thesis.geckowifi.data.remote.GeckoClient]'s public API
 * doesn't leak the geopki/ port's internal representation.
 */
data class XYBitString(
    val bits: String
)

/**
 * Encodes a client-side "where am I, how far around" query into the
 * XYBitStrings + altitude bounds the geopki map server expects.
 *
 * Faithful port of the Go reference's S2 path (the one the wasm client uses):
 *   comm.NewQuery -> comm.S2CircleApproximator -> bitstring.ApproximateCircle
 *   -> bitstring.PolygonsTo2DBitStrings (S2Geometry2D.InitialXYBitString,
 *   BFS over intersecting cells, sibling merge).
 * Quirks of the reference (int32 remainder by C_X, pole flip, unsigned wrap
 * then mask) are copied on purpose: the goal is equivalence, verified by
 * GeoQueryEncoderGoldenTest against cmd/golden-dump output.
 */
class GeoQueryEncoder {

    companion object {
        private val MIN_ALTITUDE: Int = D
        private val MAX_ALTITUDE: Int = H
        private val MAX_Z: Int = C_Z.toInt()

        // comm/query.go RADIUS_ERROR_FACTOR
        private const val RADIUS_ERROR_FACTOR = 1.0052

        // cmd/geopki-client F_GROW
        private const val F_GROW = 1.0

        // comm.S2CircleApproximator: 16 segments per quadrant = 64-gon
        private const val QUAD_SEGS = 16

        // kellydunn/golang-geo EARTH_RADIUS (km)
        private const val EARTH_RADIUS_KM = 6371.0

        private val C_X: UInt = (1u shl X_BITS) - 1u
        private val C_Y: UInt = (1u shl Y_BITS) - 1u

        private val DELTAS = intArrayOf(-1, 0, 1)
    }

    /**
     * Returns the surface bit strings covering a circle of [radiusMeters]
     * around (lat, lng), identical to the Go reference client's query.
     * Sorted by (bits, len) for deterministic requests.
     */
    fun encodeQuery(
        lat: Double,
        lng: Double,
        radiusMeters: Int
    ): List<XYBitString> {
        require(lat in -90.0..90.0) { "Latitude must be in [-90, 90]" }
        require(lng in -180.0..180.0) { "Longitude must be in [-180, 180]" }
        // Go rejects when ceil(radius * RADIUS_ERROR_FACTOR) > 255 (uint8),
        // i.e. from 254 on (golden vectors: radius-max=253 ok, err-radius=254 rejected).
        require(radiusMeters in 0..253) { "Radius must be between 0 and 253 meters" }

        val radius = ceil(radiusMeters * RADIUS_ERROR_FACTOR).toInt()
        val radiusDegrees = Math.toDegrees(radius / 1000.0 / EARTH_RADIUS_KM)

        require(radiusMeters == 0 || !isPoleAdjacent(lat, radiusDegrees)) {
            "Query circle reaches or crosses a pole - not supported. The 64-gon " +
                "vertex approximation (approximateCircle) breaks down as cos(lat) -> 0: " +
                "vertices stop being meaningfully distinguished by bearing and collapse " +
                "onto a handful of floating-point-noise-separated points, malformed " +
                "enough that Java's S2Loop.intersects() misreports intersection for " +
                "cells thousands of km away - confirmed 2026-09-28, an unguarded query " +
                "at lat=-90 ran searchCells' BFS to OutOfMemoryError. No real GPS fix " +
                "is ever this close to a pole, so this fails fast here instead of " +
                "carrying dedicated fallback logic for a case that can't occur in " +
                "practice."
        }

        val touching = run {
            val circle = approximateCircle(lng, lat, radius)
            if (isDegenerate(circle)) {
                // True point query (radius 0): all vertices genuinely coincide.
                val p = S2LatLng(circle.vertex(0))
                setOf(Voxel.fromGeodetic(p.lngDegrees(), p.latDegrees()).raw())
            } else {
                searchCells(circle, initialVoxel(circle))
            }
        }

        return mergeCells(touching)
            .sortedWith(compareBy({ it.bits }, { it.len }))
            .map { raw ->
                val v = raw.toVoxel()
                XYBitString(GeoXYBitString(v.xMin, v.yMin, v.xPrec, v.yPrec).toString())
            }
    }

    /**
     * Returns encoded altitude bounds expected by geopki.proto.Request.
     *
     * The Go client uses the query radius as vertical uncertainty, inflated
     * by [RADIUS_ERROR_FACTOR] like the horizontal circle (confirmed by the
     * golden vectors, 2026-09-28).
     */
    fun altitudeBounds(
        altitude: Double?,
        uncertaintyMeters: Int = 0
    ): Pair<Int, Int> {
        if (altitude == null) {
            return 0 to MAX_Z
        }

        require(altitude in MIN_ALTITUDE.toDouble()..MAX_ALTITUDE.toDouble()) {
            "Altitude must be in [$MIN_ALTITUDE, $MAX_ALTITUDE]"
        }

        require(uncertaintyMeters >= 0) {
            "Uncertainty must not be negative"
        }

        val encodedAltitude = altitude.toInt() - MIN_ALTITUDE
        val inflatedUncertainty = ceil(uncertaintyMeters * RADIUS_ERROR_FACTOR).toInt()

        val minZ = (encodedAltitude - inflatedUncertainty)
            .coerceAtLeast(0)

        val maxZ = (encodedAltitude + inflatedUncertainty)
            .coerceAtMost(MAX_Z)

        return minZ to maxZ
    }

    // ---------------------------------------------------------------------
    // Stage 1: circle -> 64-gon (bitstring.ApproximateCircle)
    // ---------------------------------------------------------------------

    private fun approximateCircle(lng: Double, lat: Double, radiusM: Int): S2Loop {
        val radiusKm = radiusM / 1000.0
        val segments = QUAD_SEGS * 4
        val segmentDegrees = 360.0 / segments

        val pts = arrayOfNulls<S2Point>(segments)
        for (i in 0 until segments) {
            val (pLat, pLng) = pointAtDistanceAndBearing(lat, lng, radiusKm, i * segmentDegrees)
            // ccw order, same index mapping as Go
            pts[segments - 1 - i] = S2LatLng.fromDegrees(pLat, pLng).toPoint()
        }
        return S2Loop(pts.map { it!! })
    }

    private fun isDegenerate(loop: S2Loop): Boolean =
        (0 until loop.numVertices()).map { loop.vertex(it) }.distinct().size < 3

    /**
     * True when the query circle reaches or crosses a pole - where
     * [approximateCircle]'s 64-gon vertex approximation breaks down (bearing
     * stops meaningfully separating vertices as cos(lat) -> 0), producing a
     * malformed loop Java's S2Loop can badly misjudge for `intersects()`.
     * Confirmed 2026-09-28: an unguarded query at lat=-90 made
     * [searchCells]'s BFS mark cells ~2000km away as "intersecting" a 20m
     * circle, running to `OutOfMemoryError` (and corrupting whichever
     * unrelated test shared that JVM fork). No real device GPS fix is ever
     * this close to a pole, so [encodeQuery] just rejects it outright rather
     * than carrying dedicated coverage logic for a case that can't occur in
     * practice.
     */
    private fun isPoleAdjacent(lat: Double, radiusDegrees: Double): Boolean =
        90.0 - abs(lat) <= radiusDegrees * 2

    /** kellydunn/golang-geo Point.PointAtDistanceAndBearing, verbatim. */
    private fun pointAtDistanceAndBearing(
        latDeg: Double,
        lngDeg: Double,
        distKm: Double,
        bearingDeg: Double
    ): Pair<Double, Double> {
        val dr = distKm / EARTH_RADIUS_KM
        val bearing = bearingDeg * (PI / 180.0)
        val lat1 = latDeg * (PI / 180.0)
        val lng1 = lngDeg * (PI / 180.0)

        val lat2 = asin(sin(lat1) * cos(dr) + cos(lat1) * sin(dr) * cos(bearing))

        var lng2 = lng1 + atan2(
            sin(bearing) * sin(dr) * cos(lat1),
            cos(dr) - sin(lat1) * sin(lat2)
        )
        lng2 = ((lng2 + 3 * PI) % (2 * PI)) - PI // Kotlin % == Go math.Mod (sign of dividend)

        return (lat2 * (180.0 / PI)) to (lng2 * (180.0 / PI))
    }

    // ---------------------------------------------------------------------
    // Stage 2a: starting cell (S2Geometry2D.InitialXYBitString)
    // ---------------------------------------------------------------------

    private fun initialVoxel(circle: S2Loop): Voxel {
        val v0 = S2LatLng(circle.vertex(0))
        val initial = Voxel.fromGeodetic(v0.lngDegrees(), v0.latDegrees())

        val maxArea = circle.area * F_GROW
        val currentArea = initial.loop().area
        val growSteps = log2(maxArea / currentArea)

        return when {
            growSteps < 0 || growSteps.isNaN() -> initial
            growSteps > initial.xPrec + initial.yPrec ->
                throw IllegalStateException("cannot grow larger than the whole world")
            else -> initial.grow2D(growSteps.toInt()) // Go: uint8(growSteps), truncates
        }
    }

    // ---------------------------------------------------------------------
    // Stage 2b: BFS over neighbouring cells that intersect the circle
    // ---------------------------------------------------------------------

    private fun searchCells(circle: S2Loop, initial: Voxel): Set<Raw> {
        val intersecting = HashSet<Raw>()
        val visited = HashSet<Raw>()
        val queue = ArrayDeque<Voxel>()
        queue.addLast(initial)

        while (queue.isNotEmpty()) {
            val voxel = queue.removeFirst()
            val raw = voxel.raw()
            if (!visited.add(raw)) continue

            val intersects = circle.intersects(voxel.loop())
            if (!intersects && intersecting.isEmpty()) {
                throw IllegalStateException("initial bit string does not intersect")
            }
            if (!intersects) continue

            intersecting.add(raw)

            for (dx in DELTAS) {
                for (dy in DELTAS) {
                    queue.addLast(neighbor(voxel, dx, dy))
                }
            }
        }
        return intersecting
    }

    /** Literal port of the neighbour step in PolygonsTo2DBitStrings, quirks included. */
    private fun neighbor(v: Voxel, dx: Int, dy: Int): Voxel {
        val xStep = 1 shl (X_BITS - v.xPrec)
        // int32(XMin) + dx*step, Go's sign-preserving %, then uint32 cast
        var xNext = ((v.xMin.toInt() + dx * xStep) % C_X.toInt()).toUInt()

        val yStep = v.yMin.toInt() + dy * (1 shl (Y_BITS - v.yPrec))
        var yNext = yStep.toUInt()

        if (yStep < 0) {
            // over the south pole: y stays at 0, x rotates by half the world
            yNext = 0u
            xNext = (xNext + C_X / 2u) % C_X
        } else if (yStep >= C_Y.toInt()) {
            // over the north pole
            yNext = v.yMin
            xNext = (xNext + C_X / 2u) % C_X
        }

        xNext = xNext and (UInt.MAX_VALUE shl (X_BITS - v.xPrec))

        return Voxel(xNext, yNext, v.xPrec, v.yPrec)
    }

    // ---------------------------------------------------------------------
    // Stage 3: drop redundant cells, merge siblings into parents
    // ---------------------------------------------------------------------

    private fun mergeCells(intersecting: Set<Raw>): List<Raw> {
        val set = intersecting.toMutableSet()
        val work = intersecting.toMutableList()
        val result = mutableListOf<Raw>()

        var i = 0
        outer@ while (i < work.size) {
            val c = work[i++]
            // redundant if a larger cell (prefix) is already in the set
            for (lvl in 1..c.len) {
                if (set.contains(c.ancestor(lvl))) continue@outer
            }
            // sibling present: replace both by the parent, re-check parent later
            if (c.len > 0 && set.contains(c.neighbor())) {
                val parent = c.parent()
                set.add(parent)
                work.add(parent)
                continue
            }
            result.add(c)
        }
        return result
    }

    // ---------------------------------------------------------------------
    // Cell representations (bitstring.XYBitString / RawXYBitString)
    // ---------------------------------------------------------------------

    /** Discretized cell: [xMin, xMax) x [yMin, yMax) at the given precisions. */
    private data class Voxel(val xMin: UInt, val yMin: UInt, val xPrec: Int, val yPrec: Int) {

        fun xMax(): UInt = xMin + (1u shl (X_BITS - xPrec))
        fun yMax(): UInt = yMin + (1u shl (Y_BITS - yPrec))

        /** ccw rectangle as an S2 loop (XYBitString.Loop) */
        fun loop(): S2Loop {
            val lngMin = undiscretizeX(xMin)
            val lngMax = undiscretizeX(xMax())
            val latMin = undiscretizeY(yMin)
            val latMax = undiscretizeY(yMax())
            return S2Loop(
                listOf(
                    S2LatLng.fromDegrees(latMin, lngMin).toPoint(),
                    S2LatLng.fromDegrees(latMin, lngMax).toPoint(),
                    S2LatLng.fromDegrees(latMax, lngMax).toPoint(),
                    S2LatLng.fromDegrees(latMax, lngMin).toPoint(),
                )
            )
        }

        /** XYBitString.Grow2D */
        fun grow2D(steps: Int): Voxel {
            require(xPrec + yPrec > steps) { "cannot grow further in 2D" }
            val (xClear, yClear) = when (xPrec) {
                yPrec -> steps / 2 to (steps + 1) / 2
                yPrec + 1 -> (steps + 1) / 2 to steps / 2
                else -> throw IllegalStateException("precision invariant violated")
            }
            val nx = xPrec - xClear
            val ny = yPrec - yClear
            return Voxel(
                xMin and (UInt.MAX_VALUE shl (X_BITS - nx)),
                yMin and (UInt.MAX_VALUE shl (Y_BITS - ny)),
                nx,
                ny,
            )
        }

        /** XYBitString.RawXYBitStringPair: x bits on even positions, starting at the MSB */
        fun raw(): Raw {
            val xb = xMin shl (32 - X_BITS)
            val yb = yMin shl (32 - Y_BITS)
            return Raw((interleaveWithZeros(xb) shl 1) or interleaveWithZeros(yb), xPrec + yPrec)
        }

        companion object {
            /** XYBitStringFromGeodeticCoordinates (full precision) */
            fun fromGeodetic(lng: Double, lat: Double): Voxel {
                val x: UInt = if (lng == 180.0) C_X else (((lng + 180) / 360) * (C_X + 1u).toDouble()).toUInt()
                val y: UInt = if (lat == 90.0) C_Y else (((lat + 90) / 180) * (C_Y + 1u).toDouble()).toUInt()
                return Voxel(x and 0x3ffffffu, y and 0x1ffffffu, X_BITS, Y_BITS)
            }

            fun undiscretizeX(x: UInt): Double = (x.toULong() * 360uL).toDouble() / (C_X + 1u).toDouble() - 180
            fun undiscretizeY(y: UInt): Double = (y.toULong() * 180uL).toDouble() / (C_Y + 1u).toDouble() - 90
        }
    }

    /** Top-aligned interleaved bit string, as sent on the wire. */
    private data class Raw(val bits: ULong, val len: Int) {

        fun ancestor(levels: Int): Raw =
            if (len <= levels) Raw(0uL, 0)
            else (len - levels).let { l -> Raw(bits and (ULong.MAX_VALUE shl (64 - l)), l) }

        fun parent(): Raw = ancestor(1)

        fun neighbor(): Raw = Raw(bits xor (1uL shl (64 - len)), len)

        /** RawXYBitString.BitString */
        fun toVoxel(): Voxel {
            val xMin = deInterleaveEvenBits(bits shr 1)
            val yMin = deInterleaveEvenBits(bits)
            return Voxel(
                xMin shr (32 - X_BITS),
                yMin shr (32 - Y_BITS),
                (len + 1) / 2,
                len / 2,
            )
        }
    }
}

// https://lemire.me/blog/2018/01/08/how-fast-can-you-bit-interleave-32-bit-integers/
private fun interleaveWithZeros(input: UInt): ULong {
    var w = input.toULong()
    w = (w xor (w shl 16)) and 0x0000ffff0000ffffuL
    w = (w xor (w shl 8)) and 0x00ff00ff00ff00ffuL
    w = (w xor (w shl 4)) and 0x0f0f0f0f0f0f0f0fuL
    w = (w xor (w shl 2)) and 0x3333333333333333uL
    w = (w xor (w shl 1)) and 0x5555555555555555uL
    return w
}

private fun deInterleaveEvenBits(input: ULong): UInt {
    var w = input and 0x5555555555555555uL
    w = (w or (w shr 1)) and 0x3333333333333333uL
    w = (w or (w shr 2)) and 0x0f0f0f0f0f0f0f0fuL
    w = (w or (w shr 4)) and 0x00ff00ff00ff00ffuL
    w = (w or (w shr 8)) and 0x0000ffff0000ffffuL
    w = (w or (w shr 16)) and 0x00000000ffffffffuL
    return w.toUInt()
}