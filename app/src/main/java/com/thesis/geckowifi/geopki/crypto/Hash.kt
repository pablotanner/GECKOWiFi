package com.thesis.geckowifi.geopki.crypto

import java.security.MessageDigest

/**
 * Kotlin port of netsec-ethz/geopki `pkg/crypto/hash.go`.
 *
 * Go's `SHA256Hash = [32]byte` is a value type usable directly as a map key
 * (structural equality "for free"). [ByteArray] in Kotlin has identity
 * equality, so this wraps it in a small class with content-based
 * `equals`/`hashCode` instead.
 */
class SHA256Hash(bytes: ByteArray) {
    init {
        require(bytes.size == SIZE_BYTES) { "hash must be exactly $SIZE_BYTES bytes, got ${bytes.size}" }
    }

    val bytes: ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is SHA256Hash && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val SIZE_BYTES = 32

        fun sha256(data: ByteArray): SHA256Hash =
            SHA256Hash(MessageDigest.getInstance("SHA-256").digest(data))
    }
}

/** The default SMT hash value, i.e. `SHA256(0x00)`. */
val DEFAULT_HASH: SHA256Hash = SHA256Hash(
    hexToBytes("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d")
)

/**
 * Copies [b] into a fixed-size 32-byte hash. Extra bytes are ignored and
 * missing bytes are left as zero, so it never throws on a short/long input
 * (mirrors Go's `BytesToHash`, which is intentionally permissive - unlike
 * [SHA256Hash]'s own constructor).
 */
fun bytesToHash(b: ByteArray): SHA256Hash {
    val h = ByteArray(SHA256Hash.SIZE_BYTES)
    System.arraycopy(b, 0, h, 0, minOf(b.size, h.size))
    return SHA256Hash(h)
}

/** Returns `null` for an absent (null or empty) hash, otherwise [bytesToHash]. */
fun bytesToHashOrNull(b: ByteArray?): SHA256Hash? {
    if (b == null || b.isEmpty()) return null
    return bytesToHash(b)
}

/** Returns `null` for an absent hash, otherwise its 32 bytes. */
fun hashOrNullToBytes(h: SHA256Hash?): ByteArray? = h?.bytes

fun byteArraysToHashes(bs: List<ByteArray>?): List<SHA256Hash>? = bs?.map { bytesToHash(it) }

fun hashesToByteArrays(hs: List<SHA256Hash>?): List<ByteArray>? = hs?.map { it.bytes }

/** Unsigned, big-endian-byte lexicographic comparator - matches Go's `bytes.Compare`. */
val UNSIGNED_BYTE_ARRAY_COMPARATOR: Comparator<ByteArray> = Comparator { a, b ->
    val len = minOf(a.size, b.size)
    for (i in 0 until len) {
        val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        if (cmp != 0) return@Comparator cmp
    }
    a.size - b.size
}

private fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0)
    return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
