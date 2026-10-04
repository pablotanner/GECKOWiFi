package com.thesis.geckowifi.data.remote

import android.net.Network
import com.thesis.geckowifi.BuildConfig
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.geopki.bitstring.RawXYBitString
import com.thesis.geckowifi.geopki.crypto.GeoPkiQuery
import com.thesis.geckowifi.geopki.crypto.GeoPkiVerificationException
import com.thesis.geckowifi.geopki.crypto.ensureConsistency
import com.thesis.geckowifi.geopki.crypto.verifyResponse
import com.thesis.geckowifi.verification.XYBitString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import geopki.Request as ProtoRequest
import geopki.Response as ProtoResponse
import geopki.XYBitString as ProtoXYBitString

sealed class GeckoResponse {
    /**
     * [certificates] is exactly `response.Certificates`, JSON-decoded - every
     * one of them has already been confirmed (in [verifyResponse]) to be
     * referenced by a hash inside the signed node tree. This is a genuine
     * cryptographic guarantee, not just "the server said so".
     */
    data class Success(
        val certificates: List<GeoCertificate>,
        /**
         * Certificates that were cryptographically confirmed (present in the
         * signed, root-hash-checked node tree) but whose JSON body could not
         * be decoded into [GeoCertificate] - e.g. a certificate written
         * against an older/different schema. These are dropped from
         * [certificates] rather than failing the whole response: one
         * malformed certificate at a location must not mask every other
         * (possibly hostile) certificate actually registered there.
         */
        val unparsedCount: Int = 0,
        /**
         * True if the query actually went out over the preferred network
         * binding passed to [GeckoClient] (e.g. cellular, via
         * [GeckoClient]'s `network` constructor param); false if none was
         * requested, or if it fell back to the default route (see
         * [GeckoClient]'s `fallbackClient` doc comment) - a real
         * security-relevant distinction, not decoration.
         */
        val usedPreferredNetwork: Boolean = false
    ) : GeckoResponse()

    /** Could not reach the server, or its response could not be parsed at all. Distinct from [ProofFailure]. */
    data class Unreachable(val cause: String) : GeckoResponse()

    /**
     * The server was reachable and its response was well-formed protobuf,
     * but cryptographic/structural verification failed (bad signature, root
     * hash mismatch, incomplete tree, certificate not covered by any node
     * hash, inconsistent append-only-log evidence, ...). This is positive
     * evidence of a hostile or broken server - callers must map it to
     * CONFLICT, never treat it as absence of data.
     */
    data class ProofFailure(val cause: String) : GeckoResponse()
}

class GeckoClient(
    private val baseUrl: String = BuildConfig.GEOPKI_URL,
    /**
     * Bind all requests to this specific network (e.g. cellular) rather than
     * whatever the device's default route currently is. This is a real
     * security property, not just a convenience: if verification queries
     * went out over the WiFi network being evaluated, an evil twin (or any
     * real captive portal, which routinely firewalls everything except its
     * own login server) could simply block or interfere with the
     * verification query itself - silently defeating the whole mechanism
     * without breaking any cryptography, since a blocked query surfaces as
     * [GeckoResponse.Unreachable], which correctly never warns anyone. See
     * docs/app-design.md's "GECKO connectivity channel" section. Pass
     * `NetworkObserver.cellularNetwork()` here when available; `null` (the
     * default) uses whatever OkHttp/the system would normally route through.
     */
    network: Network? = null,
    private val client: OkHttpClient = buildClient(network),
    /**
     * Only used as a fallback when [network] was given and binding to it
     * fails (e.g. a special test-only address like the emulator's 10.0.2.2
     * alias isn't routable over a secondary network like simulated
     * cellular). Falling back here is logged, not silent, and is a real
     * security tradeoff worth being honest about: it reintroduces exactly
     * the WiFi-can-block-its-own-verification weakness binding to cellular
     * was meant to close (docs/app-design.md). Fine for this prototype;
     * a production build should probably surface this as a visible warning
     * rather than silently degrading.
     */
    private val fallbackClient: OkHttpClient? = if (network != null) OkHttpClient() else null
) {
    /** True iff a preferred network (e.g. cellular) was actually requested for this client. */
    private val hasPreferredNetwork: Boolean = network != null
    private val octetStream = "application/octet-stream".toMediaType()
    // coerceInputValues: Go's `omitempty` doesn't suppress non-pointer struct
    // fields (a well-known Go quirk), so any certificate that doesn't set
    // "portal" (e.g. every pure-enterprise/eduroam certificate) still gets a
    // real "portal":{"domains":null,...} object from the server - a JSON
    // null for a non-nullable-with-default Kotlin field, which
    // kotlinx.serialization otherwise rejects instead of falling back to the
    // default. Confirmed via a real dropped-certificate log against a live
    // server (demo-eduroam-001): "Expected start of the array '[', but had
    // 'n' instead at path: $.portal.domains".
    private val certificateJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Queries the GECKO map server for the given surface bit strings and
     * altitude range, over the real `/v1/query?c` protobuf endpoint, and
     * cryptographically verifies the response before ever returning
     * [GeckoResponse.Success]. A parsed-but-unverified response never
     * reaches the caller as success.
     */
    suspend fun queryLocation(
        bitStrings: List<XYBitString>,
        minAltitude: Int,
        maxAltitude: Int
    ): GeckoResponse = withContext(Dispatchers.IO) {
        try {
            val publicKey = PinnedServerKey.publicKey

            val rawBitStrings = bitStrings.map { it.toRawXYBitString() }

            val protoRequest = ProtoRequest(
                XYBitStrings = rawBitStrings.map {
                    ProtoXYBitString(geopki_XYBitString = it.xyBitString.toLong(), XYBitStringLen = it.xyBitStringLen)
                },
                MinAltitude = minAltitude,
                MaxAltitude = maxAltitude
            )

            val httpRequest = Request.Builder()
                .url("$baseUrl/v1/query?c") // ?c = include full certificates
                .post(ProtoRequest.ADAPTER.encode(protoRequest).toRequestBody(octetStream))
                .build()

            val (rawResponse, usedPreferredNetwork) = executeWithFallback(httpRequest)
            rawResponse.use { httpResponse ->
                if (!httpResponse.isSuccessful) {
                    return@withContext GeckoResponse.Unreachable("HTTP ${httpResponse.code}")
                }

                val bytes = httpResponse.body?.bytes()
                    ?: return@withContext GeckoResponse.Unreachable("empty response body")

                val protoResponse = try {
                    ProtoResponse.ADAPTER.decode(bytes)
                } catch (e: Exception) {
                    return@withContext GeckoResponse.Unreachable("malformed response: ${e.message}")
                }

                val query = GeoPkiQuery(rawBitStrings, minAltitude, maxAltitude)

                try {
                    verifyResponse(protoResponse, query, publicKey)
                    ensureConsistency(protoResponse, publicKey)
                } catch (e: GeoPkiVerificationException) {
                    return@withContext GeckoResponse.ProofFailure(e.message ?: "proof verification failed")
                }

                // verifyResponse already confirmed every certificate here is
                // referenced by a hash inside the signed, root-hash-checked
                // node tree - only the JSON decoding below remains unverified,
                // and is done per-certificate so one malformed entry doesn't
                // discard every other (possibly hostile) certificate at this
                // location.
                val certificates = ArrayList<GeoCertificate>(protoResponse.Certificates.size)
                var unparsedCount = 0
                for (raw in protoResponse.Certificates) {
                    val bytes = raw.toByteArray()
                    try {
                        certificates += parseCertificate(bytes)
                    } catch (e: Exception) {
                        unparsedCount++
                        System.err.println("GeckoClient: dropping unparsable certificate ${certificateIdOf(bytes)}: ${e.message}")
                    }
                }
                GeckoResponse.Success(certificates, unparsedCount, usedPreferredNetwork)
            }
        } catch (e: Exception) {
            GeckoResponse.Unreachable(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Executes [request] on the preferred (possibly network-bound) client;
     * if that throws and a [fallbackClient] exists, retries on it instead of
     * failing outright - logged, not silent, since it's a real security
     * tradeoff (see the [fallbackClient] doc comment). The returned boolean
     * is true iff the preferred (network-bound) client actually served the
     * request - false both when no preferred network was requested at all
     * and when it failed and the fallback served it instead.
     */
    private fun executeWithFallback(request: Request): Pair<okhttp3.Response, Boolean> =
        try {
            client.newCall(request).execute() to hasPreferredNetwork
        } catch (e: Exception) {
            val fallback = fallbackClient ?: throw e
            System.err.println(
                "GeckoClient: request over the preferred network failed (${e.message}) - " +
                    "falling back to the default route. This weakens the anti-interference " +
                    "property described on GeckoClient's fallbackClient parameter."
            )
            fallback.newCall(request).execute() to false
        }

    // CONFIRMED (2026-09-11, against a live geopki server - see
    // app/src/test/resources/geopki-fixtures/cert0.json): each entry of
    // response.Certificates is a UTF-8 JSON document, not binary/protobuf,
    // and GeoCertificate.kt's shape correctly matches the server's real Go
    // `GeoCertificate` struct (certificate_id/wifi/portal/areas/
    // areas_altitude/not_valid_after all line up, including "wifi" genuinely
    // being required - no `omitempty` on the Go side either).
    private fun parseCertificate(bytes: ByteArray): GeoCertificate =
        certificateJson.decodeFromString(GeoCertificate.serializer(), String(bytes, Charsets.UTF_8))

    /** Best-effort `certificate_id` of a certificate that failed to parse, so the log says which one. */
    private fun certificateIdOf(bytes: ByteArray): String =
        runCatching {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["certificate_id"]?.jsonPrimitive?.content
        }.getOrNull()?.let { "\"$it\"" } ?: "(no certificate_id)"

    companion object {
        private fun buildClient(network: Network?): OkHttpClient =
            OkHttpClient.Builder().apply {
                if (network != null) {
                    socketFactory(network.socketFactory)
                    // socketFactory alone still resolves hostnames via the default network.
                    dns(object : Dns {
                        override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                    })
                }
            }.build()
    }
}

/**
 * Visible to the golden-vector test adapter ([com.thesis.geckowifi.verification.golden.UnderTest])
 * specifically so it reuses this exact conversion rather than re-implementing
 * it - a golden test that verified its own re-implementation of this
 * bit-layout logic instead of the real production code wouldn't actually
 * catch a bug here.
 */
internal fun XYBitString.toRawXYBitString(): RawXYBitString {
    val padded = bits.padEnd(64, '0')
    val value = if (padded.isEmpty()) 0uL else java.lang.Long.parseUnsignedLong(padded, 2).toULong()
    return RawXYBitString(xyBitString = value, xyBitStringLen = bits.length)
}
