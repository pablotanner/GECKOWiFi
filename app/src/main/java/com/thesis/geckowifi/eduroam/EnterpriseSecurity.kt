package com.thesis.geckowifi.eduroam

import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build

/**
 * Attaches [config] to a Wi-Fi builder with the right 802.1X security type, shared by the onboarding
 * [WifiNetworkSpecifier] and the pinned [WifiNetworkSuggestion] so both apply one rule.
 *
 * [wpa3] comes from Android's own scan classification (see
 * [com.thesis.geckowifi.network.ScannedNetwork.Real.requiresWpa3EnterpriseSpecifier]) and is true only
 * for an access point Android reports as WPA3-Enterprise and not plain EAP - i.e. a WPA3-only AP, never
 * a WPA2/WPA3 transition AP, which Android matches only with the WPA2-Enterprise specifier. Because that
 * classification is available only on API 31+, [wpa3] is effectively never true below it; the version
 * check keeps the API-31 method guarded and falls back to WPA2-Enterprise rather than the deprecated
 * `setWpa3EnterpriseConfig`.
 */
fun WifiNetworkSpecifier.Builder.setEnterpriseConfig(
    wpa3: Boolean,
    config: WifiEnterpriseConfig
): WifiNetworkSpecifier.Builder =
    if (wpa3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setWpa3EnterpriseStandardModeConfig(config)
    else setWpa2EnterpriseConfig(config)

fun WifiNetworkSuggestion.Builder.setEnterpriseConfig(
    wpa3: Boolean,
    config: WifiEnterpriseConfig
): WifiNetworkSuggestion.Builder =
    if (wpa3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setWpa3EnterpriseStandardModeConfig(config)
    else setWpa2EnterpriseConfig(config)
