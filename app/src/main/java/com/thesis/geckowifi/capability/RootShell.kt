package com.thesis.geckowifi.capability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootShell {
    fun isAvailable(): Boolean = try {
        exec("id").contains("uid=0")
    } catch (e: Exception) { false }

    fun exec(command: String): String {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        return process.inputStream.bufferedReader().use { it.readText() }
            .also { process.waitFor() }
    }

    suspend fun execAsync(command: String): String =
        withContext(Dispatchers.IO) { exec(command) }

    /**
     * Like [exec], but reads the process's stdout as raw bytes instead of
     * decoding it as text - needed for binary output (e.g. `tcpdump -w -`'s
     * pcap stream, read by [RootPacketCapture]), where text decoding would
     * corrupt the data. Unlike [exec], stderr is NOT merged into stdout
     * (tcpdump's own startup messages there would otherwise corrupt the
     * pcap bytes).
     */
    fun execBytes(command: String): ByteArray {
        val process = ProcessBuilder("su", "-c", command).start()
        val bytes = process.inputStream.use { it.readBytes() }
        process.waitFor()
        return bytes
    }
}