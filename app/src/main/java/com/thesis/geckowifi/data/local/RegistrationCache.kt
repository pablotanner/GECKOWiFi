package com.thesis.geckowifi.data.local

import android.content.SharedPreferences
import androidx.core.content.edit
import com.thesis.geckowifi.data.model.GeoCertificate
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The last verified map answer for the device's area, kept so GECKO can use a
 * network's registration without asking again (docs/eduroam-decisions.md, D21):
 * the certificates as they passed the map proofs, and where, when and over
 * which connection they were fetched.
 *
 * Only certificates that passed the map server's proofs when they were
 * fetched are stored; the proofs themselves aren't kept, so the cache trusts
 * the app's own private storage, as the OS-level GECKO would trust its own.
 */
@Serializable
data class SavedLookup(
    /** Epoch millis when the answer arrived. */
    val fetchedAt: Long,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Int,
    /** How the query went out: "Mobile data", "Wi-Fi", "Default connection". */
    val route: String,
    /** SSID of the Wi-Fi the query went over, if known. */
    val viaSsid: String? = null,
    /** When the map server signed the map head the answer was proven against (epoch millis). */
    val mapHeadMillis: Long? = null,
    /** Root hash of that map head (base64): what "the map hasn't changed" is checked against (D26). */
    val mapRootHash: String? = null,
    /**
     * When the server's signed map head was last seen with the same [mapRootHash]
     * (epoch millis): the map, and so this answer, was still current then (D26).
     */
    val confirmedAt: Long? = null,
    val certificates: List<GeoCertificate>
)

/** A signed realm record (bytes as fetched, re-verified on every use), with when and how it arrived. */
@Serializable
data class SavedRealmRecord(
    val federation: String,
    val realm: String,
    val fetchedAt: Long,
    /** Base64 of the binary SignedRealmRecord. */
    val signedBase64: String,
    val via: String? = null
)

interface RegistrationCache {
    fun lookup(): SavedLookup?
    fun saveLookup(lookup: SavedLookup)
    fun realmRecords(): List<SavedRealmRecord>
    fun saveRealmRecord(record: SavedRealmRecord)
    /** Deletes the saved answer only; realm records aren't tied to a place and expire on their own. */
    fun clearLookup()
    fun clear()
}

class InMemoryRegistrationCache : RegistrationCache {
    private var lookup: SavedLookup? = null
    private val records = linkedMapOf<Pair<String, String>, SavedRealmRecord>()
    override fun lookup() = lookup
    override fun saveLookup(lookup: SavedLookup) { this.lookup = lookup }
    override fun realmRecords() = records.values.toList()
    override fun saveRealmRecord(record: SavedRealmRecord) { records[record.federation to record.realm] = record }
    override fun clearLookup() { lookup = null }
    override fun clear() { lookup = null; records.clear() }
}

class SharedPrefsRegistrationCache(private val prefs: SharedPreferences) : RegistrationCache {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val records = ListSerializer(SavedRealmRecord.serializer())

    override fun lookup(): SavedLookup? = prefs.getString(LOOKUP, null)
        ?.let { runCatching { json.decodeFromString(SavedLookup.serializer(), it) }.getOrNull() }

    override fun saveLookup(lookup: SavedLookup) = prefs.edit { putString(LOOKUP, json.encodeToString(SavedLookup.serializer(), lookup)) }

    override fun realmRecords(): List<SavedRealmRecord> = prefs.getString(RECORDS, null)
        ?.let { runCatching { json.decodeFromString(records, it) }.getOrNull() }
        .orEmpty()

    override fun saveRealmRecord(record: SavedRealmRecord) {
        val kept = realmRecords().filterNot { it.federation == record.federation && it.realm == record.realm }
        prefs.edit { putString(RECORDS, json.encodeToString(records, kept + record)) }
    }

    override fun clearLookup() = prefs.edit { remove(LOOKUP) }

    override fun clear() = prefs.edit { remove(LOOKUP); remove(RECORDS) }

    private companion object {
        const val LOOKUP = "lookup"
        const val RECORDS = "realm_records"
    }
}
