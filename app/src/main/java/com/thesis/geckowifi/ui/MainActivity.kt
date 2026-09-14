package com.thesis.geckowifi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.thesis.geckowifi.R
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.di.AppModule
import com.thesis.geckowifi.network.FakeDemoNetworks
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.verification.VerificationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prototype UI matching the current target design (docs/app-design.md):
 * SSID-gated, silent by default, only `CONFLICT` interrupts the user.
 * Tapping a fake network runs the whole pipeline immediately using its
 * baked-in "as if already observed" identity; a real network still needs a
 * manually-typed observed domain (no automatic captive-portal detection
 * exists yet - see docs/app-design.md's root-based capture section).
 *
 * Queries prefer the cellular network (`NetworkObserver.cellularNetwork()`)
 * when available, so the WiFi network being evaluated can't block or
 * interfere with its own verification - see docs/app-design.md's "GECKO
 * connectivity channel" section for why this matters.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var networkListContainer: LinearLayout
    private lateinit var selectedNetworkText: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var latInput: EditText
    private lateinit var lngInput: EditText
    private lateinit var domainInput: EditText
    private lateinit var lastResultText: TextView

    /** Set when a [ScannedNetwork.Real] row is tapped, so the manual check below is keyed/scoped to it. */
    private var selectedRealNetwork: ScannedNetwork.Real? = null

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                statusText.text = describeNetwork()
                refreshNetworkList()
            } else {
                statusText.text = "Location permission denied - network name and device location are unavailable"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppModule.init(applicationContext)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusText = findViewById(R.id.statusText)
        networkListContainer = findViewById(R.id.networkListContainer)
        selectedNetworkText = findViewById(R.id.selectedNetworkText)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        latInput = findViewById(R.id.latInput)
        lngInput = findViewById(R.id.lngInput)
        domainInput = findViewById(R.id.domainInput)
        lastResultText = findViewById(R.id.resultText)

        findViewById<Button>(R.id.useLocationButton).setOnClickListener { fillFromDeviceLocation() }
        findViewById<Button>(R.id.verifyButton).setOnClickListener { runManualVerification() }
        findViewById<Button>(R.id.refreshNetworksButton).setOnClickListener { refreshNetworkList() }

        ensureLocationPermission()
        refreshNetworkList()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) statusText.text = describeNetwork()
    }

    private fun refreshNetworkList() {
        networkListContainer.removeAllViews()

        val real: List<ScannedNetwork> = if (hasLocationPermission()) {
            AppModule.networkObserver.scanResults()
        } else {
            emptyList()
        }
        val networks = real + FakeDemoNetworks.all

        if (networks.isEmpty()) {
            addNetworkRow("(no networks found - grant location permission or check WiFi is on)", null)
            return
        }

        for (network in networks) {
            val subtitle = when (network) {
                is ScannedNetwork.Real -> network.capabilities
                is ScannedNetwork.FakeCaptivePortal -> "fake captive portal, ${network.label} - claims ${network.presumedDomain}"
                is ScannedNetwork.FakeEduroam -> "fake eduroam, ${network.label} - claims ${network.presumedAuthServerName}"
            }
            addNetworkRow("${network.ssid}  (${network.bssid ?: "no bssid"})", subtitle) {
                onNetworkTapped(network)
            }
        }
    }

    private fun addNetworkRow(title: String, subtitle: String?, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 12, 8, 12)
            gravity = Gravity.START
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        row.addView(TextView(this).apply { text = title })
        if (!subtitle.isNullOrBlank()) {
            row.addView(TextView(this).apply { text = subtitle; textSize = 12f })
        }
        networkListContainer.addView(row)
    }

    private fun onNetworkTapped(network: ScannedNetwork) {
        when (network) {
            is ScannedNetwork.Real -> {
                selectedRealNetwork = network
                selectedNetworkText.text = "Selected: ${network.ssid} - enter the observed domain below and tap Verify"
            }
            is ScannedNetwork.FakeCaptivePortal -> runFakeCaptivePortalCheck(network)
            is ScannedNetwork.FakeEduroam -> runFakeEduroamCheck(network)
        }
    }

    private fun currentLatLngOrNull(): Pair<Double, Double>? {
        val lat = latInput.text.toString().toDoubleOrNull() ?: return null
        val lng = lngInput.text.toString().toDoubleOrNull() ?: return null
        return lat to lng
    }

    /**
     * Prefers the cellular network so the WiFi network under evaluation
     * can't interfere with its own verification query - see the class doc
     * and docs/app-design.md. Falls back to the default route if no
     * cellular network is currently available (e.g. no SIM, or an emulator
     * without a simulated cellular radio).
     */
    private fun engineForCurrentServerUrl(): VerificationEngine {
        val cellular = if (hasLocationPermission()) AppModule.networkObserver.cellularNetwork() else null
        return VerificationEngine(
            gecko = GeckoClient(baseUrl = serverUrlInput.text.toString().trim(), network = cellular),
            probe = AppModule.certProbe,
            cache = AppModule.decisionCache,
            encoder = AppModule.geoQueryEncoder
        )
    }

    private fun runFakeCaptivePortalCheck(fake: ScannedNetwork.FakeCaptivePortal) {
        val (lat, lng) = currentLatLngOrNull() ?: run {
            lastResultText.text = "Enter a valid latitude and longitude first."
            return
        }
        lastResultText.text = "Checking ${fake.ssid} (${fake.label})..."
        val engine = engineForCurrentServerUrl()
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    engine.verifyPresentedDomain(
                        networkKey = "fake:${fake.ssid}:${fake.label}",
                        host = fake.presumedDomain,
                        presentedSpkiHash = fake.presumedSpkiHashBase64,
                        lat = lat,
                        lng = lng,
                        altitude = null,
                        radiusMeters = 50,
                        ssid = fake.ssid
                    )
                }
            } catch (e: Exception) {
                lastResultText.text = "Verification threw: ${e::class.simpleName}: ${e.message}"
                return@launch
            }
            handleResult(result)
        }
    }

    private fun runFakeEduroamCheck(fake: ScannedNetwork.FakeEduroam) {
        val (lat, lng) = currentLatLngOrNull() ?: run {
            lastResultText.text = "Enter a valid latitude and longitude first."
            return
        }
        lastResultText.text = "Checking ${fake.ssid} (${fake.label})..."
        val engine = engineForCurrentServerUrl()
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    engine.verifyEnterprise(
                        networkKey = "fake:${fake.ssid}:${fake.label}",
                        observedAuthServerName = fake.presumedAuthServerName,
                        observedCaFingerprint = fake.presumedCaFingerprintBase64,
                        lat = lat,
                        lng = lng,
                        altitude = null,
                        radiusMeters = 50,
                        ssid = fake.ssid
                    )
                }
            } catch (e: Exception) {
                lastResultText.text = "Verification threw: ${e::class.simpleName}: ${e.message}"
                return@launch
            }
            handleResult(result)
        }
    }

    /** The manual path: a selected real network + a typed "observed domain" - stands in for automatic detection. */
    private fun runManualVerification() {
        val lat = latInput.text.toString().toDoubleOrNull()
        val lng = lngInput.text.toString().toDoubleOrNull()
        val host = domainInput.text.toString().trim()

        if (lat == null || lng == null) {
            lastResultText.text = "Enter a valid latitude and longitude."
            return
        }
        if (host.isEmpty()) {
            lastResultText.text = "Enter the domain this network's captive portal presented."
            return
        }
        if (!hasLocationPermission()) {
            lastResultText.text = "Location permission is required."
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        val selected = selectedRealNetwork
        val networkKey = selected?.let { it.bssid ?: it.ssid } ?: AppModule.networkObserver.currentIdentity().key
        val ssid = selected?.ssid ?: AppModule.networkObserver.currentIdentity().ssid

        lastResultText.text = "Verifying $host at ($lat, $lng)..."
        val engine = engineForCurrentServerUrl()

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    engine.verify(
                        networkKey = networkKey,
                        host = host,
                        lat = lat,
                        lng = lng,
                        altitude = null,
                        radiusMeters = 50,
                        ssid = ssid
                    )
                }
            } catch (e: Exception) {
                lastResultText.text = "Verification threw: ${e::class.simpleName}: ${e.message}"
                return@launch
            }
            handleResult(result)
        }
    }

    /**
     * The "only warn on CONFLICT" policy from docs/app-design.md: a genuine
     * evil-twin signature interrupts the user (a dialog, standing in for
     * whatever the real notification/UI ends up being); everything else -
     * including an unregistered SSID, which is the expected common case -
     * is silent, just logged in the small status line for this prototype's
     * own debugging/demo purposes.
     */
    private fun handleResult(result: VerificationResult) {
        lastResultText.text = format(result)
        if (result.state == VerificationState.CONFLICT) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Possible evil twin")
                .setMessage(
                    "This network's SSID matches one registered with GECKO at this location, " +
                        "but it presented a different identity than what's registered.\n\n" +
                        (result.reason ?: "")
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            statusText.text = describeNetwork()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun describeNetwork(): String {
        val identity = AppModule.networkObserver.currentIdentity()
        val label = identity.ssid ?: identity.bssid ?: if (identity.isCellular) "cellular" else "unknown"
        return "Network: $label"
    }

    private fun fillFromDeviceLocation() {
        if (!hasLocationPermission()) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val fix = AppModule.locationProvider.lastKnown()
        if (fix == null) {
            lastResultText.text = "No cached device location available " +
                "(on an emulator, set one via Extended Controls > Location)."
            return
        }
        latInput.setText(fix.latitude.toString())
        lngInput.setText(fix.longitude.toString())
        lastResultText.text = "Filled from device location " +
            "(±${fix.accuracyMeters}m, provider=${fix.provider ?: "unknown"})"
    }

    private fun format(result: VerificationResult): String = buildString {
        appendLine("State: ${result.state}")
        appendLine("Host: ${result.host}")
        result.matchedIdentifier?.let { appendLine("Matched identifier: $it") }
        result.matchedCertificateId?.let { appendLine("Certificate: $it") }
        result.reason?.let { appendLine("Reason: $it") }
        append("At: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(result.timestamp))}")
    }
}
