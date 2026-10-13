package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.GeoCertificate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Whether the device is inside, or within [radiusMeters] of, one of this
 * certificate's registered areas.
 *
 * A GeoCertificate only vouches for a network inside the physical area it was
 * registered for, so a certificate whose area does not reach the device must
 * not be used to judge the network. Without this, the map server's cell-based
 * query returns certificates whose polygon merely shares a (coarser) query
 * cell with the device, so an attacker who holds a valid certificate for the
 * same SSID registered at *another* place is accepted here (see
 * docs/experiments.md, G2/G3). [radiusMeters] mirrors the query radius, so the
 * area check is exactly as lenient as the location query was and no more: a
 * registration whose own area is farther than that from the device is dropped.
 *
 * A certificate with **no** areas is treated as unscoped (covers everywhere),
 * which keeps legacy/area-less certificates working. The map server is assumed
 * to reject a certificate without an area, the same registration-side
 * assumption SSID exclusivity already relies on (see docs/design-decisions.md);
 * the client cannot geographically bound a claim that carries no geography.
 *
 * Geometry is planar (equirectangular, metres) around the device point:
 * accurate to well under a metre at the city-block scale GeoCert areas use,
 * and, like [GeoQueryEncoder], not valid across a pole or the antimeridian -
 * no real GeoCert area spans either.
 */
fun GeoCertificate.coversLocation(lat: Double, lng: Double, radiusMeters: Int): Boolean {
    // should never be empty, so reject
    if (areas.isEmpty()) return false
    val r = radiusMeters.toDouble()
    return areas.any { area -> area.coordinates.any { polygon -> circleIntersectsPolygon(lat, lng, r, polygon) } }
}

/** One GeoJSON polygon: ring 0 is the outer boundary, rings 1+ are holes. Each point is [lng, lat]. */
private fun circleIntersectsPolygon(
    lat: Double, lng: Double, radiusM: Double, polygon: List<List<List<Double>>>
): Boolean {
    val outer = polygon.firstOrNull()?.takeIf { it.size >= 3 } ?: return false
    val holes = polygon.drop(1).filter { it.size >= 3 }

    val insideRegion = pointInRing(lat, lng, outer) && holes.none { pointInRing(lat, lng, it) }
    if (insideRegion) return true

    // Outside the region: the circle reaches it iff it reaches any boundary edge
    // (the outer ring, or - if the device sits in a hole - the hole's ring).
    val rings = listOf(outer) + holes
    return rings.any { ring -> minDistanceToRingMeters(lat, lng, ring) <= radiusM }
}

/** Ray-casting point-in-polygon with x = lng, y = lat; the ring closes implicitly. */
private fun pointInRing(lat: Double, lng: Double, ring: List<List<Double>>): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val xi = ring[i][0]; val yi = ring[i][1]
        val xj = ring[j][0]; val yj = ring[j][1]
        if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
}

private fun minDistanceToRingMeters(lat: Double, lng: Double, ring: List<List<Double>>): Double {
    val mPerDegLat = 111_320.0
    val mPerDegLng = 111_320.0 * cos(Math.toRadians(lat))
    fun x(p: List<Double>) = (p[0] - lng) * mPerDegLng
    fun y(p: List<Double>) = (p[1] - lat) * mPerDegLat

    var best = Double.MAX_VALUE
    var j = ring.size - 1
    for (i in ring.indices) {
        best = min(best, pointToSegment(0.0, 0.0, x(ring[j]), y(ring[j]), x(ring[i]), y(ring[i])))
        j = i
    }
    return best
}

/** Shortest distance from point (px,py) to segment a-b, all in metres. */
private fun pointToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    val cx = ax + t * dx - px; val cy = ay + t * dy - py
    return sqrt(cx * cx + cy * cy)
}
