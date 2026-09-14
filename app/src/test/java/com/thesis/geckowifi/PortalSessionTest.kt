package com.thesis.geckowifi

import com.thesis.geckowifi.data.model.*
import com.thesis.geckowifi.verification.PortalSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rewritten (2026-09) to match the current data model - the original version
 * referenced `PortalDomain`/`PortalRole` types that no longer exist (the
 * model was simplified since to a flat `PortalTLSIdentity.domains: List<String>`
 * with a single shared `pinnedSPKIHashes` list, not per-domain roles/hashes).
 * PortalSession's own comparison/state logic (in verification/PortalSession.kt)
 * is untouched - only this test's fixtures were updated to compile against
 * the current model.
 */
class PortalSessionTest {

    private fun cert(
        id: String = "cert-1",
        domains: List<String>,
        pinnedSpkiHashes: List<String> = emptyList()
    ) = GeoCertificate(
        certificateId = id,
        wifi = WiFiIdentity(ssid = "TestNet", authMode = WiFiAuthMode.OPEN),
        portal = PortalTLSIdentity(domains = domains, pinnedSPKIHashes = pinnedSpkiHashes),
        areas = emptyList(),
        notValidAfter = "2027-01-01T00:00:00Z"
    )

    @Test
    fun `primary domain with matching key sets anchor and verifies`() {
        val certificate = cert(
            domains = listOf("portal.example.com"),
            pinnedSpkiHashes = listOf("hashA")
        )
        val session = PortalSession("net-1")

        val result = session.observe("portal.example.com", listOf(certificate), "hashA")

        assertEquals(VerificationState.VERIFIED, result.state)
        assertEquals("cert-1", session.anchorCertificateId)
    }

    @Test
    fun `second domain on the same anchored certificate verifies as a delegate`() {
        // The current model shares one pinned-hash list across all domains
        // on a certificate (no per-domain PortalRole anymore) - a second
        // domain on the *same already-anchored* certificate still verifies.
        val certificate = cert(
            domains = listOf("portal.example.com", "pay.processor.com"),
            pinnedSpkiHashes = listOf("hashA")
        )
        val session = PortalSession("net-1")
        session.observe("portal.example.com", listOf(certificate), "hashA")

        val result = session.observe("pay.processor.com", listOf(certificate), "hashA")

        assertEquals(VerificationState.VERIFIED, result.state)
    }

    @Test
    fun `unlisted domain after anchor resolves to CONFLICT`() {
        val certificate = cert(domains = listOf("portal.example.com"), pinnedSpkiHashes = listOf("hashA"))
        val session = PortalSession("net-1")
        session.observe("portal.example.com", listOf(certificate), "hashA")

        val result = session.observe("evil.attacker.com", emptyList(), "hashX")

        assertEquals(VerificationState.CONFLICT, result.state)
    }

    @Test
    fun `unknown domain with no anchor resolves to UNVERIFIED`() {
        val session = PortalSession("net-1")

        val result = session.observe("unregistered.com", emptyList(), "hashX")

        assertEquals(VerificationState.UNVERIFIED, result.state)
    }

    @Test
    fun `registered domain with wrong key resolves to CONFLICT`() {
        val certificate = cert(domains = listOf("portal.example.com"), pinnedSpkiHashes = listOf("hashA"))
        val session = PortalSession("net-1")

        val result = session.observe("portal.example.com", listOf(certificate), "wrongHash")

        assertEquals(VerificationState.CONFLICT, result.state)
    }

    @Test
    fun `domain not named by any returned cert resolves to UNRECOGNIZED`() {
        val otherTenant = cert(id = "cert-2", domains = listOf("cafe.example.com"), pinnedSpkiHashes = listOf("hashC"))
        val session = PortalSession("net-1")

        val result = session.observe("unrelated.com", listOf(otherTenant), "hashX")

        assertEquals(VerificationState.UNRECOGNIZED, result.state)
    }
}
