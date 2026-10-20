package com.thesis.geckowifi.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
    suspend fun awaitFix(timeoutMs: Long = 10_000): LocationFix? {
        val listener = arrayOfNulls<android.location.LocationListener>(1)
        return try {
            // Indoors GPS may never get a fix: without the timeout, callers (the
            // network list's refresh, every check) waited forever with no location.
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    listener[0] = android.location.LocationListener { location ->
                        if (continuation.isActive) continuation.resume(location.toFix())
                    }
                    // Both providers: the network one usually answers indoors, GPS outdoors.
                    val requested = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).count { provider ->
                        runCatching { manager.requestLocationUpdates(provider, 0L, 0f, listener[0]!!) }.isSuccess
                    }
                    if (requested == 0 && continuation.isActive) continuation.resume(null)
                }
            }
        } finally {
            // Also after a fix, not only on cancellation, or GPS keeps running.
            listener[0]?.let { manager.removeUpdates(it) }
        }
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