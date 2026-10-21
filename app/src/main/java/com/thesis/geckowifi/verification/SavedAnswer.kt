package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.local.SavedLookup
import com.thesis.geckowifi.data.remote.GeckoResponse
import com.thesis.geckowifi.eduroam.SAVED_REGISTRATION_MAX_AGE
import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The saved map answer as a stand-in for an unreachable server, for a check
 * at (lat, lng, radius) of [ssid] (docs/eduroam-decisions.md, D25), or null.
 *
 * It must be at most [maxAge] old. Its certificates passed the proof when they
 * arrived; what it can't show is a registration outside the area it was
 * fetched for. So it applies when that area contains the whole query circle
 * (then "nothing registered" is as true as it was then), or when it holds a
 * valid certificate for [ssid] reaching this position (then there is
 * something to judge against).
 */
fun savedAnswer(
    saved: SavedLookup?,
    lat: Double,
    lng: Double,
    radiusMeters: Int,
    ssid: String?,
    now: Instant,
    maxAge: Duration = SAVED_REGISTRATION_MAX_AGE
): GeckoResponse.Success? {
    if (saved == null) return null
    if (Duration.between(Instant.ofEpochMilli(saved.fetchedAt), now) > maxAge) return null
    val containsQuery = distanceMeters(saved.lat, saved.lng, lat, lng) + radiusMeters <= saved.radiusMeters
    val hasSsid = ssid != null && saved.certificates.any {
        it.wifi.ssid.equals(ssid, ignoreCase = true) && it.isValidAt(now) && it.coversLocation(lat, lng, radiusMeters)
    }
    if (!containsQuery && !hasSsid) return null
    return GeckoResponse.Success(saved.certificates, mapHeadMillis = saved.mapHeadMillis, savedAt = saved.fetchedAt)
}

/** Great-circle distance in metres. */
internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val h = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lng2 - lng1) / 2).pow(2)
    return 2 * 6_371_000.0 * asin(sqrt(h))
}
