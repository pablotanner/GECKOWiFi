package com.thesis.geckowifi.capability

/** Where the RADIUS server's certificate chain of an 802.1X handshake comes from. */
interface CertificateSource {
    /**
     * Listens for up to [timeoutSeconds] for the next EAP handshake on this
     * device and returns the server's chain, or null if none was seen. The
     * user reconnects to the network meanwhile; the handshake happens either way.
     */
    suspend fun captureHandshake(timeoutSeconds: Int): PeerChain?

    /**
     * Realm of the account the device's current enterprise connection uses
     * (`ethz.ch` from `anonymous@ethz.ch`), which tells a local user from a
     * visitor. The OS knows it from the saved network; null if unknown.
     */
    suspend fun accountRealm(): String?
}
