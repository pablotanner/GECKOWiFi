package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.VerificationResult
import java.util.concurrent.ConcurrentHashMap

class DecisionCache(private val ttlMs: Long = 5 * 60_000L) {
    private data class Entry(val result: VerificationResult, val storedAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(networkKey: String, host: String) = "$networkKey|${host.lowercase()}"

    fun get(networkKey: String, host: String): VerificationResult? {
        val k = key(networkKey, host)
        val entry = entries[k] ?: return null
        if (System.currentTimeMillis() - entry.storedAt > ttlMs) {
            entries.remove(k); return null
        }
        return entry.result
    }

    fun put(networkKey: String, host: String, result: VerificationResult) {
        entries[key(networkKey, host)] = Entry(result, System.currentTimeMillis())
    }

    fun invalidateAll() = entries.clear()
}