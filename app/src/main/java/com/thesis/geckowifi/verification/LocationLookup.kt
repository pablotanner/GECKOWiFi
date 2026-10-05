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
data class LocationLookup(val status: LookupStatus, val certificates: List<GeoCertificate>)
