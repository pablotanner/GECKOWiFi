package com.thesis.geckowifi.verification.golden

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GeoQueryEncoderGoldenTest(private val v: QueryVector, @Suppress("unused") private val name: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun data(): List<Array<Any>> = GoldenVectors.golden.queries.map { arrayOf(it, it.name) }
    }

    @Test
    fun matchesReference() {
        if (v.err != null) {
            assertThrows("reference rejects: ${v.err}", Exception::class.java) {
                UnderTest.encode(v.lon, v.lat, v.alt, v.radius)
            }
            return
        }

        val got = UnderTest.encode(v.lon, v.lat, v.alt, v.radius)
        assertEquals("minAlt", v.minAlt, got.minAlt)
        assertEquals("maxAlt", v.maxAlt, got.maxAlt)

        assumeTrue("reference output not stable for ${v.name}", v.stable)
        val expected = v.xy.map { it.value to it.len }.toSet()
        val actual = got.xy.toSet()
        val missing = expected - actual
        val extra = actual - expected
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            fail("xy bitstrings differ\n  missing: ${fmt(missing)}\n  extra:   ${fmt(extra)}")
        }

        UnderTest.requestBytes(got)?.let { assertArrayEquals("request bytes", GoldenVectors.b64(v.requestB64!!), it) }
    }

    private fun fmt(s: Set<Pair<ULong, Int>>) =
        s.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString { "%016x/%d".format(it.first.toLong(), it.second) }
}