package com.thesis.geckowifi.capability

import com.thesis.geckowifi.vpn.TlsSniParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses a classic (non-pcapng) libpcap byte stream down to the TLS
 * ClientHello SNI hostnames observed in TCP traffic on a given port. Pure
 * parsing, with no dependency on root/a real device, so it's fully
 * unit-testable with synthetic sample bytes (see `PcapSniCaptureTest`).
 *
 * NOTE: [PcapTrafficObserver] already exists in this package and does this
 * same job as a live, continuous, `TrafficObserver`-integrated capture (root
 * `tcpdump` streamed and parsed on the fly) - it was already more complete
 * than a first draft of this file's now-deleted capture wrapper turned out
 * to be, and this was written without first checking for it. Its own
 * `extractSni()` is NOT unit-tested, though. Rather than run two competing,
 * un-reconciled parsers, treat this object's logic as the verified
 * reference: `PcapTrafficObserver.extractSni()`/its frame-header handling
 * should be checked against (or refactored to delegate to) this file's
 * tested logic, not left to diverge. Not yet done - flagged in
 * docs/prototype-decisions.md.
 *
 * Most captured packets on an existing connection are not a ClientHello -
 * [TlsSniParser.extractSni] returning `null` for them is the normal case,
 * not an error, and those packets are silently skipped.
 */
object PcapSniCapture {

    // Kotlin accepts a hex literal up to 32 bits directly as Int, reinterpreting
    // the bit pattern as signed - no manual two's-complement arithmetic needed.
    private const val MAGIC_LITTLE_ENDIAN: Int = 0xa1b2c3d4.toInt()
    private const val MAGIC_BYTES_SWAPPED: Int = 0xd4c3b2a1.toInt()

    private const val LINKTYPE_ETHERNET = 1
    private const val LINKTYPE_RAW = 101
    private const val LINKTYPE_LINUX_SLL = 113 // "cooked" capture, e.g. tcpdump -i any

    private const val ETHERTYPE_IPV4 = 0x0800
    private const val IP_PROTOCOL_TCP = 6

    /**
     * Every TLS SNI hostname found in TCP:[port] payloads within [pcapBytes],
     * in capture order. Returns an empty list (not an error) for anything
     * that isn't a recognizable classic-pcap stream, or that's truncated.
     */
    fun extractObservedHostnames(pcapBytes: ByteArray, port: Int = 443): List<String> {
        if (pcapBytes.size < 24) return emptyList()

        val magicLE = ByteBuffer.wrap(pcapBytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val order = when (magicLE) {
            MAGIC_LITTLE_ENDIAN -> ByteOrder.LITTLE_ENDIAN
            MAGIC_BYTES_SWAPPED -> ByteOrder.BIG_ENDIAN
            else -> return emptyList()
        }

        val header = ByteBuffer.wrap(pcapBytes).order(order)
        val linkType = header.getInt(20)

        val hostnames = mutableListOf<String>()
        var offset = 24
        while (offset + 16 <= pcapBytes.size) {
            val inclLen = header.getInt(offset + 8)
            val frameStart = offset + 16
            if (inclLen < 0 || frameStart + inclLen > pcapBytes.size) break // truncated/corrupt record

            val frame = pcapBytes.copyOfRange(frameStart, frameStart + inclLen)
            extractTcpPayload(frame, linkType, port)?.let { payload ->
                TlsSniParser.extractSni(payload)?.let { hostnames += it }
            }

            offset = frameStart + inclLen
        }
        return hostnames
    }

    /** Strips link-layer + IPv4 + TCP headers, returning the TCP payload only if dst port == [port]. */
    private fun extractTcpPayload(frame: ByteArray, linkType: Int, port: Int): ByteArray? {
        val ipStart = when (linkType) {
            LINKTYPE_ETHERNET -> {
                if (frame.size < 14) return null
                val etherType = ((frame[12].toInt() and 0xFF) shl 8) or (frame[13].toInt() and 0xFF)
                if (etherType != ETHERTYPE_IPV4) return null
                14
            }
            LINKTYPE_RAW -> 0
            LINKTYPE_LINUX_SLL -> 16
            else -> return null
        }
        if (ipStart + 20 > frame.size) return null

        val versionAndIhl = frame[ipStart].toInt() and 0xFF
        if (versionAndIhl shr 4 != 4) return null // IPv6 not handled
        val ipHeaderLen = (versionAndIhl and 0x0F) * 4
        if (ipHeaderLen < 20 || ipStart + ipHeaderLen > frame.size) return null

        val protocol = frame[ipStart + 9].toInt() and 0xFF
        if (protocol != IP_PROTOCOL_TCP) return null

        val tcpStart = ipStart + ipHeaderLen
        if (tcpStart + 20 > frame.size) return null

        val dstPort = ((frame[tcpStart + 2].toInt() and 0xFF) shl 8) or (frame[tcpStart + 3].toInt() and 0xFF)
        if (dstPort != port) return null

        val tcpHeaderLen = ((frame[tcpStart + 12].toInt() and 0xFF) shr 4) * 4
        if (tcpHeaderLen < 20 || tcpStart + tcpHeaderLen > frame.size) return null

        val payloadStart = tcpStart + tcpHeaderLen
        if (payloadStart >= frame.size) return null

        return frame.copyOfRange(payloadStart, frame.size)
    }
}
