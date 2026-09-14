package com.thesis.geckowifi.vpn

object TlsSniParser {
    fun extractSni(data: ByteArray): String? {
        if (data.size < 5 || data[0] != 0x16.toByte()) return null   // not a TLS handshake record
        var pos = 5 + 4 + 2 + 32   // record header + handshake header + version + random
        if (pos >= data.size) return null
        val sessionIdLen = data[pos].toInt() and 0xFF; pos += 1 + sessionIdLen
        if (pos + 2 > data.size) return null
        val cipherSuitesLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2 + cipherSuitesLen
        if (pos >= data.size) return null
        val compMethodsLen = data[pos].toInt() and 0xFF; pos += 1 + compMethodsLen
        if (pos + 2 > data.size) return null
        pos += 2   // extensions total length
        while (pos + 4 <= data.size) {
            val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (extType == 0x0000) {   // server_name extension
                var p = pos + 2 + 1   // skip server_name_list_length + name_type
                val nameLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF); p += 2
                return String(data, p, nameLen, Charsets.US_ASCII)
            }
            pos += extLen
        }
        return null
    }
}