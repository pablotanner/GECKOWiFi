package com.thesis.geckowifi.data.local

import com.thesis.geckowifi.data.model.VerificationResult
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

    override fun clear() = records.clear()
}

interface TrustPreferenceStore {
    var strictMode: Boolean
    var queryRadiusMeters: Int
}

class InMemoryTrustPreferenceStore : TrustPreferenceStore {
    override var strictMode: Boolean = false
    override var queryRadiusMeters: Int = 50
}