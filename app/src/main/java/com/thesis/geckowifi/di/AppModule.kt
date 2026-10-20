package com.thesis.geckowifi.di

import android.content.Context
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thesis.geckowifi.data.local.HistoryStore
import com.thesis.geckowifi.data.local.InMemoryHistoryStore
import com.thesis.geckowifi.data.local.SharedPrefsTrustPreferenceStore
import com.thesis.geckowifi.data.local.PortalCheckLog
import com.thesis.geckowifi.data.local.TrustPreferenceStore
import com.thesis.geckowifi.data.remote.GeckoClient
import com.thesis.geckowifi.eduroam.CredentialStore
import com.thesis.geckowifi.eduroam.EduroamOnboarding
import com.thesis.geckowifi.eduroam.EnterpriseProfileInstaller
import com.thesis.geckowifi.eduroam.KeystoreCredentialStore
import com.thesis.geckowifi.eduroam.RealmRecordClient
import com.thesis.geckowifi.eduroam.SpecifierOnboardingSession
import com.thesis.geckowifi.eduroam.SuggestionProfileInstaller
import com.thesis.geckowifi.location.LocationProvider
import com.thesis.geckowifi.network.NetworkObserver
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.eduroam.EduroamViewModel
import com.thesis.geckowifi.verification.VerificationEngine
import com.thesis.geckowifi.verification.CertProbe
import com.thesis.geckowifi.verification.DecisionCache
import com.thesis.geckowifi.verification.GeoQueryEncoder
import java.io.File

object AppModule {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val certProbe: CertProbe by lazy { CertProbe() }
    val decisionCache: DecisionCache by lazy { DecisionCache() }
    val historyStore: HistoryStore by lazy { InMemoryHistoryStore() }
    val trustPreferences: TrustPreferenceStore by lazy {
        SharedPrefsTrustPreferenceStore(appContext.getSharedPreferences("gecko_prefs", Context.MODE_PRIVATE))
    }

    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }
    val networkObserver: NetworkObserver by lazy { NetworkObserver(appContext) }

    val geoQueryEncoder: GeoQueryEncoder by lazy { GeoQueryEncoder() }

    /** /sdcard/Android/data/com.thesis.geckowifi/files/portal-checks/ (adb-pullable, no permission needed). */
    val portalCheckLog: PortalCheckLog by lazy {
        PortalCheckLog(File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "portal-checks"))
    }

    val credentialStore: CredentialStore by lazy {
        KeystoreCredentialStore(appContext.getSharedPreferences("gecko_eduroam", Context.MODE_PRIVATE))
    }

    /** Non-root for now (docs/eduroam-decisions.md, D1); a root installer would be chosen here. */
    val profileInstaller: EnterpriseProfileInstaller by lazy { SuggestionProfileInstaller(appContext) }

    /**
     * Everything during onboarding is bound to the onboarding network with no
     * fallback to the default route: a fresh engine and cache per session, so
     * no earlier answer can stand in for this network's.
     */
    val eduroamOnboarding: EduroamOnboarding by lazy {
        EduroamOnboarding(
            transport = SpecifierOnboardingSession(appContext),
            engineFor = { network ->
                VerificationEngine(GeckoClient(network = network, fallbackClient = null), CertProbe(), DecisionCache(), geoQueryEncoder)
            },
            realmClientFor = { network -> RealmRecordClient(network = network) },
            installer = profileInstaller
        )
    }

    val eduroamViewModelFactory = viewModelFactory {
        initializer {
            EduroamViewModel(networkObserver, locationProvider, credentialStore, eduroamOnboarding, profileInstaller, trustPreferences)
        }
    }

    val verificationViewModelFactory = viewModelFactory {
        initializer {
            VerificationViewModel(
                networkObserver = networkObserver,
                locationProvider = locationProvider,
                certProbe = certProbe,
                decisionCache = decisionCache,
                geoQueryEncoder = geoQueryEncoder,
                historyStore = historyStore,
                trustPreferences = trustPreferences,
                checkLog = portalCheckLog
            )
        }
    }
}