package com.thesis.geckowifi.ui.eduroam

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thesis.geckowifi.data.local.TrustPreferenceStore
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.eduroam.CredentialStore
import com.thesis.geckowifi.eduroam.EduroamOnboarding
import com.thesis.geckowifi.eduroam.EduroamOutcome
import com.thesis.geckowifi.eduroam.EduroamStep
import com.thesis.geckowifi.eduroam.EduroamTarget
import com.thesis.geckowifi.eduroam.EnterpriseProfileInstaller
import com.thesis.geckowifi.eduroam.QueryArea
import com.thesis.geckowifi.location.LocationFix
import com.thesis.geckowifi.location.LocationProvider
import com.thesis.geckowifi.network.NetworkObserver
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.ui.MAX_QUERY_RADIUS_METERS
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil

private const val TAG = "EduroamOnboarding"

/** Drives the eduroam setup screen: one account, one target network, one run at a time. */
class EduroamViewModel(
    private val networkObserver: NetworkObserver,
    private val locationProvider: LocationProvider,
    private val credentials: CredentialStore,
    private val onboarding: EduroamOnboarding,
    private val installer: EnterpriseProfileInstaller,
    private val trustPreferences: TrustPreferenceStore
) : ViewModel() {

    var target by mutableStateOf<EduroamTarget?>(null)
        private set

    /** Form state. The password field is never prefilled; a saved one is used when it is left empty. */
    var identity by mutableStateOf(credentials.storedIdentity() ?: "alice@homeuni.test")
    var password by mutableStateOf("")
    var hasSavedPassword by mutableStateOf(credentials.load() != null)
        private set

    var step by mutableStateOf<EduroamStep?>(null)
        private set
    var outcome by mutableStateOf<EduroamOutcome?>(null)
        private set

    /** A finished run not yet handed to History / the status card; see [takeFinishedRun]. */
    private var finished: Triple<EduroamTarget, EduroamOutcome, LocationFix>? by mutableStateOf(null)
    val hasFinishedRun: Boolean get() = finished != null

    /** Hands the last finished run over once (to [com.thesis.geckowifi.ui.VerificationViewModel.recordEduroamOutcome]). */
    fun takeFinishedRun(): Triple<EduroamTarget, EduroamOutcome, LocationFix>? = finished.also { finished = null }
    /** A failure before the flow could start (no location, no account). */
    var error by mutableStateOf<String?>(null)
        private set
    val running: Boolean get() = job != null

    private var job: Job? by mutableStateOf(null)

    fun setTarget(network: ScannedNetwork.Real) {
        val next = EduroamTarget(network.ssid, network.bssid, network.authModes)
        if (next != target) {
            target = next
            inScan = true
            outcome = null
            error = null
        }
    }

    /**
     * Lab entry (Settings, lab mode): a target that isn't in the current scan,
     * e.g. to try the Android side before the access point is set up.
     * Assumes WPA2-Enterprise, which the site check then compares.
     */
    fun setUnscannedTarget(ssid: String, bssid: String?) {
        val next = EduroamTarget(ssid, bssid, setOf(WiFiAuthMode.WPA2_ENTERPRISE))
        if (next != target) {
            target = next
            inScan = false
            outcome = null
            error = null
        }
    }

    /** False for [setUnscannedTarget]: the security type is assumed, not seen. */
    var inScan by mutableStateOf(true)
        private set

    fun start() {
        val target = target ?: return
        if (job != null) return
        if (password.isNotEmpty()) {
            credentials.save(identity, password)
            password = ""
        } else if (credentials.storedIdentity() != identity.trim()) {
            error = "Enter the password for ${identity.trim()}."
            return
        }
        val account = credentials.load() ?: run {
            error = "The saved password can't be read any more. Enter it again."
            hasSavedPassword = false
            return
        }
        hasSavedPassword = true
        outcome = null
        error = null
        job = viewModelScope.launch {
            try {
                val fix = locationProvider.lastKnown() ?: locationProvider.awaitFix()
                if (fix == null) {
                    error = "No location fix yet. GECKO looks up registrations by location."
                    return@launch
                }
                if (fix.accuracyMeters > MAX_QUERY_RADIUS_METERS) {
                    error = "Location is accurate to ${fix.accuracyMeters.toInt()} m only; GECKO needs better than $MAX_QUERY_RADIUS_METERS m."
                    return@launch
                }
                val radius = maxOf(trustPreferences.queryRadiusMeters, ceil(fix.accuracyMeters.toDouble()).toInt())
                    .coerceAtMost(MAX_QUERY_RADIUS_METERS)
                // A portal check may still hold a specifier network; Android serves one request at a time.
                networkObserver.leave()
                val result = onboarding.run(target, QueryArea(fix.latitude, fix.longitude, radius), account) { step = it }
                Log.i(TAG, "outcome for ${target.ssid}: $result")
                outcome = result
                finished = Triple(target, result, fix)
            } finally {
                step = null
                job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun forgetAccount() {
        credentials.clear()
        hasSavedPassword = false
        password = ""
    }

    fun removeProfile() {
        val ssid = target?.ssid ?: return
        viewModelScope.launch {
            installer.remove(ssid)
            outcome = null
        }
    }
}
