package com.thesis.geckowifi.eduroam

import gecko.realm.RealmRecord
import gecko.realm.SignedRealmRecord
import okio.ByteString.Companion.toByteString
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant

/** Test material for the eduroam path: a realm key, signed records, the lab chains in `eap-chains/`. */
object EduroamFixtures {
    val now: Instant = Instant.parse("2026-10-20T12:00:00Z")

    val realmKey: KeyPair = ecKey()
    val otherKey: KeyPair = ecKey()

    val ca: X509Certificate = pem("genuine-ca")
    val leaf: X509Certificate = pem("genuine-leaf")

    fun pem(name: String): X509Certificate =
        EduroamFixtures::class.java.getResourceAsStream("/eap-chains/$name.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    fun record(
        realm: String = "homeuni.test",
        method: String = "PEAP",
        phase2: String = "MSCHAPV2",
        outer: String = "anonymous@homeuni.test",
        cas: List<X509Certificate> = listOf(ca),
        names: List<String> = listOf("radius.homeuni.test"),
        issuedAt: Instant = now.minusSeconds(3600),
        expiresAt: Instant = now.plusSeconds(86_400)
    ) = RealmRecord(
        realm = realm, eap_method = method, phase2 = phase2, outer_identity = outer,
        ca_der = cas.map { it.encoded.toByteString() }, server_names = names,
        issued_at = issuedAt.epochSecond, expires_at = expiresAt.epochSecond
    )

    fun sign(record: RealmRecord, key: KeyPair = realmKey): ByteArray {
        val bytes = RealmRecord.ADAPTER.encode(record)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(bytes)
            sign()
        }
        return SignedRealmRecord.ADAPTER.encode(SignedRealmRecord(bytes.toByteString(), signature.toByteString()))
    }

    fun pins(record: RealmRecord = record()): AuthenticatedEapPins =
        (AuthenticatedEapPins.fromSignedRealmRecord(sign(record), record.realm, realmKey.public, now) as PinResult.Ok).pins

    private fun ecKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
}
