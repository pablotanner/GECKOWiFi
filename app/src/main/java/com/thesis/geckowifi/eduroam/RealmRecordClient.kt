package com.thesis.geckowifi.eduroam

import android.net.Network
import com.thesis.geckowifi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface RealmFetch {
    data class Fetched(val bytes: ByteArray) : RealmFetch

    /** The server says it has no record. Unauthenticated, so it means no more than [Unreachable]. */
    data object NotFound : RealmFetch

    data class Unreachable(val cause: String) : RealmFetch
}

/**
 * Fetches a [gecko.realm.SignedRealmRecord] from the realm endpoint
 * (`GET /v1/realm/<federation>/<realm>`, lab/realm). Transport only: the bytes are
 * trusted after [AuthenticatedEapPins.fromSignedRealmRecord], not before.
 *
 * Bound to [network] with no fallback to the default route: on the
 * onboarding session the only honest answers are "came over this network"
 * or "didn't come".
 */
class RealmRecordClient(
    private val baseUrl: String = BuildConfig.REALM_URL,
    network: Network?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .apply {
            if (network != null) {
                socketFactory(network.socketFactory)
                dns(object : Dns {
                    override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                })
            }
        }.build()
) {
    /** The record of [realm] within [federation] (D19). */
    suspend fun fetch(federation: String, realm: String): RealmFetch = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("$baseUrl/v1/realm/${federation.lowercase()}/${realm.lowercase()}").get().build()).execute().use { response ->
                when {
                    response.code == 404 -> RealmFetch.NotFound
                    !response.isSuccessful -> RealmFetch.Unreachable("HTTP ${response.code}")
                    else -> response.body?.bytes()?.let { RealmFetch.Fetched(it) } ?: RealmFetch.Unreachable("empty response body")
                }
            }
        } catch (e: Exception) {
            RealmFetch.Unreachable(e.message ?: e::class.java.simpleName)
        }
    }
}
