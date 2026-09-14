package com.thesis.geckowifi.data.remote

import com.thesis.geckowifi.data.model.GeoCertificate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
        assertEquals(emptyList<String>(), cert.portal?.domains)
        assertEquals(listOf("radius.eduroam-demo.test.local"), cert.wifi.authServerNames)
    }
}
