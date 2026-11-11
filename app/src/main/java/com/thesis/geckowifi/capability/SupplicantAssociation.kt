package com.thesis.geckowifi.capability

import com.thesis.geckowifi.network.Association
import com.thesis.geckowifi.network.AssociationSource
import com.thesis.geckowifi.network.parseSupplicantStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root [AssociationSource]: `wpa_cli status` of the running supplicant, which
 * reports the negotiated key management and PMF directly. UNTESTED ON HARDWARE,
 * like [SupplicantCertSource] (socket paths vary by device).
 */
class SupplicantAssociation(private val iface: String = "wlan0") : AssociationSource {
    override suspend fun current(): Association? = withContext(Dispatchers.IO) {
        wpaCli(iface)?.let { parseSupplicantStatus(RootShell.exec("$it status")) }
    }
}
