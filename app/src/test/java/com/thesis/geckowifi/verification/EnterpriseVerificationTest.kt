package com.thesis.geckowifi.verification

import com.thesis.geckowifi.data.model.AAADomain
import com.thesis.geckowifi.data.model.AuthServer
import com.thesis.geckowifi.data.model.EnterpriseIdentity
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.Subject
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * [evaluateEnterpriseNetwork], [AuthServer.accepts] and
 * [ObservedAuthServer.fromChain]. The chains in `eap-chains/` are real
 * certificates (P-256) from a genuine and a rogue lab CA, both naming
 * `radius.gecko-a.lab`; their keys are not kept.
 */
class EnterpriseVerificationTest {

    private val genuineCaKey = "YGlZCP1V/UTDiV15rRudgxw3LlX1aRRvgHsB/aY4uSE="
    private val genuineLeafKey = "re/AeKsaLANFxoe+1Qqo7NKNLndR9aT/pDo0DCB041Y="

    private fun pem(name: String): X509Certificate =
        javaClass.getResourceAsStream("/eap-chains/$name.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private val genuine = ObservedAuthServer("leafG", listOf("radius.gecko-a.lab"), listOf("caG"))
    private val rogue = ObservedAuthServer("leafR", listOf("radius.gecko-a.lab"), listOf("caR"))
    private val visitorServer = ObservedAuthServer("leafV", listOf("radius.uzh.ch"), listOf("caV"))

    private fun cert(
        federation: String? = "eduroam",
        realms: List<String> = listOf("gecko-a.lab"),
        server: AuthServer = AuthServer(names = listOf("radius.gecko-a.lab"), caSPKIHashes = listOf("caG"))
    ) = GeoCertificate(
        schemaVersion = 3,
        certificateId = "lab-eduroam",
        subject = Subject("gecko-a.lab", "GECKO Lab"),
        wifi = WiFiIdentity(
            ssid = "eduroam",
            authModeNames = listOf("wpa2-enterprise"),
            enterprise = EnterpriseIdentity(federation, listOf(AAADomain(realms = realms, servers = listOf(server))))
        ),
        notValidAfter = "2099-01-01T00:00:00Z"
    )

    // --- local users ---

    @Test
    fun localUser_genuineServerViaCaPin_isVerified() {
        val result = evaluateEnterpriseNetwork(genuine, "gecko-a.lab", listOf(cert()))
        assertEquals(VerificationState.VERIFIED, result.state)
        assertEquals("lab-eduroam", result.matchedCertificateId)
    }

    @Test
    fun localUser_rogueCaSameName_isConflict() {
        val result = evaluateEnterpriseNetwork(rogue, "gecko-a.lab", listOf(cert()))
        assertEquals(VerificationState.CONFLICT, result.state)
        assertEquals("leafR", result.presentedIdentifier)
    }

    @Test
    fun localUser_genuineCaButOtherName_isConflict() {
        // The CA pin alone would accept every server that CA signs.
        val otherName = ObservedAuthServer("leafX", listOf("mail.gecko-a.lab"), listOf("caG"))
        assertEquals(VerificationState.CONFLICT, evaluateEnterpriseNetwork(otherName, "gecko-a.lab", listOf(cert())).state)
    }

    @Test
    fun subrealmWildcard_coversSubrealmsOnly() {
        val c = cert(realms = listOf("*.gecko-a.lab"))
        assertEquals(VerificationState.VERIFIED, evaluateEnterpriseNetwork(genuine, "staff.gecko-a.lab", listOf(c)).state)
        // The bare realm isn't covered by "*.": that user counts as a visitor.
        assertEquals(VerificationState.REGISTERED, evaluateEnterpriseNetwork(genuine, "gecko-a.lab", listOf(c)).state)
    }

    // --- visitors ---

    @Test
    fun visitorInFederation_isRegisteredNotConflict() {
        val result = evaluateEnterpriseNetwork(visitorServer, "uzh.ch", listOf(cert()))
        assertEquals(VerificationState.REGISTERED, result.state)
        assertTrue(result.reason!!.contains("visitor"))
        assertTrue(result.reason!!.contains("GECKO Lab"))
    }

    @Test
    fun unknownRealmInFederation_rogueServerIsNotAConflict() {
        // Without the realm the server could be a visitor's home server.
        assertEquals(VerificationState.REGISTERED, evaluateEnterpriseNetwork(rogue, null, listOf(cert())).state)
    }

    @Test
    fun unknownRealmInFederation_genuineServerStillVerifies() {
        assertEquals(VerificationState.VERIFIED, evaluateEnterpriseNetwork(genuine, null, listOf(cert())).state)
    }

    @Test
    fun federationSiteWithoutLocalUsers_everyoneIsAVisitor() {
        val c = cert().let { it.copy(wifi = it.wifi.copy(enterprise = EnterpriseIdentity("eduroam", emptyList()))) }
        assertEquals(VerificationState.REGISTERED, evaluateEnterpriseNetwork(genuine, "gecko-a.lab", listOf(c)).state)
    }

    // --- closed networks ---

    @Test
    fun closedNetwork_realmIsIrrelevant() {
        val c = cert(federation = null)
        assertEquals(VerificationState.VERIFIED, evaluateEnterpriseNetwork(genuine, "elsewhere.org", listOf(c)).state)
        assertEquals(VerificationState.CONFLICT, evaluateEnterpriseNetwork(rogue, "elsewhere.org", listOf(c)).state)
    }

    // --- fail closed ---

    @Test
    fun serverWithoutPins_neverVerifies() {
        val c = cert(federation = null, server = AuthServer(names = listOf("radius.gecko-a.lab")))
        assertEquals(VerificationState.CONFLICT, evaluateEnterpriseNetwork(genuine, null, listOf(c)).state)
    }

    @Test
    fun caPinWithoutNames_neverVerifies() {
        val c = cert(federation = null, server = AuthServer(caSPKIHashes = listOf("caG")))
        assertEquals(VerificationState.CONFLICT, evaluateEnterpriseNetwork(genuine, null, listOf(c)).state)
    }

    @Test
    fun leafPin_verifiesWithoutNameOrCa() {
        val c = cert(federation = null, server = AuthServer(pinnedSPKIHashes = listOf("leafG")))
        val renamed = ObservedAuthServer("leafG", listOf("anything"), emptyList())
        assertEquals(VerificationState.VERIFIED, evaluateEnterpriseNetwork(renamed, null, listOf(c)).state)
    }

    @Test
    fun ssidRegisteredWithoutEnterpriseSection_isUnrecognized() {
        // The engine's SSID exclusivity escalates this to CONFLICT.
        val open = cert().let { it.copy(wifi = it.wifi.copy(enterprise = null, authModeNames = listOf("open"))) }
        assertEquals(VerificationState.UNRECOGNIZED, evaluateEnterpriseNetwork(genuine, "gecko-a.lab", listOf(open)).state)
    }

    @Test
    fun noCandidates_isUnverified() {
        assertEquals(VerificationState.UNVERIFIED, evaluateEnterpriseNetwork(genuine, "gecko-a.lab", emptyList()).state)
    }

    // --- chains: signatures, not just hashes ---

    @Test
    fun fromChain_genuineChain_verifiesIssuerAndReadsSan() {
        val observed = ObservedAuthServer.fromChain(listOf(pem("genuine-leaf"), pem("genuine-ca")))
        assertNotNull(observed)
        assertEquals(genuineLeafKey, observed!!.leafSpkiSha256)
        assertEquals(listOf("radius.gecko-a.lab"), observed.leafNames)
        assertEquals(listOf(genuineCaKey), observed.verifiedIssuerSpkiSha256)
    }

    @Test
    fun fromChain_rogueLeafWithGenuineCaAppended_doesNotCountTheCa() {
        // The attack a hash-only check misses: the genuine CA certificate is public
        // and can be sent along, but it never signed the rogue leaf.
        val observed = ObservedAuthServer.fromChain(listOf(pem("rogue-leaf"), pem("genuine-ca")))!!
        assertTrue(observed.verifiedIssuerSpkiSha256.isEmpty())

        val c = cert(server = AuthServer(names = listOf("radius.gecko-a.lab"), caSPKIHashes = listOf(genuineCaKey)))
        assertEquals(VerificationState.CONFLICT, evaluateEnterpriseNetwork(observed, "gecko-a.lab", listOf(c)).state)
    }

    @Test
    fun fromChain_genuineChainAgainstRealPins_isVerified() {
        val observed = ObservedAuthServer.fromChain(listOf(pem("genuine-leaf"), pem("genuine-ca")))!!
        val c = cert(server = AuthServer(names = listOf("radius.gecko-a.lab"), caSPKIHashes = listOf(genuineCaKey)))
        assertEquals(VerificationState.VERIFIED, evaluateEnterpriseNetwork(observed, "gecko-a.lab", listOf(c)).state)
    }

    @Test
    fun fromChain_rogueChain_verifiesOnlyItsOwnCa() {
        val observed = ObservedAuthServer.fromChain(listOf(pem("rogue-leaf"), pem("rogue-ca")))!!
        assertEquals(1, observed.verifiedIssuerSpkiSha256.size)
        assertFalse(genuineCaKey in observed.verifiedIssuerSpkiSha256)
    }

    @Test
    fun fromChain_leafWithoutSan_fallsBackToCn() {
        val observed = ObservedAuthServer.fromChain(listOf(pem("cn-leaf"), pem("genuine-ca")))!!
        assertEquals(listOf("radius-cn.gecko-a.lab"), observed.leafNames)
    }

    @Test
    fun fromChain_emptyChain_isNull() {
        assertEquals(null, ObservedAuthServer.fromChain(emptyList()))
    }

    // --- registered CA certificates as anchors (servers often omit their root) ---

    private fun b64(name: String) = java.util.Base64.getEncoder().encodeToString(pem(name).encoded)

    @Test
    fun anchor_leafSentWithoutItsCa_verifiesAgainstTheRegisteredCa() {
        val server = AuthServer(names = listOf("radius.gecko-a.lab"), caSPKIHashes = listOf(genuineCaKey), caCertificates = listOf(b64("genuine-ca")))
        val observed = ObservedAuthServer.fromChain(listOf(pem("genuine-leaf")), server.pinnedCaCertificates())!!
        assertEquals(listOf(genuineCaKey), observed.verifiedIssuerSpkiSha256)
        assertTrue(server.accepts(observed))
    }

    @Test
    fun anchor_rogueLeafIsNotSignedByTheRegisteredCa() {
        val server = AuthServer(names = listOf("radius.gecko-a.lab"), caSPKIHashes = listOf(genuineCaKey), caCertificates = listOf(b64("genuine-ca")))
        val observed = ObservedAuthServer.fromChain(listOf(pem("rogue-leaf"), pem("rogue-ca")), server.pinnedCaCertificates())!!
        assertFalse(server.accepts(observed))
    }

    @Test
    fun pinnedCaCertificates_dropsCertificatesWhoseKeyIsNotPinned() {
        // The hashes are the trust anchor: a certificate riding along without a matching pin is ignored.
        val server = AuthServer(caSPKIHashes = listOf(genuineCaKey), caCertificates = listOf(b64("rogue-ca"), b64("genuine-ca"), "not base64!"))
        assertEquals(listOf(genuineCaKey), server.pinnedCaCertificates().map { ObservedAuthServer.spkiSha256(it) })
    }

    // --- a captured chain through the engine (VerificationEngine.verifyEnterpriseChain) ---

    private fun engineReturning(vararg certs: GeoCertificate) = VerificationEngine(
        io.mockk.mockk<com.thesis.geckowifi.data.remote.GeckoClient> {
            io.mockk.coEvery { queryLocation(any(), any(), any()) } returns com.thesis.geckowifi.data.remote.GeckoResponse.Success(certs.toList())
        },
        io.mockk.mockk(relaxed = true), DecisionCache(), GeoQueryEncoder()
    ) { java.time.Instant.parse("2026-10-14T00:00:00Z") }

    /** The lab eduroam as registered: genuine CA pinned and carried, area around the test location. */
    private fun registeredLab() = cert(server = AuthServer(
        names = listOf("radius.gecko-a.lab"),
        caSPKIHashes = listOf(genuineCaKey),
        caCertificates = listOf(b64("genuine-ca"))
    )).copy(areas = listOf(com.thesis.geckowifi.ZURICH_AREA))

    private suspend fun captured(chain: List<X509Certificate>, realm: String?) =
        engineReturning(registeredLab()).verifyEnterpriseChain("net", chain, realm, 47.3765, 8.5473, null, 20, ssid = "eduroam")

    @Test
    fun capturedGenuineLeaf_withoutItsCa_isVerifiedViaTheRegisteredCa() = kotlinx.coroutines.test.runTest {
        // The server sent only its own certificate; the GeoCert carries the CA.
        assertEquals(VerificationState.VERIFIED, captured(listOf(pem("genuine-leaf")), "gecko-a.lab").state)
    }

    @Test
    fun capturedRogueChainWithTheGenuineCaAppended_isConflict() = kotlinx.coroutines.test.runTest {
        assertEquals(VerificationState.CONFLICT, captured(listOf(pem("rogue-leaf"), pem("genuine-ca")), "gecko-a.lab").state)
    }

    @Test
    fun capturedRogueChain_withoutRealm_isOnlyRegistered() = kotlinx.coroutines.test.runTest {
        // In a federation an unknown realm could be a visitor: no warning without it.
        assertEquals(VerificationState.REGISTERED, captured(listOf(pem("rogue-leaf"), pem("rogue-ca")), null).state)
    }
}
