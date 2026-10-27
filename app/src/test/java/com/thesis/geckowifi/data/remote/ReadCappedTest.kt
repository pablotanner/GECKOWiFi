package com.thesis.geckowifi.data.remote

import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadCappedTest {

    @Test
    fun bodyUpToTheLimit_isReadWhole() {
        val bytes = ByteArray(1000) { it.toByte() }
        assertArrayEquals(bytes, GeckoClient.readCapped(bytes.toResponseBody(), max = 1000))
        assertEquals(0, GeckoClient.readCapped(ByteArray(0).toResponseBody(), max = 1000)!!.size)
    }

    @Test
    fun bodyOverTheLimit_isRefused() {
        assertNull(GeckoClient.readCapped(ByteArray(1001).toResponseBody(), max = 1000))
    }

    @Test
    fun endlessBody_stopsJustPastTheLimit() {
        // what an attacker on the checked network could send; the old body.bytes() read it all
        var produced = 0L
        val endless = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                sink.write(ByteArray(byteCount.toInt().coerceAtMost(8192)))
                produced += byteCount.coerceAtMost(8192)
                return byteCount.coerceAtMost(8192)
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }
        assertNull(GeckoClient.readCapped(endless.buffer().asResponseBody(), max = 100_000))
        assertTrue("read $produced bytes", produced < 100_000 + 2 * 8192)
    }
}
