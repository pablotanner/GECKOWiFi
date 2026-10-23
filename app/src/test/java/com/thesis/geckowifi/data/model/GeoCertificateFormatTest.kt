package com.thesis.geckowifi.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The map server's format (geopki's generic certificate with extensions.wifi)
 * read into the app's model: the lab certificates as registered, certificates
 * of other kinds, and what makes a certificate unreadable or unevaluable.
 */
class GeoCertificateFormatTest {
    // as GeckoClient reads certificates
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val area = """"areas":[{"type":"MultiPolygon","coordinates":[[[[8.5,47.3],[8.6,47.3],[8.6,47.4],[8.5,47.4],[8.5,47.3]]],[]]}],"areas_altitude":[[0,500]],"not_valid_after":"2030-01-01T00:00:00Z""""

    private fun wifiCert(wifi: String, critical: String = """["wifi"]""") =
        """{"certificate_id":"c","domain":"cafe.example",$area,"critical":$critical,"extensions":{"wifi":$wifi}}"""

    /** Every certificate of a lab/certs file, as the server stores them (the file is an insertion request). */
    private fun labCertificates(file: String): List<String> =
        json.parseToJsonElement(File("../lab/certs/$file").readText()).jsonArray.map { it.toString() }

    @Test
    fun labCertificates_allDecodeAndAreEvaluable() {
        for (file in listOf("mango-a.json", "eduroam-lab.json", "ethz-eduroam.json", "eth-visitors.json", "demo-eduroam.json")) {
            for (text in labCertificates(file)) {
                val cert = GeoCertificateFormat.decode(json, text)!!
                assertTrue("$file: critical ${cert.critical}", cert.isEvaluable())
                assertTrue("$file: no SSID", !cert.wifi.ssid.isNullOrBlank())
            }
        }
    }

    @Test
    fun mangoA_mapsOntoTheModel() {
        val cert = GeoCertificateFormat.decode(json, labCertificates("mango-a.json").single())!!

        assertEquals("mango-a", cert.certificateId)
        assertEquals(Subject("gecko-a.lab", "GECKO Lab, Router A"), cert.subject)
        assertEquals("GeckoTest", cert.wifi.ssid)
        assertEquals(setOf(WiFiAuthMode.OPEN), cert.wifi.authModes)
        assertEquals(listOf("portal.gecko-a.lab"), cert.primaryDomains().map { it.name })
        assertEquals(PortalDomainRole.DELEGATE, cert.domainEntry("pay.gecko-pay.lab")?.role)
        assertEquals(emptyList<String>(), cert.critical)
        assertEquals(listOf(listOf(200.0, 700.0)), cert.areasAltitude)
        assertEquals("2031-09-26T15:28:00Z", cert.notValidAfter)
    }

    @Test
    fun enterprise_mapsOntoTheModel() {
        val cert = GeoCertificateFormat.decode(json, labCertificates("ethz-eduroam.json").single())!!

        assertEquals("ethz.ch", cert.subject?.id)
        assertEquals("eduroam", cert.wifi.enterprise?.federation)
        val server = cert.wifi.enterprise!!.aaa.single().servers.single()
        assertEquals(listOf("radius-service.ethz.ch"), server.names)
        assertTrue(server.caCertificates.isNotEmpty())
        assertNull(cert.portal)
    }

    /** Shops, ATMs, ...: in every answer, since the proof covers them, but not GECKO's business. */
    @Test
    fun certificateOfAnotherKind_isNull() {
        assertNull(GeoCertificateFormat.decode(json, """{"certificate_id":"shop","domain":"shop.example",$area}"""))
        assertNull(GeoCertificateFormat.decode(json,
            """{"certificate_id":"atm","domain":"bank.example",$area,"critical":["atm"],"extensions":{"atm":{"cash":true}}}"""))
    }

    @Test
    fun otherCriticalExtension_makesTheCertificateUnevaluable() {
        val cert = GeoCertificateFormat.decode(json,
            """{"certificate_id":"c","domain":"cafe.example",$area,"critical":["wifi","future"],
               "extensions":{"wifi":{"version":1,"ssid":"Cafe","auth_modes":["open"]},"future":{}}}""")!!

        assertEquals(listOf("future"), cert.critical)
        assertFalse(cert.isEvaluable())
    }

    @Test
    fun wifiWithoutCritical_isStillRead() {
        val cert = GeoCertificateFormat.decode(json, wifiCert("""{"version":1,"ssid":"Cafe","auth_modes":["open"]}""", critical = "[]"))!!
        assertTrue(cert.isEvaluable())
    }

    @Test
    fun otherWifiVersion_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GeoCertificateFormat.decode(json, wifiCert("""{"version":2,"ssid":"Cafe","auth_modes":["open"]}"""))
        }
    }

    /** The client is lenient: fields it doesn't know (a newer server or issuer) are ignored. */
    @Test
    fun unknownFields_areIgnored() {
        val cert = GeoCertificateFormat.decode(json,
            """{"certificate_id":"c","domain":"cafe.example",$area,"new_core_field":1,"critical":["wifi"],
               "extensions":{"wifi":{"version":1,"ssid":"Cafe","auth_modes":["open","wpa4"],"new_wifi_field":true}}}""")!!

        assertEquals("Cafe", cert.wifi.ssid)
        // an unknown auth mode is kept by name and never matches
        assertEquals(setOf(WiFiAuthMode.OPEN), cert.wifi.authModes)
        assertEquals(listOf("open", "wpa4"), cert.wifi.authModeNames)
    }

    /** A certificate in the former flat v3 format has no extensions: it is not read as a WiFi network. */
    @Test
    fun formerFlatFormat_isNotAWifiCertificate() {
        assertNull(GeoCertificateFormat.decode(json,
            """{"schema_version":3,"certificate_id":"old","subject":{"id":"x"},"wifi":{"ssid":"Old","auth_modes":["open"]},$area}"""))
    }
}
