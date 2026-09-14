package com.thesis.geckowifi.data.local

import com.thesis.geckowifi.data.model.VerificationResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class VerificationRecord(
    val networkKey: String,
    val ssid: String?,
    val host: String,
    val state: String,
    val matchedCertificateId: String?,
    val reason: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: Long
)

interface HistoryStore {
    fun record(
        networkKey: String, ssid: String?, result: VerificationResult,
        latitude: Double?, longitude: Double?
    )
    fun recent(limit: Int = 100): List<VerificationRecord>
    fun lastVerifiedFor(host: String): VerificationRecord?
    fun clear()
}

class InMemoryHistoryStore : HistoryStore {
    private val records = CopyOnWriteArrayList<VerificationRecord>()

    override fun record(
        networkKey: String, ssid: String?, result: VerificationResult,
        latitude: Double?, longitude: Double?
    ) {
        records += VerificationRecord(
            networkKey, ssid, result.host, result.state.name,
            result.matchedCertificateId, result.reason,
            latitude, longitude, result.timestamp
        )
    }

    override fun recent(limit: Int) =
        records.sortedByDescending { it.timestamp }.take(limit)

    override fun lastVerifiedFor(host: String) = records
        .filter { it.host.equals(host, ignoreCase = true) && it.state == "VERIFIED" }
        .maxByOrNull { it.timestamp }

    override fun clear() = records.clear()
}

data class CATrustEntry(
    val caFingerprint: String,
    val label: String,
    val trustLevel: Int,
    val minLat: Double? = null, val maxLat: Double? = null,
    val minLng: Double? = null, val maxLng: Double? = null
) {
    fun appliesAt(lat: Double, lng: Double): Boolean {
        if (minLat == null || maxLat == null || minLng == null || maxLng == null) return true
        return lat in minLat..maxLat && lng in minLng..maxLng
    }
}

interface TrustPreferenceStore {
    var strictMode: Boolean
    var queryRadiusMeters: Int
    fun caEntries(): List<CATrustEntry>
    fun upsertCA(entry: CATrustEntry)
    fun trustedAt(fingerprint: String, lat: Double, lng: Double): Boolean
}

class InMemoryTrustPreferenceStore : TrustPreferenceStore {
    override var strictMode: Boolean = false
    override var queryRadiusMeters: Int = 50

    private val entries = ConcurrentHashMap<String, CATrustEntry>()

    override fun caEntries() = entries.values.toList()

    override fun upsertCA(entry: CATrustEntry) {
        entries[entry.caFingerprint] = entry
    }

    override fun trustedAt(fingerprint: String, lat: Double, lng: Double): Boolean =
        entries[fingerprint]?.appliesAt(lat, lng) ?: false
}