package com.thesis.geckowifi.data.remote

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.PortalDomain
import com.thesis.geckowifi.data.model.PortalDomainRole
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression test for a real bug caught against a live server
 * (demo-eduroam-001, 2026-09-11): Go's `omitempty` does not suppress a
 * non-pointer struct field, so any certificate that never sets `portal`
 * (i.e. every pure-enterprise certificate) still gets a real
 * `"portal":{"domains":null,...}` object from the server - a JSON `null` for
 * a Kotlin field that is non-nullable but has a default (`domains: List<String>
 * = emptyList()`). kotlinx.serialization rejects that by default; it only
 * falls back to the default when the key is absent entirely, not when it's
 * present with value `null`. Fixed via `coerceInputValues = true` on
 * [GeckoClient]'s certificate JSON parser.
 */
class GeckoClientCertificateParsingTest {

    // The exact shape logcat showed for the dropped certificate, minus the
    // truncation - a pure-enterprise cert whose "portal" the server still
    // emits with a null "domains".
    private val eduroamCertJsonWithNullPortalDomains = """
        {
          "certificate_id": "demo-eduroam-001",
          "wifi": {
            "auth_mode": "wpa2-enterprise",
            "eap": "eap-tls",
            "auth_server_names": ["radius.eduroam-demo.test.local"],
            "trusted_ca_fingerprints": ["txb5FiQ2zAo/SIpWa0UN7n2jfmsYQDZIER2KIPxbmiU="]
          },
          "portal": {"domains": null},
          "areas": [],
          "areas_altitude": [],
          "not_valid_after": "2028-01-01T00:00:00Z"
        }
    """.trimIndent()

    @Test
    fun withoutCoerceInputValues_nullPortalDomainsThrows() {
        val strictJson = Json { ignoreUnknownKeys = true }
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(GeoCertificate.serializer(), eduroamCertJsonWithNullPortalDomains)
        }
    }

    @Test
    fun withCoerceInputValues_nullPortalDomainsFallsBackToEmptyList() {
        val lenientJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val cert = lenientJson.decodeFromString(GeoCertificate.serializer(), eduroamCertJsonWithNullPortalDomains)

        assertEquals("demo-eduroam-001", cert.certificateId)
        assertEquals(emptyList<PortalDomain>(), cert.portal?.domains)
        assertEquals(listOf("radius.eduroam-demo.test.local"), cert.wifi.authServerNames)
    }

    // Same settings as GeckoClient's certificate parser.
    private val clientJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun schemaV2_portalDomainsWithRolesAndPerDomainPins() {
        val json = """
            {
              "schema_version": 2,
              "certificate_id": "mango-a",
              "wifi": { "auth_mode": "open", "ssid": "GeckoTest" },
              "portal": { "domains": [
                { "name": "portal.gecko-a.lab", "role": "primary", "pinned_spki_sha256": ["hashA"] },
                { "name": "pay.gecko-pay.lab", "role": "delegate", "pinned_spki_sha256": ["hashP"] }
              ] },
              "areas": [],
              "areas_altitude": [],
              "not_valid_after": "2031-09-26T15:28:00Z"
            }
        """.trimIndent()

        val cert = clientJson.decodeFromString(GeoCertificate.serializer(), json)

        assertEquals(2, cert.schemaVersion)
        assertEquals(PortalDomainRole.PRIMARY, cert.domainEntry("PORTAL.gecko-a.lab")?.role)
        assertEquals(listOf("hashP"), cert.domainEntry("pay.gecko-pay.lab")?.pinnedSPKIHashes)
        assertEquals(listOf("portal.gecko-a.lab"), cert.primaryDomains().map { it.name })
    }

    @Test
    fun legacyCertificateWithEmptyAuthMode_parsesWithUnknownAuthMode() {
        // Shape of the pre-v2 golden-vector certificates (eth-a, eth-b, ...): no
        // wifi section when inserted, so the Go server emits "auth_mode": "".
        val json = """
            {
              "certificate_id": "eth-a",
              "wifi": { "auth_mode": "" },
              "portal": { "domains": null },
              "areas": [],
              "areas_altitude": [],
              "not_valid_after": "2030-01-01T00:00:00Z"
            }
        """.trimIndent()

        val cert = clientJson.decodeFromString(GeoCertificate.serializer(), json)

        assertEquals("eth-a", cert.certificateId)
        assertNull(cert.wifi.authMode)
        assertEquals(0, cert.schemaVersion)
    }

    @Test
    fun legacyStringDomains_areRejected() {
        // Pre-v2 portal format: GeckoClient drops such a certificate (logged), it
        // must not be half-parsed into a v2 certificate without pins.
        val json = """
            {
              "certificate_id": "mango-new",
              "wifi": { "auth_mode": "open", "ssid": "GeckoTest" },
              "portal": { "domains": ["gecko-a.lab"], "pinned_spki_sha256": ["hashA"] },
              "areas": [],
              "areas_altitude": [],
              "not_valid_after": "2031-09-26T15:28:00Z"
            }
        """.trimIndent()

        assertThrows(SerializationException::class.java) {
            clientJson.decodeFromString(GeoCertificate.serializer(), json)
        }
    }

    @Test
    fun isValidAt_handlesOffsetsAndFailsClosedOnGarbage() {
        val base = clientJson.decodeFromString(GeoCertificate.serializer(), """
            { "certificate_id": "c", "wifi": { "auth_mode": "open" }, "not_valid_after": "2030-01-01T00:00:00+02:00" }
        """.trimIndent())
        val now = java.time.Instant.parse("2029-12-31T21:59:59Z")

        assertEquals(true, base.isValidAt(now))
        assertEquals(false, base.isValidAt(now.plusSeconds(2)))
        assertEquals(false, base.copy(notValidAfter = "not a date").isValidAt(now))
    }
}
