package com.thesis.geckowifi.data.local

import android.util.Log
import com.thesis.geckowifi.verification.HopVerdict
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Client-side record of every portal check, one JSON object per line in
 * `<dir>/checks-<date>.jsonl` - the counterpart to the portal server's
 * `portal/logs/<role>-<date>.jsonl`, joined on time / host / client for
 * experiments. On the tablet `<dir>` is the app's external files dir:
 *
 *     adb pull /sdcard/Android/data/com.thesis.geckowifi/files/portal-checks/
 *
 * Each line is also logged under the logcat tag `PortalCheckLog`.
 */
class PortalCheckLog(private val dir: File) {

    @Serializable
    data class HopEntry(
        val index: Int,
        val url: String,
        val scheme: String,
        val host: String,
        val port: Int,
        val status: Int? = null,
        val next: String? = null,
        @SerialName("spki_sha256") val spkiSha256: String? = null,
        @SerialName("cert_subject") val certSubject: String? = null,
        val source: String,
        val time: String,
        val error: String? = null,
        /** VerificationState name, or null for an unjudged transit hop. */
        val verdict: String? = null,
        val reason: String? = null
    )

    @Serializable
    data class CheckEntry(
        @SerialName("run_id") val runId: String,
        val time: String,
        val ssid: String?,
        val bssid: String?,
        @SerialName("network_key") val networkKey: String,
        /** "discovery" (portal found by the app) or "domain" (typed / registered domain). */
        val mode: String,
        /** Discovery outcome (PortalReached / NoPortal / Failed: …) or null in domain mode. */
        val outcome: String? = null,
        val state: String,
        val reason: String? = null,
        val lat: Double,
        val lng: Double,
        @SerialName("accuracy_m") val accuracyMeters: Float? = null,
        val hops: List<HopEntry> = emptyList()
    )

    private val json = Json { encodeDefaults = false }

    /** Appends [entry]; returns the file written, or null if the directory isn't writable. */
    fun append(entry: CheckEntry): File? {
        val line = json.encodeToString(CheckEntry.serializer(), entry)
        Log.i(TAG, line)
        return runCatching {
            dir.mkdirs()
            val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Instant.parse(entry.time))
            File(dir, "checks-$date.jsonl").apply { appendText(line + "\n") }
        }.onFailure { Log.w(TAG, "could not write check log", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "PortalCheckLog"

        fun isoTime(millis: Long): String = Instant.ofEpochMilli(millis).toString()

        fun hopEntries(verdicts: List<HopVerdict>): List<HopEntry> = verdicts.map { (hop, result) ->
            HopEntry(
                index = hop.index, url = hop.url, scheme = hop.scheme, host = hop.host, port = hop.port,
                status = hop.statusCode, next = hop.next, spkiSha256 = hop.presentedSpkiHash,
                certSubject = hop.certificateSubject, source = hop.source.name,
                time = isoTime(hop.timestampMillis), error = hop.error,
                verdict = result?.state?.name, reason = result?.reason
            )
        }
    }
}
