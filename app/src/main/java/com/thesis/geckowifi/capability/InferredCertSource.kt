package com.thesis.geckowifi.capability

/** Without root, Android exposes nothing about the server's certificate. */
class InferredCertSource : CertificateSource {
    override suspend fun captureHandshake(timeoutSeconds: Int): PeerChain? = null
    override suspend fun accountRealm(): String? = null
}
