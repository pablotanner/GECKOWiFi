package com.thesis.geckowifi.network

import com.thesis.geckowifi.data.model.WiFiAuthMode

/**
 * The auth modes an access point advertises, from a scan result's capability
 * string (e.g. `[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][MFPC][ESS]`), compared with
 * a GeoCert's `auth_modes` before the network is judged (scenario A1).
 *
 * Each `[WPA-…]` / `[WPA2-…]` / `[RSN-…]` element lists its key management
 * suites joined by `+`. A transition-mode network yields several modes. Key
 * management this parser doesn't know (FILS, DPP, OSEN) is skipped rather
 * than reported, so it can't cause a false CONFLICT on its own.
 *
 * WPA3-Enterprise has no AKM of its own: it is EAP with PMF required, or the
 * SHA-256 EAP suite. Plain EAP without `[MFPR]` is WPA2-Enterprise.
 */
fun parseAuthModes(capabilities: String): Set<WiFiAuthMode> {
    val elements = Regex("""\[([^\]]*)]""").findAll(capabilities).map { it.groupValues[1] }.toList()
    val pmfRequired = "MFPR" in elements
    val modes = mutableSetOf<WiFiAuthMode>()

    for (element in elements) {
        if (element.startsWith("WEP")) modes += WiFiAuthMode.WEP
        val protocol = element.substringBefore('-')
        if (protocol != "WPA" && protocol != "WPA2" && protocol != "RSN") continue
        for (akm in element.substringAfter('-').split('+')) {
            when {
                "SUITE_B" in akm || "SUITE-B" in akm -> modes += WiFiAuthMode.WPA3_ENTERPRISE_192
                "EAP" in akm && "SHA256" in akm -> modes += WiFiAuthMode.WPA3_ENTERPRISE
                "EAP" in akm -> modes += if (pmfRequired) WiFiAuthMode.WPA3_ENTERPRISE else WiFiAuthMode.WPA2_ENTERPRISE
                "SAE" in akm -> modes += WiFiAuthMode.WPA3_PERSONAL
                "PSK" in akm -> modes += WiFiAuthMode.WPA2_PERSONAL
                "OWE" in akm -> modes += WiFiAuthMode.OWE
            }
        }
    }
    // Only an element-free or ESS/WPS-only string is open; an unparsed suite is not.
    val security = elements.any { it.startsWith("WEP") || it.startsWith("WPA") || it.startsWith("RSN") }
    if (!security) modes += WiFiAuthMode.OPEN
    return modes
}
