package com.thesis.geckowifi.verification

import com.thesis.geckowifi.geopki.bitstring.XY_BITS
import com.thesis.geckowifi.geopki.bitstring.bitStringPairFromStringPair
import com.thesis.geckowifi.geopki.bitstring.undiscretizeX
import com.thesis.geckowifi.geopki.bitstring.undiscretizeY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoQueryEncoderTest {

    private val encoder = GeoQueryEncoder()

    /** Decodes the cell (via the real geopki/bitstring port) and checks it contains (lat, lng). */
    private fun XYBitString.covers(lat: Double, lng: Double): Boolean {
        val xy = bitStringPairFromStringPair(bits, "").xy
        val (minLng, minLat) = xy.geodeticCoordinates()
        val maxLng = undiscretizeX(xy.xMax())
        val maxLat = undiscretizeY(xy.yMax())
        return lng >= minLng && lng < maxLng && lat >= minLat && lat < maxLat
    }

    @Test
    fun encodeQuery_returnsNonEmptyCover() {
        val cells = encoder.encodeQuery(lat = 47.3769, lng = 8.5417, radiusMeters = 50)
        assertTrue(cells.isNotEmpty())
    }

    @Test
    fun encodeQuery_queryPointIsCoveredByAtLeastOneCell() {
        val lat = 47.3769
        val lng = 8.5417
        val cells = encoder.encodeQuery(lat, lng, radiusMeters = 50)

        assertTrue(
            "expected the query point to fall inside at least one returned cell",
            cells.any { it.covers(lat, lng) }
        )
    }

    @Test
    fun encodeQuery_coversPointsAroundTheEdgeOfTheRadius() {
        // Not just the center - also points ~radius meters away in each
        // cardinal direction should land in the cover (this is what a real
        // "am I near a registered network" query depends on).
        val lat = 47.3769
        val lng = 8.5417
        val radius = 100
        val cells = encoder.encodeQuery(lat, lng, radiusMeters = radius)

        val metersPerDegreeLat = 111_320.0
        val latOffset = radius / metersPerDegreeLat
        val lngOffset = radius / (metersPerDegreeLat * Math.cos(Math.toRadians(lat)))

        val edgePoints = listOf(
            lat + latOffset * 0.9 to lng,
            lat - latOffset * 0.9 to lng,
            lat to lng + lngOffset * 0.9,
            lat to lng - lngOffset * 0.9
        )

        for ((edgeLat, edgeLng) in edgePoints) {
            assertTrue(
                "expected ($edgeLat, $edgeLng) near the edge of the ${radius}m radius to be covered",
                cells.any { it.covers(edgeLat, edgeLng) }
            )
        }
    }

    @Test
    fun encodeQuery_zeroRadius_returnsFullPrecisionSingleCell() {
        val cells = encoder.encodeQuery(lat = 47.3769, lng = 8.5417, radiusMeters = 0)
        assertEquals(1, cells.size)
        assertEquals(XY_BITS, cells.single().bits.length)
    }

    @Test
    fun encodeQuery_largerRadiusNeverProducesFinerPrecision() {
        val small = encoder.encodeQuery(lat = 47.3769, lng = 8.5417, radiusMeters = 10)
        val large = encoder.encodeQuery(lat = 47.3769, lng = 8.5417, radiusMeters = 200)

        assertTrue(large.first().bits.length <= small.first().bits.length)
    }

    @Test
    fun encodeQuery_rejectsInvalidLatitude() {
        assertThrows(IllegalArgumentException::class.java) { encoder.encodeQuery(91.0, 0.0, 10) }
    }

    @Test
    fun encodeQuery_rejectsInvalidLongitude() {
        assertThrows(IllegalArgumentException::class.java) { encoder.encodeQuery(0.0, 181.0, 10) }
    }

    @Test
    fun encodeQuery_rejectsOutOfRangeRadius() {
        assertThrows(IllegalArgumentException::class.java) { encoder.encodeQuery(0.0, 0.0, 255) }
    }

    @Test
    fun altitudeBounds_nullAltitude_returnsFullRange() {
        val (min, max) = encoder.altitudeBounds(null)
        assertEquals(0, min)
        assertEquals(32767, max)
    }

    @Test
    fun altitudeBounds_encodesAroundKnownAltitude() {
        val (min, max) = encoder.altitudeBounds(altitude = 400.0, uncertaintyMeters = 50)
        // 400 - (-11000) = 11400; uncertainty is inflated by RADIUS_ERROR_FACTOR
        // (1.0052) same as encodeQuery()'s radius - ceil(50 * 1.0052) = 51,
        // not the raw 50 - confirmed against golden vectors (2026-09-28).
        assertEquals(11349, min)
        assertEquals(11451, max)
    }
}
