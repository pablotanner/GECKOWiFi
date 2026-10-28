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

/** When [this] saved answer was last known to be current: fetched, or confirmed unchanged since (D26). */
val SavedLookup.currentAt: Long get() = maxOf(fetchedAt, confirmedAt ?: 0L)

/**
 * Whether [this] saved answer was last known current more than [maxAge] before
 * [now]; an expired answer is deleted, not kept (D26).
 */
fun SavedLookup.isExpired(now: Instant, maxAge: Duration = SAVED_REGISTRATION_MAX_AGE): Boolean =
    Duration.between(Instant.ofEpochMilli(currentAt), now) > maxAge

/**
 * Whether a verdict from [verdictMillis] still shows as a network's state in the
 * network list: for as long as a saved answer would be used, [maxAge]. An older
 * one is history (the History tab keeps it); in the list, without a date, it
 * would read as a statement about now.
 */
fun isRecentVerdict(verdictMillis: Long, now: Instant, maxAge: Duration = SAVED_REGISTRATION_MAX_AGE): Boolean =
    Duration.between(Instant.ofEpochMilli(verdictMillis), now) <= maxAge

/**
 * After this, a saved answer is checked against the server's map head while it
 * is still usable (D26): half of [SAVED_REGISTRATION_MAX_AGE], so it is
 * confirmed (or replaced) well before it expires.
 */
val SAVED_REGISTRATION_CONFIRM_AFTER: Duration = SAVED_REGISTRATION_MAX_AGE.dividedBy(2)

/** What keeping the saved answer useful takes right now (D26). */
enum class SavedAnswerUpkeep {
    /** It covers here and is recent: nothing to do. */
    NONE,

    /** It covers here but is getting old: ask whether the map changed (a signed map head, a few hundred bytes). */
    CONFIRM,

    /** There is none, it expired, or it doesn't cover a check here: look the place up again. */
    FETCH
}

/**
 * Decides when GECKO looks the place up again (D26), instead of on a timer:
 * when there's no usable saved answer, when a check of up to [checkRadiusMeters]
 * at ([lat], [lng]) wouldn't fit inside it anymore (so it is replaced while the
 * device is still online, before a captive portal cuts it off), or, once it is
 * [confirmAfter] old, only if the map changed meanwhile.
 */
fun savedAnswerUpkeep(
    saved: SavedLookup?,
    lat: Double,
    lng: Double,
    checkRadiusMeters: Int,
    now: Instant,
    confirmAfter: Duration = SAVED_REGISTRATION_CONFIRM_AFTER
): SavedAnswerUpkeep = when {
    saved == null || !saved.appliesAt(lat, lng, checkRadiusMeters, now) -> SavedAnswerUpkeep.FETCH
    Duration.between(Instant.ofEpochMilli(saved.currentAt), now) >= confirmAfter -> SavedAnswerUpkeep.CONFIRM
    else -> SavedAnswerUpkeep.NONE
}

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
