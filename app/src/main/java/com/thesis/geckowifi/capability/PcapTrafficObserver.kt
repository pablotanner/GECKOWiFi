package com.thesis.geckowifi.capability

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Root traffic observation: streams pcap from tcpdump and extracts SNI.
 * Observation only — pair with IptablesEnforcer for blocking.
 */
class PcapTrafficObserver(
    private val interfaceName: String = "any",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : TrafficObserver {

    private var process: Process? = null
    private var job: Job? = null

    override var isActive = false
        private set

    override fun start(onHost: (String) -> Unit) {
        if (isActive) return
        isActive = true
        job = scope.launch {
            try {
                val p = ProcessBuilder(
                    "su", "-c",
                    "tcpdump -i $interfaceName -s 0 -U -w - 'tcp port 443'"
                ).start().also { process = it }
                readPcapStream(DataInputStream(p.inputStream.buffered()), onHost)
            } catch (e: Exception) {
                this@PcapTrafficObserver.isActive = false
            }
        }
    }

    override fun stop() {
        job?.cancel()
        process?.destroy()
        process = null
        isActive = false
    }

    private suspend fun readPcapStream(input: DataInputStream, onHost: (String) -> Unit) {
        val globalHeader = ByteArray(24)
        input.readFully(globalHeader)
        val magic = ByteBuffer.wrap(globalHeader, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val order = if (magic == 0xa1b2c3d4.toInt()) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val linkType = ByteBuffer.wrap(globalHeader, 20, 4).order(order).int

        val recordHeader = ByteArray(16)
        while (currentCoroutineContext().isActive) {
            input.readFully(recordHeader)
            val capturedLength = ByteBuffer.wrap(recordHeader, 8, 4).order(order).int
            if (capturedLength <= 0 || capturedLength > 262_144) return
            val packet = ByteArray(capturedLength).also { input.readFully(it) }
            extractSni(packet, linkType)?.let(onHost)
        }
    }

    private fun extractSni(frame: ByteArray, linkType: Int): String? {
        val offset = when (linkType) {
            1 -> 14      // EN10MB
            101 -> 0     // RAW
            113 -> 16    // LINUX_SLL ("-i any")
            276 -> 20    // LINUX_SLL2
            else -> return null
        }
        if (frame.size <= offset) return null
        val ip = frame.copyOfRange(offset, frame.size)
        if (ip.isEmpty() || (ip[0].toInt() shr 4 and 0xF) != 4) return null

        val ihl = (ip[0].toInt() and 0xF) * 4
        if (ip.size < ihl + 20 || (ip[9].toInt() and 0xFF) != 6) return null
        val dataOffset = ((ip[ihl + 12].toInt() shr 4) and 0xF) * 4
        val payloadStart = ihl + dataOffset
        if (payloadStart >= ip.size) return null

        return TlsSniParser.extractSni(ip.copyOfRange(payloadStart, ip.size))
    }
}