package com.thesis.geckowifi.data.remote

import android.net.Network
import com.thesis.geckowifi.BuildConfig
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.GeoCertificateFormat
import com.thesis.geckowifi.geopki.bitstring.RawXYBitString
import com.thesis.geckowifi.geopki.crypto.GeoPkiQuery
import com.thesis.geckowifi.geopki.crypto.GeoPkiVerificationException
import com.thesis.geckowifi.geopki.crypto.SignedMapHead
import java.security.PublicKey
import java.util.Base64
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
import okhttp3.ResponseBody
import kotlin.time.Duration
import geopki.Request as ProtoRequest
import geopki.Response as ProtoResponse
import geopki.SignedMapHead as ProtoSignedMapHead
import geopki.XYBitString as ProtoXYBitString

/** What [GeckoClient.currentMapHead] found. */
sealed class MapHeadAnswer {
    /**
     * Verified against the pinned server key. Only [rootHash] is compared: it
     * is what the signature covers (see [GeckoResponse.Success.mapRootHash]).
     */
    class Current(val rootHash: ByteArray) : MapHeadAnswer() {
        val rootHashBase64: String get() = Base64.getEncoder().encodeToString(rootHash)
    }

    data class Unreachable(val cause: String) : MapHeadAnswer()

    data class ProofFailure(val reason: String) : MapHeadAnswer()
}

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
        val usedPreferredNetwork: Boolean = false,
        /** When the map server signed the map head this answer is proven against (epoch millis). */
        val mapHeadMillis: Long? = null,
        /**
         * Root hash of that map head (base64). Unlike [mapHeadMillis] it is covered
         * by the signature: geopki signs the raw map head with ECDSA, which on
         * P-256 uses only its first 32 bytes, the root hash.
         */
        val mapRootHash: String? = null,
        /**
         * Set when this isn't a live answer but a saved one standing in for an
         * unreachable server (docs/eduroam-decisions.md, D21, D25): when it was
         * fetched (epoch millis). Its proof was checked when it arrived.
         */
        val savedAt: Long? = null
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
    private val fallbackClient: OkHttpClient? = if (network != null) OkHttpClient() else null,
    /**
     * If set, a signed map head older than this is rejected as a possible
     * replay (see docs/experiments.md, R2): an attacker who replays an
     * older, still-validly-signed answer - one from before a certificate was
     * registered, revoked or re-keyed - would otherwise have it accepted.
     *
     * Off by default (`null`), on purpose. This is only a valid staleness
     * signal if the map server **re-signs the map head on a schedule**, the way
     * a Certificate Transparency log periodically re-signs its tree head. The
     * current geopki prototype server signs the map head only when the map
     * changes (a certificate insert/merge), so a genuine "latest" map head can
     * be days old, and comparing its timestamp with `now` wrongly rejects every
     * response (confirmed on hardware 2026-10-13). Enable this (e.g. 24 h, the
     * order of a CT maximum merge delay) only against a server that re-signs
     * periodically; GECKO itself does not yet define that cadence, which is the
     * point to make in the thesis. Relies on a roughly correct device clock.
     */
    private val maxMapHeadAge: Duration? = null,
    /** Nanoseconds since the Unix epoch (the map head's time base); injectable for tests. */
    private val nowNanos: () -> Long = { System.currentTimeMillis() * 1_000_000L }
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
                MaxAltitude = maxAltitude,
                // Only WiFi certificates come with bodies; the tree still lists
                // every certificate here, with its types (map version 2).
                Types = listOf(GeoCertificateFormat.WIFI)
            )

            val httpRequest = Request.Builder()
                .url("$baseUrl/v1/query?c") // ?c = include full certificates (of the requested types)
                .post(ProtoRequest.ADAPTER.encode(protoRequest).toRequestBody(octetStream))
                .build()

            val (rawResponse, usedPreferredNetwork) = executeWithFallback(httpRequest)
            rawResponse.use { httpResponse ->
                if (!httpResponse.isSuccessful) {
                    return@withContext GeckoResponse.Unreachable("HTTP ${httpResponse.code}")
                }

                val body = httpResponse.body
                    ?: return@withContext GeckoResponse.Unreachable("empty response body")
                val bytes = readCapped(body, MAX_ANSWER_BYTES)
                    ?: return@withContext GeckoResponse.Unreachable("answer larger than ${MAX_ANSWER_BYTES / 1_000_000} MB")

                val protoResponse = try {
                    ProtoResponse.ADAPTER.decode(bytes)
                } catch (e: Exception) {
                    return@withContext GeckoResponse.Unreachable("malformed response: ${e.message}")
                }

                // Completeness the other direction (see docs/acceptance-criteria.md, AC3)
                // is part of verifyResponse: with `?c`, every certificate the signed
                // tree lists as WiFi must come with its body. A missing one was
                // withheld - an attacker on the (cleartext) query path stripping it
                // from an otherwise valid answer - not "nothing registered here", so
                // it is a proof failure and surfaces as a CONFLICT. Certificates of
                // other types are listed in the tree but not sent.
                val query = GeoPkiQuery(
                    rawBitStrings, minAltitude, maxAltitude,
                    types = protoRequest.Types, withCertificates = true
                )

                try {
                    verifyResponse(protoResponse, query, publicKey)
                    ensureConsistency(protoResponse, publicKey)
                } catch (e: GeoPkiVerificationException) {
                    return@withContext GeckoResponse.ProofFailure(e.message ?: "proof verification failed")
                }

                // Freshness (see docs/experiments.md, R2). Only meaningful when the
                // server re-signs the map head on a schedule, so it is opt-in - see
                // maxMapHeadAge's doc for why it is off by default on this testbed.
                val maxAge = maxMapHeadAge
                if (maxAge != null) {
                    val mapHeadNanos = protoResponse.geopki_SignedMapHead?.Timestamp ?: 0L
                    if (isMapHeadStale(mapHeadNanos, nowNanos(), maxAge.inWholeNanoseconds)) {
                        return@withContext GeckoResponse.ProofFailure(
                            "map server answer is stale (signed map head older than $maxAge) - possible replay"
                        )
                    }
                }

                // verifyResponse already confirmed every certificate here is
                // referenced by a hash inside the signed, root-hash-checked
                // node tree - only the JSON decoding below remains unverified,
                // and is done per-certificate so one malformed entry doesn't
                // discard every other (possibly hostile) certificate at this
                // location. The server only sends WiFi certificates; any
                // other one (a certificate with several types) is left out here.
                val certificates = ArrayList<GeoCertificate>(protoResponse.Certificates.size)
                var unparsedCount = 0
                for (raw in protoResponse.Certificates) {
                    val bytes = raw.toByteArray()
                    try {
                        parseCertificate(bytes)?.let { certificates += it }
                    } catch (e: Exception) {
                        unparsedCount++
                        System.err.println("GeckoClient: dropping unparsable certificate ${certificateIdOf(bytes)}: ${e.message}")
                    }
                }
                GeckoResponse.Success(certificates, unparsedCount, usedPreferredNetwork,
                    protoResponse.geopki_SignedMapHead?.Timestamp?.let { it / 1_000_000L },
                    protoResponse.geopki_SignedMapHead?.RootHash?.toByteArray()?.let(Base64.getEncoder()::encodeToString))
            }
        } catch (e: Exception) {
            GeckoResponse.Unreachable(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * The map server's current signed map head (`GET /v1/get-smh`, a few hundred
     * bytes), to tell whether a saved answer is still current without
     * downloading it again (D26): the same root hash means the same map. Like
     * a replayed full answer (docs/experiments.md, R2), a replayed old map head
     * would pass: freshness of the map head is open either way.
     */
    suspend fun currentMapHead(): MapHeadAnswer = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/v1/get-smh").get().build()
            executeWithFallback(request).first.use { response ->
                if (!response.isSuccessful) return@withContext MapHeadAnswer.Unreachable("HTTP ${response.code}")
                val body = response.body ?: return@withContext MapHeadAnswer.Unreachable("empty response body")
                val bytes = readCapped(body, MAX_MAP_HEAD_BYTES)
                    ?: return@withContext MapHeadAnswer.Unreachable("map head larger than $MAX_MAP_HEAD_BYTES bytes")
                mapHeadFrom(bytes, PinnedServerKey.publicKey)
            }
        } catch (e: Exception) {
            MapHeadAnswer.Unreachable(e.message ?: e::class.java.simpleName)
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

    // Each entry of response.Certificates is a UTF-8 JSON document: geopki's
    // generic certificate with the WiFi network in extensions.wifi (see
    // GeoCertificateFormat). Null for a certificate of another kind.
    private fun parseCertificate(bytes: ByteArray): GeoCertificate? =
        GeoCertificateFormat.decode(certificateJson, String(bytes, Charsets.UTF_8))

    /** Best-effort `certificate_id` of a certificate that failed to parse, so the log says which one. */
    private fun certificateIdOf(bytes: ByteArray): String =
        runCatching {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["certificate_id"]?.jsonPrimitive?.content
        }.getOrNull()?.let { "\"$it\"" } ?: "(no certificate_id)"

    companion object {
        /**
         * The largest answer the client reads. The query often goes over the
         * network being checked, so an attacker could otherwise send an endless
         * (or, with gzip, a tiny but huge-when-unpacked) body and exhaust the
         * app's memory. Real answers stay far below: a few MB for a 100 m check
         * in a dense business district, about 10 MB for the network list's
         * 253 m lookup in a city centre (lab/density/README.md).
         */
        const val MAX_ANSWER_BYTES = 32_000_000L

        /** A signed map head is a few hundred bytes. */
        const val MAX_MAP_HEAD_BYTES = 64_000L

        /** Parses and verifies a serialized signed map head against [publicKey]. */
        internal fun mapHeadFrom(bytes: ByteArray, publicKey: PublicKey): MapHeadAnswer {
            val smh = try {
                SignedMapHead.fromProto(ProtoSignedMapHead.ADAPTER.decode(bytes))
            } catch (e: Exception) {
                return MapHeadAnswer.Unreachable("malformed map head: ${e.message}")
            }
            if (!smh.verify(publicKey)) return MapHeadAnswer.ProofFailure("signature on the map head is invalid")
            return MapHeadAnswer.Current(smh.mapHead.rootHash.bytes)
        }

        /**
         * The body's bytes, or null if it is longer than [max]. Reads at most
         * max + 1 bytes (after OkHttp's transparent gunzip), whatever the server
         * claims in Content-Length.
         */
        internal fun readCapped(body: ResponseBody, max: Long): ByteArray? {
            val source = body.source()
            if (source.request(max + 1)) return null
            return source.readByteArray()
        }

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
/**
 * Whether a signed map head is too old to trust, given the current time and a
 * maximum age (all in nanoseconds; the map head's time base, see R2 in docs/experiments.md). A map
 * head timestamped in the future (negative age, e.g. device-clock skew) is not
 * treated as stale.
 */
internal fun isMapHeadStale(mapHeadNanos: Long, nowNanos: Long, maxAgeNanos: Long): Boolean =
    nowNanos - mapHeadNanos > maxAgeNanos

internal fun XYBitString.toRawXYBitString(): RawXYBitString {
    val padded = bits.padEnd(64, '0')
    val value = if (padded.isEmpty()) 0uL else java.lang.Long.parseUnsignedLong(padded, 2).toULong()
    return RawXYBitString(xyBitString = value, xyBitStringLen = bits.length)
}
