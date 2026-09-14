package com.thesis.geckowifi.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float,
    val isFromMockProvider: Boolean,
    val provider: String?,
    val timestamp: Long
)

class LocationProvider(context: Context) {

    private val manager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun lastKnown(): LocationFix? {
        val candidates = listOfNotNull(
            runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        )
        return candidates.maxByOrNull { it.time }?.toFix()
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun awaitFix(timeoutMs: Long = 10_000): LocationFix? =
        suspendCancellableCoroutine { continuation ->
            val listener = android.location.LocationListener { location ->
                if (continuation.isActive) continuation.resume(location.toFix())
            }
            runCatching {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 0L, 0f, listener
                )
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        }

    /**
     * Both providers at once, so SpoofSignals can compare them.
     * A GPS fix that disagrees with the network fix is a spoofing indicator.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun independentFixes(): Pair<LocationFix?, LocationFix?> = Pair(
        runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()?.toFix(),
        runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
            .getOrNull()?.toFix()
    )

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun registerGnssCallback(onStatus: (List<Float>) -> Unit): GnssStatus.Callback {
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val cn0 = (0 until status.satelliteCount).map { status.getCn0DbHz(it) }
                onStatus(cn0)
            }
        }
        runCatching { manager.registerGnssStatusCallback(callback, null) }
        return callback
    }

    fun unregisterGnssCallback(callback: GnssStatus.Callback) {
        runCatching { manager.unregisterGnssStatusCallback(callback) }
    }

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        altitude = if (hasAltitude()) altitude else null,
        accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        isFromMockProvider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock
        else @Suppress("DEPRECATION") isFromMockProvider,
        provider = provider,
        timestamp = time
    )
}