package com.thesis.geckowifi.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One exchange with the map server, as the client saw it: the bytes sent and
 * received and what the client concluded. The outcome is [GeckoClient]'s own
 * verdict; the lab screen that shows a trace decodes the bytes for reading but
 * never verifies anything itself, so it can't disagree with the real result.
 */
class MapServerExchange(
    val startedMillis: Long,
    /** "lookup" (`/v1/query?c`) or "map head" (`/v1/get-smh`). */
    val kind: String,
    val url: String,
    val request: ByteArray?,
    /** The answer after gunzip; null if none arrived or it was too large to keep. */
    val response: ByteArray?,
    /** Size of the answer after gunzip, also when [response] isn't kept. */
    val responseSize: Int?,
    val durationMillis: Long,
    /** "OK", "Unreachable: …" or "Proof failure: …". */
    val outcome: String,
    /** Whether the preferred (bound) network served it; null if unknown. */
    val viaPreferredNetwork: Boolean? = null,
)

/**
 * The last [CAPACITY] map-server exchanges, in memory only (lost when the app
 * stops; nothing is written to disk). Shown in lab mode under Settings, Lab,
 * Map server traffic.
 */
object MapServerTrace {
    const val CAPACITY = 20

    /** Answers above this are summarized, not kept (a dense area's answer can be MBs). */
    const val MAX_KEPT_BYTES = 4_000_000

    private val _exchanges = MutableStateFlow<List<MapServerExchange>>(emptyList())

    /** Newest first. */
    val exchanges: StateFlow<List<MapServerExchange>> = _exchanges.asStateFlow()

    fun record(exchange: MapServerExchange) {
        _exchanges.update { (listOf(exchange) + it).take(CAPACITY) }
    }

    fun clear() {
        _exchanges.value = emptyList()
    }
}

/** Collects the bytes of one exchange while [GeckoClient] works on it. */
internal class ExchangeRecorder(private val kind: String, private val url: String) {
    private val started = System.currentTimeMillis()
    var request: ByteArray? = null
    var response: ByteArray? = null
    var viaPreferredNetwork: Boolean? = null

    fun finish(outcome: String) {
        val answer = response
        MapServerTrace.record(
            MapServerExchange(
                startedMillis = started,
                kind = kind,
                url = url,
                request = request,
                response = answer?.takeIf { it.size <= MapServerTrace.MAX_KEPT_BYTES },
                responseSize = answer?.size,
                durationMillis = System.currentTimeMillis() - started,
                outcome = outcome,
                viaPreferredNetwork = viaPreferredNetwork,
            )
        )
    }
}
