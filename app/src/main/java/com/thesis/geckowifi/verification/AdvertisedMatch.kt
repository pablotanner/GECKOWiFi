package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.WiFiAuthMode

/**
 * Whether what a network advertises before anyone connects (its security
 * type, from the scan) fits what is registered for its SSID here. This is all
 * that can be judged without joining, so it never verifies a network: a match
 * only means nothing contradicts the registration.
 */
sealed interface AdvertisedMatch {
    /** The SSID's certificates at this location (valid, covering). */
    val certificates: List<GeoCertificate>

    /** The advertised security type is registered. */
    data class Matches(override val certificates: List<GeoCertificate>) : AdvertisedMatch

    /** The advertised security type is not registered: not the registered network (scenario A1). */
    data class Mismatch(
        override val certificates: List<GeoCertificate>,
        val registered: List<String>,
        val advertised: Set<WiFiAuthMode>
    ) : AdvertisedMatch

    /** Registered, but nothing can be said: see [reason]. No warning. */
    data class Undecided(override val certificates: List<GeoCertificate>, val reason: String) : AdvertisedMatch
}

/**
 * Whether one of these certificates permits every advertised mode. Null if
 * none registers auth modes (v2 certificates), so there is nothing to compare.
 * Shared by [matchAdvertised] and the engine's check, so both apply one rule.
 */
fun List<GeoCertificate>.permitAdvertised(advertised: Set<WiFiAuthMode>): Boolean? {
    val registering = filter { it.wifi.authModeNames.isNotEmpty() }
    if (registering.isEmpty()) return null
    return registering.any { it.wifi.permits(advertised) }
}

/**
 * Judges [advertised] for [ssid] against [registeredHere] (certificates
 * already filtered to valid ones covering this location). Null if the SSID
 * isn't registered here. Applies the engine's rules: certificates with an
 * unknown critical feature withhold a mismatch, and so does a shared SSID,
 * where another operator's network may legitimately differ.
 */
fun matchAdvertised(ssid: String, advertised: Set<WiFiAuthMode>, registeredHere: List<GeoCertificate>): AdvertisedMatch? {
    val certs = registeredHere.filter { it.wifi.ssid?.equals(ssid, ignoreCase = true) == true }
    if (certs.isEmpty()) return null
    val evaluable = certs.filter { it.isEvaluable() }
    return when {
        evaluable.isEmpty() -> AdvertisedMatch.Undecided(certs, "registered with features this app doesn't support")
        advertised.isEmpty() -> AdvertisedMatch.Undecided(certs, "its security type isn't recognised")
        else -> when (evaluable.permitAdvertised(advertised)) {
            null -> AdvertisedMatch.Undecided(certs, "the registration names no security type")
            true -> AdvertisedMatch.Matches(certs)
            false -> when {
                evaluable.size < certs.size -> AdvertisedMatch.Undecided(certs, "some registrations use features this app doesn't support")
                certs.any { it.wifi.ssidShared } -> AdvertisedMatch.Undecided(certs, "several operators share this name here")
                else -> AdvertisedMatch.Mismatch(certs, evaluable.flatMap { it.wifi.authModeNames }.distinct(), advertised)
            }
        }
    }
}
