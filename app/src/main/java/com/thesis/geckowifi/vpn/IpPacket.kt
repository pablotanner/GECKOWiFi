package com.thesis.geckowifi.vpn

object IpPacket {
    const val TCP = 6
    fun parse(buffer: ByteArray, length: Int): Parsed? {
        if (length < 20) return null
        val version = (buffer[0].toInt() shr 4) and 0xF
        if (version != 4) return null   // IPv6 explicitly out of scope for this pass
        val ihl = (buffer[0].toInt() and 0xF) * 4
        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != TCP) return null
        val tcpOffset = ihl
        val dstPort = ((buffer[tcpOffset + 2].toInt() and 0xFF) shl 8) or (buffer[tcpOffset + 3].toInt() and 0xFF)
        val dataOffset = ((buffer[tcpOffset + 12].toInt() shr 4) and 0xF) * 4
        val payloadStart = tcpOffset + dataOffset
        if (payloadStart >= length) return null
        return Parsed(protocol, dstPort, buffer.copyOfRange(payloadStart, length))
    }
    data class Parsed(val protocol: Int, val dstPort: Int, val payload: ByteArray)
}