package com.thesis.geckowifi.data.model

data class VerificationResult(
    val state: VerificationState,
    val host: String,
    val matchedIdentifier: String? = null,
    val matchedCertificateId: String? = null,
    val reason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)