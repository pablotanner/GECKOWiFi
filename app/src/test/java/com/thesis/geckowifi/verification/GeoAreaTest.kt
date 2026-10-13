package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.GeoCertArea
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.WiFiIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GeoCertificate.coversLocation]: the geographic bound that closes G2/G3 (docs/experiments.md). */
class GeoAreaTest {

    // A ~75 m x 55 m block in central Zurich; points are [lng, lat].
    private val block = listOf(
        listOf(8.5480, 47.3775), listOf(8.5490, 47.3775),
        listOf(8.5490, 47.3780), listOf(8.5480, 47.3780), listOf(8.5480, 47.3775)
    )

    private fun cert(vararg areas: GeoCertArea) = GeoCertificate(
        certificateId = "c", wifi = WiFiIdentity(ssid = "GeckoTest"),
        areas = areas.toList(), notValidAfter = "2099-01-01T00:00:00Z"
    )

    private fun multiPolygon(vararg polygons: List<List<List<Double>>>) =
        GeoCertArea(coordinates = polygons.toList())

    @Test
    fun deviceInsideArea_isCovered() {
        assertTrue(cert(multiPolygon(listOf(block))).coversLocation(47.37775, 8.5485, 50))
    }

    @Test
    fun deviceKilometresAway_isNotCovered() {
        // Bern, ~60 km from the block.
        assertFalse(cert(multiPolygon(listOf(block))).coversLocation(46.9480, 7.4474, 50))
    }

    @Test
    fun deviceJustOutsideButWithinRadius_isCovered() {
        // ~11 m south of the block's edge (0.0001 deg lat), within the 50 m radius.
        assertTrue(cert(multiPolygon(listOf(block))).coversLocation(47.3774, 8.5485, 50))
    }

    @Test
    fun deviceJustOutsideBeyondRadius_isNotCovered() {
        // ~33 m south of the edge (0.0003 deg lat), beyond a 20 m radius.
        assertFalse(cert(multiPolygon(listOf(block))).coversLocation(47.3772, 8.5485, 20))
    }

    @Test
    fun noArea_isTreatedAsUnscoped() {
        // Area-less certificate: covers everywhere (server must reject these; see doc).
        assertTrue(cert().coversLocation(0.0, 0.0, 50))
    }

    @Test
    fun deviceInHoleBeyondRadius_isNotCovered() {
        // A big block with a hole the device sits in, far from the hole's edge.
        val outer = listOf(
            listOf(8.540, 47.370), listOf(8.560, 47.370),
            listOf(8.560, 47.385), listOf(8.540, 47.385), listOf(8.540, 47.370)
        )
        val hole = listOf(
            listOf(8.547, 47.376), listOf(8.551, 47.376),
            listOf(8.551, 47.380), listOf(8.547, 47.380), listOf(8.547, 47.376)
        )
        val donut = cert(multiPolygon(listOf(outer, hole)))
        assertFalse(donut.coversLocation(47.378, 8.549, 20))   // middle of the hole
        assertTrue(donut.coversLocation(47.3745, 8.545, 20))   // in the ring itself
    }

    @Test
    fun anyOfSeveralPolygonsCovering_isEnough() {
        val far = listOf(
            listOf(7.44, 46.94), listOf(7.45, 46.94),
            listOf(7.45, 46.95), listOf(7.44, 46.95), listOf(7.44, 46.94)
        )
        val cert = cert(multiPolygon(listOf(far), listOf(block)))
        assertTrue(cert.coversLocation(47.37775, 8.5485, 50))
    }
}
