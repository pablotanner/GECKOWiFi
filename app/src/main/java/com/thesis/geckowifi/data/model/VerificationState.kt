package com.thesis.geckowifi.data.model

enum class VerificationState {
    CHECKING,
    VERIFIED,
    /**
     * The network is registered here and nothing it showed contradicts the
     * registration, but its identity itself wasn't compared: a secured network
     * checked from its scan alone, or an eduroam visitor whose home server
     * can't be registered at this location. Never a warning, never a "verified".
     */
    REGISTERED,
    UNVERIFIED, UNRECOGNIZED, CONFLICT, UNREACHABLE
}
