package com.thesis.geckowifi.eduroam

import com.thesis.geckowifi.data.model.AAADomain
import com.thesis.geckowifi.data.model.AuthServer
import com.thesis.geckowifi.data.model.EAPMethodSpec
import com.thesis.geckowifi.data.model.EnterpriseIdentity
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.WiFiIdentity
import com.thesis.geckowifi.eduroam.EduroamFixtures.ca
import com.thesis.geckowifi.eduroam.EduroamFixtures.leaf
import com.thesis.geckowifi.eduroam.EduroamFixtures.now
import com.thesis.geckowifi.eduroam.EduroamFixtures.otherKey
import com.thesis.geckowifi.eduroam.EduroamFixtures.realmKey
import com.thesis.geckowifi.eduroam.EduroamFixtures.record
import com.thesis.geckowifi.eduroam.EduroamFixtures.sign
import com.thesis.geckowifi.verification.ObservedAuthServer
import gecko.realm.SignedRealmRecord
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Base64

/** Which realm records and site registrations yield pins, and which don't (docs/eduroam-decisions.md, D2). */
class AuthenticatedEapPinsTest {

    private fun verify(bytes: ByteArray, realm: String = "homeuni.test", at: Instant = now, federation: String = "eduroam") =
        AuthenticatedEapPins.fromSignedRealmRecord(bytes, federation, realm, realmKey.public, at)

    private fun rejected(result: PinResult, containing: String) {
        assertTrue("expected rejection, got $result", result is PinResult.Rejected)
        val reason = (result as PinResult.Rejected).reason
        assertTrue("\"$reason\" should mention \"$containing\"", reason.contains(containing))
    }

    // --- realm records ---

    @Test
    fun validRecord_givesItsPins() {
        val pins = (verify(sign(record())) as PinResult.Ok).pins
        assertEquals("homeuni.test", pins.realm)
        assertEquals(EapMethod.PEAP, pins.method)
        assertEquals(Phase2.MSCHAPV2, pins.phase2)
        assertEquals("anonymous@homeuni.test", pins.outerIdentity)
        assertEquals(listOf(ca), pins.caCertificates)
        assertEquals(listOf("radius.homeuni.test"), pins.serverNames)
        assertTrue(pins.source is PinSource.RealmRecord)
    }

    // --- federation scoping (docs/eduroam-decisions.md, D19) ---

    @Test
    fun pinsCarryTheRecordsFederation() {
        val pins = (verify(sign(record())) as PinResult.Ok).pins
        assertEquals("eduroam", pins.federation)
        assertEquals("Home University", (pins.source as PinSource.RealmRecord).displayName)
    }

    @Test
    fun recordOfAnotherFederation_isRejected() =
        rejected(verify(sign(record(federation = "govroam"))), "federation govroam, this network is in eduroam")

    @Test
    fun recordWithoutFederation_isRejected() = rejected(verify(sign(record(federation = ""))), "names no federation")

    @Test
    fun federationIsComparedCaseInsensitively() {
        assertTrue(verify(sign(record(federation = "eduroam")), federation = "EduRoam") is PinResult.Ok)
    }

    @Test
    fun recordSignedByAnotherKey_isRejected() = rejected(verify(sign(record(), otherKey)), "signature")

    @Test
    fun tamperedRecord_isRejected() {
        val signed = SignedRealmRecord.ADAPTER.decode(sign(record()))
        val swapped = gecko.realm.RealmRecord.ADAPTER.encode(record(names = listOf("radius.attacker.test")))
        val tampered = SignedRealmRecord.ADAPTER.encode(signed.copy(record = swapped.toByteString()))
        rejected(verify(tampered), "signature")
    }

    @Test
    fun garbage_isRejectedNotThrown() = rejected(verify(byteArrayOf(1, 2, 3, 4)), "malformed")

    @Test
    fun recordForAnotherRealm_isRejected() = rejected(verify(sign(record(realm = "other.test", outer = "anonymous@other.test"))), "not homeuni.test")

    @Test
    fun realmIsComparedCaseInsensitively() {
        assertTrue(verify(sign(record()), realm = "HomeUni.Test") is PinResult.Ok)
    }

    @Test
    fun expiredRecord_isRejected() = rejected(verify(sign(record(expiresAt = now.minusSeconds(1)))), "expired")

    @Test
    fun recordWithoutExpiry_isRejected() = rejected(verify(sign(record(expiresAt = Instant.EPOCH))), "expired")

    @Test
    fun recordIssuedInTheFuture_isRejected() = rejected(verify(sign(record(issuedAt = now.plusSeconds(3600)))), "future")

    @Test
    fun smallClockSkew_isTolerated() {
        assertTrue(verify(sign(record(issuedAt = now.plusSeconds(60)))) is PinResult.Ok)
    }

    @Test
    fun eapTls_isRejected() = rejected(verify(sign(record(method = "TLS"))), "unsupported EAP method")

    @Test
    fun papInsidePeap_isRejected() = rejected(verify(sign(record(phase2 = "PAP"))), "unsupported inner method")

    @Test
    fun ttlsPap_isAccepted() {
        val pins = (verify(sign(record(method = "TTLS", phase2 = "PAP"))) as PinResult.Ok).pins
        assertEquals(EapMethod.TTLS, pins.method)
        assertEquals(Phase2.PAP, pins.phase2)
    }

    @Test
    fun noCaCertificate_isRejected() = rejected(verify(sign(record(cas = emptyList()))), "no CA certificate")

    @Test
    fun leafPinnedAsCa_isRejected() = rejected(verify(sign(record(cas = listOf(leaf)))), "not a CA certificate")

    @Test
    fun noServerName_isRejected() = rejected(verify(sign(record(names = emptyList()))), "no server name")

    @Test
    fun serverNameWithListSeparator_isRejected() =
        rejected(verify(sign(record(names = listOf("radius.homeuni.test;attacker.test")))), "invalid server name")

    @Test
    fun outerIdentityInAnotherRealm_isRejected() =
        rejected(verify(sign(record(outer = "anonymous@attacker.test"))), "isn't in realm")

    @Test
    fun blankOuterIdentity_becomesAnonymousNeverTheRealOne() {
        assertEquals("anonymous@homeuni.test", (verify(sign(record(outer = ""))) as PinResult.Ok).pins.outerIdentity)
    }

    /**
     * Cross-checks lab/realm's hand-written Go encoder and signer against this
     * decoder and [PinnedRealmKey]. Skipped where the gitignored lab PKI
     * hasn't been generated (`lab/eduroam/make-pki.sh`, then `go run . sign`).
     */
    @Test
    fun labSignedRecord_verifiesAgainstThePinnedKey() {
        val file = File("../lab-secrets/realm/signed/eduroam/homeuni.test.bin")
        assumeTrue("no lab-signed record at ${file.absolutePath}", file.exists())
        val result = AuthenticatedEapPins.fromSignedRealmRecord(file.readBytes(), "eduroam", "homeuni.test", PinnedRealmKey.publicKey, Instant.now())
        val pins = (result as? PinResult.Ok)?.pins ?: throw AssertionError("lab record rejected: $result")
        assertEquals(listOf("radius.homeuni.test"), pins.serverNames)
        assertEquals("GECKO-Lab-CA", pins.caCertificates.single().subjectX500Principal.name.substringAfter("CN=").substringBefore(','))
    }

    // --- site registrations (local users) ---

    private val caHash = ObservedAuthServer.spkiSha256(ca)
    private val caB64 = Base64.getEncoder().encodeToString(ca.encoded)

    private fun site(
        server: AuthServer = AuthServer(names = listOf("radius.gecko-a.lab"), caSPKIHashes = listOf(caHash), caCertificates = listOf(caB64)),
        methods: List<EAPMethodSpec> = listOf(EAPMethodSpec("peap", listOf("mschapv2")))
    ) = GeoCertificate(
        schemaVersion = 3, certificateId = "lab-eduroam",
        wifi = WiFiIdentity(ssid = "eduroam-lab", authModeNames = listOf("wpa2-enterprise"),
            enterprise = EnterpriseIdentity("eduroam", listOf(AAADomain(listOf("gecko-a.lab", "*.gecko-a.lab"), methods, listOf(server))))),
        notValidAfter = "2099-01-01T00:00:00Z"
    )

    @Test
    fun siteRegistrationForTheRealm_givesPins() {
        val pins = (AuthenticatedEapPins.fromSiteCertificate(site(), "gecko-a.lab") as PinResult.Ok).pins
        assertEquals(listOf(ca), pins.caCertificates)
        assertEquals(listOf("radius.gecko-a.lab"), pins.serverNames)
        assertEquals("anonymous@gecko-a.lab", pins.outerIdentity)
        assertEquals(PinSource.SiteCertificate("lab-eduroam"), pins.source)
        assertEquals("eduroam", pins.federation)
    }

    @Test
    fun subrealm_usesTheUsersOwnRealm() {
        val pins = (AuthenticatedEapPins.fromSiteCertificate(site(), "staff.gecko-a.lab") as PinResult.Ok).pins
        assertEquals("staff.gecko-a.lab", pins.realm)
    }

    @Test
    fun visitorRealm_givesNoSitePins() = rejected(AuthenticatedEapPins.fromSiteCertificate(site(), "homeuni.test"), "no servers for realm")

    @Test
    fun caHashWithoutCertificate_givesNothingToPin() = rejected(
        AuthenticatedEapPins.fromSiteCertificate(site(AuthServer(names = listOf("radius.gecko-a.lab"), caSPKIHashes = listOf(caHash))), "gecko-a.lab"),
        "nothing to pin")

    @Test
    fun caCertificateWhoseKeyIsNotPinned_isIgnored() = rejected(
        AuthenticatedEapPins.fromSiteCertificate(site(AuthServer(names = listOf("radius.gecko-a.lab"), caSPKIHashes = listOf("other"), caCertificates = listOf(caB64))), "gecko-a.lab"),
        "nothing to pin")

    @Test
    fun unsupportedSiteMethod_givesNoPins() = rejected(
        AuthenticatedEapPins.fromSiteCertificate(site(methods = listOf(EAPMethodSpec("eap-tls"))), "gecko-a.lab"),
        "no EAP method")
}
