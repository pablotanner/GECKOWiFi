package com.thesis.geckowifi.location

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class LocationConfidence { HIGH, MODERATE, LOW }

data class SpoofAssessment(
    val confidence: LocationConfidence,
    val findings: List<String>
) {
    val shouldPromptUser: Boolean get() = confidence == LocationConfidence.LOW
}

data class SpoofInputs(
    val gpsFix: LocationFix?,
    val networkFix: LocationFix?,
    val gnssCn0Values: List<Float> = emptyList(),
    val previousVerifiedLat: Double? = null,
    val previousVerifiedLng: Double? = null,
    val previousVerifiedAtMs: Long? = null,
    val nowMs: Long = System.currentTimeMillis()
)

object SpoofSignals {

    const val PROVIDER_DISAGREEMENT_METERS = 2_000.0
    const val MAX_PLAUSIBLE_SPEED_KMH = 900.0
    const val CN0_UNIFORMITY_THRESHOLD = 1.5

    fun assess(inputs: SpoofInputs): SpoofAssessment {
        val findings = mutableListOf<String>()
        var penalty = 0

        val fix = inputs.gpsFix ?: inputs.networkFix
        if (fix == null) {
            return SpoofAssessment(LocationConfidence.LOW, listOf("no location fix available"))
        }

        if (fix.isFromMockProvider) {
            findings += "location reported by a mock provider"
            penalty += 3
        }

        val gps = inputs.gpsFix
        val network = inputs.networkFix
        if (gps != null && network != null) {
            val separation = haversineMeters(
                gps.latitude, gps.longitude, network.latitude, network.longitude
            )
            if (separation > PROVIDER_DISAGREEMENT_METERS) {
                findings += "GPS and network positions disagree by ${separation.toInt()} m"
                penalty += 2
            }
        }

        if (inputs.gnssCn0Values.size >= 4) {
            val spread = standardDeviation(inputs.gnssCn0Values)
            if (spread < CN0_UNIFORMITY_THRESHOLD) {
                findings += "satellite signal strengths implausibly uniform"
                penalty += 2
            }
        }

        if (impossibleTravel(
                inputs.previousVerifiedLat, inputs.previousVerifiedLng,
                inputs.previousVerifiedAtMs, fix.latitude, fix.longitude, inputs.nowMs
            )
        ) {
            findings += "position inconsistent with recent verified location"
            penalty += 2
        }

        val confidence = when {
            penalty >= 3 -> LocationConfidence.LOW
            penalty >= 1 -> LocationConfidence.MODERATE
            else -> LocationConfidence.HIGH
        }
        return SpoofAssessment(confidence, findings)
    }

    fun impossibleTravel(
        previousLat: Double?, previousLng: Double?, previousAtMs: Long?,
        currentLat: Double, currentLng: Double, nowMs: Long,
        maxSpeedKmh: Double = MAX_PLAUSIBLE_SPEED_KMH
    ): Boolean {
        if (previousLat == null || previousLng == null || previousAtMs == null) return false
        val hours = (nowMs - previousAtMs) / 3_600_000.0
        if (hours <= 0.0) return false
        val km = haversineMeters(previousLat, previousLng, currentLat, currentLng) / 1000.0
        return km / hours > maxSpeedKmh
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun standardDeviation(values: List<Float>): Double {
        if (values.size < 2) return Double.MAX_VALUE
        val mean = values.map { it.toDouble() }.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }
}