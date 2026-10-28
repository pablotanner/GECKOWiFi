package com.thesis.geckowifi.ui.lab

import com.thesis.geckowifi.data.remote.MapServerExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lab visualizations' model of the answer captured from the map-version-2 server (geopki-fixtures). */
class AnswerModelTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("geopki-fixtures/$name")!!.use { it.readBytes() }

    private val model = AnswerModel.of(
        MapServerExchange(0, "lookup", "http://test", fixture("request.bin"), fixture("response.bin"), null, 1, "OK")
    )!!

    @Test
    fun cellsAndCertificatesAreAtTheLab() {
        assertEquals(2, model.version)
        assertEquals(listOf("wifi"), model.types)
        // the golden "hb" point: ETH Hauptgebäude, Zürich
        model.cells.map(CellBounds::of).forEach { c ->
            assertTrue("$c", c.latMin in 47.0..48.0 && c.lngMin in 8.0..9.0)
            val (w, h) = c.sizeMeters()
            assertTrue("cell ${w}x$h m", w < 1_000 && h < 1_000)
        }
        val certificate = model.certificates.first()
        assertTrue(certificate.rings.single().size >= 4)
        assertNotNull(certificate.altitude)
        assertTrue(model.listings.getValue(certificate.hash).any { it.second == "wifi" })
    }

    @Test
    fun everyListingHasItsWholePathFromTheRootInTheAnswer() {
        model.listings.values.flatten().map { it.first }.distinct().forEach { node ->
            val levels = pathTo(model, node.xy, node.z)
            assertEquals(node.xy.length + node.z.length + 1, levels.size)
            assertTrue("missing node on the path to ${node.xy}/${node.z}", levels.all { it.node != null })
            assertTrue(levels.last().node === node)
        }
    }

    @Test
    fun altitudeOfAnAltitudeCell() {
        val (low, high) = CellBounds.altitudeOf("0")
        assertEquals(-11000, low)
        assertEquals(-11000 + 16384, high)
    }
}
