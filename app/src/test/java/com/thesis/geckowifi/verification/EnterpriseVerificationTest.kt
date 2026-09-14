package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.EAPMethod
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.data.model.WiFiIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class EnterpriseVerificationTest {

    private fun enterpriseCert(
        authServerNames: List<String>,
        trustedCAFingerprints: List<String> = emptyList()
    ) = GeoCertificate(
        certificateId = "eduroam-cert-1",
        wifi = WiFiIdentity(
            authMode = WiFiAuthMode.WPA2_ENTERPRISE,
            eap = EAPMethod.EAP_TLS,
            authServerNames = authServerNames,
            trustedCAFingerprints = trustedCAFingerprints
        ),
        notValidAfter = "2099-01-01T00:00:00Z"
    )

    @Test
    fun verified_whenAuthServerNameAndFingerprintMatch() {
        val cert = enterpriseCert(listOf("radius.example.org"), listOf("abc123"))

        val result = evaluateEnterpriseNetwork("radius.example.org", "abc123", listOf(cert))

        assertEquals(VerificationState.VERIFIED, result.state)
        assertEquals(cert.certificateId, result.matchedCertificateId)
    }

    @Test
    fun verified_whenNoFingerprintsPinned() {
        // No pinned fingerprints -> name match alone is sufficient, mirrors
        // PortalSession's "empty pin list means unpinned" behavior.
        val cert = enterpriseCert(listOf("radius.example.org"), emptyList())

        val result = evaluateEnterpriseNetwork("radius.example.org", "anything", listOf(cert))

        assertEquals(VerificationState.VERIFIED, result.state)
    }

    @Test
    fun conflict_whenAuthServerNameMatchesButFingerprintDoesNot() {
        val cert = enterpriseCert(listOf("radius.example.org"), listOf("abc123"))

        val result = evaluateEnterpriseNetwork("radius.example.org", "wrong-fingerprint", listOf(cert))

        assertEquals(VerificationState.CONFLICT, result.state)
        assertEquals(cert.certificateId, result.matchedCertificateId)
    }

    @Test
    fun unrecognized_whenCertificatesExistButNoneNamesThisAuthServer() {
        val cert = enterpriseCert(listOf("radius.other.org"), listOf("abc123"))

        val result = evaluateEnterpriseNetwork("radius.example.org", "abc123", listOf(cert))

        assertEquals(VerificationState.UNRECOGNIZED, result.state)
    }

    @Test
    fun unverified_whenNoCertificatesAtAll() {
        val result = evaluateEnterpriseNetwork("radius.example.org", "abc123", emptyList())

        assertEquals(VerificationState.UNVERIFIED, result.state)
    }

    @Test
    fun authServerNameMatch_isCaseInsensitive() {
        val cert = enterpriseCert(listOf("RADIUS.EXAMPLE.ORG"), listOf("abc123"))

        val result = evaluateEnterpriseNetwork("radius.example.org", "abc123", listOf(cert))

        assertEquals(VerificationState.VERIFIED, result.state)
    }
}
