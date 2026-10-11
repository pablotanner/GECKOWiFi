package com.thesis.geckowifi.data.local

import android.content.SharedPreferences
import androidx.core.content.edit
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
    /** Researcher view: individual access points, manual domain, diagnostics. Off = the end-user experience. */
    var labMode: Boolean
}

class InMemoryTrustPreferenceStore : TrustPreferenceStore {
    override var strictMode: Boolean = false
    override var queryRadiusMeters: Int = 50
    override var labMode: Boolean = false
}

/** Survives restarts, so lab mode doesn't have to be switched on again before every test run. */
class SharedPrefsTrustPreferenceStore(private val prefs: SharedPreferences) : TrustPreferenceStore {
    override var strictMode: Boolean
        get() = prefs.getBoolean("strict_mode", false)
        set(value) = prefs.edit { putBoolean("strict_mode", value) }
    override var queryRadiusMeters: Int
        get() = prefs.getInt("query_radius_m", 50)
        set(value) = prefs.edit { putInt("query_radius_m", value) }
    override var labMode: Boolean
        get() = prefs.getBoolean("lab_mode", false)
        set(value) = prefs.edit { putBoolean("lab_mode", value) }
}