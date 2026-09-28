package com.thesis.geckowifi.verification.golden

import com.thesis.geckowifi.data.remote.toRawXYBitString
import com.thesis.geckowifi.geopki.bitstring.RawXYBitString
import com.thesis.geckowifi.geopki.crypto.GeoPkiQuery
import com.thesis.geckowifi.geopki.crypto.ensureConsistency
import com.thesis.geckowifi.geopki.crypto.verifyResponse
import com.thesis.geckowifi.verification.GeoQueryEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import geopki.Request as ProtoRequest
import geopki.Response as ProtoResponse
import geopki.XYBitString as ProtoXYBitString

// Mirrors golden.json written by cmd/golden-dump (geopki repo).

@Serializable
data class XyBits(val bits: String, val len: Int) {
    /** uint64 in Go; may have bit 63 set, so ULong, never Long.parse */
    val value: ULong get() = bits.toULong(16)
}

@Serializable
data class QueryVector(
    val name: String,
    val lon: Double,
    val lat: Double,
    val alt: Double,
    val radius: Long,
    val xy: List<XyBits> = emptyList(), // sorted by (value, len)
    val minAlt: Int = 0,
    val maxAlt: Int = 0,
    val requestB64: String? = null,
    val stable: Boolean = false,
    val err: String? = null,
)

@Serializable
data class ProofVector(
    val name: String,
    val query: String, // name of the QueryVector it was verified against
    val responseB64: String,
    val accept: Boolean,
    val certHashes: List<String> = emptyList(), // base64url, no padding
    val err: String? = null,
)

@Serializable
data class Golden(
    val publicKeyB64: String,
    val queries: List<QueryVector>,
    val proofs: List<ProofVector>,
)

object GoldenVectors {
    private val json = Json { ignoreUnknownKeys = true }

    val golden: Golden by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("golden/golden.json")) {
            "golden/golden.json missing from app/src/test/resources"
        }
        json.decodeFromString(Golden.serializer(), stream.bufferedReader().use { it.readText() })
    }

    val publicKeyDer: ByteArray by lazy { b64(golden.publicKeyB64) }

    fun query(name: String): QueryVector = golden.queries.first { it.name == name }

    fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)
}

// ---------------------------------------------------------------------------
// Adapter: the ONLY place that knows your production API.
//
// Deliberately calls the real production entry points directly rather than
// re-implementing any of their logic here - the whole point of a golden-
// vector test is to catch a bug in that real code, which a parallel
// reimplementation in the adapter itself could never do.
// ---------------------------------------------------------------------------

/** Neutral encoder output. xy sorted by (value, len), same as the golden file. */
data class EncodedQuery(val xy: List<Pair<ULong, Int>>, val minAlt: Int, val maxAlt: Int)

object UnderTest {
    /**
     * Must throw on invalid input (the err-* vectors). Calls the real
     * [GeoQueryEncoder] - both `encodeQuery()` (lat/lng/radius -> xy cells)
     * and `altitudeBounds()` (altitude -> minAlt/maxAlt) validate their own
     * inputs and throw `IllegalArgumentException` on rejection, so this
     * doesn't need its own validation layer.
     */
    fun encode(lon: Double, lat: Double, alt: Double, radius: Long): EncodedQuery {
        val encoder = GeoQueryEncoder()
        val radiusMeters = radius.toInt()
        val xy = encoder.encodeQuery(lat = lat, lng = lon, radiusMeters = radiusMeters)
            .map { it.toRawXYBitString() }
            .map { it.xyBitString to it.xyBitStringLen }
            .sortedWith(compareBy({ it.first }, { it.second }))
        val (minAlt, maxAlt) = encoder.altitudeBounds(altitude = alt, uncertaintyMeters = radiusMeters)
        return EncodedQuery(xy, minAlt, maxAlt)
    }

    /**
     * Serialized protobuf Request exactly as [com.thesis.geckowifi.data.remote.GeckoClient]
     * builds it (same field mapping, same `ULong.toLong()` bit-pattern
     * reinterpretation) - only meaningful for `stable` query vectors, where
     * the golden file's own xy cell selection is deterministic; the test
     * harness skips this check for unstable ones before ever calling it.
     */
    fun requestBytes(q: EncodedQuery): ByteArray? {
        val protoRequest = ProtoRequest(
            XYBitStrings = q.xy.map { (value, len) ->
                ProtoXYBitString(geopki_XYBitString = value.toLong(), XYBitStringLen = len)
            },
            MinAltitude = q.minAlt,
            MaxAltitude = q.maxAlt
        )
        return ProtoRequest.ADAPTER.encode(protoRequest)
    }

    /**
     * Full client-side check (map-head signature, completeness, consistency
     * proof) - the same two calls [com.thesis.geckowifi.data.remote.GeckoClient.queryLocation]
     * makes, in the same order, so a `Success` here means exactly what a
     * `Success` there means. Returns the verified cert hashes as base64url
     * without padding, throws (`GeoPkiVerificationException`) on rejection.
     * Must NOT filter by geometry: that is a separate step after
     * verification, not part of what these vectors are checking.
     */
    fun verify(
        response: ByteArray,
        xy: List<Pair<ULong, Int>>,
        minAlt: Int,
        maxAlt: Int,
        publicKeyDer: ByteArray,
    ): Set<String> {
        val protoResponse = ProtoResponse.ADAPTER.decode(response)
        val query = GeoPkiQuery(
            xyBitStrings = xy.map { (value, len) -> RawXYBitString(xyBitString = value, xyBitStringLen = len) },
            minAltitude = minAlt,
            maxAltitude = maxAlt
        )
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))

        val hashes = verifyResponse(protoResponse, query, publicKey)
        ensureConsistency(protoResponse, publicKey)
        return hashes
    }
}