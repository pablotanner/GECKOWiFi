package com.thesis.geckowifi.capability

import java.security.cert.X509Certificate

class InferredCertSource : CertificateSource {
    override val isGroundTruth = false
    override suspend fun presentedCertificate(ssid: String): X509Certificate? = null
}