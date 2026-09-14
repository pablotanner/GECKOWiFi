package com.thesis.geckowifi.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Builds synthetic Ethernet+IPv4+TCP frames carrying a real TLS ClientHello
 * (with an SNI extension), wraps them in a classic-pcap byte stream, and
 * confirms [PcapSniCapture] correctly recovers the hostname - end to end,
 * without needing root or a real device. See docs/app-design.md.
 */
class PcapSniCaptureTest {

    private fun be16(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun leU32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun buildTlsClientHelloWithSni(hostname: String): ByteArray {
        val hostBytes = hostname.toByteArray(Charsets.US_ASCII)

        // server_name extension content: list_len(2) + name_type(1) + name_len(2) + name
        val serverNameEntry = ByteArrayOutputStream().apply {
            write(be16(1 + 2 + hostBytes.size))
            write(0x00)
            write(be16(hostBytes.size))
            write(hostBytes)
        }.toByteArray()

        val sniExtension = ByteArrayOutputStream().apply {
            write(be16(0x0000)) // extension type = server_name
            write(be16(serverNameEntry.size))
            write(serverNameEntry)
        }.toByteArray()

        val body = ByteArrayOutputStream().apply {
            write(be16(0x0303)) // client_version
            write(ByteArray(32)) // random
            write(0x00) // session_id_len = 0
            write(be16(2)) // cipher_suites_len
            write(be16(0x002f)) // one cipher suite
            write(0x01) // compression_methods_len
            write(0x00) // null compression
            write(be16(sniExtension.size)) // extensions total length
            write(sniExtension)
        }.toByteArray()

        val handshake = ByteArrayOutputStream().apply {
            write(0x01) // handshake type = client_hello
            val len = body.size
            write((len shr 16) and 0xFF)
            write((len shr 8) and 0xFF)
            write(len and 0xFF)
            write(body)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(0x16) // record type = handshake
            write(be16(0x0301)) // record version
            write(be16(handshake.size))
            write(handshake)
        }.toByteArray()
    }

    private fun buildEthernetIpTcpFrame(tlsPayload: ByteArray, dstPort: Int = 443): ByteArray {
        val tcpHeader = ByteArrayOutputStream().apply {
            write(be16(51234)) // src port
            write(be16(dstPort))
            write(ByteArray(4)) // seq
            write(ByteArray(4)) // ack
            write((5 shl 4) and 0xFF) // data offset = 5 (20 bytes)
            write(0x18) // flags - irrelevant to the parser
            write(be16(65535)) // window
            write(be16(0)) // checksum - unchecked
            write(be16(0)) // urgent pointer
        }.toByteArray()

        val ipTotalLen = 20 + tcpHeader.size + tlsPayload.size
        val ipHeader = ByteArrayOutputStream().apply {
            write(0x45) // version 4, IHL 5 (20 bytes)
            write(0x00)
            write(be16(ipTotalLen))
            write(be16(0)) // identification
            write(be16(0)) // flags/fragment offset
            write(0x40) // TTL
            write(0x06) // protocol = TCP
            write(be16(0)) // header checksum - unchecked
            write(byteArrayOf(10, 0, 0, 1)) // src IP
            write(byteArrayOf(10, 0, 0, 2)) // dst IP
        }.toByteArray()

        val ethernetHeader = ByteArrayOutputStream().apply {
            write(ByteArray(6) { 0xAA.toByte() }) // dst MAC
            write(ByteArray(6) { 0xBB.toByte() }) // src MAC
            write(be16(0x0800)) // ethertype = IPv4
        }.toByteArray()

        return ethernetHeader + ipHeader + tcpHeader + tlsPayload
    }

    private fun pcapGlobalHeader(linkType: Int = 1): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte())) // little-endian magic
        write(byteArrayOf(2, 0)) // version_major
        write(byteArrayOf(4, 0)) // version_minor
        write(ByteArray(4)) // thiszone
        write(ByteArray(4)) // sigfigs
        write(leU32(65535)) // snaplen
        write(leU32(linkType))
    }.toByteArray()

    private fun pcapRecord(frame: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(ByteArray(4)) // ts_sec
        write(ByteArray(4)) // ts_usec
        write(leU32(frame.size)) // incl_len
        write(leU32(frame.size)) // orig_len
        write(frame)
    }.toByteArray()

    @Test
    fun extractsRealSniFromCapturedClientHello() {
        val frame = buildEthernetIpTcpFrame(buildTlsClientHelloWithSni("login.example-portal.com"))
        val pcap = pcapGlobalHeader() + pcapRecord(frame)

        val hostnames = PcapSniCapture.extractObservedHostnames(pcap, port = 443)

        assertEquals(listOf("login.example-portal.com"), hostnames)
    }

    @Test
    fun ignoresTrafficOnOtherPorts() {
        val frame = buildEthernetIpTcpFrame(buildTlsClientHelloWithSni("ignored.example.com"), dstPort = 8443)
        val pcap = pcapGlobalHeader() + pcapRecord(frame)

        val hostnames = PcapSniCapture.extractObservedHostnames(pcap, port = 443)

        assertTrue(hostnames.isEmpty())
    }

    @Test
    fun notAPcapStream_returnsEmptyRatherThanThrowing() {
        assertTrue(PcapSniCapture.extractObservedHostnames(byteArrayOf(1, 2, 3, 4, 5)).isEmpty())
        assertTrue(PcapSniCapture.extractObservedHostnames(ByteArray(0)).isEmpty())
    }

    @Test
    fun multiplePacketsInOneCaptureAreAllObserved() {
        val frame1 = buildEthernetIpTcpFrame(buildTlsClientHelloWithSni("first.example.com"))
        val frame2 = buildEthernetIpTcpFrame(buildTlsClientHelloWithSni("second.example.com"))
        val pcap = pcapGlobalHeader() + pcapRecord(frame1) + pcapRecord(frame2)

        val hostnames = PcapSniCapture.extractObservedHostnames(pcap)

        assertEquals(listOf("first.example.com", "second.example.com"), hostnames)
    }

    @Test
    fun truncatedCapture_stopsCleanlyInsteadOfThrowing() {
        val frame = buildEthernetIpTcpFrame(buildTlsClientHelloWithSni("truncated.example.com"))
        val fullPcap = pcapGlobalHeader() + pcapRecord(frame)
        val truncated = fullPcap.copyOfRange(0, fullPcap.size - 10)

        // Should not throw; a truncated final record is simply not included.
        PcapSniCapture.extractObservedHostnames(truncated)
    }
}
