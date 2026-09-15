package com.thesis.geckowifi.data.remote

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The demo geopki server's signing public key, pinned at build time rather
 * than fetched at runtime from `/v1/public-key`.
 *
 * Why pinning instead of trust-on-first-use: this key is the sole root of
 * trust for everything [GeckoClient] verifies - it signs both the Signed Map
 * Head and the Signed Consistency Head (see geopki/crypto/Verification.kt).
 * Fetching it fresh over the network on every check meant that whenever a
 * check fell back to querying over the WiFi network under evaluation (see
 * [GeckoClient]'s `fallbackClient`), that same untrusted network could hand
 * back its own key instead - defeating every downstream cryptographic check
 * with a self-consistent forgery, since nothing would catch a wrong-but
 * internally-valid signature. Pinning the expected key at build time - the
 * same "bundled... from a trusted source" bootstrap the GECKO paper itself
 * assumes (arXiv:2511.21999, Sec. II-D) rather than specifying a mechanism
 * for - closes that gap for this single, developer-controlled demo server.
 *
 * Confirmed (2026-09-15) against the live demo server that this key is
 * persisted, not regenerated per restart - safe to hardcode for local dev.
 * If the geopki server is ever reset/reinitialized such that it generates a
 * new keypair, re-fetch it and update [PINNED_PUBLIC_KEY_BASE64] below:
 *
 *     curl http://<server>/v1/public-key | base64 -w0
 *
 * Until updated, every query will fail closed as a proof failure (mapped to
 * `CONFLICT`), never silently misverify against the stale pin - see
 * `GeoPkiVerificationException`/`GeckoResponse.ProofFailure`.
 *
 * This is a proof-of-concept simplification scoped to one known server, not
 * a general solution - see README.md's trust-model section for what a real
 * (multi-server, rotatable-key) deployment would need instead.
 */
object PinnedServerKey {

    private const val PINNED_PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEkVnSLBxDuD2h8+16rsnbYZwZqpeWmNgrFypic32Qu6NXgzJxrgDPmucl80pi9VhAuAIX1R2Dg30iezDi7VoQgA=="

    val publicKey: PublicKey by lazy {
        val der = Base64.getDecoder().decode(PINNED_PUBLIC_KEY_BASE64)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }
}
