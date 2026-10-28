package com.thesis.geckowifi.ui.lab

import com.thesis.geckowifi.data.remote.MapServerExchange
import com.thesis.geckowifi.geopki.bitstring.D
import com.thesis.geckowifi.geopki.bitstring.bitStringPairFromStringPair
import com.thesis.geckowifi.geopki.bitstring.undiscretizeX
import com.thesis.geckowifi.geopki.bitstring.undiscretizeY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.security.MessageDigest
import kotlin.math.cos
import geopki.Node as ProtoNode
import geopki.Request as ProtoRequest
import geopki.Response as ProtoResponse

/** A cell of the map in degrees: [lngMin, lngMax) x [latMin, latMax). */
data class CellBounds(val lngMin: Double, val latMin: Double, val lngMax: Double, val latMax: Double) {
    fun contains(lng: Double, lat: Double) = lng >= lngMin && lng < lngMax && lat >= latMin && lat < latMax

    /** Width and height in metres, around its own latitude. */
    fun sizeMeters(): Pair<Double, Double> {
        val midLat = Math.toRadians((latMin + latMax) / 2)
        return (lngMax - lngMin) * METERS_PER_DEGREE * cos(midLat) to (latMax - latMin) * METERS_PER_DEGREE
    }

    companion object {
        const val METERS_PER_DEGREE = 111_320.0

        /** The area of an xy bit string ("" = the whole world). */
        fun of(xy: String): CellBounds {
            val cell = bitStringPairFromStringPair(xy, "").xy
            return CellBounds(undiscretizeX(cell.xMin), undiscretizeY(cell.yMin), undiscretizeX(cell.xMax()), undiscretizeY(cell.yMax()))
        }

        /** The altitude range of a z bit string, in metres: [min, max). */
        fun altitudeOf(z: String): Pair<Int, Int> {
            val cell = bitStringPairFromStringPair("", z).z
            return D + cell.zMin.toInt() to D + cell.zMax().toInt()
        }
    }
}

/** One certificate listed in a node: its type and certificate hash (hex). */
data class AnswerEntry(val type: String, val hash: String)

/**
 * A node of the answer, by position: [xy] and [z] bit strings ("" at the top of
 * each). The `pruned` hashes are children sent only as a hash.
 */
class AnswerNode(
    val xy: String,
    val z: String,
    val prunedXyLeft: String?,
    val prunedXyRight: String?,
    val prunedZLeft: String?,
    val prunedZRight: String?,
    val entries: List<AnswerEntry>,
)

/** A delivered certificate: its hash, id and registered areas (outer rings, [lng, lat] points). */
class AnswerCertificate(
    val hash: String,
    val id: String?,
    val rings: List<List<Pair<Double, Double>>>,
    val altitude: Pair<Double, Double>?,
)

/**
 * A lookup answer, parsed for the lab visualizations (map, proof path). Like
 * [MapServerDecoder], it only reads; the verdict is the client's.
 */
class AnswerModel(
    val rootHash: String,
    val version: Int,
    val mapTimestamp: String,
    val cells: List<String>,
    val types: List<String>,
    val nodes: Map<Pair<String, String>, AnswerNode>,
    val certificates: List<AnswerCertificate>,
) {
    /** Every listed certificate, by hash, with the nodes and types it is listed under. */
    val listings: Map<String, List<Pair<AnswerNode, String>>> by lazy {
        nodes.values.flatMap { n -> n.entries.map { it.hash to (n to it.type) } }.groupBy({ it.first }, { it.second })
    }

    fun certificate(hash: String) = certificates.firstOrNull { it.hash == hash }

    companion object {
        /** The model of a lookup answer, or null for another kind of exchange or one without a kept answer. */
        fun of(e: MapServerExchange): AnswerModel? {
            if (e.kind != "lookup") return null
            val bytes = e.response ?: return null
            val r = runCatching { ProtoResponse.ADAPTER.decode(bytes) }.getOrNull() ?: return null
            val request = e.request?.let { runCatching { ProtoRequest.ADAPTER.decode(it) }.getOrNull() }
            val smh = r.geopki_SignedMapHead
            return AnswerModel(
                rootHash = smh?.RootHash?.hex().orEmpty(),
                version = smh?.Version ?: 0,
                mapTimestamp = smh?.Timestamp?.let(MapServerDecoder::nanosToText).orEmpty(),
                cells = request?.XYBitStrings?.map { xyBits(it.geopki_XYBitString, it.XYBitStringLen) }.orEmpty(),
                types = request?.Types.orEmpty(),
                nodes = r.Nodes.map(::node).associateBy { it.xy to it.z },
                certificates = r.Certificates.map { certificate(it.toByteArray()) },
            )
        }

        private fun xyBits(value: Long, len: Int) = if (len == 0) "" else MapServerDecoder.bits(value, len, 64)

        private fun node(n: ProtoNode) = AnswerNode(
            xy = xyBits(n.geopki_XYBitString, n.XYBitStringLen),
            z = if (n.ZBitStringLen == 0) "" else MapServerDecoder.bits(n.ZBitString.toLong() shl 48, n.ZBitStringLen, 64),
            prunedXyLeft = n.XYLeftChildHash?.hex(),
            prunedXyRight = n.XYRightChildHash?.hex(),
            prunedZLeft = n.ZLeftChildHash?.hex(),
            prunedZRight = n.ZRightChildHash?.hex(),
            entries = n.Entries.mapNotNull { MapServerDecoder.entry(it)?.let { (type, hash) -> AnswerEntry(type, hash) } },
        )

        private fun certificate(body: ByteArray): AnswerCertificate {
            val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            val json = runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject }.getOrNull()
            // geopki areas: [{"type": "MultiPolygon", "coordinates": [[outer rings], [inner rings]]}], rings of [lng, lat]
            val rings = (json?.get("areas") as? JsonArray).orEmpty().flatMap { area ->
                val outer = ((area as? JsonObject)?.get("coordinates") as? JsonArray)?.getOrNull(0) as? JsonArray
                outer.orEmpty().mapNotNull { ring ->
                    (ring as? JsonArray)?.mapNotNull { p ->
                        val point = p as? JsonArray ?: return@mapNotNull null
                        val lng = (point.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
                        val lat = (point.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                        if (lng != null && lat != null) lng to lat else null
                    }
                }
            }
            val altitude = ((json?.get("areas_altitude") as? JsonArray)?.getOrNull(0) as? JsonArray)?.let {
                val low = (it.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
                val high = (it.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                if (low != null && high != null) low to high else null
            }
            return AnswerCertificate(hash, (json?.get("certificate_id") as? JsonPrimitive)?.content, rings, altitude)
        }
    }
}
