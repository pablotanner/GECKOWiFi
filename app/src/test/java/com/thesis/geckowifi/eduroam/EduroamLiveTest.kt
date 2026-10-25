package com.thesis.geckowifi.eduroam

import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.verification.CertProbe
import com.thesis.geckowifi.verification.DecisionCache
import com.thesis.geckowifi.verification.GeoQueryEncoder
import com.thesis.geckowifi.verification.LookupStatus
import com.thesis.geckowifi.verification.VerificationEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The eduroam path against the live lab servers (docs/eduroam-demo.md, phase 4
 * "done" criterion): the map server knows `eduroam-lab` at the lab location,
 * and the realm endpoint serves a record that verifies against the pinned key.
 * Skipped unless both are reachable at the device flavor's addresses, or at
 * GECKO_LAB_HOST (e.g. WSL's address, when the laptop's portproxy isn't set up).
 */
class EduroamLiveTest {
    private val eth = Pair(47.3763, 8.5480) // ETH main building, inside the lab area
    private val host = System.getenv("GECKO_LAB_HOST") ?: "192.168.137.1"

    private fun reachable(port: Int) = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 1500) }
    }.isSuccess

    private fun engine() = VerificationEngine(GeckoClient(baseUrl = "http://$host:1234"), CertProbe(), DecisionCache(), GeoQueryEncoder())

    /**
     * The whole lookup of the home list: proof verified over every certificate
     * of the area, also those of other kinds the server holds, and the lab's
     * WiFi certificates read from extensions.wifi (D28). Others may be
     * registered in the area too.
     */
    @Test
    fun lookupAtTheLab_returnsTheWifiCertificates() = runTest {
        assumeTrue("map server not reachable", reachable(1234))
        val lookup = engine().registeredHere(eth.first, eth.second, null, 253)
        assertEquals(LookupStatus.OK, lookup.status)
        val ids = lookup.certificates.map { it.certificateId }.toSet()
        val lab = setOf("mango-a", "eduroam-lab-sp", "ethz-eduroam-2026a", "eth-visitors-2026a")
        assertTrue("missing ${lab - ids}", ids.containsAll(lab))
        assertTrue(lookup.certificates.all { it.isEvaluable() && it.subject?.id?.isNotEmpty() == true })
    }

    @Test
    fun siteQuery_findsTheRegisteredSite() = runTest {
        assumeTrue("map server not reachable", reachable(1234))
        val engine = engine()
        val site = engine.checkAdvertisedSite("live", "eduroam-lab", setOf(WiFiAuthMode.WPA2_ENTERPRISE), eth.first, eth.second, null, 50)
        assertEquals(site.result.reason, VerificationState.REGISTERED, site.result.state)
        assertEquals("eduroam-lab-sp", site.certificates.single().certificateId)
    }

    @Test
    fun openCloneOfTheSite_isConflict() = runTest {
        assumeTrue("map server not reachable", reachable(1234))
        val site = engine().checkAdvertisedSite("live", "eduroam-lab", setOf(WiFiAuthMode.OPEN), eth.first, eth.second, null, 50)
        assertEquals(VerificationState.CONFLICT, site.result.state)
    }

    @Test
    fun realmEndpoint_servesAVerifiableRecord() = runTest {
        assumeTrue("realm server not reachable", reachable(1235))
        val client = RealmRecordClient(baseUrl = "http://$host:1235", network = null)
        val fetched = client.fetch("eduroam-lab", "homeuni.test")
        val bytes = (fetched as? RealmFetch.Fetched)?.bytes ?: throw AssertionError("fetch: $fetched")
        val pins = (AuthenticatedEapPins.fromSignedRealmRecord(bytes, "eduroam-lab", "homeuni.test") as? PinResult.Ok)?.pins
            ?: throw AssertionError("record rejected")
        assertEquals(listOf("radius.homeuni.test"), pins.serverNames)
        assertEquals(RealmFetch.NotFound, client.fetch("eduroam-lab", "other.test"))
        // Same realm, other federation: a different binding, and not valid at an eduroam-lab site.
        val gov = (client.fetch("govroam", "homeuni.test") as RealmFetch.Fetched).bytes
        assertEquals(listOf("radius-gov.homeuni.test"),
            (AuthenticatedEapPins.fromSignedRealmRecord(gov, "govroam", "homeuni.test") as PinResult.Ok).pins.serverNames)
        assertTrue(AuthenticatedEapPins.fromSignedRealmRecord(gov, "eduroam-lab", "homeuni.test") is PinResult.Rejected)
    }
}
