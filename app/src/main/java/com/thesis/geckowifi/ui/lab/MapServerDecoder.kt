package com.thesis.geckowifi.ui.lab

import com.thesis.geckowifi.data.remote.MapServerExchange
import com.thesis.geckowifi.data.remote.MapServerTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.ByteString
import java.security.MessageDigest
import java.time.Instant
import geopki.Node as ProtoNode
import geopki.Request as ProtoRequest
import geopki.Response as ProtoResponse
import geopki.SignedConsistencyHead as ProtoSignedConsistencyHead
import geopki.SignedMapHead as ProtoSignedMapHead
import geopki.trillian.Proof as ProtoTrillianProof

/**
 * Turns a recorded map-server exchange into a tree for reading: protobuf fields
 * by name, bit strings as bits, hashes in hex, times as dates, typed entries
 * split into type and hash, certificates as their JSON. Display only: whether
 * the answer was accepted is [MapServerExchange.outcome], the client's verdict.
 */
object MapServerDecoder {

    /** Nodes listed one by one; a dense area's answer has thousands. */
    private const val MAX_NODES_LISTED = 300

    fun decode(e: MapServerExchange): JsonObject = buildJsonObject {
        put("time", Instant.ofEpochMilli(e.startedMillis).toString())
        put("kind", e.kind)
        put("url", e.url)
        put("outcome", e.outcome)
        put("duration_ms", e.durationMillis)
        e.viaPreferredNetwork?.let { put("network", if (it) "preferred (bound) network" else "default route") }
        e.request?.let { put("request", decoded(it.size) { request(it) }) }
        when {
            e.response != null -> put("response", decoded(e.response.size) {
                if (e.kind == "map head") mapHead(ProtoSignedMapHead.ADAPTER.decode(e.response)) else response(e.response)
            })
            e.responseSize != null -> put("response", "${e.responseSize} bytes, too large to keep (limit ${MapServerTrace.MAX_KEPT_BYTES})")
            else -> put("response", "none")
        }
    }

    /** [block]'s tree with the message size added, or why it couldn't be decoded. */
    private fun decoded(size: Int, block: () -> JsonObject): JsonElement =
        try {
            JsonObject(mapOf("size_bytes" to JsonPrimitive(size)) + block())
        } catch (e: Exception) {
            JsonPrimitive("$size bytes, not decodable: ${e.message}")
        }

    private fun request(bytes: ByteArray): JsonObject {
        val r = ProtoRequest.ADAPTER.decode(bytes)
        return buildJsonObject {
            putJsonArray("cells") {
                r.XYBitStrings.forEach { add(bits(it.geopki_XYBitString, it.XYBitStringLen, 64)) }
            }
            put("min_altitude", r.MinAltitude)
            put("max_altitude", r.MaxAltitude)
            putJsonArray("types") { r.Types.forEach { add(it.ifEmpty { "(no type)" }) } }
        }
    }

    private fun response(bytes: ByteArray): JsonObject {
        val r = ProtoResponse.ADAPTER.decode(bytes)
        // type(s) each certificate is listed under in the tree, by hash
        val listed = HashMap<String, MutableList<String>>()
        r.Nodes.forEach { n -> n.Entries.forEach { entry(it)?.let { (type, hash) -> listed.getOrPut(hash) { mutableListOf() } += type } } }
        val withEntries = r.Nodes.filter { it.Entries.isNotEmpty() }

        return buildJsonObject {
            r.geopki_SignedMapHead?.let { put("signed_map_head", mapHead(it)) }
            r.geopki_SignedConsistencyHead?.let { put("signed_consistency_head", consistencyHead(it)) }
            r.InclusionProof?.let { put("inclusion_proof", inclusionProof(it)) }
            putJsonObject("tree") {
                put("nodes", r.Nodes.size)
                put("altitude_nodes", r.Nodes.count { it.ZBitStringLen > 0 })
                put("pruned_subtrees", r.Nodes.sumOf { n ->
                    listOf(n.XYLeftChildHash, n.XYRightChildHash, n.ZLeftChildHash, n.ZRightChildHash).count { it != null }
                })
                put("entries", r.Nodes.sumOf { it.Entries.size })
                put("certificates_listed", listed.size)
                putJsonArray("nodes_with_entries") { withEntries.forEach { add(node(it)) } }
                putJsonArray("all_nodes") {
                    r.Nodes.take(MAX_NODES_LISTED).forEach { add(node(it)) }
                    if (r.Nodes.size > MAX_NODES_LISTED) add("… ${r.Nodes.size - MAX_NODES_LISTED} more")
                }
            }
            putJsonArray("certificates") {
                r.Certificates.forEach { c ->
                    val body = c.toByteArray()
                    val hash = hex(MessageDigest.getInstance("SHA-256").digest(body))
                    addJsonObject {
                        put("sha256", hash)
                        putJsonArray("listed_as") { listed[hash].orEmpty().forEach { add(it.ifEmpty { "(no type)" }) } }
                        put("size_bytes", body.size)
                        put("certificate", runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)) }
                            .getOrElse { JsonPrimitive(String(body, Charsets.UTF_8)) })
                    }
                }
            }
        }
    }

    private fun mapHead(h: ProtoSignedMapHead) = buildJsonObject {
        put("version", h.Version)
        put("root_hash", hex(h.RootHash))
        put("timestamp", nanosToText(h.Timestamp))
        putJsonArray("covered_ct_logs") {
            h.CoveredCTLogServers.forEach { addJsonObject { put("log_id", hex(it.LogId)); put("signed_tree_head", hex(it.SignedTreeHead)) } }
        }
        put("signature", hex(h.Signature))
    }

    private fun consistencyHead(h: ProtoSignedConsistencyHead) = buildJsonObject {
        put("log_size", h.Size)
        put("root_hash", hex(h.RootHash))
        put("timestamp", nanosToText(h.Timestamp))
        put("signature", hex(h.Signature))
    }

    private fun inclusionProof(bytes: ByteString): JsonElement =
        runCatching {
            val p = ProtoTrillianProof.ADAPTER.decode(bytes)
            buildJsonObject {
                put("leaf_index", p.leaf_index)
                putJsonArray("hashes") { p.hashes.forEach { add(hex(it)) } }
            }
        }.getOrElse { JsonPrimitive("${bytes.size} bytes, not decodable") }

    private fun node(n: ProtoNode) = buildJsonObject {
        put("xy", bits(n.geopki_XYBitString, n.XYBitStringLen, 64))
        put("z", bits(n.ZBitString.toLong() shl 48, n.ZBitStringLen, 64))
        listOf("xy_left" to n.XYLeftChildHash, "xy_right" to n.XYRightChildHash,
            "z_left" to n.ZLeftChildHash, "z_right" to n.ZRightChildHash)
            .forEach { (name, h) -> if (h != null) put("pruned_$name", hex(h)) }
        if (n.Entries.isNotEmpty()) putJsonArray("entries") {
            n.Entries.forEach { e ->
                val parsed = entry(e)
                if (parsed == null) add("malformed: ${hex(e)}") else addJsonObject {
                    put("type", parsed.first.ifEmpty { "(no type)" })
                    put("certificate_sha256", parsed.second)
                }
            }
        }
    }

    /** A typed entry `len(type) | type | SHA-256` as (type, hash in hex), or null if malformed. */
    internal fun entry(e: ByteString): Pair<String, String>? {
        if (e.size == 0) return null
        val n = e[0].toInt() and 0xFF
        if (e.size != 1 + n + 32) return null
        return e.substring(1, 1 + n).utf8() to e.substring(1 + n).hex()
    }

    /** The top [len] bits of a top-aligned [width]-bit value, or "(root)". */
    internal fun bits(value: Long, len: Int, width: Int): String =
        if (len == 0) "(root)" else (0 until len).joinToString("") { if ((value ushr (width - 1 - it)) and 1L == 1L) "1" else "0" }

    internal fun nanosToText(nanos: Long): String =
        Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L)).toString()

    internal fun hex(b: ByteString) = b.hex()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
