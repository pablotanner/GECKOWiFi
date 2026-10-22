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

/** Whether [this] saved answer is older than [maxAge] at [now]; an expired answer is deleted, not kept (D26). */
fun SavedLookup.isExpired(now: Instant, maxAge: Duration = SAVED_REGISTRATION_MAX_AGE): Boolean =
    Duration.between(Instant.ofEpochMilli(fetchedAt), now) > maxAge

/**
 * Whether [this] saved answer still speaks for the circle ([lat], [lng], [radiusMeters])
 * at [now] (docs/eduroam-decisions.md, D26): it is at most [maxAge] old, and the area
 * it was fetched for contains the whole circle. Its certificates passed the proof when
 * they arrived, but the map server only returned what overlaps that area. Inside it,
 * "nothing else is registered here" holds as it did then; outside it the answer may
 * lack registrations, also further ones of the same SSID, so it isn't used there at all.
 * The one rule behind the home list, enterprise sign-in (D21) and network checks (D25).
 */
fun SavedLookup.appliesAt(
    lat: Double,
    lng: Double,
    radiusMeters: Int,
    now: Instant,
    maxAge: Duration = SAVED_REGISTRATION_MAX_AGE
): Boolean = !isExpired(now, maxAge) && distanceMeters(this.lat, this.lng, lat, lng) + radiusMeters <= this.radiusMeters

/**
 * The saved map answer as a stand-in for an unreachable server, for a check
 * at (lat, lng, radius) (docs/eduroam-decisions.md, D25), or null when it
 * doesn't [apply][appliesAt] there.
 */
fun savedAnswer(
    saved: SavedLookup?,
    lat: Double,
    lng: Double,
    radiusMeters: Int,
    now: Instant,
    maxAge: Duration = SAVED_REGISTRATION_MAX_AGE
): GeckoResponse.Success? {
    if (saved == null || !saved.appliesAt(lat, lng, radiusMeters, now, maxAge)) return null
    return GeckoResponse.Success(saved.certificates, mapHeadMillis = saved.mapHeadMillis, savedAt = saved.fetchedAt)
}

/** Great-circle distance in metres. */
internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val h = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lng2 - lng1) / 2).pow(2)
    return 2 * 6_371_000.0 * asin(sqrt(h))
}
