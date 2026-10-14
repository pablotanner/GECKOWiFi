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
 * A certificate with **no** areas covers nothing and is dropped: a claim
 * without geography can't be bounded. The map server is assumed to reject such
 * certificates anyway (see docs/design-decisions.md).
 *
 * Areas use geopki's layout, not GeoJSON's (see [com.thesis.geckowifi.data.model.GeoCertArea]):
 * each exterior ring is a polygon, interior rings are holes. The server ignores
 * holes when indexing, so a device in a hole still receives the certificate;
 * honouring them here follows what the operator registered.
 *
 * Geometry is planar (equirectangular, metres) around the device point:
 * accurate to well under a metre at the city-block scale GeoCert areas use,
 * and, like [GeoQueryEncoder], not valid across a pole or the antimeridian -
 * no real GeoCert area spans either.
 */
fun GeoCertificate.coversLocation(lat: Double, lng: Double, radiusMeters: Int): Boolean {
    val r = radiusMeters.toDouble()
    return areas.any { area -> circleIntersectsArea(lat, lng, r, area.exteriorRings, area.interiorRings) }
}

private fun circleIntersectsArea(
    lat: Double, lng: Double, radiusM: Double,
    exteriors: List<List<List<Double>>>, interiors: List<List<List<Double>>>
): Boolean {
    val outer = exteriors.filter { it.size >= 3 }
    val holes = interiors.filter { it.size >= 3 }
    if (outer.isEmpty()) return false

    val insideRegion = outer.any { pointInRing(lat, lng, it) } && holes.none { pointInRing(lat, lng, it) }
    if (insideRegion) return true

    // Outside the region: the circle reaches it iff it reaches any boundary edge
    // (an exterior ring, or - if the device sits in a hole - the hole's ring).
    return (outer + holes).any { ring -> minDistanceToRingMeters(lat, lng, ring) <= radiusM }
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
