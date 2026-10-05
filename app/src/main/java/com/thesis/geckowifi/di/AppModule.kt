package com.thesis.geckowifi.di

import android.content.Context
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.thesis.geckowifi.data.local.HistoryStore
import com.thesis.geckowifi.data.local.InMemoryHistoryStore
import com.thesis.geckowifi.data.local.InMemoryTrustPreferenceStore
import com.thesis.geckowifi.data.local.PortalCheckLog
import com.thesis.geckowifi.data.local.TrustPreferenceStore
import com.thesis.geckowifi.enterprise.EnterpriseConfigurator
import com.thesis.geckowifi.location.LocationProvider
import com.thesis.geckowifi.network.NetworkObserver
import com.thesis.geckowifi.ui.VerificationViewModel
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
    val trustPreferences: TrustPreferenceStore by lazy { InMemoryTrustPreferenceStore() }
    val enterpriseConfigurator: EnterpriseConfigurator by lazy { EnterpriseConfigurator() }

    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }
    val networkObserver: NetworkObserver by lazy { NetworkObserver(appContext) }

    val geoQueryEncoder: GeoQueryEncoder by lazy { GeoQueryEncoder() }

    /** /sdcard/Android/data/com.thesis.geckowifi/files/portal-checks/ (adb-pullable, no permission needed). */
    val portalCheckLog: PortalCheckLog by lazy {
        PortalCheckLog(File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "portal-checks"))
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