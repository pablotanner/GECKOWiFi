package com.thesis.geckowifi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.thesis.geckowifi.di.AppModule
import com.thesis.geckowifi.ui.theme.GeckoWifiTheme

class MainActivity : ComponentActivity() {

    private var hasLocationPermission by mutableStateOf(false)

    /**
     * Fine location gates scan results on every version; Android 13+ also
     * needs NEARBY_WIFI_DEVICES for scanning/joining, or scans silently come
     * back empty. Only location decides [hasLocationPermission] - the app
     * still works (with cached/no scans) if nearby-devices is denied.
     */
    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppModule.init(applicationContext)
        enableEdgeToEdge()

        hasLocationPermission = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!requiredPermissions.all(::isGranted)) {
            requestPermissions.launch(requiredPermissions)
        }

        setContent {
            GeckoWifiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GeckoWifiApp(
                        hasLocationPermission = hasLocationPermission,
                        onRequestLocationPermission = {
                            requestPermissions.launch(requiredPermissions)
                        }
                    )
                }
            }
        }
    }
}
