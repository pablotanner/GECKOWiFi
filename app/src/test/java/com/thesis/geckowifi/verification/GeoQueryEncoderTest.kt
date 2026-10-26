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

    /** Area of the cells in m^2 (flat approximation, fine at these sizes). */
    private fun List<XYBitString>.areaM2(lat: Double): Double = sumOf {
        val xy = bitStringPairFromStringPair(it.bits, "").xy
        val (minLng, minLat) = xy.geodeticCoordinates()
        val metersPerDegree = 111_320.0
        (undiscretizeX(xy.xMax()) - minLng) * metersPerDegree * Math.cos(Math.toRadians(lat)) *
            (undiscretizeY(xy.yMax()) - minLat) * metersPerDegree
    }

    @Test
    fun finerCells_stillCoverTheWholeCircle() {
        // Every cell touching the circle is kept at any cell size, so points just
        // inside the edge, in every direction, must stay covered.
        val random = java.util.Random(1)
        val metersPerDegree = 111_320.0
        for (radius in listOf(10, 25, 50, 100, 253)) {
            repeat(20) {
                val lat = 47.3 + random.nextDouble() * 0.2
                val lng = 8.4 + random.nextDouble() * 0.2
                val cells = encoder.encodeQuery(lat, lng, radius)
                for (step in 0 until 64) {
                    val bearing = 2 * Math.PI * step / 64
                    val pLat = lat + 0.999 * radius * Math.cos(bearing) / metersPerDegree
                    val pLng = lng + 0.999 * radius * Math.sin(bearing) /
                        (metersPerDegree * Math.cos(Math.toRadians(lat)))
                    assertTrue(
                        "r=$radius at ($lat, $lng): edge point at ${step * 360 / 64} deg not covered",
                        cells.any { it.covers(pLat, pLng) }
                    )
                }
            }
        }
    }

    @Test
    fun finerCells_coverLessThanTheReferenceCells() {
        // The point of F_GROW < REFERENCE_F_GROW: less area outside the circle.
        val random = java.util.Random(2)
        val reference = GeoQueryEncoder(fGrow = GeoQueryEncoder.REFERENCE_F_GROW)
        for (radius in listOf(25, 50, 100)) {
            val ratios = mutableMapOf<Double, MutableList<Double>>()
            val cellCounts = mutableMapOf<Double, MutableList<Int>>()
            repeat(200) {
                val lat = 47.3 + random.nextDouble() * 0.2
                val lng = 8.4 + random.nextDouble() * 0.2
                val circle = Math.PI * radius * radius
                for (f in listOf(1.0, 0.5, 0.25, 0.1, 0.05)) {
                    val cells = GeoQueryEncoder(fGrow = f).encodeQuery(lat, lng, radius)
                    ratios.getOrPut(f) { mutableListOf() } += cells.areaM2(lat) / circle
                    cellCounts.getOrPut(f) { mutableListOf() } += cells.size
                }
            }
            val median = { xs: List<Double> -> xs.sorted()[xs.size / 2] }
            for ((f, xs) in ratios) {
                println("r=$radius f=$f: median ${"%.2f".format(median(xs))}x the circle, " +
                    "max ${"%.2f".format(xs.max())}x, median ${cellCounts[f]!!.sorted()[100]} cells")
            }
            assertTrue(
                "r=$radius: finer cells should cover clearly less (median)",
                median(ratios[GeoQueryEncoder.F_GROW]!!) < 0.7 * median(ratios[GeoQueryEncoder.REFERENCE_F_GROW]!!)
            )
            // the default encoder is the finer one
            val lat = 47.37
            val lng = 8.54
            assertTrue(encoder.encodeQuery(lat, lng, radius).areaM2(lat) <= reference.encodeQuery(lat, lng, radius).areaM2(lat))
        }
    }
}
