package com.thesis.geckowifi.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Non-root [AssociationSource]: `WifiInfo` of the current connection (BSSID needs
 * the location permission the app already holds) and, on API 31+, its
 * `getCurrentSecurityType()`. The joined AP's MFPR bit comes from the last scan
 * ([modeFromWifiInfo]). Below API 31 the mode is unknown and only the BSSID is reported.
 */
class WifiInfoAssociation(context: Context) : AssociationSource {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // connectionInfo: the app holds no NetworkCallback for the suggestion's network.
    override suspend fun current(): Association? {
        val info = wifi.connectionInfo ?: return null
        val bssid = info.bssid?.lowercase()?.takeUnless { it == "02:00:00:00:00:00" } // redacted / not connected
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Association(bssid, null)
        val apRequiresPmf = runCatching { wifi.scanResults }.getOrDefault(emptyList())
            .firstOrNull { it.BSSID.equals(bssid, ignoreCase = true) }?.capabilities?.contains("[MFPR]") == true
        return Association(bssid, modeFromWifiInfo(info.currentSecurityType, apRequiresPmf))
    }
}
