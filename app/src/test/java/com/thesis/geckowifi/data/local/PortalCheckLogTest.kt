package com.thesis.geckowifi.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortalCheckLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun append_writesOneJsonLinePerCheck_inADailyFile() {
        val log = PortalCheckLog(tmp.root)
        val entry = PortalCheckLog.CheckEntry(
            runId = "r1", time = "2026-10-04T10:00:00Z", ssid = "GeckoTest", bssid = "94:83:c4:97:c2:3a",
            networkKey = "94:83:c4:97:c2:3a", mode = "discovery", outcome = "PortalReached",
            state = "VERIFIED", reason = "ok", lat = 47.0, lng = 8.0,
            hops = listOf(PortalCheckLog.HopEntry(index = 0, url = "https://portal.gecko-a.lab/", scheme = "https",
                host = "portal.gecko-a.lab", port = 443, status = 200, spkiSha256 = "k",
                source = "REDIRECT_PROBE", time = "2026-10-04T10:00:00Z", verdict = "VERIFIED"))
        )

        val file = log.append(entry)
        log.append(entry.copy(runId = "r2"))

        assertNotNull(file)
        assertEquals("checks-2026-10-04.jsonl", file!!.name)
        val lines = file.readLines()
        assertEquals(2, lines.size)
        val first = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("r1", first["run_id"]?.jsonPrimitive?.content)
        val hop = first["hops"]!!.jsonArray[0].jsonObject
        assertEquals("k", hop["spki_sha256"]?.jsonPrimitive?.content)
        assertEquals("VERIFIED", hop["verdict"]?.jsonPrimitive?.content)
    }
}
