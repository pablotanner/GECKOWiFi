package com.thesis.geckowifi.verification

import com.thesis.geckowifi.geopki.bitstring.C_Z
import com.thesis.geckowifi.geopki.bitstring.D
import com.thesis.geckowifi.geopki.bitstring.H
import com.thesis.geckowifi.geopki.bitstring.X_BITS
import com.thesis.geckowifi.geopki.bitstring.Y_BITS
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import com.thesis.geckowifi.geopki.bitstring.XYBitString as GeoXYBitString

/**
 * My own wrapper for a surface bit string ready to send to the map server -
 * kept distinct from [com.thesis.geckowifi.geopki.bitstring.XYBitString]
 * (the ported coordinate type) so [GeckoClient][com.thesis.geckowifi.data.remote.GeckoClient]'s
 * public API doesn't leak the geopki/ port's internal representation.
 */
data class XYBitString(
    val bits: String
)

/**
 * Encodes a client-side "where am I, how far around" query into the
 * XYBitStrings + altitude bounds the geopki map server expects.
 *
 * Calls into [com.thesis.geckowifi.geopki.bitstring] for the actual
 * coordinate discretization (26/25-bit full precision, real interleaving)
 * rather than reimplementing it - this class's previous version used its
 * own fixed-19-bit scheme and a separate hand-rolled interleaving routine,
 * which didn't match the server's real discretization at all.
 *
 * Deviation from the Go reference (`bitstring.PolygonsTo2DBitStrings` +
 * `S2Geometry2D`): that algorithm does a real polygon/circle intersection
 * BFS using S2 geometry. Per instructions.MD's explicitly-sanctioned
 * fallback ("no GDAL - use S2, or a direct circle -> grid approximation"),
 * and to avoid pulling in a JVM S2 port as a new dependency for this scope,
 * this instead covers the query circle's bounding box with a small grid of
 * same-precision cells sized to roughly the query radius. It's deliberately
 * conservative: it can return more area than the true circle (the box's
 * corners, outside the circle), never less. It also doesn't handle a query
 * area crossing the +/-180 degree antimeridian (matches the previous
 * version's behavior - coordinates are clamped, not wrapped).
 */
class GeoQueryEncoder {

    companion object {
        private val MIN_ALTITUDE: Int = D
        private val MAX_ALTITUDE: Int = H
        private val MAX_Z: Int = C_Z.toInt()

        // The Go client expands the radius slightly to compensate for
        // projection inaccuracies (see comm/query.go RADIUS_ERROR_FACTOR).
        private const val RADIUS_ERROR_FACTOR = 1.0052

        private const val METERS_PER_DEGREE_LAT = 111_320.0
    }

    /**
     * Creates a conservative XY bitstring cover around the query point: a
     * small grid of equal-precision cells spanning the query circle's
     * bounding box. May include cells slightly outside the circle, but will
     * not miss it.
     */
    fun encodeQuery(
        lat: Double,
        lng: Double,
        radiusMeters: Int
    ): List<XYBitString> {
        require(lat in -90.0..90.0) { "Latitude must be in [-90, 90]" }
        require(lng in -180.0..180.0) { "Longitude must be in [-180, 180]" }
        require(radiusMeters in 0..254) { "Radius must be between 0 and 254 meters" }

        val radius = ceil(radiusMeters * RADIUS_ERROR_FACTOR)

        // Approximate metres-per-degree bounds.
        val latDelta = radius / METERS_PER_DEGREE_LAT
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val lngDelta = radius / (METERS_PER_DEGREE_LAT * cosLat)

        val minLat = (lat - latDelta).coerceAtLeast(-90.0)
        val maxLat = (lat + latDelta).coerceAtMost(90.0)
        val minLng = (lng - lngDelta).coerceAtLeast(-180.0)
        val maxLng = (lng + lngDelta).coerceAtMost(180.0)

        // Coarsest shared precision whose cell side is still <= the bounding
        // box's own span in each dimension, so the box is covered by a small
        // number of cells - not one (which could sit off-center relative to
        // the box and miss part of it), and not thousands.
        val xPrecision = coarsestPrecision(spanDegrees = maxLng - minLng, fullSpanDegrees = 360.0, maxBits = X_BITS)
        var yPrecision = coarsestPrecision(spanDegrees = maxLat - minLat, fullSpanDegrees = 180.0, maxBits = Y_BITS)
        // Enforce bitstring.kt's interleaving invariant:
        // xPrecision == yPrecision || xPrecision == yPrecision + 1
        yPrecision = yPrecision.coerceIn((xPrecision - 1).coerceAtLeast(0), xPrecision)

        val xCellCount = 1L shl xPrecision
        val yCellCount = 1L shl yPrecision

        val xMinIdx = coordinateToIndex(minLng, -180.0, 180.0, xCellCount)
        val xMaxIdx = coordinateToIndex(maxLng, -180.0, 180.0, xCellCount)
        val yMinIdx = coordinateToIndex(minLat, -90.0, 90.0, yCellCount)
        val yMaxIdx = coordinateToIndex(maxLat, -90.0, 90.0, yCellCount)

        val result = ArrayList<XYBitString>()
        for (x in xMinIdx..xMaxIdx) {
            for (y in yMinIdx..yMaxIdx) {
                val cell = GeoXYBitString(
                    xMin = (x shl (X_BITS - xPrecision)).toUInt(),
                    yMin = (y shl (Y_BITS - yPrecision)).toUInt(),
                    xPrecision = xPrecision,
                    yPrecision = yPrecision
                )
                result += XYBitString(cell.toString())
            }
        }
        return result
    }

    /**
     * Returns encoded altitude bounds expected by geopki.proto.Request.
     *
     * The Go client uses the query radius as vertical uncertainty.
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

        val minZ = (encodedAltitude - uncertaintyMeters)
            .coerceAtLeast(0)

        val maxZ = (encodedAltitude + uncertaintyMeters)
            .coerceAtMost(MAX_Z)

        return minZ to maxZ
    }

    /** Smallest `bits` (coarsest cell, capped at [maxBits]) such that a cell's side is `<= spanDegrees`. */
    private fun coarsestPrecision(spanDegrees: Double, fullSpanDegrees: Double, maxBits: Int): Int {
        if (spanDegrees <= 0.0) return maxBits
        val bits = ceil(ln(fullSpanDegrees / spanDegrees) / ln(2.0)).toInt()
        return bits.coerceIn(0, maxBits)
    }

    private fun coordinateToIndex(
        value: Double,
        min: Double,
        max: Double,
        count: Long
    ): Long {
        if (value >= max) return count - 1
        if (value <= min) return 0

        return (((value - min) / (max - min)) * count)
            .toLong()
            .coerceIn(0, count - 1)
    }
}
