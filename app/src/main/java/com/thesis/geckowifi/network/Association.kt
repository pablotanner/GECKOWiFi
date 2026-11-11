package com.thesis.geckowifi.network

import android.net.wifi.WifiInfo
import com.thesis.geckowifi.data.model.WiFiAuthMode

/**
 * The device's connection as negotiated after association: which access point
 * it actually joined and in which mode. The only authenticated security signal
 * (trust level 3, docs/design-decisions.md section 6). Either field is null when
 * the platform doesn't say.
 */
data class Association(val bssid: String?, val mode: WiFiAuthMode?)

/**
 * Reads the current [Association]. Non-root: [WifiInfoAssociation]; root:
 * [com.thesis.geckowifi.capability.SupplicantAssociation]. Chosen by
 * [com.thesis.geckowifi.capability.Capabilities.associationSource].
 */
interface AssociationSource {
    suspend fun current(): Association?
}

/** Whether [negotiated] is no weaker than a [registered] mode of its kind. Nothing registered (v2 certificates): nothing to compare. */
fun negotiatedFits(registered: Set<WiFiAuthMode>, negotiated: WiFiAuthMode): Boolean =
    registered.isEmpty() || registered.any { it.family == negotiated.family && negotiated >= it }

/**
 * `WifiInfo.getCurrentSecurityType()` (API 31+) as a mode. Android doesn't report
 * negotiated PMF, so plain `SECURITY_TYPE_EAP` counts as WPA3-Enterprise when the
 * joined access point requires PMF ([apRequiresPmf], its MFPR bit): it can't have
 * been negotiated without. That is the AKM 1 + MFPR case Android lists as EAP.
 */
fun modeFromWifiInfo(securityType: Int, apRequiresPmf: Boolean): WiFiAuthMode? = when (securityType) {
    WifiInfo.SECURITY_TYPE_OPEN -> WiFiAuthMode.OPEN
    WifiInfo.SECURITY_TYPE_WEP -> WiFiAuthMode.WEP
    WifiInfo.SECURITY_TYPE_PSK -> WiFiAuthMode.WPA2_PERSONAL
    WifiInfo.SECURITY_TYPE_SAE -> WiFiAuthMode.WPA3_PERSONAL
    WifiInfo.SECURITY_TYPE_OWE -> WiFiAuthMode.OWE
    WifiInfo.SECURITY_TYPE_EAP -> if (apRequiresPmf) WiFiAuthMode.WPA3_ENTERPRISE else WiFiAuthMode.WPA2_ENTERPRISE
    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> WiFiAuthMode.WPA3_ENTERPRISE
    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> WiFiAuthMode.WPA3_ENTERPRISE_192
    else -> null
}

/**
 * `wpa_cli status` output (`bssid=`, `key_mgmt=`, `pmf=`, `pairwise_cipher=`,
 * `wpa_state=`). wpa_supplicant prints `pmf=1|2` only while PMF is in use.
 * Null mode unless `wpa_state=COMPLETED`, or for a key management this app doesn't know.
 */
fun parseSupplicantStatus(status: String): Association {
    val fields = status.lineSequence().mapNotNull { line ->
        line.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }.toMap()
    val keyMgmt = fields["key_mgmt"].orEmpty()
    val pmf = fields["pmf"].let { it == "1" || it == "2" }
    val mode = when {
        fields["wpa_state"] != "COMPLETED" -> null
        "SUITE-B-192" in keyMgmt || "EAP-SHA384" in keyMgmt -> WiFiAuthMode.WPA3_ENTERPRISE_192
        "EAP" in keyMgmt || "802.1X" in keyMgmt -> if (pmf) WiFiAuthMode.WPA3_ENTERPRISE else WiFiAuthMode.WPA2_ENTERPRISE
        "SAE" in keyMgmt -> WiFiAuthMode.WPA3_PERSONAL
        "PSK" in keyMgmt -> WiFiAuthMode.WPA2_PERSONAL
        keyMgmt == "OWE" -> WiFiAuthMode.OWE
        keyMgmt == "NONE" -> if (fields["pairwise_cipher"].orEmpty().startsWith("WEP")) WiFiAuthMode.WEP else WiFiAuthMode.OPEN
        else -> null
    }
    return Association(fields["bssid"]?.lowercase(), mode)
}
