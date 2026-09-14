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
}
