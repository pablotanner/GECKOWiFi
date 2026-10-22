package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.GeoCertificate

/** Why a "what's registered here" query returned what it did. */
enum class LookupStatus {
    /** The map server answered and the response verified. [LocationLookup.certificates] is complete. */
    OK,
    /** The map server couldn't be reached - registration here is unknown, not empty. */
    UNREACHABLE,
    /** The server answered but its proof didn't verify - the answer can't be trusted. */
    UNTRUSTED
}

/** Result of [VerificationEngine.registeredHere]: the status plus any certificates found. */
data class LocationLookup(
    val status: LookupStatus,
    val certificates: List<GeoCertificate>,
    /** When the map head of the answer was signed (epoch millis); null without a verified answer. */
    val mapHeadMillis: Long? = null,
    /** Whether the query went over the network the client was bound to. */
    val usedPreferredNetwork: Boolean? = null,
    /** Why the server couldn't be reached, for the log; null otherwise. */
    val unreachableCause: String? = null
)

/**
 * Result of [VerificationEngine.checkAdvertisedSite]: the site verdict plus the
 * certificates it was judged on (empty when nothing was judged).
 */
data class SiteCheck(val result: com.thesis.geckowifi.data.model.VerificationResult, val certificates: List<GeoCertificate> = emptyList())
