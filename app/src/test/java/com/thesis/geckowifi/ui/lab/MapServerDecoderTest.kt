package com.thesis.geckowifi.ui.lab

import com.thesis.geckowifi.data.remote.MapServerExchange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes the answer captured from the map-version-2 server (geopki-fixtures, see LiveFixtureTest). */
class MapServerDecoderTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("geopki-fixtures/$name")!!.use { it.readBytes() }

    private fun exchange(kind: String, request: ByteArray?, response: ByteArray?) = MapServerExchange(
        startedMillis = 0, kind = kind, url = "http://test", request = request, response = response,
        responseSize = response?.size, durationMillis = 1, outcome = "OK"
    )

    @Test
    fun lookup_showsTypesEntriesAndCertificates() {
        val tree = MapServerDecoder.decode(exchange("lookup", fixture("request.bin"), fixture("response.bin")))

        val request = tree["request"]!!.jsonObject
        assertEquals(listOf("wifi"), request["types"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(request["cells"]!!.jsonArray.isNotEmpty())

        val response = tree["response"]!!.jsonObject
        assertEquals("2", response["signed_map_head"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        val entries = response["tree"]!!.jsonObject["nodes_with_entries"]!!.jsonArray
            .flatMap { it.jsonObject["entries"]!!.jsonArray }.map { it.jsonObject }
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it["certificate_sha256"]!!.jsonPrimitive.content.length == 64 })

        // every delivered certificate is shown as JSON, with the types it is listed under in the tree
        val certificates = response["certificates"]!!.jsonArray.map { it.jsonObject }
        assertTrue(certificates.isNotEmpty())
        certificates.forEach { c ->
            assertTrue(c["certificate"] is JsonObject)
            assertTrue("wifi" in (c["listed_as"] as JsonArray).map { it.jsonPrimitive.content })
            assertTrue(entries.any { it["certificate_sha256"] == c["sha256"] })
        }
    }

    @Test
    fun mapHead_isDecoded() {
        val tree = MapServerDecoder.decode(exchange("map head", null, fixture("smh-standalone.bin")))
        val head = tree["response"]!!.jsonObject
        assertEquals("2", head["version"]!!.jsonPrimitive.content)
        assertEquals(64, head["root_hash"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun garbage_isShownNotThrown() {
        val tree = MapServerDecoder.decode(exchange("lookup", null, byteArrayOf(0x0a, 0x7f, 0x01)))
        assertTrue((tree["response"] as JsonPrimitive).content.contains("not decodable"))
    }
}
