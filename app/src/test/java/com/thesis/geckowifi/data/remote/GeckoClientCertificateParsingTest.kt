package com.thesis.geckowifi.data.remote

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.PortalDomain
import com.thesis.geckowifi.data.model.PortalDomainRole
import com.thesis.geckowifi.data.model.WiFiAuthMode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
        // v2 enterprise fields are unknown keys now: ignored, no enterprise section.
        assertNull(cert.wifi.enterprise)
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
        assertTrue(cert.wifi.authModes.isEmpty())
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

    // --- schema v3 ---

    /**
     * mango-a in the app's own v3 shape: what the server served before the
     * extension format, and what saved data still holds. The server's current
     * format is tested in GeoCertificateFormatTest.
     */
    private val mangoAv3 = """
        {
          "schema_version": 3,
          "certificate_id": "mango-a",
          "subject": { "id": "gecko-a.lab", "name": "GECKO Lab, Router A" },
          "critical": [],
          "wifi": { "ssid": "GeckoTest", "auth_modes": ["open"] },
          "portal": { "domains": [
            { "name": "portal.gecko-a.lab", "role": "primary", "pinned_spki_sha256": ["dAJnHfG0hpOWSRhQOeWz1NBoiXHnUnB2y/40h9i2exk="] },
            { "name": "pay.gecko-pay.lab", "role": "delegate", "pinned_spki_sha256": ["u9H7ovOnPGAi3ptBn2262bEEeXNcndvZCmnr8gmG3+c="] }
          ] },
          "areas": [{ "type": "MultiPolygon",
            "coordinates": [[[[8.515,47.360],[8.565,47.360],[8.565,47.390],[8.515,47.390],[8.515,47.360]]],[]] }],
          "areas_altitude": [[200,700]],
          "not_valid_after": "2031-09-26T15:28:00Z"
        }
    """.trimIndent()

    @Test
    fun schemaV3_mangoA() {
        val cert = clientJson.decodeFromString(GeoCertificate.serializer(), mangoAv3)

        assertEquals(3, cert.schemaVersion)
        assertEquals("gecko-a.lab", cert.subject?.id)
        assertEquals(setOf(WiFiAuthMode.OPEN), cert.wifi.authModes)
        assertFalse(cert.wifi.ssidShared)
        assertTrue(cert.isEvaluable())
        assertEquals(1, cert.areas.single().exteriorRings.size)
        assertTrue(cert.areas.single().interiorRings.isEmpty())
        assertEquals(PortalDomainRole.PRIMARY, cert.domainEntry("portal.gecko-a.lab")?.role)
    }

    @Test
    fun schemaV3_eduroam() {
        val json = """
            {
              "schema_version": 3,
              "certificate_id": "eduroam",
              "subject": { "id": "ethz.ch" },
              "wifi": {
                "ssid": "eduroam",
                "auth_modes": ["wpa2-enterprise", "wpa3-enterprise"],
                "pmf": "optional",
                "enterprise": {
                  "federation": "eduroam",
                  "aaa": [{
                    "realms": ["ethz.ch", "*.ethz.ch"],
                    "eap_methods": [{ "method": "peap", "phase2": ["mschapv2"] }],
                    "servers": [{ "names": ["radius.ethz.ch"], "ca_spki_sha256": ["caKey"] }]
                  }]
                }
              },
              "portal": null,
              "areas": [],
              "areas_altitude": [],
              "not_valid_before": "2026-11-01T00:00:00Z",
              "not_valid_after": "2027-11-01T00:00:00Z"
            }
        """.trimIndent()

        val cert = clientJson.decodeFromString(GeoCertificate.serializer(), json)
        val enterprise = cert.wifi.enterprise!!

        assertEquals(setOf(WiFiAuthMode.WPA2_ENTERPRISE, WiFiAuthMode.WPA3_ENTERPRISE), cert.wifi.authModes)
        assertTrue(enterprise.isFederated)
        assertTrue(enterprise.aaa.single().coversRealm("ETHZ.ch"))
        assertTrue(enterprise.aaa.single().coversRealm("student.ethz.ch"))
        assertFalse(enterprise.aaa.single().coversRealm("notethz.ch"))
        assertEquals(listOf("caKey"), enterprise.aaa.single().servers.single().caSPKIHashes)
        assertEquals("peap", enterprise.aaa.single().eapMethods.single().method)
        assertFalse(cert.isValidAt(java.time.Instant.parse("2026-10-31T23:59:59Z")))
        assertTrue(cert.isValidAt(java.time.Instant.parse("2026-11-01T00:00:00Z")))
    }

    @Test
    fun unknownEnumValues_neverFailTheCertificateAndNeverMatch() {
        // A future server may register modes or roles this client doesn't know.
        val json = """
            {
              "schema_version": 3,
              "certificate_id": "future",
              "wifi": { "ssid": "X", "auth_modes": ["open", "wpa4-quantum"] },
              "portal": { "domains": [ { "name": "p.example", "role": "kiosk", "pinned_spki_sha256": ["k"] } ] },
              "not_valid_after": "2031-01-01T00:00:00Z"
            }
        """.trimIndent()

        val cert = clientJson.decodeFromString(GeoCertificate.serializer(), json)

        assertEquals(setOf(WiFiAuthMode.OPEN), cert.wifi.authModes)
        assertNull(cert.domainEntry("p.example")?.role)
        assertTrue(cert.primaryDomains().isEmpty())
    }

    @Test
    fun unknownCriticalFeature_makesCertificateUnevaluable() {
        val json = """
            { "certificate_id": "c", "critical": ["ssid-shared-v2"], "wifi": { "ssid": "X" }, "not_valid_after": "2031-01-01T00:00:00Z" }
        """.trimIndent()

        assertFalse(clientJson.decodeFromString(GeoCertificate.serializer(), json).isEvaluable())
    }

    @Test
    fun unparseableNotValidBefore_failsClosed() {
        val cert = clientJson.decodeFromString(GeoCertificate.serializer(), mangoAv3).copy(notValidBefore = "soon")
        assertFalse(cert.isValidAt(java.time.Instant.parse("2030-01-01T00:00:00Z")))
    }
}
