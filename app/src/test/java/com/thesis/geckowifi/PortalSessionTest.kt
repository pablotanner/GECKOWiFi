package com.thesis.geckowifi

import com.thesis.geckowifi.data.model.*
import com.thesis.geckowifi.verification.PortalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PortalSession against the schema-v2 certificate model: per-domain roles
 * (primary / delegate / api) and per-domain pins.
 */
class PortalSessionTest {

    private fun domain(name: String, role: PortalDomainRole, vararg pins: String) =
        PortalDomain(name = name, role = role, pinnedSPKIHashes = pins.toList())

    private fun cert(id: String = "cert-1", vararg domains: PortalDomain) = GeoCertificate(
        schemaVersion = 2,
        certificateId = id,
        wifi = WiFiIdentity(ssid = "TestNet", authMode = WiFiAuthMode.OPEN),
        portal = PortalTLSIdentity(domains = domains.toList()),
        areas = emptyList(),
        notValidAfter = "2099-01-01T00:00:00Z"
    )

    /** Portal with its own key on the primary and a third-party payment delegate with a different key. */
    private val portal = cert(
        "cert-1",
        domain("portal.example.com", PortalDomainRole.PRIMARY, "hashPortal"),
        domain("pay.processor.com", PortalDomainRole.DELEGATE, "hashPay")
    )

    @Test
    fun `primary domain with matching key sets anchor and verifies`() {
        val session = PortalSession("net-1")

        val result = session.observe("portal.example.com", listOf(portal), "hashPortal")

        assertEquals(VerificationState.VERIFIED, result.state)
        assertEquals("cert-1", session.anchorCertificateId)
    }

    @Test
    fun `delegate after its primary verifies with the delegate's own key`() {
        val session = PortalSession("net-1")
        session.observe("portal.example.com", listOf(portal), "hashPortal")

        val result = session.observe("pay.processor.com", listOf(portal), "hashPay")

        assertEquals(VerificationState.VERIFIED, result.state)
    }

    @Test
    fun `delegate presenting the primary's key is CONFLICT - pins are per domain`() {
        val session = PortalSession("net-1")
        session.observe("portal.example.com", listOf(portal), "hashPortal")

        val result = session.observe("pay.processor.com", listOf(portal), "hashPortal")

        assertEquals(VerificationState.CONFLICT, result.state)
        assertEquals("hashPay", result.registeredIdentifier)
    }

    @Test
    fun `primary presenting the delegate's key is CONFLICT and does not anchor`() {
        val session = PortalSession("net-1")

        val result = session.observe("portal.example.com", listOf(portal), "hashPay")

        assertEquals(VerificationState.CONFLICT, result.state)
        assertNull(session.anchorCertificateId)
    }

    @Test
    fun `delegate with genuine key before any primary is UNRECOGNIZED and does not anchor`() {
        val session = PortalSession("net-1")

        val result = session.observe("pay.processor.com", listOf(portal), "hashPay")

        assertEquals(VerificationState.UNRECOGNIZED, result.state)
        assertNull(session.anchorCertificateId)
    }

    @Test
    fun `delegate with wrong key before any primary is CONFLICT`() {
        val session = PortalSession("net-1")

        val result = session.observe("pay.processor.com", listOf(portal), "hashAttacker")

        assertEquals(VerificationState.CONFLICT, result.state)
    }

    @Test
    fun `domain without pins never verifies`() {
        val unpinned = cert("cert-2", domain("open.example.com", PortalDomainRole.PRIMARY))
        val session = PortalSession("net-1")

        val result = session.observe("open.example.com", listOf(unpinned), "anyHash")

        assertEquals(VerificationState.CONFLICT, result.state)
        assertNull(session.anchorCertificateId)
    }

    @Test
    fun `missing presented key on a registered domain is CONFLICT`() {
        val session = PortalSession("net-1")

        val result = session.observe("portal.example.com", listOf(portal), null)

        assertEquals(VerificationState.CONFLICT, result.state)
    }

    @Test
    fun `unlisted domain after anchor resolves to CONFLICT`() {
        val session = PortalSession("net-1")
        session.observe("portal.example.com", listOf(portal), "hashPortal")

        val result = session.observe("evil.attacker.com", emptyList(), "hashX")

        assertEquals(VerificationState.CONFLICT, result.state)
        assertEquals("portal.example.com", result.registeredIdentifier)
    }

    @Test
    fun `unknown domain with no anchor resolves to UNVERIFIED`() {
        val session = PortalSession("net-1")

        val result = session.observe("unregistered.com", emptyList(), "hashX")

        assertEquals(VerificationState.UNVERIFIED, result.state)
    }

    @Test
    fun `registered domain with wrong key resolves to CONFLICT`() {
        val session = PortalSession("net-1")

        val result = session.observe("portal.example.com", listOf(portal), "wrongHash")

        assertEquals(VerificationState.CONFLICT, result.state)
    }

    @Test
    fun `domain not named by any returned cert resolves to UNRECOGNIZED`() {
        val otherTenant = cert("cert-2", domain("cafe.example.com", PortalDomainRole.PRIMARY, "hashC"))
        val session = PortalSession("net-1")

        val result = session.observe("unrelated.com", listOf(otherTenant), "hashX")

        assertEquals(VerificationState.UNRECOGNIZED, result.state)
    }
}
