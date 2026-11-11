package com.thesis.geckowifi.eduroam

import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.network.Akm
import com.thesis.geckowifi.network.Cipher
import com.thesis.geckowifi.network.RsnProfile
import com.thesis.geckowifi.network.admissibleModes
import com.thesis.geckowifi.network.ceiling

/** Which 802.1X builder method a specifier or suggestion uses. */
enum class EapSecurity { WPA2, WPA3 }

sealed interface SecurityChoice {
    /** [specifier]: for the BSSID-bound connection to this access point. [suggestions]: one pinned suggestion each. */
    data class Use(val specifier: EapSecurity, val suggestions: Set<EapSecurity>) : SecurityChoice

    /** No mode this flow can join; nothing is attempted. */
    data class Refused(val reason: String) : SecurityChoice
}

/** What the PEAP/TTLS profile can join. 192-bit needs EAP-TLS with a Suite-B client certificate. */
private val JOINABLE = setOf(WiFiAuthMode.WPA2_ENTERPRISE, WiFiAuthMode.WPA3_ENTERPRISE)

/**
 * The 802.1X security type to request, from what is [registered] (the GeoCert's
 * `auth_modes`; null while unknown, e.g. for the onboarding session, which runs
 * before the site query and sends nothing secret) intersected with what this
 * access point advertises ([profile]); if they share nothing, the access point's
 * own modes when they are stronger than registered.
 *
 * - Specifier (bound to this BSSID): the strongest mode in that intersection.
 * - Suggestions (SSID only, kept, so they also match the SSID's access points
 *   elsewhere): one per registered enterprise type, plus the specifier's type so
 *   this access point is always joinable. Android keys suggestions by SSID, BSSID
 *   and default security type (`WPA_EAP` vs `WPA3_EAP`), so a WPA2 and a WPA3
 *   suggestion for one SSID coexist (AOSP `WifiNetworkSuggestion.equals`,
 *   packages/modules/Wifi android12-release and main). On API 31+ Android also
 *   adds WPA3-Enterprise as an auto-upgrade to every WPA2 suggestion
 *   (`WifiConfigurationUtil.addUpgradableSecurityTypeIfNecessary`); the extra
 *   WPA3 suggestion is what makes WPA3-only access points joinable on API 29-30.
 */
fun chooseEnterpriseSecurity(registered: Set<WiFiAuthMode>?, profile: RsnProfile, sdkInt: Int): SecurityChoice {
    val observed = profile.admissibleModes().filter { it.isEnterprise }.toSet()
    // An access point stronger than registered passes the verdict (WiFiIdentity.permits), so it may be joined in its own mode.
    val acceptable = if (registered == null) observed
        else (observed intersect registered).ifEmpty { observed.filter { o -> registered.any { it.isEnterprise && o > it } }.toSet() }
    val best = acceptable.ceiling(JOINABLE) ?: return SecurityChoice.Refused(when {
        WiFiAuthMode.WPA3_ENTERPRISE_192 in acceptable ->
            "this access point only offers WPA3-Enterprise 192-bit, which needs EAP-TLS with a Suite-B certificate; GECKO's PEAP/TTLS profile can't join it"
        observed.isEmpty() -> "this access point doesn't offer WPA2- or WPA3-Enterprise"
        else -> "this access point offers ${observed.sorted().joinToString { it.wire }}, but ${registered.orEmpty().sorted().joinToString { it.wire }} is registered"
    })
    val wpa3 = best == WiFiAuthMode.WPA3_ENTERPRISE &&
        if (sdkInt >= Build.VERSION_CODES.S) profile.androidListsWpa3Enterprise else profile.mfpr
    val specifier = if (wpa3) EapSecurity.WPA3 else EapSecurity.WPA2
    val suggestions = registered.orEmpty().mapNotNull {
        when (it) {
            WiFiAuthMode.WPA2_ENTERPRISE -> EapSecurity.WPA2
            WiFiAuthMode.WPA3_ENTERPRISE -> EapSecurity.WPA3
            else -> null
        }
    }.toSet() + specifier
    return SecurityChoice.Use(specifier, suggestions)
}

/**
 * Whether Android (API 31+) lists `SECURITY_TYPE_EAP_WPA3_ENTERPRISE` for this
 * access point, which a WPA3-Enterprise specifier needs to match it (specifiers
 * get no auto-upgrade: `WifiNetworkFactory.doesScanResultMatchWifiNetworkSpecifier`
 * → `ScanResultMatchInfo.networkTypeEquals`). Mirrors AOSP
 * `ScanResultUtil.isScanResultForWpa3Enterprise{Transition,Only}Network`: AKM 5
 * with MFPC, no TKIP, and either AKM 1 without MFPR (transition) or MFPR without
 * AKM 1 (WPA3 only). An AKM 1 + MFPR access point is WPA3-Enterprise by the WFA
 * definition, but Android lists it as plain EAP: it gets the WPA2 builder, and
 * PMF is still negotiated because the AP requires it.
 */
private val RsnProfile.androidListsWpa3Enterprise: Boolean
    get() {
        val sha1 = Akm.EAP_SHA1 in akms
        val sha256 = Akm.EAP_SHA256 in akms
        return sha256 && mfpc && Cipher.TKIP !in pairwise && !wep && (if (mfpr) !sha1 else sha1)
    }

/** Attaches [config] with [security]; API 29-30 have only the deprecated WPA3 method (PMF required). */
fun WifiNetworkSpecifier.Builder.setEnterpriseConfig(security: EapSecurity, config: WifiEnterpriseConfig): WifiNetworkSpecifier.Builder =
    when {
        security == EapSecurity.WPA2 -> setWpa2EnterpriseConfig(config)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> setWpa3EnterpriseStandardModeConfig(config)
        else -> @Suppress("DEPRECATION") setWpa3EnterpriseConfig(config)
    }

fun WifiNetworkSuggestion.Builder.setEnterpriseConfig(security: EapSecurity, config: WifiEnterpriseConfig): WifiNetworkSuggestion.Builder =
    when {
        security == EapSecurity.WPA2 -> setWpa2EnterpriseConfig(config)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> setWpa3EnterpriseStandardModeConfig(config)
        else -> @Suppress("DEPRECATION") setWpa3EnterpriseConfig(config)
    }
