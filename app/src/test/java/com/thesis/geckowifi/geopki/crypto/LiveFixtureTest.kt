package com.thesis.geckowifi.geopki.crypto

import com.thesis.geckowifi.geopki.bitstring.RawXYBitString
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import geopki.Request as ProtoRequest
import geopki.Response as ProtoResponse
import geopki.SignedConsistencyHead as ProtoSignedConsistencyHead
import geopki.SignedMapHead as ProtoSignedMapHead

/**
 * Regression test against REAL bytes captured from a locally-running
 * geopki server (see `app/src/test/resources/geopki-fixtures/`, captured by
 * `lab/golden/regen.sh` from the map-version-2 server) - genuine upstream
 * parity, not just self-consistency like the rest of the crypto test suite.
 * The first capture (2026-09-11, map version 1) showed geopki signed the raw
 * TBS bytes ("NONEwithECDSA"); version 2 signs their SHA-256
 * ("SHA256withECDSA"), so every field is covered.
 *
 * Also confirms: the Wire field mapping for SignedMapHead/SignedConsistencyHead
 * matches the live server exactly, the reconstructed `SignedConsistencyHead`
 * TBS layout (rootHash || timestamp(BE64) || size(BE64)) is correct, and the
 * reconstructed `geopki.trillian.Proof` schema (leaf_index=1, hashes=3,
 * field 2 reserved) matches the live server's inclusion proof encoding.
 */
class LiveFixtureTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("geopki-fixtures/$name")!!.use { it.readBytes() }

    private val publicKey by lazy {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(fixture("public-key.der")))
    }

    @Test
    fun signedMapHead_verifiesAgainstCapturedStandaloneResponse() {
        val proto = ProtoSignedMapHead.ADAPTER.decode(fixture("smh-standalone.bin"))
        assertTrue(SignedMapHead.fromProto(proto).verify(publicKey))
    }

    @Test
    fun signedConsistencyHead_verifiesAgainstCapturedStandaloneResponse() {
        val proto = ProtoSignedConsistencyHead.ADAPTER.decode(fixture("sch-standalone.bin"))
        assertTrue(SignedConsistencyHead.fromProto(proto).verify(publicKey))
    }

    @Test
    fun verifyResponse_succeedsAgainstCapturedQueryResponse() {
        val protoRequest = ProtoRequest.ADAPTER.decode(fixture("request.bin"))
        val protoResponse = ProtoResponse.ADAPTER.decode(fixture("response.bin"))

        val query = GeoPkiQuery(
            xyBitStrings = protoRequest.XYBitStrings.map {
                RawXYBitString(xyBitString = it.geopki_XYBitString.toULong(), xyBitStringLen = it.XYBitStringLen)
            },
            minAltitude = protoRequest.MinAltitude,
            maxAltitude = protoRequest.MaxAltitude,
            types = protoRequest.Types
        )

        val verified = verifyResponse(protoResponse, query, publicKey)

        // The captured response holds the golden WiFi certificates at HB - see cert0.json.
        assertTrue(verified.isNotEmpty())
    }

    @Test
    fun ensureConsistency_succeedsAgainstCapturedQueryResponse() {
        val protoResponse = ProtoResponse.ADAPTER.decode(fixture("response.bin"))
        ensureConsistency(protoResponse, publicKey) // throws on failure
    }

    /**
     * Completeness (see docs/acceptance-criteria.md AC3, and GeckoClient): the honest
     * captured `?c` response delivers every WiFi certificate its proof lists, and a
     * stripped copy - same signed nodes, certificate bytes removed - is rejected
     * as withholding by verifyResponse, against real server bytes.
     */
    @Test
    fun honestResponse_deliversEveryCertificateOfTheRequestedType() {
        val protoRequest = ProtoRequest.ADAPTER.decode(fixture("request.bin"))
        val protoResponse = ProtoResponse.ADAPTER.decode(fixture("response.bin"))
        val query = GeoPkiQuery.fromProtoRequest(protoRequest, withCertificates = true)

        assertTrue("fixture should ask for WiFi certificates", protoRequest.Types == listOf("wifi"))
        assertTrue("fixture should deliver a certificate", protoResponse.Certificates.isNotEmpty())
        verifyResponse(protoResponse, query, publicKey) // throws on withholding
    }

    @Test
    fun strippedResponse_isDetectedAsWithholding() {
        val protoRequest = ProtoRequest.ADAPTER.decode(fixture("request.bin"))
        val protoResponse = ProtoResponse.ADAPTER.decode(fixture("response.bin"))
        val query = GeoPkiQuery.fromProtoRequest(protoRequest, withCertificates = true)

        // Same signed map head and nodes; only the certificate bytes removed.
        val stripped = protoResponse.copy(Certificates = emptyList())

        val e = assertThrows(GeoPkiVerificationException::class.java) { verifyResponse(stripped, query, publicKey) }
        assertTrue(e.message!!, e.message!!.contains("withheld"))
    }
}
