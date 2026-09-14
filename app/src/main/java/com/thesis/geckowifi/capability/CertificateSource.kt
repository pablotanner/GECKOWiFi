package com.thesis.geckowifi.capability

import java.security.cert.X509Certificate

interface CertificateSource {
    /** Certificate the RADIUS/AP actually presented, or null if unobservable. */
    suspend fun presentedCertificate(ssid: String): X509Certificate?
    val isGroundTruth: Boolean
}