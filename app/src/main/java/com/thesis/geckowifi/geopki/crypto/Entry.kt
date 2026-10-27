package com.thesis.geckowifi.geopki.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Port of geopki `pkg/crypto/entry.go` (map version 2). A node lists each of
 * its certificates once per type the certificate has:
 * `len(type) | type | SHA-256(certificate)`. The node hash covers the entries,
 * so the client knows every certificate's type, and the server only sends the
 * certificates of the types the client asks for (`Types` in the request).
 */
fun newEntry(type: String, hash: SHA256Hash): ByteArray {
    val name = type.toByteArray(Charsets.UTF_8)
    require(name.size <= 255) { "type longer than 255 bytes" }
    return byteArrayOf(name.size.toByte()) + name + hash.bytes
}

/** An entry's type and certificate hash. */
data class Entry(val type: String, val hash: SHA256Hash)

/** Splits an entry; throws [GeoPkiVerificationException] on a malformed one. */
fun parseEntry(entry: ByteArray): Entry {
    if (entry.isEmpty()) throw GeoPkiVerificationException("received an empty entry")
    val n = entry[0].toInt() and 0xFF
    if (entry.size != 1 + n + 32) throw GeoPkiVerificationException("received an entry of invalid length ${entry.size}")
    return Entry(String(entry, 1, n, Charsets.UTF_8), bytesToHash(entry.copyOfRange(1 + n, entry.size)))
}

/**
 * The types of a certificate: the names of its extensions (geopki
 * `GeoCertificate.Types`), or the empty type without extensions. The map server
 * derives a certificate's entries from these, so a delivered certificate shows
 * whether it was listed under its true types. Throws
 * [GeoPkiVerificationException] if the bytes aren't a JSON object.
 */
fun certificateTypes(certificate: ByteArray): Set<String> {
    val json = try {
        Json.parseToJsonElement(String(certificate, Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        throw GeoPkiVerificationException("received a certificate that isn't a JSON object")
    }
    val extensions = (json["extensions"] as? kotlinx.serialization.json.JsonObject)?.keys.orEmpty()
    return extensions.ifEmpty { setOf("") }
}
