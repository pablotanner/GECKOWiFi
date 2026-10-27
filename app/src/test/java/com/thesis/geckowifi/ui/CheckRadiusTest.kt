package com.thesis.geckowifi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckRadiusTest {

    @Test
    fun preciseFix_usesTheSetting() {
        assertEquals(50, checkRadius(settingMeters = 50, accuracyMeters = 5f))
        assertEquals(50, checkRadius(settingMeters = 50, accuracyMeters = 31f)) // 1.6 x 31 = 49.6 -> 50
    }

    @Test
    fun impreciseFix_widensTo1_6TimesTheAccuracy() {
        // Android's accuracy is a 68 % radius; the check must reach ~95 %.
        assertEquals(64, checkRadius(settingMeters = 50, accuracyMeters = 40f))
        assertEquals(97, checkRadius(settingMeters = 25, accuracyMeters = 60.1f))
    }

    @Test
    fun neverWiderThanTheCap() {
        assertEquals(MAX_CHECK_RADIUS_METERS, checkRadius(settingMeters = 50, accuracyMeters = 62.5f))
        assertEquals(MAX_CHECK_RADIUS_METERS, checkRadius(settingMeters = 50, accuracyMeters = 200f))
        assertEquals(MAX_CHECK_RADIUS_METERS, checkRadius(settingMeters = 250, accuracyMeters = 5f))
        // no accuracy reported (LocationProvider uses Float.MAX_VALUE)
        assertEquals(MAX_CHECK_RADIUS_METERS, checkRadius(settingMeters = 50, accuracyMeters = Float.MAX_VALUE))
    }

    @Test
    fun theWorstAcceptedAccuracy_stillFitsUnderTheCap() {
        // checks refuse fixes worse than MAX_CHECK_ACCURACY_METERS, so the cap
        // never cuts a radius the accuracy actually needs
        val worst = MAX_CHECK_ACCURACY_METERS.toFloat()
        assertTrue(Math.ceil(worst * ACCURACY_TO_RADIUS) <= MAX_CHECK_RADIUS_METERS)
        assertEquals(62.5, MAX_CHECK_ACCURACY_METERS, 1e-9)
        assertTrue(MAX_CHECK_RADIUS_METERS <= MAX_QUERY_RADIUS_METERS)
    }
}
