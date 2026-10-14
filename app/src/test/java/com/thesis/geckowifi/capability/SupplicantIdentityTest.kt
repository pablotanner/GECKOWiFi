package com.thesis.geckowifi.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [parseCurrentNetworkId] and [realmOfIdentity] against `wpa_cli` output shapes. */
class SupplicantIdentityTest {

    @Test
    fun networkIdFromStatus() {
        val status = """
            bssid=94:83:c4:97:c2:3a
            freq=2412
            ssid=eduroam
            id=3
            mode=station
            wpa_state=COMPLETED
        """.trimIndent()
        assertEquals(3, parseCurrentNetworkId(status))
        assertNull(parseCurrentNetworkId("wpa_state=DISCONNECTED"))
    }

    @Test
    fun realmFromQuotedIdentity() {
        assertEquals("ethz.ch", realmOfIdentity("\"anonymous@ethz.ch\""))
        assertEquals("student.ethz.ch", realmOfIdentity("\"someone@Student.ETHZ.ch\""))
    }

    @Test
    fun noRealm() {
        assertNull(realmOfIdentity("FAIL"))
        assertNull(realmOfIdentity("\"\""))
        assertNull(realmOfIdentity("\"anonymous\""))
        assertNull(realmOfIdentity(null))
    }
}
