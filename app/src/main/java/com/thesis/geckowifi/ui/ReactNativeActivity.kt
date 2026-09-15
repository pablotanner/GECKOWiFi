package com.thesis.geckowifi.ui

import com.facebook.react.ReactActivity

/** Temporary RN host used while the native prototype remains the launcher. */
class ReactNativeActivity : ReactActivity() {
    override fun getMainComponentName(): String = "GECKOWiFi"
}