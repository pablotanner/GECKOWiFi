package com.thesis.geckowifi.geopki.crypto

import com.thesis.geckowifi.geopki.bitstring.RawXYBitString
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
 * netsec-ethz/geopki server on 2026-09-11 (see `app/src/test/resources/
 * geopki-fixtures/`) - genuine upstream parity, not just self-consistency
 * like the rest of the crypto test suite. This is what caught the one real
 * bug found in steps 1-4: `SignedMapHead`/`SignedConsistencyHead` verify
 * must use "NONEwithECDSA" (raw, un-hashed ECDSA over TBSBytes()), not
 * "SHA256withECDSA" - `ecdsa.SignASN1`/`VerifyASN1` in Go's `crypto/ecdsa`
 * do not hash their input themselves despite the `hash []byte` parameter
 * name, and `smh.go` passes `TBSBytes()` straight through.
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
            maxAltitude = protoRequest.MaxAltitude
        )

        val verified = verifyResponse(protoResponse, query, publicKey)

        // The captured response's one certificate is a real registered test
        // certificate ("test-benign-ap-001" / wifi.test.local) - see cert0.json.
        assertTrue(verified.isNotEmpty())
    }

    @Test
    fun ensureConsistency_succeedsAgainstCapturedQueryResponse() {
        val protoResponse = ProtoResponse.ADAPTER.decode(fixture("response.bin"))
        ensureConsistency(protoResponse, publicKey) // throws on failure
    }

    /**
     * Completeness (see docs/acceptance-criteria.md AC3, and GeckoClient): the honest
     * captured response delivers every certificate its proof references, and a
     * stripped copy - same signed nodes, certificate bytes removed - is caught
     * as withholding. Replicates GeckoClient's referenced-vs-delivered check
     * against real server bytes.
     */
    private fun deliveredHashes(response: ProtoResponse): Set<String> =
        response.Certificates.mapTo(HashSet()) {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(SHA256Hash.sha256(it.toByteArray()).bytes)
        }

    @Test
    fun honestResponse_deliversEveryReferencedCertificate() {
        val protoRequest = ProtoRequest.ADAPTER.decode(fixture("request.bin"))
        val protoResponse = ProtoResponse.ADAPTER.decode(fixture("response.bin"))
        val query = GeoPkiQuery.fromProtoRequest(protoRequest)

        val referenced = verifyResponse(protoResponse, query, publicKey)
        assertTrue("fixture should reference at least one certificate", referenced.isNotEmpty())
        assertTrue("honest response withholds nothing", (referenced - deliveredHashes(protoResponse)).isEmpty())
    }

    @Test
    fun strippedResponse_isDetectedAsWithholding() {
        val protoRequest = ProtoRequest.ADAPTER.decode(fixture("request.bin"))
        val protoResponse = ProtoResponse.ADAPTER.decode(fixture("response.bin"))
        val query = GeoPkiQuery.fromProtoRequest(protoRequest)

        // Same signed map head and nodes; only the certificate bytes removed.
        val stripped = protoResponse.copy(Certificates = emptyList())

        // The proof still verifies (nodes untouched) and still references the cert,
        val referenced = verifyResponse(stripped, query, publicKey)
        assertTrue(referenced.isNotEmpty())
        // but the certificate is no longer delivered -> withholding is detectable.
        assertTrue("stripped cert must be flagged as withheld",
            (referenced - deliveredHashes(stripped)).isNotEmpty())
    }
}
