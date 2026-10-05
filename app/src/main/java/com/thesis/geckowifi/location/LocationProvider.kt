package com.thesis.geckowifi.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float,
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

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        altitude = if (hasAltitude()) altitude else null,
        accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        provider = provider,
        timestamp = time
    )
}