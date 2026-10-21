package com.thesis.geckowifi.data.local

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The latest verdict per network (SSID), kept across restarts so the status
 * card can say what GECKO found for the network the device is on, even when
 * the app was closed in between (docs/eduroam-decisions.md, D20). Only the
 * summary is kept: no portal hops, no certificates.
 */
@Serializable
data class StoredVerdict(
    val ssid: String,
    val bssid: String? = null,
    /** [com.thesis.geckowifi.data.model.VerificationState] name. */
    val state: String,
    val reason: String? = null,
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val isEnterprise: Boolean = false,
    val modeDescription: String? = null,
    /** [com.thesis.geckowifi.ui.CheckFailure] name, if any. */
    val failure: String? = null,
    /** Enterprise: the account that connected, and the server it was pinned to. */
    val account: String? = null,
    val serverNames: List<String> = emptyList()
)

interface VerdictStore {
    fun all(): List<StoredVerdict>
    fun latest(ssid: String): StoredVerdict?
    fun put(verdict: StoredVerdict)
    fun clear()
}

class InMemoryVerdictStore : VerdictStore {
    private val bySsid = linkedMapOf<String, StoredVerdict>()
    override fun all() = bySsid.values.sortedByDescending { it.timestamp }
    override fun latest(ssid: String) = bySsid[ssid.lowercase()]
    override fun put(verdict: StoredVerdict) { bySsid[verdict.ssid.lowercase()] = verdict }
    override fun clear() = bySsid.clear()
}

class SharedPrefsVerdictStore(private val prefs: SharedPreferences) : VerdictStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredVerdict.serializer())

    override fun all(): List<StoredVerdict> = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()
        .sortedByDescending { it.timestamp }

    override fun latest(ssid: String) = all().firstOrNull { it.ssid.equals(ssid, ignoreCase = true) }

    override fun put(verdict: StoredVerdict) {
        val kept = all().filterNot { it.ssid.equals(verdict.ssid, ignoreCase = true) }
        prefs.edit { putString(KEY, json.encodeToString(serializer, (listOf(verdict) + kept).take(MAX))) }
    }

    override fun clear() = prefs.edit { remove(KEY) }

    private companion object {
        const val KEY = "verdicts"
        const val MAX = 50
    }
}
